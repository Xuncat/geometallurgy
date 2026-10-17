package com.geometallurgy.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;
import com.geometallurgy.blockentity.MixerBlockEntity;
import com.geometallurgy.blockentity.ReactorStatus;
import com.geometallurgy.chemical.Chemical;
import com.geometallurgy.menu.AbstractReactorMenu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * 反应釜与搅拌器共用的界面骨架。
 *
 * <p>界面上没有桶槽（版本 B）：桶放在外面的容器里、或者直接连着储罐方块，机器自己按面交换。
 * 面板自上而下是：状态、催化剂槽、<b>成分饼图 + 进料/混合槽切换格</b>、
 * <b>流体缓存条</b>（A / B 两根，加一根混合槽）、六面配置格，
 * 最后一行是搅拌器专属的<b>比率滑轨</b>与<b>搅拌开关</b>。
 *
 * <h2>版面约定（2026-10-15 重排）</h2>
 * <ul>
 *   <li>饼图搬到右上角并放大（半径 20）：左边只被缓存条占用，右边那整块是空的。</li>
 *   <li>饼图右侧一列两格切换「进料 / 混合槽」——纯客户端状态，不进服务端、不存档。</li>
 *   <li>六面格子<b>只写方位</b>，角色看底色、完整名字走悬浮提示 —— 27 px 塞不下「上 关闭」。</li>
 *   <li>比率改成一条 0..100 的<b>连续滑轨</b>：按住拖动，拖动时限流发送，松手补一个最终值。</li>
 * </ul>
 */
public abstract class AbstractReactorScreen<T extends AbstractReactorMenu> extends AbstractContainerScreen<T> {

	protected static final int PANEL_COLOR = 0xFFC6C6C6;
	protected static final int HIGHLIGHT = 0xFFFFFFFF;
	protected static final int SHADOW = 0xFF555555;
	protected static final int LABEL_COLOR = 0xFF404040;
	protected static final int VALUE_COLOR = 0xFF202020;
	protected static final int BAR_BACKGROUND = 0xFF5A5A5A;
	protected static final int BAR_FILL = 0xFF3FBF5F;
	protected static final int BUTTON_BG = 0xFF9A9A9A;
	protected static final int BUTTON_HOVER = 0xFFB8B8B8;
	protected static final int CELL_OFF = 0xFFADADAD;
	protected static final int CELL_ON = 0xFF4A4A4A;
	protected static final int CELL_TEXT_OFF = 0xFF303030;
	protected static final Identifier SLOT_SPRITE = Identifier.withDefaultNamespace("container/slot");
	protected static final int HOVER_TINT = 0x50FFFFFF;

	// ---- 版面（相对 leftPos / topPos）
	protected static final int STATUS_Y = 18;
	/** 催化剂槽本身在菜单里定死 (8,32)，这里只放它的文字标签。 */
	protected static final int CATALYST_LABEL_X = 30;
	protected static final int CATALYST_LABEL_Y = 37;

	/**
	 * 成分饼图：右上角那块空地。
	 *
	 * <p>半径 20 是用户点名要的「这两台机器空闲空间多，饼图做大」——
	 * 左边缓存条到 x=80 为止、饼左缘 100，不打架；饼顶 36 也在状态行之下。
	 */
	protected static final int PIE_CX = 120;
	protected static final int PIE_CY = 56;
	protected static final int PIE_R = 20;
	/** 切换格：饼图右边竖排两格。 */
	protected static final int PIE_TAB_X = 142;
	protected static final int PIE_TAB_Y = 43;
	protected static final int PIE_TAB_W = 30;
	protected static final int PIE_TAB_H = 13;
	protected static final int PIE_TAB_GAP = 1;

	/** 切换格的排列顺序与语言键；不按下标取别的数组，避免两处顺序错位。 */
	protected static final String[] PIE_TAB_KEYS = {
			"gui.geometallurgy.reactor_pie_feed",
			"gui.geometallurgy.reactor_pie_tank",
	};
	protected static final int[] PIE_TAB_COLORS = {
			0xFF3F5F9F,
			0xFF3FBF5F,
	};

