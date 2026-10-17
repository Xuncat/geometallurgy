package com.geometallurgy.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.geometallurgy.manual.Manual;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/**
 * 任务书的<b>分页器</b>：把 {@link ManualDoc} 的块流按「一页能放多高」切成书页。
 *
 * <h2>为什么单独一层</h2>
 * {@link ManualDoc} 只回答「这本书有哪些块」，回答不了「哪一块落在第几页」——
 * 那要看当前窗口给了多少宽、多少高（GUI scale 2/3、小窗口都不一样），
 * 而 {@link ManualScreen} 又得在<b>画之前</b>就知道每一页有什么（页码、跳转锚点、
 * 表格跨页补表头都靠它）。所以这里做一次纯布局：给定页宽 / 页高，
 * 输出一串 {@link Page} 和一张 {@code 锚点 → (页, y)} 表。
 *
 * <h2>流式：左页 → 右页 → 下一跨</h2>
 * 每一部分从新的一跨开始；正文块按顺序往下摆，装不下就换页。
 * 自然段按页宽折行、<b>按行切</b>（一段比一页还长也不会溢出）；
 * 表格<b>按行切</b>，换页后自动补一行表头；合成网格整块搬，不拆。
 *
 * <h2>两级目录与「按部分」的页码</h2>
 * 书首是<b>总目录</b>（一页四行，每部分一行）：
 * <ol>
 *   <li>总目录 —— 每部分一行「部分名 · N 页」，点了进该部分的<b>部分目录页</b>；</li>
 *   <li>部分目录页 —— 是该部分的<b>第一个物理页</b>，列出该部分每个小节与它在这一部分内的
 *       首页码，点了进正文那一条；小节多的时候部分目录本身也会跨页；</li>
 *   <li>正文 —— 从部分目录之后接着排，页脚印「部分名 局部页/部分总页数」。</li>
 * </ol>
 * <b>部分目录页计入该部分的总页数、并从 1 开始编号</b>（所以第一部分正文通常从
 * {@code 2/N} 起）；这样每一张物理页都有唯一、连续的局部页码，小节首页码也就是
 * {@code 物理页 - 部分首页 + 1}。跨部分补的空白页不计数、不印页脚。
 *
 * <h2>两遍布局</h2>
 * 总目录要写「该部分多少页」、部分目录要写「小节在第几页」，这些数字要等整本书排完
 * 才知道；而行的条数不受这些数字影响（都是一行）。所以排两遍：第一遍用占位符拿到
 * {@link Result#logicalStart} / {@link Result#partCount}，第二遍把真实数字填进去 ——
 * 两遍的页边界逐页相同。
 *
 * <h2>页高与裁剪</h2>
 * 页内容坐标 {@code y = 0} 是正文区顶，宽就是 {@code textWidth}。
 * 单块比整页还高（只可能在极端小窗口出现）时仍然放上去，
 * 由界面那边的 {@code enableScissor} 裁掉溢出部分 —— 宁可少看半行，也不要错位或崩。
 */
final class ManualPager {

	/** 合成网格一格的边长，界面画格子时也用这个。 */
	static final int CELL = 18;
	/** 合成网格整块占的高度：3×3 格 + 箭头右边距。 */
	static final int GRID_H = CELL * 3 + 6;

	/** 纸面墨色 —— 分页器与界面共用一套，避免两处各写一个数。 */
	static final int INK = 0xFF33291C;
	static final int INK_DIM = 0xFF7A6A50;
	static final int HEAD_INK = 0xFF6B4A22;
	static final int ACCENT = 0xFF8A6A3A;
	static final int TABLE_HEAD = 0xFFD8C9A4;
	/** 小标题下面的短横线（半透明暖褐）。 */
	private static final int RULE = 0x55B8A87E;

	private ManualPager() {
	}

	/** 合成网格的来源：返回 {@code null} = 这条没有可画的配方。 */
	interface GridSource {

		List<ItemStack> grid(int recipeIndex);
	}

	/** 一行文本（已折好）。 */
	record Line(int x, int y, FormattedCharSequence text, int color) {
	}

