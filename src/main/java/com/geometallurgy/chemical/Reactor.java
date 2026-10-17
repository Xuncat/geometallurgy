package com.geometallurgy.chemical;

import java.util.List;

/**
 * 反应釜 / 搅拌器共用的<b>反应循环</b>。
 *
 * <p>每 tick 把 {@link MolRecipes#all()} 从头到尾过一遍：按<b>限制试剂</b>决定每条反应推进多少份，
 * 允许<b>分数份</b>（不足一整条方程的余料也按比例反应）；产物直接落回同一本账（混合 mol 槽），
 * 于是下一 tick 的匹配就看到了上一 tick 的产物 ——「产物在下一 tick 继续反应」这条规则就在这里落地。
 *
 * <p>与旧反应釜的区别是<b>不再挑第一条命中的反应</b>：同一时刻所有满足条件的反应都会发生，
 * 谁先谁后只影响这一 tick 内谁先拿料，不影响最终能不能反应。
 *
 * <p>需要搅拌的反应（{@code requiresStirring}）在 {@code stirred=false} 时被跳过，
 * 并被记进 {@link Outcome#blockedByStirring()}，让反应釜能明确告诉玩家「这釜子搅不动」。
 *
 * <h2>每反应速率（动力学）</h2>
 * 每条 {@link MolBalance.Reaction} 自带 {@link MolBalance.Reaction#rate()}（份/tick）。
 * 机器传进来的 {@code ratePerTick} 是这台机器的<b>吞吐上限</b>（份/tick，一 tick 最多跑几条方程），
 * 反应速率按比例缩放它，再乘 {@link #rateMultiplier} 的修正：
 * <pre>
 *   有效推进上限 = ratePerTick × reaction.rate() × machineFactor × modifiers
 *   实际推进     = min(限制试剂, 有效推进上限, 产物容量余量)
 * </pre>
 * 于是「一条快反应」与「一条慢反应」在同一台机器上会拉开差距，多步链条里的中间体才有机会攒起来，
 * 而不是被一 tick 一份的速度直接推到底。
 */
public final class Reactor {

	private Reactor() {
	}

	/**
	 * 一轮反应的结果。
	 *
	 * <ul>
	 *   <li>{@code reactionsRun}：本轮推进了的反应<b>条数</b>（一条反应哪怕只推进 0.001 份也算 1 条）；</li>
	 *   <li>{@code blockedByStirring}：够料但没搅拌而被挡下的条数；</li>
	 *   <li>{@code molReacted}：本轮的反应吞吐，按正向方程的反应物 mol 计（含溶剂）——
	 *       正向即消耗量，逆向（可逆反应倒推）折算成同一口径；</li>
	 *   <li>{@code progress}：本轮各条反应推进<b>份数</b>之和（分数，整数倍推进时等于 reactionsRun）；</li>
	 *   <li>{@code feCost}：本轮的电费（FE）—— <b>逐反应</b>按
	 *       {@link MolBalance.Reaction#fePerMol()} × 该反应实际消耗的反应物 mol 累加。
	 *       容器型机器（反应釜 / 搅拌器）不设预算时恒为 0；只有电解炉溶液档会拿到非零值。</li>
	 * </ul>
	 */
	public record Outcome(int reactionsRun, int blockedByStirring, double molReacted, double progress,
			double feCost) {

		/** 兼容旧的两参构造（不需要 mol 计数的地方）。 */
		public Outcome(int reactionsRun, int blockedByStirring) {
			this(reactionsRun, blockedByStirring, 0.0, reactionsRun, 0.0);
		}

		/** 兼容旧的三参构造（无分数进度时，假设每条反应推进整数份）。 */
		public Outcome(int reactionsRun, int blockedByStirring, double molReacted) {
			this(reactionsRun, blockedByStirring, molReacted, reactionsRun, 0.0);
		}
	}

	/**
	 * 反应釜 / 搅拌器允许的反应种类：热反应 + 搅拌反应（搅拌反应仍受 {@code stirred} 约束）。
	 *
	 * <p>{@link MolBalance.Kind#ROASTING}（高温焙烧 / 分解）<b>不在列</b>：密闭釜里挥发物排不走，
	 * 这类反应在常温容器中不成立（bug#36）。
	 */
	public static final java.util.Set<MolBalance.Kind> VESSEL_KINDS =
			java.util.EnumSet.of(MolBalance.Kind.THERMAL, MolBalance.Kind.STIRRING);

