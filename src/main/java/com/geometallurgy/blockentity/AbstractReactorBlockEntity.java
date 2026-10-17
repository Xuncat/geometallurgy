package com.geometallurgy.blockentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.geometallurgy.chemical.Chemical;
import com.geometallurgy.chemical.MolStock;
import com.geometallurgy.chemical.MolTank;
import com.geometallurgy.chemical.OutputRouting;
import com.geometallurgy.chemical.Reactor;
import com.geometallurgy.item.ModItems;
import com.geometallurgy.item.MolCarrier;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * 反应釜与搅拌器的共同底座（「版本 B」）。
 *
 * <h2>版本 B 是什么</h2>
 * 机器内部只有<b>一本混合 mol 账</b>（{@link #tank}），没有任何桶槽位。桶放在相邻的容器里，
 * 通过六个面的角色配置与机器交换：
 * <ul>
 *   <li><b>输入面</b>：从相邻容器里取一只装了 mol 的桶，把账倒进机器，空桶放回去；</li>
 *   <li><b>输出面</b>：从相邻容器里取一只空桶，把混合槽里<b>指定相态</b>的产物灌进去，放回去。</li>
 * </ul>
 * 交换是「取一只、还一只」的原地替换，桶数不变；一次只处理一格，绝不吞桶也不凭空造桶。
 *
 * <h2>每 tick 的顺序</h2>
 * <pre>
 *   1. 输入面收桶              （把料倒进 tank 或子类的预混缓冲）
 *   2. mix()                    （搅拌器按 A:B 比率兑进 tank；反应釜不动）
 *   3. 跑一轮全部反应            （产物留在 tank，下一 tick 继续参与匹配）
 *   4. 输出面送桶              （按该面的角色挑相态灌桶 / 过户给储罐）
 * </pre>
 *
 * <h2>面角色的存法</h2>
 * 六个面各占 2 个 bit，序号语义由子类定义（反应釜用 {@link PortRole}、搅拌器用
 * {@link MixerRole}），整包成一个 int 随 {@link ContainerData} 同步，也随存档落盘。
 */
public abstract class AbstractReactorBlockEntity extends BlockEntity
		implements ExtendedMenuProvider<Unit>, WorldlyContainer, FaceConfigurable {

	/** 混合 mol 槽容量（mol）。90 mol = 6 桶，够攒几批产物。 */
	public static final double TANK_CAPACITY = 90.0;

	/** 每 tick 每条反应最多跑几份。 */
	public static final double RATE_PER_TICK = 1.0;

	/** 少于此值就当没有（mol）。 */
	public static final double MIN_MOL = 1.0e-3;

	/** 催化剂槽（唯一还留在界面里的物品槽）。 */
	public static final int SLOT_CATALYST = 0;
	public static final int ITEM_SLOTS = 1;

	/** 催化剂 id → 物品。反应表里 catalyst 写的是 id。 */
	public static final Map<String, Item> CATALYST_ITEMS = Map.of(
			"light", ModItems.CARBON_ROD,
			"vanadium_pentoxide", ModItems.MOLYBDENUM_ROD);

	// ---------------------------------------------------------------- 界面同步字段
	/** 同步字段数（{@link ContainerData} 是 16 位 short，数值都要压到 32767 以内）。 */
	/** 混合槽成分里同步给界面的最多项数（饼图用）。 */
	public static final int TRACKED_ENTRIES = 6;

	/** 同步字段数：13 个定长 + 混合槽 6 项 + 进料 1 个计数 + 进料 6 项 + 堵料 2 项 = 40。 */
	public static final int DATA_COUNT = 13 + TRACKED_ENTRIES * 2 + 1 + TRACKED_ENTRIES * 2 + 2;
	public static final int D_TANK = 0;          // 混合槽总 mol ×100
	public static final int D_MASS = 1;          // 总质量 g（已钳制）
	public static final int D_STATUS = 2;        // ReactorStatus.ordinal()
	public static final int D_PHASE = 3;         // 已废弃：相态改为跟着面走，本格恒 0
	public static final int D_ROLES = 4;         // 六面打包
	public static final int D_RATIO = 5;         // 搅拌比率（0..100，A 的百分比）
	public static final int D_BUF_A = 6;         // 预混 A 总 mol ×100
	public static final int D_BUF_B = 7;         // 预混 B 总 mol ×100
	public static final int D_DOMINANT = 8;      // 最多的那种物质，Chemical.indexOf + 1（0=无）
	public static final int D_DOMINANT_MOL = 9;  // 那种物质有多少 mol ×100
	public static final int D_STIRRING = 10;     // 搅拌开关（1 = 开）；反应釜恒 0
	public static final int D_ROLES_B = 11;      // 六面角色的高半（面 3..5，各 3 bit）
	public static final int D_COMP_COUNT = 12;   // 混合槽成分同步了几项（0..TRACKED_ENTRIES）
	public static final int D_COMP_BASE = 13;    // 之后每项两格：物质序号 + 1、占比千分位
	public static final int D_FEED_COUNT = D_COMP_BASE + TRACKED_ENTRIES * 2; // 进料成分同步了几项
	public static final int D_FEED_BASE = D_FEED_COUNT + 1; // 进料成分：每项两格，同 D_COMP_BASE
	/**
	 * 出料堵住时「哪一相堵住」：0 = 无，其余是 {@code Chemical.Phase.ordinal() + 1}。
	 *
	 * <p>只同步序号与百 mol 量，不传字符串 —— {@link ContainerData} 是 16 位。
	 */
	public static final int D_BLOCK_PHASE = D_FEED_BASE + TRACKED_ENTRIES * 2;
	/** 堵住的那一相有多少 mol ×100（钳到 16 位）。 */
	public static final int D_BLOCK_MOL = D_BLOCK_PHASE + 1;

	// ---------------------------------------------------------------- 状态
	/** 机器内部的混合 mol 账 —— 唯一的物料真相。 */
	protected final MolStock tank = MolStock.empty();

	protected ReactorStatus status = ReactorStatus.IDLE;

	/** 上一 tick 推进过的反应条数（分数份也算 1 条；界面读）。 */
	protected int lastRuns;

	/** 这一 tick 在<b>输入面</b>碰到「材质装不住内容」的桶 —— 状态栏据此提示换桶。 */
	protected boolean sawUnsafeBucket;

	/**
	 * 上一 tick 在<b>输出面</b>因为空桶装不住产物而没出成料。
	 *
	 * <p>输出排在反应之后，所以这个标记只能由下一 tick 的 {@code react()} 读 ——
	 * 差一 tick 不影响观感，但能让「产物堵在口径不对的桶上」这件事显示出来。
	 */
	protected boolean pushBlockedByBucket;

	/**
	 * 上一次 {@link #react()} 判成 {@link ReactorStatus#OUTPUT_FULL} 时，
	 * 槽里「推不出去」的那一相与它的 mol 数（见 {@link OutputRouting#blocked}）。
	 *
	 * <p>计算放在服务端，同步走 {@link #D_BLOCK_PHASE} / {@link #D_BLOCK_MOL} 两个短整型槽；
	 * 界面据此把状态写清楚（「出料堵住：液体 82.4 mol 推不出去」），不再只有一句笼统的「出料堵住」。
	 */
	protected Chemical.Phase blockedPhase;
	protected double blockedMol;

	/** 六个面的角色序号，序号含义由子类定义。 */
	protected final int[] roles = new int[6];

	protected final SimpleContainer items = new SimpleContainer(ITEM_SLOTS) {
		@Override
		public boolean canPlaceItem(int slot, ItemStack stack) {
			return slot == SLOT_CATALYST && isCatalyst(stack);
		}

		@Override
		public void setChanged() {
			super.setChanged();
			AbstractReactorBlockEntity.this.setChanged();
		}
	};

	protected final ContainerData dataAccess = new ContainerData() {
		@Override
		public int get(int index) {
			return readData(index);
		}

		@Override
		public void set(int index, int value) {
			// 只读同步字段
		}

		@Override
		public int getCount() {
			return DATA_COUNT;
		}
	};

	protected AbstractReactorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
		clearFaces();
	}

	// ================================================================ 子类接口

	/** 本机面角色的取值个数（4）。 */
	protected abstract int roleCount();

	/** 这个序号是不是「收桶倒料」的输入面。 */
	protected abstract boolean isInputRole(int ordinal);

	/** 这个序号是不是「灌桶出货」的输出面。 */
	protected abstract boolean isOutputRole(int ordinal);

	/** 输入面收到的料进哪本账（反应釜直接进 tank，搅拌器分 A/B）。 */
	protected abstract MolStock inputBuffer(int ordinal);

	/** 那本输入账的容量。 */
	protected abstract double inputBufferCapacity(int ordinal);

	/** 面角色循环到下一个。 */
	protected abstract int nextRole(int ordinal);

	/** 面角色的本地化键后缀（界面按钮的文字）。 */
	public abstract String roleKey(int ordinal);

	/** 现在是否在搅拌。反应釜恒 false，搅拌器恒 true。 */
	protected abstract boolean stirred();

	/** 方块状态里的 LIT 属性。 */
	protected abstract BooleanProperty litProperty();

	/** 某个出料面出的是哪一相；非出料面返回 {@code null}。 */
	protected abstract Chemical.Phase outputPhaseOf(int ordinal);

	/** 反应前把预混缓冲按比率兑进混合槽（搅拌器用；反应釜空实现）。 */
	protected void mix() {
	}

	/** 搅拌比率：A 的百分比（0..100）。反应釜固定 50（用不到）。 */
	public int ratio() {
		return 50;
	}

	/** 设置搅拌比率（搅拌器覆盖）。 */
	public void setRatio(int percent) {
		// 反应釜无比率
	}

	/**
	 * 面按钮的处理入口。<b>点一下就换这一面的角色（含出哪一相）</b>，
	 * 不再有单独的「输出相」按钮 —— 相态跟着面走。子类可扩展其它 id。
	 *
	 * @return 有没有处理
	 */
	public boolean handleButton(int id) {
		if (id >= 0 && id < 6) {
			// 注意：按钮传的是「界面格子序号」，不是方向序号，两边都用 FACE_ORDER 的顺序
			Direction side = FACE_ORDER[id];
			this.roles[side.ordinal()] = nextRole(this.roles[side.ordinal()]);
			this.setChanged();
			return true;
		}
		return false;
	}

	/** 六个面在界面上的固定排列顺序：上、下、北、南、西、东（菜单与界面共用）。 */
	public static final Direction[] FACE_ORDER = {
			Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
	};

	// ================================================================ 每 tick

	protected void tick(ServerLevel level, BlockPos pos, BlockState state) {
		this.sawUnsafeBucket = false;
		this.pullFaces(level, pos);
		this.mix();
		this.lastRuns = this.react();
		this.pushBlockedByBucket = false;
		this.pushFaces(level, pos);
		this.updateLit(level, pos, state);
	}

	/** 从所有输入面收料：邻面是储罐就直接过户 mol，否则从容器里的物品桶倒。 */
	protected void pullFaces(ServerLevel level, BlockPos pos) {
		// 满槽时彻底停止吸料：输入缓冲也是机器的一部分，槽没地方接，缓冲就不该再进
		// （否则邻罐 / 桶会一直往缓冲里灌，机器一边报「出料堵住」一边还在吸 —— bug#23）。
		// 缓冲 → 槽那一步由 mix() 自己看槽余量，这里先掐断上游。
		if (!OutputRouting.hasRoom(this.tank.totalMol(), TANK_CAPACITY, MIN_MOL)) {
			return;
		}
		for (Direction side : Direction.values()) {
			int role = this.roles[side.ordinal()];
			if (!isInputRole(role)) {
				continue;
			}
			MolStock dest = inputBuffer(role);
			double cap = inputBufferCapacity(role);
			if (!pullTank(level, pos, side, dest, cap)) {
				pullBucket(level, pos, side, dest, cap);
			}
		}
	}

	/** 往所有输出面送料（一次一处）：邻面是储罐就直接过户，否则灌空桶。 */
	protected void pushFaces(ServerLevel level, BlockPos pos) {
		for (Direction side : Direction.values()) {
			int role = this.roles[side.ordinal()];
			if (!isOutputRole(role)) {
				continue;
			}
			Chemical.Phase phase = outputPhaseOf(role);
			if (phase == null) {
				continue;
			}
			// 面配的相在槽里快空了（< MIN_MOL）就回退到「槽里实际最多」的那一相：
			// 一个面标成「出气」不会因为槽里主要是液体就永远不推，整机也不会因此冻住（§13.1）。
			phase = OutputRouting.effectivePhase(this.tank, phase, MIN_MOL);
			if (phase == null) {
				continue;
			}
			if (pushTank(level, pos, side, phase) || pushBucket(level, pos, side, phase)) {
				return; // 一 tick 出一次，避免瞬间抽空
			}
		}
	}

	/**
	 * 从邻面的储罐抽料进这本账。
	 *
	 * @return 有没有抽到（抽到了就不再走「倒物品桶」那条路）
	 */
	protected boolean pullTank(ServerLevel level, BlockPos pos, Direction side, MolStock dest, double cap) {
		MolTank tank = MolTank.neighbor(level, pos, side);
		if (tank == null) {
			return false;
		}
		double room = tank.room();
		if (cap > 0.0) {
			room = Math.min(room, Math.max(0.0, cap - dest.totalMol()));
		}
		if (room <= MIN_MOL) {
			return true; // 邻面确实是储罐，只是这台机器装不下了
		}
		MolStock drawn = tank.drain(room, null);
		if (drawn.isEmpty()) {
			return false;
		}
		for (Map.Entry<String, Double> e : drawn.view().entrySet()) {
			dest.add(e.getKey(), e.getValue());
		}
		this.setChanged();
		return true;
	}

	/**
	 * 把混合槽里选定相态的产物过户给邻面的储罐。
	 *
	 * <p>储罐的材质装不住这份产物就一点不动、报「桶不耐蚀」——
	 * 宁可不出料，也不要凭空把料倒进会被侵蚀的罐里。
	 */
	protected boolean pushTank(ServerLevel level, BlockPos pos, Direction side, Chemical.Phase phase) {
		MolTank tank = MolTank.neighbor(level, pos, side);
		if (tank == null) {
			return false;
		}
		MolStock available = phaseStock(this.tank, phase);
		if (available.isEmpty()) {
			return false;
		}
		double room = Math.min(tank.room(), available.totalMol());
		if (room <= MIN_MOL) {
			return true;
		}
		MolStock load = takeUpTo(available, room);
		if (load.isEmpty()) {
			return true;
		}
		if (!tank.holds(load)) {
			this.pushBlockedByBucket = true;
			return true;
		}
		// tank.pour 会就地从 load 扣料；事后再拿 load 当「倒之前那份」用，除数会变成 0
		// （或只剩残量），这本账就扣不干净 —— 邻罐凭空多出一份料。倒之前先留一份底账。
		MolStock sent = load.copy();
		MolStock accepted = tank.pour(load, room);
		if (accepted.isEmpty()) {
			return true;
		}
		double ratio = accepted.totalMol() / sent.totalMol();
		for (Map.Entry<String, Double> e : sent.view().entrySet()) {
			this.tank.take(e.getKey(), e.getValue() * ratio);
		}
		this.setChanged();
		return true;
	}

	/**
	 * 收一只桶：把相邻容器里第一只有内容的 mol 载具倒进 {@code dest}，空桶原地放回。
	 *
	 * @param cap dest 还能装多少（mol）；{@code <=0} 表示不设限
	 * @return 有没有收到
	 */
	protected boolean pullBucket(ServerLevel level, BlockPos pos, Direction side, MolStock dest, double cap) {
		Container c = neighbor(level, pos, side);
		if (c == null) {
			return false;
		}
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack stack = c.getItem(i);
			if (stack.isEmpty() || !(stack.getItem() instanceof MolCarrier carrier)) {
				continue;
			}
			// 一摞桶共享一个内容组件：整摞一起倒会「倒掉 16 只、只记一份账」（bug#26，
			// 与 bug#12 同一个病根）。消耗品（粉）的账按件现推、只减一件，不在此列。
			if (!carrier.consumedOnEmpty() && stack.getCount() > 1) {
				continue;
			}
			MolStock content = carrier.stock(stack);
			if (content.isEmpty()) {
				continue;
			}
			if (!carrier.holds(content)) {
				// 桶的材质装不住这桶料：不动它，让玩家自己换桶
				this.sawUnsafeBucket = true;
				continue;
			}
			if (cap > 0.0 && dest.totalMol() + content.totalMol() > cap + 1.0e-6) {
				continue; // 装不下，留着下次
			}
			// 再看这件载具是「消耗品」还是「可重复用的容器」，两种收法不同：
			//  - 粉：账由成分现推、take 改不动它，只能原地把计数减一（一件一次，见 consumedOnEmpty）；
			//  - 桶：take 把账清空，同一只空桶放回，桶数不变。
			// 两条路都只动一格，不会再出现「一摞一次倒完而只记一份账」（bug#12）。
			if (carrier.consumedOnEmpty()) {
				ItemStack reduced = stack.copy();
				reduced.shrink(1);
				c.setItem(i, reduced.isEmpty() ? ItemStack.EMPTY : reduced);
			} else {
				ItemStack emptied = stack.copy();
				for (Map.Entry<String, Double> e : new ArrayList<>(content.view().entrySet())) {
					carrier.take(emptied, e.getKey(), e.getValue());
				}
				c.setItem(i, emptied);
			}
			for (Map.Entry<String, Double> e : content.view().entrySet()) {
				dest.add(e.getKey(), e.getValue());
			}
			c.setChanged();
			this.setChanged();
			return true;
		}
		return false;
	}

	/**
	 * 送一只桶：把混合槽里 {@code phase} 相态的产物灌进相邻容器里第一只空桶。
	 *
	 * @return 有没有送出
	 */
	protected boolean pushBucket(ServerLevel level, BlockPos pos, Direction side, Chemical.Phase phase) {
		MolStock available = phaseStock(this.tank, phase);
		if (available.isEmpty()) {
			return false;
		}
		Container c = neighbor(level, pos, side);
		if (c == null) {
			return false;
		}
		for (int i = 0; i < c.getContainerSize(); i++) {
			ItemStack stack = c.getItem(i);
			if (stack.isEmpty() || !(stack.getItem() instanceof MolCarrier carrier)) {
				continue;
			}
			if (!carrier.isEmpty(stack)) {
				continue; // 只往空桶灌
			}
			// 一摞空桶共享一个内容组件：整摞一起灌会把 16 只标成满桶而只记一份账（bug#26）。
			if (!carrier.consumedOnEmpty() && stack.getCount() > 1) {
				continue;
			}
			// 灌多少由载具自己的容量说了算（不写死一桶）：铁桶 = 15 mol，将来别的载具自报其量。
			double room = Math.min(carrier.room(stack), carrier.capacityMol());
			if (room <= MIN_MOL) {
				continue;
			}
			MolStock load = takeUpTo(available, room);
			if (load.isEmpty()) {
				continue;
			}
			if (!carrier.holds(load)) {
				// 产物会侵蚀这只空桶：宁可不出料，也不要造出一只装不住的满桶
				this.pushBlockedByBucket = true;
				continue;
			}
			ItemStack filled = stack.copy();
			double accepted = carrier.add(filled, load);
			if (accepted <= MIN_MOL) {
				continue;
			}
			c.setItem(i, filled);
			// 账上按「实际装进去的比例」扣，保证 mol 不凭空多出
			double ratio = accepted / load.totalMol();
			for (Map.Entry<String, Double> e : new ArrayList<>(load.view().entrySet())) {
				this.tank.take(e.getKey(), e.getValue() * ratio);
			}
			c.setChanged();
			this.setChanged();
			return true;
		}
		return false;
	}

	protected static Container neighbor(ServerLevel level, BlockPos pos, Direction side) {
		BlockEntity be = level.getBlockEntity(pos.relative(side));
		return be instanceof Container c ? c : null;
	}

	// ================================================================ 反应

	/** 跑一轮反应；返回本 tick 推进过的反应条数（允许分数份推进，见 bug#19）。 */
	protected int react() {
		this.blockedPhase = null;
		this.blockedMol = 0.0;
		boolean unsafe = this.sawUnsafeBucket || this.pushBlockedByBucket;
		if (this.tank.isEmpty()) {
			this.status = unsafe ? ReactorStatus.BUCKET_UNSAFE : ReactorStatus.IDLE;
			return 0;
		}
		if (this.tank.totalMol() >= TANK_CAPACITY - MIN_MOL) {
			OutputRouting.Blocked blocked = OutputRouting.blocked(this.tank, MIN_MOL);
			this.blockedPhase = blocked.phase();
			this.blockedMol = blocked.mol();
			this.status = ReactorStatus.OUTPUT_FULL;
			return 0;
		}
		// 传 TANK_CAPACITY：产物净增会让总 mol 超过槽容量时，反应器按余量压缩推进份数（bug#19）；
		// 传 machineRateFactor()：整机速率系数，当前两台机器都是 1.0，真正的机器差异在 RATE_PER_TICK 上限。
		Reactor.Outcome outcome = Reactor.runAll(this.tank, RATE_PER_TICK, this.stirred(),
				this.catalystIds(), Reactor.VESSEL_KINDS, null, TANK_CAPACITY, this.machineRateFactor());
		if (outcome.reactionsRun() > 0) {
			this.status = ReactorStatus.RUNNING;
			this.setChanged();
		} else if (outcome.blockedByStirring() > 0) {
			this.status = ReactorStatus.NEEDS_STIRRING;
		} else {
			this.status = unsafe ? ReactorStatus.BUCKET_UNSAFE : ReactorStatus.NO_REACTION;
		}
		return outcome.reactionsRun();
	}

	/** 催化剂槽里的 id（可能为空数组）。 */
	public List<String> catalystIds() {
		ItemStack catalyst = this.items.getItem(SLOT_CATALYST);
		if (catalyst.isEmpty()) {
			return List.of();
		}
		List<String> ids = new ArrayList<>(2);
		for (Map.Entry<String, Item> e : CATALYST_ITEMS.entrySet()) {
			if (catalyst.is(e.getValue())) {
				ids.add(e.getKey());
			}
		}
		return ids;
	}

	/**
	 * 整机速率系数：在机器吞吐上限（{@link #RATE_PER_TICK}）之外，再给这台机器整体乘一个系数。
	 *
	 * <p>当前反应釜与搅拌器都返回 {@code 1.0} —— 反应速率的差异来自反应表自己的
	 * {@code Reaction.rate()} 与 {@link Reactor#rateMultiplier}，机器层面的差异只有吞吐上限。
	 * 留这个口是为了将来「某台机器整体更快 / 更慢」时不必再动 {@code Reactor} 的签名。
	 */
	protected double machineRateFactor() {
		return 1.0;
	}

	// ================================================================ 小工具

	/** 按相态抽出账里的一部分（新账本，不动原账）。 */
	protected static MolStock phaseStock(MolStock src, Chemical.Phase phase) {
		MolStock out = MolStock.empty();
		for (Map.Entry<String, Double> e : src.view().entrySet()) {
			if (Chemical.phaseOf(e.getKey()) == phase) {
				out.add(e.getKey(), e.getValue());
			}
		}
		return out;
	}

	/** 从账里取最多 {@code max} mol，按登记顺序取，返回新账本（不动原账）。 */
	protected static MolStock takeUpTo(MolStock src, double max) {
		MolStock out = MolStock.empty();
		double left = max;
		for (Map.Entry<String, Double> e : src.view().entrySet()) {
			if (left <= MIN_MOL) {
				break;
			}
			double take = Math.min(e.getValue(), left);
			if (take <= MIN_MOL) {
				continue;
			}
			out.add(e.getKey(), take);
			left -= take;
		}
		return out;
	}

	/** 把 {@code from} 里的一定量按占比兑进 {@code to}。 */
	protected static void pour(MolStock from, MolStock to, double amount) {
		double total = from.totalMol();
		if (total <= MIN_MOL || amount <= MIN_MOL) {
			return;
		}
		amount = Math.min(amount, total);
		double ratio = amount / total;
		for (Map.Entry<String, Double> e : new ArrayList<>(from.view().entrySet())) {
			double got = from.take(e.getKey(), e.getValue() * ratio);
			if (got > 0.0) {
				to.add(e.getKey(), got);
			}
		}
	}

	/** 账里 mol 最多的那种物质；空账返回 null。 */
	protected String dominant() {
		String best = null;
		double bestMol = 0.0;
		for (Map.Entry<String, Double> e : this.tank.view().entrySet()) {
			if (e.getValue() > bestMol) {
				bestMol = e.getValue();
				best = e.getKey();
			}
		}
		return best;
	}

	/** 账里最多的那种物质的 mol；空账返回 0。 */
	protected double dominantMol() {
		String best = null;
		double bestMol = 0.0;
		for (Map.Entry<String, Double> e : this.tank.view().entrySet()) {
			if (e.getValue() > bestMol) {
				bestMol = e.getValue();
				best = e.getKey();
			}
		}
		return best == null ? 0.0 : bestMol;
	}

	/** mol → 百分之一 mol（同步用，钳到 short 范围）。 */
	protected static int centi(double mol) {
		return (int) Math.max(0L, Math.min(32767L, Math.round(mol * 100.0)));
	}

	protected int readData(int index) {
		switch (index) {
			case D_TANK -> {
				return centi(this.tank.totalMol());
			}
			case D_MASS -> {
				return (int) Math.max(0L, Math.min(32767L, Math.round(this.tank.massGrams())));
			}
			case D_STATUS -> {
				return this.status.ordinal();
			}
			case D_ROLES -> {
				return packRoles() & 0x1FF;   // 面 0..2（9 bit）
			}
			case D_ROLES_B -> {
				return (packRoles() >> 9) & 0x1FF; // 面 3..5
			}
			case D_COMP_COUNT -> {
				return compositionCount();
			}
			case D_RATIO -> {
				return this.ratio();
			}
			case D_BUF_A -> {
				return centi(this.bufferMol(0));
			}
			case D_BUF_B -> {
				return centi(this.bufferMol(1));
			}
			case D_DOMINANT -> {
				String d = dominant();
				return d == null ? 0 : Chemical.indexOf(d) + 1;
			}
			case D_DOMINANT_MOL -> {
				String d = dominant();
				return centi(dominantMol());
			}
			case D_STIRRING -> {
				return stirringOn() ? 1 : 0;
			}
			case D_FEED_COUNT -> {
				return feedCompositionCount();
			}
			case D_BLOCK_PHASE -> {
				return OutputRouting.ordinal(this.blockedPhase);
			}
			case D_BLOCK_MOL -> {
				return centi(this.blockedMol);
			}
			default -> {
				if (index >= D_FEED_BASE) {
					return readFeedComposition(index - D_FEED_BASE);
				}
				return readComposition(index - D_COMP_BASE);
			}
		}
	}

	/** 界面用：第 index 本预混缓冲里有多少 mol（反应釜没有预混，恒 0）。 */
	public double bufferMol(int index) {
		return 0.0;
	}

	/**
	 * 界面用：搅拌开不开。
	 *
	 * <p>反应釜恒 {@code false}（它根本没有搅拌这回事）；搅拌器可以手动关掉 ——
	 * 关掉之后 {@code requiresStirring} 的反应就不会发生，正好用来「先配比、后放行」。
	 */
	public boolean stirringOn() {
		return false;
	}

	// ================================================================ 六面配置

	protected static final int FACES = 6;
	protected static final int BITS = 3;
	protected static final int MASK = (1 << BITS) - 1;

	public int packRoles() {
		int packed = 0;
		for (int i = 0; i < FACES; i++) {
			packed |= (this.roles[i] & MASK) << (i * BITS);
		}
		return packed;
	}

	public void unpackRoles(int packed) {
		for (int i = 0; i < FACES; i++) {
			this.roles[i] = (packed >> (i * BITS)) & MASK;
		}
	}

	@Override
	public int packFaces() {
		return packRoles();
	}

	@Override
	public void applyFaces(int packed) {
		unpackRoles(packed);
		this.setChanged();
	}

	@Override
	public void clearFaces() {
		java.util.Arrays.fill(this.roles, 0);
	}

	@Override
	public net.minecraft.resources.Identifier faceConfigKind() {
		return FaceConfigurable.kindOf(this);
	}

	/** 某个面的当前角色序号（界面读）。 */
	public int role(Direction side) {
		return this.roles[side.ordinal()];
	}

	public int roleCountPublic() {
		return roleCount();
	}

	// ================================================================ 存档

	@Override
	protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput out) {
		super.saveAdditional(out);
		out.putInt("roles", packRoles());
		out.putInt("ratio", this.ratio());
		out.putString("tank", encode(this.tank));
		this.items.storeAsItemList(out.list("items", ItemStack.CODEC));
	}

	@Override
	protected void loadAdditional(net.minecraft.world.level.storage.ValueInput in) {
		super.loadAdditional(in);
		int packed = in.getIntOr("roles", 0);
		unpackRoles(packed);
		setRatio(in.getIntOr("ratio", 50));
		this.status = ReactorStatus.IDLE;
		decode(this.tank, in.getStringOr("tank", ""));
		this.items.fromItemList(in.listOrEmpty("items", ItemStack.CODEC));
	}

	/** 「id:mol;id:mol」——存档里一行，简单、无损、与版本无关。 */
	protected static String encode(MolStock stock) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Double> e : stock.view().entrySet()) {
			if (sb.length() > 0) {
				sb.append(';');
			}
			sb.append(e.getKey()).append(':').append(e.getValue());
		}
		return sb.toString();
	}

	protected static void decode(MolStock stock, String text) {
		stock.clear();
		if (text == null || text.isEmpty()) {
			return;
		}
		for (String part : text.split(";")) {
			int colon = part.indexOf(':');
			if (colon <= 0) {
				continue;
			}
			try {
				stock.add(part.substring(0, colon), Double.parseDouble(part.substring(colon + 1)));
			} catch (NumberFormatException ignored) {
				// 坏数据跳过
			}
		}
	}

	// ================================================================ 容器

	@Override
	public int getContainerSize() {
		return ITEM_SLOTS;
	}

	@Override
	public boolean isEmpty() {
		return this.items.isEmpty();
	}

	@Override
	public ItemStack getItem(int slot) {
		return this.items.getItem(slot);
	}

	@Override
	public ItemStack removeItem(int slot, int amount) {
		return this.items.removeItem(slot, amount);
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		return this.items.removeItemNoUpdate(slot);
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		this.items.setItem(slot, stack);
	}

	@Override
	public boolean stillValid(Player player) {
		return Container.stillValidBlockEntity(this, player);
	}

	@Override
	public void clearContent() {
		this.items.clearContent();
	}

	@Override
	public boolean canPlaceItem(int slot, ItemStack stack) {
		return slot == SLOT_CATALYST && isCatalyst(stack);
	}

	@Override
	public int[] getSlotsForFace(Direction side) {
		return new int[] { SLOT_CATALYST };
	}

	@Override
	public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side) {
		return canPlaceItem(slot, stack);
	}

	@Override
	public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
		return false; // 催化剂不消耗、不取出
	}

	public static boolean isCatalyst(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		for (Item item : CATALYST_ITEMS.values()) {
			if (stack.is(item)) {
				return true;
			}
		}
		return false;
	}

	// ================================================================ 界面

	@Override
	public Unit getScreenOpeningData(ServerPlayer player) {
		return Unit.INSTANCE;
	}

	@Override
	public abstract AbstractContainerMenu createMenu(int syncId, Inventory inventory, Player player);

	public ContainerData getContainerData() {
		return this.dataAccess;
	}

	public NonNullList<ItemStack> itemSlots() {
		return this.items.getItems();
	}

	public MolStock tank() {
		return this.tank;
	}

	/**
	 * 界面用：混合槽里成分最多 {@link #TRACKED_ENTRIES} 项的条数（饼图用）。
	 *
	 * <p>按 mol 从多到少排；同步给客户端的是「物质序号 + 千分位占比」，客户端据此画扇形。
	 */
	public int compositionCount() {
		return countOf(this.tank);
	}

	/**
	 * 界面用：第 {@code slot} 格成分同步值。
	 *
	 * <p>偶数格 = 物质序号 + 1（0 表示没有），奇数格 = 占比千分位。
	 */
	public int readComposition(int slot) {
		return readCompositionOf(this.tank, slot);
	}

	/** 界面用：进料账（A/B 缓冲合并）同步了几项。 */
	public int feedCompositionCount() {
		MolStock feed = MolStock.empty();
		addFeed(feed);
		return countOf(feed);
	}

	/** 界面用：进料账第 {@code slot} 格同步值（与 {@link #readComposition} 同构）。 */
	public int readFeedComposition(int slot) {
		MolStock feed = MolStock.empty();
		addFeed(feed);
		return readCompositionOf(feed, slot);
	}

	/** 一本账里同步给界面的项数。 */
	public static int countOf(MolStock stock) {
		return Math.min(TRACKED_ENTRIES, stock.view().size());
	}

	/** 把本机的进料账合并填进 {@code out}；基类没有进料，子类各填各的。 */
	protected void addFeed(MolStock out) {
	}

	/** 界面用：{@code stock} 第 {@code slot} 格成分同步值。 */
	public int readCompositionOf(MolStock stock, int slot) {
		if (slot < 0 || slot >= TRACKED_ENTRIES * 2) {
			return 0;
		}
		List<Map.Entry<String, Double>> sorted = new ArrayList<>(stock.view().entrySet());
		sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
		int index = slot / 2;
		if (index >= sorted.size()) {
			return 0;
		}
		double total = Math.max(1.0e-9, stock.totalMol());
		// 成分多于 TRACKED_ENTRIES 时，最后一格把剩下的合并成「其他」（-1）
		boolean tail = sorted.size() > TRACKED_ENTRIES && index == TRACKED_ENTRIES - 1;
		if (tail) {
			double sum = 0.0;
			for (int i = index; i < sorted.size(); i++) {
				sum += sorted.get(i).getValue();
			}
			if (slot % 2 == 0) {
				return -1;
			}
			return (int) Math.max(0L, Math.min(1000L, Math.round(sum / total * 1000.0)));
		}
		Map.Entry<String, Double> entry = sorted.get(index);
		if (slot % 2 == 0) {
			return Chemical.indexOf(entry.getKey()) + 1;
		}
		return (int) Math.max(0L, Math.min(1000L, Math.round(entry.getValue() / total * 1000.0)));
	}

	public ReactorStatus status() {
		return this.status;
	}

	/** 堵住的那一相；没有堵住返回 {@code null}。服务端 / 测试读。 */
	public Chemical.Phase blockedPhase() {
		return this.blockedPhase;
	}

	/** 堵住的那一相有多少 mol；没有堵住返回 0。服务端 / 测试读。 */
	public double blockedMol() {
		return this.blockedMol;
	}

	@Override
	public Component getDisplayName() {
		return Component.translatable(this.getBlockState().getBlock().getDescriptionId());
	}

	/** 灯光跟随状态。 */
	protected void updateLit(ServerLevel level, BlockPos pos, BlockState state) {
		BooleanProperty property = litProperty();
		boolean lit = this.status.lit();
		if (state.getValue(property) != lit) {
			level.setBlock(pos, state.setValue(property, lit), Block.UPDATE_ALL);
		}
	}

	/** 供方块的 ticker 调用。 */
	public static void serverTick(ServerLevel level, BlockPos pos, BlockState state, AbstractReactorBlockEntity be) {
		be.tick(level, pos, state);
	}

	/** 不可用（保留给子类做老家接口时的占位）。 */
	protected static Level levelOf(BlockEntity be) {
		return be.getLevel();
	}
}
