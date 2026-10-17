package com.geometallurgy.metal;

import java.util.Map;
import java.util.Set;

import com.geometallurgy.proto.ImpurityDef;

/**
 * 金属的机械特性表 —— 下游制品（枪械等）的材料学底座。
 *
 * <h2>为什么是四根轴，而不是一个「好坏」标量</h2>
 * 单一标量会让金属排出高下，玩家只需要记住第一名。四根轴里每一根都有人占优，
 * 于是「用什么金属」变成取舍而不是查表：
 * <ul>
 *   <li>{@link Axis#STIFFNESS 刚性} —— 钨最高，铅最低。管件耐久看它；弹头的穿透也看它（硬才钻得动）。</li>
 *   <li>{@link Axis#TOUGHNESS 韧性} —— 锻铁最高，生铁最低。决定抗不抗炸。</li>
 *   <li>{@link Axis#DENSITY 密度} —— 铅最高，镁最低。决定弹头的动量与存速（射程），以及枪管后坐。</li>
 *   <li>{@link Axis#HEAT 耐热} —— 钨最高，铅最低。决定连射时会不会软化。</li>
 * </ul>
 *
 * <h2>生铁与锻铁是刻意做成取舍的</h2>
 * 生铁刚性 {@code 0.75} <b>高于</b>锻铁 {@code 0.70}，但韧性 {@code 0.25} <b>远低于</b>
 * {@code 0.95}。于是「生铁管打得远但会炸、锻铁管打得近但稳」——
 * 这不是数值没调好，是故意不让锻铁成为纯上位。
 *
 * <h2>合金按含量加权，不取最高项</h2>
 * {@code 70% 铁 + 30% 镍} 得到一个介于两者之间的值，而不是「取镍」。
 * 这与 {@link MetalActivity#weightedPotential} 是同一套做法 —— 成分表本来就是连续量，
 * 取最高项会把连续量又压回离散标签，白白浪费掉。
 *
 * <h2>杂质不参与四轴</h2>
 * 硫、磷、砷、锑这些是<b>杂质</b>，它们的影响走「杂质 → 效果」那条单独的路
 * （见 {@link ImpurityDef}），不能混进这里的加权 ——
 * 否则一块含硫 3% 的料会因为「硫也在表里」而把四轴拉偏，
 * 而它真正的后果是热脆，不是变软。
 */
public final class MetalTraits {

	private MetalTraits() {
	}

	/** 四根轴。 */
	public enum Axis {
		/** 刚性：管件打得多远、多准。 */
		STIFFNESS,
		/** 韧性：抗不抗炸、抗不抗磨损。 */
		TOUGHNESS,
		/** 密度：弹头的动能与贯穿。 */
		DENSITY,
		/** 耐热：连射时会不会软化变形。 */
		HEAT
	}

	/** 一个金属（或铁族分级）的四轴特性。全部为 0~1 的相对值。 */
	public record Traits(double stiffness, double toughness, double density, double heat) {

		public double get(Axis axis) {
			return switch (axis) {
				case STIFFNESS -> this.stiffness;
				case TOUGHNESS -> this.toughness;
				case DENSITY -> this.density;
				case HEAT -> this.heat;
			};
		}

		/** 按倍率整体缩放（回收、退火之类用得上）。 */
		public Traits scale(double factor) {
			return new Traits(this.stiffness * factor, this.toughness * factor,
					this.density * factor, this.heat * factor);
		}
	}

	/**
	 * 表外的物质（脉石、杂质、未登记的金属）一律按这个值处理。
	 *
	 * <p>取「中位偏低」而不是中位：没登记的东西不该表现得像标准材料，
	 * 否则给成分表里掺点石英就能白拿一份平均特性。
	 */
	public static final Traits UNKNOWN = new Traits(0.30, 0.30, 0.50, 0.30);

