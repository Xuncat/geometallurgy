package com.geometallurgy.chemical;

import java.util.ArrayList;
import java.util.Map;

/**
 * 搅拌器「A / B 两本预混账并入混合槽」的唯一实现（{@link com.geometallurgy.blockentity.MixerBlockEntity#mix()} 调它）。
 *
 * <p><b>为什么单独一个类</b>：这段算法只碰 {@link MolStock}，不依赖任何 Minecraft 类型，
 * 于是可以在 JVM 里离线复算（搅拌器是 BlockEntity，离线起不来）。这样比率 / 容量 /
 * 分数账的边界都能被直接验证，而机器侧只剩一次转发。
 *
 * <p><b>用户口径（bug#21）</b>：容器内部多种物质就是共存混合物，A:B 比率是
 * 「精准配比」的<b>偏好</b>，不是把料锁死在缓冲里的闸门。所以分两段：
 * <ol>
 *   <li><b>按比率兑入</b>：这一批能兑多少由目标账余量与两侧「按比率能出的量」取最小，
 *       比率仍决定均衡段的组成；</li>
 *   <li><b>余料自由兑入</b>：再把两侧剩下的料按剩余量比例并入，只受目标账容量约束 ——
 *       一侧缺料 / 比率不整除另一侧时，那部分料不会永久卡在缓冲里空占机器。</li>
 * </ol>
 *
 * <p>旧实现把第 2 段省了：{@code batch = min(room, A/aFrac, B/bFrac)}，只要一侧为空
 * （{@code B/bFrac = 0}）或比率不整除，另一侧就整批留在缓冲里 —— 表现为「倒进氯气和水，
 * 搅不动、里面一点反应都没有」。
 */
public final class MolBlend {

	/** 低于此值当作 0（与机器侧的 MIN_MOL 同量级）。 */
	public static final double MIN_MOL = 1.0e-3;

	private MolBlend() {
	}

	/**
	 * 把 {@code a} / {@code b} 两本缓冲并入 {@code tank}。
	 *
	 * @param aFraction A 侧在均衡段应占的比例（0..1）；B 侧为 {@code 1 - aFraction}
	 * @param capacity  目标账容量（mol）
	 * @return 是否真的倒入了料（调用方据此决定要不要 {@code setChanged()}）
	 */
	public static boolean blend(MolStock a, MolStock b, MolStock tank, double aFraction, double capacity) {
		aFraction = Math.max(0.0, Math.min(1.0, aFraction));
		double bFraction = 1.0 - aFraction;
		double total = a.totalMol() + b.totalMol();
		if (total <= MIN_MOL) {
			return false;
		}
		double room = capacity - tank.totalMol();
		if (room <= MIN_MOL) {
			return false;
		}

		boolean poured = false;

		// 第一段：按比率兑入。batch 以「这一批的总 mol」为单位，保证 A:B 恰好是 ratio 指定的比例。
		double batch = room;
		if (aFraction > 0.0) {
			batch = Math.min(batch, a.totalMol() / aFraction);
		}
		if (bFraction > 0.0) {
			batch = Math.min(batch, b.totalMol() / bFraction);
		}
		if (batch > MIN_MOL) {
			if (aFraction > 0.0) {
				poured |= pour(a, tank, batch * aFraction, capacity);
			}
			if (bFraction > 0.0) {
				poured |= pour(b, tank, batch * bFraction, capacity);
			}
		}

		// 第二段：剩下的自由兑入（不再看比率），只受容量约束。
		// 按两侧剩余量比例分配这一批：空间够就一次倒空，不够就等下一 tick。
		double left = a.totalMol() + b.totalMol();
		if (left <= MIN_MOL) {
			return poured;
		}
		double share = Math.min(capacity - tank.totalMol(), left);
		if (share <= MIN_MOL) {
			return poured;
		}
		double aLeft = a.totalMol();
		if (aLeft > MIN_MOL) {
			poured |= pour(a, tank, share * (aLeft / left), capacity);
		}
		double bLeft = b.totalMol();
		if (bLeft > MIN_MOL) {
			poured |= pour(b, tank, share * (bLeft / left), capacity);
		}
		return poured;
	}

	/**
	 * 把 {@code from} 里的一定量按占比兑进 {@code to}；量同时被 {@code to} 的余量钳住。
	 *
	 * @return 是否真的倒了料
	 */
	private static boolean pour(MolStock from, MolStock to, double amount, double capacity) {
		double total = from.totalMol();
		if (total <= MIN_MOL) {
			return false;
		}
		amount = Math.min(amount, Math.min(total, capacity - to.totalMol()));
		if (amount <= MIN_MOL) {
			return false;
		}
		double ratio = amount / total;
		for (Map.Entry<String, Double> e : new ArrayList<>(from.view().entrySet())) {
			double got = from.take(e.getKey(), e.getValue() * ratio);
			if (got > 0.0) {
				to.add(e.getKey(), got);
			}
		}
		return true;
	}
}
