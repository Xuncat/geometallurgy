package com.geometallurgy.prospect;

import com.geometallurgy.geology.RockType;
import com.geometallurgy.proto.OreMineral;
import com.geometallurgy.vein.EpochClock;
import com.geometallurgy.vein.OreBody;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * 矿物探测仪的扫描核心。
 *
 * <p>探测仪不「看」方块 —— 它直接把 {@link OreBody} 的生成函数反算一遍：
 * 以玩家为中心铺一圈矿体单元，逐个单元重放「世界种子 + 单元坐标 + 纪元」，
 * 看哪个单元里长了目标矿物，再取它的矿体中心。
 *
 * <p>这样探到的位置与机器真正开采到的位置<b>必然一致</b>：两边跑的是同一个纯函数。
 *
 * <p>成本是 17×17×6 = 1734 次单元重放，单次在微秒量级，一次长按的开销可以忽略。
 */
public final class Prospector {

	/** 水平搜索半径（矿体单元数）。{@value #CELL_RADIUS} × {@link OreBody#CELL_X} 格。 */
	public static final int CELL_RADIUS = 8;

	/** 垂直搜索的矿体层范围（单元号）。-2 ~ 3 覆盖 y ≈ -64 ~ 128。 */
	public static final int LAYER_MIN = -2;
	public static final int LAYER_MAX = 3;

	private Prospector() {
	}

	/**
	 * 一次探测的结果。
	 *
	 * @param mineral    目标矿物
	 * @param rock       矿体所在的母岩
	 * @param x          矿体中心 x（方块，含小数）
	 * @param y          矿体中心 y
	 * @param z          矿体中心 z
	 * @param horizontal 水平距离
	 * @param vertical   高差（正数表示在上方）
	 * @param abundance  矿体中心处该矿物的丰度
	 * @param epoch      该矿体单元所处的纪元
	 * @param ticksUntilReroll 距离该单元下一次换脉还有多少刻（虚拟日历）
	 */
	public record Reading(OreMineral mineral, RockType rock,
			double x, double y, double z,
			double horizontal, double vertical, double abundance, long epoch,
			long ticksUntilReroll) {

		/** 直线距离。 */
		public double distance() {
			return Math.sqrt(this.horizontal * this.horizontal + this.vertical * this.vertical);
		}

		/**
		 * 十六向罗盘方位角，正北为 0°、顺时针增大。
		 *
		 * <p>游戏坐标里 -Z 是北、+X 是东，所以方位角是 {@code atan2(dx, -dz)}。
		 */
		public double bearing(double fromX, double fromZ) {
			double degrees = Math.toDegrees(Math.atan2(this.x - fromX, -(this.z - fromZ)));
			return (degrees + 360.0) % 360.0;
		}

		/** 八方位名（北 / 东北 / 东 …）的翻译键后缀。 */
		public static String compassKey(double bearing) {
			String[] keys = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};
			int index = (int) Math.round(bearing / 45.0) % 8;
			return keys[index];
		}
	}

	/**
	 * 找出离玩家最近的一处目标矿体的中心。
	 *
	 * <p>找不到就是找不到 —— 不返回「最近的别的矿」，那样只会误导玩家。
	 */
	@Nullable
	public static Reading findNearest(ServerLevel level, BlockPos origin, OreMineral target) {
		return findNearest(level.getSeed(), level.getGameTime(),
				origin.getX(), origin.getY(), origin.getZ(), target);
	}

	/**
	 * 纯函数版本：探测的全部输入就是「世界种子 + 时刻 + 位置 + 目标矿物」。
	 *
	 * <p>脱离服务端也能算，所以数值探针可以离线核对它。
	 */
	@Nullable
	public static Reading findNearest(long worldSeed, long gameTime, int blockX, int blockY, int blockZ,
			OreMineral target) {
		int cellX = OreBody.CELL_X;
		int cellY = OreBody.CELL_Y;
		int cellZ = OreBody.CELL_Z;

		int originCellX = Math.floorDiv(blockX, cellX);
		int originCellZ = Math.floorDiv(blockZ, cellZ);

		Reading best = null;
		double bestDistance = Double.MAX_VALUE;

		for (int cx = originCellX - CELL_RADIUS; cx <= originCellX + CELL_RADIUS; cx++) {
			for (int cz = originCellZ - CELL_RADIUS; cz <= originCellZ + CELL_RADIUS; cz++) {
				// 纪元按矿体单元算（单元起始区块）—— 与机器的 VeinDeposit.at 同一个基准
				long epoch = EpochClock.epochOfCell(gameTime, cx, cz);
				for (int cy = LAYER_MIN; cy <= LAYER_MAX; cy++) {
					OreBody body = OreBody.ofCell(worldSeed, cx, cy, cz, epoch);
					if (!body.minerals().contains(target)) {
						continue;
					}
					double[] center = body.centerOf(target);
					if (center == null) {
						continue;
					}
					double dx = center[0] - blockX;
					double dy = center[1] - blockY;
					double dz = center[2] - blockZ;
					double horizontal = Math.hypot(dx, dz);
					double distance = Math.sqrt(horizontal * horizontal + dy * dy);
					if (distance < bestDistance) {
						bestDistance = distance;
						best = new Reading(target, body.host(), center[0], center[1], center[2],
								horizontal, dy, body.peakOf(target), epoch,
								EpochClock.ticksUntilNextCell(gameTime, cx, cz));
					}
				}
			}
		}
		return best;
	}

	/** 水平搜索半径的方块数。 */
	public static int radiusInBlocks() {
		return CELL_RADIUS * OreBody.CELL_X;
	}
}
