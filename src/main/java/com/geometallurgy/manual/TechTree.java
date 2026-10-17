package com.geometallurgy.manual;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.geometallurgy.chemical.Chemical;
import com.geometallurgy.chemical.Machine;
import com.geometallurgy.chemical.MolBalance;
import com.geometallurgy.chemical.MolRecipes;
import com.geometallurgy.chemical.ReactionGraph;
import com.geometallurgy.proto.OreMineral;

/**
 * 全屏科技线网状图的<b>唯一数据源</b> —— 整张图由化学超边图<b>算出来</b>，没有手写的节点 / 边表。
 *
 * <h2>数据来源</h2>
 * <ul>
 *   <li><b>节点 = 物质</b>：{@link MolRecipes#all()} 每条反应的反应物 / 产物，外加
 *       {@link ReactionGraph} 桥接表里的矿 / 精矿节点（矿也是节点）。</li>
 *   <li><b>边 = 反应的投影</b>：一条反应是超边（多进多出），这里把它的每个「反应物 ×
 *       产物」投影成一条有向边，边带上反应的 {@link MolBalance.Kind}（热 / 搅拌 / 电解），
 *       用来给边配色；桥接边（矿 → 精矿）的 kind 为 {@code null}。</li>
 *   <li><b>机器</b>不是节点，而是反应属性：每个节点浮层里列出「能处理它的机器」
 *       （{@code block.geometallurgy.*} 键）。</li>
 * </ul>
 *
 * <h2>布局</h2>
 * 本类只做<b>语义归档</b>（每个节点属于哪个工艺阶段）；真正的列 / 行 / 弧线数学在纯类
 * {@link TechTreeLayout} 里（无 {@code net.minecraft} 依赖，可离线测）。列由阶段钉死：
 * 矿 / 选矿 / 冶炼 / 化学 / 稀土，共 {@value TechTreeLayout#STAGE_COUNT} 列。
 *
 * <h2>配方详情</h2>
 * 本类另外把「每个物质参与了哪些反应」倒排出来（{@link #reactionsFor(String)}），
 * 并把全机器可达性 BFS 缓存成 {@link #REACHABLE} —— 详情界面每帧只查表，不再重跑 BFS。
 *
 * <h2>标签只用已有的语言键</h2>
 * 化学物质用 {@code chemical.geometallurgy.<id>}，矿用 {@code mineral.geometallurgy.<id>}，
 * 机器用 {@code block.geometallurgy.<machine>}；精矿 / 岩盐等非化学节点也复用
 * {@code chemical.geometallurgy.<id>}（这些键已存在）。
 */
public final class TechTree {

	private TechTree() {
	}

	/** 节点类型，只影响配色（药剂 / 产物）。 */
	public enum Kind {
		MACHINE,
		REAGENT,
		PRODUCT
	}

	/** 界面语义层数（列数）：矿 / 选矿 / 冶炼 / 化学 / 稀土。 */
	public static final int STAGE_COUNT = TechTreeLayout.STAGE_COUNT;

	/**
	 * 一个节点。
	 *
	 * @param id          稳定标识，边用它引用
	 * @param stage       工艺阶段（0 矿 / 1 选矿 / 2 冶炼 / 3 化学 / 4 稀土）
	 * @param langKey     标签语言键 —— 复用已有键
	 * @param fallback    语言键缺失时的回退文本
	 * @param kind        节点类型（配色）
	 * @param machineKeys 能处理该物质的机器（{@code block.geometallurgy.*} 键），浮层用
	 */
	public record Node(String id, int stage, String langKey, String fallback,
			Kind kind, List<String> machineKeys) {

		public Node {
			machineKeys = List.copyOf(machineKeys);
		}
	}

	/**
	 * 一条有向边：上游 {@code from} 产出的东西流向下游 {@code to}。
	 *
	 * @param kind 触发条件（{@code THERMAL / STIRRING / ELECTROLYSIS}）；桥接边为 {@code null}
	 */
	public record Edge(String from, String to, MolBalance.Kind kind) {
	}

	/** 反应式里的一项：物种 id + 计量系数 + （产物才有意义的）电极归属。 */
	public record Term(String id, int coeff, MolBalance.Side side) {
	}

