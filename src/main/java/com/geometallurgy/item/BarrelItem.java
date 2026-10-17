package com.geometallurgy.item;

import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import com.geometallurgy.block.BarrelBlock;
import com.geometallurgy.chemical.MolStock;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

/**
 * 储罐方块的物品形态：悬浮提示里列出罐内的账。
 *
 * <p>方块的账由 {@link com.geometallurgy.blockentity.BarrelBlockEntity} 作为<b>隐式组件</b>
 * 暴露出来（{@code collectImplicitComponents}），挖掉储罐时随掉落物一起带走。
 * 这里只负责把它读回来显示 —— 与满桶的悬浮提示同一套口径：
 * 总量 mol / 折合桶数 / 质量 + 逐项成分；空罐只写「空」。
 *
 * <p>罐子材质装不住里面的内容（组件被外部改过、或跨版本迁移）时额外报一行，红字。
 */
public class BarrelItem extends BlockItem {

	public BarrelItem(Block block, Properties properties) {
		super(block, properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
			Consumer<Component> tooltip, TooltipFlag flag) {
		super.appendHoverText(stack, context, display, tooltip, flag);

		Map<String, Double> map = stack.get(ModComponents.TANK_STOCK);
		if (map == null || map.isEmpty()) {
			tooltip.accept(Component.translatable("gui.geometallurgy.barrel.content_empty")
					.withStyle(ChatFormatting.GRAY));
			return;
		}

		MolStock stock = MolStock.empty();
		for (Map.Entry<String, Double> e : map.entrySet()) {
			stock.add(e.getKey(), e.getValue());
		}
		if (this.getBlock() instanceof BarrelBlock barrel && !barrel.material().holds(stock)) {
			tooltip.accept(Component.translatable("gui.geometallurgy.barrel_corrosion")
					.withStyle(ChatFormatting.RED));
		}

		tooltip.accept(Component.translatable("gui.geometallurgy.barrel.content_total",
				trim(stock.totalMol()), trim(stock.totalB()), trim(stock.massGrams()))
				.withStyle(ChatFormatting.GRAY));
		// 按相态分组：气 / 液 / 固各一行，行内才是同一相的混合物（设计稿 §4.1）
		for (var line : MolReadout.lines(stock, "gui.geometallurgy.barrel.content_entry")) {
			tooltip.accept(line.withStyle(ChatFormatting.DARK_GRAY));
		}
	}

	/** 显示数字：整数去尾零，小数保留两位（与金属桶同一套）。 */
	private static String trim(double v) {
		if (v == Math.rint(v)) {
			return String.format(Locale.ROOT, "%.0f", v);
		}
		return String.format(Locale.ROOT, "%.2f", v);
	}
}
