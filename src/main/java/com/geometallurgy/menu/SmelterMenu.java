package com.geometallurgy.menu;

import java.util.ArrayList;
import java.util.List;

import com.geometallurgy.blockentity.ElectrolysisRole;
import com.geometallurgy.blockentity.PortRole;
import com.geometallurgy.blockentity.SmelterBlockEntity;
import com.geometallurgy.blockentity.SmelterBlockEntity.Status;
import com.geometallurgy.blockentity.SmelterProfile;
import com.geometallurgy.chemical.Chemical;
import com.geometallurgy.chemical.OutputRouting;
import com.geometallurgy.metal.Reagent;
import com.geometallurgy.proto.Substance;
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
 * 冶炼炉的界面。两罐虚拟矿浆（进料 / 渣）不走物品槽，另有两个真物品槽（试剂 / 产物）。
 *
 * <p>同步字段布局（见 {@link SmelterBlockEntity}）：
 * 0 电能 / 1 功率×10 / 2 状态 / 3 进料量 / 4 渣量 / 5 六面包 / 6 进度 /
 * 7 纯度×1000 / 8 可回收产物×1000 / 9 试剂当量×1000，
 * 之后每 2 个一组：先 4 组 {@code [相的编码, 占比千分位]}，再 4 组 {@code [物质序号, 占比千分位]}。
 */
public class SmelterMenu extends AbstractContainerMenu {

	public static final int DATA_COUNT = SmelterBlockEntity.DATA_COUNT;

	/** 两段成分表的起点与条数位 —— 与服务端同一组常量，改一处两边一起动。 */
	private static final int INPUT_COUNT = 10;
	private static final int INPUT_BASE = SmelterBlockEntity.INPUT_BASE;
	private static final int PRODUCT_COUNT = SmelterBlockEntity.PRODUCT_COUNT_INDEX;
	private static final int PRODUCT_BASE = SmelterBlockEntity.PRODUCT_BASE;

	/** 两个虚拟罐的编号。 */
	public static final int TANK_INPUT = 0;
	public static final int TANK_SLAG = 1;

	/** 槽位坐标：必须与 {@code SmelterScreen} 里画的槽底纹对齐。 */
	public static final int SLOT_REAGENT_X = 26;
	public static final int SLOT_REAGENT_Y = 44;
	/** 阴极槽：只有电解炉有，挂在试剂（阳极）那一格下面。 */
	public static final int SLOT_CATHODE_X = 26;
	public static final int SLOT_CATHODE_Y = 64;
	public static final int SLOT_OUTPUT_X = 152;
	public static final int SLOT_OUTPUT_Y = 44;

	/** 玩家背包的纵向起点。必须与 {@code SmelterScreen.GUI_HEIGHT} 配套。 */
	private static final int INVENTORY_Y = 142;

	/** 六个面在界面上的固定排列顺序：上、下、北、南、西、东。 */
	public static final Direction[] FACE_ORDER = {
			Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
	};

	private final SmelterBlockEntity blockEntity;
	private final ContainerData data;
	private final SmelterProfile profile;

	/** 服务端构造：直接接上方块实体。 */
	public SmelterMenu(int syncId, Inventory playerInventory, SmelterBlockEntity blockEntity,
			SmelterProfile profile) {
		super(ModMenus.smelter(profile), syncId);
		this.blockEntity = blockEntity;
		this.profile = profile;
		this.data = blockEntity.getContainerData();
		this.installSlots(playerInventory, blockEntity);
	}

	/** 客户端构造：槽内容与数据全部由服务端同步过来。 */
	public SmelterMenu(int syncId, Inventory playerInventory, Unit unit, SmelterProfile profile) {
		super(ModMenus.smelter(profile), syncId);
		this.blockEntity = null;
		this.profile = profile;
		this.data = new SimpleContainerData(DATA_COUNT);
		this.installSlots(playerInventory, placeholder(profile));
	}

	/**
	 * 客户端那侧的空壳槽。
	 *
	 * <p>它也照抄「试剂槽只收试剂」这条规则 —— 否则点击时客户端会先把物品塞进去再被服务端打回来，
	 * 中间那一瞬的错位看着像卡顿。
	 */
	private static SimpleContainer placeholder(SmelterProfile profile) {
		return new SimpleContainer(SmelterBlockEntity.SLOT_COUNT) {
			@Override
			public boolean canPlaceItem(int index, ItemStack stack) {
				boolean reagentSlot = index == SmelterBlockEntity.SLOT_REAGENT
						|| (index == SmelterBlockEntity.SLOT_CATHODE
								&& profile.reagent() == SmelterProfile.ReagentKind.ELECTRODE);
				// 准入规则只有 SmelterProfile.ReagentKind.accepts 一处，别在两处各写一遍；
				// 「一次一件」载具守卫与服务端 acceptsReagent 同步（一摞桶共享内容组件）
				return reagentSlot && profile.reagent().accepts(stack) && Reagent.oneAtATime(stack);
			}
		};
	}

