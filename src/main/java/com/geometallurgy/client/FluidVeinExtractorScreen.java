package com.geometallurgy.client;

import java.util.List;
import java.util.Locale;

import com.geometallurgy.blockentity.FluidVeinExtractorBlockEntity;
import com.geometallurgy.geology.RockType;
import com.geometallurgy.item.DrillMaterial;
import com.geometallurgy.menu.FluidVeinExtractorMenu;
import com.geometallurgy.slurry.SlurryPart;
import com.geometallurgy.slurry.VirtualSlurry;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * 流体碎矿挖掘机的界面。自绘面板（不借原版容器贴图，因为要多塞一行钻头状态）。
 *
 * <p>版式：左边一根竖直能量条、中间一根矿浆液位条（按罐内各相的颜色分段），
 * 液位条下方是<b>钻头槽</b>；右侧八行文字 —— 原岩、液量、钻头、停机原因，
 * 加四行罐内成分。
 *
 * <p><b>只有这些是给玩家看的。</b>矿物的丰度、品位与杂质组成属于机器内部数据：
 * 它们会被写进存档（下游机器要用），但不在这里露脸。
 */
public class FluidVeinExtractorScreen extends AbstractContainerScreen<FluidVeinExtractorMenu> {

	private static final int PANEL_COLOR = 0xFFC6C6C6;
	private static final int HIGHLIGHT = 0xFFFFFFFF;
	private static final int SHADOW = 0xFF555555;
	private static final int LABEL_COLOR = 0xFF404040;
	private static final int BAR_BACKGROUND = 0xFF8B8B8B;
	private static final int BAR_FILL = 0xFF3FBF5F;
	private static final int TANK_BACKGROUND = 0xFF5A5A5A;
	private static final int OTHER_COLOR = 0xFF8B8B8B;
	private static final Identifier SLOT_SPRITE = Identifier.withDefaultNamespace("container/slot");

	/** 机器区下沿。分界线画在它下面 2 像素处。 */
	private static final int MACHINE_BOTTOM = 90;

	private static final int ENERGY_X = 10;
	private static final int ENERGY_Y = 16;
	private static final int ENERGY_W = 10;
	private static final int ENERGY_H = 60;

	private static final int TANK_X = 26;
	private static final int TANK_Y = 16;
	private static final int TANK_W = 18;
	private static final int TANK_H = 40;

	private static final int INFO_X = 52;
	private static final int INFO_Y = 16;
	private static final int INFO_RIGHT = 168;
	private static final int LINE_HEIGHT = 9;
	/** 留给成分的行数（前四行被原岩、液量、钻头、状态占用）。 */
	private static final int COMPOSITION_ROWS = 4;

	public static final int GUI_WIDTH = 176;
	public static final int GUI_HEIGHT = 190;

