package com.geometallurgy.client;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;

/**
 * 玻璃储罐的渲染态：服务端把账同步过来之后，{@link BarrelRenderer} 每帧只从这里取值，
 * 不再回头碰方块实体，也不重新算化学表。
 */
public class BarrelRenderState extends BlockEntityRenderState {

	/** 要不要画内嵌流体（玻璃材质且罐里有料）。 */
	public boolean visible;

	/** 按 mol 加权的混色（0xRRGGBB，不含 alpha）。 */
	public int color = 0xFFFFFF;

	/** 液位 0..1（总 mol / 容量）。 */
	public float fill;
}
