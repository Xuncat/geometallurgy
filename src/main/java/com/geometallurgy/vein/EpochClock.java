package com.geometallurgy.vein;

import net.minecraft.server.level.ServerLevel;

/**
 * 矿脉纪元时钟。
 *
 * <p>虚拟矿脉不是一成不变的：每过约 10 个游戏日，同一个位置的矿物组合会重新掷一次。
 * 「约」体现在两点：
 * <ul>
 *   <li>刻数是硬性的 {@value #TICKS_PER_DAY} 刻/天 × {@value #DAYS_PER_EPOCH} 天；</li>
 *   <li>但每个区块带一个 0 ~ 一个周期的确定性偏移，所以不同区块的换脉时刻互相错开，
 *       玩家不会在地图上看到一条整齐的「换脉线」。</li>
 * </ul>
 *
 * <p>纪元号同时被岩体场（{@link com.geometallurgy.geology.GeologySampler}）当作时间维度使用，
 * 于是原岩与矿脉是同步演化的。
 */
public final class EpochClock {

	private EpochClock() {
	}

	/** 一个游戏日的刻数。 */
	public static final long TICKS_PER_DAY = 24000L;
	/** 一个纪元包含多少游戏日。 */
	public static final long DAYS_PER_EPOCH = 10L;
	/** 一个纪元的刻数 = 240000 刻 ≈ 现实 3.3 小时（只在有玩家在线时推进）。 */
	public static final long TICKS_PER_EPOCH = TICKS_PER_DAY * DAYS_PER_EPOCH;

	/**
	 * 全局纪元号（不考虑区块偏移）—— 只是「世界日历」读数。
	 *
	 * <p><b>不要用它生成矿脉</b>：那会让矿脉链路两边差一个纪元（bug#45）。矿脉与岩体场都
	 * 必须用 {@link #epochOfCell}。
	 */
	public static long globalEpoch(ServerLevel level) {
		return Math.floorDiv(level.getGameTime(), TICKS_PER_EPOCH);
	}

	/** 距离下一次换脉还有多少刻（区块偏移版本）。 */
	public static long ticksUntilNext(ServerLevel level, int chunkX, int chunkZ) {
		long shifted = level.getGameTime() + offset(chunkX, chunkZ);
		return TICKS_PER_EPOCH - Math.floorMod(shifted, TICKS_PER_EPOCH);
	}

	/** 某个区块当前所处的纪元号。 */
	public static long epoch(ServerLevel level, int chunkX, int chunkZ) {
		return epoch(level.getGameTime(), chunkX, chunkZ);
	}

	/**
	 * 某个<b>矿体单元</b>（{@link OreBody} 的 32 格单元）当前所处的纪元号。
	 *
	 * <p><b>这是矿脉链路的权威基准</b>：探测仪、挖掘机、岩体场都必须用这一个。
	 * 单元是 32 格、横跨两个区块，而 {@link #offset} 是按 16 格区块算的 ——
	 * 若让单元里每个方块各按自己所在区块取纪元，同一个单元的两半会长出两个不同的矿体。
	 * 所以这里把单元固定映射到它的<b>起始区块</b>，一个单元只认一个纪元。
	 */
	public static long epochOfCell(ServerLevel level, int cellX, int cellZ) {
		return epochOfCell(level.getGameTime(), cellX, cellZ);
	}

	/** 离线版本，给数值探针 / 纯函数调用。 */
	public static long epochOfCell(long gameTime, int cellX, int cellZ) {
		return epoch(gameTime, cellChunk(cellX, OreBody.CELL_X), cellChunk(cellZ, OreBody.CELL_Z));
	}

	/** 距离某个矿体单元下一次换脉还有多少刻。 */
	public static long ticksUntilNextCell(long gameTime, int cellX, int cellZ) {
		long shifted = gameTime
				+ offset(cellChunk(cellX, OreBody.CELL_X), cellChunk(cellZ, OreBody.CELL_Z));
		return TICKS_PER_EPOCH - Math.floorMod(shifted, TICKS_PER_EPOCH);
	}

	/** 矿体单元坐标 → 它起始区块的区块坐标。 */
	private static int cellChunk(int cell, int cellSize) {
		return Math.floorDiv(cell * cellSize, 16);
	}

	/**
	 * 某个区块在给定时刻所处的纪元号。
	 *
	 * <p>不带 {@code Level} 的版本是给离线计算用的（数值探针、将来的地图工具）：
	 * 矿脉的生成本来就是 {@code (世界种子, 坐标, 时刻)} 的纯函数，不该被一个
	 * {@code ServerLevel} 挡住。
	 */
	public static long epoch(long gameTime, int chunkX, int chunkZ) {
		return Math.floorDiv(gameTime + offset(chunkX, chunkZ), TICKS_PER_EPOCH);
	}

	/** 纪元内的进度 0~1。 */
	public static float progress(ServerLevel level, int chunkX, int chunkZ) {
		long shifted = level.getGameTime() + offset(chunkX, chunkZ);
		return (float) Math.floorMod(shifted, TICKS_PER_EPOCH) / (float) TICKS_PER_EPOCH;
	}

	/** 区块级确定性偏移，取值 [0, 一个周期)。 */
	private static long offset(int chunkX, int chunkZ) {
		long h = 0x9E3779B97F4A7C15L;
		h ^= (long) chunkX * 0xC2B2AE3D27D4EB4FL;
		h ^= (long) chunkZ * 0x165667B19E3779F9L;
		h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
		h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
		h ^= (h >>> 31);
		return Math.floorMod(h, TICKS_PER_EPOCH);
	}
}
