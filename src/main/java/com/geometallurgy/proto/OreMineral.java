package com.geometallurgy.proto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.geometallurgy.geology.RockType;
import com.geometallurgy.slurry.SlurryPart;

import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;

import static com.geometallurgy.geology.RockType.Family.*;
import static com.geometallurgy.proto.OreBodyShape.*;
import static com.geometallurgy.proto.OreTier.Body.*;
import static com.geometallurgy.proto.OreTier.Grade.*;
import static com.geometallurgy.proto.OreTier.Spawn.*;

/**
 * 虚拟原矿种类。
 *
 * <p>注意这里指的是<b>矿物形态</b>，不是元素：铁的虚拟原矿是赤铁矿 / 褐铁矿 / 磁铁矿，
 * 而不是原版那根「铁锭」。每种矿物带五组信息：
 * <ul>
 *   <li><b>物理性质</b> —— 密度（g/cm³，真实值）、磁性、<b>莫氏硬度</b>。
 *       密度与磁性给后续的离心机、磁筛机用；硬度给挖掘机的钻头用
 *       （钻头硬度低于矿物硬度就啃不动，或者磨损快）。</li>
 *   <li><b>矿床参数</b>（{@link DepositType}）—— 出现在哪些岩体、<b>出现概率档</b>、
 *       <b>中心丰度档</b>、<b>矿体规模档</b>、矿体形状、杂质池。三条轴离散成 5 档后彼此独立，
 *       见 {@link OreTier}；真实世界的品位 / 稀有度只用来选档，不再直接当作生成参数。</li>
 *   <li><b>显示用色</b> —— 界面与矿浆色条靠它区分。</li>
 * </ul>
 *
 * <p>表格里 {@code dep(...)} 的参数顺序（前三个就是三条独立的轴，各自见 {@link OreTier}）：
 * <pre>
 *   出现概率档, 中心丰度档, 规模档, 矿物自身品位, 有价成分, 杂质池, 母岩族, 形状, 基准规模, 密度下限, 密度上限, 成因
 * </pre>
 * <p>例：{@code dep(S5, G4, B5, 1.000, "gold", ...)} = 稀有出现（6%）× 高丰度（4.5%~7%）×
 * 大矿体（×2.05）。三条轴可各自单独调，互不影响。
 */
public enum OreMineral implements SlurryPart {

	// ------------------------------------------------------------------ 铁（丰度最高的一档，10%~45%）
	MAGNETITE("magnetite", "磁铁矿", 0x3A3A42, 5.17, 1.00, 6.0,
			dep(S1, G1, B1, 0.724, "iron", List.of("sulfur", "manganese", "titanium", "vanadium"),
					Set.of(SEDIMENTARY, PLUTONIC), DISC, 26.0, 0.30, 0.90, "sedimentary")),
	HEMATITE("hematite", "赤铁矿", 0x8E3B32, 5.26, 0.15, 5.5,
			dep(S1, G1, B1, 0.699, "iron", List.of("phosphorus", "sulfur", "arsenic"),
					Set.of(VOLCANIC, SEDIMENTARY), DISC, 30.0, 0.25, 0.90, "sedimentary")),
	LIMONITE("limonite", "褐铁矿", 0x9A6A32, 3.80, 0.05, 5.0,
			dep(S1, G1, B4, 0.570, "iron", List.of("phosphorus", "manganese", "sulfur"),
					Set.of(SEDIMENTARY), POD, 14.0, 0.20, 0.70, "sedimentary")),

	// ------------------------------------------------------------------ 铜（0.5%~10%）
	NATIVE_COPPER("native_copper", "自然铜", 0xB05A32, 8.96, 0.00, 3.0,
			dep(S3, G2, B3, 1.000, "copper", List.of("sulfur", "arsenic", "antimony"),
					Set.of(VOLCANIC), POD, 10.0, 0.40, 0.80, "volcanic")),
	CHALCOPYRITE("chalcopyrite", "黄铜矿", 0xB89B3A, 4.20, 0.00, 3.75,
			dep(S2, G3, B1, 0.346, "copper", List.of("sulfur", "arsenic"),
					Set.of(VOLCANIC, METAMORPHIC), DISSEMINATED, 30.0, 0.35, 0.80, "volcanic")),
	MALACHITE("malachite", "孔雀石", 0x2E8B6E, 4.00, 0.00, 4.0,
			dep(S2, G2, B2, 0.575, "copper", List.of("phosphorus", "sulfur", "arsenic"),
					Set.of(SEDIMENTARY), BAND, 20.0, 0.30, 0.70, "sedimentary")),
	TETRAHEDRITE("tetrahedrite", "黝铜矿", 0x5A5F6E, 4.90, 0.00, 3.5,
			dep(S3, G2, B3, 0.450, "copper", List.of("sulfur", "arsenic", "antimony"),
					Set.of(METAMORPHIC, SEDIMENTARY), LODE, 12.0, 0.35, 0.75, "metamorphic")),

