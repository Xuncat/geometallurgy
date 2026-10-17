package com.geometallurgy.metal;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.geometallurgy.chemical.ChemicalTags;
import com.geometallurgy.proto.ImpurityDef;
import com.geometallurgy.proto.OreMineral;
import com.geometallurgy.proto.RareEarth;
import com.geometallurgy.proto.TracePhase;
import com.geometallurgy.slurry.SlurryPart;

import net.minecraft.util.RandomSource;

/**
 * 冶金路线表 + 批次推演。
 *
 * <h2>路线由相决定，不由产线决定</h2>
 * 三条路线（{@link SmeltRoute}）是并列的备选方案：一个相能走哪几条，写死在这张表里。
 * 赤铁矿只有碳热还原，萤石只有熔融电解，孔雀石既能酸溶置换也能火法还原，
 * 硅镁镍矿三条都行 —— 玩家按当时的试剂与电力挑一条。
 *
 * <p>表里的键是 {@link SlurryPart} 而不是矿物：罐里除了目标矿物与脉石，还有<b>微量相</b>
 * （伴生稀土、伴生金银、电解阳极泥）。它们各自也有自己的路线，所以「能炼什么」这件事
 * 对所有相是同一套逻辑 —— 换成矿物是「还原炉炼铁」，换成阳极泥就是「置换炉里淘金」。
 *
 * <h2>一批料算出来的东西</h2>
 * {@link #plan} 是整台炉子的心脏：给它一条路线和一份罐内成分，它把这一批料会发生的事全算出来
 * —— 分给产物多少、带进多少杂质、剩下多少渣、要烧多少试剂与电。
 *
 * <h2>纯度在这里变成钱</h2>
 * 料越脏（参与反应的相占罐内固体的比例越低），两件事同时发生：
 * <ul>
 *   <li><b>收率打折</b> —— {@code recoveryScale} 从 1.0 掉到 0.70，金属跑进渣里；</li>
 *   <li><b>杂质翻倍</b> —— {@code carryScale} 最高到 2.0，锭的主金属含量被压低。</li>
 * </ul>
 *
 * <h2>碳系还原剂：碳残留与氧化性杂质</h2>
 * 用碳（煤炭、木炭、碳粉、碳棒）还原时，产品里会留下没烧完的碳，也会发生两件附带的事：
 * <ol>
 *   <li><b>掺碳</b> —— 基准留下 {@link #CARBON_CARRY}（5%）的碳组分，主金属因此被稀释；</li>
 *   <li><b>夺氧</b> —— 与碳一起入炉的<b>氧化态杂质</b>（磷、锰、钛、钒的氧化物）会被碳夺走氧，
 *       消去 {@link #OXIDE_REDUCED}（70%）。这一项是好事：杂质少了，主金属的占比反而升上去，
 *       所以同一份料用碳还原比用金属还原更可能拿到高纯度的锭。</li>
 * </ol>
 * 代价是碳自己被消耗：消去多少氧化物，就从那 5% 里扣掉相应的当量
 * （{@link #CARBON_PER_OXIDE}）。脏料会把碳耗光，干净的料才会留下碳残留 —— 这一条正好
 * 与「料越干净锭越纯」同一个方向。
 *
 * <p>金属还原剂（用铝、锌去夺氧）不掺碳，也不消去氧化性杂质：它更贵，但不会把碳带进产品。
 */
public final class Metallurgy {

	private Metallurgy() {
	}

	/** 一个炉次处理的料量（mB）。 */
	public static final double BATCH = 100.0;

	/**
	 * 一批料里可回收产物占比低于这个数就判定「料太贫」：炉子停住，不烧试剂也不出锭。
	 *
	 * <p>取 5% 是照着一炉白烧的代价定的 —— 再往下的料，烧掉的试剂比得到的金属还贵。
	 * 门槛卡在「产物质量」而不是「相占比」上，是因为各矿的品位差了两个数量级：
	 * 赤铁矿只有 70% 铁，自然金是 100% 金。用同一个相占比去卡，要么放过一堆石头，
	 * 要么把含 8% 自然金的富矿当成贫矿。
	 *
	 * <p>判据用的是<b>不掷骰</b>的期望产出：否则要赌的那一炉会因为「这次没中」被判成贫矿，
	 * 永远开不了工 —— 赌局得先能开桌。
	 */
	public static final double LEAN_METAL_RATIO = 0.05;

	/** 收率随纯度下滑的下限：料最脏时只能拿到基准收率的 70%。 */
	private static final double MIN_RECOVERY_SCALE = 0.70;
	/** 杂质带入随纯度上升的倍数上限：料最脏时杂质翻一倍。 */
	private static final double MAX_CARRY_SCALE = 2.0;
	/** 脉石也要吃掉的试剂当量（每 100 mB 渣）。石英质脉石在高温下同样耗碳。 */
	private static final double GANGUE_REAGENT = 0.35;

	/** 碳系还原时，产品里留下的碳组分比例（占最终产品）。 */
	public static final double CARBON_CARRY = 0.05;
	/** 氧化性杂质被碳夺氧消去的比例。 */
	public static final double OXIDE_REDUCED = 0.70;
	/** 每消去 1 份氧化性杂质，要吃掉多少份碳。按 FeO / SiO₂ / P₂O₅ 的化学计量取中间值。 */
	public static final double CARBON_PER_OXIDE = 0.45;
	/** 稀土配分里主稀土（轻稀土）占的比例，其余留给副稀土（重稀土）。 */
	private static final double RARE_EARTH_PRIMARY_SHARE = 0.75;

