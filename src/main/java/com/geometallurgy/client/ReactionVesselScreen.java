package com.geometallurgy.client;

import com.geometallurgy.blockentity.VesselRole;
import com.geometallurgy.menu.ReactionVesselMenu;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 反应釜界面：缓存条 + 六面配置 + 混合槽成分饼图。没有搅拌、没有比率、没有桶槽。
 */
public class ReactionVesselScreen extends AbstractReactorScreen<ReactionVesselMenu> {

	private static final int COLOR_DISABLED = 0xFF4A4A4A;
	private static final int COLOR_INPUT = 0xFF8A3F3F;
	private static final int COLOR_INPUT_B = 0xFF7F3F8A;
	private static final int COLOR_OUT_LIQUID = 0xFF2F6FAF;
	private static final int COLOR_OUT_GAS = 0xFF6FAF3F;
	private static final int COLOR_OUT_SOLID = 0xFFAF8A3F;

	public ReactionVesselScreen(ReactionVesselMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, ReactionVesselMenu.GUI_WIDTH, ReactionVesselMenu.GUI_HEIGHT);
	}

	@Override
	protected Component roleName(int ordinal) {
		return Component.translatable(role(ordinal).translationKey());
	}

	@Override
	protected int roleColor(int ordinal) {
		return switch (role(ordinal)) {
			case INPUT -> COLOR_INPUT;
			case INPUT_B -> COLOR_INPUT_B;
			case OUT_LIQUID -> COLOR_OUT_LIQUID;
			case OUT_GAS -> COLOR_OUT_GAS;
			case OUT_SOLID -> COLOR_OUT_SOLID;
			case DISABLED -> COLOR_DISABLED;
		};
	}

	@Override
	protected boolean showsRatio() {
		return false;
	}

	private static VesselRole role(int ordinal) {
		VesselRole[] values = VesselRole.values();
		return values[Math.floorMod(ordinal, values.length)];
	}
}
