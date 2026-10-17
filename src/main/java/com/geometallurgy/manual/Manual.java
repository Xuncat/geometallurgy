package com.geometallurgy.manual;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.geometallurgy.Geometallurgy;

/**
 * 任务书的内容表。
 *
 * <h2>代码里只有「有哪些章、哪些条」，正文一个字都没有</h2>
 * 正文全部放在语言文件里（{@code manual.geometallurgy.<章>.<条>.l<段>}），
 * 每一条是<b>一个自然段</b>，段数由代码<b>读出来</b>：从 {@code .l1} 开始连续取，
 * 取到某个键在语言文件里不存在为止。
 *
 * <p>这么做是为了消灭「代码里写 12 行、语言文件里只写了 11 行」这类对不上号的问题 ——
 * 一边改一个字、另一边忘了同步，界面上就会少一行或多一行空白，而且不报错。
 * 现在唯一的真相来源就是语言文件。
 *
 * <p><b>但「读出来」这件事不能问游戏要翻译</b>（2026-10-14 改）：见
 * {@link com.geometallurgy.client.ManualText} —— 缺的键会先落到 {@code en_us}，
 * 于是中文书里会冒出英文段；两边都没有时，26.x 解出来的又不稳定是键名本身，
 * 那个循环就会一路数到 {@link #MAX_PARAGRAPHS}，条目凭空多出几十个空段、页码翻几倍。
 * 现在段数改成直接读玩家当前语言的语言文件，本类只负责拼键。
 *
 * <h2>分页也是算出来的</h2>
 * 作者不指定分页：界面拿到自然段后按页宽<b>自动折行</b>，再按每页能放几行切页。
 * 这样中文一行 30 字、英文一行 45 字符都能排得下，不必让作者去数格子。
 *
 * <h2>加内容怎么做</h2>
 * 本模组自己加一节 = 在 {@link #BUILT_IN} 里加一条 + 在语言文件里加对应的键，
 * 不用动界面代码。
 *
 * <h2>附属模组怎么往这本书里加一章</h2>
 * 调 {@link #registerChapter(Chapter)}，像 IE 的附属往工程师手册里加页一样。
 * 正文与定义都留在附属模组自己那边，主模组不认识它 —— 依赖方向只有一个：
 * 附属依赖主模组（见 {@code geometallurgy_guns} 的 {@code fabric.mod.json}）。
 *
 * <p>为了做到这一点，{@link Chapter} 与 {@link Entry} 各留了一个「外来户」的口子：
 * <ul>
 *   <li>{@link Chapter#keyRoot()} —— 章可以自带语言键前缀，
 *       正文就能写在附属模组自己的命名空间下，而不是硬挤进 {@code manual.geometallurgy.}；</li>
 *   <li>{@link Entry#namespace()} —— 条目可以指明配方与产物属于哪个命名空间。
 *       不指明就是本模组（{@link Geometallurgy#MOD_ID}），内置章一个字都不用改。</li>
 * </ul>
 *
 * <p>注册时机：Fabric 的 {@code onInitialize} 顺序是「依赖先初始化」，
 * 所以附属模组在主模组之后初始化，注册一定发生在玩家翻开书之前。
 * 为了不依赖这条假设，注册时会<b>立刻</b>重建合并列表与配方条目表 ——
 * 注册完再读，读到的就是新的。
 */
public final class Manual {

	private Manual() {
	}

	/** 语言键前缀。 */
	public static final String ROOT = "manual.geometallurgy.";

	/** 一条最多读多少段 —— 防止语言文件写错时无限读下去。 */
	public static final int MAX_PARAGRAPHS = 80;

	/**
	 * 一条目。
	 *
	 * @param namespace 这条条目的配方与产物属于哪个命名空间。
	 *                  内置条目一律是本模组（见下面那个只收 id 与 recipe 的构造器）；
	 *                  附属模组可以指明自己的，于是书上能画出它自己的配方与产物。
	 * @param id        条目标识，正文键由它拼出来
	 * @param recipe    这条条目要展示的合成配方 id（{@code namespace} 下的路径），
	 *                  没有就传 {@code null}。
	 *                  配方内容<b>不写在这里</b> —— 界面直接去游戏的配方管理器里读，
	 *                  所以书上画的网格永远和真实配方一致，不会出现抄错或改了配方忘了改书。
	 */
	public record Entry(String namespace, String id, String recipe) {

		/**
		 * 内置条目的写法：命名空间固定是本模组。
		 *
		 * <p>留着这个两参数构造器，是为了让内置的四部分一个字都不用改 ——
		 * 它们写的 {@code entry("intro")} 与 {@code recipe("crusher", "crusher")} 仍然照旧工作。
		 */
		public Entry(String id, String recipe) {
			this(Geometallurgy.MOD_ID, id, recipe);
		}
	}

	/**
	 * 一章。
	 *
	 * @param id      章标识
	 * @param entries 章内条目，顺序就是列表顺序
	 * @param keyRoot 这一章的语言键前缀。内置章是 {@link #ROOT}；
	 *                附属模组可以传自己的（例如 {@code manual.geometallurgy_guns.}），
	 *                正文就真的留在附属模组那边了。
	 */
	public record Chapter(String id, List<Entry> entries, String keyRoot) {

