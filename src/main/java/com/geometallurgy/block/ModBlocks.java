package com.geometallurgy.block;

import java.util.function.Function;

import com.geometallurgy.Geometallurgy;
import com.geometallurgy.blockentity.SmelterProfile;
import com.geometallurgy.item.BarrelItem;
import com.geometallurgy.item.BucketMaterial;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.sounds.BlockSoundSets;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/** 本模组的方块与对应物品。 */
public final class ModBlocks {

	private ModBlocks() {
	}

	/** 矿脉抽取机：耗能开采本区块的虚拟矿脉。 */
	public static final Block VEIN_EXTRACTOR = register("vein_extractor",
			VeinExtractorBlock::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.STONE)
					.strength(3.5F)
					.requiresCorrectToolForDrops()
					.sound(BlockSoundSets.STONE));

	/** 火力发电机：烧煤发电，向相邻机器推送能量。 */
	public static final Block COAL_GENERATOR = register("coal_generator",
			CoalGeneratorBlock::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.METAL)
					.strength(3.5F)
					.requiresCorrectToolForDrops()
					.sound(BlockSoundSets.METAL));

	/**
	 * 流体碎矿挖掘机：开采所在位置的虚拟矿脉，产物直接以混合矿浆（内部虚拟流体）的形式
	 * 注入内部储罐，而不是掉落固体矿石。
	 */
	public static final Block FLUID_VEIN_EXTRACTOR = register("fluid_vein_extractor",
			FluidVeinExtractorBlock::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.METAL)
					.strength(3.5F)
					.requiresCorrectToolForDrops()
					.sound(BlockSoundSets.METAL));

	/**
	 * 磁力筛机：把上游的混合矿浆按磁性拆成精矿与尾矿。
	 *
	 * <p>磁场强度由耗电功率决定（64 FE/t 对应额定磁场 1.0），太弱吸不住磁性颗粒、
	 * 太强会把非磁性颗粒夹带进精矿 —— 两头都分不开。六个面各自配成输入/磁性出/非磁性出/关闭。
	 */
	public static final Block MAGNETIC_SEPARATOR = register("magnetic_separator",
			MagneticSeparatorBlock::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.METAL)
					.strength(3.5F)
					.requiresCorrectToolForDrops()
					.sound(BlockSoundSets.METAL));

	/**
	 * 粉碎机：把矿浆磨成矿粉。
	 *
	 * <p>它<b>不</b>分离任何东西 —— 成分原样照搬，只是颗粒变细。代价在下游：粉料进磁筛机时
	 * 理想磁场窗口显著收窄，提炼工序也要为低纯度多烧燃料。磨矿耗能随来料莫氏硬度上升。
	 */
	public static final Block CRUSHER = register("crusher",
			CrusherBlock::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.METAL)
					.strength(3.5F)
					.requiresCorrectToolForDrops()
					.sound(BlockSoundSets.METAL));

	/**
	 * 离心机：按密度把矿浆分成重相与轻相。
	 *
	 * <p>只能吃矿浆 —— 粉料颗粒太细，液相带不住，无法按密度沉降分层。切点密度可在界面调，
	 * 转速（即功率）决定分离锐度。
	 */
	public static final Block CENTRIFUGE = register("centrifuge",
			CentrifugeBlock::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.METAL)
					.strength(3.5F)
					.requiresCorrectToolForDrops()
					.sound(BlockSoundSets.METAL));

	// ------------------------------------------------------------------ 冶金三炉
	//
	// 三台机器共用 SmelterBlock 这一个类，差别只在传进去的规格里：走哪条化学路线、
	// 烧多少电、一个炉次多久、辅助剂是哪一类。所以这里只是把三份规格各注册一遍。

	/**
	 * 还原炉：碳热或金属热还原。
	 *
	 * <p>最通用的一台 —— 氧化物矿粉进来，碳（或一种比目标更活泼的金属）把氧夺走，金属留下来。
	 * 试剂便宜、耗电中等，代价是收率与纯度都只是中等。
	 */
	public static final Block REDUCTION_FURNACE = register("reduction_furnace",
			properties -> new SmelterBlock(SmelterProfile.REDUCTION, properties),
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.METAL)
					.strength(3.5F)
					.requiresCorrectToolForDrops()
					.sound(BlockSoundSets.METAL));

	/**
	 * 电解炉：熔融电解。
	 *
	 * <p>电耗是还原炉的十倍量级，还要持续磨电极（碳棒烧得快，钨棒/钼棒几乎不耗），
	 * 换来的是三条路线里最高的收率与最干净的产品 —— 活泼金属更是只有这一条路可走。
	 */
	public static final Block ELECTROLYSIS_FURNACE = register("electrolysis_furnace",
			properties -> new SmelterBlock(SmelterProfile.ELECTROLYSIS, properties),
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.METAL)
					.strength(3.5F)
					.requiresCorrectToolForDrops()
					.sound(BlockSoundSets.METAL));

	/**
	 * 置换炉：湿法置换。
	 *
	 * <p>几乎不耗电，但每炼一份金属就要吃掉一种比它更活泼的金属（铁顶铜、锌顶银……），
	 * 并且副产一罐盐液。适合手里正好有便宜活泼金属的时候。
	 */
	public static final Block DISPLACEMENT_FURNACE = register("displacement_furnace",
			properties -> new SmelterBlock(SmelterProfile.DISPLACEMENT, properties),
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.METAL)
					.strength(3.5F)
					.requiresCorrectToolForDrops()
					.sound(BlockSoundSets.METAL));

	/** 蓄电池：无线电网的中心，输入 / 输出两种模式，19³ 范围逐设备限功率。 */
	public static final Block BATTERY = register("battery",
			BatteryBlock::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.METAL)
					.strength(3.5F)
					.requiresCorrectToolForDrops()
					.sound(BlockSoundSets.METAL));

	/** 无线感应器：立在机器顶面的入网许可，蓄电池只认装了它的机器。 */
	public static final Block WIRELESS_SENSOR = register("wireless_sensor",
			WirelessSensorBlock::new,
			WirelessSensorBlock.sensorProperties());

	/**
	 * 反应釜：纯容器型 mol 机器（设计稿 §11.5）。
	 *
	 * <p>两个输入桶 + 一个催化剂 + 一个输出桶。按化学式配平，在容器内把两个输入槽的
	 * mol 化合成产物。不耗电极、不烧料 —— 只吃化学桶，是 mol 体系的主消费方。
	 */
	public static final Block REACTION_VESSEL = register("reaction_vessel",
			ReactionVesselBlock::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.METAL)
					.strength(3.5F)
					.requiresCorrectToolForDrops()
					.sound(BlockSoundSets.METAL));

	/**
	 * 搅拌器：反应釜的「会搅、会配比」版本。
	 *
	 * <p>两个入口（A / B）各有一个预混缓冲，按界面上的比率兑进混合槽；混合均匀之后再匹配反应。
	 * 不搅拌几乎不发生的反应只有在这台机器里才跑得起来。
	 */
	public static final Block MIXER = register("mixer",
			MixerBlock::new,
			BlockBehaviour.Properties.of()
					.mapColor(MapColor.METAL)
					.strength(3.5F)
					.requiresCorrectToolForDrops()
					.sound(BlockSoundSets.METAL));

	// ------------------------------------------------------------------ 储罐
	//
	// 四只储罐共用 BarrelBlock：类里只有材质一个变量，颜色、耐蚀、音效都跟着它走。
	// 容量 60 mol（= 4 桶），存的是 mol 账而不是物品；机器可以按面直接和它过户 mol。

	/** 铁储罐：60 mol，什么都不耐，只装水、盐与中性料。 */
	public static final Block METAL_BARREL = registerBarrel("metal_barrel", BucketMaterial.IRON);

	/** 玻璃储罐：60 mol，耐酸与氧化剂，但氟化物与强碱会啃玻璃。 */
	public static final Block GLASS_BARREL = registerBarrel("glass_barrel", BucketMaterial.GLASS);

	/** 铅储罐：60 mol，耐酸与氟化物（氢氟酸只能用它或陶瓷），但硝酸会溶铅。 */
	public static final Block LEAD_BARREL = registerBarrel("lead_barrel", BucketMaterial.LEAD);

	/** 陶瓷储罐：60 mol，四类侵蚀全耐，做起来最贵。 */
	public static final Block CERAMIC_BARREL = registerBarrel("ceramic_barrel", BucketMaterial.CERAMIC);

	public static void init() {
		// 触发类加载，方块与物品在静态初始化时完成注册。
	}

	/** 注册一只储罐；材质决定贴图颜色、方块音效与耐蚀性。 */
	private static Block registerBarrel(String name, BucketMaterial material) {
		BlockBehaviour.Properties properties = switch (material) {
			case IRON, LEAD -> BlockBehaviour.Properties.of()
				.mapColor(MapColor.METAL)
				.sound(BlockSoundSets.METAL);
			case GLASS -> BlockBehaviour.Properties.of()
				.mapColor(MapColor.ICE)
				.sound(BlockSoundSets.GLASS)
				.noOcclusion();
			case CERAMIC -> BlockBehaviour.Properties.of()
				.mapColor(MapColor.CLAY)
				.sound(BlockSoundSets.STONE);
		};
		return register(name, props -> new BarrelBlock(material, props), BarrelItem::new,
				properties.strength(3.0F).requiresCorrectToolForDrops());
	}

	/**
	 * 注册方块，并顺带注册它的 {@link BlockItem}。
	 * 26.x 起 {@code Properties} 必须显式带 id，这里统一处理。
	 */
	private static Block register(String name, Function<BlockBehaviour.Properties, Block> factory,
			BlockBehaviour.Properties properties) {
		return register(name, factory, BlockItem::new, properties);
	}

	/** 注册方块，并用给定的物品工厂造它的物品（储罐要用 {@link BarrelItem}）。 */
	private static Block register(String name, Function<BlockBehaviour.Properties, Block> factory,
			java.util.function.BiFunction<Block, Item.Properties, Item> itemFactory,
			BlockBehaviour.Properties properties) {
		Identifier id = Geometallurgy.id(name);

		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
		Block block = factory.apply(properties.setId(blockKey));
		Registry.register(BuiltInRegistries.BLOCK, blockKey, block);

		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
		Item item = itemFactory.apply(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix());
		Registry.register(BuiltInRegistries.ITEM, itemKey, item);

		return block;
	}
}