	/** 电解渣里改记成「阳极泥」的比例：它就是电解时落下来的不溶残渣。 */
	public static final double ANODE_MUD_SHARE = 0.12;

	// ------------------------------------------------------------------ 调用工况

	/**
	 * 这一批的工况：用什么还原剂、掷骰结果如何。
	 *
	 * <p>两者都必须由调用方在<b>开工那一刻</b>定下来并随批次保存 —— 它们是这一批的账的一部分，
	 * 中途变化会让「界面读数」与「最后出来的东西」对不上。
	 *
	 * @param carbon 这一批用的是碳系还原剂
	 * @param rolled 已经掷过骰了（开工之后）
	 * @param roll   掷出来的点数（0~1）
	 */
	public record Charges(boolean carbon, boolean rolled, double roll) {

		/** 什么都没发生：不用碳、也还没开工。 */
		public static final Charges NONE = new Charges(false, false, 0.0);

		/** 还没开工时的显示口径：按期望值给，不掷骰。 */
		public static Charges expect(boolean carbon) {
			return new Charges(carbon, false, 0.0);
		}

		/** 这一批的随机源：同一批、同一次点数，抽出来的配分跨端跨存档都一致。 */
		public RandomSource random() {
			return RandomSource.create(Double.doubleToLongBits(this.roll) * 0x9E3779B97F4A7C15L);
		}

		/** 开工后掷过的一次。 */
		public static Charges rolled(boolean carbon, double roll) {
			return new Charges(carbon, true, roll);
		}

		/**
		 * 这一炉能不能拿到产物。
		 *
		 * <p>没掷骰时返回概率本身，所以「还没开工」时界面上看到的是<b>期望产出</b>；
		 * 一旦开工，同一个配方要么全给（1）要么全不给（0），界面上那口饼图就是这一炉的真结果。
		 */
		public double factor(double chance) {
			if (chance >= 1.0) {
				return 1.0;
			}
			return this.rolled ? (this.roll < chance ? 1.0 : 0.0) : chance;
		}
	}

	// ------------------------------------------------------------------ 路线表

	private static final Map<SlurryPart, EnumMap<SmeltRoute, SmeltRecipe>> TABLE =
			new LinkedHashMap<>();

	/**
	 * 还原炉的<b>基准功率</b>（FE/t，2026-10-17：40 → 80）。
	 *
	 * <p>它是<b>逐配方功率的锚点</b>，也是「标签兜底」配方的默认值 —— 表里每条路线自带
	 * 绝对功率（{@link SmeltRecipe#powerFePerTick()}），炉子单刻扣的就是那一个数，
	 * 不再做「基准 × 倍率」的乘法。{@code SmelterProfile.REDUCTION} 的 {@code energyPerTick}
	 * 从这里取值，两处永远是同一个数。
	 */
	public static final double REDUCTION_REFERENCE_FE_PER_TICK = 80.0;

	/** 电解炉的基准功率（FE/t，2026-10-17：80 → 160），口径同 {@link #REDUCTION_REFERENCE_FE_PER_TICK}。 */
	public static final double ELECTROLYSIS_REFERENCE_FE_PER_TICK = 160.0;

	/** 三条路线的默认参数：化学计量、基准功率、基准收率、杂质带入率。 */
	private static double defaultStoich(SmeltRoute route) {
		return switch (route) {
			case REDUCTION -> 1.00;
			case ELECTROLYSIS -> 0.40;
			case DISPLACEMENT -> 1.20;
		};
	}

	/**
	 * 没写死功率的配方用的默认功率（= 该路线的基准）。
	 *
	 * <p><b>置换路一律 0</b>：置换炉不接电（{@code SmelterProfile.usesPower()==false}），
	 * 那一列对它没有意义。显式表里也照这个写，免得日后有人把它当成「待补的功率」。
	 */
	private static double defaultPower(SmeltRoute route) {
		return switch (route) {
			case REDUCTION -> REDUCTION_REFERENCE_FE_PER_TICK;
			case ELECTROLYSIS -> ELECTROLYSIS_REFERENCE_FE_PER_TICK;
			case DISPLACEMENT -> 0.0;
		};
	}

	private static double defaultRecovery(SmeltRoute route) {
		return switch (route) {
			case REDUCTION -> 0.90;
			case ELECTROLYSIS -> 0.98;
			case DISPLACEMENT -> 0.86;
		};
	}

	private static double defaultCarry(SmeltRoute route) {
		return switch (route) {
			case REDUCTION -> 0.18;
			case ELECTROLYSIS -> 0.05;
			case DISPLACEMENT -> 0.25;
		};
	}

	private static void put(SlurryPart phase, SmeltRoute route, SmeltRecipe recipe) {
		TABLE.computeIfAbsent(phase, key -> new EnumMap<>(SmeltRoute.class)).put(route, recipe);
	}

	/** 按默认参数登记一条矿物路线。 */
	private static void add(OreMineral mineral, SmeltRoute route) {
		add(mineral, route, defaultStoich(route), defaultPower(route),
				defaultRecovery(route), defaultCarry(route));
	}

	private static void add(OreMineral mineral, SmeltRoute route, double stoich, double fePerTick,
			double recovery, double carry) {
		add(mineral, route, mineral.valuable(), stoich, fePerTick, recovery, carry);
	}