		/** 内置章的写法：语言键前缀固定是 {@link Manual#ROOT}。 */
		public Chapter(String id, List<Entry> entries) {
			this(id, entries, Manual.ROOT);
		}
	}

	// ------------------------------------------------------------------ 章节注册表
	//
	// 内置的四部分是常量，附属模组注册的章追加在它们之后（内置章永远在前）。
	// 注册是「先注册、后读」那条路：注册时就重建合并列表，所以不存在
	// 「读的时候才发现还没合并」这种时序问题。

	/**
	 * 本模组的四大部分 —— <b>前言 / 工业化，开始！ / 化学，进阶！ / 附录</b>。
	 *
	 * <h2>为什么是四部分（2026-10-18 重排）</h2>
	 * 用户口径：把任务书按「读法」重新分成四部分，每部分若干页，页 = 一个条目。
	 * 界面是一本摊开的书：书脊右侧四个部分各一枚书签、第一跨是可点的目录（见
	 * {@code client/ManualScreen} 与 {@code client/ManualPager}）。
	 * <ul>
	 *   <li><b>前言</b> —— 虚拟矿脉的生成规则（{@code (世界种子, 坐标, 纪元)} 的纯函数，不写进区块），
	 *       分布 / 形状 / 相对丰度 / 稀有度的口径，以及<b>34 种矿物表</b>；</li>
	 *   <li><b>工业化，开始！</b> —— 每台机器的输入输出 / 耗电 / 面角色 / 槽位，
	 *       外加一条「矿 → 精矿 → 精矿粉 → 化合物 → 金属锭 → 金属粉」的工序线；</li>
	 *   <li><b>化学，进阶！</b> —— 冶金三条路线（还原 / 电解 / 置换）与化工
	 *       （酸碱盐链、逐反应电解单价、速率与平衡、焙烧归还原炉）；</li>
	 *   <li><b>附录</b> —— 79 种物质表、107 条反应表、耗电一览、科技线入口。</li>
	 * </ul>
	 *
	 * <h2>正文在语言文件，数据表在代码里现算</h2>
	 * 本类只管「有哪些部分、哪些页、页里挂不挂合成配方」；
	 * 正文由 {@code client/ManualText} 从当前语言的语言文件里数段读出，
	 * <b>数据表</b>由 {@code client/ManualTables} 从 {@code proto/OreMineral}、{@code proto/OreTier}、
	 * {@code chemical/Chemical}、{@code chemical/MolRecipes}、各机器常量现算 ——
	 * 两边都没有手抄的数字。
	 *
	 * <p>带 {@code recipe} 的页会在正文与数据表之后多画一张合成网格（配方当场从配方管理器读）。
	 */
	private static final List<Chapter> BUILT_IN = List.of(
			new Chapter("preface", List.of(
					// 一、前言：矿脉是什么、怎么分布、有多少种
					entry("intro"),
					entry("rule"),
					entry("deposit"),
					entry("abundance"),
					entry("ores"),
					recipe("prospect", "mineral_prospector"))),
			new Chapter("industry", List.of(
					// 二、工业化：先看整条线，再一台台机器看
					entry("flow"),
					entry("solid"),
					entry("fluid"),
					recipe("faces", "engineering_configurator"),
					recipe("generator", "coal_generator"),
					recipe("drill", "drill_bit_iron"),
					recipe("vein_extractor", "vein_extractor"),
					recipe("fluid_extractor", "fluid_vein_extractor"),
					recipe("crusher", "crusher"),
					recipe("separator", "magnetic_separator"),
					recipe("centrifuge", "centrifuge"),
					recipe("vessel", "reaction_vessel"),
					recipe("mixer", "mixer"),
					recipe("reduction", "reduction_furnace"),
					recipe("electrolysis", "electrolysis_furnace"),
					recipe("displacement", "displacement_furnace"),
					recipe("electrode", "carbon_rod"),
					entry("casting"),
					recipe("bucket", "metal_bucket"),
					recipe("battery", "battery"),
					recipe("sensor", "wireless_sensor"))),
			new Chapter("chem", List.of(
					// 三、化学：冶金三条路线 + 化工
					entry("routes"),
					entry("chemistry"),
					entry("acidbase"),
					entry("kinetics"),
					entry("roasting"))),
			new Chapter("appendix", List.of(
					// 四、附录：全部由代码现算
					entry("substances"),
					entry("reactions"),
					entry("power"),
					entry("techtree"))));

	/** 附属模组注册进来的章，按注册顺序追加在内置章之后。 */
	private static final List<Chapter> EXTERNAL = new ArrayList<>();

	/** 内置章 + 已注册的外部章。注册时重建。 */
	private static volatile List<Chapter> merged = BUILT_IN;

	/** 合并列表里挂了配方的条目，顺序与界面要的配方载荷一致。 */
	private static volatile List<Entry> recipeEntries = buildRecipeEntries(BUILT_IN);

