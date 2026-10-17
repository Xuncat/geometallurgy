package com.geometallurgy.client;

import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;
import com.geometallurgy.blockentity.CrusherBlockEntity;
import com.geometallurgy.blockentity.PortRole;
import com.geometallurgy.menu.CrusherMenu;
import com.geometallurgy.slurry.VirtualSlurry;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;


/**
 * 粉碎机的界面。自绘面板：两根液位条（矿浆进 / 矿粉出）、两行读数、一个环形成分图、六面配置格。
 *
 * <p>刻意不画花活 —— 这台机器只做一件事（磨细），界面上唯一会让人意外的数字是<b>磨矿耗能</b>：
 * 它随来料硬度浮动，硬料进来时同一根电表转得更快、出料更慢。
 *
 * <h2>读数为什么分两行摆</h2>
 * 早先这两段是挤在一行的（左「来料硬度 … 磨矿耗能 …」、右「… FE/t」），字数一长就叠在一起。
 * 现在左边一律只放<b>一短段</b>，右侧那一列留给另一个数：第一行是硬度 / 能耗，第二行是状态 / 供电。
 *
 * <h2>成分环</h2>
 * 出料罐右侧画一圈扇形，每块颜色就是一个相、面积就是占比；鼠标移上去弹出逐项百分比。
 * 之所以不做成常驻文字列表，是因为本界面横向已经没有空位了，而环形恰好能用 26 像素装下
 * 「谁占多少」这件事。
 */
public class CrusherScreen extends AbstractContainerScreen<CrusherMenu> {

	private static final int PANEL_COLOR = 0xFFC6C6C6;
	private static final int HIGHLIGHT = 0xFFFFFFFF;
	private static final int SHADOW = 0xFF555555;
	private static final int LABEL_COLOR = 0xFF404040;
	private static final int TANK_BACKGROUND = 0xFF5A5A5A;
	private static final int OTHER_COLOR = 0xFF8B8B8B;
	private static final int HOVER_TINT = 0x50FFFFFF;
	private static final Identifier SLOT_SPRITE = Identifier.withDefaultNamespace("container/slot");

	private static final int MACHINE_BOTTOM = 110;

	private static final int TANK_TOP = 36;
	private static final int TANK_HEIGHT = 32;
	private static final int TANK_WIDTH = 24;
	private static final int TANK_INPUT_X = 40;
	private static final int TANK_OUTPUT_X = 112;

	/** 成分环：出料罐右侧那块空位，直径 26 正好塞得下。 */
	private static final int PIE_CX = 154;
	private static final int PIE_CY = 52;
	private static final int PIE_R = 13;

	private static final int LABEL_Y = 70;
	private static final int AMOUNT_Y = 82;

	/**
	 * 两个物品槽的标签行与中间那粒箭头。
	 *
	 * <p>槽底纹不在这里画 —— {@link #drawSlots} 会把菜单里**所有**槽位（含这两个）铺一遍。
	 * 这里只补文字和箭头，位置与 {@code CrusherMenu.ITEM_*_X/Y} 对齐，别再写第二份坐标。
	 */
	private static final int ITEM_LABEL_Y = 36;
	private static final int ARROW_X = 86;
	private static final int ARROW_Y = 54;

	private static final int FACE_X = 5;
	private static final int FACE_Y = 94;
	private static final int FACE_W = 27;
	private static final int FACE_H = 14;
	private static final int FACE_GAP = 1;

	private static final int COLOR_DISABLED = 0xFF4A4A4A;
	private static final int COLOR_INPUT = 0xFF3F5F9F;
	private static final int COLOR_OUTPUT = 0xFF8A6A3A;
	private static final int COLOR_BYPRODUCT = 0xFF6E7480;

	public static final int GUI_WIDTH = 176;
	public static final int GUI_HEIGHT = 210;