	/**
	 * 电解炉溶液档允许的反应种类。
	 *
	 * <p>同样不含 {@link MolBalance.Kind#ROASTING}：溶液电解只跑 {@code ELECTROLYSIS}。
	 */
	public static final java.util.Set<MolBalance.Kind> ELECTROLYSIS_KINDS =
			java.util.EnumSet.of(MolBalance.Kind.ELECTROLYSIS);

	/**
	 * 产物的分流口：{@link MolBalance.Side} 不是 {@link MolBalance.Side#NONE} 的产物
	 * 不落回 tank，而是交给它送到对应电极容器。
	 */
	@FunctionalInterface
	public interface ProductSink {
		void accept(MolBalance.Product product, double mol);
	}

	/**
	 * 跑一轮反应。
	 *
	 * @param tank        混合 mol 槽（就地修改）
	 * @param ratePerTick 机器的吞吐上限（<b>份</b>/tick，不是 mol；1.0 = 一 tick 最多一整条方程）——
	 *                    反应的 {@link MolBalance.Reaction#rate()} 在它之上再按比例缩放
	 * @param stirred     当前是否在搅拌
	 * @param catalysts   催化剂槽里的 id
	 * @param allowed     本机允许发生的反应种类（见 {@link MolBalance.Kind}）
	 */
	public static Outcome runAll(MolStock tank, double ratePerTick, boolean stirred, List<String> catalysts,
			java.util.Set<MolBalance.Kind> allowed) {
		return runAll(tank, ratePerTick, stirred, catalysts, allowed, null, 0.0);
	}

	/**
	 * 跑一轮反应，并把带电极归属的产物分流出去（电解炉用）。
	 *
	 * @param routed {@code Side != NONE} 的产物出口；{@code null} 表示全部落回 tank
	 */
	public static Outcome runAll(MolStock tank, double ratePerTick, boolean stirred, List<String> catalysts,
			java.util.Set<MolBalance.Kind> allowed, ProductSink routed) {
		return runAll(tank, ratePerTick, stirred, catalysts, allowed, routed, 0.0);
	}

	/**
	 * 跑一轮反应（分数份推进 + 产物容量约束）。
	 *
	 * @param productCapacity tank 的总 mol 容量；{@code <=0} 表示不检查
	 */
	public static Outcome runAll(MolStock tank, double ratePerTick, boolean stirred, List<String> catalysts,
			java.util.Set<MolBalance.Kind> allowed, ProductSink routed, double productCapacity) {
		return runAll(tank, ratePerTick, stirred, catalysts, allowed, routed, productCapacity, 1.0);
	}

	/**
	 * 跑一轮反应（分数份推进 + 产物容量约束 + 每反应速率 + 整机系数）。
	 *
	 * <p>每条反应的最大推进份数取三者最小：
	 * <ol>
	 *   <li><b>限制试剂</b>：{@link MolBalance#limiting} —— 允许不足一整份（如 0.75 份）；</li>
	 *   <li><b>机器吞吐 × 反应速率 × 修正</b>：
	 *       {@code ratePerTick × reaction.rate() × machineFactor × modifiers}。
	 *       {@code ratePerTick} 是机器的吞吐<b>上限</b>（份/tick），反应自己的
	 *       {@link MolBalance.Reaction#rate()}（份/tick）按比例缩放它；修正见 {@link #rateMultiplier}。
	 *       反应表的每条速率都满足 {@code rate × modifiers ≤ 1}，所以吞吐上限始终压得住；</li>
	 *   <li><b>{@code productCapacity}</b>：产物若净增会让 tank 总量越过容量，就按余量压小
	 *       （只算落回 tank 的产物，{@code routed} 接走的电极产物不算；{@code <=0} 表示不设限）。</li>
	 * </ol>
	 * 结果再向下量化到千分位（{@link MolBalance#quantize}）。量化后低于
	 * {@link MolBalance#MIN_PROGRESS} 时，只有「料够、容量够、纯粹是速率太低」这一种情形会被
	 * {@link #slowFloorShouldLift} 抬到最小量子 —— 极慢反应因此不会永远卡在阈值下方；
	 * 容量不够或料不够则照旧跳过，不空转、不越界。
	 *
	 * <p><b>可逆反应</b>（{@link MolBalance.Reaction#reversible()}）：先算反应商代理
	 * {@code Q}（{@link MolBalance#equilibriumQuotient}），与 {@code K} 比 —— {@code Q < K}
	 * 走正向、{@code Q > K} 走逆向（{@link #applyReverse}），{@code |Q-K| <= 5%·K} 进死区
	 * 不动。方向定了以后仍受上面三条上限约束，另外再夹一个「只推进到平衡点、不越过 K」
	 * 的上限（{@link MolBalance#progressToEquilibrium}），避免每 tick 整份推进在平衡点
	 * 两侧来回震荡。不可逆反应与旧行为逐 bit 一致。分流（{@code routed != null}）不做可逆。
	 *
	 * @param machineFactor 整机速率系数（当前三台机器都传 1.0；留作「某类机器整体更快」的口）
	 */
	public static Outcome runAll(MolStock tank, double ratePerTick, boolean stirred, List<String> catalysts,
			java.util.Set<MolBalance.Kind> allowed, ProductSink routed, double productCapacity,
			double machineFactor) {
		return runAll(tank, ratePerTick, stirred, catalysts, allowed, routed, productCapacity,
				machineFactor, 0.0);
	}