	private void installSlots(Inventory playerInventory, Container container) {
		this.addSlot(new Slot(container, SmelterBlockEntity.SLOT_REAGENT,
				SLOT_REAGENT_X, SLOT_REAGENT_Y));
		if (this.profile.reagent() == SmelterProfile.ReagentKind.ELECTRODE) {
			// 电解池是两电极的：上面那格是阳极（烧得快），下面那格是阴极（只传电）
			this.addSlot(new Slot(container, SmelterBlockEntity.SLOT_CATHODE,
					SLOT_CATHODE_X, SLOT_CATHODE_Y));
		}
		this.addSlot(new Slot(container, SmelterBlockEntity.SLOT_OUTPUT,
				SLOT_OUTPUT_X, SLOT_OUTPUT_Y) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				// 产物槽由机器自己写，玩家只能取
				return false;
			}
		});
		this.addStandardInventorySlots(playerInventory, 8, INVENTORY_Y);
		this.addDataSlots(this.data);
	}

	// ------------------------------------------------------------------ 数据读取

	public SmelterProfile profile() {
		return this.profile;
	}

	public long energy() {
		// 服务端按 FE÷10 传（见 SmelterBlockEntity.ENERGY_SYNC_DIVISOR），这里乘回来
		return this.data.get(0) * (long) SmelterBlockEntity.ENERGY_SYNC_DIVISOR;
	}

	public double power() {
		return this.data.get(1) / 10.0;
	}

	public Status status() {
		Status[] values = Status.values();
		int ordinal = this.data.get(2);
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : Status.IDLE;
	}

	/** 电解容器堵住的那一相；没有堵住返回 {@code null}（见 {@link SmelterBlockEntity#updateBlockedVessel}）。 */
	public Chemical.Phase blockedPhase() {
		return OutputRouting.phaseAt(this.data.get(SmelterBlockEntity.BLOCK_PHASE_INDEX));
	}

	/** 电解容器堵住的那一相有多少 mol；没有堵住返回 0。 */
	public double blockedMol() {
		return this.data.get(SmelterBlockEntity.BLOCK_MOL_INDEX) / 100.0;
	}

	public double tankAmount(int tank) {
		return this.data.get(3 + tank);
	}

	/** 参与反应的矿物占罐内固体的比例（0~1）。料越脏这个数越小。 */
	public double purity() {
		return this.data.get(7) / 1000.0;
	}

	/** 一个炉次能回收的产物量（mB 当量）。 */
	public double productYield() {
		return this.data.get(8) / (double) SmelterBlockEntity.YIELD_SYNC_SCALE;
	}

	public double reagentCharge() {
		return this.data.get(9) / 1000.0;
	}

	/** 溶液电解账的总 mol（只有电解炉非零）。 */
	public double solutionMol() {
		return this.data.get(SmelterBlockEntity.SOLUTION_MOL_INDEX) / 100.0;
	}

	/** 阳极容器账的总 mol。 */
	public double anodeMol() {
		return this.data.get(SmelterBlockEntity.ANODE_MOL_INDEX) / 100.0;
	}

	/** 阴极容器账的总 mol。 */
	public double cathodeMol() {
		return this.data.get(SmelterBlockEntity.CATHODE_MOL_INDEX) / 100.0;
	}

	/** 这台机器是不是电解炉（带溶液电解档）。 */
	public boolean usesSolution() {
		return this.profile.reagent() == SmelterProfile.ReagentKind.ELECTRODE;
	}

	/**
	 * 电解炉此刻该按哪一档显示。
	 *
	 * <p>这台炉子两档并存、没有独立的模式字段，所以界面按<b>玩家配了哪些面</b>来选：
	 * 只要有一面被点成 {@link ElectrolysisRole#POWDER_IN} 或 {@link ElectrolysisRole#SLAG_OUT}，
	 * 就按熔盐档画（矿粉罐 / 渣罐 + 进料 / 产物两个页签）；否则按溶液档画
	 * （溶液条 + 阳极 / 阴极容器 + 五个页签）。两个布局不会同时出现。
	 *
	 * <p>还原 / 置换两路没有溶液档，恒为熔盐档布局。
	 */
	public boolean solutionMode() {
		if (!this.usesSolution()) {
			return false;
		}
		for (Direction side : Direction.values()) {
			ElectrolysisRole role = this.electroRole(side);
			if (role == ElectrolysisRole.POWDER_IN || role == ElectrolysisRole.SLAG_OUT) {
				return false;
			}
		}
		return true;
	}

	/** 溶液电解账里 mol 最多的物质 id；空账返回 null。 */
	public String solutionDominantId() {
		return dominantId(this.solutionComposition());
	}

	/** 阳极容器里 mol 最多的物质 id；空账返回 null。 */
	public String anodeDominantId() {
		return dominantId(this.anodeComposition());
	}

	/** 阴极容器里 mol 最多的物质 id；空账返回 null。 */
	public String cathodeDominantId() {
		return dominantId(this.cathodeComposition());
	}

	private static String dominantId(List<ChemicalEntry> entries) {
		String best = null;
		double bestFraction = 0.0;
		for (ChemicalEntry entry : entries) {
			if (entry.fraction() > bestFraction) {
				bestFraction = entry.fraction();
				best = entry.id();
			}
		}
		return best;
	}

	/** 溶液成分表（同步过来的，按占比降序）。 */
	public List<ChemicalEntry> solutionComposition() {
		return this.composition(SmelterBlockEntity.SOLUTION_COUNT_INDEX, SmelterBlockEntity.SOLUTION_BASE);
	}

	/** 阳极容器成分表。 */
	public List<ChemicalEntry> anodeComposition() {
		return this.composition(SmelterBlockEntity.ANODE_COUNT_INDEX, SmelterBlockEntity.ANODE_BASE);
	}

	/** 阴极容器成分表。 */
	public List<ChemicalEntry> cathodeComposition() {
		return this.composition(SmelterBlockEntity.CATHODE_COUNT_INDEX, SmelterBlockEntity.CATHODE_BASE);
	}

	/** 一份 mol 账的成分表（同步过来的，按占比降序）。 */
	private List<ChemicalEntry> composition(int countIndex, int base) {
		int count = count(this.data.get(countIndex));
		List<ChemicalEntry> entries = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			String id = com.geometallurgy.chemical.Chemical.idAt(this.data.get(base + i * 2) - 1);
			if (id == null) {
				continue;
			}
			entries.add(new ChemicalEntry(id, this.data.get(base + i * 2 + 1) / 1000.0));
		}
		return entries;
	}

	/** 溶液成分的一项：物质 id + 占比（0~1）。 */
	public record ChemicalEntry(String id, double fraction) {
	}

	public int progress() {
		return this.data.get(6);
	}

	public boolean running() {
		return this.status() == Status.RUNNING;
	}

	/** 一个炉次的总刻数。 */
	public int batchTicks() {
		return this.profile.ticksPerBatch();
	}

	/** 进度（0~1）。 */
	public double progressFraction() {
		return this.batchTicks() <= 0 ? 0.0 : (double) this.progress() / this.batchTicks();
	}

	/** 共用（还原 / 置换）路的某一面角色；电解路请用 {@link #electroRole(Direction)}。 */
	public PortRole role(Direction side) {
		int packed = this.data.get(5);
		PortRole[] values = PortRole.values();
		return values[(packed >> (side.ordinal() * 2)) & 0x3];
	}

	/**
	 * 电解路某一面的角色。
	 *
	 * <p>七个取值 → 每面 3 bit、六面 18 bit，一个同步槽塞不下，所以与
	 * {@code AbstractReactorBlockEntity} 一样拆两个槽：面 0~2 在 {@code data[5]}，
	 * 面 3~5 在 {@link SmelterBlockEntity#ROLES_B_INDEX}。
	 */
	public ElectrolysisRole electroRole(Direction side) {
		int ordinal = side.ordinal();
		int packed = ordinal < 3
				? this.data.get(5)
				: this.data.get(SmelterBlockEntity.ROLES_B_INDEX);
		ElectrolysisRole[] values = ElectrolysisRole.values();
		int index = (packed >> ((ordinal % 3) * 3)) & 0x7;
		return index < values.length ? values[index] : ElectrolysisRole.DISABLED;
	}

	/** 进料罐里的主要相。 */
	public List<PhaseEntry> inputPhases() {
		int count = count(this.data.get(INPUT_COUNT));
		List<PhaseEntry> entries = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			SlurryPart part = SlurryParts.decode(this.data.get(INPUT_BASE + i * 2));
			if (part == null) {
				continue;
			}
			entries.add(new PhaseEntry(part, this.data.get(INPUT_BASE + i * 2 + 1) / 1000.0));
		}
		return entries;
	}

	/** 这一炉预计产出的成分。 */
	public List<ProductEntry> plannedProduct() {
		int count = count(this.data.get(PRODUCT_COUNT));
		List<ProductEntry> entries = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			String substance = Substance.byIndex(this.data.get(PRODUCT_BASE + i * 2));
			if (substance == null) {
				continue;
			}
			entries.add(new ProductEntry(substance, this.data.get(PRODUCT_BASE + i * 2 + 1) / 1000.0));
		}
		return entries;
	}

	/** 同步数组里「条数」那一位；服务端把超出配额的条目也填成了 0，所以要卡上限。 */
	private static int count(int raw) {
		return Math.clamp(raw, 0, SmelterBlockEntity.TRACKED_ENTRIES);
	}

	public record PhaseEntry(SlurryPart part, double fraction) {
	}

	public record ProductEntry(String substance, double fraction) {
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
