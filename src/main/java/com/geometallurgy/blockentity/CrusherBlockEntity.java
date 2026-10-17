package com.geometallurgy.blockentity;

import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.geometallurgy.block.CrusherBlock;
import com.geometallurgy.item.ModItems;
import com.geometallurgy.menu.CrusherMenu;
import com.geometallurgy.metal.Ingots;
import com.geometallurgy.slurry.SlurryForm;
import com.geometallurgy.slurry.SlurryHandler;
import com.geometallurgy.slurry.SlurryPart;
import com.geometallurgy.slurry.SlurryParts;
import com.geometallurgy.slurry.SlurryProps;
import com.geometallurgy.slurry.SlurryProvider;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import team.reborn.energy.api.EnergyStorage;
import team.reborn.energy.api.base.SimpleEnergyStorage;

/**
 * 粉碎机：把矿浆磨成矿粉。
 *
 * <h2>它<b>不</b>做分离</h2>
 * 这是最容易误解的一台机器。粉碎机只做一件事 —— <b>把颗粒磨细</b>，成分一个不少地照搬：
 * 进去多少赤铁矿、多少脉石，出来还是那些。所以「磨一下就能变纯」是不可能的，
 * 混合料磨成粉仍然是混合料，纯度一分不涨。
 *
 * <p>代价全都发生在<b>下游</b>：
 * <ul>
 *   <li>粉料进磁筛机时理想磁场窗口显著收窄（见 {@code MagneticSeparatorBlockEntity}），
 *       磁场控制稍偏就分不开 —— 精度要求凭空涨一大截。</li>
 *   <li>纯度低的矿粉在后续提炼工序里要多烧燃料、多耗还原剂；若是选了不对路的提炼路线
 *       （例如想用碳还原法对付氧化铝），还会直接失败。所以「先把料混匀了再磨」不是好主意。</li>
 * </ul>
 *
 * <h2>耗能按硬度走</h2>
 * 磨矿是<b>啃</b>而不是<b>倒</b>：磨 1 mB 需要的能量随料里颗粒的加权莫氏硬度上升
 * （{@code 8 FE/mB × (1 + 莫氏/4)}）。花岗岩（6.5）比石灰岩（3.0）贵一倍有余，
 * 钻石（10）更是啃得极慢。同一条产线上，供同样的电，硬料就是出得慢。
 */
