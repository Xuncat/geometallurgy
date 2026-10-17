package com.geometallurgy.menu;

import java.util.ArrayList;
import java.util.List;

import com.geometallurgy.blockentity.CentrifugeBlockEntity;
import com.geometallurgy.blockentity.CentrifugeBlockEntity.Status;
import com.geometallurgy.blockentity.PortRole;
import com.geometallurgy.slurry.SlurryPart;
import com.geometallurgy.slurry.SlurryParts;

import net.minecraft.core.Direction;
import net.minecraft.util.Unit;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/**
 * 离心机的界面。没有物品槽 —— 三罐装的都是虚拟矿浆。
 *
 * <p>同步字段布局（见 {@link CentrifugeBlockEntity}）：
 * 0 电能 / 1 功率×10 / 2 状态 / 3~5 三罐液量 / 6 六面包 / 7 切点×100 / 8~10 三罐成分条数，
 * 之后每罐 {@code TRACKED_ENTRIES} 组 [相的编码, 占比千分位]。
 */
public class CentrifugeMenu extends AbstractContainerMenu {

	public static final int DATA_COUNT = CentrifugeBlockEntity.DATA_COUNT;

	/** 三个罐的编号：0 进料 / 1 重相 / 2 轻相。 */
	public static final int TANK_INPUT = 0;
	public static final int TANK_HEAVY = 1;
	public static final int TANK_LIGHT = 2;

	private static final int FIXED_DATA = 11;

	/** 必须与 {@code CentrifugeScreen.GUI_HEIGHT} 配套，否则背包会画到面板外。 */
	private static final int INVENTORY_Y = 158;

	/** 六个面在界面上的固定排列顺序：上、下、北、南、西、东。 */
	public static final Direction[] FACE_ORDER = {
			Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
	};

	private final CentrifugeBlockEntity blockEntity;
	private final ContainerData data;

	public CentrifugeMenu(int syncId, Inventory playerInventory, CentrifugeBlockEntity blockEntity) {
		super(ModMenus.CENTRIFUGE, syncId);
		this.blockEntity = blockEntity;
		this.data = blockEntity.getContainerData();
		this.addStandardInventorySlots(playerInventory, 8, INVENTORY_Y);
		this.addDataSlots(this.data);
	}

	public CentrifugeMenu(int syncId, Inventory playerInventory, Unit unit) {
		super(ModMenus.CENTRIFUGE, syncId);
		this.blockEntity = null;
		this.data = new SimpleContainerData(DATA_COUNT);
		this.addStandardInventorySlots(playerInventory, 8, INVENTORY_Y);
		this.addDataSlots(this.data);
	}

	// ------------------------------------------------------------------ 数据读取

	public long energy() {
		return this.data.get(0) & 0xFFFFFFFFL;
	}

	public double power() {
		return this.data.get(1) / 10.0;
	}

	/** 转速。1.0 = 额定功率。 */
	public double rotation() {
		return power() / CentrifugeBlockEntity.NOMINAL_POWER;
	}

	public Status status() {
		Status[] values = Status.values();
		int ordinal = this.data.get(2);
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : Status.IDLE;
	}

	public double tankAmount(int tank) {
		return this.data.get(3 + tank);
	}

	/** 切点密度（g/cm³）。 */
	public double cut() {
		return this.data.get(7) / 100.0;
	}

	public List<CompositionEntry> composition(int tank) {
		int count = Math.clamp(this.data.get(8 + tank), 0, CentrifugeBlockEntity.TRACKED_ENTRIES);
		int base = FIXED_DATA + tank * CentrifugeBlockEntity.TRACKED_ENTRIES * 2;
		List<CompositionEntry> entries = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			SlurryPart part = SlurryParts.decode(this.data.get(base + i * 2));
			if (part == null) {
				continue;
			}
			double fraction = this.data.get(base + i * 2 + 1) / 1000.0;
			entries.add(new CompositionEntry(part, fraction));
		}
		return entries;
	}

	public PortRole role(Direction side) {
		int packed = this.data.get(6);
		PortRole[] values = PortRole.values();
		return values[(packed >> (side.ordinal() * 2)) & 0x3];
	}

	/** 一条成分记录：相 + 占该罐液相的比例。 */
	public record CompositionEntry(SlurryPart part, double fraction) {
	}

	// ------------------------------------------------------------------ 标准菜单方法

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		return ItemStack.EMPTY;
	}

	@Override
	public boolean clickMenuButton(Player player, int id) {
		if (this.blockEntity == null) {
			return false;
		}
		return this.blockEntity.handleButton(id);
	}

	@Override
	public boolean stillValid(Player player) {
		if (this.blockEntity == null) {
			return true;
		}
		return Container.stillValidBlockEntity(this.blockEntity, player);
	}
}
