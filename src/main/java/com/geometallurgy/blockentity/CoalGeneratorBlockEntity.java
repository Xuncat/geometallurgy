package com.geometallurgy.blockentity;

import com.geometallurgy.block.CoalGeneratorBlock;
import com.geometallurgy.menu.CoalGeneratorMenu;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Unit;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import team.reborn.energy.api.EnergyStorage;
import team.reborn.energy.api.EnergyStorageUtil;
import team.reborn.energy.api.base.SimpleEnergyStorage;

import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

/**
 * 火力发电机的方块实体。
 *
 * <p>数值：1 块煤 = 200 刻燃烧 = 每刻 64 能量，合计 12800 能量。
 * 内部缓冲容量正好是一个煤的量，缓冲满了就不再点火，避免白烧燃料。
 * 能量按 Team Reborn Energy 的 push 语义主动推给相邻机器。
 *
 * <p>燃料槽接受煤炭类物品与岩浆桶（后者沿用原版熔炉的 20000 刻）；
 * 烧完要留下容器的燃料，其空桶会进入输出槽。
 *
 * <p>本类直接实现 {@link Container} 与 {@link WorldlyContainer}（与熔炉等原版机器同构），
 * 因此原版漏斗能从上方、侧面把燃料送进燃料槽，也能从任意面取走内容。
 */
