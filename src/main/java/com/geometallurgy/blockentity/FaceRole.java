package com.geometallurgy.blockentity;

/**
 * 磁力筛机六个面各自扮演的角色。
 *
 * <p>角色按<b>世界方向</b>记，不随方块朝向旋转 —— 界面上直接写「上 / 下 / 北 / 南 / 东 / 西」，
 * 玩家抬头就能对上，不必先弄清哪面是「机器的正面」。
 *
 * <p>序号被压在 2 个 bit 里随界面同步（见 {@code MagneticSeparatorBlockEntity}），
 * 所以顺序不要随意改动；{@link #next()} 是界面上点击时用的循环顺序。
 */
public enum FaceRole {

	/** 进料口：上游机器的矿浆从这里被抽进来。 */
	INPUT("input"),
	/** 磁性产物口：精矿（被磁场吸住的那一份）从这里出去。 */
	MAGNETIC("magnetic"),
	/** 非磁性产物口：尾矿从这里出去；若开了「直接销毁」则这一面不出料。 */
	NON_MAGNETIC("non_magnetic"),
	/** 关闭：这一面什么都不接。 */
	DISABLED("disabled");

	private final String key;

	FaceRole(String key) {
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
	public FaceRole next() {
		FaceRole[] values = values();
		return values[(this.ordinal() + 1) % values.length];
	}
}