	/**
	 * 跑一轮反应（再增一个<b>反应物 mol 预算</b>上限）。
	 *
	 * <p>预算用来把「反应进度」卡在这台机器这一刻<b>买得起</b>的量以内。2026-10-17 起这个预算是
	 * <b>电预算（FE）</b>而不是 mol 预算：每条反应有自己的电解单价
	 * （{@link MolBalance.Reaction#fePerMol()}，FE / mol 反应物，见 {@code MolRecipes}），
	 * 所以「买得起几份」= 剩余电费 / 该反应每份的电费。旧的平铺常量
	 * {@code SmelterBlockEntity.SOLUTION_FE_PER_MOL} 已删除。
	 *
	 * <p>它和 {@code productCapacity} 一样是「按余量压缩推进份数」，属于 bug#19 的分数份推进设计：
	 * 不是拒绝整条反应，而是推进到预算刚好用完为止，低于 {@code MolBalance.MIN_PROGRESS} 就跳过、
	 * 不空转。预算与产物容量取小：谁先见底谁说了算。
	 *
	 * <p>{@code feBudget <= 0} 表示不设预算（容器型机器走旧重载，行为逐 bit 不变；
	 * 此时 {@link Outcome#feCost()} 恒为 0）。
	 *
	 * @param feBudget 本轮允许花掉的电费上限（FE），按每条反应自己的单价折算成 mol；{@code <= 0} 表示不限
	 */
	public static Outcome runAll(MolStock tank, double ratePerTick, boolean stirred, List<String> catalysts,
			java.util.Set<MolBalance.Kind> allowed, ProductSink routed, double productCapacity,
			double machineFactor, double feBudget) {
		List<MolBalance.Reaction> recipes = MolRecipes.all();
		int ran = 0;
		int blocked = 0;
		double mol = 0.0;
		double progressTotal = 0.0;
		double feTotal = 0.0;
		double budgetLeft = feBudget > 0.0 ? feBudget : Double.POSITIVE_INFINITY;
		for (MolBalance.Reaction r : recipes) {
			if (!allowed.contains(r.kind())) {
				continue;
			}
			// 可逆反应：先按反应商代理 Q 与平衡比 K 决定方向；Q 在死区内就不动，避免震荡。
			// 电极分流那条路（routed != null）不做可逆 —— 产物的一部分不在 tank，
			// 用 tank 算 Q 没有意义；当前可逆反应全是容器型（THERMAL/STIRRING），routed 为 null。
			boolean reversible = r.reversible() && routed == null;
			boolean reverse = false;
			double exact;
			if (reversible) {
				double q = MolBalance.equilibriumQuotient(tank, r);
				if (MolBalance.withinEquilibriumBand(q, r.equilibriumRatio())) {
					continue; // 已到平衡：本条不动，也不计入 blocked
				}
				reverse = q > r.equilibriumRatio();
				double directionLimit = reverse
						? MolBalance.limitingReverse(tank, r, catalysts)
						: MolBalance.limiting(tank, r, catalysts);
				// 只推到平衡点、不越过 K：否则整份推进会在平衡点两侧来回震荡。
				double equilibriumLimit = Math.abs(
						MolBalance.progressToEquilibrium(tank, r, r.equilibriumRatio()));
				exact = Math.min(directionLimit, equilibriumLimit);
			} else {
				// 限制试剂允许的最大进度（分数）。低于一个千分位份就不跑，避免空转；
				// 催化剂不满足时这里同样是 0，自然跳过。
				exact = MolBalance.limiting(tank, r, catalysts);
			}
			if (exact < MolBalance.MIN_PROGRESS) {
				continue;
			}
			if (r.requiresStirring() && !stirred) {
				blocked++;
				continue;
			}
			// 反应自己的速率按机器的吞吐上限缩放，再乘修正（催化剂 / 搅拌 / 整机）。
			double effectiveRate = ratePerTick * r.rate()
					* rateMultiplier(r, stirred, catalysts) * machineFactor;
			double limited = Math.min(exact, Math.max(0.0, effectiveRate));
			// 逆向时净增 = 反应物 − 产物（tank 里），正向相反。
			double roomBound = Double.POSITIVE_INFINITY;
			if (productCapacity > 0.0) {
				double netGain = reverse
						? reactantMol(r) - tankProductMol(r, routed)
						: tankProductMol(r, routed) - reactantMol(r);
				if (netGain > 0.0) {
					roomBound = Math.max(0.0, (productCapacity - tank.totalMol()) / netGain);
				}
			}
			// 预算上限：每条反应每份要花 reactantMol(r) × 本反应单价 的电费，
			// 剩余预算只够买多少份。与 roomBound 一样按余量压缩；预算不足以推进一个最小量子时，
			// 这一条跳过（不空烧、不欠账，bug#31 的口径）。
			double budgetBound = Double.POSITIVE_INFINITY;
			if (feBudget > 0.0) {
				double perRun = reactantMol(r) * r.fePerMol();
				if (perRun > 0.0) {
					budgetBound = Math.max(0.0, budgetLeft / perRun);
				}
			}
			double bound = Math.min(roomBound, budgetBound);
			double progress = MolBalance.quantize(Math.min(limited, bound));
			if (progress < MolBalance.MIN_PROGRESS) {
				if (slowFloorShouldLift(exact, limited, bound, effectiveRate)) {
					progress = MolBalance.MIN_PROGRESS;
				} else {
					continue;
				}
			}
			if (reverse) {
				applyReverse(tank, r, progress, routed);
			} else {
				apply(tank, r, progress, routed);
			}
			double used = reactantMol(r) * progress;
			mol += used;
			double spent = used * r.fePerMol();
			feTotal += spent;
			if (feBudget > 0.0) {
				budgetLeft -= spent;
			}
			progressTotal += progress;
			ran++;
		}
		return new Outcome(ran, blocked, mol, progressTotal, feTotal);
	}

