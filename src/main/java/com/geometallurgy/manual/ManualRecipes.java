package com.geometallurgy.manual;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * 把真实配方读成「书上能画的一格一格」。
 *
 * <h2>为什么在服务端读</h2>
 * 客户端根本没有按 id 取配方的能力 —— {@code ClientRecipeContainer} 只带属性集与切石机配方，
 * 完整的 {@code RecipeManager} 只在服务端。所以由服务端读、把结果塞进开屏数据发过去；
 * 书里<b>不存任何配方数据</b>，玩家改数据包改了配方，书跟着变，不会出现「书上是旧配方」。
 *
 * <h2>怎么把配方摆成 3×3</h2>
 * 靠 {@link PlacementInfo}：它本来就带「第几个格子用哪一号原料」的映射
 * （{@code slotsToIngredientIndex()}，空槽是 {@code EMPTY_SLOT = -1}），
 * 原版配方书用的就是这一份。有它就不必去分辨有序 / 无序配方，也不用管图案长什么样。
 */
public final class ManualRecipes {

	private ManualRecipes() {
	}

	/**
	 * 打包全部配方网格，顺序与 {@link Manual#recipeEntries()} 一致。
	 *
	 * <p>每条占 {@link Manual#RECIPE_SLOTS} 格：0~8 是合成格（行优先），9 是产物。
	 */
	public static List<ItemStack> buildPayload(ServerLevel level) {
		List<Manual.Entry> entries = Manual.recipeEntries();
		List<ItemStack> out = new ArrayList<>(entries.size() * Manual.RECIPE_SLOTS);
		for (Manual.Entry entry : entries) {
			out.addAll(gridOf(level, entry));
		}
		return out;
	}

	/** 一条配方的 10 格。读不到就整条填空，界面会退化成「只有文字」。 */
	private static List<ItemStack> gridOf(ServerLevel level, Manual.Entry entry) {
		List<ItemStack> slots = new ArrayList<>(Manual.RECIPE_SLOTS);
		for (int i = 0; i < Manual.RECIPE_SLOTS; i++) {
			slots.add(ItemStack.EMPTY);
		}

		ResourceKey<Recipe<?>> key = ResourceKey.create(Registries.RECIPE,
				Identifier.fromNamespaceAndPath(entry.namespace(), entry.recipe()));
		RecipeHolder<?> holder = level.getServer().getRecipeManager().byKey(key).orElse(null);
		if (holder == null) {
			return slots;
		}

		PlacementInfo placement = holder.value().placementInfo();
		if (!placement.isImpossibleToPlace()) {
			List<Ingredient> ingredients = placement.ingredients();
			var mapping = placement.slotsToIngredientIndex();
			for (int slot = 0; slot < 9 && slot < mapping.size(); slot++) {
				int index = mapping.getInt(slot);
				if (index == PlacementInfo.EMPTY_SLOT || index < 0 || index >= ingredients.size()) {
					continue;
				}
				slots.set(slot, representative(ingredients.get(index)));
			}
		}
		slots.set(9, productOf(entry));
		return slots;
	}

	/**
	 * 一格原料拿哪个物品代表。
	 *
	 * <p>标签原料（例如 {@code #minecraft:coals}）会枚举出一串物品，只取第一个 ——
	 * 原版配方书也是这么显示的（它会轮播）。
	 */
	private static ItemStack representative(Ingredient ingredient) {
		return ingredient.getSingleItem()
				.map(holder -> new ItemStack(holder.value()))
				.orElseGet(() -> ingredient.items().findFirst()
						.map(holder -> new ItemStack(holder.value()))
						.orElse(ItemStack.EMPTY));
	}

	/**
	 * 产物。配方 id 与产物物品 id 同名，所以直接按 id 取物品。
	 *
	 * <p>命名空间取条目自己的 —— 内置条目是本模组，附属模组注册的条目是它自己的，
	 * 所以书上也能画出附属模组的产物（例如 {@code geometallurgy_guns:gun_bench}）。
	 */
	private static ItemStack productOf(Manual.Entry entry) {
		Registry<Item> items = BuiltInRegistries.ITEM;
		Item item = items.getValue(
				Identifier.fromNamespaceAndPath(entry.namespace(), entry.recipe()));
		if (item == null || item == Items.AIR) {
			return ItemStack.EMPTY;
		}
		return new ItemStack(item);
	}
}
