package com.geometallurgy.item;

/**
 * 钻头材质。
 *
 * <p>三个属性各自独立，谁也替不了谁 —— 这是「钻石钻头就是全面更强」这种想法在现实里破产的地方：
 * <ul>
 *   <li><b>硬度上限</b>（莫氏）—— 挖掘等级。钻头只能啃动比它软的料，碰上更硬的矿物直接卡住不转。
 *       铁钻头（7.0）能对付绝大多数岩体，唯独石英岩、燧石（7.0）以及钻石（10.0）啃不动。</li>
 *   <li><b>韧性</b>（0~1）—— 越<b>低</b>越容易在使用中<b>直接崩碎</b>：不是慢慢磨掉，
 *       而是整根断掉。钻石钻头就是典型：硬得能啃钻石，脆得也像钻石。</li>
 *   <li><b>耐磨</b>—— 基础耐久（每次开采都要扣）。铁钻头不耐磨不行，但它耐磨；钻石正好反过来。</li>
 * </ul>
 *
 * <p>所以便宜耐操的铁钻头适合大面积开挖，贵而脆的钻石钻头只在啃硬料时才值得装上去。
 * 后续还会加模组注册的特殊合金钻头，把三项一起顶高。
 */
public enum DrillMaterial {

	/** 石钻头：只啃得动软的沉积岩，便宜、够用一会儿。 */
	STONE("stone", 3.5, 0.60, 128, 1.00),
	/** 金钻头：硬度低但很韧、磨得飞快 —— 适合低硬度矿层的快速推进。 */
	GOLD("gold", 4.5, 0.75, 64, 1.80),
	/** 铁钻头：硬度中等偏低、韧性中、耐磨高。性价比最高的一档。 */
	IRON("iron", 7.0, 0.50, 512, 1.15),
	/** 钻石钻头：硬度顶格、韧性极低、耐磨很差。能啃钻石，也容易当场断在钻石上。 */
	DIAMOND("diamond", 10.0, 0.25, 192, 1.40);

	private final String id;
	private final double maxMohs;
	private final double toughness;
	private final int durability;
	private final double speed;

	DrillMaterial(String id, double maxMohs, double toughness, int durability, double speed) {
		this.id = id;
		this.maxMohs = maxMohs;
		this.toughness = toughness;
		this.durability = durability;
		this.speed = speed;
	}

	public String id() {
		return id;
	}

	/** 能啃动的最大莫氏硬度。低于矿脉所需硬度的钻头装上就是白装。 */
	public double maxMohs() {
		return maxMohs;
	}

	/** 韧性 0~1。越低越容易被一次崩碎。 */
	public double toughness() {
		return toughness;
	}

	/** 基础耐久（耐磨度）。每次开采都要扣，具体扣多少随所挖料的硬度浮动。 */
	public int durability() {
		return durability;
	}

	/** 推进速度倍率。 */
	public double speed() {
		return speed;
	}

	/**
	 * 每次开采直接崩碎的概率。
	 *
	 * <p>与磨损是两条独立的路径：磨损是耐久见底，崩碎是<b>当场断掉</b> ——
	 * 一根崭新的钻石钻头也可能在第一块石英岩上就断成两截。
	 */
	public double breakChance() {
		return 0.02 * (1.0 - this.toughness);
	}

	/** 按 id 反查；存档里读到不认识的名字时返回 null。 */
	public static DrillMaterial byId(String id) {
		for (DrillMaterial material : values()) {
			if (material.id.equals(id)) {
				return material;
			}
		}
		return null;
	}
}
