package com.geometallurgy.slurry;

import net.minecraft.network.chat.Component;

/**
 * 能悬浮在虚拟矿浆里的一个「相」。
 *
 * <p>矿浆不是纯矿物液 —— 碎矿挖掘机啃下来的是<b>整块岩石</b>，磨碎后悬浮在液相里，
 * 因此罐内同时存在两类相：
 * <ul>
 *   <li><b>原岩相</b>（{@link com.geometallurgy.geology.RockType}）—— 占绝对多数，是矿脉的基体；</li>
 *   <li><b>矿物相</b>（{@link com.geometallurgy.proto.OreMineral}）—— 含量很低的目标矿物颗粒。</li>
 * </ul>
 *
 * <p>后续的磁筛机按磁性、离心机按密度分选，处理的正是这两类相之间的差别，
 * 所以它们在数据结构上必须平级共存，而不是「矿物里附带一点石头」。
 */
public interface SlurryPart {

	/** 全局唯一的相 id。 */
	String id();

	/** 显示用色 0xRRGGBB。 */
	int color();

	/** 译名。 */
	Component displayName();

	/** 是否是目标矿物相（原岩相返回 false）。 */
	boolean isMineral();
}
