package com.geometallurgy.menu;

import java.util.ArrayList;
import java.util.List;

import com.geometallurgy.blockentity.FaceRole;
import com.geometallurgy.blockentity.MagneticSeparatorBlockEntity;
import com.geometallurgy.blockentity.MagneticSeparatorBlockEntity.Status;
import com.geometallurgy.slurry.SlurryForm;
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
 * 磁力筛机的界面。
 *
 * <p>与流体碎矿挖掘机一样，本机没有物品槽 —— 三个罐装的都是虚拟矿浆，搬不动也丢不进漏斗。
 * 界面里的六个面配置格与「尾矿处理」开关都走 {@code clickMenuButton}：
 * 那是一条现成的客户端 → 服务端通道，不必为此加网络包。
 *
 * <p>同步字段布局（见 {@link MagneticSeparatorBlockEntity}）：
 * 0 电能 / 1 功率×10 / 2 状态 / 3~5 三罐液量 / 6 六面包 / 7 尾矿销毁 / 8~10 三罐成分条数，
 * 之后每罐 {@code TRACKED_ENTRIES} 组 [相的编码, 占比千分位]。
 */
public class MagneticSeparatorMenu extends AbstractContainerMenu {

	public static final int DATA_COUNT = MagneticSeparatorBlockEntity.DATA_COUNT;

	/** 三个罐的编号：0 输入 / 1 磁性产物 / 2 非磁性产物。 */
	public static final int TANK_INPUT = 0;
	public static final int TANK_MAGNETIC = 1;
	public static final int TANK_NON_MAGNETIC = 2;

	private static final int FIXED_DATA = 12;

	/**
	 * 玩家背包在界面里的纵向起点。
	 *
	 * <p>这个数字必须与 {@code MagneticSeparatorScreen.GUI_HEIGHT} 配套：前者是面板高度、
	 * 这里是物品栏位置，差了就会让背包画到面板外去。
	 */
	private static final int INVENTORY_Y = 158;

	/** 六个面在界面上的固定排列顺序：上、下、北、南、西、东。 */
	public static final Direction[] FACE_ORDER = {
			Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
	};

	private final MagneticSeparatorBlockEntity blockEntity;
	private final ContainerData data;

	/** 服务端构造：直接接上方块实体。 */
	public MagneticSeparatorMenu(int syncId, Inventory playerInventory, MagneticSeparatorBlockEntity blockEntity) {
		super(ModMenus.MAGNETIC_SEPARATOR, syncId);
		this.blockEntity = blockEntity;
		this.data = blockEntity.getContainerData();
		this.addStandardInventorySlots(playerInventory, 8, INVENTORY_Y);
		this.addDataSlots(this.data);
	}

	/** 客户端构造：全部数据由服务端同步过来。 */
	public MagneticSeparatorMenu(int syncId, Inventory playerInventory, Unit unit) {
		super(ModMenus.MAGNETIC_SEPARATOR, syncId);
		this.blockEntity = null;
		this.data = new SimpleContainerData(DATA_COUNT);
		this.addStandardInventorySlots(playerInventory, 8, INVENTORY_Y);
		this.addDataSlots(this.data);
	}

	// ------------------------------------------------------------------ 数据读取

	public long energy() {
		return this.data.get(0) & 0xFFFFFFFFL;
	}

	/** 当前功率（FE/t）。磁场强度 = 它 / 64。 */
	public double power() {
		return this.data.get(1) / 10.0;
	}

	public double fieldStrength() {
		return this.power() / MagneticSeparatorBlockEntity.NOMINAL_POWER;
	}

	/** 当前进料是矿浆还是矿粉。粉料走干式磁选，窗口要窄一截。 */
	public SlurryForm form() {
		SlurryForm[] values = SlurryForm.values();
		int ordinal = this.data.get(11);
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : SlurryForm.SLURRY;
	}

	public boolean powder() {
		return form() == SlurryForm.POWDER;
	}

	/**
	 * 理想窗口的两端。
	 *
	 * <p>必须与方块实体上的 {@code fieldWeak()} / {@code fieldStrong()} 保持一致 ——
	 * 界面画的那条绿带子就是这两个数，画错了人就会照着坏区调电。
	 */
	public double fieldWeak() {
		return powder() ? MagneticSeparatorBlockEntity.POWDER_WEAK : MagneticSeparatorBlockEntity.FIELD_WEAK;
	}

	public double fieldStrong() {
		return powder() ? MagneticSeparatorBlockEntity.POWDER_STRONG : MagneticSeparatorBlockEntity.FIELD_STRONG;
	}

	public Status status() {
		Status[] values = Status.values();
		int ordinal = this.data.get(2);
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : Status.IDLE;
	}

	/** 某个罐的液量（mB）。 */
	public double tankAmount(int tank) {
		return this.data.get(3 + tank);
	}

	/** 某个罐里按含量排序的成分；界面把它画成液位条上的色段。 */
	public List<CompositionEntry> composition(int tank) {
		int count = Math.clamp(this.data.get(8 + tank), 0, MagneticSeparatorBlockEntity.TRACKED_ENTRIES);
		int base = FIXED_DATA + tank * MagneticSeparatorBlockEntity.TRACKED_ENTRIES * 2;
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

	/** 某个面当前的角色。 */
	public FaceRole role(Direction side) {
		int packed = this.data.get(6);
		FaceRole[] values = FaceRole.values();
		return values[(packed >> (side.ordinal() * 2)) & 0x3];
	}

	public boolean destroyTailings() {
		return this.data.get(7) != 0;
	}

	/** 一条成分记录：相 + 占该罐液相的比例。 */
	public record CompositionEntry(SlurryPart part, double fraction) {
	}

	// ------------------------------------------------------------------ 标准菜单方法

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		return ItemStack.EMPTY;
	}

	/**
	 * 界面上点六面配置格与尾矿开关走的就是这条路（客户端发按钮号，服务端在这里落地）。
	 *
	 * <p>配置本身存在方块实体上，菜单只是一层皮，所以这里直接转交过去。
	 */
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
