package com.geometallurgy.proto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.geometallurgy.geology.RockType;

import net.minecraft.util.RandomSource;

/**
 * 一处矿脉里某一种矿物的完整属性。
 *
 * <p>由「矿物种 + 所在岩体 + 一个确定性随机源」掷出。整套值是纯函数的结果：
 * 只要种子、坐标与 epoch 相同，重新算一遍得到的一定是同一份矿脉。机器另外还会把算出来的
 * 结果写进存档（见 {@link #encode()}），这样即使日后改了生成算法，老存档里那罐矿浆的来历也不会变。
 *
 * <p><b>两个层次要分清：</b>
 * <ul>
 *   <li>{@code abundance 丰度} —— 这种矿物在<b>矿体</b>里占多少体积。<b>不是常数</b>：
 *       同一个矿体，中心处最高、往外按形状衰减到零（见 {@link com.geometallurgy.vein.OreBody}）。
 *       它由矿体的几何算出来后传进来。数值上贴合真实矿床：赤铁矿 10%~40%、黄铜矿 0.8%~3%、
 *       自然金万分之几。</li>
 *   <li>{@code richness 品位} —— 矿物颗粒<b>本身</b>有多少可回收的有价成分。
 *       按化学式算，再扣掉那部分难分离的杂质（见 {@link DepositType#MIN_IMPURITY}）。</li>
 * </ul>
 * 这两项连同杂质组成都属于「只落盘、不上界面」的内部数据。
 *
 * @param abundance  丰度：矿物在矿脉岩体中的体积分数
 * @param richness   品位：有价主成分占矿物本身的比例
 * @param impurities 杂质元素 → 占比（占矿物本身）
 * @param gangue     脉石物质 → 占比（来自母岩）
 * @param companion  伴生金属 → 占比
 * @param density    矿脉致密程度
 */
