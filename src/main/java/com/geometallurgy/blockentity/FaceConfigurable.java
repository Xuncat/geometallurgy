package com.geometallurgy.blockentity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 「六个面各自接什么」可以整体读写、也可以被整份搬走的机器。
 *
 * <p>目前四台机器实现了它：粉碎机、离心机、冶金炉走 {@link PortRole}，
 * 磁力筛机走 {@link FaceRole}。两套枚举取值不同，但对外的三个动作是一样的 ——
 * 打包、换上一份、全关。工程配置器因此不必认识任何一种枚举，
 * 只管把一份 {@code int} 从一台机器搬到另一台。
 *
 * <h2>类型为什么必须单独带</h2>
 * 见 {@link FaceConfig}。一句话：两套枚举里同一个序号的含义不同，
 * 搬错类型不会报错，只会得到一台全错的机器。所以 {@link #faceConfigKind()} 是这套接口
 * 里唯一不能省的东西 —— 它把「哪台机器的配置」这件事和「六个面分别是什么」分开携带。
 */
public interface FaceConfigurable {

	/**
	 * 机器类型标识，取方块的注册名。
	 *
	 * <p>用方块的注册名而不是方块实体类型，是因为这个标识要回答的是玩家嘴里那句
	 * 「同名机器」—— 玩家看的是方块，同一台机器换一个方块实体类型是实现细节。
	 */
	Identifier faceConfigKind();

	/** 把当前六个面打包成一个 int（见 {@link FaceConfig}）。 */
	int packFaces();

	/**
	 * 换上一份打包好的六面配置，并落盘。
	 *
	 * <p>调用方<b>必须</b>先确认 {@code packFaces()} 的来源与本机 {@link #faceConfigKind()} 相同。
	 */
	void applyFaces(int packed);

	/** 六个面全部关闭 —— 出厂状态，也是配置器上的「初始化」。 */
	void clearFaces();

	/** 方块的注册名。四台机器共用这一行，写在接口里省得各写一遍。 */
	static Identifier kindOf(BlockEntity entity) {
		return BuiltInRegistries.BLOCK.getKey(entity.getBlockState().getBlock());
	}
}
