package com.geometallurgy.blockentity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.geometallurgy.Geometallurgy;
import com.geometallurgy.block.BatteryBlock;
import com.geometallurgy.block.WirelessSensorBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import team.reborn.energy.api.EnergyStorage;
import team.reborn.energy.api.base.SimpleEnergyStorage;

/**
 * 蓄电池：无线电网的中心。
 *
 * <h2>没有方向：一份储能，两个方向同时工作</h2>
 * 蓄电池<b>不分输入档 / 输出档</b>。每刻它同时做两件事，各自 50% 损耗：
 * <ul>
 *   <li><b>送出</b>——把电送进 19³ 范围内装了感应器的<b>用电器</b>，逐台限功率；
 *       送出 1 FE 实际到账，电池要付 2 FE（输出损耗 50%）。</li>
 *   <li><b>取电</b>——从范围内装了感应器的<b>电源</b>（发电机，或者另一块电池）拉电：
 *       电源付出 2 FE，电池到账 1 FE（输入损耗 50%）。</li>
 * </ul>
 * 方向由<b>邻居</b>的能力决定，不由本机状态决定：能收的收、能给的给，两者互不排斥
 * （见 {@link EnergyLinkPolicy}）。所以界面上不存在可切的「输入 / 输出」档，
 * 切换更不会让一条本来能通的连接变成不通（bug#43）。
 * 感应器（{@link WirelessSensorBlock}）只是入网许可：机器头顶立着它，才被本机的扫描看见。
 *
 * <h2>逐设备限功率</h2>
 * 每台设备一个功率档（{@link #RATE_STEPS}，用电器可从 0 调到 1024 FE/t），
 * 存进一张以「宿主机坐标」为键的表 —— 感应器拆了重装，配置还在。
 *
 * <h2>扫描节奏</h2>
 * 每 20 刻扫一遍 19³（6859 格）找感应器，列表缓存下来逐刻使用 —— 每刻都扫纯属浪费。
 * 感应器是静态方块，20 刻的延迟顶多是「刚装上要等一秒」。
 */