	/** 一块底色（表格表头等）。 */
	record Fill(int x0, int y0, int x1, int y1, int color) {
	}

	/** 一块可点 / 可悬停的区域。{@code anchor} 供搜索跳转后高亮定位。 */
	record Hot(int x0, int y0, int x1, int y1, String action, List<Component> tooltip, String anchor) {
	}

	/** 合成网格。 */
	record Grid(int x, int y, int recipeIndex) {
	}

	/**
	 * 一页 = 正文区里的一屏。
	 *
	 * <p>{@code crumb} 是这一页左上角要印的面包屑（{@code 部分名 → 小节名}）：
	 * 在<b>建页那一刻</b>按「这一页是从哪个小节接上的」定下来，所以跨页的续页
	 * 仍然是同一小节。{@code part} 是所属部分（前言目录 = -1），{@code blank}
	 * 是跨部分补的空白页（不印页脚、不计数）。
	 */
	static final class Page {

		final List<Line> lines = new ArrayList<>();
		final List<Fill> fills = new ArrayList<>();
		final List<Hot> hots = new ArrayList<>();
		final List<Grid> grids = new ArrayList<>();
		Component crumb = Component.empty();
		int part = -1;
		int logical = -1;
		int height;
		boolean blank;

		boolean isEmpty() {
			return this.lines.isEmpty() && this.fills.isEmpty() && this.hots.isEmpty() && this.grids.isEmpty();
		}
	}

	/** 分页结果。 */
	static final class Result {

		final List<Page> pages;
		/** 锚点 → {页下标, 页内 y}。 */
		final Map<String, int[]> anchors;
		/** 逻辑页（条目）→ 它的第一个物理页；{@code -1} = 没找到。 */
		final int[] logicalStart;
		/** 部分 → 它的第一个物理页（= 部分目录页）。 */
		final int[] partStart;
		/** 部分 → 这一部分的物理页数（含部分目录页，不含空白页）。 */
		final int[] partCount;
		/** 物理页 → 所属部分；{@code -1} = 书首总目录 / 空白页。 */
		final int[] pagePart;
		/** 物理页 → 部分内页码（1 起）；空白页 = 0。 */
		final int[] pageLocal;

		Result(List<Page> pages, Map<String, int[]> anchors, int[] logicalStart, int[] partStart,
				int[] partCount, int[] pagePart, int[] pageLocal) {
			this.pages = pages;
			this.anchors = anchors;
			this.logicalStart = logicalStart;
			this.partStart = partStart;
			this.partCount = partCount;
			this.pagePart = pagePart;
			this.pageLocal = pageLocal;
		}

		/** 锚点所在页；没有这个锚点就返回 {@code fallback}。 */
		int pageOf(String anchor, int fallback) {
			int[] spot = anchor == null ? null : this.anchors.get(anchor);
			return spot == null ? fallback : spot[0];
		}

		/** 锚点的页内 y；没有就返回 0。 */
		int yOf(String anchor) {
			int[] spot = anchor == null ? null : this.anchors.get(anchor);
			return spot == null ? 0 : spot[1];
		}

		/** 这一张物理页属于哪个部分；书首 / 空白页 = -1。 */
		int partOf(int pageIndex) {
			return pageIndex < 0 || pageIndex >= this.pagePart.length ? -1 : this.pagePart[pageIndex];
		}

		/** 这一张物理页在部分内的页码（1 起）；书首 / 空白页 = 0。 */
		int localPage(int pageIndex) {
			return pageIndex < 0 || pageIndex >= this.pageLocal.length ? 0 : this.pageLocal[pageIndex];
		}

		/** 这一部分总共有多少张物理页（含部分目录页）。 */
		int partPages(int partIndex) {
			return partIndex < 0 || partIndex >= this.partCount.length ? 0 : this.partCount[partIndex];
		}
	}

	// ------------------------------------------------------------------ 入口