	/**
	 * 产物与矿物声明的有价成分不一致时用这个重载。
	 *
	 * <p>理由是「有价成分」与「这条路线真正夺回来的东西」不是一回事：萤石的有价成分是氟，
	 * 但熔盐电解 CaF₂ 拿到的是<b>钙</b>（氟在这条路线里以含氟气体跑掉了）。照 valuable 直接填，
	 * 电解炉就会产出一块「氟锭」，那是化学错误。
	 */
	private static void add(OreMineral mineral, SmeltRoute route, String product, double stoich,
			double fePerTick, double recovery, double carry) {
		put(mineral, route, new SmeltRecipe(mineral, route, product, true,
				mineral.deposit().richness(), stoich, fePerTick, recovery, carry, 1.0,
				SmeltRecipe.Mixture.NONE));
	}

	/** 登记一条产物不是金属的矿物路线（石墨电解制碳粉）。 */
	private static void addNonMetal(OreMineral mineral, SmeltRoute route, String product,
			double stoich, double fePerTick, double recovery, double carry) {
		put(mineral, route, new SmeltRecipe(mineral, route, product, false,
				mineral.deposit().richness(), stoich, fePerTick, recovery, carry, 1.0,
				SmeltRecipe.Mixture.NONE));
	}

	/** 登记一条微量相（伴生相 / 阳极泥）的路线。 */
	private static void addTrace(TracePhase phase, SmeltRoute route, String product,
			double richFraction, double stoich, double fePerTick, double recovery, double carry) {
		addTrace(phase, route, product, richFraction, stoich, fePerTick, recovery, carry, 1.0);
	}

	private static void addTrace(TracePhase phase, SmeltRoute route, String product,
			double richFraction, double stoich, double fePerTick, double recovery, double carry,
			double chance) {
		put(phase, route, new SmeltRecipe(phase, route, product, true,
				richFraction, stoich, fePerTick, recovery, carry, chance,
				SmeltRecipe.Mixture.NONE));
	}

	/** 登记一条产出稀土配分的路线（产物要摊成 1~3 种主稀土 + 2~6 种副稀土）。 */
	private static void addRareEarth(TracePhase phase, SmeltRoute route,
			double richFraction, double stoich, double fePerTick, double recovery, double carry) {
		put(phase, route, new SmeltRecipe(phase, route, "rare_earth", true,
				richFraction, stoich, fePerTick, recovery, carry, 1.0,
				SmeltRecipe.Mixture.RARE_EARTH));
	}