public class BatteryBlockEntity extends BlockEntity
		implements net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider<net.minecraft.util.Unit> {

	public static final long CAPACITY = 1_000_000L;
	/** 与常规电源 / 用电器对齐的直连上限（无线逐设备的限幅另算）。 */
	public static final long MAX_IO = 2048L;
	/** 扫描半径：±9 格，正好 19×19×19。 */
	public static final int RANGE = 9;
	/** 每种工作模式最多列 16 台设备；输入页和输出页分别扫描各自的设备集合。 */
	public static final int MAX_DEVICES = 16;

	/** 功率档（FE/t）。0 = 停供。 */
	public static final long[] RATE_STEPS = {0L, 16L, 32L, 64L, 128L, 256L, 512L, 1024L};
	/** 新设备默认落在哪一档。 */
	public static final int DEFAULT_RATE_INDEX = 3;

	/** 两侧各 50% 的损耗：送 1 到账 1 要付 2。刻意做成常量，升级件改的就是它。 */
	public static final int LOSS_NUMERATOR = 1;
	public static final int LOSS_DENOMINATOR = 2;

	/** 同步给界面的布局：4 个读数 + 每台设备 4 个 short（相对坐标 × 3 + 功率档）。 */
	public static final int DATA_COUNT = 4 + MAX_DEVICES * 4;

	/**
	 * 旧的「输入 / 输出」二选一档位。
	 *
	 * <p><b>行为上已停用</b>（bug#43）：蓄电池没有方向，判定只看邻居的能力
	 * （{@link EnergyLinkPolicy}）。枚举与 {@code mode} 字段只为兼容旧存档的
	 * {@code mode} 键而保留，读进来的值不参与任何传输判定，界面也不再有档位可切。
	 */
	public enum Mode {
		INPUT,
		OUTPUT;

		public Mode flipped() {
			return this == INPUT ? OUTPUT : INPUT;
		}

		public String translationKey() {
			return "gui.geometallurgy.battery.mode." + this.name().toLowerCase();
		}
	}

	/** 旧存档兼容字段：见 {@link Mode}；不再被 {@code serverTick} / {@code visibleDevices} 读取。 */
	private Mode mode = Mode.OUTPUT;
	public final SimpleEnergyStorage energy = new SimpleEnergyStorage(CAPACITY, MAX_IO, MAX_IO) {
		@Override
		protected void onFinalCommit() {
			setChanged();
		}
	};

	/** 宿主机坐标 → 功率档下标。感应器拆了配置不删，重装即恢复。 */
	private final Map<BlockPos, Integer> rates = new HashMap<>();
	/** 最近一次扫描发现的全部宿主机坐标（已排序，供当前模式筛选）。 */
	private final List<BlockPos> devices = new ArrayList<>();
	private int scanCooldown;

	public BatteryBlockEntity(BlockPos pos, BlockState state) {
		super(ModBlockEntities.BATTERY, pos, state);
	}

	public static void serverTick(ServerLevel level, BlockPos pos, BlockState state,
			BatteryBlockEntity battery) {
		if (--battery.scanCooldown <= 0) {
			battery.scanCooldown = 20;
			battery.scan(level, pos);
		}
		// 双向同时：先送后取。没有档位门控 —— 邻居能接多少就送多少，能抽多少就抽多少。
		battery.pushOut(level);
		battery.pullIn(level);
	}

	// ------------------------------------------------------------------ 扫描

	private void scan(ServerLevel level, BlockPos center) {
		this.devices.clear();
		BlockPos min = center.offset(-RANGE, -RANGE, -RANGE);
		BlockPos max = center.offset(RANGE, RANGE, RANGE);
		for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
			if (!(level.getBlockState(pos).getBlock() instanceof WirelessSensorBlock)) {
				continue;
			}
			BlockPos host = pos.immutable().below();
			if (host.equals(center)) {
				continue;
			}
			if (EnergyStorage.SIDED.find(level, host, Direction.UP) != null) {
				this.devices.add(host);
			}
		}
		// 排序让界面上的列表顺序稳定，不随方块迭代顺序漂
		this.devices.sort(BlockPos::compareTo);
		// 已知的坐标不在列表里就把它清掉，免得旧配置无限累积
		this.rates.keySet().removeIf(key -> !this.devices.contains(key));
		setChanged();
	}

	// ------------------------------------------------------------------ 传输

	private void pushOut(ServerLevel level) {
		for (BlockPos host : this.visibleDevices(level)) {
			long rate = rateOf(host);
			if (rate <= 0 || this.energy.amount < LOSS_DENOMINATOR) {
				continue;
			}
			EnergyStorage sink = EnergyStorage.SIDED.find(level, host, Direction.UP);
			if (sink == null || !EnergyLinkPolicy.canFeed(sink.supportsInsertion())) {
				continue;
			}
			long budget = Math.min(rate, this.energy.amount / LOSS_DENOMINATOR);
			if (budget <= 0) {
				continue;
			}
			// 先试插再入账：实际到账多少，电池就付双倍多少 —— 损耗永远成对出现
			long delivered;
			try (Transaction transaction = Transaction.openOuter()) {
				delivered = sink.insert(budget, transaction);
				transaction.abort();
			}
			if (delivered <= 0) {
				continue;
			}
			try (Transaction transaction = Transaction.openOuter()) {
				long got = sink.insert(delivered, transaction);
				// 实际到账可能比试探少一点（对方在这两刻之间变了），损耗按实际到账算
				this.energy.amount -= got * LOSS_DENOMINATOR;
				transaction.commit();
			}
		}
	}

	private void pullIn(ServerLevel level) {
		long room = CAPACITY - this.energy.amount;
		if (room <= 0) {
			return;
		}
		for (BlockPos host : this.visibleDevices(level)) {
			long rate = rateOf(host);
			if (rate <= 0 || room <= 0) {
				continue;
			}
			EnergyStorage source = EnergyStorage.SIDED.find(level, host, Direction.UP);
			if (source == null || !EnergyLinkPolicy.canDraw(source.supportsExtraction())) {
				continue;
			}
			long want = Math.min(rate, room * LOSS_DENOMINATOR);
			long pulled;
			try (Transaction transaction = Transaction.openOuter()) {
				pulled = source.extract(want, transaction);
				transaction.abort();
			}
			if (pulled <= 0) {
				continue;
			}
			try (Transaction transaction = Transaction.openOuter()) {
				long got = source.extract(pulled, transaction);
				this.energy.amount += got / LOSS_DENOMINATOR;
				room -= got / LOSS_DENOMINATOR;
				transaction.commit();
			}
		}
	}

	// ------------------------------------------------------------------ 配置

	/** 某台设备的当前档位。 */
	public int rateIndexOf(BlockPos host) {
		return this.rates.getOrDefault(host, DEFAULT_RATE_INDEX);
	}

	/** 某台设备的实际限幅（FE/t）。 */
	public long rateOf(BlockPos host) {
		int index = rateIndexOf(host);
		if (index < 0) {
			index = 0;
		}
		if (index >= RATE_STEPS.length) {
			index = RATE_STEPS.length - 1;
		}
		return RATE_STEPS[index];
	}

	/** 循环调档：升到顶回零，降到零回顶。 */
	public void cycleRate(BlockPos host, int direction) {
		int index = rateIndexOf(host);
		int count = RATE_STEPS.length;
		index = ((index + direction) % count + count) % count;
		this.rates.put(host, index);
		setChanged();
	}

	public List<BlockPos> devices() {
		return this.devices;
	}

	/**
	 * 入网设备：能收或能给，任一即可 —— <b>不再按档位过滤</b>。
	 *
	 * <p>双向机（另一块电池）两个方向都会出现；纯电源只进取电的一半、纯用电器只进送电的一半，
	 * 但都留在同一张列表里，因为限功率对两个方向都成立（bug#43）。
	 */
	public List<BlockPos> visibleDevices(ServerLevel level) {
		List<BlockPos> visible = new ArrayList<>();
		for (BlockPos host : this.devices) {
			EnergyStorage storage = EnergyStorage.SIDED.find(level, host, Direction.UP);
			if (storage == null) {
				continue;
			}
			if (EnergyLinkPolicy.visible(storage.supportsInsertion(), storage.supportsExtraction())) {
				visible.add(host);
				if (visible.size() >= MAX_DEVICES) {
					break;
				}
			}
		}
		return visible;
	}

	// ------------------------------------------------------------------ 能量接口

	public EnergyStorage getEnergyStorage() {
		return this.energy;
	}

	// ------------------------------------------------------------------ 界面入口

	@Override
	public net.minecraft.network.chat.Component getDisplayName() {
		return net.minecraft.network.chat.Component.translatable("block.geometallurgy.battery");
	}

	@Override
	public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int syncId,
			net.minecraft.world.entity.player.Inventory inventory, net.minecraft.world.entity.player.Player player) {
		return new com.geometallurgy.menu.BatteryMenu(syncId, inventory, this);
	}

	/** 扩展菜单的开口载荷：本界面不需要额外数据。 */
	@Override
	public net.minecraft.util.Unit getScreenOpeningData(net.minecraft.server.level.ServerPlayer player) {
		return net.minecraft.util.Unit.INSTANCE;
	}

	// ------------------------------------------------------------------ 同步

	/** 界面的只读数据通道。 */
	private final ContainerData dataAccess = new ContainerData() {
		@Override
		public int get(int index) {
			return BatteryBlockEntity.this.readData(index);
		}

		@Override
		public void set(int index, int value) {
			// 只读
		}

		@Override
		public int getCount() {
			return DATA_COUNT;
		}
	};

	public ContainerData getContainerData() {
		return this.dataAccess;
	}

	/**
	 * 读数给界面：0 模式 / 1 电能÷1000 / 2 设备数 / 3 扫描半径，
	 * 之后每台设备 4 位 —— 相对坐标（±9 稳稳落在 16 位里）+ 功率档。
	 */
	public int readData(int index) {
		if (index == 0) {
			return this.mode.ordinal();
		}
		if (index == 1) {
			return (int) (this.energy.amount / 1000L);
		}
		if (index == 2) {
			if (this.level instanceof ServerLevel serverLevel) {
				return Math.min(this.visibleDevices(serverLevel).size(), MAX_DEVICES);
			}
			return 0;
		}
		if (index == 3) {
			return RANGE;
		}
		int entry = (index - 4) / 4;
		int part = (index - 4) % 4;
		if (entry >= this.devices.size()) {
			return 0;
		}
		BlockPos host;
		if (this.level instanceof ServerLevel serverLevel) {
			List<BlockPos> visible = this.visibleDevices(serverLevel);
			if (entry >= visible.size()) {
				return 0;
			}
			host = visible.get(entry);
		} else {
			if (entry >= this.devices.size()) {
				return 0;
			}
			host = this.devices.get(entry);
		}
		BlockPos relative = host.subtract(this.worldPosition);
		return switch (part) {
			case 0 -> relative.getX();
			case 1 -> relative.getY();
			case 2 -> relative.getZ();
			default -> rateIndexOf(host);
		};
	}

	// ------------------------------------------------------------------ 存档

	@Override
	protected void saveAdditional(ValueOutput out) {
		super.saveAdditional(out);
		out.putLong("energy", this.energy.amount);
		out.putInt("mode", this.mode.ordinal());
		// 限功率表用一行字符串扛：坐标都很小，没必要为它搞嵌套结构
		StringBuilder text = new StringBuilder();
		for (Map.Entry<BlockPos, Integer> entry : this.rates.entrySet()) {
			BlockPos pos = entry.getKey();
			text.append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ())
					.append(':').append(entry.getValue()).append(';');
		}
		out.putString("rates", text.toString());
	}

	@Override
	protected void loadAdditional(ValueInput in) {
		super.loadAdditional(in);
		this.energy.amount = in.getLongOr("energy", 0L);
		int ordinal = in.getIntOr("mode", 1);
		ordinal = ordinal < 0 ? 1 : Math.min(ordinal, Mode.values().length - 1);
		this.mode = Mode.values()[ordinal];
		this.rates.clear();
		for (String chunk : in.getStringOr("rates", "").split(";")) {
			if (chunk.isEmpty()) {
				continue;
			}
			String[] parts = chunk.split(":");
			String[] xyz = parts[0].split(",");
			this.rates.put(new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]),
					Integer.parseInt(xyz[2])), Integer.parseInt(parts[1]));
		}
		this.scanCooldown = 0;
	}
}
