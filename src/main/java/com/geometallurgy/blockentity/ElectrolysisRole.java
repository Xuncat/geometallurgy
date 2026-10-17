package com.geometallurgy.blockentity;

/**
 * 电解炉的六面角色。
 *
 * <p>与共用的 {@link PortRole} 分开，原因是这台炉子有两档、账不止一本：
 * <ul>
 *   <li><b>溶液档</b>（盐水等）：三本 mol 账 —— 溶液、阳极容器、阴极容器。三本账能进的进、
 *       能出的出各不相同，用四值的 {@code PortRole} 表达不了。</li>
 *   <li><b>熔盐档</b>（金属氧化物粉进 → 熔融金属 + 渣出）：仍是矿粉进 / 渣出两件事，
 *       但走的是本机自己的面角色，不能借 {@link PortRole}（见 {@link FaceConfig#pack}
 *       的类型校验）。</li>
 * </ul>
 *
 * <p>七个取值 → 3 bit，六面共 18 bit，一个 16 位 {@code ContainerData} 槽塞不下，
 * 所以与 {@link AbstractReactorBlockEntity} 一样拆成两个槽同步（低三面一个、高三面一个，
 * 见 {@code SmelterBlockEntity.ROLES_B_INDEX}）。不能和 {@link PortRole} / {@link MixerRole}
 * 混用，粘贴前靠 {@link FaceConfigurable#faceConfigKind()} 拦住（见 {@link FaceConfig}）。
 */
public enum ElectrolysisRole {

	/** 关闭：这一面什么都不接。 */
	DISABLED("disabled"),
	/** 溶液入口：从相邻 {@code MolTank} 抽溶液进溶液账。 */
	SOLUTION_IN("solution_in"),
	/** 溶液出口：只把溶液账里非电极归属的产物推出去（气 / 固走各自的电极面）。 */
	SOLUTION_OUT("solution_out"),
	/** 阳极容器出口：把阳极账整个推给相邻 {@code MolTank}。 */
	ANODE_OUT("anode_out"),
	/** 阴极容器出口：把阴极账整个推给相邻 {@code MolTank}。 */
	CATHODE_OUT("cathode_out"),
	/** 矿粉入口：熔盐档从相邻 {@code SlurryProvider} 抽矿粉进熔盐料罐。 */
	POWDER_IN("powder_in"),
	/** 渣出口：熔盐档把渣罐推给相邻 {@code SlurryProvider}。 */
	SLAG_OUT("slag_out");

	private final String key;

	ElectrolysisRole(String key) {
		this.key = key;
	}

	/** 语言文件的键后缀。 */
	public String key() {
		return this.key;
	}

	public String translationKey() {
		return "gui.geometallurgy.electro_face_" + this.key;
	}

	/** 界面上点击一次之后变成的角色。 */
	public ElectrolysisRole next() {
		ElectrolysisRole[] values = values();
		return values[(this.ordinal() + 1) % values.length];
	}
}