public record VeinAttributes(
		OreMineral mineral,
		RockType rock,
		double abundance,
		double richness,
		Map<String, Double> impurities,
		Map<String, Double> gangue,
		Map<String, Double> companion,
		double density,
		String genesis) {

	/** 三角分布随机：两个均匀随机取平均，值更容易落在区间中部。 */
	private static double triangular(RandomSource random, double min, double max) {
		if (min >= max) {
			return min;
		}
		double range = max - min;
		double rollA = random.nextDouble() * range;
		double rollB = random.nextDouble() * range;
		return min + (rollA + rollB) / 2.0;
	}

	private static double round3(double value) {
		return Math.round(value * 1000.0) / 1000.0;
	}

	private static double sum(Map<String, Double> map) {
		double total = 0.0;
		for (double value : map.values()) {
			total += value;
		}
		return total;
	}

	public static VeinAttributes generate(RandomSource random, OreMineral mineral, RockType rock,
			double abundance) {
		DepositType deposit = mineral.deposit();

		// 丰度由 {@link com.geometallurgy.vein.OreBody} 的矿体几何算好传进来：
		// 中心高、沿形状衰减、方块级散点。这里不再自己掷。

		// 1) 杂质总量：矿物一旦出现，提取出的金属总会夹带 10%~30% 难分离的伴生杂质
		double impurityTotal = DepositType.MIN_IMPURITY
				+ random.nextDouble() * (DepositType.MAX_IMPURITY - DepositType.MIN_IMPURITY);

		// 2) 从杂质池里选若干种，按权重把总量分下去
		Map<String, Double> impurities = new LinkedHashMap<>();
		List<String> pool = new ArrayList<>(deposit.impurityPool());
		if (!pool.isEmpty()) {
			shuffle(pool, random);
			int count = 1 + random.nextInt(pool.size());
			double[] weights = new double[count];
			double weightSum = 0.0;
			for (int i = 0; i < count; i++) {
				ImpurityDef.Entry entry = ImpurityDef.get(pool.get(i));
				double base = entry == null
						? 2.0
						: entry.minWeight() + random.nextDouble() * (entry.maxWeight() - entry.minWeight());
				double weight = Math.max(base + random.nextGaussian() * 2.0, 0.5);
				weights[i] = weight;
				weightSum += weight;
			}
			for (int i = 0; i < count; i++) {
				double fraction = weightSum > 0.0 ? weights[i] / weightSum * impurityTotal : 0.0;
				if (fraction > 0.0005) {
					impurities.merge(pool.get(i), fraction, Double::sum);
				}
			}
		}

		// 3) 品位：矿物自身的化学含量（方铅矿 86.6% 铅、黄铜矿 34.6% 铜……），
		//    再扣掉那部分难分离的杂质，剩下的才是最终能回收的量
		double richness = deposit.richness() * (1.0 - impurityTotal);

		// 4) 伴生相：用主矿自己声明的那几种，各抖 ±30%。
		//    它是「顺带捞出来的东西」，所以量必须压得低（千分之几到百分之几）——
		//    不然主产品线就得给伴生让路，那就本末倒置了。
		double leftover = Math.max(0.0, 1.0 - richness - sum(impurities));
		Map<String, Double> declared = mineral.companions();
		Map<String, Double> companion = new LinkedHashMap<>();
		if (!declared.isEmpty()) {
			for (Map.Entry<String, Double> entry : declared.entrySet()) {
				companion.merge(entry.getKey(),
						entry.getValue() * (0.70 + random.nextDouble() * 0.60), Double::sum);
			}
			double total = sum(companion);
			double cap = leftover * 0.6;
			if (total > cap && total > 0.0) {
				double shrink = cap / total;
				companion.replaceAll((key, value) -> value * shrink);
			}
		}

		// 5) 脉石：剩下的部分按母岩的脉石组成分配
		double gangueTotal = Math.max(0.0, 1.0 - richness - sum(impurities) - sum(companion));
		Map<String, Double> gangue = new LinkedHashMap<>();
		Map<String, Double> rockGangue = rock.gangue();
		double rockGangueSum = sum(rockGangue);
		if (gangueTotal > 0.0 && rockGangueSum > 0.0) {
			for (Map.Entry<String, Double> entry : rockGangue.entrySet()) {
				gangue.put(entry.getKey(), entry.getValue() / rockGangueSum * gangueTotal);
			}
		}

		// 6) 千分位对齐 + 归一化，保证总和恰为 1.000
		richness = round3(richness);
		impurities = roundMap(impurities);
		companion = roundMap(companion);
		gangue = roundMap(gangue);

		double total = richness + sum(impurities) + sum(companion) + sum(gangue);
		if (total > 0.0 && Math.abs(total - 1.0) > 0.001) {
			double scale = 1.0 / total;
			richness = round3(richness * scale);
			impurities = scaleMap(impurities, scale);
			companion = scaleMap(companion, scale);
			gangue = scaleMap(gangue, scale);
		}

		double density = triangular(random, deposit.minDensity(), deposit.maxDensity());

		return new VeinAttributes(mineral, rock, abundance, richness,
				Map.copyOf(impurities), Map.copyOf(gangue), Map.copyOf(companion),
				density, deposit.genesis());
	}

	/** 换一个丰度，其余字段照旧。用于把一处分脉的总丰度压回上限。 */
	public VeinAttributes withAbundance(double newAbundance) {
		return new VeinAttributes(mineral, rock, newAbundance, richness,
				impurities, gangue, companion, density, genesis);
	}

	/** 转成四维成分表，并做一次最大余数法归一化。 */
	public OreComposition toComposition() {
		return new OreComposition(
				Map.of(mineral.valuable(), richness),
				gangue,
				impurities,
				companion,
				genesis).normalized();
	}

	public double impurityTotal() {
		return sum(impurities);
	}

	public double companionTotal() {
		return sum(companion);
	}

	public double gangueTotal() {
		return sum(gangue);
	}

	// ------------------------------------------------------------------ 落盘

	/**
	 * 序列化成一行文本：{@code 矿物id|丰度|品位|致密度|成因|杂质|伴生|脉石}。
	 *
	 * <p>只给存档用，不参与网络同步 —— 界面上不显示丰度与杂质，所以没必要把它们推给客户端。
	 */
	public String encode() {
		return String.join("|",
				mineral.id(),
				num(abundance),
				num(richness),
				num(density),
				genesis,
				encodeMap(impurities),
				encodeMap(companion),
				encodeMap(gangue));
	}

	/** {@link #encode()} 的逆运算。矿物名对不上或字段残缺时返回 null，让调用方跳过这一条。 */
	@Nullable
	public static VeinAttributes decode(String text, RockType rock) {
		String[] parts = text.split("\\|", -1);
		if (parts.length < 8) {
			return null;
		}
		OreMineral mineral = OreMineral.byId(parts[0]);
		if (mineral == null) {
			return null;
		}
		try {
			return new VeinAttributes(mineral, rock,
					Double.parseDouble(parts[1]),
					Double.parseDouble(parts[2]),
					decodeMap(parts[5]),
					decodeMap(parts[7]),
					decodeMap(parts[6]),
					Double.parseDouble(parts[3]),
					parts[4]);
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private static String num(double value) {
		return String.format(Locale.ROOT, "%.6f", value);
	}

	private static String encodeMap(Map<String, Double> map) {
		StringBuilder builder = new StringBuilder();
		for (Map.Entry<String, Double> entry : map.entrySet()) {
			if (builder.length() > 0) {
				builder.append(',');
			}
			builder.append(entry.getKey()).append('=').append(num(entry.getValue()));
		}
		return builder.toString();
	}

	private static Map<String, Double> decodeMap(String text) {
		Map<String, Double> map = new LinkedHashMap<>();
		if (text.isEmpty()) {
			return Map.copyOf(map);
		}
		for (String pair : text.split(",")) {
			int equals = pair.indexOf('=');
			if (equals <= 0) {
				continue;
			}
			try {
				map.put(pair.substring(0, equals), Double.parseDouble(pair.substring(equals + 1)));
			} catch (NumberFormatException ignored) {
				// 坏数据跳过
			}
		}
		return Map.copyOf(map);
	}

	// ------------------------------------------------------------------ 内部

	private static void shuffle(List<String> list, RandomSource random) {
		Collections.shuffle(list, new java.util.Random(random.nextLong()));
	}

	private static Map<String, Double> roundMap(Map<String, Double> map) {
		Map<String, Double> out = new LinkedHashMap<>();
		map.forEach((key, value) -> {
			double rounded = round3(value);
			if (rounded > 0.0005) {
				out.put(key, rounded);
			}
		});
		return out;
	}

	private static Map<String, Double> scaleMap(Map<String, Double> map, double scale) {
		Map<String, Double> out = new LinkedHashMap<>();
		map.forEach((key, value) -> {
			double scaled = round3(value * scale);
			if (scaled > 0.0005) {
				out.put(key, scaled);
			}
		});
		return out;
	}
}
