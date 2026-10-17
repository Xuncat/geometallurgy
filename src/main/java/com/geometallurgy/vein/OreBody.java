package com.geometallurgy.vein;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.geometallurgy.geology.GeologySampler;
import com.geometallurgy.geology.RockType;
import com.geometallurgy.proto.DepositType;
import com.geometallurgy.proto.OreBodyShape;
import com.geometallurgy.proto.OreMineral;

import net.minecraft.util.RandomSource;

/**
 * 一个矿体（ore body）。
 *
 * <p>矿脉不再是「每个方块各掷各的」，而是一个<b>有中心、有外沿的几何体</b>：
 * 世界被切成 {@value #CELL_X}×{@value #CELL_Y}×{@value #CELL_Z} 的单元
 * —— 这个尺寸不是随便取的，它<b>正好等于岩体场的单元</b>（{@link GeologySampler#UNIT_BLOCKS}
 * × {@link GeologySampler#LAYER_HEIGHT}），所以一个矿体必然落在同一种原岩里，
 * 「什么岩体出什么矿」这条规则不會被矿体的边界切坏。
 *
 * <p>每个单元至多长出一个矿体，流程是：
 * <ol>
 *   <li>取单元中心的原岩当<b>母岩</b>，由它给出候选矿物表；</li>
 *   <li>每一种候选矿物<b>各自独立</b>掷一次骰子，概率由它的
 *       {@link com.geometallurgy.proto.OreTier.Spawn 出现概率档}给出 —— 所以一个矿体里有几种
 *       矿物是随机的，而常见矿与稀有矿的出现频率<b>不再相同</b>；</li>
 *   <li>出现的每一种矿物，按自己的 {@link OreBodyShape 形状}、规模与方位长成一块矿体几何；
 *       同一种矿物在矿体中心的丰度取自它的真实矿床品位区间（见 {@link DepositType}）；</li>
 *   <li>离开中心越远，实际丰度按形状的衰减指数往下掉；出了形状边界就是零 —— 那里只有原岩。</li>
 * </ol>
 *
 * <p>还要叠一层<b>方块级的散点扰动</b>（±{@value #SPECKLE_PERCENT}%）：相邻方块的丰度不会一模一样，
 * 看上去才像浸染在岩石里的矿，而不是一块渐变玻璃。
 *
 * <p>整条链路是 {@code (世界种子, 单元坐标, epoch)} 的纯函数，不写存档。
 */
public final class OreBody {

	/**
	 * 矿体单元的水平边长。
	 *
	 * <p>取 32 而不是岩体单元的 64：一个岩体单元（64×32×64）里放得下 2×2 = 4 个矿体单元，
	 * 于是矿体的<b>数量</b>多了四倍、单个规模小一半。两者都仍然完全落在同一个岩体单元里
	 * （32 的整数倍区间必然被 64 的整数倍区间包含），所以「矿体不会跨越两种原岩」这条不变。
	 *
	 * <p>为什么不干脆用 64：一个矿体至多占满自己那个单元，用 64 的话矿体会稀到
	 * 只有约 6% 的地下空间含矿，机器摆哪儿都是啃石头。用 32 之后约 12%，
	 * 玩家的钻机随手一放就有六成把握踩在矿体边缘上，走几步就能找到中心。
	 */
	public static final int CELL_X = 32;
	/** 矿体单元的高度。与岩体分层厚度一致，所以垂直方向不会再切。 */
	public static final int CELL_Y = GeologySampler.LAYER_HEIGHT;
	/** 矿体单元的进深。 */
	public static final int CELL_Z = 32;

	// 出现概率不再是全体共用的 0.25~0.40 常数，而是每种矿物自己的 OreTier.Spawn 档。
	// 这是本轮 ore balance 的关键：出现频率与矿体丰度彻底解耦（见 OreTier）。
	/** 方块级散点扰动的幅度（百分比）。 */
	public static final int SPECKLE_PERCENT = 20;
	/** 矿体在任一方向上的最小半轴（方块）。 */
	public static final double MIN_HALF_AXIS = 1.5;

