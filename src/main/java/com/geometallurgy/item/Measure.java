package com.geometallurgy.item;

/**
 * 载具/物品的<b>计量单位标签</b>（设计稿 §六.7 的单位路由机制）。
 *
 * <p>把「这台机器收哪一套单位」从「物品类型隐含」提升为「显式组件标签」：
 * 任何载具在制造时就打好自己的单位，机器（反应釜、将来的电解炉/置换炉）读
 * {@link ModComponents#MEASURE} 决定接收 / 拒收 —— 不再硬编码认某一种物品。
 *
 * <p>三值：
 * <ul>
 *   <li>{@link #MOL} —— mol 账本（{@code MolStock}），本模组 mol 体系的本征单位。</li>
 *   <li>{@link #MILLIBUCKET} —— 体积（mB），矿浆 / 未来体积流体的记账。</li>
 *   <li>{@link #BUCKET} —— B（1 B = 1000 mB），纯显示名，不查物性。</li>
 * </ul>
 *
 * <p>判定习惯：机器用 {@link #isVolume()} 把 mB/B 归成一类「体积系」拒绝，
 * 或直接用 {@link #isMol()} 只认 mol。
 */
public enum Measure {

	/** mol 账本：唯一本征记账量，与相态无关。 */
	MOL("mol"),
	/** 体积（毫桶）：矿浆等体积系流体的记账。 */
	MILLIBUCKET("mb"),
	/** B（桶）：1 B = 1000 mB 的显示名，不查物性。 */
	BUCKET("b");

	private final String key;

	Measure(String key) {
		this.key = key;
	}

	/** 序列化键（存档 / 网络用）。 */
	public String key() {
		return this.key;
	}

	/** 是不是 mol 系。 */
	public boolean isMol() {
		return this == MOL;
	}

	/** 是不是体积系（mB 或 B）。 */
	public boolean isVolume() {
		return this == MILLIBUCKET || this == BUCKET;
	}

	/** 按序列化键反查；不认识返回 null。 */
	public static Measure fromKey(String key) {
		for (Measure m : values()) {
			if (m.key.equals(key)) {
				return m;
			}
		}
		return null;
	}
}