	// 缓存条：A / B / 混合槽（反应釜与搅拌器都是两根进料条）
	protected static final int BAR_Y = 52;
	protected static final int BAR_H = 26;
	protected static final int BAR_A_X = 8;
	protected static final int BAR_B_X = 28;
	protected static final int BAR_SIDE_W = 16;
	protected static final int BAR_TANK_X = 54;
	protected static final int BAR_TANK_W = 26;
	protected static final int BAR_LABEL_Y = 80;
	protected static final int BAR_NUM_Y = 90;

	protected static final int FACE_Y = 102;
	protected static final int FACE_X = 5;
	protected static final int FACE_W = 27;
	protected static final int FACE_H = 14;
	protected static final int FACE_GAP = 1;

	/** 搅拌器的比率滑轨 + 搅拌开关（与反应釜的主成分同一行）。 */
	protected static final int RATIO_BAND_Y = 120;
	protected static final int RATIO_BAND_H = 14;
	protected static final int RATIO_TRACK_X = 8;
	protected static final int RATIO_TRACK_W = 90;
	protected static final int RATIO_TRACK_Y = 128;
	protected static final int RATIO_TRACK_H = 6;
	protected static final int STIR_X = 116;
	protected static final int STIR_W = 52;
	protected static final int STIR_H = 14;

	/** 反应釜的主成分那一行。 */
	protected static final int DOMINANT_Y = 120;

	/**
	 * 成分饼图当前在看哪一份。纯客户端状态：只决定画哪一份，不改变机器行为，
	 * 所以既不用同步、也不必存进方块实体。
	 */
	private int pieTab = 0;

	/** 正在拖比率滑轨；拖动时用本地预览值，免得等服务端回包。 */
	private boolean draggingRatio;
	private int ratioPreview = -1;
	private int lastSentRatio = -1;

	protected AbstractReactorScreen(T menu, Inventory inventory, Component title, int width, int height) {
		super(menu, inventory, title, width, height);
	}

	/** 六个面在界面里的顺序。 */
	protected static Direction face(int index) {
		return AbstractReactorMenu.FACE_ORDER[index];
	}

	/** 某个面角色序号对应的本地化名字（完整，用于悬浮提示）。 */
	protected abstract Component roleName(int ordinal);

	/** 某个面角色序号对应的底色。 */
	protected abstract int roleColor(int ordinal);

	/** 这台机器画不画比率滑轨与搅拌开关。 */
	protected abstract boolean showsRatio();

	// ------------------------------------------------------------------ 绘制