	// ------------------------------------------------------------------ 锌铅（2%~15%）
	SPHALERITE("sphalerite", "闪锌矿", 0x6E6A4A, 4.00, 0.00, 3.75,
			dep(S2, G1, B1, 0.671, "zinc", List.of("sulfur", "manganese", "vanadium"),
					Set.of(METAMORPHIC, SEDIMENTARY), BAND, 28.0, 0.35, 0.80, "sedimentary")),
	GALENA("galena", "方铅矿", 0x6E7480, 7.60, 0.00, 2.6,
			dep(S2, G1, B1, 0.866, "lead", List.of("sulfur", "arsenic", "antimony"),
					Set.of(SEDIMENTARY, METAMORPHIC), BAND, 28.0, 0.35, 0.85, "sedimentary")),
	CERUSSITE("cerussite", "白铅矿", 0xC9C4B0, 6.50, 0.00, 3.25,
			dep(S3, G2, B3, 0.775, "lead", List.of("sulfur", "phosphorus"),
					Set.of(SEDIMENTARY), POD, 12.0, 0.30, 0.70, "sedimentary")),

	// ------------------------------------------------------------------ 镍钴（0.1%~5%）
	GARNIERITE("garnierite", "硅镁镍矿", 0x5FA05A, 2.80, 0.20, 2.5,
			dep(S2, G2, B1, 0.300, "nickel", List.of("sulfur", "arsenic"),
					Set.of(PLUTONIC), PLACER, 28.0, 0.35, 0.75, "plutonic")),
	PENTLANDITE("pentlandite", "镍黄铁矿", 0xC0A24A, 4.90, 0.35, 4.0,
			dep(S3, G2, B4, 0.340, "nickel", List.of("sulfur", "manganese"),
					Set.of(PLUTONIC), PIPE, 10.0, 0.40, 0.85, "plutonic")),
	COBALTITE("cobaltite", "辉砷钴矿", 0x8A6BA0, 6.30, 0.20, 5.5,
			dep(S4, G3, B5, 0.355, "cobalt", List.of("sulfur", "arsenic", "manganese"),
					Set.of(METAMORPHIC, PLUTONIC), LODE, 8.0, 0.30, 0.70, "metamorphic")),

	// ------------------------------------------------------------------ 锰铬（5%~45%）
	PYROLUSITE("pyrolusite", "软锰矿", 0x4A4A52, 5.00, 0.05, 6.25,
			dep(S1, G1, B1, 0.632, "manganese", List.of("phosphorus", "sulfur", "arsenic"),
					Set.of(SEDIMENTARY), DISC, 22.0, 0.30, 0.75, "sedimentary")),
	RHODOCHROSITE("rhodochrosite", "菱锰矿", 0xC06A78, 3.70, 0.05, 3.75,
			dep(S1, G2, B3, 0.478, "manganese", List.of("phosphorus", "sulfur"),
					Set.of(VOLCANIC, SEDIMENTARY), LODE, 12.0, 0.30, 0.70, "sedimentary")),
	CHROMITE("chromite", "铬铁矿", 0x3E4650, 4.80, 0.10, 5.5,
			dep(S2, G1, B4, 0.460, "chromium", List.of("sulfur", "titanium", "vanadium"),
					Set.of(PLUTONIC), POD, 10.0, 0.40, 0.85, "plutonic")),

	// ------------------------------------------------------------------ 轻金属（8%~35%）
	// 氧化铝与氧化镁的键能极高，碳夺不动它们的氧（这正是它们做耐火材料的原因），
	// 工业上也只有熔盐电解一条路 —— 这两块是电解炉的独占对象。
	BAUXITE("bauxite", "铝土矿", 0xC8B49A, 2.50, 0.00, 3.5,
			dep(S1, G1, B3, 0.530, "aluminum", List.of("titanium", "vanadium", "iron"),
					Set.of(SEDIMENTARY), BAND, 18.0, 0.30, 0.70, "sedimentary")),
	MAGNESITE("magnesite", "菱镁矿", 0xDCD6C8, 3.00, 0.00, 4.0,
			dep(S1, G1, B3, 0.288, "magnesium", List.of("iron", "manganese", "sulfur"),
					Set.of(SEDIMENTARY, METAMORPHIC), LODE, 14.0, 0.30, 0.70, "sedimentary")),