	/**
	 * 配方详情用的一条反应（化学超边或桥接机器步骤）。
	 *
	 * @param id         稳定 id（化学 {@code rN}，桥接 {@code bridge:*})
	 * @param chemical   true = 来自 {@link MolRecipes}；false = 桥接机器步骤
	 * @param kind       触发条件；桥接边为 {@code null}
	 * @param reactants  反应物（含系数）
	 * @param products   产物（含系数与电极归属）
	 * @param catalysts  催化剂 id
	 * @param machines   能跑这条反应 / 步骤的机器
	 * @param reversible 是否可逆
	 */
	public record ReactionInfo(String id, boolean chemical, MolBalance.Kind kind,
			List<Term> reactants, List<Term> products, List<String> catalysts,
			List<Machine> machines, boolean reversible) {

		public ReactionInfo {
			reactants = List.copyOf(reactants);
			products = List.copyOf(products);
			catalysts = List.copyOf(catalysts);
			machines = List.copyOf(machines);
		}

		/** 该物种是否出现在这条反应里。 */
		public boolean involves(String speciesId) {
			for (Term t : reactants) {
				if (t.id().equals(speciesId)) {
					return true;
				}
			}
			for (Term t : products) {
				if (t.id().equals(speciesId)) {
					return true;
				}
			}
			return false;
		}
	}

	/** 全部节点，按 (stage, id) 排好。 */
	public static final List<Node> NODES;
	/** 全部边，顺序稳定。 */
	public static final List<Edge> EDGES;
	/** 每个物种 → 它参与的全部反应，按 id 稳定排序。 */
	private static final Map<String, List<ReactionInfo>> REACTIONS;
	/** 全机器可用时从矿根 + 环境根出发的可达节点（BFS 只跑这一次，详情界面查表）。 */
	public static final Set<String> REACHABLE;

	/** 内部：投影后的有向弧（{@code kind} 为 null 表示桥接边）。 */
	private record Arc(String from, String to, MolBalance.Kind kind) {
	}

	// 注意：SMELTING 必须在下面那个 static 块之前声明 —— 类初始化按文本顺序执行，
	// static 块里会调 stageOf()，放在后面会让它在初始化期读到 null（NPE）。
	/**
	 * 桥接第二段直接产出的「冶炼中间体」—— 它们由矿 / 精矿经焙烧 / 浸出得到，
	 * 进入化学段之前归「冶炼」列。这是唯一一处人工钉住阶段的地方（其余按规则推导），
	 * 规则顺序见 {@link #stageOf(String)}。
	 */
	private static final Set<String> SMELTING = Set.of(
			"iron_oxide", "manganese_dioxide", "aluminum_hydroxide", "aluminum_oxide",
			"silicon_dioxide", "calcium_fluoride", "calcium_phosphate", "magnesium_carbonate",
			"magnesium_oxide", "sodium_chloride", "potassium_chloride", "carbon",
			"calcium_oxide", "sodium_oxide",
			// 金属粉（§十八）：金属锭粉碎的产物，归「冶炼」列。
			"iron", "copper", "zinc", "manganese", "aluminum");

	static {
		// 1) 化学反应：每条超边的「每个反应物 × 每个产物」投影成一条有向边。
		List<Arc> arcs = new ArrayList<>();
		Map<String, LinkedHashSet<String>> machineMap = new LinkedHashMap<>();
		for (MolBalance.Reaction r : MolRecipes.all()) {
			for (MolBalance.Reactant a : r.reactants()) {
				for (MolBalance.Product p : r.products()) {
					arcs.add(new Arc(a.id(), p.id(), r.kind()));
				}
			}
			for (Machine m : r.machines()) {
				String key = machineKey(m);
				for (MolBalance.Reactant a : r.reactants()) {
					machineMap.computeIfAbsent(a.id(), k -> new LinkedHashSet<>()).add(key);
				}
				for (MolBalance.Product p : r.products()) {
					machineMap.computeIfAbsent(p.id(), k -> new LinkedHashSet<>()).add(key);
				}
			}
		}

		// 2) 桥接超边（矿 → 精矿 / 精矿 → 离子物种）：只用 ReactionGraph 的公开查询重建。
		Set<Integer> bridgeSeen = new HashSet<>();
		Set<String> visited = new LinkedHashSet<>();
		Deque<String> queue = new ArrayDeque<>();
		for (String root : ReactionGraph.ORE_ROOTS) {
			if (visited.add(root)) {
				queue.add(root);
			}
		}
		for (String id : Chemical.ids()) {
			if (visited.add(id)) {
				queue.add(id);
			}
		}
		while (!queue.isEmpty()) {
			String id = queue.poll();
			for (int idx : ReactionGraph.edgesAsReactant(id)) {
				ReactionGraph.Edge e = ReactionGraph.edge(idx);
				if (e.chemical() || !bridgeSeen.add(idx)) {
					continue;
				}
				for (String from : e.reactants()) {
					for (String to : e.products()) {
						arcs.add(new Arc(from, to, null));
					}
				}
				for (Machine m : e.machines()) {
					String key = machineKey(m);
					for (String from : e.reactants()) {
						machineMap.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(key);
					}
					for (String to : e.products()) {
						machineMap.computeIfAbsent(to, k -> new LinkedHashSet<>()).add(key);
					}
				}
				for (String to : e.products()) {
					if (visited.add(to)) {
						queue.add(to);
					}
				}
			}
		}

		// 3) 同一对 from→to 可能被多条反应重复给出，保序去重。
		Map<String, Arc> unique = new LinkedHashMap<>();
		for (Arc a : arcs) {
			unique.putIfAbsent(a.from() + "\u0000" + a.to(), a);
		}
		List<Arc> uarcs = new ArrayList<>(unique.values());

		// 4) 节点集 = 弧端点 ∪ 矿根。
		LinkedHashSet<String> nodeIds = new LinkedHashSet<>();
		nodeIds.addAll(ReactionGraph.ORE_ROOTS);
		for (Arc a : uarcs) {
			nodeIds.add(a.from());
			nodeIds.add(a.to());
		}

		Set<String> hasIn = new HashSet<>();
		for (Arc a : uarcs) {
			hasIn.add(a.to());
		}

		List<Node> nodes = new ArrayList<>(nodeIds.size());
		for (String id : nodeIds) {
			Kind kind;
			if (ReactionGraph.ORE_ROOTS.contains(id) || !hasIn.contains(id)) {
				kind = Kind.REAGENT;
			} else {
				kind = Kind.PRODUCT;
			}
			nodes.add(new Node(id, stageOf(id), nameKey(id), nameFallback(id), kind,
					toList(machineMap.get(id))));
		}
		nodes.sort((a, b) -> a.stage() != b.stage()
				? Integer.compare(a.stage(), b.stage())
				: a.id().compareTo(b.id()));

		List<Edge> edges = new ArrayList<>(uarcs.size());
		for (Arc a : uarcs) {
			edges.add(new Edge(a.from(), a.to(), a.kind()));
		}

		NODES = List.copyOf(nodes);
		EDGES = List.copyOf(edges);
		REACTIONS = buildReactionIndex();
		REACHABLE = ReactionGraph.reachableFrom(ReactionGraph.ORE_ROOTS, EnumSet.allOf(Machine.class));
	}