	/** 特性表。键与 {@link com.geometallurgy.proto.Substance} 的金属 id 一致。 */
	private static final Map<String, Traits> TABLE = Map.ofEntries(
			// ---- 碱金属：软、活泼、不耐热，做结构件等于自找麻烦 ----
			Map.entry("potassium", new Traits(0.05, 0.10, 0.15, 0.05)),
			Map.entry("sodium", new Traits(0.05, 0.08, 0.18, 0.05)),
			Map.entry("calcium", new Traits(0.10, 0.15, 0.25, 0.10)),
			Map.entry("barium", new Traits(0.15, 0.20, 0.30, 0.15)),

			// ---- 轻金属：轻但刚性一般，密度低是唯一亮点 ----
			Map.entry("magnesium", new Traits(0.40, 0.50, 0.20, 0.45)),
			Map.entry("aluminum", new Traits(0.45, 0.65, 0.25, 0.55)),

			// ---- 脆而硬的一组：刚性够、韧性差 ----
			Map.entry("manganese", new Traits(0.60, 0.35, 0.65, 0.70)),
			Map.entry("bismuth", new Traits(0.20, 0.30, 0.85, 0.25)),
			Map.entry("zinc", new Traits(0.30, 0.40, 0.55, 0.30)),
			Map.entry("silicon", new Traits(0.55, 0.20, 0.35, 0.60)),

			// ---- 铁族基准 ----
			Map.entry("iron", new Traits(0.70, 0.70, 0.60, 0.60)),
			Map.entry("cobalt", new Traits(0.70, 0.70, 0.65, 0.80)),
			Map.entry("nickel", new Traits(0.65, 0.85, 0.70, 0.75)),
			Map.entry("chromium", new Traits(0.75, 0.60, 0.65, 0.85)),

			// ---- 铁族分级：碳与纯度把同一块铁分成四档 ----
			// 生铁刚性高于锻铁、韧性远低于锻铁 —— 刻意留出取舍。
			Map.entry(Ingots.PIG_IRON, new Traits(0.75, 0.25, 0.60, 0.50)),
			Map.entry(Ingots.STEEL, new Traits(0.80, 0.80, 0.60, 0.70)),
			Map.entry(Ingots.ALLOY_STEEL, new Traits(0.85, 0.75, 0.65, 0.80)),
			Map.entry(Ingots.WROUGHT_IRON, new Traits(0.70, 0.95, 0.60, 0.65)),

			// ---- 难熔金属：刚性与耐热的天花板，代价是韧性 ----
			Map.entry("tungsten", new Traits(0.95, 0.55, 0.95, 1.00)),
			Map.entry("molybdenum", new Traits(0.85, 0.60, 0.70, 0.90)),

			// ---- 软金属：密度与延展性好，刚性差 ----
			Map.entry("copper", new Traits(0.40, 0.70, 0.70, 0.35)),
			Map.entry("silver", new Traits(0.45, 0.65, 0.80, 0.45)),
			Map.entry("gold", new Traits(0.35, 0.60, 0.95, 0.40)),
			Map.entry("tin", new Traits(0.25, 0.55, 0.55, 0.30)),
			Map.entry("lead", new Traits(0.15, 0.60, 1.00, 0.20)),

			// ---- 液态与混合：汞做不了结构件；稀土是一族并项，取中位 ----
			Map.entry("mercury", new Traits(0.00, 0.00, 0.90, 0.10)),
			Map.entry("rare_earth", new Traits(0.50, 0.45, 0.60, 0.55)));

