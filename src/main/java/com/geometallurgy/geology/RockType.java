package com.geometallurgy.geology;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import com.geometallurgy.slurry.SlurryPart;

import net.minecraft.network.chat.Component;

/**
 * 虚拟岩体（原岩）类型。
 *
 * <p>世界地下并不真的存在这些岩石，而是由 {@link GeologySampler} 的场方程算出来：
 * 每个位置都会被判定属于某一种岩体，岩体再决定这一带能出现哪些虚拟矿脉。
 *
 * <p>四族分别对应不同的成岩环境，也对应不同的深度带：
 * <ul>
 *   <li>{@link Family#SEDIMENTARY 沉积岩} —— 浅层，铁矿（磁铁矿/褐铁矿）、铅锌（方铅矿）</li>
 *   <li>{@link Family#VOLCANIC 喷出岩} —— 中层，赤铁矿、自然铜、自然金</li>
 *   <li>{@link Family#PLUTONIC 侵入岩} —— 深层，锡石、镍矿、钼矿</li>
 *   <li>{@link Family#METAMORPHIC 变质岩} —— 最深处，闪锌矿、黝铜矿、珠宝</li>
 * </ul>
 *
 * <p>每种岩体附带两份数据：
 * <ul>
 *   <li><b>脉石组成</b> —— 开采出来的矿浆里除矿物之外的那部分无用岩石，
 *       后续由磁筛机 / 离心机按密度与磁性分离掉。</li>
 *   <li><b>莫氏硬度</b> —— 按主导矿物取：石灰岩是方解石（3），石英岩是石英（7），
 *       花岗岩是长石与石英的混合（6.5）。挖掘机的钻头要按它选型：
 *       钻头硬度低于岩石硬度就啃不动，或者磨损飞快。</li>
 * </ul>
 *
 * <p>它同时是一个 {@link SlurryPart}：碎矿挖掘机磨碎的是整块岩石，所以矿浆里占绝对多数的
 * 那一相就是这里的一种岩体，而不是任何矿物。
 */
public enum RockType implements SlurryPart {

	// ---------------------------------------------------------------- 沉积岩
	CHALK("chalk", Family.SEDIMENTARY, 0xDCD8CE, 2.40, 3.0, gangue("calcite", 0.6, "clay", 0.4)),
	LIMESTONE("limestone", Family.SEDIMENTARY, 0xB8B4A8, 2.60, 3.0, gangue("calcite", 0.7, "dolomite", 0.3)),
	DOLOMITE("dolomite", Family.SEDIMENTARY, 0xC0BCA8, 2.85, 3.8, gangue("dolomite", 0.9, "calcite", 0.1)),
	SHALE("shale", Family.SEDIMENTARY, 0x6E6A60, 2.45, 2.8, gangue("clay", 0.5, "quartz", 0.3, "feldspar", 0.2)),
	CLAYSTONE("claystone", Family.SEDIMENTARY, 0x9A8A78, 2.35, 2.2, gangue("clay", 0.8, "quartz", 0.2)),
	CHERT("chert", Family.SEDIMENTARY, 0x8A8A80, 2.60, 7.0, gangue("quartz", 0.9, "chalcedony", 0.1)),
	CONGLOMERATE("conglomerate", Family.SEDIMENTARY, 0x9C9484, 2.50, 5.5, gangue("quartz", 0.4, "feldspar", 0.3, "rock_fragments", 0.3)),
	// 岩盐：石盐为主，共生少量钾盐（KCl）——钾源就是从这里采出来的。
	// 硝石（natratine NaNO3）与石盐 / 钾盐共生在蒸发岩里 —— 氮族的矿源。
	ROCK_SALT("rock_salt", Family.SEDIMENTARY, 0xE8E4DC, 2.17, 2.5, gangue("halite", 0.82, "sylvite", 0.13, "natratine", 0.05)),
	// 石膏质蒸发岩带少量钾硝石（niter KNO3 / 硝石）。
	GYPSUM("gypsum", Family.SEDIMENTARY, 0xE0DCD0, 2.32, 2.0, gangue("gypsum", 0.95, "niter", 0.05)),

	// ---------------------------------------------------------------- 喷出岩
	ANDESITE("andesite", Family.VOLCANIC, 0x888A86, 2.70, 6.0, gangue("plagioclase", 0.5, "pyroxene", 0.3, "hornblende", 0.2)),
	// 玄武岩是火山岩：喷气孔冷凝出硇砂（sal ammoniac NH4Cl）—— 氨的矿源。
	BASALT("basalt", Family.VOLCANIC, 0x4A4C50, 2.95, 6.0, gangue("plagioclase", 0.38, "pyroxene", 0.35, "olivine", 0.25, "sal_ammoniac", 0.02)),
	DACITE("dacite", Family.VOLCANIC, 0x9A968C, 2.70, 6.5, gangue("quartz", 0.4, "plagioclase", 0.4, "biotite", 0.2)),
	RHYOLITE("rhyolite", Family.VOLCANIC, 0xB49C8C, 2.50, 6.5, gangue("quartz", 0.5, "feldspar", 0.4, "biotite", 0.1)),

