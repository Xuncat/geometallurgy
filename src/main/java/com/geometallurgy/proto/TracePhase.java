package com.geometallurgy.proto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.geometallurgy.slurry.SlurryPart;

import net.minecraft.network.chat.Component;

/**
 * 微量相：矿浆里的<b>第三类</b>相。
 *
 * <p>罐内原本只有两类相 —— 目标矿物（{@link OreMineral}）与脉石（{@link com.geometallurgy.geology.RockType}）。
 * 这一类是除它们之外的矿物，含量在千分之几到百分之几，所以要专门指出来：
 * <ul>
 *   <li><b>伴生相</b> —— 主矿里天然夹带的其他矿物。热液型钨锡矿带少量稀土、
 *       铝土矿带钒与稀土、斑岩铜矿带金钼，都是真实矿床的常态。它们写在矿物的
 *       {@link OreMineral#companions()} 里，含量很低但值得单独回收。</li>
 *   <li><b>副产相</b> —— 工艺过程中落下来的东西，目前只有电解的<b>阳极泥</b>：
 *       阳极被电解氧化后留下的不溶残渣，贵金属与稀土会在里面富集。</li>
 * </ul>
 *
 * <h2>为什么要独立成相，而不是记在矿物里「顺带」处理</h2>
 * 因为它<b>密度与磁性都和主矿不一样</b>。独立成相之后，离心机与磁筛机就能把它们从主矿里
 * 分开 —— 「先分选再冶炼」这条主线因此多了一层：不分开就只能在主产品的渣里捡剩的，
 * 分开就能单独送去提。这也是「其他金属的矿渣里还能提稀土」这句话的实现方式：
 * 渣本身就是可以回炉的粉料。
 *
 * <h2>为什么不做成每种稀土一个相</h2>
 * 真实世界里稀土是十七种元素共生在一起的，分不开是常态，工业上也是先拿混合稀土再分离。
 * 所以这里只有<b>一个</b>稀土相，与「稀土不做单独矿物」的口径一致。
 */
public final class TracePhase implements SlurryPart {

	/** 一条微量相的定义。 */
	private record Def(String substance, List<String> impurityPool) {
	}

	/**
	 * 全部微量相。
	 *
	 * <p>顺序就是编解码用的序号，<b>只增不改</b>：中间插入会打乱已经在存档里的键
	 * （存档走的是 {@code trace:<物质>} 字符串，但界面同步走序号）。
	 */
	private static final List<Def> DEFS = List.of(
			new Def("rare_earth", List.of("phosphorus", "titanium", "vanadium")),
			new Def("gold", List.of("silver", "sulfur", "antimony")),
			new Def("silver", List.of("gold", "sulfur", "antimony")),
			new Def("nickel", List.of("sulfur", "manganese")),
			new Def("copper", List.of("sulfur", "arsenic")),
			new Def("lead", List.of("sulfur", "antimony")),
			new Def("zinc", List.of("sulfur", "manganese")),
			new Def("anode_mud", List.of("antimony", "arsenic", "sulfur")));

	private static final List<TracePhase> ALL = new ArrayList<>();
	private static final Map<String, TracePhase> BY_SUBSTANCE = new HashMap<>();

	static {
		for (int index = 0; index < DEFS.size(); index++) {
			TracePhase phase = new TracePhase(index, DEFS.get(index));
			ALL.add(phase);
			BY_SUBSTANCE.put(phase.substance, phase);
		}
	}

	/** 稀土：铝土矿、磷灰石、黑钨矿的伴生相，也是阳极泥里的主要可回收物。 */
	public static final TracePhase RARE_EARTH = BY_SUBSTANCE.get("rare_earth");
	/** 伴生金。 */
	public static final TracePhase GOLD = BY_SUBSTANCE.get("gold");
	/** 伴生银。 */
	public static final TracePhase SILVER = BY_SUBSTANCE.get("silver");
	/** 伴生镍。 */
	public static final TracePhase NICKEL = BY_SUBSTANCE.get("nickel");
	/** 伴生铜。 */
	public static final TracePhase COPPER = BY_SUBSTANCE.get("copper");
	/** 伴生铅。 */
	public static final TracePhase LEAD = BY_SUBSTANCE.get("lead");
	/** 伴生锌。 */
	public static final TracePhase ZINC = BY_SUBSTANCE.get("zinc");
	/** 电解阳极泥。 */
	public static final TracePhase ANODE_MUD = BY_SUBSTANCE.get("anode_mud");

	private final int index;
	private final String substance;
	private final List<String> impurityPool;

	private TracePhase(int index, Def def) {
		this.index = index;
		this.substance = def.substance();
		this.impurityPool = def.impurityPool();
	}

	/** 用它代表的物质 id 做相 id，带前缀以免与矿物撞名。 */
	@Override
	public String id() {
		return "trace_" + this.substance;
	}

	/** 这个相代表哪种物质。 */
	public String substance() {
		return this.substance;
	}

	@Override
	public int color() {
		return Substance.colorOf(this.substance);
	}

	@Override
	public Component displayName() {
		return Component.translatable(translationKey());
	}

	public String translationKey() {
		return "trace.geometallurgy." + this.substance;
	}

	/** 微量相是有价的目标，不是脉石。 */
	@Override
	public boolean isMineral() {
		return true;
	}

	/** 夹带杂质时的候选池。 */
	public List<String> impurityPool() {
		return this.impurityPool;
	}

	/** 编解码序号。 */
	public int index() {
		return this.index;
	}

	public static List<TracePhase> all() {
		return ALL;
	}

	/** 按物质 id 取相；这个物质没有微量相时返回 null。 */
	@Nullable
	public static TracePhase of(String substance) {
		return substance == null ? null : BY_SUBSTANCE.get(substance);
	}

	@Nullable
	public static TracePhase byIndex(int index) {
		return index >= 0 && index < ALL.size() ? ALL.get(index) : null;
	}
}
