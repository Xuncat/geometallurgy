package com.geometallurgy.metal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.geometallurgy.item.ModComponents;
import com.geometallurgy.item.ModItems;
import com.geometallurgy.proto.RareEarth;
import com.geometallurgy.proto.Substance;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 单一「锭」物品的语义层。
 *
 * <h2>一个物品，无数种锭</h2>
 * 本模组只注册<b>一个</b>锭物品（{@code geometallurgy:ingot}），它是什么金属完全由两个组件决定：
 * <ul>
 *   <li>{@link ModComponents#INGOT_COMPOSITION} —— 完整成分表（物质 id → 质量分数）。
 *       这个名字/贴图/配方匹配全都要用到它。</li>
 *   <li>{@link ModComponents#INGOT_METAL} —— <b>主金属键</b>，一个低基数标签。
 *       主金属占比够高时是那种金属的 id，否则是 {@link #ALLOY}。
 *       它单独存在，是因为物品模型要按它选贴图（{@code minecraft:select} 需要一个可枚举的键），
 *       而成分表是连续量、没法当选择器用。</li>
 * </ul>
 *
 * <h2>70% 这条线</h2>
 * 主金属占比达到 {@link #CALL_THRESHOLD} 才「算得上」是那种金属：名字叫铁锭、贴图是铁的、
 * 模组配方里也能当铁锭用。够不上就只能叫合金锭 —— 所以「把矿选干净再进炉」是有回报的。
 *
 * <p><b>关于原版配方</b>：26.x 的原版 {@code Ingredient} 已经收成一个纯物品集合
 * （{@code final}，内部只有 {@code HolderSet<Item>}），看不到物品组件，因此原版配方没法直接
 * 识别成分。想要把高纯锭接回原版合成链，得靠 {@code IngotCastingRecipe} 那条特殊配方。
 */
public final class Ingots {

	private Ingots() {
	}

	/** 主金属占比达到这个数，才算得上「是那种金属」。 */
	public static final double CALL_THRESHOLD = 0.70;

	/** 占比再高一点，名字前头就加个「精」字。 */
	public static final double FINE_THRESHOLD = 0.90;

	/** 主金属键在「不够纯」时的取值。 */
	public static final String ALLOY = "alloy";

	/** 铁与碳的物质 id —— 铁族分级只围着他俩转。 */
	private static final String IRON = "iron";
	private static final String CARBON = "carbon";

	/** 稀土那一族并项之后的键。命名与浮层都按它走。 */
	public static final String RARE_EARTH = "rare_earth";

	// ------------------------------------------------------------------ 铁族分级
	//
	// 铁是唯一一个「名字要看含碳量」的金属：碳热还原会往产品里掺碳（基准 5%、按反应消耗扣），
	// 所以同一台还原炉出来的铁，含碳量在 0~5% 之间浮动 —— 这正好是生铁 / 钢 / 熟铁的分界线。

	/** 主金属键：铁含碳 ≥ 此线为生铁（铸铁那一档）。 */
	public static final String PIG_IRON = "pig_iron";
	/** 主金属键：含碳（但不至于生铁）为钢。 */
	public static final String STEEL = "steel";
	/** 主金属键：钢里再掺足别的金属为合金钢。 */
	public static final String ALLOY_STEEL = "alloy_steel";
	/** 主金属键：极高纯度且几乎无碳为锻铁。 */
	public static final String WROUGHT_IRON = "wrought_iron";

	/**
	 * 碳占比达到此线（4%）判生铁。
	 *
	 * <p>成分表在 {@link Ingots#normalize} 里按<b>百分之一</b>网格量化（可堆叠的前提），
	 * 所以分界线只能落在 1% 的整数倍上 —— 3.5% 这种数字写出来也存不进去。
	 */
	public static final double CARBON_PIG_IRON = 0.04;
	/** 碳占比达到此线（1%）就算「含碳」，落进钢那一档。 */
	public static final double CARBON_STEEL = 0.01;
	/** 其他金属（铁和碳之外）占比达到此线（2%），钢就叫合金钢。 */
	public static final double ALLOYING_THRESHOLD = 0.02;

	/**
	 * 残留元素（tramp element）：掺进去也<b>不算「合金化」</b>的那几种。
	 *
	 * <p>冶金学里 tramp element 指的正是这类东西 —— 不是有意添加的合金元素，
	 * 而是跟着矿石和废钢混进来的杂质。磷、硫、砷、锑都在此列：它们的共同后果是
	 * 让钢变脆，而不是让钢变强。
	 *
	 * <p>为什么必须在 {@link #ironKeyOf} 里把它们摘出去：那一档判的是「有没有掺别的金属」，
	 * 只要铁碳之外的东西够 2% 就叫合金钢 —— 而合金钢的四轴比普通钢<b>更硬</b>
	 * （刚性 0.85 对 0.80）。若不摘，掺 2% 的磷会把一块钢从 STEEL 抬进 ALLOY_STEEL，
	 * 于是「掺杂质」反而让刚性上升，与常理正好相反。
	 */
	public static final Set<String> TRAMP_ELEMENTS =
			Set.of("phosphorus", "sulfur", "arsenic", "antimony");
	/** 铁纯度达到此线（95%）且碳在钢线以下，才是锻铁。 */
	public static final double WROUGHT_PURITY = 0.95;

	// ------------------------------------------------------------------ 组件读写

	/** 完整成分表；没有组件时返回空表。 */
	public static Map<String, Double> composition(ItemStack stack) {
		Map<String, Double> map = stack.get(ModComponents.INGOT_COMPOSITION);
		return map == null ? Map.of() : map;
	}

	/** 主金属键：金属 id，或 {@link #ALLOY}。没有组件时返回 null。 */
	public static String metalKey(ItemStack stack) {
		return stack.get(ModComponents.INGOT_METAL);
	}

	/** 按成分表造一个锭。主金属键自动推出来，不必调用方自己判 70%。 */
	public static ItemStack create(Map<String, Double> composition, int count) {
		Map<String, Double> normalized = normalize(composition);
		ItemStack stack = new ItemStack(ModItems.INGOT, count);
		stack.set(ModComponents.INGOT_COMPOSITION, normalized);
		stack.set(ModComponents.INGOT_METAL, metalKeyOf(normalized));
		return stack;
	}

	/**
	 * 按成分表造一份<b>金属粉</b>：与 {@link #create} 完全同一套组件，只是物品是粉末。
	 *
	 * <p>粉碎机把金属锭磨成粉就是调它 —— 金属粉是金属单质在 mol 世界的入口（§十八）。
	 */
	public static ItemStack createPowder(Map<String, Double> composition, int count) {
		Map<String, Double> normalized = normalize(composition);
		ItemStack stack = new ItemStack(ModItems.METAL_POWDER, count);
		stack.set(ModComponents.INGOT_COMPOSITION, normalized);
		stack.set(ModComponents.INGOT_METAL, metalKeyOf(normalized));
		return stack;
	}

	/** 把一个已有的锭的金属键重新推一遍（成分被改动过之后用）。 */
	public static ItemStack retag(ItemStack stack) {
		Map<String, Double> composition = composition(stack);
		if (!composition.isEmpty()) {
			stack.set(ModComponents.INGOT_METAL, metalKeyOf(composition));
		}
		return stack;
	}

	// ------------------------------------------------------------------ 判定

	/**
	 * 按成分表推主金属键：占比最高的那一种，且必须过 {@link #CALL_THRESHOLD} 这条线；
	 * 过不了就是 {@link #ALLOY}（合金）。
	 *
	 * <p>先按「稀土一族」并项再挑：一炉稀土拿到的是十几分之一份配分，镧铈镨钕分开看
	 * 谁都过不了七成，合起来才是「这块是稀土锭」这个事实。
	 */
	public static String metalKeyOf(Map<String, Double> composition) {
		Map<String, Double> grouped = group(composition);
		String dominant = dominant(grouped);
		if (dominant == null || share(grouped, dominant) < CALL_THRESHOLD) {
			return ALLOY;
		}
		if (IRON.equals(dominant)) {
			return ironKeyOf(grouped);
		}
		return dominant;
	}

	/**
	 * 铁族分级：含碳量定生铁 / 钢，纯度定锻铁，别的金属定合金钢。
	 *
	 * <p>分级顺序是有讲究的：<b>碳优先</b> —— 一块碳 4% 的料哪怕锰再多也是生铁
	 * （真实冶金里碳太高什么都白搭）；碳在钢区间时再看别的金属够不够格叫「合金」钢；
	 * 碳几乎为零时才轮到纯度说话，95% 以上才配叫锻铁。
	 */
	private static String ironKeyOf(Map<String, Double> grouped) {
		double carbon = grouped.getOrDefault(CARBON, 0.0);
		if (carbon >= CARBON_PIG_IRON) {
			return PIG_IRON;
		}
		if (carbon >= CARBON_STEEL) {
			double other = 0.0;
			for (Map.Entry<String, Double> entry : grouped.entrySet()) {
				String id = entry.getKey();
				// 残留元素不算「合金化」：掺磷是意外，掺镍才是配方。
				if (IRON.equals(id) || CARBON.equals(id) || TRAMP_ELEMENTS.contains(id)) {
					continue;
				}
				other += entry.getValue();
			}
			return other >= ALLOYING_THRESHOLD ? ALLOY_STEEL : STEEL;
		}
		return share(grouped, IRON) >= WROUGHT_PURITY ? WROUGHT_IRON : IRON;
	}

	/** 铁族分级键在浮层与显示名里都还原成「铁」。 */
	public static String baseMetalOf(String key) {
		return switch (key) {
			case PIG_IRON, STEEL, ALLOY_STEEL, WROUGHT_IRON -> IRON;
			default -> key;
		};
	}

	/** 这个键是不是铁族的分级键（生铁 / 钢 / 合金钢 / 锻铁）。 */
	public static boolean isIronFamily(String key) {
		return PIG_IRON.equals(key) || STEEL.equals(key)
				|| ALLOY_STEEL.equals(key) || WROUGHT_IRON.equals(key);
	}

	/**
	 * 这块料的含碳量是不是落在生铁区间。
	 *
	 * <h2>判据只看碳，不看铁纯度</h2>
	 * 生铁是<b>碳</b>决定的，不是纯度决定的 —— 所以一块 60% 铁 + 5% 碳的料，
	 * 虽然在 {@link #metalKeyOf} 那里够不上「生铁锭」这个名字（铁不到七成，
	 * 会被判成 {@link #ALLOY}），但它在冶金上就是生铁，该脆的一点不少。
	 *
	 * <p>之所以要单独问这一句：{@link MetalTraits} 的四轴表里<b>没有碳</b> ——
	 * 碳是杂质不是金属，{@link MetalTraits#weighted} 会整项跳过它。于是一块
	 * 60% 铁 + 5% 碳的料，韧性会被算成 0.70，看上去和铁一样体面，脆性一点没露出来。
	 * 所以「能不能用」这条判断必须直接问成分表，不能只信四轴。
	 */
	public static boolean isPigIronRange(Map<String, Double> composition) {
		if (composition == null || composition.isEmpty()) {
			return false;
		}
		Map<String, Double> grouped = group(composition);
		if (!IRON.equals(dominant(grouped))) {
			return false;
		}
		return grouped.getOrDefault(CARBON, 0.0) >= CARBON_PIG_IRON;
	}

	/**
	 * 把成分表里的稀土元素并成 {@link #RARE_EARTH} 一项，其余原样。
	 *
	 * <p>命名、纯度、浮层都要先过这一道 —— 单看某一种稀土元素没有意义。
	 */
	public static Map<String, Double> group(Map<String, Double> composition) {
		Map<String, Double> out = new LinkedHashMap<>();
		for (Map.Entry<String, Double> entry : composition.entrySet()) {
			String key = RareEarth.isRareEarth(entry.getKey()) ? RARE_EARTH : entry.getKey();
			out.merge(key, entry.getValue(), Double::sum);
		}
		return out;
	}

	/** 只挑出稀土元素那一部分 —— 浮层里要把它们缩进列在稀土那一行下面。 */
	public static Map<String, Double> rareEarthPart(Map<String, Double> composition) {
		Map<String, Double> out = new LinkedHashMap<>();
		for (Map.Entry<String, Double> entry : composition.entrySet()) {
			if (RareEarth.isRareEarth(entry.getKey())) {
				out.put(entry.getKey(), entry.getValue());
			}
		}
		return out;
	}

	/** 某一项在成分表里的占比；{@link #RARE_EARTH} 按整族合计。 */
	public static double share(Map<String, Double> composition, String substance) {
		return purity(group(composition), substance);
	}

	/** 占比最高的那种物质；成分表为空时返回 null。 */
	public static String dominant(Map<String, Double> composition) {
		String best = null;
		double bestValue = 0.0;
		for (Map.Entry<String, Double> entry : composition.entrySet()) {
			if (entry.getValue() > bestValue) {
				bestValue = entry.getValue();
				best = entry.getKey();
			}
		}
		return best;
	}

	/** 某种物质在锭里的质量分数。 */
	public static double purity(Map<String, Double> composition, String substance) {
		if (composition.isEmpty()) {
			return 0.0;
		}
		double total = 0.0;
		for (double value : composition.values()) {
			total += value;
		}
		return total <= 0.0 ? 0.0 : composition.getOrDefault(substance, 0.0) / total;
	}

	/**
	 * 这个锭能不能当 {@code metal} 的锭用。
	 *
	 * <p>判据就是主金属键 —— 它已经把 70% 这条线算进去了。
	 */
	public static boolean matches(ItemStack stack, String metal) {
		String key = metalKey(stack);
		return key != null && key.equals(metal);
	}

	/** 这个锭是不是「随便什么金属锭」——给「任意锭」这类配方用。 */
	public static boolean isIngot(ItemStack stack) {
		return !stack.isEmpty() && stack.getItem() == ModItems.INGOT;
	}

	// ------------------------------------------------------------------ 显示

	/**
	 * 名字：普通金属按纯度叫「铁锭 / 精铁锭」；铁族按含碳与纯度细分——
	 * 生铁锭、钢锭、合金钢锭、锻铁锭；不够纯叫「合金锭」。
	 */
	public static Component displayName(ItemStack stack) {
		Map<String, Double> composition = composition(stack);
		String key = metalKey(stack);
		if (key == null || key.equals(ALLOY) || composition.isEmpty()) {
			return Component.translatable("item.geometallurgy.ingot.alloy");
		}
		if (isIronFamily(key)) {
			// 铁族的四个名字是完整词，不再套「精×××」的前缀
			return Component.translatable("item.geometallurgy.ingot." + key);
		}
		// 用还原后的基础金属 + 并项后的表查占比 —— 与 tooltip 同一个口径。
		// 现在走到这里的 key 一定不是分级键（铁族上面已经返回了），
		// 但留这两道是防以后加新的分级族、或稀土这类并项族时又踩同一个坑：
		// 拿 rare_earth 去查原始成分表（键是 cerium / dysprosium…）同样查不到。
		String base = baseMetalOf(key);
		double purity = share(group(composition), base);
		String grade = purity >= FINE_THRESHOLD ? "fine" : "plain";
		return Component.translatable("item.geometallurgy.ingot." + grade,
				Substance.displayName(base));
	}

	/**
	 * 鼠标浮层：主金属一行 + 其余成分逐项列出。
	 *
	 * <h2>主金属不在下面那一列里重复出现</h2>
	 * 第一行已经写了「主金属 铁 88.0%」，下面再列一条「铁 88.0%」是把同一件事说两遍。
	 * 照 {@code tfc_chemical} 的分层写法（主金属一行、伴生元素另列），
	 * 下面那一列只列<b>除主金属之外</b>的成分 —— 玩家要看的正是「除了铁，还掺了什么」。
	 *
	 * <p>稀土一行底下仍列出这一炉的<b>配分</b>：稀土是十几分之一份混合物，
	 * 「稀土 12%」这句话没说完，玩家要知道里面到底是铈多还是镝多。
	 */
	public static List<Component> tooltip(ItemStack stack) {
		List<Component> lines = new ArrayList<>();
		Map<String, Double> composition = composition(stack);
		if (composition.isEmpty()) {
			lines.add(Component.translatable("gui.geometallurgy.ingot.no_data")
					.withStyle(ChatFormatting.GRAY));
			return lines;
		}
		Map<String, Double> grouped = group(composition);
		String key = metalKey(stack);
		// 主金属的<b>元素键</b> —— 下面那一列要跳过它。
		String primary;
		if (key != null && !key.equals(ALLOY)) {
			// 铁族分级键（生铁 / 钢……）不是物质，浮层里还原成「铁」。
			//
			// 查占比时<b>必须</b>用两个还原：
			//   ① 键要还原成基础金属 —— metalKey 给的是分级键（alloy_steel），
			//      而成分表里的键是基础金属（iron）；拿分级键去查表查不到，
			//      于是「主金属 铁」后面跟一个 0.0%，而下一行「铁 88.0%」又是对的。
			//   ② 表要用<b>并项之后</b>的 grouped —— 稀土十几分之一会被并成
			//      rare_earth 一个键，而原始表里根本没有这个键。用原始表查
			//      纯稀土锭同样会得到 0.0%。
			primary = baseMetalOf(key);
			net.minecraft.network.chat.Component metal = Substance.displayName(primary);
			lines.add(Component.translatable("gui.geometallurgy.ingot.purity",
					metal, percent(share(grouped, primary))).withStyle(ChatFormatting.GRAY));
		} else {
			// 主金属占比不足七成：没有单一的「主金属」可言，所以第一行不给占比，
			// 下面那一列也就<b>不跳过</b>任何一项（没有哪一项已经在上面写过了）。
			primary = null;
			lines.add(Component.translatable("gui.geometallurgy.ingot.alloy_note")
					.withStyle(ChatFormatting.GRAY));
		}
		for (Map.Entry<String, Double> entry : sorted(grouped)) {
			if (entry.getKey().equals(primary)) {
				// 主金属已经在第一行写过了。
				continue;
			}
			if (RARE_EARTH.equals(entry.getKey())) {
				lines.add(Component.translatable("gui.geometallurgy.ingot.entry",
						Substance.displayName(RARE_EARTH), percent(entry.getValue()))
						.withStyle(ChatFormatting.DARK_GRAY));
				for (Map.Entry<String, Double> child : sorted(rareEarthPart(composition))) {
					lines.add(Component.translatable("gui.geometallurgy.ingot.sub_entry",
							Substance.displayName(child.getKey()), percent(child.getValue()))
							.withStyle(ChatFormatting.DARK_GRAY));
				}
			} else {
				lines.add(Component.translatable("gui.geometallurgy.ingot.entry",
						Substance.displayName(entry.getKey()), percent(entry.getValue()))
						.withStyle(ChatFormatting.DARK_GRAY));
			}
		}
		return lines;
	}

	private static String percent(double fraction) {
		return String.format(Locale.ROOT, "%.1f%%", fraction * 100.0);
	}

	/** 成分按占比降序排列。 */
	public static List<Map.Entry<String, Double>> sorted(Map<String, Double> composition) {
		List<Map.Entry<String, Double>> list = new ArrayList<>(composition.entrySet());
		list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
		return list;
	}

	// ------------------------------------------------------------------ 归一化

	/**
	 * 把成分表归一到总和 1.000，并对齐到千分位。
	 *
	 * <p>取整到千分位不是为了省地方，而是为了<b>可堆叠</b>：成分是连续量，不量化的话
	 * 每一锭都是独一无二的，背包会炸。千分位之后，同一炉同一种料出来的锭就长得一模一样了。
	 */
	public static Map<String, Double> normalize(Map<String, Double> raw) {
		double total = 0.0;
		for (double value : raw.values()) {
			total += value;
		}
		if (total <= 0.0) {
			return Map.of();
		}
		Map<String, Double> rounded = new LinkedHashMap<>();
		double assigned = 0.0;
		String largestKey = null;
		double largestValue = -1.0;
		for (Map.Entry<String, Double> entry : raw.entrySet()) {
			double share = entry.getValue() / total;
			double step = Math.round(share * 100.0) / 100.0;
			if (step <= 0.0) {
				continue;
			}
			rounded.put(entry.getKey(), step);
			assigned += step;
			if (step > largestValue) {
				largestValue = step;
				largestKey = entry.getKey();
			}
		}
		// 逐个四舍五入会把总和推离 1.00，零头记在最大的一项上
		double drift = Math.round((1.0 - assigned) * 100.0) / 100.0;
		if (largestKey != null && Math.abs(drift) >= 0.005) {
			double fixed = rounded.get(largestKey) + drift;
			if (fixed > 0.0) {
				rounded.put(largestKey, Math.round(fixed * 100.0) / 100.0);
			}
		}
		return Map.copyOf(rounded);
	}
}
