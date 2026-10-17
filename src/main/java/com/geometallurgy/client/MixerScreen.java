package com.geometallurgy.client;

import com.geometallurgy.blockentity.MixerRole;
import com.geometallurgy.menu.MixerMenu;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 搅拌器界面：在反应釜的读数之上多一条比率档位格与一个搅拌开关，
 * 六个面用的是搅拌器自己的六个角色（两个入口 + 三个按相态分的出口 + 关闭）。
 */
public class MixerScreen extends AbstractReactorScreen<MixerMenu> {

	private static final int COLOR_DISABLED = 0xFF4A4A4A;
	private static final int COLOR_INPUT_A = 0xFF8A3F3F;
	private static final int COLOR_INPUT_B = 0xFF7F3F8A;
	private static final int COLOR_OUT_LIQUID = 0xFF2F6FAF;
	private static final int COLOR_OUT_GAS = 0xFF6FAF3F;
	private static final int COLOR_OUT_SOLID = 0xFFAF8A3F;

	public MixerScreen(MixerMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, MixerMenu.GUI_WIDTH, MixerMenu.GUI_HEIGHT);
	}

	@Override
	protected Component roleName(int ordinal) {
		return Component.translatable(role(ordinal).translationKey());
	}

	@Override
	protected int roleColor(int ordinal) {
		return switch (role(ordinal)) {
			case INPUT_A -> COLOR_INPUT_A;
			case INPUT_B -> COLOR_INPUT_B;
			case OUT_LIQUID -> COLOR_OUT_LIQUID;
			case OUT_GAS -> COLOR_OUT_GAS;
			case OUT_SOLID -> COLOR_OUT_SOLID;
			case DISABLED -> COLOR_DISABLED;
		};
	}

	@Override
	protected boolean showsRatio() {
		return true;
	}

	private static MixerRole role(int ordinal) {
		MixerRole[] values = MixerRole.values();
		return values[Math.floorMod(ordinal, values.length)];
	}
}
