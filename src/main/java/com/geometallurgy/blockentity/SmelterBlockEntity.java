package com.geometallurgy.blockentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.geometallurgy.block.SmelterBlock;
import com.geometallurgy.chemical.Chemical;
import com.geometallurgy.chemical.ChemicalTags;
import com.geometallurgy.chemical.MolBalance;
import com.geometallurgy.chemical.OutputRouting;
import com.geometallurgy.chemical.MolRecipes;
import com.geometallurgy.chemical.MolStock;
import com.geometallurgy.chemical.MolTank;
import com.geometallurgy.chemical.Reactor;
import com.geometallurgy.chemical.ReductantGas;
import com.geometallurgy.item.MolCarrier;
import com.geometallurgy.item.ModItems;
import com.geometallurgy.menu.SmelterMenu;
import com.geometallurgy.metal.Ingots;
import com.geometallurgy.metal.MetalSeries;
import com.geometallurgy.metal.Metallurgy;
import com.geometallurgy.metal.Reagent;
import com.geometallurgy.metal.SmeltRoute;
import com.geometallurgy.proto.Substance;
import com.geometallurgy.slurry.SlurryForm;
import com.geometallurgy.slurry.SlurryHandler;
import com.geometallurgy.slurry.SlurryPart;
import com.geometallurgy.slurry.SlurryParts;
import com.geometallurgy.slurry.SlurryProvider;
import com.geometallurgy.slurry.SlurryStore;
import com.geometallurgy.slurry.SlurryTransfer;
import com.geometallurgy.slurry.VirtualSlurry;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import team.reborn.energy.api.EnergyStorage;
import team.reborn.energy.api.base.SimpleEnergyStorage;

/**
 * 冶炼炉：把矿粉按某条化学路线炼成金属锭。
 *
 * <p>三台机器（还原炉 / 电解炉 / 置换炉）共用这一个方块实体，差别全在 {@link SmelterProfile}
 * 里 —— 走哪条路线、烧多少电、一个炉次多久、辅助剂收哪一类。
 *
 * <h2>一批料会发生什么，全由 {@link Metallurgy#plan} 说了算</h2>
 * 每刻都拿当前罐内成分去问一次「这一炉会出什么」，答案同时喂给三处：状态判定、界面读数、
 * 以及真正完工时的产出。所以界面上写的「预计产出 78% 铁」与最后出来的那锭一定对得上
 * —— 它们本来就是同一次计算的结果。
 *
 * <h2>为什么一批料是一个整批</h2>
 * 炉次总是处理 {@link Metallurgy#BATCH} 那么多料：罐里不够就停在「攒料中」，不烧半点电与试剂。
 * 这一条不是讲究，是为了让账能算平 —— {@link Metallurgy#plan} 是按<b>罐内成分的比例</b>切出一批的，
 * 罐内成分一变，同一步进度对应的产出就跟着变。
 *
 * <p>所以还有第二条：<b>开工之后不再收料</b>，两个方向都挡住 —— 本机不去抽（{@link #tick}），
 * 上游也推不进来（{@link #inputPort} 的 {@code canInsert}）。早先每刻都吸，上游推一点、
 * 罐内比例就变一点，试剂当量与电耗倍率跟着抖，判定在「能烧」与「缺试剂 / 缺电」之间翻来覆去，
 * 进度被反复清零，结果是一炉也跑不完。现在开工那一刻罐内成分就定住了，直到这一批抽走为止。
 * 一炉只吃 100 mB，罐却有 16000 mB，所以这 100 刻里不收料并不憋上游。
 *
 * <p>顺带把「中途停机」与「换一批料」区分开：缺电、渣满、产物槽堵都<b>保留</b>进度，
 * 只有罐里这批料真没了才清零。
 *
 * <h2>为什么先判料再判电</h2>
 * 上一轮在粉碎机上踩过这个坑：先把电抽走、再判能不能干活，于是罐里其实没料也报「电力不足」。
 * 这里从顺序上钉死：<b>料 → 路线 → 贫富 → 空间 → 试剂 → 电</b>。
 * 越靠前的越该先修，界面上的那句抱怨也就指向正确的动作。
 *
 * <h2>辅助剂为什么要有「池」</h2>
 * 料脏时脉石也要陪着烧掉一份试剂，一个炉次需要的当量会冒到 1.2、1.5 这种非整数。
 * 若按整件计，这份浪费就被取整抹平了。所以炉内维持一个能存小数的试剂池：缺多少就从槽里
 * 取一整件补进去，再按实际需求扣 —— 同样的试剂、更少的产量，这笔账才记得清。
 */
