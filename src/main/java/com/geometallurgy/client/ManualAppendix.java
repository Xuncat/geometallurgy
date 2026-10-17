package com.geometallurgy.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.geometallurgy.blockentity.SmelterProfile;
import com.geometallurgy.chemical.Chemical;
import com.geometallurgy.chemical.ChemicalTags;
import com.geometallurgy.chemical.Machine;
import com.geometallurgy.chemical.MolBalance;
import com.geometallurgy.chemical.MolRecipes;
import com.geometallurgy.chemical.ReactionGraph;

import net.minecraft.network.chat.Component;

/**
 * 附录的「一条一页」生成器（2026-10-18 用户反馈 B/C）。
 *
 * <h2>为什么不再是一张大表</h2>
 * 旧的「79 种物质」是一张六列表（名字/id/化学/s相/摩/溶）、「107 条反应」是一张六列表
 * （编号/方程/类/机器/速/电）。书页只有半张纸宽，列宽按权重硬分，权重和大于页宽时
 * 最后一列整块落到纸外；单元格又是「按列宽裁剪、不折行」，于是 id 被截成 {@code sodi}、
 * 化学式被截成 {@code H2}（bug#47）。现在两张表<b>逐条成页</b>：
 * <ul>
 *   <li><b>物质页</b>（{@link Chemical#ids()}，79 条）：名字 / id / 化学式 / 相态 / 摩尔质量 /
 *       是否溶质 / 颜色 / 标签类别 / 理化性质简述 / 参与的反应（可点）/ 生成它的反应（可点）/
 *       消耗它的反应（可点）/ 常用度；</li>
 *   <li><b>反应页</b>（{@link MolRecipes#all()}，107 条）：完整方程（系数全显，名字版 + 化学式版）/
 *       类型 / 机器 / 催化剂 / 速率 / 电耗（溶液档逐反应单价、熔盐档逐配方）/ 可逆与 K /
 *       涉及的物质（可点）/ 用途（在「矿 → 精矿 → … → 金属」链上的位置）。</li>
 * </ul>
 *
 * <h2>一个字都不是手抄的</h2>
 * 物种、公式、摩尔质量、相态、溶解度、颜色、标签、反应、系数、机器、速率、单价、平衡比
 * 全部取自 {@link Chemical} / {@link ChemicalTags} / {@link MolRecipes} / {@link ReactionGraph} /
 * {@code Metallurgy} 的 public 数据；连「在链上的位置」也是 {@link ReactionGraph} 现跑 BFS 算的。
 * 本类只负责排版，不新增任何手写数据表。
 *
 * <h2>常用度</h2>
 * 从数据现算，不拍数字：
 * <pre>
 *   物质：S(s) = 2·R(s) + 3·D(s) + 10·A(s)
 *     R = 参与反应数（作反应物或产物，去重）
 *     D = 下游反应数（s 作为反应物的反应条数）
 *     A = 1 若 s 在「全部机器可用」下从 {@link ReactionGraph#ORE_ROOTS} 可达，否则 0
 *   反应：S(r) = 20·A(r) + 8·B(r) + Σ_{p∈产物} R(p)
 *     A = 1 若该反应全部反应物都可达，B = 1 若全部产物都可达
 * </pre>
 * 两条公式都印在附录条目的封面页上，并给出<b>按分数排序、可点击</b>的排行榜。
 *
 * <h2>页 id 与跳转</h2>
 * 物质页 id = {@code appendix.sub.<物质 id>}，反应页 id = {@code appendix.rxn.<序号>}。
 * 书里自己印的链接用 action {@code "app:<目标页 id>"}，由 {@code ManualScreen} 在跳转时
 * 用 {@link ManualDoc.Book#indexOfId(String)} 解析成逻辑页号 —— 谁在第几页要等整本书排完才知道。
 */
final class ManualAppendix {

	private ManualAppendix() {
	}

	/** 物质页 id 前缀。 */
	private static final String SUB = "appendix.sub.";
	/** 反应页 id 前缀。 */
	private static final String RXN = "appendix.rxn.";

	/** 物质分类的展示顺序（键同时是 lang 键后缀 {@code manual.geometallurgy.group.<键>}）。 */
	private static final List<String> SUB_GROUPS = List.of(
			"element", "acid", "base", "oxide", "hydroxide", "salt", "oxidizer", "other");