	/**
	 * 先排总目录、再按部分排（部分目录页 + 正文）——
	 * 目录的跳转目标只认逻辑页号（{@code book.flat} 的下标），所以谁先排都行；
	 * 把目录排在最前面，后面的物理页号天然就带上偏移，不必再回填。
	 */
	static Result build(Font font, ManualDoc.Book book, int textWidth, int bodyHeight,
			GridSource grids, Component tocHeader) {
		Result first = buildOnce(font, book, textWidth, bodyHeight, grids, tocHeader, null);
		return buildOnce(font, book, textWidth, bodyHeight, grids, tocHeader, first);
	}

	/** 排一遍。{@code known} 是第一遍的结果（第二遍用来填真实页数）；第一遍传 {@code null}。 */
	private static Result buildOnce(Font font, ManualDoc.Book book, int textWidth, int bodyHeight,
			GridSource grids, Component tocHeader, Result known) {
		Builder builder = new Builder(font, Math.max(24, textWidth), Math.max(20, bodyHeight),
				book.flat.size(), book.parts.size(), grids);

		builder.frontMatter(tocHeader);
		builder.toc(book, known);
		builder.finishPart();

		int flat = 0;
		for (int p = 0; p < book.parts.size(); p++) {
			ManualDoc.Part part = book.parts.get(p);
			builder.startPart(part.title,
					Component.translatable("manual.geometallurgy.toc.part_title", part.title), p);
			builder.partToc(part, flat, known);
			for (ManualDoc.Page page : part.pages) {
				builder.beginSection(page.title, page.id, flat, page.ownPage);
				for (ManualDoc.Block block : page.blocks) {
					builder.block(block);
				}
				flat++;
			}
			builder.finishPart();
		}
		return builder.result();
	}

	// ------------------------------------------------------------------ 构建

	private static final class Builder {

		private final Font font;
		private final int width;
		private final int bodyH;
		private final int step;
		private final GridSource grids;
		private final List<Page> pages = new ArrayList<>();
		private final Map<String, int[]> anchors = new LinkedHashMap<>();
		private final int[] logicalStart;
		private final int[] partStart;

		private Page cur;
		private int y;
		private int logicalCursor = -1;
		/** 当前部分（书首 = -1）。 */
		private int partIndex = -1;
		/** 当前部分的标题 —— 面包屑的左半。 */
		private Component partTitle;
		/** 当前小节的标题 —— 面包屑的右半；{@code null} = 还没进正文。 */
		private Component sectionTitle;

		Builder(Font font, int width, int bodyH, int logicalCount, int partCount, GridSource grids) {
			this.font = font;
			this.width = width;
			this.bodyH = bodyH;
			this.step = Math.max(9, font.lineHeight + 2);
			this.grids = grids;
			this.logicalStart = new int[logicalCount];
			Arrays.fill(this.logicalStart, -1);
			this.partStart = new int[partCount];
			Arrays.fill(this.partStart, -1);
		}

		// -------------------------------------------------------------- 页

		private void newPage() {
			Page page = new Page();
			page.logical = this.logicalCursor;
			page.part = this.partIndex;
			page.crumb = crumb();
			this.cur = page;
			this.pages.add(page);
			this.y = 0;
		}

		/** 面包屑：有部分 + 小节就两个都写，只有部分（目录页）就写部分。 */
		private Component crumb() {
			if (this.sectionTitle != null && this.partTitle != null) {
				return Component.translatable("manual.geometallurgy.crumb", this.partTitle, this.sectionTitle);
			}
			if (this.sectionTitle != null) {
				return this.sectionTitle;
			}
			return this.partTitle == null ? Component.empty() : this.partTitle;
		}

		private int pageIndex() {
			return this.pages.size() - 1;
		}

		private boolean fits(int height) {
			return this.y + height <= this.bodyH;
		}

		/** 放不下这块就换页（第一页不换，避免开头一片空白）。 */
		private void ensure(int height) {
			if (!fits(height) && this.y > 0) {
				newPage();
			}
		}

		/** 标题类：如果剩下的高度连「标题 + 至少一行」都塞不下，就先换页。 */
		private void keep(int height) {
			if (this.bodyH - this.y < height && this.y > 0) {
				newPage();
			}
		}

		private void anchor(String key, int yy) {
			if (key == null) {
				return;
			}
			this.anchors.putIfAbsent(key, new int[] { pageIndex(), yy });
		}

