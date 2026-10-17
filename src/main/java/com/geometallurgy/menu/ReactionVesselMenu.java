package com.geometallurgy.menu;

import com.geometallurgy.blockentity.ReactionVesselBlockEntity;

import net.minecraft.util.Unit;
import net.minecraft.world.entity.player.Inventory;

/**
 * 反应釜的界面菜单（版本 B）：只有一个催化剂槽，其余都是读数与六面配置。
 *
 * <p>输出相态、状态、混合槽计量、六面角色都通过 {@link net.minecraft.world.inventory.ContainerData}
 * 从服务端同步过来。
 */
public class ReactionVesselMenu extends AbstractReactorMenu {

	public static final int GUI_WIDTH = 176;
	public static final int GUI_HEIGHT = 238;

	public ReactionVesselMenu(int syncId, Inventory playerInventory, ReactionVesselBlockEntity blockEntity) {
		super(ModMenus.REACTION_VESSEL, syncId, blockEntity);
		addPlayerInventory(playerInventory);
	}

	public ReactionVesselMenu(int syncId, Inventory playerInventory, Unit unit) {
		super(ModMenus.REACTION_VESSEL, syncId);
		addPlayerInventory(playerInventory);
	}

	@Override
	public double bufferCapacity(int index) {
		return ReactionVesselBlockEntity.INPUT_CAPACITY;
	}
}