	/**
	 * 杂质的四轴。
	 *
	 * <h2>为什么不并进 {@link #TABLE}</h2>
	 * 那张表是「<b>可以当材料用</b>」的名单 —— {@link #rejectionOf} 正是靠
	 * {@code TABLE.containsKey} 把表外的键判成 {@link Rejection#NON_STRUCTURAL}。
	 * 一放进去，磷和硫就会从「表外」被提升成 {@link Rejection#OK}，
	 * 摇身变成一种可选的结构材料。<b>杂质要有数值，但不该有资格</b>，所以另开一张表。
	 *
	 * <h2>两组，两种命运</h2>
	 * <ul>
	 *   <li><b>有害</b>（磷、硫、砷、锑）—— 共同后果是让基体变脆：磷致冷脆、
	 *       硫致热脆，砷与锑同理。刚性与韧性双双垫底。它们除了在加权里拉低数值，
	 *       还要额外吃一道晶格缺陷（见 {@link #harmfulImpurityFraction}）。</li>
	 *   <li><b>有价</b>（钛、钒）—— 是合金元素而不是污染物：钛轻而强、钒硬而耐热。
	 *       掺它们应当<b>有回报</b>，否则成分表里放这两种元素就只剩坑玩家的作用。</li>
	 * </ul>
	 * 锰两边都沾（{@link ImpurityDef} 登记了它，但 {@link #TABLE} 里也有它），
	 * 取表里那份 —— 锰是锰钢的立身之本，不该按污染物算。
	 */
	private static final Map<String, Traits> IMPURITY_TRAITS = Map.ofEntries(
			// ---- 有害：致脆 ----
			Map.entry("phosphorus", new Traits(0.12, 0.06, 0.50, 0.15)),
			Map.entry("sulfur", new Traits(0.08, 0.04, 0.50, 0.10)),
			Map.entry("arsenic", new Traits(0.18, 0.06, 0.75, 0.15)),
			Map.entry("antimony", new Traits(0.20, 0.05, 0.78, 0.18)),
			// ---- 有价：合金元素 ----
			Map.entry("titanium", new Traits(0.65, 0.60, 0.45, 0.85)),
			Map.entry("vanadium", new Traits(0.70, 0.55, 0.60, 0.90)));

	/**
	 * 有害杂质：在加权之外还要再扣一道晶格缺陷的那几种。
	 *
	 * <p>严格按元素周期表，非金属只有磷和硫，砷与锑是类金属。这里按<b>冶金后果</b>
	 * 分组 —— 它们四个都会让钢变脆，在「这根枪管会不会裂」这件事上是同一类东西。
	 */
	private static final Set<String> HARMFUL_IMPURITIES = Ingots.TRAMP_ELEMENTS;

	/** 某个金属的四轴特性；表外返回 {@link #UNKNOWN}。 */
	public static Traits of(String metal) {
		return TABLE.getOrDefault(metal, UNKNOWN);
	}

	// ------------------------------------------------------------------ 可用性
	//
	// 「有特性」和「能做结构件」是两件事。
	//
	// 钾钠钙钡遇水即爆、镁烧起来扑不灭、汞常温是液体、稀土极易氧化 —— 这些金属
	// 有明确的机械特性（都很软），但拿它们做枪管不是「属性差」，是「不该被允许」。
	//
	// 所以它们**留在特性表里**：一是合金加权需要它们（铁里混 2% 钙是冶炼现实，
	// 那块料不该因此被判死）；二是拒绝时能说清**为什么**，比一句「不能用」有用。
	// 判死的是「主金属是它」的料，不是「成分里出现过它」的料。

	/** 不能用来做结构件的原因。 */
	public enum Rejection {
		/** 可以正常使用。 */
		OK(""),
		/** 化学性质太活泼：遇水或空气就反应，做出来也存不住。 */
		REACTIVE("gui.geometallurgy.reject.reactive"),
		/** 易燃：点着之后极难扑灭。 */
		FLAMMABLE("gui.geometallurgy.reject.flammable"),
		/** 极易氧化：短期看不出，长期会自己烂掉。 */
		OXIDIZABLE("gui.geometallurgy.reject.oxidizable"),
		/** 有毒：加工与使用过程中会中毒。 */
		TOXIC("gui.geometallurgy.reject.toxic"),
		/** 常温是液体：没有固定形状，做不了固件。 */
		LIQUID("gui.geometallurgy.reject.liquid"),
		/** 含碳量落在生铁区间：铸出来的件一受力就裂。 */
		BRITTLE("gui.geometallurgy.reject.brittle"),
		/** 非金属或脆性材料：承受不了膛压。 */
		NON_STRUCTURAL("gui.geometallurgy.reject.non_structural");

		private final String key;

		Rejection(String key) {
			this.key = key;
		}

		public boolean usable() {
			return this == OK;
		}

		/** 语言键；{@link #OK} 没有对应的键。 */
		public String translationKey() {
			return this.key;
		}
	}

