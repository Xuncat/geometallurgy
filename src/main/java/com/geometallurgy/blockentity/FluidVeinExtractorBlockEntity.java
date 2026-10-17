package com.geometallurgy.blockentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.geometallurgy.block.FluidVeinExtractorBlock;
import com.geometallurgy.geology.RockType;
import com.geometallurgy.item.DrillBitItem;
import com.geometallurgy.item.DrillMaterial;
import com.geometallurgy.item.ModItems;
import com.geometallurgy.menu.FluidVeinExtractorMenu;
import com.geometallurgy.proto.TracePhase;
import com.geometallurgy.proto.VeinAttributes;
import com.geometallurgy.slurry.SlurryForm;
import com.geometallurgy.slurry.SlurryHandler;
import com.geometallurgy.slurry.SlurryPart;
import com.geometallurgy.slurry.SlurryParts;
import com.geometallurgy.slurry.SlurryProvider;
import com.geometallurgy.slurry.VirtualSlurry;
import com.geometallurgy.vein.EpochClock;
import com.geometallurgy.vein.OreBody;
import com.geometallurgy.vein.VeinDeposit;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
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
 * 流体碎矿挖掘机的方块实体。
 *
 * <p>工作链路：勘测所在位置的岩体 → 由岩体推出虚拟矿脉 → 每刻耗 256 能量推进开采 →
 * 每刻掷一次产出，把矿物颗粒以悬浊液形式注入内部储罐。
 *
 * <p>与老抽取机的关键差别是<b>没有产物物品槽</b>：产物全部留在 {@link VirtualSlurry} 里，
 * 因此本机不能被漏斗抽走任何东西。它<b>确实</b>有一个物品槽，但那是<b>钻头槽</b> ——
 * 装磨头，不装产物。
 *
 * <h2>钻头决定这台机器能不能开工</h2>
 * 钻头是消耗品，三项属性各自约束一件事（见 {@link DrillMaterial}）：
 * <ul>
 *   <li><b>硬度</b>卡门槛：矿脉里但凡有一样矿物的莫氏硬度高过钻头，整台机器就停下报
 *       「钻头太软」—— 不是磨得慢，而是根本啃不动。想挖钻石就得装钻石钻头。</li>
 *   <li><b>耐磨</b>是耐久：每次开采扣一点，越硬的料扣得越多。</li>
 *   <li><b>韧性</b>管崩碎：每次开采都有一发独立掷骰，中了就<b>当场折断</b>，
 *       耐久条还有大半也没用。钻石钻头硬得能啃钻石，也脆得容易断在钻石上。</li>
 * </ul>
 *
 * <p>矿脉每约 10 游戏日重掷一次（见 {@link EpochClock}）：纪元号一变就重新勘测，
 * 但储罐里已经挖出来的矿浆不受影响。
 */