	/**
	 * 路线表（每行 = 一个相 × 一条路线）。
	 *
	 * <p>参数是「试剂当量 / 功率(FE/t) / 收率 / 杂质带入率」。<b>功率是绝对数，不是倍率</b>，
	 * 炉子单刻扣的就是它；{@code 0.0} = <b>试剂驱动</b>：这批料一度电都不要，成本全压在还原剂上
	 * （见 {@link SmeltRecipe#powerFePerTick()}）。
	 *
	 * <p>试剂驱动 vs 电驱动的分界<b>只有一条</b>：
	 * <ul>
	 *   <li><b>碳既当还原剂又当热源</b>的传统火法（高炉炼铁、反射炉炼锡 / 铜 / 铅、自然金属熔炼）
	 *       → 0 FE/t，试剂当量按「原本由电出的那份热」上调 50%~70%；</li>
	 *   <li><b>需要电弧炉 / 电炉高温（&gt;1400 ℃）或先焙烧</b>的体系（工业硅、铬铁、锰、钨、钼、
	 *       硫化物与砷化物）→ 保留电力，且功率按新基准翻倍。</li>
	 * </ul>
	 * 置换路（湿法）一律 0 —— 置换炉根本不接电。
	 */
	static {
		// -------------------------------------------------------------- 铁：只有火法（高炉：焦炭既还原又供热）
		add(OreMineral.MAGNETITE, SmeltRoute.REDUCTION, 1.60, 0.0, 0.90, 0.18);  // Fe₃O₄：试剂驱动
		add(OreMineral.HEMATITE, SmeltRoute.REDUCTION, 1.60, 0.0, 0.90, 0.18);   // Fe₂O₃：同上
		add(OreMineral.LIMONITE, SmeltRoute.REDUCTION, 1.75, 0.0, 0.88, 0.18);   // 含水，先脱羟基再还原

		// -------------------------------------------------------------- 铜
		add(OreMineral.NATIVE_COPPER, SmeltRoute.REDUCTION, 0.40, 0.0, 0.99, 0.04);  // 已是单质，只需熔化（试剂=燃料）
		add(OreMineral.CHALCOPYRITE, SmeltRoute.REDUCTION, 1.40, 104.0, 0.85, 0.20); // 硫化物，先焙烧（电）
		add(OreMineral.CHALCOPYRITE, SmeltRoute.DISPLACEMENT, 1.35, 0.0, 0.86, 0.22);
		add(OreMineral.MALACHITE, SmeltRoute.REDUCTION, 1.90, 0.0, 0.87, 0.18);      // 碳酸盐：反射炉
		add(OreMineral.MALACHITE, SmeltRoute.DISPLACEMENT, 1.05, 0.0, 0.90, 0.20);   // 酸溶后置换，最省
		add(OreMineral.TETRAHEDRITE, SmeltRoute.REDUCTION, 1.40, 104.0, 0.84, 0.22);
		add(OreMineral.TETRAHEDRITE, SmeltRoute.DISPLACEMENT, 1.30, 0.0, 0.85, 0.24);

		// -------------------------------------------------------------- 锌铅：火法为主，铅走电解精炼
		add(OreMineral.SPHALERITE, SmeltRoute.REDUCTION, 1.45, 108.0, 0.86, 0.20);   // 闪锌矿：焙烧 + 蒸馏
		add(OreMineral.GALENA, SmeltRoute.REDUCTION, 1.35, 100.0, 0.88, 0.20);       // 方铅矿：焙烧 + 还原
		add(OreMineral.GALENA, SmeltRoute.ELECTROLYSIS, 0.50, 480.0, 0.96, 0.06);    // 电解精炼铅
		add(OreMineral.CERUSSITE, SmeltRoute.REDUCTION, 1.85, 0.0, 0.86, 0.22);      // 白铅矿：反射炉（试剂驱动）

		// -------------------------------------------------------------- 镍钴
		add(OreMineral.GARNIERITE, SmeltRoute.REDUCTION, 1.30, 112.0, 0.84, 0.22);  // 硅镁镍矿：电炉熔炼
		add(OreMineral.GARNIERITE, SmeltRoute.DISPLACEMENT, 1.45, 0.0, 0.82, 0.26);
		add(OreMineral.GARNIERITE, SmeltRoute.ELECTROLYSIS, 0.50, 512.0, 0.97, 0.06);
		add(OreMineral.PENTLANDITE, SmeltRoute.REDUCTION, 1.40, 104.0, 0.86, 0.20);  // 镍黄铁矿：焙烧 + 电炉
		add(OreMineral.PENTLANDITE, SmeltRoute.ELECTROLYSIS, 0.50, 496.0, 0.96, 0.06); // 镍电解精炼
		add(OreMineral.COBALTITE, SmeltRoute.REDUCTION, 1.50, 112.0, 0.83, 0.24);    // 砷，最脏的一种
		add(OreMineral.COBALTITE, SmeltRoute.ELECTROLYSIS, 0.55, 512.0, 0.95, 0.06); // 钴电解，顺便甩砷

		// -------------------------------------------------------------- 锰铬：难还原，电炉 / 电解两条腿
		add(OreMineral.PYROLUSITE, SmeltRoute.REDUCTION, 1.25, 96.0, 0.88, 0.18);    // MnO 要 1400 ℃ 以上 → 电炉
		add(OreMineral.PYROLUSITE, SmeltRoute.ELECTROLYSIS, 0.55, 512.0, 0.95, 0.06); // 电解金属锰
		add(OreMineral.RHODOCHROSITE, SmeltRoute.REDUCTION, 1.15, 84.0, 0.87, 0.20);  // 菱锰矿：同上，电炉
		add(OreMineral.CHROMITE, SmeltRoute.REDUCTION, 1.60, 176.0, 0.82, 0.22);     // 铬铁必须电炉碳热
		add(OreMineral.CHROMITE, SmeltRoute.ELECTROLYSIS, 0.70, 576.0, 0.90, 0.08);  // 电解铬，最费电的一条

		// -------------------------------------------------------------- 锡钨钼铋
		add(OreMineral.CASSITERITE, SmeltRoute.REDUCTION, 1.70, 0.0, 0.92, 0.14);    // SnO₂ 反射炉 ~1200 ℃（试剂驱动）
		add(OreMineral.WOLFRAMITE, SmeltRoute.REDUCTION, 1.45, 144.0, 0.85, 0.18);   // 黑钨矿：1500 ℃ 以上，电炉
		add(OreMineral.MOLYBDENITE, SmeltRoute.REDUCTION, 1.45, 120.0, 0.84, 0.20);  // 辉钼矿：先焙烧再还原
		add(OreMineral.BISMUTHINITE, SmeltRoute.REDUCTION, 1.35, 96.0, 0.86, 0.22);  // 辉铋矿：焙烧 + 还原（电）
		add(OreMineral.BISMUTHINITE, SmeltRoute.DISPLACEMENT, 1.25, 0.0, 0.88, 0.24);

		// -------------------------------------------------------------- 轻金属：只能电解，铝土矿与菱镁矿是招牌
		// 氧化铝与氧化镁的键能太高，碳夺不动氧（这正是它们做耐火材料的原因），
		// 工业上也只有熔盐电解一条路 —— 所以这两条路线是电解炉的独占对象。
		add(OreMineral.BAUXITE, SmeltRoute.ELECTROLYSIS, "aluminum", 0.95, 600.0, 0.88, 0.05);   // 160×4.20 被 maxDraw 截到 600
		add(OreMineral.MAGNESITE, SmeltRoute.ELECTROLYSIS, "magnesium", 0.85, 600.0, 0.88, 0.05); // 160×4.00 同样截到 600

		// -------------------------------------------------------------- 贵金属
		add(OreMineral.NATIVE_SILVER, SmeltRoute.REDUCTION, 0.40, 0.0, 0.99, 0.04);   // 自然银：熔炼（试剂驱动）
		add(OreMineral.NATIVE_SILVER, SmeltRoute.DISPLACEMENT, 0.90, 0.0, 0.95, 0.08); // 锌粉置换，银里的一把手
		add(OreMineral.NATIVE_GOLD, SmeltRoute.REDUCTION, 0.40, 0.0, 0.99, 0.04);     // 自然金：熔炼（试剂驱动）
		add(OreMineral.NATIVE_GOLD, SmeltRoute.DISPLACEMENT, 0.95, 0.0, 0.96, 0.06);   // 锌粉置换（氰化法的替代）

		// -------------------------------------------------------------- 非金属：电解炉的两块招牌
		add(OreMineral.QUARTZ, SmeltRoute.REDUCTION, 1.65, 136.0, 0.80, 0.14);       // 碳热制工业硅：电弧炉 ~1800 ℃
		add(OreMineral.QUARTZ, SmeltRoute.ELECTROLYSIS, 0.55, 544.0, 0.92, 0.06);    // 熔融二氧化硅电解
		// 萤石的有价成分是氟，但熔盐电解拿到的是钙 —— 氟以含氟气体跑掉，所以产物要显式改写
		add(OreMineral.FLUORITE, SmeltRoute.ELECTROLYSIS, "calcium", 0.60, 576.0, 0.95, 0.05);
		add(OreMineral.BARITE, SmeltRoute.ELECTROLYSIS, 0.65, 576.0, 0.93, 0.05);
		addNonMetal(OreMineral.GRAPHITE, SmeltRoute.ELECTROLYSIS, "carbon",
				0.25, 224.0, 0.94, 0.03);                                          // 熔盐电解提纯石墨（最便宜的一档）

		// 其余矿物（自然硫、磷灰石、钻石）没有金属可提，不进表：
		// 它们进炉只会变成渣，这正是「选矿要把废矿物先甩掉」的理由。

		// -------------------------------------------------------------- 伴生相：跟着主矿一起进来的那一小撮
		// 伴生量很小，但纯度往往比主矿还高 —— 伴生金是自然金级别的东西，
		// 所以这里 richFraction 给得高，靠的又不是「量大」而是「值钱」。
		addRareEarth(TracePhase.RARE_EARTH, SmeltRoute.ELECTROLYSIS,
				0.85, 0.80, 600.0, 0.86, 0.06);      // 混合稀土只能熔盐电解（160×4.20 → 截 600）
		addTrace(TracePhase.GOLD, SmeltRoute.DISPLACEMENT, "gold",
				0.90, 1.30, 0.0, 0.88, 0.06, 0.40); // 锌粉置换：赌，但一度电都不花
		addTrace(TracePhase.GOLD, SmeltRoute.ELECTROLYSIS, "gold",
				0.90, 0.45, 544.0, 0.97, 0.03);     // 电解精炼：稳，代价是电
		addTrace(TracePhase.SILVER, SmeltRoute.DISPLACEMENT, "silver",
				0.92, 1.20, 0.0, 0.90, 0.06, 0.50);
		addTrace(TracePhase.SILVER, SmeltRoute.ELECTROLYSIS, "silver",
				0.92, 0.45, 528.0, 0.97, 0.03);
		// 伴生相的还原路线一律试剂驱动：它们跟着主料一起入炉，主料的碳与热已经付过，
		// 没有理由为这一小撮再单独通一次电（当量上调的那部分就是这份热）。
		addTrace(TracePhase.NICKEL, SmeltRoute.REDUCTION, "nickel",
				0.80, 1.90, 0.0, 0.86, 0.20);
		addTrace(TracePhase.NICKEL, SmeltRoute.ELECTROLYSIS, "nickel",
				0.80, 0.50, 512.0, 0.97, 0.06);
		addTrace(TracePhase.COPPER, SmeltRoute.REDUCTION, "copper",
				0.85, 1.85, 0.0, 0.88, 0.18);
		addTrace(TracePhase.COPPER, SmeltRoute.DISPLACEMENT, "copper",
				0.85, 1.10, 0.0, 0.90, 0.20);
		addTrace(TracePhase.LEAD, SmeltRoute.REDUCTION, "lead",
				0.88, 1.90, 0.0, 0.88, 0.18);
		addTrace(TracePhase.LEAD, SmeltRoute.ELECTROLYSIS, "lead",
				0.88, 0.50, 464.0, 0.96, 0.06);
		addTrace(TracePhase.ZINC, SmeltRoute.REDUCTION, "zinc",
				0.82, 2.15, 0.0, 0.86, 0.20);
		addTrace(TracePhase.ZINC, SmeltRoute.ELECTROLYSIS, "zinc",
				0.82, 0.55, 496.0, 0.95, 0.06);

		// -------------------------------------------------------------- 电解阳极泥
		// 阳极泥本身就是一份浓缩物（不溶残渣越攒越富），所以它的 richFraction 反而很高。
		// 两条路线各取一头：置换炉淘贵金属（要赌），电解炉抽稀土（必得）。
		addTrace(TracePhase.ANODE_MUD, SmeltRoute.DISPLACEMENT, "gold",
				0.25, 1.60, 0.0, 0.78, 0.12, 0.35);
		addRareEarth(TracePhase.ANODE_MUD, SmeltRoute.ELECTROLYSIS,
				0.30, 1.10, 600.0, 0.82, 0.10);     // 160×4.00 → 截 600
	}

