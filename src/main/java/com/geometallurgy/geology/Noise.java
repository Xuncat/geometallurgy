package com.geometallurgy.geology;

/**
 * 确定性的三维值噪声与 fBm 叠加。
 *
 * <p>整个岩体场必须做到「同一个世界 + 同一个坐标 = 同一个岩体」，因此这里不用
 * {@link java.util.Random}，而是把整数格点坐标经 SplitMix64 混合后取 [0,1) 的分量，
 * 再对八个角做五次平滑插值。这样噪声是坐标的纯函数，不占存档、可无限重算。
 *
 * <p>fBm（分形叠加）把若干个倍频的噪声按振幅减半叠加，得到自相似的花纹；
 * 岩体场再对输入坐标做一次域扭曲（domain warp），花纹就会呈现熔岩流动般的
 * 条带与旋涡，而不是白噪声式的斑点。
 */
final class Noise {

	private Noise() {
	}

	/** 单层值噪声，返回 [0,1)。 */
	static double value(long seed, double x, double y, double z) {
		int x0 = fastFloor(x);
		int y0 = fastFloor(y);
		int z0 = fastFloor(z);
		double fx = smooth(x - x0);
		double fy = smooth(y - y0);
		double fz = smooth(z - z0);

		double c000 = hash01(seed, x0, y0, z0);
		double c100 = hash01(seed, x0 + 1, y0, z0);
		double c010 = hash01(seed, x0, y0 + 1, z0);
		double c110 = hash01(seed, x0 + 1, y0 + 1, z0);
		double c001 = hash01(seed, x0, y0, z0 + 1);
		double c101 = hash01(seed, x0 + 1, y0, z0 + 1);
		double c011 = hash01(seed, x0, y0 + 1, z0 + 1);
		double c111 = hash01(seed, x0 + 1, y0 + 1, z0 + 1);

		double x00 = lerp(fx, c000, c100);
		double x10 = lerp(fx, c010, c110);
		double x01 = lerp(fx, c001, c101);
		double x11 = lerp(fx, c011, c111);

		double y0v = lerp(fy, x00, x10);
		double y1v = lerp(fy, x01, x11);

		return lerp(fz, y0v, y1v);
	}

	/** 分形叠加，返回 [0,1)。octaves 越多细节越丰富。 */
	static double fbm(long seed, double x, double y, double z, int octaves) {
		double sum = 0.0;
		double amp = 1.0;
		double norm = 0.0;
		double freq = 1.0;
		for (int i = 0; i < octaves; i++) {
			sum += amp * value(seed + i * 0x9E3779B97F4A7C15L, x * freq, y * freq, z * freq);
			norm += amp;
			amp *= 0.5;
			freq *= 2.0;
		}
		return norm <= 0.0 ? 0.0 : sum / norm;
	}

	/** 单层噪声映射到 [-1,1)。 */
	static double signed(long seed, double x, double y, double z) {
		return value(seed, x, y, z) * 2.0 - 1.0;
	}

	// ------------------------------------------------------------------ 内部

	private static int fastFloor(double v) {
		int i = (int) v;
		return v < i ? i - 1 : i;
	}

	/** 五次平滑（Perlin 的 6t^5-15t^4+10t^3），保证一阶、二阶导连续，花纹不会出现格子感。 */
	private static double smooth(double t) {
		return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
	}

	private static double lerp(double t, double a, double b) {
		return a + t * (b - a);
	}

	/** 把 (seed, 格点) 压成一个 [0,1) 的确定性分量。 */
	private static double hash01(long seed, int x, int y, int z) {
		long h = seed;
		h ^= (long) x * 0x9E3779B97F4A7C15L;
		h ^= (long) y * 0xC2B2AE3D27D4EB4FL;
		h ^= (long) z * 0x165667B19E3779F9L;
		h = mix64(h);
		// 取高 53 位映射到 [0,1)，避免低位的周期性问题
		return (h >>> 11) * 0x1.0p-53;
	}

	private static long mix64(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}
}
