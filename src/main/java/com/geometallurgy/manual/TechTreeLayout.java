package com.geometallurgy.manual;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 科技线总图的<b>纯布局</b>：没有 {@code net.minecraft} 依赖，可以在普通 JVM 里离线跑
 * （一次性 harness 会用它验证「确定性 / 盒子不重叠 / 边端点落在节点边界 / 环不挂死」）。
 *
 * <h2>为什么不再用「DFS 断环 + 逆后序最长路径」</h2>
 * 旧算法把回边断在 DFS 树里，再把剩下的 DAG 拉成 15+ 层：横向很长，回流线从右往左乱穿。
 * 现在改为<b>列由工艺阶段钉死</b>（矿 / 选矿 / 冶炼 / 化学 / 稀土，共 {@value #STAGE_COUNT} 列），
 * 阶段内顺序由依赖关系（Sugiyama 重心法）算出来；跨阶段回流与同列内的边分别走「下方弧线 /
 * 列间侧弧」，不再穿过节点盒。
 *
 * <h2>输入 / 输出</h2>
 * <ul>
 *   <li>输入：节点（id、阶段、半宽 —— 半宽由界面按字体量出来）+ 有向边（上游、下游）。</li>
 *   <li>输出：每个节点的列中心 {@code cx}、行中心 {@code cy}；每条边的路由类型
 *       （正向直线 / 同列侧弧 / 回流下方弧）与弧线下沉档位；列数、行数、交叉数。</li>
 * </ul>
 *
 * <p><b>盒子不重叠是构造保证</b>：同一列内每个节点一个行号，行距 {@link #ROW_H} 大于节点高度；
 * 相邻列的 x 区间被 {@link #COL_GAP} 分开 —— 列中心按「该列最宽节点的一半」逐列累加，
 * 所以无论名字多长，两列都不会水平重叠。
 *
 * <p>环不会让它挂死：重心法只按固定次数（{@link #SWEEPS}）迭代，既不需要拓扑序，
 * 也不区分有向无环与否。
 */
public final class TechTreeLayout {

	private TechTreeLayout() {
	}

	/** 工艺阶段数（列数）：矿 / 选矿 / 冶炼 / 化学 / 稀土。 */
	public static final int STAGE_COUNT = 5;

	/** 行距（像素）。必须大于节点盒高度，保证同列不重叠。 */
	public static final int ROW_H = 30;

	/** 相邻列之间的空白（像素）。侧弧就画在这条缝里。 */
	public static final int COL_GAP = 120;

	/** 空列的兜底半宽（像素），免得空阶段把后面的列拽到一起。 */
	public static final int MIN_HALF_W = 34;

	/** 重心法迭代次数；固定次数 = 环也不会挂死。 */
	public static final int SWEEPS = 8;

	/**
	 * 一个待布局的节点。
	 *
	 * @param id    稳定标识，边用它引用
	 * @param stage 工艺阶段（0 矿 / 1 选矿 / 2 冶炼 / 3 化学 / 4 稀土）
	 * @param halfW 盒子半宽（界面按字体量出来；离线 harness 传任意值）
	 */
	public record Node(String id, int stage, int halfW) {
	}

	/** 一条有向边（上游 → 下游）。 */
	public record Edge(String from, String to) {
	}

	/**
	 * 节点的最终落点。
	 *
	 * @param row 列内行号（0 起）
	 */
	public record Placement(String id, int stage, int row, int cx, int cy, int halfW) {
	}

	/** 边的路由：正向直线 / 同列侧弧 / 回流下方弧。 */
	public enum Route {
		/** 下游在更右的阶段：从两盒边界直接连直线。 */
		FORWARD,
		/** 同一列内：在列右侧的缝里鼓一条小弧，不穿同列节点。 */
		SIDE,
		/** 下游在更左的阶段（回边）：沉到整张图下方画弧。 */
		BELOW
	}

	/**
	 * 边的最终路由。
	 *
	 * @param depth {@link Route#BELOW} 的下沉档位（0/1/2 交错，避免多条回流弧完全重叠）；
	 *              其余路由为 0
	 */
	public record Routed(String from, String to, Route route, int depth) {
	}

	/**
	 * 布局结果。
	 *
	 * @param stageCounts 每列的节点数（长度 {@value #STAGE_COUNT}）
	 * @param rowCounts   每列的行数（长度 {@value #STAGE_COUNT}）
	 * @param crossings   正向边在相邻列之间两两交叉的次数（越小越好，用于复盘）
	 * @param width       整张图宽度（像素，最右列的右沿）
	 * @param height      整张图高度（像素，最底行的下沿）
	 */
	public record Result(List<Placement> nodes, List<Routed> edges, int[] stageCounts, int[] rowCounts,
			int crossings, int width, int height) {

		public Result {
			nodes = List.copyOf(nodes);
			edges = List.copyOf(edges);
			stageCounts = stageCounts.clone();
			rowCounts = rowCounts.clone();
		}

		/** 按 id 取落点；没有返回 {@code null}。 */
		public Placement placement(String id) {
			for (Placement p : nodes) {
				if (p.id().equals(id)) {
					return p;
				}
			}
			return null;
		}

		/** 按 (from,to) 取路由；没有返回 {@code null}。 */
		public Routed rout(String from, String to) {
			for (Routed r : edges) {
				if (r.from().equals(from) && r.to().equals(to)) {
					return r;
				}
			}
			return null;
		}
	}

	/**
	 * 算一次布局。输入顺序随意，输出与输入顺序无关（内部先按 id 排序），因此<b>确定性</b>。
	 *
	 * @param nodes 全部节点（id 必须唯一）
	 * @param edges 全部有向边（端点不存在的边会被忽略）
	 */
	public static Result compute(List<Node> nodes, List<Edge> edges) {
		int n = nodes.size();
		String[] id = new String[n];
		int[] stage = new int[n];
		int[] halfW = new int[n];
		Map<String, Integer> index = new LinkedHashMap<>();
		for (int i = 0; i < n; i++) {
			Node node = nodes.get(i);
			id[i] = node.id();
			stage[i] = clampStage(node.stage());
			halfW[i] = Math.max(1, node.halfW());
			index.put(id[i], i);
		}

		// 邻接（只保留两端都存在的边）。
		List<List<Integer>> out = new ArrayList<>(n);
		List<List<Integer>> in = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			out.add(new ArrayList<>());
			in.add(new ArrayList<>());
		}
		List<int[]> arcs = new ArrayList<>();
		for (Edge e : edges) {
			Integer a = index.get(e.from());
			Integer b = index.get(e.to());
			if (a == null || b == null) {
				continue;
			}
			if (a.equals(b)) {
				continue; // 自环不上图，也不参与顺序
			}
			out.get(a).add(b);
			in.get(b).add(a);
			arcs.add(new int[] { a, b });
		}

		// 每列初始顺序：按 id 排序（确定性）。
		List<List<Integer>> columns = new ArrayList<>(STAGE_COUNT);
		for (int s = 0; s < STAGE_COUNT; s++) {
			columns.add(new ArrayList<>());
		}
		for (int i = 0; i < n; i++) {
			columns.get(stage[i]).add(i);
		}
		for (List<Integer> column : columns) {
			column.sort(Comparator.comparing(i -> id[i]));
		}

		int[] pos = new int[n];
		writePositions(columns, pos);

		// 重心法：奇数轮从左往右看左邻居，偶数轮从右往左看右邻居。
		for (int sweep = 0; sweep < SWEEPS; sweep++) {
			boolean leftToRight = (sweep % 2) == 0;
			if (leftToRight) {
				for (int s = 1; s < STAGE_COUNT; s++) {
					reorder(columns.get(s), pos, in, stage, id, s, true);
					writePositions(columns, pos);
				}
			} else {
				for (int s = STAGE_COUNT - 2; s >= 0; s--) {
					reorder(columns.get(s), pos, out, stage, id, s, false);
					writePositions(columns, pos);
				}
			}
		}

		// 列中心：逐列累加「该列最宽节点的一半」，列与列之间留 COL_GAP。
		int[] colHalf = new int[STAGE_COUNT];
		for (int i = 0; i < n; i++) {
			colHalf[stage[i]] = Math.max(colHalf[stage[i]], halfW[i]);
		}
		for (int s = 0; s < STAGE_COUNT; s++) {
			colHalf[s] = Math.max(colHalf[s], MIN_HALF_W);
		}
		int[] colCx = new int[STAGE_COUNT];
		int cursor = 0;
		for (int s = 0; s < STAGE_COUNT; s++) {
			colCx[s] = cursor + colHalf[s];
			cursor = colCx[s] + colHalf[s] + COL_GAP;
		}
		int width = cursor - COL_GAP;

		int[] rowCounts = new int[STAGE_COUNT];
		int maxRows = 0;
		for (int s = 0; s < STAGE_COUNT; s++) {
			rowCounts[s] = columns.get(s).size();
			maxRows = Math.max(maxRows, rowCounts[s]);
		}

		List<Placement> placements = new ArrayList<>(n);
		for (int s = 0; s < STAGE_COUNT; s++) {
			List<Integer> column = columns.get(s);
			for (int r = 0; r < column.size(); r++) {
				int i = column.get(r);
				placements.add(new Placement(id[i], s, r, colCx[s], r * ROW_H, halfW[i]));
			}
		}
		placements.sort(Comparator.comparing(Placement::id));

		int[] stageCounts = new int[STAGE_COUNT];
		for (int i = 0; i < n; i++) {
			stageCounts[stage[i]]++;
		}

		// 路由：右 = 正向直线；同列 = 列间侧弧；左 = 图下方弧线。
		int[] rowOf = new int[n];
		for (Placement p : placements) {
			rowOf[index.get(p.id())] = p.row();
		}
		List<Routed> routed = new ArrayList<>(arcs.size());
		for (int[] arc : arcs) {
			int a = arc[0];
			int b = arc[1];
			Route route;
			if (stage[b] > stage[a]) {
				route = Route.FORWARD;
			} else if (stage[b] == stage[a]) {
				route = Route.SIDE;
			} else {
				route = Route.BELOW;
			}
			routed.add(new Routed(id[a], id[b], route, 0));
		}
		// 先按 (from,to) 排稳定，再给回流弧交错档位 —— 档位与输入顺序无关。
		routed.sort(Comparator.comparing(Routed::from).thenComparing(Routed::to));
		int below = 0;
		for (int i = 0; i < routed.size(); i++) {
			Routed r = routed.get(i);
			if (r.route() == Route.BELOW) {
				routed.set(i, new Routed(r.from(), r.to(), r.route(), below++ % 3));
			}
		}

		int crossings = countCrossings(arcs, stage, rowOf);
		int height = maxRows == 0 ? 0 : (maxRows - 1) * ROW_H;

		return new Result(placements, routed, stageCounts, rowCounts, crossings, width, height);
	}

	/** 用给定一侧的邻居重心排序这一列，平局按 id。 */
	private static void reorder(List<Integer> column, int[] pos, List<List<Integer>> side,
			int[] stage, String[] id, int s, boolean incoming) {
		if (column.size() < 2) {
			return;
		}
		Map<Integer, Double> bary = new LinkedHashMap<>();
		for (int i : column) {
			double sum = 0;
			int count = 0;
			for (int j : side.get(i)) {
				// 只用来自「锚定侧」的邻居：从左扫看更左的阶段，从右扫看更右的阶段。
				if (incoming ? stage[j] < s : stage[j] > s) {
					sum += pos[j];
					count++;
				}
			}
			if (count == 0) {
				// 该侧没有邻居：保持当前相对位置，避免被拽到列首。
				sum = pos[i];
				count = 1;
			}
			bary.put(i, sum / count);
		}
		column.sort(Comparator.<Integer, Double>comparing(bary::get).thenComparing(i -> id[i]));
	}

	private static void writePositions(List<List<Integer>> columns, int[] pos) {
		for (List<Integer> column : columns) {
			for (int r = 0; r < column.size(); r++) {
				pos[column.get(r)] = r;
			}
		}
	}

	/** 两两数交叉：只在「相同的起止阶段对」之间比较，行序反转即交叉。 */
	private static int countCrossings(List<int[]> arcs, int[] stage, int[] rowOf) {
		int crossings = 0;
		for (int i = 0; i < arcs.size(); i++) {
			int[] a = arcs.get(i);
			if (stage[a[1]] <= stage[a[0]]) {
				continue;
			}
			for (int j = i + 1; j < arcs.size(); j++) {
				int[] b = arcs.get(j);
				if (stage[b[1]] <= stage[b[0]]) {
					continue;
				}
				if (stage[a[0]] != stage[b[0]] || stage[a[1]] != stage[b[1]]) {
					continue;
				}
				boolean ab = rowOf[a[0]] < rowOf[b[0]] && rowOf[a[1]] > rowOf[b[1]];
				boolean ba = rowOf[a[0]] > rowOf[b[0]] && rowOf[a[1]] < rowOf[b[1]];
				if (ab || ba) {
					crossings++;
				}
			}
		}
		return crossings;
	}

	private static int clampStage(int stage) {
		return Math.max(0, Math.min(STAGE_COUNT - 1, stage));
	}

	// ------------------------------------------------------------------ 端点 / 弧线几何（纯函数，harness 直接测）

	/**
	 * 从矩形中心沿单位方向 (ux,uy) 射到边界的距离。
	 *
	 * <p>边端点取它，箭头就贴在盒子上，不会钻进节点。
	 */
	public static double boundaryOffset(double halfW, double halfH, double ux, double uy) {
		double tx = ux == 0.0 ? Double.MAX_VALUE : halfW / Math.abs(ux);
		double ty = uy == 0.0 ? Double.MAX_VALUE : halfH / Math.abs(uy);
		return Math.min(tx, ty);
	}

	/** 从盒子中心朝 {@code (tx,ty)} 射一条线，返回落在盒子边界上的点 {@code {x,y}}。 */
	public static double[] boundaryPoint(double cx, double cy, double halfW, double halfH,
			double tx, double ty) {
		double dx = tx - cx;
		double dy = ty - cy;
		double len = Math.hypot(dx, dy);
		if (len < 1.0e-9) {
			return new double[] { cx, cy };
		}
		double ux = dx / len;
		double uy = dy / len;
		double offset = boundaryOffset(halfW, halfH, ux, uy);
		return new double[] { cx + ux * offset, cy + uy * offset };
	}

	/**
	 * 直线边的两端点：分别落在 from / to 盒子的边界上。
	 *
	 * @return {@code {x0,y0,x1,y1}}
	 */
	public static double[] endpoints(double cx0, double cy0, double halfW0, double halfH0,
			double cx1, double cy1, double halfW1, double halfH1) {
		double dx = cx1 - cx0;
		double dy = cy1 - cy0;
		double len = Math.hypot(dx, dy);
		if (len < 1.0e-9) {
			return new double[] { cx0, cy0, cx1, cy1 };
		}
		double ux = dx / len;
		double uy = dy / len;
		double o0 = boundaryOffset(halfW0, halfH0, ux, uy);
		double o1 = boundaryOffset(halfW1, halfH1, ux, uy);
		return new double[] { cx0 + ux * o0, cy0 + uy * o0, cx1 - ux * o1, cy1 - uy * o1 };
	}

	/**
	 * 二次 Bézier 上的一点 {@code (1-t)²P0 + 2(1-t)tC + t²P1}。
	 *
	 * @return {@code {x,y}}
	 */
	public static double[] quadratic(double p0x, double p0y, double cx, double cy,
			double p1x, double p1y, double t) {
		double u = 1.0 - t;
		double x = u * u * p0x + 2.0 * u * t * cx + t * t * p1x;
		double y = u * u * p0y + 2.0 * u * t * cy + t * t * p1y;
		return new double[] { x, y };
	}

	/** 二次 Bézier 在 {@code t} 处的切线方向（未归一化）{@code {dx,dy}}。 */
	public static double[] quadraticTangent(double p0x, double p0y, double cx, double cy,
			double p1x, double p1y, double t) {
		double dx = 2.0 * (1.0 - t) * (cx - p0x) + 2.0 * t * (p1x - cx);
		double dy = 2.0 * (1.0 - t) * (cy - p0y) + 2.0 * t * (p1y - cy);
		return new double[] { dx, dy };
	}
}
