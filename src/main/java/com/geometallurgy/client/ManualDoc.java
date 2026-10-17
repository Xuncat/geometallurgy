package com.geometallurgy.client;

import java.util.ArrayList;
import java.util.List;

import com.geometallurgy.Geometallurgy;
import com.geometallurgy.manual.Manual;

import net.minecraft.network.chat.Component;

/**
 * 任务书的<b>文档模型</b>：四大部分 → 若干页 → 若干块。
 *
 * <h2>为什么要有这一层</h2>
 * 旧的 {@link ManualScreen} 把「哪些章、哪些条、每条几段」全部写在代码里，正文却写在语言文件里，
 * 两边对不上号时界面只会少一行或多一行空白，不报错。现在把「页」的骨架收进 {@link Manual}（每页
 * 一个条目），正文仍然只从语言文件里读（见 {@link ManualText}），而<b>数据表格</b>一律由
 * {@link ManualTables} 在运行时从代码里现算（矿表来自 {@code proto/OreMineral} + {@code proto/OreTier}，
 * 物质表来自 {@code chemical/Chemical}，反应表来自 {@code chemical/MolRecipes}，耗电表来自
 * 各机器方块实体的 public 常量与 {@code chemical/ProductionCost}）——
 * 书里一个字都不是抄的，改了数值书就跟着改。
 *
 * <h2>块</h2>
 * 页内容是一串 {@link Block}：{@link Heading} 小标题、{@link Text} 自然段（按页宽折行）、
 * {@link Table} 表格（每行一个 {@link Row}，可挂物质 id 供搜索）、{@link Link} 可点跳转、
 * {@link Grid} 合成网格、{@link Gap} 空行。
 *
 * <p>每个块带一个 {@code anchor}：搜索命中后要「翻到那一页并高亮这一条」，
 * 落在第几页、页内多高，都是 {@link ManualPager} 在分页时记下的 {@code anchor → (页, y)} 表里查的。
 *
 * <h2>搜索条目</h2>
 * {@link ManualSearch} 的索引直接从这些块与页标题生成，<b>不维护第二份关键词表</b>：
 * 中文查中文（{@code Component} 的当前语言文本）、id 查 id（{@link Row#terms()}）。
 */
public final class ManualDoc {

	private ManualDoc() {
	}

	// ------------------------------------------------------------------ 块

	/** 页内容的一块。 */
	public sealed interface Block permits Heading, Text, Table, Link, Grid, Gap {
		/** 锚点 id；同一页内唯一。 */
		String anchor();
	}

	/** 小标题。 */
	public record Heading(String anchor, Component text) implements Block {
	}

	/** 自然段，按页宽自动折行。 */
	public record Text(String anchor, Component text) implements Block {
	}

	/** 可点的一行。{@code action} 见 {@link ManualScreen#activate(String)}。 */
	public record Link(String anchor, Component text, String action) implements Block {
	}

	/** 空行。 */
	public record Gap(int height) implements Block {
		@Override
		public String anchor() {
			return "gap";
		}
	}

	/** 合成网格：{@code recipeIndex} 是 {@code ManualMenu#recipeGrid(int)} 的下标，负数 = 不画。 */
	public record Grid(String anchor, int recipeIndex) implements Block {
	}

	/**
	 * 表格的一行。
	 *
	 * @param cells        各列文本，第一列通常就是「名字」
	 * @param terms        这一行携带的 id（矿 id / 物质 id / 机器名……），只进搜索索引、不显示
	 * @param searchTitle  搜索结果的标题；{@code null} = 用第一列
	 * @param speciesId    物质 id：搜索命中时直接打开 {@link ReactionDetailScreen}；{@code null} = 只是跳转
	 */
	public record Row(List<Component> cells, String terms, Component searchTitle, String speciesId) {

		public Row {
			cells = List.copyOf(cells);
		}

		public Row(List<Component> cells) {
			this(cells, null, null, null);
		}

		public Row(List<Component> cells, String terms) {
			this(cells, terms, null, null);
		}
	}

	/** 表格。{@code weights} 是各列的相对宽度（不必归一）。 */
	public record Table(String anchor, Component caption, List<Component> headers, List<Row> rows,
			int[] weights) implements Block {

		public Table {
			headers = List.copyOf(headers);
			rows = List.copyOf(rows);
			weights = weights.clone();
		}
	}

