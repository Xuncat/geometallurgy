package com.geometallurgy.blockentity;

/**
 * 机器的六个面各自接什么。
 *
 * <p>与磁筛机的 {@link FaceRole} 是同一套思路（角色按<b>世界方向</b>记、不随方块朝向旋转），
 * 但取值更少：粉碎机与离心机都只有「进料 / 主产物 / 副产物」三种角色，没有磁筛机那种
 * 「磁性出 / 非磁性出」的专属语义。四值刚好占 2 个 bit，与 {@link FaceRole} 一样能压进一个 int
 * 随界面同步。
 *
 * <p>各机器对 {@link #OUTPUT} 与 {@link #BYPRODUCT} 的解释：
 * <ul>
 *   <li>粉碎机 —— {@code OUTPUT} 是磨出来的矿粉；副产物闲置（粉碎不分离任何东西）。</li>
 *   <li>离心机 —— {@code OUTPUT} 是重相（密度高于切点），{@code BYPRODUCT} 是轻相。</li>
 * </ul>
 */
public enum PortRole {

	/** 关闭：这一面什么都不接。 */
	DISABLED("disabled"),
	/** 进料口。 */
	INPUT("input"),
	/** 主产物口。 */
	OUTPUT("output"),
	/** 副产物口。 */
	BYPRODUCT("byproduct");

	private final String key;

	PortRole(String key) {
		this.key = key;
	}

	/** 语言文件的键后缀。 */
	public String key() {
		return this.key;
	}

	public String translationKey() {
		return "gui.geometallurgy.face_" + this.key;
	}

	/** 界面上点击一次之后变成的角色。 */
	public PortRole next() {
		PortRole[] values = values();
		return values[(this.ordinal() + 1) % values.length];
	}
}