	// ------------------------------------------------------------------ 阶段归档

	/**
	 * 工艺阶段归档（混合式：规则优先，少数歧义靠 {@link #SMELTING} 人工钉住）。
	 *
	 * <ol>
	 *   <li><b>矿</b>：{@link OreMineral} 里的原矿，外加环境根 {@code water}；</li>
	 *   <li><b>选矿</b>：{@code *_concentrate} 精矿；</li>
	 *   <li><b>冶炼</b>：{@code *_powder} 矿粉、{@code *_ingot} 金属锭，以及
	 *       {@link #SMELTING} 里的氧化物 / 盐 / 碳；</li>
	 *   <li><b>稀土</b>：id 含 {@code rare_earth} 或前缀 {@code light_/heavy_}（精矿与矿粉已在第 2 / 3 步拦下）；</li>
	 *   <li><b>化学</b>：其余全部。</li>
	 * </ol>
	 */
	public static int stageOf(String id) {
		if (OreMineral.byId(id) != null || id.equals("water")) {
			return 0;
		}
		if (id.endsWith("_concentrate")) {
			return 1;
		}
		// 精矿经粉碎机磨成的矿粉、以及化合物还原 / 电解出来的金属锭，都归「冶炼」列
		// （矿粉是进炉料，金属锭是出炉料）。这一条放在稀土之前，让稀土精矿粉也落进冶炼列，
		// 而不是被 rare_earth 规则兜去稀土列。
		if (id.endsWith("_powder") || id.endsWith("_ingot")) {
			return 2;
		}
		if (id.contains("rare_earth") || id.startsWith("light_") || id.startsWith("heavy_")) {
			return 4;
		}
		if (SMELTING.contains(id)) {
			return 2;
		}
		return 3;
	}

	// ------------------------------------------------------------------ 配方倒排 + 可达性缓存

