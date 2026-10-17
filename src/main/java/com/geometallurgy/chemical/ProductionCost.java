package com.geometallurgy.chemical;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 产线成本模型（本轮：分析用，纯 Java，无 {@code net.minecraft} 依赖）。
 *
 * <h2>它算什么</h2>
 * 给一个目标节点（{@link Chemical} 物种 id，或矿 / 精矿 / 锭这类非化学节点），
 * 沿 {@link ReactionGraph} 的超边<b>反向</b>走，累加「生产 1 个单位目标」需要付的：
 * <ul>
 *   <li>{@link #cost().roots()} —— <b>矿根 mol</b>：把 {@link ReactionGraph#ORE_ROOTS}
 *       当免费（或只付开采电）的起点，逐层折算回根节点所需的量；</li>
 *   <li>{@link #cost().ticks()} —— <b>机器刻数</b>：化学反应按
 *       {@code 机器吞吐上限 × Reaction.rate() × 修正} 推进；桥接机器步骤按其 RATE；</li>
 *   <li>{@link #cost().fe()} —— <b>FE</b>：只在机器真正计费的地方记
 *       （溶液电解按<b>逐反应单价</b> {@link MolBalance.Reaction#fePerMol()} × 反应物 mol；
 *       桥接的萃取 / 粉碎 / 离心 / 磁选按 FE/t × 刻数，冶炼三炉按 {@link #SMELT_STEP} 的逐配方功率）；</li>
 *   <li>{@link #cost().reactionUnits()} / 桥接步数 —— 做了多少「份」方程、多少步机器步骤。</li>
 * </ul>
 *
 * <h2>路线怎么选</h2>
 * 同一物质可能有多条生产路线（例如 HCl 有 {@code H2+Cl2} 与 {@code NaCl+H2SO4} 两条）。
 * 本模型用 Bellman-Ford 式的<b>迭代松弛</b>求每条路线的成本，并按
 * {@link Objective} 取最优：{@link Objective#ORE_FIRST}（矿根 mol 优先，本轮口径 ——
 * 另一名代理正在重调矿脉丰度，所以用 mol 量而不是区块频率）、{@link Objective#FE_FIRST}、
 * {@link Objective#TICKS_FIRST}。三种口径都会保留，便于看「省矿 / 省电 / 省时间」的取舍。
 *
 * <h2>环的守卫</h2>
 * 图里有环（{@code 2NaOH → Na2O + H2O} 与 {@code Na2O + H2O → 2NaOH} 互逆；
 * {@code 4HNO3 → NOx} 与 Ostwald 环也成环）。本模型的守卫有两层：
 * <ol>
 *   <li><b>迭代上限</b> {@link #MAX_ITERATIONS}：松弛到不动点就停；到达上限仍在变，
 *       {@link Result#hitIterationCap()} 置真并给出提示 —— <b>绝不静默死循环</b>；</li>
 *   <li><b>回溯守卫</b>：重建路线时对已访问节点剪枝（{@link #MAX_TRACE_DEPTH}），
 *       遇到环就标 {@code (cycle)}，不递归爆栈。</li>
 * </ol>
 * 因为所有边成本都非负、且正反应至少消耗一份试剂，最优路线不会自己套自己，
 * 所以本模型不需要「按路径」的专门去环；迭代上限是兜底。
 *
 * <h2>有意不建模的（见 docs/产线成本账.md「模型不覆盖什么」）</h2>
 * <ul>
 *   <li><b>可逆反应的平衡产率</b>：本模型按化学计量完全转化计。合成氨 K=0.25、
 *       碳酸盐焙烧 K=6/8 这类，实际产率低于 100%，成本被低估；</li>
 *   <li><b>选矿 / 冶金的收率与杂质</b>：{@code metal/Metallurgy} 的 recoveryScale、
 *       carryScale、碳残留、矿石品位差；桥接边是 1:1 抽象，不带这些；</li>
 *   <li><b>矿浆 mB ↔ mol 的接缝</b>：桥接边把「1 单位矿石」当成「1 单位物种」，
 *       没有 mB→mol 的换算（这是既有设计稿 §六.7 的已知未决项）；</li>
 *   <li><b>玩家劳动、容器 / 催化剂物品的造价</b>：桶（4 铜锭 / 玻璃 / 铅 / 陶瓷）、
 *       碳棒 / 钼棒只记「不消耗」，物品本身的成本不在本模型内；</li>
 *   <li><b>燃料</b>：FE 只记用电设备；烧燃料的发电成本、火力发电机与电池的损耗
 *       不在本模型内（{@code CoalGeneratorBlockEntity} 1 煤 = 12800 FE，
 *       {@code BatteryBlockEntity} 有 50% 出入损耗，可当汇率用）；</li>
 *   <li><b>还原剂 / 燃料本身的价格</b>：2026-10-17 起还原炉有一批路线是<b>试剂驱动</b>（0 FE/t，
 *       高炉 / 反射炉 / 自然金属熔炼），它们的成本全压在还原剂当量上，而本模型只记电。
 *       所以这些路线（铁 / 铜 / 铅 / 锡 / 贵金属的还原）在本表里的 FE 会被<b>低估</b> ——
 *       要读还原剂那一半请看 docs/耗电一览.md §2.5 的当量表；</li>
 *   <li><b>可选的湿法置换炉</b>：本图中没有任何边用到 {@code DISPLACEMENT}，
 *       所以置换路线不参与比价。</li>
 * </ul>
 *
 * <p>所有数值都从代码常量抄来，并在 {@link #MACHINE_STEP} 的注释里点名出处；
 * 改游戏数值后本表要一起改（本模型的唯一意义是「用同一套数复算」，不是另一套平衡）。
 */
public final class ProductionCost {

	private ProductionCost() {
	}

	// ==================================================================== 机器步骤的成本表（全部来自机器 BE 的 public 常量）

	/**
	 * 一台桥接机器每处理 1 个「单位」（= 1 mB 进料，按桥接边的 1:1 抽象）的开销。
	 *
	 * <p>出处（读完 machine BE 抄的，单位换算写在行尾）—— <b>2026-10-17 用户口径</b>：
	 * <ul>
	 *   <li>{@code VEIN_EXTRACTOR}：{@code VeinExtractorBlockEntity.ENERGY_PER_TICK=1024}、
	 *       {@code HARVEST_TICKS=20} → <b>20480 FE / 件</b>、20 刻 / 件（旧 256 FE/t → 5120，本轮 ×4。
	 *       流体版同锚点：100 mB = 0.1 mol 矿浆 / 20 刻 → <b>204800 FE/mol</b>）；</li>
	 *   <li>{@code CRUSHER}：{@code BASE_ENERGY_PER_MB=16×(1+莫氏/4)}、{@code RATE=4 mB/t}。
	 *       本表按「莫氏 5.0」的中间硬度算 <b>36 FE/mB</b>，0.25 刻/mB（旧 18；硬料更贵，见类注释）；</li>
	 *   <li>{@code CENTRIFUGE}：额定 {@code NOMINAL_POWER=128 FE/t}、{@code RATE=8 mB/t}
	 *       → <b>16 FE/mB</b>，0.125 刻/mB（旧 8；{@code MAX_DRAW=384} 是上限，按额定记）；</li>
	 *   <li>{@code MAGNETIC_SEPARATOR}：场强 1.0 = 128 FE/t、{@code RATE=8 mB/t}
	 *       → <b>16 FE/mB</b>，0.125 刻/mB（旧 8）；</li>
	 *   <li>{@code REDUCTION_FURNACE} / {@code ELECTROLYSIS_FURNACE}：<b>本轮起逐配方功率</b>，
	 *       所以不在这张表里 —— 冶炼那 6 条桥接边各自查 {@link #SMELT_STEP}
	 *       （钥匙是 {@link ReactionGraph} 的桥接边 id）。表里保留的两项只是「没有桥接边时的兜底」；</li>
	 *   <li>{@code REACTION_VESSEL} / {@code MIXER}：<b>没有能量字段，0 FE/t</b>；
	 *       桥接烘焙步按机器吞吐 1 份/t 记 1 刻/单位（桥接边没有速率字段，这是约定）。</li>
	 * </ul>
	 */
	public record MachineStep(String machine, double fePerUnit, double ticksPerUnit) {
	}

	// ---------------------------------------------------------------- 冶炼：逐配方功率
	//
	// 2026-10-17 起三台炉子的电耗不再是「基准 × 倍率」：
	//   · 还原炉：每条路线自带 FE/t，燃料驱动的传统火法（高炉 / 反射炉 / 自然金属熔炼）写 0 ——
	//     这些路线一度电都不吃，成本压在还原剂当量上（本模型<b>不记还原剂</b>，
	//     见类注释「不覆盖什么」，所以这些路线的成本会被本模型低估）；
	//   · 电解炉熔盐档：每条路线自带 FE/t（{@code SmelterProfile.ELECTROLYSIS.maxDraw=600} 封顶）；
	//   · 电解炉溶液档：逐反应 FE/mol（本模型直接读 {@code MolBalance.Reaction#fePerMol()}）。
	//
	// 本模型是纯 Java（不能 import Metallurgy），所以把「冶炼那 6 条桥接边」的功率手抄在下面。
	// 改 metal/Metallurgy.java 的路线表时，这里必须一起改。
	//
	// 换算：一个炉次 100 mB 料，还原走 100 刻 / 电解走 120 刻，所以 FE/mB = 路线功率 × 刻/mB。

	/** 还原炉的刻/mB（{@code SmelterProfile.REDUCTION.ticksPerBatch() = 100 刻 / 100 mB}）。 */
	private static final double REDUCTION_TICKS_PER_MB = 1.00;
	/** 电解炉熔盐档的刻/mB（{@code SmelterProfile.ELECTROLYSIS.ticksPerBatch() = 120 刻 / 100 mB}）。 */
	private static final double ELECTROLYSIS_TICKS_PER_MB = 1.20;
	/** 电解炉熔盐档单刻耗电上限（{@code SmelterProfile.ELECTROLYSIS.maxDraw()}）。 */
	private static final double ELECTROLYSIS_MAX_DRAW = 600.0;

	/**
	 * 冶炼那 6 条桥接边的成本（钥匙 = {@link ReactionGraph} 桥接边的 id，含 {@code bridge:} 前缀）。
	 *
	 * <p>每条都写明「走哪台炉子 + 该路线的功率（FE/t，出自 {@code metal/Metallurgy.java} 的路线表）」，
	 * 括号里是本轮之前的老值：
	 * <ul>
	 *   <li>{@code bridge:iron-ingot}：磁铁矿 / 赤铁矿 / 褐铁矿的还原路都是 <b>0 FE/t</b>
	 *       （高炉：焦炭既还原又供热）→ <b>0 FE/mB</b>（旧 40）。这是本轮最大的口径变化：
	 *       「铁 → 锭」这一段的电费直接归零，代价是还原剂当量 1.00 → 1.60 / 100 mB，
	 *       而还原剂不在本模型里 —— 读这张表时要记得这一笔；</li>
	 *   <li>{@code bridge:copper-ingot}：自然铜熔炼 <b>0 FE/t</b> → 0 FE/mB（旧 40），当量 0.15 → 0.40；</li>
	 *   <li>{@code bridge:zinc-ingot}：闪锌矿还原 <b>108 FE/t</b> → 108 FE/mB（旧 40；硫化物先焙烧）；</li>
	 *   <li>{@code bridge:manganese-ingot}：还原 96 FE/t 比熔盐电解 512×1.2 = 614.4 便宜 →
	 *       <b>96 FE/mB</b>（旧 40）；</li>
	 *   <li>{@code bridge:aluminum-ingot}：只能熔盐电解，铝土矿 160×4.20 = 672 被 maxDraw 截到
	 *       <b>600 FE/t</b> → 600 × 1.2 = <b>720 FE/mB</b>（旧 360，正好翻倍）；</li>
	 *   <li>{@code bridge:silicon-ingot}：石英还原 <b>136 FE/t</b>（电弧炉）比电解 544×1.2 便宜 →
	 *       <b>136 FE/mB</b>（旧 40）。</li>
	 * </ul>
	 */
	public static final Map<String, MachineStep> SMELT_STEP = smeltStepTable();

	private static Map<String, MachineStep> smeltStepTable() {
		Map<String, MachineStep> m = new LinkedHashMap<>();
		m.put("bridge:iron-ingot",
				new MachineStep("reduction_furnace", 0.0 * REDUCTION_TICKS_PER_MB, REDUCTION_TICKS_PER_MB));
		m.put("bridge:copper-ingot",
				new MachineStep("reduction_furnace", 0.0 * REDUCTION_TICKS_PER_MB, REDUCTION_TICKS_PER_MB));
		m.put("bridge:zinc-ingot",
				new MachineStep("reduction_furnace", 108.0 * REDUCTION_TICKS_PER_MB, REDUCTION_TICKS_PER_MB));
		m.put("bridge:manganese-ingot",
				new MachineStep("reduction_furnace", 96.0 * REDUCTION_TICKS_PER_MB, REDUCTION_TICKS_PER_MB));
		m.put("bridge:aluminum-ingot", new MachineStep("electrolysis_furnace",
				Math.min(160.0 * 4.20, ELECTROLYSIS_MAX_DRAW) * ELECTROLYSIS_TICKS_PER_MB,
				ELECTROLYSIS_TICKS_PER_MB));
		m.put("bridge:silicon-ingot",
				new MachineStep("reduction_furnace", 136.0 * REDUCTION_TICKS_PER_MB, REDUCTION_TICKS_PER_MB));
		return Collections.unmodifiableMap(m);
	}

	/** 机器步骤成本表（按 {@link Machine} 的 name 取；冶炼三炉走 {@link #SMELT_STEP}）。 */
	public static final Map<String, MachineStep> MACHINE_STEP = machineStepTable();

	private static Map<String, MachineStep> machineStepTable() {
		Map<String, MachineStep> m = new LinkedHashMap<>();
		m.put(Machine.VEIN_EXTRACTOR.name(), new MachineStep("vein_extractor", 1024.0 * 20.0, 20.0));
		m.put(Machine.CRUSHER.name(), new MachineStep("crusher", 16.0 * (1.0 + 5.0 / 4.0), 1.0 / 4.0));
		m.put(Machine.CENTRIFUGE.name(), new MachineStep("centrifuge", 128.0 / 8.0, 1.0 / 8.0));
		m.put(Machine.MAGNETIC_SEPARATOR.name(), new MachineStep("magnetic_separator", 128.0 / 8.0, 1.0 / 8.0));
		// 三炉的兜底值：真实成本逐桥接边查 SMELT_STEP（还原 80 FE/t 与电解 160 FE/t 只是锚点）。
		m.put(Machine.REDUCTION_FURNACE.name(),
				new MachineStep("reduction_furnace", 80.0 * REDUCTION_TICKS_PER_MB, REDUCTION_TICKS_PER_MB));
		m.put(Machine.ELECTROLYSIS_FURNACE.name(),
				new MachineStep("electrolysis_furnace", 160.0 * ELECTROLYSIS_TICKS_PER_MB, ELECTROLYSIS_TICKS_PER_MB));
		// 容器型机器：无能量字段。
		m.put(Machine.REACTION_VESSEL.name(), new MachineStep("reaction_vessel", 0.0, 1.0));
		m.put(Machine.MIXER.name(), new MachineStep("mixer", 0.0, 1.0));
		return Collections.unmodifiableMap(m);
	}

	// ==================================================================== 化学反应机器的参数（同样抄自 BE）

	/** 反应釜 / 搅拌器的吞吐上限：{@code AbstractReactorBlockEntity.RATE_PER_TICK = 1.0} 份/t。 */
	public static final double VESSEL_RATE_PER_TICK = 1.0;

	/** 电解炉溶液档的吞吐上限：{@code SmelterBlockEntity.SOLUTION_RATE_PER_TICK = 0.5} 份/t。 */
	public static final double SOLUTION_RATE_PER_TICK = 0.5;

	/**
	 * 溶液电解的兜底单价（{@code MolBalance.Reaction.DEFAULT_FE_PER_MOL = 120} FE/mol 反应物）。
	 *
	 * <p>2026-10-17 起电解<b>逐反应</b>标价（{@link MolBalance.Reaction#fePerMol()}：
	 * 电解水 150、氯碱 260、钾碱 300、锰酸钾 180、氟化氢 520 FE/mol 反应物），
	 * 旧的平铺常量 {@code SmelterBlockEntity.SOLUTION_FE_PER_MOL} 已删除。
	 * 本常量只作为「反应没标价时」的显示兜底，真实计费一律走 {@link MolBalance.Reaction#fePerMol()}。
	 */
	public static final double SOLUTION_FE_PER_MOL_FALLBACK = MolBalance.Reaction.DEFAULT_FE_PER_MOL;

	/** 矿根「采一件」的开采电（{@code VeinExtractorBlockEntity}：1024 FE/t × 20 刻 = 20480 FE）。 */
	public static final double ROOT_VEIN_FE = 1024.0 * 20.0;
	/** 矿根「采一件」的刻数。 */
	public static final double ROOT_VEIN_TICKS = 20.0;

	/**
	 * 默认是否把「矿根开采电」计入成本。
	 *
	 * <p><b>默认 false</b>：{@link ReactionGraph#ORE_ROOTS} 的定义是「生存起点 / 玩家挖得到」，
	 * 不是「抽取机产物」—— 用手挖矿没有 FE，矿根是<b>劳动</b>换来的，不是电换来的。
	 * 需要看「全靠抽取机自动化」的开销时用
	 * {@link #evaluate(String, double, Objective, boolean)} 传 {@code true}
	 * （每 mol 矿根 = {@link #ROOT_VEIN_FE} FE、{@value #ROOT_VEIN_TICKS} 刻）。
	 */
	public static final boolean DEFAULT_CHARGE_ROOT_EXTRACTION = false;

	/** 松弛迭代上限（环的守卫之一）。 */
	public static final int MAX_ITERATIONS = 512;
	/** 路线回溯的深度上限（环的守卫之二）。 */
	public static final int MAX_TRACE_DEPTH = 64;
	/** 浮点比较死区。 */
	private static final double EPS = 1.0e-9;

	// ==================================================================== 成本 / 目标

	/** 比价口径。 */
	public enum Objective {
		/** 矿根 mol 优先，再比电，再比时间。本轮默认（矿脉丰度正在被另一名代理重调，只看 mol 量）。 */
		ORE_FIRST,
		/** FE 优先。 */
		FE_FIRST,
		/** 机器刻数优先。 */
		TICKS_FIRST
	}

	/**
	 * 一条路线的成本（全部是「每个单位目标」的量，可线性缩放）。
	 *
	 * @param roots       矿根 mol：{@code 根节点 id → mol}
	 * @param fe          机器电耗（含矿根开采电）
	 * @param feExtraction 其中属于矿根开采（抽取机）的那部分
	 * @param ticks       机器刻数（反应釜按反应速率，桥接按机器 RATE）
	 * @param reactionUnits 化学反应推进的「份」数之和
	 * @param bridgeUnits 桥接机器步骤数之和
	 * @param edgeSteps   走过的边数（反应 + 桥接，按 1/c_p 缩放后的计数）
	 */
	public record Cost(Map<String, Double> roots, double fe, double feExtraction, double ticks,
			double reactionUnits, double bridgeUnits, double edgeSteps) {

		public Cost {
			TreeMap<String, Double> clean = new TreeMap<>();
			for (Map.Entry<String, Double> e : roots.entrySet()) {
				if (e.getValue() > 1.0e-9) {
					clean.put(e.getKey(), e.getValue());
				}
			}
			roots = Collections.unmodifiableMap(clean);
		}

		/** 矿根总 mol（= 各根之和）。 */
		public double rootMol() {
			double t = 0.0;
			for (double v : roots.values()) {
				t += v;
			}
			return t;
		}

		/**
		 * <b>稀缺矿根</b> mol：{@link #rootMol()} 扣掉水。
		 *
		 * <p>水是 1 桶就能从水源无限取的介质（{@code water} 在 {@link ReactionGraph#ORE_ROOTS} 里），
		 * 不构成稀缺；比价用这个量，矿脉丰度重调时看的也是它。水仍留在 {@link #roots()} 里，
		 * 好在报告里看到耗了多少。水在 {@link #rootMol()} 里照常计入。
		 */
		public double scarceRootMol() {
			double t = 0.0;
			for (Map.Entry<String, Double> e : roots.entrySet()) {
				if (!"water".equals(e.getKey())) {
					t += e.getValue();
				}
			}
			return t;
		}

		/** 只含开采的加工电耗（总 FE − 开采 FE）。 */
		public double processFe() {
			return fe - feExtraction;
		}
	}

	/** 一次求解的结果。 */
	public record Result(String target, double amount, Cost cost, List<String> path,
			boolean hitIterationCap, Objective objective) {
	}

	// ==================================================================== 入口

	/** 以 {@link Objective#ORE_FIRST} 求 1 个单位 {@code target} 的成本（矿根按手挖、不计开采电）。 */
	public static Result evaluate(String target) {
		return evaluate(target, 1.0, Objective.ORE_FIRST, DEFAULT_CHARGE_ROOT_EXTRACTION);
	}

	/** 求 {@code amount} 个单位 {@code target} 的成本（矿根按手挖、不计开采电）。 */
	public static Result evaluate(String target, double amount, Objective objective) {
		return evaluate(target, amount, objective, DEFAULT_CHARGE_ROOT_EXTRACTION);
	}

	/** 求 {@code amount} 个单位 {@code target} 的成本；{@code chargeRootExtraction} 决定矿根是否计开采电。 */
	public static Result evaluate(String target, double amount, Objective objective,
			boolean chargeRootExtraction) {
		Solve solve = solve(objective, chargeRootExtraction);
		Cost base = solve.costs.get(target);
		if (base == null) {
			return new Result(target, amount, null, List.of("(不可达：从矿根没有任何路线能产出 " + target + ")"),
					solve.capped, objective);
		}
		Cost scaled = scale(base, amount);
		List<String> path = new ArrayList<>();
		Set<String> stack = new LinkedHashSet<>();
		boolean ok = trace(target, amount, solve, path, stack, 0);
		if (!ok && path.isEmpty()) {
			path.add("(回溯被环截断)");
		}
		return new Result(target, amount, scaled, List.copyOf(path), solve.capped, objective);
	}

	/** 一桶（{@link MolStock#BUCKET_MOL}=15 mol）某物质的成本（矿根按手挖）。 */
	public static Result bucketCost(String speciesId, Objective objective) {
		return evaluate(speciesId, MolStock.BUCKET_MOL, objective, DEFAULT_CHARGE_ROOT_EXTRACTION);
	}

	/** 一桶某物质的成本；{@code chargeRootExtraction} 决定矿根是否计开采电。 */
	public static Result bucketCost(String speciesId, Objective objective, boolean chargeRootExtraction) {
		return evaluate(speciesId, MolStock.BUCKET_MOL, objective, chargeRootExtraction);
	}

	// ==================================================================== 吞吐（用户真正在意的那一半）

	/**
	 * 一份酸「处理一个锭」要多少 mol 酸。
	 *
	 * <p>口径：在 {@link MolRecipes} 里找「金属单质 + 酸 → … + H2」的反应（规则 5 生成的那几条），
	 * 取 {@code 酸系数 / 金属系数 × }{@link MolStock#INGOT_MOL}。一个锭 = 0.1 mol 金属粉。
	 *
	 * @return 每个锭要的酸 mol；找不到这样的反应返回 {@code -1}
	 */
	public static double acidPerIngot(String acidId, String metalSpeciesId) {
		for (MolBalance.Reaction r : MolRecipes.all()) {
			double metal = 0.0;
			double acid = 0.0;
			boolean hydrogen = false;
			for (MolBalance.Reactant x : r.reactants()) {
				if (x.id().equals(metalSpeciesId)) {
					metal = x.coeff();
				}
				if (x.id().equals(acidId)) {
					acid = x.coeff();
				}
			}
			for (MolBalance.Product p : r.products()) {
				if (p.id().equals("hydrogen")) {
					hydrogen = true;
				}
			}
			if (metal > 0.0 && acid > 0.0 && hydrogen) {
				return acid / metal * MolStock.INGOT_MOL;
			}
		}
		return -1.0;
	}

	/**
	 * 一桶酸能处理多少个锭（{@code BUCKET_MOL / acidPerIngot}）。
	 *
	 * @return 找不到「金属 + 酸 → 盐 + H2」路线返回 {@code -1}
	 */
	public static double ingotsPerBucket(String acidId, String metalSpeciesId) {
		double per = acidPerIngot(acidId, metalSpeciesId);
		return per <= 0.0 ? -1.0 : MolStock.BUCKET_MOL / per;
	}

	// ==================================================================== 求解核心

	/**
	 * 一条边的解析形态（化学反应或桥接步骤统一）。
	 *
	 * @param bridgeId 桥接边的 id（含 {@code bridge:} 前缀，见 {@link ReactionGraph}）；
	 *                 化学边为 {@code null}。冶炼那 6 条用它去查 {@link #SMELT_STEP}
	 */
	private record Edge(boolean chemical, String[] reactants, double[] rc, String[] products, double[] pc,
			MolBalance.Reaction reaction, String label, Set<Machine> machines, String bridgeId) {
	}

	private static final class Solve {
		final Map<String, Cost> costs = new LinkedHashMap<>();
		final Map<String, Edge> producerEdge = new LinkedHashMap<>();
		final Map<String, Integer> producerProduct = new LinkedHashMap<>();
		final Map<String, String> producerMachine = new LinkedHashMap<>();
		boolean capped;

		Cost best(String target, Objective objective) {
			return costs.get(target);
		}
	}

	private static Solve solve(Objective objective, boolean chargeRootExtraction) {
		List<Edge> edges = buildEdges();
		Solve solve = new Solve();

		// 1) 矿根：免费起点 + 开采电（水取水源，不计开采电）
		for (String root : ReactionGraph.ORE_ROOTS) {
			boolean water = "water".equals(root);
			Map<String, Double> roots = new LinkedHashMap<>();
			roots.put(root, 1.0);
			double fe = water || !chargeRootExtraction ? 0.0 : ROOT_VEIN_FE;
			double ticks = water || !chargeRootExtraction ? 0.0 : ROOT_VEIN_TICKS;
			Cost c = new Cost(roots, fe, fe, ticks, 0.0, 0.0, 0.0);
			putIfBetter(solve, root, c, null, -1, null, objective);
		}

		// 2) 迭代松弛到不动点（或迭代上限）
		boolean changed = true;
		int iter = 0;
		for (; iter < MAX_ITERATIONS && changed; iter++) {
			changed = false;
			for (Edge e : edges) {
				if (e.chemical) {
					changed |= relaxChemical(solve, e, objective);
				} else {
					changed |= relaxBridge(solve, e, objective);
				}
			}
		}
		solve.capped = changed; // 到上限仍未收敛
		return solve;
	}

	/** 化学反应：对每个产物试算 {@code Σ (c_r/c_p)·cost(r) + 本反应开销}。 */
	private static boolean relaxChemical(Solve solve, Edge e, Objective objective) {
		boolean changed = false;
		MolBalance.Reaction r = e.reaction;
		MachineHost host = hostOf(r);
		double reactantMol = 0.0;
		for (MolBalance.Reactant x : r.reactants()) {
			reactantMol += x.coeff();
		}
		for (int pi = 0; pi < e.products.length; pi++) {
			double cProd = e.pc[pi];
			if (cProd <= 0.0) {
				continue;
			}
			double progress = 1.0 / cProd; // 产出 1 个该产物要推几「份」
			double fe = host.electrolysis ? e.reaction.fePerMol() * reactantMol * progress : 0.0;
			double ticks = progress / host.effectiveRate;
			Map<String, Double> roots = new LinkedHashMap<>();
			double childFe = 0.0;
			double childFeExtraction = 0.0;
			double childTicks = 0.0;
			double childReaction = 0.0;
			double childBridge = 0.0;
			double childSteps = 0.0;
			boolean complete = true;
			for (int ri = 0; ri < e.reactants.length; ri++) {
				Cost c = solve.costs.get(e.reactants[ri]);
				if (c == null) {
					complete = false;
					break;
				}
				double scale = e.rc[ri] * progress;
				mergeRoots(roots, c.roots(), scale);
				childFe += c.fe() * scale;
				childFeExtraction += c.feExtraction() * scale;
				childTicks += c.ticks() * scale;
				childReaction += c.reactionUnits() * scale;
				childBridge += c.bridgeUnits() * scale;
				childSteps += c.edgeSteps() * scale;
			}
			if (!complete) {
				continue;
			}
			Cost cand = new Cost(roots, childFe + fe, childFeExtraction, childTicks + ticks,
					childReaction + progress, childBridge, childSteps + progress);
			changed |= putIfBetter(solve, e.products[pi], cand, e, pi, host.label, objective);
		}
		return changed;
	}

	/** 桥接步骤：{@code cost(from) + min(机器开销)}。 */
	private static boolean relaxBridge(Solve solve, Edge e, Objective objective) {
		Cost from = solve.costs.get(e.reactants[0]);
		if (from == null) {
			return false;
		}
		// 冶炼那条接缝先查逐配方表：成本挂在「哪条桥接边」上（哪台炉子 + 该路线自己的 FE/t），
		// 不挂在机器上。其余桥接步仍按机器取 MACHINE_STEP，并按比价口径挑最省的那台。
		MachineStep best = e.bridgeId() == null ? null : SMELT_STEP.get(e.bridgeId());
		String bestName = best == null ? null : best.machine();
		if (best == null) {
			for (Machine m : e.machines) {
				MachineStep step = MACHINE_STEP.get(m.name());
				if (step == null) {
					continue;
				}
				if (best == null || betterStep(step, best, objective)) {
					best = step;
					bestName = m.name();
				}
			}
		}
		if (best == null) {
			return false;
		}
		Cost cand = new Cost(from.roots(), from.fe() + best.fePerUnit(), from.feExtraction(),
				from.ticks() + best.ticksPerUnit(), from.reactionUnits(), from.bridgeUnits() + 1.0,
				from.edgeSteps() + 1.0);
		return putIfBetter(solve, e.products[0], cand, e, 0, bestName, objective);
	}

	/** 反应宿主与有效速率（催化剂按在位、搅拌能上就上 —— 都是「最好情况」）。 */
	private record MachineHost(boolean electrolysis, double effectiveRate, String label) {
	}

	private static MachineHost hostOf(MolBalance.Reaction r) {
		if (r.kind() == MolBalance.Kind.ELECTROLYSIS) {
			double effective = SOLUTION_RATE_PER_TICK * r.rate();
			return new MachineHost(true, effective, "electrolysis_furnace");
		}
		boolean mixer = r.machines().contains(Machine.MIXER);
		double modifier = 1.0;
		if (mixer) {
			modifier *= MolBalance.Reaction.STIRRING_RATE_BONUS; // 搅拌提速（搅拌器）
		}
		if (r.catalysts().length > 0) {
			modifier *= MolBalance.Reaction.CATALYST_RATE_BONUS; // 催化剂在位（不消耗）
		}
		double effective = VESSEL_RATE_PER_TICK * r.rate() * modifier;
		String label = mixer ? "mixer" : "reaction_vessel";
		if (r.catalysts().length > 0) {
			label += "+catalyst";
		}
		return new MachineHost(false, effective, label);
	}

	private static boolean betterStep(MachineStep a, MachineStep b, Objective objective) {
		return switch (objective) {
			case ORE_FIRST, FE_FIRST -> a.fePerUnit() < b.fePerUnit() - EPS
					|| (Math.abs(a.fePerUnit() - b.fePerUnit()) <= EPS && a.ticksPerUnit() < b.ticksPerUnit() - EPS);
			case TICKS_FIRST -> a.ticksPerUnit() < b.ticksPerUnit() - EPS
					|| (Math.abs(a.ticksPerUnit() - b.ticksPerUnit()) <= EPS && a.fePerUnit() < b.fePerUnit() - EPS);
		};
	}

	private static boolean putIfBetter(Solve solve, String node, Cost cand, Edge edge, int product,
			String machine, Objective objective) {
		Cost old = solve.costs.get(node);
		if (old != null && !better(cand, old, objective)) {
			return false;
		}
		solve.costs.put(node, cand);
		if (edge == null) {
			solve.producerEdge.remove(node);
			solve.producerProduct.remove(node);
			solve.producerMachine.remove(node);
		} else {
			solve.producerEdge.put(node, edge);
			solve.producerProduct.put(node, product);
			solve.producerMachine.put(node, machine);
		}
		return true;
	}

	private static boolean better(Cost a, Cost b, Objective objective) {
		switch (objective) {
			case ORE_FIRST: {
				int c = cmp(a.scarceRootMol(), b.scarceRootMol());
				if (c != 0) {
					return c < 0;
				}
				c = cmp(a.fe(), b.fe());
				if (c != 0) {
					return c < 0;
				}
				return cmp(a.ticks(), b.ticks()) < 0;
			}
			case FE_FIRST: {
				int c = cmp(a.fe(), b.fe());
				if (c != 0) {
					return c < 0;
				}
				c = cmp(a.scarceRootMol(), b.scarceRootMol());
				if (c != 0) {
					return c < 0;
				}
				return cmp(a.ticks(), b.ticks()) < 0;
			}
			case TICKS_FIRST: {
				int c = cmp(a.ticks(), b.ticks());
				if (c != 0) {
					return c < 0;
				}
				c = cmp(a.fe(), b.fe());
				if (c != 0) {
					return c < 0;
				}
				return cmp(a.rootMol(), b.rootMol()) < 0;
			}
		}
		return false;
	}

	private static int cmp(double a, double b) {
		if (Math.abs(a - b) <= EPS) {
			return 0;
		}
		return Double.compare(a, b);
	}

	// ==================================================================== 边表

	private static List<Edge> buildEdges() {
		List<Edge> out = new ArrayList<>();
		for (MolBalance.Reaction r : MolRecipes.all()) {
			String[] rs = new String[r.reactants().length];
			double[] rc = new double[r.reactants().length];
			for (int i = 0; i < r.reactants().length; i++) {
				rs[i] = r.reactants()[i].id();
				rc[i] = r.reactants()[i].coeff();
			}
			String[] ps = new String[r.products().length];
			double[] pc = new double[r.products().length];
			for (int i = 0; i < r.products().length; i++) {
				ps[i] = r.products()[i].id();
				pc[i] = r.products()[i].coeff();
			}
			out.add(new Edge(true, rs, rc, ps, pc, r, label(r), r.machines(), null));
		}
		for (ReactionGraph.Edge be : ReactionGraph.allEdges()) {
			if (be.chemical()) {
				continue;
			}
			out.add(new Edge(false, new String[] { be.reactants().get(0) }, new double[] { 1.0 },
					new String[] { be.products().get(0) }, new double[] { 1.0 }, null, be.label(),
					be.machines(), be.id()));
		}
		return out;
	}

	private static String label(MolBalance.Reaction r) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < r.reactants().length; i++) {
			if (i > 0) {
				sb.append(" + ");
			}
			sb.append(r.reactants()[i].coeff()).append(' ').append(r.reactants()[i].id());
		}
		sb.append(" -> ");
		for (int i = 0; i < r.products().length; i++) {
			if (i > 0) {
				sb.append(" + ");
			}
			sb.append(r.products()[i].coeff()).append(' ').append(r.products()[i].id());
		}
		sb.append("  [rate=").append(r.rate()).append(", ").append(r.kind()).append(']');
		return sb.toString();
	}

	// ==================================================================== 路线回溯

	private static boolean trace(String node, double amount, Solve solve, List<String> out,
			Set<String> stack, int depth) {
		if (depth > MAX_TRACE_DEPTH) {
			out.add(indent(depth) + node + " ×" + fmt(amount) + "   (depth cap)");
			return false;
		}
		if (!stack.add(node)) {
			out.add(indent(depth) + node + " ×" + fmt(amount) + "   (cycle)");
			return false;
		}
		Edge e = solve.producerEdge.get(node);
		Cost c = solve.costs.get(node);
		if (e == null) {
			// 根节点
			String note = ReactionGraph.ORE_ROOTS.contains(node)
					? ("矿根/" + ("water".equals(node) ? "水源（无限）"
							: "手挖" + (DEFAULT_CHARGE_ROOT_EXTRACTION ? "（已按抽取机计 " + (int) ROOT_VEIN_FE + " FE）"
									: "；若走抽取机另加 " + (int) ROOT_VEIN_FE + " FE/件")))
					: "无生产者";
			out.add(indent(depth) + node + " ×" + fmt(amount) + "   [" + note + "]");
			stack.remove(node);
			return true;
		}
		String machine = solve.producerMachine.get(node);
		if (e.chemical) {
			MachineHost host = hostOf(e.reaction);
			double progress = amount / e.pc[solve.producerProduct.get(node)];
			out.add(indent(depth) + e.label + "   via " + machine + " · " + fmt(progress) + " 份 · "
					+ fmt(progress / host.effectiveRate) + " 刻"
					+ (host.electrolysis
							? " · " + fmt(e.reaction.fePerMol() * sum(e.rc) * progress)
									+ " FE（单价 " + fmt(e.reaction.fePerMol()) + " FE/mol 反应物）"
							: " · 0 FE"));
			for (int i = 0; i < e.reactants.length; i++) {
				trace(e.reactants[i], amount * e.rc[i] / e.pc[solve.producerProduct.get(node)],
						solve, out, stack, depth + 1);
			}
		} else {
			MachineStep step = MACHINE_STEP.get(machine);
			out.add(indent(depth) + e.label + "   via " + machine
					+ (step == null ? "" : " · " + fmt(step.fePerUnit()) + " FE · " + fmt(step.ticksPerUnit()) + " 刻"));
			trace(e.reactants[0], amount, solve, out, stack, depth + 1);
		}
		stack.remove(node);
		return true;
	}

	private static String indent(int depth) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < depth; i++) {
			sb.append("  ");
		}
		return sb.toString();
	}

	// ==================================================================== 工具

	private static Cost scale(Cost c, double scale) {
		Map<String, Double> roots = new LinkedHashMap<>();
		mergeRoots(roots, c.roots(), scale);
		return new Cost(roots, c.fe() * scale, c.feExtraction() * scale, c.ticks() * scale,
				c.reactionUnits() * scale, c.bridgeUnits() * scale, c.edgeSteps() * scale);
	}

	private static void mergeRoots(Map<String, Double> into, Map<String, Double> roots, double scale) {
		for (Map.Entry<String, Double> e : roots.entrySet()) {
			double v = e.getValue() * scale;
			if (v > EPS) {
				into.merge(e.getKey(), v, Double::sum);
			}
		}
	}

	private static double sum(double[] xs) {
		double t = 0.0;
		for (double x : xs) {
			t += x;
		}
		return t;
	}

	/** 数字格式化（给文档与日志用，最多 4 位小数）。 */
	public static String fmt(double v) {
		if (v == 0.0) {
			return "0";
		}
		if (Math.abs(v - Math.rint(v)) < 1.0e-6 && Math.abs(v) < 1.0e12) {
			return Long.toString(Math.round(v));
		}
		return String.format(java.util.Locale.ROOT, "%.4f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
	}

	/** 直接把一个结果打成人读的文本（供 harness 与文档复算）。 */
	public static String describe(Result r) {
		if (r.cost() == null) {
			return r.target() + " ×" + fmt(r.amount()) + "：不可达（" + String.join("; ", r.path()) + "）";
		}
		Cost c = r.cost();
		StringBuilder sb = new StringBuilder();
		sb.append(r.target()).append(" ×").append(fmt(r.amount())).append('\n');
		sb.append("  口径=").append(r.objective()).append("  稀缺矿根 mol=").append(fmt(c.scarceRootMol())).append("  矿根总 mol=").append(fmt(c.rootMol()))
				.append("  FE=").append(fmt(c.fe())).append("（其中开采 ").append(fmt(c.feExtraction()))
				.append("）  刻=").append(fmt(c.ticks())).append("  反应份=").append(fmt(c.reactionUnits()))
				.append("  桥接步=").append(fmt(c.bridgeUnits())).append("  边=").append(fmt(c.edgeSteps()));
		if (r.hitIterationCap()) {
			sb.append("\n  ⚠ 达到迭代上限 ").append(MAX_ITERATIONS).append(" 仍未不动点，结果可能不是最优。");
		}
		sb.append("\n  矿根明细：");
		if (c.roots().isEmpty()) {
			sb.append("(无)");
		} else {
			List<String> parts = new ArrayList<>();
			for (Map.Entry<String, Double> e : c.roots().entrySet()) {
				parts.add(e.getKey() + "=" + fmt(e.getValue()));
			}
			sb.append(String.join(", ", parts));
		}
		sb.append("\n  路线：\n");
		for (String line : r.path()) {
			sb.append("    ").append(line).append('\n');
		}
		return sb.toString();
	}
}
