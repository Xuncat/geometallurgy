package com.geometallurgy.client;

import java.util.List;
import java.util.Locale;

import com.geometallurgy.blockentity.FaceRole;
import com.geometallurgy.blockentity.MagneticSeparatorBlockEntity;
import com.mojang.blaze3d.platform.InputConstants;
import com.geometallurgy.menu.MagneticSeparatorMenu;
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
 * 磁力筛机的界面。
 *
 * <p>自绘面板（没有借原版容器的贴图），因为机器区比原版那 58 像素高得多：
 * 要放下三根液位条、一根磁场窗口条、六格面配置和一排读数。
 *
 * <p>版式自上而下：磁场读数、状态、四根条（磁场 + 三个罐）、罐量、
 * 六面配置、图例与尾矿开关，最后是玩家背包。
 *
 * <p>六面配置格和尾矿开关都是「点一下就切换」——点击通过
 * {@link #sendButton} 送到服务端，不需要额外的网络包。
 */
public class MagneticSeparatorScreen extends AbstractContainerScreen<MagneticSeparatorMenu> {

	private static final int PANEL_COLOR = 0xFFC6C6C6;
	private static final int HIGHLIGHT = 0xFFFFFFFF;
	private static final int SHADOW = 0xFF555555;
	private static final int LABEL_COLOR = 0xFF404040;
	private static final int TANK_BACKGROUND = 0xFF5A5A5A;
	private static final int OTHER_COLOR = 0xFF8B8B8B;
	/** 磁场窗口条上标出「理想区间」的那一段。 */
	private static final int IDEAL_BAND = 0xFF3FBF5F;
	/** 当前磁场位置的游标。 */
	private static final int FIELD_MARKER = 0xFFFFE060;
	/** 鼠标悬停在可点击格子上时盖的那层淡白。 */
	private static final int HOVER_TINT = 0x50FFFFFF;
	/** 原版通用空槽精灵。自己不画的话物品栏就是一片灰底，看着像贴图坏了。 */
	private static final Identifier SLOT_SPRITE = Identifier.withDefaultNamespace("container/slot");

	/**
	 * 机器区下沿。分界线画在它下面 2 像素处，之后留给基类那条「物品栏」标签
	 * （父类把它固定在 {@code imageHeight - 94}，跟内容抢地方的就是它）。
	 */
	private static final int MACHINE_BOTTOM = 138;

	private static final int FIELD_BAR_X = 8;
	private static final int TANK_TOP = 48;
	private static final int TANK_HEIGHT = 34;
	private static final int TANK_WIDTH = 20;
	private static final int TANK_INPUT_X = 24;
	private static final int TANK_MAGNETIC_X = 52;
	private static final int TANK_NON_MAGNETIC_X = 80;

	private static final int LABEL_Y = 84;
	private static final int AMOUNT_Y = 96;

	/**
	 * 成分环：三罐的右边那块空位。
	 *
	 * <p>磁场条加三个罐一共用到 x≈100，六面配置格要到 y=107 才开始，所以 x∈[100,176]、
	 * y∈[48,94] 这块是空的 —— 一个直径 24 的环加一列切换格正好填进去。
	 */
	private static final int PIE_CX = 114;
	private static final int PIE_CY = 61;
	private static final int PIE_R = 12;
	/** 切换格：饼图右边竖排三格，点一下换看哪一个罐。 */
	private static final int PIE_TAB_X = 137;
	private static final int PIE_TAB_Y = 49;
	private static final int PIE_TAB_W = 32;
	private static final int PIE_TAB_H = 13;
	private static final int PIE_TAB_GAP = 1;

	/** 切换格的排列顺序与它们对应的罐。 */
	private static final int[] PIE_TANKS = {
			MagneticSeparatorMenu.TANK_INPUT,
			MagneticSeparatorMenu.TANK_MAGNETIC,
			MagneticSeparatorMenu.TANK_NON_MAGNETIC,
	};
	private static final String[] PIE_TAB_KEYS = {
			"gui.geometallurgy.tank_input",
			"gui.geometallurgy.tank_magnetic",
			"gui.geometallurgy.tank_non_magnetic",
	};

	/** 六个面配置格：一行六格，每格写「方向 + 角色」。 */
	private static final int FACE_X = 5;
	private static final int FACE_Y = 107;
	private static final int FACE_W = 27;
	private static final int FACE_H = 14;
	private static final int FACE_GAP = 1;

	private static final int LEGEND_Y = 126;
	private static final int LEGEND_STEP = 22;

	private static final int TAIL_X = 96;
	private static final int TAIL_Y = 122;
	private static final int TAIL_W = 76;
	private static final int TAIL_H = 14;

	private static final int COLOR_INPUT = 0xFF3F5F9F;
	private static final int COLOR_MAGNETIC = 0xFF8E3B32;
	private static final int COLOR_NON_MAGNETIC = 0xFF6E7480;
	private static final int COLOR_DISABLED = 0xFF4A4A4A;

	private static final int COLOR_TAB_OFF = 0xFFADADAD;
	/** 选中格的底色：比面板深一档，让白字读得出来。 */
	private static final int COLOR_TAB_ON = 0xFF4A4A4A;
	private static final int COLOR_TAB_TEXT_OFF = 0xFF303030;

	/**
	 * 成分环当前在看哪一个罐。
	 *
	 * <p>纯客户端状态：它只决定「画哪一罐的成分」，不改变机器行为，所以既不用同步、
	 * 也不必存进方块实体。界面上那排切换格改的就是它。
	 */
	private int pieTab = 0;

	/**
	 * 尺寸必须在这里定死：{@code imageWidth} / {@code imageHeight} 在 26.x 是 final，
	 * 只能经父类这个带尺寸的构造器传进去，不能再像老版本那样在构造体里赋值。
	 *
	 * <p>高度取 238 —— 比原版的 166 高出 72 像素，多出来的正是读数第三行、六面配置格、
	 * 图例与尾矿开关那几行；物品栏相应下移到 y=158（见 {@link MagneticSeparatorMenu}）。
	 * 这两个数字与菜单里的 {@code addStandardInventorySlots} 是一套，必须一起改：
	 * 高度不够时物品栏会落到面板外面去。
	 */
	public static final int GUI_WIDTH = 176;
	public static final int GUI_HEIGHT = 238;

	public MagneticSeparatorScreen(MagneticSeparatorMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, GUI_WIDTH, GUI_HEIGHT);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(extractor, mouseX, mouseY, partialTick);
		this.drawPanel(extractor);
		this.drawSlots(extractor);
		this.drawReadout(extractor);
		this.drawFieldBar(extractor);
		this.drawTank(extractor, TANK_INPUT_X, MagneticSeparatorMenu.TANK_INPUT);
		this.drawTank(extractor, TANK_MAGNETIC_X, MagneticSeparatorMenu.TANK_MAGNETIC);
		this.drawTank(extractor, TANK_NON_MAGNETIC_X, MagneticSeparatorMenu.TANK_NON_MAGNETIC);
		this.drawLabels(extractor);
		this.drawAmounts(extractor);
		this.drawPie(extractor);
		this.drawPieTabs(extractor, mouseX, mouseY);
		this.drawFaceCells(extractor, mouseX, mouseY);
		this.drawLegend(extractor);
		this.drawTailingsToggle(extractor, mouseX, mouseY);
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
			SlurryPie.popup(extractor, this.font, this.pieTitle(), this.slices(),
					mouseX, mouseY, this.width, this.height, this.leftPos + this.imageWidth - 4);
		}
	}

	/**
	 * 物品栏的槽底纹。
	 *
	 * <p>这套底纹本来是由原版容器贴图提供的 —— 别的机器界面借的是熔炉贴图，
	 * 所以「什么都没画」也照样有槽。本界面是自绘的纯色面板，必须自己补上，
	 * 否则物品栏就是一片灰，看着像贴图丢了。
	 *
	 * <p>槽的坐标是<b>相对面板左上角</b>的 16×16 内容区，而空槽精灵是 18×18，
	 * 所以要往左上各挪 1 像素。
	 */
	private void drawSlots(GuiGraphicsExtractor extractor) {
		for (Slot slot : this.menu.slots) {
			extractor.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, 18, 18, 0, 0,
					this.leftPos + slot.x - 1, this.topPos + slot.y - 1, 18, 18);
		}
	}

	// ------------------------------------------------------------------ 面板

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
		// 机器区与物品栏之间的分界线
		extractor.fill(left + 4, top + MACHINE_BOTTOM + 2, right - 4, top + MACHINE_BOTTOM + 3, SHADOW);
	}

	// ------------------------------------------------------------------ 读数

	/**
	 * 读数分三行，每行左侧最多一段短字。
	 *
	 * <p>早先场强与供电是拼在同一行、右端又另外顶着一截「理想 x~y」，字数一长两段就叠上了
	 * （拼出来的「FE/t」正好压在「理想」头上）。现在把供电单拎一行、理想独占第一行右端，
	 * 任何一行的两段之间都留着几十像素的富余。
	 */
	private void drawReadout(GuiGraphicsExtractor extractor) {
		Component head = Component.translatable("gui.geometallurgy.separator_field",
				String.format(Locale.ROOT, "%.2f", this.menu.fieldStrength()));
		UiText.text(extractor, this.font, head, this.leftPos + 8, this.topPos + 15, LABEL_COLOR);

		Component ideal = Component.translatable("gui.geometallurgy.separator_ideal",
				String.format(Locale.ROOT, "%.2f", this.menu.fieldWeak()),
				String.format(Locale.ROOT, "%.2f", this.menu.fieldStrong()));
		UiText.text(extractor, this.font, ideal, this.leftPos + this.imageWidth - 8 - this.font.width(ideal),
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
			case FIELD_WEAK, FIELD_STRONG, OUTPUT_FULL -> 0xFF9A4A18;
			default -> LABEL_COLOR;
		};
	}

	// ------------------------------------------------------------------ 磁场窗口条

	/**
	 * 磁场条画的是「窗口」而不是「存量」：底色是场强 0~3 的量程，
	 * 中间那段绿的是理想区间，黄色游标是当前场强。两头都是坏区 —— 左边吸不住，右边夹带。
	 */
	private void drawFieldBar(GuiGraphicsExtractor extractor) {
		int x = this.leftPos + FIELD_BAR_X;
		int y = this.topPos + TANK_TOP;
		int right = x + 8;
		int bottom = y + TANK_HEIGHT;

		extractor.fill(x, y, right, bottom, TANK_BACKGROUND);

		double scale = MagneticSeparatorBlockEntity.FIELD_DISPLAY_MAX;
		int idealTop = (int) Math.round(bottom
				- Math.min(scale, this.menu.fieldStrong()) / scale * TANK_HEIGHT);
		int idealBottom = (int) Math.round(bottom
				- Math.min(scale, this.menu.fieldWeak()) / scale * TANK_HEIGHT);
		extractor.fill(x + 1, idealTop, right - 1, idealBottom, IDEAL_BAND);

		double field = Math.clamp(this.menu.fieldStrength(), 0.0, scale);
		int marker = (int) Math.round(bottom - field / scale * TANK_HEIGHT);
		extractor.fill(x, Math.max(y, marker - 1), right, Math.min(bottom, marker + 1), FIELD_MARKER);

		this.drawBorder(extractor, x, y, right, bottom);
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
			List<MagneticSeparatorMenu.CompositionEntry> entries = this.menu.composition(tank);
			int cursor = bottom;
			int remaining = filled;
			for (int i = 0; i < entries.size() && remaining > 0; i++) {
				MagneticSeparatorMenu.CompositionEntry entry = entries.get(i);
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
		this.drawCentered(extractor, Component.translatable("gui.geometallurgy.tank_field"), FIELD_BAR_X, 8, LABEL_Y);
		// 进料口的标签跟着实际形态走：粉料进干式磁选时窗口会收窄，这事必须在界面上看得见。
		this.drawCentered(extractor, Component.translatable(this.menu.powder()
						? "gui.geometallurgy.tank_input_powder"
						: "gui.geometallurgy.tank_input"),
				TANK_INPUT_X, TANK_WIDTH, LABEL_Y);
		this.drawCentered(extractor, Component.translatable("gui.geometallurgy.tank_magnetic"), TANK_MAGNETIC_X, TANK_WIDTH, LABEL_Y);
		this.drawCentered(extractor, Component.translatable("gui.geometallurgy.tank_non_magnetic"), TANK_NON_MAGNETIC_X, TANK_WIDTH, LABEL_Y);
	}

	private void drawAmounts(GuiGraphicsExtractor extractor) {
		Component text = Component.translatable("gui.geometallurgy.separator_amounts",
				String.format(Locale.ROOT, "%.0f", this.menu.tankAmount(MagneticSeparatorMenu.TANK_INPUT)),
				String.format(Locale.ROOT, "%.0f", this.menu.tankAmount(MagneticSeparatorMenu.TANK_MAGNETIC)),
				String.format(Locale.ROOT, "%.0f", this.menu.tankAmount(MagneticSeparatorMenu.TANK_NON_MAGNETIC)));
		UiText.text(extractor, this.font, text, this.leftPos + 8, this.topPos + AMOUNT_Y, LABEL_COLOR);
	}

	private void drawCentered(GuiGraphicsExtractor extractor, Component text, int offsetX, int width, int offsetY) {
		UiText.text(extractor, this.font, text,
				this.leftPos + offsetX + (width - this.font.width(text)) / 2,
				this.topPos + offsetY, LABEL_COLOR);
	}

	// ------------------------------------------------------------------ 成分环

	/**
	 * 画当前选中罐的成分环。渲染本体在 {@link SlurryPie} 里，与粉碎机共用。
	 *
	 * <p>这台机器有三个罐，所以环旁边挂一列切换格（{@link #drawPieTabs}）——
	 * 六个面配置格已经在 y=107 占掉整行了，横着排不下第二个控件列。
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
	 * <p>每格左边一道竖条用的是该罐在液位条上的那个颜色，好让人一眼把格子与罐对上；
	 * 底色只区分「选中／未选中」，不参与指代，免得跟条上的相色混淆。
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
			case 1 -> COLOR_MAGNETIC;
			default -> COLOR_NON_MAGNETIC;
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

	// ------------------------------------------------------------------ 六面配置

	private void drawFaceCells(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		// 格子只写方位；角色看底色，完整名字走悬浮提示
		int hovered = this.faceCellAt(mouseX, mouseY);
		for (int i = 0; i < MagneticSeparatorMenu.FACE_ORDER.length; i++) {
			Direction side = MagneticSeparatorMenu.FACE_ORDER[i];
			FaceRole role = this.menu.role(side);
			int x = this.leftPos + FACE_X + i * (FACE_W + FACE_GAP);
			int y = this.topPos + FACE_Y;

			extractor.fill(x, y, x + FACE_W, y + FACE_H, roleColor(role));
			extractor.fill(x, y, x + FACE_W, y + 1, HIGHLIGHT);
			extractor.fill(x, y, x + 1, y + FACE_H, HIGHLIGHT);
			if (hovered == i) {
				extractor.fill(x, y, x + FACE_W, y + FACE_H, HOVER_TINT);
			}

			Component label = directionLabel(side);
			UiText.text(extractor, this.font, label, x + (FACE_W - this.font.width(label)) / 2, y + 4, 0xFFFFFFFF);
		}
		if (hovered >= 0) {
			Direction side = MagneticSeparatorMenu.FACE_ORDER[hovered];
			extractor.setTooltipForNextFrame(Component.translatable("gui.geometallurgy.face_cell",
					directionLabel(side), roleLabel(this.menu.role(side))), mouseX, mouseY);
		}
	}

	private void drawLegend(GuiGraphicsExtractor extractor) {
		FaceRole[] order = {FaceRole.INPUT, FaceRole.MAGNETIC, FaceRole.NON_MAGNETIC, FaceRole.DISABLED};
		for (int i = 0; i < order.length; i++) {
			int x = this.leftPos + 8 + i * LEGEND_STEP;
			int y = this.topPos + LEGEND_Y;
			extractor.fill(x, y + 2, x + 5, y + 7, roleColor(order[i]));
			UiText.text(extractor, this.font, roleLabel(order[i]), x + 7, y, LABEL_COLOR);
		}
	}

	private void drawTailingsToggle(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		boolean destroy = this.menu.destroyTailings();
		int x = this.leftPos + TAIL_X;
		int y = this.topPos + TAIL_Y;
		extractor.fill(x, y, x + TAIL_W, y + TAIL_H, destroy ? 0xFF7A3A3A : 0xFF4A6A4A);
		extractor.fill(x, y, x + TAIL_W, y + 1, HIGHLIGHT);
		extractor.fill(x, y, x + 1, y + TAIL_H, HIGHLIGHT);
		if (this.inside(mouseX, mouseY, TAIL_X, TAIL_Y, TAIL_W, TAIL_H)) {
			extractor.fill(x, y, x + TAIL_W, y + TAIL_H, HOVER_TINT);
		}

		Component text = Component.translatable(destroy
				? "gui.geometallurgy.tailings_destroy"
				: "gui.geometallurgy.tailings_keep");
		UiText.text(extractor, this.font, text, x + (TAIL_W - this.font.width(text)) / 2, y + 3, 0xFFFFFFFF);
	}

	private static int roleColor(FaceRole role) {
		return switch (role) {
			case INPUT -> COLOR_INPUT;
			case MAGNETIC -> COLOR_MAGNETIC;
			case NON_MAGNETIC -> COLOR_NON_MAGNETIC;
			case DISABLED -> COLOR_DISABLED;
		};
	}

	private static Component roleLabel(FaceRole role) {
		return Component.translatable(role.translationKey());
	}

	/** 方向的短标签；不按下标取，免得枚举顺序一变标签就串位。 */
	private static Component directionLabel(Direction side) {
		return Component.translatable("gui.geometallurgy.dir_" + side.getSerializedName());
	}

	// ------------------------------------------------------------------ 交互

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// 26.x 的窗口换成了 SDL，鼠标键号跟着换成 SDL 那套：左键是 1 不是 0。
		// 这里必须用常量，按老习惯写 == 0 的话左键永远进不来（点上去毫无反应）。
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			// 切换成分环看哪个罐 —— 纯客户端状态，不必往服务端发按钮。
			int tab = this.pieTabAt(event.x(), event.y());
			if (tab >= 0) {
				this.pieTab = tab;
				return true;
			}
			int cell = this.faceCellAt(event.x(), event.y());
			if (cell >= 0) {
				// 传的是方向的枚举序，不是格子的排列序 —— 界面把六个面按「上下北南西东」排，
				// 而枚举序是「下上北南西东」，两者差一位，混用会按到隔壁那个面。
				this.sendButton(MagneticSeparatorMenu.FACE_ORDER[cell].ordinal());
				return true;
			}
			if (this.inside(event.x(), event.y(), TAIL_X, TAIL_Y, TAIL_W, TAIL_H)) {
				this.sendButton(MagneticSeparatorBlockEntity.TAILINGS_BUTTON);
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	private int faceCellAt(double mouseX, double mouseY) {
		for (int i = 0; i < MagneticSeparatorMenu.FACE_ORDER.length; i++) {
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

	/** 四条 fill 画的边框：GUI 的 {@code outline()} 参数语义与 {@code fill()} 不一致，不能混用。 */
	private void drawBorder(GuiGraphicsExtractor extractor, int x, int y, int right, int bottom) {
		extractor.fill(x - 1, y - 1, right + 1, y, LABEL_COLOR);
		extractor.fill(x - 1, bottom, right + 1, bottom + 1, LABEL_COLOR);
		extractor.fill(x - 1, y - 1, x, bottom + 1, LABEL_COLOR);
		extractor.fill(right, y - 1, right + 1, bottom + 1, LABEL_COLOR);
	}
}