public class CrusherBlockEntity extends BlockEntity
		implements ExtendedMenuProvider<Unit>, SlurryProvider, WorldlyContainer, FaceConfigurable {

	// ------------------------------------------------------------------ 功率与参数

	/**
	 * 单刻最多吃多少电（2026-10-17 用户口径：128 → 256）。
	 *
	 * <p>粉碎机没有「额定功率」——耗电完全按实际磨掉的 mB × 硬度算，见 {@link #crush()}。
	 * 这个上限只决定「这一刻最多能磨多少 mB」：256 / 56 FE/mB（钻石）= 4.57 mB ≥ {@link #RATE} 4，
	 * 所以任何料都跑得满速，RATE 才是真正的产能瓶颈。
	 */
	public static final long MAX_DRAW = 256L;
	/** 内部供电缓冲：40 刻的额定量（= {@link #MAX_DRAW} × 40）。 */
	public static final long CAPACITY = MAX_DRAW * 40L;
	/** 允许相邻发电机单刻推入的最大量：与额定耗电同值（本机单刻最多花 256，进电就不该更快）。 */
	public static final long MAX_INSERT = MAX_DRAW;
	/** 磁场读数那样的平滑系数。 */
	private static final double POWER_SMOOTHING = 0.25;

	/** 每刻最多磨多少矿浆（mB）。 */
	public static final double RATE = 4.0;
	/**
	 * 磨 1 mB 的基准能耗（FE）；实际值再乘上硬度系数（2026-10-17：8 → 16）。
	 *
	 * <p>新基准按额定功率反推：{@link #MAX_DRAW} 256 FE/t ÷ {@link #RATE} 4 mB/t = 64 FE/mB 是
	 * <b>满速上限</b>；基准取它的 1/4（莫氏 4.0 的料吃满一半额定功率）。旧基准 8 = 旧上限 32 的
	 * 1/4，比值一字未动，所以每一种料的 FE/mB 都恰好翻倍（磁铁矿 20 → 40）。
	 */
	public static final double BASE_ENERGY_PER_MB = 16.0;
	/** 单个面每刻的搬运量（mB）。 */
	public static final double TRANSFER_RATE = 16.0;

	/**
	 * 少于这么多就算「没有」。
	 *
	 * <p>抽取是按比例分配再取回的小数，罐底注定要剩一点浮点残渣；残渣不是料，
	 * 不能拿它去解释「机器为什么停了」。
	 */
	public static final double MIN_BATCH = 1.0e-3;

	/** 同步给界面的成分条数。 */
	public static final int TRACKED_ENTRIES = 4;
	/** 固定同步字段数。 */
	private static final int FIXED_DATA = 8;
	public static final int DATA_COUNT = FIXED_DATA + TRACKED_ENTRIES * 2;

	// ------------------------------------------------------------------ 物品槽（磨整件）

	/** 物品槽数量：0 进料、1 出料。 */
	public static final int ITEM_SLOTS = 2;
	public static final int SLOT_INPUT = 0;
	public static final int SLOT_OUTPUT = 1;

	/** 磨一件物品要几刻。 */
	public static final int GRIND_TICKS = 20;
	/**
	 * 磨一件物品期间每刻的耗电（FE），整件合计 = {@link #GRIND_TICKS} × 这个数 = 640 FE/件。
	 *
	 * <p>取 {@link #MAX_DRAW} 的 1/8（旧值 16 = 旧上限 128 的 1/8，比值不变）：整件路不肯按满功率
	 * 空烧电池，它只需要「一件东西慢慢磨」的那点电。若按满额定 256 FE/t 记，一炉碳粉要
	 * 1280 FE（木炭 → 4 碳粉），比它下游的所有工序加起来还贵，那是另一套平衡，不做。
	 */
	public static final long GRIND_DRAW = MAX_DRAW / 8L;

	/**
	 * 整件研磨表：能磨的物品 → 产物与产量。
	 *
	 * <p>与矿浆那条路是两码事。矿浆路磨的是<b>罐里的悬浮颗粒</b>，进度按 mB 算、不经过物品槽；
	 * 这条路磨的是<b>整件物品</b>（一根木炭），不占任何 mB 容量。两条路共用同一份电，
	 * 所以闸门只有一个，不会出现「磨粉磨到一半被人抢电」的情况。
	 *
	 * <p>为什么木炭是碳粉的正统来源：煤炭<b>不是</b>可再生物（正经来源只有凋灵骷髅掉的头），
	 * 木炭才是（树 → 原木 → 烧成炭，可以无限循环）。1 木炭 → 4 碳粉 也比原先
	 * 「2 煤炭 → 1 碳粉」的手工配方高一个量级，于是碳粉这条线整体挪到了可再生一侧。
	 */
	private static final Map<Item, Grind> GRINDING = Map.of(
			Items.CHARCOAL, new Grind(ModItems.CARBON_POWDER, 4));

	/** 一条研磨规则：磨出什么、磨出几个。 */
	private record Grind(Item product, int count) {
	}

	/**
	 * 这东西进得了进料槽吗。
	 *
	 * <p>客户端也要问同一个问题（{@code CrusherMenu} 的空壳槽用它），否则点击时客户端
	 * 会先把物品塞进去再被服务端打回来，中间那一瞬的错位看着像卡顿。
	 *
	 * <p>金属锭（{@link ModItems#INGOT}）是特例：它磨出来的金属粉要<b>原样带着成分</b>
	 * （见 {@link #grindProduct}），不是「物品 → 固定物品」查表能表达的，所以单独放行。
	 */
	public static boolean isGrindable(ItemStack stack) {
		return !stack.isEmpty()
				&& (stack.getItem() == ModItems.INGOT || GRINDING.containsKey(stack.getItem()));
	}

	public enum Status {
		/** 输入罐是空的。 */
		IDLE("idle", false),
		/** 电不够磨动一批料。 */
		NO_POWER("no_power", false),
		/** 产物罐满了。 */
		OUTPUT_FULL("output_full", false),
		/** 正在磨。 */
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
			return "gui.geometallurgy.status_" + this.key;
		}

		public Component translatable() {
			return Component.translatable(translationKey());
		}

		public boolean lit() {
			return this.lit;
		}
	}

	// ------------------------------------------------------------------ 状态

	private final VirtualSlurry input = new VirtualSlurry();
	private final VirtualSlurry output = new VirtualSlurry();

	private final SlurryHandler inputPort = new TankHandler(this.input, true, false);
	private final SlurryHandler outputPort = new TankHandler(this.output, false, true);

	/**
	 * 两个物品槽。进料槽只收研磨表里认得的东西，出料槽只出不进 ——
	 * 这条准入规则服务端和客户端各问一次同一个 {@link #isGrindable}，答案是同一份。
	 */
	private final SimpleContainer items = new SimpleContainer(ITEM_SLOTS) {
		@Override
		public boolean canPlaceItem(int index, ItemStack stack) {
			return index == SLOT_INPUT && isGrindable(stack);
		}

		@Override
		public void setChanged() {
			super.setChanged();
			CrusherBlockEntity.this.setChanged();
		}
	};

	/** 当前这一件磨到第几刻了。 */
	private int grindProgress;

	public final SimpleEnergyStorage energy = new SimpleEnergyStorage(CAPACITY, MAX_INSERT, 0L) {
		@Override
		protected void onFinalCommit() {
			CrusherBlockEntity.this.setChanged();
		}
	};

	private final ContainerData dataAccess = new ContainerData() {
		@Override
		public int get(int index) {
			return CrusherBlockEntity.this.readData(index);
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

	/** 六个面各自的角色，下标即 {@link Direction#ordinal()}。 */
	private final PortRole[] roles = new PortRole[6];

	private double power;
	/** 上一刻实际耗掉的电（FE）。用来把功率读数打成实际开销而不是「可支配额度」。 */
	private double lastDraw;
	private Status status = Status.IDLE;

	private long lastCompositionTick = -1L;
	private List<Map.Entry<SlurryPart, Double>> entries = List.of();

	public CrusherBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.CRUSHER, pos, state);
		// 本机是「浆进粉出」的单向转换器，两个罐各钉死一种形态：
		// 进了别的东西就没法解释产出的到底是哪一种，下游也没法跟着走。
		this.input.allow(SlurryForm.SLURRY);
		this.output.allow(SlurryForm.POWDER);
		clearFaces();
	}

	/**
	 * 出厂状态：六个面全关。
	 *
	 * <p>原本的出厂配置是「上进下出」。改成全关是为了让「哪个面接什么」变成玩家的明确决定 ——
	 * 否则一台随手摆下的机器会自己开始抽料、自己出货，接错线时要先把默认配置逐面改回来
	 * 才能看清现状。现在新机器不接任何东西，方向由工程配置器或界面一次配好。
	 */
	@Override
	public void clearFaces() {
		java.util.Arrays.fill(this.roles, PortRole.DISABLED);
		setChanged();
	}

	// ------------------------------------------------------------------ 主循环

	public static void serverTick(ServerLevel level, BlockPos pos, BlockState state,
			CrusherBlockEntity crusher) {
		crusher.tick(level, pos, state);
	}

	private void tick(ServerLevel level, BlockPos pos, BlockState state) {
		this.pullInput(level, pos);

		// 顺序很要紧：这里<b>不能</b>先把电抽走再判断能磨多少。
		// 发电机与本机同处一条 tick 队列，谁先谁后并不确定；若本机先跑，抽完就只剩 0，
		// 于是每刻都误判成「没电」，机器永远转不起来。所以先让 crush() 按当前电量决定磨多少，
		// 由它自己扣费，再把这一笔开销喂给平滑器当功率读数。
		//
		// 整件研磨与矿浆磨粉共用同一份电、同一个功率读数，所以 lastDraw 在这里清零、
		// 由两条路各自往上加，最后一起送进平滑器。
		this.lastDraw = 0.0;
		Status item = this.grindItem();
		Status slurry = this.crush();
		this.status = merge(item, slurry);
		this.power += (this.lastDraw - this.power) * POWER_SMOOTHING;

		this.pushOutput(level, pos);

		boolean lit = this.status.lit();
		if (state.getValue(CrusherBlock.LIT) != lit) {
			level.setBlock(pos, state.setValue(CrusherBlock.LIT, lit), Block.UPDATE_ALL);
		}
	}

	/**
	 * 两条路各自可能卡在不同地方，这里合成一条状态给玩家看。
	 *
	 * <p>优先级：有人在转 → 正在磨；否则产物堵住比缺电更值得先说 ——
	 * 缺电时玩家多半会去查发电，而产物堵住是「机器没问题，是你要来收货了」。
	 */
	private static Status merge(Status item, Status slurry) {
		if (item == Status.RUNNING || slurry == Status.RUNNING) {
			return Status.RUNNING;
		}
		if (item == Status.OUTPUT_FULL || slurry == Status.OUTPUT_FULL) {
			return Status.OUTPUT_FULL;
		}
		if (item == Status.NO_POWER || slurry == Status.NO_POWER) {
			return Status.NO_POWER;
		}
		return Status.IDLE;
	}

	/**
	 * 磨进料槽里的一件东西（木炭 → 碳粉）。
	 *
	 * <p>三个卡点的归因与 {@link #crush()} 一个规矩：没料、出料槽放不下、电不够，
	 * 各报各的，不混成一句「电力不足」。换料会把没跑完的进度丢掉，免得两件不同的东西
	 * 共用一个进度条。
	 */
	private Status grindItem() {
		ItemStack input = this.items.getItem(SLOT_INPUT);
		ItemStack product = input.isEmpty() ? ItemStack.EMPTY : grindProduct(input);
		if (product.isEmpty()) {
			this.grindProgress = 0;
			return Status.IDLE;
		}
		if (!canAccept(product)) {
			return Status.OUTPUT_FULL;
		}
		if (this.energy.amount < GRIND_DRAW) {
			return Status.NO_POWER;
		}
		this.energy.amount -= GRIND_DRAW;
		this.lastDraw += GRIND_DRAW;
		this.grindProgress++;
		if (this.grindProgress >= GRIND_TICKS) {
			this.grindProgress = 0;
			this.items.removeItem(SLOT_INPUT, 1);
			this.deposit(product);
		}
		setChanged();
		return Status.RUNNING;
	}

	/**
	 * 进料槽里这一件磨出来的产物。
	 *
	 * <p>金属锭 → 金属粉：这是「金属 + 酸 → 盐 + H₂」规则 5 的入口（§十八）。粉与锭共用同一套
	 * 成分组件，所以磨完还是同一种金属，只是形态从整锭变成粉 —— 它不是化学反应（不改配平），
	 * 是一条普通机器配方。
	 *
	 * <p>其余整件研磨仍查 {@link #GRINDING}。两样都不认返回空（理论上到不了，进料槽的准入
	 * 已经挡过一道，但服务端仍要不依赖它）。
	 */
	private static ItemStack grindProduct(ItemStack input) {
		if (input.getItem() == ModItems.INGOT) {
			return Ingots.createPowder(Ingots.composition(input), 1);
		}
		Grind grind = GRINDING.get(input.getItem());
		return grind == null ? ItemStack.EMPTY : new ItemStack(grind.product(), grind.count());
	}

	/** 出料槽还放得下这一份产物吗。 */
	private boolean canAccept(ItemStack product) {
		ItemStack out = this.items.getItem(SLOT_OUTPUT);
		if (out.isEmpty()) {
			return true;
		}
		if (!ItemStack.isSameItemSameComponents(out, product)) {
			return false;
		}
		return out.getCount() + product.getCount() <= out.getMaxStackSize();
	}

	private void deposit(ItemStack product) {
		ItemStack out = this.items.getItem(SLOT_OUTPUT);
		if (out.isEmpty()) {
			this.items.setItem(SLOT_OUTPUT, product);
		} else {
			out.grow(product.getCount());
			this.items.setChanged();
		}
	}

	/**
	 * 磨一批料。
	 *
	 * <p>先进后判的顺序照磁筛机的规矩：料不进来、罐没地方放、电不够磨，都先停住不动，
	 * 免得把输入罐里的料抽出来一半又塞不回去。
	 *
	 * <p><b>三个卡点要分开归因</b>，不能像早先那样一律报「电力不足」：料见底、产物罐堵住、
	 * 电不够是三种完全不同的毛病，修法也各不相同。而且抽取是按比例做的，罐底总会留下
	 * 一丁点浮点残渣 —— 这点残渣若不当作「没有」，机器就会以「电力不足」的名义卡在那儿，
	 * 玩家去查电却发现电是满的。所以先按料量、再按空间、最后才按电量依次排除，
	 * 每一档都用 {@link #MIN_BATCH} 当浮点容差。
	 */
	private Status crush() {
		if (this.input.amount() < MIN_BATCH) {
			return Status.IDLE;
		}
		if (this.output.room() < MIN_BATCH) {
			return Status.OUTPUT_FULL;
		}

		double costPerMb = energyPerMillibucket();
		// 单刻可支配的电：缓冲里的存量，但不超过线圈/磨盘的额定上限。
		double affordable = Math.min(this.energy.amount, MAX_DRAW) / costPerMb;
		if (affordable < MIN_BATCH) {
			return Status.NO_POWER;
		}

		double budget = Math.min(RATE, this.input.amount());
		budget = Math.min(budget, this.output.room());
		double doing = Math.min(budget, affordable);

		Map<SlurryPart, Double> batch = this.input.drainProportional(doing);
		// 出去的这一份是粉：颗粒被磨细了，但成分表原封不动。
		this.output.setForm(SlurryForm.POWDER);
		this.output.insertMap(batch);
		double cost = doing * costPerMb;
		this.energy.amount -= Math.round(cost);
		this.lastDraw = cost;
		setChanged();
		return Status.RUNNING;
	}

	/** 磨 1 mB 需要的能量。颗粒越硬越贵，这是本机唯一随来料变化的开销。 */
	public double energyPerMillibucket() {
		return BASE_ENERGY_PER_MB * (1.0 + this.averageMohs() / 4.0);
	}

	/** 输入罐里颗粒的加权莫氏硬度（按体积加权）。 */
	public double averageMohs() {
		double total = 0.0;
		double weighted = 0.0;
		for (Map.Entry<SlurryPart, Double> entry : this.input.contents().entrySet()) {
			total += entry.getValue();
			weighted += entry.getValue() * SlurryProps.mohs(entry.getKey());
		}
		return total <= 0.0 ? 0.0 : weighted / total;
	}

	// ------------------------------------------------------------------ 面的搬运

	private void pullInput(Level level, BlockPos pos) {
		if (this.input.room() <= 0.0) {
			return;
		}
		for (Direction side : Direction.values()) {
			if (this.roles[side.ordinal()] != PortRole.INPUT) {
				continue;
			}
			SlurryHandler source = SlurryProvider.find(level, pos, side);
			if (source != null) {
				SlurryTransfer.move(source, this.input, TRANSFER_RATE);
			}
		}
	}

	private void pushOutput(Level level, BlockPos pos) {
		if (this.output.isEmpty()) {
			return;
		}
		for (Direction side : Direction.values()) {
			if (this.roles[side.ordinal()] != PortRole.OUTPUT) {
				continue;
			}
			SlurryHandler dest = SlurryProvider.find(level, pos, side);
			if (dest != null) {
				SlurryTransfer.move(this.output, dest, TRANSFER_RATE);
			}
		}
	}

	@Override
	public SlurryHandler slurryAt(Direction side) {
		return switch (this.roles[side.ordinal()]) {
			case INPUT -> this.inputPort;
			case OUTPUT -> this.outputPort;
			default -> null;
		};
	}

	/** 一个面上的罐句柄。 */
	private record TankHandler(VirtualSlurry tank, boolean canInsert, boolean canExtract)
			implements SlurryHandler {
	}

	// ------------------------------------------------------------------ Container（漏斗与管道）

	/*
	 * 方块实体必须**自己**实现 Container：原版漏斗找的是「方块实体本身是不是 Container」，
	 * 光持有一个 SimpleContainer 字段它看不见，机器就成了自动化里的孤岛。
	 */

	@Override
	public int getContainerSize() {
		return ITEM_SLOTS;
	}

	@Override
	public boolean isEmpty() {
		return this.items.isEmpty();
	}

	@Override
	public ItemStack getItem(int index) {
		return this.items.getItem(index);
	}

	@Override
	public ItemStack removeItem(int index, int count) {
		return this.items.removeItem(index, count);
	}

	@Override
	public ItemStack removeItemNoUpdate(int index) {
		return this.items.removeItemNoUpdate(index);
	}

	@Override
	public void setItem(int index, ItemStack stack) {
		this.items.setItem(index, stack);
	}

	@Override
	public boolean stillValid(Player player) {
		return Container.stillValidBlockEntity(this, player);
	}

	@Override
	public void clearContent() {
		this.items.clearContent();
	}

	@Override
	public boolean canPlaceItem(int index, ItemStack stack) {
		return this.items.canPlaceItem(index, stack);
	}

	/**
	 * 每个面看得见哪些槽。
	 *
	 * <p>沿用六面角色配置 —— 标成「进」的面收料、标成「出」的面出货，与矿浆走的是同一份配置。
	 * 玩家只需要理解一次「哪个面是进、哪个面是出」，物品和矿浆都跟着走。
	 */
	@Override
	public int[] getSlotsForFace(Direction side) {
		return switch (this.roles[side.ordinal()]) {
			case INPUT -> new int[] {SLOT_INPUT};
			case OUTPUT -> new int[] {SLOT_OUTPUT};
			default -> new int[0];
		};
	}

	@Override
	public boolean canPlaceItemThroughFace(int index, ItemStack stack, Direction side) {
		return index == SLOT_INPUT
				&& this.roles[side.ordinal()] == PortRole.INPUT
				&& canPlaceItem(index, stack);
	}

	@Override
	public boolean canTakeItemThroughFace(int index, ItemStack stack, Direction side) {
		return index == SLOT_OUTPUT && this.roles[side.ordinal()] == PortRole.OUTPUT;
	}

	// ------------------------------------------------------------------ 数据同步

	private void refreshComposition() {
		long time = this.level == null ? -1L : this.level.getGameTime();
		if (time == this.lastCompositionTick) {
			return;
		}
		this.lastCompositionTick = time;
		// 进出两罐成分恒等（粉碎不改变成分），同步一份就够。
		this.entries = this.input.isEmpty() ? this.output.sortedContents() : this.input.sortedContents();
	}

	private int readData(int index) {
		refreshComposition();
		return switch (index) {
			case 0 -> (int) this.energy.amount;
			case 1 -> (int) Math.round(this.power * 10.0);
			case 2 -> this.status.ordinal();
			case 3 -> (int) Math.round(this.input.amount());
			case 4 -> (int) Math.round(this.output.amount());
			case 5 -> this.packRoles();
			case 6 -> (int) Math.round(this.averageMohs() * 100.0);
			case 7 -> Math.min(TRACKED_ENTRIES, this.entries.size());
			default -> this.readComposition(index - FIXED_DATA);
		};
	}

	private int readComposition(int slot) {
		int entryIndex = slot / 2;
		if (entryIndex >= this.entries.size()) {
			return 0;
		}
		Map.Entry<SlurryPart, Double> entry = this.entries.get(entryIndex);
		if (slot % 2 == 0) {
			return SlurryParts.encode(entry.getKey());
		}
		double total = this.input.isEmpty() ? this.output.amount() : this.input.amount();
		return total <= 0.0 ? 0 : (int) Math.round(entry.getValue() / total * 1000.0);
	}

	private int packRoles() {
		return FaceConfig.pack(this.roles);
	}

	private void unpackRoles(int packed) {
		FaceConfig.unpack(packed, this.roles, PortRole.values(), PortRole.DISABLED);
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

	/**
	 * 界面上的按钮点击：0~5 用 {@link Direction#ordinal()} 指代六个面。
	 *
	 * <p>只转交，不自己处理 —— 配置的持有者是方块实体，菜单只是视图。
	 */
	public boolean handleButton(int id) {
		Direction[] directions = Direction.values();
		if (id >= 0 && id < directions.length) {
			int index = directions[id].ordinal();
			this.roles[index] = this.roles[index].next();
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

	public VirtualSlurry output() {
		return this.output;
	}

	/** 两个物品槽的容器。界面拿它建 {@code Slot}，读写与准入规则都在方块实体这一份上。 */
	public Container itemSlots() {
		return this.items;
	}

	/** 当前这一件磨到第几刻了（0 表示没在磨）。 */
	public int grindProgress() {
		return this.grindProgress;
	}

	public PortRole role(Direction side) {
		return this.roles[side.ordinal()];
	}

	public double power() {
		return this.power;
	}

	public Status status() {
		return this.status;
	}

	public long storedEnergy() {
		return this.energy.amount;
	}

	public EnergyStorage getEnergyStorage() {
		return this.energy;
	}

	// ------------------------------------------------------------------ 界面

	@Override
	public Component getDisplayName() {
		return Component.translatable("block.geometallurgy.crusher");
	}

	@Override
	public AbstractContainerMenu createMenu(int syncId, Inventory inventory, Player player) {
		return new CrusherMenu(syncId, inventory, this);
	}

	@Override
	public Unit getScreenOpeningData(ServerPlayer player) {
		return Unit.INSTANCE;
	}

	// ------------------------------------------------------------------ 存档

	@Override
	protected void saveAdditional(ValueOutput out) {
		super.saveAdditional(out);
		out.putLong("energy", this.energy.amount);
		out.putInt("power_tenths", (int) Math.round(this.power * 10.0));
		out.putInt("roles", this.packRoles());
		storeSlurry(out, "input", this.input);
		out.putInt("input_form", this.input.form().ordinal());
		storeSlurry(out, "output", this.output);
		out.putInt("output_form", this.output.form().ordinal());
		this.items.storeAsItemList(out.list("items", ItemStack.CODEC));
		out.putInt("grind_progress", this.grindProgress);
	}

	@Override
	protected void loadAdditional(ValueInput in) {
		super.loadAdditional(in);
		this.energy.amount = in.getLongOr("energy", 0L);
		this.power = in.getIntOr("power_tenths", 0) / 10.0;
		if (in.getIntOr("roles", -1) < 0) {
			this.clearFaces();
		} else {
			this.unpackRoles(in.getIntOr("roles", this.packRoles()));
		}
		loadSlurry(in, "input", this.input, in.getIntOr("input_form", 0));
		loadSlurry(in, "output", this.output, in.getIntOr("output_form", 0));
		this.items.fromItemList(in.listOrEmpty("items", ItemStack.CODEC));
		this.grindProgress = in.getIntOr("grind_progress", 0);
	}

	private static void storeSlurry(ValueOutput out, String key, VirtualSlurry slurry) {
		ValueOutput.TypedOutputList<String> list = out.list(key, Codec.STRING);
		slurry.contents().forEach((part, amount) -> list.add(SlurryParts.key(part) + "=" + amount));
	}

	private static void loadSlurry(ValueInput in, String key, VirtualSlurry slurry, int formOrdinal) {
		slurry.clear();
		for (String entry : in.listOrEmpty(key, Codec.STRING)) {
			int separator = entry.indexOf('=');
			if (separator <= 0) {
				continue;
			}
			SlurryPart part = SlurryParts.fromKey(entry.substring(0, separator));
			if (part == null) {
				continue;
			}
			try {
				slurry.insert(part, Double.parseDouble(entry.substring(separator + 1)));
			} catch (NumberFormatException ignored) {
				// 坏数据跳过，不让整台机器读不出来
			}
		}
		SlurryForm[] forms = SlurryForm.values();
		if (!slurry.isEmpty() && formOrdinal >= 0 && formOrdinal < forms.length) {
			slurry.setForm(forms[formOrdinal]);
		}
	}
}
