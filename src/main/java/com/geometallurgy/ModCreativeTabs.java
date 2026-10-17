package com.geometallurgy;

import java.util.List;

import com.geometallurgy.block.ModBlocks;
import com.geometallurgy.chemical.Chemical;
import com.geometallurgy.chemical.MolStock;
import com.geometallurgy.item.BucketMaterial;
import com.geometallurgy.item.MetalBucket;
import com.geometallurgy.item.ModItems;

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

/** 创造模式物品栏页签。 */
public final class ModCreativeTabs {

	private ModCreativeTabs() {
	}

	public static final ResourceKey<CreativeModeTab> VEIN_MINE_KEY =
			ResourceKey.create(Registries.CREATIVE_MODE_TAB, Geometallurgy.id("vein_mine"));

	public static final CreativeModeTab VEIN_MINE = Registry.register(
			BuiltInRegistries.CREATIVE_MODE_TAB,
			VEIN_MINE_KEY,
			FabricCreativeModeTab.builder()
					.title(Component.translatable("itemGroup.geometallurgy.vein_mine"))
					.icon(() -> new ItemStack(ModBlocks.VEIN_EXTRACTOR))
					.displayItems((parameters, output) -> {
						output.accept(ModBlocks.VEIN_EXTRACTOR);
						output.accept(ModBlocks.COAL_GENERATOR);
						output.accept(ModBlocks.FLUID_VEIN_EXTRACTOR);
						output.accept(ModBlocks.MAGNETIC_SEPARATOR);
						output.accept(ModBlocks.CRUSHER);
						output.accept(ModBlocks.CENTRIFUGE);
						output.accept(ModBlocks.REDUCTION_FURNACE);
						output.accept(ModBlocks.ELECTROLYSIS_FURNACE);
						output.accept(ModBlocks.DISPLACEMENT_FURNACE);
						output.accept(ModBlocks.BATTERY);
						output.accept(ModBlocks.WIRELESS_SENSOR);
						output.accept(ModBlocks.REACTION_VESSEL);
						output.accept(ModBlocks.MIXER);
						output.accept(ModBlocks.METAL_BARREL);
						output.accept(ModBlocks.GLASS_BARREL);
						output.accept(ModBlocks.LEAD_BARREL);
						output.accept(ModBlocks.CERAMIC_BARREL);
						output.accept(ModItems.FIELD_MANUAL);
						output.accept(ModItems.MINERAL_PROSPECTOR);
						output.accept(ModItems.ENGINEERING_CONFIGURATOR);
						output.accept(ModItems.DRILL_BIT_STONE);
						output.accept(ModItems.DRILL_BIT_GOLD);
						output.accept(ModItems.DRILL_BIT_IRON);
						output.accept(ModItems.DRILL_BIT_DIAMOND);
						output.accept(ModItems.CARBON_POWDER);
						output.accept(ModItems.METAL_POWDER);
						output.accept(ModItems.CARBON_ROD);
						output.accept(ModItems.TUNGSTEN_ROD);
						output.accept(ModItems.MOLYBDENUM_ROD);
						output.accept(ModItems.METAL_BUCKET);
						output.accept(ModItems.GLASS_BUCKET);
						output.accept(ModItems.LEAD_BUCKET);
						output.accept(ModItems.CERAMIC_BUCKET);
						// 药剂桶：满桶的 mol 账，颜色由内容物决定（见 MetalBucket#refreshColor）。
						// 材质按 BucketMaterial.cheapestFor 自动挑 —— 装酸碱的药剂不会被发成铁桶。
						// 反应釜/搅拌器之外的任何机器都不会自己造出化学物质，所以这里必须把
						// 全套药剂都给出来，否则新加的三十几个反应在生存模式里永远点不着。
						for (String id : Chemical.ids()) {
							output.accept(bucketOf(id));
						}
						// 锭只挂一个空白的（成分表为空）——它是个容器，具体是哪种金属由炉子决定
						output.accept(ModItems.INGOT);
					})
					.build());

	/** 酿造页签里只放最常见的十来种，免得把原版页签塞满。 */
	private static final List<String> COMMON_REAGENTS = List.of(
			"water", "hydrochloric_acid", "sulfuric_acid", "nitric_acid",
			"oxalic_acid", "sodium_hydroxide", "calcium_hydroxide", "ammonium_hydroxide",
			"sodium_chloride", "sodium_carbonate", "rare_earth_oxide", "manganese_dioxide",
			"iron_oxide", "calcium_carbonate", "silicon_dioxide");

