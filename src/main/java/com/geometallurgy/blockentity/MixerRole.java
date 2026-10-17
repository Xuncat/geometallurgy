package com.geometallurgy.blockentity;

import com.geometallurgy.chemical.Chemical;

/**
 * 搅拌器的六面角色。
 *
 * <p>搅拌器有两个流体入口（A / B），出料口按<b>相态</b>细分 —— 相态跟着面走：
 * 三个出料面可以分别出液、出气、出渣，不必全机器共用一个「输出相」旋钮。
 *
 * <p>六个取值 → 3 bit，六面共 18 bit；与 {@link VesselRole} 一样不能和
 * {@link PortRole} 混用，粘贴前靠 {@link FaceConfigurable#faceConfigKind()} 拦住（见 {@link FaceConfig}）。
 */
public enum MixerRole {

	/** 关闭。 */
	DISABLED("disabled", null),
	/** A 液入口。 */
	INPUT_A("input_a", null),
	/** B 液入口。 */
	INPUT_B("input_b", null),
	/** 出液。 */
	OUT_LIQUID("out_liquid", Chemical.Phase.LIQUID),
	/** 出气。 */
	OUT_GAS("out_gas", Chemical.Phase.GAS),
	/** 出渣（沉淀）。 */
	OUT_SOLID("out_solid", Chemical.Phase.SOLID);

	private final String key;
	private final Chemical.Phase phase;

	MixerRole(String key, Chemical.Phase phase) {
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
		return "gui.geometallurgy.mixer_face_" + this.key;
	}

	/** 界面上点一次之后变成的角色。 */
	public MixerRole next() {
		MixerRole[] values = values();
		return values[(this.ordinal() + 1) % values.length];
	}
}
