package com.geometallurgy.chemical;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 反应表的<b>超边图</b>（派生索引，类初始化时从 {@link MolRecipes#all()} 建一次）。
 *
 * <p>建模口径：
 * <ul>
 *   <li><b>节点 = 物质</b>：{@link Chemical} 里的每一条，外加矿世界里的原矿 / 精矿节点
 *       （见 {@link #ORE_ROOTS} 与桥接表）——「矿也是节点」。</li>
 *   <li><b>超边 = 反应</b>：一条边有多个反应物（入节点）与多个产物（出节点），
 *       不是一个节点到另一个节点的普通有向边。{@link MolRecipes} 的每条反应是一条超边，
 *       每条边带 {@code Set<Machine>} 属性表示「哪些机器能跑它」。</li>
 *   <li><b>桥接表</b>：矿世界（{@code proto/OreMineral}、{@code slurry/}）与化学世界之间的
 *       接缝，收录「矿 → 精矿」与「精矿 / 矿物 → 真实离子物种」这类<b>机器步骤</b>超边。
 *       故意保持很小、可手改。</li>
 * </ul>
 *
 * <p><b>为什么不用稠密对称矩阵</b>：一条反应可以同时吃 2 个反应物、吐 3 个产物
 * （超边），稠密的 species×species 矩阵表达不了「一条边牵动多个节点」，
 * 而且没地方挂机器属性。这里存的是<b>稀疏关联</b>（每条边自己的反应物/产物列表），
 * 再从它派生 CSR 风格的倒排索引：
 * <ul>
 *   <li>{@link #edgesAsReactant(String)} —— 该物质作为<b>反应物</b>出现在哪些边；</li>
 *   <li>{@link #edgesAsProduct(String)} —— 该物质作为<b>产物</b>出现在哪些边；</li>
 *   <li>{@link #edgesFor(Machine)} —— 某台机器能跑哪些边。</li>
 * </ul>
 * 三个索引存的是 int[]（行 = key，值 = 边下标），边下标与 {@link #allEdges()} 对齐。
 *
 * <p><b>启动审计</b>：类初始化时从矿根 + 全机器跑一次可达性，INFO 一行摘要、
 * WARN 列出到不了的化学物质与非化学节点。它只是报告（TODO 清单），<b>从不抛异常</b>。
 */
public final class ReactionGraph {

	private static final Logger LOGGER = LoggerFactory.getLogger("geometallurgy");

	private ReactionGraph() {
	}

	/** 空数组常量（查不到的 key 返回它）。 */
	private static final int[] NONE = new int[0];

	/**
	 * 一条超边。
	 *
	 * @param id        稳定 id（化学边 {@code r1..rN} 按反应表顺序；桥接边 {@code bridge:*}）
	 * @param label     人类可读的「A + B -> C」（仅日志/调试用）
	 * @param machines  能跑这条边的机器（不可为空，任一命中即可跑）
	 * @param reactants 入节点（全部可达这条边才可跑）
	 * @param products  出节点（边可跑时这些节点变为可达）
	 * @param chemical  true = 来自 {@link MolRecipes}；false = 来自桥接表
	 */
	public record Edge(String id, String label, Set<Machine> machines, List<String> reactants,
			List<String> products, boolean chemical) {
	}

	// ------------------------------------------------------------------ 桥接表（矿 → 化学的接缝）

	/**
	 * 生存起点（矿根）：这些节点在开局就可达。其余节点必须由超边推导出来。
	 *
	 * <p>取值是矿世界里的 id（与 {@code proto/OreMineral} 的 {@code id()} 对齐）：
	 * 主矿的 id 直接写在这里，避免把 Minecraft 的 {@code Component} 依赖拖进这张派生图。
	 * 加/减矿种 = 改这一行。
	 *
	 * <p>{@code water} 不是矿，但「手持桶右键水源」是已实现的能力（见 {@code MetalBucket}），
	 * 所以它是实打实的生存起点，列入根集，否则 {@code 2H2O → 2H2 + O2} 这条链会被误报。
	 */
	public static final List<String> ORE_ROOTS = List.of(
			"magnetite", "hematite", "limonite",          // 铁
			"pyrolusite", "rhodochrosite",                // 锰
			"native_copper",                               // 铜（自然铜；金属粉路线要用）
			"sphalerite",                                  // 锌（闪锌矿；金属粉路线要用）
			"bauxite",                                     // 铝
			"quartz",                                      // 硅
			"fluorite",                                    // 氟
			"apatite",                                     // 磷
			"bastnasite", "monazite", "xenotime",          // 稀土
			"magnesite",                                   // 镁（菱镁矿，OreMineral）
			"graphite",                                    // 碳（石墨，OreMineral；碳热还原的固体碳源）
			"halite",                                      // 钠（岩盐：rock_salt 岩体的脉石，直接开采）
			"sylvite",                                     // 钾（钾盐：rock_salt 岩体的脉石之一，KCl）
			"natratine",                                   // 钠硝石 NaNO3（岩盐 / 硝石脉的脉石；氮族矿源）
			"niter",                                       // 钾硝石 KNO3（石膏质蒸发岩的脉石；氮族矿源）
			"sal_ammoniac",                                // 硇砂 NH4Cl（玄武岩质火山岩的脉石；氨的矿源）
			"sulfur",                                      // 自然硫（矿 id 与化学物质 id 同名，同一个节点）
			"water");                                      // 桶取自水源
	// 注：上一轮的空气分离环境根（air）已按用户口径删除——空气不是矿物，也不是 Chemical 物种；
	// 氮改由硝石 / 硇砂经桥接边与 MolRecipes 的氮族反应得到。

	/**
	 * 桥接表：把「矿 / 精矿」节点接到化学世界上。这是选矿与化学之间唯一的手写接缝，
	 * 故意保持很小、可手改；要补一条机器步骤，就往 {@link #bridge} 里加一行，id 只需唯一。
	 *
	 * <p>五段：
	 * <ol>
	 *   <li><b>矿 → 精矿</b>（抽取机 / 磁筛机 / 离心机 / 粉碎机）：选矿段；</li>
	 *   <li><b>精矿 → 矿粉</b>（粉碎机）：只把颗粒磨细，成分照搬，不产生 mol 物种；</li>
	 *   <li><b>矿粉 / 矿物 → 化合物</b>：萤石 → CaF2、铝土矿 → Al(OH)3、菱镁矿 → MgCO3、
	 *       磷灰石 → Ca3(PO4)2、岩盐 → NaCl、石墨 → C，以及铁 / 锰 / 稀土精矿的焙烧氧化物。
	 *       机器属性按操作类型取现有枚举：焙烧（roasting）走反应釜热档，
	 *       浸出 / 结晶（leaching / crystallisation）走离心机，提纯走粉碎机；</li>
	 *   <li><b>化合物 → 金属锭</b>（还原炉 / 熔盐电解）：{@link Machine#REDUCTION_FURNACE}
	 *       与 {@link Machine#ELECTROLYSIS_FURNACE}。铜 / 锌没有化合物物种：自然铜熔炼、
	 *       闪锌矿火法还原，所以直接从矿给「矿 → 金属锭」；</li>
	 *   <li><b>金属锭 → 金属粉</b>（粉碎机）：这是 {@link Chemical} 金属单质物种的<b>唯一</b>
	 *       入口；金属粉就是 mol 物种本身（见 {@code metal/PowderContent}）。</li>
	 * </ol>
	 *
	 * <p>上一批 TODO（氧化钠 / 硝酸 / NOx / 高锰酸钾 / 氮源）已由 {@link MolRecipes} 的
	 * 氮族与锰酸盐反应 + 硝石 / 硇砂矿源闭合（见 docs/科技线-v1.md §十七）。
	 */
	private static List<Edge> buildBridge() {
		List<Edge> out = new ArrayList<>();
		Machine extract = Machine.VEIN_EXTRACTOR;
		Machine magnetic = Machine.MAGNETIC_SEPARATOR;
		Machine crusher = Machine.CRUSHER;
		Machine centrifuge = Machine.CENTRIFUGE;
		Machine vessel = Machine.REACTION_VESSEL;
		Machine reduction = Machine.REDUCTION_FURNACE;
		Machine electrolysis = Machine.ELECTROLYSIS_FURNACE;

		// 第一段：矿 → 精矿（选矿）
		// 铁：磁铁矿 / 赤铁矿 / 褐铁矿 → 铁精矿
		out.add(bridge("iron-concentrate-magnetite", "magnetite", "iron_concentrate", extract, magnetic));
		out.add(bridge("iron-concentrate-hematite", "hematite", "iron_concentrate", extract, magnetic));
		out.add(bridge("iron-concentrate-limonite", "limonite", "iron_concentrate", extract, magnetic));

		// 锰：软锰矿 / 菱锰矿 → 锰精矿
		out.add(bridge("mn-concentrate-pyrolusite", "pyrolusite", "manganese_concentrate", extract, magnetic));
		out.add(bridge("mn-concentrate-rhodochrosite", "rhodochrosite", "manganese_concentrate", extract, magnetic));

		// 铝：铝土矿 → 氧化铝精矿（拜耳法前段：碱溶 → 离心分离）
		out.add(bridge("alumina-concentrate", "bauxite", "alumina_concentrate", extract, centrifuge));

		// 硅：石英脉 → 硅精矿
		out.add(bridge("silica-concentrate", "quartz", "silica_concentrate", extract, crusher));

		// 氟：萤石 → 氟石精矿（氟化工的起点）
		out.add(bridge("fluorite-concentrate", "fluorite", "fluorite_concentrate", extract, magnetic));

		// 磷：磷灰石 → 磷精矿
		out.add(bridge("phosphate-concentrate", "apatite", "phosphate_concentrate", extract, centrifuge));

		// 稀土：氟碳铈矿 / 独居石 / 磷钇矿 → 稀土精矿
		out.add(bridge("re-concentrate-bastnasite", "bastnasite", "rare_earth_concentrate", extract, magnetic));
		out.add(bridge("re-concentrate-monazite", "monazite", "rare_earth_concentrate", extract, magnetic));
		out.add(bridge("re-concentrate-xenotime", "xenotime", "rare_earth_concentrate", extract, magnetic));

		// ---------------------------------------------------------------- 第二段：精矿 → 矿粉（粉碎机）
		// 精矿是选矿的富集料，进炉前先磨成粉。粉碎机只把颗粒磨细、成分一个不少地照搬，
		// 所以这一步是物理步骤，不产生任何 mol 物种。
		out.add(bridge("iron-concentrate-crush", "iron_concentrate", "iron_concentrate_powder", crusher));
		out.add(bridge("mn-concentrate-crush", "manganese_concentrate", "manganese_concentrate_powder", crusher));
		out.add(bridge("alumina-concentrate-crush", "alumina_concentrate", "alumina_concentrate_powder", crusher));
		out.add(bridge("silica-concentrate-crush", "silica_concentrate", "silica_concentrate_powder", crusher));
		out.add(bridge("fluorite-concentrate-crush", "fluorite_concentrate", "fluorite_concentrate_powder", crusher));
		out.add(bridge("phosphate-concentrate-crush", "phosphate_concentrate", "phosphate_concentrate_powder", crusher));
		out.add(bridge("re-concentrate-crush", "rare_earth_concentrate", "rare_earth_concentrate_powder", crusher));

		// ---------------------------------------------------------------- 第三段：矿粉 / 矿物 → 化合物（焙烧 / 浸出 / 结晶）
		// 机器属性按操作类型取现有枚举 —— 焙烧走反应釜热档；浸出 / 结晶走离心机；
		// 石英 / 石墨提纯走粉碎机。
		out.add(bridge("iron-oxide", "iron_concentrate_powder", "iron_oxide", vessel));
		out.add(bridge("manganese-dioxide", "manganese_concentrate_powder", "manganese_dioxide", vessel));
		out.add(bridge("aluminum-hydroxide", "alumina_concentrate_powder", "aluminum_hydroxide", centrifuge));
		out.add(bridge("silicon-dioxide", "silica_concentrate_powder", "silicon_dioxide", crusher));
		out.add(bridge("calcium-fluoride", "fluorite_concentrate_powder", "calcium_fluoride", centrifuge));
		out.add(bridge("calcium-phosphate", "phosphate_concentrate_powder", "calcium_phosphate", centrifuge));
		out.add(bridge("rare-earth-oxide", "rare_earth_concentrate_powder", "rare_earth_oxide", vessel));

		// 矿 → 化合物 的第二类：没有精矿节点的矿物，浸出 / 结晶直接成盐（含碳：石墨 → 固体碳）。
		// 碳是磷炉的还原剂，石墨本身就是碳，粉碎提纯即可。
		out.add(bridge("magnesium-carbonate", "magnesite", "magnesium_carbonate", centrifuge));
		out.add(bridge("sodium-chloride", "halite", "sodium_chloride", centrifuge));
		out.add(bridge("potassium-chloride", "sylvite", "potassium_chloride", centrifuge));
		out.add(bridge("sodium-nitrate", "natratine", "sodium_nitrate", centrifuge));
		out.add(bridge("potassium-nitrate", "niter", "potassium_nitrate", centrifuge));
		out.add(bridge("ammonium-chloride", "sal_ammoniac", "ammonium_chloride", centrifuge));
		out.add(bridge("carbon", "graphite", "carbon", crusher));

		// ---------------------------------------------------------------- 第四段：化合物 → 金属锭（还原炉 / 熔盐电解）
		// 这一步才真正把金属从化合物里取出来。熔盐电解复用已有的 ELECTROLYSIS_FURNACE；
		// 锰 / 硅两条路（碳热还原 + 熔盐电解）都真实存在，所以标两台炉子（任一命中即可）。
		out.add(bridge("iron-ingot", "iron_oxide", "iron_ingot", reduction));
		out.add(bridge("manganese-ingot", "manganese_dioxide", "manganese_ingot", reduction, electrolysis));
		out.add(bridge("aluminum-ingot", "aluminum_oxide", "aluminum_ingot", electrolysis));
		out.add(bridge("silicon-ingot", "silicon_dioxide", "silicon_ingot", reduction, electrolysis));
		// 铜 / 锌没有化合物节点：自然铜已是单质（熔炼即可），闪锌矿走火法还原（先焙烧再还原）。
		out.add(bridge("copper-ingot", "native_copper", "copper_ingot", reduction));
		out.add(bridge("zinc-ingot", "sphalerite", "zinc_ingot", reduction));

		// ---------------------------------------------------------------- 第五段：金属锭 → 金属粉（粉碎机）
		// 金属锭在粉碎机里磨成金属粉，粉才是 Chemical 里的金属单质物种（metal/PowderContent）。
		// 这里起，金属单质才第一次出现在 mol 世界里，规则 5（金属 + 酸）才有入口。
		out.add(bridge("iron-powder", "iron_ingot", "iron", crusher));
		out.add(bridge("manganese-powder", "manganese_ingot", "manganese", crusher));
		out.add(bridge("aluminum-powder", "aluminum_ingot", "aluminum", crusher));
		out.add(bridge("copper-powder", "copper_ingot", "copper", crusher));
		out.add(bridge("zinc-powder", "zinc_ingot", "zinc", crusher));

		return out;
	}

	/** 建一条「单进单出」的桥接超边。 */
	private static Edge bridge(String id, String from, String to, Machine... machines) {
		return new Edge("bridge:" + id, from + " -> " + to, Set.of(machines),
				List.of(from), List.of(to), false);
	}

	// ------------------------------------------------------------------ 派生数据

	/** 全部超边：先化学（反应表顺序），后桥接。索引数据结构以此为基准。 */
	private static final List<Edge> EDGES;
	/** 节点 id → 它作为反应物出现的边下标（CSR 行）。 */
	private static final Map<String, int[]> REACTANT_EDGES;
	/** 节点 id → 它作为产物出现的边下标（CSR 行）。 */
	private static final Map<String, int[]> PRODUCT_EDGES;
	/** 机器 → 它能跑的边下标（CSR 行）。 */
	private static final Map<Machine, int[]> MACHINE_EDGES;
	/** 全部节点（化学物质 + 非化学节点）。 */
	private static final Set<String> NODES;

	private static final int CHEMICAL_EDGE_COUNT;
	private static final int BRIDGE_EDGE_COUNT;

	static {
		List<Edge> edges = new ArrayList<>();
		List<MolBalance.Reaction> recipes = MolRecipes.all();
		for (int i = 0; i < recipes.size(); i++) {
			MolBalance.Reaction r = recipes.get(i);
			List<String> ins = new ArrayList<>();
			for (MolBalance.Reactant x : r.reactants()) {
				ins.add(x.id());
			}
			List<String> outs = new ArrayList<>();
			for (MolBalance.Product p : r.products()) {
				outs.add(p.id());
			}
			edges.add(new Edge("r" + (i + 1), label(ins, outs), Set.copyOf(r.machines()),
					List.copyOf(ins), List.copyOf(outs), true));
		}
		CHEMICAL_EDGE_COUNT = edges.size();

		List<Edge> bridge = buildBridge();
		edges.addAll(bridge);
		BRIDGE_EDGE_COUNT = bridge.size();

		EDGES = List.copyOf(edges);
		REACTANT_EDGES = buildNodeIndex(EDGES, true);
		PRODUCT_EDGES = buildNodeIndex(EDGES, false);
		MACHINE_EDGES = buildMachineIndex(EDGES);

		LinkedHashSet<String> nodes = new LinkedHashSet<>(Chemical.ids());
		nodes.addAll(ORE_ROOTS);
		for (Edge e : EDGES) {
			nodes.addAll(e.reactants());
			nodes.addAll(e.products());
		}
		NODES = Collections.unmodifiableSet(nodes);

		audit();
	}

	/** 触发类加载（{@code Geometallurgy.onInitialize} 调用），让启动审计必定跑一次。 */
	public static void init() {
		// 空方法；类初始化块已经建好图并打完审计。
	}

	// ------------------------------------------------------------------ 查询

	/** 全部超边（只读，顺序稳定）。 */
	public static List<Edge> allEdges() {
		return EDGES;
	}

	/** 全部节点（化学物质 + 非化学节点，只读）。 */
	public static Set<String> nodes() {
		return NODES;
	}

	/** 非化学节点（矿 / 精矿 / 中间节点）子集。 */
	public static Set<String> nonChemicalNodes() {
		LinkedHashSet<String> out = new LinkedHashSet<>();
		for (String id : NODES) {
			if (!Chemical.isKnown(id)) {
				out.add(id);
			}
		}
		return Collections.unmodifiableSet(out);
	}

	/** 该物质作为<b>反应物</b>出现在哪些边（CSR 行；不认识返回空数组）。 */
	public static int[] edgesAsReactant(String speciesId) {
		return REACTANT_EDGES.getOrDefault(speciesId, NONE);
	}

	/** 该物质作为<b>产物</b>出现在哪些边（CSR 行；不认识返回空数组）。 */
	public static int[] edgesAsProduct(String speciesId) {
		return PRODUCT_EDGES.getOrDefault(speciesId, NONE);
	}

	/** 某台机器能跑哪些边（CSR 行）。 */
	public static int[] edgesFor(Machine machine) {
		return MACHINE_EDGES.getOrDefault(machine, NONE);
	}

	/** 第 i 条边；越界抛 {@link IndexOutOfBoundsException}。 */
	public static Edge edge(int i) {
		return EDGES.get(i);
	}

	// ------------------------------------------------------------------ 可达性（超边 BFS）

	/**
	 * 从 {@code roots} 出发，在「机器集命中 {@code available}」的超边上做 BFS。
	 *
	 * <p>超边的可跑条件：<b>每一个反应物都可达</b>（AND 入边），且<b>至少一台机器可用</b>
	 * （OR 机器，按任务口径「至少一个命中即可」）。可跑后，所有产物变为可达。
	 * 迭代到不动点为止（产物还能再触发别的边）。
	 *
	 * <p>催化剂不建模：反应表把催化剂当「存在性条件、不消耗」，且像 {@code light} /
	 * {@code vanadium_pentoxide} 并不是 {@link Chemical} 物质。这是本审计的已知盲区。
	 *
	 * @param roots     起点节点（可为空 / null）
	 * @param available 可用机器（null 视作空集：只有根可达）
	 * @return 可达节点集合（含 roots），顺序为发现顺序
	 */
	public static Set<String> reachableFrom(Collection<String> roots, Set<Machine> available) {
		LinkedHashSet<String> reached = new LinkedHashSet<>();
		if (roots != null) {
			for (String r : roots) {
				if (r != null) {
					reached.add(r);
				}
			}
		}
		Set<Machine> machines = available == null ? Set.of() : available;
		boolean[] fired = new boolean[EDGES.size()];
		boolean changed = true;
		while (changed) {
			changed = false;
			for (int i = 0; i < EDGES.size(); i++) {
				if (fired[i]) {
					continue;
				}
				Edge e = EDGES.get(i);
				if (!canRun(e, machines)) {
					continue;
				}
				boolean ready = true;
				for (String in : e.reactants()) {
					if (!reached.contains(in)) {
						ready = false;
						break;
					}
				}
				if (!ready) {
					continue;
				}
				fired[i] = true;
				changed = true;
				reached.addAll(e.products());
			}
		}
		return Collections.unmodifiableSet(reached);
	}

	private static boolean canRun(Edge e, Set<Machine> available) {
		for (Machine m : e.machines()) {
			if (available.contains(m)) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ 启动审计

	/**
	 * 启动审计：全机器可用时，从 {@link #ORE_ROOTS} 出发列出到不了的节点。
	 * 只报告，不抛异常（这是「还缺什么」的 TODO，不是启动门槛）。
	 */
	private static void audit() {
		try {
			Set<Machine> all = EnumSet.allOf(Machine.class);
			Set<String> reached = reachableFrom(ORE_ROOTS, all);

			List<String> missSpecies = new ArrayList<>();
			for (String id : Chemical.ids()) {
				if (!reached.contains(id)) {
					missSpecies.add(id);
				}
			}
			List<String> missOther = new ArrayList<>();
			for (String id : NODES) {
				if (!Chemical.isKnown(id) && !reached.contains(id)) {
					missOther.add(id);
				}
			}
			Collections.sort(missSpecies);
			Collections.sort(missOther);

			LOGGER.info("[ReactionGraph] 超边图：{} 条边（化学 {} + 桥接 {}），{} 个节点"
					+ "（化学 {} + 非化学 {}），{} 台机器；矿根 {} 个，全机器可达 {} 个节点。",
					EDGES.size(), CHEMICAL_EDGE_COUNT, BRIDGE_EDGE_COUNT, NODES.size(),
					Chemical.count(), NODES.size() - Chemical.count(), Machine.values().length,
					ORE_ROOTS.size(), reached.size());
			LOGGER.warn("[ReactionGraph] 启动审计 · 从矿根不可达的化学物质 {} 种：\n    {}",
					missSpecies.size(), wrap(missSpecies));
			LOGGER.warn("[ReactionGraph] 启动审计 · 从矿根不可达的非化学节点 {} 个：\n    {}",
					missOther.size(), wrap(missOther));
		} catch (Throwable t) {
			// 报告永远不许把游戏拖下水。
			LOGGER.warn("[ReactionGraph] 启动审计失败（已忽略）", t);
		}
	}

	// ------------------------------------------------------------------ 内部工具

	private static String label(List<String> ins, List<String> outs) {
		return String.join(" + ", ins) + " -> " + String.join(" + ", outs);
	}

	private static Map<String, int[]> buildNodeIndex(List<Edge> edges, boolean reactantSide) {
		Map<String, List<Integer>> tmp = new LinkedHashMap<>();
		for (int i = 0; i < edges.size(); i++) {
			List<String> side = reactantSide ? edges.get(i).reactants() : edges.get(i).products();
			for (String id : side) {
				tmp.computeIfAbsent(id, k -> new ArrayList<>()).add(i);
			}
		}
		Map<String, int[]> out = new LinkedHashMap<>();
		for (Map.Entry<String, List<Integer>> e : tmp.entrySet()) {
			out.put(e.getKey(), toIntArray(e.getValue()));
		}
		return Collections.unmodifiableMap(out);
	}

	private static Map<Machine, int[]> buildMachineIndex(List<Edge> edges) {
		Map<Machine, List<Integer>> tmp = new LinkedHashMap<>();
		for (int i = 0; i < edges.size(); i++) {
			for (Machine m : edges.get(i).machines()) {
				tmp.computeIfAbsent(m, k -> new ArrayList<>()).add(i);
			}
		}
		Map<Machine, int[]> out = new LinkedHashMap<>();
		for (Map.Entry<Machine, List<Integer>> e : tmp.entrySet()) {
			out.put(e.getKey(), toIntArray(e.getValue()));
		}
		return Collections.unmodifiableMap(out);
	}

	private static int[] toIntArray(List<Integer> list) {
		int[] arr = new int[list.size()];
		for (int i = 0; i < arr.length; i++) {
			arr[i] = list.get(i);
		}
		return arr;
	}

	/** 紧凑打印：逗号分隔、约 110 列换行；空列表打印 {@code (none)}。 */
	private static String wrap(Collection<String> ids) {
		if (ids.isEmpty()) {
			return "(none)";
		}
		StringBuilder sb = new StringBuilder();
		int lineLen = 0;
		for (String id : ids) {
			if (lineLen > 0 && lineLen + 2 + id.length() > 110) {
				sb.append(",\n    ");
				lineLen = 4;
			} else if (lineLen > 0) {
				sb.append(", ");
				lineLen += 2;
			}
			sb.append(id);
			lineLen += id.length();
		}
		return sb.toString();
	}
}
