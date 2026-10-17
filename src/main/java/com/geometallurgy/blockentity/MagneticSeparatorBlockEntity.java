package com.geometallurgy.blockentity;

import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.geometallurgy.block.MagneticSeparatorBlock;
import com.geometallurgy.menu.MagneticSeparatorMenu;
import com.geometallurgy.proto.OreMineral;
import com.geometallurgy.slurry.SlurryForm;
import com.geometallurgy.slurry.SlurryHandler;
import com.geometallurgy.slurry.SlurryPart;
import com.geometallurgy.slurry.SlurryParts;
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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import org.jetbrains.annotations.Nullable;

import team.reborn.energy.api.EnergyStorage;
import team.reborn.energy.api.base.SimpleEnergyStorage;

/**
 * 磁力筛机的方块实体：把混合矿浆按<b>磁性</b>分选成精矿与尾矿两股。
 *
 * <h2>磁场从哪来</h2>
 * 分选的判据是磁场强度，而磁场强度<b>直接就是线圈这一时刻吃掉的功率</b>：
 * {@code B = 本刻耗电(FE/t) / 64}。额定功率 64 FE/t 对应 B = 1，正好是一台火力发电机
 * 的输出。接多少电，磁场就有多强 —— 这也是本机唯一「不花钱就调不了」的参数。
 *
 * <h2>为什么会「分不开」</h2>
 * 磁场有一个<b>窄窗口</b>，两头都会坏，而且坏法不同：
 * <ul>
 *   <li><b>太弱</b>：磁性颗粒吸不住。每种矿物有自己的矫顽力，弱磁性矿物（褐铁矿、软锰矿）
 *       要接近额定磁场才吸得牢，强磁性的磁铁矿只要六成场强就够。场弱时它们
 *       从尾矿口一起被冲走 —— 精矿是「漏」掉的，不是被污染的。</li>
 *   <li><b>太强</b>：磁团聚。非磁性颗粒被磁性颗粒裹着一起被拽进精矿，
 *       尾矿因此变干净而精矿反而脏了。</li>
 * </ul>
 * 界面上的磁场条把理想区间标出来，两头是坏区。
 *
 * <h2>三种罐与六个面</h2>
 * 输入罐、磁性产物罐、非磁性产物罐各一个；六个面各自指定成
 * {@link FaceRole} 之一（输入 / 磁性出 / 非磁性出 / 关闭），
 * 因此同一条产线可以任意摆布、而不必让机器「正面朝哪」。
 *
 * <p>尾矿可选<b>直接销毁</b>——那正是本机存在的理由：碎矿挖掘机啃下来的绝大部分
 * 是没用的原岩，一律囤着迟早把罐子塞满。默认仍然囤着，方便观察分离效果。
 */
