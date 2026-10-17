package com.geometallurgy.client;

import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;
import com.geometallurgy.blockentity.CentrifugeBlockEntity;
import com.geometallurgy.blockentity.PortRole;
import com.geometallurgy.menu.CentrifugeMenu;
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
 * 离心机的界面。三根液位条（矿浆进 / 重相 / 轻相）、转速读数、一行切点调节、六面配置格。
 *
 * <p>切点是本机唯一需要人盯的参数：把 ρc 放在脉石（2.2~3.0）与重矿物（4~5）之间，
 * 出来的就是「石头走轻相、矿走重相」；放到两种矿物的密度之间，才是「矿物互分」。
 * 两个按钮按 {@link CentrifugeBlockEntity#CUT_STEP} 的步进加减。
 */
public class CentrifugeScreen extends AbstractContainerScreen<CentrifugeMenu> {

	private static final int PANEL_COLOR = 0xFFC6C6C6;
	private static final int HIGHLIGHT = 0xFFFFFFFF;
	private static final int SHADOW = 0xFF555555;
	private static final int LABEL_COLOR = 0xFF404040;
	private static final int TANK_BACKGROUND = 0xFF5A5A5A;
	private static final int OTHER_COLOR = 0xFF8B8B8B;
	private static final int HOVER_TINT = 0x50FFFFFF;
	private static final Identifier SLOT_SPRITE = Identifier.withDefaultNamespace("container/slot");

	private static final int MACHINE_BOTTOM = 138;

	private static final int TANK_TOP = 48;
	private static final int TANK_HEIGHT = 34;
	private static final int TANK_WIDTH = 26;
	private static final int TANK_INPUT_X = 40;
	private static final int TANK_HEAVY_X = 76;
	private static final int TANK_LIGHT_X = 112;

	private static final int LABEL_Y = 84;
	private static final int AMOUNT_Y = 96;

	/**
	 * 成分环与它的切换格。
	 *
	 * <p>三根液位条吃掉中间那 98 像素，两侧各剩一块空地：左边 x∈[4,40]、右边 x∈[138,176]，
	 * 纵向都是 y∈[48,94]（再往下是罐量行，再再往下是切点行与六面格）。环挂左边、切换格挂右边。
	 */
	private static final int PIE_CX = 22;
	private static final int PIE_CY = 61;
	private static final int PIE_R = 12;
	private static final int PIE_TAB_X = 142;
	private static final int PIE_TAB_Y = 49;
	private static final int PIE_TAB_W = 32;
	private static final int PIE_TAB_H = 13;
	private static final int PIE_TAB_GAP = 1;

	/** 切换格的排列顺序与它们对应的罐。 */
	private static final int[] PIE_TANKS = {
			CentrifugeMenu.TANK_INPUT,
			CentrifugeMenu.TANK_HEAVY,
			CentrifugeMenu.TANK_LIGHT,
	};
	private static final String[] PIE_TAB_KEYS = {
			"gui.geometallurgy.tank_input",
			"gui.geometallurgy.tank_heavy",
			"gui.geometallurgy.tank_light",
	};

	private static final int CUT_Y = 106;
	private static final int CUT_BTN_W = 14;
	private static final int CUT_BTN_H = 12;
	private static final int CUT_MINUS_X = 30;
	private static final int CUT_PLUS_X = 132;

	private static final int FACE_X = 5;
	private static final int FACE_Y = 120;
	private static final int FACE_W = 27;
	private static final int FACE_H = 14;
	private static final int FACE_GAP = 1;

	private static final int COLOR_DISABLED = 0xFF4A4A4A;
	private static final int COLOR_INPUT = 0xFF3F5F9F;
	private static final int COLOR_OUTPUT = 0xFF7A5A2A;
	private static final int COLOR_BYPRODUCT = 0xFF6E7480;

	private static final int COLOR_TAB_OFF = 0xFFADADAD;
	/** 选中格的底色：比面板深一档，让白字读得出来。 */
	private static final int COLOR_TAB_ON = 0xFF4A4A4A;
	private static final int COLOR_TAB_TEXT_OFF = 0xFF303030;

	/**
	 * 成分环当前在看哪一个罐。
	 *
	 * <p>纯客户端状态：只决定「画哪一罐的成分」，不改变机器行为，所以既不用同步、
	 * 也不必存进方块实体。
	 */
	private int pieTab = 0;

	public static final int GUI_WIDTH = 176;
	public static final int GUI_HEIGHT = 238;

	public CentrifugeScreen(CentrifugeMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, GUI_WIDTH, GUI_HEIGHT);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(extractor, mouseX, mouseY, partialTick);
		this.drawPanel(extractor);
		this.drawSlots(extractor);
		this.drawReadout(extractor);
		this.drawTank(extractor, TANK_INPUT_X, CentrifugeMenu.TANK_INPUT);
		this.drawTank(extractor, TANK_HEAVY_X, CentrifugeMenu.TANK_HEAVY);
		this.drawTank(extractor, TANK_LIGHT_X, CentrifugeMenu.TANK_LIGHT);
		this.drawLabels(extractor);
		this.drawAmounts(extractor);
		this.drawPie(extractor);
		this.drawPieTabs(extractor, mouseX, mouseY);
		this.drawCutRow(extractor, mouseX, mouseY);
		this.drawFaceCells(extractor, mouseX, mouseY);
	}

	/**
	 * 悬停浮层。
	 *
	 * <p>走这条通道是为了让成分表盖在物品栏之上 —— 覆写 {@code extractBackground} 的话，
	 * 浮层会被随后绘制的物品压住。
	 */
	@Override
	protected void extractTooltip(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		super.extractTooltip(extractor, mouseX, mouseY);
		if (SlurryPie.hovered(mouseX, mouseY, this.leftPos + PIE_CX, this.topPos + PIE_CY, PIE_R)) {
			SlurryPie.popup(extractor, this.font, this.pieTitle(), this.slices(),
					mouseX, mouseY, this.width, this.height, this.leftPos + this.imageWidth - 4);
		}
	}

	private void drawSlots(GuiGraphicsExtractor extractor) {
		for (Slot slot : this.menu.slots) {
			extractor.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, 18, 18, 0, 0,
					this.leftPos + slot.x - 1, this.topPos + slot.y - 1, 18, 18);
		}
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

	/**
	 * 读数分三行，每行左侧最多一段短字。
	 *
	 * <p>和磁力筛机同样的道理：转速与供电早先拼在同一行、右端还顶着「分离带」，
	 * 字数一长就叠在一起。现在供电单拎一行，状态独占一行。
	 */
	private void drawReadout(GuiGraphicsExtractor extractor) {
		Component head = Component.translatable("gui.geometallurgy.centrifuge_spin",
				String.format(Locale.ROOT, "%.2f", this.menu.rotation()));
		UiText.text(extractor, this.font, head, this.leftPos + 8, this.topPos + 15, LABEL_COLOR);

		Component band = Component.translatable("gui.geometallurgy.centrifuge_band",
				String.format(Locale.ROOT, "%.2f", CentrifugeBlockEntity.BASE_WIDTH
						/ Math.max(this.menu.rotation(), CentrifugeBlockEntity.MIN_RPM)));
		UiText.text(extractor, this.font, band, this.leftPos + this.imageWidth - 8 - this.font.width(band),
				this.topPos + 15, LABEL_COLOR);

		Component draw = Component.translatable("gui.geometallurgy.separator_draw",
				String.format(Locale.ROOT, "%.1f", this.menu.power()));
		UiText.text(extractor, this.font, draw, this.leftPos + 8, this.topPos + 26, LABEL_COLOR);

		UiText.text(extractor, this.font, this.menu.status().translatable(), this.leftPos + 8, this.topPos + 37,
				this.statusColor());
	}

	private int statusColor() {
		return switch (this.menu.status()) {
			case RUNNING -> 0xFF1F7A33;
			case NO_SPIN, OUTPUT_FULL, NEEDS_SLURRY -> 0xFF9A4A18;
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
			List<CentrifugeMenu.CompositionEntry> entries = this.menu.composition(tank);
			int cursor = bottom;
			int remaining = filled;
			for (int i = 0; i < entries.size() && remaining > 0; i++) {
				CentrifugeMenu.CompositionEntry entry = entries.get(i);
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
		this.drawCentered(extractor, Component.translatable("gui.geometallurgy.tank_input"),
				TANK_INPUT_X, TANK_WIDTH, LABEL_Y);
		this.drawCentered(extractor, Component.translatable("gui.geometallurgy.tank_heavy"),
				TANK_HEAVY_X, TANK_WIDTH, LABEL_Y);
		this.drawCentered(extractor, Component.translatable("gui.geometallurgy.tank_light"),
				TANK_LIGHT_X, TANK_WIDTH, LABEL_Y);
	}

	private void drawAmounts(GuiGraphicsExtractor extractor) {
		Component text = Component.translatable("gui.geometallurgy.centrifuge_amounts",
				String.format(Locale.ROOT, "%.0f", this.menu.tankAmount(CentrifugeMenu.TANK_INPUT)),
				String.format(Locale.ROOT, "%.0f", this.menu.tankAmount(CentrifugeMenu.TANK_HEAVY)),
				String.format(Locale.ROOT, "%.0f", this.menu.tankAmount(CentrifugeMenu.TANK_LIGHT)));
		UiText.text(extractor, this.font, text, this.leftPos + 8, this.topPos + AMOUNT_Y, LABEL_COLOR);
	}

	private void drawCentered(GuiGraphicsExtractor extractor, Component text, int offsetX, int width, int offsetY) {
		UiText.text(extractor, this.font, text,
				this.leftPos + offsetX + (width - this.font.width(text)) / 2,
				this.topPos + offsetY, LABEL_COLOR);
	}

	// ------------------------------------------------------------------ 成分环

	/**
	 * 画当前选中罐的成分环。渲染本体在 {@link SlurryPie} 里，与粉碎机、磁力筛机共用。
	 *
	 * <p>这台机器分选的目标是「重相 vs 轻相」，所以除了进料，另外两罐的成分各看各的 ——
	 * 切换格把这三者摆在一处，比原先只能靠液位条上的色段猜要清楚得多。
	 */
	private void drawPie(GuiGraphicsExtractor extractor) {
		SlurryPie.draw(extractor, this.leftPos + PIE_CX, this.topPos + PIE_CY, PIE_R, this.slices());
	}

	private List<SlurryPie.Slice> slices() {
		return this.menu.composition(PIE_TANKS[this.pieTab]).stream()
				.map(entry -> SlurryPie.of(entry.part(), entry.fraction()))
				.toList();
	}

	private Component pieTitle() {
		return Component.translatable("gui.geometallurgy.composition_of",
				Component.translatable(PIE_TAB_KEYS[this.pieTab]));
	}

	/**
	 * 一列三格，点一下换看哪一个罐。
	 *
	 * <p>每格左边一道竖条用的是该罐在液位条上的那个颜色；底色只区分「选中／未选中」。
	 */
	private void drawPieTabs(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		int hovered = this.pieTabAt(mouseX, mouseY);
		for (int i = 0; i < PIE_TANKS.length; i++) {
			int x = this.leftPos + PIE_TAB_X;
			int y = this.topPos + PIE_TAB_Y + i * (PIE_TAB_H + PIE_TAB_GAP);
			boolean selected = this.pieTab == i;

			extractor.fill(x, y, x + PIE_TAB_W, y + PIE_TAB_H,
					selected ? COLOR_TAB_ON : COLOR_TAB_OFF);
			extractor.fill(x, y, x + 3, y + PIE_TAB_H, tankColor(i));
			extractor.fill(x, y, x + PIE_TAB_W, y + 1, HIGHLIGHT);

			Component text = Component.translatable(PIE_TAB_KEYS[i]);
			UiText.text(extractor, this.font, text,
					x + 3 + (PIE_TAB_W - 3 - this.font.width(text)) / 2, y + 3,
					selected ? 0xFFFFFFFF : COLOR_TAB_TEXT_OFF);

			if (hovered == i && !selected) {
				extractor.fill(x, y, x + PIE_TAB_W, y + PIE_TAB_H, HOVER_TINT);
			}
		}
	}

	private static int tankColor(int tab) {
		return switch (tab) {
			case 0 -> COLOR_INPUT;
			case 1 -> COLOR_OUTPUT;
			default -> COLOR_BYPRODUCT;
		};
	}

	private int pieTabAt(double mouseX, double mouseY) {
		for (int i = 0; i < PIE_TANKS.length; i++) {
			int offsetY = PIE_TAB_Y + i * (PIE_TAB_H + PIE_TAB_GAP);
			if (this.inside(mouseX, mouseY, PIE_TAB_X, offsetY, PIE_TAB_W, PIE_TAB_H)) {
				return i;
			}
		}
		return -1;
	}

	// ------------------------------------------------------------------ 切点调节

	private void drawCutRow(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		this.drawSmallButton(extractor, CUT_MINUS_X, "-", this.hovering(mouseX, mouseY, CUT_MINUS_X));
		this.drawSmallButton(extractor, CUT_PLUS_X, "+", this.hovering(mouseX, mouseY, CUT_PLUS_X));

		Component text = Component.translatable("gui.geometallurgy.centrifuge_cut",
				String.format(Locale.ROOT, "%.2f", this.menu.cut()));
		UiText.text(extractor, this.font, text,
				this.leftPos + (this.imageWidth - this.font.width(text)) / 2, this.topPos + CUT_Y + 2,
				LABEL_COLOR);
	}

	private void drawSmallButton(GuiGraphicsExtractor extractor, int offsetX, String label, boolean hovered) {
		int x = this.leftPos + offsetX;
		int y = this.topPos + CUT_Y;
		extractor.fill(x, y, x + CUT_BTN_W, y + CUT_BTN_H, 0xFF6A6A6A);
		extractor.fill(x, y, x + CUT_BTN_W, y + 1, HIGHLIGHT);
		if (hovered) {
			extractor.fill(x, y, x + CUT_BTN_W, y + CUT_BTN_H, HOVER_TINT);
		}
		UiText.text(extractor, this.font, label, x + (CUT_BTN_W - this.font.width(label)) / 2, y + 2, 0xFFFFFFFF);
	}

	// ------------------------------------------------------------------ 六面配置

	private void drawFaceCells(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		// 格子只写方位；角色看底色，完整名字走悬浮提示
		int hovered = this.faceCellAt(mouseX, mouseY);
		for (int i = 0; i < CentrifugeMenu.FACE_ORDER.length; i++) {
			Direction side = CentrifugeMenu.FACE_ORDER[i];
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
			Direction side = CentrifugeMenu.FACE_ORDER[hovered];
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
			// 切换成分环看哪个罐 —— 纯客户端状态，不必往服务端发按钮。
			int tab = this.pieTabAt(event.x(), event.y());
			if (tab >= 0) {
				this.pieTab = tab;
				return true;
			}
			if (this.hovering(event.x(), event.y(), CUT_MINUS_X)) {
				this.sendButton(CentrifugeBlockEntity.CUT_DOWN_BUTTON);
				return true;
			}
			if (this.hovering(event.x(), event.y(), CUT_PLUS_X)) {
				this.sendButton(CentrifugeBlockEntity.CUT_UP_BUTTON);
				return true;
			}
			int cell = this.faceCellAt(event.x(), event.y());
			if (cell >= 0) {
				// 传方向的枚举序：界面按「上下北南西东」排，枚举序是「下上北南西东」，差一位。
				this.sendButton(CentrifugeMenu.FACE_ORDER[cell].ordinal());
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	private int faceCellAt(double mouseX, double mouseY) {
		for (int i = 0; i < CentrifugeMenu.FACE_ORDER.length; i++) {
			int offsetX = FACE_X + i * (FACE_W + FACE_GAP);
			if (this.inside(mouseX, mouseY, offsetX, FACE_Y, FACE_W, FACE_H)) {
				return i;
			}
		}
		return -1;
	}

	private boolean hovering(double mouseX, double mouseY, int offsetX) {
		return this.inside(mouseX, mouseY, offsetX, CUT_Y, CUT_BTN_W, CUT_BTN_H);
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