	/**
	 * 一条反应的速率修正（全是乘性、全是显式常数，便于离线复算）。
	 *
	 * <ul>
	 *   <li><b>催化剂</b>：所需催化剂全部在位 → ×{@link MolBalance.Reaction#CATALYST_RATE_BONUS}。
	 *       闸门仍在 {@link MolBalance#limiting} 里（缺催化剂连 <b>1</b> 份都跑不起来）；
	 *       这里只在「本来就能跑」之上提速，不替代闸门；</li>
	 *   <li><b>搅拌</b>：{@code stirred=true} → ×{@link MolBalance.Reaction#STIRRING_RATE_BONUS}。
	 *       需要搅拌的反应只有在搅拌时才跑得到这里，所以它们等价于常带这个加成；</li>
	 *   <li>整机系数不在这里，走 {@code runAll} 的 {@code machineFactor} 参数。</li>
	 * </ul>
	 */
	public static double rateMultiplier(MolBalance.Reaction r, boolean stirred, List<String> catalysts) {
		double multiplier = 1.0;
		if (r.catalysts().length > 0 && MolBalance.catalystsPresent(r, catalysts)) {
			multiplier *= MolBalance.Reaction.CATALYST_RATE_BONUS;
		}
		if (stirred) {
			multiplier *= MolBalance.Reaction.STIRRING_RATE_BONUS;
		}
		return multiplier;
	}

