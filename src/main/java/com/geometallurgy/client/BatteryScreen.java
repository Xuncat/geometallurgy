package com.geometallurgy.client;

import java.util.Locale;

import com.geometallurgy.blockentity.BatteryBlockEntity;
import com.geometallurgy.menu.BatteryMenu;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * 蓄电池的界面：顶部一条<b>双向储能</b>角色条 —— 没有输入 / 输出档可切（bug#43）。
 *
 * <p>设备列表把 19³ 里装了感应器的机器列出来，每台一粒 −/+ 循环调功率档；
 * 列表与功率配置在两个页签下是同一份 —— 模式决定电流方向，限幅对哪个方向都成立。
 *
 * <p><b>面板高度是硬约束。</b>GUI 缩放由游戏按窗口自动选，规则保证逻辑可视高度
 * 至少 240、再进一档就不满足。原版最高的六行箱子界面是 222。设备最多 16 台，
 * 16 × 行高 = 176 像素，若把面板拉高到 362 就会被窗口上下同时裁掉，
 * 而背包在最底部 —— 先被裁掉的正好是背包。所以列表改成**固定 6 行的滚动窗口**，
 * 面板高度锁在 {@link BatteryMenu#GUI_HEIGHT}。
 */
public class BatteryScreen extends AbstractContainerScreen<BatteryMenu> {

	private static final int PANEL_COLOR = 0xFFC6C6C6;
	private static final int HIGHLIGHT = 0xFFFFFFFF;
	private static final int SHADOW = 0xFF555555;
	private static final int LABEL_COLOR = 0xFF404040;
	private static final int HOVER_TINT = 0x50FFFFFF;
	private static final int BAR_BG = 0xFF3A3A3A;
	private static final int BAR_FILL = 0xFF3F9F5F;
	private static final int ROW_BG = 0xFFB2B2B2;
	private static final int BUTTON_BG = 0xFF8B8B8B;
	private static final int ROLE_COLOR = 0xFF3F5F9F;
	private static final int SCROLL_TRACK = 0xFF8B8B8B;
	private static final int SCROLL_THUMB = 0xFF555555;
	private static final Identifier SLOT_SPRITE = Identifier.withDefaultNamespace("container/slot");

	// 页签
	private static final int TAB_Y = 16;
	private static final int TAB_H = 11;

	// 能量条
	private static final int BAR_X = 8;
	private static final int BAR_Y = 30;
	private static final int BAR_H = 10;

	/** 界面尺寸。高度锁在原版六行箱子的 222，理由见类注释。 */
	public static final int GUI_WIDTH = 176;
	public static final int GUI_HEIGHT = BatteryMenu.GUI_HEIGHT;

	// 设备列表：可见行数、首行位置、行高全部取自菜单，两边共用一份契约
	private static final int VISIBLE = BatteryMenu.VISIBLE_DEVICES;
	private static final int LIST_Y = BatteryMenu.LIST_Y;
	private static final int ROW_H = BatteryMenu.ROW_H;
	private static final int LIST_LEFT = 8;
	private static final int LIST_RIGHT = GUI_WIDTH - 14;
	private static final int SCROLL_X = LIST_RIGHT + 2;
	private static final int SCROLL_W = 3;

	private static final int BUTTON_W = 11;
	private static final int BUTTON_H = 9;

	/** 列表滚动到第几行（相对 16 台设备的下标）。纯客户端状态，不同步。 */
	private int scrollRow;

	public BatteryScreen(BatteryMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, GUI_WIDTH, GUI_HEIGHT);
		this.inventoryLabelX = 8;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(extractor, mouseX, mouseY, partialTick);
		this.clampScroll();
		this.drawPanel(extractor);
		this.drawRoleBanner(extractor);
		this.drawEnergyBar(extractor);
		this.drawDeviceList(extractor, mouseX, mouseY);
		this.drawPlayerInventorySlots(extractor);
	}

	// ------------------------------------------------------------------ 绘制

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
	}

	/**
	 * 顶部只有一条<b>角色</b>条，不是一个可点的档位。
	 *
	 * <p>蓄电池没有方向：每个面同时可充可放，成网设备也是「能收的收、能给的给」，
	 * 不存在输入 / 输出档可切（bug#43）。这里只把这个语义画出来，不接受点击。
	 */
	private void drawRoleBanner(GuiGraphicsExtractor extractor) {
		int x = this.leftPos + 8;
		int w = GUI_WIDTH - 16;
		int y = this.topPos + TAB_Y;
		extractor.fill(x, y, x + w, y + TAB_H, ROLE_COLOR);
		extractor.fill(x, y, x + w, y + 1, HIGHLIGHT);
		extractor.fill(x, y, x + 1, y + TAB_H, HIGHLIGHT);
		Component text = Component.translatable("gui.geometallurgy.battery.role");
		UiText.text(extractor, this.font, text, x + (w - this.font.width(text)) / 2, y + 1, 0xFFFFFFFF);
	}

	private void drawEnergyBar(GuiGraphicsExtractor extractor) {
		int x = this.leftPos + BAR_X;
		int right = this.leftPos + GUI_WIDTH - BAR_X;
		int y = this.topPos + BAR_Y;

		extractor.fill(x, y, right, y + BAR_H, BAR_BG);
		long capacity = this.menu.capacity();
		double fraction = capacity <= 0 ? 0.0 : (double) this.menu.energy() / capacity;
		fraction = Math.max(0.0, Math.min(fraction, 1.0));
		int fill = (int) Math.round((right - x - 2) * fraction);
		if (fill > 0) {
			extractor.fill(x + 1, y + 1, x + 1 + fill, y + BAR_H - 1, BAR_FILL);
		}
		extractor.fill(x, y, right, y + 1, HIGHLIGHT);

		Component text = Component.translatable("gui.geometallurgy.battery.energy",
				kilo(this.menu.energy()), kilo(capacity));
		UiText.text(extractor, this.font, text, x + (right - x - this.font.width(text)) / 2, y + 1, 0xFFFFFFFF);
	}

	/**
	 * 玩家背包的 36 个槽底纹。本界面是纯色自绘面板，基类只负责画槽里的物品、
	 * 不会替我们画槽底，所以这里必须自己按 {@code container/slot} 精灵补上。
	 *
	 * <p>行位置与 {@link BatteryMenu} 的 {@code addStandardInventorySlots} 严格对应：
	 * 三行主背包在 {@code INVENTORY_Y + i×18}，快捷栏在 {@code INVENTORY_Y + 58}。
	 */
	private void drawPlayerInventorySlots(GuiGraphicsExtractor extractor) {
		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 9; column++) {
				this.drawSlot(extractor, 8 + column * 18, BatteryMenu.INVENTORY_Y + row * 18);
			}
		}
		for (int column = 0; column < 9; column++) {
			this.drawSlot(extractor, 8 + column * 18, BatteryMenu.INVENTORY_Y + 58);
		}
	}

	private void drawSlot(GuiGraphicsExtractor extractor, int x, int y) {
		extractor.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, 18, 18, 0, 0,
				this.leftPos + x - 1, this.topPos + y - 1, 18, 18);
	}

	private void drawDeviceList(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		int count = this.visibleCount();
		int left = this.leftPos + LIST_LEFT;
		int right = this.leftPos + LIST_RIGHT;

		Component header = Component.translatable("gui.geometallurgy.battery.devices",
				count, BatteryBlockEntity.MAX_DEVICES);
		UiText.text(extractor, this.font, header, left, this.topPos + LIST_Y - 10, LABEL_COLOR);
		Component range = Component.translatable("gui.geometallurgy.battery.loss");
		UiText.text(extractor, this.font, range, right - this.font.width(range),
				this.topPos + LIST_Y - 10, LABEL_COLOR);

		if (count == 0) {
			Component none = Component.translatable("gui.geometallurgy.battery.none");
			UiText.text(extractor, this.font, none, left + 2, this.topPos + LIST_Y + 2, LABEL_COLOR);
			return;
		}

		int shown = Math.min(VISIBLE, count - this.scrollRow);
		for (int row = 0; row < shown; row++) {
			int index = this.scrollRow + row;
			int y = this.topPos + LIST_Y + row * ROW_H;
			extractor.fill(left, y, right, y + ROW_H - 1, row % 2 == 0 ? ROW_BG : PANEL_COLOR);

			Component where = Component.translatable("gui.geometallurgy.battery.device",
					this.menu.deviceOffsetX(index), this.menu.deviceOffsetY(index),
					this.menu.deviceOffsetZ(index));
			UiText.text(extractor, this.font, where, left + 2, y + 1, LABEL_COLOR);

			int rateIndex = this.menu.deviceRateIndex(index);
			rateIndex = Math.max(0, Math.min(rateIndex, BatteryBlockEntity.RATE_STEPS.length - 1));
			long rate = BatteryBlockEntity.RATE_STEPS[rateIndex];
			Component rateText = Component.translatable("gui.geometallurgy.battery.rate", rate);
			UiText.text(extractor, this.font, rateText,
					right - 2 * (BUTTON_W + 2) - 4 - this.font.width(rateText), y + 1, LABEL_COLOR);

			int hovered = this.buttonAt(mouseX, mouseY);
			this.drawButton(extractor, right - 2 * (BUTTON_W + 2), y + 1, "−", hovered == index * 2);
			this.drawButton(extractor, right - BUTTON_W - 2, y + 1, "+", hovered == index * 2 + 1);
		}

		this.drawScrollBar(extractor, count);
	}

	/** 列表右侧的滚动条：只在放不下时出现。 */
	private void drawScrollBar(GuiGraphicsExtractor extractor, int count) {
		if (count <= VISIBLE) {
			return;
		}
		int x = this.leftPos + SCROLL_X;
		int top = this.topPos + LIST_Y;
		int height = VISIBLE * ROW_H - 1;
		extractor.fill(x, top, x + SCROLL_W, top + height, SCROLL_TRACK);

		int thumbH = Math.max(8, height * VISIBLE / count);
		int travel = height - thumbH;
		int max = this.maxScroll();
		int offset = max <= 0 ? 0 : travel * this.scrollRow / max;
		extractor.fill(x, top + offset, x + SCROLL_W, top + offset + thumbH, SCROLL_THUMB);
	}

	private void drawButton(GuiGraphicsExtractor extractor, int x, int y, String label, boolean hover) {
		int right = x + BUTTON_W;
		int bottom = y + BUTTON_H;
		extractor.fill(x, y, right, bottom, BUTTON_BG);
		extractor.fill(x, y, right, y + 1, HIGHLIGHT);
		extractor.fill(x, y, x + 1, bottom, HIGHLIGHT);
		extractor.fill(x, bottom - 1, right, bottom, SHADOW);
		extractor.fill(right - 1, y, right, bottom, SHADOW);
		if (hover) {
			extractor.fill(x, y, right, bottom, HOVER_TINT);
		}
		UiText.text(extractor, this.font, label, x + (BUTTON_W - this.font.width(label)) / 2, y + 1, 0xFFFFFFFF);
	}

	// ------------------------------------------------------------------ 交互

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// 26.x 的窗口是 SDL，左键键号是 1 而不是 0，必须用常量比对。
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			// 顶部角色条不可点：蓄电池没有方向档（bug#43）。
			int button = this.buttonAt(event.x(), event.y());
			if (button >= 0) {
				this.sendButton(button);
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (this.inside(mouseX, mouseY, LIST_LEFT, LIST_Y,
				LIST_RIGHT - LIST_LEFT, VISIBLE * ROW_H)) {
			int step = scrollY > 0 ? -1 : (scrollY < 0 ? 1 : 0);
			int next = Math.clamp(this.scrollRow + step, 0, this.maxScroll());
			if (next != this.scrollRow) {
				this.scrollRow = next;
				return true;
			}
			return false;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	/** 服务端同步过来的设备台数，并夹到 16 台上限。 */
	private int visibleCount() {
		return Math.max(0, Math.min(this.menu.deviceCount(), BatteryBlockEntity.MAX_DEVICES));
	}

	private int maxScroll() {
		return Math.max(0, this.visibleCount() - VISIBLE);
	}

	private void clampScroll() {
		this.scrollRow = Math.clamp(this.scrollRow, 0, this.maxScroll());
	}

	/**
	 * 命中的是哪一粒按钮。返回值是**设备在完整列表里的下标**编码
	 * （第 i 台减档 = i×2，加档 = i×2+1），因为服务端也是按同一个下标取设备，
	 * 两边必须用同一个编号，不能按「屏幕上第几行」算。
	 */
	private int buttonAt(double mouseX, double mouseY) {
		int count = this.visibleCount();
		int shown = Math.min(VISIBLE, Math.max(0, count - this.scrollRow));
		for (int row = 0; row < shown; row++) {
			int index = this.scrollRow + row;
			int y = LIST_Y + row * ROW_H + 1;
			if (this.inside(mouseX, mouseY, LIST_RIGHT - 2 * (BUTTON_W + 2), y, BUTTON_W, BUTTON_H)) {
				return index * 2;
			}
			if (this.inside(mouseX, mouseY, LIST_RIGHT - BUTTON_W - 2, y, BUTTON_W, BUTTON_H)) {
				return index * 2 + 1;
			}
		}
		return -1;
	}

	private boolean inside(double mouseX, double mouseY, int offsetX, int offsetY, int width, int height) {
		double x = mouseX - this.leftPos;
		double y = mouseY - this.topPos;
		return x >= offsetX && x < offsetX + width && y >= offsetY && y < offsetY + height;
	}

	private void sendButton(int id) {
		if (this.minecraft != null && this.minecraft.gameMode != null) {
			this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, id);
		}
	}

	private static String kilo(long fe) {
		return String.format(Locale.ROOT, "%.0f kFE", fe / 1000.0);
	}
}
