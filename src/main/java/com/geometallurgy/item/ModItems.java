package com.geometallurgy.item;

import java.util.function.Function;

import com.geometallurgy.Geometallurgy;
import com.geometallurgy.proto.OreMineral;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/** 本模组的物品。 */
public final class ModItems {

	private ModItems() {
	}

	/**
	 * 任务书：开局自动发一本，右键翻开。
	 *
	 * <p>帕秋莉手册本体还没跟上 26.x，而开局最需要的就是一本能翻的说明 —— 这本书顶上。
	 * 正文全在语言文件里（见 {@link com.geometallurgy.manual.Manual}），加内容不用改代码。
	 */
	public static final Item FIELD_MANUAL = register("field_manual",
			FieldManualItem::new,
			new Item.Properties().stacksTo(1));

	/**
	 * 矿物探测仪：短按右键选矿脉、长按右键探测最近的矿体中心。
	 *
	 * <p>默认锁定赤铁矿 —— 它是最常见、也最好找的一种，适合当新手的第一个目标。
	 */
	public static final Item MINERAL_PROSPECTOR = register("mineral_prospector",
			MineralProspectorItem::new,
			new Item.Properties()
					.stacksTo(1)
					.component(ModComponents.TARGET_MINERAL, OreMineral.HEMATITE.id()));

	/**
	 * 钻头。四档基础材质，都是消耗品（带标准耐久条）。
	 *
	 * <p>耐磨度直接就是耐久上限，所以钻石钻头反而最不经用 —— 它硬，但脆。
	 */
	public static final Item DRILL_BIT_STONE = drillBit("drill_bit_stone", DrillMaterial.STONE);
	public static final Item DRILL_BIT_GOLD = drillBit("drill_bit_gold", DrillMaterial.GOLD);
	public static final Item DRILL_BIT_IRON = drillBit("drill_bit_iron", DrillMaterial.IRON);
	public static final Item DRILL_BIT_DIAMOND = drillBit("drill_bit_diamond", DrillMaterial.DIAMOND);

	/**
	 * 单一「锭」。
	 *
	 * <p>整个模组只有这一个锭物品 —— 铁锭、铜锭、合金锭全靠物品组件区分
	 * （见 {@link com.geometallurgy.metal.Ingots}）。所以这里的代价是：它不能按金属进不同的
	 * 物品标签，原版配方也就没法直接识别成分；要接回原版合成链得走那条铸造特殊配方。
	 */
	public static final Item INGOT = register("ingot", IngotItem::new, new Item.Properties());

	/**
	 * 碳粉：标准计量的还原剂（当量 1.0）。
	 *
	 * <p>它自己也是产线产物 —— 石墨矿粉进电解炉，出来就是这个。
	 */
	public static final Item CARBON_POWDER = register("carbon_powder", Item::new, new Item.Properties());

	/**
	 * 金属粉：金属锭经粉碎机磨成的粉，也是「金属单质」在 mol 反应表里的载体物种（§十八）。
	 *
	 * <p>与锭一样是「一个物品、任意金属」—— 成分由 {@link com.geometallurgy.metal.Ingots}
	 * 的两个组件决定，名字 / 浮层随成分走。金属 + 酸 → 盐 + H₂ 的规则 5 就靠它。
	 */
	public static final Item METAL_POWDER = register("metal_powder",
			MetalPowderItem::new,
			new Item.Properties().component(ModComponents.MEASURE, Measure.MOL.key()));

	/**
	 * 碳棒：电解炉的消耗性阳极。便宜、好做，但烧得快 —— 一根只撑 8 个炉次。
	 *
	 * <p>用原版的耐久条来记「还能撑几个炉次」，所以耐久上限就是炉次数。
	 */
	public static final Item CARBON_ROD = register("carbon_rod", Item::new,
			new Item.Properties().durability(8));

	/**
	 * 工程配置器：把一台机器的六面配置整份搬到另一台同型机器上。
	 *
	 * <p>新机器的六个面一律是关的，所以「配好一台、其余照抄」会变成常规操作 ——
	 * 逐面点界面在成排的机器面前太慢。见 {@link EngineeringConfiguratorItem}。
	 */
	public static final Item ENGINEERING_CONFIGURATOR = register("engineering_configurator",
			EngineeringConfiguratorItem::new,
			new Item.Properties().stacksTo(1));

	/** 钨棒：惰性电极。贵得离谱，但一根能撑 64 个炉次 —— 长期跑电解就靠它。 */
	public static final Item TUNGSTEN_ROD = register("tungsten_rod", Item::new,
			new Item.Properties().durability(64));

	/** 钼棒：同样是惰性电极，便宜一点、寿命短一点。 */
	public static final Item MOLYBDENUM_ROD = register("molybdenum_rod", Item::new,
			new Item.Properties().durability(40));

	/**
	 * 化学金属桶：以固定 15 mol 容量盛放化学内容。
	 *
	 * <p>mol 体系的载具（设计稿 §二）。不认体积 —— 内容直接是一本 mol 账
	 * （{@link ModComponents#BUCKET_STOCK}），可以同时装几种溶质 + 溶剂。
	 * 空桶也能堆叠（16）；装东西之后是否堆叠取决于成分完全一致。
	 */
	public static final Item METAL_BUCKET = bucket("metal_bucket", BucketMaterial.IRON);

	/** 玻璃桶：耐酸与氧化剂，但氢氟酸与强碱会吃玻璃。 */
	public static final Item GLASS_BUCKET = bucket("glass_bucket", BucketMaterial.GLASS);

	/** 铅桶：耐酸与氟化物（氢氟酸就靠它），但硝酸会溶铅。 */
	public static final Item LEAD_BUCKET = bucket("lead_bucket", BucketMaterial.LEAD);

	/** 陶瓷桶：四类侵蚀全耐的万能桶，做起来最贵。 */
	public static final Item CERAMIC_BUCKET = bucket("ceramic_bucket", BucketMaterial.CERAMIC);

	private static Item bucket(String name, BucketMaterial material) {
		return register(name, properties -> new MetalBucket(properties, material),
				new Item.Properties().stacksTo(16)
						.component(ModComponents.MEASURE, Measure.MOL.key()));
	}

	// 枪械（框架 / 枪管 / 撞针 / 枪机 / 弹头）不在这里 ——
	// 整块搬到了附属模组 geometallurgy_guns，见那边的 GunItems。
	// 本模组只提供金属与成分，枪怎么造是枪包的事。

	public static void init() {
		// 触发类加载。
	}

	private static Item drillBit(String name, DrillMaterial material) {
		return register(name, properties -> new DrillBitItem(material, properties),
				new Item.Properties().durability(material.durability()));
	}

	/** 材质 → 对应的钻头物品。存档读回钻头时要用。 */
	public static Item drillBit(DrillMaterial material) {
		return switch (material) {
			case STONE -> DRILL_BIT_STONE;
			case GOLD -> DRILL_BIT_GOLD;
			case IRON -> DRILL_BIT_IRON;
			case DIAMOND -> DRILL_BIT_DIAMOND;
		};
	}

	private static Item register(String name, Function<Item.Properties, Item> factory, Item.Properties properties) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Geometallurgy.id(name));
		Item item = factory.apply(properties.setId(key));
		Registry.register(BuiltInRegistries.ITEM, key, item);
		return item;
	}
}
