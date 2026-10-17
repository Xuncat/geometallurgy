package com.geometallurgy.item;

import net.minecraft.world.item.Item;

/**
 * 钻头：装进流体碎矿挖掘机的那一格。
 *
 * <p>它是<b>消耗品</b>，走的是标准耐久条（{@link Item.Properties#durability(int)}），
 * 所以槽位里那条耐久条本身就是信息 —— 玩家不必再点开什么界面看剩余寿命。
 * 具体属性（硬度上限、韧性、耐磨）全在 {@link DrillMaterial} 上。
 */
public class DrillBitItem extends Item {

	private final DrillMaterial material;

	public DrillBitItem(DrillMaterial material, Properties properties) {
		super(properties);
		this.material = material;
	}

	public DrillMaterial material() {
		return this.material;
	}
}
