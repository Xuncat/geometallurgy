package com.geometallurgy.chemical;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 标签 / 价态驱动的<b>规则生成器</b>（§十三 13.3 第 3 步 + open#4 的溶解半边）。
 *
 * <p>硬编码的 {@link MolRecipes} 表退化为<b>覆盖层</b>：本类在类初始化时从
 * {@link ChemicalTags}（性质标签）、{@link ChemicalData}（价态 / 酸根 / 活动性序 / 公式解析）
 * 现算一批候选反应，再逐条过 {@link MolBalance#isMassBalanced} 与
 * {@link MolBalance.Reaction} 的规范构造器；配不平 / 产物物种不存在 / 与显式表冲突的，
 * <b>丢弃并把理由记进 {@link Result#skipped()}</b>，绝不抛异常、绝不放一条不平衡的反应进表。
 *
 * <h2>系数是算出来的，不是写死的</h2>
 * 对每个候选（反应物 id 列表 + 产物 id 列表），取每种物质由 {@link ChemicalData#parseSpecies}
 * 解析出的原子计数，构造「元素 × 物种」的齐次线性方程组，用精确有理数做 RREF，
 * 求一维零空间，再通分 + 约分得到<b>最小整数系数</b>。自由变量多于一个（多解）、
 * 无解、系数为 0、两侧符号不一致——一律判为配不平。
 *
 * <h2>规则集 v1</h2>
 * <ol>
 *   <li>{@code active_metal_oxide_water}：活泼金属氧化物 + 水 → 氢氧化物（单向）；</li>
 *   <li>{@code metal_oxide_acid}：金属氧化物 + 酸 → 盐 + 水；</li>
 *   <li>{@code hydroxide_acid}：氢氧化物 + 酸 → 盐 + 水；</li>
 *   <li>{@code carbonate_acid}：碳酸盐 + 酸 → 盐 + CO2 + 水；</li>
 *   <li>{@code metal_acid}：金属单质 + 酸 → 盐 + H2（活动性序在 H 之前才生成）；</li>
 *   <li>{@code nonmetal_oxide_water}：非金属氧化物 + 水 → 酸（标可逆，弱酸有平衡）；</li>
 *   <li>{@code dissolution}：气体 / 固体 + 水 → 溶液形态（open#4）——当前数据模型没有
 *       「溶液形态」产物物种，规则整体跳过并记录，本轮不新增物种。</li>
 * </ol>
 *
 * <p>规则级的物理例外（活动性边界、MnO2 的氧化还原溶解、NO2 的歧化……）在
 * {@link ChemicalData#ruleDisables()} 里显式登记，遇到即跳过；被 ban 的物种
 * （{@link ChemicalData#bannedSpecies()}）同样跳过并进 WARN 清单。
 *
 * <p>本类无 {@code net.minecraft} 依赖，可在普通 JVM 里跑（一次性 harness 就是这么验收的）。
 * 全部迭代顺序固定（{@link Chemical#ids()} 顺序、规则固定次序），输出确定性、与 HashMap 顺序无关。
 */
public final class ReactionRules {

	private ReactionRules() {
	}

	// 规则 id（与 ChemicalData 的禁用表共用同一批常量，避免两处漂移）
	private static final String R1 = ChemicalData.RULE_ACTIVE_METAL_OXIDE_WATER;
	private static final String R2 = ChemicalData.RULE_METAL_OXIDE_ACID;
	private static final String R3 = ChemicalData.RULE_HYDROXIDE_ACID;
	private static final String R4 = ChemicalData.RULE_CARBONATE_ACID;
	private static final String R5 = ChemicalData.RULE_METAL_ACID;
	private static final String R6 = ChemicalData.RULE_NONMETAL_OXIDE_WATER;
	private static final String R7 = ChemicalData.RULE_DISSOLUTION;

	// 速率（份/tick）：中性化 / 成盐热反应快；碳酸盐 + 酸快；水合 / 溶解要搅拌，慢。
	private static final double RATE_FAST = 0.8;
	private static final double RATE_SALT = 0.5;
	private static final double RATE_HYDRATION = 0.6;
	private static final double RATE_SLOW = 0.1;
	/** 弱酸水合的粗平衡比（与合成氨同一档，不做真 K 标定）。 */
	private static final double K_WEAK_ACID = 0.25;

	/**
	 * 整条规则的停用开关（默认全开）。要停用某条规则，把它的 id 填进来即可
	 * （如 {@code Set.of(R2, R5)}），其余代码一行不用动 —— 配合 {@link ChemicalData#ruleDisables()}
	 * 的「(规则, 物种) 级禁用」，规则的可读 / 可禁用都只在一处。
	 */
	private static final Set<String> DISABLED_RULES = Set.of();

	private static boolean enabled(String rule) {
		return !DISABLED_RULES.contains(rule);
	}

	/** 跳过原因。 */
	public enum Reason {
		/** 物种被 {@link ChemicalData#bannedSpecies()} 显式 ban。 */
		BANNED_SPECIES,
		/** (规则, 物种/元素) 被 {@link ChemicalData#ruleDisables()} 显式禁用。 */
		RULE_DISABLED,
		/** 所需产物物种在 {@link Chemical} 表里不存在（下一轮 TODO）。 */
		SPECIES_MISSING,
		/** 线性配平失败（无解 / 多解 / 系数为 0 / 符号不一致 / 质量校验不过）。 */
		UNBALANCED,
		/** 与硬编码表冲突：显式表优先。 */
		EXPLICIT_OVERRIDE,
		/** 与已生成的反应重复。 */
		DUPLICATE,
		/** 规则对该类物质整体不适用（如没有金属单质物种）。 */
		NOT_APPLICABLE
	}

	/** 一条被跳过的候选。 */
	public record Skip(String rule, String equation, Reason reason, String detail) {
	}

	/** 一条被接受的生成反应（含系数与来源规则）。 */
	public record Added(String rule, String equation, int[] reactantCoeffs, int[] productCoeffs,
			MolBalance.Reaction reaction) {

		public Added {
			reactantCoeffs = reactantCoeffs.clone();
			productCoeffs = productCoeffs.clone();
		}
	}

	/** 生成结果：接受 / 跳过 / ban 表 / 规则禁用表。 */
	public record Result(List<Added> added, List<Skip> skipped, List<String> missingSpecies,
			List<ChemicalData.Ban> bans, List<ChemicalData.RuleDisable> ruleDisables) {

		public Result {
			added = List.copyOf(added);
			skipped = List.copyOf(skipped);
			missingSpecies = List.copyOf(missingSpecies);
			bans = List.copyOf(bans);
			ruleDisables = List.copyOf(ruleDisables);
		}
	}

	// ==================================================================== 索引（从 Chemical 表推导）

	/** (阳离子记号 | 酸根记号) → 已有物种 id。 */
	private static final Map<String, String> SALT_BY_ION = new LinkedHashMap<>();

	/** 原子计数规范键 → 物种 id（用于「非金属氧化物 + 水 = 哪种酸」）。 */
	private static final Map<String, String> SPECIES_BY_ATOMS = new LinkedHashMap<>();

	/** 真酸：既是 ACID 标签，主阳离子又是 H⁺（排除酸性盐 / 铁盐这类伪酸）。 */
	private static final List<String> ACIDS = new ArrayList<>();

	static {
		for (String id : Chemical.ids()) {
			ChemicalData.Formula formula = ChemicalData.parseSpecies(id);
			if (formula.chargeResolved() && !formula.atoms().isEmpty()) {
				SPECIES_BY_ATOMS.putIfAbsent(atomKey(formula.atoms()), id);
			}
			ChemicalData.Ion cation = ChemicalData.cationOf(id);
			ChemicalData.Ion anion = ChemicalData.anionOf(id);
			if (cation != null && anion != null) {
				SALT_BY_ION.putIfAbsent(cation.token() + "|" + anion.token(), id);
			}
			if (cation != null && "H".equals(cation.token())
					&& ChemicalTags.has(id, ChemicalTags.Tag.ACID)) {
				ACIDS.add(id);
			}
		}
	}

	// ==================================================================== 入口

	/**
	 * 生成规则反应。
	 *
	 * @param explicitReactions 已登记的硬编码反应（覆盖层）；与生成候选冲突时显式表优先
	 * @return 结果（含全部跳过理由与 ban / 禁用表，供调用方记日志）
	 */
	public static Result generate(List<MolBalance.Reaction> explicitReactions) {
		Builder b = new Builder(explicitReactions);
		if (enabled(R1)) {
			ruleActiveOxideWater(b);
		}
		if (enabled(R2)) {
			ruleMetalOxideAcid(b);
		}
		if (enabled(R3)) {
			ruleHydroxideAcid(b);
		}
		if (enabled(R4)) {
			ruleCarbonateAcid(b);
		}
		if (enabled(R5)) {
			ruleMetalAcid(b);
		}
		if (enabled(R6)) {
			ruleNonmetalOxideWater(b);
		}
		if (enabled(R7)) {
			ruleDissolution(b);
		}
		return new Result(b.added, b.skipped, new ArrayList<>(b.missing),
				new ArrayList<>(ChemicalData.bannedSpecies().values()),
				ChemicalData.ruleDisables());
	}

	// ------------------------------------------------------------------ 规则 1

	private static void ruleActiveOxideWater(Builder b) {
		for (String oxide : Chemical.ids()) {
			if (!ChemicalData.isActiveMetalOxide(oxide)) {
				continue;
			}
			ChemicalData.Ion metal = ChemicalData.cationOf(oxide);
			if (metal == null) {
				continue;
			}
			String hydroxide = SALT_BY_ION.get(metal.token() + "|OH");
			List<String> reactants = List.of(oxide, "water");
			if (hydroxide == null) {
				String needed = compound(metal.token(), metal.charge(), "OH", -1);
				b.missing(R1, raw(reactants, List.of(needed)), needed);
				continue;
			}
			// 活泼金属氧化物水合是单向的：氢氧化物受热才脱水，常温不回头。
			b.candidate(R1, reactants, List.of(hydroxide),
					MolBalance.Kind.THERMAL, false, 1.0, RATE_FAST);
		}
	}

	// ------------------------------------------------------------------ 规则 2

	private static void ruleMetalOxideAcid(Builder b) {
		for (String oxide : Chemical.ids()) {
			if (!ChemicalData.isMetalOxide(oxide)) {
				continue;
			}
			ChemicalData.Ion metal = ChemicalData.cationOf(oxide);
			if (metal == null) {
				continue;
			}
			String disabled = ChemicalData.ruleDisableReason(R2, oxide);
			if (disabled != null) {
				b.disabled(R2, raw(List.of(oxide), List.of()), disabled);
				continue;
			}
			for (String acid : ACIDS) {
				ChemicalData.Ion anion = ChemicalData.anionOf(acid);
				if (!saltFormingAnion(anion)) {
					continue;
				}
				List<String> reactants = List.of(oxide, acid);
				String salt = SALT_BY_ION.get(metal.token() + "|" + anion.token());
				if (salt == null) {
					String needed = compound(metal.token(), metal.charge(), anion.token(), anion.charge());
					b.missing(R2, raw(reactants, List.of(needed, "water")), needed);
					continue;
				}
				b.candidate(R2, reactants, List.of(salt, "water"),
						MolBalance.Kind.THERMAL, false, 1.0, RATE_FAST);
			}
		}
	}

	// ------------------------------------------------------------------ 规则 3

	private static void ruleHydroxideAcid(Builder b) {
		for (String hydroxide : Chemical.ids()) {
			if (!ChemicalData.isHydroxide(hydroxide)) {
				continue;
			}
			ChemicalData.Ion metal = ChemicalData.cationOf(hydroxide);
			if (metal == null) {
				continue;
			}
			String disabled = ChemicalData.ruleDisableReason(R3, hydroxide);
			if (disabled != null) {
				b.disabled(R3, raw(List.of(hydroxide), List.of()), disabled);
				continue;
			}
			for (String acid : ACIDS) {
				ChemicalData.Ion anion = ChemicalData.anionOf(acid);
				if (!saltFormingAnion(anion)) {
					continue;
				}
				List<String> reactants = List.of(hydroxide, acid);
				String salt = SALT_BY_ION.get(metal.token() + "|" + anion.token());
				if (salt == null) {
					String needed = compound(metal.token(), metal.charge(), anion.token(), anion.charge());
					b.missing(R3, raw(reactants, List.of(needed, "water")), needed);
					continue;
				}
				b.candidate(R3, reactants, List.of(salt, "water"),
						MolBalance.Kind.THERMAL, false, 1.0, RATE_FAST);
			}
		}
	}

	// ------------------------------------------------------------------ 规则 4

	private static void ruleCarbonateAcid(Builder b) {
		for (String carbonate : Chemical.ids()) {
			if (!ChemicalData.isCarbonate(carbonate)) {
				continue;
			}
			ChemicalData.Ion metal = ChemicalData.cationOf(carbonate);
			if (metal == null) {
				continue;
			}
			String disabled = ChemicalData.ruleDisableReason(R4, carbonate);
			if (disabled != null) {
				b.disabled(R4, raw(List.of(carbonate), List.of()), disabled);
				continue;
			}
			for (String acid : ACIDS) {
				ChemicalData.Ion anion = ChemicalData.anionOf(acid);
				if (!saltFormingAnion(anion)) {
					continue;
				}
				List<String> reactants = List.of(carbonate, acid);
				String salt = SALT_BY_ION.get(metal.token() + "|" + anion.token());
				if (salt == null) {
					String needed = compound(metal.token(), metal.charge(), anion.token(), anion.charge());
					b.missing(R4, raw(reactants, List.of(needed, "water", "carbon_dioxide")), needed);
					continue;
				}
				b.candidate(R4, reactants, List.of(salt, "water", "carbon_dioxide"),
						MolBalance.Kind.THERMAL, false, 1.0, RATE_SALT);
			}
		}
	}

	// ------------------------------------------------------------------ 规则 5

	private static void ruleMetalAcid(Builder b) {
		boolean anyMetal = false;
		for (String element : Chemical.ids()) {
			if (!ChemicalTags.has(element, ChemicalTags.Tag.ELEMENT)
					|| !ChemicalTags.has(element, ChemicalTags.Tag.METAL)) {
				continue;
			}
			anyMetal = true;
			ChemicalData.Ion metal = ChemicalData.cationOf(element);
			if (metal == null) {
				continue;
			}
			// 元素级禁用（规则, 物种）：如铁 —— 稀酸只给 FeCl2（亚铁），而表里只有 FeCl3，
			// 按现有物种配平会拼出 2Fe + 6HCl -> 2FeCl3 + 3H2 这种配得平但化学是假的反应。
			String elementDisabled = ChemicalData.ruleDisableReason(R5, element);
			if (elementDisabled != null) {
				b.skipped.add(new Skip(R5, element + " + acid -> salt + H2",
						Reason.RULE_DISABLED, elementDisabled));
				continue;
			}
			if (!ChemicalData.isAboveHydrogen(metal.token())) {
				String disabled = ChemicalData.ruleDisableReason(R5, metal.token());
				b.skipped.add(new Skip(R5, element + " + acid -> salt + H2",
						disabled != null ? Reason.RULE_DISABLED : Reason.NOT_APPLICABLE,
						disabled != null ? disabled : "活动性在 H 之后，不置换出 H2（物理上不反应）"));
				continue;
			}
			for (String acid : ACIDS) {
				ChemicalData.Ion anion = ChemicalData.anionOf(acid);
				if (!saltFormingAnion(anion)) {
					continue;
				}
				// 酸级禁用（规则, 物种）：氧化性酸 / 弱酸不置换出 H2（HNO3/HClO/H2S/H2CO3）。
				String acidDisabled = ChemicalData.ruleDisableReason(R5, acid);
				if (acidDisabled != null) {
					b.skipped.add(new Skip(R5, element + " + " + Chemical.formulaOf(acid) + " -> salt + H2",
							Reason.RULE_DISABLED, acidDisabled));
					continue;
				}
				List<String> reactants = List.of(element, acid);
				String salt = SALT_BY_ION.get(metal.token() + "|" + anion.token());
				if (salt == null) {
					String needed = compound(metal.token(), metal.charge(), anion.token(), anion.charge());
					b.missing(R5, raw(reactants, List.of(needed, "hydrogen")), needed);
					continue;
				}
				b.candidate(R5, reactants, List.of(salt, "hydrogen"),
						MolBalance.Kind.THERMAL, false, 1.0, RATE_SALT);
			}
		}
		if (!anyMetal) {
			// 本轮 Chemical 表里没有金属单质（H2/Cl2/F2/O2/N2/S/P/C 全是非金属 / 准金属）。
			b.skipped.add(new Skip(R5, "M + acid -> salt + H2", Reason.NOT_APPLICABLE,
					"Chemical 表里没有金属单质物种（Zn/Cu/Fe… 需下一轮新增）；活动性闸门已由 ChemicalData.isAboveHydrogen 实现"));
		}
	}

	// ------------------------------------------------------------------ 规则 6

	private static void ruleNonmetalOxideWater(Builder b) {
		ChemicalData.Formula water = ChemicalData.parseSpecies("water");
		for (String oxide : Chemical.ids()) {
			if (!ChemicalTags.has(oxide, ChemicalTags.Tag.OXIDE) || ChemicalData.isMetalOxide(oxide)) {
				continue;
			}
			String disabled = ChemicalData.ruleDisableReason(R6, oxide);
			if (disabled != null) {
				b.disabled(R6, oxide + " + H2O", disabled);
				continue;
			}
			ChemicalData.Formula formula = ChemicalData.parseSpecies(oxide);
			Map<String, Integer> combined = new LinkedHashMap<>(formula.atoms());
			water.atoms().forEach((k, v) -> combined.merge(k, v, Integer::sum));
			List<String> reactants = List.of(oxide, "water");
			String acid = SPECIES_BY_ATOMS.get(atomKey(combined));
			if (acid == null) {
				String needed = formulaText(combined);
				b.missing(R6, raw(reactants, List.of(needed)), needed);
				continue;
			}
			// 水合是平衡反应，标可逆；弱酸（H2CO3 那类）给低平衡比。
			b.candidate(R6, reactants, List.of(acid),
					MolBalance.Kind.STIRRING, true, K_WEAK_ACID, RATE_HYDRATION);
		}
	}

	// ------------------------------------------------------------------ 规则 7（open#4 溶解半边）

	private static void ruleDissolution(Builder b) {
		// Chemical.Info.solution 只标记「已经是溶质」的物质；没有独立的「溶液形态」产物 id
		// （溶解后的 HCl 就是 hydrochloric_acid 本身）。本轮不新增物种 → 整条边无法生成。
		b.skipped.add(new Skip(R7, "gas/solid + H2O -> solution", Reason.NOT_APPLICABLE,
				"数据模型没有「溶液形态」产物物种：Chemical.Info.solution 只标记已有溶质，"
						+ "没有 dissolved-gas / 水合态的独立 id；本轮不新增物种，open#4 的溶解半边仍缺"));
	}

	// ==================================================================== Builder

	/** 候选装配：先查 ban / 规则禁用，再配平，再走与 {@link MolRecipes} 相同的质量校验与冲突判定。 */
	private static final class Builder {

		private final List<Added> added = new ArrayList<>();
		private final List<Skip> skipped = new ArrayList<>();
		private final Set<String> missing = new LinkedHashSet<>();
		private final Set<String> explicitKeys;
		private final Set<String> generatedKeys = new LinkedHashSet<>();

		Builder(List<MolBalance.Reaction> explicit) {
			this.explicitKeys = new LinkedHashSet<>();
			for (MolBalance.Reaction r : explicit) {
				this.explicitKeys.add(key(r));
			}
		}

		void candidate(String rule, List<String> reactants, List<String> products,
				MolBalance.Kind kind, boolean reversible, double equilibriumRatio, double rate) {
			List<String> all = new ArrayList<>(reactants);
			all.addAll(products);
			for (String id : all) {
				ChemicalData.Ban ban = ChemicalData.banOf(id);
				if (ban != null) {
					skipped.add(new Skip(rule, raw(reactants, products), Reason.BANNED_SPECIES,
							"禁用物种 " + id + "（" + ban.reason() + "）"));
					return;
				}
				String disabled = ChemicalData.ruleDisableReason(rule, id);
				if (disabled != null) {
					skipped.add(new Skip(rule, raw(reactants, products), Reason.RULE_DISABLED,
							"规则对 " + id + " 禁用：" + disabled));
					return;
				}
			}
			Balance balance = balance(reactants, products);
			if (balance.coeffs() == null) {
				skipped.add(new Skip(rule, raw(reactants, products), Reason.UNBALANCED, balance.problem()));
				return;
			}
			int[] rc = Arrays.copyOfRange(balance.coeffs(), 0, reactants.size());
			int[] pc = Arrays.copyOfRange(balance.coeffs(), reactants.size(), balance.coeffs().length);
			MolBalance.Reactant[] rs = new MolBalance.Reactant[reactants.size()];
			for (int i = 0; i < rs.length; i++) {
				rs[i] = new MolBalance.Reactant(reactants.get(i), rc[i]);
			}
			MolBalance.Product[] ps = new MolBalance.Product[products.size()];
			for (int i = 0; i < ps.length; i++) {
				ps[i] = new MolBalance.Product(products.get(i), pc[i]);
			}
			MolBalance.Reaction reaction = new MolBalance.Reaction(rs, new String[0], ps, kind,
					Machine.defaultsFor(kind), reversible, equilibriumRatio, rate);
			if (!MolBalance.isMassBalanced(reaction)) {
				skipped.add(new Skip(rule, equation(reactants, rc, products, pc), Reason.UNBALANCED,
						"质量守恒校验未通过（isMassBalanced=false）"));
				return;
			}
			String key = key(reactants, rc, products, pc);
			if (explicitKeys.contains(key)) {
				skipped.add(new Skip(rule, equation(reactants, rc, products, pc), Reason.EXPLICIT_OVERRIDE,
						"与硬编码表冲突，显式表优先"));
				return;
			}
			if (!generatedKeys.add(key)) {
				skipped.add(new Skip(rule, equation(reactants, rc, products, pc), Reason.DUPLICATE,
						"与已生成反应重复，保留先出现的规则"));
				return;
			}
			added.add(new Added(rule, equation(reactants, rc, products, pc), rc, pc, reaction));
		}

		void missing(String rule, String equation, String needed) {
			missing.add(needed);
			skipped.add(new Skip(rule, equation, Reason.SPECIES_MISSING, "需要物种 " + needed));
		}

		void disabled(String rule, String equation, String reason) {
			skipped.add(new Skip(rule, equation, Reason.RULE_DISABLED, reason));
		}
	}

	// ==================================================================== 配平（精确有理数 + 零空间）

	private record Balance(int[] coeffs, String problem) {
	}

	/**
	 * 解齐次方程组，返回最小正整数系数（反应物在前、产物在后）；配不平返回 {@code null}。
	 */
	static Balance balance(List<String> reactants, List<String> products) {
		List<String> species = new ArrayList<>(reactants);
		species.addAll(products);
		List<Map<String, Integer>> atomList = new ArrayList<>();
		LinkedHashSet<String> elementSet = new LinkedHashSet<>();
		for (String id : species) {
			ChemicalData.Formula formula = ChemicalData.parseSpecies(id);
			if (formula.atoms().isEmpty()) {
				return new Balance(null, "无法解析物质 " + id + "（无原子计数）");
			}
			atomList.add(formula.atoms());
			elementSet.addAll(formula.atoms().keySet());
		}
		List<String> elements = new ArrayList<>(elementSet);
		int rows = elements.size();
		int cols = species.size();
		Frac[][] m = new Frac[rows][cols];
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < cols; c++) {
				m[r][c] = Frac.of(atomList.get(c).getOrDefault(elements.get(r), 0));
			}
		}
		List<Integer> pivotCols = new ArrayList<>();
		int pivotRow = 0;
		for (int col = 0; col < cols && pivotRow < rows; col++) {
			int sel = -1;
			for (int r = pivotRow; r < rows; r++) {
				if (!m[r][col].isZero()) {
					sel = r;
					break;
				}
			}
			if (sel < 0) {
				continue;
			}
			Frac[] tmp = m[pivotRow];
			m[pivotRow] = m[sel];
			m[sel] = tmp;
			Frac pivot = m[pivotRow][col];
			for (int c = col; c < cols; c++) {
				m[pivotRow][c] = m[pivotRow][c].div(pivot);
			}
			for (int r = 0; r < rows; r++) {
				if (r == pivotRow || m[r][col].isZero()) {
					continue;
				}
				Frac factor = m[r][col];
				for (int c = col; c < cols; c++) {
					m[r][c] = m[r][c].sub(factor.mul(m[pivotRow][c]));
				}
			}
			pivotCols.add(col);
			pivotRow++;
		}
		List<Integer> free = new ArrayList<>();
		for (int c = 0; c < cols; c++) {
			if (!pivotCols.contains(c)) {
				free.add(c);
			}
		}
		if (free.isEmpty()) {
			return new Balance(null, "无解（各组分线性无关，配不平）");
		}
		if (free.size() > 1) {
			return new Balance(null, "多解（自由变量 " + free.size() + " 个）");
		}
		int freeCol = free.get(0);
		Frac[] x = new Frac[cols];
		Arrays.fill(x, Frac.ZERO);
		x[freeCol] = Frac.ONE;
		for (int i = 0; i < pivotCols.size(); i++) {
			int pc = pivotCols.get(i);
			Frac value = Frac.ZERO;
			for (int fc : free) {
				value = value.add(m[i][fc].mul(x[fc]));
			}
			x[pc] = value.neg();
		}
		BigInteger lcm = BigInteger.ONE;
		for (Frac xf : x) {
			lcm = lcm.divide(lcm.gcd(xf.d)).multiply(xf.d);
		}
		long[] raw = new long[cols];
		BigInteger gcd = BigInteger.ZERO;
		for (int i = 0; i < cols; i++) {
			BigInteger value = x[i].n.multiply(lcm.divide(x[i].d));
			raw[i] = value.longValueExact();
			gcd = gcd.gcd(value.abs());
		}
		if (gcd.signum() == 0) {
			return new Balance(null, "零解");
		}
		int[] coeffs = new int[cols];
		for (int i = 0; i < cols; i++) {
			coeffs[i] = (int) (raw[i] / gcd.longValueExact());
		}
		int reactantSign = 0;
		for (int i = 0; i < reactants.size(); i++) {
			int sign = Integer.signum(coeffs[i]);
			if (sign == 0) {
				return new Balance(null, "有反应物系数为 0（不参与反应）");
			}
			if (reactantSign == 0) {
				reactantSign = sign;
			} else if (reactantSign != sign) {
				return new Balance(null, "反应物侧符号不一致");
			}
		}
		int productSign = 0;
		for (int i = reactants.size(); i < cols; i++) {
			int sign = Integer.signum(coeffs[i]);
			if (sign == 0) {
				return new Balance(null, "有产物系数为 0（不参与反应）");
			}
			if (productSign == 0) {
				productSign = sign;
			} else if (productSign != sign) {
				return new Balance(null, "产物侧符号不一致");
			}
		}
		if (reactantSign == 0 || productSign == 0 || reactantSign == productSign) {
			return new Balance(null, "候选产物方向不成立（与反应物同号）");
		}
		for (int i = 0; i < cols; i++) {
			coeffs[i] = Math.abs(coeffs[i]);
		}
		return new Balance(coeffs, null);
	}

	/** 精确有理数（BigInteger，约分、分母恒正）。 */
	private static final class Frac {

		static final Frac ZERO = new Frac(BigInteger.ZERO, BigInteger.ONE);
		static final Frac ONE = new Frac(BigInteger.ONE, BigInteger.ONE);

		final BigInteger n;
		final BigInteger d;

		Frac(BigInteger n, BigInteger d) {
			if (d.signum() < 0) {
				n = n.negate();
				d = d.negate();
			}
			BigInteger g = n.gcd(d);
			if (g.signum() != 0) {
				n = n.divide(g);
				d = d.divide(g);
			}
			this.n = n;
			this.d = d;
		}

		static Frac of(long value) {
			return value == 0 ? ZERO : new Frac(BigInteger.valueOf(value), BigInteger.ONE);
		}

		Frac add(Frac o) {
			return new Frac(n.multiply(o.d).add(o.n.multiply(d)), d.multiply(o.d));
		}

		Frac sub(Frac o) {
			return new Frac(n.multiply(o.d).subtract(o.n.multiply(d)), d.multiply(o.d));
		}

		Frac mul(Frac o) {
			return new Frac(n.multiply(o.n), d.multiply(o.d));
		}

		Frac div(Frac o) {
			return new Frac(n.multiply(o.d), d.multiply(o.n));
		}

		Frac neg() {
			return new Frac(n.negate(), d);
		}

		boolean isZero() {
			return n.signum() == 0;
		}
	}

	// ==================================================================== 键 / 文本

	/** 反应规范键：两侧按 id 排序的「id*系数（已约）」串；用于覆盖 / 去重。 */
	static String key(MolBalance.Reaction r) {
		int nr = r.reactants().length;
		List<String> rin = new ArrayList<>(nr);
		int[] rc = new int[nr];
		for (int i = 0; i < nr; i++) {
			rin.add(r.reactants()[i].id());
			rc[i] = r.reactants()[i].coeff();
		}
		int np = r.products().length;
		List<String> pout = new ArrayList<>(np);
		int[] pc = new int[np];
		for (int i = 0; i < np; i++) {
			pout.add(r.products()[i].id());
			pc[i] = r.products()[i].coeff();
		}
		return key(rin, rc, pout, pc);
	}

	static String key(List<String> reactants, int[] rc, List<String> products, int[] pc) {
		int g = 0;
		for (int c : rc) {
			g = gcd(g, c);
		}
		for (int c : pc) {
			g = gcd(g, c);
		}
		if (g == 0) {
			g = 1;
		}
		return sideKey(reactants, rc, g) + "=>" + sideKey(products, pc, g);
	}

	private static String sideKey(List<String> ids, int[] coeffs, int g) {
		List<String> tokens = new ArrayList<>(ids.size());
		for (int i = 0; i < ids.size(); i++) {
			tokens.add(ids.get(i) + "*" + (coeffs[i] / g));
		}
		Collections.sort(tokens);
		return String.join("+", tokens);
	}

	private static String raw(List<String> reactants, List<String> products) {
		return formulaList(reactants) + " -> " + formulaList(products);
	}

	private static String formulaList(List<String> ids) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < ids.size(); i++) {
			if (i > 0) {
				sb.append(" + ");
			}
			sb.append(Chemical.formulaOf(ids.get(i)));
		}
		return sb.toString();
	}

	private static String equation(List<String> reactants, int[] rc, List<String> products, int[] pc) {
		return sideEquation(reactants, rc) + " -> " + sideEquation(products, pc);
	}

	private static String sideEquation(List<String> ids, int[] coeffs) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < ids.size(); i++) {
			if (i > 0) {
				sb.append(" + ");
			}
			if (coeffs[i] != 1) {
				sb.append(coeffs[i]).append(' ');
			}
			sb.append(Chemical.formulaOf(ids.get(i)));
		}
		return sb.toString();
	}

	/** 原子计数规范键（键排序，计数跟在后，顺序稳定）。 */
	static String atomKey(Map<String, Integer> atoms) {
		List<String> keys = new ArrayList<>(atoms.keySet());
		Collections.sort(keys);
		StringBuilder sb = new StringBuilder();
		for (String k : keys) {
			sb.append(k).append(':').append(atoms.get(k)).append(';');
		}
		return sb.toString();
	}

	/** 把原子计数写成可读式子（H 在前、C 其次、其余字典序）。用于「需要物种 X」清单。 */
	static String formulaText(Map<String, Integer> atoms) {
		List<String> keys = new ArrayList<>(atoms.keySet());
		keys.sort((a, b) -> {
			int ra = rank(a);
			int rb = rank(b);
			return ra != rb ? Integer.compare(ra, rb) : a.compareTo(b);
		});
		StringBuilder sb = new StringBuilder();
		for (String k : keys) {
			int count = atoms.get(k);
			sb.append(k);
			if (count > 1) {
				sb.append(count);
			}
		}
		return sb.toString();
	}

	private static int rank(String element) {
		if ("H".equals(element)) {
			return 0;
		}
		if ("O".equals(element)) {
			return 2;
		}
		return 1;
	}

	/** 按离子电荷配一个化合物式子（供「需要物种 X」用）。 */
	static String compound(String cation, int cationCharge, String anion, int anionCharge) {
		int g = gcd(Math.abs(cationCharge), Math.abs(anionCharge));
		if (g == 0) {
			g = 1;
		}
		int nCat = Math.abs(anionCharge) / g;
		int nAn = Math.abs(cationCharge) / g;
		boolean polyCat = ChemicalData.cationGroups().containsKey(cation);
		boolean polyAn = ChemicalData.anionGroups().containsKey(anion);
		String catPart = (polyCat && nCat > 1 ? "(" + cation + ")" : cation)
				+ (nCat > 1 ? Integer.toString(nCat) : "");
		String anPart = (polyAn && nAn > 1 ? "(" + anion + ")" : anion)
				+ (nAn > 1 ? Integer.toString(nAn) : "");
		return catPart + anPart;
	}

	/**
	 * 酸根能不能拿去配盐。
	 *
	 * <p>解析器至今把 H2SO3 的亚硫酸根读成单原子 O²⁻（SO3 基团会和中性 SO3 冲突，没有建组）。
	 * 拿 O²⁻ 去查 {@code 金属 | O} 只会命中该金属自己的氧化物，配出来的「盐」就是反应物本身，
	 * 全部会在配平阶段被拒。这里显式跳过，避免往跳过清单里灌无意义的候选 —— 亚硫酸盐
	 * （K2SO3 等）要等 SO3 基团建好后才谈得上，属已知边界。
	 */
	private static boolean saltFormingAnion(ChemicalData.Ion anion) {
		return anion != null && !"O".equals(anion.token());
	}

	private static int gcd(int a, int b) {
		a = Math.abs(a);
		b = Math.abs(b);
		while (b != 0) {
			int t = a % b;
			a = b;
			b = t;
		}
		return a;
	}
}