	/** 被拒绝的材料 → 原因。没登记在这里的，只要在特性表里就算可用。 */
	private static final Map<String, Rejection> REJECTED = Map.ofEntries(
			// 碱金属与碱土金属：遇水放氢，钾钠还会自燃
			Map.entry("potassium", Rejection.REACTIVE),
			Map.entry("sodium", Rejection.REACTIVE),
			Map.entry("calcium", Rejection.REACTIVE),
			Map.entry("barium", Rejection.REACTIVE),
			// 镁：镁条点燃后温度极高，二氧化碳和水都扑不灭
			Map.entry("magnesium", Rejection.FLAMMABLE),
			// 稀土：一族并项，金属态极易氧化，且软而脆
			Map.entry("rare_earth", Rejection.OXIDIZABLE),
			// 汞：常温液态，且蒸气有毒
			Map.entry("mercury", Rejection.LIQUID),
			// 砷、锑：重金属毒物
			Map.entry("arsenic", Rejection.TOXIC),
			Map.entry("antimony", Rejection.TOXIC),
			// 硅：非金属，脆且没有延展性
			Map.entry("silicon", Rejection.NON_STRUCTURAL),
			// 生铁：碳 4% 以上的铁基料。按成分表判用时走
			// {@link #rejectionOfComposition} 里那条碳的线，这里登记是为了
			// 让直接拿分级键来问的调用（isUsable("pig_iron")）也有正确答复。
			Map.entry(Ingots.PIG_IRON, Rejection.BRITTLE));

	/**
	 * 这个主金属键能不能用来做结构件。
	 *
	 * <p>判据是<b>主金属键</b>（{@link Ingots#metalKey}），不是「成分表里有没有出现」——
	 * 铁里混进 2% 的钙是冶炼现实，不该因此把整块料判死；但主金属是钾的料，
	 * 做出来就是一块遇水就炸的东西。
	 *
	 * <p>表外的键（脉石、未知物质）一律按 {@link Rejection#NON_STRUCTURAL} 处理：
	 * 没登记过的东西不该默认可用于承压件。
	 */
	public static Rejection rejectionOf(String metal) {
		if (metal == null || metal.isEmpty()) {
			return Rejection.NON_STRUCTURAL;
		}
		Rejection rejected = REJECTED.get(metal);
		if (rejected != null) {
			return rejected;
		}
		return TABLE.containsKey(metal) ? Rejection.OK : Rejection.NON_STRUCTURAL;
	}

	/** 便捷判据：能不能用来做结构件。 */
	public static boolean isUsable(String metal) {
		return rejectionOf(metal).usable();
	}

	/**
	 * 按<b>成分表</b>判断能不能做结构件：取占比最高的那一项来看。
	 *
	 * <p>这条是给「合金锭」用的 —— 主金属不到七成时 {@link Ingots#metalKey} 只给一个
	 * {@link Ingots#ALLOY}，那个键不带任何信息，得回到成分表去问「到底什么最多」。
	 * 一块 45% 铁 + 40% 镍的料仍然是可用的，不会因为够不上「铁锭」这个名字就被判死。
	 */
	public static Rejection rejectionOfComposition(Map<String, Double> composition) {
		if (composition == null || composition.isEmpty()) {
			return Rejection.NON_STRUCTURAL;
		}
		// 生铁区间要排在主金属之前判。
		//
		// 下面那条只问「占比最高的是哪种物质」，铁基料永远传进 "iron"，
		// 铁族的分级键（生铁 / 钢 / 锻铁）根本到不了这里 —— 碳不在四轴表里，
		// 加权也会整项跳过它。于是含碳 5% 的料会一路绿灯，韧性看着有 0.70。
		// 所以碳这条线必须自己问，且必须在主金属之前拦下来。
		if (Ingots.isPigIronRange(composition)) {
			return Rejection.BRITTLE;
		}
		return rejectionOf(Ingots.dominant(Ingots.group(composition)));
	}

	/** 便捷判据：按成分表判能不能做结构件。 */
	public static boolean isUsableComposition(Map<String, Double> composition) {
		return rejectionOfComposition(composition).usable();
	}

