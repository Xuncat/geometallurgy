package com.geometallurgy.blockentity;

import com.geometallurgy.block.VeinExtractorBlock;
import com.geometallurgy.menu.VeinExtractorMenu;
import com.geometallurgy.vein.BlockVeinDistribution;
import com.geometallurgy.vein.VeinGenerator;

import java.util.List;

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
 * 矿脉抽取机的方块实体。
 *
 * <p>工作链路：勘测本区块的虚拟矿脉分布 -> 每刻消耗 256 单位能量推进开采 ->
 * 按稀有度加权 + 丰度惩罚掷出产物 -> 放进内部缓冲 -> 定期外送给相邻容器。
 * 能量由相邻的火力发电机按 push 语义推入本机的缓冲。
 */
public class VeinExtractorBlockEntity extends BlockEntity implements ExtendedMenuProvider<Unit> {

	/**
	 * 运转需要的能量：每刻 1024 FE（2026-10-17 用户口径，与流体碎矿挖掘机同锚点）。
	 *
	 * <p>一次开采 20 刻 → <b>20480 FE / 件</b>（旧值 5120）。两台抽取机是并行的两个入口
	 * （固体取整件、流体取 100 mB 矿浆），模型 chemical/ProductionCost 里共用
	 * {@code ROOT_VEIN_FE} 一个数，所以必须同锚点，否则同一份矿根在两条路上差 4 倍电费。
	 */
	public static final long ENERGY_PER_TICK = 1024L;
	/** 内部能量缓冲容量：100 刻的量。 */
	public static final long CAPACITY = ENERGY_PER_TICK * 100L;
	/** 允许被充能的最大速率：4 倍消耗，便于快速回血。 */
	public static final long MAX_INSERT = ENERGY_PER_TICK * 4L;
	/** 一次开采需要的刻数。 */
	public static final int HARVEST_TICKS = 20;
	/** 产物缓冲槽数。 */
	public static final int OUTPUT_SLOTS = 9;
	/** 同步给界面的整数字段数：能量、进度、丰度、条目数 + 最多 4 种矿物 × 2。 */
	public static final int DATA_COUNT = 4 + 4 * 2;

	/**
	 * 能量同步刻度：内部按 FE 存，传到界面的能量读数 ÷ 这个数，界面再乘回来。
	 *
	 * <p><b>为什么必须有它</b>：{@code ContainerData} 最终由
	 * {@code ClientboundContainerSetDataPacket} 按 {@code writeShort} 发出，16 位有符号，
	 * 超过 32767 当场截断。本机缓冲是 {@link #CAPACITY} = 102400 FE（2026-10-17 从 25600 提上来），
	 * 直接传就会把界面读数打成一个巨大的正数、能量条一直是满的（bug#33）。
	 * 取 4：102400 / 4 = 25600，稳稳落在上限内，而 4 FE 的刻度在界面上看不出来。
	 */
	public static final int ENERGY_SYNC_DIVISOR = 4;

	public final SimpleEnergyStorage energy = new SimpleEnergyStorage(CAPACITY, MAX_INSERT, 0L) {
		@Override
		protected void onFinalCommit() {
			VeinExtractorBlockEntity.this.setChanged();
		}
	};

	private final SimpleContainer output = new SimpleContainer(OUTPUT_SLOTS) {
		@Override
		public void setChanged() {
			super.setChanged();
			VeinExtractorBlockEntity.this.setChanged();
		}
	};

	/** 只读的数据同步视图，服务端填值、客户端由菜单机制接收。 */
	private final ContainerData dataAccess = new ContainerData() {
		@Override
		public int get(int index) {
			return VeinExtractorBlockEntity.this.readData(index);
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

	/** 本区块的矿脉分布。由世界种子确定性算出，不入存档，需要时重算。 */
	private BlockVeinDistribution distribution;
	private int progress;
	private long harvested;

	public VeinExtractorBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.VEIN_EXTRACTOR, pos, state);
	}

	// ------------------------------------------------------------------ 主循环

	public static void serverTick(ServerLevel level, BlockPos pos, BlockState state, VeinExtractorBlockEntity extractor) {
		extractor.tick(level, pos, state);
	}

	private void tick(ServerLevel level, BlockPos pos, BlockState state) {
		if (this.distribution == null) {
			this.distribution = VeinGenerator.forPosition(level, pos);
		}

		// 只有「缓冲还收得下产物」时才烧电：九格全满就停扣、停推进，等下游取走再继续。
		// 旧行为是「有电就扣、扣到 harvest tick 才发现放不下」，产物满时会一直空烧
		// ENERGY_PER_TICK（bug#30）。流体版的 evaluate() 早已按 OUTPUT_FULL 停机，这里对齐它。
		boolean running = !this.outputFull() && this.energy.amount >= ENERGY_PER_TICK;
		if (running) {
			this.energy.amount -= ENERGY_PER_TICK;

			this.progress++;
			if (this.progress >= HARVEST_TICKS) {
				if (this.produce(level)) {
					this.progress = 0;
					this.harvested++;
				} else {
					// 缓冲满了：保持进度等待取走
					this.progress = HARVEST_TICKS;
				}
			}
			setChanged();
		}

		if (level.getGameTime() % 20L == 0L) {
			this.pushToNeighbors(level, pos);
		}

		if (state.getValue(VeinExtractorBlock.LIT) != running) {
			level.setBlock(pos, state.setValue(VeinExtractorBlock.LIT, running), Block.UPDATE_ALL);
		}
	}

