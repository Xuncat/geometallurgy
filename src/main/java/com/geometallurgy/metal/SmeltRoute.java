package com.geometallurgy.metal;

import java.util.Set;

import com.geometallurgy.chemical.ChemicalTags;

/**
 * 从矿粉里取出金属的三条化学路线。
 *
 * <p>它们<b>不是</b>一条产线上的三道工序，而是三条并列的备选方案 —— 同一份矿粉能不能走某条路线，
 * 由矿物本身的化学属性决定（见 {@link Metallurgy}）。有的矿物三条都能走（硅镁镍矿），
 * 有的只能走一条（赤铁矿只有碳热还原，萤石只有熔融电解）。
 *
 * <p>三条路线的取舍各不相同，这正是让玩家做选择的地方：
 * <ul>
 *   <li>{@link #REDUCTION} —— 用碳（通用）或比目标更活泼的金属把氧化物夺氧。耗电中等、
 *       试剂便宜，收率与纯度都居中。<b>氧化物最对路</b>。</li>
 *   <li>{@link #ELECTROLYSIS} —— 熔融电解。耗电最大，还要磨电极（碳棒烧得快，
 *       钨棒/钼棒几乎不耗），换来<b>最高收率与最干净的产品</b>。活泼金属只能走这条。</li>
 *   <li>{@link #DISPLACEMENT} —— 湿法置换：用一种更活泼的金属把溶液里的目标金属顶出来。
 *       <b>几乎不耗电</b>，但要用掉一种比目标更贵的金属，并且副产一罐盐液。指标居中偏低。</li>
 * </ul>
 */
public enum SmeltRoute {

	REDUCTION("reduction"),
	ELECTROLYSIS("electrolysis"),
	DISPLACEMENT("displacement");

	private final String key;

	SmeltRoute(String key) {
		this.key = key;
	}

	/** 语言文件的键后缀。 */
	public String key() {
		return this.key;
	}

	public String translationKey() {
		return "gui.geometallurgy.route_" + this.key;
	}

	/**
	 * 这条路线按<b>物质标签</b>收不收料 —— 给显式路线表（{@link Metallurgy#TABLE}）兜底，
	 * 新物质/新矿只靠公式推出的标签就能进对炉子。
	 *
	 * <p>目前只给还原炉开口：<b>氧化物</b>通用可还原（{@link ChemicalTags.Tag#OXIDE}），
	 * 但{@link ChemicalTags.Tag#REFRACTORY 难还原氧化物}（Al₂O₃ / MgO 这类，碳夺不动氧）除外。
	 * 电解 / 置换仍由显式表逐条决定 —— 这两条路的准入依赖的不只是官能团（电解要熔盐、置换要活动性顺序），
	 * 标签兜底会误收，所以这里返回 false，既有熔盐电解与湿法置换的准入一个字都没动。
	 */
	public boolean acceptsTagged(Set<ChemicalTags.Tag> tags) {
		return switch (this) {
			case REDUCTION -> tags.contains(ChemicalTags.Tag.OXIDE)
					&& !tags.contains(ChemicalTags.Tag.REFRACTORY);
			case ELECTROLYSIS, DISPLACEMENT -> false;
		};
	}
}
