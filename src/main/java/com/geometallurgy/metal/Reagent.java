package com.geometallurgy.metal;

import java.util.Map;

import com.geometallurgy.chemical.ReductantGas;
import com.geometallurgy.item.MolCarrier;
import com.geometallurgy.item.ModItems;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 辅助剂表：把「玩家往槽里放的那个东西」翻译成炉子看得懂的东西。
 *
 * <p>三台炉子各要一种辅助剂，这里统一管：
 * <ul>
 *   <li><b>还原剂</b>（还原炉）—— <b>碳系是基础还原剂，永远可用</b>（煤炭 / 木炭 / 碳粉 /
 *       碳棒，见 {@link #isCarbonFamily}）；金属只是<b>可选</b>的替代（铝热之类），
 *       它<b>不是</b>还原炉的刚需 —— 高炉 / 反射炉那一路本来就是碳既还原又供热。</li>
 *   <li><b>置换剂</b>（置换炉）—— <b>只有它</b>要求金属，且必须比目标金属活泼
 *       （{@link MetalSeries#canDisplace}）。</li>
 *   <li><b>电极</b>（电解炉）—— 碳棒烧得飞快，钨棒/钼棒能撑很久。电极按耐久消耗，
 *       每跑完一个炉次扣一点。</li>
 * </ul>
 *
 * <h2>为什么用「当量」而不是「个数」</h2>
 * 一个炉次要吃的还原剂未必是整数个：料脏的时候脉石也要陪着烧掉一份，需求会冒到 1.2、1.5。
 * 所以炉子里有一个能存小数的<b>试剂池</b>，缺多少就从槽里拿一个整件补进去 —— 这样
 * 「同样的试剂、更少的产量」这种浪费才真实地体现出来，而不是被取整抹平。
 */
public final class Reagent {

	private Reagent() {
	}

	/** 碳系试剂：每件的当量。煤炭最差，碳粉是标准计量。 */
	private static final Map<net.minecraft.world.item.Item, Double> CARBON = Map.of(
			ModItems.CARBON_POWDER, 1.0,
			Items.COAL, 0.8,
			Items.CHARCOAL, 0.9,
			ModItems.CARBON_ROD, 1.5);

	/** 原版金属锭也认，这样玩家手里的铁锭能直接当置换剂/还原剂。 */
	private static final Map<net.minecraft.world.item.Item, String> VANILLA_METALS = Map.of(
			Items.IRON_INGOT, "iron",
			Items.COPPER_INGOT, "copper",
			Items.GOLD_INGOT, "gold");

	/** 电极：物品 → 能撑几个炉次（也是它的耐久上限）。 */
	private static final Map<net.minecraft.world.item.Item, Integer> ELECTRODES = Map.of(
			ModItems.CARBON_ROD, 8,
			ModItems.TUNGSTEN_ROD, 64,
			ModItems.MOLYBDENUM_ROD, 40);

	// ------------------------------------------------------------------ 还原剂

	/**
	 * 碳系还原剂（煤炭 / 木炭 / 碳粉 / 碳棒）—— 还原炉的<b>基础</b>还原剂，永远可用。
	 *
	 * <p>与 {@link #isCarbon} 是同一个判定，别名只为让「还原剂模型」的读法更直白：
	 * 还原路 = 碳系；置换路 = 金属。
	 */
	public static boolean isCarbonFamily(ItemStack stack) {
		return isCarbon(stack);
	}

	/**
	 * 能不能当还原剂：碳系（通用）<b>或</b>能读出一个金属的锭<b>或</b>桶装的 CO / H₂。
	 *
	 * <p>金属与气体都是<b>可选</b>替代，不是还原炉的刚需 —— 还原炉的准入只看这一条；
	 * 「必须比目标活泼」那道活动性闸门只属于置换炉，见
	 * {@code SmelterBlockEntity#reagentFits}。
	 *
	 * <p>气体还原剂的载体是 {@code MolCarrier}（药剂桶）：装 CO 或 H₂ 的桶按 mol 计当量
	 * （{@link ReductantGas}），装水 / 酸的桶一律不算 —— {@link #isGasReductant} 只认
	 * 整桶都是还原性气体的账。桶在炉子里被抽走的是<b>气体</b>，空桶原样留下（见
	 * {@code SmelterBlockEntity#drawGas}）。
	 */
	public static boolean isReductant(ItemStack stack) {
		return isCarbon(stack) || metalOf(stack) != null || isGasReductant(stack);
	}

	// ------------------------------------------------------------------ 气体还原剂（桶装 CO / H₂）

	/**
	 * 这只桶能不能当气体还原剂：必须是 {@link MolCarrier}，且桶里<b>每一种</b>
	 * 成分都是还原性气体（CO / H₂）。
	 *
	 * <p>水桶 / 酸桶、空桶、以及混了杂质的桶都返回 false —— 混装整桶作废。
	 */
	public static boolean isGasReductant(ItemStack stack) {
		return gasUnits(stack) > 0.0;
	}

	/** 这只桶里的气体还原当量（mol 氧 / mol 气体）；不是气体桶返回 0。 */
	public static double gasUnits(ItemStack stack) {
		if (stack.isEmpty() || !(stack.getItem() instanceof MolCarrier carrier)) {
			return 0.0;
		}
		return ReductantGas.units(carrier.stock(stack));
	}

	/**
	 * 一摞载具的「一次一件」守卫。
	 *
	 * <p>一摞桶共享同一个内容组件：整摞抽气会「抽了 16 只、只记一份账」（bug#12 / bug#26）。
	 * 所以非消耗品载具（桶）在试剂槽里只允许单只；消耗品载具（金属粉，{@code consumedOnEmpty}）
	 * 按件现推、只减一件，不受此限。普通物品（碳系 / 金属锭 / 电极）本来就不共享账，恒允许。
	 */
	public static boolean oneAtATime(ItemStack stack) {
		if (stack.isEmpty() || !(stack.getItem() instanceof MolCarrier carrier)) {
			return true;
		}
		return carrier.consumedOnEmpty() || stack.getCount() == 1;
	}

	public static boolean isCarbon(ItemStack stack) {
		return !stack.isEmpty() && CARBON.containsKey(stack.getItem());
	}

	/** 这种试剂的当量。 */
	public static double units(ItemStack stack) {
		if (stack.isEmpty()) {
			return 0.0;
		}
		Double carbon = CARBON.get(stack.getItem());
		if (carbon != null) {
			return carbon;
		}
		return metalOf(stack) == null ? 0.0 : 2.0;
	}

	/**
	 * 试剂的「身份」：碳系返回 null 的金属，金属返回它的金属 id。
	 *
	 * <p>炉子靠它判「这种试剂对这个目标金属有没有用」—— 碳系是通用还原剂，
	 * 金属只能还原/置换比它自己不活泼的金属。
	 */
	public static String metalOf(ItemStack stack) {
		if (stack.isEmpty()) {
			return null;
		}
		String vanilla = VANILLA_METALS.get(stack.getItem());
		if (vanilla != null) {
			return vanilla;
		}
		if (Ingots.isIngot(stack)) {
			String key = Ingots.metalKey(stack);
			// 合金锭没有明确身份，不能当置换剂用（它自己就是混合物）；
			// 铁族分级键（生铁 / 钢……）不是物质，还原成铁再比活动性
			if (key == null || Ingots.ALLOY.equals(key)) {
				return null;
			}
			return Ingots.baseMetalOf(key);
		}
		return null;
	}

	// ------------------------------------------------------------------ 电极

	public static boolean isElectrode(ItemStack stack) {
		return !stack.isEmpty() && ELECTRODES.containsKey(stack.getItem());
	}

	/** 这根电极能撑几个炉次。 */
	public static int electrodeBatches(ItemStack stack) {
		return ELECTRODES.getOrDefault(stack.getItem(), 0);
	}

	/** 描述用：电极的标称寿命。 */
	public static int nominalBatches(net.minecraft.world.item.Item item) {
		return ELECTRODES.getOrDefault(item, 0);
	}
}
