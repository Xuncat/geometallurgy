package com.geometallurgy.menu;

import com.geometallurgy.blockentity.MixerBlockEntity;

import net.minecraft.util.Unit;
import net.minecraft.world.entity.player.Inventory;

/**
 * 搅拌器的界面菜单：与反应釜同构，多一条 A:B 比率的读数。
 *
 * <p>比率按钮通过 {@code clickMenuButton} 的 7 / 8 / 9 号 id 调整
 * （减 5 / 加 5 / 复位 50），六面与相态沿用基类的 0..6 号。
 */
public class MixerMenu extends AbstractReactorMenu {

	public static final int GUI_WIDTH = 176;
	public static final int GUI_HEIGHT = 238;

	public MixerMenu(int syncId, Inventory playerInventory, MixerBlockEntity blockEntity) {
		super(ModMenus.MIXER, syncId, blockEntity);
		addPlayerInventory(playerInventory);
	}

	public MixerMenu(int syncId, Inventory playerInventory, Unit unit) {
		super(ModMenus.MIXER, syncId);
		addPlayerInventory(playerInventory);
	}

	@Override
	public double bufferCapacity(int index) {
		return MixerBlockEntity.BUFFER_CAPACITY;
	}
}