	public CrusherScreen(CrusherMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, GUI_WIDTH, GUI_HEIGHT);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(extractor, mouseX, mouseY, partialTick);
		this.drawPanel(extractor);
		this.drawSlots(extractor);
		this.drawItemSlotLabels(extractor);
		this.drawReadout(extractor);
		this.drawTank(extractor, TANK_INPUT_X, CrusherMenu.TANK_INPUT);
		this.drawTank(extractor, TANK_OUTPUT_X, CrusherMenu.TANK_OUTPUT);
		this.drawLabels(extractor);
		this.drawAmounts(extractor);
		this.drawPie(extractor);
		this.drawFaceCells(extractor, mouseX, mouseY);
	}

	/**
	 * 悬停浮层。
	 *
	 * <p>走这条通道是为了让成分表盖在物品栏之上 —— 覆写 {@code extractBackground} 的话，
	 * 浮层会被随后绘制的物品压住。父类这一层本来就是物品提示所在的高度。
	 */
	@Override
	protected void extractTooltip(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		super.extractTooltip(extractor, mouseX, mouseY);
		if (SlurryPie.hovered(mouseX, mouseY, this.leftPos + PIE_CX, this.topPos + PIE_CY, PIE_R)) {
			SlurryPie.popup(extractor, this.font,
					Component.translatable("gui.geometallurgy.composition_title"), this.slices(),
					mouseX, mouseY, this.width, this.height, this.leftPos + this.imageWidth - 4);
		}
	}

	private void drawSlots(GuiGraphicsExtractor extractor) {
		for (Slot slot : this.menu.slots) {
			extractor.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, 18, 18, 0, 0,
					this.leftPos + slot.x - 1, this.topPos + slot.y - 1, 18, 18);
		}
	}

	/**
	 * 两个物品槽的标签与中间那粒箭头。
	 *
	 * <p>标签用两字，是因为槽位只有 18 像素宽、两个槽之间也只隔 8 像素 —— 三个字（27 像素）
	 * 就会跟旁边那个槽的标签撞上。
	 *
	 * <p><b>箭头这里必须自己加 leftPos/topPos</b>：{@code extractor.fill} 收的是**屏幕绝对坐标**，
	 * 而本方法里的 ARROW_X/ARROW_Y 是相对面板左上角的偏移。标签不用管是因为 {@code drawCentered}
	 * 内部已经加过了 —— 一个方法里两种坐标口径混着用，漏加就会把箭头画到面板外面去。
	 */
	private void drawItemSlotLabels(GuiGraphicsExtractor extractor) {
		this.drawCentered(extractor, Component.translatable("gui.geometallurgy.crusher_item_in"),
				CrusherMenu.ITEM_IN_X - 1, 18, ITEM_LABEL_Y);
		this.drawCentered(extractor, Component.translatable("gui.geometallurgy.crusher_item_out"),
				CrusherMenu.ITEM_OUT_X - 1, 18, ITEM_LABEL_Y);

		// 向右的一粒小箭头：横杆 + 三级箭尖
		int x = this.leftPos + ARROW_X;
		int y = this.topPos + ARROW_Y;
		extractor.fill(x, y, x + 4, y + 1, LABEL_COLOR);
		extractor.fill(x + 3, y - 2, x + 4, y + 3, LABEL_COLOR);
		extractor.fill(x + 2, y - 1, x + 3, y + 2, LABEL_COLOR);
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

	// ------------------------------------------------------------------ 读数

	private void drawReadout(GuiGraphicsExtractor extractor) {
		// 第一行：硬度（左） / 能耗（右）—— 两个都是「这批料有多难磨」，摆一起好对照。
		Component mohs = Component.translatable("gui.geometallurgy.crusher_mohs",
				String.format(Locale.ROOT, "%.1f", this.menu.averageMohs()));
		UiText.text(extractor, this.font, mohs, this.leftPos + 8, this.topPos + 15, LABEL_COLOR);

		Component cost = Component.translatable("gui.geometallurgy.crusher_cost",
				String.format(Locale.ROOT, "%.0f", this.menu.energyPerMillibucket()));
		UiText.text(extractor, this.font, cost, this.leftPos + this.imageWidth - 8 - this.font.width(cost),
				this.topPos + 15, LABEL_COLOR);

		// 第二行：状态（左） / 实际功率（右）
		UiText.text(extractor, this.font, this.menu.status().translatable(), this.leftPos + 8, this.topPos + 26,
				this.statusColor());

		Component power = Component.translatable("gui.geometallurgy.separator_power",
				String.format(Locale.ROOT, "%.1f", this.menu.power()));
		UiText.text(extractor, this.font, power, this.leftPos + this.imageWidth - 8 - this.font.width(power),
				this.topPos + 26, LABEL_COLOR);
	}

	private int statusColor() {
		return switch (this.menu.status()) {
			case RUNNING -> 0xFF1F7A33;
			case NO_POWER, OUTPUT_FULL -> 0xFF9A4A18;
			case IDLE -> LABEL_COLOR;
		};
	}

	// ------------------------------------------------------------------ 液位条

	private void drawTank(GuiGraphicsExtractor extractor, int offsetX, int tank) {
		int x = this.leftPos + offsetX;
		int y = this.topPos + TANK_TOP;
		int right = x + TANK_WIDTH;
		int bottom = y + TANK_HEIGHT;

		extractor.fill(x, y, right, bottom, TANK_BACKGROUND);

		double amount = this.menu.tankAmount(tank);
		if (amount > 0.0) {
			int filled = Mth.ceil(amount / VirtualSlurry.CAPACITY * TANK_HEIGHT);
			List<CrusherMenu.CompositionEntry> entries = this.menu.composition();
			int cursor = bottom;
			int remaining = filled;
			for (int i = 0; i < entries.size() && remaining > 0; i++) {
				CrusherMenu.CompositionEntry entry = entries.get(i);
				int segment = (i == entries.size() - 1)
						? remaining
						: (int) Math.round(entry.fraction() * filled);
				segment = Math.min(segment, remaining);
				if (segment > 0) {
					extractor.fill(x + 1, cursor - segment, right - 1, cursor, 0xFF000000 | entry.part().color());
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

	private void drawLabels(GuiGraphicsExtractor extractor) {
		this.drawCentered(extractor, Component.translatable("gui.geometallurgy.crusher_in"),
				TANK_INPUT_X, TANK_WIDTH, LABEL_Y);
		this.drawCentered(extractor, Component.translatable("gui.geometallurgy.crusher_out"),
				TANK_OUTPUT_X, TANK_WIDTH, LABEL_Y);
		// 标签用「成分」两个字：出料标签已经占到 x=142，再长就要跟它撞上。
		this.drawCentered(extractor, Component.translatable("gui.geometallurgy.composition_label"),
				PIE_CX - PIE_R, PIE_R * 2, LABEL_Y);
	}

	private void drawAmounts(GuiGraphicsExtractor extractor) {
		Component text = Component.translatable("gui.geometallurgy.crusher_amounts",
				String.format(Locale.ROOT, "%.0f", this.menu.tankAmount(CrusherMenu.TANK_INPUT)),
				String.format(Locale.ROOT, "%.0f", this.menu.tankAmount(CrusherMenu.TANK_OUTPUT)));
		UiText.text(extractor, this.font, text, this.leftPos + 8, this.topPos + AMOUNT_Y, LABEL_COLOR);
	}

	private void drawCentered(GuiGraphicsExtractor extractor, Component text, int offsetX, int width, int offsetY) {
		UiText.text(extractor, this.font, text,
				this.leftPos + offsetX + (width - this.font.width(text)) / 2,
				this.topPos + offsetY, LABEL_COLOR);
	}

	// ------------------------------------------------------------------ 成分环

	/**
	 * 把罐内成分画成一个饼。渲染本体在 {@link SlurryPie} 里，三台机器共用。
	 *
	 * <p>粉碎机只有一罐（进出两罐成分相同），所以不需要切换 —— 画的就是出料罐那一份。
	 */
	private void drawPie(GuiGraphicsExtractor extractor) {
		SlurryPie.draw(extractor, this.leftPos + PIE_CX, this.topPos + PIE_CY, PIE_R, this.slices());
	}

	private List<SlurryPie.Slice> slices() {
		return this.menu.composition().stream()
				.map(entry -> SlurryPie.of(entry.part(), entry.fraction()))
				.toList();
	}

	// ------------------------------------------------------------------ 六面配置

	private void drawFaceCells(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		// 格子只写方位；角色看底色，完整名字走悬浮提示
		int hovered = this.faceCellAt(mouseX, mouseY);
		for (int i = 0; i < CrusherMenu.FACE_ORDER.length; i++) {
			Direction side = CrusherMenu.FACE_ORDER[i];
			PortRole role = this.menu.role(side);
			int x = this.leftPos + FACE_X + i * (FACE_W + FACE_GAP);
			int y = this.topPos + FACE_Y;

			extractor.fill(x, y, x + FACE_W, y + FACE_H, roleColor(role));
			extractor.fill(x, y, x + FACE_W, y + 1, HIGHLIGHT);
			extractor.fill(x, y, x + 1, y + FACE_H, HIGHLIGHT);
			if (hovered == i) {
				extractor.fill(x, y, x + FACE_W, y + FACE_H, HOVER_TINT);
			}

			Component label = Component.translatable("gui.geometallurgy.dir_" + side.getSerializedName());
			UiText.text(extractor, this.font, label, x + (FACE_W - this.font.width(label)) / 2, y + 4, 0xFFFFFFFF);
		}
		if (hovered >= 0) {
			Direction side = CrusherMenu.FACE_ORDER[hovered];
			extractor.setTooltipForNextFrame(Component.translatable("gui.geometallurgy.face_cell",
					Component.translatable("gui.geometallurgy.dir_" + side.getSerializedName()),
					Component.translatable(this.menu.role(side).translationKey())), mouseX, mouseY);
		}
	}

	private static int roleColor(PortRole role) {
		return switch (role) {
			case INPUT -> COLOR_INPUT;
			case OUTPUT -> COLOR_OUTPUT;
			case BYPRODUCT -> COLOR_BYPRODUCT;
			case DISABLED -> COLOR_DISABLED;
		};
	}

	// ------------------------------------------------------------------ 交互

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// 26.x 的窗口是 SDL，左键键号是 1 而不是 0。
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			int cell = this.faceCellAt(event.x(), event.y());
			if (cell >= 0) {
				// 传方向的枚举序，不是格子的排列序：界面按「上下北南西东」排，
				// 枚举序是「下上北南西东」，两者差一位。
				this.sendButton(CrusherMenu.FACE_ORDER[cell].ordinal());
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	private int faceCellAt(double mouseX, double mouseY) {
		for (int i = 0; i < CrusherMenu.FACE_ORDER.length; i++) {
			int offsetX = FACE_X + i * (FACE_W + FACE_GAP);
			if (this.inside(mouseX, mouseY, offsetX, FACE_Y, FACE_W, FACE_H)) {
				return i;
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

	// ------------------------------------------------------------------ 工具

	private void drawBorder(GuiGraphicsExtractor extractor, int x, int y, int right, int bottom) {
		extractor.fill(x - 1, y - 1, right + 1, y, LABEL_COLOR);
		extractor.fill(x - 1, bottom, right + 1, bottom + 1, LABEL_COLOR);
		extractor.fill(x - 1, y - 1, x, bottom + 1, LABEL_COLOR);
		extractor.fill(right, y - 1, right + 1, bottom + 1, LABEL_COLOR);
	}
}
