package com.geometallurgy.recipe;

import java.util.Map;

import com.geometallurgy.metal.Ingots;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * 「铸造」特殊配方：把一锭高纯金属变成对应的原版锭。
 *
 * <h2>为什么需要它</h2>
 * 本模组只有一个锭物品，它是哪种金属由组件决定。而 26.x 的原版 {@code Ingredient} 已经收成
 * 一个纯物品集合（{@code final}，内部只有 {@code HolderSet}），<b>看不到物品组件</b>：
 * 配方里写 {@code "key":{"I":"geometallurgy:ingot"}}，它认的是物品 id，不管这一锭是铁还是铜。
 * 所以「≥70% 铁就当铁锭」这件事在原版的常规配方里根本写不出来。
 *
 * <p>破局的办法只能是绕开 {@code Ingredient}：{@link CustomRecipe}（原版盔甲染色、地图复制
 * 那一类走的就是它）拿到的是<b>整个合成格</b>，匹配逻辑完全由我们自己写。
 * 于是就有了这条配方 —— 它不认物品 id，只认「这一格里的锭，主金属是什么」。
 *
 * <h2>它换的是什么</h2>
 * 只换原版确实存在的三种锭（铁 / 铜 / 金），一换一。换成原版锭之后，这一件就能进原版的
 * 任何配方、任何标签了 —— 等于把模组的冶炼产线接回了原版的合成体系。
 *
 * <p>名字叫「铸造」是因为这一步本来就只是一次再熔铸：成分没变，只是换了个壳。
 */
public class IngotCastingRecipe extends CustomRecipe {

	/** 主金属 → 对应的原版锭。表里没有的金属（镍、钴、钨……）本配方一概不认。 */
	private static final Map<String, Item> VANILLA = Map.of(
			"iron", Items.IRON_INGOT,
			// 铁族分级键也铸回原版铁锭：原版没有对应的物品，而把生铁 / 钢留在
			// 「无处可用」的死胡同里，比放宽这条桥更伤游戏性。成分还写锭上，玩家看得见差别。
			Ingots.WROUGHT_IRON, Items.IRON_INGOT,
			Ingots.PIG_IRON, Items.IRON_INGOT,
			Ingots.STEEL, Items.IRON_INGOT,
			Ingots.ALLOY_STEEL, Items.IRON_INGOT,
			"copper", Items.COPPER_INGOT,
			"gold", Items.GOLD_INGOT);

	public IngotCastingRecipe() {
		super();
	}

	@Override
	public boolean matches(CraftingInput input, Level level) {
		return soleIngot(input) != null;
	}

	@Override
	public ItemStack assemble(CraftingInput input) {
		ItemStack ingot = soleIngot(input);
		if (ingot == null) {
			return ItemStack.EMPTY;
		}
		Item vanilla = VANILLA.get(Ingots.metalKey(ingot));
		return vanilla == null ? ItemStack.EMPTY : new ItemStack(vanilla);
	}

	/**
	 * 合成格里有没有「恰好一锭够纯的锭」。
	 *
	 * <p>要求独占一格、且只有一锭：多了会与「一换一」的产出对不上，
	 * 而一次只铸一锭也让这条配方不至于变成偷偷批量兑换的捷径。
	 */
	private static ItemStack soleIngot(CraftingInput input) {
		ItemStack found = null;
		for (int i = 0; i < input.size(); i++) {
			ItemStack stack = input.getItem(i);
			if (stack.isEmpty()) {
				continue;
			}
			if (found != null) {
				return null;
			}
			if (!Ingots.isIngot(stack) || stack.getCount() != 1) {
				return null;
			}
			if (!VANILLA.containsKey(Ingots.metalKey(stack))) {
				return null;
			}
			found = stack;
		}
		return found;
	}

	@Override
	public RecipeSerializer<? extends CustomRecipe> getSerializer() {
		return ModRecipes.INGOT_CASTING;
	}
}
