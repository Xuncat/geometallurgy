package com.geometallurgy.slurry;

/**
 * 一台机器在<b>某一个面</b>上暴露出来的矿浆接口。
 *
 * <p>虚拟矿浆不是原版流体，也不是物品，所以既走不了 {@code FluidStorage} 也走不了
 * 漏斗的 {@code Container} 通道。它是一份纯数据（{@link VirtualSlurry}），
 * 机器之间靠这个接口直接对接：<b>由下游主动去抽，或由上游主动来推</b>。
 *
 * <p>「面」在这里是必要条件而非细节：磁筛机的同一台机器上有三个罐，
 * 哪个面接的是输入罐、哪个面接的是磁性产物罐，完全由面决定 ——
 * 所以句柄必须按面取，不能按方块实体取。
 *
 * <p>方向语义：{@code side} 是<b>由本机指向外部</b>的那个方向，
 * 也就是「邻居从哪个面来接我」。
 */
public interface SlurryHandler {

	/** 这个面背后真正的那个罐。 */
	VirtualSlurry tank();

	/** 允许外部往这里灌料（输入端）。 */
	boolean canInsert();

	/** 允许外部从这里抽料（输出端）。 */
	boolean canExtract();
}
