package com.geometallurgy.vein;

import java.util.List;

import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 一个区块的虚拟矿脉分布。
 *
 * @param entries   本区块含有的矿物，1~4 种；每种带一个稀有度（充当抽取权重）
 * @param abundance 丰度，0.10~1.00。低于 1.0 时，产出会以 {@code 1 - 丰度} 的概率
 *                  退化成圆石 —— 丰度越低，挖出来的空石越多。
 */
public record BlockVeinDistribution(List<Entry> entries, float abundance) {

	/** 单一矿物条目：类型 + 本区块内该矿物的稀有度（权重）。 */
	public record Entry(OreType type, float rarity) {
	}

	public boolean isEmpty() {
		return entries.isEmpty();
	}

	public float totalRarity() {
		float total = 0F;
		for (Entry entry : entries) {
			total += entry.rarity();
		}
		return total;
	}

	/**
	 * 掷一次产出：先按稀有度加权选出一种矿物，再按丰度决定是否退化为圆石。
	 */
	public ItemStack roll(RandomSource random) {
		if (entries.isEmpty()) {
			return ItemStack.EMPTY;
		}

		float total = totalRarity();
		float pick = random.nextFloat() * total;
		Entry chosen = entries.get(entries.size() - 1);
		float accumulated = 0F;
		for (Entry entry : entries) {
			accumulated += entry.rarity();
			if (pick < accumulated) {
				chosen = entry;
				break;
			}
		}

		if (random.nextFloat() >= abundance) {
			return new ItemStack(Items.COBBLESTONE);
		}
		return chosen.type().createStack(random);
	}
}
