package com.geometallurgy.proto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 虚拟原矿的成分。
 *
 * <p>四张表 + 一个成因标签，每张表都是「物质 id → 占比」，四张表加起来恒等于 1.000：
 * <ul>
 *   <li>{@code valuable} —— 有价主成分（赤铁矿的 {@code iron}，石英的 {@code silicon}）</li>
 *   <li>{@code gangue} —— 脉石，来自母岩的无用组分（石英、长石、方解石……）</li>
 *   <li>{@code impurities} —— 夹带的杂质元素（磷、硫、砷……）</li>
 *   <li>{@code companion} —— 伴生金属（挖锌时顺带出来的银、铜……）</li>
 * </ul>
 *
 * <p>构建时就把每项对齐到千分位并剔除浮点残渣，所以拿到手的数据是「洁净」的。
 */
public record OreComposition(
		Map<String, Double> valuable,
		Map<String, Double> gangue,
		Map<String, Double> impurities,
		Map<String, Double> companion,
		String genesis) {

	public OreComposition {
		valuable = clean(valuable);
		gangue = clean(gangue);
		impurities = clean(impurities);
		companion = clean(companion);
		if (genesis == null) {
			genesis = "";
		}
	}

	private static Map<String, Double> clean(Map<String, Double> map) {
		Map<String, Double> result = new LinkedHashMap<>();
		map.forEach((key, value) -> {
			double rounded = Math.round(value * 1000.0) / 1000.0;
			if (rounded > 0.0001) {
				result.put(key, rounded);
			}
		});
		return Map.copyOf(result);
	}

	/** 把四张表拼成一个序列（保留同名物质分属两桶的情况），交给最大余数法归一到 1.000。 */
	public OreComposition normalized() {
		Map<String, Double> raw = new LinkedHashMap<>();
		valuable.forEach((k, v) -> raw.merge(k, v, Double::sum));
		gangue.forEach((k, v) -> raw.merge(k, v, Double::sum));
		impurities.forEach((k, v) -> raw.merge(k, v, Double::sum));
		companion.forEach((k, v) -> raw.merge(k, v, Double::sum));

		Map<String, Double> normalized = CompositionMath.normalize(raw);

		Map<String, Double> v = new LinkedHashMap<>();
		Map<String, Double> g = new LinkedHashMap<>();
		Map<String, Double> i = new LinkedHashMap<>();
		Map<String, Double> c = new LinkedHashMap<>();
		valuable.keySet().forEach(k -> v.put(k, normalized.getOrDefault(k, 0.0)));
		gangue.keySet().forEach(k -> g.put(k, normalized.getOrDefault(k, 0.0)));
		impurities.keySet().forEach(k -> i.put(k, normalized.getOrDefault(k, 0.0)));
		companion.keySet().forEach(k -> c.put(k, normalized.getOrDefault(k, 0.0)));
		return new OreComposition(v, g, i, c, genesis);
	}

	/** 四张表的合计占比（正常应为 1.000）。 */
	public double total() {
		return sum(valuable) + sum(gangue) + sum(impurities) + sum(companion);
	}

	/** 有价成分的纯度：有价 /（有价 + 伴生）。脉石与杂质不计入 —— 那是选矿要处理的东西。 */
	public double purity() {
		double v = sum(valuable);
		double c = sum(companion);
		double denominator = v + c;
		return denominator <= 0.0 ? 0.0 : v / denominator;
	}

	private static double sum(Map<String, Double> map) {
		double total = 0.0;
		for (double value : map.values()) {
			total += value;
		}
		return total;
	}
}