	/** 把化学超边与桥接机器步骤倒排到「物种 → 反应」。 */
	private static Map<String, List<ReactionInfo>> buildReactionIndex() {
		Map<String, List<ReactionInfo>> map = new LinkedHashMap<>();

		int i = 0;
		for (MolBalance.Reaction r : MolRecipes.all()) {
			i++;
			List<Term> ins = new ArrayList<>();
			for (MolBalance.Reactant x : r.reactants()) {
				ins.add(new Term(x.id(), x.coeff(), MolBalance.Side.NONE));
			}
			List<Term> outs = new ArrayList<>();
			for (MolBalance.Product p : r.products()) {
				outs.add(new Term(p.id(), p.coeff(), p.side()));
			}
			ReactionInfo info = new ReactionInfo("r" + i, true, r.kind(), ins, outs,
					List.of(r.catalysts()), List.copyOf(r.machines()), r.reversible());
			put(map, info);
		}

		for (ReactionGraph.Edge e : ReactionGraph.allEdges()) {
			if (e.chemical()) {
				continue;
			}
			List<Term> ins = new ArrayList<>();
			for (String id : e.reactants()) {
				ins.add(new Term(id, 1, MolBalance.Side.NONE));
			}
			List<Term> outs = new ArrayList<>();
			for (String id : e.products()) {
				outs.add(new Term(id, 1, MolBalance.Side.NONE));
			}
			ReactionInfo info = new ReactionInfo(e.id(), false, null, ins, outs,
					List.of(), List.copyOf(e.machines()), false);
			put(map, info);
		}

		// 保持插入顺序（化学表在前、桥接步骤在后），同一物种在同一反应里两侧都出现时只留一条。
		Map<String, List<ReactionInfo>> out = new LinkedHashMap<>();
		for (Map.Entry<String, List<ReactionInfo>> e : map.entrySet()) {
			Map<String, ReactionInfo> unique = new LinkedHashMap<>();
			for (ReactionInfo info : e.getValue()) {
				unique.putIfAbsent(info.id(), info);
			}
			out.put(e.getKey(), List.copyOf(unique.values()));
		}
		return Collections.unmodifiableMap(out);
	}

	private static void put(Map<String, List<ReactionInfo>> map, ReactionInfo info) {
		for (Term t : info.reactants()) {
			map.computeIfAbsent(t.id(), k -> new ArrayList<>()).add(info);
		}
		for (Term t : info.products()) {
			map.computeIfAbsent(t.id(), k -> new ArrayList<>()).add(info);
		}
	}

	/** 某物质参与的全部反应（反应物或产物），id 稳定排序；未知 id 返回空表。 */
	public static List<ReactionInfo> reactionsFor(String speciesId) {
		return REACTIONS.getOrDefault(speciesId, List.of());
	}

	/** 该物质是不是某条（化学或桥接）反应的产物 —— 没有生成路线时详情界面会提示。 */
	public static boolean hasProducer(String speciesId) {
		for (ReactionInfo info : reactionsFor(speciesId)) {
			for (Term t : info.products()) {
				if (t.id().equals(speciesId)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * 全机器审计下该物质「当前不可达」时缺什么：列出所有能生成它的反应里、
	 * 本身也不可达的反应物（去重保序）。可达 / 无生成路线时返回空表。
	 */
	public static List<String> missingFor(String speciesId) {
		if (REACHABLE.contains(speciesId)) {
			return List.of();
		}
		LinkedHashSet<String> missing = new LinkedHashSet<>();
		for (ReactionInfo info : reactionsFor(speciesId)) {
			boolean produces = false;
			for (Term t : info.products()) {
				if (t.id().equals(speciesId)) {
					produces = true;
					break;
				}
			}
			if (!produces) {
				continue;
			}
			for (Term in : info.reactants()) {
				if (!REACHABLE.contains(in.id()) && !in.id().equals(speciesId)) {
					missing.add(in.id());
				}
			}
		}
		return List.copyOf(missing);
	}

	// ------------------------------------------------------------------ 查询

	private static List<String> toList(Set<String> set) {
		return set == null ? List.of() : List.copyOf(set);
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

	/** 物质的标签键：化学物质 / 矿用已有的键；精矿等沿用化学前缀。 */
	public static String nameKey(String id) {
		if (OreMineral.byId(id) != null) {
			return "mineral.geometallurgy." + id;
		}
		return "chemical.geometallurgy." + id;
	}

	/** 标签回退文本：矿用中文名，其余把 id 的下划线换成空格。 */
	public static String nameFallback(String id) {
		OreMineral mineral = OreMineral.byId(id);
		if (mineral != null) {
			return mineral.cnName();
		}
		return id.replace('_', ' ');
	}

	/** 按键取节点。 */
	public static Optional<Node> byId(String id) {
		for (Node node : NODES) {
			if (node.id().equals(id)) {
				return Optional.of(node);
			}
		}
		return Optional.empty();
	}

	/** 某一语义层的标题键（界面文字，不是节点标签）。 */
	public static String stageKey(int stage) {
		return switch (stage) {
			case 0 -> "gui.geometallurgy.techtree.stage.ore";
			case 1 -> "gui.geometallurgy.techtree.stage.dressing";
			case 2 -> "gui.geometallurgy.techtree.stage.smelting";
			case 3 -> "gui.geometallurgy.techtree.stage.chemistry";
			default -> "gui.geometallurgy.techtree.stage.rare_earth";
		};
	}

	/** 层名的回退文本（语言键还没合并时用）。 */
	public static String stageFallback(int stage) {
		return switch (stage) {
			case 0 -> "矿";
			case 1 -> "选矿";
			case 2 -> "冶炼";
			case 3 -> "化学";
			default -> "稀土";
		};
	}
}