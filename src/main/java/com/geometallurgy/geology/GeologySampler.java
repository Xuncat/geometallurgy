package com.geometallurgy.geology;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 岩体场方程。
 *
 * <p>把地下空间理解为一片缓慢流动的岩浆：
 * <ol>
 *   <li>先在「单元坐标」上取一层噪声作为<b>域扭曲</b>（domain warp）——它把原本规整的
 *       噪声坐标揉成一团，于是等值线变成熔岩般的条带与旋涡，而不是斑点；</li>
 *   <li>再用扭曲后的坐标取 fBm 得到主控值 {@code v ∈ [0,1)}，由 {@code v} 与该处的
 *       深度层共同决定岩体族；</li>
 *   <li>最后在该族内部用一层更高频的噪声挑出具体岩石。</li>
 * </ol>
 *
 * <p>时间维度以 {@code epoch} 的形式进入域扭曲的相位：每过一轮（约 10 游戏日），
 * 整个岩体场沿噪声空间漂移一点点，于是同一块地的原岩会缓慢改变——
 * 模拟地质演化，而不是瞬间跳变。
 *
 * <p>一切取值都是 {@code (世界种子, 坐标, epoch)} 的纯函数，不写存档。
 */
public final class GeologySampler {

	private GeologySampler() {
	}

	/** 岩体单元的水平边长（方块）= 4 个区块。 */
	public static final int UNIT_BLOCKS = 64;
	/** 垂直方向的层厚（方块）。 */
	public static final int LAYER_HEIGHT = 32;
	/** 世界最低层的 y 坐标（26.x 主世界）。 */
	public static final int MIN_Y = -64;

	private static final double UNIT_FREQ = 0.28;
	private static final double LAYER_FREQ = 0.75;
	/** 域扭曲振幅（以单元为单位）。越大越像熔岩流，花纹被拉成长条与漩涡。 */
	private static final double WARP_STRENGTH = 2.60;
	/** 每个 epoch 在噪声空间中漂移的距离，控制「演化」的快慢。 */
	private static final double DRIFT_PER_EPOCH = 0.09;

	/** 单元坐标（水平）。 */
	public static int unitX(int blockX) {
		return Math.floorDiv(blockX, UNIT_BLOCKS);
	}

	public static int unitZ(int blockZ) {
		return Math.floorDiv(blockZ, UNIT_BLOCKS);
	}

	/** 垂直分层序号：0 为世界最低的一层。 */
	public static int layerOf(int blockY) {
		return Math.floorDiv(blockY - MIN_Y, LAYER_HEIGHT);
	}

	public static RockType rockAt(ServerLevel level, BlockPos pos, long epoch) {
		return rockAt(level.getSeed(), pos.getX(), pos.getY(), pos.getZ(), epoch);
	}

	public static RockType rockAt(long worldSeed, int blockX, int blockY, int blockZ, long epoch) {
		return rockOfUnit(worldSeed, unitX(blockX), layerOf(blockY), unitZ(blockZ), epoch);
	}

	/**
	 * 探测用途：给出某个水平位置的代表性原岩。
	 *
	 * <p>取的是「地下中层」那一层（y ≈ 32），也就是大多数机器摆放所在的深度带，
	 * 免得玩家站在地表探到的全是沉积岩。
	 */
	public static RockType rockAtColumn(long worldSeed, int blockX, int blockZ, long epoch) {
		return rockOfUnit(worldSeed, unitX(blockX), layerOf(32), unitZ(blockZ), epoch);
	}

	public static RockType rockOfUnit(long worldSeed, int unitX, int layer, int unitZ, long epoch) {
		double nx = unitX * UNIT_FREQ;
		double nz = unitZ * UNIT_FREQ;
		double ny = layer * LAYER_FREQ;
		double drift = epoch * DRIFT_PER_EPOCH;

		// 1) 域扭曲：三个方向的偏移量各自来自一层低频噪声
		double warpX = Noise.signed(worldSeed ^ 0x51A7C3L, nx + drift, ny, nz) * WARP_STRENGTH;
		double warpZ = Noise.signed(worldSeed ^ 0x62B8D4L, nx, ny, nz + drift) * WARP_STRENGTH;
		double warpY = Noise.signed(worldSeed ^ 0x73C9E5L, nx, ny + drift, nz) * 1.20;

		double wx = nx + warpX;
		double wz = nz + warpZ;
		double wy = ny + warpY;

		// 2) 主控场：决定该处偏向哪一族。
		//    这里刻意用「单层」噪声而不是 fBm —— fBm 多次叠加后取值会向 0.5 集中，
		//    四族的阈值区间被压扁，结果整片地下只剩中间那一族。单层值噪声的取值
		//    接近均匀分布，四族才会按预期的比例出现。花纹的流动感由上面的域扭曲提供。
		double v = Noise.value(worldSeed ^ 0x84DAF6L, wx, wy, wz);

		RockType.Family family = familyFor(layer, v);

		// 3) 族内细化：更高频的一层噪声挑出具体岩石
		RockType[] options = family.all();
		double pick = Noise.value(worldSeed ^ 0x95EB07L, wx * 2.7, wy * 2.7, wz * 2.7);
		int index = Math.clamp((int) Math.floor(pick * options.length), 0, options.length - 1);
		return options[index];
	}

	/**
	 * 深度带 × 主控值 → 岩体族。
	 *
	 * <p>不再硬分档，而是把两个因素合成一个连续量 {@code t}：
	 * {@code t = 深度权重 × 1.15 + (主控值 − 0.5) × 0.45}。
	 * 深度权重由层号给出（世界最底为 0，浅处为 1），主控值贡献横向波动，
	 * 于是每一层都可能是四族中的任意一族，只是比例不同 —— 横向上就会出现
	 * 两族交错的流动状边界，而不是一条条平行的「地层线」。
	 *
	 * <p>各层的典型结果：最深处以变质岩为主，往下依次过渡到侵入岩、喷出岩，
	 * 浅处归于沉积岩。
	 */
	private static RockType.Family familyFor(int layer, double v) {
		double depth = Math.clamp(layer / 6.0, 0.0, 1.0);
		double t = Math.clamp(depth * 1.15 + (v - 0.5) * 0.45, 0.0, 1.0);
		if (t < 0.22) {
			return RockType.Family.METAMORPHIC;
		}
		if (t < 0.45) {
			return RockType.Family.PLUTONIC;
		}
		if (t < 0.70) {
			return RockType.Family.VOLCANIC;
		}
		return RockType.Family.SEDIMENTARY;
	}
}
