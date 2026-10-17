package com.geometallurgy.menu;

import com.geometallurgy.item.MineralProspectorItem;
import com.geometallurgy.proto.OreMineral;
import com.geometallurgy.vein.EpochClock;
import com.geometallurgy.vein.OreBody;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/**
 * 矿物探测仪的选择界面。
 *
 * <p>界面里没有一个物品槽 —— 它只是一张矿物清单。选项序号走
 * {@link ContainerData} 同步（服务端 → 客户端），点选走
 * {@code clickMenuButton}（客户端 → 服务端），两条都是原版现成的通道，
 * 不需要自定义网络包。
 *
 * <p>点选之后由服务端把选择写回玩家手里的探测仪组件，所以关掉界面、换个槽位、
 * 甚至退出重进，选择都还在。
 */
public class ProspectorMenu extends AbstractContainerMenu {

	public static final int DATA_COUNT = 3;
	/** 序号在同步数组里的下标。 */
	private static final int SELECTED = 0;
	/** 本处矿体单元的纪元（虚拟日历）。 */
	private static final int CELL_EPOCH = 1;
	/** 距离本处换脉的剩余游戏日 × 10（0~100），避开 16 位有符号通道。 */
	private static final int DAYS_LEFT_X10 = 2;

	private final ContainerData data;
	/** 服务端据此算虚拟日历；客户端只用服务端下发的同步值。 */
	private final Player owner;

	/**
	 * 服务端与客户端共用同一个构造器：服务端由 {@code Opening.createMenu} 直接 new，
	 * 客户端由 {@code ExtendedMenuType} 用开屏数据重建，入参形状一致。
	 */
	public ProspectorMenu(int syncId, Inventory playerInventory, Integer selectedOrdinal) {
		super(ModMenus.MINERAL_PROSPECTOR, syncId);
		this.data = new SimpleContainerData(DATA_COUNT);
		this.owner = playerInventory.player;
		this.data.set(SELECTED, selectedOrdinal == null ? 0 : selectedOrdinal);
		this.addDataSlots(this.data);
	}

	// ------------------------------------------------------------------ 虚拟日历

	/**
	 * 每次服务端同步前刷新纪元读数。
	 *
	 * <p>{@code ServerPlayer} 每刻都会调 {@link #broadcastChanges()}，所以这里写入的值
	 * 会自动随原版数据包下发；客户端侧 {@code level()} 不是 {@link ServerLevel}，直接跳过。
	 */
	@Override
	public void broadcastChanges() {
		this.syncCalendar();
		super.broadcastChanges();
	}

	private void syncCalendar() {
		if (!(this.owner.level() instanceof ServerLevel level)) {
			return;
		}
		BlockPos pos = this.owner.blockPosition();
		int cellX = Math.floorDiv(pos.getX(), OreBody.CELL_X);
		int cellZ = Math.floorDiv(pos.getZ(), OreBody.CELL_Z);
		long now = level.getGameTime();
		this.data.set(CELL_EPOCH, (int) Math.clamp(EpochClock.epochOfCell(now, cellX, cellZ), 0L, 32000L));
		long until = EpochClock.ticksUntilNextCell(now, cellX, cellZ);
		this.data.set(DAYS_LEFT_X10,
				(int) Math.round(until / (double) EpochClock.TICKS_PER_DAY * 10.0));
	}

	/** 本处矿体单元的纪元号（虚拟日历读数）。 */
	public long cellEpoch() {
		return this.data.get(CELL_EPOCH);
	}

	/** 距离本处换脉还有多少游戏日。 */
	public double daysUntilReroll() {
		return this.data.get(DAYS_LEFT_X10) / 10.0;
	}

	// ------------------------------------------------------------------ 读取

	/** 当前选中的矿物。 */
	public OreMineral selected() {
		OreMineral[] all = OreMineral.values();
		return all[Math.clamp(this.data.get(SELECTED), 0, all.length - 1)];
	}

	/** 当前选中的序号。 */
	public int selectedOrdinal() {
		OreMineral[] all = OreMineral.values();
		return Math.clamp(this.data.get(SELECTED), 0, all.length - 1);
	}

	// ------------------------------------------------------------------ 点选

	@Override
	public boolean clickMenuButton(Player player, int ordinal) {
		OreMineral[] all = OreMineral.values();
		if (ordinal < 0 || ordinal >= all.length) {
			return false;
		}
		OreMineral mineral = all[ordinal];
		this.data.set(SELECTED, ordinal);

		// 写回玩家手里的探测仪：主手优先，其次副手
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack stack = player.getItemInHand(hand);
			if (stack.getItem() instanceof MineralProspectorItem) {
				MineralProspectorItem.setTarget(stack, mineral);
				break;
			}
		}
		this.broadcastChanges();
		return true;
	}

	// ------------------------------------------------------------------ 标准菜单方法

	/** 没有机器槽，什么都不用搬。 */
	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		return ItemStack.EMPTY;
	}

	/** 这是一张跟着人走的选择表，只要界面还开着就有效。 */
	@Override
	public boolean stillValid(Player player) {
		return true;
	}
}