	// ------------------------------------------------------------------ 查询

	/** 这个相走不走得通这条路线。 */
	public static boolean supports(SlurryPart phase, SmeltRoute route) {
		return recipe(phase, route) != null;
	}

	/**
	 * 这个相在这条路线上的配方：<b>先查显式表</b>（三条路各自的化学计量、收率、杂质率），
	 * 查不到再走{@link #taggedRecipe 标签兜底}。
	 *
	 * <p>两条路都返回同一个 {@link SmeltRecipe}，所以 {@code supports} 与 {@code plan}
	 * 看到的「能不能走」永远是同一个答案 —— 不会出现「纯度算上了、产物却没有」的错位。
	 */
	@Nullable
	public static SmeltRecipe recipe(SlurryPart phase, SmeltRoute route) {
		EnumMap<SmeltRoute, SmeltRecipe> map = TABLE.get(phase);
		SmeltRecipe explicit = map == null ? null : map.get(route);
		return explicit != null ? explicit : taggedRecipe(phase, route);
	}

	/**
	 * 标签兜底：显式表里没登记的相，按物性标签决定路线与默认配方。
	 *
	 * <p>还原炉是唯一开口：{@link SmeltRoute#acceptsTagged} 认氧化物（{@link ChemicalTags.Tag#OXIDE}），
	 * 于是<b>任何氧化物矿</b>都自动可还原，不必再写一条 {@code add(OreMineral.X, REDUCTION)}。
	 * 配方用该矿自己的品位（{@code deposit().richness()}）与还原路的默认参数推出来，数量级与
	 * 表里手写的氧化物条目一致。难还原氧化物（{@link ChemicalTags.Tag#REFRACTORY}）与其余两路仍返回 null，
	 * 保持它们现有的准入不变。
	 */
	@Nullable
	private static SmeltRecipe taggedRecipe(SlurryPart phase, SmeltRoute route) {
		if (!(phase instanceof OreMineral mineral)) {
			return null;
		}
		if (!route.acceptsTagged(ChemicalTags.tagsOf(phase.id()))) {
			return null;
		}
		return new SmeltRecipe(phase, route, mineral.valuable(), true,
				mineral.deposit().richness(), defaultStoich(route), defaultPower(route),
				defaultRecovery(route), defaultCarry(route), 1.0, SmeltRecipe.Mixture.NONE);
	}