	// ---------------------------------------------------------------- 侵入岩
	GRANITE("granite", Family.PLUTONIC, 0xA08078, 2.70, 6.5, gangue("quartz", 0.3, "feldspar", 0.55, "biotite", 0.15)),
	DIORITE("diorite", Family.PLUTONIC, 0x9A9A9E, 2.90, 6.0, gangue("plagioclase", 0.6, "hornblende", 0.25, "biotite", 0.15)),
	GABBRO("gabbro", Family.PLUTONIC, 0x50525A, 3.00, 6.0, gangue("plagioclase", 0.5, "pyroxene", 0.4, "olivine", 0.1)),

	// ---------------------------------------------------------------- 变质岩
	GNEISS("gneiss", Family.METAMORPHIC, 0x8E8494, 2.75, 6.5, gangue("quartz", 0.35, "feldspar", 0.4, "biotite", 0.25)),
	SCHIST("schist", Family.METAMORPHIC, 0x7A7E86, 2.80, 4.0, gangue("quartz", 0.3, "mica", 0.4, "feldspar", 0.3)),
	SLATE("slate", Family.METAMORPHIC, 0x5C6068, 2.75, 3.5, gangue("clay", 0.5, "quartz", 0.3, "mica", 0.2)),
	PHYLLITE("phyllite", Family.METAMORPHIC, 0x6E7278, 2.75, 3.0, gangue("quartz", 0.4, "mica", 0.4, "clay", 0.2)),
	QUARTZITE("quartzite", Family.METAMORPHIC, 0xD6D2C6, 2.65, 7.0, gangue("quartz", 1.0)),
	MARBLE("marble", Family.METAMORPHIC, 0xD8D8D2, 2.70, 3.5, gangue("calcite", 1.0));

	/** 成岩环境。族决定深度带与可出现的矿物范围。 */
	public enum Family {

		SEDIMENTARY("sedimentary"),
		VOLCANIC("volcanic"),
		PLUTONIC("plutonic"),
		METAMORPHIC("metamorphic");

		private final String id;
		private RockType[] members;

		Family(String id) {
			this.id = id;
		}

		public String id() {
			return id;
		}

		/**
		 * 本族包含的全部岩石。
		 *
		 * <p>刻意做成惰性计算，而不是在 {@link RockType} 的静态块里预先填好 —— 因为
		 * 引用 {@code RockType.Family.SEDIMENTARY} 这样的嵌套枚举常量<b>不会</b>触发外层
		 * {@code RockType} 的类初始化，静态块可能压根没执行过，取出一个空表。
		 * 这里第一次调用时才去问 {@code RockType.values()}，而这一步必然会完成外层初始化。
		 */
		public RockType[] all() {
			RockType[] cached = members;
			if (cached == null) {
				cached = Arrays.stream(RockType.values())
						.filter(rock -> rock.family == this)
						.toArray(RockType[]::new);
				members = cached;
			}
			return cached;
		}

		public String translationKey() {
			return "rock_family.geometallurgy." + id;
		}

		public Component displayName() {
			return Component.translatable(translationKey());
		}
	}

	private final String id;
	private final Family family;
	private final int color;
	private final double density;
	private final double mohs;
	private final Map<String, Double> gangue;

	RockType(String id, Family family, int color, double density, double mohs,
			Map<String, Double> gangue) {
		this.id = id;
		this.family = family;
		this.color = color;
		this.density = density;
		this.mohs = mohs;
		this.gangue = gangue;
	}

	public String id() {
		return id;
	}

	public Family family() {
		return family;
	}

	/** 用于探针界面与调试的显示色（0xRRGGBB）。 */
	public int color() {
		return color;
	}

	/** 真实密度（g/cm³），按主导矿物的常见值取。离心机按它把脉石与重矿物分层。 */
	public double density() {
		return density;
	}

	/** 莫氏硬度 1~10，按主导矿物取。钻头选型的依据。 */
	public double mohs() {
		return mohs;
	}

	/** 脉石组成：矿物名 → 占比（合计 1.0）。 */
	public Map<String, Double> gangue() {
		return gangue;
	}

	public String translationKey() {
		return "rock.geometallurgy." + id;
	}

	public Component displayName() {
		return Component.translatable(translationKey());
	}

	@Override
	public boolean isMineral() {
		return false;
	}

	public static RockType byId(String id) {
		for (RockType rock : values()) {
			if (rock.id.equals(id)) {
				return rock;
			}
		}
		return null;
	}

	/** 把 "id,占比,id,占比…" 展开成保持插入顺序的脉石表。 */
	private static Map<String, Double> gangue(Object... pairs) {
		Map<String, Double> map = new LinkedHashMap<>();
		for (int i = 0; i + 1 < pairs.length; i += 2) {
			map.put((String) pairs[i], (Double) pairs[i + 1]);
		}
		return Map.copyOf(map);
	}
}
