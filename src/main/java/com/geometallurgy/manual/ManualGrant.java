package com.geometallurgy.manual;

import com.geometallurgy.Geometallurgy;
import com.geometallurgy.item.ModItems;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;

/**
 * 开局发放任务书。
 *
 * <h2>怎么保证「只发一次」</h2>
 * 用玩家的<b>计分板标签</b>（{@code entityTags()}）记一笔，而不是每次进世界都查背包：
 * <ul>
 *   <li>查背包的做法，玩家把书丢进岩浆后再进世界就又拿一本 —— 等于可以无限刷书；</li>
 *   <li>标签跟着玩家存档走，一次就是一次。丢了不要紧，书本身有合成配方，能再造。</li>
 * </ul>
 *
 * <p>标签用的是模组命名空间，不和别的模组撞。
 */
public final class ManualGrant {

	/** 已经发过书的标记。 */
	public static final String GIVEN_TAG = Geometallurgy.MOD_ID + ".manual_given";

	private ManualGrant() {
	}

	public static void init() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				grantIfFirstTime(handler.getPlayer()));
	}

	/**
	 * 第一次进世界就发一本，之后永不再发。
	 *
	 * <p>抽成独立方法而不是写在 lambda 里，是为了让它<b>可被直接调用</b> ——
	 * 「只发一次」这条规则必须能验证，而不是只能靠读代码相信。
	 *
	 * @return 这次真的发了返回 true；已经发过、什么都没做返回 false
	 */
	public static boolean grantIfFirstTime(ServerPlayer player) {
		if (player.entityTags().contains(GIVEN_TAG)) {
			return false;
		}
		player.addTag(GIVEN_TAG);
		// 进世界时背包是空的，正常一定放得下；真放不下就掉在脚下，也不吞掉。
		player.getInventory().placeItemBackInInventory(
				new ItemStack(ModItems.FIELD_MANUAL), Prediction.SERVER_ONLY);
		player.sendSystemMessage(Component.translatable("manual.geometallurgy.granted")
				.withStyle(ChatFormatting.GOLD));
		return true;
	}
}