public class FluidVeinExtractorBlockEntity extends BlockEntity
		implements ExtendedMenuProvider<Unit>, SlurryProvider, Container {

	/**
	 * 运转耗能：每刻 1024 FE（2026-10-17 用户口径）。
	 *
	 * <p>一次开采仍是 {@link #HARVEST_TICKS} 刻 × {@link #MILLIBUCKETS_PER_HARVEST} 100 mB，
	 * 所以一刻不停时每 20 刻产出 100 mB = <b>0.1 mol</b> 矿浆、花 <b>20480 FE</b>：
	 * 20480 FE / 0.1 mol = <b>204800 FE/mol</b>，是旧值（51200 FE/mol）的 4 倍。
	 * 旧值 256 FE/t 见 docs/耗电一览.md 的历史表。
	 *
	 * <p>硬度/钻头差异<b>仍只改刻数、不改每刻功耗</b>（{@link #drillSpeed()} 推进进度条）：
	 * 一次开采的电费 = 1024 × 20 / speed，硬料更慢更贵，快钻头更快更省 —— 口径与旧版逐字相同。
	 */
	public static final long ENERGY_PER_TICK = 1024L;
	/** 内部能量缓冲容量：100 刻的量。 */
	public static final long CAPACITY = ENERGY_PER_TICK * 100L;
	/** 允许被充能的最大速率。 */
	public static final long MAX_INSERT = ENERGY_PER_TICK * 4L;
	/** 一次开采需要的刻数（速度倍率为 1 时）。 */
	public static final int HARVEST_TICKS = 20;
	/** 一次开采注入的矿浆量（mB）。 */
	public static final double MILLIBUCKETS_PER_HARVEST = 100.0;

	/** 同步给界面的成分条数（原岩相 + 矿物相，界面最多列得下这么多行）。 */
	public static final int TRACKED_ENTRIES = 6;
	/**
	 * 同步字段布局：
	 * 0 能量 / 1 进度 / 2 液量(mB) / 3 成分种数 / 4 原岩序号+1 /
	 * 5 钻头材质序号+1（0 = 没装）/ 6 钻头耐久千分位 / 7 状态序号，
	 * 之后每 2 个一组：[相的编码, 占比千分位]。
	 */
	private static final int FIXED_DATA = 8;
	public static final int DATA_COUNT = FIXED_DATA + TRACKED_ENTRIES * 2;

	/**
	 * 能量同步刻度：界面读到的能量 = 同步值 × 这个数（见 {@code readData} 的 case 0）。
	 *
	 * <p>{@code ContainerData} 是 16 位有符号通道，而本机缓冲 {@link #CAPACITY} = 102400 FE
	 * （2026-10-17 从 25600 提上来）超过 32767 —— 不除刻度就会在客户端被截成一个巨大的正数
	 * （bug#33）。取 4：102400 / 4 = 25600 ≤ 32767。
	 */
	public static final int ENERGY_SYNC_DIVISOR = 4;

	/**
	 * 机器此刻为什么开不了工。
	 *
	 * <p>四种停机理由分开报，是因为它们指向完全不同的动作：加电、装钻头、换更硬的钻头、
	 * 或者把下游接上。糊成一个「停机」等于让人自己猜。
	 */
	public enum Status {
		/** 电不够。 */
		NO_POWER("no_power", false),
		/** 钻头槽是空的。 */
		NO_DRILL("no_drill", false),
		/** 钻头硬度低于矿脉里最硬的矿物 —— 啃不动。 */
		DRILL_TOO_SOFT("drill_too_soft", false),
		/** 内部储罐满了。 */
		OUTPUT_FULL("output_full", false),
		/** 正常开采。 */
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

	public final SimpleEnergyStorage energy = new SimpleEnergyStorage(CAPACITY, MAX_INSERT, 0L) {
		@Override
		protected void onFinalCommit() {
			FluidVeinExtractorBlockEntity.this.setChanged();
		}
	};

	private final VirtualSlurry slurry = new VirtualSlurry();

	/**
	 * 钻头槽：一格，只收钻头。
	 *
	 * <p>虽然是唯一一个物品槽，它装的也<b>不是产物</b> —— 产物是罐里的矿浆，搬不走。
	 * 改内容要通知方块实体存盘，否则钻头的耐久会白掉。
	 */
	private final SimpleContainer drillSlot = new SimpleContainer(1) {
		@Override
		public boolean canPlaceItem(int index, ItemStack stack) {
			return stack.getItem() instanceof DrillBitItem;
		}

		@Override
		public void setChanged() {
			super.setChanged();
			FluidVeinExtractorBlockEntity.this.setChanged();
		}
	};

	/**
	 * 对外只出不进的矿浆口：六个面共用同一个罐。
	 *
	 * <p>本机是产线的<b>起点</b>，上游没有东西能给它，所以 {@code canInsert} 永远是 false ——
	 * 这也挡住了一个容易犯的错：把磁筛机的「精矿出口」直接怼在挖掘机上，
	 * 精矿会一头灌进储罐、把成分搅浑。
	 */
	private final SlurryHandler outputPort = new SlurryHandler() {
		@Override
		public VirtualSlurry tank() {
			return FluidVeinExtractorBlockEntity.this.slurry;
		}

		@Override
		public boolean canInsert() {
			return false;
		}

		@Override
		public boolean canExtract() {
			return true;
		}
	};

	private final ContainerData dataAccess = new ContainerData() {
		@Override
		public int get(int index) {
			return FluidVeinExtractorBlockEntity.this.readData(index);
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

	/** 当前纪元下本位置的矿脉。由种子与坐标确定性算出，不入存档。 */
	private VeinDeposit deposit;
	private double progress;
	private long harvested;
	private Status status = Status.NO_POWER;

	public FluidVeinExtractorBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.FLUID_VEIN_EXTRACTOR, pos, state);
		// 碎矿机啃下来的是粗颗粒悬浮液 —— 本罐永远只装矿浆，粉是下一台机器的事。
		this.slurry.allow(SlurryForm.SLURRY);
	}

	// ------------------------------------------------------------------ 主循环

	public static void serverTick(ServerLevel level, BlockPos pos, BlockState state,
			FluidVeinExtractorBlockEntity extractor) {
		extractor.tick(level, pos, state);
	}

	private void tick(ServerLevel level, BlockPos pos, BlockState state) {
		// 换脉纪元按矿体单元取（单元起始区块），与探测仪 Prospector 是同一个基准。
		// 这里曾用 globalEpoch，结果探测仪报的矿体中心在机器这边落到了另一个纪元的
		// 矿体上，甚至是一块纯原岩 —— 用户看到的「矿脉中心没有任何矿物」（bug#45）。
		long epoch = EpochClock.epochOfCell(level,
				Math.floorDiv(pos.getX(), OreBody.CELL_X), Math.floorDiv(pos.getZ(), OreBody.CELL_Z));
		if (this.deposit == null || this.deposit.epoch() != epoch) {
			// 换纪元了：重新勘测。已经挖出来的矿浆留在罐里，不受影响。
			this.deposit = VeinDeposit.at(level, pos);
			setChanged();
		}

		this.status = this.evaluate();
		boolean running = this.status == Status.RUNNING;
		if (running) {
			this.energy.amount -= ENERGY_PER_TICK;

			this.progress += this.drillSpeed();
			if (this.progress >= HARVEST_TICKS) {
				if (this.produce(level)) {
					this.progress = 0.0;
					this.harvested++;
					// 每一次成功的开采都要为钻头付出代价：要么少一截，要么当场断。
					this.wearDrill(level);
				} else {
					this.progress = HARVEST_TICKS;
				}
			}
			setChanged();
		}

		if (state.getValue(FluidVeinExtractorBlock.LIT) != running) {
			level.setBlock(pos, state.setValue(FluidVeinExtractorBlock.LIT, running), Block.UPDATE_ALL);
		}
	}

	/** 判断这一时刻能不能开工。顺序即优先级：电 → 钻头 → 硬度 → 空间。 */
	private Status evaluate() {
		if (this.energy.amount < ENERGY_PER_TICK) {
			return Status.NO_POWER;
		}
		ItemStack bit = this.drillSlot.getItem(0);
		if (!(bit.getItem() instanceof DrillBitItem item)) {
			return Status.NO_DRILL;
		}
		if (item.material().maxMohs() < this.requiredMohs()) {
			return Status.DRILL_TOO_SOFT;
		}
		if (this.slurry.isFull()) {
			return Status.OUTPUT_FULL;
		}
		return Status.RUNNING;
	}

	/**
	 * 一次开采：磨下 100 mB 的岩石，其中绝大部分是原岩，只有按丰度算出的那一小部分是目标矿物。
	 *
	 * <p>矿脉里一种矿物都没有时照样开采 —— 那就是在啃一块纯原岩，产出的矿浆全是石头。
	 */
	private boolean produce(ServerLevel level) {
		if (this.deposit == null) {
			return false;
		}
		RockType rock = this.deposit.rock();
		VeinAttributes vein = this.deposit.roll(level.getRandom());
		if (vein == null) {
			return this.slurry.insert(rock, MILLIBUCKETS_PER_HARVEST) > 0.0;
		}
		double ore = MILLIBUCKETS_PER_HARVEST * vein.abundance();
		double stone = MILLIBUCKETS_PER_HARVEST - ore;
		double accepted = this.slurry.insert(rock, stone);

		// 伴生相是从这一份矿物里切出来的 —— 矿物颗粒本来就不是纯的。
		// 那一小撮伴生稀土 / 金银跟着主矿一起下来，密度与磁性和主矿都不一样，
		// 所以后面能靠离心机与磁筛机把它们单独分出来。
		double companionTotal = vein.companionTotal();
		double companionVolume = ore * companionTotal;
		accepted += this.slurry.insert(vein.mineral(), ore - companionVolume);
		if (companionVolume > 0.0 && companionTotal > 0.0) {
			for (Map.Entry<String, Double> entry : vein.companion().entrySet()) {
				TracePhase phase = TracePhase.of(entry.getKey());
				if (phase == null) {
					continue;
				}
				accepted += this.slurry.insert(phase,
						companionVolume * entry.getValue() / companionTotal);
			}
		}
		if (accepted <= 0.0) {
			return false;
		}
		setChanged();
		return true;
	}

	// ------------------------------------------------------------------ 钻头

	/** 本处矿脉要求的钻头硬度：岩体与其中<b>最硬</b>的那种矿物取大者。 */
	public double requiredMohs() {
		if (this.deposit == null) {
			return 0.0;
		}
		double required = this.deposit.rock().mohs();
		for (VeinAttributes vein : this.deposit.veins()) {
			required = Math.max(required, vein.mineral().mohs());
		}
		return required;
	}

	/** 当前钻头的推进速度倍率；没装钻头时为 1（反正也不会跑）。 */
	public double drillSpeed() {
		ItemStack bit = this.drillSlot.getItem(0);
		return bit.getItem() instanceof DrillBitItem item ? item.material().speed() : 1.0;
	}

	/**
	 * 一次开采之后磨损钻头。
	 *
	 * <p>两条独立路径：先掷崩碎（韧性越低越容易当场断），没断才扣耐久。
	 * 扣的量随所挖料的硬度走 —— 啃石英岩比啃页岩费磨头得多。
	 */
	private void wearDrill(ServerLevel level) {
		ItemStack bit = this.drillSlot.getItem(0);
		if (!(bit.getItem() instanceof DrillBitItem item)) {
			return;
		}
		if (level.getRandom().nextDouble() < item.material().breakChance()) {
			this.drillSlot.setItem(0, ItemStack.EMPTY);
			setChanged();
			return;
		}
		int wear = (int) Math.max(1, Math.round(0.5 + this.requiredMohs() / 3.0));
		int damage = bit.getDamageValue() + wear;
		if (damage >= bit.getMaxDamage()) {
			this.drillSlot.setItem(0, ItemStack.EMPTY);
		} else {
			bit.setDamageValue(damage);
		}
		setChanged();
	}

	/** 方块被玩家破坏时把钻头弹出来 —— 否则半根钻石钻头就凭空蒸发了。 */
	public void dropDrill() {
		if (this.level == null) {
			return;
		}
		ItemStack bit = this.drillSlot.getItem(0);
		if (!bit.isEmpty()) {
			Block.popResource(this.level, this.worldPosition, bit);
			this.drillSlot.setItem(0, ItemStack.EMPTY);
		}
	}

	// ------------------------------------------------------------------ 数据同步

	private int readData(int index) {
		List<Map.Entry<SlurryPart, Double>> composition = this.slurry.sortedContents();
		switch (index) {
			case 0:
				return (int) (this.energy.amount / ENERGY_SYNC_DIVISOR);
			case 1:
				return (int) this.progress;
			case 2:
				return (int) Math.round(this.slurry.amount());
			case 3:
				return Math.min(TRACKED_ENTRIES, composition.size());
			case 4:
				return this.deposit == null ? 0 : this.deposit.rock().ordinal() + 1;
			case 5:
				return this.drillMaterial() == null ? 0 : this.drillMaterial().ordinal() + 1;
			case 6: {
				ItemStack bit = this.drillSlot.getItem(0);
				if (bit.isEmpty() || bit.getMaxDamage() <= 0) {
					return 0;
				}
				int left = bit.getMaxDamage() - bit.getDamageValue();
				return (int) Math.round((double) left / bit.getMaxDamage() * 1000.0);
			}
			case 7:
				return this.status.ordinal();
			default:
				int slot = index - FIXED_DATA;
				int entryIndex = slot / 2;
				if (entryIndex < 0 || entryIndex >= composition.size()) {
					return 0;
				}
				SlurryPart part = composition.get(entryIndex).getKey();
				return (slot % 2 == 0)
						? SlurryParts.encode(part)
						: (int) Math.round(this.slurry.fraction(part) * 1000.0);
		}
	}

	// ------------------------------------------------------------------ 访问器

	public ContainerData getContainerData() {
		return this.dataAccess;
	}

	public VirtualSlurry getSlurry() {
		return this.slurry;
	}

	public VeinDeposit deposit() {
		return this.deposit;
	}

	public int progress() {
		return (int) this.progress;
	}

	/** 钻头槽。界面把这个容器直接挂成一个槽位。 */
	public SimpleContainer drillSlot() {
		return this.drillSlot;
	}

	/** 当前装的钻头材质；没装时为 null。 */
	public DrillMaterial drillMaterial() {
		ItemStack bit = this.drillSlot.getItem(0);
		return bit.getItem() instanceof DrillBitItem item ? item.material() : null;
	}

	public Status status() {
		return this.status;
	}

	public long storedEnergy() {
		return this.energy.amount;
	}

	public long harvested() {
		return this.harvested;
	}

	public EnergyStorage getEnergyStorage() {
		return this.energy;
	}

	@Override
	public SlurryHandler slurryAt(Direction side) {
		return this.outputPort;
	}

	// ------------------------------------------------------------------ 界面

	@Override
	public Component getDisplayName() {
		return Component.translatable("block.geometallurgy.fluid_vein_extractor");
	}

	@Override
	public AbstractContainerMenu createMenu(int syncId, Inventory inventory, Player player) {
		return new FluidVeinExtractorMenu(syncId, inventory, this);
	}

	@Override
	public Unit getScreenOpeningData(ServerPlayer player) {
		return Unit.INSTANCE;
	}

	// ------------------------------------------------------------------ 存档

	@Override
	protected void saveAdditional(ValueOutput out) {
		super.saveAdditional(out);
		out.putInt("progress", (int) this.progress);
		out.putLong("harvested", this.harvested);
		out.putLong("energy", this.energy.amount);

		// 钻头按「材质 + 已磨损量」落盘。不走 ItemStack 的整套编解码，
		// 是因为这里只需要这两样东西，而它们恰好是最稳的两种基本类型。
		DrillMaterial material = this.drillMaterial();
		if (material != null) {
			out.putString("drill", material.id());
			out.putInt("drill_damage", this.drillSlot.getItem(0).getDamageValue());
		}

		ValueOutput.TypedOutputList<String> list = out.list("slurry", Codec.STRING);
		this.slurry.contents().forEach((part, amount) -> list.add(SlurryParts.key(part) + "=" + amount));

		// 矿脉的丰度与杂质：只落盘，界面上一个字都不显示。
		// 记下来是为了两件事 —— 老存档里的矿浆成分永远解释得清，日后下游机器（磁筛机、
		// 离心机）想读这条矿脉的品位与杂质时也有据可查，不必重算。
		if (this.deposit != null) {
			out.putString("vein_rock", this.deposit.rock().id());
			out.putLong("vein_epoch", this.deposit.epoch());
			ValueOutput.TypedOutputList<String> veins = out.list("veins", Codec.STRING);
			for (VeinAttributes vein : this.deposit.veins()) {
				veins.add(vein.encode());
			}
		}
	}

	@Override
	protected void loadAdditional(ValueInput in) {
		super.loadAdditional(in);
		this.progress = in.getIntOr("progress", 0);
		this.harvested = in.getLongOr("harvested", 0L);
		this.energy.amount = in.getLongOr("energy", 0L);

		DrillMaterial material = DrillMaterial.byId(in.getStringOr("drill", ""));
		if (material != null) {
			ItemStack bit = new ItemStack(ModItems.drillBit(material));
			bit.setDamageValue(in.getIntOr("drill_damage", 0));
			this.drillSlot.setItem(0, bit);
		} else {
			this.drillSlot.setItem(0, ItemStack.EMPTY);
		}

		this.slurry.clear();
		for (String entry : in.listOrEmpty("slurry", Codec.STRING)) {
			int separator = entry.indexOf('=');
			if (separator <= 0) {
				continue;
			}
			SlurryPart part = SlurryParts.fromKey(entry.substring(0, separator));
			if (part == null) {
				continue;
			}
			try {
				this.slurry.insert(part, Double.parseDouble(entry.substring(separator + 1)));
			} catch (NumberFormatException ignored) {
				// 存档里出现坏数据时跳过该项，不让整台机器读不出来
			}
		}

		RockType rock = RockType.byId(in.getStringOr("vein_rock", ""));
		if (rock != null) {
			List<VeinAttributes> veins = new ArrayList<>();
			for (String text : in.listOrEmpty("veins", Codec.STRING)) {
				VeinAttributes vein = VeinAttributes.decode(text, rock);
				if (vein != null) {
					veins.add(vein);
				}
			}
			this.deposit = VeinDeposit.of(rock, in.getLongOr("vein_epoch", Long.MIN_VALUE), veins);
		}
	}

	/** 被破坏时没有产物物品需要掉落 —— 产物是内部的虚拟流体；钻头另由方块负责弹出。 */
	@Override
	public void setRemoved() {
		super.setRemoved();
	}

	// ------------------------------------------------------------------ 容器转发
	//
	// 本机唯一的物品槽是钻头槽。把它按标准 Container 暴露出来，是为了让漏斗（以及
	// /item 命令）能自动续钻头 —— 一台整天啃石头的机器，最烦的就是人守在旁边换磨头。
	// 注意 setChanged() 不要在这里转发：drillSlot 的覆写已经在调用本类的 setChanged()，
	// 两边互相调用会直接栈溢出。

	@Override
	public int getContainerSize() {
		return this.drillSlot.getContainerSize();
	}

	@Override
	public boolean isEmpty() {
		return this.drillSlot.isEmpty();
	}

	@Override
	public ItemStack getItem(int slot) {
		return this.drillSlot.getItem(slot);
	}

	@Override
	public ItemStack removeItem(int slot, int amount) {
		return this.drillSlot.removeItem(slot, amount);
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		return this.drillSlot.removeItemNoUpdate(slot);
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		this.drillSlot.setItem(slot, stack);
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return this.drillSlot.canPlaceItem(slot, stack);
	}

	@Override
	public void clearContent() {
		this.drillSlot.clearContent();
	}

	@Override
	public boolean stillValid(Player player) {
		return Container.stillValidBlockEntity(this, player);
	}

	/** 占位：让 {@link Level} 相关的调用不至于因为未被覆写而漏掉副作用。 */
	public boolean isRunning() {
		return this.status == Status.RUNNING;
	}
}