	public FluidVeinExtractorScreen(FluidVeinExtractorMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, GUI_WIDTH, GUI_HEIGHT);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(extractor, mouseX, mouseY, partialTick);
		this.drawPanel(extractor);
		this.drawSlots(extractor);
		this.drawEnergy(extractor);
		this.drawTank(extractor);
		this.drawInfo(extractor);
	}

	private void drawPanel(GuiGraphicsExtractor extractor) {
		int left = this.leftPos;
		int top = this.topPos;
		int right = left + this.imageWidth;
		int bottom = top + this.imageHeight;

		extractor.fill(left, top, right, bottom, PANEL_COLOR);
		extractor.fill(left, top, right, top + 1, HIGHLIGHT);
		extractor.fill(left, top, left + 1, bottom, HIGHLIGHT);
		extractor.fill(left, bottom - 1, right, bottom, SHADOW);
		extractor.fill(right - 1, top, right, bottom, SHADOW);
		extractor.fill(left + 4, top + MACHINE_BOTTOM + 2, right - 4, top + MACHINE_BOTTOM + 3, SHADOW);
	}

	/** 自绘面板必须自己补槽底纹，否则物品栏与钻头槽都是一片灰。 */
	private void drawSlots(GuiGraphicsExtractor extractor) {
		for (Slot slot : this.menu.slots) {
			extractor.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, 18, 18, 0, 0,
					this.leftPos + slot.x - 1, this.topPos + slot.y - 1, 18, 18);
		}
	}

	// ------------------------------------------------------------------ 能量

	private void drawEnergy(GuiGraphicsExtractor extractor) {
		int x = this.leftPos + ENERGY_X;
		int y = this.topPos + ENERGY_Y;
		int right = x + ENERGY_W;
		int bottom = y + ENERGY_H;

		extractor.fill(x, y, right, bottom, BAR_BACKGROUND);

		long capacity = Math.max(1L, FluidVeinExtractorBlockEntity.CAPACITY);
		int filled = Mth.ceil((float) Math.min(this.menu.energy(), capacity) / (float) capacity * ENERGY_H);
		if (filled > 0) {
			// 能量从下往上长
			extractor.fill(x + 1, bottom - filled, right - 1, bottom, BAR_FILL);
		}
		this.drawBorder(extractor, x, y, right, bottom);
	}

	// ------------------------------------------------------------------ 液位

	private void drawTank(GuiGraphicsExtractor extractor) {
		int x = this.leftPos + TANK_X;
		int y = this.topPos + TANK_Y;
		int right = x + TANK_W;
		int bottom = y + TANK_H;

		extractor.fill(x, y, right, bottom, TANK_BACKGROUND);

		double amount = this.menu.slurryAmount();
		if (amount > 0.0) {
			int filled = Mth.ceil(amount / VirtualSlurry.CAPACITY * TANK_H);
			List<FluidVeinExtractorMenu.CompositionEntry> entries = this.menu.composition();
			int cursor = bottom;
			int remaining = filled;
			for (int i = 0; i < entries.size() && remaining > 0; i++) {
				FluidVeinExtractorMenu.CompositionEntry entry = entries.get(i);
				int segment = (i == entries.size() - 1)
						? remaining
						: (int) Math.round(entry.fraction() * filled);
				segment = Math.min(segment, remaining);
				if (segment > 0) {
					extractor.fill(x + 1, cursor - segment, right - 1, cursor,
							0xFF000000 | entry.part().color());
					cursor -= segment;
					remaining -= segment;
				}
			}
			if (remaining > 0) {
				extractor.fill(x + 1, cursor - remaining, right - 1, cursor, OTHER_COLOR);
			}
		}
		this.drawBorder(extractor, x, y, right, bottom);
	}

	// ------------------------------------------------------------------ 文字

	private void drawInfo(GuiGraphicsExtractor extractor) {
		int x = this.leftPos + INFO_X;
		int y = this.topPos + INFO_Y;

		// 第 1 行：当前位置的原岩
		RockType rock = this.menu.rockType();
		Component rockLine = rock == null
				? Component.translatable("gui.geometallurgy.rock_unknown")
				: Component.translatable("gui.geometallurgy.rock_line", rock.displayName(), rock.family().displayName());
		UiText.text(extractor, this.font, rockLine, x, y, LABEL_COLOR);

		// 第 2 行：罐内液量
		String amount = String.format(Locale.ROOT, "%.0f / %.0f mB",
				this.menu.slurryAmount(), VirtualSlurry.CAPACITY);
		UiText.text(extractor, this.font, Component.translatable("gui.geometallurgy.slurry_line", amount),
				x, y + LINE_HEIGHT, LABEL_COLOR);

		// 第 3 行：钻头 —— 硬度门槛与剩余寿命，决定这台机器到底转不转得起来
		UiText.text(extractor, this.font, this.drillLine(), x, y + LINE_HEIGHT * 2, LABEL_COLOR);

		// 第 4 行：停机原因（或者正在开采）
		UiText.text(extractor, this.font, this.menu.status().translatable(),
				x, y + LINE_HEIGHT * 3, this.statusColor());

		int firstRow = INFO_Y + LINE_HEIGHT * 4;
		List<FluidVeinExtractorMenu.CompositionEntry> entries = this.menu.composition();
		if (entries.isEmpty()) {
			UiText.text(extractor, this.font, Component.translatable("gui.geometallurgy.slurry_empty"),
					x, this.topPos + firstRow, LABEL_COLOR);
			return;
		}

		// 含量最高的那一项几乎总是原岩 —— 矿脉本来就是块石头，矿物只是嵌在里面的颗粒。
		boolean overflow = entries.size() > COMPOSITION_ROWS;
		int shown = overflow ? COMPOSITION_ROWS - 1 : entries.size();

		double shownSum = 0.0;
		for (int i = 0; i < shown; i++) {
			FluidVeinExtractorMenu.CompositionEntry entry = entries.get(i);
			this.drawEntry(extractor, firstRow + i * LINE_HEIGHT, entry.part(), entry.fraction(), entry.part().color());
			shownSum += entry.fraction();
		}
		if (overflow) {
			// 余下的（含未被同步的零头）合成一行，免得列表溢出机器区
			double rest = Math.max(0.0, 1.0 - shownSum);
			if (rest > 0.0005) {
				this.drawEntry(extractor, firstRow + shown * LINE_HEIGHT, null, rest, OTHER_COLOR);
			}
		}
	}

	/** 钻头那一行。没装钻头时也要说清楚，否则「机器停了」就无从解释。 */
	private Component drillLine() {
		DrillMaterial material = this.menu.drillMaterial();
		if (material == null) {
			return Component.translatable("gui.geometallurgy.drill_none");
		}
		return Component.translatable("gui.geometallurgy.drill_line",
				Component.translatable("item.geometallurgy.drill_bit_" + material.id()),
				String.format(Locale.ROOT, "%.1f", material.maxMohs()),
				String.format(Locale.ROOT, "%.0f", this.menu.drillDurability() * 100.0));
	}

	private int statusColor() {
		return switch (this.menu.status()) {
			case RUNNING -> 0xFF1F7A33;
			case NO_POWER, NO_DRILL, DRILL_TOO_SOFT, OUTPUT_FULL -> 0xFF9A4A18;
		};
	}

	/**
	 * 一行成分：色块 + 名字（左对齐） + 占比（右对齐）。
	 *
	 * <p>{@code y} 是<b>相对</b>机器区顶端的偏移，这里再补上 {@code topPos} ——
	 * 界面里这两种坐标混用是最容易出错的地方。
	 */
	private void drawEntry(GuiGraphicsExtractor extractor, int y, SlurryPart part, double fraction, int color) {
		int x = this.leftPos + INFO_X;
		int row = this.topPos + y;
		extractor.fill(x, row + 2, x + 5, row + 7, 0xFF000000 | color);

		Component name = part == null
				? Component.translatable("gui.geometallurgy.slurry_other")
				: part.displayName();
		UiText.text(extractor, this.font, name, x + 8, row, LABEL_COLOR);

		String percent = String.format(Locale.ROOT, "%.1f%%", fraction * 100.0);
		UiText.text(extractor, this.font, percent,
				this.leftPos + INFO_RIGHT - this.font.width(percent), row, LABEL_COLOR);
	}

	// ------------------------------------------------------------------ 工具

	/**
	 * 用四条 fill 画边框。
	 *
	 * <p>不能用 {@code outline()}：GUI 的 outline 参数语义是 (x, y, width, height)，
	 * 与 fill 的 (x1, y1, x2, y2) 不一致，混用会画出巨框。
	 */
	private void drawBorder(GuiGraphicsExtractor extractor, int x, int y, int right, int bottom) {
		extractor.fill(x - 1, y - 1, right + 1, y, LABEL_COLOR);
		extractor.fill(x - 1, bottom, right + 1, bottom + 1, LABEL_COLOR);
		extractor.fill(x - 1, y - 1, x, bottom + 1, LABEL_COLOR);
		extractor.fill(right, y - 1, right + 1, bottom + 1, LABEL_COLOR);
	}
}