	/** 表格第 {@code index} 行的锚点 —— 布局与搜索索引共用，改一处即可。 */
	public static String rowAnchor(Table table, int index) {
		return table.anchor() + "#r" + index;
	}

	// ------------------------------------------------------------------ 页 / 部

	/**
	 * 一页 = 一个 {@link Manual.Entry}，或者一条<b>由代码展开的条目</b>（附录的一条一页）。
	 *
	 * <p>展开出来的页 {@code chapter} / {@code entry} 为 {@code null}：
	 * 它不是语言文件里的条目、没有正文段、也没有合成配方，一切内容都由
	 * {@link ManualAppendix} 现算（见 {@link #ownPage}）。
	 */
	public static final class Page {

		public final String id;
		public final String partId;
		public final Component title;
		public final List<Block> blocks;
		public final Manual.Chapter chapter;
		public final Manual.Entry entry;
		/**
		 * 这一页<b>自己占一张物理页</b>（= 摊开书的一整面）：附录「一种物质一页 / 一条反应一页」。
		 * 分页器在它前面强制换页，但不会为它留一张空纸 —— 只在这张纸已经写了东西时才换。
		 */
		public final boolean ownPage;
		/**
		 * 只进搜索索引、不上纸的语料。普通页就是 {@link #id}（页 id 里含矿 id / 物质 id）；
		 * 展开页额外带上化学式、标签名、物种 id —— 否则按 id 查「一条反应」会落空。
		 */
		public final String terms;

		Page(String id, String partId, Component title, List<Block> blocks,
				Manual.Chapter chapter, Manual.Entry entry) {
			this(id, partId, title, blocks, chapter, entry, false, null);
		}

		/** 展开页：独占一页、无正文、无配方；{@code terms} 供搜索索引。 */
		Page(String id, String partId, Component title, List<Block> blocks, String terms) {
			this(id, partId, title, blocks, null, null, true, terms);
		}

		Page(String id, String partId, Component title, List<Block> blocks,
				Manual.Chapter chapter, Manual.Entry entry, boolean ownPage, String terms) {
			this.id = id;
			this.partId = partId;
			this.title = title;
			this.blocks = List.copyOf(blocks);
			this.chapter = chapter;
			this.entry = entry;
			this.ownPage = ownPage;
			this.terms = terms == null || terms.isBlank() ? id : terms;
		}
	}

	/**
	 * 部分目录里的一<b>组</b>页。
	 *
	 * <p>{@code title} 非空时，部分目录在这一组之前多印一行分组名（氧化物 / 酸 / 电解…）；
	 * 为 {@code null} 就是「接着上一组列」—— 用于封面类的单页（物质表 / 反应表 / 耗电一览）。
	 * 各组按顺序拼起来必须<b>恰好</b>等于 {@link Part#pages}，分页器靠这一点算逻辑页号。
	 */
	public record Group(Component title, List<Page> pages) {

		public Group {
			pages = List.copyOf(pages);
		}
	}

	/** 一部分 = 一个 {@link Manual.Chapter}。 */
	public static final class Part {

		public final String id;
		public final Component title;
		public final List<Page> pages;
		/** {@link #pages} 的分组视图（部分目录按它排版；顺序与 {@link #pages} 一致）。 */
		public final List<Group> groups;

		Part(String id, Component title, List<Page> pages, List<Group> groups) {
			this.id = id;
			this.title = title;
			this.pages = List.copyOf(pages);
			this.groups = List.copyOf(groups);
		}
	}

	/** 整本书。{@code flat} 与 {@code parts} 里的页是同一批对象，只是摊平了。 */
	public static final class Book {

		public final List<Part> parts;
		public final List<Page> flat;

		Book(List<Part> parts, List<Page> flat) {
			this.parts = List.copyOf(parts);
			this.flat = List.copyOf(flat);
		}

		/** 页在 {@link #flat} 里的下标；找不到返回 -1。 */
		public int indexOf(Page page) {
			for (int i = 0; i < this.flat.size(); i++) {
				if (this.flat.get(i) == page) {
					return i;
				}
			}
			return -1;
		}

