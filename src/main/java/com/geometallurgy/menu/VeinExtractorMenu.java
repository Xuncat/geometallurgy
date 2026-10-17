package com.geometallurgy.menu;

import java.util.ArrayList;
import java.util.List;

import com.geometallurgy.blockentity.VeinExtractorBlockEntity;
import com.geometallurgy.vein.BlockVeinDistribution;
import com.geometallurgy.vein.OreType;

import net.minecraft.util.Unit;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 矿脉抽取机的界面：3x3 产物缓冲 + 能量/矿脉信息。
 *
 * <p>矿脉分布通过 {@link ContainerData} 以整数形式同步：条目数、丰度、
 * 以及每种矿物的类型序号与稀有度（按万分之一量化）。这样不需要额外的网络包。
 */
public class VeinExtractorMenu extends AbstractContainerMenu {

	/** 同步数据槽数量：能量、进度、丰度、条目数，外加每种矿物 2 个（类型 + 稀有度），最多 4 种。 */
	public static final int DATA_COUNT = 4 + 4 * 2;
	public static final int OUTPUT_SLOTS = VeinExtractorBlockEntity.OUTPUT_SLOTS;

	private final VeinExtractorBlockEntity blockEntity;
	private final ContainerData data;

	/** 服务端构造：直接接上方块实体。 */
	public VeinExtractorMenu(int syncId, Inventory playerInventory, VeinExtractorBlockEntity blockEntity) {
		super(ModMenus.VEIN_EXTRACTOR, syncId);
		this.blockEntity = blockEntity;
		this.data = blockEntity.getContainerData();
		this.addMachineSlots(blockEntity.getOutputContainer());
		this.addPlayerSlots(playerInventory);
		this.addDataSlots(this.data);
	}

	/** 客户端构造（来自 MenuType 工厂）：容器内容与数据都由服务端同步过来。 */
	public VeinExtractorMenu(int syncId, Inventory playerInventory, Unit unit) {
		super(ModMenus.VEIN_EXTRACTOR, syncId);
		this.blockEntity = null;
		this.data = new SimpleContainerData(DATA_COUNT);
		this.addMachineSlots(new SimpleContainer(OUTPUT_SLOTS));
		this.addPlayerSlots(playerInventory);
		this.addDataSlots(this.data);
	}

	private void addMachineSlots(Container container) {
		// 3x3 产物缓冲，坐标对齐原版发射器界面的槽位
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 3; col++) {
				this.addSlot(new Slot(container, col + row * 3, 62 + col * 18, 17 + row * 18));
			}
		}
	}

	private void addPlayerSlots(Inventory playerInventory) {
		this.addStandardInventorySlots(playerInventory, 8, 84);
	}

	// ------------------------------------------------------------------ 数据读取

	/** 缓冲里的电（FE）。同步通道是 16 位有符号，所以方块实体那边除了刻度（bug#33），这里乘回来。 */
	public long energy() {
		return (this.data.get(0) & 0xFFFFFFFFL) * VeinExtractorBlockEntity.ENERGY_SYNC_DIVISOR;
	}

	public int progress() {
		return this.data.get(1);
	}

	public float abundance() {
		return this.data.get(2) / 1000F;
	}

	public int oreCount() {
		return Math.min(4, Math.max(0, this.data.get(3)));
	}

	/** 从同步数据重建本区块的矿脉分布（客户端绘制用）。 */
	public BlockVeinDistribution distribution() {
		List<BlockVeinDistribution.Entry> entries = new ArrayList<>(oreCount());
		for (int i = 0; i < oreCount(); i++) {
			int ordinal = this.data.get(4 + i * 2);
			if (ordinal < 0 || ordinal >= OreType.values().length) {
				continue;
			}
			OreType type = OreType.values()[ordinal];
			float rarity = this.data.get(5 + i * 2) / 10000F;
			entries.add(new BlockVeinDistribution.Entry(type, rarity));
		}
		return new BlockVeinDistribution(List.copyOf(entries), abundance());
	}

	// ------------------------------------------------------------------ 标准菜单方法

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = this.slots.get(index);
		if (!slot.hasItem()) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = slot.getItem();
		ItemStack snapshot = stack.copy();

		if (index < OUTPUT_SLOTS) {
			// 产物 -> 玩家背包
			if (!this.moveItemStackTo(stack, OUTPUT_SLOTS, this.slots.size(), true)) {
				return ItemStack.EMPTY;
			}
		} else {
			// 玩家背包 -> 产物缓冲（仅用于手动清理，机器本身只往缓冲里写）
			if (!this.moveItemStackTo(stack, 0, OUTPUT_SLOTS, false)) {
				return ItemStack.EMPTY;
			}
		}

		if (stack.isEmpty()) {
			slot.set(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}
		if (stack.getCount() == snapshot.getCount()) {
			return ItemStack.EMPTY;
		}
		slot.onTake(player, stack);
		return snapshot;
	}

	@Override
	public boolean stillValid(Player player) {
		if (this.blockEntity == null) {
			// 客户端侧：真正的距离校验由服务端负责
			return true;
		}
		return Container.stillValidBlockEntity(this.blockEntity, player);
	}
}