	// ------------------------------------------------------------------ 锡钨钼铋（0.04%~1%）
	CASSITERITE("cassiterite", "锡石", 0x8A7A5A, 6.90, 0.00, 6.5,
			dep(S4, G3, B5, 0.788, "tin", List.of("sulfur", "arsenic"),
					Set.of(PLUTONIC), LODE, 12.0, 0.40, 0.85, "plutonic")),
	WOLFRAMITE("wolframite", "黑钨矿", 0x35353A, 7.30, 0.05, 4.5,
			dep(S4, G3, B5, 0.600, "tungsten", List.of("sulfur", "arsenic"),
					Set.of(PLUTONIC, METAMORPHIC), LODE, 13.0, 0.35, 0.80, "plutonic")),
	MOLYBDENITE("molybdenite", "辉钼矿", 0x4A5560, 4.80, 0.00, 1.25,
			dep(S5, G3, B2, 0.599, "molybdenum", List.of("sulfur", "arsenic"),
					Set.of(PLUTONIC), DISSEMINATED, 18.0, 0.30, 0.70, "plutonic")),
	BISMUTHINITE("bismuthinite", "辉铋矿", 0xA8B0BC, 6.80, 0.00, 2.25,
			dep(S5, G4, B5, 0.815, "bismuth", List.of("sulfur", "arsenic", "antimony"),
					Set.of(VOLCANIC, SEDIMENTARY), LODE, 8.0, 0.30, 0.75, "volcanic")),

	// ------------------------------------------------------------------ 贵金属（万分之几到千分之一）
	NATIVE_SILVER("native_silver", "自然银", 0xC8CCD2, 10.50, 0.00, 2.75,
			dep(S5, G4, B5, 1.000, "silver", List.of("sulfur", "arsenic", "antimony"),
					Set.of(PLUTONIC), LODE, 8.0, 0.25, 0.60, "plutonic")),
	NATIVE_GOLD("native_gold", "自然金", 0xD8B63A, 19.30, 0.00, 2.75,
			dep(S5, G4, B5, 1.000, "gold", List.of("sulfur", "arsenic", "antimony"),
					Set.of(VOLCANIC, PLUTONIC), LODE, 11.0, 0.20, 0.55, "plutonic")),

	// ------------------------------------------------------------------ 非金属（本身就可以是主要成分）
	QUARTZ("quartz", "石英脉", 0xE0DED4, 2.65, 0.00, 7.0,
			dep(S1, G1, B2, 0.467, "silicon", List.of("aluminum", "iron", "titanium"),
					Set.of(METAMORPHIC, VOLCANIC), LODE, 18.0, 0.40, 0.90, "metamorphic")),
	FLUORITE("fluorite", "萤石", 0x7FC6C0, 3.20, 0.00, 4.0,
			dep(S1, G1, B3, 0.487, "fluorine", List.of("phosphorus", "sulfur"),
					Set.of(SEDIMENTARY, VOLCANIC), LODE, 14.0, 0.30, 0.75, "sedimentary")),
	BARITE("barite", "重晶石", 0xD8D0C0, 4.50, 0.00, 3.25,
			dep(S1, G1, B2, 0.589, "barium", List.of("sulfur", "arsenic"),
					Set.of(SEDIMENTARY), BAND, 22.0, 0.30, 0.70, "sedimentary")),
	GRAPHITE("graphite", "石墨", 0x2A2A2E, 2.20, 0.00, 1.5,
			dep(S1, G1, B1, 1.000, "carbon", List.of("sulfur", "phosphorus"),
					Set.of(METAMORPHIC), DISC, 20.0, 0.35, 0.85, "metamorphic")),
	SULFUR("sulfur", "自然硫", 0xD8C93A, 2.10, 0.00, 2.0,
			dep(S1, G1, B3, 1.000, "sulfur", List.of("arsenic", "phosphorus"),
					Set.of(SEDIMENTARY, VOLCANIC), BAND, 18.0, 0.35, 0.80, "sedimentary")),
	APATITE("apatite", "磷灰石", 0xC8C0A8, 3.20, 0.00, 5.0,
			dep(S1, G1, B3, 0.180, "phosphorus", List.of("sulfur", "manganese"),
					Set.of(PLUTONIC, METAMORPHIC, SEDIMENTARY), BAND, 24.0, 0.30, 0.70, "sedimentary")),
	// ------------------------------------------------------------------ 稀土（轻稀土与重稀土各有一路主力矿物）
	// 这三张是稀土真正的工业来源：氟碳铈矿与独居石供轻稀土（镧铈镨钕），
	// 磷钇矿供重稀土（钇与镝铽铒）。它们都是副矿物，丰度比铁铜低两三个数量级，
	// 亮点在于「品位」极高 —— 原矿里稀土含量能到一半以上，选矿之后几乎就是纯料。
	BASTNASITE("bastnasite", "氟碳铈矿", 0xC8B48A, 5.00, 0.00, 4.5,
			dep(S3, G3, B3, 0.700, "rare_earth", List.of("phosphorus", "sulfur", "calcium", "iron"),
					Set.of(PLUTONIC, METAMORPHIC, SEDIMENTARY), LODE, 14.0, 0.35, 0.80, "sedimentary")),
	MONAZITE("monazite", "独居石", 0xC9A24A, 5.10, 0.10, 5.0,
			dep(S3, G3, B3, 0.520, "rare_earth", List.of("phosphorus", "sulfur", "silicon"),
					Set.of(PLUTONIC, METAMORPHIC), PLACER, 12.0, 0.30, 0.75, "plutonic")),
	XENOTIME("xenotime", "磷钇矿", 0xA8A0C8, 4.60, 0.05, 4.5,
			dep(S4, G3, B5, 0.600, "rare_earth", List.of("phosphorus", "sulfur", "silicon", "iron"),
					Set.of(PLUTONIC), PLACER, 8.0, 0.30, 0.70, "plutonic")),