		// -------------------------------------------------------------- 书首 / 部 / 条

		/** 书首（总目录）：不属于任何部分，面包屑只写目录名。 */
		void frontMatter(Component title) {
			this.partIndex = -1;
			this.partTitle = title;
			this.sectionTitle = null;
			this.logicalCursor = -1;
			newPage();
		}

		/**
		 * 开始一部分：先补齐到偶数页（新部分从新的一跨左页开始），再开部分目录页。
		 *
		 * @param partTitle 部分名（面包屑在正文页里用它）
		 * @param heading   部分目录页顶上那一行（通常是「部分名 · 目录」）
		 */
		void startPart(Component partTitle, Component heading, int partIndex) {
			if (!this.pages.isEmpty() && (this.pages.size() & 1) == 1) {
				addBlank();
			}
			this.logicalCursor = -1;
			this.partIndex = partIndex;
			this.partTitle = partTitle;
			this.sectionTitle = null;
			newPage();
			this.partStart[partIndex] = pageIndex();
			if (heading != null) {
				this.cur.lines.add(new Line(0, this.y, heading.getVisualOrderText(), HEAD_INK));
				this.y += this.step + 3;
				this.cur.fills.add(new Fill(0, this.y - 2,
						Math.max(24, this.font.width(heading) + 6), this.y - 1, RULE));
			}
		}

		/** 结束一部分：奇数页就补一张空白页，让下一部分仍从跨的左页开始。 */
		void finishPart() {
			if ((this.pages.size() & 1) == 1) {
				addBlank();
			}
		}

		private void addBlank() {
			newPage();
			this.cur.blank = true;
		}

		/**
		 * 开始一个条目：记下它的第一页，并画一行条目标题。
		 * 每个条目都会画标题，所以 {@code page.id} 这个锚点一定存在 —— 目录点得动。
		 *
		 * @param ownPage 这一条<b>独占一张物理页</b>（附录的「一条一页」）。
		 *                只在这一页已经写了东西时才换页，所以连续两条不会各留一张空纸。
		 *                条目标题照旧按页宽折行 —— 方程式的产物名可以很长，宁可她占两行。
		 */
		void beginSection(Component title, String id, int logicalIndex, boolean ownPage) {
			this.logicalCursor = logicalIndex;
			this.sectionTitle = title;
			if (ownPage && this.y > 0) {
				newPage();
			}
			keep(this.step * 2);
			if (this.cur.logical < 0) {
				// 部分目录页之后新开的那一页：它的面包屑在 newPage 时还没进正文，
				// 到这里补上「部分名 → 小节名」。
				this.cur.logical = logicalIndex;
				this.cur.crumb = crumb();
			}
			if (logicalIndex >= 0 && logicalIndex < this.logicalStart.length) {
				this.logicalStart[logicalIndex] = pageIndex();
			}
			anchor(id, this.y);
			for (FormattedCharSequence line : split(title, this.width)) {
				this.cur.lines.add(new Line(0, this.y, line, HEAD_INK));
				this.y += this.step;
			}
			this.y += 3;
			this.cur.fills.add(new Fill(0, this.y - 2,
					Math.min(this.width, Math.max(24, this.font.width(title) + 6)), this.y - 1, RULE));
		}

		// -------------------------------------------------------------- 目录

		/** 总目录：只有四行，每部分一行「部分名 · N 页」，点了进部分目录页。 */
		void toc(ManualDoc.Book book, Result known) {
			for (int p = 0; p < book.parts.size(); p++) {
				ManualDoc.Part part = book.parts.get(p);
				ensure(this.step * 2);
				int count = known == null ? 0 : known.partPages(p);
				Component text = count > 0
						? Component.translatable("manual.geometallurgy.toc.part_row", part.title, count)
						: Component.translatable("manual.geometallurgy.toc.part_row", part.title, "…");
				link("toc.part." + p, text, "part:" + p, 0);
				this.y += 4;
			}
		}

