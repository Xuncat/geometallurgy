package com.geometallurgy.client;

import java.util.Locale;

import com.geometallurgy.menu.ProspectorMenu;
import com.geometallurgy.proto.OreMineral;
import com.geometallurgy.proto.Substance;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 矿物探测仪的选择界面。
 *
 * <p>一张可以滚的矿物清单，没有物品槽。滚轮翻页、点击选中，
 * 选中项立刻通过 {@code handleInventoryButtonClick} 发给服务端写进手里的探测仪。
 *
 * <p>绘制仍然走 {@code extractBackground}：这里画自己的面板（176×148，底部一行留给虚拟日历）与清单，
 * 标题行由基类负责。
 */
public class ProspectorScreen extends AbstractContainerScreen<ProspectorMenu> {

	// ---------------------------------------------------------------- 配色

	private static final int PANEL = 0xFFC6C6C6;
	private static final int FRAME_DARK = 0xFF555555;
	private static final int FRAME_LIGHT = 0xFFFFFFFF;
	private static final int OUTLINE = 0xFF000000;

	private static final int LIST_BG = 0xFFA8A8A8;
	private static final int ROW_HOVER = 0xFFD9D9D9;
	private static final int ROW_SELECTED = 0xFF3E5F9E;

	private static final int TEXT = 0xFF2A2A2A;
	private static final int TEXT_MUTED = 0xFF5A5A5A;
	private static final int TEXT_ON_SELECTED = 0xFFFFFFFF;

	private static final int SCROLL_TRACK = 0xFF8B8B8B;
	private static final int SCROLL_THUMB = 0xFF4A4A4A;

	// ---------------------------------------------------------------- 布局

	private static final int LIST_X = 8;
	private static final int LIST_Y = 18;
	private static final int LIST_W = 152;
	private static final int LIST_H = 88;
	private static final int ROW_HEIGHT = 11;
	/** 清单里同时能看到的行数。 */
	private static final int VISIBLE_ROWS = LIST_H / ROW_HEIGHT;

	private static final int SCROLL_X = 162;
	private static final int SCROLL_W = 6;

	private static final int LINE_SELECTED_Y = 110;
	private static final int LINE_HINT_Y = 121;
	/** 虚拟日历那一行的 y（面板比旧版加高 12 px 给它）。 */
	private static final int LINE_CALENDAR_Y = 132;

	/** 清单顶端那一行的序号。 */
	private int scrollRow;

