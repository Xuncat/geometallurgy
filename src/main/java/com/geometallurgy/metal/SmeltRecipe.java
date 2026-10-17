package com.geometallurgy.metal;

import com.geometallurgy.proto.TracePhase;

import com.geometallurgy.slurry.SlurryPart;

/**
 * 一条「某个相 → 某种产物」的冶炼路线。
 *
 * <p>每条路线只回答几件事，炉子的行为全部由它们推出来：
 * <ul>
 *   <li>{@code richFraction} —— 这个相里可提取的有价成分占多少（方铅矿 86.6% 铅、石英 46.7% 硅、
 *       阳极泥 30% 稀土……）。矿物相的这一项由矿床的化学式给，伴生相/副产相自己声明。</li>
 *   <li>{@code stoichiometry} —— 每 100 mB 该相要吃掉多少试剂（当量）。硫化物要先焙烧再还原，
 *       所以比氧化物费；自然金属只需熔融，几乎不耗。<b>试剂驱动的路线（{@code powerFePerTick == 0}）
 *       把原本由电提供的热量折进这一项</b>，所以它比同族的电炉路线高 50%~70%。</li>
 *   <li>{@code powerFePerTick} —— <b>这条路线自己的功率（FE/t），绝对数</b>，不是倍率：
 *       炉子单刻扣的就是它（再被 {@code SmelterProfile.maxDraw} 封顶）。
 *       {@code 0} = <b>试剂驱动</b>：一条电都不吃，全靠还原剂（高炉 / 反射炉 / 熔炼那一路）；
 *       电解路线一律是几百 FE/t 的量级（熔盐电解最贵）。见 {@link Metallurgy} 的路线表。</li>
 *   <li>{@code recovery} —— 基准收率：有价成分有多少能进到产品里。
 *       实际收率还要乘一个「料干不干净」的因子（见 {@link Metallurgy}）。</li>
 *   <li>{@code carryRate} —— 杂质带入率：非有价的那部分有多大比例会跟着进产品。
 *       电解最干净，湿法最脏。</li>
 *   <li>{@code chance} —— 一炉能不能<b>真的</b>拿到这批产物。1.0 是必然，小于 1 就是赌 ——
 *       目前只有湿法置换回收阳极泥里的贵金属用它。</li>
 *   <li>{@code mixture} —— 产物是单一物质，还是一份要摊开的<b>配分</b>。见 {@link Mixture}。</li>
 * </ul>
 *
 * @param phase         适用的相（矿物相、伴生相或阳极泥）
 * @param route         路线
 * @param product       产物的物质 id：金属路线就是金属 id（{@code iron}），
 *                      非金属路线是元素 id（石墨电解制碳粉是 {@code carbon}；
 *                      稀土则是 {@code rare_earth} 这个族名，具体元素见 {@code mixture}）
 * @param ingot         产物是锭还是粉。目前只有石墨制碳粉一条非锭路线
 * @param richFraction  该相里可提取的有价成分含量（0~1）
 * @param stoichiometry 试剂当量（每 100 mB）
 * @param powerFePerTick 这条路线自己的功率（FE/t，绝对值；0 = 试剂驱动、不要电）
 * @param recovery      基准收率
 * @param carryRate     杂质带入率
 * @param chance        这一炉拿到产物的概率（1.0 必然）
 * @param mixture       产物形态：单一物质还是配分
 */
public record SmeltRecipe(
		SlurryPart phase,
		SmeltRoute route,
		String product,
		boolean ingot,
		double richFraction,
		double stoichiometry,
		double powerFePerTick,
		double recovery,
		double carryRate,
		double chance,
		Mixture mixture) {

	/**
	 * 产物是单一物质，还是要摊开成一份配分。
	 *
	 * <p>只有稀土用得上：稀土的产物不是「一种东西」，而是 1~3 种主稀土加 2~6 种副稀土
	 * 按丰度配出来的一份混合物（见 {@link com.geometallurgy.proto.RareEarth}）。
	 */
	public enum Mixture {
		/** 单一物质。 */
		NONE,
		/** 稀土配分。 */
		RARE_EARTH
	}

	/** 这种产物是不是金属（金属才受活动性顺序约束）。 */
	public boolean metalProduct() {
		return this.ingot && MetalSeries.isKnownMetal(this.product);
	}

	/** 这一炉要不要掷骰。 */
	public boolean conditional() {
		return this.chance < 1.0;
	}
}