	/** 这个相支持的全部路线，顺序固定为「还原 / 电解 / 置换」。 */
	public static List<SmeltRoute> routes(SlurryPart phase) {
		List<SmeltRoute> list = new ArrayList<>();
		for (SmeltRoute route : SmeltRoute.values()) {
			if (supports(phase, route)) {
				list.add(route);
			}
		}
		return list;
	}

	/** 表里登记过至少一条路线的全部相，供界面与调试用。 */
	public static List<SlurryPart> routable() {
		return new ArrayList<>(TABLE.keySet());
	}

	/** 这个相夹带杂质时的候选池。 */
	private static List<String> impurityPoolOf(SlurryPart phase) {
		if (phase instanceof OreMineral mineral) {
			return mineral.deposit().impurityPool();
		}
		if (phase instanceof TracePhase trace) {
			return trace.impurityPool();
		}
		return List.of();
	}

	// ------------------------------------------------------------------ 批次推演

	/**
	 * 一批料会发生的事。
	 *
	 * @param volume             本批实际处理的体积（mB）
	 * @param reactiveVolume     其中能走这条路线的那部分
	 * @param solidVolume        本批固体总量（= {@code volume}，单列出来是为了让「纯度」读起来直白）
	 * @param purity             参与反应的相占本批固体的比例（0~1）
	 * @param metalMass          本批实际能拿到的产物总量（掷骰之后）
	 * @param expectedMetalMass  掷骰之前的期望产出，用来判「料太贫」
	 * @param reagentUnits       本批要吃的试剂当量
	 * @param powerFePerTick     本批的加权功率（FE/t，绝对值；0 = 试剂驱动、一度电都不要）
	 * @param carbonCharge       碳残留量（0 表示没用碳系还原剂或碳被耗光）
	 * @param metals             产物成分：物质 id → 量
	 * @param impurities         杂质与夹带物：物质 id → 量（碳残留也在这一桶里）
	 * @param slag               渣：相 → 量
	 * @param ingot              产物是不是「锭」。目前只有石墨电解制碳粉一条路线的产物是粉
	 */
	public record Batch(
			double volume,
			double reactiveVolume,
			double solidVolume,
			double purity,
			double metalMass,
			double expectedMetalMass,
			double reagentUnits,
			double powerFePerTick,
			double carbonCharge,
			Map<String, Double> metals,
			Map<String, Double> impurities,
			Map<SlurryPart, Double> slag,
			boolean ingot) {

		public boolean empty() {
			return this.volume <= 0.0 || this.reactiveVolume <= 0.0;
		}

		/** 料太贫：提不出值得烧的东西，这一炉不该开工。 */
		public boolean lean() {
			return this.expectedMetalMass < this.volume * LEAN_METAL_RATIO;
		}

		/** 产品的成分表（产物 + 杂质 + 碳残留），未归一化。 */
		public Map<String, Double> product() {
			Map<String, Double> all = new LinkedHashMap<>(this.metals);
			this.impurities.forEach((key, value) -> all.merge(key, value, Double::sum));
			return all;
		}
	}

	/** 不指定工况的推演：不用碳、不掷骰。 */
	public static Batch plan(SmeltRoute route, Map<SlurryPart, Double> contents, double volume) {
		return plan(route, contents, volume, Charges.NONE);
	}

