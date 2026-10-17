package com.geometallurgy.menu;

import com.geometallurgy.blockentity.CoalGeneratorBlockEntity;

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

/** 火力发电机的界面：燃料槽 + 输出槽（容器返还），以及能量与燃烧进度。 */
public class CoalGeneratorMenu extends AbstractContainerMenu {

	public static final int DATA_COUNT = 3;
	/** 机器自有的槽位数：燃料槽 + 输出槽。 */
	private static final int MACHINE_SLOTS = 2;

	private final CoalGeneratorBlockEntity blockEntity;
	private final ContainerData data;

	public CoalGeneratorMenu(int syncId, Inventory playerInventory, CoalGeneratorBlockEntity blockEntity) {
		super(ModMenus.COAL_GENERATOR, syncId);
		this.blockEntity = blockEntity;
		this.data = blockEntity.getContainerData();
		this.addMachineSlots(blockEntity.getFuelContainer(), blockEntity.getOutputContainer());
		this.addPlayerSlots(playerInventory);
		this.addDataSlots(this.data);
	}

	public CoalGeneratorMenu(int syncId, Inventory playerInventory, Unit unit) {
		super(ModMenus.COAL_GENERATOR, syncId);
		this.blockEntity = null;
		this.data = new SimpleContainerData(DATA_COUNT);
		this.addMachineSlots(new SimpleContainer(1), new SimpleContainer(1));
		this.addPlayerSlots(playerInventory);
		this.addDataSlots(this.data);
	}

	private void addMachineSlots(Container fuelContainer, Container outputContainer) {
		// 坐标与原版熔炉的输入位、燃料位一致。
		// 燃料槽只接受燃料；输出槽由机器写入，不接受手动放入。
		this.addSlot(new Slot(fuelContainer, 0, 56, 17) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return CoalGeneratorBlockEntity.isFuel(stack);
			}
		});
		this.addSlot(new Slot(outputContainer, 0, 56, 53) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return false;
			}
		});
	}

	private void addPlayerSlots(Inventory playerInventory) {
		this.addStandardInventorySlots(playerInventory, 8, 84);
	}

	public long energy() {
		return this.data.get(0) & 0xFFFFFFFFL;
	}

	public int burnRemaining() {
		return this.data.get(1);
	}

	public int burnTotal() {
		return Math.max(1, this.data.get(2));
	}

	public float burnProgress() {
		return (float) burnRemaining() / (float) burnTotal();
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = this.slots.get(index);
		if (!slot.hasItem()) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = slot.getItem();
		ItemStack snapshot = stack.copy();

		if (index < MACHINE_SLOTS) {
			// 燃料槽 / 输出槽 -> 玩家背包
			if (!this.moveItemStackTo(stack, MACHINE_SLOTS, this.slots.size(), true)) {
				return ItemStack.EMPTY;
			}
		} else if (!this.moveItemStackTo(stack, 0, 1, false)) {
			// 玩家背包 -> 只进燃料槽（输出槽由机器写入，不接受手动放入）
			return ItemStack.EMPTY;
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
			return true;
		}
		return Container.stillValidBlockEntity(this.blockEntity, player);
	}
}
