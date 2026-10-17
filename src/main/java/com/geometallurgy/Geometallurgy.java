package com.geometallurgy;

import com.geometallurgy.block.ModBlocks;
import com.geometallurgy.blockentity.ModBlockEntities;
import com.geometallurgy.item.ModComponents;
import com.geometallurgy.item.ModItems;
import com.geometallurgy.menu.ModMenus;
import com.geometallurgy.recipe.ModRecipes;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 虚拟矿脉开采 (Vein Mine)
 *
 * <p>地下不生成真实矿石方块，而是按「世界种子 + 区块坐标」确定性地埋设虚拟矿脉：
 * 每个区块随机含有 1~4 种矿物，各自带稀有度，外加一个丰度。
 * 玩家用火力发电机供电给矿脉抽取机，即可无限开采本区块的矿物分布。
 */
public class Geometallurgy implements ModInitializer {
	public static final String MOD_ID = "geometallurgy";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModComponents.init();
		ModItems.init();
		ModBlocks.init();
		ModBlockEntities.init();
		ModMenus.init();
		ModRecipes.init();
		com.geometallurgy.chemical.ReactionGraph.init(); // 建超边图并跑启动可达性审计（纯报告）
		ModCreativeTabs.init();
		com.geometallurgy.manual.ManualGrant.init();
		// 测试场地：/geometallurgy testrig（会在世界里批量放置方块）。
		// 只在开发环境、或显式 -Dgeometallurgy.testrig=true 时注册；
		// 正式发行版默认不存在该命令，避免测试脚手架进入玩家客户端。
		if (net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()
				|| Boolean.getBoolean("geometallurgy.testrig")) {
			com.geometallurgy.debug.TestRigCommand.init();
		}

		// 手册正文段数探针（不进世界，主菜单就能跑）：-Dgeometallurgy.manualTextProbe=true
		if (Boolean.getBoolean("geometallurgy.manualTextProbe")) {
			com.geometallurgy.debug.ManualTextProbe.init();
		}

		LOGGER.info("[Geometallurgy] 已加载：虚拟矿脉 + 火力发电 + 矿浆开采 + 选矿 + 冶金三炉。");
	}

	/** 生成属于本模组命名空间的 Identifier。 */
	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