	DIAMOND("diamond", "钻石", 0x6FE0DC, 3.50, 0.00, 10.0,
			dep(S5, G5, B5, 1.000, "carbon", List.of("iron", "titanium"),
					Set.of(PLUTONIC), PIPE, 6.0, 0.10, 0.35, "plutonic"));

	private final String id;
	private final String cnName;
	private final int color;
	private final double density;
	private final double magnetic;
	private final double mohs;
	private final DepositType deposit;

	/**
	 * 伴生相：主矿里天然夹带的其他矿物，含量在千分之几到百分之几。
	 *
	 * <p>这是「除主矿与脉石之外的第三种东西」，也是真实矿床的常态 ——
	 * 热液型钨锡矿带稀土、磷灰石带稀土、含金石英脉带金、黝铜矿带银。它们含量低但值钱，
	 * 而且密度与磁性都和主矿不一样，所以选矿设备能把它们单独分出来
	 * （见 {@link TracePhase}）。
	 *
	 * <p>值 = 占<b>矿物颗粒</b>的比例。没登记的矿物就是没有伴生相，不要为了「热闹」硬塞。
	 */
	private static final Map<OreMineral, Map<String, Double>> COMPANIONS = Map.ofEntries(
			// 铜系：斑岩铜矿带金银，黝铜矿是最典型的含银铜矿
			Map.entry(NATIVE_COPPER, Map.of("silver", 0.008)),
			Map.entry(CHALCOPYRITE, Map.of("gold", 0.004, "silver", 0.012)),
			Map.entry(MALACHITE, Map.of("silver", 0.006)),
			Map.entry(TETRAHEDRITE, Map.of("silver", 0.025, "gold", 0.003)),
			// 铅锌：方铅矿含银是银的主要来源之一，闪锌矿常带铅
			Map.entry(SPHALERITE, Map.of("silver", 0.006, "lead", 0.010)),
			Map.entry(GALENA, Map.of("silver", 0.022, "zinc", 0.008)),
			Map.entry(CERUSSITE, Map.of("silver", 0.010)),
			// 镍钴：铜镍共生，钴矿常伴镍
			Map.entry(GARNIERITE, Map.of("copper", 0.010)),
			Map.entry(PENTLANDITE, Map.of("copper", 0.012, "gold", 0.003)),
			Map.entry(COBALTITE, Map.of("nickel", 0.010, "gold", 0.002)),
			Map.entry(RHODOCHROSITE, Map.of("silver", 0.005)),
			// 铬铁矿常含铂族 —— 这里用金代表
			Map.entry(CHROMITE, Map.of("gold", 0.002)),
			// 锡钨钼铋：热液型钨锡矿带稀土，钼矿带铜
			Map.entry(CASSITERITE, Map.of("rare_earth", 0.006, "lead", 0.004)),
			Map.entry(WOLFRAMITE, Map.of("rare_earth", 0.020, "gold", 0.003)),
			Map.entry(MOLYBDENITE, Map.of("copper", 0.008, "gold", 0.002)),
			Map.entry(BISMUTHINITE, Map.of("silver", 0.015)),
			// 贵金属互相伴生
			Map.entry(NATIVE_SILVER, Map.of("gold", 0.020)),
			Map.entry(NATIVE_GOLD, Map.of("silver", 0.030)),
			// 轻金属：铝土矿是稀土与钒钛的载体，菱镁矿带镍
			Map.entry(BAUXITE, Map.of("rare_earth", 0.015)),
			Map.entry(MAGNESITE, Map.of("nickel", 0.004)),
			// 非金属：含金石英脉是老牌金矿，磷灰石是稀土的重要来源，萤石也带稀土
			Map.entry(QUARTZ, Map.of("gold", 0.002)),
			Map.entry(FLUORITE, Map.of("rare_earth", 0.008)),
			Map.entry(BARITE, Map.of("lead", 0.006)),
			Map.entry(APATITE, Map.of("rare_earth", 0.030)),
			// 稀土副矿物互相伴生，且常与磷、铁、锡钛共生
			Map.entry(BASTNASITE, Map.of("sulfur", 0.008, "iron", 0.010)),
			Map.entry(MONAZITE, Map.of("phosphorus", 0.015, "sulfur", 0.005)),
			Map.entry(XENOTIME, Map.of("tin", 0.004, "titanium", 0.008)));

