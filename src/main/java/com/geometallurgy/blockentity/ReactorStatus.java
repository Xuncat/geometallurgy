package com.geometallurgy.blockentity;

/**
 * 反应釜 / 搅拌器的工作状态（界面与灯光的唯一来源）。
 */
public enum ReactorStatus {

	/** 混合槽空了，没料可反应。 */
	IDLE("idle", false),
	/** 有料，但没有任何一条反应够料。 */
	NO_REACTION("no_reaction", false),
	/** 有反应够料，但它必须搅拌才能发生 —— 换搅拌器。 */
	NEEDS_STIRRING("needs_stirring", false),
	/** 混合槽满了，产物出不去。接个输出面、放几只空桶。 */
	OUTPUT_FULL("output_full", false),
	/** 正在反应。 */
	RUNNING("running", true),
	/** 输入面的桶装的东西会侵蚀它自己的材质 —— 换一只耐蚀的桶。 */
	BUCKET_UNSAFE("bucket_unsafe", false);

	private final String key;
	private final boolean lit;

	ReactorStatus(String key, boolean lit) {
		this.key = key;
		this.lit = lit;
	}

	public String key() {
		return this.key;
	}

	public String translationKey() {
		return "gui.geometallurgy.reactor_status." + this.key;
	}

	/** 界面用：这一状态下机器是不是「在干活」（点亮 LIT）。 */
	public boolean lit() {
		return this.lit;
	}
}