public class CoalGeneratorBlockEntity extends BlockEntity
		implements ExtendedMenuProvider<Unit>, Container, WorldlyContainer {

	/** 对外暴露的槽位索引：0 = 燃料槽，1 = 输出槽。 */
	public static final int FUEL_SLOT = 0;
	public static final int OUTPUT_SLOT = 1;
	/** 对外暴露的槽位总数。 */
	public static final int CONTAINER_SIZE = 2;

	/** 各面的可访问槽位。上方与侧面只开放燃料槽，下方额外开放输出槽以便抽取。 */
	private static final int[] SLOTS_UP = {FUEL_SLOT};
	private static final int[] SLOTS_SIDE = {FUEL_SLOT};
	private static final int[] SLOTS_DOWN = {FUEL_SLOT, OUTPUT_SLOT};

	/** 一块煤的燃烧时长（刻）。10 秒 = 200 刻。 */
	public static final int BURN_TICKS = 200;
	/** 一桶岩浆的燃烧时长（刻），沿用原版熔炉的取值。 */
	public static final int LAVA_BUCKET_BURN_TICKS = 20000;
	/** 每刻发电量。 */
	public static final long ENERGY_PER_TICK = 64L;
	/** 一块煤的总发电量。 */
	public static final long ENERGY_PER_FUEL = BURN_TICKS * ENERGY_PER_TICK;
	/** 内部缓冲容量。 */
	public static final long CAPACITY = ENERGY_PER_FUEL;
	/** 单次推送上限：让积压的能量能较快送出去。 */
	public static final long MAX_EXTRACT = ENERGY_PER_TICK * 4L;

	public final SimpleEnergyStorage energy = new SimpleEnergyStorage(CAPACITY, 0L, MAX_EXTRACT) {
		@Override
		protected void onFinalCommit() {
			CoalGeneratorBlockEntity.this.setChanged();
		}
	};

	private final SimpleContainer fuel = new SimpleContainer(1) {
		@Override
		public void setChanged() {
			super.setChanged();
			CoalGeneratorBlockEntity.this.setChanged();
		}
	};

	/** 输出槽：承接燃料容器的返还（岩浆桶烧完留下空桶）。 */
	private final SimpleContainer output = new SimpleContainer(1) {
		@Override
		public void setChanged() {
			super.setChanged();
			CoalGeneratorBlockEntity.this.setChanged();
		}
	};

	private final ContainerData dataAccess = new ContainerData() {
		@Override
		public int get(int index) {
			return switch (index) {
				case 0 -> (int) CoalGeneratorBlockEntity.this.energy.amount;
				case 1 -> CoalGeneratorBlockEntity.this.burnTime;
				default -> CoalGeneratorBlockEntity.this.burnTotal;
			};
		}

		@Override
		public void set(int index, int value) {
			// 只读
		}

		@Override
		public int getCount() {
			return CoalGeneratorMenu.DATA_COUNT;
		}
	};

	private int burnTime;
	private int burnTotal = BURN_TICKS;

	public CoalGeneratorBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.COAL_GENERATOR, pos, state);
	}

	public static void serverTick(ServerLevel level, BlockPos pos, BlockState state, CoalGeneratorBlockEntity generator) {
		generator.tick(level, pos, state);
	}

	private void tick(ServerLevel level, BlockPos pos, BlockState state) {
		boolean burning = this.burnTime > 0;

		// 1) 没在烧、并且缓冲还有空间 -> 尝试点火
		if (!burning && this.energy.amount < this.energy.getCapacity() && this.consumeFuel()) {
			burning = true;
		}

		// 2) 燃烧发电
		if (burning) {
			long room = this.energy.getCapacity() - this.energy.amount;
			if (room > 0L) {
				this.burnTime--;
				this.energy.amount += Math.min(ENERGY_PER_TICK, room);
				setChanged();
			}
		}

		// 3) 把能量推给相邻机器（push 语义）
		this.pushEnergy(level, pos);

		// 4) 点亮状态
		if (state.getValue(CoalGeneratorBlock.LIT) != burning) {
			level.setBlock(pos, state.setValue(CoalGeneratorBlock.LIT, burning), Block.UPDATE_ALL);
		}
	}

	/** 该物品能否作为燃料：煤炭类，或岩浆桶（后者烧完留下空桶）。 */
	public static boolean isFuel(ItemStack stack) {
		return stack.is(ItemTags.COALS) || stack.is(Items.LAVA_BUCKET);
	}

	private boolean consumeFuel() {
		ItemStack stack = this.fuel.getItem(0);
		if (stack.isEmpty()) {
			return false;
		}

		int ticks;
		ItemStack remainder;
		if (stack.is(ItemTags.COALS)) {
			ticks = BURN_TICKS;
			remainder = ItemStack.EMPTY;
		} else if (stack.is(Items.LAVA_BUCKET)) {
			ticks = LAVA_BUCKET_BURN_TICKS;
			remainder = new ItemStack(Items.BUCKET);
		} else {
			return false;
		}

		// 要返还容器时先确认输出槽放得下，否则不点火 —— 避免把桶吞掉。
		if (!remainder.isEmpty() && !canOutputAccept(remainder)) {
			return false;
		}

		stack.shrink(1);
		if (!remainder.isEmpty()) {
			this.output.addItem(remainder);
			this.output.setChanged();
		}
		this.burnTime = ticks;
		this.burnTotal = ticks;
		this.fuel.setChanged();
		setChanged();
		return true;
	}

	private boolean canOutputAccept(ItemStack stack) {
		for (int slot = 0; slot < this.output.getContainerSize(); slot++) {
			ItemStack existing = this.output.getItem(slot);
			if (existing.isEmpty()) {
				return true;
			}
			if (ItemStack.isSameItemSameComponents(existing, stack)
					&& existing.getCount() + stack.getCount() <= existing.getMaxStackSize()) {
				return true;
			}
		}
		return false;
	}

	private void pushEnergy(ServerLevel level, BlockPos pos) {
		if (this.energy.amount <= 0L) {
			return;
		}
		for (Direction direction : Direction.values()) {
			EnergyStorage target = EnergyStorage.SIDED.find(level, pos.relative(direction), direction.getOpposite());
			if (target == null) {
				continue;
			}
			try (Transaction transaction = Transaction.openOuter()) {
				long moved = EnergyStorageUtil.move(this.energy, target, MAX_EXTRACT, transaction);
				if (moved > 0L) {
					transaction.commit();
				}
			}
			if (this.energy.amount <= 0L) {
				return;
			}
		}
	}

	// ------------------------------------------------------------------ 容器接口（供漏斗与管道访问）

	@Override
	public int getContainerSize() {
		return CONTAINER_SIZE;
	}

	@Override
	public boolean isEmpty() {
		return this.fuel.isEmpty() && this.output.isEmpty();
	}

	@Override
	public ItemStack getItem(int index) {
		return switch (index) {
			case FUEL_SLOT -> this.fuel.getItem(0);
			case OUTPUT_SLOT -> this.output.getItem(0);
			default -> ItemStack.EMPTY;
		};
	}

	@Override
	public ItemStack removeItem(int index, int count) {
		return switch (index) {
			case FUEL_SLOT -> this.fuel.removeItem(0, count);
			case OUTPUT_SLOT -> this.output.removeItem(0, count);
			default -> ItemStack.EMPTY;
		};
	}

	@Override
	public ItemStack removeItemNoUpdate(int index) {
		return switch (index) {
			case FUEL_SLOT -> this.fuel.removeItemNoUpdate(0);
			case OUTPUT_SLOT -> this.output.removeItemNoUpdate(0);
			default -> ItemStack.EMPTY;
		};
	}

	@Override
	public void setItem(int index, ItemStack stack) {
		switch (index) {
			case FUEL_SLOT -> this.fuel.setItem(0, stack);
			case OUTPUT_SLOT -> this.output.setItem(0, stack);
			default -> {
			}
		}
	}

	@Override
	public void clearContent() {
		this.fuel.clearContent();
		this.output.clearContent();
	}

	@Override
	public boolean stillValid(Player player) {
		return Container.stillValidBlockEntity(this, player);
	}

	/** 只有燃料槽接受放入，且必须是真的燃料；输出槽由机器自己写入。 */
	@Override
	public boolean canPlaceItem(int index, ItemStack stack) {
		return index == FUEL_SLOT && isFuel(stack);
	}

	@Override
	public int[] getSlotsForFace(Direction side) {
		if (side == Direction.UP) {
			return SLOTS_UP;
		}
		if (side == Direction.DOWN) {
			return SLOTS_DOWN;
		}
		return SLOTS_SIDE;
	}

	@Override
	public boolean canPlaceItemThroughFace(int index, ItemStack stack, Direction side) {
		return this.canPlaceItem(index, stack);
	}

	@Override
	public boolean canTakeItemThroughFace(int index, ItemStack stack, Direction side) {
		return true;
	}

	// ------------------------------------------------------------------ 对外数据

	public long storedEnergy() {
		return this.energy.amount;
	}

	public long energyCapacity() {
		return this.energy.getCapacity();
	}

	public boolean isBurning() {
		return this.burnTime > 0;
	}

	/** 当前燃烧剩余刻数。 */
	public int burnTime() {
		return this.burnTime;
	}

	public int burnTotal() {
		return this.burnTotal;
	}

	public float burnProgress() {
		return this.burnTotal <= 0 ? 0F : (float) this.burnTime / (float) this.burnTotal;
	}

	public SimpleContainer getFuelContainer() {
		return this.fuel;
	}

	public SimpleContainer getOutputContainer() {
		return this.output;
	}

	public ContainerData getContainerData() {
		return this.dataAccess;
	}

	public EnergyStorage getEnergyStorage() {
		return this.energy;
	}

	// ------------------------------------------------------------------ 界面

	@Override
	public Component getDisplayName() {
		return Component.translatable("block.geometallurgy.coal_generator");
	}

	@Override
	public AbstractContainerMenu createMenu(int syncId, Inventory inventory, Player player) {
		return new CoalGeneratorMenu(syncId, inventory, this);
	}

	/** Fabric 的 {@link ExtendedMenuProvider} 契约：本界面不需要额外开屏数据。 */
	@Override
	public Unit getScreenOpeningData(ServerPlayer player) {
		return Unit.INSTANCE;
	}

	// ------------------------------------------------------------------ 存档

	@Override
	protected void saveAdditional(ValueOutput out) {
		super.saveAdditional(out);
		out.putInt("burn_time", this.burnTime);
		out.putInt("burn_total", this.burnTotal);
		out.putLong("energy", this.energy.amount);
		this.fuel.storeAsItemList(out.list("fuel", ItemStack.CODEC));
		this.output.storeAsItemList(out.list("output", ItemStack.CODEC));
	}

	@Override
	protected void loadAdditional(ValueInput in) {
		super.loadAdditional(in);
		this.burnTime = in.getIntOr("burn_time", 0);
		this.burnTotal = in.getIntOr("burn_total", BURN_TICKS);
		this.energy.amount = in.getLongOr("energy", 0L);
		this.fuel.fromItemList(in.listOrEmpty("fuel", ItemStack.CODEC));
		this.output.fromItemList(in.listOrEmpty("output", ItemStack.CODEC));
	}
}