	/** 盐类的几个标签：带上任何一个、且没被前面的酸碱氧化物截走，就算「盐」。 */
	private static final List<ChemicalTags.Tag> SALT_TAGS = List.of(
			ChemicalTags.Tag.SULFATE, ChemicalTags.Tag.BISULFATE, ChemicalTags.Tag.CHLORIDE,
			ChemicalTags.Tag.CARBONATE, ChemicalTags.Tag.PHOSPHATE, ChemicalTags.Tag.FLUORIDE,
			ChemicalTags.Tag.NITRATE, ChemicalTags.Tag.OXALATE, ChemicalTags.Tag.PERMANGANATE);

	// ------------------------------------------------------------------ 展开入口

	/**
	 * 展开出来的一条 = 一张独占页。
	 *
	 * @param groupKey   分组键（分组变化的边界）；{@code null} = 不分组（接着上一组列）
	 * @param groupTitle 分组标题；只有每组的<b>第一条</b>带标题，其余为 {@code null}
	 * @param terms      只进搜索索引、不上纸的语料（物种 id、化学式式子、反应类型）
	 */
	record Item(String groupKey, Component groupTitle, String id, Component title, List<ManualDoc.Block> blocks,
			String terms) {
	}

	/**
	 * 这一条条目页之后要展开哪些逐条页。只有附录的「79 种物质 / 107 条反应」两条会展开，
	 * 其余条目（包括附属模组注册的同名条目）返回空表 —— 展开是编译进主模组的排版规则。
	 */
	static List<Item> after(String chapterId, String entryId) {
		if (!"appendix".equals(chapterId)) {
			return List.of();
		}
		return switch (entryId) {
			case "substances" -> substances();
			case "reactions" -> reactions();
			default -> List.of();
		};
	}

	/** 附录物质表的封面页：公式说明 + 常用度排行榜（可点）。 */
	static List<ManualDoc.Block> coverSubstances() {
		List<ManualDoc.Block> out = new ArrayList<>();
		out.add(new ManualDoc.Text("appendix.substances#count", Component.translatable(
				"manual.geometallurgy.table.sub.caption", Chemical.count())));
		out.add(new ManualDoc.Text("appendix.substances#formula",
				Component.translatable("manual.geometallurgy.usage.sub_formula_note")));
		out.add(new ManualDoc.Heading("appendix.substances#rank",
				Component.translatable("manual.geometallurgy.usage.rank_sub", Data.SUB_IDS.size())));
		List<String> ranked = new ArrayList<>(Data.SUB_IDS);
		ranked.sort(Comparator.comparingInt((String id) -> -Data.subScore(id)).thenComparing(id -> id));
		for (String id : ranked) {
			out.add(new ManualDoc.Link("appendix.substances#rank." + id, rankLine(id, Data.subScore(id)),
					"app:" + SUB + id));
		}
		return out;
	}

	/** 附录反应表的封面页：公式说明 + 常用度排行榜（可点）。 */
	static List<ManualDoc.Block> coverReactions() {
		List<ManualDoc.Block> out = new ArrayList<>();
		out.add(new ManualDoc.Text("appendix.reactions#count", Component.translatable(
				"manual.geometallurgy.table.rxn.caption", MolRecipes.count())));
		out.add(new ManualDoc.Text("appendix.reactions#formula",
				Component.translatable("manual.geometallurgy.usage.rxn_formula_note")));
		out.add(new ManualDoc.Heading("appendix.reactions#rank",
				Component.translatable("manual.geometallurgy.usage.rank_rxn", Data.RXNS.size())));
		List<Integer> ranked = new ArrayList<>();
		for (int i = 0; i < Data.RXNS.size(); i++) {
			ranked.add(i);
		}
		ranked.sort(Comparator.comparingInt((Integer i) -> -Data.rxnScore(i)).thenComparingInt(i -> i));
		for (int i : ranked) {
			out.add(new ManualDoc.Link("appendix.reactions#rank." + (i + 1),
					rankLine(reactionTitle(i), Data.rxnScore(i)), "app:" + RXN + (i + 1)));
		}
		return out;
	}

	private static Component rankLine(String name, int score) {
		return Component.translatable("manual.geometallurgy.usage.rank_line",
				Component.literal(Integer.toString(score)), Component.literal(name));
	}

