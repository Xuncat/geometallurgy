package com.geometallurgy.item;

import java.util.function.Consumer;

import com.geometallurgy.metal.Ingots;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * 单一「锭」物品。
 *
 * <p>整个模组只注册这一个锭，它是什么金属完全由组件决定（见 {@link Ingots}）：
 * 名字浮层读成分表，模型则由 {@code items/ingot.json} 里的 {@code minecraft:select}
 * 按 {@link ModComponents#INGOT_METAL} 选贴图。所以「一个物品 ID、按成分换名换贴图」
 * 这件事在服务端与客户端各做一半 —— 这一半负责名字，那一半负责模型。
 */
public class IngotItem extends Item {

	public IngotItem(Properties properties) {
		super(properties);
	}

	/** 名字随成分走：铁锭 / 精铁锭 / 合金锭。 */
	@Override
	public Component getName(ItemStack stack) {
		return Ingots.displayName(stack);
	}

	/** 浮层列出主金属、纯度与逐项成分。 */
	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
			Consumer<Component> tooltip, TooltipFlag flag) {
		super.appendHoverText(stack, context, display, tooltip, flag);
		for (Component line : Ingots.tooltip(stack)) {
			tooltip.accept(line);
		}
	}
}
