package com.geometallurgy.chemical;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 物质的「官能团 / 离子类别」标签（轨 B）—— 机器按标签收料，不再逐条写「这个能不能进那台炉子」。
 *
 * <p>物质 = 公式 + 一组性质标签。标签<b>不是</b>手写清单：它从 {@link Chemical} 已有的公式字符串
 * 推导出来（含 OH → 氢氧化物；SO4 / HSO4 → 硫酸盐 / 硫酸氢盐；NO3 → 硝酸盐；CO3 → 碳酸盐；
 * C2O4 → 草酸盐；MnO4 → 高锰酸盐；F → 氟化物；Cl → 氯化物；「元素 + O」→ 氧化物；单一元素 → 单质）。
 * 因此以后往 {@link Chemical} 里<b>追加</b>的物质会自动带上标签，无需回来登记。
 *
 * <p>公式推导必然有推错或推不出的地方（混合价态、伪元素 {@code L} / {@code RE} / {@code H}、
 * 酸性盐、水合物……）。这些一律进 {@link #OVERRIDES} 显式增删表，<b>只在一处改</b>。
 * 矿浆相（{@code proto/OreMineral} 等）没有公式字段，另有一张极小的
 * {@link #PHASE_FORMULAS}「相 id → 化学式」表，走同一套推导。
 *
 * <h2>标签集</h2>
 * 官能团 / 离子：{@link Tag#OXIDE}、{@link Tag#HYDROXIDE}、{@link Tag#CHLORIDE}、{@link Tag#SULFATE}、
 * {@link Tag#BISULFATE}、{@link Tag#CARBONATE}、{@link Tag#PHOSPHATE}、{@link Tag#FLUORIDE}、
 * {@link Tag#NITRATE}、{@link Tag#OXALATE}、{@link Tag#PERMANGANATE}；
 * 酸碱性：{@link Tag#ACID}、{@link Tag#BASE}；
 * 单质：{@link Tag#ELEMENT} 与互斥子标签 {@link Tag#METAL} / {@link Tag#NONMETAL}；
 * 另有两个性质标签：{@link Tag#OXIDIZER}（氧化剂，桶材质要）与 {@link Tag#REFRACTORY}
 * （难还原氧化物，如 Al₂O₃ / MgO，碳夺不动氧，还原炉不收）。相态仍在 {@link Chemical.Phase}，这里不重复。
 *
 * <p>桶的侵蚀类别（酸 / 氟化物 / 氧化剂 / 碱）不再由 {@code item/BucketMaterial} 手工维护，
 * 而是读这里的 {@link #attackMask(String)}，两处从此不会漂移。
 */
public final class ChemicalTags {

	private ChemicalTags() {
	}

	/** 物质标签。 */
	public enum Tag {
		/** 氧化物：含 O，且不是含氧酸盐 / 氢氧化物 / 含氧酸。 */
		OXIDE,
		/** 氢氧化物（含 OH）。 */
		HYDROXIDE,
		/** 氯化物（含 Cl）。 */
		CHLORIDE,
		/** 硫酸盐（SO4）。 */
		SULFATE,
		/** 硫酸氢盐（HSO4），酸性盐。 */
		BISULFATE,
		/** 碳酸盐（CO3）。 */
		CARBONATE,
		/** 磷酸盐（PO4）。 */
		PHOSPHATE,
		/** 氟化物（含 F）。 */
		FLUORIDE,
		/** 硝酸盐（NO3）。 */
		NITRATE,
		/** 草酸盐（C2O4）。 */
		OXALATE,
		/** 高锰酸盐（MnO4），强氧化剂。 */
		PERMANGANATE,
		/** 酸（含酸性盐、酸性氧化物）。 */
		ACID,
		/** 碱（含强碱弱酸盐、氨、碱性氧化物）。 */
		BASE,
		/** 单质。 */
		ELEMENT,
		/** 金属单质（{@link #ELEMENT} 的子标签）。 */
		METAL,
		/** 非金属单质（{@link #ELEMENT} 的子标签）。 */
		NONMETAL,
		/** 氧化剂（性质标签，公式推不出，见显式表）。 */
		OXIDIZER,
		/** 难还原氧化物：碳夺不动氧，还原炉不收（如 Al₂O₃ / MgO）。 */
		REFRACTORY
	}

	// ------------------------------------------------------------------ 桶蚀类别位

	/** 侵蚀类别：酸。与 {@code BucketMaterial.Tag.ACID} 同值。 */
	public static final int ATTACK_ACID = 1;
	/** 侵蚀类别：氟化物。 */
	public static final int ATTACK_FLUORIDE = 2;
	/** 侵蚀类别：氧化剂。 */
	public static final int ATTACK_OXIDIZER = 4;
	/** 侵蚀类别：碱。 */
	public static final int ATTACK_BASE = 8;

	/** 常见非金属元素（用于把单质分成金属 / 非金属）。 */
	private static final Set<String> NONMETALS = Set.of(
			"H", "C", "N", "O", "F", "P", "S", "Cl", "Se", "Br", "I");

	/** 含氧阴离子 / 基团：出现其一就说明「含 O」不是氧化物，而是盐 / 酸 / 氢氧化物。 */
	private static final Set<String> OXO_GROUPS = Set.of(
			"C2O4", "MnO4", "NO3", "CO3", "HSO4", "SO4", "PO4", "OH", "HCO2");

	/** 一次显式改写：加哪些标签、去哪些标签。 */
	private record Override(Set<Tag> add, Set<Tag> remove) {
	}

	/** 显式增删表：公式推错 / 推不出的都只改这里（键 = 物质 id 或相 id）。 */
	private static final Map<String, Override> OVERRIDES = new HashMap<>();

	/** 矿浆相的「相 id → 化学式」表（矿没有公式字段，这里补上；键是 {@code SlurryPart.id()}）。 */
	private static final Map<String, String> PHASE_FORMULAS = new LinkedHashMap<>();

	/** 惰性缓存：id → 最终标签集。 */
	private static final Map<String, Set<Tag>> CACHE = new HashMap<>();

	static {
		registerPhases();
		registerOverrides();

		// 显式表里写错 id 的话在这里就炸，不要等到游戏里才发现
		for (String id : OVERRIDES.keySet()) {
			if (!Chemical.isKnown(id) && !PHASE_FORMULAS.containsKey(id)) {
				throw new IllegalStateException("ChemicalTags 显式表里有未知物质/相: " + id);
			}
		}
	}

	/** 矿浆相公式表：只列会参与路线判定 / 上色的相。 */
	private static void registerPhases() {
		phase("magnetite", "Fe3O4");
		phase("hematite", "Fe2O3");
		phase("limonite", "Fe2O3·H2O");
		phase("native_copper", "Cu");
		phase("chalcopyrite", "CuFeS2");
		phase("malachite", "Cu2CO3(OH)2");
		phase("tetrahedrite", "Cu12Sb4S13");
		phase("sphalerite", "ZnS");
		phase("galena", "PbS");
		phase("cerussite", "PbCO3");
		phase("garnierite", "Ni3Si2O5(OH)4");
		phase("pentlandite", "Fe9Ni9S16");
		phase("cobaltite", "CoAsS");
		phase("pyrolusite", "MnO2");
		phase("rhodochrosite", "MnCO3");
		phase("chromite", "FeCr2O4");
		phase("bauxite", "Al2O3");
		phase("magnesite", "MgCO3");
		phase("cassiterite", "SnO2");
		phase("wolframite", "FeMnWO4");
		phase("molybdenite", "MoS2");
		phase("bismuthinite", "Bi2S3");
		phase("native_silver", "Ag");
		phase("native_gold", "Au");
		phase("quartz", "SiO2");
		phase("fluorite", "CaF2");
		phase("barite", "BaSO4");
		phase("graphite", "C");
		phase("sulfur", "S");
		phase("apatite", "Ca5(PO4)3F");
		phase("bastnasite", "RECO3F");
		phase("monazite", "REPO4");
		phase("xenotime", "REPO4");
		phase("diamond", "C");
	}

	/**
	 * 显式增删表。
	 *
	 * <p>「加」的是公式推不出的化学性质：氧化剂、酸性氧化物、酸性盐、碱性盐、碱性氧化物。
	 * 「去」的是公式推过头的情况：水 / 重稀土氧化物被当成酸、水被当成氧化物。
	 */
	private static void registerOverrides() {
		// ---- 酸（公式不含 H，但确是酸 / 强酸性）
		extra("chlorine", Tag.ACID);
		extra("sulfur_dioxide", Tag.ACID);
		extra("sulfur_trioxide", Tag.ACID);
		extra("ammonium_chloride", Tag.ACID);
		extra("iron_chloride", Tag.ACID);
		extra("rare_earth_chloride", Tag.ACID);
		extra("light_rare_earth_chloride", Tag.ACID);

		// ---- 碱（强碱弱酸盐 / 氨 / 碱性氧化物）
		extra("sodium_oxide", Tag.BASE);
		extra("calcium_oxide", Tag.BASE);
		extra("sodium_carbonate", Tag.BASE);
		extra("potassium_carbonate", Tag.BASE);
		extra("sodium_hydrogen_carbonate", Tag.BASE);
		extra("sodium_sulfide", Tag.BASE);

		// ---- 氧化剂（性质，公式推不出）
		extra("fluorine", Tag.OXIDIZER);
		extra("chlorine", Tag.OXIDIZER);
		extra("nitric_oxide", Tag.OXIDIZER);
		extra("nitrogen_dioxide", Tag.OXIDIZER);
		extra("nitric_acid", Tag.OXIDIZER);
		extra("ammonium_nitrate", Tag.OXIDIZER);
		extra("manganese_dioxide", Tag.OXIDIZER);

		// ---- 去：伪元素 H（重稀土）让公式像酸；水既不是酸也不是可还原氧化物
		suppress("water", Tag.ACID, Tag.OXIDE);
		suppress("heavy_rare_earth_oxalate", Tag.ACID);
		suppress("heavy_rare_earth_oxide", Tag.ACID);
		// 两性氢氧化物：Al(OH)3 遇强碱才溶，不当强碱算（否则玻璃桶会莫名其妙装不住它）
		suppress("aluminum_hydroxide", Tag.BASE);
		// HClO 含 O 但它是酸不是氧化物 —— 公式推导会把它误标成 OXIDE。
		suppress("hypochlorous_acid", Tag.OXIDE);
		// 本轮新增物种的标签纠正：
		// H2CO3 是酸本身，不是「碳酸盐」——否则规则 4 会拿它当碳酸盐去置换，配出 HCl 两侧抵消的伪反应。
		suppress("carbonic_acid", Tag.CARBONATE);
		// H2SO3 的亚硫酸根还没建组（SO3 与中性 SO3 冲突，见 ChemicalData），公式推导见 O 即标 OXIDE；
		// 它是酸不是氧化物，去掉。规则 6 的水合边靠原子计数匹配，不依赖 OXIDE 标签。
		suppress("sulfurous_acid", Tag.OXIDE);
		// K2MnO4 是锰酸盐（MnO4²⁻），公式里的 MnO4 会被推导误标成高锰酸盐；
		// 它确实是氧化剂（OXIDIZER 保留），只摘掉 PERMANGANATE 这个官能团标签。
		suppress("potassium_manganate", Tag.PERMANGANATE);

		// ---- 难还原氧化物：Al₂O₃ / MgO 键能太高，碳夺不动氧，还原炉不收（电解炉独占）
		extra("bauxite", Tag.REFRACTORY);
		extra("aluminum_oxide", Tag.REFRACTORY);
		extra("magnesium_oxide", Tag.REFRACTORY);
	}

	private static void phase(String id, String formula) {
		PHASE_FORMULAS.put(id, formula);
	}

	private static void extra(String id, Tag... tags) {
		OVERRIDES.merge(id, new Override(Set.of(tags), Set.of()), ChemicalTags::mergeOverride);
	}

	private static void suppress(String id, Tag... tags) {
		OVERRIDES.merge(id, new Override(Set.of(), Set.of(tags)), ChemicalTags::mergeOverride);
	}

	private static Override mergeOverride(Override a, Override b) {
		Set<Tag> add = new LinkedHashSet<>(a.add());
		add.addAll(b.add());
		Set<Tag> remove = new LinkedHashSet<>(a.remove());
		remove.addAll(b.remove());
		return new Override(Set.copyOf(add), Set.copyOf(remove));
	}

	// ------------------------------------------------------------------ 推导

	/** 一个 id 的最终标签集（不可变，按 id 缓存）。不认识返回空集。 */
	public static Set<Tag> tagsOf(String id) {
		if (id == null) {
			return Set.of();
		}
		return CACHE.computeIfAbsent(id, ChemicalTags::computeTags);
	}

	/** 这个 id 带不带某个标签。 */
	public static boolean has(String id, Tag tag) {
		return tagsOf(id).contains(tag);
	}

	/** 是不是氧化物。 */
	public static boolean isOxide(String id) {
		return has(id, Tag.OXIDE);
	}

	/** 是不是「可被碳热还原的氧化物」——氧化物且不是 {@link Tag#REFRACTORY}。 */
	public static boolean isReducibleOxide(String id) {
		return has(id, Tag.OXIDE) && !has(id, Tag.REFRACTORY);
	}

	/**
	 * 桶的侵蚀类别位（0 = 温和）。与旧 {@code BucketMaterial.ATTACK} 手写表等价 —— 酸 / 氟化物 /
	 * 氧化剂 / 碱各自从对应标签读。
	 */
	public static int attackMask(String id) {
		Set<Tag> tags = tagsOf(id);
		int mask = 0;
		if (tags.contains(Tag.ACID)) {
			mask |= ATTACK_ACID;
		}
		if (tags.contains(Tag.FLUORIDE)) {
			mask |= ATTACK_FLUORIDE;
		}
		if (tags.contains(Tag.OXIDIZER)) {
			mask |= ATTACK_OXIDIZER;
		}
		if (tags.contains(Tag.BASE)) {
			mask |= ATTACK_BASE;
		}
		return mask;
	}

	/** 调试 / 文档用：把标签按枚举顺序拼成 {@code OXIDE|ACID}；无标签返回 {@code -}。 */
	public static String describe(String id) {
		Set<Tag> tags = tagsOf(id);
		if (tags.isEmpty()) {
			return "-";
		}
		StringBuilder sb = new StringBuilder();
		for (Tag tag : Tag.values()) {
			if (tags.contains(tag)) {
				if (sb.length() > 0) {
					sb.append('|');
				}
				sb.append(tag.name());
			}
		}
		return sb.toString();
	}

	private static Set<Tag> computeTags(String id) {
		String formula = formulaOf(id);
		EnumSet<Tag> tags = formula == null ? EnumSet.noneOf(Tag.class) : derive(formula);
		Override override = OVERRIDES.get(id);
		if (override != null) {
			tags.addAll(override.add());
			tags.removeAll(override.remove());
		}
		return Collections.unmodifiableSet(tags);
	}

	private static String formulaOf(String id) {
		if (Chemical.isKnown(id)) {
			return Chemical.formulaOf(id);
		}
		return PHASE_FORMULAS.get(id);
	}

	/** 从公式串推导标签。只认字符串模式，不解析价态 —— 推不准的交给 {@link #OVERRIDES}。 */
	private static EnumSet<Tag> derive(String formula) {
		EnumSet<Tag> tags = EnumSet.noneOf(Tag.class);
		if (formula == null || formula.isEmpty()) {
			return tags;
		}
		Set<String> elements = elementsOf(formula);
		boolean oxoGroup = false;

		if (formula.contains("C2O4")) {
			tags.add(Tag.OXALATE);
			oxoGroup = true;
		}
		if (formula.contains("MnO4")) {
			tags.add(Tag.PERMANGANATE);
			tags.add(Tag.OXIDIZER);
			oxoGroup = true;
		}
		if (formula.contains("NO3")) {
			tags.add(Tag.NITRATE);
			oxoGroup = true;
		}
		if (formula.contains("CO3")) {
			tags.add(Tag.CARBONATE);
			oxoGroup = true;
		}
		if (formula.contains("HSO4")) {
			tags.add(Tag.BISULFATE);
			tags.add(Tag.ACID);
			oxoGroup = true;
		} else if (formula.contains("SO4")) {
			tags.add(Tag.SULFATE);
			oxoGroup = true;
		}
		if (formula.contains("PO4")) {
			tags.add(Tag.PHOSPHATE);
			oxoGroup = true;
		}
		if (formula.contains("OH")) {
			tags.add(Tag.HYDROXIDE);
			tags.add(Tag.BASE);
			oxoGroup = true;
		}
		if (elements.contains("Cl")) {
			tags.add(Tag.CHLORIDE);
		}
		if (elements.contains("F")) {
			tags.add(Tag.FLUORIDE);
		}

		// 单一元素 = 单质；到此为止，不再往化合物方向推
		if (elements.size() == 1 && formula.matches("[A-Z][a-z]?\\d*")) {
			tags.add(Tag.ELEMENT);
			tags.add(NONMETALS.contains(elements.iterator().next()) ? Tag.NONMETAL : Tag.METAL);
			return tags;
		}

		for (String group : OXO_GROUPS) {
			if (formula.contains(group)) {
				oxoGroup = true;
				break;
			}
		}
		if (formula.contains("O") && !oxoGroup) {
			tags.add(Tag.OXIDE);
		}
		// 以 H 开头的化合物当酸（HCl / H2SO4 / HNO3 / H2S / HF / H2C2O4…）；伪元素 H 的重稀土由显式表去掉
		if (formula.startsWith("H")) {
			tags.add(Tag.ACID);
		}
		if ("NH3".equals(formula)) {
			tags.add(Tag.BASE);
		}
		return tags;
	}

	/** 公式里的元素符号集合（{@code Ca(OH)2 → [Ca, O, H]}）。 */
	private static Set<String> elementsOf(String formula) {
		Set<String> out = new LinkedHashSet<>();
		for (int i = 0; i < formula.length(); i++) {
			char c = formula.charAt(i);
			if (c < 'A' || c > 'Z') {
				continue;
			}
			int end = i + 1;
			if (end < formula.length() && formula.charAt(end) >= 'a' && formula.charAt(end) <= 'z') {
				end++;
			}
			out.add(formula.substring(i, end));
			i = end - 1;
		}
		return out;
	}
}