public class MagneticSeparatorBlockEntity extends BlockEntity
		implements ExtendedMenuProvider<Unit>, SlurryProvider, FaceConfigurable {

	// ------------------------------------------------------------------ 磁场与功率

	/**
	 * 额定功率（FE/t）。这一刻吃满 {@value} FE/t，磁场强度就是 1.0（2026-10-17：64 → 128）。
	 *
	 * <p>与离心机同锚点（粉碎机新额定 256 的一半），所以「场强 = 功率 / 额定」与
	 * 0.5~1.5 的理想窗口一个字没变，只有每 mB 的电费翻倍：额定 128 ÷ {@link #RATE} 8
	 * = <b>16 FE/mB</b>（旧 8）。
	 */
	public static final double NOMINAL_POWER = 128.0;
	/** 线圈单刻最多吃多少电（场强上限 4.0，与旧 256/64 同比例）。再多的电也变不出更强的磁场，只会把缓冲积满、逼停发电机。 */
	public static final long MAX_DRAW = 512L;
	/**
	 * 内部供电缓冲。
	 *
	 * <p>磁选机<b>没有电力缓冲</b>（用户 2026-10-15 口径）：磁场不会因为这一刻没电就停 ——
	 * 所以它不攒电，只保留一 tick 的过手量，这一 tick 拿不到电，磁场立刻就弱下去。
	 * 容量取 {@link #MAX_DRAW} 即「一 tick 的过手量」，等于没有缓冲。
	 */
	public static final long CAPACITY = MAX_DRAW;
	/** 允许相邻发电机单刻推入的最大量。 */
	public static final long MAX_INSERT = MAX_DRAW;
	/** 磁场读数的一阶平滑系数。发电机是逐刻推电的，不做平滑读数会来回跳。 */
	private static final double POWER_SMOOTHING = 0.25;

	/** 低于这个场强，弱磁性矿物开始成片吸不住。 */
	public static final double FIELD_WEAK = 0.5;
	/** 高于这个场强，非磁性颗粒开始被夹带。 */
	public static final double FIELD_STRONG = 1.5;
	/** 到这个场强，非磁性颗粒被夹带干净（精矿几乎全是垃圾）。 */
	public static final double FIELD_CARRY_FULL = 3.0;
	/** 磁场条的显示上限。 */
	public static final double FIELD_DISPLAY_MAX = 3.0;

	/**
	 * 磨成粉之后的两头边界。
	 *
	 * <p>粉料是<b>干式磁选</b>：颗粒细、比表面大、互相黏连，磁场的可选区间被压得很窄 ——
	 * 弱场更早开始漏磁性，强场也更早开始夹带。窗口从 {@value #FIELD_WEAK}~{@value #FIELD_STRONG}
	 * 收成 {@value #POWDER_WEAK}~{@value #POWDER_STRONG}，差一点点就分不开。
	 * 这正是「粉碎之后可以进磁选机，但精度控制要求大幅增加」的落点。
	 */
	public static final double POWDER_WEAK = 0.75;
	public static final double POWDER_STRONG = 1.25;

	// ------------------------------------------------------------------ 分选

	/** 每刻处理的矿浆量（mB）。 */
	public static final double RATE = 8.0;
	/** 单个面每刻的搬运量（mB）。 */
	public static final double TRANSFER_RATE = 16.0;

	/** 每个罐同步给界面的成分条数。 */
	public static final int TRACKED_ENTRIES = 4;
	/** 固定同步字段数。 */
	private static final int FIXED_DATA = 12;
	public static final int DATA_COUNT = FIXED_DATA + TRACKED_ENTRIES * 2 * 3;

	/** 点一下「尾矿处理」按钮对应的菜单按钮号；0~5 依次是六个面。 */
	public static final int TAILINGS_BUTTON = 6;

	/**
	 * 机器此刻的状态。界面直接把 {@link #key()} 拼进语言键显示。
	 *
	 * <p>{@code lit} 表示这一刻机器算不算「在干活」—— 磁场偏了也算在干活，只是干得不好。
	 */
	public enum Status {
		/** 输入罐是空的。 */
		IDLE("idle", false),
		/** 输入里没有任何磁性矿物 —— 没什么可分选的，机器停住并提示。 */
		NO_SEPARABLE("no_separable", false),
		/** 产物罐满了。 */
		OUTPUT_FULL("output_full", false),
		/** 磁场过弱，磁性矿物正在被冲进尾矿。 */
		FIELD_WEAK("field_weak", true),
		/** 磁场过强，非磁性颗粒正在被夹带进精矿。 */
		FIELD_STRONG("field_strong", true),
		/** 正常分选。 */
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
	private final VirtualSlurry magnetic = new VirtualSlurry();
	private final VirtualSlurry nonMagnetic = new VirtualSlurry();

	private final SlurryHandler inputPort = new TankHandler(this.input, true, false);
	private final SlurryHandler magneticPort = new TankHandler(this.magnetic, false, true);
	private final SlurryHandler nonMagneticPort = new TankHandler(this.nonMagnetic, false, true);

	public final SimpleEnergyStorage energy = new SimpleEnergyStorage(CAPACITY, MAX_INSERT, 0L) {
		@Override
		protected void onFinalCommit() {
			MagneticSeparatorBlockEntity.this.setChanged();
		}
	};

	private final ContainerData dataAccess = new ContainerData() {
		@Override
		public int get(int index) {
			return MagneticSeparatorBlockEntity.this.readData(index);
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
	private final FaceRole[] roles = new FaceRole[6];

	/** 尾矿是否直接销毁。 */
	private boolean destroyTailings;
	/** 平滑后的功率（FE/t），磁场强度由它除以额定值得到。 */
	private double power;
	private Status status = Status.IDLE;

	private long lastCompositionTick = -1L;
	private List<Map.Entry<SlurryPart, Double>> inputEntries = List.of();
	private List<Map.Entry<SlurryPart, Double>> magneticEntries = List.of();
	private List<Map.Entry<SlurryPart, Double>> nonMagneticEntries = List.of();

	public MagneticSeparatorBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.MAGNETIC_SEPARATOR, pos, state);
		clearFaces();
	}

	/** 出厂状态：六个面全关（方向由玩家用工程配置器或界面自己配）。 */
	@Override
	public void clearFaces() {
		java.util.Arrays.fill(this.roles, FaceRole.DISABLED);
		setChanged();
	}

	// ------------------------------------------------------------------ 主循环

	public static void serverTick(ServerLevel level, BlockPos pos, BlockState state,
			MagneticSeparatorBlockEntity separator) {
		separator.tick(level, pos, state);
	}

	private void tick(ServerLevel level, BlockPos pos, BlockState state) {
		// 1) 进料：从配成「输入」的面把上游矿浆抽进来
		this.pullInput(level, pos);

		// 2) 线圈取电。这一刻真正吃掉多少电，磁场就有多强 —— 通电 F/t 与磁场是同一件事。
		//
		// 【设计口径 · 空转也扣电，是特性不是 bug，别"修"掉】
		// 与离心机不同，磁选机的线圈只要通电就持续吃电（哪怕输入罐是空的、状态 IDLE），
		// 因为磁场不会因为这一刻没料就断电；上限 MAX_DRAW=256 FE/t，吃多少由上游供电决定。
		// 用户 2026-10-16 明确：「磁选机空转扣电是特性，因为之后会有永磁磁铁升级」。
		// TODO(永磁磁铁升级)：后续新增的"永磁磁铁"升级件会降低甚至免除这段空转基线耗电 ——
		// 那才是收口这个行为的地方，而不是在这里加 input.isEmpty() 早退。
		long drawn = Math.min(this.energy.amount, MAX_DRAW);
		if (drawn > 0L) {
			this.energy.amount -= drawn;
		}
		this.power += ((double) drawn - this.power) * POWER_SMOOTHING;

		// 3) 分选
		this.status = this.separate();

		// 4) 出料：把两个产物罐推给配成输出的面
		this.pushOutputs(level, pos);

		boolean lit = this.status.lit();
		if (state.getValue(MagneticSeparatorBlock.LIT) != lit) {
			level.setBlock(pos, state.setValue(MagneticSeparatorBlock.LIT, lit), Block.UPDATE_ALL);
		}
	}

	// ------------------------------------------------------------------ 分选

	/**
	 * 处理一批矿浆。
	 *
	 * <p>顺序上先判「有没有东西可分」再动料：输入里一个磁性矿物都没有时机器停住，
	 * 而不是把整罐原岩当作尾矿倒掉 —— 否则开着「尾矿销毁」的机器会把上游辛苦挖出来的
	 * 矿浆原地蒸发。
	 */
	private Status separate() {
		if (this.input.isEmpty()) {
			return Status.IDLE;
		}
		if (!this.hasSeparable()) {
			return Status.NO_SEPARABLE;
		}
		if (this.magnetic.isFull() || (!this.destroyTailings && this.nonMagnetic.isFull())) {
			return Status.OUTPUT_FULL;
		}

		double total = Math.min(RATE, this.input.amount());
		// 产物罐跟随来料形态：进的是矿粉，出去的精矿与尾矿也都是矿粉。
		SlurryForm form = this.input.form();
		if (this.magnetic.isEmpty()) {
			this.magnetic.setForm(form);
		}
		if (this.nonMagnetic.isEmpty()) {
			this.nonMagnetic.setForm(form);
		}
		Map<SlurryPart, Double> batch = this.input.drainProportional(total);
		for (Map.Entry<SlurryPart, Double> entry : batch.entrySet()) {
			SlurryPart part = entry.getKey();
			double volume = entry.getValue();
			double captured = volume * this.captureChance(part);

			if (captured > 0.0) {
				this.magnetic.insert(part, captured);
			}
			double tailings = volume - captured;
			if (tailings > 0.0 && !this.destroyTailings) {
				this.nonMagnetic.insert(part, tailings);
			}
		}
		setChanged();

		double field = this.fieldStrength();
		if (field < this.fieldWeak()) {
			return Status.FIELD_WEAK;
		}
		if (field > this.fieldStrong()) {
			return Status.FIELD_STRONG;
		}
		return Status.RUNNING;
	}

	/** 输入里的颗粒有没有一个带磁性的。没有的话磁筛机无从下手。 */
	private boolean hasSeparable() {
		for (Map.Entry<SlurryPart, Double> entry : this.input.contents().entrySet()) {
			if (entry.getValue() > 0.0 && isMagnetic(entry.getKey())) {
				return true;
			}
		}
		return false;
	}

	/** 一种颗粒有没有磁性（原岩一律没有）。 */
	public static boolean isMagnetic(SlurryPart part) {
		return part instanceof OreMineral mineral && mineral.magnetic() > 0.0;
	}

	/**
	 * 一颗颗粒最终落进磁性产物罐的概率。
	 *
	 * <p>磁性颗粒：每种矿物有自己的「需要多强的场才吸得住」，磁性越弱要求越高。
	 * 场强达到要求就全收，达不到就按比例漏掉 —— 漏掉的那部分随尾矿走。
	 *
	 * <p>非磁性颗粒：场强过了 {@link #FIELD_STRONG} 才开始被夹带，到
	 * {@link #FIELD_CARRY_FULL} 时夹带干净。原岩颗粒也走这条线。
	 */
	public double captureChance(SlurryPart part) {
		double field = this.fieldStrength();
		if (isMagnetic(part)) {
			double required = Math.clamp(0.90 - 0.65 * ((OreMineral) part).magnetic(), 0.30, 0.90);
			return Math.clamp(field / required, 0.0, 1.0);
		}
		return Math.clamp((field - this.fieldStrong()) / (FIELD_CARRY_FULL - this.fieldStrong()), 0.0, 1.0);
	}

	/** 当前磁场强度。1.0 = 额定。 */
	public double fieldStrength() {
		return this.power / NOMINAL_POWER;
	}

	/**
	 * 理想窗口的弱端。
	 *
	 * <p>粉料（干式磁选）要窄一截：颗粒细、比表面大，磁场稍弱就开始漏磁性。
	 */
	public double fieldWeak() {
		return this.input.isPowder() ? POWDER_WEAK : FIELD_WEAK;
	}

	/** 理想窗口的强端。粉料同样更早开始夹带。 */
	public double fieldStrong() {
		return this.input.isPowder() ? POWDER_STRONG : FIELD_STRONG;
	}

	// ------------------------------------------------------------------ 面的搬运

	private void pullInput(Level level, BlockPos pos) {
		if (this.input.room() <= 0.0) {
			return;
		}
		for (Direction side : Direction.values()) {
			if (this.roles[side.ordinal()] != FaceRole.INPUT) {
				continue;
			}
			SlurryHandler source = SlurryProvider.find(level, pos, side);
			if (source != null) {
				SlurryTransfer.move(source, this.input, TRANSFER_RATE);
			}
		}
	}

	private void pushOutputs(Level level, BlockPos pos) {
		for (Direction side : Direction.values()) {
			VirtualSlurry product = switch (this.roles[side.ordinal()]) {
				case MAGNETIC -> this.magnetic;
				case NON_MAGNETIC -> this.destroyTailings ? null : this.nonMagnetic;
				default -> null;
			};
			if (product == null || product.isEmpty()) {
				continue;
			}
			SlurryHandler dest = SlurryProvider.find(level, pos, side);
			if (dest != null) {
				SlurryTransfer.move(product, dest, TRANSFER_RATE);
			}
		}
	}

	@Override
	@Nullable
	public SlurryHandler slurryAt(Direction side) {
		return switch (this.roles[side.ordinal()]) {
			case INPUT -> this.inputPort;
			case MAGNETIC -> this.magneticPort;
			case NON_MAGNETIC -> this.destroyTailings ? null : this.nonMagneticPort;
			case DISABLED -> null;
		};
	}

	/** 一个面上的罐句柄。 */
	private record TankHandler(VirtualSlurry tank, boolean canInsert, boolean canExtract)
			implements SlurryHandler {
	}

	// ------------------------------------------------------------------ 数据同步

	private void refreshComposition() {
		long time = this.level == null ? -1L : this.level.getGameTime();
		if (time == this.lastCompositionTick) {
			return;
		}
		this.lastCompositionTick = time;
		this.inputEntries = this.input.sortedContents();
		this.magneticEntries = this.magnetic.sortedContents();
		this.nonMagneticEntries = this.nonMagnetic.sortedContents();
	}

	private int readData(int index) {
		refreshComposition();
		return switch (index) {
			case 0 -> (int) this.energy.amount;
			case 1 -> (int) Math.round(this.power * 10.0);
			case 2 -> this.status.ordinal();
			case 3 -> (int) Math.round(this.input.amount());
			case 4 -> (int) Math.round(this.magnetic.amount());
			case 5 -> (int) Math.round(this.nonMagnetic.amount());
			case 6 -> this.packRoles();
			case 7 -> this.destroyTailings ? 1 : 0;
			case 8 -> Math.min(TRACKED_ENTRIES, this.inputEntries.size());
			case 9 -> Math.min(TRACKED_ENTRIES, this.magneticEntries.size());
			case 10 -> Math.min(TRACKED_ENTRIES, this.nonMagneticEntries.size());
			case 11 -> this.input.form().ordinal();
			default -> this.readComposition(index - FIXED_DATA);
		};
	}

	private int readComposition(int slot) {
		int perTank = TRACKED_ENTRIES * 2;
		int tank = slot / perTank;
		int within = slot % perTank;
		List<Map.Entry<SlurryPart, Double>> entries = switch (tank) {
			case 0 -> this.inputEntries;
			case 1 -> this.magneticEntries;
			default -> this.nonMagneticEntries;
		};
		int entryIndex = within / 2;
		if (entryIndex >= entries.size()) {
			return 0;
		}
		Map.Entry<SlurryPart, Double> entry = entries.get(entryIndex);
		if (within % 2 == 0) {
			return SlurryParts.encode(entry.getKey());
		}
		double total = switch (tank) {
			case 0 -> this.input.amount();
			case 1 -> this.magnetic.amount();
			default -> this.nonMagnetic.amount();
		};
		return total <= 0.0 ? 0 : (int) Math.round(entry.getValue() / total * 1000.0);
	}

	private int packRoles() {
		return FaceConfig.pack(this.roles);
	}

	private void unpackRoles(int packed) {
		FaceConfig.unpack(packed, this.roles, FaceRole.values(), FaceRole.DISABLED);
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
	 * 界面上的按钮点击。客户端只发一个按钮号，真正改的是服务端这边 —— 方块实体才是
	 * 配置的持有者，菜单只是它的一个视图。
	 *
	 * @param id 0~5 用 {@link Direction#ordinal()} 指代六个面（即 下 上 北 南 西 东），
	 *           {@link #TAILINGS_BUTTON} 是尾矿处理开关
	 */
	public boolean handleButton(int id) {
		Direction[] directions = Direction.values();
		if (id >= 0 && id < directions.length) {
			FaceRole[] values = FaceRole.values();
			int index = directions[id].ordinal();
			this.roles[index] = values[(this.roles[index].ordinal() + 1) % values.length];
			setChanged();
			return true;
		}
		if (id == TAILINGS_BUTTON) {
			this.destroyTailings = !this.destroyTailings;
			setChanged();
			return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ 访问器

	public ContainerData getContainerData() {
		return this.dataAccess;
	}

	public VirtualSlurry tank(int index) {
		return switch (index) {
			case 0 -> this.input;
			case 1 -> this.magnetic;
			default -> this.nonMagnetic;
		};
	}

	public FaceRole role(Direction side) {
		return this.roles[side.ordinal()];
	}

	public boolean destroyTailings() {
		return this.destroyTailings;
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
		return Component.translatable("block.geometallurgy.magnetic_separator");
	}

	@Override
	public AbstractContainerMenu createMenu(int syncId, Inventory inventory, Player player) {
		return new MagneticSeparatorMenu(syncId, inventory, this);
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
		out.putInt("destroy_tailings", this.destroyTailings ? 1 : 0);
		storeSlurry(out, "input", this.input);
		out.putInt("input_form", this.input.form().ordinal());
		storeSlurry(out, "magnetic", this.magnetic);
		out.putInt("magnetic_form", this.magnetic.form().ordinal());
		storeSlurry(out, "non_magnetic", this.nonMagnetic);
		out.putInt("non_magnetic_form", this.nonMagnetic.form().ordinal());
	}

	@Override
	protected void loadAdditional(ValueInput in) {
		super.loadAdditional(in);
		this.energy.amount = in.getLongOr("energy", 0L);
		this.power = in.getIntOr("power_tenths", 0) / 10.0;
		this.destroyTailings = in.getIntOr("destroy_tailings", 0) != 0;
		if (in.getIntOr("roles", -1) < 0) {
			this.clearFaces();
		} else {
			this.unpackRoles(in.getIntOr("roles", this.packRoles()));
		}
		loadSlurry(in, "input", this.input, in.getIntOr("input_form", 0));
		loadSlurry(in, "magnetic", this.magnetic, in.getIntOr("magnetic_form", 0));
		loadSlurry(in, "non_magnetic", this.nonMagnetic, in.getIntOr("non_magnetic_form", 0));
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
