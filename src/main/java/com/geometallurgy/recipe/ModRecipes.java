package com.geometallurgy.recipe;

import com.mojang.serialization.MapCodec;
import com.geometallurgy.Geometallurgy;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.crafting.RecipeSerializer;

/**
 * 本模组的配方序列化器。
 *
 * <p>目前只有一条：{@link IngotCastingRecipe}。它是无参数配方 —— 一条 JSON
 * （{@code {"type":"geometallurgy:ingot_casting"}}）就代表整条规则，所有判断都在代码里，
 * 所以编解码用 {@code unit} 即可，不需要任何字段。
 *
 * <p>{@link net.minecraft.world.item.crafting.RecipeType} 不用自己注册：铸造配方的
 * {@code getType()} 走 {@code CraftingRecipe} 的默认实现，就是原版的 {@code crafting} ——
 * 它本来就是在工作台上用的。
 */
public final class ModRecipes {

	private ModRecipes() {
	}

	public static final RecipeSerializer<IngotCastingRecipe> INGOT_CASTING = Registry.register(
			BuiltInRegistries.RECIPE_SERIALIZER,
			Geometallurgy.id("ingot_casting"),
			new RecipeSerializer<>(
					MapCodec.unit(IngotCastingRecipe::new),
					StreamCodec.unit(new IngotCastingRecipe())));

	public static final RecipeSerializer<ElectrodeRodRecipe> ELECTRODE_ROD = Registry.register(
			BuiltInRegistries.RECIPE_SERIALIZER,
			Geometallurgy.id("electrode_rod"),
			new RecipeSerializer<>(
					MapCodec.unit(ElectrodeRodRecipe::new),
					StreamCodec.unit(new ElectrodeRodRecipe())));

	// 部件加工（锭 → 枪管 / 撞针 / 枪机）那条 gun_part 配方随枪械搬到了
	// 附属模组 geometallurgy_guns。

	public static void init() {
		// 触发类加载。
	}
}
