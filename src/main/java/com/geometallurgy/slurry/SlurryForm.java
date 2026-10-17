package com.geometallurgy.slurry;

import net.minecraft.network.chat.Component;

/**
 * 虚拟矿浆的<b>形态</b>——同一份料可以是粗颗粒悬浮液，也可以是磨细的粉料。
 *
 * <p>形态决定「谁能处理它」，这是本模组后半条产线的核心约束：
 * <ul>
 *   <li>{@link #SLURRY 矿浆} —— 碎矿挖掘机直接啃下来的东西：整块岩石磨成的粗颗粒悬浮在液相里。
 *       <b>离心机与磁筛机都能处理它</b>：离心机要靠液相让颗粒自由重排才能按密度分层。</li>
 *   <li>{@link #POWDER 矿粉} —— 粉碎机把矿浆磨细后的产物。颗粒细到液相已经带不住它们，
 *       只能<b>干式磁选</b>：<b>只有磁筛机吃得了</b>，离心机进去只会堵住（细粉无法按密度沉降分层）。</li>
 * </ul>
 *
 * <p>形态<b>不会</b>因为粉碎而变纯：粉碎机只减小粒度、不分离任何东西，所以混合料磨成粉之后
 * 照样是混合料，纯度一分不涨。代价发生在下游 —— 粉料进磁筛机时理想磁场窗口会显著收窄，
 * 磁场控制稍偏就分不开；后续提炼工序也会因为纯度低而多烧燃料、多耗还原剂。
 */
public enum SlurryForm {

	/** 粗颗粒悬浮液：挖掘机产出，离心机与磁筛机可处理。 */
	SLURRY("slurry"),
	/** 磨细的粉料：粉碎机产出，只有磁筛机（干式）可处理。 */
	POWDER("powder");

	private final String key;

	SlurryForm(String key) {
		this.key = key;
	}

	/** 语言文件的键后缀。 */
	public String key() {
		return this.key;
	}

	public String translationKey() {
		return "gui.geometallurgy.form_" + this.key;
	}

	public Component displayName() {
		return Component.translatable(translationKey());
	}
}