	/**
	 * 产物缓冲九格是否连一件都放不下。
	 *
	 * <p>只看「槽位本身还有没有空位 / 半叠」，不提前掷产物 —— 掷骰要消耗随机数，
	 * 不能因为「可能放不下」就把随机序列推着走。真正放不放得下仍由 {@link #produce} 的
	 * {@code canAddItem} 把关。
	 */
	private boolean outputFull() {
		for (int slot = 0; slot < this.output.getContainerSize(); slot++) {
			ItemStack stack = this.output.getItem(slot);
			if (stack.isEmpty()
					|| stack.getCount() < Math.min(this.output.getMaxStackSize(), stack.getMaxStackSize())) {
				return false;
			}
		}
		return true;
	}

	private boolean produce(ServerLevel level) {
		ItemStack product = this.distribution.roll(level.getRandom());
		if (product.isEmpty() || !this.output.canAddItem(product)) {
			return false;
		}
		this.output.addItem(product);
		setChanged();
		return true;
	}

	// ------------------------------------------------------------------ 外送

	private void pushToNeighbors(ServerLevel level, BlockPos pos) {
		if (this.output.isEmpty()) {
			return;
		}
		for (Direction direction : Direction.values()) {
			BlockEntity neighbor = level.getBlockEntity(pos.relative(direction));
			if (neighbor instanceof Container container && neighbor != this) {
				this.moveInto(container);
			}
		}
	}

	private void moveInto(Container target) {
		boolean moved = false;
		for (int slot = 0; slot < this.output.getContainerSize(); slot++) {
			ItemStack stack = this.output.getItem(slot);
			if (stack.isEmpty()) {
				continue;
			}
			if (this.mergeInto(target, stack)) {
				moved = true;
			}
		}
		if (moved) {
			this.output.setChanged();
			target.setChanged();
		}
	}

	private boolean mergeInto(Container target, ItemStack stack) {
		int size = target.getContainerSize();

		for (int slot = 0; slot < size && !stack.isEmpty(); slot++) {
			ItemStack existing = target.getItem(slot);
			if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, stack)) {
				continue;
			}
			if (!target.canPlaceItem(slot, stack)) {
				continue;
			}
			int max = Math.min(target.getMaxStackSize(existing), existing.getMaxStackSize());
			int room = max - existing.getCount();
			if (room <= 0) {
				continue;
			}
			int amount = Math.min(room, stack.getCount());
			existing.grow(amount);
			stack.shrink(amount);
			target.setChanged();
		}

		for (int slot = 0; slot < size && !stack.isEmpty(); slot++) {
			if (!target.getItem(slot).isEmpty()) {
				continue;
			}
			if (!target.canPlaceItem(slot, stack)) {
				continue;
			}
			target.setItem(slot, stack.copy());
			stack.setCount(0);
			target.setChanged();
		}

		return stack.isEmpty();
	}

	// ------------------------------------------------------------------ 数据

	private int readData(int index) {
		List<BlockVeinDistribution.Entry> entries = this.distribution == null ? List.of() : this.distribution.entries();
		switch (index) {
			case 0:
				return (int) (this.energy.amount / ENERGY_SYNC_DIVISOR);
			case 1:
				return this.progress;
			case 2:
				return this.distribution == null ? 0 : Math.round(this.distribution.abundance() * 1000F);
			case 3:
				return entries.size();
			default:
				int slot = index - 4;
				int entryIndex = slot / 2;
				if (entryIndex < 0 || entryIndex >= entries.size()) {
					return 0;
				}
				BlockVeinDistribution.Entry entry = entries.get(entryIndex);
				return (slot % 2 == 0)
						? entry.type().ordinal()
						: Math.round(entry.rarity() * 10000F);
		}
	}

	public ContainerData getContainerData() {
		return this.dataAccess;
	}

	public BlockVeinDistribution distribution() {
		return this.distribution;
	}

	public long storedEnergy() {
		return this.energy.amount;
	}

	public long energyCapacity() {
		return this.energy.getCapacity();
	}

	public int progress() {
		return this.progress;
	}

	public long harvested() {
		return this.harvested;
	}

	public Container getOutputContainer() {
		return this.output;
	}

	/** 暴露给能量接口：相邻发电机按 push 语义往这里充电。 */
	public EnergyStorage getEnergyStorage() {
		return this.energy;
	}

	// ------------------------------------------------------------------ 界面

	@Override
	public Component getDisplayName() {
		return Component.translatable("block.geometallurgy.vein_extractor");
	}

	@Override
	public AbstractContainerMenu createMenu(int syncId, Inventory inventory, Player player) {
		return new VeinExtractorMenu(syncId, inventory, this);
	}

	/**
	 * Fabric 的 {@link ExtendedMenuProvider} 契约：菜单由 {@code ExtendedMenuType}
	 * 注册时，打开方必须提供发送给客户端的开屏数据。本界面不需要额外数据。
	 */
	@Override
	public Unit getScreenOpeningData(ServerPlayer player) {
		return Unit.INSTANCE;
	}

	// ------------------------------------------------------------------ 存档

	@Override
	protected void saveAdditional(ValueOutput out) {
		super.saveAdditional(out);
		out.putInt("progress", this.progress);
		out.putLong("harvested", this.harvested);
		out.putLong("energy", this.energy.amount);
		this.output.storeAsItemList(out.list("output", ItemStack.CODEC));
	}

	@Override
	protected void loadAdditional(ValueInput in) {
		super.loadAdditional(in);
		this.progress = in.getIntOr("progress", 0);
		this.harvested = in.getLongOr("harvested", 0L);
		this.energy.amount = in.getLongOr("energy", 0L);
		this.output.fromItemList(in.listOrEmpty("output", ItemStack.CODEC));
	}
}
