package com.geometallurgy.metal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 金属活动性顺序。
 *
 * <p>置换炉的物理根据就是这一张表：<b>只有活动性排在前面的金属，才能把排在后面的金属从它的
 * 化合物里顶出来</b>。铁能置换铜（湿法炼铜的老办法），铜置换不了铁。
 *
 * <p><b>活动性闸门只属于置换炉</b>（用户 2026-10-17 口径：「置换才需要金属，还原不刚需金属」）：
 * 还原炉的基础还原剂是碳系，金属只是可选的替代，不在那里按活动性卡人。
 *
 * <p>序号越小越活泼。数值本身没有物理含义，只是一个「够不够活泼」的比较基准；
 * 顺序按中学化学的活动性顺序排，钾钙钠镁铝在前，金银汞在最后。
 */
public final class MetalSeries {

	private MetalSeries() {
	}

	/** 由活泼到不活泼。 */
	private static final List<String> ORDER = List.of(
			"potassium", "calcium", "sodium", "magnesium", "aluminum",
			"manganese", "zinc", "chromium", "iron", "cobalt", "nickel",
			"tin", "lead", "tungsten", "molybdenum", "copper",
			"bismuth", "silver", "gold", "mercury");

	private static final Map<String, Integer> INDEX = new HashMap<>();

	static {
		for (int i = 0; i < ORDER.size(); i++) {
			INDEX.put(ORDER.get(i), i);
		}
	}

	/** 活动性序号；不在表里的物质给一个「比汞还不活泼」的大值。 */
	public static int activity(String metalId) {
		Integer index = INDEX.get(metalId);
		return index == null ? ORDER.size() + 1 : index;
	}

	/**
	 * {@code reductant} 能不能把 {@code target} 从它的化合物里顶出来。
	 *
	 * <p>同种金属不算 —— 用铁去置换铁没有意义，也顶不动。
	 */
	public static boolean canDisplace(String reductant, String target) {
		return !reductant.equals(target) && activity(reductant) < activity(target);
	}

	/** 这种物质在表里有没有被当作金属看待。 */
	public static boolean isKnownMetal(String id) {
		return INDEX.containsKey(id);
	}

	/** 表里登记了多少种金属。 */
	public static int size() {
		return ORDER.size();
	}

	/**
	 * 成分的<b>活泼度</b>：0~1，越活泼越接近 1。
	 *
	 * <p>下游制品（撞针）要用它：活泼度高的材料更容易打响，但也更容易氧化消耗。
	 * 这里把「序号」线性翻成「活泼度」—— 序号 0（钾）→ {@code 1.0}，
	 * 序号 19（汞）→ {@code 0.0}。
	 *
	 * <p><b>含量加权</b>而不是取最活泼的那一项：一块九成铁掺一成钾的料，
	 * 它的活泼度该接近铁，而不是接近钾 —— 取最活泼会让一点点掺料把整块料的性质拉爆。
	 *
	 * <p>未登记的键（碳、石英这些非金属）直接跳过；若一项都没匹配上，
	 * 返回 {@code 0.5}（中位）而不是 0 —— 返回 0 会让「不确定」看起来像「最惰性」。
	 */
	public static double activityOf(Map<String, Double> composition) {
		if (composition == null || composition.isEmpty()) {
			return 0.5;
		}
		double total = 0.0;
		double sum = 0.0;
		int span = Math.max(1, ORDER.size() - 1);
		for (Map.Entry<String, Double> entry : composition.entrySet()) {
			Integer index = INDEX.get(entry.getKey());
			if (index == null || entry.getValue() <= 0.0) {
				continue;
			}
			double weight = entry.getValue();
			sum += weight * (1.0 - (double) index / span);
			total += weight;
		}
		return total <= 0.0 ? 0.5 : sum / total;
	}
}