	public ProspectorScreen(ProspectorMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, 176, 148);
		// 本界面没有物品槽，把基类要画的「物品栏」标签挪到画面外
		this.inventoryLabelY = 10000;
	}

	// ---------------------------------------------------------------- 绘制

	@Override
	public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(extractor, mouseX, mouseY, partialTick);

		int x = this.leftPos;
		int y = this.topPos;
		extractor.fill(x, y, x + this.imageWidth, y + this.imageHeight, PANEL);
		this.drawOutline(extractor, x, y, x + this.imageWidth, y + this.imageHeight);

		this.drawList(extractor, mouseX, mouseY);
		this.drawFooter(extractor);
	}

	private void drawList(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		int x = this.leftPos + LIST_X;
		int y = this.topPos + LIST_Y;
		int right = x + LIST_W;
		int bottom = y + LIST_H;
		extractor.fill(x, y, right, bottom, LIST_BG);
		this.drawOutline(extractor, x, y, right, bottom);

		OreMineral[] all = OreMineral.values();
		int selected = this.menu.selectedOrdinal();
		int hovered = this.rowAt(mouseX, mouseY);

		for (int row = 0; row < VISIBLE_ROWS; row++) {
			int index = this.scrollRow + row;
			if (index >= all.length) {
				break;
			}
			int rowY = y + row * ROW_HEIGHT;
			boolean isSelected = index == selected;

			if (isSelected) {
				extractor.fill(x + 1, rowY, right - 1, rowY + ROW_HEIGHT - 1, ROW_SELECTED);
			} else if (index == hovered) {
				extractor.fill(x + 1, rowY, right - 1, rowY + ROW_HEIGHT - 1, ROW_HOVER);
			}

			OreMineral mineral = all[index];
			int textColor = isSelected ? TEXT_ON_SELECTED : TEXT;
			int mutedColor = isSelected ? TEXT_ON_SELECTED : TEXT_MUTED;

			// 选中行左侧加一道竖条，色盲玩家也能看出选的是哪一行
			if (isSelected) {
				extractor.fill(x + 1, rowY, x + 3, rowY + ROW_HEIGHT - 1, FRAME_LIGHT);
			}

			extractor.fill(x + 6, rowY + 2, x + 11, rowY + 7, 0xFF000000 | mineral.color());
			UiText.text(extractor, this.font, mineral.displayName(), x + 14, rowY + 1, textColor);

			Component valuable = Substance.displayName(mineral.valuable());
			UiText.text(extractor, this.font, valuable,
					right - 6 - this.font.width(valuable), rowY + 1, mutedColor);
		}

		this.drawScrollBar(extractor);
	}

	private void drawScrollBar(GuiGraphicsExtractor extractor) {
		OreMineral[] all = OreMineral.values();
		int x = this.leftPos + SCROLL_X;
		int y = this.topPos + LIST_Y;
		int bottom = y + LIST_H;
		extractor.fill(x, y, x + SCROLL_W, bottom, SCROLL_TRACK);

		int maxScroll = Math.max(0, all.length - VISIBLE_ROWS);
		if (maxScroll <= 0) {
			return;
		}
		int thumbHeight = Math.max(10, LIST_H * VISIBLE_ROWS / all.length);
		int travel = LIST_H - thumbHeight;
		int thumbY = y + travel * this.scrollRow / maxScroll;
		extractor.fill(x, thumbY, x + SCROLL_W, thumbY + thumbHeight, SCROLL_THUMB);
	}

	private void drawFooter(GuiGraphicsExtractor extractor) {
		int x = this.leftPos + LIST_X;

		Component selectedLine = Component.translatable("gui.geometallurgy.prospector.current",
				this.menu.selected().displayName());
		UiText.text(extractor, this.font, selectedLine, x, this.topPos + LINE_SELECTED_Y, TEXT);

		Component hint = Component.translatable("gui.geometallurgy.prospector.hint_short")
				.append(Component.literal(" · "))
				.append(Component.translatable("gui.geometallurgy.prospector.hint_long_short"));
		UiText.text(extractor, this.font, hint, x, this.topPos + LINE_HINT_Y, TEXT_MUTED);

		// 虚拟日历：脚下的矿体单元属于哪个纪元、还有多久换脉。
		// 与探测报告、机器重勘用同一个基准，读数不会互相打架。
		Component calendar = Component.translatable("gui.geometallurgy.prospector.calendar",
				Long.toString(this.menu.cellEpoch()),
				String.format(Locale.ROOT, "%.1f", this.menu.daysUntilReroll()));
		UiText.text(extractor, this.font, calendar, x, this.topPos + LINE_CALENDAR_Y, TEXT_MUTED);

		// 标题行右侧说明怎么滚
		Component scrollHint = Component.translatable("gui.geometallurgy.prospector.scroll_hint");
		UiText.text(extractor, this.font, scrollHint,
				this.leftPos + this.imageWidth - 8 - this.font.width(scrollHint),
				this.topPos + 6, TEXT_MUTED);
	}

	/** 四条 fill 画一圈黑边 —— GUI 的 outline 参数语义与 fill 不同，不能混用。 */
	private void drawOutline(GuiGraphicsExtractor extractor, int x, int y, int right, int bottom) {
		extractor.fill(x, y, right, y + 1, OUTLINE);
		extractor.fill(x, bottom - 1, right, bottom, OUTLINE);
		extractor.fill(x, y, x + 1, bottom, OUTLINE);
		extractor.fill(right - 1, y, right, bottom, OUTLINE);
		// 内层高光/暗影，做出原版那种「面板」的立体感
		extractor.fill(x + 1, y + 1, right - 1, y + 2, FRAME_LIGHT);
		extractor.fill(x + 1, y + 1, x + 2, bottom - 1, FRAME_LIGHT);
		extractor.fill(x + 1, bottom - 2, right - 1, bottom - 1, FRAME_DARK);
		extractor.fill(right - 2, y + 1, right - 1, bottom - 1, FRAME_DARK);
	}

	// ---------------------------------------------------------------- 交互

	/** 鼠标指着清单里的第几项；不在清单上返回 -1。 */
	private int rowAt(double mouseX, double mouseY) {
		double localX = mouseX - this.leftPos - LIST_X;
		double localY = mouseY - this.topPos - LIST_Y;
		if (localX < 1.0 || localX >= LIST_W - 1.0 || localY < 0.0 || localY >= LIST_H) {
			return -1;
		}
		int index = this.scrollRow + (int) (localY / ROW_HEIGHT);
		return index < OreMineral.values().length ? index : -1;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY != 0.0) {
			this.scrollBy(scrollY > 0.0 ? -1 : 1);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		// 上下键也能翻，键盘玩家不必去够滚轮
		int key = event.key();
		if (key == InputConstants.KEY_UP) {
			this.scrollBy(-1);
			return true;
		}
		if (key == InputConstants.KEY_DOWN) {
			this.scrollBy(1);
			return true;
		}
		return super.keyPressed(event);
	}

	private void scrollBy(int step) {
		int maxScroll = Math.max(0, OreMineral.values().length - VISIBLE_ROWS);
		this.scrollRow = Math.clamp(this.scrollRow + step, 0, maxScroll);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int index = this.rowAt(event.x(), event.y());
		if (index >= 0) {
			// 原版通道：客户端发按钮点击包，服务端走 clickMenuButton
			this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, index);
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}
}
