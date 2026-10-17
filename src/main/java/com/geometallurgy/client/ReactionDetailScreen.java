package com.geometallurgy.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.geometallurgy.chemical.Machine;
import com.geometallurgy.chemical.MolBalance;
import com.geometallurgy.manual.TechTree;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * 「某物质的配方详情」独立界面。
 *
 * <h2>它显示什么</h2>
 * 从科技线总图选中一个节点后双击 / 按 E / 点「查看配方」进来，列出该物质参与的<b>全部</b>反应
 * （{@link TechTree#reactionsFor(String)}，化学超边 + 桥接机器步骤），一条一卡：
 * <pre>
 *   2 氯化钠 + 2 水  ->  2 氢氧化钠 + 1 氢气 + 1 氯气
 *     类型：电解
 *     机器：电解炉
 *     催化剂：无
 *     电极归属：氯气 → 阳极，氢气 → 阴极
 * </pre>
 * 卡片按 {@link MolBalance.Kind} 上色（桥接绿 / 热蓝 / 搅拌紫 / 电解金，与总图一致），
 * 方程里的每个物质名都可点击 —— 点了就跳到它的详情（内部维护一条返回栈，ESC 逐步后退）。
 *
 * <h2>可达性</h2>
 * 表头标出「当前可达 / 不可达」，不可达时列出缺哪种料。可达集合是 {@link TechTree#REACHABLE}
 * —— 启动时只跑一次 BFS（全机器审计），本界面每帧只查表，不重跑。
 *
 * <h2>滚动</h2>
 * 列表可滚轮滚动（一个物质最多牵涉十几条反应），内容用 {@code enableScissor} 裁剪在视口里。
 * 方向键上 / 下也能滚。
 */
public class ReactionDetailScreen extends Screen {

	// ---------------------------------------------------------------- 配色（与总图一致）

	private static final int BACKGROUND = 0xF00B0F17;
	private static final int TITLE_INK = 0xFFFFE0A0;
	private static final int INK = 0xFFF2F2F2;
	private static final int DIM = 0xFF9FB0C4;
	private static final int HINT_INK = 0xFF8FA0B8;
	private static final int OK_INK = 0xFF7FD48A;
	private static final int BAD_INK = 0xFFE07A7A;
	private static final int CARD_FILL = 0x40101218;
	private static final int BACK_FILL = 0xFF2E4E7E;
	private static final int BACK_HOVER = 0xFF3E5E8E;

	private static final int KIND_BRIDGE = 0xFF8FD46A;
	private static final int KIND_THERMAL = 0xFF7FA8E0;
	private static final int KIND_STIRRING = 0xFFB07CE0;
	private static final int KIND_ELECTROLYSIS = 0xFFE0C060;
	private static final int KIND_ROASTING = 0xFFE08A4A;

	// ---------------------------------------------------------------- 尺寸

	private static final int MARGIN = 14;
	private static final int PAD = 6;
	private static final int LINE = 11;
	private static final int CARD_GAP = 8;
	private static final int LIST_TOP = 58;
	private static final int LIST_BOTTOM = 6;
	private static final int BACK_W = 56;
	private static final int BACK_H = 16;

	// ---------------------------------------------------------------- 绘制原语（内容坐标，y=0 为列表顶点）

	private record TextOp(int x, int y, Component text, int color) {
	}

	private record Hit(int x0, int y0, int x1, int y1, String target) {
	}

	private record Card(int x0, int y0, int x1, int y1, int color) {
	}

	private record Token(String text, String target, int color) {
	}

	// ---------------------------------------------------------------- 状态

	private final Screen parent;
	private final Deque<String> history = new ArrayDeque<>();

	private String speciesId;

	private final List<TextOp> textOps = new ArrayList<>();
	private final List<Hit> hits = new ArrayList<>();
	private final List<Card> cards = new ArrayList<>();

	private int contentHeight;
	private int scroll;
	private boolean reachable;
	private List<String> missing = List.of();

	public ReactionDetailScreen(Screen parent, String speciesId) {
		super(Component.translatableWithFallback(
				"gui.geometallurgy.recipe.title", "配方详情"));
		this.parent = parent;
		this.speciesId = speciesId;
	}

	@Override
	protected void init() {
		super.init();
		this.scroll = 0;
		this.rebuild();
	}

	// ---------------------------------------------------------------- 数据 + 布局

	private void rebuild() {
		this.reachable = TechTree.REACHABLE.contains(this.speciesId);
		this.missing = TechTree.missingFor(this.speciesId);
		this.rebuildContent();
	}

	private void rebuildContent() {
		this.textOps.clear();
		this.hits.clear();
		this.cards.clear();

		List<TechTree.ReactionInfo> reactions = TechTree.reactionsFor(this.speciesId);
		int left = MARGIN;
		int right = this.width - MARGIN;
		int y = 0;

		if (reactions.isEmpty()) {
			this.textOps.add(new TextOp(left + PAD, PAD,
					Component.translatableWithFallback("gui.geometallurgy.recipe.none",
							"该物质不参与任何反应"), DIM));
			y = PAD + LINE;
		}

		for (TechTree.ReactionInfo info : reactions) {
			int kindColor = kindColor(info.kind(), info.chemical());
			int cardY = y;
			int cy = cardY + PAD;

			// 方程（带系数的物质名可点）
			int cx = left + PAD;
			int wrapRight = right - PAD;
			for (Token token : tokens(info, kindColor)) {
				int w = this.font.width(token.text());
				if (cx > left + PAD && cx + w > wrapRight) {
					cx = left + PAD;
					cy += LINE;
				}
				int color = token.color() != 0 ? token.color() : kindColor;
				this.textOps.add(new TextOp(cx, cy, Component.literal(token.text()), color));
				if (token.target() != null) {
					this.hits.add(new Hit(cx, cy - 1, cx + w, cy + LINE - 2, token.target()));
				}
				cx += w;
			}
			cy += LINE + 4;

			// 元信息：每条一行，避免长值折行
			cy = this.meta(info, left + PAD + 10, cy, kindColor);

			int cardH = cy - cardY + PAD;
			this.cards.add(new Card(left, cardY, right, cardY + cardH, kindColor));
			y = cardY + cardH + CARD_GAP;
		}

		this.contentHeight = y;
		this.clampScroll();
	}

	/** 方程 token：物质名（可点）+ 连接符。 */
	private List<Token> tokens(TechTree.ReactionInfo info, int kindColor) {
		List<Token> out = new ArrayList<>();
		appendSide(out, info.reactants(), kindColor);
		out.add(new Token("  ->  ", null, DIM));
		appendSide(out, info.products(), kindColor);
		return out;
	}

	private void appendSide(List<Token> out, List<TechTree.Term> terms, int kindColor) {
		for (int i = 0; i < terms.size(); i++) {
			if (i > 0) {
				out.add(new Token(" + ", null, DIM));
			}
			TechTree.Term term = terms.get(i);
			out.add(new Token("[" + term.coeff() + "] " + this.name(term.id()), term.id(), kindColor));
		}
	}

	/** 元信息四行：类型 / 机器 / 催化剂 / 电极归属（可逆反应再加一行）。返回下一行的 y。 */
	private int meta(TechTree.ReactionInfo info, int x, int y, int kindColor) {
		this.textOps.add(new TextOp(x, y, Component.literal(
				label("gui.geometallurgy.recipe.kind", "类型：") + kindName(info)), kindColor));
		y += LINE;

		this.textOps.add(new TextOp(x, y, Component.literal(
				label("gui.geometallurgy.recipe.machines", "机器：") + machineNames(info)), INK));
		y += LINE;

		this.textOps.add(new TextOp(x, y, Component.literal(
				label("gui.geometallurgy.recipe.catalyst", "催化剂：") + catalystNames(info)), DIM));
		y += LINE;

		this.textOps.add(new TextOp(x, y, Component.literal(
				label("gui.geometallurgy.recipe.electrodes", "电极归属：") + electrodeNames(info)), DIM));
		y += LINE;

		if (info.chemical() && info.reversible()) {
			this.textOps.add(new TextOp(x, y, Component.literal(
					label("gui.geometallurgy.recipe.reversible", "可逆反应：")
							+ label("gui.geometallurgy.recipe.yes", "是")), DIM));
			y += LINE;
		}
		return y;
	}

	private String kindName(TechTree.ReactionInfo info) {
		if (!info.chemical()) {
			return label("gui.geometallurgy.recipe.kind.bridge", "矿处理");
		}
		return switch (info.kind()) {
			case THERMAL -> label("gui.geometallurgy.recipe.kind.thermal", "热反应");
			case STIRRING -> label("gui.geometallurgy.recipe.kind.stirring", "需搅拌");
			case ELECTROLYSIS -> label("gui.geometallurgy.recipe.kind.electrolysis", "电解");
			case ROASTING -> label("gui.geometallurgy.recipe.kind.roasting", "焙烧");
		};
	}

	private String machineNames(TechTree.ReactionInfo info) {
		if (info.machines().isEmpty()) {
			return label("gui.geometallurgy.recipe.none_value", "无");
		}
		StringBuilder sb = new StringBuilder();
		for (Machine m : info.machines()) {
			if (sb.length() > 0) {
				sb.append(" · ");
			}
			sb.append(label(machineKey(m), m.name()));
		}
		return sb.toString();
	}

	private String catalystNames(TechTree.ReactionInfo info) {
		if (info.catalysts().isEmpty()) {
			return label("gui.geometallurgy.recipe.none_value", "无");
		}
		StringBuilder sb = new StringBuilder();
		for (String c : info.catalysts()) {
			if (sb.length() > 0) {
				sb.append(" · ");
			}
			sb.append(label("gui.geometallurgy.recipe.catalyst." + c, c));
		}
		return sb.toString();
	}

	/** 产物里 {@code side != NONE} 的列出归属；都没有就是「无」。 */
	private String electrodeNames(TechTree.ReactionInfo info) {
		StringBuilder sb = new StringBuilder();
		for (TechTree.Term t : info.products()) {
			String side = switch (t.side()) {
				case NONE -> null;
				case ANODE -> label("gui.geometallurgy.recipe.electrode.anode", "阳极");
				case CATHODE -> label("gui.geometallurgy.recipe.electrode.cathode", "阴极");
			};
			if (side == null) {
				continue;
			}
			if (sb.length() > 0) {
				sb.append("，");
			}
			sb.append(this.name(t.id())).append(" → ").append(side);
		}
		return sb.length() == 0 ? label("gui.geometallurgy.recipe.none_value", "无") : sb.toString();
	}

	private String name(String id) {
		return Component.translatableWithFallback(TechTree.nameKey(id), TechTree.nameFallback(id))
				.getString();
	}

	private String label(String key, String fallback) {
		return Component.translatableWithFallback(key, fallback).getString();
	}

	private static String machineKey(Machine m) {
		return switch (m) {
			case REACTION_VESSEL -> "block.geometallurgy.reaction_vessel";
			case MIXER -> "block.geometallurgy.mixer";
			case ELECTROLYSIS_FURNACE -> "block.geometallurgy.electrolysis_furnace";
			case VEIN_EXTRACTOR -> "block.geometallurgy.vein_extractor";
			case CRUSHER -> "block.geometallurgy.crusher";
			case CENTRIFUGE -> "block.geometallurgy.centrifuge";
			case MAGNETIC_SEPARATOR -> "block.geometallurgy.magnetic_separator";
			case REDUCTION_FURNACE -> "block.geometallurgy.reduction_furnace";
		};
	}

	private static int kindColor(MolBalance.Kind kind, boolean chemical) {
		if (!chemical || kind == null) {
			return KIND_BRIDGE;
		}
		return switch (kind) {
			case THERMAL -> KIND_THERMAL;
			case STIRRING -> KIND_STIRRING;
			case ELECTROLYSIS -> KIND_ELECTROLYSIS;
			case ROASTING -> KIND_ROASTING;
		};
	}

	// ---------------------------------------------------------------- 绘制

	@Override
	public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
			float partialTick) {
		extractor.fill(0, 0, this.width, this.height, BACKGROUND);

		int viewBottom = this.height - LIST_BOTTOM;
		int top = LIST_TOP;
		int dy = top - this.scroll;

		extractor.enableScissor(0, top, this.width, viewBottom);
		for (Card card : this.cards) {
			int fill = (card.color() & 0x00FFFFFF) | 0x30000000;
			extractor.fill(card.x0(), card.y0() + dy, card.x1(), card.y1() + dy, fill);
			extractor.fill(card.x0(), card.y0() + dy, card.x0() + 3, card.y1() + dy, card.color());
			extractor.fill(card.x0(), card.y0() + dy, card.x1(), card.y0() + dy + 1,
					(card.color() & 0x00FFFFFF) | 0x80000000);
			extractor.fill(card.x0(), card.y1() + dy - 1, card.x1(), card.y1() + dy,
					(card.color() & 0x00FFFFFF) | 0x80000000);
		}
		for (TextOp op : this.textOps) {
			UiText.text(extractor, this.font, op.text(), op.x(), op.y() + dy, op.color());
		}
		extractor.disableScissor();

		this.drawChrome(extractor, mouseX, mouseY, viewBottom);
	}

	private void drawChrome(GuiGraphicsExtractor extractor, int mouseX, int mouseY, int viewBottom) {
		// 返回按钮先占住左上角，物质名 + 可达性排在它右边。
		int bx = MARGIN;
		int by = 8;
		boolean hover = mouseX >= bx && mouseX < bx + BACK_W && mouseY >= by && mouseY < by + BACK_H;
		extractor.fill(bx, by, bx + BACK_W, by + BACK_H, hover ? BACK_HOVER : BACK_FILL);
		Component back = Component.translatableWithFallback("gui.geometallurgy.recipe.back", "返回");
		UiText.text(extractor, this.font, back, bx + (BACK_W - this.font.width(back)) / 2, by + 4, INK);

		// 物质名 + 可达性
		int titleX = bx + BACK_W + 10;
		UiText.text(extractor, this.font, Component.literal(this.name(this.speciesId)), titleX, 10, TITLE_INK);

		int titleW = this.font.width(this.name(this.speciesId));
		boolean ok = this.reachable;
		String badge = label(ok ? "gui.geometallurgy.recipe.reachable"
				: "gui.geometallurgy.recipe.unreachable", ok ? "当前可达" : "当前不可达");
		UiText.text(extractor, this.font, Component.literal(badge), titleX + titleW + 12, 10,
				ok ? OK_INK : BAD_INK);

		if (!ok) {
			String detail;
			if (!TechTree.hasProducer(this.speciesId)) {
				detail = label("gui.geometallurgy.recipe.no_route", "无已知生成路线");
			} else if (!this.missing.isEmpty()) {
				StringBuilder sb = new StringBuilder();
				for (String id : this.missing) {
					if (sb.length() > 0) {
						sb.append(" · ");
					}
					sb.append(this.name(id));
				}
				detail = label("gui.geometallurgy.recipe.missing", "缺少：") + sb;
			} else {
				detail = "";
			}
			if (!detail.isEmpty()) {
				this.clipText(extractor, detail, MARGIN, 22, BAD_INK);
			}
		}

		UiText.text(extractor, this.font, Component.literal(label("gui.geometallurgy.recipe.reactions",
				"参与反应：") + TechTree.reactionsFor(this.speciesId).size()), MARGIN, 34, DIM);

		// 提示行贴底
		UiText.text(extractor, this.font, Component.translatableWithFallback(
				"gui.geometallurgy.recipe.hint", "滚轮滚动 · 点击物质名跳转 · ESC 返回"),
				MARGIN, this.height - 12, HINT_INK);

		// 滚动条
		int viewH = viewBottom - LIST_TOP;
		if (this.contentHeight > viewH) {
			int trackX = this.width - 6;
			extractor.fill(trackX, LIST_TOP, trackX + 3, viewBottom, 0x40FFFFFF);
			int thumbH = Math.max(12, viewH * viewH / this.contentHeight);
			int maxScroll = this.contentHeight - viewH;
			int thumbY = LIST_TOP + (int) ((long) (viewH - thumbH) * this.scroll / maxScroll);
			extractor.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0x80FFFFFF);
		}
	}

	/** 顶栏文字太宽时裁到屏幕内。 */
	private void clipText(GuiGraphicsExtractor extractor, String text, int x, int y, int color) {
		extractor.enableScissor(x, y - 1, this.width - MARGIN, y + 10);
		UiText.text(extractor, this.font, Component.literal(text), x, y, color);
		extractor.disableScissor();
	}

	// ---------------------------------------------------------------- 交互

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY != 0.0) {
			this.scroll -= (int) Math.round(scrollY * 18);
			this.clampScroll();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return super.mouseClicked(event, doubleClick);
		}
		// 返回按钮
		if (event.x() >= MARGIN && event.x() < MARGIN + BACK_W
				&& event.y() >= 8 && event.y() < 8 + BACK_H) {
			this.goBack();
			return true;
		}
		// 物质名
		int dy = LIST_TOP - this.scroll;
		double my = event.y();
		if (my >= LIST_TOP && my <= this.height - LIST_BOTTOM) {
			for (Hit hit : this.hits) {
				double y0 = hit.y0() + dy;
				double y1 = hit.y1() + dy;
				if (event.x() >= hit.x0() && event.x() < hit.x1()
						&& my >= y0 && my < y1) {
					this.goTo(hit.target());
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	private void goTo(String target) {
		if (target == null || target.equals(this.speciesId)) {
			return;
		}
		this.history.push(this.speciesId);
		this.speciesId = target;
		this.afterNavigate();
	}

	private void goBack() {
		if (!this.history.isEmpty()) {
			this.speciesId = this.history.pop();
			this.afterNavigate();
		} else if (this.parent != null) {
			this.minecraft.gui.setScreen(this.parent);
		} else {
			super.onClose();
		}
	}

	private void afterNavigate() {
		this.scroll = 0;
		this.rebuild();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_UP) {
			this.scroll -= 18;
			this.clampScroll();
			return true;
		}
		if (event.key() == InputConstants.KEY_DOWN) {
			this.scroll += 18;
			this.clampScroll();
			return true;
		}
		return super.keyPressed(event);
	}

	private void clampScroll() {
		int viewH = this.height - LIST_BOTTOM - LIST_TOP;
		int max = Math.max(0, this.contentHeight - viewH);
		this.scroll = Math.max(0, Math.min(max, this.scroll));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		this.goBack();
	}
}