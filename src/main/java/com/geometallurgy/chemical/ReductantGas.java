package com.geometallurgy.chemical;

import java.util.Map;

/**
 * 气体还原剂（CO / H₂）的当量表 —— 桶装气体当还原剂时，当量的<b>唯一来源</b>。
 *
 * <h2>刻度：1.0 当量 = 夺走 1 mol 氧</h2>
 * 与碳系同刻度。{@code Reagent.CARBON} 里碳粉记 1.0，本征化过来就是
 * 「一件碳粉 = 1 mol C」，正好对应 {@code C + [O] -> CO}（每 mol 碳夺 1 mol 氧）；
 * 煤炭 0.8 / 木炭 0.9 / 碳棒 1.5 则是每件的含碳摩尔数。
 *
 * <h2>两种气体都是 1.0 当量/mol</h2>
 * <ul>
 *   <li>{@code CO + [O] -> CO₂}：每 mol CO 夺 1 mol 氧 ⇒ <b>1.0</b></li>
 *   <li>{@code H₂ + [O] -> H₂O}：每 mol H₂ 夺 1 mol 氧 ⇒ <b>1.0</b></li>
 * </ul>
 * 化学计量相同，所以两个数都是 1.0；仍然分列常数，日后要单独调价不用改算法。
 * 一个满桶 {@link MolStock#BUCKET_MOL} = 15 mol ⇒ <b>15.0 当量</b>。
 *
 * <p>这是一个<b>纯类</b>（只碰 {@link MolStock}），可在普通 JVM 里离线复算。
 */
public final class ReductantGas {

	private ReductantGas() {
	}

	/** 气体 id → 每 mol 夺走的氧（mol），也就是「当量 / mol」。 */
	private static final Map<String, Double> UNITS_PER_MOL = Map.of(
			"carbon_monoxide", 1.0,
			"hydrogen", 1.0);

	/** 这种物质是不是气体还原剂。 */
	public static boolean isGas(String id) {
		return UNITS_PER_MOL.containsKey(id);
	}

	/** 这种气体每 mol 的当量；不是气体还原剂返回 0。 */
	public static double unitsPerMol(String id) {
		return UNITS_PER_MOL.getOrDefault(id, 0.0);
	}

	/**
	 * 一本账里全部的还原当量。
	 *
	 * <p><b>混了非还原剂（水 / 酸等）就整桶作废</b>：返回 0，这只桶不会被当成还原剂收。
	 * 空账同样返回 0。所以「水桶 / 酸桶」在还原路一律被拒。
	 */
	public static double units(MolStock stock) {
		if (stock == null) {
			return 0.0;
		}
		double sum = 0.0;
		for (Map.Entry<String, Double> e : stock.view().entrySet()) {
			double perMol = unitsPerMol(e.getKey());
			if (perMol <= 0.0) {
				return 0.0;
			}
			sum += e.getValue() * perMol;
		}
		return sum;
	}

	/**
	 * 从 {@code source} 里扣出最多 {@code wantUnits} 当量的气体，返回<b>实际扣出的那一本账</b>。
	 *
	 * <p>就地扣减 {@code source}（负数=扣减走 {@link MolStock#take}，bug#9 的口径）。
	 * 调用方拿到返回的新账后，按账把 mol 写回载具（{@code MolCarrier.take}），
	 * 这样扣的 mol 与写回的 mol 永远同步。
	 */
	public static MolStock draw(MolStock source, double wantUnits) {
		MolStock out = MolStock.empty();
		if (source == null || wantUnits <= 0.0) {
			return out;
		}
		double drawn = 0.0;
		for (String id : new java.util.ArrayList<>(source.view().keySet())) {
			double perMol = unitsPerMol(id);
			if (perMol <= 0.0) {
				continue;
			}
			double need = (wantUnits - drawn) / perMol;
			if (need <= 1.0e-12) {
				break;
			}
			double mol = Math.min(need, source.of(id));
			double taken = source.take(id, mol);
			if (taken > 0.0) {
				out.add(id, taken);
				drawn += taken * perMol;
			}
		}
		return out;
	}
}
