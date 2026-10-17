package com.geometallurgy.chemical;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * 「能直接按 mol 存料」的方块（储罐）抽象。
 *
 * <p>机器与储罐之间按面直接过户 mol，不必经过物品桶 —— 物品桶只负责玩家手动搬运。
 * 反应釜 / 搅拌器的 {@code pullFaces} 会先看邻面有没有 {@link MolTank}，没有才退回
 * 「从相邻容器的物品桶里倒」这条老路。
 *
 * <p>实现方自己负责：按 mol 钳制、材质耐蚀（{@link #holds}）、改完标脏与同步。
 * 单位一律 mol，界面才换算成 B / 桶。
 */
public interface MolTank {

	/** 当前 mol 账（只读视图；实现方持有真实账本）。 */
	MolStock tank();

	/** 容量（mol）。 */
	double capacity();

	/** 还能装多少 mol。 */
	default double room() {
		return Math.max(0.0, capacity() - tank().totalMol());
	}

	/**
	 * 收料：最多收 {@code maxMol}。
	 *
	 * @return 实际收下的账（调用方按它扣来源；空账表示一点没进去）
	 */
	MolStock pour(MolStock amount, double maxMol);

	/**
	 * 抽料：最多抽 {@code maxMol}；{@code phase} 非空时只抽该相态的成分。
	 *
	 * @return 实际抽出的账
	 */
	MolStock drain(double maxMol, Chemical.Phase phase);

	/** 这个罐的材质装得住这本账吗（耐蚀判定）。 */
	default boolean holds(MolStock stock) {
		return true;
	}

	/** 邻面的储罐；不是储罐返回 {@code null}。 */
	static MolTank neighbor(Level level, BlockPos pos, Direction side) {
		return level.getBlockEntity(pos.relative(side)) instanceof MolTank tank ? tank : null;
	}
}
