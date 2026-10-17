package com.geometallurgy.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.geometallurgy.chemical.Chemical;
import com.geometallurgy.chemical.MolStock;
import com.geometallurgy.chemical.PhaseSplit;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * 「按相态分组的 mol 读数」：把一份 {@link MolStock} 读成
 * <b>气 / 液 / 固三行</b>，每行是同一相内各物质的混合物。
 *
 * <p>设计稿 §4.1（2026-10-16 用户口径）：容器内部的 mol 内容要标注状态 ——
 * 气液固必须分开形成混合物。落地形状：
 *
 * <pre>
 * 气体：H2 12.0 mol · O2 3.0 mol
 * 液体：H2O 5.0 mol · NaOH 2.0 mol
 * 固体：—
 * </pre>
 *
 * <p>拆分本身<b>复用</b> {@link PhaseSplit#split}，本类只负责把每相拼成一行文本。
 * 相名复用<b>已有</b>语言键 {@code gui.geometallurgy.phase_gas / phase_liquid / phase_solid}；
 * 分隔符「：」「 · 」与空相占位「—」是标点，直接写在 Java 侧，<b>不新增语言键</b>。
 * 单条物质沿用调用方给的现有条目键（桶是 {@code gui.geometallurgy.bucket.entry}，
 * 储罐是 {@code gui.geometallurgy.barrel.content_entry}，都是 {@code %s %s mol}）。
 */
public final class MolReadout {

	/** 空相占位。 */
	private static final String EMPTY = "—";
	/** 相名与内容之间的全角冒号。 */
	private static final String PHASE_SEPARATOR = "：";
	/** 同相内两种物质之间的分隔。 */
	private static final String ITEM_SEPARATOR = " · ";

	private MolReadout() {
	}

	/** 相态 → 现有语言键。 */
	public static String phaseKey(Chemical.Phase phase) {
		return switch (phase) {
			case GAS -> "gui.geometallurgy.phase_gas";
			case LIQUID -> "gui.geometallurgy.phase_liquid";
			case SOLID -> "gui.geometallurgy.phase_solid";
		};
	}

	/**
	 * 一份账按相态读成三行，顺序固定为气 / 液 / 固；某相没有成员时写「—」。
	 *
	 * @param entryKey 单条物质那一行的语言键（{@code %s %s mol} 形状）
	 */
	public static List<MutableComponent> lines(MolStock stock, String entryKey) {
		Map<Chemical.Phase, MolStock> split = PhaseSplit.split(stock);
		Chemical.Phase[] phases = Chemical.Phase.values();
		List<MutableComponent> out = new ArrayList<>(phases.length);
		for (Chemical.Phase phase : phases) {
			out.add(line(phase, split.get(phase), entryKey));
		}
		return out;
	}

	private static MutableComponent line(Chemical.Phase phase, MolStock group, String entryKey) {
		MutableComponent line = Component.empty()
				.append(Component.translatable(phaseKey(phase)))
				.append(Component.literal(PHASE_SEPARATOR));
		if (group == null || group.isEmpty()) {
			return line.append(Component.literal(EMPTY));
		}
		boolean first = true;
		for (Map.Entry<String, Double> e : group.view().entrySet()) {
			if (!first) {
				line.append(Component.literal(ITEM_SEPARATOR));
			}
			first = false;
			line.append(Component.translatable(entryKey,
					Component.translatable("chemical.geometallurgy." + e.getKey()),
					amount(e.getValue())));
		}
		return line;
	}

	/** 显示数字：一位小数（与设计稿样例的 {@code 12.0 mol} 对齐）。 */
	private static String amount(double v) {
		return String.format(Locale.ROOT, "%.1f", v);
	}
}
