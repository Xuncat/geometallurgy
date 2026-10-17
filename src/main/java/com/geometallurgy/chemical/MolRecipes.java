package com.geometallurgy.chemical;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 配平反应表（mol 计量体系的「能发生什么」）。
 *
 * <p>每一条都是整数系数的配平式；{@code requiresStirring} 为 true 的反应在反应釜里
 * 几乎不发生，必须用<b>搅拌器</b>（见 {@link com.geometallurgy.blockentity.MixerBlockEntity}）。
 * 稀土分离链的萃取步也是这类「不搅拌不动」的反应。
 *
 * <p><b>每反应速率</b>：每条反应在这里显式标一个 {@code rate}（<b>份/tick</b>，一份 = 一整条方程），
 * 交给 {@code Reactor} 按机器的吞吐上限缩放（见 {@link Reactor} 类注释与
 * {@code docs/科技线-v1.md} 的「反应速率 / 动力学」速率表）。速率不参与质量守恒 ——
 * 一 tick 推 0.02 份与推 1 份，每份的整数系数都由 {@link #add} 的质量校验兜底。
 *
 * <p>本表在类初始化时逐条做质量守恒校验（{@link MolBalance#isMassBalanced}），
 * 不守恒直接抛异常 —— 宁可启动就炸，也不要让玩家在游戏里发现产物凭空多出来。
 */
public final class MolRecipes {

	private MolRecipes() {
	}

	private static final Logger LOGGER = LoggerFactory.getLogger("geometallurgy");

	private static final List<MolBalance.Reaction> ALL = new ArrayList<>();

	// ------------------------------------------------------------------ 溶液电解的单价（FE / mol 反应物）
	//
	// 2026-10-17 用户口径：电解炉的溶液档<b>逐反应</b>计费，取代旧的平铺常量 SOLUTION_FE_PER_MOL=120。
	// 单价按工业槽电压的量级排开（1.9 V 的水电解最便宜，5~6 V 的无水氟化氢最贵），
	// 并留出「回收 5 条电解反应各自显式标价」的启动断言（见文件末尾）。

	/** 碱性水电解 2H₂O → 2H₂ + O₂：槽电压约 1.9 V，产物只是气 —— 最便宜的一档。 */
	private static final double FE_PER_MOL_WATER = 150.0;
	/** 氯碱 2NaCl + 2H₂O → 2NaOH + H₂ + Cl₂：约 3.1 V，还要膜 / 汞齐防腐。 */
	private static final double FE_PER_MOL_CHLOR_ALKALI = 260.0;
	/** 钾版氯碱 2KCl + 2H₂O → 2KOH + H₂ + Cl₂：阳离子更活泼、槽压略高。 */
	private static final double FE_PER_MOL_POTASH = 300.0;
	/** 无水氟化氢电解 2HF → H₂ + F₂：约 5~6 V，工业上最费电的电解之一。 */
	private static final double FE_PER_MOL_HF = 520.0;
	/** 锰酸钾阳极氧化 2K₂MnO₄ + 2H₂O → 2KMnO₄ + 2KOH + H₂：阳极反应温和，但稀溶液导电差。 */
	private static final double FE_PER_MOL_MANGANATE = 180.0;

	/** 硬编码（覆盖层）反应的条数 —— 生成反应一律追加在其后。 */
	private static int EXPLICIT_COUNT;

	/** 由 {@link ReactionRules} 生成并接受的反应（追加在 ALL 表尾）。 */
	private static final List<MolBalance.Reaction> GENERATED = new ArrayList<>();

	/**
	 * 按输入/输出/催化剂建一条反应，并校验质量守恒。
	 *
	 * <p>催化剂是「存在的必要条件」，不消耗；质量校验只算反应物与产物。
	 */
	private static MolBalance.Reaction add(MolBalance.Reaction r) {
		if (!MolBalance.isMassBalanced(r)) {
			throw new IllegalStateException("反应质量不守恒: " + r);
		}
		// 机器集由 Reaction 的规范构造器保证非空；这里再点名一次，让「反应没有机器」
		// 在启动时就以反应表自己的口吻炸出来（超边图的每条边都必须可执行）。
		if (r.machines().isEmpty()) {
			throw new IllegalStateException("反应没有可用机器: " + r);
		}
		ALL.add(r);
		return r;
	}

	/** 建一条带显式速率（份/tick）的反应；速率校验在 {@link MolBalance.Reaction} 的构造器里。 */
	private static MolBalance.Reaction add(MolBalance.Reaction r, double rate) {
		return add(r.withRate(rate));
	}

	/**
	 * 追加一条<b>生成</b>的反应：与 {@link #add(MolBalance.Reaction)} 走同一套校验，
	 * 但失败只记 WARN、返回 false，绝不抛异常（生成器永远不许把游戏拖下水）。
	 */
	private static boolean addGenerated(MolBalance.Reaction r) {
		if (!MolBalance.isMassBalanced(r)) {
			LOGGER.warn("[ReactionRules] 生成的候选反应质量不守恒，已丢弃: {}", r);
			return false;
		}
		if (r.machines() == null || r.machines().isEmpty()) {
			LOGGER.warn("[ReactionRules] 生成的候选反应没有机器，已丢弃: {}", r);
			return false;
		}
		ALL.add(r);
		return true;
	}

	/** 把生成器的结果打成紧凑的日志：条数、ban / 禁用表、按原因分组的跳过清单。 */
	private static void logGenerated(ReactionRules.Result result) {
		LOGGER.info("[ReactionRules] 规则生成：显式 {} 条 + 生成 {} 条 = {} 条；跳过 {} 条，缺物种 {} 种。",
				EXPLICIT_COUNT, GENERATED.size(), ALL.size(), result.skipped().size(),
				result.missingSpecies().size());
		if (!result.bans().isEmpty()) {
			LOGGER.warn("[ReactionRules] 禁用/覆盖物种 {} 项：{}", result.bans().size(), banText(result.bans()));
		}
		if (!result.ruleDisables().isEmpty()) {
			LOGGER.warn("[ReactionRules] 规则级显式禁用 {} 项：{}", result.ruleDisables().size(),
					disableText(result.ruleDisables()));
		}
		if (!result.missingSpecies().isEmpty()) {
			LOGGER.warn("[ReactionRules] 跳过 · 缺物种（下一轮 TODO）：{}", String.join(", ", result.missingSpecies()));
		}
		Map<ReactionRules.Reason, List<ReactionRules.Skip>> byReason = new LinkedHashMap<>();
		for (ReactionRules.Skip skip : result.skipped()) {
			byReason.computeIfAbsent(skip.reason(), k -> new ArrayList<>()).add(skip);
		}
		for (Map.Entry<ReactionRules.Reason, List<ReactionRules.Skip>> e : byReason.entrySet()) {
			LOGGER.warn("[ReactionRules] 跳过[{}] {} 条：{}", e.getKey(), e.getValue().size(),
					skipText(e.getValue()));
		}
	}

	private static String banText(List<ChemicalData.Ban> bans) {
		List<String> out = new ArrayList<>(bans.size());
		for (ChemicalData.Ban b : bans) {
			out.add(b.id() + "(" + b.originalFormula() + ")=" + b.treatment() + "：" + b.reason());
		}
		return String.join("；", out);
	}

	private static String disableText(List<ChemicalData.RuleDisable> disables) {
		List<String> out = new ArrayList<>(disables.size());
		for (ChemicalData.RuleDisable d : disables) {
			out.add(d.rule() + "/" + d.subject() + "：" + d.reason());
		}
		return String.join("；", out);
	}

	private static String skipText(List<ReactionRules.Skip> skips) {
		List<String> out = new ArrayList<>(skips.size());
		for (ReactionRules.Skip s : skips) {
			out.add(s.rule() + " " + s.equation() + "（" + s.detail() + "）");
		}
		return String.join("；", out);
	}

	static {
		// 2 NaCl + 2 H2O → 2 NaOH + H2 + Cl2   rate 0.2（电解炉吞吐上限 0.5，实际 0.1 份/tick）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("sodium_chloride", 2), new MolBalance.Reactant("water", 2) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("sodium_hydroxide", 2, MolBalance.Side.NONE), new MolBalance.Product("hydrogen", 1, MolBalance.Side.CATHODE), new MolBalance.Product("chlorine", 1, MolBalance.Side.ANODE) },
				MolBalance.Kind.ELECTROLYSIS).withPower(FE_PER_MOL_CHLOR_ALKALI), 0.2);

		// H2 + Cl2 → 2 HCl   rate 0.2（光催化剂在位 ×4 → 1.0 份/tick；缺光则不发生）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("hydrogen", 1), new MolBalance.Reactant("chlorine", 1) },
				new String[] { "light" },
				new MolBalance.Product[] { new MolBalance.Product("hydrochloric_acid", 2) },
				true), 0.2);

		// 2 SO2 + O2 → 2 SO3  （接触法；平衡常数约 10^24，离谱到一边倒 —— 刻意判单向，不标 reversible）
		// rate 0.03（钒催化剂 ×4、搅拌 ×1.25 → 约 0.15 份/tick）：全表最慢的催化步，SO2 因此能攒住。
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("sulfur_dioxide", 2), new MolBalance.Reactant("oxygen", 1) },
				new String[] { "vanadium_pentoxide" },
				new MolBalance.Product[] { new MolBalance.Product("sulfur_trioxide", 2) },
				true), 0.03);

		// Na2O + H2O → 2 NaOH   rate 0.8（遇水即成碱，快）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("sodium_oxide", 1), new MolBalance.Reactant("water", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("sodium_hydroxide", 2) },
				false), 0.8);

		// SO3 + H2O → H2SO4   rate 0.6（水合快，但比中和慢一档，SO3 能短暂留存）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("sulfur_trioxide", 1), new MolBalance.Reactant("water", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("sulfuric_acid", 1) },
				false), 0.6);

		// 2 NaCl + H2SO4 → Na2SO4 + 2 HCl   rate 0.3（复分解，中速）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("sodium_chloride", 2), new MolBalance.Reactant("sulfuric_acid", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("sodium_sulfate", 1), new MolBalance.Product("hydrochloric_acid", 2) },
				false), 0.3);

		// NaOH + HCl → NaCl + H2O   rate 0.8（中和，快 —— 用户点名「中和类快」）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("sodium_hydroxide", 1), new MolBalance.Reactant("hydrochloric_acid", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("sodium_chloride", 1), new MolBalance.Product("water", 1) },
				false), 0.8);

		// CO2 + 2 NaOH → Na2CO3 + H2O   rate 0.6（气体吸收，较快）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("carbon_dioxide", 1), new MolBalance.Reactant("sodium_hydroxide", 2) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("sodium_carbonate", 1), new MolBalance.Product("water", 1) },
				false), 0.6);

		// NaOH + CO2 → NaHCO3   rate 0.6（同上，两步吸收）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("sodium_hydroxide", 1), new MolBalance.Reactant("carbon_dioxide", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("sodium_hydrogen_carbonate", 1) },
				false), 0.6);

		// 2 NaHCO3 <-> Na2CO3 + H2O + CO2  （小苏打受热分解；可逆，CO2 / H2O 排出后趋完全，K=8）
		// rate 0.05：煅烧类反应，需持续加热，慢。
		add(MolBalance.Reaction.ofReversible(
				new MolBalance.Reactant[] { new MolBalance.Reactant("sodium_hydrogen_carbonate", 2) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("sodium_carbonate", 1), new MolBalance.Product("water", 1), new MolBalance.Product("carbon_dioxide", 1) },
				false, 8.0), 0.05);

		// CaCO3 <-> CaO + CO2  （石灰石煅烧；可逆，CO2 排出后趋完全，K=6）  rate 0.05（窑烧慢）
		add(MolBalance.Reaction.ofReversible(
				new MolBalance.Reactant[] { new MolBalance.Reactant("calcium_carbonate", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("calcium_oxide", 1), new MolBalance.Product("carbon_dioxide", 1) },
				false, 6.0), 0.05);

		// CaO + H2O → Ca(OH)2   rate 0.8（消化，遇水即成，快）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("calcium_oxide", 1), new MolBalance.Reactant("water", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("calcium_hydroxide", 1) },
				false), 0.8);

		// Na2CO3 + Ca(OH)2 → CaCO3 + 2 NaOH   rate 0.4（苛化，中速）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("sodium_carbonate", 1), new MolBalance.Reactant("calcium_hydroxide", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("calcium_carbonate", 1), new MolBalance.Product("sodium_hydroxide", 2) },
				false), 0.4);

		// CaCO3 + 2 HCl → CaCl2 + H2O + CO2   rate 0.5（酸溶，较快）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("calcium_carbonate", 1), new MolBalance.Reactant("hydrochloric_acid", 2) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("calcium_chloride", 1), new MolBalance.Product("water", 1), new MolBalance.Product("carbon_dioxide", 1) },
				false), 0.5);

		// 2 NO + O2 → 2 NO2   rate 0.3（NO 氧化，中速）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("nitric_oxide", 2), new MolBalance.Reactant("oxygen", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("nitrogen_dioxide", 2) },
				true), 0.3);

		// 3 NO2 + H2O → 2 HNO3 + NO   rate 0.3（吸收塔，中速）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("nitrogen_dioxide", 3), new MolBalance.Reactant("water", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("nitric_acid", 2), new MolBalance.Product("nitric_oxide", 1) },
				false), 0.3);

		// N2 + 3 H2 <-> 2 NH3  （合成氨；可逆，粗平衡比 K=0.25 —— 常压一次转化率约 1/4）
		// rate 0.02（搅拌 ×1.25 → 0.025 份/tick）：常压合成氨很慢，N2/H2 会长时间停留。
		add(MolBalance.Reaction.ofReversible(
				new MolBalance.Reactant[] { new MolBalance.Reactant("nitrogen", 1), new MolBalance.Reactant("hydrogen", 3) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("ammonia", 2) },
				true, 0.25), 0.02);

		// NH3 + HCl → NH4Cl   rate 0.8（酸碱成盐发烟，快）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("ammonia", 1), new MolBalance.Reactant("hydrochloric_acid", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("ammonium_chloride", 1) },
				false), 0.8);

		// NH3 + H2O → NH4OH   rate 0.6（溶解平衡，较快）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("ammonia", 1), new MolBalance.Reactant("water", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("ammonium_hydroxide", 1) },
				false), 0.6);

		// HNO3 + NH4OH → NH4NO3 + H2O   rate 0.8（中和，快）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("nitric_acid", 1), new MolBalance.Reactant("ammonium_hydroxide", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("ammonium_nitrate", 1), new MolBalance.Product("water", 1) },
				false), 0.8);

		// H2 + F2 → 2 HF   rate 0.8（氟氢直接化合，爆快）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("hydrogen", 1), new MolBalance.Reactant("fluorine", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("hydrogen_fluoride", 2) },
				true), 0.8);

		// 2 H2O → 2 H2 + O2   rate 0.2（电解炉吞吐上限 0.5，实际 0.1 份/tick）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("water", 2) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("hydrogen", 2, MolBalance.Side.CATHODE), new MolBalance.Product("oxygen", 1, MolBalance.Side.ANODE) },
				MolBalance.Kind.ELECTROLYSIS).withPower(FE_PER_MOL_WATER), 0.2);

		// 4 HF + SiO2 → SiF4 + 2 H2O   rate 0.1（玻璃蚀刻，慢）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("hydrogen_fluoride", 4), new MolBalance.Reactant("silicon_dioxide", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("silicon_tetrafluoride", 1), new MolBalance.Product("water", 2) },
				false), 0.1);

		// Fe2O3 + 6 HCl → 2 FeCl3 + 3 H2O   rate 0.15（氧化物溶解，慢于中和）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("iron_oxide", 1), new MolBalance.Reactant("hydrochloric_acid", 6) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("iron_chloride", 2), new MolBalance.Product("water", 3) },
				false), 0.15);

		// MnO2 + 4 HCl → MnCl2 + Cl2 + 2 H2O   rate 0.15（需加热的氧化溶解）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("manganese_dioxide", 1), new MolBalance.Reactant("hydrochloric_acid", 4) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("manganese_chloride", 1), new MolBalance.Product("chlorine", 1), new MolBalance.Product("water", 2) },
				false), 0.15);

		// KOH + HCl → KCl + H2O   rate 0.8（中和，快）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("potassium_hydroxide", 1), new MolBalance.Reactant("hydrochloric_acid", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("potassium_chloride", 1), new MolBalance.Product("water", 1) },
				false), 0.8);

		// 2 KOH + CO2 → K2CO3 + H2O   rate 0.6（气体吸收，较快）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("potassium_hydroxide", 2), new MolBalance.Reactant("carbon_dioxide", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("potassium_carbonate", 1), new MolBalance.Product("water", 1) },
				false), 0.6);

		// RE2O3 + 6 HCl → 2 RECl3 + 3 H2O   rate 0.15（稀土氧化物酸溶，慢）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("rare_earth_oxide", 1), new MolBalance.Reactant("hydrochloric_acid", 6) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("rare_earth_chloride", 2), new MolBalance.Product("water", 3) },
				false), 0.15);

		// 2 RECl3 → LCl3 + HCl3   rate 0.01（轻重稀土分家：全表最慢，搅拌 ×1.25 → 0.0125 份/tick）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("rare_earth_chloride", 2) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("light_rare_earth_chloride", 1), new MolBalance.Product("heavy_rare_earth_chloride", 1) },
				true), 0.01);

		// 2 LCl3 + 3 H2C2O4 → L2(C2O4)3 + 6 HCl   rate 0.05
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("light_rare_earth_chloride", 2), new MolBalance.Reactant("oxalic_acid", 3) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("light_rare_earth_oxalate", 1), new MolBalance.Product("hydrochloric_acid", 6) },
				false), 0.05);

		// 2 HCl3 + 3 H2C2O4 → H2(C2O4)3 + 6 HCl   rate 0.05
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("heavy_rare_earth_chloride", 2), new MolBalance.Reactant("oxalic_acid", 3) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("heavy_rare_earth_oxalate", 1), new MolBalance.Product("hydrochloric_acid", 6) },
				false), 0.05);

		// L2(C2O4)3 → L2O3 + 3 CO2 + 3 CO   rate 0.03（草酸盐焙烧）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("light_rare_earth_oxalate", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("light_rare_earth_oxide", 1), new MolBalance.Product("carbon_dioxide", 3), new MolBalance.Product("carbon_monoxide", 3) },
				false), 0.03);

		// H2(C2O4)3 → H2O3 + 3 CO2 + 3 CO   rate 0.03（草酸盐焙烧）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("heavy_rare_earth_oxalate", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("heavy_rare_earth_oxide", 1), new MolBalance.Product("carbon_dioxide", 3), new MolBalance.Product("carbon_monoxide", 3) },
				false), 0.03);

		// S + O2 → SO2  （自然硫焙烧：硫酸链的起点；THERMAL → 反应釜 + 搅拌器）  rate 0.5
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("sulfur", 1), new MolBalance.Reactant("oxygen", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("sulfur_dioxide", 1) }), 0.5);

		// ================================================================ 矿物 → 离子物种 的下游（桥接表把矿接进化学世界后，这些反应才跑得起来）

		// CaF2 + H2SO4 → CaSO4 + 2 HF  （萤石制 HF 的经典路线：强酸置换，THERMAL）  rate 0.2
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("calcium_fluoride", 1), new MolBalance.Reactant("sulfuric_acid", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("calcium_sulfate", 1), new MolBalance.Product("hydrogen_fluoride", 2) }), 0.2);

		// 2 Al(OH)3 <-> Al2O3 + 3 H2O  （拜耳法后段：氢氧化铝焙烧；可逆，水排出后趋完全，K=6）  rate 0.05
		add(MolBalance.Reaction.ofReversible(
				new MolBalance.Reactant[] { new MolBalance.Reactant("aluminum_hydroxide", 2) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("aluminum_oxide", 1), new MolBalance.Product("water", 3) },
				false, 6.0), 0.05);

		// MgCO3 <-> MgO + CO2  （菱镁矿焙烧；可逆，CO2 排出后趋完全，K=6；金属镁仍是熔盐电解的活）  rate 0.05
		add(MolBalance.Reaction.ofReversible(
				new MolBalance.Reactant[] { new MolBalance.Reactant("magnesium_carbonate", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("magnesium_oxide", 1), new MolBalance.Product("carbon_dioxide", 1) },
				false, 6.0), 0.05);

		// Ca3(PO4)2 + 5 C → 2 P + 3 CaO + 5 CO  （磷炉：磷酸钙碳热还原，THERMAL；CO 逸出，单向）  rate 0.02（电炉，很慢）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("calcium_phosphate", 1), new MolBalance.Reactant("carbon", 5) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("phosphorus", 2), new MolBalance.Product("calcium_oxide", 3), new MolBalance.Product("carbon_monoxide", 5) }), 0.02);

		// 2 HF → H2 + F2  （无水氟化氢电解：氟单质，ELECTROLYSIS）  rate 0.1（电解上限 0.5 → 0.05 份/tick，慢）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("hydrogen_fluoride", 2) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("hydrogen", 1, MolBalance.Side.CATHODE), new MolBalance.Product("fluorine", 1, MolBalance.Side.ANODE) },
				MolBalance.Kind.ELECTROLYSIS).withPower(FE_PER_MOL_HF), 0.1);

		// NaCl + H2SO4 → NaHSO4 + HCl  （硫酸氢钠：芒硝法前段，THERMAL）  rate 0.3
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("sodium_chloride", 1), new MolBalance.Reactant("sulfuric_acid", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("sodium_bisulfate", 1), new MolBalance.Product("hydrochloric_acid", 1) }), 0.3);

		// H2 + S → H2S  （硫化氢；气—固接触需搅拌，STIRRING）  rate 0.15（搅拌 → 约 0.19 份/tick）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("hydrogen", 1), new MolBalance.Reactant("sulfur", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("hydrogen_sulfide", 1) },
				true), 0.15);

		// 2 NaOH + H2S → Na2S + 2 H2O  （硫化钠，THERMAL）  rate 0.8
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("sodium_hydroxide", 2), new MolBalance.Reactant("hydrogen_sulfide", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("sodium_sulfide", 1), new MolBalance.Product("water", 2) }), 0.8);

		// NaOH + HF → NaF + H2O  （氟化钠，THERMAL）  rate 0.8
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("sodium_hydroxide", 1), new MolBalance.Reactant("hydrogen_fluoride", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("sodium_fluoride", 1), new MolBalance.Product("water", 1) }), 0.8);

		// Cl2 + H2O <-> HCl + HClO  （氯水，经典可逆；bug#21）
		// 用户报「搅拌器无法混合氯气和水」—— 反应根本不在表里，混匀了也只会显示「无反应」。
		// STIRRING：气—液接触靠搅拌，正是搅拌器的活；顺带保证反应釜不会「随手把氯水做掉」。
		// 粗平衡比 K = 0.25：与合成氨同一档（本模型的 Q = Σ产物/Σ反应物，水在分母里已把 Q 压得很低；
		// 真正标定 Kc 不在本轮口径内）。15 mol Cl2 + 15 mol 水会生成约 3 mol HCl + 3 mol HClO 后停住。
		// rate 0.1（搅拌 ×1.25 → 0.125 份/tick）：氯水生成慢，Cl2 / H2O 会长时间共存。
		add(MolBalance.Reaction.ofReversible(
				new MolBalance.Reactant[] { new MolBalance.Reactant("chlorine", 1), new MolBalance.Reactant("water", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("hydrochloric_acid", 1), new MolBalance.Product("hypochlorous_acid", 1) },
				true, 0.25), 0.1);

		// ================================================================ §十六补的缺口（草酸链 / 钾源；氮源见本轮 §十七的矿物路线）
		// 这些反应是「显式覆盖层」：它们把原来没有来源的 oxalic_acid / KOH 接进图，
		// 生成的规则边照旧只在硬编码表之外补充。

		// CO + NaOH → HCOONa  （甲酸钠：工业上 CO 被烧碱吸收）  rate 0.4（气—液吸收，中速）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("carbon_monoxide", 1), new MolBalance.Reactant("sodium_hydroxide", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("sodium_formate", 1) }), 0.4);

		// 2 HCOONa → Na2C2O4 + H2  （甲酸钠干热脱氢制草酸钠）  rate 0.05（要持续加热，慢）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("sodium_formate", 2) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("sodium_oxalate", 1), new MolBalance.Product("hydrogen", 1) }), 0.05);

		// Na2C2O4 + H2SO4 → H2C2O4 + Na2SO4  （草酸钠酸化，复分解）  rate 0.3
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("sodium_oxalate", 1), new MolBalance.Reactant("sulfuric_acid", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("oxalic_acid", 1), new MolBalance.Product("sodium_sulfate", 1) }), 0.3);

		// 2 KCl + 2 H2O → 2 KOH + H2 + Cl2  （钾版氯碱：把岩盐电解换成钾盐电解，KOH 的唯一来源）  rate 0.2
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("potassium_chloride", 2), new MolBalance.Reactant("water", 2) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("potassium_hydroxide", 2), new MolBalance.Product("hydrogen", 1, MolBalance.Side.CATHODE), new MolBalance.Product("chlorine", 1, MolBalance.Side.ANODE) },
				MolBalance.Kind.ELECTROLYSIS).withPower(FE_PER_MOL_POTASH), 0.2);

		// ================================================================ 本轮：氮来自矿物（§十七；空气分离已删除，不再是环境根）
		// 硝石 / 硇砂在桥接表里由离心机浸出成物种，这里的反应只做物种之间的转化。

		// NH4Cl + NaOH → NH3 + NaCl + H2O  （硇砂 + 烧碱：不需要 Haber 的制氨路线）  rate 0.5（气—液逸出，中速）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("ammonium_chloride", 1), new MolBalance.Reactant("sodium_hydroxide", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("ammonia", 1), new MolBalance.Product("sodium_chloride", 1), new MolBalance.Product("water", 1) }), 0.5);

		// 2 NaNO3 + H2SO4 → Na2SO4 + 2 HNO3  （硝石 + 硫酸：挥发法制硝酸）  rate 0.3（复分解，中速）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("sodium_nitrate", 2), new MolBalance.Reactant("sulfuric_acid", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("sodium_sulfate", 1), new MolBalance.Product("nitric_acid", 2) }), 0.3);

		// 2 KNO3 + H2SO4 → K2SO4 + 2 HNO3  （钾硝石同法；顺带补上 K2SO4 这个原本缺失的盐）  rate 0.3
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("potassium_nitrate", 2), new MolBalance.Reactant("sulfuric_acid", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("potassium_sulfate", 1), new MolBalance.Product("nitric_acid", 2) }), 0.3);

		// NH3 + HNO3 → NH4NO3  （氨与硝酸直接成盐，硝酸铵的最短路线）  rate 0.8（中和，快）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("ammonia", 1), new MolBalance.Reactant("nitric_acid", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("ammonium_nitrate", 1) }), 0.8);

		// 2 NH4NO3 → 2 N2 + O2 + 4 H2O  （硝酸铵受热分解：N2 从这里来，不再靠空气分离）  rate 0.05（持续加热，慢）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("ammonium_nitrate", 2) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("nitrogen", 2), new MolBalance.Product("oxygen", 1), new MolBalance.Product("water", 4) }), 0.05);

		// 4 HNO3 → 4 NO2 + O2 + 2 H2O  （浓硝酸受热分解；与既有吸收 / NO 氧化共同构成 Ostwald 环）  rate 0.1（需加热，慢）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("nitric_acid", 4) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("nitrogen_dioxide", 4), new MolBalance.Product("oxygen", 1), new MolBalance.Product("water", 2) }), 0.1);

		// 2 NaOH → Na2O + H2O  （烧碱高温脱水 / 焙烧：水蒸气必须离开才成立；sodium_oxide 的唯一生成路线）  rate 0.05
		//
		// 2026-10-17（bug#36）：这条判成 Kind.ROASTING —— 常温反应釜里，它与规则 1 生成的
		// Na2O + H2O → 2 NaOH 是一对「快 + 慢」的非可逆互逆反应，会一直对消、在釜尾留下固定的一撮
		// Na2O 与多出来的水（实测 0.05 mol，占 50 mol 槽的 0.1%），釜子永远显示「反应中」。
		// 焙烧需要高温把水蒸气排走，密闭釜里不成立，所以两台容器型机器都不再跑它。
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("sodium_hydroxide", 2) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("sodium_oxide", 1), new MolBalance.Product("water", 1) },
				MolBalance.Kind.ROASTING), 0.05);

		// 2 MnO2 + 4 KOH + O2 → 2 K2MnO4 + 2 H2O  （软锰矿碱熔氧化：生成锰酸钾）  rate 0.05（焙烧，慢）
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("manganese_dioxide", 2), new MolBalance.Reactant("potassium_hydroxide", 4), new MolBalance.Reactant("oxygen", 1) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("potassium_manganate", 2), new MolBalance.Product("water", 2) }), 0.05);

		// 2 K2MnO4 + 2 H2O → 2 KMnO4 + 2 KOH + H2  （锰酸钾溶液电解氧化：阳极 MnO4²⁻ → MnO4⁻，阴极放 H2）  rate 0.1
		add(new MolBalance.Reaction(
				new MolBalance.Reactant[] { new MolBalance.Reactant("potassium_manganate", 2), new MolBalance.Reactant("water", 2) },
				new String[] {  },
				new MolBalance.Product[] { new MolBalance.Product("potassium_permanganate", 2), new MolBalance.Product("potassium_hydroxide", 2), new MolBalance.Product("hydrogen", 1, MolBalance.Side.CATHODE) },
				MolBalance.Kind.ELECTROLYSIS).withPower(FE_PER_MOL_MANGANATE), 0.1);
	}

	// 启动期断言：表里每一条反应都必须至少绑定一台机器。
	// 规范构造器与 add() 已经各查一次，这里是对「整张表」的最终兜底 —— 将来有人绕过 add()
	// 直接往 ALL 里塞反应时，启动仍然会在这里炸，而不是留一条永远跑不起来的边。
	static {
		for (MolBalance.Reaction r : ALL) {
			if (r.machines() == null || r.machines().isEmpty()) {
				throw new IllegalStateException("反应没有可用机器: " + r);
			}
		}
	}

	// 覆盖层建完后，跑规则生成：接受的反应追加进同一张 ALL 表（唯一事实来源），
	// 配不平 / 缺物种 / 与显式表冲突的一律丢弃并进 WARN 跳过清单。
	static {
		EXPLICIT_COUNT = ALL.size();
		ReactionRules.Result result = ReactionRules.generate(List.copyOf(ALL));
		for (ReactionRules.Added added : result.added()) {
			if (addGenerated(added.reaction())) {
				GENERATED.add(added.reaction());
			}
		}
		logGenerated(result);
	}

	// 启动期断言之二：每条<b>电解</b>反应都必须显式标过电解单价（2026-10-17 逐反应计费）。
	// 漏标会落进 {@code DEFAULT_FE_PER_MOL = 120} 的兜底 —— 宁可启动就炸，
	// 也不要让一条新反应悄悄按旧平铺价算（那正是本轮要消灭的东西）。
	static {
		for (MolBalance.Reaction r : ALL) {
			if (r.kind() == MolBalance.Kind.ELECTROLYSIS && !r.priced()) {
				throw new IllegalStateException("电解反应没有标价（fePerMol）: " + r);
			}
		}
	}

	/** 全部反应（只读；显式表在前，生成表追加在后）。 */
	public static List<MolBalance.Reaction> all() {
		return List.copyOf(ALL);
	}

	/** 硬编码（覆盖层）反应条数。 */
	public static int explicitCount() {
		return EXPLICIT_COUNT;
	}

	/** 由规则生成并接受的反应（只读）。 */
	public static List<MolBalance.Reaction> generated() {
		return List.copyOf(GENERATED);
	}

	/** 由规则生成并接受的反应条数。 */
	public static int generatedCount() {
		return GENERATED.size();
	}

	/** 第 index 条（从 1 开始编号，UI 用）；越界返回 null。 */
	public static MolBalance.Reaction byIndex(int index) {
		int i = index - 1;
		return i < 0 || i >= ALL.size() ? null : ALL.get(i);
	}

	/** 表里有几条反应。 */
	public static int count() {
		return ALL.size();
	}
}
