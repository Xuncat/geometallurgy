package com.geometallurgy.recipe;

import java.util.Map;

import com.geometallurgy.item.ModItems;
import com.geometallurgy.metal.Ingots;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * 「拉棒」特殊配方：把一块高熔点金属锭拉成电极棒。
 *
 * <h2>为什么它也是一条特殊配方</h2>
 * 与 {@link IngotCastingRecipe} 同一个理由 —— 钨锭与钼锭共用一个物品 id，
 * 原版 {@code Ingredient} 只认 id 不认组件，所以「只许拿钨锭来拉」这件事在普通配方里写不出来。
 * 若退而求其次写成「任意锭 ×2 → 钨棒」，玩家就能烧一根铁锭换一根钨电极，那就荒唐了。
 *
 * <h2>为什么值得存在</h2>
 * 电解炉的电极有两个档次：碳棒便宜但只撑 8 个炉次，钨棒/钼棒能撑 64 / 40 个。
 * 长期跑电解的人迟早要换惰性电极，这条配方就是那个台阶。顺便它也让钨、钼这两条矿脉
 * 除了「铸成原版锭换钱」之外，多了一个真正在产线里用得上的去处。
 */
public class ElectrodeRodRecipe extends CustomRecipe {

	/** 能拉成棒的金属 → 对应的棒。表里没有的金属本配方一概不认。 */
	private static final Map<String, Item> RODS = Map.of(
			"tungsten", ModItems.TUNGSTEN_ROD,
			"molybdenum", ModItems.MOLYBDENUM_ROD);

	/** 一锭一棍拉出两根棒。 */
	private static final int OUTPUT = 2;

	public ElectrodeRodRecipe() {
		super();
	}

	@Override
	public boolean matches(CraftingInput input, Level level) {
		return metalOf(input) != null;
	}

	@Override
	public ItemStack assemble(CraftingInput input) {
		String metal = metalOf(input);
		Item rod = metal == null ? null : RODS.get(metal);
		return rod == null ? ItemStack.EMPTY : new ItemStack(rod, OUTPUT);
	}

	/**
	 * 合成格里必须<b>恰好</b>是一锭 + 一棍，且锭的主金属在表里。
	 *
	 * <p>要求恰好，是因为产出是按「一锭一棍 → 两根棒」定的；多放了东西还照出，
	 * 就成了可以拿杂物凑数的配方。
	 */
	private static String metalOf(CraftingInput input) {
		ItemStack ingot = null;
		int sticks = 0;
		for (int i = 0; i < input.size(); i++) {
			ItemStack stack = input.getItem(i);
			if (stack.isEmpty()) {
				continue;
			}
			if (stack.is(Items.STICK)) {
				sticks += stack.getCount();
				continue;
			}
			if (ingot != null) {
				return null;
			}
			ingot = stack;
		}
		if (ingot == null || ingot.getCount() != 1 || sticks != 1) {
			return null;
		}
		if (!Ingots.isIngot(ingot)) {
			return null;
		}
		String metal = Ingots.metalKey(ingot);
		return RODS.containsKey(metal) ? metal : null;
	}

	@Override
	public RecipeSerializer<? extends CustomRecipe> getSerializer() {
		return ModRecipes.ELECTRODE_ROD;
	}
}
