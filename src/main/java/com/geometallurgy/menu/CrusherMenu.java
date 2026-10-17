package com.geometallurgy.menu;

import java.util.ArrayList;
import java.util.List;

import com.geometallurgy.blockentity.CrusherBlockEntity;
import com.geometallurgy.blockentity.CrusherBlockEntity.Status;
import com.geometallurgy.blockentity.PortRole;
import com.geometallurgy.slurry.SlurryPart;
import com.geometallurgy.slurry.SlurryParts;

import net.minecraft.core.Direction;
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
 * 粉碎机的界面。
 *
 * <p>两类输入并存：两罐虚拟矿浆（进料 / 出料），和两个物品槽（磨整件东西）。
 * 它们是两条独立的路 —— 矿浆路磨悬浮颗粒、按 mB 计；物品路磨整件（木炭 → 碳粉）、
 * 不占罐容。两条路共用同一份电。
 *
 * <p>同步字段布局（见 {@link CrusherBlockEntity}）：
 * 0 电能 / 1 功率×10 / 2 状态 / 3 进料量 / 4 出料量 / 5 六面包 / 6 加权硬度×100 / 7 成分条数，
 * 之后每 2 个一组 [相的编码, 占比千分位]。
 */
public class CrusherMenu extends AbstractContainerMenu {

	public static final int DATA_COUNT = CrusherBlockEntity.DATA_COUNT;

	/** 两个罐的编号：0 进料（矿浆）/ 1 出料（矿粉）。 */
	public static final int TANK_INPUT = 0;
	public static final int TANK_OUTPUT = 1;

	private static final int FIXED_DATA = 8;

	/** 必须与 {@code CrusherScreen.GUI_HEIGHT} 配套，否则背包会画到面板外。 */
	private static final int INVENTORY_Y = 130;

	/**
	 * 两个物品槽的内容区坐标（16×16，不含边框）。
	 *
	 * <p>摆在两个罐子之间的空当里：那里的横向区间是 65~111，正好塞得下两个 18 宽的槽位
	 * 外加中间一个箭头，纵向落在罐子的高度带里，不占任何新行 —— 面板高度因此一点没变。
	 *
	 * <p>这两个数字同时被界面用来画标签，所以只此一份，别在 {@code CrusherScreen} 里再写一遍。
	 */
	public static final int ITEM_IN_X = 68;
	public static final int ITEM_IN_Y = 48;
	public static final int ITEM_OUT_X = 94;
	public static final int ITEM_OUT_Y = 48;

	/** 六个面在界面上的固定排列顺序：上、下、北、南、西、东。 */
	public static final Direction[] FACE_ORDER = {
			Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
	};

	private final CrusherBlockEntity blockEntity;
	private final ContainerData data;

	public CrusherMenu(int syncId, Inventory playerInventory, CrusherBlockEntity blockEntity) {
		super(ModMenus.CRUSHER, syncId);
		this.blockEntity = blockEntity;
		this.data = blockEntity.getContainerData();
		this.addSlots(blockEntity.itemSlots(), playerInventory);
	}

	public CrusherMenu(int syncId, Inventory playerInventory, Unit unit) {
		super(ModMenus.CRUSHER, syncId);
		this.blockEntity = null;
		this.data = new SimpleContainerData(DATA_COUNT);
		this.addSlots(placeholder(), playerInventory);
	}

	/**
	 * 机器槽在前、玩家背包在后 —— 与其它机器一致。
	 *
	 * <p>槽位下标因此是 0 进料 / 1 出料 / 2 起是玩家背包；
	 * {@code CrusherBlockEntity.SLOT_INPUT / SLOT_OUTPUT} 说的就是前两个。
	 */
	private void addSlots(Container items, Inventory playerInventory) {
		this.addSlot(new Slot(items, CrusherBlockEntity.SLOT_INPUT, ITEM_IN_X, ITEM_IN_Y));
		this.addSlot(new OutputSlot(items, CrusherBlockEntity.SLOT_OUTPUT, ITEM_OUT_X, ITEM_OUT_Y));
		this.addStandardInventorySlots(playerInventory, 8, INVENTORY_Y);
		this.addDataSlots(this.data);
	}

	/**
	 * 客户端那侧的空壳物品槽。
	 *
	 * <p>照抄「只收研磨表里认得的东西」这条规则 —— 否则点击时客户端会先把物品塞进去、
	 * 再被服务端打回来，中间那一瞬的错位看着像卡顿。
	 */
	private static SimpleContainer placeholder() {
		return new SimpleContainer(CrusherBlockEntity.ITEM_SLOTS) {
			@Override
			public boolean canPlaceItem(int index, ItemStack stack) {
				return index == CrusherBlockEntity.SLOT_INPUT && CrusherBlockEntity.isGrindable(stack);
			}
		};
	}

	/** 出料槽：只出不进，玩家往里拖东西会被拒。 */
	private static class OutputSlot extends Slot {
		OutputSlot(Container container, int index, int x, int y) {
			super(container, index, x, y);
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return false;
		}
	}

	// ------------------------------------------------------------------ 数据读取

	public long energy() {
		return this.data.get(0) & 0xFFFFFFFFL;
	}

	public double power() {
		return this.data.get(1) / 10.0;
	}

	public Status status() {
		Status[] values = Status.values();
		int ordinal = this.data.get(2);
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : Status.IDLE;
	}

	public double tankAmount(int tank) {
		return this.data.get(3 + tank);
	}

	/** 进料颗粒的加权莫氏硬度。 */
	public double averageMohs() {
		return this.data.get(6) / 100.0;
	}

	/** 磨 1 mB 当前的能耗（FE）。 */
	public double energyPerMillibucket() {
		return CrusherBlockEntity.BASE_ENERGY_PER_MB * (1.0 + averageMohs() / 4.0);
	}

	public List<CompositionEntry> composition() {
		int count = Math.clamp(this.data.get(7), 0, CrusherBlockEntity.TRACKED_ENTRIES);
		List<CompositionEntry> entries = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			SlurryPart part = SlurryParts.decode(this.data.get(FIXED_DATA + i * 2));
			if (part == null) {
				continue;
			}
			double fraction = this.data.get(FIXED_DATA + i * 2 + 1) / 1000.0;
			entries.add(new CompositionEntry(part, fraction));
		}
		return entries;
	}

	public PortRole role(Direction side) {
		int packed = this.data.get(5);
		PortRole[] values = PortRole.values();
		return values[(packed >> (side.ordinal() * 2)) & 0x3];
	}

	/** 一条成分记录：相 + 占比。进出两罐成分相同，所以只有一份。 */
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
