package com.geometallurgy.menu;

import com.geometallurgy.blockentity.BatteryBlockEntity;

import net.minecraft.util.Unit;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/**
 * 蓄电池的界面。没有物品槽 —— 它不吞吐任何东西，唯一的交互是
 * 调每台设备的限功率（列表里的 −/+ 两粒按钮）。蓄电池没有方向档可切（bug#43）。
 *
 * <p>同步布局见 {@link BatteryBlockEntity#readData}：
 * 0 旧模式（仅供兼容，界面不再读）/ 1 电能÷1000 / 2 设备数 / 3 扫描半径，之后每台设备 4 位。
 *
 * <p>按钮走原版的 {@code handleInventoryButtonClick} 通道：按钮 id 编码为
 * {@code 设备序号 × 2 + (0 = 减档 / 1 = 加档)}；{@code 100+} 的旧模式按钮一律拒绝。
 */
public class BatteryMenu extends AbstractContainerMenu {

	// ------------------------------------------------------------------ 界面布局
	//
	// 这几个常量是「菜单槽位」与「界面绘制」之间唯一的契约，两边都从这里取，
	// 不再各自算一遍 —— 之前两边各写一份，改一处漏一处就会让背包画到面板外。
	//
	// 高度为什么必须压到 222：GUI 缩放是游戏按窗口自动选的，规则保证**逻辑可视高度
	// 至少 240**、再大一档就不满足。原版最高的六行箱子界面正好 222。任何超过 240 的
	// 面板都会被窗口上下同时裁掉，而背包恰好在面板最底部 —— 先被裁的就是它。

	/** 设备列表一屏能显示几行。16 台设备靠滚轮翻看，不能靠拉高面板。 */
	public static final int VISIBLE_DEVICES = 6;

	/** 设备列表第一行的纵向位置。 */
	public static final int LIST_Y = 58;

	/** 设备列表行高。中文全角字形占满 9 像素，行高低于 11 会紧贴。 */
	public static final int ROW_H = 11;

	/** 玩家背包第一行的纵向位置。 */
	public static final int INVENTORY_Y = 140;

	/** 界面总高：背包最后一行（快捷栏）再往下留 6 像素。与原版六行箱子一致。 */
	public static final int GUI_HEIGHT = INVENTORY_Y + 82;

	/** 模式切换按钮的 id 基址（设备序号最多 12×2−1=23，100 起绝不会撞）。 */
	public static final int MODE_BUTTON_BASE = 100;

	public static final int DATA_COUNT = BatteryBlockEntity.DATA_COUNT;

	private final BatteryBlockEntity blockEntity;
	private final ContainerData data;

	/** 服务端构造。 */
	public BatteryMenu(int syncId, Inventory playerInventory, BatteryBlockEntity blockEntity) {
		super(ModMenus.BATTERY, syncId);
		this.blockEntity = blockEntity;
		this.data = blockEntity.getContainerData();
		this.addSlots(playerInventory);
	}

	/** 客户端构造。 */
	public BatteryMenu(int syncId, Inventory playerInventory, Unit unit) {
		super(ModMenus.BATTERY, syncId);
		this.blockEntity = null;
		this.data = new SimpleContainerData(DATA_COUNT);
		this.addSlots(playerInventory);
	}

	/** 只有玩家背包：机器侧一个槽都没有。 */
	private void addSlots(Inventory playerInventory) {
		this.addStandardInventorySlots(playerInventory, 8, INVENTORY_Y);
		this.addDataSlots(this.data);
	}

	public BatteryBlockEntity blockEntity() {
		return this.blockEntity;
	}

	/** 电能（FE）。服务端按 ÷1000 传（16 位上限），这里乘回来。 */
	public long energy() {
		return this.data.get(1) * 1000L;
	}

	public long capacity() {
		return BatteryBlockEntity.CAPACITY;
	}

	public int deviceCount() {
		return this.data.get(2);
	}

	public int range() {
		return this.data.get(3);
	}

	/** 第 i 台设备的相对坐标（相对本机）。 */
	public int deviceOffsetX(int i) {
		return this.data.get(4 + i * 4);
	}

	public int deviceOffsetY(int i) {
		return this.data.get(4 + i * 4 + 1);
	}

	public int deviceOffsetZ(int i) {
		return this.data.get(4 + i * 4 + 2);
	}

	/** 第 i 台设备的功率档下标。 */
	public int deviceRateIndex(int i) {
		return this.data.get(4 + i * 4 + 3);
	}

	@Override
	public boolean clickMenuButton(Player player, int id) {
		if (this.blockEntity == null) {
			return false;
		}
		if (id >= MODE_BUTTON_BASE) {
			// 蓄电池没有方向档：旧的模式按钮一律拒绝（bug#43）。
			return false;
		}
		int entry = id / 2;
		int direction = id % 2 == 0 ? -1 : 1;
		if (!(this.blockEntity.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) {
			return false;
		}
		java.util.List<net.minecraft.core.BlockPos> visible = this.blockEntity.visibleDevices(level);
		if (entry >= visible.size()) {
			return false;
		}
		this.blockEntity.cycleRate(visible.get(entry), direction);
		return true;
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		// 没有机器槽位，shift 点背包没有意义
		return ItemStack.EMPTY;
	}

	@Override
	public boolean stillValid(Player player) {
		return this.blockEntity != null
				&& net.minecraft.world.Container.stillValidBlockEntity(this.blockEntity, player);
	}
}
