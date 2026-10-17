package com.geometallurgy.slurry;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 方块实体实现它，向相邻机器摊开自己的矿浆接口。
 *
 * <p>这里刻意没有走 Fabric 的 {@code BlockApiLookup}：本模组内部只有四五种机器，
 * 直接判 {@code instanceof} 已经够用，少一层注册与命名空间约定。
 */
public interface SlurryProvider {

	/**
	 * 取本机在 {@code side} 面上的矿浆接口。
	 *
	 * @param side 由本机指向外部的方向
	 * @return 该面可用的接口；该面不对外（或该面是「关闭」）时返回 null
	 */
	@Nullable
	SlurryHandler slurryAt(Direction side);

	/**
	 * 查 {@code pos} 处方块在 {@code side} 方向上的邻居，返回其朝向本机的那个接口。
	 *
	 * @param side 由 {@code pos} 指向邻居的方向
	 */
	@Nullable
	static SlurryHandler find(Level level, BlockPos pos, Direction side) {
		BlockEntity neighbor = level.getBlockEntity(pos.relative(side));
		if (neighbor instanceof SlurryProvider provider) {
			return provider.slurryAt(side.getOpposite());
		}
		return null;
	}
}