	/**
	 * 一种矿物的矿体几何。
	 *
	 * <p>(u, v, w) 是一组正交标架：u 是主轴（走向），v 是副轴，w 是厚度方向。
	 * 方块相对中心的位移投影到这三个轴上，除以各自的半轴 (a, b, c)，就得到归一化距离 q。
	 */
	private record Shape(
			double cx, double cy, double cz,
			double ux, double uy, double uz,
			double vx, double vy, double vz,
			double wx, double wy, double wz,
			double a, double b, double c,
			boolean cylinder,
			double falloff,
			double peak) {

		/** 归一化距离：0 = 中心，1 = 边界，>1 = 在外面。 */
		double distance(int x, int y, int z) {
			double dx = x + 0.5 - cx;
			double dy = y + 0.5 - cy;
			double dz = z + 0.5 - cz;
			double p = dx * ux + dy * uy + dz * uz;
			double q = dx * vx + dy * vy + dz * vz;
			double r = dx * wx + dy * wy + dz * wz;
			if (cylinder) {
				return Math.max(Math.hypot(p, q) / Math.max(a, 1.0e-9), Math.abs(r) / Math.max(c, 1.0e-9));
			}
			double na = p / Math.max(a, 1.0e-9);
			double nb = q / Math.max(b, 1.0e-9);
			double nc = r / Math.max(c, 1.0e-9);
			return Math.sqrt(na * na + nb * nb + nc * nc);
		}

		/** 该方块的丰度（体积分数）。出了边界返回 0。 */
		double abundanceAt(long speckleSeed, int x, int y, int z) {
			double q = distance(x, y, z);
			if (q >= 1.0) {
				return 0.0;
			}
			// 富矿核内不打折；出了核按形状的指数衰减到边界
			double grade = q <= OreBodyShape.CORE_RADIUS
					? 1.0
					: Math.pow((1.0 - q) / (1.0 - OreBodyShape.CORE_RADIUS), falloff);
			return peak * grade * speckle(speckleSeed, x, y, z);
		}
	}

	private final long worldSeed;
	private final long epoch;
	private final long cellSeed;
	private final int cellX;
	private final int cellY;
	private final int cellZ;
	private final RockType host;
	private final List<OreMineral> minerals;
	private final Map<OreMineral, Shape> shapes;

	private OreBody(long worldSeed, long epoch, long cellSeed, int cellX, int cellY, int cellZ,
			RockType host, List<OreMineral> minerals, Map<OreMineral, Shape> shapes) {
		this.worldSeed = worldSeed;
		this.epoch = epoch;
		this.cellSeed = cellSeed;
		this.cellX = cellX;
		this.cellY = cellY;
		this.cellZ = cellZ;
		this.host = host;
		this.minerals = List.copyOf(minerals);
		this.shapes = Map.copyOf(shapes);
	}

	// ------------------------------------------------------------------ 生成

	public static OreBody of(long worldSeed, int blockX, int blockY, int blockZ, long epoch) {
		int cx = Math.floorDiv(blockX, CELL_X);
		int cy = Math.floorDiv(blockY, CELL_Y);
		int cz = Math.floorDiv(blockZ, CELL_Z);
		return ofCell(worldSeed, cx, cy, cz, epoch);
	}

	public static OreBody ofCell(long worldSeed, int cellX, int cellY, int cellZ, long epoch) {
		long cellSeed = mix(worldSeed, cellX, cellY, cellZ, epoch);
		RandomSource random = RandomSource.create(cellSeed);
		int baseX = cellX * CELL_X;
		int baseY = cellY * CELL_Y;
		int baseZ = cellZ * CELL_Z;

		// 单元中心的原岩就是这个矿体的母岩 —— 整个单元里岩体恒定，不会出现半个矿体跨两种岩
		RockType host = GeologySampler.rockAt(worldSeed, baseX + CELL_X / 2, baseY + CELL_Y / 2,
				baseZ + CELL_Z / 2, epoch);

		List<OreMineral> minerals = new ArrayList<>();
		Map<OreMineral, Shape> shapes = new LinkedHashMap<>();
		for (OreMineral mineral : OreMineral.occurringIn(host)) {
			// 出现概率只由 Spawn 档决定，与丰度、矿体大小无关
			if (random.nextDouble() >= mineral.deposit().spawnChance()) {
				continue;
			}
			// 每种矿物用自己的随机源：这样换个方块位置重算，几何完全一致
			RandomSource mineralRandom = RandomSource.create(mineral.geometrySeed(cellSeed));
			shapes.put(mineral, buildShape(mineralRandom, mineral.deposit(), baseX, baseY, baseZ));
			minerals.add(mineral);
		}
		return new OreBody(worldSeed, epoch, cellSeed, cellX, cellY, cellZ,
				host, minerals, shapes);
	}