		/**
		 * 页 id 在 {@link #flat} 里的下标；找不到返回 -1。
		 *
		 * <p>书里自己印的跳转（一条反应 → 它涉及的物质页）用的是 {@code "app:" + 目标页 id}，
		 * 跳转时才解析成逻辑页号 —— 因为「谁在第几页」要等整本书排完才知道。
		 */
		public int indexOfId(String pageId) {
			if (pageId == null) {
				return -1;
			}
			for (int i = 0; i < this.flat.size(); i++) {
				if (pageId.equals(this.flat.get(i).id)) {
					return i;
				}
			}
			return -1;
		}
	}

	// ------------------------------------------------------------------ 组装

	/**
	 * 把「章节骨架 + 当前语言的正文 + 现算的数据表」拼成一本书。
	 *
	 * <p>段数照旧由 {@link ManualText} 从语言文件里数出来，不在代码里写死；
	 * 数据表由 {@link ManualTables#extra(String, String, String)} 按页追加。
	 */
	public static Book build(ManualText text) {
		List<Part> parts = new ArrayList<>();
		List<Page> flat = new ArrayList<>();

		for (Manual.Chapter chapter : Manual.chapters()) {
			List<Page> pages = new ArrayList<>();
			List<Group> groups = new ArrayList<>();

			// 部分目录的分组：一句条目正文（封面）自己一组，它展开的逐条页按类别 / Kind 分组。
			List<Page> bucket = new ArrayList<>();
			String bucketKey = null;
			Component bucketTitle = null;

			for (Manual.Entry entry : chapter.entries()) {
				String id = chapter.id() + "." + entry.id();
				List<Block> blocks = new ArrayList<>();

				int paragraphs = text.paragraphCount(chapter, entry);
				for (int i = 1; i <= paragraphs; i++) {
					Component paragraph = text.paragraph(chapter, entry, i);
					if (paragraph.getString().isEmpty()) {
						blocks.add(new Gap(6));
					} else {
						blocks.add(new Text(id, paragraph));
					}
				}

				try {
					blocks.addAll(ManualTables.extra(chapter.id(), entry.id(), id));
				} catch (RuntimeException e) {
					// 现算表失败只丢表、不丢书 —— 附录坏一张表不该让整本任务书打不开。
					Geometallurgy.LOGGER.warn("[手册] 现算数据表失败，这一页只留正文：{}.{}（{}）",
							chapter.id(), entry.id(), e.toString());
				}

				if (entry.recipe() != null) {
					int index = Manual.recipeIndexOf(entry);
					if (index >= 0) {
						blocks.add(new Grid(id + "#recipe", index));
					}
				}

				List<ManualAppendix.Item> items = ManualAppendix.after(chapter.id(), entry.id());

				// 会展开成逐条页的条目（附录的物质 / 反应）：封面自己一组，展开项各自成组。
				if (!items.isEmpty()) {
					if (!bucket.isEmpty()) {
						groups.add(new Group(bucketTitle, bucket));
						bucket = new ArrayList<>();
					}
					bucketKey = null;
					bucketTitle = null;
				}

				Page page = new Page(id, chapter.id(),
						Component.translatable(Manual.titleKey(chapter, entry)), blocks, chapter, entry);
				pages.add(page);
				flat.add(page);
				bucket.add(page);

				for (ManualAppendix.Item item : items) {
					if (!java.util.Objects.equals(bucketKey, item.groupKey())) {
						if (!bucket.isEmpty()) {
							groups.add(new Group(bucketTitle, bucket));
							bucket = new ArrayList<>();
						}
						bucketKey = item.groupKey();
						bucketTitle = item.groupTitle();
					}
					Page generated = new Page(item.id(), chapter.id(), item.title(), item.blocks(),
							item.terms());
					pages.add(generated);
					flat.add(generated);
					bucket.add(generated);
				}

				if (!items.isEmpty()) {
					if (!bucket.isEmpty()) {
						groups.add(new Group(bucketTitle, bucket));
						bucket = new ArrayList<>();
					}
					bucketKey = null;
					bucketTitle = null;
				}
			}

			if (!bucket.isEmpty()) {
				groups.add(new Group(bucketTitle, bucket));
			}
			parts.add(new Part(chapter.id(), Component.translatable(Manual.chapterTitleKey(chapter)),
					pages, groups));
		}
		return new Book(parts, flat);
	}
}
