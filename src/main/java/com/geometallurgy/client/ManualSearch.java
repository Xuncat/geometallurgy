package com.geometallurgy.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.network.chat.Component;

/**
 * 任务书的搜索索引 —— <b>运行时从文档本身现建</b>，不维护第二份关键词表。
 *
 * <h2>索引里有什么</h2>
 * 每一页、每个小标题、每个可点链接、表格的每一行都是一条索引项：
 * <ul>
 *   <li>{@code title} —— 页标题 / 小标题 / 表格首列（反应行是整条方程）。<b>已翻译</b>，
 *       所以中文查中文、英文查英文；</li>
 *   <li>{@code terms} —— 这一行携带的 id（矿 id、物质 id、机器枚举名）与反应的 id / 化学式式子，
 *       所以 {@code sodium_chloride}、{@code NaCl} 也能命中；</li>
 *   <li>{@code body} —— 页正文（自然段 + 表格单元格拼起来），按「正文包含」排序；</li>
 *   <li>{@code speciesId} —— 物质行的物质 id；命中它时直接打开 {@link ReactionDetailScreen}。</li>
 * </ul>
 *
 * <p>因为索引是从 {@link ManualDoc} 的块里长出来的，正文改了 / 数据表重算了，索引自动跟着变，
 * 不存在「关键词表写着 X、书里已经改成 Y」这种漂移。
 *
 * <h2>排序</h2>
 * 精确标题 &gt; 标题前缀 &gt; 标题包含 &gt; id 包含 &gt; 正文包含；同档按标题长短、页码排。
 * 命中数上限由界面给（通常 8 条）。
 */
public final class ManualSearch {

	private ManualSearch() {
	}

	/**
	 * 一条索引项。
	 *
	 * @param title     显示标题（已翻译）
	 * @param body      正文语料
	 * @param terms     额外语料：id、化学式式子、枚举名
	 * @param anchor    跳转锚点
	 * @param page      页下标（{@link ManualDoc.Book#flat}）
	 * @param speciesId 物质 id；非物质行为 {@code null}
	 */
	public record Entry(Component title, String body, String terms, String anchor, int page,
			String speciesId) {
	}

	/** 一条命中。 */
	public record Result(Entry entry, int score) {
	}

	/** 建索引。 */
	public static List<Entry> index(ManualDoc.Book book) {
		List<Entry> out = new ArrayList<>();
		for (int pageIndex = 0; pageIndex < book.flat.size(); pageIndex++) {
			ManualDoc.Page page = book.flat.get(pageIndex);

			StringBuilder body = new StringBuilder();
			for (ManualDoc.Block block : page.blocks) {
				if (block instanceof ManualDoc.Text text) {
					body.append(text.text().getString()).append(' ');
				} else if (block instanceof ManualDoc.Heading heading) {
					body.append(heading.text().getString()).append(' ');
				} else if (block instanceof ManualDoc.Link link) {
					body.append(link.text().getString()).append(' ');
				} else if (block instanceof ManualDoc.Table table) {
					body.append(table.caption().getString()).append(' ');
					for (ManualDoc.Row row : table.rows()) {
						for (Component cell : row.cells()) {
							body.append(cell.getString()).append(' ');
						}
						if (row.terms() != null) {
							body.append(row.terms()).append(' ');
						}
					}
				}
			}

			out.add(new Entry(page.title, body.toString(), page.terms, page.id, pageIndex, null));

			for (ManualDoc.Block block : page.blocks) {
				if (block instanceof ManualDoc.Heading heading) {
					out.add(new Entry(heading.text(), "", null, heading.anchor(), pageIndex, null));
				} else if (block instanceof ManualDoc.Link link) {
					out.add(new Entry(link.text(), "", null, link.anchor(), pageIndex, null));
				} else if (block instanceof ManualDoc.Table table) {
					for (int i = 0; i < table.rows().size(); i++) {
						ManualDoc.Row row = table.rows().get(i);
						Component title = row.searchTitle() != null ? row.searchTitle()
								: row.cells().isEmpty() ? Component.empty() : row.cells().get(0);
						StringBuilder rowBody = new StringBuilder();
						for (int c = 1; c < row.cells().size(); c++) {
							rowBody.append(row.cells().get(c).getString()).append(' ');
						}
						out.add(new Entry(title, rowBody.toString(), row.terms(),
								ManualDoc.rowAnchor(table, i), pageIndex, row.speciesId()));
					}
				}
			}
		}
		return out;
	}

	/**
	 * 查询：大小写不敏感的子串匹配，按「标题优先级」打分排序。
	 *
	 * @param entries 索引（{@link #index(ManualDoc.Book)}）
	 * @param raw     原始输入
	 * @param limit   最多返回几条
	 */
	public static List<Result> query(List<Entry> entries, String raw, int limit) {
		String query = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
		if (query.isEmpty()) {
			return List.of();
		}

		Map<String, Result> best = new LinkedHashMap<>();
		for (Entry entry : entries) {
			int score = score(entry, query);
			if (score < 0) {
				continue;
			}
			String key = entry.anchor() + "\u0000" + entry.title().getString();
			Result old = best.get(key);
			if (old == null || score < old.score()) {
				best.put(key, new Result(entry, score));
			}
		}

		List<Result> results = new ArrayList<>(best.values());
		results.sort(Comparator.comparingInt(Result::score)
				.thenComparingInt(result -> result.entry().title().getString().length())
				.thenComparingInt(result -> result.entry().page()));

		if (results.size() > limit) {
			return List.copyOf(results.subList(0, limit));
		}
		return List.copyOf(results);
	}

	/** 打分：越小越靠前，{@code -1} = 不命中。 */
	private static int score(Entry entry, String query) {
		String title = entry.title().getString().toLowerCase(Locale.ROOT);
		if (title.equals(query)) {
			return 0;
		}
		if (title.startsWith(query)) {
			return 1;
		}
		if (title.contains(query)) {
			return 2;
		}
		if (entry.terms() != null && entry.terms().toLowerCase(Locale.ROOT).contains(query)) {
			return 3;
		}
		if (entry.body() != null && entry.body().toLowerCase(Locale.ROOT).contains(query)) {
			return 4;
		}
		return -1;
	}
}