	private static Shape buildShape(RandomSource random, DepositType deposit,
			int baseX, int baseY, int baseZ) {
		OreBodyShape shape = deposit.shape();
		// 规模只由 Body 档决定（extent × 档位倍率），与出现概率、丰度无关
		double extent = deposit.scaledExtent() * (0.60 + random.nextDouble() * 0.60);
		// 中心丰度只由 Grade 档决定，与规模、出现概率无关
		double peak = deposit.grade().pick(random);
		double azimuth = random.nextDouble() * Math.PI * 2.0;
		double dip = Math.toRadians(45.0 + random.nextDouble() * 45.0);

		double ux;
		double uy;
		double uz;
		double vx;
		double vy;
		double vz;
		switch (shape.orientation()) {
			case HORIZONTAL -> {
				ux = 1.0;
				uy = 0.0;
				uz = 0.0;
				vx = 0.0;
				vy = 0.0;
				vz = 1.0;
			}
			case STRIKE -> {
				double c = Math.cos(azimuth);
				double s = Math.sin(azimuth);
				ux = c;
				uy = 0.0;
				uz = s;
				vx = -s;
				vy = 0.0;
				vz = c;
			}
			default -> {
				// 走向 + 倾角：v 在矿脉平面内往下倾，w 是薄板的法向
				double c = Math.cos(azimuth);
				double s = Math.sin(azimuth);
				double cp = Math.cos(dip);
				double sp = Math.sin(dip);
				ux = c;
				uy = 0.0;
				uz = s;
				vx = -s * cp;
				vy = sp;
				vz = c * cp;
			}
		}
		// w = u × v（HORIZONTAL / STRIKE 时就是竖直方向）
		double wx = uy * vz - uz * vy;
		double wy = uz * vx - ux * vz;
		double wz = ux * vy - uy * vx;

		double a = extent * shape.uRatio();
		double b = extent * shape.vRatio();
		double c = extent * shape.wRatio();

		// 三个半轴都不小于 1.5 格。矿脉再薄，也得让至少一列方块的格心落进富矿核里 ——
		// 否则玩家在矿脉正中间摆机器也采不到标称品位。
		a = Math.max(a, MIN_HALF_AXIS);
		b = Math.max(b, MIN_HALF_AXIS);
		c = Math.max(c, MIN_HALF_AXIS);

		// 包围盒半长 —— 矿体必须整个待在单元里，否则边界上会出现被切平的硬边
		double hx = Math.abs(ux) * a + Math.abs(vx) * b + Math.abs(wx) * c;
		double hy = Math.abs(uy) * a + Math.abs(vy) * b + Math.abs(wy) * c;
		double hz = Math.abs(uz) * a + Math.abs(vz) * b + Math.abs(wz) * c;
		double limitX = CELL_X * 0.5 - 1.0;
		double limitY = CELL_Y * 0.5 - 1.0;
		double limitZ = CELL_Z * 0.5 - 1.0;
		double fit = Math.min(1.0, Math.min(limitX / Math.max(hx, 1.0e-6),
				Math.min(limitZ / Math.max(hz, 1.0e-6), limitY / Math.max(hy, 1.0e-6))));
		a *= fit;
		b *= fit;
		c *= fit;
		hx *= fit;
		hy *= fit;
		hz *= fit;

		double spanX = Math.max(0.0, CELL_X - 2.0 * hx);
		double spanZ = Math.max(0.0, CELL_Z - 2.0 * hz);
		double cx = baseX + hx + random.nextDouble() * spanX;
		double cz = baseZ + hz + random.nextDouble() * spanZ;
		double cy;
		if (shape == OreBodyShape.PLACER) {
			// 砂矿贴在本层顶部：风化残积、冲积物本来就在上面
			cy = baseY + CELL_Y - hy - random.nextDouble() * 3.0;
		} else {
			double spanY = Math.max(0.0, CELL_Y - 2.0 * hy);
			cy = baseY + hy + random.nextDouble() * spanY;
		}

		return new Shape(cx, cy, cz,
				ux, uy, uz, vx, vy, vz, wx, wy, wz,
				a, b, c, shape.cylinder(), shape.falloff(), peak);
	}

	// ------------------------------------------------------------------ 查询

	public RockType host() {
		return host;
	}

