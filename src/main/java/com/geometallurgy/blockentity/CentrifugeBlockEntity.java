package com.geometallurgy.blockentity;

import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.geometallurgy.block.CentrifugeBlock;
import com.geometallurgy.menu.CentrifugeMenu;
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

import team.reborn.energy.api.EnergyStorage;
import team.reborn.energy.api.base.SimpleEnergyStorage;

/**
 * 离心机：按<b>密度</b>把矿浆分成重相与轻相，用来把不同矿物拆开、或者把矿物与脉石拆开。
 *
 * <h2>它<b>不</b>分离伴生杂质</h2>
 * 这一点必须说清楚：矿脉数据里那份「10%~30% 难分离伴生杂质」是<b>长在矿物颗粒里</b>的，
 * 不是混在液相里的另一堆颗粒，离心机看不见它，也甩不掉它。离心机按密度能分开的，
 * 只有「密度本来就不同的颗粒」—— 换个矿物、或者把脉石甩出去。杂质要去掉，得靠后续的
 * 湿法冶金工序。
 *
 * <h2>只吃矿浆，不吃矿粉</h2>
 * 离心分层的<b>前提是颗粒能在液相里自由重排</b>：重的往下沉、轻的往上浮，靠的是液相这个介质。
 * 粉碎机出来的矿粉细到液相已经带不住它们，进来只会糊成一团 —— 所以本机的进料口直接拒收粉料
 * （{@link SlurryForm#POWDER}），要分粉请走干式磁选（磁力筛机）。
 *
 * <h2>转速与切点</h2>
 * <ul>
 *   <li><b>切点密度 ρc</b> —— 界面上的两个按钮调，范围 {@value #MIN_CUT}~{@value #MAX_CUT}。
 *       密度高于它的颗粒进重相，低于它的进轻相。</li>
 *   <li><b>转速</b> —— 就是本刻吃掉的功率（{@code rpm = 功率 / 64}）。转速决定分离的<b>锐度</b>：
 *       转速越低，切点附近那条「分不清」的带子越宽。额定转速下约 ±0.6 g/cm³ 之内的
 *       颗粒才会互相串（磁铁矿 5.17 与赤铁矿 5.26 就是这么分不开的），
 *       电不够时这条带子会宽到把整罐糊在一起。</li>
 * </ul>
 */
