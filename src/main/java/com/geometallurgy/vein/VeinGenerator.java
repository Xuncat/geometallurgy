package com.geometallurgy.vein;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/**
 * 区块级虚拟矿脉的确定性生成。
 *
 * <p>分片不写入存档：由「世界种子 + 区块坐标」哈希出一个随机源，再从 8 种矿物里
 * 抽出 1~4 种（不重复）、给每种抽一个稀有度、给整个区块抽一个丰度。
 * 因此同一个世界、同一个区块永远得到同一份分布，换个世界就会变。
 */
public final class VeinGenerator {

	private VeinGenerator() {
	}

	/** 一个区块至少含有几种矿物。 */
	public static final int MIN_TYPES = 1;
	/** 一个区块至多含有几种矿物。 */
	public static final int MAX_TYPES = 4;
	/** 丰度下限。 */
	public static final float MIN_ABUNDANCE = 0.10F;
	/** 丰度上限（1.0 = 完全不产生圆石）。 */
	public static final float MAX_ABUNDANCE = 1.00F;

	private static final long SALT = 0x2545F4914F6CDD1DL;

	public static BlockVeinDistribution forPosition(ServerLevel level, BlockPos pos) {
		return forChunk(level, pos.getX() >> 4, pos.getZ() >> 4);
	}

	public static BlockVeinDistribution forChunk(ServerLevel level, int chunkX, int chunkZ) {
		RandomSource random = RandomSource.create(mix(level.getSeed() ^ SALT, chunkX, chunkZ));

		int typeCount = MIN_TYPES + random.nextInt(MAX_TYPES - MIN_TYPES + 1);

		List<OreType> pool = new ArrayList<>(List.of(OreType.values()));
		List<BlockVeinDistribution.Entry> entries = new ArrayList<>(typeCount);
		for (int i = 0; i < typeCount && !pool.isEmpty(); i++) {
			OreType type = pool.remove(random.nextInt(pool.size()));
			float rarity = type.minRarity() + random.nextFloat() * (type.maxRarity() - type.minRarity());
			entries.add(new BlockVeinDistribution.Entry(type, rarity));
		}

		float abundance = MIN_ABUNDANCE + random.nextFloat() * (MAX_ABUNDANCE - MIN_ABUNDANCE);
		return new BlockVeinDistribution(List.copyOf(entries), abundance);
	}

	private static long mix(long seed, long a, long b) {
		long x = seed;
		x = mix64(x + a * 0x9E3779B97F4A7C15L);
		x = mix64(x + b * 0xC2B2AE3D27D4EB4FL);
		return x;
	}

	private static long mix64(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}
}
