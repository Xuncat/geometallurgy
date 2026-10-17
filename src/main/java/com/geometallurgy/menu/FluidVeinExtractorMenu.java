package com.geometallurgy.menu;

import java.util.ArrayList;
import java.util.List;

import com.geometallurgy.blockentity.FluidVeinExtractorBlockEntity;
import com.geometallurgy.blockentity.FluidVeinExtractorBlockEntity.Status;
import com.geometallurgy.geology.RockType;
import com.geometallurgy.item.DrillBitItem;
import com.geometallurgy.item.DrillMaterial;
import com.geometallurgy.slurry.SlurryPart;
import com.geometallurgy.slurry.SlurryParts;

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
 * 流体碎矿挖掘机的界面。
 *
 * <p>机器上只有一个物品槽，而且是<b>钻头槽</b>（不是产物槽）：产物是内部的虚拟矿浆，
 * 搬不动也丢不进漏斗。其余状态（能量、进度、液量、成分、原岩、钻头磨损、停机原因）
 * 全部通过 {@link ContainerData} 以整数形式同步，不需要额外的网络包。
 *
 * <p>槽位坐标必须与 {@code FluidVeinExtractorScreen} 里画的槽底纹对齐，
 * 物品栏的纵向起点同理 —— 两处的数字是一套，改一个就得改另一个。
 */
public class FluidVeinExtractorMenu extends AbstractContainerMenu {

	public static final int DATA_COUNT = FluidVeinExtractorBlockEntity.DATA_COUNT;
	/** 成分配额在同步数组中的起始下标。 */
	private static final int FRACTION_BASE = 8;

	/** 钻头槽在槽位列表里的下标。 */
	public static final int DRILL_SLOT = 0;
	/** 钻头槽的内容区坐标（16×16，不含边框）。 */
	private static final int DRILL_SLOT_X = 27;
	private static final int DRILL_SLOT_Y = 61;

	/** 玩家背包的纵向起点。必须与界面高度配套。 */
	private static final int INVENTORY_Y = 110;

	private final FluidVeinExtractorBlockEntity blockEntity;
	private final ContainerData data;

	/** 服务端构造：直接接上方块实体。 */
	public FluidVeinExtractorMenu(int syncId, Inventory playerInventory, FluidVeinExtractorBlockEntity blockEntity) {
		super(ModMenus.FLUID_VEIN_EXTRACTOR, syncId);
		this.blockEntity = blockEntity;
		this.data = blockEntity.getContainerData();
		this.addSlot(new Slot(blockEntity.drillSlot(), 0, DRILL_SLOT_X, DRILL_SLOT_Y));
		this.addStandardInventorySlots(playerInventory, 8, INVENTORY_Y);
		this.addDataSlots(this.data);
	}

	/** 客户端构造：槽内容与数据全部由服务端同步过来。 */
	public FluidVeinExtractorMenu(int syncId, Inventory playerInventory, Unit unit) {
		super(ModMenus.FLUID_VEIN_EXTRACTOR, syncId);
		this.blockEntity = null;
		this.data = new SimpleContainerData(DATA_COUNT);
		this.addSlot(new Slot(placeholder(), 0, DRILL_SLOT_X, DRILL_SLOT_Y));
		this.addStandardInventorySlots(playerInventory, 8, INVENTORY_Y);
		this.addDataSlots(this.data);
	}

	/**
	 * 客户端那侧的空壳钻头槽。
	 *
	 * <p>它也照抄「只收钻头」这条规则 —— 否则点击时客户端会先把物品塞进去再被服务端打回来，
	 * 中间那一瞬的错位看着像卡顿。
	 */
	private static SimpleContainer placeholder() {
		return new SimpleContainer(1) {
			@Override
			public boolean canPlaceItem(int index, ItemStack stack) {
				return stack.getItem() instanceof DrillBitItem;
			}
		};
	}

	// ------------------------------------------------------------------ 数据读取

	/** 缓冲里的电（FE）。同步通道是 16 位有符号，所以方块实体那边除了刻度（bug#33），这里乘回来。 */
	public long energy() {
		return (this.data.get(0) & 0xFFFFFFFFL) * FluidVeinExtractorBlockEntity.ENERGY_SYNC_DIVISOR;
	}

	public int progress() {
		return this.data.get(1);
	}

	/** 罐内液量（mB）。 */
	public double slurryAmount() {
		return this.data.get(2);
	}

	public int compositionCount() {
		return Math.clamp(this.data.get(3), 0, FluidVeinExtractorBlockEntity.TRACKED_ENTRIES);
	}

	/** 本机所在位置的原岩；尚未勘测时为 null。 */
	public RockType rockType() {
		int value = this.data.get(4);
		RockType[] values = RockType.values();
		if (value <= 0 || value > values.length) {
			return null;
		}
		return values[value - 1];
	}

	/** 当前装的钻头材质；没装时为 null。 */
	public DrillMaterial drillMaterial() {
		int value = this.data.get(5);
		DrillMaterial[] values = DrillMaterial.values();
		if (value <= 0 || value > values.length) {
			return null;
		}
		return values[value - 1];
	}

	/** 钻头剩余耐久的千分位（0~1000）；没装钻头时为 0。 */
	public double drillDurability() {
		return this.data.get(6) / 1000.0;
	}

	public Status status() {
		Status[] values = Status.values();
		int ordinal = this.data.get(7);
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : Status.NO_POWER;
	}

	/** 罐内成分，按占比从高到低。含量最高的那项几乎总是原岩。 */
	public List<CompositionEntry> composition() {
		int count = compositionCount();
		List<CompositionEntry> entries = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			SlurryPart part = SlurryParts.decode(this.data.get(FRACTION_BASE + i * 2));
			if (part == null) {
				continue;
			}
			double fraction = this.data.get(FRACTION_BASE + i * 2 + 1) / 1000.0;
			entries.add(new CompositionEntry(part, fraction));
		}
		return entries;
	}

	/** 一条成分记录：相（原岩或矿物）+ 占液相的比例。 */
	public record CompositionEntry(SlurryPart part, double fraction) {
	}

	// ------------------------------------------------------------------ 标准菜单方法

	/**
	 * Shift 点击：钻头槽 ↔ 玩家背包。
	 *
	 * <p>产物搬不动（是罐里的虚拟流体），所以这条捷径只服务钻头 —— 顺手换一根而已。
	 */
	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = this.slots.get(index);
		if (!slot.hasItem()) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = slot.getItem();
		ItemStack original = stack.copy();
		if (index == DRILL_SLOT) {
			if (!this.moveItemStackTo(stack, 1, this.slots.size(), true)) {
				return ItemStack.EMPTY;
			}
		} else if (!this.moveItemStackTo(stack, DRILL_SLOT, DRILL_SLOT + 1, false)) {
			return ItemStack.EMPTY;
		}
		if (stack.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}
		return original;
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
