package com.geometallurgy.proto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.util.RandomSource;

/**
 * 稀土元素表。
 *
 * <h2>为什么单独一张表，而不是一种「稀土」</h2>
 * 真实稀土矿从来不是单一元素：十七种元素共生在一起，工业上也是先拿到<b>混合稀土</b>，
 * 再按配分（distribution）去分离。所以矿石里那点稀土本来就是一整族东西，
 * 一炉能同时拿到的通常只有三五种 —— 这里就把这件事写进模型：抽取时抽
 * <b>1~3 种主稀土</b>构成主体、<b>2~6 种副稀土</b>作零头，权重按地壳丰度。
 *
 * <h2>主 / 副怎么分</h2>
 * 按真实的轻 / 重稀土分组：轻稀土（镧铈镨钕钐铕）是配分里的大头，铈一份能占掉近四成；
 * 重稀土（钆铽镝钬铒铥镱镥钇钪）是零头。玩家拿到的那块「稀土锭」，
 * 名字统一叫稀土，具体配分在浮层里逐项列出来 —— 环上只画一块，不然十几种淡色扇形
 * 挤在一起就是一团糊。
 *
 * <p>钷（Pm）没有稳定同位素，天然几乎不存在，所以不收进表里。
 */
public final class RareEarth {

	private RareEarth() {
	}

	/**
	 * 一种稀土元素。
	 *
	 * @param id      物质 id（也是 {@link Substance} 的键）
	 * @param cnName  中文名
	 * @param color   显示用色（稀土氧化物都是淡色，靠色相细微分家）
	 * @param primary 是不是轻稀土（主稀土）
	 * @param weight  相对权重：拿地壳丰度当配分权重，铈最高、铥镥最低
	 */
	public record Def(String id, String cnName, int color, boolean primary, double weight) {
	}

	/** 轻稀土：配分里的大头。 */
	private static final List<Def> LIGHT = List.of(
			new Def("cerium", "铈", 0xE0DCC8, true, 1.00),
			new Def("lanthanum", "镧", 0xD8D4C8, true, 0.72),
			new Def("neodymium", "钕", 0xC8C0D8, true, 0.55),
			new Def("praseodymium", "镨", 0xD8D0A8, true, 0.16),
			new Def("samarium", "钐", 0xD0C8D0, true, 0.10),
			new Def("europium", "铕", 0xD8C8C8, true, 0.03));

	/** 重稀土：零头，但值钱（镝铽是磁材的关键添加）。 */
	private static final List<Def> HEAVY = List.of(
			new Def("yttrium", "钇", 0xD0D4D8, false, 1.00),
			new Def("dysprosium", "镝", 0xC8C0C0, false, 0.16),
			new Def("gadolinium", "钆", 0xC8C8D0, false, 0.16),
			new Def("scandium", "钪", 0xCCCCC4, false, 0.09),
			new Def("erbium", "铒", 0xD8C0C8, false, 0.09),
			new Def("ytterbium", "镱", 0xD0D0C0, false, 0.09),
			new Def("terbium", "铽", 0xC0C8C8, false, 0.04),
			new Def("holmium", "钬", 0xD0C8B8, false, 0.04),
			new Def("thulium", "铥", 0xC0C0D0, false, 0.015),
			new Def("lutetium", "镥", 0xC8C8C0, false, 0.015));

	private static final List<Def> ALL = new ArrayList<>();
	private static final Map<String, Def> BY_ID = new HashMap<>();

	static {
		ALL.addAll(LIGHT);
		ALL.addAll(HEAVY);
		for (Def def : ALL) {
			BY_ID.put(def.id(), def);
		}
	}

	/** 全部稀土元素。 */
	public static List<Def> all() {
		return ALL;
	}

	/** 这个物质 id 是不是稀土元素。 */
	public static boolean isRareEarth(@Nullable String id) {
		return id != null && BY_ID.containsKey(id);
	}

	@Nullable
	public static Def byId(String id) {
		return BY_ID.get(id);
	}

	/**
	 * 按权重不放回地抽 {@code [min, max]} 个。
	 *
	 * <p>用权重当抽样概率（铈比铥容易被抽中），抽出来的个数在区间内均匀 ——
	 * 所以「一炉通常只有三五种」这件事是真的，不是权重筛出来的假象。
	 */
	public static List<Def> pick(RandomSource random, boolean primary, int min, int max) {
		List<Def> pool = new ArrayList<>(primary ? LIGHT : HEAVY);
		int count = Math.min(pool.size(), min + random.nextInt(max - min + 1));
		List<Def> chosen = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			double total = 0.0;
			for (Def def : pool) {
				total += def.weight();
			}
			double roll = random.nextDouble() * total;
			int index = pool.size() - 1;
			double acc = 0.0;
			for (int j = 0; j < pool.size(); j++) {
				acc += pool.get(j).weight();
				if (roll <= acc) {
					index = j;
					break;
				}
			}
			chosen.add(pool.remove(index));
		}
		return chosen;
	}
}