		/**
		 * 部分目录：该部分每个小节一行「小节名 · 部分内首页码」，点了进正文那一条。
		 *
		 * <p>条目按 {@link ManualDoc.Group} <b>分组</b>列：分组标题（氧化物 / 酸 / 电解…）
		 * 单独印一行、不可点，下面跟着这一组的条目。分组只是目录的排版 ——
		 * 正文里的物理页顺序与 {@code part.pages} 完全一致，所以 {@code flat} 照旧逐个自增。
		 */
		void partToc(ManualDoc.Part part, int flatBase, Result known) {
			int flat = flatBase;
			for (ManualDoc.Group group : part.groups) {
				Component groupTitle = group.title();
				if (groupTitle != null && !groupTitle.getString().isEmpty()) {
					keep(this.step * 2);
					ensure(this.step);
					for (FormattedCharSequence line : split(groupTitle, this.width)) {
						this.cur.lines.add(new Line(0, this.y, line, HEAD_INK));
						this.y += this.step;
					}
					this.cur.fills.add(new Fill(0, this.y - 2, this.width, this.y - 1, TABLE_HEAD));
					this.y += 2;
				}
				for (ManualDoc.Page page : group.pages()) {
					int local = localOf(known, flat);
					Component text = local > 0
							? Component.translatable("manual.geometallurgy.toc.section_entry", page.title, local)
							: Component.translatable("manual.geometallurgy.toc.section_entry", page.title, "…");
					link("toc.section." + flat, text, "go:" + flat + ":" + page.id, 0);
					flat++;
				}
			}
			// 正文另起一页 —— 部分目录页永远是这一部分的第 1 页。
			this.logicalCursor = -1;
			newPage();
		}

		private static int localOf(Result known, int flat) {
			if (known == null || flat < 0 || flat >= known.logicalStart.length) {
				return 0;
			}
			int physical = known.logicalStart[flat];
			return physical < 0 ? 0 : known.localPage(physical);
		}

		// -------------------------------------------------------------- 块

		void block(ManualDoc.Block block) {
			if (block instanceof ManualDoc.Heading heading) {
				text(heading.anchor(), heading.text(), HEAD_INK, 0, true);
			} else if (block instanceof ManualDoc.Text text) {
				text(text.anchor(), text.text(), INK, 0, false);
			} else if (block instanceof ManualDoc.Link link) {
				link(link.anchor(), link.text(), link.action(), 0);
			} else if (block instanceof ManualDoc.Gap gap) {
				this.y += gap.height();
			} else if (block instanceof ManualDoc.Grid grid) {
				grid(grid.anchor(), grid.recipeIndex(), 0);
			} else if (block instanceof ManualDoc.Table table) {
				table(table);
			}
		}

		/** 一段折行正文，按行切页。 */
		private void text(String anchor, Component comp, int color, int indent, boolean heading) {
			int wrap = Math.max(24, this.width - indent);
			List<FormattedCharSequence> lines;
			if (comp.getString().isEmpty()) {
				lines = List.of(FormattedCharSequence.EMPTY);
			} else {
				lines = this.font.split(comp, wrap);
			}
			if (lines.isEmpty()) {
				lines = List.of(FormattedCharSequence.EMPTY);
			}
			if (heading) {
				keep(this.step * 2);
			}
			boolean first = true;
			for (FormattedCharSequence line : lines) {
				ensure(this.step);
				if (first) {
					anchor(anchor, this.y);
					first = false;
				}
				this.cur.lines.add(new Line(indent, this.y, line, color));
				this.y += this.step;
			}
			this.y += 2;
		}

		/**
		 * 一条可点行。按页宽折行：方程式的物质名可以很长，宁可它占两行，
		 * 也不能让后半截被纸边裁掉（裁掉的那半截玩家既看不到、也点不着）。
		 */
		private void link(String anchor, Component comp, String action, int indent) {
			List<FormattedCharSequence> lines = split(comp, Math.max(24, this.width - indent));
			boolean first = true;
			for (FormattedCharSequence line : lines) {
				ensure(this.step);
				if (first) {
					anchor(anchor, this.y);
					first = false;
				}
				this.cur.hots.add(new Hot(indent, this.y - 1, this.width,
						this.y + this.font.lineHeight - 1, action, null, anchor));
				this.cur.lines.add(new Line(indent, this.y, line, ACCENT));
				this.y += this.step;
			}
		}

