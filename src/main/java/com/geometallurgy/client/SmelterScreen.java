package com.geometallurgy.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;
import com.geometallurgy.blockentity.ElectrolysisRole;
import com.geometallurgy.blockentity.PortRole;
import com.geometallurgy.chemical.Chemical;
import com.geometallurgy.chemical.MolStock;
import com.geometallurgy.blockentity.SmelterBlockEntity;
import com.geometallurgy.blockentity.SmelterProfile;
import com.geometallurgy.menu.SmelterMenu;
import com.geometallurgy.item.MolReadout;
import com.geometallurgy.metal.Ingots;
import com.geometallurgy.proto.RareEarth;
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
 * 冶炼炉的界面。三台炉子共用这一份实现 —— 差别只在 {@link SmelterMenu#profile()}。
 *
 * <p>自绘面板：两条液位条、两行读数、一条炉次进度、一个可切换的成分环、六面配置格，
 * 外加两个真物品槽（试剂 / 产物）。
 *
 * <p>电解炉的布局分两档（见 {@link SmelterMenu#solutionMode()}）：溶液档画溶液条 +
 * 阳极 / 阴极容器条与 <b>液 / 阳 / 阴 三个页签</b>，<b>不画矿粉罐 / 渣罐，也不画「进 / 锭」两格</b>
 * （这两份账在溶液档里不存在）；熔盐档画原来的矿粉罐 / 渣罐与 进 / 锭 两个页签。
 * 两套部件不会同时出现，页签集合同样按档切换（{@link #tabIds()}）。
 *
 * <h2>成分环为什么可以切</h2>
 * 这台机器上有两个值得看的成分：罐里现在是什么（决定能不能开工），以及这一炉会出什么锭
 * （决定值不值得开工）。两个都做常驻图会挤掉液位条，所以做成一个环加两格切换：
 * 默认画「这一炉预计出什么」——那是玩家真正关心的；想看料就切到「进料」。
 *
 * <h2>读数为什么分两行</h2>
 * 左边一律只放一个数，右侧那一列留给另一个数。上一轮在粉碎机上吃过挤在一行的亏：
 * 字数一长就叠在一起，而「叠字」这种毛病在验收截图上看不出来，得靠行剖面审计才抓得住。
 */
public class SmelterScreen extends AbstractContainerScreen<SmelterMenu> {

	private static final int PANEL_COLOR = 0xFFC6C6C6;
	private static final int HIGHLIGHT = 0xFFFFFFFF;
	private static final int SHADOW = 0xFF555555;
	private static final int LABEL_COLOR = 0xFF404040;
	private static final int TANK_BACKGROUND = 0xFF5A5A5A;
	private static final int OTHER_COLOR = 0xFF8B8B8B;
	private static final int HOVER_TINT = 0x50FFFFFF;
	/** 渣罐的底色：灰褐，与矿粉那种按相上色的罐子区分开。 */
	private static final int SLAG_COLOR = 0xFF6B6257;
	private static final Identifier SLOT_SPRITE = Identifier.withDefaultNamespace("container/slot");

	private static final int MACHINE_BOTTOM = 122;

	private static final int TANK_TOP = 36;
	private static final int TANK_HEIGHT = 32;
	private static final int TANK_WIDTH = 22;
	private static final int TANK_INPUT_X = 56;
	private static final int TANK_SLAG_X = 84;

	/** 溶液电解条：贴左沿，正好落在试剂（阳极）槽左边那一条空档。 */
	private static final int SOLUTION_X = 5;
	private static final int SOLUTION_W = 18;

	/** 成分环。 */
	private static final int PIE_CX = 130;
	private static final int PIE_CY = 52;
	private static final int PIE_R = 13;

	/** 环的两个切换格：挂在右侧，上下各一格。 */
	private static final int TAB_X = 152;
	private static final int TAB_Y = 64;
	private static final int TAB_W = 17;
	private static final int TAB_H = 7;
	private static final int TAB_GAP = 1;

	private static final int LABEL_Y = 70;
	private static final int AMOUNT_Y = 82;

	private static final int BAR_X = 8;
	private static final int BAR_Y = 94;
	private static final int BAR_H = 10;

	private static final int FACE_X = 5;
	private static final int FACE_Y = 106;
	private static final int FACE_W = 27;
	private static final int FACE_H = 14;
	private static final int FACE_GAP = 1;

	private static final int COLOR_DISABLED = 0xFF4A4A4A;
	private static final int COLOR_INPUT = 0xFF3F5F9F;
	private static final int COLOR_OUTPUT = 0xFF8A6A3A;
	private static final int COLOR_BYPRODUCT = 0xFF6E7480;

	/**
	 * 环上画的是哪一份成分：0 = 进料相，1 = 这一炉预计出的产物，2 = 溶液电解账，
	 * 3 = 阳极容器，4 = 阴极容器。
	 *
	 * <p>这是<b>语义 id</b>，不是页签的位置：溶液档没有「进料 / 产物」这两份账
	 * （矿粉罐与产物槽在这条档口下都不参与），所以页签位置由 {@link #tabIds()} 映射，
	 * 溶液档只画 液 / 阳 / 阴 三格。
	 */
	private static final int TAB_INPUT = 0;
	private static final int TAB_PRODUCT = 1;
	private static final int TAB_SOLUTION = 2;
	private static final int TAB_ANODE = 3;
	private static final int TAB_CATHODE = 4;

	public static final int GUI_WIDTH = 176;
	public static final int GUI_HEIGHT = 222;

	private int pieTab = TAB_PRODUCT;

	public SmelterScreen(SmelterMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title, GUI_WIDTH, GUI_HEIGHT);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(extractor, mouseX, mouseY, partialTick);
		// 两档的页签集合不同，切换时把停在对面档位页签上的选择收回来 ——
		// 否则会画出一个不存在的活动页（熔盐档只有 进/锭，溶液档只有 液/阳/阴）。
		if (this.menu.solutionMode()) {
			if (this.pieTab <= TAB_PRODUCT) {
				this.pieTab = TAB_SOLUTION;
			}
		} else if (this.pieTab > TAB_PRODUCT) {
			this.pieTab = TAB_PRODUCT;
		}
		this.drawPanel(extractor);
		this.drawSlots(extractor);
		this.drawReadout(extractor);
		if (this.menu.solutionMode()) {
			// 溶液档没有矿粉罐与渣罐：那两个位置让给阳极 / 阴极容器
			this.drawVesselBar(extractor, TANK_INPUT_X, TAB_ANODE);
			this.drawVesselBar(extractor, TANK_SLAG_X, TAB_CATHODE);
		} else {
			this.drawTank(extractor, TANK_INPUT_X, SmelterMenu.TANK_INPUT);
			this.drawSlagTank(extractor);
		}
		this.drawSolutionBar(extractor);
		this.drawLabels(extractor);
		this.drawAmounts(extractor);
		this.drawProgress(extractor);
		this.drawPie(extractor);
		this.drawTabs(extractor, mouseX, mouseY);
		this.drawFaceCells(extractor, mouseX, mouseY);
		this.drawSlotLabels(extractor);
	}

	/**
	 * 电解炉的两个电极格要标出来。
	 *
	 * <p>两格长得一模一样，作用却差得远：阳极每炉扣一点耐久，阴极四炉才扣一点。
	 * 不标出来玩家只能靠自己试，而「试」的代价是一根钨棒。
	 */
	private void drawSlotLabels(GuiGraphicsExtractor extractor) {
		if (this.menu.profile().reagent() != SmelterProfile.ReagentKind.ELECTRODE) {
			return;
		}
		this.drawSlotLabel(extractor, "gui.geometallurgy.slot_anode", SmelterMenu.SLOT_REAGENT_Y);
		this.drawSlotLabel(extractor, "gui.geometallurgy.slot_cathode", SmelterMenu.SLOT_CATHODE_Y);
	}

	private void drawSlotLabel(GuiGraphicsExtractor extractor, String key, int offsetY) {
		Component text = Component.translatable(key);
		// 标签画在槽右侧（x=46）：左边那一条留给溶液电解条，别再叠上去。
		UiText.text(extractor, this.font, text, this.leftPos + 44,
				this.topPos + offsetY + 5, LABEL_COLOR);
	}

	/**
	 * 悬停浮层。
	 *
	 * <p>走这条通道是为了让成分表盖在物品栏之上 —— 画在背景层的话，浮层会被随后绘制的物品压住。
	 */
	@Override
	protected void extractTooltip(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		super.extractTooltip(extractor, mouseX, mouseY);
		if (SlurryPie.hovered(mouseX, mouseY, this.leftPos + PIE_CX, this.topPos + PIE_CY, PIE_R)) {
			SlurryPie.popup(extractor, this.font, Component.translatable(this.pieTitleKey()),
					this.slices(), mouseX, mouseY, this.width, this.height,
					this.leftPos + this.imageWidth - 4);
		}
		// 溶液电解账的读数：按相态分组（气 / 液 / 固各一行，行内才是混合物，设计稿 §4.1）
		if (this.menu.solutionMode()
				&& this.inside(mouseX, mouseY, SOLUTION_X, TANK_TOP, SOLUTION_W, TANK_HEIGHT)) {
			List<Component> lines = new ArrayList<>(
					MolReadout.lines(this.solutionStock(), "gui.geometallurgy.bucket.entry"));
			extractor.setComponentTooltipForNextFrame(this.font, lines, mouseX, mouseY);
		}
		// 阳极 / 阴极容器同样按相态分组读数
		if (this.menu.solutionMode()
				&& this.inside(mouseX, mouseY, TANK_INPUT_X, TANK_TOP, TANK_WIDTH, TANK_HEIGHT)) {
			List<Component> lines = new ArrayList<>(
					MolReadout.lines(this.anodeStock(), "gui.geometallurgy.bucket.entry"));
			extractor.setComponentTooltipForNextFrame(this.font, lines, mouseX, mouseY);
		}
		if (this.menu.solutionMode()
				&& this.inside(mouseX, mouseY, TANK_SLAG_X, TANK_TOP, TANK_WIDTH, TANK_HEIGHT)) {
			List<Component> lines = new ArrayList<>(
					MolReadout.lines(this.cathodeStock(), "gui.geometallurgy.bucket.entry"));
			extractor.setComponentTooltipForNextFrame(this.font, lines, mouseX, mouseY);
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

	private void drawReadout(GuiGraphicsExtractor extractor) {
		// 第一行：状态（左） / 实际功率（右）。电解炉有溶液在反应时，把「等待进料」换成
		//「溶液电解中」—— 否则明明在跑，界面却写空闲。
		Component statusText = this.menu.usesSolution() && this.menu.solutionMol() > 0.0
				&& this.menu.status() == SmelterBlockEntity.Status.IDLE
				? Component.translatable("gui.geometallurgy.smelter_solution_running")
				: this.menu.status().translatable();
		UiText.text(extractor, this.font, statusText, this.leftPos + 8, this.topPos + 15,
				this.statusColor());

		Component power = Component.translatable("gui.geometallurgy.smelter_power",
				String.format(Locale.ROOT, "%.0f", this.menu.power()));
		UiText.text(extractor, this.font, power, this.leftPos + this.imageWidth - 8 - this.font.width(power),
				this.topPos + 15, LABEL_COLOR);

		// 第二行：纯度（左） / 这一炉能出多少（右）—— 「料有多干净」与「值不值得烧」摆一起
		// 百分号拼在 Java 侧：语言文件里带 % 会被当成格式符，写进去反而要转义
		Component purity = Component.translatable("gui.geometallurgy.smelter_purity",
				String.format(Locale.ROOT, "%.0f%%", this.menu.purity() * 100.0));
		UiText.text(extractor, this.font, purity, this.leftPos + 8, this.topPos + 26, LABEL_COLOR);

		Component yield = Component.translatable("gui.geometallurgy.smelter_yield",
				String.format(Locale.ROOT, "%.0f", this.menu.productYield()));
		UiText.text(extractor, this.font, yield, this.leftPos + this.imageWidth - 8 - this.font.width(yield),
				this.topPos + 26, LABEL_COLOR);
	}

	private int statusColor() {
		return switch (this.menu.status()) {
			case RUNNING -> 0xFF1F7A33;
			case NO_POWER, TOO_LEAN -> 0xFF9A4A18;
			case WRONG_REAGENT, NO_REAGENT -> 0xFF8A2E8A;
			case OUTPUT_FULL, SLAG_FULL -> 0xFF8A6A18;
			case IDLE, NO_ROUTE, GATHERING -> LABEL_COLOR;
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
			List<SmelterMenu.PhaseEntry> entries = this.menu.inputPhases();
			int cursor = bottom;
			int remaining = filled;
			for (int i = 0; i < entries.size() && remaining > 0; i++) {
				SmelterMenu.PhaseEntry entry = entries.get(i);
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

	/**
	 * 渣罐只画一根灰褐色的柱子。
	 *
	 * <p>渣的成分对玩法没有影响（它就是"扔掉的东西"），所以不必按相上色 ——
	 * 把一根柱子做成分色反而会让人以为那里面还有什么可回收的。
	 */
	private void drawSlagTank(GuiGraphicsExtractor extractor) {
		int x = this.leftPos + TANK_SLAG_X;
		int y = this.topPos + TANK_TOP;
		int right = x + TANK_WIDTH;
		int bottom = y + TANK_HEIGHT;

		extractor.fill(x, y, right, bottom, TANK_BACKGROUND);
		double amount = this.menu.tankAmount(SmelterMenu.TANK_SLAG);
		if (amount > 0.0) {
			int filled = Mth.ceil(amount / VirtualSlurry.CAPACITY * TANK_HEIGHT);
			extractor.fill(x + 1, bottom - filled, right - 1, bottom, SLAG_COLOR);
		}
		this.drawBorder(extractor, x, y, right, bottom);
	}

	/**
	 * 溶液电解条：竖直液位，颜色取账里 mol 最多的那种物质。
	 *
	 * <p>只有溶液档会画（{@link SmelterMenu#solutionMode()}）；熔盐档与别的炉子这条恒为空，
	 * 画出来只会让人以为有个用不上的槽。
	 */
	private void drawSolutionBar(GuiGraphicsExtractor extractor) {
		if (!this.menu.solutionMode()) {
			return;
		}
		int x = this.leftPos + SOLUTION_X;
		int y = this.topPos + TANK_TOP;
		int right = x + SOLUTION_W;
		int bottom = y + TANK_HEIGHT;

		extractor.fill(x, y, right, bottom, TANK_BACKGROUND);
		double amount = this.menu.solutionMol();
		if (amount > 0.0) {
			int filled = Mth.ceil(amount / SmelterBlockEntity.SOLUTION_CAPACITY * TANK_HEIGHT);
			String dominant = this.menu.solutionDominantId();
			int color = dominant == null ? OTHER_COLOR : 0xFF000000 | Chemical.colorOf(dominant);
			extractor.fill(x + 1, bottom - filled, right - 1, bottom, color);
		}
		this.drawBorder(extractor, x, y, right, bottom);

		Component label = Component.translatable("gui.geometallurgy.smelter_solution");
		UiText.text(extractor, this.font, label, x + (SOLUTION_W - this.font.width(label)) / 2,
				this.topPos + LABEL_Y, LABEL_COLOR);
		Component value = Component.literal(String.format(Locale.ROOT, "%.0f", amount));
		UiText.text(extractor, this.font, value, x + (SOLUTION_W - this.font.width(value)) / 2,
				this.topPos + AMOUNT_Y, LABEL_COLOR);
	}

	/**
	 * 阳极 / 阴极容器液位条：电解炉把原来的矿粉罐、渣罐位置让给这两个电极容器。
	 *
	 * <p>只有溶液档会画；熔盐档与别的炉子这两个位置仍是矿粉 / 渣罐。标签取短名（条宽只有 22），
	 * 完整名字走成分环的页签与浮层标题。
	 */
	private void drawVesselBar(GuiGraphicsExtractor extractor, int offsetX, int tab) {
		int x = this.leftPos + offsetX;
		int y = this.topPos + TANK_TOP;
		int right = x + TANK_WIDTH;
		int bottom = y + TANK_HEIGHT;

		extractor.fill(x, y, right, bottom, TANK_BACKGROUND);
		double amount = tab == TAB_ANODE ? this.menu.anodeMol() : this.menu.cathodeMol();
		if (amount > 0.0) {
			int filled = Mth.ceil(amount / SmelterBlockEntity.VESSEL_CAPACITY * TANK_HEIGHT);
			String dominant = tab == TAB_ANODE
					? this.menu.anodeDominantId()
					: this.menu.cathodeDominantId();
			int color = dominant == null ? OTHER_COLOR : 0xFF000000 | Chemical.colorOf(dominant);
			extractor.fill(x + 1, bottom - filled, right - 1, bottom, color);
		}
		this.drawBorder(extractor, x, y, right, bottom);

		Component label = Component.translatable(tab == TAB_ANODE
				? "gui.geometallurgy.smelter_anode_vessel"
				: "gui.geometallurgy.smelter_cathode_vessel");
		UiText.text(extractor, this.font, label, x + (TANK_WIDTH - this.font.width(label)) / 2,
				this.topPos + LABEL_Y, LABEL_COLOR);
		Component value = Component.literal(String.format(Locale.ROOT, "%.0f", amount));
		UiText.text(extractor, this.font, value, x + (TANK_WIDTH - this.font.width(value)) / 2,
				this.topPos + AMOUNT_Y, LABEL_COLOR);
	}

	private void drawLabels(GuiGraphicsExtractor extractor) {
		if (!this.menu.solutionMode()) {
			this.drawCentered(extractor, Component.translatable("gui.geometallurgy.smelter_in"),
					TANK_INPUT_X, TANK_WIDTH, LABEL_Y);
			this.drawCentered(extractor, Component.translatable("gui.geometallurgy.smelter_slag"),
					TANK_SLAG_X, TANK_WIDTH, LABEL_Y);
		}
		this.drawCentered(extractor, Component.translatable(this.pieTitleKey()),
				PIE_CX - PIE_R, PIE_R * 2, LABEL_Y);
	}

	private void drawAmounts(GuiGraphicsExtractor extractor) {
		if (this.menu.solutionMode()) {
			// 溶液档的两个数已经画在阳极 / 阴极容器条下面
			return;
		}
		Component text = Component.translatable("gui.geometallurgy.smelter_amounts",
				String.format(Locale.ROOT, "%.0f", this.menu.tankAmount(SmelterMenu.TANK_INPUT)),
				String.format(Locale.ROOT, "%.0f", this.menu.tankAmount(SmelterMenu.TANK_SLAG)));
		UiText.text(extractor, this.font, text, this.leftPos + 26, this.topPos + AMOUNT_Y, LABEL_COLOR);
	}

	private void drawCentered(GuiGraphicsExtractor extractor, Component text, int offsetX, int width, int offsetY) {
		UiText.text(extractor, this.font, text,
				this.leftPos + offsetX + (width - this.font.width(text)) / 2,
				this.topPos + offsetY, LABEL_COLOR);
	}

	// ------------------------------------------------------------------ 炉次进度

	private void drawProgress(GuiGraphicsExtractor extractor) {
		int x = this.leftPos + BAR_X;
		int y = this.topPos + BAR_Y;
		int right = x + (this.imageWidth - BAR_X * 2);

		extractor.fill(x, y, right, y + BAR_H, TANK_BACKGROUND);
		double fraction = this.menu.progressFraction();
		if (fraction > 0.0) {
			int filled = Math.max(2, (int) Math.round(fraction * (right - x)));
			extractor.fill(x + 1, y + 1, x + 1 + filled, y + BAR_H - 1, 0xFF3F7F5F);
		}
		this.drawBorder(extractor, x, y, right, y + BAR_H);

		// 百分比画在条里面：条高 10 像素，正好容得下一行字。
		// 早先是画在条<b>上方</b>的，结果跟上面那行「进 xx · 渣 xx mB」撞在一起 ——
		// 两行文字叠着看，在截图里几乎发现不了，只有把行剖面拉出来才看得出来。
		// 电解容器堵住时，进度条里改报「哪一相、多少 mol」—— 这正是它停在原地不推的原因
		// （docs/科技线-v1.md §13.1）。没堵就还是原来的炉次百分比。
		Component text = this.menu.blockedPhase() != null
				? Component.translatable("gui.geometallurgy.smelter_vessel_full",
						Component.translatable(phaseKey(this.menu.blockedPhase())),
						String.format(Locale.ROOT, "%.1f", this.menu.blockedMol()))
				: Component.translatable("gui.geometallurgy.smelter_progress",
						String.format(Locale.ROOT, "%.0f%%", fraction * 100.0));
		UiText.text(extractor, this.font, text,
				x + (right - x - this.font.width(text)) / 2, y + 1, 0xFFFFFFFF);
	}

	// ------------------------------------------------------------------ 成分环

	private String pieTitleKey() {
		return switch (this.pieTab) {
			case TAB_INPUT -> "gui.geometallurgy.smelter_pie_input";
			case TAB_SOLUTION -> "gui.geometallurgy.smelter_pie_solution";
			case TAB_ANODE -> "gui.geometallurgy.smelter_pie_anode";
			case TAB_CATHODE -> "gui.geometallurgy.smelter_pie_cathode";
			default -> "gui.geometallurgy.smelter_pie_product";
		};
	}

	private List<SlurryPie.Slice> slices() {
		if (this.pieTab == TAB_INPUT) {
			return this.menu.inputPhases().stream()
					.map(entry -> SlurryPie.of(entry.part(), entry.fraction()))
					.toList();
		}
		if (this.pieTab == TAB_SOLUTION) {
			return this.solutionSlices();
		}
		if (this.pieTab == TAB_ANODE) {
			return this.anodeSlices();
		}
		if (this.pieTab == TAB_CATHODE) {
			return this.cathodeSlices();
		}
		return this.productSlices();
	}

	/**
	 * 溶液账的饼：按<b>相态</b>并块，相内物质作为 children 缩进在浮层里（设计稿 §4.1）。
	 *
	 * <p>溶液账里是 Chemical 物质，不是矿相 Substance：名字用 chemical.geometallurgy.*、
	 * 颜色取 Chemical.colorOf(id)，走 PhasePie 的化学物质通路（不能走 SlurryPie.of(String)，
	 * 那条会显示原始键、而且所有扇形都掉进同一个兜底色 —— bug#16）。
	 */
	private List<SlurryPie.Slice> solutionSlices() {
		return this.chemicalSlices(this.menu.solutionComposition());
	}

	/** 阳极容器的饼：与溶液同一套按相态并块的画法。 */
	private List<SlurryPie.Slice> anodeSlices() {
		return this.chemicalSlices(this.menu.anodeComposition());
	}

	/** 阴极容器的饼。 */
	private List<SlurryPie.Slice> cathodeSlices() {
		return this.chemicalSlices(this.menu.cathodeComposition());
	}

	private List<SlurryPie.Slice> chemicalSlices(List<SmelterMenu.ChemicalEntry> composition) {
		return PhasePie.slices(composition.stream()
				.map(entry -> new PhasePie.Part(entry.id(), entry.fraction()))
				.toList());
	}

	/**
	 * 一份 mol 账按总 mol 复原成 {@link MolStock}：同步过来的是各成分占比，乘以总 mol
	 * 就是各成分的 mol，供分相读数用。
	 */
	private MolStock stockOf(List<SmelterMenu.ChemicalEntry> composition, double total) {
		MolStock stock = MolStock.empty();
		for (SmelterMenu.ChemicalEntry entry : composition) {
			stock.add(entry.id(), entry.fraction() * total);
		}
		return stock;
	}

	/** 溶液账按总 mol 复原。 */
	private MolStock solutionStock() {
		return this.stockOf(this.menu.solutionComposition(), this.menu.solutionMol());
	}

	/** 阳极容器账按总 mol 复原。 */
	private MolStock anodeStock() {
		return this.stockOf(this.menu.anodeComposition(), this.menu.anodeMol());
	}

	/** 阴极容器账按总 mol 复原。 */
	private MolStock cathodeStock() {
		return this.stockOf(this.menu.cathodeComposition(), this.menu.cathodeMol());
	}

	/**
	 * 产物饼。
	 *
	 * <p>稀土在环上只占<b>一块</b>：十六种元素各画一小片淡色扇形，环就成了一团糊，
	 * 何况也没人能从颜色上分出镝和铽。逐项配分留到浮层里展开 —— 见
	 * {@link SlurryPie.Slice#children()}。
	 */
	private List<SlurryPie.Slice> productSlices() {
		List<SlurryPie.Slice> slices = new ArrayList<>();
		List<SlurryPie.Slice> rareEarth = new ArrayList<>();
		double rareEarthTotal = 0.0;
		for (SmelterMenu.ProductEntry entry : this.menu.plannedProduct()) {
			if (RareEarth.isRareEarth(entry.substance())) {
				rareEarth.add(SlurryPie.of(entry.substance(), entry.fraction()));
				rareEarthTotal += entry.fraction();
			} else {
				slices.add(SlurryPie.of(entry.substance(), entry.fraction()));
			}
		}
		if (rareEarthTotal > 0.0) {
			slices.add(SlurryPie.of(Ingots.RARE_EARTH, rareEarthTotal, rareEarth));
		}
		slices.sort(Comparator.comparingDouble(SlurryPie.Slice::fraction).reversed());
		return slices;
	}

	private void drawPie(GuiGraphicsExtractor extractor) {
		SlurryPie.draw(extractor, this.leftPos + PIE_CX, this.topPos + PIE_CY, PIE_R, this.slices());
	}

	/**
	 * 这一档要画哪几个页签（值是 {@code TAB_*} 语义 id，顺序即画出来的上下顺序）。
	 *
	 * <p>溶液档的三本账是溶液 / 阳极 / 阴极，<b>没有</b>矿粉进料与产物锭 —— 矿粉罐与产物槽
	 * 都只属于熔盐档，所以「进 / 锭」两格在溶液档里不画（用户 2026-10-17 报：没切到熔盐档时
	 * 这两格还在，像两个用不上的槽）。熔盐档与还原 / 置换两路仍是 进 / 锭 两格。
	 */
	private int[] tabIds() {
		return this.menu.solutionMode()
				? new int[] { TAB_SOLUTION, TAB_ANODE, TAB_CATHODE }
				: new int[] { TAB_INPUT, TAB_PRODUCT };
	}

	private int tabCount() {
		return tabIds().length;
	}

	/** 第 {@code index} 个页签对应的语义 id。 */
	private int tabId(int index) {
		return tabIds()[index];
	}

	private String tabKey(int tab) {
		return switch (tab) {
			case TAB_INPUT -> "gui.geometallurgy.smelter_tab_input";
			case TAB_SOLUTION -> "gui.geometallurgy.smelter_tab_solution";
			case TAB_ANODE -> "gui.geometallurgy.smelter_tab_anode";
			case TAB_CATHODE -> "gui.geometallurgy.smelter_tab_cathode";
			default -> "gui.geometallurgy.smelter_tab_product";
		};
	}

	private int tabColor(int tab) {
		return switch (tab) {
			case TAB_INPUT -> COLOR_INPUT;
			case TAB_SOLUTION -> 0xFF3F7FAF;
			case TAB_ANODE -> 0xFF9A5A3A;
			case TAB_CATHODE -> 0xFF4A5A9A;
			default -> COLOR_OUTPUT;
		};
	}

	private void drawTabs(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		for (int tab = 0; tab < tabCount(); tab++) {
			int id = tabId(tab);
			int y = this.topPos + TAB_Y + tab * (TAB_H + TAB_GAP);
			int x = this.leftPos + TAB_X;
			boolean active = this.pieTab == id;
			extractor.fill(x, y, x + TAB_W, y + TAB_H, active ? tabColor(id) : COLOR_DISABLED);
			extractor.fill(x, y, x + TAB_W, y + 1, HIGHLIGHT);
			extractor.fill(x, y, x + 1, y + TAB_H, HIGHLIGHT);
			if (this.tabAt(mouseX, mouseY) == tab) {
				extractor.fill(x, y, x + TAB_W, y + TAB_H, HOVER_TINT);
			}
			Component text = Component.translatable(tabKey(id));
			UiText.text(extractor, this.font, text, x + (TAB_W - this.font.width(text)) / 2, y, 0xFFFFFFFF);
		}
	}

	private int tabAt(double mouseX, double mouseY) {
		for (int tab = 0; tab < tabCount(); tab++) {
			int offsetY = TAB_Y + tab * (TAB_H + TAB_GAP);
			if (this.inside(mouseX, mouseY, TAB_X, offsetY, TAB_W, TAB_H)) {
				return tab;
			}
		}
		return -1;
	}

	// ------------------------------------------------------------------ 六面配置

	private void drawFaceCells(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		// 格子只写方位；角色看底色，完整名字走悬浮提示 —— 所有机器统一（bug#7）
		int hovered = this.faceCellAt(mouseX, mouseY);
		for (int i = 0; i < SmelterMenu.FACE_ORDER.length; i++) {
			Direction side = SmelterMenu.FACE_ORDER[i];
			int x = this.leftPos + FACE_X + i * (FACE_W + FACE_GAP);
			int y = this.topPos + FACE_Y;

			extractor.fill(x, y, x + FACE_W, y + FACE_H, faceColor(side));
			extractor.fill(x, y, x + FACE_W, y + 1, HIGHLIGHT);
			extractor.fill(x, y, x + 1, y + FACE_H, HIGHLIGHT);
			if (hovered == i) {
				extractor.fill(x, y, x + FACE_W, y + FACE_H, HOVER_TINT);
			}

			Component label = Component.translatable("gui.geometallurgy.dir_" + side.getSerializedName());
			UiText.text(extractor, this.font, label, x + (FACE_W - this.font.width(label)) / 2, y + 4, 0xFFFFFFFF);
		}
		if (hovered >= 0) {
			Direction side = SmelterMenu.FACE_ORDER[hovered];
			extractor.setTooltipForNextFrame(Component.translatable("gui.geometallurgy.face_cell",
					Component.translatable("gui.geometallurgy.dir_" + side.getSerializedName()),
					faceName(side)), mouseX, mouseY);
		}
	}

	/** 某一面的底色：电解路用自己的角色枚举，其余两路仍是 {@link PortRole}。 */
	private int faceColor(Direction side) {
		return this.menu.usesSolution()
				? roleColor(this.menu.electroRole(side))
				: roleColor(this.menu.role(side));
	}

	/** 某一面角色的完整名字（悬浮提示用）。 */
	private Component faceName(Direction side) {
		return this.menu.usesSolution()
				? Component.translatable(this.menu.electroRole(side).translationKey())
				: Component.translatable(this.menu.role(side).translationKey());
	}

	private static int roleColor(PortRole role) {
		return switch (role) {
			case INPUT -> COLOR_INPUT;
			case OUTPUT -> COLOR_OUTPUT;
			case BYPRODUCT -> COLOR_BYPRODUCT;
			case DISABLED -> COLOR_DISABLED;
		};
	}

	private static int roleColor(ElectrolysisRole role) {
		return switch (role) {
			case SOLUTION_IN -> COLOR_INPUT;
			case SOLUTION_OUT -> COLOR_OUTPUT;
			case ANODE_OUT -> 0xFF9A5A3A;
			case CATHODE_OUT -> 0xFF4A5A9A;
			case POWDER_IN -> COLOR_INPUT;
			case SLAG_OUT -> COLOR_BYPRODUCT;
			case DISABLED -> COLOR_DISABLED;
		};
	}

	// ------------------------------------------------------------------ 交互

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// 26.x 的窗口是 SDL，左键键号是 1 而不是 0。
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			int tab = this.tabAt(event.x(), event.y());
			if (tab >= 0) {
				// 纯客户端状态：切换看哪一份成分，不需要跟服务端打招呼。
				// tabAt 给的是位置，pieTab 存的是语义 id，两者在溶液档不是一回事。
				this.pieTab = this.tabId(tab);
				return true;
			}
			int cell = this.faceCellAt(event.x(), event.y());
			if (cell >= 0) {
				// 传方向的枚举序，不是格子的排列序：界面按「上下北南西东」排，
				// 枚举序是「下上北南西东」，两者差一位。
				this.sendButton(SmelterMenu.FACE_ORDER[cell].ordinal());
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	private int faceCellAt(double mouseX, double mouseY) {
		for (int i = 0; i < SmelterMenu.FACE_ORDER.length; i++) {
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

	/** 相态的本地化键（气体 / 液体 / 固体）。 */
	private static String phaseKey(Chemical.Phase phase) {
		return switch (phase) {
			case GAS -> "gui.geometallurgy.phase_gas";
			case LIQUID -> "gui.geometallurgy.phase_liquid";
			case SOLID -> "gui.geometallurgy.phase_solid";
		};
	}

	private void drawBorder(GuiGraphicsExtractor extractor, int x, int y, int right, int bottom) {
		extractor.fill(x - 1, y - 1, right + 1, y, LABEL_COLOR);
		extractor.fill(x - 1, bottom, right + 1, bottom + 1, LABEL_COLOR);
		extractor.fill(x - 1, y - 1, x, bottom + 1, LABEL_COLOR);
		extractor.fill(right, y - 1, right + 1, bottom + 1, LABEL_COLOR);
	}
}
