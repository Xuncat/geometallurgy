package com.geometallurgy.item;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import com.geometallurgy.chemical.Chemical;
import com.geometallurgy.chemical.MolStock;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * 化学金属桶：以固定 {@code 15 mol} 容量盛放化学内容。
 *
 * <p>mol 体系的「内容单位」载具（设计稿 §二）。桶不认体积 —— 它是 mol 载具，
 * 不是流动流体，所以可以带牌内容、可以被机器当槽位接收。
 *
 * <p>内容记在 {@link ModComponents#BUCKET_STOCK}（物质 id → mol 数）里，
 * 一个空桶没有这个组件、或组件为空表。mol 是本征记账量，B 只是 1:1 显示名
 * （{@link MolStock#totalB}）。
 *
 * <p><b>单位标签</b>：本类实现 {@link MolCarrier}，且新桶默认打上
 * {@code measure = mol}（见 {@link ModComponents#MEASURE}）。机器按接口 + 标签路由，
 * 不硬编码认本类 —— 见设计稿 §六.7。
 */
public class MetalBucket extends Item implements MolCarrier {

	/** 一桶的固定容量（mol），见 {@link MolStock#BUCKET_MOL}。 */
	public static final double CAPACITY = MolStock.BUCKET_MOL;

	private final BucketMaterial material;

	public MetalBucket(Properties properties, BucketMaterial material) {
		super(properties);
		this.material = material;
	}

	/** 这只桶的材质（耐蚀性）。 */
	public BucketMaterial material() {
		return this.material;
	}

	// ------------------------------------------------------------------ 右键水源：装水（设计稿 §二.2）

	/**
	 * 右键水源方块：空桶装 {@code 15 mol} 水。
	 *
	 * <p>签名经反汇编核对：26.x 的物品对方块交互走
	 * {@code Item.useOn(net.minecraft.world.item.context.UseOnContext)}（{@code ItemStack} 同名转发）。
	 * 方块自身的 {@code useItemOn} 先执行，所以对着储罐 / 机器右键时轮不到这里 —— 不冲突。
	 *
	 * <p>只处理「空桶 + 水源」这一种情形，其余一律 {@link InteractionResult#PASS}，
	 * 把右键让给别的逻辑（比如往储罐里倒）。一摞桶共享同一个内容组件，
	 * 所以一次只处理一只（与 {@code BarrelBlock.oneAtATime} 同一条理由）。
	 *
	 * <p>写回手牌走 {@code BarrelBlock.handOffOne} 的同一套
	 * （{@code setItemInHand} + {@code getInventory().setChanged()} +
	 * {@code inventoryMenu.broadcastChanges()}），否则客户端手里那只桶的悬浮文本
	 * 要等下一次背包同步才刷新。
	 */
	@Override
	public InteractionResult useOn(UseOnContext context) {
		Player player = context.getPlayer();
		if (player == null) {
			return InteractionResult.PASS;
		}
		Level level = context.getLevel();
		BlockPos pos = context.getClickedPos();
		FluidState fluid = level.getFluidState(pos);
		if (!fluid.isSourceOfType(Fluids.WATER)) {
			return InteractionResult.PASS; // 不是水源：让给别人
		}
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS; // 客户端只摆手臂，账在服务端动
		}
		InteractionHand hand = context.getHand();
		ItemStack held = player.getItemInHand(hand);
		if (!(held.getItem() instanceof MetalBucket)) {
			return InteractionResult.PASS;
		}
		if (!stock(held).isEmpty()) {
			return InteractionResult.PASS; // 桶里有料：不抢，可能是想倒出来
		}
		if (held.getCount() > 1) {
			player.sendOverlayMessage(Component.translatable("gui.geometallurgy.bucket_one_at_a_time"));
			return InteractionResult.SUCCESS;
		}
		ItemStack filled = held.copyWithCount(1);
		setStock(filled, MolStock.of("water", CAPACITY));
		player.setItemInHand(hand, filled);
		player.getInventory().setChanged();
		player.inventoryMenu.broadcastChanges();
		if (player.containerMenu != player.inventoryMenu) {
			player.containerMenu.broadcastChanges();
		}
		// 与香草桶一致：水源被取走，否则单个水源就成了无限水
		level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
		player.sendOverlayMessage(filled.getHoverName());
		return InteractionResult.SUCCESS;
	}

	/** 材质 → 物品。反向映射，创造物品栏与药剂配方提示用。 */
	public static Item itemFor(BucketMaterial material) {
		return switch (material) {
			case IRON -> ModItems.METAL_BUCKET;
			case GLASS -> ModItems.GLASS_BUCKET;
			case LEAD -> ModItems.LEAD_BUCKET;
			case CERAMIC -> ModItems.CERAMIC_BUCKET;
		};
	}

	@Override
	public boolean holds(MolStock stock) {
		return this.material.holds(stock);
	}

	// ------------------------------------------------------------------ MolCarrier 实现

	@Override
	public double capacityMol() {
		return CAPACITY;
	}

	@Override
	public MolStock stock(ItemStack stack) {
		Map<String, Double> map = stack.get(ModComponents.BUCKET_STOCK);
		if (map == null || map.isEmpty()) {
			return MolStock.empty();
		}
		MolStock s = MolStock.empty();
		for (Map.Entry<String, Double> e : map.entrySet()) {
			s.add(e.getKey(), e.getValue());
		}
		return s;
	}

	@Override
	public boolean isEmpty(ItemStack stack) {
		return stock(stack).isEmpty();
	}

	@Override
	public double room(ItemStack stack) {
		return Math.max(0.0, CAPACITY - stock(stack).totalMol());
	}

	@Override
	public double take(ItemStack stack, String id, double mol) {
		if (mol <= 0.0) {
			return 0.0;
		}
		MolStock s = stock(stack);
		double taken = s.take(id, mol);
		setStock(stack, s);
		return taken;
	}

	@Override
	public double add(ItemStack stack, MolStock amount) {
		if (amount == null || amount.isEmpty()) {
			return 0.0;
		}
		MolStock s = stock(stack);
		double accepted = 0.0;
		double room = CAPACITY - s.totalMol();
		for (Map.Entry<String, Double> e : amount.view().entrySet()) {
			double piece = Math.min(e.getValue(), Math.max(0.0, room - accepted));
			if (piece > 0.0) {
				s.add(e.getKey(), piece);
				accepted += piece;
			}
		}
		if (accepted > 0.0) {
			setStock(stack, s);
		}
		return accepted;
	}

	// ------------------------------------------------------------------ 便捷方法（金属桶专属）

	/** 造一个装好内容的铁桶（默认打 mol 标签）。调用方负责总量 ≤ 容量。 */
	public static ItemStack create(MolStock content) {
		return create(ModItems.METAL_BUCKET, content);
	}

	/** 造一个指定材质的满桶。调用方负责内容与材质相容、总量 ≤ 容量。 */
	public static ItemStack create(Item item, MolStock content) {
		ItemStack stack = new ItemStack(item, 1);
		stack.set(ModComponents.MEASURE, Measure.MOL.key());
		setStock(stack, content);
		return stack;
	}

	/** 把一份 mol 账写进桶（空账清除内容组件，保留单位标签）。 */
	public static void setStock(ItemStack stack, MolStock content) {
		if (content == null || content.isEmpty()) {
			stack.remove(ModComponents.BUCKET_STOCK);
		} else {
			stack.set(ModComponents.BUCKET_STOCK, content.view());
		}
		refreshColor(stack, content);
	}

	/**
	 * 把内容的代表色写进 {@code custom_model_data}，供物品模型的
	 * {@code minecraft:custom_model_data} 染色源取用（见
	 * {@code items/metal_bucket.json}）。
	 *
	 * <p>取「按 mol 加权的混色」：纯净物就是它自己的颜色，混合物是各成分的加权平均。
	 * 无色物质（近无色）会得到一个接近桶底色的灰白，所以空桶与清水桶看起来都不刺眼。
	 */
	private static void refreshColor(ItemStack stack, MolStock content) {
		int rgb = Chemical.colorOf(content) & 0xFFFFFF;
		stack.set(DataComponents.CUSTOM_MODEL_DATA,
				new CustomModelData(List.of(), List.of(), List.of(), List.of(rgb)));
	}

	// ------------------------------------------------------------------ 显示

	@Override
	public Component getName(ItemStack stack) {
		MolStock s = stock(stack);
		String dominant = s.isEmpty() ? null : dominant(s);
		Component content = dominant == null
				? Component.translatable("item.geometallurgy.bucket.empty_content")
				: Component.translatable("chemical.geometallurgy." + dominant);
		return Component.translatable("item.geometallurgy.bucket.named",
				Component.translatable(this.material.nameKey()), content);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
			Consumer<Component> tooltip, TooltipFlag flag) {
		super.appendHoverText(stack, context, display, tooltip, flag);
		// 材质与它耐什么 —— 装化学品之前先看这一行
		tooltip.accept(Component.translatable("gui.geometallurgy.bucket.material_line",
				Component.translatable(this.material.nameKey()),
				Component.translatable("gui.geometallurgy.bucket.resists." + this.material.id()))
				.withStyle(ChatFormatting.DARK_GRAY));
		MolStock s = stock(stack);
		if (s.isEmpty()) {
			tooltip.accept(Component.translatable("gui.geometallurgy.bucket.empty")
					.withStyle(ChatFormatting.GRAY));
			return;
		}
		if (!this.material.holds(s)) {
			tooltip.accept(Component.translatable("gui.geometallurgy.bucket.corrosion")
					.withStyle(ChatFormatting.RED));
		}
		tooltip.accept(Component.translatable("gui.geometallurgy.bucket.total",
				trim(s.totalMol()), trim(s.totalB()), trim(s.massGrams()))
				.withStyle(ChatFormatting.GRAY));
		// 按相态分组：气 / 液 / 固各一行，行内才是同一相的混合物（设计稿 §4.1）
		for (var line : MolReadout.lines(s, "gui.geometallurgy.bucket.entry")) {
			tooltip.accept(line.withStyle(ChatFormatting.DARK_GRAY));
		}
	}

	/** 成分最多的那种物质 id；空返回 null。 */
	private static String dominant(MolStock s) {
		String best = null;
		double bestValue = 0.0;
		for (Map.Entry<String, Double> e : s.view().entrySet()) {
			if (e.getValue() > bestValue) {
				bestValue = e.getValue();
				best = e.getKey();
			}
		}
		return best;
	}

	/** 显示数字：整数去尾零，小数保留两位。 */
	private static String trim(double v) {
		if (v == Math.rint(v)) {
			return String.format(Locale.ROOT, "%.0f", v);
		}
		return String.format(Locale.ROOT, "%.2f", v);
	}

	static {
		// 确保 Chemical 表已加载（只为静态块触发的存在校验，避免死代码被忽略）
		Chemical.molarMassOf("water");
	}
}