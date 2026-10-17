package com.geometallurgy.client;

import com.geometallurgy.blockentity.BarrelBlockEntity;
import com.geometallurgy.blockentity.ModBlockEntities;
import com.geometallurgy.menu.ModMenus;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/**
 * 客户端入口：把每个菜单类型绑到它的界面。
 *
 * <p>{@code MenuScreens.register} 在原版里是 private，Fabric 通过
 * fabric-transitive-access-wideners-v1 开放了它与 {@code ScreenConstructor}。
 *
 * <p>枪械的界面（组装台、开火输入、第一人称持枪、弹头实体渲染）不在这里 ——
 * 整块在附属模组 geometallurgy_guns，见那边的客户端入口。
 */
public class GeometallurgyClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		// 玻璃储罐的内嵌流体。类型显式写出来，免得 register 的 ? super 通配把 lambda 推断绕进去；
		// 铁 / 铅 / 陶瓷由渲染器自己按材质跳过。
		BlockEntityRendererProvider<BarrelBlockEntity, BarrelRenderState> barrelRenderer =
				context -> new BarrelRenderer();
		BlockEntityRendererRegistry.register(ModBlockEntities.BARREL, barrelRenderer);

		MenuScreens.register(ModMenus.VEIN_EXTRACTOR, VeinExtractorScreen::new);
		MenuScreens.register(ModMenus.COAL_GENERATOR, CoalGeneratorScreen::new);
		MenuScreens.register(ModMenus.FLUID_VEIN_EXTRACTOR, FluidVeinExtractorScreen::new);
		MenuScreens.register(ModMenus.MAGNETIC_SEPARATOR, MagneticSeparatorScreen::new);
		MenuScreens.register(ModMenus.CRUSHER, CrusherScreen::new);
		MenuScreens.register(ModMenus.CENTRIFUGE, CentrifugeScreen::new);
		MenuScreens.register(ModMenus.BATTERY, BatteryScreen::new);
		MenuScreens.register(ModMenus.REACTION_VESSEL, ReactionVesselScreen::new);
		MenuScreens.register(ModMenus.MIXER, MixerScreen::new);
		MenuScreens.register(ModMenus.REDUCTION_FURNACE, SmelterScreen::new);
		MenuScreens.register(ModMenus.ELECTROLYSIS_FURNACE, SmelterScreen::new);
		MenuScreens.register(ModMenus.DISPLACEMENT_FURNACE, SmelterScreen::new);
		MenuScreens.register(ModMenus.MINERAL_PROSPECTOR, ProspectorScreen::new);
		MenuScreens.register(ModMenus.MANUAL, ManualScreen::new);
	}
}
