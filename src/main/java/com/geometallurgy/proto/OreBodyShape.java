package com.geometallurgy.proto;

/**
 * 矿体形状。
 *
 * <p>真实矿床很少是一团成分均匀的矿石，它们的几何形态由成矿方式决定。这里取了七种最常见的：
 *
 * <table>
 *   <tr><th>形状</th><th>真实对应</th><th>本模组里用它的矿物</th></tr>
 *   <tr><td>{@link #DISC 扁豆状}</td><td>层状沉积 / 变质矿床，水平铺开的扁透镜</td><td>赤铁矿（条带状铁建造）、磁铁矿、软锰矿、石墨</td></tr>
 *   <tr><td>{@link #BAND 带状}</td><td>层控矿床，沿层面拉长的薄板</td><td>方铅矿、闪锌矿（密西西比河谷型）、重晶石、磷灰石、自然硫</td></tr>
 *   <tr><td>{@link #PIPE 筒状}</td><td>金伯利岩筒、铜镍硫化物岩筒</td><td>钻石、镍黄铁矿</td></tr>
 *   <tr><td>{@link #LODE 脉状}</td><td>沿断裂充填的陡倾斜薄板</td><td>自然金、黑钨矿、石英脉、萤石、菱锰矿……</td></tr>
 *   <tr><td>{@link #POD 透镜状}</td><td>孤立的小矿包</td><td>铬铁矿（豆荚状）、自然铜、白铅矿</td></tr>
 *   <tr><td>{@link #DISSEMINATED 浸染状}</td><td>斑岩型，矿体巨大但品位很低</td><td>黄铜矿、辉钼矿</td></tr>
 *   <tr><td>{@link #PLACER 砂矿}</td><td>风化残积 / 冲积，近地表的水平薄层</td><td>硅镁镍矿（红土镍矿）</td></tr>
 * </table>
 *
 * <p>每个形状给出三样东西：
 * <ol>
 *   <li><b>三个半轴比例</b> —— 相对 {@code extent}（矿体规模）的 u / v / w 半轴。
 *       {@code u} 是主轴（走向），{@code v} 是副轴，{@code w} 是厚度方向。</li>
 *   <li><b>取向方式</b> —— {@link Orientation#HORIZONTAL 水平}、{@link Orientation#STRIKE 绕竖轴转}
 *       （给走向一个随机方位角）、{@link Orientation#DIPPING 再叠一个倾角}（陡倾脉）。</li>
 *   <li><b>品位衰减指数</b> —— 从中心往外怎么掉。指数越大，贫化越快、富矿核越小；
 *       脉状矿（2.4）只有中心一条是富的，浸染状（0.8）则一大片都还算富。</li>
 * </ol>
 *
 * <p>「某个方块离矿体中心有多远」由 {@code com.geometallurgy.vein.OreBody} 用唯一一条公式算：
 * 把方块相对中心的位移投影到 (u, v, w) 三个轴上，除以对应半轴，得到归一化距离
 * {@code q}；{@code q >= 1} 就在矿体之外。中心 {@code q = 0}，丰度最高。
 */
public enum OreBodyShape {

	DISC("disc", Orientation.HORIZONTAL, 1.00, 1.00, 0.32, false, 1.2),
	BAND("band", Orientation.STRIKE, 1.00, 0.34, 0.22, false, 1.3),
	PIPE("pipe", Orientation.HORIZONTAL, 0.30, 0.30, 1.40, true, 1.6),
	LODE("lode", Orientation.DIPPING, 1.00, 0.85, 0.16, false, 1.8),
	POD("pod", Orientation.HORIZONTAL, 0.40, 0.40, 0.38, false, 1.6),
	DISSEMINATED("disseminated", Orientation.HORIZONTAL, 1.00, 0.85, 0.45, false, 0.8),
	PLACER("placer", Orientation.HORIZONTAL, 1.00, 1.00, 0.14, false, 1.4);

	/**
	 * 富矿核的半径（归一化距离）。
	 *
	 * <p>{@code q < 0.30} 的范围内品位不打折，出了这个核才开始衰减。
	 * 真实矿体也这样：中间有一块高品位的核，外面包着慢慢变贫的晕。
	 *
	 * <p>这条同时解决一个纯实现问题：如果让品位从中心点单调掉下去，而矿脉厚度只有一两格，
	 * 那么<b>没有任何一个整数方块的格心会正好落在中心</b>，玩家永远采不到标称品位。
	 * 给一个有限的核之后，「最富的那一格」就真的等于标称品位。
	 */
	public static final double CORE_RADIUS = 0.30;

	/** 矿体在空间里的摆放方式。 */
	public enum Orientation {
		/** 轴对齐：扁豆、筒、透镜、浸染体、砂矿都是水平的。 */
		HORIZONTAL,
		/** 绕竖轴转一个方位角（走向），厚度方向仍是竖直的：带状矿。 */
		STRIKE,
		/** 先定走向、再叠一个倾角：陡倾的脉状矿。 */
		DIPPING
	}

	private final String id;
	private final Orientation orientation;
	private final double uRatio;
	private final double vRatio;
	private final double wRatio;
	private final boolean cylinder;
	private final double falloff;

	OreBodyShape(String id, Orientation orientation,
			double uRatio, double vRatio, double wRatio, boolean cylinder, double falloff) {
		this.id = id;
		this.orientation = orientation;
		this.uRatio = uRatio;
		this.vRatio = vRatio;
		this.wRatio = wRatio;
		this.cylinder = cylinder;
		this.falloff = falloff;
	}

	public String id() {
		return id;
	}

	public Orientation orientation() {
		return orientation;
	}

	/** 主轴（走向）半轴 / extent。 */
	public double uRatio() {
		return uRatio;
	}

	/** 副轴半轴 / extent。 */
	public double vRatio() {
		return vRatio;
	}

	/** 厚度方向半轴 / extent。 */
	public double wRatio() {
		return wRatio;
	}

	/**
	 * 是否用柱面判据而不是椭球判据。
	 *
	 * <p>筒状矿体（金伯利岩筒）上下近乎垂直，椭圆截面会收成纺锤形，不像管子 ——
	 * 对这类形状改用 {@code max(水平距离 / 半径, 竖直距离 / 半高)}。
	 */
	public boolean cylinder() {
		return cylinder;
	}

	/** 品位从中心往外的衰减指数。 */
	public double falloff() {
		return falloff;
	}

	public String translationKey() {
		return "ore_shape.geometallurgy." + id;
	}
}