		private void grid(String anchor, int recipeIndex, int indent) {
			if (this.grids == null) {
				return;
			}
			List<ItemStack> items = this.grids.grid(recipeIndex);
			if (items == null || items.size() < Manual.RECIPE_SLOTS) {
				return;
			}
			boolean any = false;
			for (ItemStack stack : items) {
				if (!stack.isEmpty()) {
					any = true;
					break;
				}
			}
			if (!any) {
				return;
			}
			ensure(GRID_H);
			anchor(anchor, this.y);
			this.cur.grids.add(new Grid(indent, this.y, recipeIndex));
			this.y += GRID_H;
		}

		/**
		 * 一张表：标题 + 表头 + 逐行；换页补表头。
		 *
		 * <p><b>单元格一律折行，绝不裁切</b>（bug#47）：每一行的行高 = 该行各列折行后的
		 * 最大行数 × 行距；某一行的行数连整页都放不下时，按<b>文本行</b>切页（续页补表头），
		 * 行的热区跟着它落在哪张纸就画在哪张纸 —— 所以「名字/id/化学/相/摩/溶」六列
		 * 挤一页时也不会把 id 截成 {@code sodi}、把 H₂O 截成 {@code H2}。
		 */
		private void table(ManualDoc.Table table) {
			int columns = Math.max(1, table.headers().size());
			int[] widths = columnWidths(table, columns);

			// 表头行放不下的页，不如从下一页开头画整张表。
			if (table.caption() != null) {
				keep(this.step * 2);
				ensure(this.step);
				this.cur.lines.add(new Line(0, this.y, table.caption().getVisualOrderText(), HEAD_INK));
				this.y += this.step + 2;
			}
			keep(this.step * 2);
			tableHeader(table, widths);

			List<ManualDoc.Row> rows = table.rows();
			for (int r = 0; r < rows.size(); r++) {
				ManualDoc.Row row = rows.get(r);
				// 每格先按它自己的列宽折好行，再取最大行数当行高。
				List<List<FormattedCharSequence>> cells = new ArrayList<>(columns);
				int height = 1;
				for (int c = 0; c < columns; c++) {
					Component cell = c < row.cells().size() ? row.cells().get(c) : Component.empty();
					List<FormattedCharSequence> lines = split(cell, Math.max(1, widths[c] - 4));
					cells.add(lines);
					height = Math.max(height, lines.size());
				}
				String anchor = ManualDoc.rowAnchor(table, r);
				String action = row.speciesId() != null
						? "species:" + row.speciesId()
						: "go:" + this.logicalCursor + ":" + anchor;
				boolean anchored = false;
				for (int line = 0; line < height; line++) {
					if (!fits(this.step) && this.y > 0) {
						newPage();
						tableHeader(table, widths);
					}
					if (!anchored) {
						anchor(anchor, this.y);
						anchored = true;
					}
					int cx = 0;
					for (int c = 0; c < columns; c++) {
						List<FormattedCharSequence> lines = cells.get(c);
						if (line < lines.size()) {
							this.cur.lines.add(new Line(cx, this.y, lines.get(line), INK));
						}
						cx += widths[c];
					}
					this.cur.hots.add(new Hot(0, this.y - 1, this.width,
							this.y + this.font.lineHeight - 1, action, row.cells(), anchor));
					this.y += this.step;
				}
			}
			this.y += 3;
		}

