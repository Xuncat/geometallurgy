package com.geometallurgy.menu;

import java.util.List;

import com.geometallurgy.manual.Manual;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * 任务书的菜单。
 *
 * <p>它<b>一个槽都没有</b>：这里说的「格」是书上画的配方网格，不是可交互的槽位。
 * 唯一的同步内容就是那份配方载荷（见 {@link com.geometallurgy.manual.ManualRecipes}），
 * 由服务端读真实配方之后随开屏数据发过来。
 *
 * <p>之所以还走菜单而不是直接开一个纯客户端界面，是为了复用本模组其它界面那条已经跑通的
 * 链路（{@code ExtendedMenuType} + {@code ExtendedMenuProvider} + {@code MenuScreens.register}），
 * 免得物品类去直接引用客户端类、在专用服务端上被类加载器揪住。
 */
public class ManualMenu extends AbstractContainerMenu {

	/** 配方载荷：第 i 条配方占 [i*RECIPE_SLOTS, (i+1)*RECIPE_SLOTS)。 */
	private final List<ItemStack> recipes;

	public ManualMenu(int syncId, Inventory playerInventory, List<ItemStack> recipes) {
		super(ModMenus.MANUAL, syncId);
		this.recipes = List.copyOf(recipes);
	}

	/** 第 {@code index} 条配方的 10 格（0~8 合成格、9 产物）；没有就返回空表。 */
	public List<ItemStack> recipeGrid(int index) {
		int from = index * Manual.RECIPE_SLOTS;
		int to = from + Manual.RECIPE_SLOTS;
		if (index < 0 || to > this.recipes.size()) {
			return List.of();
		}
		return this.recipes.subList(from, to);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean stillValid(Player player) {
		// 书拿在手上、丢在地上都能继续看 —— 不绑定任何方块或实体。
		return true;
	}
}