	/**
	 * 注册一章。<b>给附属模组用的扩展点</b>。
	 *
	 * <p>内置章永远在前，外部章按注册顺序接在后面 —— 玩家已经记住的目录
	 * 不会因为装了一个附属模组就整体错位。
	 *
	 * <p>注册时立刻重建合并列表与配方条目表：读的时候不必再判「有没有注册过」。
	 * 重建只换列表本身，<b>不换 {@link Entry} 对象</b> ——
	 * {@link #recipeIndexOf(Entry)} 用的是引用比较（见它的注释），
	 * 把对象换掉会让界面上那张网格指到隔壁条目去。
	 */
	public static synchronized void registerChapter(Chapter chapter) {
		if (chapter == null) {
			return;
		}
		EXTERNAL.add(chapter);

		List<Chapter> out = new ArrayList<>(BUILT_IN.size() + EXTERNAL.size());
		out.addAll(BUILT_IN);
		out.addAll(EXTERNAL);
		List<Chapter> snapshot = List.copyOf(out);

		merged = snapshot;
		recipeEntries = buildRecipeEntries(snapshot);
	}

	/** 全部章节：内置章在前，附属模组注册的在后。 */
	public static List<Chapter> chapters() {
		return merged;
	}

	/** 所有挂了配方的条目，按遍历顺序排好。 */
	public static List<Entry> recipeEntries() {
		return recipeEntries;
	}

	private static Entry entry(String id) {
		return new Entry(id, null);
	}

	/** 带一张合成网格的条目。{@code recipe} 是模组命名空间下的配方路径。 */
	private static Entry recipe(String id, String recipe) {
		return new Entry(id, recipe);
	}

	// ------------------------------------------------------------------ 配方载荷
	//
	// 配方内容不写在书里：客户端根本没有按 id 取配方的能力（ClientRecipeContainer
	// 只有切石机配方和属性集），所以由服务端读真实的配方管理器、把 9 格原料 + 1 格产物
	// 打包进开屏数据发过来。这样书上画的一定就是玩家真能合出来的东西。
	//
	// 载荷是一维的：第 i 条配方占 [i*SLOTS, (i+1)*SLOTS)，顺序与 recipeEntries() 一致。

	/** 一条配方占 10 格：9 格合成格 + 1 格产物。 */
	public static final int RECIPE_SLOTS = 10;

	private static List<Entry> buildRecipeEntries(List<Chapter> chapters) {
		List<Entry> out = new ArrayList<>();
		for (Chapter chapter : chapters) {
			for (Entry entry : chapter.entries()) {
				if (entry.recipe() != null) {
					out.add(entry);
				}
			}
		}
		return List.copyOf(out);
	}

	/**
	 * 这条条目在配方载荷里的下标；没有配方返回 {@code -1}。
	 *
	 * <p>用**引用**比较而不是 {@code equals}：record 的相等只看字段，
	 * 万一两处写了同名同配方的条目就会被判成同一条，指到隔壁那格去。
	 */
	public static int recipeIndexOf(Entry entry) {
		List<Entry> entries = recipeEntries;
		for (int i = 0; i < entries.size(); i++) {
			if (entries.get(i) == entry) {
				return i;
			}
		}
		return -1;
	}

	// ------------------------------------------------------------------ 取内容
	//
	// 这里只拼键、不发问。段数怎么数、哪一段算「这一条没有」，
	// 全在 client 的 ManualText 里 —— 它读的是玩家当前语言的语言文件。

	/** 章标题的键 —— 前缀由章自己带（内置章是 {@link #ROOT}）。 */
	public static String chapterTitleKey(Chapter chapter) {
		return chapter.keyRoot() + chapter.id() + ".title";
	}

	public static String titleKey(Chapter chapter, Entry entry) {
		return chapter.keyRoot() + chapter.id() + "." + entry.id() + ".title";
	}

	/** 一条目的第 {@code index} 段（从 1 起）的语言键。 */
	public static String paragraphKey(Chapter chapter, Entry entry, int index) {
		return chapter.keyRoot() + chapter.id() + "." + entry.id() + ".l" + index;
	}

	/**
	 * 这一章的语言键前缀（{@code manual.<命名空间>.}）里的命名空间。
	 *
	 * <p>正文与配方都在命名空间下找，所以附属模组注册章时只要给对
	 * {@link Chapter#keyRoot()}，界面就知道该去谁的 {@code lang/} 里翻。
	 */
	public static String namespaceOf(Chapter chapter) {
		String root = chapter.keyRoot();
		int from = root.indexOf('.') + 1;
		int to = root.lastIndexOf('.');
		return from > 0 && to > from ? root.substring(from, to) : Geometallurgy.MOD_ID;
	}

	/** 手册用到的全部命名空间（内置的是本模组，附属模组带自己的）。 */
	public static Set<String> namespaces() {
		Set<String> out = new LinkedHashSet<>();
		for (Chapter chapter : merged) {
			out.add(namespaceOf(chapter));
		}
		return out;
	}
}