		/** 表头：同样按列宽折行，底色按实际行数铺满。 */
		private void tableHeader(ManualDoc.Table table, int[] widths) {
			int columns = widths.length;
			List<List<FormattedCharSequence>> cells = new ArrayList<>(columns);
			int height = 1;
			for (int i = 0; i < columns; i++) {
				Component head = i < table.headers().size() ? table.headers().get(i) : Component.empty();
				List<FormattedCharSequence> lines = split(head, Math.max(1, widths[i] - 4));
				cells.add(lines);
				height = Math.max(height, lines.size());
			}
			ensure(this.step * height);
			this.cur.fills.add(new Fill(0, this.y - 1, this.width,
					this.y + this.step * (height - 1) + this.font.lineHeight, TABLE_HEAD));
			for (int line = 0; line < height; line++) {
				int x = 0;
				for (int i = 0; i < columns; i++) {
					List<FormattedCharSequence> lines = cells.get(i);
					if (line < lines.size()) {
						this.cur.lines.add(new Line(x, this.y, lines.get(line), HEAD_INK));
					}
					x += widths[i];
				}
				this.y += this.step;
			}
		}

		/**
		 * 把可用宽度按权重分给各列，<b>保证 Σ 恰好等于 {@code this.width}</b>。
		 *
		 * <p>旧写法把每列都抬到至少 12 px、最后一列吃掉余数，权重和页宽对不上时
		 * 总和会超出纸宽 —— 多出来的那列整块落到纸外（表格被裁切的老根因）。
		 * 现在每列还至少留 1 px 给后面的列，最后一列拿剩下的全部。
		 */
		private int[] columnWidths(ManualDoc.Table table, int columns) {
			int[] widths = new int[columns];
			int total = 0;
			for (int i = 0; i < columns; i++) {
				total += i < table.weights().length ? Math.max(1, table.weights()[i]) : 1;
			}
			if (total <= 0) {
				total = columns;
			}
			int used = 0;
			for (int i = 0; i < columns; i++) {
				int weight = i < table.weights().length ? Math.max(1, table.weights()[i]) : 1;
				int room = this.width - used - (columns - 1 - i);
				int w = i == columns - 1 ? this.width - used : this.width * weight / total;
				widths[i] = Math.max(1, Math.min(w, room));
				used += widths[i];
			}
			return widths;
		}

		/**
		 * 一段文本按最大宽度折行。
		 *
		 * <p>空文本 / 折不出行时给一个空行，保证「至少一行」的高度 ——
		 * 表格行高按最大行数算，缺了这一步空单元格会把整行压成 0 高。
		 * 空白符的排版照旧交给 {@link Font#split}：它在空格处折，遇到超长的
		 * 不可断词（化学式 / id）会硬切，所以折行结果一定画得进给定宽度。
		 */
		private List<FormattedCharSequence> split(Component text, int width) {
			if (text == null || text.getString().isEmpty()) {
				return List.of(FormattedCharSequence.EMPTY);
			}
			List<FormattedCharSequence> lines = this.font.split(text, Math.max(1, width));
			return lines.isEmpty() ? List.of(FormattedCharSequence.EMPTY) : lines;
		}

		// -------------------------------------------------------------- 出口

		Result result() {
			for (Page page : this.pages) {
				int max = 0;
				for (Line line : page.lines) {
					max = Math.max(max, line.y() + this.font.lineHeight);
				}
				for (Fill fill : page.fills) {
					max = Math.max(max, fill.y1());
				}
				for (Grid grid : page.grids) {
					max = Math.max(max, grid.y() + GRID_H);
				}
				page.height = max;
			}

			// 每张物理页属于哪个部分、部分内第几页；空白页计数但不编号。
			int n = this.pages.size();
			int[] pagePart = new int[n];
			Arrays.fill(pagePart, -1);
			int[] pageLocal = new int[n];
			int[] partCount = new int[this.partStart.length];
			for (int p = 0; p < this.partStart.length; p++) {
				int from = this.partStart[p];
				if (from < 0) {
					continue;
				}
				int to = n;
				for (int q = p + 1; q < this.partStart.length; q++) {
					if (this.partStart[q] >= 0) {
						to = this.partStart[q];
						break;
					}
				}
				int local = 0;
				for (int i = from; i < to; i++) {
					pagePart[i] = p;
					if (!this.pages.get(i).blank) {
						local++;
						pageLocal[i] = local;
					}
				}
				partCount[p] = local;
			}

			return new Result(List.copyOf(this.pages), Map.copyOf(this.anchors), this.logicalStart,
					this.partStart, partCount, pagePart, pageLocal);
		}
	}
}
