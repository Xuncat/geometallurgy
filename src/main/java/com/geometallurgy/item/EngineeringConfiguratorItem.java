package com.geometallurgy.item;

import com.geometallurgy.blockentity.FaceConfigurable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 工程配置器：把一台机器的六面配置整份搬到另一台上去。
 *
 * <h2>操作只有三下</h2>
 * <ol>
 *   <li><b>Shift + 右键机器</b> —— 手上是空的就<b>复制</b>这台机器的六个面；</li>
 *   <li><b>Shift + 右键另一台机器</b> —— 手上已经有配置就<b>写入</b>，但要求两台机器是同一型；</li>
 *   <li><b>Shift + 右键空气</b> —— <b>初始化</b>，把手上的记录清掉。</li>
 * </ol>
 *
 * <p>不按 Shift 时只报一句当前状态，什么都不改 —— 右键在 Minecraft 里是最高频的动作，
 * 一个会误触就改配置的工具不能用。
 *
 * <h2>「同一型」为什么是硬条件</h2>
 * 本模组有两套面角色枚举（粉碎机/离心机/冶金炉走 {@code PortRole}，磁力筛机走 {@code FaceRole}），
 * 同一个序号在两边含义不同。不比对类型的话，「粉碎机的配置贴到磁筛机上」会安安静静地成功，
 * 而结果是六个面全错 —— 界面上看不出任何异常。所以这里宁可拒绝，并明说是哪两型对不上。
 *
 * <p>音效与提示都放在<b>服务端</b>判定之后：客户端的 {@code useOn} 直接返回成功，
 * 只负责让手臂摆一下，不参与任何判断 —— 否则客户端和服务端会各说一套。
 */
public class EngineeringConfiguratorItem extends Item {

	/** 提示用的键前缀。 */
	private static final String KEY = "item.geometallurgy.engineering_configurator.";

	public EngineeringConfiguratorItem(Properties properties) {
		super(properties);
	}

	// ------------------------------------------------------------------ 空手右键：初始化

	/**
	 * 没瞄着方块的右键。
	 *
	 * <p>按住 Shift 是「初始化」；不按 Shift 只念一句当前记着什么。
	 */
	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		ItemStack stack = player.getItemInHand(hand);
		FaceCopy held = FaceCopy.of(stack);

		if (!player.isShiftKeyDown()) {
			// 只是看一眼，不动它
			player.sendSystemMessage(held == null
					? Component.translatable(KEY + "status_empty").withStyle(ChatFormatting.GRAY)
					: Component.translatable(KEY + "status_held", held.displayKind())
							.withStyle(ChatFormatting.GRAY));
			return InteractionResult.SUCCESS;
		}

		if (held == null) {
			player.sendSystemMessage(Component.translatable(KEY + "nothing_to_clear")
					.withStyle(ChatFormatting.GRAY));
		} else {
			FaceCopy.clear(stack);
			player.sendSystemMessage(Component.translatable(KEY + "cleared")
					.withStyle(ChatFormatting.YELLOW));
			blip(player, 0.8F);
		}
		return InteractionResult.SUCCESS;
	}

	// ------------------------------------------------------------------ 对着方块右键：复制 / 写入

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Player player = context.getPlayer();
		if (player == null || !player.isShiftKeyDown()) {
			// 不按 Shift 一律让给别人：可能是想开这台机器的界面
			return InteractionResult.PASS;
		}
		Level level = context.getLevel();
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}

		BlockPos pos = context.getClickedPos();
		if (!(level.getBlockEntity(pos) instanceof FaceConfigurable machine)) {
			player.sendSystemMessage(Component.translatable(KEY + "not_a_machine",
					level.getBlockState(pos).getBlock().getName()).withStyle(ChatFormatting.GRAY));
			return InteractionResult.SUCCESS;
		}

		ItemStack stack = context.getItemInHand();
		FaceCopy held = FaceCopy.of(stack);
		Identifier kind = machine.faceConfigKind();

		if (held == null) {
			// 空手 → 复制
			FaceCopy.write(stack, kind, machine.packFaces());
			player.sendOverlayMessage(Component.translatable(KEY + "copied",
					machineName(level, pos)));
			blip(player, 1.5F);
		} else if (held.sameKindAs(kind)) {
			// 手上有同型配置 → 写入
			machine.applyFaces(held.faces());
			player.sendOverlayMessage(Component.translatable(KEY + "pasted",
					machineName(level, pos)));
			blip(player, 1.1F);
		} else {
			// 类型对不上 → 拒绝，并说清是哪两型
			player.sendSystemMessage(Component.translatable(KEY + "kind_mismatch",
					held.displayKind(), machineName(level, pos)).withStyle(ChatFormatting.RED));
		}
		return InteractionResult.SUCCESS;
	}

	// ------------------------------------------------------------------ 名称

	/**
	 * 把「手上记着谁的配置」写进物品名。
	 *
	 * <p>不这么做的话，背包里那把配置器看上去永远是一样的 —— 玩家没法在不右键的情况下
	 * 知道它现在是不是拿着别人的配置，误贴就发生了。
	 */
	@Override
	public Component getName(ItemStack stack) {
		Component base = super.getName(stack);
		FaceCopy held = FaceCopy.of(stack);
		return held == null ? base
				: Component.translatable(KEY + "named", base, held.displayKind());
	}

	// ------------------------------------------------------------------ 小工具

	private static Component machineName(Level level, BlockPos pos) {
		return level.getBlockState(pos).getBlock().getName();
	}

	private static void blip(Player player, float pitch) {
		player.level().playSound(null, player.blockPosition(), SoundEvents.NOTE_BLOCK_PLING.value(),
				SoundSource.PLAYERS, 0.5F, pitch);
	}
}
