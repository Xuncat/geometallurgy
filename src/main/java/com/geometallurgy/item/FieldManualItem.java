package com.geometallurgy.item;

import java.util.List;

import com.geometallurgy.manual.ManualRecipes;
import com.geometallurgy.menu.ManualMenu;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 任务书：右键打开。
 *
 * <p>这本书取代了「装一个帕秋莉手册」那条路 —— 帕秋莉本体还没跟上 26.x，
 * 而开局最需要的恰恰是一本能翻的说明。正文全在语言文件里（见
 * {@link com.geometallurgy.manual.Manual}），配方则是在开书这一刻
 * <b>从游戏自己的配方管理器现读</b>的（见 {@link ManualRecipes}）。
 *
 * <p>它会在玩家<b>第一次进世界时自动发一本</b>（见
 * {@link com.geometallurgy.manual.ManualGrant}），丢了也能再合成一本。
 */
public class FieldManualItem extends Item {

	public FieldManualItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
			serverPlayer.openMenu(new Opening(ManualRecipes.buildPayload(serverLevel)));
			level.playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN,
					SoundSource.PLAYERS, 0.7F, 1.0F);
		}
		// 返回 SUCCESS 而不是 PASS：手上有物品时 PASS 会被原版当成「这一下不算数」，
		// 表现就是点了没反应。
		return InteractionResult.SUCCESS;
	}

	/** 开屏数据就是配方载荷，没有别的。 */
	private record Opening(List<ItemStack> recipes)
			implements ExtendedMenuProvider<List<ItemStack>> {

		@Override
		public Component getDisplayName() {
			return Component.translatable("item.geometallurgy.field_manual");
		}

		@Override
		public AbstractContainerMenu createMenu(int syncId, Inventory inventory, Player player) {
			return new ManualMenu(syncId, inventory, this.recipes);
		}

		@Override
		public List<ItemStack> getScreenOpeningData(ServerPlayer player) {
			return this.recipes;
		}
	}
}
