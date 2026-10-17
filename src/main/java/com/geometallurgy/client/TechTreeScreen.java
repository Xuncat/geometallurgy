package com.geometallurgy.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.geometallurgy.chemical.MolBalance;
import com.geometallurgy.manual.TechTree;
import com.geometallurgy.manual.TechTreeLayout;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * 全屏科技线网状图。
 *
 * <h2>布局</h2>
 * 数据全在 {@link TechTree}（节点带工艺阶段、边是 (上游,下游)）；<b>列 / 行 / 弧线数学</b>在纯类
 * {@link TechTreeLayout}：列由阶段钉死（矿 / 选矿 / 冶炼 / 化学 / 稀土），阶段内顺序由依赖
 * 关系（重心法）算出，跨阶段回流画到图下方的弧线、同列内的边鼓在列间缝里 —— 都不穿节点。
 *
 * <h2>交互</h2>
 * <ul>
 *   <li><b>点击选中（粘性）</b>：点节点选中，选中描边比悬停更粗更亮；点空白取消。
 *       选中的节点的直接上下游边保持高亮，不随鼠标移开熄灭。</li>
 *   <li><b>查看配方</b>：双击选中节点、按 E、或点右下角「查看配方」按钮，打开
 *       {@link ReactionDetailScreen}（独立可滚动界面）。</li>
 *   <li>拖动平移、滚轮以光标为中心缩放（0.35× ~ 3×）、悬停浮层、ESC 返回任务书。</li>
 * </ul>
 *
 * <h2>26.x 的界面 API</h2>
 * 绘制入口是 {@code extractBackground(GuiGraphicsExtractor, ...)}，没有 {@code PoseStack}／
 * {@code GuiGraphics}；线段靠 {@code pose().rotate(...)} + 一次 {@code fill} 画出来，
 * 曲线按采样点连成折线。
 */
public class TechTreeScreen extends Screen {

	// ---------------------------------------------------------------- 布局

	/** 节点盒高度。 */
	private static final int NODE_H = 18;
	/** 节点盒最小宽度。 */
	private static final int MIN_NODE_W = 68;
	/** 层底色比节点外沿多出来的边距。 */
	private static final int BAND_PAD = 46;

	private static final float MIN_ZOOM = 0.35f;
	private static final float MAX_ZOOM = 3.0f;

	/** 拖动超过这个像素就算平移，不算点击。 */
	private static final double CLICK_SLOP = 4.0;

	private static final int RECIPE_W = 84;
	private static final int RECIPE_H = 16;

	// ---------------------------------------------------------------- 配色

	private static final int BACKGROUND = 0xF00B0F17;
	private static final int TITLE_INK = 0xFFFFE0A0;
	private static final int HINT_INK = 0xFF8FA0B8;
	private static final int STAGE_INK = 0xFFB4C8E0;
	private static final int LABEL_INK = 0xFFF2F2F2;

	private static final int EDGE = 0x7090A8C8;
	private static final int EDGE_MINE = 0x7096C878;
	private static final int EDGE_STIRRING = 0x70B07CE0;
	private static final int EDGE_ELECTROLYSIS = 0x70E0C060;
	/** 高温焙烧 / 分解（Kind.ROASTING）—— 与电解同族但更暖的橙。 */
	private static final int EDGE_ROASTING = 0x70E08A4A;
	private static final int EDGE_HOVER = 0xFFFFFFFF;
	private static final int EDGE_SELECTED = 0xFFFFD060;

	private static final int[] BAND = {
			0x221E3A5A, 0x22203A20, 0x223A2A18, 0x222A1E3A, 0x223A2030,
	};

	private record Box(TechTree.Node node, int cx, int cy, int halfW, int halfH, Component label) {
	}

	private static final int HALF_H = NODE_H / 2;

	// ---------------------------------------------------------------- 状态

	private final Screen parent;

	/** 节点 id → 布局盒。构造与 resize 时重建。 */
	private final Map<String, Box> boxes = new LinkedHashMap<>();
	/** 边 (from\0to) → 路由（正向直线 / 同列侧弧 / 回流下方弧）。 */
	private final Map<String, TechTreeLayout.Routed> routes = new LinkedHashMap<>();

	private float panX;
	private float panY;
	private float zoom = 1f;

	private boolean dragging;
	private boolean dragMoved;
	private double pressX;
	private double pressY;