	private static Component rankLine(Component name, int score) {
		return Component.translatable("manual.geometallurgy.usage.rank_line",
				Component.literal(Integer.toString(score)), name);
	}

	// ------------------------------------------------------------------ 物质页

	private static List<Item> substances() {
		List<Item> out = new ArrayList<>();
		for (String group : SUB_GROUPS) {
			boolean opened = false;
			for (String id : Data.SUB_IDS) {
				if (!group.equals(primaryGroup(id))) {
					continue;
				}
				String pageId = SUB + id;
				// 每组的<b>第一条</b>带上分组标题，其余为 null —— ManualDoc 靠分组键变化切组。
				Component groupTitle = opened ? null : Component.translatable(
						"manual.geometallurgy.group." + group);
				opened = true;
				out.add(new Item(group, groupTitle, pageId, substanceTitle(id), substanceBlocks(id, pageId),
						id + " " + Chemical.formulaOf(id) + " " + tagIds(id)));
			}
		}
		return out;
	}

	private static Component substanceTitle(String id) {
		return Component.translatableWithFallback("chemical.geometallurgy." + id, id);
	}

	/**
	 * 一种物质的整页内容。
	 *
	 * <p><b>刻意不用表格</b>：半面纸只有 13 行左右（9 px 字号），八行字段表会把这一页占满，
	 * 反应列表只能全挤到下一页 —— 那就不是「一种物质一页」了。改成「一行一件事」的紧凑行，
	 * 每件事仍按页宽折行，一个字都不省（名字 / id / 化学式 / 相态 / 摩尔质量 / 溶质 /
	 * 颜色 / 标签类别 / 理化性质 / 常用度全在）。
	 */
	private static List<ManualDoc.Block> substanceBlocks(String id, String pageId) {
		Chemical.Info info = Chemical.of(id);
		Set<ChemicalTags.Tag> tags = ChemicalTags.tagsOf(id);
		List<ManualDoc.Block> out = new ArrayList<>();

		// 独立的「配方详情」界面还在，留着入口（点名字进它的反应明细）。
		out.add(new ManualDoc.Link(pageId + "#detail",
				Component.translatable("manual.geometallurgy.sub.detail"), "species:" + id));
		out.add(new ManualDoc.Text(pageId + "#identity", Component.translatable(
				"manual.geometallurgy.sub.line.identity", substanceTitle(id),
				Component.literal(id), Component.literal(info.formula()))));
		out.add(new ManualDoc.Text(pageId + "#physical", Component.translatable(
				"manual.geometallurgy.sub.line.physical", phaseClause(info),
				Component.literal(ManualTables.num(info.molarMass())),
				Component.translatable(info.solution()
						? "manual.geometallurgy.solution.yes" : "manual.geometallurgy.solution.no"))));
		out.add(new ManualDoc.Text(pageId + "#color_tags", Component.translatable(
				"manual.geometallurgy.sub.line.color_tags", colorText(info), tagText(tags))));
		out.add(new ManualDoc.Text(pageId + "#nature", Component.translatable(
				"manual.geometallurgy.sub.nature", phaseClause(info), solutionClause(info),
				colorClause(info), tagText(tags))));
		out.add(new ManualDoc.Text(pageId + "#usage", Component.translatable(
				"manual.geometallurgy.usage.sub_score",
				Component.literal(Integer.toString(Data.subScore(id))),
				Component.literal(Integer.toString(Data.involved(id).size())),
				Component.literal(Integer.toString(Data.asReactant(id).size())),
				Component.literal(Data.REACHED.contains(id) ? "1" : "0"))));

		reactionList(out, pageId + "#all", id);
		return out;
	}

