package com.geometallurgy.client;

import com.geometallurgy.blockentity.BarrelBlockEntity;
import com.geometallurgy.chemical.Chemical;
import com.geometallurgy.chemical.MolStock;
import com.geometallurgy.item.BucketMaterial;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;

/**
 * 玻璃储罐内嵌流体的渲染器。
 *
 * <h2>画什么</h2>
 * 罐子内部一个内缩的立方体：x/z 各 2/16..14/16（即 12/16 宽），底 3/16，满液面 13/16；
 * 颜色是罐内按 mol 加权的混色（{@link Chemical#colorOf(MolStock)}），液面随总量线性上升。
 * 玻璃罐模型是半透明的（{@code force_translucent}），所以这层颜色会透出来。
 *
 * <h2>只在客户端</h2>
 * 整个类都在 {@code client/} 下，服务端不会加载；方块实体里没有任何渲染代码。
 *
 * <h2>26.x 渲染 API</h2>
 * 26.x 是 render-state 的：{@code createRenderState} / {@code extractRenderState} 先把要画的东西
 * 抄成一份纯数据，{@code submit} 再拿 {@link com.mojang.blaze3d.vertex.PoseStack} 与
 * {@link SubmitNodeCollector} 提交。形状照抄原版 {@code BeaconRenderer} 的
 * {@code submitCustomGeometry(pose, RenderTypes.xxx(), (pose, vc) -> ...)}。
 *
 * <p>用 {@code RenderTypes.debugFilledBox()}：它是 {@code POSITION_COLOR} 的填充四边形管线
 * （混合开、双面、不写深度），顶点只需 position + color，正好是「一块半透明色块」。
 */
public class BarrelRenderer implements BlockEntityRenderer<BarrelBlockEntity, BarrelRenderState> {

	/** 内缩立方体的水平范围（方块坐标，1 = 1 格）。 */
	private static final float INNER_MIN = 2.0F / 16.0F;
	private static final float INNER_MAX = 14.0F / 16.0F;

	/** 流体的底与满液面。 */
	private static final float FLUID_BOTTOM = 3.0F / 16.0F;
	private static final float FLUID_TOP = 13.0F / 16.0F;

	/** 一点点料也要看得见：液面至少抬 1/16。 */
	private static final float MIN_LEVEL = 1.0F / 16.0F;

	/** 流体不透明度（0..255）—— 别盖过玻璃本身的高光。 */
	private static final int FLUID_ALPHA = 0xC0;

	@Override
	public BarrelRenderState createRenderState() {
		return new BarrelRenderState();
	}

	@Override
	public void extractRenderState(BarrelBlockEntity barrel, BarrelRenderState state, float partialTick,
			Vec3 cameraPos, ModelFeatureRenderer.CrumblingOverlay breakProgress) {
		BlockEntityRenderer.super.extractRenderState(barrel, state, partialTick, cameraPos, breakProgress);
		MolStock stock = barrel.tank();
		state.visible = barrel.material() == BucketMaterial.GLASS && !stock.isEmpty();
		if (!state.visible) {
			return;
		}
		state.color = Chemical.colorOf(stock);
		state.fill = (float) Math.min(1.0, stock.totalMol() / BarrelBlockEntity.CAPACITY);
	}

	@Override
	public void submit(BarrelRenderState state, PoseStack pose, SubmitNodeCollector collector,
			CameraRenderState camera) {
		if (!state.visible) {
			return;
		}
		float top = FLUID_BOTTOM + (FLUID_TOP - FLUID_BOTTOM) * Math.max(state.fill, MIN_LEVEL);
		int argb = (FLUID_ALPHA << 24) | (state.color & 0xFFFFFF);
		collector.submitCustomGeometry(pose, RenderTypes.debugFilledBox(),
				(pose1, builder) -> box(pose1, builder, argb, top));
	}

	/** 六个面一次提交。{@code debugFilledBox} 关双面剔除，绕序不重要。 */
	private static void box(PoseStack.Pose pose, VertexConsumer builder, int argb, float top) {
		float x0 = INNER_MIN;
		float x1 = INNER_MAX;
		float z0 = INNER_MIN;
		float z1 = INNER_MAX;
		float y0 = FLUID_BOTTOM;
		float y1 = top;
		// 下
		quad(pose, builder, argb, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
		// 上
		quad(pose, builder, argb, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);
		// 北 -Z
		quad(pose, builder, argb, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
		// 南 +Z
		quad(pose, builder, argb, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1);
		// 西 -X
		quad(pose, builder, argb, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0);
		// 东 +X
		quad(pose, builder, argb, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
	}

	private static void quad(PoseStack.Pose pose, VertexConsumer builder, int argb,
			float ax, float ay, float az, float bx, float by, float bz,
			float cx, float cy, float cz, float dx, float dy, float dz) {
		builder.addVertex(pose, ax, ay, az).setColor(argb);
		builder.addVertex(pose, bx, by, bz).setColor(argb);
		builder.addVertex(pose, cx, cy, cz).setColor(argb);
		builder.addVertex(pose, dx, dy, dz).setColor(argb);
	}
}
