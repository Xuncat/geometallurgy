package com.geometallurgy.blockentity;

import com.geometallurgy.chemical.Chemical;

/**
 * 反应釜的六面角色。
 *
 * <p>与 {@link PortRole} 分开：`PortRole` 被粉碎机 / 离心机 / 冶炼炉共用，那里的「主产物 / 副产物」
 * 各有机器专属含义；反应釜要的是「这一面出哪一相」——相态跟着面走，所以单独一套。
 *
 * <p>六个取值 → 3 bit，与 {@link MixerRole} 一样能压进一个 int 同步（六面共 18 bit，
 * 拆成两个短整型槽传，见 {@code AbstractReactorBlockEntity.D_ROLES}）。
 */
public enum VesselRole {

	/** 关闭。 */
	DISABLED("disabled", null),
	/** 进料 A：从这一面收桶 / 收储罐的料，进第一本输入缓冲。 */
	INPUT("input", null),
	/** 进料 B：第二本输入缓冲，给需要两种原料分别计量的反应。 */
	INPUT_B("input_b", null),
	/** 出液：把混合槽里的液体装桶推出。 */
	OUT_LIQUID("out_liquid", Chemical.Phase.LIQUID),
	/** 出气。 */
	OUT_GAS("out_gas", Chemical.Phase.GAS),
	/** 出渣（沉淀）。 */
	OUT_SOLID("out_solid", Chemical.Phase.SOLID);

	private final String key;
	private final Chemical.Phase phase;

	VesselRole(String key, Chemical.Phase phase) {
		this.key = key;
		this.phase = phase;
	}

	public String key() {
		return this.key;
	}

	/** 出料面出的是哪一相；非出料面返回 {@code null}。 */
	public Chemical.Phase phase() {
		return this.phase;
	}

	public String translationKey() {
		return "gui.geometallurgy.vessel_face_" + this.key;
	}

	/** 界面上点一次之后变成的角色。 */
	public VesselRole next() {
		VesselRole[] values = values();
		return values[(this.ordinal() + 1) % values.length];
	}
}