	/**
	 * 极慢反应的量化兜底：量化后低于 {@link MolBalance#MIN_PROGRESS} 时，要不要把这一步抬到
	 * 最小量子？
	 *
	 * <p>只在这一步确实<b>卡在反应速率</b>上时才抬：料够（{@code exact >= MIN_PROGRESS}）、
	 * 容量也够（{@code roomBound >= MIN_PROGRESS}）、且速率本身还没到最小量子
	 * （{@code effectiveRate > 0} 且 {@code limited < MIN_PROGRESS}）。容量是瓶颈时抬上去会撑破
	 * 容量；机器吞吐为 0 时抬上去等于关不掉反应 —— 这两种都不抬。
	 */
	public static boolean slowFloorShouldLift(double exact, double limited, double roomBound,
			double effectiveRate) {
		return effectiveRate > 0.0
				&& limited < MolBalance.MIN_PROGRESS
				&& exact >= MolBalance.MIN_PROGRESS
				&& roomBound >= MolBalance.MIN_PROGRESS;
	}

	/**
	 * 一条反应跑一份时会<b>落回 tank</b> 的产物 mol —— {@code routed} 接走的
	 * {@code Side != NONE} 产物不算（它们不占 tank 容量）。容器容量约束用。
	 */
	public static double tankProductMol(MolBalance.Reaction r, ProductSink routed) {
		double sum = 0.0;
		for (MolBalance.Product p : r.products()) {
			if (routed == null || p.side() == MolBalance.Side.NONE) {
				sum += p.coeff();
			}
		}
		return sum;
	}

	/** 旧签名：默认允许反应釜 / 搅拌器的两类反应（保持既有行为）。 */
	public static Outcome runAll(MolStock tank, double ratePerTick, boolean stirred, List<String> catalysts) {
		return runAll(tank, ratePerTick, stirred, catalysts, VESSEL_KINDS);
	}

	/** 一条反应跑一份要消耗多少 mol 反应物（含溶剂）。 */
	public static double reactantMol(MolBalance.Reaction r) {
		double sum = 0.0;
		for (MolBalance.Reactant x : r.reactants()) {
			sum += x.coeff();
		}
		return sum;
	}

	/** 按份数扣反应物、生成产物，全在同一本账里。 */
	public static void apply(MolStock tank, MolBalance.Reaction r, double runs) {
		apply(tank, r, runs, null);
	}

	/**
	 * 按份数扣反应物、生成产物。
	 *
	 * <p>带电极归属（{@code Side != NONE}）的产物交 {@code routed}；没有分流口时落回 tank。
	 */
	public static void apply(MolStock tank, MolBalance.Reaction r, double runs, ProductSink routed) {
		if (runs <= 0.0) {
			return;
		}
		for (MolBalance.Reactant x : r.reactants()) {
			tank.take(x.id(), x.coeff() * runs);
		}
		for (MolBalance.Product p : r.products()) {
			double mol = p.coeff() * runs;
			if (routed != null && p.side() != MolBalance.Side.NONE) {
				routed.accept(p, mol);
			} else {
				tank.add(p.id(), mol);
			}
		}
	}

	/**
	 * 按份数<b>逆向</b>推进一条反应：消耗产物、生成反应物。
	 *
	 * <p>逆向边不另配平 —— 与 {@link #apply} 共用同一套整数系数，只是方向反过来；
	 * {@link MolBalance#isMassBalanced} 的校验对方向对称，所以逆向同样守恒。
	 * 分数份、限速、产物容量由 {@link #runAll} 统一处理，本方法只负责记账。
	 *
	 * <p><b>限制</b>：电极分流（{@code routed != null}）不做逆向 —— 电极产物不在
	 * tank，逆向没有可消耗的来源。{@link #runAll} 已经把可逆分流这条路排除；
	 * 这里再兜底一次，宁可什么都不做，也绝不凭空生成反应物。
	 */
	public static void applyReverse(MolStock tank, MolBalance.Reaction r, double runs) {
		applyReverse(tank, r, runs, null);
	}

	/** 逆向推进（带产物分流口；{@code routed != null} 时对电极产物直接跳过）。 */
	public static void applyReverse(MolStock tank, MolBalance.Reaction r, double runs, ProductSink routed) {
		if (runs <= 0.0) {
			return;
		}
		if (routed != null) {
			for (MolBalance.Product p : r.products()) {
				if (p.side() != MolBalance.Side.NONE) {
					return; // 电极产物无法从 tank 倒推，整条不逆向（防凭空生料）
				}
			}
		}
		for (MolBalance.Product p : r.products()) {
			tank.take(p.id(), p.coeff() * runs);
		}
		for (MolBalance.Reactant x : r.reactants()) {
			tank.add(x.id(), x.coeff() * runs);
		}
	}
}