	/**
	 * 按 {@code volume} 从 {@code contents} 里切一份料，推演它在 {@code route} 下会发生什么。
	 *
	 * <p>{@code contents} 是整罐的成分；本方法按比例切出 {@code volume} 那一份来算，
	 * 不修改入参 —— 所以炉子可以放心地「先算再决定要不要动」。
	 */
	public static Batch plan(SmeltRoute route, Map<SlurryPart, Double> contents, double volume,
			Charges charges) {
		double total = 0.0;
		for (double value : contents.values()) {
			total += value;
		}
		if (total <= 0.0 || volume <= 0.0) {
			return new Batch(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0,
					Map.of(), Map.of(), Map.of(), true);
		}
		double scale = Math.min(1.0, volume / total);
		double batchVolume = total * scale;

		// 第一遍：只数「有多少料能走这条路线」，好把纯度算出来
		double reactiveVolume = 0.0;
		double gangueVolume = 0.0;
		for (Map.Entry<SlurryPart, Double> entry : contents.entrySet()) {
			double part = entry.getValue() * scale;
			if (part <= 0.0) {
				continue;
			}
			if (supports(entry.getKey(), route)) {
				reactiveVolume += part;
			} else {
				gangueVolume += part;
			}
		}
		double solid = reactiveVolume + gangueVolume;
		double purity = solid <= 0.0 ? 0.0 : reactiveVolume / solid;
		// 收率打折、杂质翻倍：两个因子都由同一个纯度推出来，所以「料脏」只有一种解释
		double recoveryScale = MIN_RECOVERY_SCALE + (1.0 - MIN_RECOVERY_SCALE) * purity;
		double carryScale = 1.0 + (1.0 - purity);

		// 第二遍：按纯度因子算每一相的产出。渣用「本相总量减去产出」收口，不必额外补零头。
		Map<String, Double> metals = new LinkedHashMap<>();
		Map<String, Double> impurities = new LinkedHashMap<>();
		Map<SlurryPart, Double> slag = new LinkedHashMap<>();

		double reagentUnits = 0.0;
		double powerWeighted = 0.0;
		boolean ingotProduct = false;

		for (Map.Entry<SlurryPart, Double> entry : contents.entrySet()) {
			double part = entry.getValue() * scale;
			if (part <= 0.0) {
				continue;
			}
			SlurryPart phase = entry.getKey();
			SmeltRecipe recipe = recipe(phase, route);
			if (recipe == null) {
				// 脉石，或者没有这条路线可走的相：整份进渣，还要白吃一份试剂
				slag.merge(phase, part, Double::sum);
				reagentUnits += part / BATCH * GANGUE_REAGENT;
				continue;
			}
			if (recipe.ingot()) {
				ingotProduct = true;
			}

			double good = part * recipe.richFraction();
			// 期望产出不含掷骰：用它判贫富，赌局才开得起来
			double expected = good * recipe.recovery() * recoveryScale;
			double factor = charges.factor(recipe.chance());
			double metal = expected * factor;
			// 杂质是「占提取出的金属」的比例（见 DepositType 的说明），所以乘在 metal 上
			double impurity = metal * recipe.carryRate() * carryScale;
			// 自然金属的品位是 1.000，收率又高，加总有可能略过 1 —— 按相总量截一刀
			double taken = metal + impurity;
			if (taken > part) {
				double shrink = part / taken;
				metal *= shrink;
				impurity *= shrink;
				taken = part;
			}

			if (recipe.mixture() == SmeltRecipe.Mixture.RARE_EARTH) {
				spreadRareEarth(metals, metal, charges);
			} else {
				metals.merge(recipe.product(), metal, Double::sum);
			}
			spreadImpurities(impurities, impurity, impurityPoolOf(phase));
			slag.merge(phase, part - taken, Double::sum);

			reagentUnits += part / BATCH * recipe.stoichiometry();
			powerWeighted += part * recipe.powerFePerTick();
		}

		double metalMass = sum(metals);
		double expectedMetalMass = 0.0;
		for (Map.Entry<SlurryPart, Double> entry : contents.entrySet()) {
			double part = entry.getValue() * scale;
			SmeltRecipe recipe = part <= 0.0 ? null : recipe(entry.getKey(), route);
			if (recipe != null) {
				expectedMetalMass += part * recipe.richFraction() * recipe.recovery() * recoveryScale;
			}
		}
		// 加权功率：本批里每一相自己的 FE/t 按体积加权 —— 试剂驱动的相贡献 0，但它的体积仍在分母里，
		// 所以「一炉里混了不要电的料」会把整批的功率按比例摊薄（与电耗倍率时代的加权口径一致）。
		double powerFePerTick = reactiveVolume <= 0.0 ? defaultPower(route) : powerWeighted / reactiveVolume;

		// 渣 = 本批总量 − 产物 − 杂质。用减法收口，避免浮点零头把渣算丢。
		closeSlag(slag, batchVolume - metalMass - sum(impurities));

		// 碳系还原剂的两件附带事，必须在渣收口之后再动 —— 它们改的是产品那一侧的账
		double carbonCharge = chargeCarbon(charges, metals, impurities);

		// 电解的阳极泥：把渣里的一小部分改记到阳极泥名下（守体积守恒）
		if (route == SmeltRoute.ELECTROLYSIS) {
			relabelAnodeMud(slag);
		}

		return new Batch(batchVolume, reactiveVolume, solid, purity, metalMass, expectedMetalMass,
				reagentUnits, powerFePerTick, carbonCharge, Map.copyOf(metals),
				Map.copyOf(impurities), Map.copyOf(slag), ingotProduct);
	}

	/** 渣的浮点零头并到最后一相上，保证「渣 + 产物 + 杂质 = 本批总量」。 */
	private static void closeSlag(Map<SlurryPart, Double> slag, double target) {
		double current = 0.0;
		for (double value : slag.values()) {
			current += value;
		}
		double correction = target - current;
		if (Math.abs(correction) <= 1.0e-9 || slag.isEmpty()) {
			return;
		}
		SlurryPart last = null;
		for (SlurryPart key : slag.keySet()) {
			last = key;
		}
		slag.merge(last, correction, Double::sum);
	}

