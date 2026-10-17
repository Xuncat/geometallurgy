package com.geometallurgy.item;

import com.geometallurgy.chemical.MolStock;

import net.minecraft.world.item.ItemStack;

/**
 * 「能承载 mol 账本」的载具抽象（设计稿 §二 载体三形态）。
 *
 * <p>机器只认这个接口 + {@link ModComponents#MEASURE} 单位标签，不再硬编码某一种物品：
 * 任何实现了本接口、并打了 {@code mol} 单位标签的载具都能进 mol 机器的槽位 ——
 * 金属桶是第一个实现，将来的「保留内容的方块」「兼容槽位」各做各的实现。
 *
 * <p>五个动作就是 {@code MolStock} 账本在物品上的投影：读账、清账、扣、添、能否添。
 * 调用方不必知道实现里是 {@code Map} 组件还是别的存储。
 */
public interface MolCarrier {

	/** 本载具的 mol 容量。 */
	double capacityMol();

	/** 当前 mol 账（只读）。 */
	MolStock stock(ItemStack stack);

	/** 是否空。 */
	boolean isEmpty(ItemStack stack);

	/** 还能装多少 mol。 */
	double room(ItemStack stack);

	/** 从账里取走一种物质的 mol（按库存钳制）。@return 实际取走量 */
	double take(ItemStack stack, String id, double mol);

	/** 添一份 mol 账（总量不超容量）。@return 实际添入量 */
	double add(ItemStack stack, MolStock amount);

	/**
	 * 这个载具装得住这一整本账吗（容器材质耐不耐蚀）。
	 *
	 * <p>机器在收桶 / 灌桶之前问这一句：装不住的桶既不会把料倒进机器，也不会被灌进产物 ——
	 * 界面上会报「桶不耐蚀」，让玩家换一只。默认载具没有材质概念，一律装得住。
	 */
	default boolean holds(MolStock stock) {
		return true;
	}

	/**
	 * 机器把这件载具倒空时，是「<b>消耗掉一件</b>」，还是「<b>还一只空载具</b>」。
	 *
	 * <p>桶是后者：{@code take} 把账清掉，同一只桶原地放回。金属粉是前者：它的账由金属成分
	 * <b>现推</b>，{@code take} 改不动它，只能按「消耗一件」处理 —— 机器收料时必须把计数减一，
	 * 而不是把原样放回，否则粉越倒越多（与 bug#12「一摞共享一个组件」同类）。
	 *
	 * <p>默认 false（桶及将来「保留内容」的载具）。实现返回 true 时，{@link #stock} 必须返回
	 * <b>单件</b>的内容（不是整摞），调用方一次只消耗一件。
	 */
	default boolean consumedOnEmpty() {
		return false;
	}

	/** 这个载具是不是空载具（还没打内容、也没打单位标签）。 */
	static boolean isBlank(ItemStack stack) {
		return ModComponents.measureOf(stack) == null;
	}
}