	/**
	 * 「参与的反应」列表 —— <b>一张列表、逐条标方向</b>，不再分印三份。
	 *
	 * <p>水参与 76 条反应，分印「参与 / 生成 / 消耗」三张列表就是 228 行、十几张纸；
	 * 用「用 / 产 / 用·产」标记方向，既能看出哪条生成它、哪条消耗它，行数也只有一份。
	 * 生成 / 消耗的条数写在标题行里。
	 */
	private static void reactionList(List<ManualDoc.Block> out, String anchor, String id) {
		List<Integer> indices = Data.involved(id);
		int reactantCount = Data.asReactant(id).size();
		int productCount = Data.asProduct(id).size();
		out.add(new ManualDoc.Heading(anchor, Component.translatable(
				"manual.geometallurgy.sub.reactions",
				Component.literal(Integer.toString(indices.size())),
				Component.literal(Integer.toString(productCount)),
				Component.literal(Integer.toString(reactantCount)))));
		if (indices.isEmpty()) {
			out.add(new ManualDoc.Text(anchor + "#none",
					Component.translatable("gui.geometallurgy.recipe.none_value")));
			return;
		}
		for (int i : indices) {
			boolean used = Data.asReactant(id).contains(i);
			boolean made = Data.asProduct(id).contains(i);
			out.add(new ManualDoc.Link(anchor + "#" + (i + 1), reactionLinkText(i, used, made),
					"app:" + RXN + (i + 1)));
		}
	}

	private static Component reactionLinkText(int index, boolean used, boolean made) {
		String dir = used ? (made ? "both" : "use") : "make";
		return Component.translatable("manual.geometallurgy.usage.rxn_link",
				Component.translatable("manual.geometallurgy.sub.dir." + dir),
				Component.literal("R" + (index + 1)),
				Component.literal(ManualTables.equation(Data.RXNS.get(index), true)));
	}

	private static Component colorText(Chemical.Info info) {
		String hex = String.format(Locale.ROOT, "0x%06X", info.color() & 0xFFFFFF);
		if (info.color() == Chemical.COLORLESS) {
			return Component.translatable("manual.geometallurgy.sub.color.colorless", hex);
		}
		return Component.translatable("manual.geometallurgy.sub.color.colored", hex);
	}

	private static Component phaseClause(Chemical.Info info) {
		return Component.translatable("manual.geometallurgy.sub.nature."
				+ info.phase().name().toLowerCase(Locale.ROOT));
	}

	private static Component solutionClause(Chemical.Info info) {
		return Component.translatable(info.solution()
				? "manual.geometallurgy.sub.nature.solute" : "manual.geometallurgy.sub.nature.bulk");
	}

	private static Component colorClause(Chemical.Info info) {
		return info.color() == Chemical.COLORLESS
				? Component.translatable("manual.geometallurgy.sub.nature.colorless")
				: Component.translatable("manual.geometallurgy.sub.nature.colored");
	}

	/** 标签类别：按枚举顺序拼；无标签给「无」。 */
	private static Component tagText(Set<ChemicalTags.Tag> tags) {
		if (tags.isEmpty()) {
			return Component.translatable("manual.geometallurgy.tag.none");
		}
		Component out = Component.empty();
		boolean first = true;
		for (ChemicalTags.Tag tag : ChemicalTags.Tag.values()) {
			if (!tags.contains(tag)) {
				continue;
			}
			if (!first) {
				out = out.copy().append(Component.literal(" · "));
			}
			out = out.copy().append(Component.translatable(
					"manual.geometallurgy.tag." + tag.name().toLowerCase(Locale.ROOT)));
			first = false;
		}
		return out;
	}

	/** 一个物质归到哪一类（每类只进一次，分类顺序见 {@link #SUB_GROUPS}）。 */
	private static String primaryGroup(String id) {
		Set<ChemicalTags.Tag> tags = ChemicalTags.tagsOf(id);
		if (tags.contains(ChemicalTags.Tag.ELEMENT)) {
			return "element";
		}
		if (tags.contains(ChemicalTags.Tag.ACID)) {
			return "acid";
		}
		if (tags.contains(ChemicalTags.Tag.BASE)) {
			return "base";
		}
		if (tags.contains(ChemicalTags.Tag.OXIDE)) {
			return "oxide";
		}
		if (tags.contains(ChemicalTags.Tag.HYDROXIDE)) {
			return "hydroxide";
		}
		for (ChemicalTags.Tag salt : SALT_TAGS) {
			if (tags.contains(salt)) {
				return "salt";
			}
		}
		if (tags.contains(ChemicalTags.Tag.OXIDIZER)) {
			return "oxidizer";
		}
		return "other";
	}

	// ------------------------------------------------------------------ 反应页