public class CentrifugeBlockEntity extends BlockEntity
		implements ExtendedMenuProvider<Unit>, SlurryProvider, FaceConfigurable {

	// ------------------------------------------------------------------ 功率与参数

	/**
	 * 额定功率（FE/t），对应转速 1.0（2026-10-17：64 → 128）。
	 *
	 * <p>锚点取粉碎机的新额定 256 FE/t 的一半（旧值 64 也是旧粉碎机上限 128 的一半），
	 * 所以「转速 = 功率 / 额定」这条界面语言一个字没变，只有每 mB 的电费翻倍：
	 * 额定 128 FE/t ÷ {@link #RATE} 8 mB/t = <b>16 FE/mB</b>（旧 8）。
	 */
	public static final double NOMINAL_POWER = 128.0;
	/** 单刻最多吃多少电：3 倍额定（转速上限 3.0，与旧 192/64 同比例）。 */
	public static final long MAX_DRAW = 384L;
	/** 内部供电缓冲：40 刻的额定量。 */
	public static final long CAPACITY = (long) (NOMINAL_POWER * 40.0);
	/** 允许相邻发电机单刻推入的最大量：4 倍额定（与旧 256/64 同比例）。 */
	public static final long MAX_INSERT = (long) (NOMINAL_POWER * 4.0);
	private static final double POWER_SMOOTHING = 0.25;

	/** 每刻处理的矿浆量（mB）。 */
	public static final double RATE = 8.0;
	/** 单个面每刻的搬运量（mB）。 */
	public static final double TRANSFER_RATE = 16.0;

	/** 额定转速下「分不清」的密度带半宽（g/cm³）。 */
	public static final double BASE_WIDTH = 0.6;
	/** 低于这个转速就分不动了，报「转速不足」。 */
	public static final double MIN_RPM = 0.2;

	/** 切点密度的调节范围与步进。 */
	public static final double MIN_CUT = 1.5;
	public static final double MAX_CUT = 8.0;
	public static final double CUT_STEP = 0.5;
	/** 出厂切点：正好落在典型脉石（2.2~3.0）之上、铁矿（5.2）之下。 */
	public static final double DEFAULT_CUT = 3.0;

	/** 界面上两个按钮：减 / 加切点。0~5 是六个面。 */
	public static final int CUT_DOWN_BUTTON = 6;
	public static final int CUT_UP_BUTTON = 7;

	/** 每个罐同步给界面的成分条数。 */
	public static final int TRACKED_ENTRIES = 3;
	private static final int FIXED_DATA = 11;
	public static final int DATA_COUNT = FIXED_DATA + TRACKED_ENTRIES * 2 * 3;

	public enum Status {
		/** 输入罐是空的。 */
		IDLE("idle", false),
		/** 进的是矿粉 —— 离心机分不动粉料，请改走干式磁选。 */
		NEEDS_SLURRY("needs_slurry", false),
		/** 转速不够，分不清。 */
		NO_SPIN("no_spin", false),
		/** 产物罐满了。 */
		OUTPUT_FULL("output_full", false),
		/** 正常分离。 */
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
	private final VirtualSlurry heavy = new VirtualSlurry();
	private final VirtualSlurry light = new VirtualSlurry();

	private final SlurryHandler inputPort = new TankHandler(this.input, true, false);
	private final SlurryHandler heavyPort = new TankHandler(this.heavy, false, true);
	private final SlurryHandler lightPort = new TankHandler(this.light, false, true);

	public final SimpleEnergyStorage energy = new SimpleEnergyStorage(CAPACITY, MAX_INSERT, 0L) {
		@Override
		protected void onFinalCommit() {
			CentrifugeBlockEntity.this.setChanged();
		}
	};

	private final ContainerData dataAccess = new ContainerData() {
		@Override
		public int get(int index) {
			return CentrifugeBlockEntity.this.readData(index);
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
	private double cut = DEFAULT_CUT;
	private Status status = Status.IDLE;

	private long lastCompositionTick = -1L;
	private List<Map.Entry<SlurryPart, Double>> inputEntries = List.of();
	private List<Map.Entry<SlurryPart, Double>> heavyEntries = List.of();
	private List<Map.Entry<SlurryPart, Double>> lightEntries = List.of();

	public CentrifugeBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.CENTRIFUGE, pos, state);
		// 离心分层要求颗粒能在液相里自由重排，粉料进来只会糊成一团，所以三个罐一律只认矿浆。
		this.input.allow(SlurryForm.SLURRY);
		this.heavy.allow(SlurryForm.SLURRY);
		this.light.allow(SlurryForm.SLURRY);
		clearFaces();
	}

	/** 出厂状态：六个面全关（方向由玩家用工程配置器或界面自己配）。 */
	@Override
	public void clearFaces() {
		java.util.Arrays.fill(this.roles, PortRole.DISABLED);
		setChanged();
	}

	// ------------------------------------------------------------------ 主循环

	public static void serverTick(ServerLevel level, BlockPos pos, BlockState state,
			CentrifugeBlockEntity centrifuge) {
		centrifuge.tick(level, pos, state);
	}

	private void tick(ServerLevel level, BlockPos pos, BlockState state) {
		this.pullInput(level, pos);

		// 取电只在「这一刻真的有活可干」时发生：有矿浆可分层、进的是浆不是粉、两个产物罐还收得下。
		// 空罐 / 粉料 / 产物堵住都一 FE 不扣；{@link #power} 平滑到 0，转速读数随之归零。
		// 这修掉了「空转也按 MAX_DRAW 抽干电池」的缺陷（bug#29）。额定 64 FE/t 仍只是转速系数：
		// 真正吃多少由上游供给与本判断共同决定，上限 MAX_DRAW。
		boolean wantsWork = !this.input.isEmpty()
				&& !this.input.isPowder()
				&& !this.heavy.isFull()
				&& !this.light.isFull();
		long drawn = wantsWork ? Math.min(this.energy.amount, MAX_DRAW) : 0L;
		if (drawn > 0L) {
			this.energy.amount -= drawn;
		}
		this.power += ((double) drawn - this.power) * POWER_SMOOTHING;

		this.status = this.separate();

		this.pushOutputs(level, pos);

		boolean lit = this.status.lit();
		if (state.getValue(CentrifugeBlock.LIT) != lit) {
			level.setBlock(pos, state.setValue(CentrifugeBlock.LIT, lit), Block.UPDATE_ALL);
		}
	}

	// ------------------------------------------------------------------ 分离

	private Status separate() {
		if (this.input.isEmpty()) {
			return Status.IDLE;
		}
		if (this.input.isPowder()) {
			return Status.NEEDS_SLURRY;
		}
		if (this.rotation() < MIN_RPM) {
			return Status.NO_SPIN;
		}
		if (this.heavy.isFull() || this.light.isFull()) {
			return Status.OUTPUT_FULL;
		}

		double total = Math.min(RATE, this.input.amount());
		Map<SlurryPart, Double> batch = this.input.drainProportional(total);
		for (Map.Entry<SlurryPart, Double> entry : batch.entrySet()) {
			SlurryPart part = entry.getKey();
			double volume = entry.getValue();
			// 按比例分配等价于大批量下的期望：密度远高于切点的颗粒几乎全进重相，
			// 正落在切点上的颗粒对半分。
			double sunk = volume * this.sinkChance(part);
			if (sunk > 0.0) {
				this.heavy.insert(part, sunk);
			}
			double floated = volume - sunk;
			if (floated > 0.0) {
				this.light.insert(part, floated);
			}
		}
		setChanged();

		return Status.RUNNING;
	}

	/**
	 * 一颗颗粒落进重相的概率。
	 *
	 * <p>用 logistic 而不是硬阈值：真实的离心分层在切点附近本来就是模糊的，
	 * 密度差得越远分得越干净，正好落在切点上的对半分。转速越低，这条过渡带越宽。
	 */
	public double sinkChance(SlurryPart part) {
		double density = SlurryProps.density(part);
		double width = this.separationWidth();
		return 1.0 / (1.0 + Math.exp(-(density - this.cut) / width));
	}

	/** 当前「分不清」的密度带半宽。转速越低越宽。 */
	public double separationWidth() {
		return BASE_WIDTH / Math.max(this.rotation(), MIN_RPM);
	}

	/** 转速。1.0 = 额定功率。 */
	public double rotation() {
		return this.power / NOMINAL_POWER;
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
				// 粉料由 SlurryTransfer 按本罐的 allowed 集合挡回去，这里不必再判一次。
				SlurryTransfer.move(source, this.input, TRANSFER_RATE);
			}
		}
	}

	private void pushOutputs(Level level, BlockPos pos) {
		for (Direction side : Direction.values()) {
			VirtualSlurry product = switch (this.roles[side.ordinal()]) {
				case OUTPUT -> this.heavy;
				case BYPRODUCT -> this.light;
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
	public SlurryHandler slurryAt(Direction side) {
		return switch (this.roles[side.ordinal()]) {
			case INPUT -> this.inputPort;
			case OUTPUT -> this.heavyPort;
			case BYPRODUCT -> this.lightPort;
			default -> null;
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
		this.heavyEntries = this.heavy.sortedContents();
		this.lightEntries = this.light.sortedContents();
	}

	private int readData(int index) {
		refreshComposition();
		return switch (index) {
			case 0 -> (int) this.energy.amount;
			case 1 -> (int) Math.round(this.power * 10.0);
			case 2 -> this.status.ordinal();
			case 3 -> (int) Math.round(this.input.amount());
			case 4 -> (int) Math.round(this.heavy.amount());
			case 5 -> (int) Math.round(this.light.amount());
			case 6 -> this.packRoles();
			case 7 -> (int) Math.round(this.cut * 100.0);
			case 8 -> Math.min(TRACKED_ENTRIES, this.inputEntries.size());
			case 9 -> Math.min(TRACKED_ENTRIES, this.heavyEntries.size());
			case 10 -> Math.min(TRACKED_ENTRIES, this.lightEntries.size());
			default -> this.readComposition(index - FIXED_DATA);
		};
	}

	private int readComposition(int slot) {
		int perTank = TRACKED_ENTRIES * 2;
		int tank = slot / perTank;
		int within = slot % perTank;
		List<Map.Entry<SlurryPart, Double>> entries = switch (tank) {
			case 0 -> this.inputEntries;
			case 1 -> this.heavyEntries;
			default -> this.lightEntries;
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
			case 1 -> this.heavy.amount();
			default -> this.light.amount();
		};
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

	/** 界面按钮：0~5 是六个面，{@link #CUT_DOWN_BUTTON} / {@link #CUT_UP_BUTTON} 调切点。 */
	public boolean handleButton(int id) {
		Direction[] directions = Direction.values();
		if (id >= 0 && id < directions.length) {
			int index = directions[id].ordinal();
			this.roles[index] = this.roles[index].next();
			setChanged();
			return true;
		}
		if (id == CUT_DOWN_BUTTON) {
			this.cut = Math.clamp(this.cut - CUT_STEP, MIN_CUT, MAX_CUT);
			setChanged();
			return true;
		}
		if (id == CUT_UP_BUTTON) {
			this.cut = Math.clamp(this.cut + CUT_STEP, MIN_CUT, MAX_CUT);
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
			case 1 -> this.heavy;
			default -> this.light;
		};
	}

	public PortRole role(Direction side) {
		return this.roles[side.ordinal()];
	}

	public double power() {
		return this.power;
	}

	public double cut() {
		return this.cut;
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
		return Component.translatable("block.geometallurgy.centrifuge");
	}

	@Override
	public AbstractContainerMenu createMenu(int syncId, Inventory inventory, Player player) {
		return new CentrifugeMenu(syncId, inventory, this);
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
		out.putInt("cut_hundredths", (int) Math.round(this.cut * 100.0));
		out.putInt("roles", this.packRoles());
		storeSlurry(out, "input", this.input);
		storeSlurry(out, "heavy", this.heavy);
		storeSlurry(out, "light", this.light);
	}

	@Override
	protected void loadAdditional(ValueInput in) {
		super.loadAdditional(in);
		this.energy.amount = in.getLongOr("energy", 0L);
		this.power = in.getIntOr("power_tenths", 0) / 10.0;
		this.cut = Math.clamp(in.getIntOr("cut_hundredths", (int) (DEFAULT_CUT * 100)) / 100.0,
				MIN_CUT, MAX_CUT);
		if (in.getIntOr("roles", -1) < 0) {
			this.clearFaces();
		} else {
			this.unpackRoles(in.getIntOr("roles", this.packRoles()));
		}
		loadSlurry(in, "input", this.input);
		loadSlurry(in, "heavy", this.heavy);
		loadSlurry(in, "light", this.light);
	}

	private static void storeSlurry(ValueOutput out, String key, VirtualSlurry slurry) {
		ValueOutput.TypedOutputList<String> list = out.list(key, Codec.STRING);
		slurry.contents().forEach((part, amount) -> list.add(SlurryParts.key(part) + "=" + amount));
	}

	/**
	 * 读罐。
	 *
	 * <p>离心机三个罐永远只装矿浆（进料口就拦了粉），所以形态不必落盘 —— 读回来一律是
	 * {@link SlurryForm#SLURRY}。
	 */
	private static void loadSlurry(ValueInput in, String key, VirtualSlurry slurry) {
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
	}
}
