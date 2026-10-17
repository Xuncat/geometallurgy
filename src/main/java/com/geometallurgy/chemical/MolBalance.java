package com.geometallurgy.chemical;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 化学反应的 mol 配平器 —— 设计稿 §十 / §11.5 的核心。
 *
 * <p>mol 体系的真收益是<b>配平回到整数系数</b>（§七）：反应按化学式整数比进行，
 * 而不是现有的 `Metallurgy.stoichiometry` 那种经验当量（1.00 / 0.40 / 1.20）。
 *
 * <p>一个反应定义为：<pre>
 *   ReactantSpec[] inputs   （成分 id + 整数系数，如 HCl×6）
 *   String[] catalysts      （催化剂 id 列表，可不放）
 *   ProductSpec[] outputs   （产物 id + 整数系数，如 FeCl₃×2、H₂O×3）
 * </pre>
 *
 * <p>执行时按<b>限速试剂</b>定反应进度：整数系数是质量守恒的契约，推进量则可以是
 * <b>分数份</b>（{@link #limiting} / {@link #quantize}）—— 不足一整份的余料也能按比例消耗 / 生成，
 * 余料留在账本里（不强制耗光）。催化剂只判"放没放"，本身不消耗。
 */
public final class MolBalance {

	private MolBalance() {
	}

	/** 一种反应物：id + 整数系数。 */
	public record Reactant(String id, int coeff) {
	}

	/**
	 * 一种产物在电解池里的电极归属。
	 *
	 * <ul>
	 *   <li>{@link #NONE}：不分电极 —— 留在溶液 / 混合槽里（如 NaOH），或走非电解反应；</li>
	 *   <li>{@link #ANODE}：阳极析出（气体 / 固体），进阳极容器；</li>
	 *   <li>{@link #CATHODE}：阴极析出，进阴极容器。</li>
	 * </ul>
	 */
	public enum Side {
		NONE, ANODE, CATHODE
	}

	/**
	 * 一种产物：id + 整数系数 + 电极归属。
	 *
	 * <p>保留 2 参构造器（默认 {@link Side#NONE}），表里旧反应一条都不用改。
	 */
	public record Product(String id, int coeff, Side side) {

		/** 默认不分电极：留在溶液 / 混合槽里。 */
		public Product(String id, int coeff) {
			this(id, coeff, Side.NONE);
		}
	}

	/**
	 * 一条反应发生的<b>条件种类</b>。
	 *
	 * <ul>
	 *   <li>{@link #THERMAL}：常温 / 加热就能走，反应釜与搅拌器都会跑；</li>
	 *   <li>{@link #STIRRING}：必须搅拌才会发生（气体—气体这类需要反复接触的反应）；</li>
	 *   <li>{@link #ELECTROLYSIS}：只有电解炉通电时才会发生（溶液电解档）；</li>
	 *   <li>{@link #ROASTING}：<b>高温焙烧 / 分解</b> —— 必须让挥发物（水蒸气 / CO₂）持续离开，
	 *       常温常压的密闭反应釜里走不动（例如受热脱水 {@code 2 NaOH -> Na2O + H2O}）。
	 *       它<b>不是</b>反应釜 / 搅拌器 / 电解炉能跑的条件，两台容器型机器都不收它。</li>
	 * </ul>
	 *
	 * <p>三种机器共用一张反应表，靠本枚举分流：反应釜 / 搅拌器允许
	 * {@code {THERMAL, STIRRING}}，电解炉只允许 {@code {ELECTROLYSIS}}。
	 * {@link #ROASTING} 两套都不在列，所以它不会在当前的任何一台化学机器上跑 ——
	 * 这是<b>刻意</b>的：焙烧档要有对应的炉子才谈得上开（见 {@link Machine#REDUCTION_FURNACE}）。
	 */
	public enum Kind {
		THERMAL, STIRRING, ELECTROLYSIS,
		/**
		 * 高温焙烧 / 分解（追加在末尾，绝不插队改动旧 ordinal）。
		 *
		 * <p>引入它的直接原因（bug#36）：{@code 2 NaOH -> Na2O + H2O}（慢，0.05 份/tick）与
		 * 规则 1 生成的 {@code Na2O + H2O -> 2 NaOH}（快，0.8）在同一台反应釜里构成
		 * <b>非可逆的互逆对</b> —— 前者每 tick 在釜尾留下 0.05 mol Na₂O，后者下一 tick 又把它
		 * 合回去，账面上永远有一撮 Na₂O（截图 0.1%）与多出来的水，釜子一直显示「反应中」。
		 * 把焙烧侧判成 {@link #ROASTING} 后，常温反应釜不再跑它，Na₂O 被水合吃干。
		 */
		ROASTING
	}

	/**
	 * 一条反应定义。
	 *
	 * <p>{@code kind} 是这条反应的发生条件（见 {@link Kind}）；{@code machines} 是
	 * 「哪些机器能跑这条反应」—— 不可为空（{@link Machine}）。默认按 {@code kind} 派生：
	 * THERMAL → 反应釜 + 搅拌器，STIRRING → 只有搅拌器，ELECTROLYSIS → 只有电解炉。
	 * 例外（某条反应只在别的机器上跑）用显式机器集构造器。
	 *
	 * <p><b>兼容</b>：保留旧的 3 参 / 布尔 4 参 / {@link Kind} 4 参构造器，表里 44 条反应
	 * 一条都不用改；电解型反应显式传 {@link Kind#ELECTROLYSIS}。
	 *
	 * <p>{@code rate}（份/tick）是本轮新增的<b>每反应速率</b>：旧构造器不带它，走
	 * {@link #DEFAULT_RATE}；需要标耗时的用 {@link #withRate(double)}。它不是配平的一部分，
	 * 只决定「这份方程多久推完」，由机器吞吐上限按比例缩放（见 {@code Reactor}）。
	 *
	 * <p>{@code fePerMol}（FE / mol 反应物）是 2026-10-17 新增的<b>逐反应电解单价</b>：
	 * 溶液电解档按它乘实际推进的 mol 扣费，取代了旧的平铺常量 {@code SOLUTION_FE_PER_MOL}。
	 * 旧构造器不带它，走 {@link #UNPRICED}（= {@link #DEFAULT_FE_PER_MOL} 兜底）；
	 * 标价用 {@link #withPower(double)}，非电解反应用不到它。
	 *
	 * <p>记录体的规范构造器会把 {@code machines} 冻结成不可变集合，并拒绝空集 ——
	 * 一条没有机器的反应等于一条永远跑不起来的超边，宁可启动就炸。
	 */
	public record Reaction(Reactant[] reactants, String[] catalysts, Product[] products, Kind kind,
			Set<Machine> machines, boolean reversible, double equilibriumRatio, double rate,
			double fePerMol) {

		/**
		 * 没标价的电解反应用的兜底单价（FE / mol 反应物）—— <b>就是旧的平铺常量
		 * {@code SOLUTION_FE_PER_MOL = 120}</b>。
		 *
		 * <p>2026-10-17 起电解炉<b>逐反应</b>计费：表里 5 条电解反应全部显式标价
		 * （见 {@link MolRecipes} 的 {@code withPower} 与启动断言），这个兜底只防「日后新加的
		 * 电解反应忘了标价」把它算成 0 —— 宁可算贵，不可白送。
		 */
		public static final double DEFAULT_FE_PER_MOL = 120.0;

		/** 还没标价（{@link #fePerMol()} 会返回 {@link #DEFAULT_FE_PER_MOL}）。 */
		public static final double UNPRICED = -1.0;

		/**
		 * 本反应的电解单价，单位 <b>FE / mol 反应物</b>（含溶剂，见
		 * {@code Reactor.reactantMol}）—— 溶液档按它乘实际推进的 mol 扣费。
		 *
		 * <p>烧碱、氯气这类产物的价格差着好几倍（氯碱 ~3 V、氟化氢 ~5.5 V），所以单价必须挂在
		 * 反应上，不能挂在机器上。
		 */
		@Override
		public double fePerMol() {
			return this.fePerMol == UNPRICED ? DEFAULT_FE_PER_MOL : this.fePerMol;
		}

		/** 这条反应有没有显式标过电解单价。电解反应一律要求为真（MolRecipes 的启动断言）。 */
		public boolean priced() {
			return this.fePerMol != UNPRICED;
		}

		/**
		 * 平衡死区（相对 K）：{@code |Q - K| <= 5% · K} 视为「已到平衡」，反应不动。
		 *
		 * <p>粗模型刻意留宽：既避免每 tick 在平衡点两侧来回震荡，也不必真去标定 K。
		 */
		public static final double EQUILIBRIUM_BAND = 0.05;

		/**
		 * 未显式标速率的反应用的默认速率，单位 <b>份/tick</b>（一份 = 一整条方程）。
		 *
		 * <p>取 {@code 0.25}：一小批料大约 4 tick（0.2 s，按 20 tps）走完一整条方程 ——
		 * 比改动前的「一 tick 一份」（{@code RATE_PER_TICK = 1.0}）慢一个肉眼可见的档，
		 * 又不至于让新加的反应变成苦等。它只是<b>兜底</b>：当前反应表 44 条全部显式标了速率
		 * （见 {@link MolRecipes} 与 {@code docs/科技线-v1.md} 的速率表）。
		 */
		public static final double DEFAULT_RATE = 0.25;

		/**
		 * 所需催化剂全部在位时的速率倍率。
		 *
		 * <p>催化剂原来只是「存在性闸门」（缺了整条反应 {@link MolBalance#limiting} 返回 0），
		 * 现在<b>闸门照旧</b>，在位时再乘以本倍率提速 —— 两条语义叠加，不互相替代。
		 */
		public static final double CATALYST_RATE_BONUS = 4.0;

		/** 搅拌中的速率倍率（搅拌本身仍是闸门，见 {@link #requiresStirring()}；这是额外提速）。 */
		public static final double STIRRING_RATE_BONUS = 1.25;

		/**
		 * 规范构造：机器集不可变且非空；平衡比与速率都必须是有限正数。
		 *
		 * <p>{@code reversible} 默认 {@code false}（旧构造器全部走默认），因此旧表里
		 * 没有显式标记的反应与改动前完全一致；{@code equilibriumRatio} = 平衡时
		 * 产物总 mol / 反应物总 mol 的<b>粗代理</b>（见 {@link MolBalance#equilibriumQuotient}），
		 * 默认 {@code 1.0}；{@code rate} = 这条反应自己愿意跑多快（份/tick），
		 * 由机器的吞吐上限（{@code Reactor.runAll} 的 {@code ratePerTick}）按比例缩放。
		 */
		public Reaction {
			machines = machines == null ? Set.of() : Set.copyOf(machines);
			if (machines.isEmpty()) {
				throw new IllegalStateException("反应没有绑定任何机器（超边永远跑不起来）");
			}
			if (!(equilibriumRatio > 0.0) || Double.isInfinite(equilibriumRatio)) {
				throw new IllegalArgumentException("平衡比必须是有限正数: " + equilibriumRatio);
			}
			if (!(rate > 0.0) || Double.isInfinite(rate)) {
				throw new IllegalArgumentException("反应速率必须是有限正数（份/tick）: " + rate);
			}
			if (fePerMol != UNPRICED && (!(fePerMol > 0.0) || Double.isInfinite(fePerMol))) {
				throw new IllegalArgumentException("电解单价必须是有限正数（FE/mol）或 UNPRICED: " + fePerMol);
			}
		}

		/** 旧的 8 参构造器（不带电解单价）—— 单价走 {@link #UNPRICED}（= 兜底 120 FE/mol）。 */
		public Reaction(Reactant[] reactants, String[] catalysts, Product[] products, Kind kind,
				Set<Machine> machines, boolean reversible, double equilibriumRatio, double rate) {
			this(reactants, catalysts, products, kind, machines, reversible, equilibriumRatio, rate, UNPRICED);
		}

		/** 换一个电解单价（FE / mol 反应物）返回新反应，其余字段不变。溶液电解表标价用。 */
		public Reaction withPower(double fePerMol) {
			return new Reaction(reactants, catalysts, products, kind, machines, reversible,
					equilibriumRatio, rate, fePerMol);
		}

		/**
		 * 旧 7 参构造器（不带速率）—— 兼容既有调用，速率走 {@link #DEFAULT_RATE}。
		 *
		 * <p>换成速率用 {@link #withRate(double)}；这是为「不动旧表、只给需要的那几条提速」
		 * 留的兼容口。
		 */
		public Reaction(Reactant[] reactants, String[] catalysts, Product[] products, Kind kind,
				Set<Machine> machines, boolean reversible, double equilibriumRatio) {
			this(reactants, catalysts, products, kind, machines, reversible, equilibriumRatio, DEFAULT_RATE);
		}

		/** 换一个反应速率（份/tick）返回新反应，其余字段不变。反应表标速率用。 */
		public Reaction withRate(double newRate) {
			return new Reaction(reactants, catalysts, products, kind, machines, reversible,
					equilibriumRatio, newRate, fePerMol);
		}

		/** 旧 5 参构造器（默认不可逆，平衡比 1.0）—— 兼容既有调用。 */
		public Reaction(Reactant[] reactants, String[] catalysts, Product[] products, Kind kind,
				Set<Machine> machines) {
			this(reactants, catalysts, products, kind, machines, false, 1.0);
		}

		/** 默认：普通热反应（反应釜 + 搅拌器）。 */
		public Reaction(Reactant[] reactants, String[] catalysts, Product[] products) {
			this(reactants, catalysts, products, Kind.THERMAL);
		}

		/** 旧签名：{@code true} = 必须搅拌（只有搅拌器），{@code false} = 普通热反应。 */
		public Reaction(Reactant[] reactants, String[] catalysts, Product[] products,
				boolean requiresStirring) {
			this(reactants, catalysts, products, requiresStirring ? Kind.STIRRING : Kind.THERMAL);
		}

		/** 按 {@link Kind} 取默认机器集（{@link Machine#defaultsFor}）。 */
		public Reaction(Reactant[] reactants, String[] catalysts, Product[] products, Kind kind) {
			this(reactants, catalysts, products, kind, Machine.defaultsFor(kind));
		}

		/**
		 * 显式机器集构造器（例外用）：把这条反应钉死在给定的机器上。
		 *
		 * <p>规范 5 参构造器（末参 {@code Set<Machine>}）同样可用；本重载只是给「一条反应
		 * 就在某几台机器上跑」的写出更顺手的写法。
		 */
		public Reaction(Reactant[] reactants, String[] catalysts, Product[] products, Kind kind,
				Machine... machines) {
			this(reactants, catalysts, products, kind, Set.of(machines));
		}

		/**
		 * 可逆反应工厂（默认机器集）：给定发生条件与粗平衡比。
		 *
		 * <p>用工厂而不是再加一个构造器，是为了让「哪条反应开了可逆」在反应表里一眼可见，
		 * 不改动任何既有构造器的重载解析。
		 */
		public static Reaction ofReversible(Reactant[] reactants, String[] catalysts, Product[] products,
				Kind kind, double equilibriumRatio) {
			return new Reaction(reactants, catalysts, products, kind, Machine.defaultsFor(kind),
					true, equilibriumRatio);
		}

		/** 可逆热反应工厂：{@code requiresStirring} 决定 {@link Kind}。 */
		public static Reaction ofReversible(Reactant[] reactants, String[] catalysts, Product[] products,
				boolean requiresStirring, double equilibriumRatio) {
			return ofReversible(reactants, catalysts, products,
					requiresStirring ? Kind.STIRRING : Kind.THERMAL, equilibriumRatio);
		}

		/** 这条反应是否必须搅拌（= kind 是 STIRRING）。 */
		public boolean requiresStirring() {
			return this.kind == Kind.STIRRING;
		}
	}

	/**
	 * 对一个 {@link MolStock} 反应，返回<b>新增一份</b>产物账（不修改入参）。
	 *
	 * @param stock    反应容器当前账本（只读）
	 * @param reaction 反应定义
	 * @param catalysts 当前槽里的催化剂（可为空数组）
	 * @return 本次反应新增的产物 MolStock；若催化剂不满足或任何反应物不足，返回空
	 */
	public static MolStock react(MolStock stock, Reaction reaction, java.util.List<String> catalysts) {
		// 1) 催化剂条件：所需催化剂必须在槽里（只判存在，不消耗）
		for (String need : reaction.catalysts()) {
			boolean present = catalysts != null && catalysts.stream().anyMatch(need::equals);
			if (!present) {
				return MolStock.empty();
			}
		}

		// 2) 限速：每个反应物能跑几份，取最小
		double runs = Double.MAX_VALUE;
		for (Reactant r : reaction.reactants()) {
			runs = Math.min(runs, Math.floor(stock.of(r.id()) / r.coeff()));
		}
		if (runs < 1.0) {
			return MolStock.empty(); // 连一份都不够
		}

		// 3) 配平：产物按份数生成。用 1 份的原子账（整数系数），产物独立成条，
		//    不在此合并异构体/溶质 —— 成分必须分离。
		MolStock out = new MolStock();
		for (Product p : reaction.products()) {
			out.add(p.id(), p.coeff() * 1.0 * runs);
		}
		return out;
	}

	/**
	 * 推进份数的最小计量单位（千分位一份）。
	 *
	 * <p>{@link MolStock} 的记账分辨率就是千分位 mol，分数推进按千分位<b>向下</b>量化
	 * （{@link #quantize}）：任何反应物 / 产物的实际 mol 都落在千分位网格上，
	 * {@code add} 的取整因此不会把「扣了多少」和「加了多少」错开，长时间跑也不漂移。
	 * 低于这个值的余料不值得再开一轮（否则会每 tick 空转）。
	 */
	public static final double MIN_PROGRESS = 1.0e-3;

	/**
	 * 这条反应要求的催化剂是否<b>全部</b>在位（只判存在、不消耗）。
	 *
	 * <p>是 {@link #limiting} / {@link #limitingReverse} / {@link Reactor} 提速倍率共用的
	 * 单一判定 —— 闸门与提速都从这一处读，不可能漂移。
	 */
	public static boolean catalystsPresent(Reaction reaction, java.util.List<String> catalysts) {
		for (String need : reaction.catalysts()) {
			boolean present = catalysts != null && catalysts.stream().anyMatch(need::equals);
			if (!present) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 这条反应此刻最多能推进多少<b>份</b>（分数，不是整数）。
	 *
	 * <p>这是限速逻辑的单一事实来源：催化剂不满足返回 0；否则返回
	 * {@code min(每个反应物库存 / 该反应物整数系数)} —— 也就是「限制试剂」允许的最大进度。
	 * 1.0 表示恰好一份整方程，0.75 表示四分之三份。整数倍推进的旧逻辑是
	 * {@link #runs}；分数推进（反应釜剩余料能跑完）用本方法与 {@link #quantize}。
	 *
	 * @return 最大推进份数（>=0）；缺催化剂 / 没有反应物时返回 0
	 */
	public static double limiting(MolStock stock, Reaction reaction, java.util.List<String> catalysts) {
		if (!catalystsPresent(reaction, catalysts)) {
			return 0.0;
		}
		double runs = Double.MAX_VALUE;
		for (Reactant r : reaction.reactants()) {
			runs = Math.min(runs, stock.of(r.id()) / r.coeff());
		}
		if (runs == Double.MAX_VALUE) {
			return 0.0; // 没有反应物 —— 不成其为反应
		}
		return Math.max(0.0, runs);
	}

	/**
	 * 把推进份数向下量化到千分位（{@link #MIN_PROGRESS}）。
	 *
	 * <p>向下取整保证绝不超出限制试剂允许的量；千分位网格保证
	 * {@code 系数 × 份数} 与 {@link MolStock} 的千分位记账对齐。
	 */
	public static double quantize(double progress) {
		if (progress <= 0.0) {
			return 0.0;
		}
		return Math.floor(progress * 1000.0) / 1000.0;
	}

	/**
	 * <b>逆向</b>推进的限速：把产物当反应物，返回此刻最多能倒推多少<b>份</b>（分数）。
	 *
	 * <p>与 {@link #limiting} 完全对偶 —— 整数系数是同一套（逆向边不另配平），
	 * 取 {@code min(每个产物库存 / 该产物整数系数)}；催化剂条件与正向一样必须满足。
	 *
	 * @return 最大倒推份数（>=0）；缺催化剂 / 没有产物时返回 0
	 */
	public static double limitingReverse(MolStock stock, Reaction reaction, java.util.List<String> catalysts) {
		if (!catalystsPresent(reaction, catalysts)) {
			return 0.0;
		}
		double runs = Double.MAX_VALUE;
		for (Product p : reaction.products()) {
			runs = Math.min(runs, stock.of(p.id()) / p.coeff());
		}
		if (runs == Double.MAX_VALUE) {
			return 0.0; // 没有产物 —— 倒推不成其为反应
		}
		return Math.max(0.0, runs);
	}

	/**
	 * 反应商代理 {@code Q}：<b>产物总 mol / 反应物总 mol</b>。
	 *
	 * <p>这是刻意的<b>粗模型</b>（用户口径：不做真正的 K 标定）—— 只按账面上两边的
	 * mol 总量比，不乘整数系数、不区分相态。它只用来判断往哪个方向推、以及什么时候停，
	 * 不参与质量守恒（守恒由整数系数保证）。
	 *
	 * @return {@code Q}；只有产物（反应物为 0）返回 {@link Double#POSITIVE_INFINITY}；
	 *         两样都空返回 {@code 1.0}（中性，不会触发方向）
	 */
	public static double equilibriumQuotient(MolStock stock, Reaction reaction) {
		double products = 0.0;
		for (Product p : reaction.products()) {
			products += stock.of(p.id());
		}
		double reactants = 0.0;
		for (Reactant r : reaction.reactants()) {
			reactants += stock.of(r.id());
		}
		if (reactants <= 1.0e-9) {
			return products <= 1.0e-9 ? 1.0 : Double.POSITIVE_INFINITY;
		}
		return products / reactants;
	}

	/** {@code |Q - K| <= 5% · K} 视为已到平衡（{@link Reaction#EQUILIBRIUM_BAND}）。 */
	public static boolean withinEquilibriumBand(double quotient, double ratio) {
		if (Double.isNaN(quotient) || Double.isInfinite(quotient)) {
			return false;
		}
		return Math.abs(quotient - ratio) <= Reaction.EQUILIBRIUM_BAND * ratio;
	}

	/**
	 * 从当前账推进多少份恰好到达平衡比 {@code ratio}（<b>有符号</b>）。
	 *
	 * <p>正向：{@code p = (K·R − P) / (Pc + K·Rc)}；逆向就是同一个式子的相反数。
	 * 其中 {@code P/R} 是产物 / 反应物的总 mol，{@code Pc/Rc} 是一条完整方程里
	 * 产物 / 反应物的系数和。正数 = 需要正向推进的份数，负数 = 需要逆向推进的份数。
	 *
	 * <p>{@code Reactor} 用它的绝对值当上限，从而<b>只推进到 K、不越过 K</b>；
	 * 否则每 tick 整份推进会在平衡点两侧来回震荡（K 很小时一步就能把反应物耗光）。
	 */
	public static double progressToEquilibrium(MolStock stock, Reaction reaction, double ratio) {
		double products = 0.0;
		double reactants = 0.0;
		double productCoeffs = 0.0;
		double reactantCoeffs = 0.0;
		for (Product p : reaction.products()) {
			products += stock.of(p.id());
			productCoeffs += p.coeff();
		}
		for (Reactant r : reaction.reactants()) {
			reactants += stock.of(r.id());
			reactantCoeffs += r.coeff();
		}
		double denom = productCoeffs + ratio * reactantCoeffs;
		if (denom <= 0.0) {
			return 0.0;
		}
		return (ratio * reactants - products) / denom;
	}

	/**
	 * 这次反应能跑几份（限速逻辑的整数倍版本，保持既有语义）。
	 *
	 * <p>催化剂条件不满足返回 0；满足则返回「每个反应物能跑的份数」的最小值，向下取整。
	 * {@link #react} 与 {@link #consume} 内部都调它，配平只此一处，别处不得复算。
	 * 需要分数推进（不足一整份的余料也要反应）用 {@link #limiting}。
	 *
	 * @return 本次反应能跑几份（整数值）；催化剂缺 / 连一份都不够时返回 0
	 */
	public static double runs(MolStock stock, Reaction reaction, java.util.List<String> catalysts) {
		double runs = limiting(stock, reaction, catalysts);
		return runs < 1.0 ? 0.0 : Math.floor(runs);
	}

	/**
	 * 反应并<b>扣减</b>入参库存。
	 *
	 * @param stock 可变账本，会按反应物消耗
	 * @return 本次反应新增的产物 MolStock
	 */
	public static MolStock consume(MolStock stock, Reaction reaction, java.util.List<String> catalysts) {
		double runs = runs(stock, reaction, catalysts);
		if (runs < 1.0) {
			return MolStock.empty();
		}
		for (Reactant r : reaction.reactants()) {
			stock.take(r.id(), r.coeff() * runs);
		}
		MolStock out = new MolStock();
		for (Product p : reaction.products()) {
			out.add(p.id(), p.coeff() * runs);
		}
		return out;
	}

	/** 反应的质量守恒校验：产物总质量 ≈ 反应物总质量（化学计量常数，容许小数误差）。 */
	public static boolean isMassBalanced(Reaction r) {
		double in = 0.0;
		for (Reactant x : r.reactants()) {
			in += x.coeff() * Chemical.molarMassOf(x.id());
		}
		double out = 0.0;
		for (Product x : r.products()) {
			out += x.coeff() * Chemical.molarMassOf(x.id());
		}
		return Math.abs(in - out) < 1.0; // g 级容差，允许式量取整
	}
}