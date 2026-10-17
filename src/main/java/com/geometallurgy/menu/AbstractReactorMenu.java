package com.geometallurgy.menu;

import com.geometallurgy.blockentity.AbstractReactorBlockEntity;
import com.geometallurgy.blockentity.ReactorStatus;
import com.geometallurgy.chemical.Chemical;

import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 反应釜与搅拌器共用的菜单骨架（版本 B）。
 *
 * <p><b>版本 B 的界面里没有桶槽。</b>机器内部的「混合 mol 槽」是一本账，不是物品格；
 * 桶放在旁边的箱子里，由机器按面角色自己交换。界面上只剩一个催化剂槽，
 * 其余空间都用来读账、选输出相、配六个面。
 *
 * <p>同步字段布局见 {@link AbstractReactorBlockEntity} 的 {@code D_*} 常量，
 * 全部是 16 位短整型，大数值统一按 ×100 压缩。
 */
public abstract class AbstractReactorMenu extends AbstractContainerMenu {

	public static final int DATA_COUNT = AbstractReactorBlockEntity.DATA_COUNT;

	/** 六个面在界面上的固定排列顺序：上、下、北、南、西、东（与方块实体一致）。 */
	public static final Direction[] FACE_ORDER = AbstractReactorBlockEntity.FACE_ORDER;

	/**
	 * 背包第一行的 y。与界面的 {@code GUI_HEIGHT} 配套。
	 *
	 * <p>2026-10-15 把机器区加高（多了 A/B 两根缓存条、比率档位格与搅拌开关），
	 * 面板从 222 抬到 238、物品栏跟着下移到 155。
	 */
	public static final int INVENTORY_Y = 155;

	/** 催化剂槽在界面里的位置。 */
	public static final int CATALYST_X = 8;
	public static final int CATALYST_Y = 32;

	protected final AbstractReactorBlockEntity blockEntity;
	private final ContainerData data;

	protected AbstractReactorMenu(MenuType<?> type, int syncId, AbstractReactorBlockEntity blockEntity) {
		super(type, syncId);
		this.blockEntity = blockEntity;
		this.data = blockEntity.getContainerData();
		addReactorSlots(blockEntity, this);
	}

	/** 客户端用的空壳：没有方块实体，数据全为 0。 */
	protected AbstractReactorMenu(MenuType<?> type, int syncId) {
		super(type, syncId);
		this.blockEntity = null;
		this.data = new SimpleContainerData(DATA_COUNT);
		addReactorSlots(placeholder(), this);
	}

	private static void addReactorSlots(Container items, AbstractReactorMenu menu) {
		menu.addSlot(new Slot(items, AbstractReactorBlockEntity.SLOT_CATALYST, CATALYST_X, CATALYST_Y));
	}

	/** 客户端空壳物品槽（镜像服务端的准入规则）。 */
	private static SimpleContainer placeholder() {
		return new SimpleContainer(AbstractReactorBlockEntity.ITEM_SLOTS) {
			@Override
			public boolean canPlaceItem(int index, ItemStack stack) {
				return index == AbstractReactorBlockEntity.SLOT_CATALYST
						&& AbstractReactorBlockEntity.isCatalyst(stack);
			}
		};
	}

	/** 子类把背包槽加进来（各机器的面板高度不同）。 */
	protected void addPlayerInventory(Inventory playerInventory) {
		this.addStandardInventorySlots(playerInventory, 8, INVENTORY_Y);
		this.addDataSlots(this.data);
	}

	// ------------------------------------------------------------------ 读数

	public double tankMol() {
		return this.data.get(AbstractReactorBlockEntity.D_TANK) / 100.0;
	}

	public double tankCapacity() {
		return AbstractReactorBlockEntity.TANK_CAPACITY;
	}

	/** 混合槽装满程度 0..1。 */
	public double fillFraction() {
		double capacity = tankCapacity();
		return capacity <= 0.0 ? 0.0 : Math.min(1.0, tankMol() / capacity);
	}

	public double massGrams() {
		return this.data.get(AbstractReactorBlockEntity.D_MASS);
	}

	public ReactorStatus status() {
		ReactorStatus[] values = ReactorStatus.values();
		return values[Math.floorMod(this.data.get(AbstractReactorBlockEntity.D_STATUS), values.length)];
	}

	/**
	 * {@code OUTPUT_FULL} 时堵住的那一相；没有堵住返回 {@code null}。
	 *
	 * <p>服务端把相态序号（0 = 无）+ 百 mol 量压进两个 16 位槽，这里还原成
	 * {@link Chemical.Phase} 与 mol —— 界面据此把状态写清楚。
	 */
	public Chemical.Phase blockedPhase() {
		return com.geometallurgy.chemical.OutputRouting.phaseAt(
				this.data.get(AbstractReactorBlockEntity.D_BLOCK_PHASE));
	}

