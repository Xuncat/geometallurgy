package com.geometallurgy.vein;

import java.util.ArrayList;
import java.util.List;

import com.geometallurgy.geology.GeologySampler;
import com.geometallurgy.geology.RockType;
import com.geometallurgy.proto.OreMineral;
import com.geometallurgy.proto.VeinAttributes;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/**
 * 某一个方块位置上的虚拟矿脉。
 *
 * <p>现在它只是 {@link OreBody} 在这一点上的切片：矿体给出「这里有哪几种矿物、每种离中心多远」，
 * 本类把距离翻成丰度、再补上成分（品位 / 杂质 / 伴生 / 脉石），得到那一刻、那一格的完整描述。
 *
 * <p>所以同一台机器摆在矿体中心还是外沿，看到的是<b>同一种矿物、不同的丰度</b> ——
 * 这正是「中心位置丰度高、外围位置丰度低」落在方块上的样子。
 *
 * <p>整条链路是 {@code (种子, 坐标, epoch)} 的纯函数，不入存档 —— 除非机器主动把它写下来。
 */
public final class VeinDeposit {

	/**
	 * 一个矿体里所有矿物的丰度之和的上限。
	 *
	 * <p>几种矿物的丰度是<b>共享</b>同一块空间的，不能各自独立叠上去 ——
	 * 不然一个同时含赤铁矿（峰值 40%）、萤石（40%）、自然硫（30%）的矿体会加起来 110%，
	 * 变成「整块岩体都是矿」，既不符合实际，也违背「矿脉里大部分是原岩」。
	 *
	 * <p>取 50%：单矿物的矿体不受影响（最高的赤铁矿峰值 45% 也在限内，仍能长到标称值），
	 * 而多矿物共生的富矿中心则按比例压缩 —— 于是矿体中心最富的地方，原岩也至少占一半，
	 * 离开中心之后更是迅速回到九成以上。
	 */
	public static final double MAX_TOTAL_ABUNDANCE = 0.50;

	private final RockType rock;
	private final long epoch;
	private final int cellX;
	private final int cellY;
	private final int cellZ;
	private final List<VeinAttributes> veins;

	private VeinDeposit(RockType rock, long epoch, int cellX, int cellY, int cellZ,
			List<VeinAttributes> veins) {
		this.rock = rock;
		this.epoch = epoch;
		this.cellX = cellX;
		this.cellY = cellY;
		this.cellZ = cellZ;
		this.veins = veins;
	}

	/** 从存档里恢复一条矿脉（单元信息在存档里没有意义，给 0）。 */
	public static VeinDeposit of(RockType rock, long epoch, List<VeinAttributes> veins) {
		return new VeinDeposit(rock, epoch, 0, 0, 0, List.copyOf(veins));
	}

	public RockType rock() {
		return rock;
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

	public List<VeinAttributes> veins() {
		return veins;
	}

	/** 这个位置一种矿物都没有 —— 就是一块纯原岩。 */
	public boolean isEmpty() {
		return veins.isEmpty();
	}

	public static VeinDeposit at(ServerLevel level, BlockPos pos) {
		// 纪元必须按矿体单元取（单元起始区块），与 Prospector 用的是同一个基准；
		// 用 globalEpoch 会让机器落在另一个纪元的矿体上（bug#45）。
		return at(level.getSeed(), pos.getX(), pos.getY(), pos.getZ(),
				EpochClock.epochOfCell(level, Math.floorDiv(pos.getX(), OreBody.CELL_X),
						Math.floorDiv(pos.getZ(), OreBody.CELL_Z)));
	}

	public static VeinDeposit at(long seed, int blockX, int blockY, int blockZ, long epoch) {
		RockType rock = GeologySampler.rockAt(seed, blockX, blockY, blockZ, epoch);
		OreBody body = OreBody.of(seed, blockX, blockY, blockZ, epoch);

		List<VeinAttributes> veins = new ArrayList<>();
		for (OreMineral mineral : body.minerals()) {
			double abundance = body.abundanceAt(mineral, blockX, blockY, blockZ);
			if (abundance <= 0.0) {
				// 落在矿体之外：这一格只有原岩
				continue;
			}
			// 成分随机源挂在矿体单元上，于是整个矿体内的同一种矿物成分一致 ——
			// 机器摆在中心还是外沿，差别只在丰度
			RandomSource random = RandomSource.create(mineral.attributeSeed(body.cellSeed()));
			veins.add(VeinAttributes.generate(random, mineral, rock, abundance));
		}

		return new VeinDeposit(rock, epoch, body.cellX(), body.cellY(), body.cellZ(),
				capAbundance(veins));
	}

	/** 把总丰度压回上限。 */
	private static List<VeinAttributes> capAbundance(List<VeinAttributes> veins) {
		double total = 0.0;
		for (VeinAttributes vein : veins) {
			total += vein.abundance();
		}
		if (total <= MAX_TOTAL_ABUNDANCE || total <= 0.0) {
			return List.copyOf(veins);
		}
		double scale = MAX_TOTAL_ABUNDANCE / total;
		List<VeinAttributes> capped = new ArrayList<>(veins.size());
		for (VeinAttributes vein : veins) {
			capped.add(vein.withAbundance(vein.abundance() * scale));
		}
		return List.copyOf(capped);
	}

	/**
	 * 掷一次开采产出：按各矿物的丰度加权选出一种。
	 *
	 * <p>用丰度而不是等概率，于是「靠近中心的矿物出得多、外沿的出得少」在产出侧也成立。
	 * 一种矿物都没有时返回 null，由调用方决定怎么处理纯原岩。
	 */
	public VeinAttributes roll(RandomSource random) {
		if (veins.isEmpty()) {
			return null;
		}
		double total = 0.0;
		for (VeinAttributes vein : veins) {
			total += vein.abundance();
		}
		if (total <= 0.0) {
			return veins.get(random.nextInt(veins.size()));
		}
		double pick = random.nextDouble() * total;
		double accumulated = 0.0;
		for (VeinAttributes vein : veins) {
			accumulated += vein.abundance();
			if (pick < accumulated) {
				return vein;
			}
		}
		return veins.get(veins.size() - 1);
	}
}
