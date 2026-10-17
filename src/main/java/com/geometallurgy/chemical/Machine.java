package com.geometallurgy.chemical;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 能执行一条超边（化学反应或「矿 → 精矿」机器步骤）的机器。
 *
 * <p>本枚举是 {@link MolBalance.Reaction} 与 {@link ReactionGraph} 之间的属性：
 * 反应表只描述「能发生什么」，机器集描述「在哪台机器上发生」。
 *
 * <p>取值分两类：
 * <ul>
 *   <li><b>化学三机</b>（{@link #REACTION_VESSEL} / {@link #MIXER} / {@link #ELECTROLYSIS_FURNACE}）——
 *       化学反应表 {@link MolRecipes} 只允许这三台，按 {@link MolBalance.Kind} 取默认集；</li>
 *   <li><b>选矿机器</b>（{@link #VEIN_EXTRACTOR} / {@link #CRUSHER} / {@link #CENTRIFUGE} /
 *       {@link #MAGNETIC_SEPARATOR}）—— 只出现在 {@link ReactionGraph} 的桥接表里（矿 → 精矿 / 精矿 → 矿粉），
 *       <b>绝不</b>作为化学反应默认机器。</li>
 *   <li><b>冶金炉</b>（{@link #REDUCTION_FURNACE}）—— 同样只出现在桥接表里，是「化合物 → 金属锭」
 *       那一步的宿主（碳热 / 金属热还原）。它做的是 {@code metal/Metallurgy} 的路线，不是
 *       {@link MolRecipes} 的化学方程，所以也绝不做化学反应默认机器。熔盐电解不用新值，
 *       复用已经存在的 {@link #ELECTROLYSIS_FURNACE}。湿法置换炉目前没有任何桥接边用到
 *       （本图建模到的金属链都走还原 / 电解），所以先不进枚举 —— 要用时再加。</li>
 * </ul>
 */
public enum Machine {

	// ------------------------------------------------------------------ 化学三机（MolRecipes 用）
	/** 反应釜：热反应。 */
	REACTION_VESSEL,
	/** 搅拌器：热反应 + 必须搅拌才发生的反应。 */
	MIXER,
	/** 电解炉：只有电解型反应（溶液/熔盐通电）。 */
	ELECTROLYSIS_FURNACE,

	// ------------------------------------------------------------------ 矿 → 精矿 的机器步骤（桥接表用）
	/** 矿脉抽取机：从虚拟矿脉采出原矿。 */
	VEIN_EXTRACTOR,
	/** 粉碎机：矿浆磨成矿粉（只减粒度，不分离）。 */
	CRUSHER,
	/** 离心机：按密度分层（只能吃矿浆）。 */
	CENTRIFUGE,
	/** 磁力筛机：按磁性分成精矿 / 尾矿。 */
	MAGNETIC_SEPARATOR,

	// ------------------------------------------------------------------ 冶金炉（桥接表用）
	/**
	 * 还原炉：碳热还原 / 金属热还原，把氧化物夺氧成金属锭（{@code SmeltRoute#REDUCTION}）。
	 *
	 * <p>主要作桥接边「化合物 → 金属锭」的宿主标签；唯一进 {@link #defaultsFor(MolBalance.Kind)}
	 * 的地方是 {@link MolBalance.Kind#ROASTING}（高温焙烧 / 分解）—— 一台真正的热炉。
	 */
	REDUCTION_FURNACE;

	/**
	 * 按反应种类给出默认机器集：
	 *
	 * <ul>
	 *   <li>{@link MolBalance.Kind#THERMAL} → 反应釜 + 搅拌器（常温/加热就能走）；</li>
	 *   <li>{@link MolBalance.Kind#STIRRING} → 只有搅拌器（不搅拌不动）；</li>
	 *   <li>{@link MolBalance.Kind#ELECTROLYSIS} → 只有电解炉；</li>
 *   <li>{@link MolBalance.Kind#ROASTING} → 只有 {@link #REDUCTION_FURNACE}（高温焙烧 / 分解：
 *       挥发物必须持续离开，密闭反应釜里走不动）。</li>
	 * </ul>
	 */
	public static Set<Machine> defaultsFor(MolBalance.Kind kind) {
		return switch (kind) {
			case THERMAL -> Collections.unmodifiableSet(EnumSet.of(REACTION_VESSEL, MIXER));
			case STIRRING -> Collections.unmodifiableSet(EnumSet.of(MIXER));
			case ELECTROLYSIS -> Collections.unmodifiableSet(EnumSet.of(ELECTROLYSIS_FURNACE));
			// 高温焙烧 / 分解：只有还原炉这种真正的热炉收（反应釜 / 搅拌器 / 电解炉都不收）
			case ROASTING -> Collections.unmodifiableSet(EnumSet.of(REDUCTION_FURNACE));
		};
	}
}