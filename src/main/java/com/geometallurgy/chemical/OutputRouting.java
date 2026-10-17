package com.geometallurgy.chemical;

import java.util.Map;

/**
 * 出料相态的唯一决策实现（{@code AbstractReactorBlockEntity.pushFaces} 调它）。
 *
 * <p><b>为什么单独一个类</b>：这段算法只碰 {@link MolStock} 与 {@link Chemical}，
 * 不依赖任何 Minecraft 类型，于是可以在 JVM 里离线复算 —— BossEntity 起不来，
 * 但「哪个相该出、哪一相堵住、堵了多少」是纯函数，必须能直接验证。这样机器侧只剩一次转发。
 *
 * <p><b>用户口径（docs/科技线-v1.md §13.1，用户 2026-10-16 截图）</b>：一个面标成「出气」
 * 就永远推不走槽里 91% 的液体，槽满之后整机冻在 {@code OUTPUT_FULL}。规则分两条：
 * <ol>
 *   <li><b>按相出料仍优先</b>：这一面配的相态在账里确实有料（≥ {@code minMol}）时，
 *       只推那一相 —— 玩家故意把液、气、渣分到三个面上的路由不被破坏；</li>
 *   <li><b>空相回退</b>：这一面配的相态在账里几乎没有（&lt; {@code minMol}）时，
 *       改推<b>账里实际最多</b>的那一相。空白面因此永远不会把整机堵死。</li>
 * </ol>
 *
 * <p>{@link #blocked} 给出「堵住的是哪一相、有多少」—— 它就是状态栏那句
 * 「出料堵住：液体 82.4 mol 推不出去」的数据来源。
 */
public final class OutputRouting {

	/** 低于此值当作 0（与机器侧的 {@code MIN_MOL} 同量级）。 */
	public static final double MIN_MOL = 1.0e-3;

	private OutputRouting() {
	}

	/**
	 * 槽还有没有余量接着收料：余量 ≤ {@code minMol} 就是满槽，返回 false。
	 *
	 * <p>满槽时输入面必须彻底停止吸料（既不让邻罐过户、也不倒桶），否则会一边报
	 * 「出料堵住」一边继续往输入缓冲里灌 —— 见 docs/科技线-v1.md §13.1 与 bug#23。
	 */
	public static boolean hasRoom(double totalMol, double capacity, double minMol) {
		// 留 1e-9 的绝对容差：容量与总 mol 都是浮点累加，边界处「余量恰好 == minMol」
		// 会因舍入变成 0.001000000000019，差这一点点不该被当成还有空间。
		return capacity - totalMol - minMol > 1.0e-9;
	}

	/** 账里某一相的 mol 之和。 */
	public static double phaseMol(MolStock stock, Chemical.Phase phase) {
		if (stock == null || phase == null) {
			return 0.0;
		}
		double sum = 0.0;
		for (Map.Entry<String, Double> entry : stock.view().entrySet()) {
			if (Chemical.phaseOf(entry.getKey()) == phase) {
				sum += entry.getValue();
			}
		}
		return sum;
	}

	/** 账里 mol 最多的相；账里没有任何一相达到 {@code minMol} 时返回 {@code null}。 */
	public static Chemical.Phase largestPhase(MolStock stock, double minMol) {
		Chemical.Phase best = null;
		double bestMol = minMol;
		for (Chemical.Phase phase : Chemical.Phase.values()) {
			double mol = phaseMol(stock, phase);
			if (mol > bestMol) {
				bestMol = mol;
				best = phase;
			}
		}
		return best;
	}

	/** 账里 mol 最多那一相的 mol；没有达到 {@code minMol} 的相返回 0。 */
	public static double largestPhaseMol(MolStock stock, double minMol) {
		double best = 0.0;
		for (Chemical.Phase phase : Chemical.Phase.values()) {
			double mol = phaseMol(stock, phase);
			if (mol > best) {
				best = mol;
			}
		}
		return best >= minMol ? best : 0.0;
	}

	/**
	 * 这一面实际该推的相。
	 *
	 * @param configured 面角色配的相态（出料面不会为 null；为 null 时直接走回退）
	 * @return 配的相有料时就是它，否则是账里最多的一相；账里没料返回 {@code null}
	 */
	public static Chemical.Phase effectivePhase(MolStock stock, Chemical.Phase configured, double minMol) {
		if (configured != null && phaseMol(stock, configured) >= minMol) {
			return configured;
		}
		return largestPhase(stock, minMol);
	}

	/** 回退：把一组出料面里「空相」的那些挑出来，统一改推账里最多的一相（新集合，不动原集合）。 */
	public static boolean isFallback(MolStock stock, Chemical.Phase configured, double minMol) {
		return configured == null || phaseMol(stock, configured) < minMol;
	}

	/**
	 * 堵住的是哪一相、堵了多少。
	 *
	 * <p>槽满时「出不去」的就是账里占比最大的那一相 —— 就算别的面还能顺带推走另外的小相，
	 * 这一相只要没有对应出口就永远出不去，机器也就一直冻着。没有达到 {@code minMol} 的相
	 * 返回 {@link Blocked#none()}.
	 */
	public static Blocked blocked(MolStock stock, double minMol) {
		Chemical.Phase best = null;
		double bestMol = 0.0;
		if (stock != null) {
			for (Chemical.Phase phase : Chemical.Phase.values()) {
				double mol = phaseMol(stock, phase);
				if (mol > bestMol) {
					bestMol = mol;
					best = phase;
				}
			}
		}
		if (best == null || bestMol < minMol) {
			return Blocked.none();
		}
		return new Blocked(best, bestMol);
	}

	/** 同步进 {@code ContainerData} 的相态序号：0 = 无，其余是 {@code Phase.ordinal() + 1}。 */
	public static int ordinal(Chemical.Phase phase) {
		return phase == null ? 0 : phase.ordinal() + 1;
	}

	/** {@link #ordinal} 的反解；越界返回 {@code null}。 */
	public static Chemical.Phase phaseAt(int ordinal) {
		Chemical.Phase[] values = Chemical.Phase.values();
		return ordinal >= 1 && ordinal <= values.length ? values[ordinal - 1] : null;
	}

	/** 「哪一相堵住、堵了多少」的值对象；没有堵住时 {@link #phase()} 为 null、{@link #mol()} 为 0。 */
	public record Blocked(Chemical.Phase phase, double mol) {

		public static Blocked none() {
			return new Blocked(null, 0.0);
		}

		public boolean blocked() {
			return this.phase != null;
		}
	}
}