	/** 被明确拒绝的材料有几种（自检与界面用）。 */
	public static int rejectedCount() {
		return REJECTED.size();
	}

	/** 这个键在特性表里有没有登记。 */
	public static boolean isRegistered(String id) {
		return TABLE.containsKey(id);
	}

	/** 表里登记了多少种材料（自检与界面用）。 */
	public static int size() {
		return TABLE.size();
	}

	/**
	 * 按成分表算四轴。
	 *
	 * <h2>铁族四级先走分级表，不走加权</h2>
	 * 生铁 / 钢 / 合金钢 / 锻铁这四档是由<b>含碳量与纯度</b>决定的，而碳不在特性表里
	 * （它不是金属），含量加权会把它整项跳过 —— 于是 {@code 94% 铁 + 6% 碳} 会算出
	 * 「纯铁」的特性，把生铁该有的脆丢干净。所以铁族先由 {@link Ingots#metalKeyOf}
	 * 定档，直接取那一档的特性。
	 *
	 * <h2>其余一律含量加权</h2>
	 * 掺了镍的铁、够不上七成的合金，都按各成分占比加权 —— 取最高项会把连续量
	 * 又压回离散标签，白白浪费成分表。
	 *
	 * <p>只有<b>表外物质</b>会被跳过（石英、长石这类脉石）。<b>杂质要计入</b>：
	 * 掺了磷的铁不该和纯铁算出同一组数 —— 磷、硫、砷、锑、钛、钒各自在
	 * {@link #IMPURITY_TRAITS} 里都有四轴，和金属一样直接参与加权。
	 * 有害的那几种还要额外吃一道晶格缺陷，但那道折扣不归这一层管
	 * （这一层只出四轴），见 {@link #harmfulImpurityFraction}。
	 *
	 * <p>权重按<b>参与加权的部分</b>重新归一：一块含 20% 脉石的料，其金属部分的特性
	 * 不该因为掺了石头就整体缩水 —— 「掺石头」的代价体现在收率上，不在这里重复扣。
	 */
	public static Traits weighted(Map<String, Double> composition) {
		if (composition == null || composition.isEmpty()) {
			return UNKNOWN;
		}
		// 铁族四级：碳与纯度定档，加权抓不到碳
		String key = Ingots.metalKeyOf(composition);
		if (key != null && Ingots.isIronFamily(key) && TABLE.containsKey(key)) {
			return ironFamilyTraits(TABLE.get(key), composition);
		}
		double total = 0.0;
		double stiffness = 0.0;
		double toughness = 0.0;
		double density = 0.0;
		double heat = 0.0;
		for (Map.Entry<String, Double> entry : composition.entrySet()) {
			String id = entry.getKey();
			double weight = entry.getValue();
			if (weight <= 0.0) {
				continue;
			}
			Traits traits = traitOf(id);
			if (traits == null) {
				continue;
			}
			stiffness += weight * traits.stiffness();
			toughness += weight * traits.toughness();
			density += weight * traits.density();
			heat += weight * traits.heat();
			total += weight;
		}
		if (total <= 0.0) {
			return UNKNOWN;
		}
		return new Traits(stiffness / total, toughness / total, density / total, heat / total);
	}