	private static List<Item> reactions() {
		List<Item> out = new ArrayList<>();
		for (MolBalance.Kind kind : MolBalance.Kind.values()) {
			boolean opened = false;
			for (int i = 0; i < Data.RXNS.size(); i++) {
				if (Data.RXNS.get(i).kind() != kind) {
					continue;
				}
				String key = "kind." + kind.name().toLowerCase(Locale.ROOT);
				Component groupTitle = opened ? null : ManualTables.kindName(kind);
				opened = true;
				out.add(new Item(key, groupTitle, RXN + (i + 1), reactionTitle(i), reactionBlocks(i),
						speciesTerms(Data.RXNS.get(i))));
			}
		}
		return out;
	}

	private static Component reactionTitle(int index) {
		MolBalance.Reaction reaction = Data.RXNS.get(index);
		Component out = Component.literal("R" + (index + 1));
		if (reaction.products().length > 0) {
			out = out.copy().append(Component.literal(" · "))
					.append(ManualTables.name(reaction.products()[0].id()));
		}
		return out;
	}

	/** 一条反应的整页内容。 */
	private static List<ManualDoc.Block> reactionBlocks(int index) {
		MolBalance.Reaction reaction = Data.RXNS.get(index);
		String pageId = RXN + (index + 1);
		List<ManualDoc.Block> out = new ArrayList<>();

		out.add(new ManualDoc.Text(pageId + "#eq", Component.translatable(
				"manual.geometallurgy.rxn.equation_line",
				Component.literal(fullEquation(reaction, true)))));
		out.add(new ManualDoc.Text(pageId + "#eqf", Component.translatable(
				"manual.geometallurgy.rxn.formula_line",
				Component.literal(fullEquation(reaction, false)))));

		// 同样刻意不用八行字段表：半面纸只有 13 行左右，字段表会把物质列表挤到下一页。
		out.add(new ManualDoc.Text(pageId + "#kind_machine", Component.translatable(
				"manual.geometallurgy.rxn.line.kind_machine", ManualTables.kindName(reaction.kind()),
				ManualTables.machines(reaction))));
		out.add(new ManualDoc.Text(pageId + "#rate_rev", Component.translatable(
				"manual.geometallurgy.rxn.line.rate_rev", catalystText(reaction),
				Component.literal(ManualTables.num(reaction.rate())),
				Component.translatable(reaction.reversible()
						? "gui.geometallurgy.recipe.yes" : "manual.geometallurgy.no"),
				Component.literal(reaction.reversible()
						? ManualTables.num(reaction.equilibriumRatio()) : "—"))));
		out.add(new ManualDoc.Text(pageId + "#power", powerDetail(reaction)));
		out.add(new ManualDoc.Text(pageId + "#use", Component.translatable(
				"manual.geometallurgy.usage.rxn_score",
				Component.literal(Integer.toString(Data.rxnScore(index))),
				Component.literal(Data.allReactantsReached(reaction) ? "1" : "0"),
				Component.literal(Data.allProductsReached(reaction) ? "1" : "0"),
				Component.literal(Integer.toString(Data.productInvolvement(reaction))))));

		out.add(new ManualDoc.Heading(pageId + "#species",
				Component.translatable("manual.geometallurgy.rxn.species")));
		Set<String> species = new LinkedHashSet<>();
		for (MolBalance.Reactant r : reaction.reactants()) {
			species.add(r.id());
		}
		for (MolBalance.Product p : reaction.products()) {
			species.add(p.id());
		}
		for (String id : species) {
			if (Chemical.isKnown(id)) {
				out.add(new ManualDoc.Link(pageId + "#sp." + id, ManualTables.name(id), "app:" + SUB + id));
			} else {
				out.add(new ManualDoc.Text(pageId + "#sp." + id,
						Component.translatable("manual.geometallurgy.rxn.non_chemical", ManualTables.name(id))));
			}
		}

		out.add(new ManualDoc.Heading(pageId + "#path",
				Component.translatable("manual.geometallurgy.rxn.use")));
		String upstream = Data.upstreamPath(reaction);
		out.add(new ManualDoc.Text(pageId + "#up", upstream == null
				? Component.translatable("manual.geometallurgy.rxn.upstream_none")
				: Component.translatable("manual.geometallurgy.rxn.upstream", Component.literal(upstream))));
		String downstream = Data.downstreamPath(reaction);
		out.add(new ManualDoc.Text(pageId + "#down", downstream == null
				? Component.translatable("manual.geometallurgy.rxn.independent")
				: Component.translatable("manual.geometallurgy.rxn.downstream", Component.literal(downstream))));
		return out;
	}