	OreMineral(String id, String cnName, int color, double density, double magnetic, double mohs,
			DepositType deposit) {
		this.id = id;
		this.cnName = cnName;
		this.color = color;
		this.density = density;
		this.magnetic = magnetic;
		this.mohs = mohs;
		this.deposit = deposit;
	}

	/** 本矿物的伴生相：物质 id → 占矿物颗粒的比例。没有伴生相时返回空表。 */
	public Map<String, Double> companions() {
		return COMPANIONS.getOrDefault(this, Map.of());
	}

	public String id() {
		return id;
	}

	public String cnName() {
		return cnName;
	}

	/** 显示用色 0xRRGGBB。 */
	public int color() {
		return color;
	}

	/** 真实密度（g/cm³）。离心机按它分层。 */
	public double density() {
		return density;
	}

	/** 相对磁性 0~1。磁铁矿最强，磁筛机按它分选。 */
	public double magnetic() {
		return magnetic;
	}

	/** 莫氏硬度 1~10。挖掘机的钻头要按它选。 */
	public double mohs() {
		return mohs;
	}

	public DepositType deposit() {
		return deposit;
	}

	/** 主成分物质 id。 */
	public String valuable() {
		return deposit.valuable();
	}

	/** 矿体中心该矿物占矿体的体积分数区间。 */
	public double minAbundance() {
		return deposit.minAbundance();
	}

	public double maxAbundance() {
		return deposit.maxAbundance();
	}

	public OreBodyShape shape() {
		return deposit.shape();
	}

	public String translationKey() {
		return "mineral.geometallurgy." + id;
	}

	public Component displayName() {
		return Component.translatable(translationKey());
	}

	@Override
	public boolean isMineral() {
		return true;
	}

	public static OreMineral byId(String id) {
		for (OreMineral mineral : values()) {
			if (mineral.id.equals(id)) {
				return mineral;
			}
		}
		return null;
	}

	/** 某种岩体里可能出现的全部虚拟原矿。 */
	public static List<OreMineral> occurringIn(RockType rock) {
		List<OreMineral> list = new ArrayList<>();
		for (OreMineral mineral : values()) {
			if (mineral.deposit.canOccurIn(rock)) {
				list.add(mineral);
			}
		}
		return list;
	}

	/**
	 * 由矿体单元种子派生出「本矿物几何」的随机源种子。
	 *
	 * <p>与 {@link #attributeSeed} 用两套不同的乘子，避免几何和成分恰好共用同一串随机数。
	 */
	public long geometrySeed(long cellSeed) {
		return cellSeed * 0x9E3779B97F4A7C15L + ordinal() * 0xBF58476D1CE4E5B9L;
	}

	/** 由矿体单元种子派生出「本矿物成分（杂质/伴生/致密度）」的随机源种子。 */
	public long attributeSeed(long cellSeed) {
		return cellSeed * 0xC2B2AE3D27D4EB4FL + ordinal() * 0x94D049BB133111EBL;
	}

	private static DepositType dep(OreTier.Spawn spawn, OreTier.Grade grade, OreTier.Body body,
			double richness, String valuable, List<String> impurityPool, Set<RockType.Family> hostFamilies,
			OreBodyShape shape, double extent,
			double minDensity, double maxDensity, String genesis) {
		return new DepositType(spawn, grade, body, richness, valuable, impurityPool,
				hostFamilies, shape, extent, minDensity, maxDensity, genesis);
	}
}
