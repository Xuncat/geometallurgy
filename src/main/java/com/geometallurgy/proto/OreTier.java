package com.geometallurgy.proto;

import net.minecraft.util.RandomSource;

/**
 * 矿脉的<b>两轴离散化模型</b>（ore balance 定稿）。
 *
 * <p>旧模型把两件事糊在一起：每种矿物在同一岩体里都以 {@code 0.25~0.40} 的<b>同一</b>概率出现，
 * 而矿体中心丰度直接照抄真实品位 —— 从石英脉的 90% 到钻石的 0.002%，跨度约 4.5×10⁴ 倍。
 * 于是「出矿量」被丰度一项独占：自然金的标称浓度看着有几百 g/t，实际一台机器每秒只能
 * 采到约 0.01 mB 的金矿物，稀有矿物在生存里根本不可用。
 *
 * <p>现在把三条旋钮<b>分开、各自离散成 5 档</b>，一条只管一件事：
 * <ul>
 *   <li>{@link Spawn 出现概率} —— 一个候选矿体单元里，这种矿物<b>长不长出来</b>。只碰频率。</li>
 *   <li>{@link Grade 矿体中心丰度} —— 长出来之后，矿体中心处这种矿物占多少体积分数。只碰浓度。</li>
 *   <li>{@link Body 矿体规模} —— 矿体延伸到真实 {@code extent} 的倍率。只碰体积。</li>
 * </ul>
 * 三者互不影响：调 {@code Spawn} 不会动到矿体大小，调 {@code Body} 也不会动到出现概率；
 * 它们分别由 {@code OreMineral.dep(...)} 的<b>前三个参数</b>给出，每个矿一行，便于调参。
 *
 * <p><b>压缩是刻意的。</b>真实世界里最稀有的矿比最常见的稀 10⁶ 量级；
 * 这里把「出现概率 × 丰度」的乘积压到约 {@value #RARITY_SPREAD} 倍（改前约 3000 倍），
 * 稀有矿物因此落进可玩区间。地质上不真实的地方在每一档的注释里写明 ——
 * 宁可标签上写「游戏修正」，也不要假装它是真实品位。
 *
 * <h2>真实世界 → 档位 映射表</h2>
 * <table border="1">
 *   <tr><th>轴</th><th>档</th><th>取值</th><th>真实世界对应</th></tr>
 *   <tr><td rowspan="5">{@link Spawn}</td><td>S1</td><td>0.40</td><td>常见成矿：石英脉、自然硫、石墨、磷灰石、铁锰氧化物</td></tr>
 *   <tr><td>S2</td><td>0.28</td><td>主要经济矿：铅锌、铜、铬、镍红土</td></tr>
 *   <tr><td>S3</td><td>0.18</td><td>中等：黝铜矿、白铅矿、镍黄铁矿、稀土副矿物</td></tr>
 *   <tr><td>S4</td><td>0.11</td><td>稀少：锡石、黑钨矿、辉砷钴矿、磷钇矿</td></tr>
 *   <tr><td>S5</td><td>0.06</td><td>极稀有：辉钼矿、辉铋矿、自然金、自然银、钻石</td></tr>
 *   <tr><td rowspan="5">{@link Grade}</td><td>G1</td><td>0.24~0.36</td><td>真实品位 ≥20%</td></tr>
 *   <tr><td>G2</td><td>0.13~0.20</td><td>真实品位 8%~20%</td></tr>
 *   <tr><td>G3</td><td>0.070~0.110</td><td>真实品位 3%~8%</td></tr>
 *   <tr><td>G4</td><td>0.045~0.070</td><td>真实品位 1%~3%（含贵金属的<b>上调修正</b>）</td></tr>
 *   <tr><td>G5</td><td>0.028~0.045</td><td>真实品位 <1%（含贵金属 / 钻石的<b>上调修正</b>）</td></tr>
 *   <tr><td rowspan="5">{@link Body}</td><td>B1</td><td>×0.50</td><td>目标矿量最小的一档：把原本靠 fit 截断的超大矿体收小</td></tr>
 *   <tr><td>B2</td><td>×0.70</td><td>偏小</td></tr>
 *   <tr><td>B3</td><td>×1.00</td><td>保持该矿原有的 extent（基准档）</td></tr>
 *   <tr><td>B4</td><td>×1.45</td><td>偏大</td></tr>
 *   <tr><td>B5</td><td>×2.05</td><td>最大：给原本体积太小、采不到东西的脉状 / 筒状矿体扩容</td></tr>
 * </table>
 *
 * <p>改前 / 改后的每矿期望产出一览见 {@code docs/科技线-v1.md} 第十九节。
 */
public final class OreTier {

	private OreTier() {
	}

	/**
	 * 改后「最稀有 ÷ 最常见」的稀有度指数（= 出现概率 × 中心丰度中值）之比。
	 *
	 * <p>= S1×G1中值 ÷ S5×G5中值 = (0.40×0.30) ÷ (0.06×0.0365) ≈ {@value #RARITY_SPREAD}。
	 * 改前这个比值约 3000（所有矿物共用 0.25~0.40 的出现概率，只剩丰度的 4.5×10⁴ 跨度）。
	 * 这是本轮要压的那个数。
	 */
	public static final double RARITY_SPREAD = 0.40 * 0.30 / (0.06 * 0.0365);

	/** 出现概率：一个候选矿体单元里长出这种矿物的概率。 */
	public enum Spawn {

		/** 极常见。 */
		S1(0.40),
		/** 常见。 */
		S2(0.28),
		/** 中等。 */
		S3(0.18),
		/** 稀少。 */
		S4(0.11),
		/** 极稀有。 */
		S5(0.06);

		private final double chance;

		Spawn(double chance) {
			this.chance = chance;
		}

		/** 单位置出现概率（0~1）。 */
		public double chance() {
			return chance;
		}
	}

	/** 矿体中心丰度：该矿物在矿体中心处占的体积分数。 */
	public enum Grade {

		/** 真实品位 ≥20%。 */
		G1(0.24, 0.36),
		/** 真实品位 8%~20%。 */
		G2(0.13, 0.20),
		/** 真实品位 3%~8%。 */
		G3(0.070, 0.110),
		/** 真实品位 1%~3%；贵金属上调。 */
		G4(0.045, 0.070),
		/** 真实品位 <1%；贵金属 / 钻石上调。 */
		G5(0.028, 0.045);

		private final double min;
		private final double max;

		Grade(double min, double max) {
			this.min = min;
			this.max = max;
		}

		public double min() {
			return min;
		}

		public double max() {
			return max;
		}

		/** 中值，供文档 / 估算用。 */
		public double mid() {
			return (min + max) / 2.0;
		}

		/** 在档内均匀掷一个中心丰度。 */
		public double pick(RandomSource random) {
			return min + random.nextDouble() * (max - min);
		}
	}

	/** 矿体规模：延伸（extent）相对该矿真实写值的倍率。 */
	public enum Body {

		B1(0.50),
		B2(0.70),
		B3(1.00),
		B4(1.45),
		B5(2.05);

		private final double extentScale;

		Body(double extentScale) {
			this.extentScale = extentScale;
		}

		/** 矿体延伸的倍率。 */
		public double extentScale() {
			return extentScale;
		}
	}
}
