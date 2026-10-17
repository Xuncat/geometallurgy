package com.geometallurgy.chemical;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.geometallurgy.chemical.Chemical.Phase;

/**
 * 一份 mol 账的<b>相态投影</b>（设计稿 §十 显示层，2026-10-15 定）。
 *
 * <p>底层 {@link MolStock} 永远<b>分成分、无损</b>记账（同分异构 / 溶质 / 溶剂各是独立条目，
 * 绝不合并）。本类只做<b>显示与分罐</b>这一层有损投影，回答两个问题：
 *
 * <ul>
 *   <li><b>怎么分装</b>（{@link #split}）：把一份账按 {@link Chemical#phaseOf 相态} 拆成
 *       气 / 液 / 固三份。气与液<b>不能同罐</b>（{@code NaOH 溶液}与{@code Cl₂ *}各进一罐），
 *       这正是电解产物要分两个储罐的原因。相态相同的合并进同一罐。</li>
 *   <li><b>怎么显示</b>（{@link #fractions}）：一罐（同相混合物）显示成「总量 + 各成分百分比」，
 *       例如 {@code 50% H₂O + 50% C₂H₅OC₂H₅}。账里 mol 数不变，只是 UI 摊开成百分比。</li>
 * </ul>
 *
 * <p>与「成分必须分离（无损）／显示可为混合物（有损）」这条原则一致：{@link #split} 与
 * {@link #fractions} 都<b>不改动传入的账</b>，只读出投影；真正存的仍是原始 {@link MolStock}。
 *
 * <p>未知 id 当 {@link Phase#SOLID}（与 {@link Chemical#phaseOf} 的兜底一致）。
 */
public final class PhaseSplit {

	private PhaseSplit() {
	}

	/** 一罐里的一个成分占比百分比投影。 */
	public record Fraction(String id, double mol, double fraction) {
	}

	/**
	 * 把一份账按相态拆成非空子账。key 是相态，value 是只含该相成员的新 {@link MolStock}。
	 *
	 * <p>拆分对 mol 守恒：各子账 {@link MolStock#totalMol} 之和 = 原账总 mol。
	 * 不产生空相条目（某相没有成员就不放进结果）。
	 */
	public static Map<Phase, MolStock> split(MolStock stock) {
		Map<Phase, MolStock> out = new EnumMap<>(Phase.class);
		if (stock == null) {
			return out;
		}
		for (Map.Entry<String, Double> e : stock.view().entrySet()) {
			Phase phase = Chemical.phaseOf(e.getKey());
			out.computeIfAbsent(phase, p -> MolStock.empty()).add(e.getKey(), e.getValue());
		}
		return out;
	}

	/** 这份账里有没有不止一种相态（决定产物要不要分罐）。 */
	public static boolean spansMultiplePhases(MolStock stock) {
		return split(stock).size() > 1;
	}

	/**
	 * 一份账（通常已是一罐同相混合物）摊成百分比列表，按 mol 降序。
	 *
	 * <p>{@code fraction} = 该成分 mol ÷ 总 mol，列表内各项之和 ≈ 1。空账返回空表。
	 */
	public static List<Fraction> fractions(MolStock stock) {
		List<Fraction> list = new ArrayList<>();
		if (stock == null || stock.isEmpty()) {
			return list;
		}
		double total = stock.totalMol();
		if (total <= 0.0) {
			return list;
		}
		for (Map.Entry<String, Double> e : stock.view().entrySet()) {
			list.add(new Fraction(e.getKey(), e.getValue(), e.getValue() / total));
		}
		list.sort((a, b) -> Double.compare(b.mol(), a.mol()));
		return list;
	}
}
