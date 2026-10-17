package com.geometallurgy.blockentity;

import com.geometallurgy.Geometallurgy;
import com.geometallurgy.block.ModBlocks;

import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

import team.reborn.energy.api.EnergyStorage;

/** 方块实体类型注册，以及能量接口的挂接。 */
public final class ModBlockEntities {

	private ModBlockEntities() {
	}

	public static final BlockEntityType<VeinExtractorBlockEntity> VEIN_EXTRACTOR =
			Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Geometallurgy.id("vein_extractor"),
					FabricBlockEntityTypeBuilder
							.create(VeinExtractorBlockEntity::new, ModBlocks.VEIN_EXTRACTOR)
							.build());

	public static final BlockEntityType<CoalGeneratorBlockEntity> COAL_GENERATOR =
			Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Geometallurgy.id("coal_generator"),
					FabricBlockEntityTypeBuilder
							.create(CoalGeneratorBlockEntity::new, ModBlocks.COAL_GENERATOR)
							.build());

	public static final BlockEntityType<FluidVeinExtractorBlockEntity> FLUID_VEIN_EXTRACTOR =
			Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Geometallurgy.id("fluid_vein_extractor"),
					FabricBlockEntityTypeBuilder
							.create(FluidVeinExtractorBlockEntity::new, ModBlocks.FLUID_VEIN_EXTRACTOR)
							.build());

	public static final BlockEntityType<MagneticSeparatorBlockEntity> MAGNETIC_SEPARATOR =
			Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Geometallurgy.id("magnetic_separator"),
					FabricBlockEntityTypeBuilder
							.create(MagneticSeparatorBlockEntity::new, ModBlocks.MAGNETIC_SEPARATOR)
							.build());

	public static final BlockEntityType<CrusherBlockEntity> CRUSHER =
			Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Geometallurgy.id("crusher"),
					FabricBlockEntityTypeBuilder
							.create(CrusherBlockEntity::new, ModBlocks.CRUSHER)
							.build());

	public static final BlockEntityType<CentrifugeBlockEntity> CENTRIFUGE =
			Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Geometallurgy.id("centrifuge"),
					FabricBlockEntityTypeBuilder
							.create(CentrifugeBlockEntity::new, ModBlocks.CENTRIFUGE)
							.build());

	// ------------------------------------------------------------------ 冶金三炉
	//
	// 三台机器是同一个方块实体类，规格从方块身上读回来（SmelterBlockEntity 的双参构造器），
	// 所以这里只需要各注册一个类型。类型只在构造器里用来取「这属于哪个类型」，
	// 所以 smelter(profile) 这张表必须在常量都建好之后才填 —— 见下面的静态块。

	public static final BlockEntityType<SmelterBlockEntity> REDUCTION_FURNACE =
			Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Geometallurgy.id("reduction_furnace"),
					FabricBlockEntityTypeBuilder
							.create(SmelterBlockEntity::new, ModBlocks.REDUCTION_FURNACE)
							.build());

	public static final BlockEntityType<SmelterBlockEntity> ELECTROLYSIS_FURNACE =
			Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Geometallurgy.id("electrolysis_furnace"),
					FabricBlockEntityTypeBuilder
							.create(SmelterBlockEntity::new, ModBlocks.ELECTROLYSIS_FURNACE)
							.build());

	public static final BlockEntityType<SmelterBlockEntity> DISPLACEMENT_FURNACE =
			Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Geometallurgy.id("displacement_furnace"),
					FabricBlockEntityTypeBuilder
							.create(SmelterBlockEntity::new, ModBlocks.DISPLACEMENT_FURNACE)
							.build());

	public static final BlockEntityType<BatteryBlockEntity> BATTERY =
			Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Geometallurgy.id("battery"),
					FabricBlockEntityTypeBuilder
							.create(BatteryBlockEntity::new, ModBlocks.BATTERY)
							.build());

	public static final BlockEntityType<ReactionVesselBlockEntity> REACTION_VESSEL =
			Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Geometallurgy.id("reaction_vessel"),
					FabricBlockEntityTypeBuilder
							.create(ReactionVesselBlockEntity::new, ModBlocks.REACTION_VESSEL)
							.build());

	public static final BlockEntityType<MixerBlockEntity> MIXER =
			Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Geometallurgy.id("mixer"),
					FabricBlockEntityTypeBuilder
							.create(MixerBlockEntity::new, ModBlocks.MIXER)
							.build());

	/**
	 * 储罐：四种材质共用这一个方块实体类型。
	 *
	 * <p>类型只在构造器里用来取「这属于哪个类型」，材质从方块身上读回来，
	 * 所以一个类型足以服务四个方块。
	 */
	public static final BlockEntityType<BarrelBlockEntity> BARREL =
			Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Geometallurgy.id("barrel"),
					FabricBlockEntityTypeBuilder
							.create(BarrelBlockEntity::new, ModBlocks.METAL_BARREL, ModBlocks.GLASS_BARREL,
								ModBlocks.LEAD_BARREL, ModBlocks.CERAMIC_BARREL)
							.build());

	private static final java.util.Map<String, BlockEntityType<SmelterBlockEntity>> SMELTERS =
			new java.util.HashMap<>();

	static {
		SMELTERS.put(SmelterProfile.REDUCTION.id(), REDUCTION_FURNACE);
		SMELTERS.put(SmelterProfile.ELECTROLYSIS.id(), ELECTROLYSIS_FURNACE);
		SMELTERS.put(SmelterProfile.DISPLACEMENT.id(), DISPLACEMENT_FURNACE);
	}

	/** 某个规格对应的方块实体类型。方块实体的构造器要用它去 super(...)。 */
	public static BlockEntityType<SmelterBlockEntity> smelter(SmelterProfile profile) {
		BlockEntityType<SmelterBlockEntity> type = SMELTERS.get(profile.id());
		if (type == null) {
			throw new IllegalStateException("未注册的冶炼炉规格：" + profile.id());
		}
		return type;
	}

	public static void init() {
		// 把能量接口挂到方块实体类型上（必须在类型创建之后）。
		// 这样发电机才能通过 EnergyStorage.SIDED.find(...) 找到相邻机器的储能源。
		EnergyStorage.SIDED.registerForBlockEntity(
				(VeinExtractorBlockEntity extractor, Direction direction) -> extractor.getEnergyStorage(),
				VEIN_EXTRACTOR);
		EnergyStorage.SIDED.registerForBlockEntity(
				(CoalGeneratorBlockEntity generator, Direction direction) -> generator.getEnergyStorage(),
				COAL_GENERATOR);
		EnergyStorage.SIDED.registerForBlockEntity(
				(FluidVeinExtractorBlockEntity extractor, Direction direction) -> extractor.getEnergyStorage(),
				FLUID_VEIN_EXTRACTOR);
		EnergyStorage.SIDED.registerForBlockEntity(
				(MagneticSeparatorBlockEntity separator, Direction direction) -> separator.getEnergyStorage(),
				MAGNETIC_SEPARATOR);
		EnergyStorage.SIDED.registerForBlockEntity(
				(CrusherBlockEntity crusher, Direction direction) -> crusher.getEnergyStorage(),
				CRUSHER);
		EnergyStorage.SIDED.registerForBlockEntity(
				(CentrifugeBlockEntity centrifuge, Direction direction) -> centrifuge.getEnergyStorage(),
				CENTRIFUGE);
		EnergyStorage.SIDED.registerForBlockEntity(
				(SmelterBlockEntity smelter, Direction direction) -> smelter.getEnergyStorage(),
				REDUCTION_FURNACE);
		EnergyStorage.SIDED.registerForBlockEntity(
				(SmelterBlockEntity smelter, Direction direction) -> smelter.getEnergyStorage(),
				ELECTROLYSIS_FURNACE);
		EnergyStorage.SIDED.registerForBlockEntity(
				(SmelterBlockEntity smelter, Direction direction) -> smelter.getEnergyStorage(),
				DISPLACEMENT_FURNACE);
		EnergyStorage.SIDED.registerForBlockEntity(
				(BatteryBlockEntity battery, Direction direction) -> battery.getEnergyStorage(),
				BATTERY);
		// 反应釜与搅拌器不使用能量（化学反应不需要电），所以这里没有 EnergyStorage 挂接。

		// 物品接口：把发电机暴露为可被外部输入/抽取的容器。
		// 原版漏斗走 Container（已由方块实体自身实现），这条挂接让走 Transfer API 的模组管道也能访问，
		// 同时 Fabric 的 HopperBlockEntityMixin 会把漏斗的插入/抽取接过来，两边行为一致。
		ItemStorage.SIDED.registerForBlockEntity(
				(CoalGeneratorBlockEntity generator, Direction direction) -> ContainerStorage.of(generator, direction),
				COAL_GENERATOR);

		// 挖掘机只暴露一个钻头槽；接上这条，漏斗就能自动给它续磨头。
		ItemStorage.SIDED.registerForBlockEntity(
				(FluidVeinExtractorBlockEntity extractor, Direction direction) -> ContainerStorage.of(extractor, direction),
				FLUID_VEIN_EXTRACTOR);

		// 三台冶炼炉：试剂槽收件、产物槽取件，具体哪一面干什么由方块实体自己的六个面角色决定
		// （见 SmelterBlockEntity#getSlotsForFace）。
		ItemStorage.SIDED.registerForBlockEntity(
				(SmelterBlockEntity smelter, Direction direction) -> ContainerStorage.of(smelter, direction),
				REDUCTION_FURNACE);
		ItemStorage.SIDED.registerForBlockEntity(
				(SmelterBlockEntity smelter, Direction direction) -> ContainerStorage.of(smelter, direction),
				ELECTROLYSIS_FURNACE);
		ItemStorage.SIDED.registerForBlockEntity(
				(SmelterBlockEntity smelter, Direction direction) -> ContainerStorage.of(smelter, direction),
				DISPLACEMENT_FURNACE);

		// 反应釜 / 搅拌器：只有一个催化剂槽，走 Transfer API 的管道也只能看到它。
		ItemStorage.SIDED.registerForBlockEntity(
				(ReactionVesselBlockEntity vessel, Direction direction) -> ContainerStorage.of(vessel, direction),
				REACTION_VESSEL);
		ItemStorage.SIDED.registerForBlockEntity(
				(MixerBlockEntity mixer, Direction direction) -> ContainerStorage.of(mixer, direction),
				MIXER);
	}
}