	/**
	 * 铁族：档位值打底，再把杂质叠上去。
	 *
	 * <h2>为什么不能直接返回档位值</h2>
	 * 那样「钢 + 5% 磷」会和「纯钢」算出一模一样的数 —— 杂质等于白掺。
	 * 档位值只反映了<b>铁与碳</b>（外加「有没有掺别的金属」这个粗粒度），
	 * 磷、硫这些根本不在其中，必须让它们从基体里分走自己那一份。
	 *
	 * <h2>为什么只叠杂质，不叠镍铬这些</h2>
	 * 镍的贡献已经通过档位分级体现过了：掺了别的金属就是合金钢，
	 * 而合金钢的四轴本来就比钢硬（见 {@link Ingots#ALLOY_STEEL}）。再叠一次是重复计算。
	 * 磷硫没有这样的档位可挂，所以只能在这里补。
	 */
	private static Traits ironFamilyTraits(Traits base, Map<String, Double> composition) {
		double stiffness = 0.0;
		double toughness = 0.0;
		double density = 0.0;
		double heat = 0.0;
		double impurity = 0.0;
		for (Map.Entry<String, Double> entry : composition.entrySet()) {
			Traits traits = IMPURITY_TRAITS.get(entry.getKey());
			if (traits == null) {
				continue;
			}
			double weight = entry.getValue();
			if (weight <= 0.0) {
				continue;
			}
			stiffness += weight * traits.stiffness();
			toughness += weight * traits.toughness();
			density += weight * traits.density();
			heat += weight * traits.heat();
			impurity += weight;
		}
		if (impurity <= 0.0) {
			return base;
		}
		// 成分表已归一到 1.00，所以杂质吃掉的份额要从基体里扣，而不是另加一份。
		double baseWeight = Math.max(1.0 - impurity, 0.0);
		double total = baseWeight + impurity;
		if (total <= 0.0) {
			return base;
		}
		return new Traits(
				(baseWeight * base.stiffness() + stiffness) / total,
				(baseWeight * base.toughness() + toughness) / total,
				(baseWeight * base.density() + density) / total,
				(baseWeight * base.heat() + heat) / total);
	}

	/** 某个成分键的四轴：金属表优先，其次杂质表；两张都没有（脉石、未知物）返回 null。
	 *
	 * <p>金属表优先是为了锰 —— 它在两张表里都沾边，但它是锰钢的立身之本，
	 * 该按合金元素算，不该按污染物算。
	 */
	private static Traits traitOf(String id) {
		Traits traits = TABLE.get(id);
		return traits != null ? traits : IMPURITY_TRAITS.get(id);
	}

	/**
	 * 有害杂质（磷、硫、砷、锑）在成分里的总占比。
	 *
	 * <p>这是<b>晶格缺陷</b>的量化依据：这些元素的原子尺寸与基体对不上，溶进去就是
	 * 一个个畸变中心，位错在它们附近堆积、材料就在那里开裂。所以它们除了在四轴上
	 * 把数值拉低（那是一次<b>平均</b>意义上的损失），还要再吃一道乘法折扣 ——
	 * 前者是「这块料整体是什么水平」，后者是「它内部有多少个裂缝起点」。
	 *
	 * <p>折扣的强度由调用方决定：这一层只报<b>有多少</b>，不擅自定罚多少。
	 */
	public static double harmfulImpurityFraction(Map<String, Double> composition) {
		if (composition == null || composition.isEmpty()) {
			return 0.0;
		}
		double sum = 0.0;
		for (String id : HARMFUL_IMPURITIES) {
			sum += Math.max(composition.getOrDefault(id, 0.0), 0.0);
		}
		return sum;
	}

	// ------------------------------------------------------------------ 纯度

	/** 纯度下限：主金属占比到这条线才「算得上」是那种金属（见 {@link Ingots#CALL_THRESHOLD}）。 */
	public static final double MIN_PURITY = Ingots.CALL_THRESHOLD;

	/** 纯度到下限时的性能系数。 */
	public static final double PURITY_FLOOR = 0.80;

	/**
	 * 纯度对性能的系数：{@code 0.70 → 0.80}，{@code 1.00 → 1.00}，中间线性。
	 *
	 * <p>为什么线性而不是「越高收益越大」：纯度的提升是<b>可预期地难</b>的
	 * （要多选一遍矿、多跑一炉），玩家能自己算清楚「再提 5% 纯度值不值」。
	 * 凸曲线会让高纯度区间变成赌博，凹曲线又让精炼失去意义。
	 *
	 * <p>下限以下（理论上不会出现，因为那种料会落到合金锭）按 {@code 0.70} 截断，
	 * 不做额外惩罚 —— 合金锭本身已经是惩罚了。
	 */
	public static double purityFactor(double purity) {
		double clamped = Math.clamp(purity, MIN_PURITY, 1.0);
		double t = (clamped - MIN_PURITY) / (1.0 - MIN_PURITY);
		return PURITY_FLOOR + (1.0 - PURITY_FLOOR) * t;
	}
}
