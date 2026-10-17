package com.geometallurgy.vein;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 可被虚拟矿脉产出的矿物类型。
 *
 * <p>每种矿物自带一个「稀有度区间」。区块勘测时在该区间内随机取一个值，
 * 产出抽取时这个值充当权重 —— 数值越大越常见，越小越稀有。
 * 产出物品统一是粗矿或掉落物（粗铁、煤炭、红石粉……）。
 */
public enum OreType {

	COAL("coal", 0.10F, 0.30F, Items.COAL, 1, 3),
	IRON("iron", 0.05F, 0.10F, Items.RAW_IRON, 1, 2),
	COPPER("copper", 0.05F, 0.10F, Items.RAW_COPPER, 2, 4),
	REDSTONE("redstone", 0.01F, 0.02F, Items.REDSTONE, 2, 5),
	LAPIS("lapis", 0.01F, 0.02F, Items.LAPIS_LAZULI, 2, 5),
	GOLD("gold", 0.01F, 0.05F, Items.RAW_GOLD, 1, 2),
	EMERALD("emerald", 0.005F, 0.03F, Items.EMERALD, 1, 1),
	DIAMOND("diamond", 0.001F, 0.01F, Items.DIAMOND, 1, 1);

	private static final Map<String, OreType> BY_ID = new HashMap<>();

	static {
		for (OreType type : values()) {
			BY_ID.put(type.id, type);
		}
	}

	private final String id;
	private final float minRarity;
	private final float maxRarity;
	private final Item drop;
	private final int minCount;
	private final int maxCount;

	OreType(String id, float minRarity, float maxRarity, Item drop, int minCount, int maxCount) {
		this.id = id;
		this.minRarity = minRarity;
		this.maxRarity = maxRarity;
		this.drop = drop;
		this.minCount = minCount;
		this.maxCount = maxCount;
	}

	public String id() {
		return id;
	}

	public float minRarity() {
		return minRarity;
	}

	public float maxRarity() {
		return maxRarity;
	}

	public Item drop() {
		return drop;
	}

	public int minCount() {
		return minCount;
	}

	public int maxCount() {
		return maxCount;
	}

	public String translationKey() {
		return "ore.geometallurgy." + id;
	}

	public Component displayName() {
		return Component.translatable(translationKey());
	}

	/** 按数量区间掷出一份该矿物的产出。 */
	public ItemStack createStack(RandomSource random) {
		int count = (minCount == maxCount)
				? minCount
				: minCount + random.nextInt(maxCount - minCount + 1);
		return new ItemStack(drop, count);
	}

	public static OreType byId(String id) {
		return BY_ID.get(id);
	}
}
