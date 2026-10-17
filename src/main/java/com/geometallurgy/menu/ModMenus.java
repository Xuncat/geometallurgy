package com.geometallurgy.menu;

import com.geometallurgy.Geometallurgy;
import com.geometallurgy.blockentity.SmelterProfile;
import com.geometallurgy.menu.BatteryMenu;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.util.Unit;
import net.minecraft.world.inventory.MenuType;

/**
 * 菜单类型注册。
 *
 * <p>26.x 的 {@code MenuType} 没有公开构造器，Fabric 用 {@link ExtendedMenuType}
 * 提供创建入口。这两个界面不需要额外的打开数据，所以数据载荷用 {@link Unit}。
 */
public final class ModMenus {

	private ModMenus() {
	}

	public static final MenuType<VeinExtractorMenu> VEIN_EXTRACTOR = Registry.register(
			BuiltInRegistries.MENU,
			Geometallurgy.id("vein_extractor"),
			new ExtendedMenuType<>(VeinExtractorMenu::new, Unit.STREAM_CODEC));

	public static final MenuType<CoalGeneratorMenu> COAL_GENERATOR = Registry.register(
			BuiltInRegistries.MENU,
			Geometallurgy.id("coal_generator"),
			new ExtendedMenuType<>(CoalGeneratorMenu::new, Unit.STREAM_CODEC));

	public static final MenuType<FluidVeinExtractorMenu> FLUID_VEIN_EXTRACTOR = Registry.register(
			BuiltInRegistries.MENU,
			Geometallurgy.id("fluid_vein_extractor"),
			new ExtendedMenuType<>(FluidVeinExtractorMenu::new, Unit.STREAM_CODEC));

	public static final MenuType<MagneticSeparatorMenu> MAGNETIC_SEPARATOR = Registry.register(
			BuiltInRegistries.MENU,
			Geometallurgy.id("magnetic_separator"),
			new ExtendedMenuType<>(MagneticSeparatorMenu::new, Unit.STREAM_CODEC));

	public static final MenuType<CrusherMenu> CRUSHER = Registry.register(
			BuiltInRegistries.MENU,
			Geometallurgy.id("crusher"),
			new ExtendedMenuType<>(CrusherMenu::new, Unit.STREAM_CODEC));

	public static final MenuType<CentrifugeMenu> CENTRIFUGE = Registry.register(
			BuiltInRegistries.MENU,
			Geometallurgy.id("centrifuge"),
			new ExtendedMenuType<>(CentrifugeMenu::new, Unit.STREAM_CODEC));

	public static final MenuType<BatteryMenu> BATTERY = Registry.register(
			BuiltInRegistries.MENU,
			Geometallurgy.id("battery"),
			new ExtendedMenuType<>(BatteryMenu::new, Unit.STREAM_CODEC));

	public static final MenuType<ReactionVesselMenu> REACTION_VESSEL = Registry.register(
			BuiltInRegistries.MENU,
			Geometallurgy.id("reaction_vessel"),
			new ExtendedMenuType<>(ReactionVesselMenu::new, Unit.STREAM_CODEC));

	public static final MenuType<MixerMenu> MIXER = Registry.register(
			BuiltInRegistries.MENU,
			Geometallurgy.id("mixer"),
			new ExtendedMenuType<>(MixerMenu::new, Unit.STREAM_CODEC));

	// ------------------------------------------------------------------ 冶金三炉
	//
	// 三台机器的界面是同一个类，差别只在规格。菜单类型必须一台一个（客户端靠它决定开哪个界面），
	// 所以每个类型都用 lambda 把它那一份规格带进构造器。

	public static final MenuType<SmelterMenu> REDUCTION_FURNACE = registerSmelter(SmelterProfile.REDUCTION);
	public static final MenuType<SmelterMenu> ELECTROLYSIS_FURNACE = registerSmelter(SmelterProfile.ELECTROLYSIS);
	public static final MenuType<SmelterMenu> DISPLACEMENT_FURNACE = registerSmelter(SmelterProfile.DISPLACEMENT);

	private static final java.util.Map<String, MenuType<SmelterMenu>> SMELTERS = new java.util.HashMap<>();

	static {
		SMELTERS.put(SmelterProfile.REDUCTION.id(), REDUCTION_FURNACE);
		SMELTERS.put(SmelterProfile.ELECTROLYSIS.id(), ELECTROLYSIS_FURNACE);
		SMELTERS.put(SmelterProfile.DISPLACEMENT.id(), DISPLACEMENT_FURNACE);
	}

	private static MenuType<SmelterMenu> registerSmelter(SmelterProfile profile) {
		return Registry.register(
				BuiltInRegistries.MENU,
				Geometallurgy.id(profile.id()),
				new ExtendedMenuType<SmelterMenu, Unit>(
						(syncId, inventory, unit) -> new SmelterMenu(syncId, inventory, unit, profile),
						Unit.STREAM_CODEC));
	}

	/** 某个规格对应的菜单类型。 */
	public static MenuType<SmelterMenu> smelter(SmelterProfile profile) {
		MenuType<SmelterMenu> type = SMELTERS.get(profile.id());
		if (type == null) {
			throw new IllegalStateException("未注册的冶炼炉菜单：" + profile.id());
		}
		return type;
	}

	/**
	 * 矿物探测仪的选择界面。
	 *
	 * <p>这是本模组唯一带开屏数据的菜单：载荷是<b>当前锁定的矿物序号</b>，
	 * 客户端靠它把清单高亮在正确的行上。
	 */
	public static final MenuType<ProspectorMenu> MINERAL_PROSPECTOR = Registry.register(
			BuiltInRegistries.MENU,
			Geometallurgy.id("mineral_prospector"),
			new ExtendedMenuType<ProspectorMenu, Integer>(ProspectorMenu::new, ByteBufCodecs.VAR_INT));

	/**
	 * 任务书。没有槽位；开屏数据是**配方载荷**（9 格原料 + 1 格产物，一维铺开），
	 * 由服务端读真实配方之后发过来 —— 客户端自己没有按 id 取配方的能力。
	 */
	public static final MenuType<ManualMenu> MANUAL = Registry.register(
			BuiltInRegistries.MENU,
			Geometallurgy.id("manual"),
			new ExtendedMenuType<ManualMenu, java.util.List<net.minecraft.world.item.ItemStack>>(
					ManualMenu::new, net.minecraft.world.item.ItemStack.OPTIONAL_LIST_STREAM_CODEC));

	public static void init() {
		// 触发类加载。
	}
}
