package com.geometallurgy.item;

import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import com.geometallurgy.chemical.MolStock;
import com.geometallurgy.metal.Ingots;
import com.geometallurgy.metal.PowderContent;
import com.geometallurgy.proto.Substance;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * 金属粉：金属锭经粉碎机磨成的粉（§十八「金属 + 酸 → H₂ 走金属锭粉碎」）。
 *
 * <p>与「锭」共用同一套成分组件（{@link ModComponents#INGOT_COMPOSITION} /
 * {@link ModComponents#INGOT_METAL}），名字与浮层随成分走 —— 所以它是「一个物品 ID、
 * 任意金属」，不必为每种金属注册一个物品。它同时是金属单质在 mol 反应表里的来源：
 * 反应釜里的金属物种（{@code zinc} 等）就是这种粉。
 *
 * <h2>为什么它实现 {@link MolCarrier}</h2>
 * 粉是<b>消耗品</b>，不是桶：它在 mol 世界里出现的那一刻，账就由金属成分现推出来；
 * 机器收下它就把这一件吃掉（计数 -1），不像桶那样还一只空载具。
 * 实现里因此不存任何可变 mol 组件 —— 那正是 bug#12 的病根（一摞共享一个组件，
 * 倒一次清一整摞）。{@link #stock} 每次现算，{@link #consumedOnEmpty} 告诉机器「消耗一件」。
 *
 * <p>粉不是容器：{@link #room} 恒 0，机器不会试图往粉里灌产物；{@link #add} 恒不收。
 */
public class MetalPowderItem extends Item implements MolCarrier {

	/** 一件金属粉携带的 mol 数（见 {@link PowderContent#MOL_PER_POWDER}）。 */
	public static final double MOL_PER_POWDER = PowderContent.MOL_PER_POWDER;

	public MetalPowderItem(Properties properties) {
		super(properties);
	}

	/** 名字随成分走：锌粉 / 铁粉；成分不明时叫「金属粉」。 */
	@Override
	public Component getName(ItemStack stack) {
		String key = Ingots.metalKey(stack);
		if (key == null || Ingots.ALLOY.equals(key)) {
			return Component.translatable("item.geometallurgy.metal_powder");
		}
		return Component.translatable("item.geometallurgy.metal_powder.named",
				Substance.displayName(Ingots.baseMetalOf(key)));
	}

	/** 浮层沿用锭的成分读法（主金属 + 逐项成分），最上面再加一行 mol 账。 */
	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
			Consumer<Component> tooltip, TooltipFlag flag) {
		super.appendHoverText(stack, context, display, tooltip, flag);
		MolStock content = stock(stack);
		if (!content.isEmpty()) {
			// 这一行是玩家最该先看到的：这件粉能往反应釜里送多少 mol。
			for (Map.Entry<String, Double> e : content.view().entrySet()) {
				tooltip.accept(Component.translatable("gui.geometallurgy.metal_powder.content",
						Substance.displayName(e.getKey()), trim(e.getValue()))
						.withStyle(ChatFormatting.GRAY));
			}
		}
		for (Component line : Ingots.tooltip(stack)) {
			tooltip.accept(line);
		}
	}

	// ------------------------------------------------------------------ MolCarrier 实现

	@Override
	public double capacityMol() {
		return MOL_PER_POWDER;
	}

	/** 账从金属成分现推（单件量），不存组件 —— 见类注释与 bug#12。 */
	@Override
	public MolStock stock(ItemStack stack) {
		return PowderContent.contentOf(Ingots.metalKey(stack));
	}

	@Override
	public boolean isEmpty(ItemStack stack) {
		return stock(stack).isEmpty();
	}

	/** 粉不可回灌：机器只认空桶作产物载体，容量对粉恒为 0。 */
	@Override
	public double room(ItemStack stack) {
		return 0.0;
	}

	/**
	 * 粉的账是派生的、改不动，所以这里只回答「这件里有多少」，真正的消耗由
	 * 调用方按 {@link #consumedOnEmpty} 减计数（正数 mol 才回话）。
	 */
	@Override
	public double take(ItemStack stack, String id, double mol) {
		if (mol <= 0.0) {
			return 0.0;
		}
		return Math.min(stock(stack).of(id), mol);
	}

	/** 粉不接受灌装。 */
	@Override
	public double add(ItemStack stack, MolStock amount) {
		return 0.0;
	}

	/** 机器倒空它 = 消耗一件（而不是还一只空载具）。 */
	@Override
	public boolean consumedOnEmpty() {
		return true;
	}

	/** 显示数字：整数去尾零，小数保留两位（与桶的读法同一套）。 */
	private static String trim(double v) {
		if (v == Math.rint(v)) {
			return String.format(Locale.ROOT, "%.0f", v);
		}
		return String.format(Locale.ROOT, "%.2f", v);
	}
}
