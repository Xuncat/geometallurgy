package com.geometallurgy.chemical;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一份「物质的量」账本：{@code 物质 id → mol 数}。
 *
 * <p>mol 是唯一本征记账量（§一）。这个对象承载一个容器/槽/桶里的全部内容，
 * 与 {@link com.geometallurgy.proto.OreComposition} 的「物质 id → 占比」同构，
 * 但这里存的是<b>绝对 mol 数</b>，不是归一化占比。
 *
 * <p>要点（对应设计稿 §三 / §十）：
 * <ul>
 *   <li><b>成分必须分离，显示可为混合物</b>：同分异构体 / 溶质 / 溶剂各自是独立条目，
 *       绝不合并 —— 账本无损，显示层才允许有损聚合。</li>
 *   <li><b>溶剂是介质</b>：水（{@link Chemical#isSolvent}）照常记 mol，但它不参与反应账。</li>
 *   <li><b>质量可精确派生</b>：{@link #massGrams} = Σ nᵢ·Mᵢ（相态无关）。</li>
 *   <li><b>B 是 1:1 显示名</b>：{@link #totalB} = 总 mol 数（1 mol ≡ 1 B = 1000 mB），不查物性。</li>
 * </ul>
 */
public final class MolStock {

	/** 全局换算：1 mol ≡ 1 B = 1000 mB（设计稿 §一 锚点，2026-10-14 定）。 */
	public static final int MB_PER_B = 1000;
	public static final double MOL_PER_B = 1.0;

	/**
	 * 一金属桶的容量 = 15 mol = 15 B（设计稿 §一 / §十五 锚点，2026-10-14 定）。
	 *
	 * <p>桶是<b>抽象的大量级</b>载体：它只是一个「装得够多」的罐子，
	 * 15 mol 不折算成任何现实容器的体积，也不与锭数挂钩（见 {@link #INGOT_MOL}）。
	 */
	public static final double BUCKET_MOL = 15.0;

	/**
	 * 一块金属锭（= 一次粉碎 = 一件金属粉）承载的 mol 数 = <b>0.1 mol</b>。
	 *
	 * <p><b>这里与 {@link #BUCKET_MOL} 刻意不对称</b>（2026-10-16 定，设计稿 §十五）：
	 * 桶是抽象的大量级载体（15 mol），锭是 0.1 mol 的化学计量载体。
	 * 所以 <b>1 桶 ≠ 150 锭</b>是刻意的，不是漏算 —— 桶与锭本来就不是同一量纲下的东西，
	 * 拿「桶能装几锭」去推配方是错的。
	 *
	 * <p>0.1 mol 让「1 锭 → 1 粉 → 0.1 mol」成为粉碎机的一次无损换算：它不改配平，
	 * 只把整锭变成 mol 世界的入口（见 {@code metal/PowderContent}）。
	 *
	 * <p><b>有意接受的抽象</b>：0.1 mol 的金属折成质量远小于真实锭重
	 * （例如 Fe 0.1 mol ≈ 5.6 g，不是 837.8 g）—— MC 的单位本来就抽象，
	 * 这里只把「锭 / 粉」这个锚点钉在 0.1 mol 上，好让矿 → 金属的产量经济不必重调。
	 * 质量 g = Σnᵢ·Mᵢ 仍是精确派生量，只是它不再声称等于现实里一块锭的重量。
	 */
	public static final double INGOT_MOL = 0.1;

	// 有序：迭代顺序稳定，界面/网络/测试都依赖
	private final Map<String, Double> moles = new LinkedHashMap<>();

	/** 空账本。 */
	public static MolStock empty() {
		return new MolStock();
	}

	/** 单项账本。 */
	public static MolStock of(String id, double mol) {
		MolStock s = new MolStock();
		s.add(id, mol);
		return s;
	}

	public double of(String id) {
		return moles.getOrDefault(id, 0.0);
	}

	/**
	 * 加 mol。<b>负数就是扣</b>（{@link #take} 靠它实现），趋 0 的残渣被抹平
	 * （与 OreComposition.clean 同策略）。
	 *
	 * <p><b>警告</b>：这里曾经写成 `if (mol <= 0.0) return;` —— 那一行让 {@link #take} 变成
	 * 「说自己扣了、其实什么都没扣」，全模组的扣料（储罐抽取、桶倒料、机器过户、反应消耗）
	 * 全部静默失效，表现为凭空生料 / 取之不竭。改这里之前先想清楚 take 依赖它。
	 */
	public void add(String id, double mol) {
		if (id == null || mol == 0.0) {
			return;
		}
		double sum = moles.getOrDefault(id, 0.0) + mol;
		double rounded = Math.round(sum * 1000.0) / 1000.0;
		if (rounded > 0.0001) {
			moles.put(id, rounded);
		} else {
			moles.remove(id); // 扣到 0 就清掉，不留零残渣
		}
	}

	/** 扣 mol（可负则钳 0）。 */
	public double take(String id, double mol) {
		double have = of(id);
		double gone = Math.min(have, mol);
		add(id, -gone);
		return gone;
	}

	/** 能不能拿出至少 mol。 */
	public boolean has(String id, double mol) {
		return of(id) >= mol - 1e-9;
	}

	/** 总 mol 数（即 B 数，1:1）。 */
	public double totalMol() {
		double t = 0.0;
		for (double v : moles.values()) {
			t += v;
		}
		return t;
	}

	/** B 显示名：= 总 mol（1 mol ≡ 1 B）。 */
	public double totalB() {
		return totalMol();
	}

	/** 质量 g = Σ nᵢ·Mᵢ，精确、相态无关。 */
	public double massGrams() {
		double t = 0.0;
		for (Map.Entry<String, Double> e : moles.entrySet()) {
			t += e.getValue() * Chemical.molarMassOf(e.getKey());
		}
		return t;
	}

	/** 是否为空。 */
	public boolean isEmpty() {
		return moles.isEmpty();
	}

	/** 只读视图（成分必须分离，绝不在此合并）。 */
	public Map<String, Double> view() {
		return java.util.Collections.unmodifiableMap(moles);
	}

	/** 清空账本（存档读档时用）。 */
	public void clear() {
		moles.clear();
	}

	/** 复制一份（独立可变）。 */
	public MolStock copy() {
		MolStock s = new MolStock();
		s.moles.putAll(this.moles);
		return s;
	}

	@Override
	public String toString() {
		return "MolStock" + moles;
	}
}