	/** {@code OUTPUT_FULL} 时堵住的那一相有多少 mol；没有堵住返回 0。 */
	public double blockedMol() {
		return this.data.get(AbstractReactorBlockEntity.D_BLOCK_MOL) / 100.0;
	}

	/** 混合槽里最多的那种物质的 id；空账返回 null。 */
	public String dominantId() {
		int index = this.data.get(AbstractReactorBlockEntity.D_DOMINANT);
		return index <= 0 ? null : Chemical.idAt(index - 1);
	}

	public double dominantMol() {
		return this.data.get(AbstractReactorBlockEntity.D_DOMINANT_MOL) / 100.0;
	}

	public int ratio() {
		return this.data.get(AbstractReactorBlockEntity.D_RATIO);
	}

	/** 搅拌开不开（反应釜恒 false）。 */
	public boolean stirring() {
		return this.data.get(AbstractReactorBlockEntity.D_STIRRING) != 0;
	}

	/** 第 index 本预混缓冲的容量（mol）—— 由子类给常量，不需要同步。 */
	public abstract double bufferCapacity(int index);

	/** 第 index 本预混缓冲的装满程度 0..1。 */
	public double bufferFraction(int index) {
		double capacity = bufferCapacity(index);
		return capacity <= 0.0 ? 0.0 : Math.min(1.0, bufferMol(index) / capacity);
	}

	public double bufferMol(int index) {
		int d = index == 0 ? AbstractReactorBlockEntity.D_BUF_A : AbstractReactorBlockEntity.D_BUF_B;
		return this.data.get(d) / 100.0;
	}

	/**
	 * 某个面当前的「角色序号」（各机器自己的枚举序号）。
	 *
	 * <p>六个面各 3 bit（角色现在含出料相态，取值 5~6 个），共 18 bit 塞不进一个短整型，
	 * 所以拆成两个同步槽：{@code D_ROLES} 放方向序号 0..2，{@code D_ROLES_B} 放 3..5。
	 */
	public int roleOrdinal(Direction side) {
		int ordinal = side.ordinal();
		if (ordinal < 3) {
			return (this.data.get(AbstractReactorBlockEntity.D_ROLES) >> (ordinal * 3)) & 0x7;
		}
		return (this.data.get(AbstractReactorBlockEntity.D_ROLES_B) >> ((ordinal - 3) * 3)) & 0x7;
	}

	/** 混合槽成分（按 mol 从多到少），最多 {@link AbstractReactorBlockEntity#TRACKED_ENTRIES} 项。 */
	public java.util.List<CompositionEntry> composition() {
		return composition(AbstractReactorBlockEntity.D_COMP_COUNT, AbstractReactorBlockEntity.D_COMP_BASE);
	}

	/** 进料成分（A/B 缓冲按 mol 合并），饼图切到「进料」那一份时用。 */
	public java.util.List<CompositionEntry> feedComposition() {
		return composition(AbstractReactorBlockEntity.D_FEED_COUNT, AbstractReactorBlockEntity.D_FEED_BASE);
	}

	/** 读一份同构的成分同步块：计数格 + 每项两格（物质序号 + 1、占比千分位）。 */
	private java.util.List<CompositionEntry> composition(int countIndex, int base) {
		int count = Math.clamp(this.data.get(countIndex), 0, AbstractReactorBlockEntity.TRACKED_ENTRIES);
		java.util.List<CompositionEntry> out = new java.util.ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			int index = this.data.get(base + i * 2);
			int permille = this.data.get(base + i * 2 + 1);
			if (index == -1) {
				out.add(new CompositionEntry(null, permille / 1000.0)); // 余下的合成「其他」
				continue;
			}
			String id = index <= 0 ? null : Chemical.idAt(index - 1);
			if (id == null) {
				continue;
			}
			out.add(new CompositionEntry(id, permille / 1000.0));
		}
		return out;
	}

	/** 饼图的一项：物质 id + 占比。 */
	public record CompositionEntry(String chemicalId, double fraction) {
	}

	// ------------------------------------------------------------------ 交互

	@Override
	public boolean clickMenuButton(Player player, int id) {
		return this.blockEntity != null && this.blockEntity.handleButton(id);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = this.slots.get(index);
		if (!slot.hasItem() || this.blockEntity == null) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = slot.getItem();
		// 只有催化剂槽：在背包与催化剂槽之间搬。
		if (index == 0) {
			if (!this.moveItemStackTo(stack, 1, this.slots.size(), true)) {
				return ItemStack.EMPTY;
			}
		} else {
			if (!AbstractReactorBlockEntity.isCatalyst(stack)) {
				return ItemStack.EMPTY;
			}
			if (!this.moveItemStackTo(stack, 0, 1, false)) {
				return ItemStack.EMPTY;
			}
		}
		slot.setChanged();
		return stack;
	}

	@Override
	public boolean stillValid(Player player) {
		if (this.blockEntity == null) {
			return true;
		}
		return Container.stillValidBlockEntity(this.blockEntity, player);
	}
}
