package com.geometallurgy.chemical;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则生成的化学底座：离子价态、常见酸根、金属活动性序、化学式解析。
 *
 * <p><b>纯数据 + 纯函数，无 {@code net.minecraft} 依赖</b> —— 可在普通 JVM 里直接跑
 * （验证 harness 就是这么做的）。这是 §十三 13.2/13.3「标签 + 少量类规则 → 现场生成配方」的
 * 第一步：本轮<b>只提供底座</b>，不生成任何反应、不动机器逻辑。
 *
 * <p>设计哲学与 {@link ChemicalTags} 一致：<b>能由公式推的绝不写死</b>，
 * 推不出 / 推不准的一律进显式表（{@link #FORMULA_ALIASES}），未知或歧义一律返回
 * {@code null} / 空集，<b>绝不猜一个错误价态</b>。
 *
 * <h2>能力</h2>
 * <ul>
 *   <li>金属阳离子价态（多价态）：Fe²⁺/Fe³⁺、Cu⁺/Cu²⁺、Mn²⁺/Mn⁴⁺/Mn⁷⁺、Sn²⁺/Sn⁴⁺、
 *       Cr³⁺、V⁵⁺ ……，覆盖当前 64 种物质与矿表里出现的全部金属。</li>
 *   <li>常见酸根（{@link #anionGroups()}）：OH⁻、Cl⁻、F⁻、NO₃⁻、SO₄²⁻、HSO₄⁻、HCO₃⁻、
 *       CO₃²⁻、PO₄³⁻、C₂O₄²⁻、S²⁻、MnO₄⁻、O²⁻、H⁺（H 当阳离子）、NH₄⁺、ClO⁻。</li>
 *   <li>金属活动性序（{@link #activityRank(String)} / {@link #isAboveHydrogen(String)}）：
 *       Cs&gt;Rb&gt;K&gt;Ba&gt;Sr&gt;Ca&gt;Na&gt;Li&gt;Mg&gt;RE&gt;Al&gt;Mn&gt;Zn&gt;Cr&gt;Fe&gt;Cd&gt;Co&gt;Ni&gt;Sn&gt;Pb&gt;(H)&gt;Cu&gt;Hg&gt;Ag&gt;Pt&gt;Au。</li>
 *   <li>公式解析（{@link #parse(String)}）：括号、下标、多组分「·」水合物、元素计数、
 *       <b>唯一价态解</b>与总电荷；支持伪元素 {@code L} / {@code RE} / {@code H}（重稀土，
 *       经显式别名表重解释）。</li>
 *   <li>下一步规则要用的查询：{@link #cationOf}、{@link #anionOf}、{@link #chargeOf}、
 *       {@link #valenceOf}、{@link #isMetal}，以及结构判定
 *       （{@link #isMetalOxide} / {@link #isHydroxide} / {@link #isCarbonate} /
 *       {@link #isSulfate} / {@link #isHalide} / {@link #isSulfide}；标签全部转发给
 *       {@link ChemicalTags}，不重复实现）。</li>
 * </ul>
 *
 * <h2>公式解析的诚实边界</h2>
 * 解析器要求「每种元素只有一个氧化态」使全式电荷归零。若一个式子里同一元素必须同时
 * 取两种价态（如 NH₄NO₃ 的两条 N），解析器按整式无法求解 —— 但本仓库把 NO₃⁻ 当
 * 独立酸根记号，NH₄ 当独立阳离子记号，因此实际都能解。真正解不出 / 多解时返回
 * {@code chargeResolved=false}，由显式表或人工接管。
 */
public final class ChemicalData {

	private ChemicalData() {
	}

	// ==================================================================== 价态表

	/** 元素 / 伪元素 → 可能氧化态（第一个是「最常见」）。 */
	private static final Map<String, int[]> VALENCES = new LinkedHashMap<>();

	private static void val(String symbol, int... states) {
		VALENCES.put(symbol, states);
	}

	/** 非金属（含类金属）。{@link #isMetal} 判断的另一半。 */
	private static final Set<String> NONMETALS = Set.of(
			"H", "B", "C", "N", "O", "F", "Si", "P", "S", "Cl", "As", "Se", "Br", "I", "Te");

	static {
		// 碱金属 / 碱土金属
		val("H", 1);
		val("Li", 1);
		val("Na", 1);
		val("K", 1);
		val("Rb", 1);
		val("Cs", 1);
		val("Be", 2);
		val("Mg", 2);
		val("Ca", 2);
		val("Sr", 2);
		val("Ba", 2);
		// 主族
		val("B", 3);
		val("Al", 3);
		val("C", 4, 2, -4);
		val("Si", 4);
		val("N", 5, 3, 4, 2, -3);
		val("P", 5, 3, -3);
		val("O", -2);
		val("F", -1);
		val("S", 6, 4, 2, -2);
		val("Cl", -1, 1, 3, 5, 7);
		// 过渡金属（Fe2+/Fe3+、Cu+/Cu2+、Mn2+/Mn4+/Mn7+、Sn2+/Sn4+、Cr3+、V5+…）
		val("Ti", 4, 3, 2);
		val("V", 5, 4, 3, 2);
		val("Cr", 3, 6, 2);
		val("Mn", 2, 4, 7, 3, 6);
		val("Fe", 3, 2);
		val("Co", 2, 3);
		val("Ni", 2, 3);
		val("Cu", 2, 1);
		val("Zn", 2);
		val("Zr", 4);
		val("Nb", 5, 3);
		val("Mo", 6, 4);
		val("Ag", 1, 2);
		val("Sn", 2, 4);
		val("Sb", 3, 5);
		val("I", -1, 1, 5, 7);
		val("W", 6, 4);
		val("Pt", 2, 4);
		val("Au", 3, 1);
		val("Pb", 2, 4);
		val("Bi", 3, 5);
		// 类金属 / 其他非金属
		val("As", 5, 3, -3);
		val("Se", 6, 4, -2);
		val("Br", -1, 1, 5);
		val("Te", 6, 4, -2);
		// 稀土 / 锕系
		val("Sc", 3);
		val("Y", 3);
		val("La", 3);
		val("Ce", 3, 4);
		val("Pr", 3);
		val("Nd", 3);
		val("Sm", 3);
		val("Eu", 3, 2);
		val("Gd", 3);
		val("Tb", 3, 4);
		val("Dy", 3);
		val("Ho", 3);
		val("Er", 3);
		val("Tm", 3);
		val("Yb", 3, 2);
		val("Lu", 3);
		val("Th", 4);
		val("U", 6, 4);
		// 本仓库 Chemical 表里的伪元素占位符（L=轻稀土、RE=稀土、HRE=重稀土）
		val("RE", 3);
		val("L", 3);
		val("HRE", 3);
	}

	/** 某种元素 / 伪元素的全部已知价态（拷贝；未知返回空数组）。 */
	public static int[] valencesOf(String element) {
		int[] v = VALENCES.get(element);
		return v == null ? new int[0] : v.clone();
	}

	/** 最常见价态；未知返回 {@code null}（不猜）。 */
	public static Integer valenceOf(String element) {
		int[] v = VALENCES.get(element);
		return v == null || v.length == 0 ? null : v[0];
	}

	/** 是不是已知元素 / 伪元素符号。 */
	public static boolean isKnownElement(String element) {
		return element != null && VALENCES.containsKey(element);
	}

	/** 是不是金属（含稀土伪元素；类金属与非金属返回 false）。 */
	public static boolean isMetal(String element) {
		return element != null && VALENCES.containsKey(element) && !NONMETALS.contains(element);
	}

	// ==================================================================== 活动性序

	/**
	 * 金属活动性序（从左到右活动性递减）。位置 {@code RE/L/HRE} 是稀土占位符的近似档位
	 * （真实稀土约在 Mg~Al 之间）。{@code H} 是「氢」参照点，不代表金属。
	 */
	private static final List<String> ACTIVITY = List.of(
			"Cs", "Rb", "K", "Ba", "Sr", "Ca", "Na", "Li", "Mg", "RE", "L", "HRE",
			"Al", "Mn", "Zn", "Cr", "Fe", "Cd", "Co", "Ni", "Sn", "Pb",
			"H",
			"Cu", "Hg", "Ag", "Pt", "Au");

	private static final Map<String, Integer> ACTIVITY_RANK = new LinkedHashMap<>();

	static {
		for (int i = 0; i < ACTIVITY.size(); i++) {
			ACTIVITY_RANK.put(ACTIVITY.get(i), i);
		}
	}

	/** 活动性序列表（只读）。 */
	public static List<String> activitySeries() {
		return ACTIVITY;
	}

	/** 活动性名次：0 = 最活泼；未知元素返回 -1。 */
	public static int activityRank(String element) {
		Integer r = ACTIVITY_RANK.get(element);
		return r == null ? -1 : r;
	}

	/** 是否排在氢之前（即能与稀酸置换出 H₂）。未知元素返回 false。 */
	public static boolean isAboveHydrogen(String element) {
		int r = activityRank(element);
		int h = activityRank("H");
		return r >= 0 && r < h;
	}

	/** a 是否比 b 更活泼（未知一律 false）。 */
	public static boolean isMoreActive(String a, String b) {
		int ra = activityRank(a);
		int rb = activityRank(b);
		return ra >= 0 && rb >= 0 && ra < rb;
	}

	// ==================================================================== 酸根 / 阳离子基团

	/** 常见酸根：记号 → 电荷。 */
	private static final Map<String, Integer> ANION_GROUPS = new LinkedHashMap<>();

	/** 多原子阳离子：记号 → 电荷。 */
	private static final Map<String, Integer> CATION_GROUPS = new LinkedHashMap<>();

	/** 记号 → 展开的元素计数（用于「元素计数」表）。 */
	private static final Map<String, Map<String, Integer>> GROUP_ATOMS = new LinkedHashMap<>();

	private static void anion(String group, int charge, String... atoms) {
		ANION_GROUPS.put(group, charge);
		GROUP_ATOMS.put(group, counts(atoms));
	}

	private static void cation(String group, int charge, String... atoms) {
		CATION_GROUPS.put(group, charge);
		GROUP_ATOMS.put(group, counts(atoms));
	}

	private static Map<String, Integer> counts(String... pairs) {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < pairs.length; i += 2) {
			m.put(pairs[i], Integer.parseInt(pairs[i + 1]));
		}
		return Collections.unmodifiableMap(m);
	}

	static {
		anion("HC2O4", -1, "H", "1", "C", "2", "O", "4");
		anion("HSO4", -1, "H", "1", "S", "1", "O", "4");
		anion("HCO3", -1, "H", "1", "C", "1", "O", "3");
		// 甲酸根 HCO2⁻（甲酸钠 NaHCO2 的酸根）；没有它 NaHCO2 会被解成 Na⁺ + O²⁻，
		// 进而被误标成「金属氧化物」，污染规则 2。
		anion("HCO2", -1, "H", "1", "C", "1", "O", "2");
		anion("C2O4", -2, "C", "2", "O", "4");
		anion("MnO4", -1, "Mn", "1", "O", "4");
		anion("SO4", -2, "S", "1", "O", "4");
		anion("CO3", -2, "C", "1", "O", "3");
		anion("NO3", -1, "N", "1", "O", "3");
		anion("PO4", -3, "P", "1", "O", "4");
		anion("ClO", -1, "Cl", "1", "O", "1");
		anion("OH", -1, "O", "1", "H", "1");
		cation("NH4", 1, "N", "1", "H", "4");
	}

	/** 记号 → 电荷，酸根与阳离子基团的并集。 */
	private static final Map<String, Integer> GROUP_CHARGES = new LinkedHashMap<>();

	/**
	 * <b>物种级</b>记号电荷覆盖：同一个记号在不同物种里价态不同时用。
	 *
	 * <p>当前只有一处：锰酸钾 {@code K2MnO4} 里的 {@code MnO4} 是<b>锰酸根 MnO₄²⁻</b>，
	 * 与高锰酸钾 {@code KMnO4} 里的<b>高锰酸根 MnO₄⁻</b> 同记号不同价态。
	 * 全局酸根表只能登记一个（默认 -1 = 高锰酸根），锰酸根在此按物种显式覆盖，
	 * 而不是把某一方的价态猜错（见 bug#40）。
	 */
	private static final Map<String, Map<String, Integer>> UNIT_CHARGE_OVERRIDES = new LinkedHashMap<>();

	/** 登记一条「某物种里某记号取某个电荷」。 */
	private static void chargeOverride(String speciesId, String unit, int charge) {
		UNIT_CHARGE_OVERRIDES.computeIfAbsent(speciesId, k -> new LinkedHashMap<>()).put(unit, charge);
	}

	/** 解析时按长度降序尝试的记号（避免 SO4 被拆成 S+O4）。 */
	private static final List<String> GROUP_TOKENS = new ArrayList<>();

	static {
		GROUP_CHARGES.putAll(ANION_GROUPS);
		GROUP_CHARGES.putAll(CATION_GROUPS);
		GROUP_TOKENS.addAll(GROUP_CHARGES.keySet());
		GROUP_TOKENS.sort(Comparator.comparingInt(String::length).reversed());
		// 锰酸钾：MnO4 在这里是 MnO4²⁻（锰酸根），不是全局表默认的 MnO4⁻（高锰酸根）。
		chargeOverride("potassium_manganate", "MnO4", -2);
	}

	/** 常见酸根（记号 → 电荷，只读）。 */
	public static Map<String, Integer> anionGroups() {
		return Collections.unmodifiableMap(ANION_GROUPS);
	}

	/** 多原子阳离子（记号 → 电荷，只读）。 */
	public static Map<String, Integer> cationGroups() {
		return Collections.unmodifiableMap(CATION_GROUPS);
	}

	/** 物种级记号电荷覆盖（物种 id → 记号 → 电荷，只读）。审计 / harness 用。 */
	public static Map<String, Map<String, Integer>> unitChargeOverrides() {
		Map<String, Map<String, Integer>> copy = new LinkedHashMap<>();
		UNIT_CHARGE_OVERRIDES.forEach((k, v) -> copy.put(k, Collections.unmodifiableMap(v)));
		return Collections.unmodifiableMap(copy);
	}

	// ==================================================================== 显式表（能推的绝不写死）

	/** 物种 id → 公式里某个符号的重解释（伪元素 H = 重稀土）。 */
	private static final Map<String, Map<String, String>> FORMULA_ALIASES = new LinkedHashMap<>();

	private static void alias(String speciesId, String from, String to) {
		FORMULA_ALIASES.computeIfAbsent(speciesId, k -> new LinkedHashMap<>()).put(from, to);
	}

	/** 被显式别名表重解释过的物种 id（只读）。供审计 / harness 汇报用。 */
	public static java.util.Set<String> aliasOverriddenSpecies() {
		return java.util.Collections.unmodifiableSet(FORMULA_ALIASES.keySet());
	}

	/** 某个物种的别名表（空表表示纯公式推导）。 */
	public static Map<String, String> aliasesOf(String speciesId) {
		return FORMULA_ALIASES.getOrDefault(speciesId, Map.of());
	}

	static {
		// 重稀土在表里写作 H（见 Chemical.heavy_rare_earth_*）：H 默认是氢，这三条必须显式纠正。
		alias("heavy_rare_earth_chloride", "H", "HRE");
		alias("heavy_rare_earth_oxide", "H", "HRE");
		alias("heavy_rare_earth_oxalate", "H", "HRE");
	}

	// ==================================================================== 公式解析

	/** 解析结果。 */
	public record Formula(
			String source,
			Map<String, Integer> atoms,
			Map<String, Integer> units,
			Map<String, Integer> unitCharges,
			int charge,
			boolean chargeResolved,
			int solutions,
			List<String> problems) {

		/** 公式记号全部识别、括号配平。 */
		public boolean parsed() {
			return problems.isEmpty();
		}

		/** 总电荷文本；未解出时返回 {@code "?"}。 */
		public String chargeText() {
			if (!chargeResolved) {
				return "?";
			}
			if (charge == 0) {
				return "0";
			}
			return charge > 0 ? "+" + charge : Integer.toString(charge);
		}

		/** 某个元素 / 记号的整数计数；没有返回 0。 */
		public int count(String token) {
			return atoms.getOrDefault(token, 0);
		}
	}

	/** 一个离子（阳离子或酸根）。 */
	public record Ion(String token, int charge, int count) {

		/** 形如 {@code Fe3+} / {@code SO4 2-} / {@code OH-}。 */
		public String label() {
			if (charge == 0) {
				return token;
			}
			return token + " " + Math.abs(charge) + (charge > 0 ? "+" : "-");
		}

		/** 这个离子是不是金属（含稀土伪元素）。 */
		public boolean metal() {
			return isMetal(token);
		}
	}

	/** 解析纯公式串（不做物种别名，不套物种级电荷覆盖）。 */
	public static Formula parse(String formula) {
		return parse(formula, Map.of(), Map.of());
	}

	private static Formula parse(String formula, Map<String, String> alias) {
		return parse(formula, alias, Map.of());
	}

	private static Formula parse(String formula, Map<String, String> alias,
			Map<String, Integer> chargeOverrides) {
		if (formula == null || formula.isEmpty()) {
			return new Formula(formula, Map.of(), Map.of(), Map.of(), 0, false, 0,
					List.of("空公式"));
		}
		Map<String, Integer> units = new LinkedHashMap<>();
		List<String> problems = new ArrayList<>();
		for (String raw : formula.split("·")) {
			if (raw.isEmpty()) {
				continue;
			}
			// 水合物写法允许「·10H2O」这种组分系数
			int lead = 1;
			int d = 0;
			while (d < raw.length() && raw.charAt(d) >= '0' && raw.charAt(d) <= '9') {
				d++;
			}
			if (d > 0) {
				lead = Integer.parseInt(raw.substring(0, d));
				raw = raw.substring(d);
			}
			final int mult = lead;
			Reader reader = new Reader(raw, alias);
			reader.parse();
			reader.units.forEach((k, v) -> units.merge(k, v * mult, Integer::sum));
			problems.addAll(reader.problems);
		}
		Map<String, Integer> atoms = expandAtoms(units);
		Solve solve = solveCharge(units, chargeOverrides);
		problems.addAll(solve.problems());
		return new Formula(formula, immutable(atoms), immutable(units),
				immutable(solve.charges()), solve.charge(), solve.resolved(),
				solve.solutions(), List.copyOf(problems));
	}

	private static Map<String, Integer> immutable(Map<String, Integer> m) {
		return Collections.unmodifiableMap(new LinkedHashMap<>(m));
	}

	private static Map<String, Integer> expandAtoms(Map<String, Integer> units) {
		Map<String, Integer> atoms = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> e : units.entrySet()) {
			Map<String, Integer> group = GROUP_ATOMS.get(e.getKey());
			if (group == null) {
				atoms.merge(e.getKey(), e.getValue(), Integer::sum);
			} else {
				for (Map.Entry<String, Integer> a : group.entrySet()) {
					atoms.merge(a.getKey(), a.getValue() * e.getValue(), Integer::sum);
				}
			}
		}
		return atoms;
	}

	/** 电荷求解结果。 */
	private record Solve(int charge, boolean resolved, int solutions,
			Map<String, Integer> charges, List<String> problems) {
	}

	private static final int MAX_SOLUTIONS = 2;
	private static final long MAX_COMBINATIONS = 65536L;

	/** 记号电荷：物种级覆盖优先，其次全局酸根 / 阳离子表。 */
	private static Integer unitCharge(Map<String, Integer> chargeOverrides, String token) {
		Integer overridden = chargeOverrides.get(token);
		return overridden != null ? overridden : GROUP_CHARGES.get(token);
	}

	private static Solve solveCharge(Map<String, Integer> units, Map<String, Integer> chargeOverrides) {
		if (units.isEmpty()) {
			return new Solve(0, false, 0, Map.of(), List.of("没有可解析的记号"));
		}
		// 单质：只有一种记号（且不是酸根 / 基团）→ 氧化态 0
		if (units.size() == 1) {
			String only = units.keySet().iterator().next();
			if (unitCharge(chargeOverrides, only) == null) {
				return new Solve(0, true, 1, Map.of(only, 0), List.of());
			}
		}
		List<String> tokens = new ArrayList<>(units.keySet());
		List<int[]> candidates = new ArrayList<>();
		long combinations = 1L;
		for (String token : tokens) {
			Integer fixed = unitCharge(chargeOverrides, token);
			int[] options = fixed != null ? new int[] {fixed} : VALENCES.get(token);
			if (options == null || options.length == 0) {
				return new Solve(0, false, 0, Map.of(), List.of("未知元素记号: " + token));
			}
			candidates.add(options);
			combinations *= options.length;
			if (combinations > MAX_COMBINATIONS) {
				return new Solve(0, false, 0, Map.of(),
						List.of("价态组合过多，放弃求解: " + units.keySet()));
			}
		}
		int n = tokens.size();
		Map<String, Integer> found = null;
		int count = 0;
		int[] choice = new int[n];
		for (long combo = 0; combo < combinations; combo++) {
			long x = combo;
			int sum = 0;
			for (int i = 0; i < n; i++) {
				int[] options = candidates.get(i);
				choice[i] = (int) (x % options.length);
				x /= options.length;
				sum += units.get(tokens.get(i)) * options[choice[i]];
			}
			if (sum == 0) {
				count++;
				if (found == null) {
					found = new LinkedHashMap<>();
					for (int i = 0; i < n; i++) {
						found.put(tokens.get(i), candidates.get(i)[choice[i]]);
					}
				} else {
					found = null; // 多解：不猜
				}
				if (count >= MAX_SOLUTIONS) {
					break;
				}
			}
		}
		if (count == 1 && found != null) {
			return new Solve(0, true, 1, found, List.of());
		}
		if (count == 0) {
			return new Solve(0, false, 0, Map.of(), List.of("无价态解（电荷无法归零）"));
		}
		return new Solve(0, false, count, Map.of(), List.of("价态多解（" + count + " 组），不猜"));
	}

	/** 递归下降解析器。 */
	private static final class Reader {
		private final String s;
		private final Map<String, String> alias;
		private int pos;
		private final Map<String, Integer> units = new LinkedHashMap<>();
		private final List<String> problems = new ArrayList<>();

		Reader(String s, Map<String, String> alias) {
			this.s = s;
			this.alias = alias;
		}

		void parse() {
			sequence(units, 1);
			if (pos < s.length()) {
				problems.add("解析停在位置 " + pos + "（'" + s.charAt(pos) + "'）");
			}
		}

		private void sequence(Map<String, Integer> out, int mult) {
			while (pos < s.length()) {
				char c = s.charAt(pos);
				if (c == ')') {
					return;
				}
				if (c == '(') {
					pos++;
					Map<String, Integer> inner = new LinkedHashMap<>();
					sequence(inner, 1);
					if (pos < s.length() && s.charAt(pos) == ')') {
						pos++;
					} else {
						problems.add("括号不闭合 @" + pos);
						return;
					}
					int m = number();
					inner.forEach((k, v) -> out.merge(k, v * m * mult, Integer::sum));
					continue;
				}
				String symbol = symbol();
				if (symbol == null) {
					problems.add("无法识别的记号 '" + c + "' @" + pos);
					pos++;
					continue;
				}
				int m = number();
				out.merge(symbol, m * mult, Integer::sum);
			}
		}

		private String symbol() {
			for (String group : GROUP_TOKENS) {
				if (s.startsWith(group, pos)) {
					pos += group.length();
					return group;
				}
			}
			if (s.startsWith("RE", pos)) {
				pos += 2;
				return "RE";
			}
			char c = s.charAt(pos);
			if (c >= 'A' && c <= 'Z') {
				int len = 1;
				if (pos + 1 < s.length() && s.charAt(pos + 1) >= 'a' && s.charAt(pos + 1) <= 'z') {
					len = 2;
				}
				String sym = s.substring(pos, pos + len);
				if (VALENCES.containsKey(sym)) {
					pos += len;
					return alias.getOrDefault(sym, sym);
				}
				pos += len;
				return null;
			}
			return null;
		}

		private int number() {
			int start = pos;
			while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
				pos++;
			}
			if (pos == start) {
				return 1;
			}
			try {
				return Integer.parseInt(s.substring(start, pos));
			} catch (NumberFormatException e) {
				problems.add("下标过大 @" + start);
				return 1;
			}
		}
	}

	// ==================================================================== 物种查询

	private static final Map<String, Formula> SPECIES_CACHE = new HashMap<>();

	/** 解析一个 {@link Chemical} 物种（含显式别名表）。未知 id 返回带问题的结果，不抛异常。 */
	public static Formula parseSpecies(String speciesId) {
		if (speciesId == null) {
			return new Formula(null, Map.of(), Map.of(), Map.of(), 0, false, 0, List.of("null id"));
		}
		return SPECIES_CACHE.computeIfAbsent(speciesId, id -> {
			if (!Chemical.isKnown(id)) {
				return new Formula(id, Map.of(), Map.of(), Map.of(), 0, false, 0,
						List.of("未知物质 id: " + id));
			}
			String formula = CANONICAL_FORMULA_OVERRIDES.getOrDefault(id, Chemical.formulaOf(id));
			return parse(formula, FORMULA_ALIASES.getOrDefault(id, Map.of()),
					UNIT_CHARGE_OVERRIDES.getOrDefault(id, Map.of()));
		});
	}

	/** 物种公式总电荷；未知 / 未解出返回 {@code null}。 */
	public static Integer chargeOf(String speciesId) {
		Formula f = parseSpecies(speciesId);
		return f.chargeResolved() ? f.charge() : null;
	}

	/** 主阳离子（金属优先，其次 H⁺，再次 NH₄⁺）；共价物 / 未知返回 {@code null}。 */
	public static Ion cationOf(String speciesId) {
		Formula f = parseSpecies(speciesId);
		if (!f.chargeResolved()) {
			return null;
		}
		// 单质金属：公式只含一种元素（且该元素是金属）时，阳离子就是元素本身。
		// 没有这一条，Fe / Zn / Cu 这类单质物种的 cationOf 会返回 null —— 规则 5（金属 + 酸）
		// 会静默 continue、一条边都不生成，活动性闸门形同虚设（见 bug#25）。
		if (f.atoms().size() == 1 && f.charge() == 0) {
			String only = f.atoms().keySet().iterator().next();
			Integer valence = isMetal(only) ? valenceOf(only) : null;
			if (valence != null) {
				return new Ion(only, valence, 1);
			}
		}
		List<Ion> positives = ions(f, true);
		Ion best = null;
		int bestRank = Integer.MAX_VALUE;
		for (Ion ion : positives) {
			if (!isMetal(ion.token())) {
				continue;
			}
			int rank = activityRank(ion.token());
			int key = rank < 0 ? Integer.MAX_VALUE - 1 : rank;
			if (key < bestRank) {
				bestRank = key;
				best = ion;
			}
		}
		if (best != null) {
			return best;
		}
		for (Ion ion : positives) {
			if ("H".equals(ion.token())) {
				return ion;
			}
		}
		for (Ion ion : positives) {
			if ("NH4".equals(ion.token())) {
				return ion;
			}
		}
		return null;
	}

	/** 主酸根（多原子酸根优先，其次单原子；没有返回 {@code null}）。 */
	public static Ion anionOf(String speciesId) {
		List<Ion> anions = anionsOf(speciesId);
		return anions.isEmpty() ? null : anions.get(0);
	}

	/** 全部阴离子，多原子酸根在前。 */
	public static List<Ion> anionsOf(String speciesId) {
		Formula f = parseSpecies(speciesId);
		if (!f.chargeResolved()) {
			return List.of();
		}
		List<Ion> negatives = ions(f, false);
		negatives.sort((a, b) -> {
			boolean ga = GROUP_CHARGES.containsKey(a.token());
			boolean gb = GROUP_CHARGES.containsKey(b.token());
			if (ga != gb) {
				return ga ? -1 : 1;
			}
			if (a.count() != b.count()) {
				return Integer.compare(b.count(), a.count());
			}
			return Integer.compare(b.token().length(), a.token().length());
		});
		return List.copyOf(negatives);
	}

	private static List<Ion> ions(Formula f, boolean positive) {
		List<Ion> out = new ArrayList<>();
		for (Map.Entry<String, Integer> e : f.units().entrySet()) {
			Integer q = f.unitCharges().get(e.getKey());
			if (q == null) {
				continue;
			}
			if (positive ? q > 0 : q < 0) {
				out.add(new Ion(e.getKey(), q, e.getValue()));
			}
		}
		return out;
	}

	// ==================================================================== 结构判定（标签转发）

	/** 氧化物且阳离子是金属（如 Fe₂O₃、CaO、MnO₂；SO₃ 不算）。 */
	public static boolean isMetalOxide(String speciesId) {
		if (!ChemicalTags.has(speciesId, ChemicalTags.Tag.OXIDE)) {
			return false;
		}
		Ion cation = cationOf(speciesId);
		return cation != null && isMetal(cation.token());
	}

	/**
	 * 「活泼金属氧化物」——与水剧烈/明显反应生成氢氧化物的碱性氧化物。
	 *
	 * <p>判据是<b>策展集合</b>（碱金属 + 碱土金属的氧化物）而不是纯活动性序：
	 * 活动性序会把 FeO / ZnO 这类实际上不与水反应的氧化物也算进来。Al₂O₃ / MgO 虽在
	 * 氢之前，但本仓库已用 {@link ChemicalTags.Tag#REFRACTORY} 表达「难还原 / 惰性」，
	 * 这里也排除。
	 */
	public static boolean isActiveMetalOxide(String speciesId) {
		if (!ChemicalTags.has(speciesId, ChemicalTags.Tag.OXIDE)
				|| ChemicalTags.has(speciesId, ChemicalTags.Tag.REFRACTORY)) {
			return false;
		}
		Ion cation = cationOf(speciesId);
		return cation != null && ACTIVE_OXIDE_METALS.contains(cation.token());
	}

	/** 遇水生成强碱的金属（氧化物）。 */
	private static final Set<String> ACTIVE_OXIDE_METALS = Set.of(
			"Li", "Na", "K", "Rb", "Cs", "Mg", "Ca", "Sr", "Ba");

	/** 氢氧化物（标签转发）。 */
	public static boolean isHydroxide(String speciesId) {
		return ChemicalTags.has(speciesId, ChemicalTags.Tag.HYDROXIDE);
	}

	/** 碳酸盐（标签转发）。 */
	public static boolean isCarbonate(String speciesId) {
		return ChemicalTags.has(speciesId, ChemicalTags.Tag.CARBONATE);
	}

	/** 硫酸盐（标签转发；不含硫酸氢盐）。 */
	public static boolean isSulfate(String speciesId) {
		return ChemicalTags.has(speciesId, ChemicalTags.Tag.SULFATE);
	}

	/** 卤化物 = 氯化物或氟化物（标签转发）。 */
	public static boolean isHalide(String speciesId) {
		return ChemicalTags.has(speciesId, ChemicalTags.Tag.CHLORIDE)
				|| ChemicalTags.has(speciesId, ChemicalTags.Tag.FLUORIDE);
	}

	/** 硫化物（S²⁻ 阴离子；{@link ChemicalTags} 没有 SULFIDE 标签，故由解析结果判定）。 */
	public static boolean isSulfide(String speciesId) {
		Ion anion = anionOf(speciesId);
		return anion != null && "S".equals(anion.token()) && anion.charge() == -2;
	}

	// ==================================================================== 覆盖 / 禁用表（可审计，物理上不成立的不许猜）

	/** 规则 id（{@link ReactionRules} 使用；禁用表按此登记）。 */
	public static final String RULE_ACTIVE_METAL_OXIDE_WATER = "active_metal_oxide_water";
	public static final String RULE_METAL_OXIDE_ACID = "metal_oxide_acid";
	public static final String RULE_HYDROXIDE_ACID = "hydroxide_acid";
	public static final String RULE_CARBONATE_ACID = "carbonate_acid";
	public static final String RULE_METAL_ACID = "metal_acid";
	public static final String RULE_NONMETAL_OXIDE_WATER = "nonmetal_oxide_water";
	public static final String RULE_DISSOLUTION = "dissolution";

	/**
	 * 一个被显式禁用（ban）或规范化的物种 / 矿相。
	 *
	 * @param id              物种 / 矿相 id
	 * @param originalFormula 原公式
	 * @param treatment       处理方式（ban / 规范化）
	 * @param reason          物理 / 建模理由（必须能被人复核）
	 */
	public record Ban(String id, String originalFormula, String treatment, String reason) {
	}

	/**
	 * 公式规范化覆盖：id → 干净式子（{@link #parseSpecies} 优先用它）。
	 *
	 * <p>当前为空：本轮的问题物种（混合价 / 固溶体 / 多解 / 电荷自洽但物理不成立）一律走
	 * {@link #BANNED} 硬禁用，而不是硬凑一个「干净式子」。留着这个口子，
	 * 将来若有能安全规范化的式子只改这一张表。
	 */
	private static final Map<String, String> CANONICAL_FORMULA_OVERRIDES = new LinkedHashMap<>();

	/** 被 ban 的物种 / 矿相（可审计）。 */
	private static final Map<String, Ban> BANNED = new LinkedHashMap<>();

	private static void ban(String id, String formula, String reason) {
		BANNED.put(id, new Ban(id, formula, "ban", reason));
	}

	static {
		// 混合价态：解析器要求「单元素单氧化态」使全式电荷归零，Fe3O4 的 Fe 同时是 +2/+3，做不到。
		ban("magnetite", "Fe3O4",
				"混合价态 Fe²⁺/Fe³⁺：同一元素两种氧化态，纯公式无法唯一求解；禁止参与规则生成（选矿/冶金走显式表）");
		// 固溶体式量：电荷自洽 != 一个化合物。
		ban("pentlandite", "Fe9Ni9S16", "固溶体式量（Fe-Ni 硫化物固溶体），不是可用单一价态描述的化合物；ban");
		ban("tetrahedrite", "Cu12Sb4S13", "固溶体式量（Cu-Sb 硫盐固溶体）；ban");
		// 多解：电荷能解，但有多组价态解。
		ban("chalcopyrite", "CuFeS2", "价态多解（CuFeS2 有多组解），解析器不猜；ban");
		// 电荷守恒但物理上不成立。
		ban("wolframite", "FeMnWO4",
				"电荷自洽但物理上不是单一化合物（Fe/Mn 固溶钨酸盐）；公式解析只保证电荷自洽，不代表成立；ban");
	}

	/** 这个 id 是否被显式 ban。 */
	public static boolean isBanned(String id) {
		return id != null && BANNED.containsKey(id);
	}

	/** 取 ban 记录；未禁返回 {@code null}。 */
	public static Ban banOf(String id) {
		return BANNED.get(id);
	}

	/** 全部 ban（只读，顺序稳定）。 */
	public static Map<String, Ban> bannedSpecies() {
		return Collections.unmodifiableMap(BANNED);
	}

	/** 公式规范化覆盖表（只读）。 */
	public static Map<String, String> canonicalFormulaOverrides() {
		return Collections.unmodifiableMap(CANONICAL_FORMULA_OVERRIDES);
	}

	/** 取规范化公式；没有覆盖时返回 {@code fallback}。 */
	public static String canonicalFormula(String id, String fallback) {
		return CANONICAL_FORMULA_OVERRIDES.getOrDefault(id, fallback);
	}

	/**
	 * 非化学式「环境 / 混合物」节点的显式登记（可审计）。
	 *
	 * <p>这些 id 刻意<b>不是</b> {@link Chemical} 物种：它们没有单一化学式，参与配平只会
	 * 引入假式子。它们在 {@link ReactionGraph} 里当 ambient root（开局即可达），再由桥接边
	 * 转成真实物种。按用户口径，物理上不是单一化合物 / 特殊的对象必须在此登记并给一行理由，
	 * <b>不许猜</b>。
	 */
	public record SpecialNode(String id, String treatment, String reason) {
	}

	private static final Map<String, SpecialNode> SPECIAL_NODES = new LinkedHashMap<>();

	private static void special(String id, String treatment, String reason) {
		SPECIAL_NODES.put(id, new SpecialNode(id, treatment, reason));
	}

	static {
		// 目前无登记项：上一轮的 air（空气分离）已按用户口径删除 —— 氮改从硝石 / 硇砂矿获得。
		// 这张表保留，作为「物理上不是单一化合物、又不能凭空当 Chemical 物种」的节点唯一登记处。
	}

	/** 全部「环境 / 混合物」特殊节点（只读，顺序稳定）。 */
	public static Map<String, SpecialNode> specialNodes() {
		return Collections.unmodifiableMap(SPECIAL_NODES);
	}

	/**
	 * 规则级的显式禁用：某个 (规则, 物种/元素) 组合物理上不成立，登记理由，生成器遇到直接跳过。
	 */
	public record RuleDisable(String rule, String subject, String reason) {
	}

	private static final List<RuleDisable> RULE_DISABLES = new ArrayList<>();

	private static void disable(String rule, String subject, String reason) {
		RULE_DISABLES.add(new RuleDisable(rule, subject, reason));
	}

	static {
		// 活动性边界：排在 H 之后的金属与稀酸不置换出 H2。
		for (String metal : List.of("Cu", "Hg", "Ag", "Pt", "Au")) {
			disable(RULE_METAL_ACID, metal, "活动性在 H 之后，与稀酸不反应（物理上不置换氢）");
		}
		// 活泼金属氧化物遇水：MgO / Al2O3 不（明显）与水反应。
		disable(RULE_ACTIVE_METAL_OXIDE_WATER, "magnesium_oxide", "MgO 与冷水几乎不反应（REFRACTORY），不生成水合边");
		disable(RULE_ACTIVE_METAL_OXIDE_WATER, "aluminum_oxide", "Al2O3 两性、不溶于水（REFRACTORY），不生成水合边");
		// 金属氧化物 + 酸：MnO2 是氧化还原溶解，不是复分解。
		disable(RULE_METAL_OXIDE_ACID, "manganese_dioxide",
				"MnO2 酸溶是氧化还原（Mn⁴⁺→Mn²⁺ 并放 Cl2），简单「盐 + 水」复分解配不平，不生成");
		// 非金属氧化物 + 水：不按 1:1 水合，或根本不成酸。
		disable(RULE_NONMETAL_OXIDE_WATER, "nitrogen_dioxide", "NO2 与水歧化为 HNO3 + HNO2（3:1），不是 1:1 水合");
		disable(RULE_NONMETAL_OXIDE_WATER, "nitric_oxide", "NO 不溶于水、也不与水成酸");
		disable(RULE_NONMETAL_OXIDE_WATER, "carbon_monoxide", "CO 不溶于水、不与水反应（中性氧化物）");
		disable(RULE_NONMETAL_OXIDE_WATER, "silicon_dioxide", "SiO2 常温不溶于水；物理上不成立");
		// 碳酸盐 + 酸：只有酸性强于碳酸的酸才能把 CO3²⁻ 置换出 CO2；
		// H2S / HClO 的 pKa 高于 H2CO3，只能停在 HCO3⁻ / HS⁻，配平出来的「盐 + CO2」是假反应。
		disable(RULE_CARBONATE_ACID, "hydrogen_sulfide", "H2S 酸性弱于碳酸，不能把 CO3²⁻ 置换出 CO2（只能到 HCO3⁻/HS⁻）");
		disable(RULE_CARBONATE_ACID, "hypochlorous_acid", "HClO 酸性弱于碳酸，不能把 CO3²⁻ 置换出 CO2");
		// 金属 + 酸（规则 5）：只有「非氧化性、酸性足够」的酸才置换出 H2。
		// 硝酸 / 次氯酸是氧化性酸，金属被氧化而不是放 H2，简单置换配平是假反应。
		disable(RULE_METAL_ACID, "nitric_acid", "HNO3 是氧化性酸，金属与它反应放 NOx 而不是 H2");
		disable(RULE_METAL_ACID, "hypochlorous_acid", "HClO 是氧化剂，金属被氧化而不是置换出 H2");
		// 弱酸不足以溶解金属放 H2；显式登记，免得将来补了它的盐就拼出假反应。
		disable(RULE_METAL_ACID, "hydrogen_sulfide", "H2S 酸性太弱，不足以与金属置换出 H2");
		disable(RULE_METAL_ACID, "carbonic_acid", "H2CO3 酸性太弱，不足以与金属置换出 H2");
		// 铁：Fe 与稀酸给的是 FeCl2（亚铁），表里只有 FeCl3（氧化态铁，需 Cl2 氧化）。
		// 按现有物种配平会唯一解出 2 Fe + 6 HCl -> 2 FeCl3 + 3 H2 —— 配平正确但化学是错的，禁用。
		disable(RULE_METAL_ACID, "iron",
				"Fe + 稀酸生成 FeCl2（亚铁）；表中只有 FeCl3（三价铁需氧化剂），置换配平会拼出假的 FeCl3 + H2");
	}

	/** 该 (规则, 物种/元素) 是否被显式禁用；未禁返回 {@code null}，否则返回理由。 */
	public static String ruleDisableReason(String rule, String subject) {
		for (RuleDisable d : RULE_DISABLES) {
			if (d.rule().equals(rule) && d.subject().equals(subject)) {
				return d.reason();
			}
		}
		return null;
	}

	/** 全部规则级禁用（只读）。 */
	public static List<RuleDisable> ruleDisables() {
		return List.copyOf(RULE_DISABLES);
	}
}
