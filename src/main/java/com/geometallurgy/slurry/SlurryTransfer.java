package com.geometallurgy.slurry;

import java.util.Map;

/**
 * 两罐之间的搬运。
 *
 * <p>搬运单位是「一批混合液」：{@link VirtualSlurry#drainProportional} 按比例抽出的那份成分表，
 * 原样并进目标罐。目标装不下的部分不会凭空消失 —— 这里先把请求量削到目标的剩余容量，
 * 再动源罐，所以不会出现「抽出来了却塞不进去」的损耗。
 */
public final class SlurryTransfer {

	private SlurryTransfer() {
	}

	/** 从 {@code source} 这个接口抽料，灌进 {@code dest} 这个罐。 */
	public static double move(SlurryHandler source, VirtualSlurry dest, double millibuckets) {
		if (source == null || !source.canExtract()) {
			return 0.0;
		}
		return move(source.tank(), dest, millibuckets);
	}

	/** 从 {@code source} 这个罐抽料，推进 {@code dest} 这个接口。 */
	public static double move(VirtualSlurry source, SlurryHandler dest, double millibuckets) {
		if (dest == null || !dest.canInsert()) {
			return 0.0;
		}
		return move(source, dest.tank(), millibuckets);
	}

	/** 罐对罐。 */
	public static double move(VirtualSlurry source, VirtualSlurry dest, double millibuckets) {
		if (source == null || dest == null || source == dest || millibuckets <= 0.0) {
			return 0.0;
		}
		// 形态必须对得上：矿浆和矿粉不是同一种东西，混进一个罐里下游就没法处理了。
		// 先看目标罐收不收这种形态（声明式，挡得住「上游主动推」这条路），
		// 再看两边形态是否一致（罐空时不算冲突 —— 空罐还没有内容形态）。
		if (!dest.allows(source.form())) {
			return 0.0;
		}
		if (dest.amount() > 0.0 && dest.form() != source.form()) {
			return 0.0;
		}
		double budget = Math.min(millibuckets, Math.min(source.amount(), dest.room()));
		if (budget <= 0.0) {
			return 0.0;
		}
		if (dest.amount() <= 0.0) {
			dest.setForm(source.form());
		}
		Map<SlurryPart, Double> batch = source.drainProportional(budget);
		return dest.insertMap(batch);
	}
}
