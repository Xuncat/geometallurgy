package com.geometallurgy.client;

import java.util.List;
import java.util.Locale;

import com.geometallurgy.blockentity.VeinExtractorBlockEntity;
import com.geometallurgy.menu.VeinExtractorMenu;
import com.geometallurgy.vein.BlockVeinDistribution;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

/**
 * 矿脉抽取机的界面。
 *
 * <p>直接复用原版发射器界面的贴图与 3x3 槽位布局。绘制走原版的
 * {@code extractBackground} 钩子：框架层（背景、槽位、物品栏标签）由基类负责，
 * 这里只补贴图与本模组自己的信息，避免重复绘制。
 */
public class VeinExtractorScreen extends AbstractContainerScreen<VeinExtractorMenu> {

	/** 原版发射器界面贴图，176x166，3x3 槽位与我们的产物缓冲一致。 */
	private static final Identifier TEXTURE =
			Identifier.withDefaultNamespace("textures/gui/container/dispenser.png");
	/** 原版熔炉的箭头精灵，这里用来表示内部能量缓冲的充能程度。 */
	private static final Identifier ARROW_SPRITE =
			Identifier.withDefaultNamespace("container/furnace/burn_progress");

	/** 原版界面文字色。 */
	private static final int LABEL_COLOR = 0xFF404040;

	public VeinExtractorScreen(VeinExtractorMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(extractor, mouseX, mouseY, partialTick);

		extractor.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, this.leftPos, this.topPos, 0F, 0F,
				this.imageWidth, this.imageHeight, 256, 256);

		this.drawVeinList(extractor);
		this.drawEnergy(extractor);
	}

	/** 3x3 槽位右侧的空白区：本区块勘测到的矿脉分布。 */
	private void drawVeinList(GuiGraphicsExtractor extractor) {
		List<BlockVeinDistribution.Entry> entries = this.menu.distribution().entries();
		int x = this.leftPos + 116;
		int y = this.topPos + 20;

		if (entries.isEmpty()) {
			UiText.text(extractor, this.font, Component.translatable("gui.geometallurgy.no_vein"), x, y, LABEL_COLOR);
			return;
		}
		for (BlockVeinDistribution.Entry entry : entries) {
			Component line = Component.translatable("gui.geometallurgy.ore_line",
					entry.type().displayName(),
					String.format(Locale.ROOT, "%.1f%%", entry.rarity() * 100F));
			UiText.text(extractor, this.font, line, x, y, LABEL_COLOR);
			y += 10;
		}
	}

	/** 3x3 槽位左侧的空白区：丰度与能量缓冲。 */
	private void drawEnergy(GuiGraphicsExtractor extractor) {
		int x = this.leftPos + 6;

		String abundance = String.format(Locale.ROOT, "%.0f%%", this.menu.distribution().abundance() * 100F);
		UiText.text(extractor, this.font, Component.translatable("gui.geometallurgy.abundance", abundance),
				x, this.topPos + 20, LABEL_COLOR);

		long energy = this.menu.energy();
		long capacity = Math.max(1L, VeinExtractorBlockEntity.CAPACITY);
		int filled = Mth.ceil((float) Math.min(energy, capacity) / (float) capacity * 24F);
		if (filled > 0) {
			extractor.blitSprite(RenderPipelines.GUI_TEXTURED, ARROW_SPRITE, 24, 16, 0, 0,
					x, this.topPos + 34, filled, 16);
		}
		UiText.text(extractor, this.font, Component.translatable("gui.geometallurgy.energy_short", energy),
				x, this.topPos + 54, LABEL_COLOR);
	}
}