	@Override
	public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(extractor, mouseX, mouseY, partialTick);
		this.drawPanel(extractor);
		this.drawSlots(extractor);
		this.drawReadout(extractor, mouseX, mouseY);
		this.drawBars(extractor, mouseX, mouseY);
		this.drawPieTabs(extractor, mouseX, mouseY);
		this.drawFaceCells(extractor, mouseX, mouseY);
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
	}

	/** 自绘面板必须自己补槽底纹，否则物品栏与催化剂槽都是一片灰。 */
	private void drawSlots(GuiGraphicsExtractor extractor) {
		for (Slot slot : this.menu.slots) {
			extractor.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, 18, 18, 0, 0,
					this.leftPos + slot.x - 1, this.topPos + slot.y - 1, 18, 18);
		}
	}

	private void drawReadout(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		ReactorStatus status = this.menu.status();
		UiText.text(extractor, this.font, this.statusText(status), this.leftPos + 8,
				this.topPos + STATUS_Y, statusColor(status));

		UiText.text(extractor, this.font, Component.translatable("gui.geometallurgy.reactor_catalyst"),
				this.leftPos + CATALYST_LABEL_X, this.topPos + CATALYST_LABEL_Y, LABEL_COLOR);

		// 成分饼图（进料 / 混合槽两份，切换格决定画哪一份）
		this.drawPie(extractor);

		// 质量：画在缓存条数字那一行的右端，任何机器都只有这一处
		String mass = Component.translatable("gui.geometallurgy.reactor_mass", format(this.menu.massGrams()))
				.getString();
		UiText.text(extractor, this.font, mass, this.leftPos + this.imageWidth - 8 - this.font.width(mass),
				this.topPos + BAR_NUM_Y, LABEL_COLOR);

		if (showsRatio()) {
			this.drawRatioSlider(extractor, mouseX, mouseY);
		} else {
			// 反应釜没有滑轨，主成分就在这一行
			String dominant = this.menu.dominantId();
			if (dominant != null) {
				UiText.text(extractor, this.font, dominantLine(dominant), this.leftPos + 8,
						this.topPos + DOMINANT_Y, LABEL_COLOR);
			}
		}
	}

	// ------------------------------------------------------------------ 缓存条

	private void drawBars(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		// 两台机器都是两根进料条：搅拌器是 A / B 预混，反应釜是两本输入缓冲
		this.drawBar(extractor, BAR_A_X, BAR_SIDE_W, this.menu.bufferFraction(0), BAR_FILL,
				Component.translatable("gui.geometallurgy.bar_a"), this.menu.bufferMol(0), false);
		this.drawBar(extractor, BAR_B_X, BAR_SIDE_W, this.menu.bufferFraction(1), BAR_FILL,
				Component.translatable("gui.geometallurgy.bar_b"), this.menu.bufferMol(1), false);

		// 混合槽：颜色取主成分的显示色，空槽就是一层暗底
		String dominant = this.menu.dominantId();
		int color = dominant == null ? BAR_BACKGROUND : 0xFF000000 | Chemical.colorOf(dominant);
		boolean hover = this.inside(mouseX, mouseY, BAR_TANK_X - 1, BAR_Y - 1, BAR_TANK_W + 2, BAR_H + 2);
		this.drawBar(extractor, BAR_TANK_X, BAR_TANK_W, this.menu.fillFraction(), color,
				Component.translatable("gui.geometallurgy.bar_tank"), this.menu.tankMol(), hover);
		if (hover) {
			Component line = dominant == null
					? Component.translatable("gui.geometallurgy.reactor_tank",
							format(this.menu.tankMol()), format(this.menu.tankCapacity()))
					: dominantLine(dominant);
			extractor.setTooltipForNextFrame(line, mouseX, mouseY);
		}
	}

	/**
	 * 一根竖直缓存条：从下往上长，外圈一道边框，条下方一行名字、一行数字（整数 mol）。
	 *
	 * <p>{@code hover} 为真时额外描一层淡白，提示「这一条有悬浮说明」。
	 */
	private void drawBar(GuiGraphicsExtractor extractor, int offsetX, int width, double fraction, int fillColor,
			Component label, double mol, boolean hover) {
		int x = this.leftPos + offsetX;
		int y = this.topPos + BAR_Y;
		int right = x + width;
		int bottom = y + BAR_H;

		extractor.fill(x, y, right, bottom, BAR_BACKGROUND);
		int filled = (int) Math.round(BAR_H * Math.max(0.0, Math.min(1.0, fraction)));
		if (filled > 0) {
			extractor.fill(x + 1, bottom - filled, right - 1, bottom, fillColor);
		}
		if (hover) {
			extractor.fill(x, y, right, bottom, HOVER_TINT);
		}
		this.drawBorder(extractor, x, y, right, bottom);

		UiText.text(extractor, this.font, label, this.leftPos + offsetX + (width - this.font.width(label)) / 2,
				this.topPos + BAR_LABEL_Y, LABEL_COLOR);
	}

	// ------------------------------------------------------------------ 成分饼图

	/**
	 * 当前切换格对应的成分环 + 标题。
	 *
	 * <p>标题画在环的<b>上方</b>（不是在下方）：环顶在 y=36，标题落在状态行之下、
	 * 又不会碰到下面 80 行那排缓存条数字。
	 */
	private void drawPie(GuiGraphicsExtractor extractor) {
		SlurryPie.draw(extractor, this.leftPos + PIE_CX, this.topPos + PIE_CY, PIE_R, this.pieSlices());
		Component title = Component.translatable(PIE_TAB_KEYS[this.pieTab]);
		UiText.text(extractor, this.font, title, this.leftPos + PIE_CX - this.font.width(title) / 2,
				this.topPos + PIE_CY - PIE_R - 9, LABEL_COLOR);
	}

	private List<SlurryPie.Slice> pieSlices() {
		List<AbstractReactorMenu.CompositionEntry> entries = this.pieTab == 1
				? this.menu.composition()
				: this.menu.feedComposition();
		List<PhasePie.Part> parts = new ArrayList<>();
		List<SlurryPie.Slice> out = new ArrayList<>();
		for (AbstractReactorMenu.CompositionEntry entry : entries) {
			if (entry.chemicalId() == null) {
				// 超出同步配额的「其他」在相态上不可归属，保留成独立扇形
				out.add(new SlurryPie.Slice(0xFF8B8B8B,
						Component.translatable("gui.geometallurgy.slurry_other"),
						entry.fraction(), List.of()));
			} else {
				parts.add(new PhasePie.Part(entry.chemicalId(), entry.fraction()));
			}
		}
		// 每个相各占一块，相内物质作为 children 缩进在浮层里（设计稿 §4.1）
		out.addAll(0, PhasePie.slices(parts));
		return out;
	}

	/**
	 * 一列两格，点一下换看「进料」还是「混合槽」。
	 *
	 * <p>每格左边一道竖条是这一份在缓存条上的代表色；底色只区分选中／未选中。
	 */
	private void drawPieTabs(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		int hovered = this.pieTabAt(mouseX, mouseY);
		for (int i = 0; i < PIE_TAB_KEYS.length; i++) {
			int x = this.leftPos + PIE_TAB_X;
			int y = this.topPos + PIE_TAB_Y + i * (PIE_TAB_H + PIE_TAB_GAP);
			boolean selected = this.pieTab == i;

			extractor.fill(x, y, x + PIE_TAB_W, y + PIE_TAB_H, selected ? CELL_ON : CELL_OFF);
			extractor.fill(x, y, x + 3, y + PIE_TAB_H, PIE_TAB_COLORS[i]);
			extractor.fill(x, y, x + PIE_TAB_W, y + 1, HIGHLIGHT);

			Component text = Component.translatable(PIE_TAB_KEYS[i]);
			UiText.text(extractor, this.font, text,
					x + 3 + (PIE_TAB_W - 3 - this.font.width(text)) / 2, y + 3,
					selected ? 0xFFFFFFFF : CELL_TEXT_OFF);

			if (hovered == i && !selected) {
				extractor.fill(x, y, x + PIE_TAB_W, y + PIE_TAB_H, HOVER_TINT);
			}
		}
	}

	private int pieTabAt(double mouseX, double mouseY) {
		for (int i = 0; i < PIE_TAB_KEYS.length; i++) {
			int offsetY = PIE_TAB_Y + i * (PIE_TAB_H + PIE_TAB_GAP);
			if (this.inside(mouseX, mouseY, PIE_TAB_X, offsetY, PIE_TAB_W, PIE_TAB_H)) {
				return i;
			}
		}
		return -1;
	}

	/** 鼠标停在饼图上时，弹出当前这一份的逐项百分比（与磁选机同一套浮层）。 */
	@Override
	protected void extractTooltip(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		super.extractTooltip(extractor, mouseX, mouseY);
		if (SlurryPie.hovered(mouseX, mouseY, this.leftPos + PIE_CX, this.topPos + PIE_CY, PIE_R)) {
			SlurryPie.popup(extractor, this.font, Component.translatable(PIE_TAB_KEYS[this.pieTab]),
					this.pieSlices(), mouseX, mouseY, this.width, this.height,
					this.leftPos + this.imageWidth - 4);
		}
	}

	private Component dominantLine(String dominant) {
		return Component.translatable("gui.geometallurgy.reactor_dominant",
				Component.translatable("chemical.geometallurgy." + dominant),
				format(this.menu.dominantMol()));
	}

	// ------------------------------------------------------------------ 比率滑轨与搅拌

	/**
	 * 一条 0..100 的连续滑轨 + 游标。
	 *
	 * <p>轨道下面的数字是当前百分比；拖动时读的是本地预览值，松手后服务端回包再接管。
	 */
	private void drawRatioSlider(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		int current = this.draggingRatio ? this.ratioPreview : this.menu.ratio();
		int trackX = this.leftPos + RATIO_TRACK_X;
		int trackY = this.topPos + RATIO_TRACK_Y;
		int trackRight = trackX + RATIO_TRACK_W;
		int handleX = trackX + (int) Math.round(Math.clamp(current, 0, 100) / 100.0 * RATIO_TRACK_W);

		extractor.fill(trackX, trackY, trackRight, trackY + RATIO_TRACK_H, BAR_BACKGROUND);
		extractor.fill(trackX, trackY, handleX, trackY + RATIO_TRACK_H, BAR_FILL);
		// 0/25/50/75/100 五道刻度
		for (int k = 0; k <= 4; k++) {
			int tickX = trackX + (int) Math.round(k * RATIO_TRACK_W / 4.0);
			extractor.fill(tickX, trackY, tickX + 1, trackY + RATIO_TRACK_H, SHADOW);
		}
		// 游标
		extractor.fill(handleX - 2, trackY - 3, handleX + 3, trackY + RATIO_TRACK_H + 3, SHADOW);
		extractor.fill(handleX - 1, trackY - 2, handleX + 2, trackY + RATIO_TRACK_H + 2, HIGHLIGHT);

		Component value = Component.translatable("gui.geometallurgy.mixer_ratio", Integer.toString(current));
		UiText.text(extractor, this.font, value, this.leftPos + RATIO_TRACK_X + (RATIO_TRACK_W - this.font.width(value)) / 2,
				this.topPos + RATIO_BAND_Y, LABEL_COLOR);

		if (this.inside(mouseX, mouseY, RATIO_TRACK_X, RATIO_BAND_Y, RATIO_TRACK_W, RATIO_BAND_H)) {
			extractor.setTooltipForNextFrame(Component.translatable("gui.geometallurgy.mixer_ratio_hint"),
					mouseX, mouseY);
		}

		// 搅拌开关
		boolean stir = this.menu.stirring();
		boolean stirHover = this.inside(mouseX, mouseY, STIR_X, RATIO_BAND_Y, STIR_W, STIR_H);
		Component label = Component.translatable("gui.geometallurgy.stir_toggle",
				Component.translatable(stir
						? "gui.geometallurgy.stir_on"
						: "gui.geometallurgy.stir_off"));
		this.drawButton(extractor, STIR_X, RATIO_BAND_Y, STIR_W, STIR_H, label, stirHover);
		if (stirHover) {
			this.tip(extractor, mouseX, mouseY, "gui.geometallurgy.stir_hint");
		}
	}

	/** 鼠标 x → 0..100 的比率。 */
	private int ratioAt(double mouseX) {
		double rel = (mouseX - (this.leftPos + RATIO_TRACK_X)) / (double) RATIO_TRACK_W;
		return (int) Math.round(Math.clamp(rel, 0.0, 1.0) * 100.0);
	}

	/** 把当前鼠标位置写进预览；拖动时限流发送（差 ≥2% 才发一条）。 */
	private void applyRatio(double mouseX, boolean throttled) {
		int ratio = ratioAt(mouseX);
		this.ratioPreview = ratio;
		if (throttled && Math.abs(ratio - this.lastSentRatio) < 2) {
			return;
		}
		this.sendButton(MixerBlockEntity.SET_RATIO_BUTTON_BASE + ratio);
		this.lastSentRatio = ratio;
	}

	private void drawButton(GuiGraphicsExtractor extractor, int x, int y, int w, int h, Component label, boolean hover) {
		int color = hover ? BUTTON_HOVER : BUTTON_BG;
		extractor.fill(this.leftPos + x, this.topPos + y, this.leftPos + x + w, this.topPos + y + h, color);
		extractor.fill(this.leftPos + x, this.topPos + y, this.leftPos + x + w, this.topPos + y + 1, HIGHLIGHT);
		extractor.fill(this.leftPos + x, this.topPos + y, this.leftPos + x + 1, this.topPos + y + h, HIGHLIGHT);
		extractor.fill(this.leftPos + x, this.topPos + y + h - 1, this.leftPos + x + w, this.topPos + y + h, SHADOW);
		extractor.fill(this.leftPos + x + w - 1, this.topPos + y, this.leftPos + x + w, this.topPos + y + h, SHADOW);
		UiText.text(extractor, this.font, label, this.leftPos + x + (w - this.font.width(label)) / 2,
				this.topPos + y + (h - 8) / 2, VALUE_COLOR);
	}

	// ------------------------------------------------------------------ 六面配置

	private void drawFaceCells(GuiGraphicsExtractor extractor, int mouseX, int mouseY) {
		int hovered = this.faceCellAt(mouseX, mouseY);
		for (int i = 0; i < AbstractReactorMenu.FACE_ORDER.length; i++) {
			Direction side = face(i);
			int ordinal = this.menu.roleOrdinal(side);
			int x = this.leftPos + FACE_X + i * (FACE_W + FACE_GAP);
			int y = this.topPos + FACE_Y;

			extractor.fill(x, y, x + FACE_W, y + FACE_H, roleColor(ordinal));
			extractor.fill(x, y, x + FACE_W, y + 1, HIGHLIGHT);
			extractor.fill(x, y, x + 1, y + FACE_H, HIGHLIGHT);
			if (hovered == i) {
				extractor.fill(x, y, x + FACE_W, y + FACE_H, HOVER_TINT);
			}

			// 格子只写方位：角色看底色，完整名字走悬浮提示
			Component dir = Component.translatable("gui.geometallurgy.dir_" + side.getSerializedName());
			UiText.text(extractor, this.font, dir, x + (FACE_W - this.font.width(dir)) / 2, y + 4, 0xFFFFFFFF);
		}
		if (hovered >= 0) {
			Direction side = face(hovered);
			extractor.setTooltipForNextFrame(Component.translatable("gui.geometallurgy.face_cell",
					Component.translatable("gui.geometallurgy.dir_" + side.getSerializedName()),
					roleName(this.menu.roleOrdinal(side))), mouseX, mouseY);
		}
	}

	/** 悬浮提示的统一入口。 */
	private void tip(GuiGraphicsExtractor extractor, int mouseX, int mouseY, String key) {
		extractor.setTooltipForNextFrame(Component.translatable(key), mouseX, mouseY);
	}

	/** 四条 fill 画的边框：GUI 的 {@code outline()} 参数语义与 {@code fill()} 不一致，不能混用。 */
	private void drawBorder(GuiGraphicsExtractor extractor, int x, int y, int right, int bottom) {
		extractor.fill(x - 1, y - 1, right + 1, y, LABEL_COLOR);
		extractor.fill(x - 1, bottom, right + 1, bottom + 1, LABEL_COLOR);
		extractor.fill(x - 1, y - 1, x, bottom + 1, LABEL_COLOR);
		extractor.fill(right, y - 1, right + 1, bottom + 1, LABEL_COLOR);
	}

	/**
	 * 状态栏那一行。{@code OUTPUT_FULL} 且服务端算出了堵住的相时，用带相态与 mol 数的
	 * 格式化文案（docs/科技线-v1.md §13.1）；其余状态仍是原来的短句。
	 */
	protected Component statusText(ReactorStatus status) {
		if (status == ReactorStatus.OUTPUT_FULL && this.menu.blockedPhase() != null) {
			return Component.translatable("gui.geometallurgy.reactor_output_full",
					Component.translatable(phaseKey(this.menu.blockedPhase())), format(this.menu.blockedMol()));
		}
		return Component.translatable(status.translationKey());
	}

	/** 相态的本地化键（气体 / 液体 / 固体）。 */
	protected static String phaseKey(Chemical.Phase phase) {
		return switch (phase) {
			case GAS -> "gui.geometallurgy.phase_gas";
			case LIQUID -> "gui.geometallurgy.phase_liquid";
			case SOLID -> "gui.geometallurgy.phase_solid";
		};
	}

	protected static int statusColor(ReactorStatus status) {
		return switch (status) {
			case RUNNING -> 0xFF1F7A33;
			case NEEDS_STIRRING -> 0xFF7A1F7A;
			case OUTPUT_FULL, NO_REACTION, BUCKET_UNSAFE -> 0xFF9A4A18;
			case IDLE -> LABEL_COLOR;
		};
	}

	protected static String format(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}

	// ------------------------------------------------------------------ 交互

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			// 切换成分环看哪一份 —— 纯客户端状态，不必往服务端发按钮。
			int tab = this.pieTabAt(event.x(), event.y());
			if (tab >= 0) {
				this.pieTab = tab;
				return true;
			}
			int cell = this.faceCellAt(event.x(), event.y());
			if (cell >= 0) {
				this.sendButton(cell);
				return true;
			}
			if (showsRatio()) {
				if (this.inside(event.x(), event.y(), RATIO_TRACK_X, RATIO_BAND_Y, RATIO_TRACK_W, RATIO_BAND_H)) {
					this.draggingRatio = true;
					this.setDragging(true);
					this.ratioPreview = this.menu.ratio();
					this.lastSentRatio = this.menu.ratio();
					this.applyRatio(event.x(), false);
					return true;
				}
				if (this.inside(event.x(), event.y(), STIR_X, RATIO_BAND_Y, STIR_W, STIR_H)) {
					this.sendButton(MixerBlockEntity.STIR_BUTTON);
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (this.draggingRatio) {
			this.applyRatio(event.x(), true);
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (this.draggingRatio) {
			this.applyRatio(event.x(), true);
			this.draggingRatio = false;
			this.setDragging(false);
			// 松手补一个最终值，保证末段「差 <2%」的那点也送出去
			if (this.ratioPreview >= 0 && this.ratioPreview != this.lastSentRatio) {
				this.sendButton(MixerBlockEntity.SET_RATIO_BUTTON_BASE + this.ratioPreview);
				this.lastSentRatio = this.ratioPreview;
			}
			return true;
		}
		return super.mouseReleased(event);
	}

	private int faceCellAt(double mouseX, double mouseY) {
		for (int i = 0; i < AbstractReactorMenu.FACE_ORDER.length; i++) {
			int offsetX = FACE_X + i * (FACE_W + FACE_GAP);
			if (this.inside(mouseX, mouseY, offsetX, FACE_Y, FACE_W, FACE_H)) {
				return i;
			}
		}
		return -1;
	}

	protected boolean inside(double mouseX, double mouseY, int offsetX, int offsetY, int width, int height) {
		double x = mouseX - this.leftPos;
		double y = mouseY - this.topPos;
		return x >= offsetX && x < offsetX + width && y >= offsetY && y < offsetY + height;
	}

	protected void sendButton(int id) {
		if (this.minecraft != null && this.minecraft.gameMode != null) {
			this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, id);
		}
	}
}
