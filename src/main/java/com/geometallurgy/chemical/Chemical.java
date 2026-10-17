package com.geometallurgy.chemical;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 化学物质定义表（mol 计量体系的「物质是谁」）。
 *
 * <p>与 {@link Substance} 同构：键是字符串 id，静态表 + record。区别在于这里
 * 每一条还带 <b>摩尔质量 M</b>（g/mol）—— 这是 mol→质量 的唯一本征换算，也是
 * 整个 mol 体系的锚点（§一）。B（1 B = 1000 mB）只是 1:1 显示名，不查物性，
 * 不需要密度。
 *
 * <p>每一条还带 <b>显示色</b>（0xRRGGBB）：金属桶里流体那一层的颜色（见
 * {@link #colorOf(MolStock)} 的按 mol 加权混色）。无色物质给近无色，
 * 显色物质（NO₂ 红棕、KMnO₄ 紫、FeCl₃ 黄棕、Fe₂O₃ 红）给本征色 —— 玩家看一眼
 * 桶就知道装的是什么。
 *
 * <p>覆盖设计稿 §11.3 的无机试剂：轨 A 是气体/单质，轨 B 是酸/碱/盐溶液，
 * 外加稀土分离链（氧化物→氯化物→轻/重分组→草酸盐→氧化物）。
 * 有机物走 §十 的哈希体系，是下一阶段，不在此表。
 */
public final class Chemical {

	private Chemical() {
	}

	/**
	 * 一条化学物质。
	 *
	 * <p>{@code phase}: GAS / LIQUID / SOLID；{@code solution} 为 true 表示是「溶质」，需配溶剂；
	 * {@code color} 是 0xRRGGBB 显示色（桶里流体层的颜色，无色给近无色）。
	 */
	public record Info(String id, String formula, double molarMass, Phase phase, boolean solution, int color) {
	}

	/** 相态。B 显示名与它无关（1:1 恒定），仅用于 UI 图标/桶倒置。 */
	public enum Phase {
		GAS, LIQUID, SOLID
	}

	/** 无色物质的兜底显示色（近无色）。 */
	public static final int COLORLESS = 0xEFEFEF;

	private static final Map<String, Info> ALL = new LinkedHashMap<>();

	/** 重稀土公式里的 H 是伪元素（{@link ChemicalData} 的别名 H → HRE），摩尔质量须按 HRE 算。 */
	private static final Map<String, String> HEAVY_RARE_EARTH_ALIAS = Map.of("H", "HRE");

	/**
	 * 建一条物质：摩尔质量由 {@link MolarMass} 从公式<b>现算</b>（原子量唯一来源，见 bug#39）。
	 *
	 * <p>从前这里收一个手写的 {@code double m}，于是同一元素在不同条目里各写各的取整
	 * （Ca 在 CaO 当 40、在 CaF2 当 40.07、在 CaSO4 当 40.14……），配平残差混过 1 g 的启动校验后
	 * 在长时间反应里逐条累积。现在整表只有 {@link MolarMass} 一个原子量来源，不再有第二处摩尔质量。
	 */
	private static void gas(String id, String formula, int color) {
		ALL.put(id, new Info(id, formula, MolarMass.of(formula), Phase.GAS, false, color));
	}

	private static void liquid(String id, String formula, boolean solution, int color) {
		ALL.put(id, new Info(id, formula, MolarMass.of(formula), Phase.LIQUID, solution, color));
	}

	private static void solid(String id, String formula, int color) {
		ALL.put(id, new Info(id, formula, MolarMass.of(formula), Phase.SOLID, false, color));
	}

	/** 重稀土（公式里的 H 是伪元素）：按 HRE 的原子量算摩尔质量。 */
	private static void heavySolid(String id, String formula, int color) {
		ALL.put(id, new Info(id, formula, MolarMass.of(formula, HEAVY_RARE_EARTH_ALIAS), Phase.SOLID, false, color));
	}

	private static void heavyLiquid(String id, String formula, boolean solution, int color) {
		ALL.put(id, new Info(id, formula, MolarMass.of(formula, HEAVY_RARE_EARTH_ALIAS), Phase.LIQUID, solution, color));
	}

	static {
		// ------------------------------------------------------------------ 轨 A：气体 / 单质
		gas("chlorine", "Cl2", 0xD8E850);
		gas("hydrogen", "H2", 0xE8E8E8);
		gas("fluorine", "F2", 0xE4E88A);
		gas("oxygen", "O2", 0xE8E8E8);   // 制硫酸中间体
		gas("nitrogen", "N2", 0xE8E8E8); // 惰性/吹扫
		liquid("sulfur", "S", false, 0xD8C93A);    // 单质硫，作粉/块
		liquid("phosphorus", "P", false, 0xE0D8C0);

		// 硫酸中间体（气体）
		gas("sulfur_dioxide", "SO2", 0xE9E4C0);
		gas("sulfur_trioxide", "SO3", 0xEDEDED);

		// 制烧碱的原料（氧化钠）
		liquid("sodium_oxide", "Na2O", false, 0xF0F0F0);

		// ------------------------------------------------------------------ 轨 B：酸 / 碱 / 盐溶液（溶质）
		liquid("hydrochloric_acid", "HCl", true, 0xE4EDE4);  // 36.5
		liquid("sodium_hydroxide", "NaOH", true, 0xF2F2F2);
		liquid("sulfuric_acid", "H2SO4", true, 0xE8E4D0);
		liquid("sodium_chloride", "NaCl", true, 0xEEEEEE);   // 58.5
		// 溶剂（水）单独列，作一切溶液的介质；色取原版水桶蓝
		liquid("water", "H2O", false, 0x3F76E4);

		// ------------------------------------------------------------------ 常见气体（化合物）
		gas("carbon_dioxide", "CO2", 0xEDEDED);
		gas("carbon_monoxide", "CO", 0xEDEDED);
		gas("ammonia", "NH3", 0xE4EDF2);
		gas("nitric_oxide", "NO", 0xE8E8E8);
		gas("nitrogen_dioxide", "NO2", 0xB04A20);
		gas("hydrogen_fluoride", "HF", 0xE4EDE4);
		gas("hydrogen_sulfide", "H2S", 0xE6E8DC);
		gas("silicon_tetrafluoride", "SiF4", 0xEDEDED);

		// ------------------------------------------------------------------ 常见酸碱盐溶液
		liquid("nitric_acid", "HNO3", true, 0xE9EDE9);
		liquid("sodium_carbonate", "Na2CO3", true, 0xF0F0F0);
		liquid("calcium_hydroxide", "Ca(OH)2", true, 0xECECEC);
		liquid("calcium_chloride", "CaCl2", true, 0xEDEDED);
		liquid("manganese_chloride", "MnCl2", true, 0xE8D8D0);
		liquid("sodium_sulfate", "Na2SO4", true, 0xF0F0F0);
		liquid("sodium_bisulfate", "NaHSO4", true, 0xEFEFEF);
		liquid("ammonium_chloride", "NH4Cl", true, 0xF0F0F0);
		liquid("ammonium_hydroxide", "NH4OH", true, 0xE8EDED);
		liquid("ammonium_nitrate", "NH4NO3", true, 0xF0F0F0);
		liquid("sodium_fluoride", "NaF", true, 0xF0F0F0);
		liquid("sodium_sulfide", "Na2S", true, 0xE8DFA8);
		liquid("potassium_hydroxide", "KOH", true, 0xF0F0F0);
		liquid("potassium_chloride", "KCl", true, 0xEEEEEE);
		liquid("potassium_carbonate", "K2CO3", true, 0xEFEFEF);
		liquid("potassium_permanganate", "KMnO4", true, 0x5B2A86);
		liquid("iron_chloride", "FeCl3", true, 0x8A5A2A);
		liquid("oxalic_acid", "H2C2O4", true, 0xF2F2F2);
		liquid("rare_earth_chloride", "RECl3", true, 0xD8D0E0);
		liquid("light_rare_earth_chloride", "LCl3", true, 0xDED4E6);
		heavyLiquid("heavy_rare_earth_chloride", "HCl3", true, 0xC6BCD6);
		liquid("light_rare_earth_oxalate", "L2(C2O4)3", true, 0xE8E2EE);
		heavyLiquid("heavy_rare_earth_oxalate", "H2(C2O4)3", true, 0xD8D2E0);

		// ------------------------------------------------------------------ 固体（沉淀 / 氧化物 / 粉料）
		solid("calcium_carbonate", "CaCO3", 0xF2F0E8);
		solid("calcium_oxide", "CaO", 0xF4F4F4);
		solid("silicon_dioxide", "SiO2", 0xE8E6DC);
		solid("manganese_dioxide", "MnO2", 0x2E2E38);
		solid("iron_oxide", "Fe2O3", 0x9E4B2A);
		solid("aluminum_oxide", "Al2O3", 0xEDEDF2);
		solid("sodium_hydrogen_carbonate", "NaHCO3", 0xF2F2F2);
		solid("rare_earth_oxide", "RE2O3", 0xD8D0E0);
		solid("light_rare_earth_oxide", "L2O3", 0xE0D6E8);
		heavySolid("heavy_rare_earth_oxide", "H2O3", 0xC8BED8);

		// ------------------------------------------------------------------ 矿物 → 离子物种（桥接表新增；必须追加在最后，见文件头 wire-format 约定）
		// 这几条都是「盐 / 氢氧化物」本体：萤石 CaF2、石膏 CaSO4、铝土矿的 Al(OH)3、
		// 菱镁矿 MgCO3 与其焙烧产物 MgO、磷灰石的主成分 Ca3(PO4)2，以及碳热还原
		// 磷酸盐要用的固体碳。前 15 种是 wire-format 契约，新物质只能往表尾追加。
		solid("carbon", "C", 0x2A2A2E);
		solid("calcium_fluoride", "CaF2", 0xEDEDF2);
		solid("calcium_sulfate", "CaSO4", 0xF2F0E8);
		solid("aluminum_hydroxide", "Al(OH)3", 0xEDEDF2);
		solid("magnesium_carbonate", "MgCO3", 0xF0EEE8);
		solid("magnesium_oxide", "MgO", 0xF4F4F4);
		solid("calcium_phosphate", "Ca3(PO4)2", 0xE8E4DA);

		// 氯水（bug#21 的产物）：Cl2 + H2O <-> HCl + HClO。同样只能追加在表尾 ——
		// 前 15 种是 wire-format 契约（物质序号 = 登记下标），顺序一旦改动就与旧存档/同步错位。
		// HClO：M = 1 + 35.5 + 16 = 52.5（与表内 Cl 取 35.5 的口径一致），弱酸水溶液。
		liquid("hypochlorous_acid", "HClO", true, 0xE9F0D0);

		// ------------------------------------------------------------------ 草酸链（本轮新增；只追加在表尾）
		// CO + NaOH → 甲酸钠（工业上 CO 被 NaOH 吸收）；2 甲酸钠 → 草酸钠 + H2（干热脱氢）；
		// 草酸钠 + H2SO4 → 草酸 + 硫酸钠。这条链把 oxalic_acid 接进可达图，从而解锁
		// 稀土草酸盐 → 稀土氧化物的下游（见 docs/科技线-v1.md §十六）。
		// 甲酸钠写成 NaHCO2（甲酸根 HCO2⁻ 已在 ChemicalData 的酸根表登记），不是 Na2O 的离子对。
		liquid("sodium_formate", "NaHCO2", true, 0xF0F0F0);
		liquid("sodium_oxalate", "Na2C2O4", true, 0xEFEFEF);

		// ------------------------------------------------------------------ 两个弱酸（本轮新增；规则 6「非金属氧化物 + 水」会自动接上）
		// H2CO3 / H2SO3 是 CO2 / SO2 的水合产物，弱酸、只标可逆水合边，不另写反应。
		liquid("carbonic_acid", "H2CO3", true, 0xF0F0F0);
		liquid("sulfurous_acid", "H2SO3", true, 0xF0F0F0);

		// ------------------------------------------------------------------ 氮族 / 钾盐（§十七：氮来自矿物，不做空气分离）
		// 硝石（natratine = NaNO3 / niter = KNO3）与硇砂（sal ammoniac = NH4Cl）是矿物，
		// 在桥接表里由离心机浸出成 salt 物种；这里的物种只负责化学转换，不含矿相。
		liquid("sodium_nitrate", "NaNO3", true, 0xF0F0F0);
		liquid("potassium_nitrate", "KNO3", true, 0xF0F0F0);
		liquid("potassium_sulfate", "K2SO4", true, 0xF0F0F0);
		// 锰酸钾 K2MnO4：高锰酸钾路线的中间体（MnO2 + KOH + O2 焙烧 → 锰酸钾，再电解氧化）。
		// 公式含 MnO4 会被标签推导误标成「高锰酸盐」，由 ChemicalTags 显式摘掉 PERMANGANATE。
		liquid("potassium_manganate", "K2MnO4", true, 0x2E6B4F);

		// ------------------------------------------------------------------ 金属粉（§十八：金属锭粉碎 → 金属粉，规则 5 的入口）
		// 金属单质在表里第一次出现：粉碎机把金属锭磨成粉，粉是 mol 物种，从而能进反应釜与酸反应。
		// 公式就是元素符号，价态 / 活动性序已在 ChemicalData；METAL 标签由公式自动推导。
		solid("iron", "Fe", 0xD8D8D8);
		solid("copper", "Cu", 0xE07A46);
		solid("zinc", "Zn", 0x9FB0BC);
		solid("manganese", "Mn", 0x9E8FA8);
		solid("aluminum", "Al", 0xC4CBD2);
		// 规则 5 配盐需要的两个金属盐（其余盐类缺失的进跳过清单，见 docs §十八）。
		liquid("zinc_chloride", "ZnCl2", true, 0xEDEDED);
		liquid("aluminum_chloride", "AlCl3", true, 0xEDEDF2);
	}

	public static Info of(String id) {
		return ALL.get(id);
	}

	public static boolean isKnown(String id) {
		return ALL.containsKey(id);
	}

	/** 摩尔质量；不认识返回 0（调用方应视为数据错误）。 */
	public static double molarMassOf(String id) {
		Info info = ALL.get(id);
		return info == null ? 0.0 : info.molarMass();
	}

	public static String formulaOf(String id) {
		Info info = ALL.get(id);
		return info == null ? id : info.formula();
	}

	public static Phase phaseOf(String id) {
		Info info = ALL.get(id);
		return info == null ? Phase.SOLID : info.phase();
	}

	/** 显示色（0xRRGGBB）；不认识返回近无色。 */
	public static int colorOf(String id) {
		Info info = ALL.get(id);
		return info == null ? COLORLESS : info.color();
	}

	/**
	 * 一账本的加权显示色：按各成分的 mol 占比混色。
	 *
	 * <p>这是「桶里那一层流体」的颜色 —— 单质就是本征色，混合物就是按量的混色。
	 */
	public static int colorOf(MolStock stock) {
		if (stock == null || stock.isEmpty()) {
			return COLORLESS;
		}
		double total = stock.totalMol();
		if (total <= 0.0) {
			return COLORLESS;
		}
		double r = 0.0;
		double g = 0.0;
		double b = 0.0;
		for (Map.Entry<String, Double> e : stock.view().entrySet()) {
			int c = colorOf(e.getKey());
			double w = e.getValue() / total;
			r += ((c >> 16) & 0xFF) * w;
			g += ((c >> 8) & 0xFF) * w;
			b += (c & 0xFF) * w;
		}
		return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
	}

	private static int clamp(double v) {
		int i = (int) Math.round(v);
		return i < 0 ? 0 : (i > 255 ? 255 : i);
	}

	/** 是否溶剂（水）。溶剂是介质：不参与反应账，只随 mol 记。 */
	public static boolean isSolvent(String id) {
		return "water".equals(id);
	}

	/** 表里有几条物质。 */
	public static int count() {
		return ALL.size();
	}

	/** 登记顺序的 id 列表（只读）。用于把物质 id 压成 int 做同步。 */
	public static java.util.List<String> ids() {
		return java.util.List.copyOf(ALL.keySet());
	}

	/** id 在 {@link #ids()} 里的下标；不认识返回 -1。 */
	public static int indexOf(String id) {
		int i = 0;
		for (String key : ALL.keySet()) {
			if (key.equals(id)) {
				return i;
			}
			i++;
		}
		return -1;
	}

	/** {@link #ids()} 的第 index 个；越界返回 null。 */
	public static String idAt(int index) {
		int i = 0;
		for (String key : ALL.keySet()) {
			if (i == index) {
				return key;
			}
			i++;
		}
		return null;
	}
}