	/** 完整方程：系数<b>全显</b>（1 也写出来）。{@code withNames} = 用译名，否则用化学式。 */
	static String fullEquation(MolBalance.Reaction reaction, boolean withNames) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < reaction.reactants().length; i++) {
			if (i > 0) {
				sb.append(" + ");
			}
			MolBalance.Reactant r = reaction.reactants()[i];
			sb.append(r.coeff()).append(' ')
					.append(withNames ? ManualTables.name(r.id()).getString() : formulaOf(r.id()));
		}
		sb.append("  ->  ");
		for (int i = 0; i < reaction.products().length; i++) {
			if (i > 0) {
				sb.append(" + ");
			}
			MolBalance.Product p = reaction.products()[i];
			sb.append(p.coeff()).append(' ')
					.append(withNames ? ManualTables.name(p.id()).getString() : formulaOf(p.id()));
		}
		return sb.toString();
	}

	/** 标签的英文枚举名串（搜索索引用：「oxide」「acid」也命中）。 */
	private static String tagIds(String id) {
		StringBuilder sb = new StringBuilder();
		for (ChemicalTags.Tag tag : ChemicalTags.tagsOf(id)) {
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(tag.name().toLowerCase(Locale.ROOT));
		}
		return sb.toString();
	}

	/** 一条反应的搜索语料：反应物 / 产物 id + 化学式式子 + 反应类型。 */
	private static String speciesTerms(MolBalance.Reaction reaction) {
		StringBuilder sb = new StringBuilder();
		for (MolBalance.Reactant r : reaction.reactants()) {
			sb.append(r.id()).append(' ');
		}
		for (MolBalance.Product p : reaction.products()) {
			sb.append(p.id()).append(' ');
		}
		sb.append(fullEquation(reaction, false)).append(' ').append(reaction.kind().name());
		return sb.toString();
	}

	/** 化学式；不是 {@link Chemical} 物种（桥接节点）就退回它的 id。 */
	private static String formulaOf(String id) {
		return Chemical.isKnown(id) ? Chemical.formulaOf(id) : id;
	}

	private static Component catalystText(MolBalance.Reaction reaction) {
		if (reaction.catalysts().length == 0) {
			return Component.translatable("gui.geometallurgy.recipe.none_value");
		}
		Component out = Component.empty();
		for (int i = 0; i < reaction.catalysts().length; i++) {
			String id = reaction.catalysts()[i];
			Component name = Chemical.isKnown(id)
					? ManualTables.name(id)
					: Component.translatableWithFallback("gui.geometallurgy.recipe.catalyst." + id, id);
			if (i > 0) {
				out = out.copy().append(Component.literal(" · "));
			}
			out = out.copy().append(name);
		}
		return out;
	}

	/**
	 * 电耗明细：溶液电解档是<b>逐反应</b>的 {@code fePerMol}，熔盐档走电解炉的逐配方基准。
	 */
	private static Component powerDetail(MolBalance.Reaction reaction) {
		if (reaction.kind() != MolBalance.Kind.ELECTROLYSIS) {
			return Component.translatable("manual.geometallurgy.rxn.power_plain",
					Component.translatable("manual.geometallurgy.spec.power"),
					Component.literal(ManualTables.power(reaction)));
		}
		Component solution = Component.translatable("manual.geometallurgy.rxn.power_solution",
				Component.literal(ManualTables.num(reaction.fePerMol())));
		Component molten = Component.translatable("manual.geometallurgy.rxn.molten",
				Component.literal(ManualTables.num(SmelterProfile.ELECTROLYSIS.energyPerTick())));
		return Component.translatable("manual.geometallurgy.rxn.power_plain",
				Component.translatable("manual.geometallurgy.spec.power"),
				Component.translatable("manual.geometallurgy.rxn.power_pair", solution, molten));
	}

	// ------------------------------------------------------------------ 现算数据（一次）

	/** 全附录共用的现算数据：一次算完，逐页只查表。 */
	private static final class Data {

		static final List<String> SUB_IDS = List.copyOf(Chemical.ids());
		static final List<MolBalance.Reaction> RXNS = List.copyOf(MolRecipes.all());
		/** 全部机器可用时、从矿根可达的节点（ReactionGraph 的现成 BFS）。 */
		static final Set<String> REACHED = Set.copyOf(ReactionGraph.reachableFrom(
				ReactionGraph.ORE_ROOTS, EnumSet.allOf(Machine.class)));

		/** 物质 id → 参与的反应下标（升序）。 */
		static final Map<String, List<Integer>> INVOLVED = new LinkedHashMap<>();
		/** 物质 id → 它作为反应物的反应下标。 */
		static final Map<String, List<Integer>> AS_REACTANT = new LinkedHashMap<>();
		/** 物质 id → 它作为产物的反应下标。 */
		static final Map<String, List<Integer>> AS_PRODUCT = new LinkedHashMap<>();

		/** 从矿根出发的推导深度与父节点（上游路径用）。 */
		static final Map<String, Integer> DEPTH = new HashMap<>();
		static final Map<String, String> PARENT = new HashMap<>();

		/** 能一路走到「金属锭」的节点（下游路径用）。 */
		static final Set<String> TOWARD_INGOT = new LinkedHashSet<>();
		static final Set<String> INGOTS = new LinkedHashSet<>();

		static {
			for (int i = 0; i < RXNS.size(); i++) {
				MolBalance.Reaction reaction = RXNS.get(i);
				Set<String> ids = new LinkedHashSet<>();
				for (MolBalance.Reactant r : reaction.reactants()) {
					ids.add(r.id());
					AS_REACTANT.computeIfAbsent(r.id(), unused -> new ArrayList<>()).add(i);
				}
				for (MolBalance.Product p : reaction.products()) {
					ids.add(p.id());
					AS_PRODUCT.computeIfAbsent(p.id(), unused -> new ArrayList<>()).add(i);
				}
				for (String id : ids) {
					INVOLVED.computeIfAbsent(id, unused -> new ArrayList<>()).add(i);
				}
			}

			// 上游：从矿根做一次超边 BFS（全部机器可用）。
			Deque<String> queue = new ArrayDeque<>();
			for (String root : ReactionGraph.ORE_ROOTS) {
				if (DEPTH.putIfAbsent(root, 0) == null) {
					queue.add(root);
				}
			}
			List<ReactionGraph.Edge> edges = ReactionGraph.allEdges();
			boolean changed = true;
			while (changed) {
				changed = false;
				for (ReactionGraph.Edge edge : edges) {
					int depth = 0;
					boolean ready = true;
					for (String in : edge.reactants()) {
						Integer d = DEPTH.get(in);
						if (d == null) {
							ready = false;
							break;
						}
						depth = Math.max(depth, d);
					}
					if (!ready) {
						continue;
					}
					for (String out : edge.products()) {
						if (DEPTH.containsKey(out)) {
							continue;
						}
						DEPTH.put(out, depth + 1);
						for (String in : edge.reactants()) {
							if (DEPTH.containsKey(in)) {
								PARENT.put(out, in);
								break;
							}
						}
						queue.add(out);
						changed = true;
					}
				}
			}
			queue.clear();

			// 下游：能走到金属锭的节点集合（反着做一次不动点）。
			for (String node : ReactionGraph.nodes()) {
				if (node.endsWith("_ingot")) {
					INGOTS.add(node);
					TOWARD_INGOT.add(node);
				}
			}
			changed = true;
			while (changed) {
				changed = false;
				for (ReactionGraph.Edge edge : edges) {
					if (edge.reactants().isEmpty() || edge.products().isEmpty()) {
						continue;
					}
					boolean all = true;
					for (String out : edge.products()) {
						if (!TOWARD_INGOT.contains(out)) {
							all = false;
							break;
						}
					}
					if (!all) {
						continue;
					}
					for (String in : edge.reactants()) {
						if (TOWARD_INGOT.add(in)) {
							changed = true;
						}
					}
				}
			}
		}

		static List<Integer> involved(String id) {
			return INVOLVED.getOrDefault(id, List.of());
		}

		static List<Integer> asReactant(String id) {
			return AS_REACTANT.getOrDefault(id, List.of());
		}

		static List<Integer> asProduct(String id) {
			return AS_PRODUCT.getOrDefault(id, List.of());
		}

		/** 物质常用度：2R + 3D + 10A。 */
		static int subScore(String id) {
			return 2 * involved(id).size() + 3 * asReactant(id).size()
					+ (REACHED.contains(id) ? 10 : 0);
		}

		static boolean allReactantsReached(MolBalance.Reaction reaction) {
			if (reaction.reactants().length == 0) {
				return false;
			}
			for (MolBalance.Reactant r : reaction.reactants()) {
				if (!REACHED.contains(r.id())) {
					return false;
				}
			}
			return true;
		}

		static boolean allProductsReached(MolBalance.Reaction reaction) {
			if (reaction.products().length == 0) {
				return false;
			}
			for (MolBalance.Product p : reaction.products()) {
				if (!REACHED.contains(p.id())) {
					return false;
				}
			}
			return true;
		}

		/** 产物参与反应数之和 ΣR（去重后的产物）。 */
		static int productInvolvement(MolBalance.Reaction reaction) {
			Set<String> ids = new LinkedHashSet<>();
			for (MolBalance.Product p : reaction.products()) {
				ids.add(p.id());
			}
			int sum = 0;
			for (String id : ids) {
				sum += involved(id).size();
			}
			return sum;
		}

		/** 反应常用度：20A + 8B + ΣR。 */
		static int rxnScore(int index) {
			MolBalance.Reaction reaction = RXNS.get(index);
			return (allReactantsReached(reaction) ? 20 : 0)
					+ (allProductsReached(reaction) ? 8 : 0)
					+ productInvolvement(reaction);
		}

		/**
		 * 上游链：矿根 → … → 该反应<b>拿到的第一件料</b>。
		 *
		 * <p>取「推导深度最小的反应物」，再从它沿父节点走回根 —— 这就是这条反应在
		 * 「矿 → 精矿 → 精矿粉 → 化合物 → 金属」上的接入点。接不上返回 {@code null}。
		 */
		static String upstreamPath(MolBalance.Reaction reaction) {
			String best = null;
			for (MolBalance.Reactant r : reaction.reactants()) {
				if (!DEPTH.containsKey(r.id())) {
					continue;
				}
				if (best == null || DEPTH.get(r.id()) < DEPTH.get(best)) {
					best = r.id();
				}
			}
			if (best == null) {
				return null;
			}
			List<String> path = new ArrayList<>();
			String cursor = best;
			path.add(cursor);
			int guard = 0;
			while (PARENT.containsKey(cursor) && guard++ < 512) {
				cursor = PARENT.get(cursor);
				path.add(cursor);
			}
			java.util.Collections.reverse(path);
			return join(path);
		}

		/**
		 * 下游链：该反应的产物 → … → 金属锭。
		 *
		 * <p>从产物里挑一个「能走到金属锭」的起点，再每次挑一条「全部产物都还能到锭」的超边
		 * 往前推。一步都走不动（独立化工反应）返回 {@code null}。
		 */
		static String downstreamPath(MolBalance.Reaction reaction) {
			String start = null;
			for (MolBalance.Product p : reaction.products()) {
				if (TOWARD_INGOT.contains(p.id())) {
					start = p.id();
					break;
				}
			}
			if (start == null) {
				return null;
			}
			List<String> path = new ArrayList<>();
			String cursor = start;
			path.add(cursor);
			int guard = 0;
			while (!INGOTS.contains(cursor) && guard++ < 512) {
				String next = null;
				for (ReactionGraph.Edge edge : ReactionGraph.allEdges()) {
					if (!edge.reactants().contains(cursor)) {
						continue;
					}
					String candidate = null;
					for (String out : edge.products()) {
						if (TOWARD_INGOT.contains(out)) {
							candidate = out;
							break;
						}
					}
					if (candidate != null && !path.contains(candidate)) {
						next = candidate;
						break;
					}
				}
				if (next == null) {
					break;
				}
				cursor = next;
				path.add(cursor);
			}
			return join(path);
		}

		private static String join(List<String> path) {
			StringBuilder sb = new StringBuilder();
			for (String id : path) {
				if (sb.length() > 0) {
					sb.append("  →  ");
				}
				sb.append(ManualTables.name(id).getString());
			}
			return sb.toString();
		}
	}
}