public class SmelterBlockEntity extends BlockEntity
		implements ExtendedMenuProvider<Unit>, SlurryProvider, Container, WorldlyContainer,
		FaceConfigurable {

	// ------------------------------------------------------------------ 常量

	/** 阳极槽。电解炉里它被电解氧化、烧得最快；其余炉子这就是唯一的试剂槽（还原剂 / 置换剂）。 */
	public static final int SLOT_REAGENT = 0;
	/** 阴极槽。只有电解炉用：它只传电不参与反应，磨得比阳极慢得多。 */
	public static final int SLOT_CATHODE = 1;
	/** 产物槽（锭 / 碳粉）。 */
	public static final int SLOT_OUTPUT = 2;
	public static final int SLOT_COUNT = 3;

	/** 阴极每撑过这么多炉才扣一点耐久 —— 同一根碳棒插在阴极上能顶四倍时间。 */
	public static final int BATCHES_PER_CATHODE_WEAR = 4;

	/** 少于这么多就算「没有」—— 罐底那点浮点残渣不是料。 */
	public static final double MIN_BATCH = 1.0e-3;

	/** 每个面每刻的矿浆搬运量（mB）。 */
	public static final double TRANSFER_RATE = 16.0;

	/**
	 * 电能同步刻度：内部按 FE 存，传到界面的是 FE ÷ 这个数。
	 *
	 * <p>电解炉的缓冲有 48000 FE，直接传就会撞上下面那条 16 位上限。
	 */
	public static final int ENERGY_SYNC_DIVISOR = 10;

	/**
	 * 回收量同步刻度：传上去的是 mB × 这个数。
	 *
	 * <p>取 100 而不是 1000 是被逼的：一个整批能回收 100 mB，×1000 就是 100000，
	 * 远超同步通道的上限（见 {@link #DATA_COUNT} 的说明）。×100 只到 10000，
	 * 而界面本来就只显示到小数点后一位。
	 */
	public static final int YIELD_SYNC_SCALE = 100;

	/**
	 * 同步给界面的成分条数（进料与产物各一份）。
	 */
	public static final int TRACKED_ENTRIES = 10;
	/**
	 * 电解路每面角色的位数（7 个取值 → 3 bit）。共用 {@link PortRole} 仍是 2 bit
	 * （见 {@link FaceConfig#BITS}），所以打包 / 解包必须按路线分开，不能混。
	 */
	private static final int ELECTRO_BITS = 3;
	/** 单个电解角色的位掩码。 */
	private static final int ELECTRO_MASK = (1 << ELECTRO_BITS) - 1;
	/** 低三个面的位掩码（9 bit）。 */
	private static final int ELECTRO_LOW_MASK = (1 << (3 * ELECTRO_BITS)) - 1;

	/**
	 * 六面角色的高半：电解路 3 bit/面放不下一个 16 位槽，面 3~5 挪到这里。
	 *
	 * <p>与 {@code AbstractReactorBlockEntity.D_ROLES / D_ROLES_B} 同一套拆法。
	 * 其余两条炉路的共用角色只占 12 bit，这一格恒 0。
	 */
	public static final int ROLES_B_INDEX = 11;

	/**
	 * 固定同步字段数：0~9 是读数，10 是进料条数，11 是六面角色的高半，其余是产物条数与成分表。
	 *
	 * <p><b>这条通道的每个值都必须落在 16 位有符号整数里。</b>
	 * {@code ContainerData} 最终由 {@code ClientboundContainerSetDataPacket} 发出去，
	 * 而那个包把值按 {@code writeShort} 写 —— 超过 32767 会当场截成负数。
	 * 电解炉的产量曾经因此显示成「-18 mB」：48014 被截成了 -17522。
	 * 所以凡是有可能上千的量，先在服务端除一个刻度，界面再乘回来。
	 */
	private static final int FIXED_DATA = 12;
	/** 进料成分在同步数组里的起点。 */
	public static final int INPUT_BASE = FIXED_DATA;
	/** 产物成分条数所在的下标。 */
	public static final int PRODUCT_COUNT_INDEX = INPUT_BASE + TRACKED_ENTRIES * 2;
	/** 产物成分在同步数组里的起点。 */
	public static final int PRODUCT_BASE = PRODUCT_COUNT_INDEX + 1;
	/** 溶液电解账的总 mol ×100（只有电解炉非零，见 {@link #solution}）。 */
	public static final int SOLUTION_MOL_INDEX = PRODUCT_BASE + TRACKED_ENTRIES * 2;
	/** 溶液成分同步了几项（0~{@link #TRACKED_ENTRIES}）。 */
	public static final int SOLUTION_COUNT_INDEX = SOLUTION_MOL_INDEX + 1;
	/** 溶液成分：每项两格 —— {@code 物质序号 + 1} 与占比千分位。 */
	public static final int SOLUTION_BASE = SOLUTION_COUNT_INDEX + 1;
	/** 阳极容器账的总 mol ×100（只有电解炉非零，见 {@link #anode}）。 */
	public static final int ANODE_MOL_INDEX = SOLUTION_BASE + TRACKED_ENTRIES * 2;
	/** 阳极成分同步了几项（0~{@link #TRACKED_ENTRIES}）。 */
	public static final int ANODE_COUNT_INDEX = ANODE_MOL_INDEX + 1;
	/** 阳极成分：同溶液，每项两格。 */
	public static final int ANODE_BASE = ANODE_COUNT_INDEX + 1;
	/** 阴极容器账的总 mol ×100。 */
	public static final int CATHODE_MOL_INDEX = ANODE_BASE + TRACKED_ENTRIES * 2;
	/** 阴极成分同步了几项。 */
	public static final int CATHODE_COUNT_INDEX = CATHODE_MOL_INDEX + 1;
	/** 阴极成分：每项两格。 */
	public static final int CATHODE_BASE = CATHODE_COUNT_INDEX + 1;
	/**
	 * 电解容器堵住时「哪一相堵住」：0 = 无，其余是 {@code Chemical.Phase.ordinal() + 1}。
	 *
	 * <p>与 {@link AbstractReactorBlockEntity#D_BLOCK_PHASE} 同构：只传序号与百 mol 量，
	 * 不传字符串。溶液 / 阳极 / 阴极三本账谁满了就报谁，见 {@link #updateBlockedVessel()}.
	 */
	public static final int BLOCK_PHASE_INDEX = CATHODE_BASE + TRACKED_ENTRIES * 2;
	/** 堵住的那一相有多少 mol ×100（钳到 16 位）。 */
	public static final int BLOCK_MOL_INDEX = BLOCK_PHASE_INDEX + 1;
	public static final int DATA_COUNT = BLOCK_MOL_INDEX + 1;

	// ---------------------------------------------------------------- 溶液电解档（设计稿 §二.1）

	/** 溶液容量（mol）：90 mol = 6 桶。 */
	public static final double SOLUTION_CAPACITY = 90.0;
	/** 阳极 / 阴极容器容量（mol）：与溶液同量级，一个电极攒够 90 mol 就该排走。 */
	public static final double VESSEL_CAPACITY = 90.0;
	/** 溶液电解每 tick 最多跑几份反应（0.5 份 = 每 2 tick 一整份，按 mol 计电费）。 */
	public static final double SOLUTION_RATE_PER_TICK = 0.5;
	/** 单次从邻罐抽进 / 推给邻罐的 mol 上限（一桶）。 */
	public static final double SOLUTION_TRANSFER_MOL = MolStock.BUCKET_MOL;

	/**
	 * 溶液面能推出的物质：电解反应里 {@code Side == NONE} 的产物，再扣掉所有反应物。
	 *
	 * <p>电极归属的产物（气 / 固）走各自的阳极 / 阴极面；未反应的原料（NaCl / 水）留在账上
	 * 继续反应。三类物质各走各的面，才不会出现「输出面把刚抽进来的原料原样推走」。
	 */
	private static final java.util.Set<String> SOLUTION_PRODUCTS = solutionProducts();

	private static java.util.Set<String> solutionProducts() {
		java.util.Set<String> reactants = new java.util.HashSet<>();
		java.util.Set<String> products = new java.util.HashSet<>();
		for (MolBalance.Reaction r : MolRecipes.all()) {
			if (r.kind() != MolBalance.Kind.ELECTROLYSIS) {
				continue;
			}
			for (MolBalance.Reactant x : r.reactants()) {
				reactants.add(x.id());
			}
			for (MolBalance.Product p : r.products()) {
				if (p.side() == MolBalance.Side.NONE) {
					products.add(p.id());
				}
			}
		}
		products.removeAll(reactants);
		return java.util.Set.copyOf(products);
	}

	/** 阳极容器只收这些物质（电解反应里 {@code Side = ANODE} 的产物）。 */
	private static final java.util.Set<String> ANODE_PRODUCTS = electrodeProducts(MolBalance.Side.ANODE);
	/** 阴极容器只收这些物质（{@code Side = CATHODE} 的产物）。 */
	private static final java.util.Set<String> CATHODE_PRODUCTS = electrodeProducts(MolBalance.Side.CATHODE);

	private static java.util.Set<String> electrodeProducts(MolBalance.Side side) {
		java.util.Set<String> ids = new java.util.HashSet<>();
		for (MolBalance.Reaction r : MolRecipes.all()) {
			if (r.kind() != MolBalance.Kind.ELECTROLYSIS) {
				continue;
			}
			for (MolBalance.Product p : r.products()) {
				if (p.side() == side) {
					ids.add(p.id());
				}
			}
		}
		return java.util.Set.copyOf(ids);
	}

	/** 功率读数的平滑系数。 */
	private static final double POWER_SMOOTHING = 0.25;

	/**
	 * 机器此刻为什么停着。
	 *
	 * <p>分开报这么多停机理由，是因为它们各自指向完全不同的动作：加电、换试剂、
	 * 把料选干净一点、把渣排掉、把产物取走。糊成一个「停机」等于让人自己猜。
	 */
	public enum Status {
		/** 进料罐是空的。 */
		IDLE("idle", false),
		/** 罐里有料，但还不到一个整批 —— 炉子要先攒够一批才开工。 */
		GATHERING("gathering", false),
		/** 罐里有料，但没有一种矿物走得了本机这条路。 */
		NO_ROUTE("no_route", false),
		/** 有能走这条路的矿物，但可回收金属太少 —— 这一炉烧了也是白烧。 */
		TOO_LEAN("too_lean", false),
		/** 渣罐满了。 */
		SLAG_FULL("slag_full", false),
		/** 产物槽满了（或里面躺着一锭成分不同的）。 */
		OUTPUT_FULL("output_full", false),
		/** 试剂槽空的。 */
		NO_REAGENT("no_reagent", false),
		/** 试剂放错了 —— 例如拿铁锭去置换铁，活动性不够。 */
		WRONG_REAGENT("wrong_reagent", false),
		/** 电不够。 */
		NO_POWER("no_power", false),
		/** 正在冶炼。 */
		RUNNING("running", true);

		private final String key;
		private final boolean lit;

		Status(String key, boolean lit) {
			this.key = key;
			this.lit = lit;
		}

		public String key() {
			return this.key;
		}

		public String translationKey() {
			// 状态键是各机器共用的，但「在干什么」各家不一样：磁筛机是分选中、粉碎机是磨矿中、
			// 炉子是冶炼中。所以「运行中」这一条炉子单占一个键 —— 不然还原炉会显示成「分选中」。
			return this == RUNNING
					? "gui.geometallurgy.status_smelting"
					: "gui.geometallurgy.status_" + this.key;
		}

		public Component translatable() {
			return Component.translatable(translationKey());
		}

		public boolean lit() {
			return this.lit;
		}
	}

	// ------------------------------------------------------------------ 状态

	private final SmelterProfile profile;

	private final VirtualSlurry input = new VirtualSlurry();
	private final VirtualSlurry slag = new VirtualSlurry();

	/**
	 * 进料口。
	 *
	 * <p>「炉膛在反应时不收料」这条规矩必须挡在<b>面上</b>，而不是只挡在 {@link #pullInput} 里：
	 * 搬运有两个方向 —— 本机去抽（走 {@code pullInput}）和上游来推（走 {@link SlurryProvider} 的
	 * {@code canInsert}）。只挡住前者的话，上游那台机器照样能把料灌进来，这一批的成分又会被搅动。
	 */
	private final SlurryHandler inputPort = new SlurryHandler() {
		@Override
		public VirtualSlurry tank() {
			return SmelterBlockEntity.this.input;
		}

		@Override
		public boolean canInsert() {
			return SmelterBlockEntity.this.status != Status.RUNNING;
		}

		@Override
		public boolean canExtract() {
			return false;
		}
	};

	private final SlurryHandler slagPort = new TankHandler(this.slag, false, true);

	/** 试剂槽 + 阴极槽 + 产物槽。 */
	private final SimpleContainer items = new SimpleContainer(SLOT_COUNT) {
		@Override
		public boolean canPlaceItem(int index, ItemStack stack) {
			if (index == SLOT_REAGENT) {
				return acceptsReagent(stack);
			}
			// 阴极槽只有电解炉有地方放；别的炉子这一格是空的、什么也塞不进去
			return index == SLOT_CATHODE && SmelterBlockEntity.this.usesCathode()
					&& acceptsReagent(stack);
		}

		@Override
		public void setChanged() {
			super.setChanged();
			SmelterBlockEntity.this.setChanged();
		}
	};

	/** 电力缓冲。容量随机器规格走，所以只能在构造器里建。 */
	public final SimpleEnergyStorage energy;

	/** 共用（还原 / 置换）路的面角色。电解路不用这一份。 */
	private final PortRole[] roles = new PortRole[6];

	/** 电解路自己的面角色：三本账各进各出，取值比 {@link PortRole} 多。 */
	private final ElectrolysisRole[] electroRoles = new ElectrolysisRole[6];

	/** 溶液电解账（mol）—— 只有电解炉用：桶 / 储罐送进来的盐溶液在这里发生 ELECTROLYSIS 反应。 */
	private final MolStock solution = MolStock.empty();

	/** 阳极容器账：阳极那一份气体 / 固体（液体留在 {@link #solution}）。 */
	private final MolStock anode = MolStock.empty();

	/** 阴极容器账：阴极那一份气体 / 固体。 */
	private final MolStock cathode = MolStock.empty();

	/** 上一刻溶液电解跑了几份（界面用）。 */
	private int solutionRuns;

	/**
	 * 电解容器（溶液 / 阳极 / 阴极）满到推不出去时，堵住的那一相与它的 mol 数。
	 *
	 * <p>与反应釜的 {@code blockedPhase / blockedMol} 同一套语义，只是这里的「账」是
	 * {@link #solution} / {@link #anode} / {@link #cathode} 三本；谁先到容量就报谁。
	 * 界面据此把「容器堵住：气体 90.0 mol 推不出去」写出来。
	 */
	private Chemical.Phase blockedPhase;
	private double blockedMol;

	/** 试剂池（当量，可存小数）。 */
	private double reagentCharge;
	private int progress;
	private double power;
	private double lastDraw;
	private Status status = Status.IDLE;

	/** 上一次从槽里取走的那件试剂是不是碳系 —— 槽空了之后还要靠它认账。 */
	private boolean reagentIsCarbon;

	/** 阴极距上一次磨损已经过了几炉。 */
	private int cathodeWear;

	/**
	 * 本批掷出来的点数（0~1），管「阳极泥里能不能淘到贵金属」这类赌局。
	 *
	 * <p>在<b>开工那一刻</b>定下来，之后直到这一批结束都不再变 —— 界面上那口饼图与最后出来的
	 * 那锭必须是同一个结果，所以赌局一旦开始就不能重掷。
	 */
	private double batchRoll = 0.5;
	/** 本批是否已经掷过骰。 */
	private boolean batchRolled;

	private long cachedTick = Long.MIN_VALUE;
	private Metallurgy.Batch cachedPlan;

	private final ContainerData dataAccess = new ContainerData() {
		@Override
		public int get(int index) {
			return SmelterBlockEntity.this.readData(index);
		}

		@Override
		public void set(int index, int value) {
			// 只读
		}

		@Override
		public int getCount() {
			return DATA_COUNT;
		}
	};

	/**
	 * 方块实体类型工厂要用这个签名，而方块自己知道规格，所以这里从方块身上把规格读回来。
	 */
	public SmelterBlockEntity(BlockPos pos, BlockState state) {
		this(pos, state, state.getBlock() instanceof SmelterBlock smelter
				? smelter.profile()
				: SmelterProfile.REDUCTION);
	}

	public SmelterBlockEntity(BlockPos pos, BlockState state, SmelterProfile profile) {
		super(ModBlockEntities.smelter(profile), pos, state);
		this.profile = profile;
		// 不用电的炉子（置换炉）容量与进电上限都是 0：留着一个空的储能源只会误导发电机往里推。
		this.energy = new SimpleEnergyStorage(
				profile.usesPower() ? profile.capacity() : 0L,
				profile.usesPower() ? profile.insertCap() : 0L,
				0L) {
			@Override
			protected void onFinalCommit() {
				SmelterBlockEntity.this.setChanged();
			}
		};
		this.cachedPlan = Metallurgy.plan(profile.route(), Map.of(), 0.0);
		// 本机只吃磨细的矿粉 —— 粗颗粒矿浆没法在炉膛里均匀反应，那是磁筛机与离心机的事。
		this.input.allow(SlurryForm.POWDER);
		// 渣也是粉：进的是粉，出来的当然是粉。形态对不上，渣就送不回炉子里再炼一遍，
		// 而「矿渣里还有没提干净的东西」正是伴生相与阳极泥的第二条来路。
		this.slag.setForm(SlurryForm.POWDER);
		clearFaces();
	}

	/**
	 * 出厂状态：六个面全关。
	 *
	 * <p>原本给渣留了两条默认出路（北、南各一个），理由是「否则一开机就把渣罐顶满」。
	 * 现在连这两条也不预设 —— 界面上一眼能看出哪个面干什么，比替玩家猜要好。
	 * 代价是<b>新摆的炉子必须先把渣口和进料口配好</b>，否则第一炉跑到渣满就停在那儿；
	 * 状态栏会写「渣满」，不是静默卡住。
	 */
	@Override
	public void clearFaces() {
		java.util.Arrays.fill(this.roles, PortRole.DISABLED);
		java.util.Arrays.fill(this.electroRoles, ElectrolysisRole.DISABLED);
		setChanged();
	}

	public SmelterProfile profile() {
		return this.profile;
	}

	public SmeltRoute route() {
		return this.profile.route();
	}

	/**
	 * 这台炉子按标签收不收某个相。
	 *
	 * <p>真正决定一批料走不走得通的是 {@link Metallurgy#plan}（它内部先查显式路线表、
	 * 再走 {@link Metallurgy#recipe} 的标签兜底）；这里是同一份判定暴露给界面 / 测试 / 调试的入口 ——
	 * 还原炉对任何氧化物返回 true，难还原氧化物（Al₂O₃ / MgO）与电解 / 置换两路仍按显式表来。
	 */
	public boolean acceptsTagged(SlurryPart phase) {
		return phase != null && this.profile.acceptsTagged(ChemicalTags.tagsOf(phase.id()));
	}

	// ------------------------------------------------------------------ 主循环

	public static void serverTick(ServerLevel level, BlockPos pos, BlockState state,
			SmelterBlockEntity smelter) {
		smelter.tick(level, pos, state);
	}

	private void tick(ServerLevel level, BlockPos pos, BlockState state) {
		// 炉膛在反应时不吸料 —— 否则上游一边推、罐内成分一边变，这一批的账就没法算清了。
		// {@code status} 是上一刻的结果，所以「刚开始烧的那一 tick」仍会吸一次，正是我们要的。
		if (this.status != Status.RUNNING) {
			this.pullInput(level, pos);
		}
		// 溶液电解档与熔盐炉次并行：它只动 solution 账，不碰 input / slag。
		this.tickSolution(level, pos);

		this.status = this.evaluate();
		// 开工：先把这一批的掷骰定下来，再用它重判一次 —— 否则头一 tick 的耗电是按期望值算的，
		// 而这一批的实际产出是按掷骰结果算的，两者对不上。
		if (this.status == Status.RUNNING && !this.batchRolled) {
			this.batchRoll = level.getRandom().nextDouble();
			this.batchRolled = true;
			this.cachedTick = Long.MIN_VALUE;
			this.status = this.evaluate();
		}
		this.lastDraw = 0.0;
		if (this.status == Status.RUNNING) {
			long draw = this.profile.usesPower() ? this.currentDraw() : 0L;
			if (!this.profile.usesPower() || this.energy.amount >= draw) {
				this.energy.amount -= draw;
				this.lastDraw = draw;
				this.progress++;
				if (this.progress >= this.profile.ticksPerBatch()) {
					this.progress = 0;
					this.completeBatch();
				}
			}
		} else if (this.status == Status.IDLE) {
			// 只有「罐里这批料已经没了」才清进度。缺电、渣满、产物槽堵这类停顿都保留进度，
			// 否则任何一次风吹草动都得从头再烧一遍 —— 上一版就是这么被「打断」的。
			this.progress = 0;
			this.batchRolled = false;
		}
		this.power += (this.lastDraw - this.power) * POWER_SMOOTHING;

		this.pushSlag(level, pos);

		boolean lit = this.status.lit();
		if (state.getValue(SmelterBlock.LIT) != lit) {
			level.setBlock(pos, state.setValue(SmelterBlock.LIT, lit), Block.UPDATE_ALL);
		}
	}

	/**
	 * 本刻该扣多少电：<b>这一批料自己的加权功率</b>（逐配方 FE/t，见
	 * {@link com.geometallurgy.metal.SmeltRecipe#powerFePerTick()}），再压到单刻上限。
	 *
	 * <p>{@code 0} 是合法值，而且是本轮的重点：还原炉里那些<b>试剂驱动</b>的路线（高炉 / 反射炉 /
	 * 自然金属熔炼）一度电都不吃，成本全压在还原剂上（写死在 {@code Metallurgy} 的路线表里）。
	 * 所以这里<b>不能</b>再留 {@code max(1, …)} 的下限 —— 那会给「不要电的配方」强塞 1 FE/t，
	 * 让它在缺电时明明不耗电却报「没电」。
	 */
	private long currentDraw() {
		double power = this.plan().powerFePerTick();
		if (power <= 0.0) {
			return 0L;
		}
		return Math.max(1L, Math.min(this.profile.maxDraw(), Math.round(power)));
	}

	/**
	 * 现在能不能干活。
	 *
	 * <p>顺序就是优先级：料 → 路线 → 贫富 → 攒够没 → 空间 → 试剂 → 电。
	 *
	 * <p>「攒够没」排在贫富之后，是因为这两个问题的主语不同：能不能炼是<b>料性</b>，
	 * 攒没攒够是<b>批量</b>。一罐 20 mB 的石英进还原炉，该说的是「料里没有走得了这条路的矿物」
	 * 而不是「还在攒料」—— 后者会让人一直等着它开工。
	 */
	private Status evaluate() {
		if (this.input.amount() < MIN_BATCH) {
			return Status.IDLE;
		}
		Metallurgy.Batch plan = this.plan();
		if (plan.reactiveVolume() <= 0.0) {
			return Status.NO_ROUTE;
		}
		if (plan.lean()) {
			return Status.TOO_LEAN;
		}
		// 一批料就是一个整批：攒不到就不开工。这样每一炉烧的都是同样多的料，
		// 进度条、单炉耗时、试剂当量三者的刻度才是稳定的（见类注释）。
		if (this.progress <= 0 && this.input.amount() < Metallurgy.BATCH) {
			return Status.GATHERING;
		}
		if (this.slag.room() < MIN_BATCH) {
			return Status.SLAG_FULL;
		}
		if (!this.canAcceptProduct(plan)) {
			return Status.OUTPUT_FULL;
		}
		if (!this.hasReagent(plan)) {
			return Status.NO_REAGENT;
		}
		if (!this.reagentFits(plan)) {
			return Status.WRONG_REAGENT;
		}
		if (this.profile.usesPower() && this.energy.amount < this.currentDraw()) {
			return Status.NO_POWER;
		}
		return Status.RUNNING;
	}

	/**
	 * 跑完一个炉次：付试剂、抽料、倒渣、出锭。
	 *
	 * <p>四个动作共用同一个 {@link Metallurgy#plan} 的结果，所以罐里少一分料、渣就少一分、
	 * 锭上就少一分成分，不会各自为政。
	 */
	private void completeBatch() {
		Metallurgy.Batch plan = this.plan();
		if (plan.empty() || plan.lean()) {
			return;
		}
		// 这一批到此为止，下一批要重新掷骰
		this.batchRolled = false;

		// 1) 辅助剂：电极按耐久扣，其余按当量从池里扣
		if (this.profile.reagent() == SmelterProfile.ReagentKind.ELECTRODE) {
			this.wearElectrode();
		} else {
			this.payReagent(plan.reagentUnits());
		}

		// 2) 抽走这一批料（按比例带走每一相）
		this.input.drainProportional(plan.volume());

		// 3) 渣直接进渣罐
		this.slag.insertMap(plan.slag());

		// 4) 出产物
		if (plan.ingot()) {
			Map<String, Double> composition = Ingots.normalize(plan.product());
			if (!composition.isEmpty()) {
				this.mergeIntoOutput(Ingots.create(composition, 1));
			}
		} else {
			this.mergeIntoOutput(new ItemStack(ModItems.CARBON_POWDER, 1));
		}
		setChanged();
	}

	/** 产品能不能塞进产物槽：空槽可以，或者里面那摞成分完全一样的还能再叠。 */
	private boolean canAcceptProduct(Metallurgy.Batch plan) {
		ItemStack existing = this.items.getItem(SLOT_OUTPUT);
		if (existing.isEmpty()) {
			return true;
		}
		ItemStack prospective = this.prospectiveProduct(plan);
		// 这一批什么都不出（掷骰没中）时不该占着槽 —— 判成「产物槽满」会让人去搬空气
		if (prospective.isEmpty()) {
			return true;
		}
		return ItemStack.isSameItemSameComponents(existing, prospective)
				&& existing.getCount() < existing.getMaxStackSize();
	}

	private ItemStack prospectiveProduct(Metallurgy.Batch plan) {
		if (!plan.ingot()) {
			return new ItemStack(ModItems.CARBON_POWDER, 1);
		}
		Map<String, Double> composition = Ingots.normalize(plan.product());
		return composition.isEmpty() ? ItemStack.EMPTY : Ingots.create(composition, 1);
	}

	private void mergeIntoOutput(ItemStack stack) {
		ItemStack existing = this.items.getItem(SLOT_OUTPUT);
		if (existing.isEmpty()) {
			this.items.setItem(SLOT_OUTPUT, stack);
			return;
		}
		if (ItemStack.isSameItemSameComponents(existing, stack)) {
			existing.grow(stack.getCount());
			this.items.setChanged();
		}
	}

	// ------------------------------------------------------------------ 试剂

	/**
	 * 这个物品能不能进试剂槽 —— 规则收在 {@link SmelterProfile.ReagentKind#accepts} 一处。
	 *
	 * <p>额外加一道与化学无关的「一次一件」守卫：一摞桶共享一个内容组件，整摞处理会凭空造料 /
	 * 销料（bug#12 / bug#26）。守卫收在 {@link Reagent#oneAtATime}，客户端空壳槽照用同一句。
	 */
	private boolean acceptsReagent(ItemStack stack) {
		return this.profile.reagent().accepts(stack) && Reagent.oneAtATime(stack);
	}

	/**
	 * 试剂够不够跑完这一炉。
	 *
	 * <p>电解炉要<b>两根电极同时在位</b> —— 这是一台电解池，缺一根就不成回路。
	 */
	private boolean hasReagent(Metallurgy.Batch plan) {
		if (this.profile.reagent() == SmelterProfile.ReagentKind.ELECTRODE) {
			return this.usableElectrode(SLOT_REAGENT)
					&& (!this.usesCathode() || this.usableElectrode(SLOT_CATHODE));
		}
		return this.canPayReagent(plan.reagentUnits());
	}

	/** 这一格的电极还在不在役（是电极，且没磨穿）。 */
	private boolean usableElectrode(int slot) {
		ItemStack electrode = this.items.getItem(slot);
		return Reagent.isElectrode(electrode) && electrode.getDamageValue() < electrode.getMaxDamage();
	}

	/** 这台炉子是不是两电极的电解池。 */
	public boolean usesCathode() {
		return this.profile.reagent() == SmelterProfile.ReagentKind.ELECTRODE;
	}

	/**
	 * 试剂池加上槽里那一摞，够不够付这一批的当量。
	 *
	 * <p>要按<b>整摞</b>算，不能只算最上面那一件：一炉脏料要的当量能冒到 1.6，
	 * 而一个煤炭只有 0.8 —— 只看一件的话，玩家槽里堆着 64 个煤也会被报「缺辅助剂」。
	 */
	private boolean canPayReagent(double units) {
		if (this.reagentCharge >= units) {
			return true;
		}
		ItemStack stack = this.items.getItem(SLOT_REAGENT);
		if (stack.isEmpty() || !this.acceptsReagent(stack)) {
			return false;
		}
		// 气体桶：账按 mol 现推（抽走多少气体记多少当量），不是「一件 = 固定当量」
		if (Reagent.isGasReductant(stack)) {
			return this.reagentCharge + Reagent.gasUnits(stack) >= units;
		}
		return this.reagentCharge + Reagent.units(stack) * stack.getCount() >= units;
	}

	/**
	 * 试剂对不对路。
	 *
	 * <p><b>活动性闸门只属于置换炉</b> —— 置换就是「用更活泼的金属把目标顶出来」，
	 * 插一根不如目标的金属要报 WRONG_REAGENT。还原炉不在这里卡玩家：碳系是通用还原剂，
	 * 金属只是可选替代（用户 2026-10-17 口径：「置换才需要金属，还原不刚需金属」）。
	 */
	private boolean reagentFits(Metallurgy.Batch plan) {
		if (this.profile.reagent() != SmelterProfile.ReagentKind.DISPLACEMENT) {
			// 还原炉（碳系 / 金属都收）与电解炉（电极）没有「挑金属」这回事
			return true;
		}
		ItemStack stack = this.items.getItem(SLOT_REAGENT);
		String metal = stack.isEmpty() ? null : Reagent.metalOf(stack);
		if (metal == null) {
			return false; // 置换路只能吃金属（准入在 acceptsReagent，这里兜底）
		}
		for (String product : plan.metals().keySet()) {
			if (MetalSeries.isKnownMetal(product) && !MetalSeries.canDisplace(metal, product)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 从池里扣当量，不够就从槽里补。
	 *
	 * <p>两种补法：
	 * <ul>
	 *   <li><b>普通试剂</b>（碳系 / 金属锭）—— 取走<b>整件</b>、按件计当量，剩一支放回槽；</li>
	 *   <li><b>气体桶</b>（CO / H₂）—— 从桶里<b>抽走气体 mol</b>（走 {@link MolCarrier} 路径），
	 *       空桶原地留下，桶本身不消耗。</li>
	 * </ul>
	 */
	private void payReagent(double units) {
		while (this.reagentCharge < units) {
			ItemStack stack = this.items.getItem(SLOT_REAGENT);
			if (stack.isEmpty() || !this.acceptsReagent(stack)) {
				break;
			}
			if (Reagent.isGasReductant(stack)) {
				// 一次一件的守卫已在 acceptsReagent 里（一摞桶不改共享组件）
				double drawn = this.drawGas(stack, units - this.reagentCharge);
				if (drawn <= 1.0e-9) {
					break;
				}
				this.reagentCharge += drawn;
				continue;
			}
			this.reagentIsCarbon = Reagent.isCarbon(stack);
			this.reagentCharge += Reagent.units(stack);
			ItemStack left = stack.copy();
			left.shrink(1);
			this.items.setItem(SLOT_REAGENT, left.isEmpty() ? ItemStack.EMPTY : left);
		}
		this.reagentCharge = Math.max(0.0, this.reagentCharge - units);
	}

	/**
	 * 从槽里的气体桶抽走 {@code wantUnits} 当量的 CO / H₂，返回实际抽到的当量。
	 *
	 * <p><b>消耗语义</b>：抽的是桶里的<b>气体 mol</b>，不是桶本身 —— 走 {@code MolCarrier.take}
	 * （与反应釜 {@code pullBucket} 同一条路径），抽完的空桶原地写回同一格，
	 * 道具槽内容变了由菜单下一次同步广播给客户端。桶的 mol 账与它在这台机器里的账永远同步：
	 * 先按 {@link ReductantGas#draw} 从账上扣，再按扣出来的账写回载具。
	 */
	private double drawGas(ItemStack stack, double wantUnits) {
		ItemStack bucket = stack.copy();
		double drawn = drawGasInto(bucket, wantUnits);
		if (drawn <= 0.0) {
			return 0.0;
		}
		// 抽完（可能只剩一部分气体）的桶写回同一格 —— 空桶也是一只桶（一次一件）
		this.items.setItem(SLOT_REAGENT, bucket);
		this.items.setChanged();
		return drawn;
	}

	/**
	 * 就地从 {@code stack} 这只气体桶抽走 {@code wantUnits} 当量的气体，返回实际抽到的当量。
	 *
	 * <p><b>纯道具操作，不碰机器状态</b>：便于离线 harness 直接复算「抽了多少 mol、剩多少」。
	 * 抽的是气体 mol（{@code MolCarrier.take}），抽空后同一件物品仍是空桶。
	 */
	static double drawGasInto(ItemStack stack, double wantUnits) {
		if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof MolCarrier carrier)) {
			return 0.0;
		}
		MolStock content = carrier.stock(stack);
		if (content.isEmpty()) {
			return 0.0;
		}
		MolStock drawn = ReductantGas.draw(content, wantUnits);
		if (drawn.isEmpty()) {
			return 0.0;
		}
		for (Map.Entry<String, Double> e : drawn.view().entrySet()) {
			carrier.take(stack, e.getKey(), e.getValue());
		}
		return ReductantGas.units(drawn);
	}

	/**
	 * 一个炉次之后扣电极。
	 *
	 * <p>阳极每炉扣一点 —— 它被电解氧化（碳棒变成 CO₂ 跑掉），本来就烧得快；
	 * 阴极只传电、不参与反应，每 {@link #BATCHES_PER_CATHODE_WEAR} 炉才扣一点。
	 * 所以同一根碳棒插在阴极上能顶四倍时间，把便宜货插在阴极、把好料留给阳极是划算的。
	 */
	private void wearElectrode() {
		this.wearElectrode(SLOT_REAGENT);
		if (this.usesCathode() && ++this.cathodeWear >= BATCHES_PER_CATHODE_WEAR) {
			this.cathodeWear = 0;
			this.wearElectrode(SLOT_CATHODE);
		}
	}

	/** 扣穿一点；扣穿了槽就空了，得再插一根。 */
	private void wearElectrode(int slot) {
		ItemStack electrode = this.items.getItem(slot);
		if (!Reagent.isElectrode(electrode)) {
			return;
		}
		int damage = electrode.getDamageValue() + 1;
		if (damage >= electrode.getMaxDamage()) {
			this.items.setItem(slot, ItemStack.EMPTY);
		} else {
			electrode.setDamageValue(damage);
			this.items.setChanged();
		}
	}

	// ------------------------------------------------------------------ 批次推演缓存

	/**
	 * 当前罐内成分的批次推演结果。
	 *
	 * <p>同一刻内多处要用（状态判定、功率、界面读数、完工产出），所以按游戏刻缓存一份 ——
	 * 罐内成分一刻之内不会变，重复算没有意义。
	 */
	private Metallurgy.Batch plan() {
		long tick = this.level == null ? Long.MIN_VALUE : this.level.getGameTime();
		if (this.cachedPlan == null || tick != this.cachedTick) {
			this.cachedTick = tick;
			this.cachedPlan = Metallurgy.plan(this.route(), this.input.contents(), Metallurgy.BATCH,
					this.charges());
		}
		return this.cachedPlan;
	}

	/**
	 * 这一批的工况：用不用碳系还原剂、掷骰结果如何。
	 *
	 * <p>没开工时给的是<b>期望值</b>（掷骰那一项按概率折算），开工之后给的是这一批的真点数。
	 * 界面上那口饼图因此始终是「这一炉真会出什么」，而不是一句空话。
	 */
	private Metallurgy.Charges charges() {
		boolean carbon = this.carbonReduction();
		return this.batchRolled
				? Metallurgy.Charges.rolled(carbon, this.batchRoll)
				: Metallurgy.Charges.expect(carbon);
	}

	/**
	 * 这一批用的是不是碳系还原剂。
	 *
	 * <p>槽空了就沿用最后一次付掉的那件的身份 —— 试剂池里剩下的当量是它留下的，
	 * 记账得记在同一件东西上。
	 */
	private boolean carbonReduction() {
		if (this.profile.reagent() != SmelterProfile.ReagentKind.REDUCTANT) {
			return false;
		}
		ItemStack stack = this.items.getItem(SLOT_REAGENT);
		return stack.isEmpty() ? this.reagentIsCarbon : Reagent.isCarbon(stack);
	}

	/** 界面用：计划产物的成分（按占比降序）。 */
	public List<Map.Entry<String, Double>> plannedProduct() {
		Map<String, Double> product = this.plan().product();
		if (product.isEmpty()) {
			return List.of();
		}
		List<Map.Entry<String, Double>> list = new ArrayList<>(product.entrySet());
		list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
		return list;
	}

	/** 界面用：进料罐里的主要相（按占比降序）。 */
	public List<Map.Entry<SlurryPart, Double>> inputPhases() {
		return this.input.sortedContents();
	}

	// ------------------------------------------------------------------ 面的搬运

	private void pullInput(Level level, BlockPos pos) {
		// 电解炉有溶液档与熔盐档两套面角色：熔盐档的粉进面是 ElectrolysisRole.POWDER_IN，
		// 溶液档的进料走 tickSolution（MolTank），两者互不相干。
		if (this.input.room() <= 0.0) {
			return;
		}
		for (Direction side : Direction.values()) {
			if (!this.acceptsPowderIn(side)) {
				continue;
			}
			SlurryHandler source = SlurryProvider.find(level, pos, side);
			if (source != null) {
				SlurryTransfer.move(source, this.input, TRANSFER_RATE);
			}
		}
	}

	private void pushSlag(Level level, BlockPos pos) {
		if (this.slag.isEmpty()) {
			return;
		}
		for (Direction side : Direction.values()) {
			if (!this.acceptsSlagOut(side)) {
				continue;
			}
			SlurryHandler dest = SlurryProvider.find(level, pos, side);
			if (dest != null) {
				SlurryTransfer.move(this.slag, dest, TRANSFER_RATE);
			}
		}
	}

	/**
	 * 这一面是不是本机的矿粉入口。
	 *
	 * <p>电解炉两档并存，所以这一处必须点名用哪一套角色：熔盐档是
	 * {@link ElectrolysisRole#POWDER_IN}，还原 / 置换两路仍是 {@link PortRole#INPUT}。
	 * 之前电解路在这里直接 return，把熔盐档的进料整条短路了（bug#18）。
	 */
	private boolean acceptsPowderIn(Direction side) {
		if (this.usesSolution()) {
			return this.electroRoles[side.ordinal()] == ElectrolysisRole.POWDER_IN;
		}
		return this.roles[side.ordinal()] == PortRole.INPUT;
	}

	/** 这一面是不是本机的渣出口：电解熔盐档 {@link ElectrolysisRole#SLAG_OUT}，其余两路 {@link PortRole#BYPRODUCT}。 */
	private boolean acceptsSlagOut(Direction side) {
		if (this.usesSolution()) {
			return this.electroRoles[side.ordinal()] == ElectrolysisRole.SLAG_OUT;
		}
		return this.roles[side.ordinal()] == PortRole.BYPRODUCT;
	}

	// ------------------------------------------------------------------ 溶液电解档（设计稿 §二.1）

	/** 这台炉子是不是带溶液电解档（只有电解炉是）。 */
	public boolean usesSolution() {
		return this.profile.reagent() == SmelterProfile.ReagentKind.ELECTRODE;
	}

	/** 溶液电解账（界面 / 测试读）。 */
	public MolStock solution() {
		return this.solution;
	}

	/** 阳极容器账（界面 / 测试读）。 */
	public MolStock anode() {
		return this.anode;
	}

	/** 阴极容器账（界面 / 测试读）。 */
	public MolStock cathode() {
		return this.cathode;
	}

	/** 上一刻溶液电解跑了几份。 */
	public int solutionRuns() {
		return this.solutionRuns;
	}

	/** 电解容器堵住的那一相；没有堵住返回 {@code null}。服务端 / 测试读。 */
	public Chemical.Phase blockedPhase() {
		return this.blockedPhase;
	}

	/** 电解容器堵住的那一相有多少 mol；没有堵住返回 0。服务端 / 测试读。 */
	public double blockedMol() {
		return this.blockedMol;
	}

	/**
	 * 每 tick 的溶液电解：抽料 → 按<b>逐反应单价</b>计电费跑 ELECTROLYSIS 反应 → 推产物。
	 *
	 * <p>与熔盐档（batch）互不干扰：这一档只动 {@link #solution}，熔盐那套仍走
	 * {@link #input} / {@link #slag} 与 {@link #profile} 的炉次循环。
	 *
	 * <p><b>2026-10-17：电费从「全表一个 SOLUTION_FE_PER_MOL=120」改成逐反应单价</b>
	 * （{@link com.geometallurgy.chemical.MolBalance.Reaction#fePerMol()}：水电解 150、
	 * 氯碱 260、钾碱 300、锰酸钾 180、氟化氢 520 FE/mol 反应物）。
	 * 所以预算也换成<b>电预算（FE）</b>交给反应器：反应器按每条反应自己的单价折算「买得起几份」，
	 * 余额不够的反应直接不跑（bug#31 的口径不变：绝不先反应后欠费）。
	 */
	private void tickSolution(ServerLevel level, BlockPos pos) {
		if (!this.usesSolution()) {
			return;
		}
		this.pullSolution(level, pos);
		if (!this.solution.isEmpty() && this.energy.amount > 0L) {
			// 预算 = 缓冲里的电（FE），单价由反应自带 —— 便宜的电解水与昂贵的氟化氢各按各的价跑。
			// 传 SOLUTION_CAPACITY：非电极产物落回溶液，反应器按余量压缩推进份数，防止溢出（bug#19）
			Reactor.Outcome outcome = Reactor.runAll(this.solution, SOLUTION_RATE_PER_TICK, false,
					List.of(), Reactor.ELECTROLYSIS_KINDS, this::routeElectrodeProduct, SOLUTION_CAPACITY,
					1.0, (double) this.energy.amount);
			if (outcome.reactionsRun() > 0) {
				long cost = Math.max(0L, Math.round(outcome.feCost()));
				// 反应器已按预算卡过推进量，这里只做浮点取整的兜底钳制，绝不扣穿缓冲。
				this.energy.amount -= Math.min(this.energy.amount, cost);
				this.solutionRuns = outcome.reactionsRun();
				this.setChanged();
			}
		}
		// 三本账各推各的：溶液只推非电极产物，阳极 / 阴极只推自己那一本。
		this.pushSolution(level, pos);
		this.pushVessel(level, pos, this.anode, ANODE_PRODUCTS, ElectrolysisRole.ANODE_OUT);
		this.pushVessel(level, pos, this.cathode, CATHODE_PRODUCTS, ElectrolysisRole.CATHODE_OUT);
		this.updateBlockedVessel();
	}

	/**
	 * 记下电解容器里「堵住的是哪一相、多少 mol」。
	 *
	 * <p>三本账谁到容量就报谁：溶液满（{@link #SOLUTION_CAPACITY}）→ 报溶液里最多的相；
	 * 否则阳极 / 阴极满（{@link #VESSEL_CAPACITY}）→ 报那一本里最多的相。都不满就清空。
	 * 这是 {@code SmelterScreen} 那句「容器堵住：…」的数据来源（docs/科技线-v1.md §13.1）。
	 */
	private void updateBlockedVessel() {
		MolStock full = null;
		if (this.solution.totalMol() >= SOLUTION_CAPACITY - MIN_BATCH) {
			full = this.solution;
		} else if (this.anode.totalMol() >= VESSEL_CAPACITY - MIN_BATCH) {
			full = this.anode;
		} else if (this.cathode.totalMol() >= VESSEL_CAPACITY - MIN_BATCH) {
			full = this.cathode;
		}
		OutputRouting.Blocked blocked = full == null ? OutputRouting.Blocked.none() : OutputRouting.blocked(full, MIN_BATCH);
		this.blockedPhase = blocked.phase();
		this.blockedMol = blocked.mol();
	}

	/**
	 * 把本轮产物按电极归属送进对应容器。
	 *
	 * <p>{@link MolBalance.Side#NONE} 的产物不经过这里（反应器直接落回 solution）。
	 * 容器满了就把这一份退回 solution —— 宁可先留在溶液里，也不能凭空蒸发。
	 */
	private void routeElectrodeProduct(MolBalance.Product product, double mol) {
		MolStock vessel = product.side() == MolBalance.Side.ANODE ? this.anode : this.cathode;
		double room = VESSEL_CAPACITY - vessel.totalMol();
		if (room <= MIN_BATCH) {
			this.solution.add(product.id(), mol);
			return;
		}
		double accepted = Math.min(room, mol);
		vessel.add(product.id(), accepted);
		if (accepted < mol) {
			this.solution.add(product.id(), mol - accepted);
		}
	}

	/** 从所有 {@link ElectrolysisRole#SOLUTION_IN} 面的邻罐（{@link MolTank}）抽溶液进账。 */
	private void pullSolution(ServerLevel level, BlockPos pos) {
		double room = SOLUTION_CAPACITY - this.solution.totalMol();
		for (Direction side : Direction.values()) {
			if (room <= MIN_BATCH) {
				break;
			}
			if (this.electroRoles[side.ordinal()] != ElectrolysisRole.SOLUTION_IN) {
				continue;
			}
			MolTank tank = MolTank.neighbor(level, pos, side);
			if (tank == null) {
				continue;
			}
			double want = Math.min(room, Math.min(tank.room(), SOLUTION_TRANSFER_MOL));
			if (want <= MIN_BATCH) {
				continue;
			}
			MolStock drawn = tank.drain(want, null);
			if (drawn.isEmpty()) {
				continue;
			}
			for (Map.Entry<String, Double> e : drawn.view().entrySet()) {
				this.solution.add(e.getKey(), e.getValue());
			}
			room = SOLUTION_CAPACITY - this.solution.totalMol();
			this.setChanged();
		}
	}

	/**
	 * 把溶液账里的非电极产物过户给所有 {@link ElectrolysisRole#SOLUTION_OUT} 面的邻罐。
	 *
	 * <p>只推 {@link #SOLUTION_PRODUCTS} 里的物质 —— 电极产物走各自的电极面，
	 * 原料留在账上继续反应。
	 */
	private void pushSolution(ServerLevel level, BlockPos pos) {
		if (this.solution.isEmpty()) {
			return;
		}
		for (Direction side : Direction.values()) {
			if (this.electroRoles[side.ordinal()] != ElectrolysisRole.SOLUTION_OUT) {
				continue;
			}
			MolTank tank = MolTank.neighbor(level, pos, side);
			if (tank == null) {
				continue;
			}
			double room = Math.min(tank.room(), SOLUTION_TRANSFER_MOL);
			if (room <= MIN_BATCH) {
				continue;
			}
			MolStock load = takeSolutionProducts(room);
			if (load.isEmpty() || !tank.holds(load)) {
				continue;
			}
			MolStock sent = load.copy();
			MolStock accepted = tank.pour(load, room);
			if (accepted.isEmpty()) {
				continue;
			}
			double ratio = accepted.totalMol() / sent.totalMol();
			for (Map.Entry<String, Double> e : sent.view().entrySet()) {
				this.solution.take(e.getKey(), e.getValue() * ratio);
			}
			this.setChanged();
		}
	}

	/** 从溶液账里取最多 {@code max} mol 的电解产物（新账，不动原账）。 */
	private MolStock takeSolutionProducts(double max) {
		return takeUpTo(this.solution, max, SOLUTION_PRODUCTS);
	}

	/**
	 * 从账里取最多 {@code max} mol；{@code filter} 非空时只取表内的物质（新账，不动原账）。
	 */
	private MolStock takeUpTo(MolStock stock, double max, java.util.Set<String> filter) {
		MolStock out = MolStock.empty();
		double left = max;
		for (Map.Entry<String, Double> e : stock.view().entrySet()) {
			if (left <= MIN_BATCH) {
				break;
			}
			if (filter != null && !filter.contains(e.getKey())) {
				continue;
			}
			double take = Math.min(e.getValue(), left);
			if (take <= MIN_BATCH) {
				continue;
			}
			out.add(e.getKey(), take);
			left -= take;
		}
		return out;
	}

	/**
	 * 把溶液账里属于某电极的溢流收回对应容器。
	 *
	 * <p>电极容器满的时候 {@link #routeElectrodeProduct} 会把那一份退回溶液；出料前先把退回来的
	 * 收回去，电极账才能自己管自己。只认本电极的物质，绝不碰溶液原料。
	 */
	private void absorbFromSolution(MolStock vessel, java.util.Set<String> allowed) {
		double room = VESSEL_CAPACITY - vessel.totalMol();
		if (room <= MIN_BATCH) {
			return;
		}
		for (Map.Entry<String, Double> e : new ArrayList<>(this.solution.view().entrySet())) {
			if (room <= MIN_BATCH) {
				break;
			}
			if (!allowed.contains(e.getKey())) {
				continue;
			}
			double move = Math.min(e.getValue(), room);
			if (move <= MIN_BATCH) {
				continue;
			}
			double taken = this.solution.take(e.getKey(), move);
			if (taken <= MIN_BATCH) {
				continue;
			}
			vessel.add(e.getKey(), taken);
			room -= taken;
			this.setChanged();
		}
	}

	/**
	 * 把一本电极账过户给所有该电极出料面的邻罐。
	 *
	 * <p>出料前先把溶液里的本电极溢流收回来（见 {@link #absorbFromSolution}）。
	 * 阳极面只认 {@link ElectrolysisRole#ANODE_OUT}，阴极面只认
	 * {@link ElectrolysisRole#CATHODE_OUT}，两面不会互相串料。
	 */
	private void pushVessel(ServerLevel level, BlockPos pos, MolStock vessel,
			java.util.Set<String> allowed, ElectrolysisRole role) {
		this.absorbFromSolution(vessel, allowed);
		if (vessel.isEmpty()) {
			return;
		}
		for (Direction side : Direction.values()) {
			if (this.electroRoles[side.ordinal()] != role) {
				continue;
			}
			MolTank tank = MolTank.neighbor(level, pos, side);
			if (tank == null) {
				continue;
			}
			double room = Math.min(tank.room(), SOLUTION_TRANSFER_MOL);
			if (room <= MIN_BATCH) {
				continue;
			}
			MolStock load = takeUpTo(vessel, room, null);
			if (load.isEmpty() || !tank.holds(load)) {
				continue;
			}
			MolStock sent = load.copy();
			MolStock accepted = tank.pour(load, room);
			if (accepted.isEmpty()) {
				continue;
			}
			double ratio = accepted.totalMol() / sent.totalMol();
			for (Map.Entry<String, Double> e : sent.view().entrySet()) {
				vessel.take(e.getKey(), e.getValue() * ratio);
			}
			this.setChanged();
		}
	}

	@Override
	public SlurryHandler slurryAt(Direction side) {
		// 电解炉两档并存：熔盐档收 / 放矿粉与渣，溶液档只走 MolTank（见 tickSolution）。
		// 之前电解路在这里整个返回 null，上游推不进来、渣也排不出去（bug#18）。
		if (this.usesSolution()) {
			return switch (this.electroRoles[side.ordinal()]) {
				case POWDER_IN -> this.inputPort;
				case SLAG_OUT -> this.slagPort;
				default -> null;
			};
		}
		return switch (this.roles[side.ordinal()]) {
			case INPUT -> this.inputPort;
			case BYPRODUCT -> this.slagPort;
			default -> null;
		};
	}

	/** 一个面上的罐句柄。 */
	private record TankHandler(VirtualSlurry tank, boolean canInsert, boolean canExtract)
			implements SlurryHandler {
	}

	// ------------------------------------------------------------------ 数据同步

	private int readData(int index) {
		Metallurgy.Batch plan = this.plan();
		// 两段成分表按位置判，不走 switch：区间判定写在一处，界面那边照抄同一组常量就不会错位
		if (index >= INPUT_BASE && index < INPUT_BASE + TRACKED_ENTRIES * 2) {
			return this.readInputEntry(index - INPUT_BASE);
		}
		if (index >= PRODUCT_BASE && index < PRODUCT_BASE + TRACKED_ENTRIES * 2) {
			return this.readProductEntry(index - PRODUCT_BASE);
		}
		if (index == SOLUTION_MOL_INDEX) {
			return centi(this.solution.totalMol());
		}
		if (index == SOLUTION_COUNT_INDEX) {
			return Math.min(TRACKED_ENTRIES, this.solution.view().size());
		}
		if (index >= SOLUTION_BASE && index < SOLUTION_BASE + TRACKED_ENTRIES * 2) {
			return this.readStockEntry(index - SOLUTION_BASE, this.solution);
		}
		if (index == ANODE_MOL_INDEX) {
			return centi(this.anode.totalMol());
		}
		if (index == ANODE_COUNT_INDEX) {
			return Math.min(TRACKED_ENTRIES, this.anode.view().size());
		}
		if (index >= ANODE_BASE && index < ANODE_BASE + TRACKED_ENTRIES * 2) {
			return this.readStockEntry(index - ANODE_BASE, this.anode);
		}
		if (index == CATHODE_MOL_INDEX) {
			return centi(this.cathode.totalMol());
		}
		if (index == CATHODE_COUNT_INDEX) {
			return Math.min(TRACKED_ENTRIES, this.cathode.view().size());
		}
		if (index >= CATHODE_BASE && index < CATHODE_BASE + TRACKED_ENTRIES * 2) {
			return this.readStockEntry(index - CATHODE_BASE, this.cathode);
		}
		return switch (index) {
			case 0 -> (int) (this.energy.amount / ENERGY_SYNC_DIVISOR);
			case 1 -> (int) Math.round(this.power * 10.0);
			case 2 -> this.status.ordinal();
			case 3 -> (int) Math.round(this.input.amount());
			case 4 -> (int) Math.round(this.slag.amount());
			case 5 -> this.usesSolution()
					? this.packRoles() & ELECTRO_LOW_MASK
					: this.packRoles();
			case ROLES_B_INDEX -> this.usesSolution()
					? (this.packRoles() >> (3 * ELECTRO_BITS)) & ELECTRO_LOW_MASK
					: 0;
			case 6 -> this.progress;
			case 7 -> (int) Math.round(plan.purity() * 1000.0);
			case 8 -> (int) Math.round(plan.metalMass() * YIELD_SYNC_SCALE);
			case 9 -> (int) Math.round(this.reagentCharge * 1000.0);
			// 条数必须自己同步：界面拿不到服务端的 List，只能靠这个数决定读几组
			case 10 -> Math.min(TRACKED_ENTRIES, this.inputPhases().size());
			case PRODUCT_COUNT_INDEX -> Math.min(TRACKED_ENTRIES, this.plannedProduct().size());
			case BLOCK_PHASE_INDEX -> OutputRouting.ordinal(this.blockedPhase);
			case BLOCK_MOL_INDEX -> centi(this.blockedMol);
			default -> 0;
		};
	}

	private int readInputEntry(int slot) {
		List<Map.Entry<SlurryPart, Double>> phases = this.inputPhases();
		int entryIndex = slot / 2;
		if (entryIndex >= phases.size()) {
			return 0;
		}
		Map.Entry<SlurryPart, Double> entry = phases.get(entryIndex);
		if (slot % 2 == 0) {
			return SlurryParts.encode(entry.getKey());
		}
		double total = Math.max(this.input.amount(), 1.0e-9);
		return (int) Math.round(entry.getValue() / total * 1000.0);
	}

	private int readProductEntry(int slot) {
		List<Map.Entry<String, Double>> product = this.plannedProduct();
		int entryIndex = slot / 2;
		if (entryIndex >= product.size()) {
			return 0;
		}
		Map.Entry<String, Double> entry = product.get(entryIndex);
		if (slot % 2 == 0) {
			return Substance.indexOf(entry.getKey());
		}
		double total = 0.0;
		for (double value : this.plan().product().values()) {
			total += value;
		}
		return total <= 0.0 ? 0 : (int) Math.round(entry.getValue() / total * 1000.0);
	}

	/** mol → 百分之一 mol（同步用，钳到 16 位 short 范围）。 */
	private static int centi(double mol) {
		return (int) Math.max(0L, Math.min(32767L, Math.round(mol * 100.0)));
	}

	/** 一份 mol 账的成分同步值：偶数格 = 物质序号 + 1，奇数格 = 占比千分位（与别的成分表同构）。 */
	private int readStockEntry(int slot, MolStock stock) {
		List<Map.Entry<String, Double>> sorted = new ArrayList<>(stock.view().entrySet());
		sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
		int entryIndex = slot / 2;
		if (entryIndex >= sorted.size()) {
			return 0;
		}
		Map.Entry<String, Double> entry = sorted.get(entryIndex);
		if (slot % 2 == 0) {
			return com.geometallurgy.chemical.Chemical.indexOf(entry.getKey()) + 1;
		}
		double total = Math.max(1.0e-9, stock.totalMol());
		return (int) Math.max(0L, Math.min(1000L, Math.round(entry.getValue() / total * 1000.0)));
	}

	/** 这台炉子的面角色每面占几位：共用 {@link PortRole} 2 bit，电解 {@link ElectrolysisRole} 3 bit。 */
	public int roleBits() {
		return this.usesSolution() ? ELECTRO_BITS : FaceConfig.BITS;
	}

	/** 这台炉子的面角色取值个数。 */
	public int roleCount() {
		return this.usesSolution() ? ElectrolysisRole.values().length : PortRole.values().length;
	}

	/** 六面角色打包；电解路 18 bit，其余路仍是 12 bit 的 {@link FaceConfig} 格式。 */
	private int packRoles() {
		return this.usesSolution() ? this.packElectroRoles() : FaceConfig.pack(this.roles);
	}

	private void unpackRoles(int packed) {
		if (this.usesSolution()) {
			this.unpackElectroRoles(packed);
			return;
		}
		FaceConfig.unpack(packed, this.roles, PortRole.values(), PortRole.DISABLED);
	}

	/** 电解路六面角色打包：每面 3 bit，低三面占 0~8，高三面占 9~17。 */
	private int packElectroRoles() {
		int packed = 0;
		for (int i = 0; i < 6; i++) {
			packed |= (this.electroRoles[i].ordinal() & ELECTRO_MASK) << (i * ELECTRO_BITS);
		}
		return packed;
	}

	private void unpackElectroRoles(int packed) {
		ElectrolysisRole[] values = ElectrolysisRole.values();
		for (int i = 0; i < 6; i++) {
			int index = (packed >>> (i * ELECTRO_BITS)) & ELECTRO_MASK;
			this.electroRoles[i] = index < values.length ? values[index] : ElectrolysisRole.DISABLED;
		}
	}

	/**
	 * 旧存档迁移：过去电解炉与其余两路共用 {@link PortRole}，六个面记在 {@code roles} 里。
	 * 现在第一次读到的电解存档没有 {@code electro_roles}，就把旧值按语义折成新角色 ——
	 * {@code INPUT} 是溶液入，{@code OUTPUT / BYPRODUCT} 都是溶液出。
	 */
	private void migrateSharedRolesToElectro(int packed) {
		java.util.Arrays.fill(this.electroRoles, ElectrolysisRole.DISABLED);
		PortRole[] values = PortRole.values();
		for (int i = 0; i < 6; i++) {
			int index = (packed >>> (i * FaceConfig.BITS)) & ((1 << FaceConfig.BITS) - 1);
			PortRole role = index < values.length ? values[index] : PortRole.DISABLED;
			this.electroRoles[i] = switch (role) {
				case INPUT -> ElectrolysisRole.SOLUTION_IN;
				case OUTPUT, BYPRODUCT -> ElectrolysisRole.SOLUTION_OUT;
				case DISABLED -> ElectrolysisRole.DISABLED;
			};
		}
	}

	// ------------------------------------------------------------------ 六面配置对外接口

	@Override
	public Identifier faceConfigKind() {
		return FaceConfigurable.kindOf(this);
	}

	@Override
	public int packFaces() {
		return this.packRoles();
	}

	@Override
	public void applyFaces(int packed) {
		this.unpackRoles(packed);
		setChanged();
	}

	/** 界面上的按钮点击：0~5 用 {@link Direction#ordinal()} 指代六个面。 */
	public boolean handleButton(int id) {
		Direction[] directions = Direction.values();
		if (id >= 0 && id < directions.length) {
			int index = directions[id].ordinal();
			if (this.usesSolution()) {
				this.electroRoles[index] = this.electroRoles[index].next();
			} else {
				this.roles[index] = this.roles[index].next();
			}
			setChanged();
			return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ 访问器

	public ContainerData getContainerData() {
		return this.dataAccess;
	}

	public VirtualSlurry input() {
		return this.input;
	}

	public VirtualSlurry slag() {
		return this.slag;
	}

	public PortRole role(Direction side) {
		return this.roles[side.ordinal()];
	}

	/** 电解路某一面的角色（只有电解炉有意义）。 */
	public ElectrolysisRole electroRole(Direction side) {
		return this.electroRoles[side.ordinal()];
	}

	public double power() {
		return this.power;
	}

	public Status status() {
		return this.status;
	}

	public int progress() {
		return this.progress;
	}

	public long storedEnergy() {
		return this.energy.amount;
	}

	public double reagentCharge() {
		return this.reagentCharge;
	}

	/** 界面用：上一刻实际耗掉的电（FE/t）；停机时为 0。 */
	public long drawPerTick() {
		return this.status == Status.RUNNING ? this.currentDraw() : 0L;
	}

	public ItemStack reagentStack() {
		return this.items.getItem(SLOT_REAGENT);
	}

	/** 界面用：阴极槽（只有电解炉会用到）。 */
	public ItemStack cathodeStack() {
		return this.items.getItem(SLOT_CATHODE);
	}

	public ItemStack outputStack() {
		return this.items.getItem(SLOT_OUTPUT);
	}

	public EnergyStorage getEnergyStorage() {
		// 不用电的炉子对外暴露「没有储能源」，这样发电机与无线网都不会给它推电。
		return this.profile.usesPower() ? this.energy : null;
	}

	// ------------------------------------------------------------------ 容器

	@Override
	public int getContainerSize() {
		return SLOT_COUNT;
	}

	@Override
	public boolean isEmpty() {
		return this.items.isEmpty();
	}

	@Override
	public ItemStack getItem(int slot) {
		return this.items.getItem(slot);
	}

	@Override
	public ItemStack removeItem(int slot, int amount) {
		return this.items.removeItem(slot, amount);
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		return this.items.removeItemNoUpdate(slot);
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		this.items.setItem(slot, stack);
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return this.items.canPlaceItem(slot, stack);
	}

	@Override
	public void clearContent() {
		this.items.clearContent();
	}

	@Override
	public boolean stillValid(Player player) {
		return Container.stillValidBlockEntity(this, player);
	}

	// 漏斗的六个面：进试剂的那几面收件，标了「出」的那一面（以及下方）可以抽走产物。

	@Override
	public int[] getSlotsForFace(Direction side) {
		int[] reagentSlots = this.usesCathode()
				? new int[]{SLOT_REAGENT, SLOT_CATHODE}
				: new int[]{SLOT_REAGENT};
		if (this.usesSolution()) {
			ElectrolysisRole role = this.electroRoles[side.ordinal()];
			if (role == ElectrolysisRole.DISABLED) {
				return new int[0];
			}
			if (role == ElectrolysisRole.SOLUTION_IN || role == ElectrolysisRole.POWDER_IN) {
				// 两个电极槽都对漏斗开：碳棒烧完了得有人续上
				return reagentSlots;
			}
			// 出料面（溶液 / 阳极 / 阴极 / 渣）：只管抽走产物槽
			return new int[]{SLOT_OUTPUT};
		}
		PortRole role = this.roles[side.ordinal()];
		if (role == PortRole.DISABLED) {
			return new int[0];
		}
		if (role == PortRole.OUTPUT) {
			return new int[]{SLOT_OUTPUT};
		}
		// 两个电极槽都对漏斗开：碳棒烧完了得有人续上
		return reagentSlots;
	}

	@Override
	public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side) {
		boolean reagentSlot = slot == SLOT_REAGENT
				|| (slot == SLOT_CATHODE && this.usesCathode());
		if (!reagentSlot || !this.acceptsReagent(stack)) {
			return false;
		}
		if (this.usesSolution()) {
			ElectrolysisRole role = this.electroRoles[side.ordinal()];
			return role == ElectrolysisRole.SOLUTION_IN || role == ElectrolysisRole.POWDER_IN;
		}
		PortRole role = this.roles[side.ordinal()];
		return role != PortRole.OUTPUT && role != PortRole.DISABLED;
	}

	@Override
	public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
		return slot == SLOT_OUTPUT;
	}

	/** 方块被拆时把槽里的东西弹出来 —— 半根钨棒也是钱。 */
	public void dropContents() {
		if (this.level == null) {
			return;
		}
		for (int slot = 0; slot < SLOT_COUNT; slot++) {
			ItemStack stack = this.items.getItem(slot);
			if (!stack.isEmpty()) {
				Block.popResource(this.level, this.worldPosition, stack);
				this.items.setItem(slot, ItemStack.EMPTY);
			}
		}
	}

	// ------------------------------------------------------------------ 界面

	@Override
	public Component getDisplayName() {
		return Component.translatable("block.geometallurgy." + this.profile.id());
	}

	@Override
	public AbstractContainerMenu createMenu(int syncId, Inventory inventory, Player player) {
		return new SmelterMenu(syncId, inventory, this, this.profile);
	}

	@Override
	public Unit getScreenOpeningData(ServerPlayer player) {
		return Unit.INSTANCE;
	}

	// ------------------------------------------------------------------ 存档

	/** 「id:mol;id:mol」——溶液账与输入罐同一套写法。 */
	private static String encodeSolution(MolStock stock) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Double> e : stock.view().entrySet()) {
			if (sb.length() > 0) {
				sb.append(';');
			}
			sb.append(e.getKey()).append(':').append(e.getValue());
		}
		return sb.toString();
	}

	private static void decodeSolution(MolStock stock, String text) {
		stock.clear();
		if (text == null || text.isEmpty()) {
			return;
		}
		for (String part : text.split(";")) {
			int colon = part.indexOf(':');
			if (colon <= 0) {
				continue;
			}
			try {
				stock.add(part.substring(0, colon), Double.parseDouble(part.substring(colon + 1)));
			} catch (NumberFormatException ignored) {
				// 坏数据跳过
			}
		}
	}

	@Override
	protected void saveAdditional(ValueOutput out) {
		super.saveAdditional(out);
		out.putLong("energy", this.energy.amount);
		// 共用角色照旧写 roles（其余两路原样解码）；电解路另写一份 3 bit 的配置。
		out.putInt("roles", FaceConfig.pack(this.roles));
		if (this.usesSolution()) {
			out.putInt("electro_roles", this.packElectroRoles());
		}
		out.putInt("progress", this.progress);
		out.putInt("batch_roll", (int) Math.round(this.batchRoll * 10000.0));
		out.putBoolean("batch_rolled", this.batchRolled);
		out.putInt("reagent_milli", (int) Math.round(this.reagentCharge * 1000.0));
		out.putInt("cathode_wear", this.cathodeWear);
		out.putInt("power_tenths", (int) Math.round(this.power * 10.0));
		out.putString("solution", encodeSolution(this.solution));
		out.putString("anode", encodeSolution(this.anode));
		out.putString("cathode", encodeSolution(this.cathode));
		out.putInt("solution_runs", this.solutionRuns);
		SlurryStore.store(out, "input", this.input);
		out.putInt("input_form", this.input.form().ordinal());
		SlurryStore.store(out, "slag", this.slag);
		// 物品槽整摞存 —— ItemStack.CODEC 连同组件一起带上，所以产物锭的成分不会丢
		this.items.storeAsItemList(out.list("items", ItemStack.CODEC));
	}

	@Override
	protected void loadAdditional(ValueInput in) {
		super.loadAdditional(in);
		this.energy.amount = in.getLongOr("energy", 0L);
		int sharedRoles = in.getIntOr("roles", -1);
		if (sharedRoles < 0) {
			java.util.Arrays.fill(this.roles, PortRole.DISABLED);
		} else {
			FaceConfig.unpack(sharedRoles, this.roles, PortRole.values(), PortRole.DISABLED);
		}
		if (this.usesSolution()) {
			int electroPacked = in.getIntOr("electro_roles", -1);
			if (electroPacked < 0) {
				// 旧电解存档：按旧共用角色的语义折成新角色
				this.migrateSharedRolesToElectro(Math.max(0, sharedRoles));
			} else {
				this.unpackElectroRoles(electroPacked);
			}
		} else {
			java.util.Arrays.fill(this.electroRoles, ElectrolysisRole.DISABLED);
		}
		this.progress = in.getIntOr("progress", 0);
		this.batchRoll = in.getIntOr("batch_roll", 5000) / 10000.0;
		this.batchRolled = in.getBooleanOr("batch_rolled", false);
		this.reagentCharge = in.getIntOr("reagent_milli", 0) / 1000.0;
		this.cathodeWear = in.getIntOr("cathode_wear", 0);
		this.power = in.getIntOr("power_tenths", 0) / 10.0;
		decodeSolution(this.solution, in.getStringOr("solution", ""));
		decodeSolution(this.anode, in.getStringOr("anode", ""));
		decodeSolution(this.cathode, in.getStringOr("cathode", ""));
		this.solutionRuns = in.getIntOr("solution_runs", 0);
		SlurryStore.load(in, "input", this.input, in.getIntOr("input_form", 0));
		SlurryStore.load(in, "slag", this.slag, SlurryForm.POWDER.ordinal());
		this.items.fromItemList(in.listOrEmpty("items", ItemStack.CODEC));
		this.cachedTick = Long.MIN_VALUE;
	}
}