	public long epoch() {
		return epoch;
	}

	public int cellX() {
		return cellX;
	}

	public int cellY() {
		return cellY;
	}

	public int cellZ() {
		return cellZ;
	}

	/** 这个矿体单元里含哪些矿物（可能是空的 —— 那就是一块纯原岩）。 */
	public List<OreMineral> minerals() {
		return minerals;
	}

	public boolean isEmpty() {
		return minerals.isEmpty();
	}

	/** 某个方块的丰度（体积分数）。矿物不在本节里、或该方块在矿体之外，都返回 0。 */
	public double abundanceAt(OreMineral mineral, int blockX, int blockY, int blockZ) {
		Shape shape = shapes.get(mineral);
		if (shape == null) {
			return 0.0;
		}
		return shape.abundanceAt(cellSeed, blockX, blockY, blockZ);
	}

	/** 某个方块到该矿物矿体中心的归一化距离（0 = 中心，1 = 边界，>1 = 外面）。探针用。 */
	public double distanceAt(OreMineral mineral, int blockX, int blockY, int blockZ) {
		Shape shape = shapes.get(mineral);
		return shape == null ? Double.MAX_VALUE : shape.distance(blockX, blockY, blockZ);
	}

	/** 某个矿物矿体在本单元内的中心坐标。探针用。 */
	@Nullable
	public double[] centerOf(OreMineral mineral) {
		Shape shape = shapes.get(mineral);
		return shape == null ? null : new double[] {shape.cx(), shape.cy(), shape.cz()};
	}

	/** 该矿物在本单元的中心丰度（未衰减、未散点）。 */
	public double peakOf(OreMineral mineral) {
		Shape shape = shapes.get(mineral);
		return shape == null ? 0.0 : shape.peak();
	}

	/** 该矿物矿体的三个半轴长。探针用。 */
	@Nullable
	public double[] axesOf(OreMineral mineral) {
		Shape shape = shapes.get(mineral);
		return shape == null ? null : new double[] {shape.a(), shape.b(), shape.c()};
	}

	/** 该矿物矿体的主轴方向（走向）。探针用。 */
	@Nullable
	public double[] directionOf(OreMineral mineral) {
		Shape shape = shapes.get(mineral);
		return shape == null ? null : new double[] {shape.ux(), shape.uy(), shape.uz()};
	}

	/** 整个单元里该矿物的最高丰度（逐格扫）。探针用。 */
	public double maxAbundanceInCell(OreMineral mineral) {
		Shape shape = shapes.get(mineral);
		if (shape == null) {
			return 0.0;
		}
		int baseX = cellX * CELL_X;
		int baseY = cellY * CELL_Y;
		int baseZ = cellZ * CELL_Z;
		double best = 0.0;
		for (int dy = 0; dy < CELL_Y; dy++) {
			for (int dz = 0; dz < CELL_Z; dz++) {
				for (int dx = 0; dx < CELL_X; dx++) {
					double value = shape.abundanceAt(cellSeed, baseX + dx, baseY + dy, baseZ + dz);
					if (value > best) {
						best = value;
					}
				}
			}
		}
		return best;
	}

	public long cellSeed() {
		return cellSeed;
	}

	public long worldSeed() {
		return worldSeed;
	}

	// ------------------------------------------------------------------ 工具

	/**
	 * 方块级散点扰动。
	 *
	 * <p>真实矿体里的矿物是浸染状的：大趋势是「中心富、外围贫」，但具体到某一立方米，
	 * 富一点贫一点是随机的。没有这一层，矿体会像一块渐变玻璃，一眼假。
	 */
	private static double speckle(long seed, int x, int y, int z) {
		long h = mix(seed, x, y, z, 0L);
		double unit = (h >>> 11) * 0x1.0p-53;
		double amplitude = SPECKLE_PERCENT / 100.0;
		return 1.0 - amplitude + 2.0 * amplitude * unit;
	}

	private static long mix(long seed, long a, long b, long c, long d) {
		long h = seed ^ 0x5DEE2B17A3L;
		h = mix64(h + a * 0x9E3779B97F4A7C15L);
		h = mix64(h + b * 0xC2B2AE3D27D4EB4FL);
		h = mix64(h + c * 0x165667B19E3779F9L);
		h = mix64(h + d * 0x27D4EB2F165667C5L);
		return h;
	}

	private static long mix64(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}
}
