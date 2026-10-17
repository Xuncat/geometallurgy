package com.geometallurgy.client;

import com.geometallurgy.blockentity.CoalGeneratorBlockEntity;
import com.geometallurgy.menu.CoalGeneratorMenu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

/**
 * 火力发电机的界面。
 *
 * <p>借原版熔炉贴图的边框与物品栏区，但机器区抹成纯色后自己排布：
 * 燃料槽与输出槽用原版空槽精灵绘制，火焰用原版 {@code lit_progress} 精灵按剩余燃料裁剪，
 * 其余位置留给能量条与数值。
 */
public class CoalGeneratorScreen extends AbstractContainerScreen<CoalGeneratorMenu> {

	private static final Identifier TEXTURE =
			Identifier.withDefaultNamespace("textures/gui/container/furnace.png");
	private static final Identifier LIT_PROGRESS_SPRITE =
			Identifier.withDefaultNamespace("container/furnace/lit_progress");
	/** 原版通用空槽精灵。 */
	private static final Identifier SLOT_SPRITE =
			Identifier.withDefaultNamespace("container/slot");

	/** 原版容器界面的面板底色。 */
	private static final int PANEL_COLOR = 0xFFC6C6C6;
	/** 原版界面文字色。 */
	private static final int LABEL_COLOR = 0xFF404040;
	private static final int BAR_BACKGROUND = 0xFF8B8B8B;
	private static final int BAR_FILL = 0xFF3FBF5F;

	/** 机器区纵向范围（抹成纯色的部分），避开标题行与物品栏标签行。 */
	private static final int MACHINE_TOP = 13;
	private static final int MACHINE_BOTTOM = 71;

	/** 燃料槽：原版熔炉的输入位。 */
	private static final int FUEL_SLOT_X = 56;
	private static final int FUEL_SLOT_Y = 17;
	/** 输出槽：原版熔炉的燃料位。 */
	private static final int OUTPUT_SLOT_X = 56;
	private static final int OUTPUT_SLOT_Y = 53;

	private static final int FLAME_X = 56;
	private static final int FLAME_Y = 36;

	private static final int BAR_X = 79;
	private static final int BAR_Y = 38;
	private static final int BAR_WIDTH = 24;
	private static final int BAR_HEIGHT = 8;

	public CoalGeneratorScreen(CoalGeneratorMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(extractor, mouseX, mouseY, partialTick);

		extractor.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, this.leftPos, this.topPos, 0F, 0F,
				this.imageWidth, this.imageHeight, 256, 256);

		// 熔炉贴图自带的三个槽位用不上 —— 把机器区抹成纯色，再按自己的布局画。
		extractor.fill(this.leftPos + 4, this.topPos + MACHINE_TOP,
				this.leftPos + this.imageWidth - 4, this.topPos + MACHINE_BOTTOM, PANEL_COLOR);

		this.drawSlot(extractor, FUEL_SLOT_X, FUEL_SLOT_Y);
		this.drawSlot(extractor, OUTPUT_SLOT_X, OUTPUT_SLOT_Y);
		this.drawFlame(extractor);
		this.drawEnergyBar(extractor);

		UiText.text(extractor, this.font,
				Component.translatable("gui.geometallurgy.generator_energy",
						this.menu.energy(), CoalGeneratorBlockEntity.CAPACITY),
				this.leftPos + 80, this.topPos + 6, LABEL_COLOR);
	}

	private void drawSlot(GuiGraphicsExtractor extractor, int x, int y) {
		extractor.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, 18, 18, 0, 0,
				this.leftPos + x - 1, this.topPos + y - 1, 18, 18);
	}

	/** 火焰从下往上按剩余燃料裁剪，与原版熔炉的公式一致。 */
	private void drawFlame(GuiGraphicsExtractor extractor) {
		float burn = this.menu.burnProgress();
		if (burn <= 0F) {
			return;
		}
		int litHeight = Mth.ceil(burn * 13.0F) + 1;
		extractor.blitSprite(RenderPipelines.GUI_TEXTURED, LIT_PROGRESS_SPRITE, 14, 14,
				0, 14 - litHeight,
				this.leftPos + FLAME_X, this.topPos + FLAME_Y + 14 - litHeight,
				14, litHeight);
	}

	private void drawEnergyBar(GuiGraphicsExtractor extractor) {
		int x = this.leftPos + BAR_X;
		int y = this.topPos + BAR_Y;
		int right = x + BAR_WIDTH;
		int bottom = y + BAR_HEIGHT;

		extractor.fill(x, y, right, bottom, BAR_BACKGROUND);
		long capacity = Math.max(1L, CoalGeneratorBlockEntity.CAPACITY);
		int filled = Mth.ceil((float) Math.min(this.menu.energy(), capacity) / (float) capacity * BAR_WIDTH);
		if (filled > 0) {
			extractor.fill(x, y, x + filled, bottom, BAR_FILL);
		}

		// 边框用四条 fill 画：GUI 的 outline() 参数语义是 (x, y, width, height)，
		// 与 fill() 的 (x1, y1, x2, y2) 不一致，混用会画出巨框。
		extractor.fill(x - 1, y - 1, right + 1, y, LABEL_COLOR);
		extractor.fill(x - 1, bottom, right + 1, bottom + 1, LABEL_COLOR);
		extractor.fill(x - 1, y - 1, x, bottom + 1, LABEL_COLOR);
		extractor.fill(right, y - 1, right + 1, bottom + 1, LABEL_COLOR);
	}
}