	/** 纯客户端选中态（不存档）；点空白清空。 */
	private String selectedNodeId;

	/** 整张图的包围盒（世界坐标），用于夹住平移范围。 */
	private float minX;
	private float minY;
	private float maxX;
	private float maxY;

	/** 每层的中点横坐标（世界坐标），用来把层名印在带子中间。 */
	private final float[] stageCenterX = new float[TechTree.STAGE_COUNT];

	public TechTreeScreen(Screen parent) {
		super(Component.translatableWithFallback(
				"gui.geometallurgy.techtree.title", "科技线 · 总图"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		super.init();
		this.rebuild();
		this.zoom = 1f;
		this.panX = 24 - this.minX;
		float graphH = this.maxY - this.minY;
		this.panY = graphH < this.height - 40
				? (this.height - graphH) / 2f - this.minY
				: 24 - this.minY;
	}

	/** 量宽 → 纯布局 → 建盒。字体宽度只有界面知道，所以在这里量。 */
	private void rebuild() {
		this.boxes.clear();
		this.routes.clear();

		List<TechTreeLayout.Node> layoutNodes = new ArrayList<>(TechTree.NODES.size());
		Map<String, Component> labels = new LinkedHashMap<>();
		for (TechTree.Node node : TechTree.NODES) {
			Component label = Component.translatableWithFallback(node.langKey(), node.fallback());
			labels.put(node.id(), label);
			int halfW = Math.max(MIN_NODE_W, this.font.width(label) + 12) / 2;
			layoutNodes.add(new TechTreeLayout.Node(node.id(), node.stage(), halfW));
		}
		List<TechTreeLayout.Edge> layoutEdges = new ArrayList<>(TechTree.EDGES.size());
		for (TechTree.Edge edge : TechTree.EDGES) {
			layoutEdges.add(new TechTreeLayout.Edge(edge.from(), edge.to()));
		}

		TechTreeLayout.Result result = TechTreeLayout.compute(layoutNodes, layoutEdges);
		for (TechTreeLayout.Routed route : result.edges()) {
			this.routes.put(key(route.from(), route.to()), route);
		}

		float minX = Float.MAX_VALUE;
		float minY = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;
		float[] stageMin = new float[TechTree.STAGE_COUNT];
		float[] stageMax = new float[TechTree.STAGE_COUNT];
		for (int i = 0; i < TechTree.STAGE_COUNT; i++) {
			stageMin[i] = Float.MAX_VALUE;
			stageMax[i] = -Float.MAX_VALUE;
		}

		for (TechTreeLayout.Placement p : result.nodes()) {
			TechTree.Node node = TechTree.byId(p.id()).orElse(null);
			if (node == null) {
				continue;
			}
			Component label = labels.get(p.id());
			Box box = new Box(node, p.cx(), p.cy(), p.halfW(), HALF_H, label);
			this.boxes.put(p.id(), box);

			minX = Math.min(minX, p.cx() - p.halfW());
			maxX = Math.max(maxX, p.cx() + p.halfW());
			minY = Math.min(minY, p.cy() - HALF_H);
			maxY = Math.max(maxY, p.cy() + HALF_H);

			int stage = Math.clamp(node.stage(), 0, TechTree.STAGE_COUNT - 1);
			stageMin[stage] = Math.min(stageMin[stage], p.cx() - p.halfW());
			stageMax[stage] = Math.max(stageMax[stage], p.cx() + p.halfW());
		}

		this.minX = minX;
		this.minY = minY;
		this.maxX = maxX;
		this.maxY = maxY;
		for (int i = 0; i < TechTree.STAGE_COUNT; i++) {
			this.stageCenterX[i] = stageMin[i] == Float.MAX_VALUE
					? 0
					: (stageMin[i] + stageMax[i]) / 2f;
		}
	}

	private static String key(String from, String to) {
		return from + "\u0000" + to;
	}

	// ---------------------------------------------------------------- 绘制

	@Override
	public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY,
			float partialTick) {
		extractor.fill(0, 0, this.width, this.height, BACKGROUND);

		TechTree.Node hovered = this.nodeAt(mouseX, mouseY);

		var pose = extractor.pose();
		pose.pushMatrix();
		pose.translate(this.panX, this.panY);
		pose.scale(this.zoom);

		this.drawBands(extractor);
		this.drawEdges(extractor, this.selectedNodeId, hovered == null ? null : hovered.id());
		this.drawNodes(extractor, hovered);

		pose.popMatrix();

		this.drawChrome(extractor, hovered);

		if (hovered != null) {
			extractor.setComponentTooltipForNextFrame(this.font, this.tooltip(hovered), mouseX, mouseY);
		}
	}

	/** 每层一片淡淡的底色带，另一条竖线把层分开。 */
	private void drawBands(GuiGraphicsExtractor extractor) {
		for (int stage = 0; stage < TechTree.STAGE_COUNT; stage++) {
			float cx = this.stageCenterX[stage];
			float half = BAND_PAD + this.stageHalfWidth(stage);
			extractor.fill(Math.round(cx - half), Math.round(this.minY - BAND_PAD),
					Math.round(cx + half), Math.round(this.maxY + BAND_PAD), BAND[stage]);
		}
		for (int stage = 1; stage < TechTree.STAGE_COUNT; stage++) {
			float left = this.stageCenterX[stage - 1];
			float right = this.stageCenterX[stage];
			int x = Math.round((left + right) / 2f);
			extractor.fill(x, Math.round(this.minY - BAND_PAD), x + 1,
					Math.round(this.maxY + BAND_PAD), 0x30FFFFFF);
		}
	}

	private float stageHalfWidth(int stage) {
		float lo = Float.MAX_VALUE;
		float hi = -Float.MAX_VALUE;
		for (Box box : this.boxes.values()) {
			if (box.node().stage() != stage) {
				continue;
			}
			lo = Math.min(lo, box.cx() - box.halfW());
			hi = Math.max(hi, box.cx() + box.halfW());
		}
		return lo == Float.MAX_VALUE ? 0 : (hi - lo) / 2f;
	}

	/**
	 * 画全部边，一次遍历：选中集合与悬停集合的并集用亮色加粗。
	 * 回流边与同列边走弧线，绝不穿过节点盒。
	 */
	private void drawEdges(GuiGraphicsExtractor extractor, String selected, String hovered) {
		for (TechTree.Edge edge : TechTree.EDGES) {
			Box from = this.boxes.get(edge.from());
			Box to = this.boxes.get(edge.to());
			if (from == null || to == null) {
				continue;
			}
			TechTreeLayout.Routed route = this.routes.get(key(edge.from(), edge.to()));
			if (route == null) {
				route = new TechTreeLayout.Routed(edge.from(), edge.to(),
						TechTreeLayout.Route.FORWARD, 0);
			}
			boolean isSelected = selected != null
					&& (edge.from().equals(selected) || edge.to().equals(selected));
			boolean isHovered = hovered != null
					&& (edge.from().equals(hovered) || edge.to().equals(hovered));
			int color = isSelected ? EDGE_SELECTED
					: isHovered ? EDGE_HOVER : edgeColor(edge.kind());
			int half = (isSelected || isHovered) ? 2 : 1;
			this.drawRoute(extractor, from, to, route, color, half);
		}
	}

	private void drawRoute(GuiGraphicsExtractor extractor, Box from, Box to,
			TechTreeLayout.Routed route, int color, int half) {
		switch (route.route()) {
			case FORWARD -> {
				double[] e = TechTreeLayout.endpoints(from.cx(), from.cy(), from.halfW(), from.halfH(),
						to.cx(), to.cy(), to.halfW(), to.halfH());
				this.drawSegment(extractor, e[0], e[1], e[2], e[3], color, half);
				this.drawArrow(extractor, e[2], e[3], Math.atan2(e[3] - e[1], e[2] - e[0]), color, half, 9.0);
			}
			case SIDE -> {
				// 控制点放在该列右沿外的缝里，弧线不穿同列节点。
				double controlX = this.columnRight(from.node().stage()) + TechTreeLayout.COL_GAP * 0.45;
				double controlY = (from.cy() + to.cy()) / 2.0;
				this.drawQuadratic(extractor, from, to, controlX, controlY, color, half);
			}
			case BELOW -> {
				// 回流：沉到整张图下方，档位交错避免完全重叠。
				double controlX = (from.cx() + to.cx()) / 2.0;
				double controlY = this.maxY + BAND_PAD + (route.depth() + 1) * TechTreeLayout.ROW_H;
				this.drawQuadratic(extractor, from, to, controlX, controlY, color, half);
			}
		}
	}

	private void drawQuadratic(GuiGraphicsExtractor extractor, Box from, Box to,
			double controlX, double controlY, int color, int half) {
		double[] p0 = TechTreeLayout.boundaryPoint(from.cx(), from.cy(), from.halfW(), from.halfH(),
				controlX, controlY);
		double[] p1 = TechTreeLayout.boundaryPoint(to.cx(), to.cy(), to.halfW(), to.halfH(),
				controlX, controlY);

		int steps = 18;
		double px = p0[0];
		double py = p0[1];
		for (int i = 1; i <= steps; i++) {
			double t = (double) i / steps;
			double[] p = TechTreeLayout.quadratic(p0[0], p0[1], controlX, controlY, p1[0], p1[1], t);
			this.drawSegment(extractor, px, py, p[0], p[1], color, half);
			px = p[0];
			py = p[1];
		}
		double[] tan = TechTreeLayout.quadraticTangent(p0[0], p0[1], controlX, controlY, p1[0], p1[1], 1.0);
		this.drawArrow(extractor, p1[0], p1[1], Math.atan2(tan[1], tan[0]), color, half, 8.0);
	}

	/** 一段带粗细的直线：平移到起点 → 旋转 → 画一个水平矩形。 */
	private void drawSegment(GuiGraphicsExtractor extractor, double x0, double y0,
			double x1, double y1, int color, int half) {
		double dx = x1 - x0;
		double dy = y1 - y0;
		double len = Math.sqrt(dx * dx + dy * dy);
		if (len < 0.5) {
			return;
		}
		float angle = (float) Math.atan2(dy, dx);
		var pose = extractor.pose();
		pose.pushMatrix();
		pose.translate((float) x0, (float) y0);
		pose.rotate(angle);
		extractor.fill(0, -half, (int) Math.round(len), half + 1, color);
		pose.popMatrix();
	}

	/** 箭头：从箭尖往回撇两条短线。 */
	private void drawArrow(GuiGraphicsExtractor extractor, double x, double y, double angle,
			int color, int half, double head) {
		float armSpan = (float) Math.atan2(head * 0.6, head);
		float armLength = (float) Math.sqrt(head * head + head * 0.6 * head * 0.6);
		var pose = extractor.pose();
		for (int side = -1; side <= 1; side += 2) {
			pose.pushMatrix();
			pose.translate((float) x, (float) y);
			pose.rotate((float) angle + (float) Math.PI + side * armSpan);
			extractor.fill(0, -half, Math.round(armLength), half + 1, color);
			pose.popMatrix();
		}
	}

	/** 该阶段列的最右沿（世界坐标）。 */
	private float columnRight(int stage) {
		float hi = -Float.MAX_VALUE;
		for (Box box : this.boxes.values()) {
			if (box.node().stage() != stage) {
				continue;
			}
			hi = Math.max(hi, box.cx() + box.halfW());
		}
		return hi == -Float.MAX_VALUE ? this.stageCenterX[stage] : hi;
	}

	/** 边的颜色按触发条件分：桥接（矿 → 精矿）/ 热反应 / 搅拌 / 电解。 */
	private static int edgeColor(MolBalance.Kind kind) {
		if (kind == null) {
			return EDGE_MINE;
		}
		return switch (kind) {
			case THERMAL -> EDGE;
			case STIRRING -> EDGE_STIRRING;
			case ELECTROLYSIS -> EDGE_ELECTROLYSIS;
			case ROASTING -> EDGE_ROASTING;
		};
	}

	private void drawNodes(GuiGraphicsExtractor extractor, TechTree.Node hovered) {
		for (Box box : this.boxes.values()) {
			boolean selected = this.selectedNodeId != null && this.selectedNodeId.equals(box.node().id());
			boolean hot = hovered != null && hovered.id().equals(box.node().id());
			int face = faceColor(box.node().kind(), selected, hot);
			int x0 = box.cx() - box.halfW();
			int y0 = box.cy() - box.halfH();
			int x1 = box.cx() + box.halfW();
			int y1 = box.cy() + box.halfH();
			extractor.fill(x0, y0, x1, y1, face);
			if (selected) {
				// 选中的描边更粗、金色，和悬停的细白边一眼能分开。
				extractor.fill(x0, y0, x1, y0 + 2, EDGE_SELECTED);
				extractor.fill(x0, y1 - 2, x1, y1, EDGE_SELECTED);
				extractor.fill(x0, y0, x0 + 2, y1, EDGE_SELECTED);
				extractor.fill(x1 - 2, y0, x1, y1, EDGE_SELECTED);
			} else {
				int edge = edgeColor(box.node().kind(), hot);
				extractor.fill(x0, y0, x1, y0 + 1, edge);
				extractor.fill(x0, y1 - 1, x1, y1, edge);
				extractor.fill(x0, y0, x0 + 1, y1, edge);
				extractor.fill(x1 - 1, y0, x1, y1, edge);
			}
			UiText.text(extractor, this.font, box.label(), box.cx() - this.font.width(box.label()) / 2,
					box.cy() - 4, LABEL_INK);
		}
	}

	private static int faceColor(TechTree.Kind kind, boolean selected, boolean hot) {
		int base = switch (kind) {
			case MACHINE -> 0xF02E4E7E;
			case REAGENT -> 0xF03E5A2E;
			case PRODUCT -> 0xF06A4A1E;
		};
		int lift = selected ? 0x38 : hot ? 0x20 : 0;
		int r = Math.min(255, ((base >> 16) & 0xFF) + lift);
		int g = Math.min(255, ((base >> 8) & 0xFF) + lift);
		int b = Math.min(255, (base & 0xFF) + lift);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	private static int edgeColor(TechTree.Kind kind, boolean hot) {
		if (hot) {
			return 0xFFFFFFFF;
		}
		return switch (kind) {
			case MACHINE -> 0xFF6FA8FF;
			case REAGENT -> 0xFF8FD46A;
			case PRODUCT -> 0xFFE0A94A;
		};
	}

	/** 标题、层名、操作提示、查看配方按钮 —— 都画在屏幕坐标里，不随缩放变。 */
	private void drawChrome(GuiGraphicsExtractor extractor, TechTree.Node hovered) {
		Component title = Component.translatableWithFallback(
				"gui.geometallurgy.techtree.title", "科技线 · 总图");
		UiText.text(extractor, this.font, title, 10, 8, TITLE_INK);

		for (int stage = 0; stage < TechTree.STAGE_COUNT; stage++) {
			Component label = Component.translatableWithFallback(
					TechTree.stageKey(stage), TechTree.stageFallback(stage));
			int x = Math.round(this.panX + this.stageCenterX[stage] * this.zoom
					- this.font.width(label) / 2f);
			UiText.text(extractor, this.font, label, x, 24, STAGE_INK);
		}

		// 选中浮层：物质名 + 能处理它的机器 + 操作提示
		if (this.selectedNodeId != null) {
			TechTree.Node node = TechTree.byId(this.selectedNodeId).orElse(null);
			if (node != null) {
				Component name = Component.translatableWithFallback(node.langKey(), node.fallback());
				UiText.text(extractor, this.font, name, 10, 40, EDGE_SELECTED);
				Component tip = Component.translatableWithFallback(
						"gui.geometallurgy.techtree.selected_hint", "双击 / 按 E 查看配方");
				UiText.text(extractor, this.font, tip, 14, 52, HINT_INK);
			}
		}

		// 查看配方按钮：只在选中时有内容（未选中时暗掉）
		int bx = this.recipeButtonX();
		int by = this.recipeButtonY();
		boolean active = this.selectedNodeId != null;
		extractor.fill(bx, by, bx + RECIPE_W, by + RECIPE_H, active ? 0xFF2E6E4E : 0xFF2A2F38);
		extractor.fill(bx, by, bx + RECIPE_W, by + 1, active ? 0xFF5FBF8A : 0xFF3A4048);
		Component view = Component.translatableWithFallback(
				"gui.geometallurgy.techtree.view", "查看配方");
		UiText.text(extractor, this.font, view, bx + (RECIPE_W - this.font.width(view)) / 2,
				by + 4, active ? LABEL_INK : 0xFF707880);

		Component hint = Component.translatableWithFallback(
				"gui.geometallurgy.techtree.hint", "点击选中 · 双击查看配方 · 拖动平移 · 滚轮缩放 · ESC 返回");
		UiText.text(extractor, this.font, hint, 10, this.height - 14, HINT_INK);
	}

	private int recipeButtonX() {
		return this.width - RECIPE_W - 10;
	}

	private int recipeButtonY() {
		return this.height - RECIPE_H - 32;
	}

	// ---------------------------------------------------------------- 交互

	/** 悬停浮层：物质名 + 能处理它的机器。 */
	private List<Component> tooltip(TechTree.Node node) {
		List<Component> lines = new ArrayList<>(2);
		lines.add(Component.translatableWithFallback(node.langKey(), node.fallback()));
		if (!node.machineKeys().isEmpty()) {
			MutableComponent machines = Component.empty();
			boolean first = true;
			for (String key : node.machineKeys()) {
				if (!first) {
					machines.append(" · ");
				}
				machines.append(Component.translatable(key));
				first = false;
			}
			lines.add(machines);
		}
		return lines;
	}

	private TechTree.Node nodeAt(double mouseX, double mouseY) {
		float wx = (float) ((mouseX - this.panX) / this.zoom);
		float wy = (float) ((mouseY - this.panY) / this.zoom);
		for (Box box : this.boxes.values()) {
			if (wx >= box.cx() - box.halfW() && wx < box.cx() + box.halfW()
					&& wy >= box.cy() - box.halfH() && wy < box.cy() + box.halfH()) {
				return box.node();
			}
		}
		return null;
	}

	private boolean recipeButtonAt(double mouseX, double mouseY) {
		int bx = this.recipeButtonX();
		int by = this.recipeButtonY();
		return mouseX >= bx && mouseX < bx + RECIPE_W && mouseY >= by && mouseY < by + RECIPE_H;
	}

	/** 打开选中节点的配方详情；没选中或没有父屏时不动作。 */
	private void openRecipe() {
		if (this.selectedNodeId == null || this.parent == null) {
			return;
		}
		this.minecraft.gui.setScreen(new ReactionDetailScreen(this, this.selectedNodeId));
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
			return super.mouseClicked(event, doubleClick);
		}
		if (this.recipeButtonAt(event.x(), event.y())) {
			this.openRecipe();
			return true;
		}
		TechTree.Node node = this.nodeAt(event.x(), event.y());
		if (doubleClick && node != null) {
			// 第二击落在同一个节点：直接进配方详情（第一次点击已经选中它了）。
			this.selectedNodeId = node.id();
			this.openRecipe();
			return true;
		}
		this.dragging = true;
		this.dragMoved = false;
		this.pressX = event.x();
		this.pressY = event.y();
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			boolean wasDragging = this.dragging;
			this.dragging = false;
			if (wasDragging && !this.dragMoved) {
				TechTree.Node node = this.nodeAt(event.x(), event.y());
				// 点节点 = 粘性选中；点空白 = 取消。
				this.selectedNodeId = node == null ? null : node.id();
			}
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (this.dragging && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			double dx = event.x() - this.pressX;
			double dy = event.y() - this.pressY;
			if (dx * dx + dy * dy > CLICK_SLOP * CLICK_SLOP) {
				this.dragMoved = true;
			}
			this.panX += (float) dragX;
			this.panY += (float) dragY;
			this.clampPan();
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY != 0.0) {
			float previous = this.zoom;
			float next = scrollY > 0 ? previous * 1.15f : previous / 1.15f;
			next = Math.clamp(next, MIN_ZOOM, MAX_ZOOM);
			if (next != previous) {
				this.zoom = next;
				// 让光标下的那个世界点保持不动
				this.panX = (float) (mouseX - (mouseX - this.panX) * (next / previous));
				this.panY = (float) (mouseY - (mouseY - this.panY) * (next / previous));
				this.clampPan();
			}
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_E && this.selectedNodeId != null) {
			this.openRecipe();
			return true;
		}
		return super.keyPressed(event);
	}

	/** 图不能被拖到完全看不见：至少留一小块在屏幕里。 */
	private void clampPan() {
		float pad = 40f;
		float left = this.panX + this.minX * this.zoom;
		float right = this.panX + this.maxX * this.zoom;
		float top = this.panY + this.minY * this.zoom;
		float bottom = this.panY + this.maxY * this.zoom;
		if (right < pad) {
			this.panX += pad - right;
		}
		if (left > this.width - pad) {
			this.panX -= left - (this.width - pad);
		}
		if (bottom < pad) {
			this.panY += pad - bottom;
		}
		if (top > this.height - pad) {
			this.panY -= top - (this.height - pad);
		}
	}

	@Override
	public boolean isPauseScreen() {
		// 与任务书一致：不额外暂停世界
		return false;
	}

	@Override
	public void onClose() {
		if (this.parent != null) {
			this.minecraft.gui.setScreen(this.parent);
		} else {
			super.onClose();
		}
	}
}
