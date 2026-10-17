package com.geometallurgy.proto;

import java.util.Map;

/**
 * 杂质定义。
 *
 * <p>每种杂质给一个权重区间，掷骰时在该区间内取一个基准值，再叠加一层高斯噪声，
 * 最后按权重把「杂质总量」分配给被选中的那几种。
 *
 * <p>这些杂质大多是有害元素（磷、硫、砷）或可以回收的有价元素（锰、钛、钒），
 * 后面的粉碎 / 焙烧 / 湿法冶金环节会处理它们。
 *
 * <h2>为什么要标「氧化性」</h2>
 * 杂质在矿石里以什么形态存在，决定了碳热还原时它会不会被碳夺氧：
 * <ul>
 *   <li>{@code oxidic = true} —— 以<b>氧化物</b>形态存在（磷灰石里的 P₂O₅、软锰矿里的 MnO、
 *       金红石里的 TiO₂、钒矿里的 V₂O₅）。碳在高温下会把氧夺走，于是这部分杂质被消去
 *       —— 详见 {@link com.geometallurgy.metal.Metallurgy} 的碳热还原一节。</li>
 *   <li>{@code oxidic = false} —— 以硫化物 / 砷化物 / 单质形态存在（黄铁矿的硫、辉砷钴矿的砷、
 *       辉锑矿的锑）。碳对它们无能为力，只能原样留在金属里。</li>
 * </ul>
 * 这个标记只影响碳系还原剂的炉次，金属还原剂与电解不受影响。
 */
public final class ImpurityDef {

	private ImpurityDef() {
	}

	/**
	 * @param minWeight 权重下限
	 * @param maxWeight 权重上限
	 * @param oxidic    是否以氧化态存在（碳热还原时会被夺氧消去）
	 */
	public record Entry(double minWeight, double maxWeight, boolean oxidic) {
	}

	public static final Map<String, Entry> ALL = Map.ofEntries(
			Map.entry("phosphorus", new Entry(2.0, 5.0, true)),
			Map.entry("sulfur", new Entry(3.0, 6.0, false)),
			Map.entry("manganese", new Entry(2.0, 4.0, true)),
			Map.entry("arsenic", new Entry(1.0, 3.0, false)),
			Map.entry("antimony", new Entry(1.0, 3.0, false)),
			Map.entry("titanium", new Entry(1.0, 3.0, true)),
			Map.entry("vanadium", new Entry(1.0, 2.0, true)));

	public static Entry get(String id) {
		return ALL.get(id);
	}

	/** 这种杂质在碳热还原时会不会被夺氧消去。表里没有的按「不会」处理。 */
	public static boolean isOxidic(String id) {
		Entry entry = ALL.get(id);
		return entry != null && entry.oxidic();
	}
}