	/** 一份满桶（15 mol）的药剂：材质自动挑最便宜又耐得住的那种。 */
	private static ItemStack bucketOf(String chemicalId) {
		BucketMaterial material = BucketMaterial.cheapestFor(chemicalId);
		return MetalBucket.create(MetalBucket.itemFor(material),
				MolStock.of(chemicalId, MolStock.BUCKET_MOL));
	}

	/** 原版页签的 ResourceKey。26.x 里 {@code CreativeModeTabs} 的字段是私有的，只能自己按 id 造。 */
	private static ResourceKey<CreativeModeTab> vanilla(String path) {
		return ResourceKey.create(Registries.CREATIVE_MODE_TAB,
				Identifier.withDefaultNamespace(path));
	}

	public static void init() {
		// 光有自己的页签不够用。Fabric 会把创造页签栏分页（每页 10 个，原版 14 个页签占满第 1 页），
		// 模组页签被排到第 2 页 —— 玩家不点那个不起眼的翻页箭头就永远看不到，
		// 表现就是「创造物品栏里没有这个模组的东西」。
		// 所以把机器与物品**同时**挂进原版页签：首页直接可见，搜索也一定命中。
		// 自己的页签保留，作为分类浏览的入口。
		CreativeModeTabEvents.modifyOutputEvent(vanilla("redstone_blocks")).register(output -> {
			output.accept(ModBlocks.VEIN_EXTRACTOR);
			output.accept(ModBlocks.COAL_GENERATOR);
			output.accept(ModBlocks.FLUID_VEIN_EXTRACTOR);
			output.accept(ModBlocks.MAGNETIC_SEPARATOR);
			output.accept(ModBlocks.CRUSHER);
			output.accept(ModBlocks.CENTRIFUGE);
			output.accept(ModBlocks.REDUCTION_FURNACE);
			output.accept(ModBlocks.ELECTROLYSIS_FURNACE);
			output.accept(ModBlocks.DISPLACEMENT_FURNACE);
			output.accept(ModBlocks.BATTERY);
			output.accept(ModBlocks.WIRELESS_SENSOR);
			output.accept(ModBlocks.REACTION_VESSEL);
			output.accept(ModBlocks.MIXER);
		});
		// 储罐是容器，挂到「功能方块」页签（模组页签之外再给一条必经之路）
		CreativeModeTabEvents.modifyOutputEvent(vanilla("functional_blocks")).register(output -> {
			output.accept(ModBlocks.METAL_BARREL);
			output.accept(ModBlocks.GLASS_BARREL);
			output.accept(ModBlocks.LEAD_BARREL);
			output.accept(ModBlocks.CERAMIC_BARREL);
		});
		CreativeModeTabEvents.modifyOutputEvent(vanilla("tools_and_utilities")).register(output -> {
			output.accept(ModItems.FIELD_MANUAL);
			output.accept(ModItems.MINERAL_PROSPECTOR);
			output.accept(ModItems.ENGINEERING_CONFIGURATOR);
			output.accept(ModItems.DRILL_BIT_STONE);
			output.accept(ModItems.DRILL_BIT_GOLD);
			output.accept(ModItems.DRILL_BIT_IRON);
			output.accept(ModItems.DRILL_BIT_DIAMOND);
		});
		CreativeModeTabEvents.modifyOutputEvent(vanilla("ingredients")).register(output -> {
			output.accept(ModItems.CARBON_POWDER);
			output.accept(ModItems.METAL_POWDER);
			output.accept(ModItems.CARBON_ROD);
			output.accept(ModItems.TUNGSTEN_ROD);
			output.accept(ModItems.MOLYBDENUM_ROD);
			output.accept(ModItems.METAL_BUCKET);
			output.accept(ModItems.GLASS_BUCKET);
			output.accept(ModItems.LEAD_BUCKET);
			output.accept(ModItems.CERAMIC_BUCKET);
			for (String id : COMMON_REAGENTS) {
				output.accept(bucketOf(id));
			}
			output.accept(ModItems.INGOT);
		});
	}
}
