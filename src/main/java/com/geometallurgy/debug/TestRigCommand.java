package com.geometallurgy.debug;

import java.util.Locale;

import com.geometallurgy.Geometallurgy;
import com.geometallurgy.vein.EpochClock;
import com.geometallurgy.vein.OreBody;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 测试场地的两条通道（见 docs/测试场地.md）：
 *
 * <ul>
 *   <li><b>A. 调试命令</b>：{@code /geometallurgy testrig <site>} 在玩家脚边搭好一个场地；
 *       无参数列出全部场地，{@code clear} 清掉上一次。场地直接做成字面量子命令，
 *       Brigadier 因此自带 tab 补全（不依赖参数类型）。</li>
 *   <li><b>B. 进世界自动搭</b>：系统属性 {@code -Dgeometallurgy.testrig=true} 打开后，
 *       加入名字含 {@code test}（不分大小写）的存档时，在出生点旁边一次性搭出全部场地。
 *       只走服务端的 {@link ServerPlayConnectionEvents#JOIN}；用玩家计分板标签
 *       {@value #AUTO_TAG} 记「已经搭过」，重进世界不重复搭。</li>
 * </ul>
 *
 * <p>所有文字走 {@code Component.literal}（不新增 lang 键）。
 */
public final class TestRigCommand {

	/** 自动搭建开关的系统属性名：{@code -Dgeometallurgy.testrig=true}。 */
	public static final String AUTO_SWITCH = "geometallurgy.testrig";

	/** 「自动搭建已经跑过」的玩家标签；跟着玩家存档走，保证每个玩家只搭一次。 */
	private static final String AUTO_TAG = Geometallurgy.MOD_ID + ".testrig_auto";

	private TestRigCommand() {
	}

	/** 注册命令与自动搭建钩子（Geometallurgy 里那一行注册调用它）。 */
	public static void init() {
		CommandRegistrationCallback.EVENT.register(
				(dispatcher, registries, selection) -> register(dispatcher));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				autoBuild(handler.getPlayer(), server));
	}

	// ------------------------------------------------------------------ 命令

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> testrig = Commands.literal("testrig")
				.executes(ctx -> list(ctx.getSource()));
		for (String site : TestRig.SITES) {
			testrig.then(Commands.literal(site).executes(ctx -> build(ctx.getSource(), site)));
		}
		testrig.then(Commands.literal("clear").executes(ctx -> clear(ctx.getSource())));

		dispatcher.register(Commands.literal("geometallurgy")
				.requires(Commands.hasPermission(Commands.LEVEL_ALL))
				.then(testrig)
				.then(Commands.literal("calendar").executes(ctx -> calendar(ctx.getSource()))));
	}

	/**
	 * {@code /geometallurgy calendar} —— 虚拟日历的只读调试行。
	 *
	 * <p>世界纪元是全局的（{@link EpochClock#globalEpoch}），而玩家脚下这一处矿脉属于哪个
	 * 纪元、还有多久换脉，要按<b>矿体单元</b>算（{@link EpochClock#epochOfCell}）。两者
	 * 相差 0 或 1 个纪元，因为每个区块带一个确定性偏移。文字走 literal，不新增 lang 键。
	 */
	private static int calendar(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendSystemMessage(Component.literal("该指令只能由玩家执行。"));
			return 0;
		}
		ServerLevel level = player.level();
		long now = level.getGameTime();
		long world = EpochClock.globalEpoch(level);
		double dayInEpoch = Math.floorMod(now, EpochClock.TICKS_PER_EPOCH)
				/ (double) EpochClock.TICKS_PER_DAY;
		BlockPos pos = player.blockPosition();
		int cellX = Math.floorDiv(pos.getX(), OreBody.CELL_X);
		int cellZ = Math.floorDiv(pos.getZ(), OreBody.CELL_Z);
		long cellEpoch = EpochClock.epochOfCell(now, cellX, cellZ);
		long until = EpochClock.ticksUntilNextCell(now, cellX, cellZ);
		source.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
				"【纪元日历】世界纪元 %d · 第 %.1f / %d 游戏日",
				world, dayInEpoch, EpochClock.DAYS_PER_EPOCH)).withStyle(ChatFormatting.GOLD));
		source.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
				"【本处矿脉】单元 (%d, %d) 纪元 %d · 还有 %.1f 游戏日（%d 刻）换脉",
				cellX, cellZ, cellEpoch, until / (double) EpochClock.TICKS_PER_DAY, until))
				.withStyle(ChatFormatting.GRAY));
		return 1;
	}

	private static int list(CommandSourceStack source) {
		source.sendSystemMessage(Component.literal("【测试场地】可用：")
				.append(Component.literal(String.join(" | ", TestRig.SITES))));
		source.sendSystemMessage(Component.literal(
				"用法：/geometallurgy testrig <site>，或 /geometallurgy testrig clear"));
		return 1;
	}

	private static int build(CommandSourceStack source, String site) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendSystemMessage(Component.literal("该指令只能由玩家执行。"));
			return 0;
		}
		boolean cleared = TestRig.clearLast(player.level(), player);
		if (!TestRig.build(player.level(), player, site)) {
			source.sendSystemMessage(Component.literal("未知场地：" + site));
			return 0;
		}
		source.sendSystemMessage(Component.literal("【测试场地 " + site + "】已在脚边搭好"
				+ (cleared ? "（已清掉上一次）" : "") + "。").withStyle(ChatFormatting.GOLD));
		for (String line : TestRig.checklist(site)) {
			source.sendSystemMessage(Component.literal("· " + line).withStyle(ChatFormatting.GRAY));
		}
		return 1;
	}

	private static int clear(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			source.sendSystemMessage(Component.literal("该指令只能由玩家执行。"));
			return 0;
		}
		boolean cleared = TestRig.clearLast(player.level(), player);
		source.sendSystemMessage(Component.literal(cleared
				? "【测试场地】已清除上一次搭的场地。"
				: "【测试场地】没有记录到上一次搭的场地。").withStyle(ChatFormatting.GOLD));
		return 1;
	}

	// ------------------------------------------------------------------ 自动搭建

	/**
	 * 进世界自动搭：{@code -Dgeometallurgy.testrig=true} + 存档名含 {@code test} + 本玩家还没搭过。
	 *
	 * <p>先查标签再动手；标签在成功搭完后才写，中途报错不会把玩家标记成「已搭」。
	 */
	private static void autoBuild(ServerPlayer player, MinecraftServer server) {
		if (!Boolean.getBoolean(AUTO_SWITCH)) {
			return;
		}
		String name = server.getWorldData().getLevelName();
		if (name == null || !name.toLowerCase(Locale.ROOT).contains("test")) {
			return;
		}
		if (player.entityTags().contains(AUTO_TAG)) {
			return;
		}
		try {
			int count = TestRig.buildAll(player.level(), player);
			player.addTag(AUTO_TAG);
			player.sendSystemMessage(Component.literal(
					"【测试场地】测试存档「" + name + "」：已自动搭好 " + count + " 个场地（开关 "
							+ AUTO_SWITCH + "）。").withStyle(ChatFormatting.GOLD));
			player.sendSystemMessage(Component.literal(
					"用 /geometallurgy testrig clear 清场。").withStyle(ChatFormatting.GRAY));
		} catch (RuntimeException e) {
			Geometallurgy.LOGGER.warn("[测试场地] 自动搭建失败", e);
		}
	}
}
