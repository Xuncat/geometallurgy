package com.geometallurgy.metal;

import java.util.Map;

import com.geometallurgy.chemical.Chemical;
import com.geometallurgy.chemical.ChemicalTags;
import com.geometallurgy.chemical.MolStock;

/**
 * 金属粉的 mol 内容：金属键 → Chemical 金属单质 id → mol（纯函数）。
 *
 * <p>这一层刻意<b>不碰任何 Minecraft 类型</b>（不导入 ItemStack / Ingots）：
 * 它就是「一件粉对应哪个 species、多少 mol」这条规则的唯一出处，
 * 因此可以脱离游戏、用一次性 plain-Java harness 直接跑（见 docs/科技线-v1.md §十八）。
 *
 * <h2>为什么 mol 数是常数而不是按成分质量折算</h2>
 * 粉的 mol 账是<b>派生量</b>，不是存在物品上的可变组件（bug#12：一摞共享一个组件，
 * 倒一次会把整摞清空）。既然派生，就必须能只由「这是哪种金属」推出来，
 * 否则同一种金属的两件粉会算出不同的 mol，账就不可堆叠、也不可复现。
 * 所以这里只认主金属：{@code 一件粉 = MOL_PER_POWDER mol 该金属}。
 * 成分表里的伴生元素不折成 mol —— 规则 5（金属 + 酸）只认金属单质，
 * 留它们在成分表里给玩家看纯度即可。
 *
 * <p><b>与 {@link Ingots#baseMetalOf} 的关系</b>：铁族那四个分级键（生铁 / 钢 / 合金钢 / 锻铁）
 * 在浮层与 mol 账里都要还原成基础金属 {@code iron}。两张表说的是一回事，
 * 改一处时另一处也要跟着改。
 */
public final class PowderContent {

	private PowderContent() {
	}

	/** 一件金属粉携带的 mol 数 = 一锭 = 0.1 mol，见 {@link MolStock#INGOT_MOL}。 */
	public static final double MOL_PER_POWDER = MolStock.INGOT_MOL;

	/** 铁族分级键 → 基础金属键（镜像 {@link Ingots#baseMetalOf} 的四个分支）。 */
	private static final Map<String, String> IRON_FAMILY = Map.of(
			"pig_iron", "iron",
			"steel", "iron",
			"alloy_steel", "iron",
			"wrought_iron", "iron");

	/**
	 * 金属键 → {@link Chemical} 里的金属单质 id；挑不出返回 {@code null}。
	 *
	 * <p>下面三种都返回 null（粉进不了 mol 账，不是错误，是它本来就不是金属单质）：
	 * <ul>
	 *   <li>{@code alloy}：主金属不足七成，没有哪一种金属能代表它；</li>
	 *   <li>稀土族：{@code rare_earth} 是并项，Chemical 表里没有这个物种；</li>
	 *   <li>表里没登记成金属的 id（防以后加非金属单质时误收）。</li>
	 * </ul>
	 */
	public static String speciesOf(String metalKey) {
		if (metalKey == null) {
			return null;
		}
		String id = IRON_FAMILY.getOrDefault(metalKey, metalKey);
		if (!Chemical.isKnown(id)) {
			return null;
		}
		return ChemicalTags.has(id, ChemicalTags.Tag.METAL) ? id : null;
	}

	/** 一件金属粉的 mol 账：{@code 金属 id -> MOL_PER_POWDER}；挑不出金属给空账。 */
	public static MolStock contentOf(String metalKey) {
		String species = speciesOf(metalKey);
		return species == null ? MolStock.empty() : MolStock.of(species, MOL_PER_POWDER);
	}
}
