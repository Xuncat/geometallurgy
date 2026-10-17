package com.geometallurgy.item;

import java.util.Locale;

import com.geometallurgy.menu.ProspectorMenu;
import com.geometallurgy.proto.OreMineral;
import com.geometallurgy.prospect.Prospector;
import com.geometallurgy.vein.EpochClock;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * 矿物探测仪。
 *
 * <p>两种用法压在同一个右键上，靠<b>按住时长</b>区分：
 * <ul>
 *   <li><b>短按</b>（不足 {@value #LONG_PRESS_TICKS} 刻）—— 抬手时打开选择界面，
 *       滚轮挑一种矿物；</li>
 *   <li><b>长按</b>（达到 {@value #LONG_PRESS_TICKS} 刻，约 0.5 秒）—— 抬手时开扫，
 *       报出最近的该种矿体中心的方向与大致坐标。</li>
 * </ul>
 *
 * <p>判定只能放在<b>松手那一刻</b>：按下时无从知道这一按是长是短。所以这里走原版
 * 「使用中」机制（弓、望远镜同一套）：{@code use} 只负责开始计时，
 * 松手时 {@code releaseUsing} 拿到剩余额，减出按住的刻数再分流。
 *
 * <p>之所以不直接用「使用结束」当触发（即把 useDuration 设成短值），是因为那样长按超过
 * 阈值就会自动触发，而玩家还没松手 —— 手感会变成「按太久会自己扫」。这里把时长设成
 * {@value #USE_DURATION}，让分流永远发生在玩家松手的那一帧。
 */
public class MineralProspectorItem extends Item {

	/** 「使用中」窗口的最大刻数。取足够大，长按不会自行结束。 */
	public static final int USE_DURATION = 72000;

	/** 按住多少刻算长按（10 刻 = 0.5 秒）。 */
	public static final int LONG_PRESS_TICKS = 10;

	public MineralProspectorItem(Properties properties) {
		super(properties);
	}

	// ------------------------------------------------------------------ 目标

	/** 物品当前锁定的矿物。组件缺失或存了陌生的 id 时退回默认值。 */
	public static OreMineral target(ItemStack stack) {
		String id = stack.get(ModComponents.TARGET_MINERAL);
		OreMineral mineral = id == null ? null : OreMineral.byId(id);
		return mineral == null ? OreMineral.HEMATITE : mineral;
	}

	/** 把选择写回物品。 */
	public static void setTarget(ItemStack stack, OreMineral mineral) {
		stack.set(ModComponents.TARGET_MINERAL, mineral.id());
	}

	// ------------------------------------------------------------------ 右键分流

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		// 只开始计时，什么都不做 —— 是短按还是长按要等松手才知道
		return ItemUtils.startUsingInstantly(level, player, hand);
	}

	@Override
	public int getUseDuration(ItemStack stack, LivingEntity entity) {
		return USE_DURATION;
	}

	@Override
	public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
		// 刚跨过长按阈值的这一帧：给玩家一个「锁定住了」的回执，之后松手就是探测
		if (entity instanceof ServerPlayer player && USE_DURATION - remaining == LONG_PRESS_TICKS) {
			player.sendSystemMessage(Component.translatable("gui.geometallurgy.prospector.charging",
					target(stack).displayName()), true);
			player.level().playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_PLING.value(),
					SoundSource.PLAYERS, 0.6F, 1.6F);
		}
	}

	@Override
	public boolean releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
		int held = USE_DURATION - timeLeft;
		if (!(entity instanceof ServerPlayer player)) {
			return false;
		}
		OreMineral mineral = target(stack);
		if (held >= LONG_PRESS_TICKS) {
			scan(player, mineral);
		} else {
			player.openMenu(new Opening(mineral.ordinal()));
		}
		return true;
	}

	// ------------------------------------------------------------------ 探测

	private static void scan(ServerPlayer player, OreMineral mineral) {
		ServerLevel level = player.level() instanceof ServerLevel serverLevel ? serverLevel : null;
		if (level == null) {
			return;
		}
		Prospector.Reading reading = Prospector.findNearest(level, player.blockPosition(), mineral);
		if (reading == null) {
			player.sendSystemMessage(Component.translatable("gui.geometallurgy.prospector.none",
					Prospector.radiusInBlocks(), mineral.displayName()).withStyle(ChatFormatting.GRAY));
			return;
		}

		level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME,
				SoundSource.PLAYERS, 0.8F, 1.4F);

		double bearing = reading.bearing(player.getX(), player.getZ());
		String compass = Component.translatable("compass.geometallurgy." + Prospector.Reading.compassKey(bearing))
				.getString();

		player.sendSystemMessage(Component.translatable("gui.geometallurgy.prospector.header",
				mineral.displayName()).withStyle(ChatFormatting.AQUA));
		player.sendSystemMessage(Component.translatable("gui.geometallurgy.prospector.bearing",
				compass,
				String.format(Locale.ROOT, "%.0f", bearing),
				String.format(Locale.ROOT, "%.0f", reading.horizontal()),
				formatVertical(reading.vertical())));
		player.sendSystemMessage(Component.translatable("gui.geometallurgy.prospector.position",
				block(reading.x()), block(reading.y()), block(reading.z())));
		player.sendSystemMessage(Component.translatable("gui.geometallurgy.prospector.content",
				String.format(Locale.ROOT, "%.1f%%", reading.abundance() * 100.0),
				reading.rock().displayName()).withStyle(ChatFormatting.GRAY));
		// 虚拟日历：这一处矿脉属于哪个纪元、还有多久换脉。与机器重勘用的是同一个基准。
		player.sendSystemMessage(Component.translatable("gui.geometallurgy.prospector.calendar",
				Long.toString(reading.epoch()),
				String.format(Locale.ROOT, "%.1f",
						reading.ticksUntilReroll() / (double) EpochClock.TICKS_PER_DAY))
				.withStyle(ChatFormatting.GRAY));
	}

	private static Component formatVertical(double vertical) {
		String value = String.format(Locale.ROOT, "%.0f", Math.abs(vertical));
		return vertical >= 0.0
				? Component.translatable("gui.geometallurgy.prospector.up", value)
				: Component.translatable("gui.geometallurgy.prospector.down", value);
	}

	/**
	 * 直接报矿体中心所在的那个整数方块。
	 *
	 * <p>不再四舍五入到 8 格：矿体半轴最小 {@link com.geometallurgy.vein.OreBody#MIN_HALF_AXIS}，
	 * 而中心方块到几何中心的距离最多 0.87 格，所以这个方块必定在矿体内部、必定含矿。
	 * 旧的 8 格取整会让约一成的报告指到矿体之外（bug#45）。
	 */
	private static String block(double value) {
		return Integer.toString((int) Math.floor(value));
	}

	// ------------------------------------------------------------------ 开屏

	/**
	 * 打开选择界面。
	 *
	 * <p>26.x 的菜单类型是 {@code ExtendedMenuType}，打开的提供者<b>必须</b>同时实现
	 * {@link ExtendedMenuProvider}，否则原版会直接抛
	 * {@code must be opened with an ExtendedMenuProvider!}。
	 * 这里顺带把当前选中的序号当作开屏数据发过去，客户端界面不必自己去猜初始值。
	 */
	private record Opening(int ordinal) implements ExtendedMenuProvider<Integer> {

		@Override
		public Component getDisplayName() {
			return Component.translatable("gui.geometallurgy.prospector.title");
		}

		@Override
		public AbstractContainerMenu createMenu(int syncId, Inventory inventory, Player player) {
			return new ProspectorMenu(syncId, inventory, this.ordinal);
		}

		@Override
		public Integer getScreenOpeningData(ServerPlayer player) {
			return this.ordinal;
		}
	}

	// ------------------------------------------------------------------ 名称

	/**
	 * 把当前目标写进物品名：背包与快捷栏里一眼就能看出锁的是哪种矿。
	 *
	 * <p>没有走 {@code appendHoverText} —— 它在 26.x 已标记过时，新的写法要求把提示做成
	 * 一个实现 {@code TooltipProvider} 的数据组件；而物品名本来就会随组件同步，
	 * 显示当前目标是它的顺手用法，不必再挂一层组件。
	 */
	@Override
	public Component getName(ItemStack stack) {
		return Component.translatable("item.geometallurgy.mineral_prospector.named",
				super.getName(stack), target(stack).displayName());
	}

	/** 备用：允许其他代码取当前目标。 */
	@Nullable
	public static OreMineral targetOrNull(ItemStack stack) {
		String id = stack.get(ModComponents.TARGET_MINERAL);
		return id == null ? null : OreMineral.byId(id);
	}
}