	/**
	 * 碳系还原剂的两件附带事：消去氧化性杂质、留下碳残留。
	 *
	 * <p>顺序不能反 —— 先夺氧（杂质减少、纯度上去），再按「产品总量的 5%」掺碳，
	 * 最后从碳里扣掉夺氧消耗掉的那部分。
	 *
	 * @return 实际留在产品里的碳量（0 表示没用碳系，或碳被杂质耗光了）
	 */
	private static double chargeCarbon(Charges charges, Map<String, Double> metals,
			Map<String, Double> impurities) {
		if (!charges.carbon()) {
			return 0.0;
		}
		// 1) 氧化性杂质被碳夺氧消去 —— 该杂质少了，主金属的占比自然升上去
		double removed = 0.0;
		for (String id : new ArrayList<>(impurities.keySet())) {
			if (!ImpurityDef.isOxidic(id)) {
				continue;
			}
			double amount = impurities.get(id);
			double gone = amount * OXIDE_REDUCED;
			removed += gone;
			double left = amount - gone;
			if (left > 1.0e-6) {
				impurities.put(id, left);
			} else {
				impurities.remove(id);
			}
		}
		// 2) 掺碳：碳占最终产品 CARBON_CARRY，所以相对产品其余部分要放大 1/(1-x) 倍
		double base = sum(metals) + sum(impurities);
		double carbon = base * CARBON_CARRY / (1.0 - CARBON_CARRY);
		// 3) 夺氧要吃掉碳，从这 5% 里扣
		carbon = Math.max(0.0, carbon - removed * CARBON_PER_OXIDE);
		if (carbon > 1.0e-6) {
			impurities.merge("carbon", carbon, Double::sum);
			return carbon;
		}
		return 0.0;
	}

	/**
	 * 把这一批的稀土量摊成一份「配分」。
	 *
	 * <p>抽 1~3 种主稀土（轻稀土）构成主体、2~6 种副稀土（重稀土）作零头，
	 * 组内再按地壳丰度分 —— 所以铈通常最大、铥镥最小。加一层 ±25% 的抖动，
	 * 免得每一炉的配分长得一模一样。
	 *
	 * <p>还没开工时给的是<b>期望值</b>：按全表权重直接摊，好让界面上「这一炉大概出什么」
	 * 有个说法。开工之后换成掷骰定下来的那一份，与最终产出的锭是同一份配分。
	 */
	private static void spreadRareEarth(Map<String, Double> into, double amount, Charges charges) {
		if (amount <= 0.0) {
			return;
		}
		if (!charges.rolled()) {
			double total = 0.0;
			for (RareEarth.Def element : RareEarth.all()) {
				total += element.weight();
			}
			for (RareEarth.Def element : RareEarth.all()) {
				into.merge(element.id(), amount * element.weight() / total, Double::sum);
			}
			return;
		}
		RandomSource random = charges.random();
		shareGroup(into, RareEarth.pick(random, true, 1, 3),
				amount * RARE_EARTH_PRIMARY_SHARE, random);
		shareGroup(into, RareEarth.pick(random, false, 2, 6),
				amount * (1.0 - RARE_EARTH_PRIMARY_SHARE), random);
	}

	/** 把一份量按组内权重、带抖动地分给这一组元素（份额归一，总量不漂）。 */
	private static void shareGroup(Map<String, Double> into, List<RareEarth.Def> group,
			double amount, RandomSource random) {
		if (group.isEmpty() || amount <= 0.0) {
			return;
		}
		double[] weights = new double[group.size()];
		double sum = 0.0;
		for (int i = 0; i < group.size(); i++) {
			weights[i] = group.get(i).weight() * (0.75 + random.nextDouble() * 0.5);
			sum += weights[i];
		}
		if (sum <= 0.0) {
			return;
		}
		for (int i = 0; i < group.size(); i++) {
			into.merge(group.get(i).id(), amount * weights[i] / sum, Double::sum);
		}
	}

	/**
	 * 电解的阳极泥：把渣里的一部分改记到阳极泥名下。
	 *
	 * <p>不做「凭空多出一份」的事 —— 阳极泥就是电解时落下来的不溶残渣，本来就在渣里，
	 * 只是给它单立一个相，好让它能被单独送去提贵金属与稀土。所以这里按比例改写标签，
	 * 渣的总量不变。
	 */
	private static void relabelAnodeMud(Map<SlurryPart, Double> slag) {
		double total = 0.0;
		for (double value : slag.values()) {
			total += value;
		}
		double mud = total * ANODE_MUD_SHARE;
		if (mud <= 1.0e-6) {
			return;
		}
		double keep = 1.0 - ANODE_MUD_SHARE;
		slag.replaceAll((phase, value) -> value * keep);
		slag.merge(TracePhase.ANODE_MUD, mud, Double::sum);
	}

	private static double sum(Map<String, Double> map) {
		double total = 0.0;
		for (double value : map.values()) {
			total += value;
		}
		return total;
	}

	/**
	 * 把一份杂质分到该相声明过的杂质池里。
	 *
	 * <p>用 {@link ImpurityDef} 的权重区间中点当权重 —— 磷、硫这类容易混进金属的元素本来就多，
	 * 分出来的锭也更像真的（铁锭里带点磷硫，而不是均匀撒一层随机元素）。
	 */
	private static void spreadImpurities(Map<String, Double> into, double amount,
			List<String> pool) {
		if (amount <= 0.0 || pool.isEmpty()) {
			return;
		}
		double weightSum = 0.0;
		double[] weights = new double[pool.size()];
		for (int i = 0; i < pool.size(); i++) {
			ImpurityDef.Entry entry = ImpurityDef.get(pool.get(i));
			weights[i] = entry == null ? 2.0 : (entry.minWeight() + entry.maxWeight()) / 2.0;
			weightSum += weights[i];
		}
		for (int i = 0; i < pool.size(); i++) {
			double share = weightSum <= 0.0 ? 1.0 / pool.size() : weights[i] / weightSum;
			double value = amount * share;
			if (value > 1.0e-6) {
				into.merge(pool.get(i), value, Double::sum);
			}
		}
	}
}
