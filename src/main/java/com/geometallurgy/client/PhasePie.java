package com.geometallurgy.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.geometallurgy.chemical.Chemical;
import com.geometallurgy.chemical.MolStock;
import com.geometallurgy.chemical.PhaseSplit;
import com.geometallurgy.item.MolReadout;

import net.minecraft.network.chat.Component;

/**
 * 把一份「物质 id + 占比」的成分表按<b>相态</b>并成饼图扇区（设计稿 §4.1）。
 *
 * <p>每个相只占<b>一块</b>扇形，相内各物质放进 {@link SlurryPie.Slice#children()}——
 * 浮层会自动把它们缩进列在相名底下，这就是「气液固分开形成混合物」。
 *
 * <p>相色取该相里占比最大那种物质的 {@link Chemical#colorOf(String)}（也可换成固定相色）；
 * 拆分本身复用 {@link PhaseSplit#split}，不另写一份。
 *
 * <p>只接受已知化学物质；{@code null}（界面里的「其他」兜底项）应由调用方另行保留，
 * 因为它在相态上不可归属。
 */
public final class PhasePie {

	/** 占比（0~1）放大到 mol 的倍数，只为复用 {@link MolStock} / {@link PhaseSplit}。 */
	private static final double PERMILLE = 1000.0;

	private PhasePie() {
	}

	/** 一项「物质 id + 占比」。 */
	public record Part(String id, double fraction) {
	}

	/**
	 * 按相态并扇区，顺序固定为气 / 液 / 固；某相没有成员就不出扇区。
	 *
	 * @param parts 已知化学物质的成分表；{@code id} 不得为 {@code null}
	 */
	public static List<SlurryPie.Slice> slices(List<Part> parts) {
		MolStock stock = MolStock.empty();
		for (Part part : parts) {
			if (part.id() != null && part.fraction() > 0.0) {
				stock.add(part.id(), part.fraction() * PERMILLE);
			}
		}
		Map<Chemical.Phase, MolStock> split = PhaseSplit.split(stock);
		List<SlurryPie.Slice> out = new ArrayList<>();
		for (Chemical.Phase phase : Chemical.Phase.values()) {
			MolStock group = split.get(phase);
			if (group != null && !group.isEmpty()) {
				out.add(slice(phase, group));
			}
		}
		return out;
	}

	private static SlurryPie.Slice slice(Chemical.Phase phase, MolStock group) {
		double total = group.totalMol();
		List<SlurryPie.Slice> children = new ArrayList<>();
		String dominant = null;
		double best = -1.0;
		for (Map.Entry<String, Double> e : group.view().entrySet()) {
			children.add(new SlurryPie.Slice(0xFF000000 | Chemical.colorOf(e.getKey()),
					Component.translatable("chemical.geometallurgy." + e.getKey()),
					e.getValue() / total, List.of()));
			if (e.getValue() > best) {
				best = e.getValue();
				dominant = e.getKey();
			}
		}
		children.sort(Comparator.comparingDouble(SlurryPie.Slice::fraction).reversed());
		int color = dominant == null ? 0xFF8B8B8B : 0xFF000000 | Chemical.colorOf(dominant);
		return new SlurryPie.Slice(color, Component.translatable(MolReadout.phaseKey(phase)),
				total / PERMILLE, children);
	}
}
