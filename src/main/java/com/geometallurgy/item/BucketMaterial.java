package com.geometallurgy.item;

import com.geometallurgy.chemical.ChemicalTags;
import com.geometallurgy.chemical.MolStock;

/**
 * 桶的材质 —— 化学药剂会侵蚀容器，所以「用什么装」是这套体系的一部分。
 *
 * <h2>侵蚀是怎么算的</h2>
 * 物质带一组「侵蚀标签」（酸 / 氟化物 / 氧化剂 / 碱），材质声明自己<b>耐</b>哪些标签：
 * 只要物质带了一个材质不耐的标签，这只桶就装不住它。类别不再在本类手工维护 ——
 * 一律读 {@link ChemicalTags#attackMask(String)}（酸 / 氟化物 / 氧化剂 / 碱各自从物质标签推出来），
 * 容器耐蚀性这一侧只剩 {@link #resists} 几位。
 *
 * <ul>
 *   <li>{@link #IRON}：什么标签都不耐。只装水、盐、中性料 —— 便宜，是入门桶。</li>
 *   <li>{@link #GLASS}：耐酸、耐氧化剂（盐酸、硫酸、硝酸、氯水、高锰酸钾…），
 *       但氢氟酸与强碱会吃玻璃。</li>
 *   <li>{@link #LEAD}：耐酸、耐氟化物（氢氟酸、氟化钠、四氟化硅都要用它），
 *       但硝酸会溶铅。</li>
 *   <li>{@link #CERAMIC}：四类全耐，万能桶，做起来最贵。</li>
 * </ul>
 *
 * <p>声明顺序就是「从便宜到贵」，{@link #cheapestFor(String)} 依赖这个顺序。
 */
public enum BucketMaterial {

	// ------------------------------------------------------------------ 材质
	IRON("iron", 0x8E8E96, 0),
	GLASS("glass", 0xC7E4E8, Tag.ACID | Tag.OXIDIZER),
	LEAD("lead", 0x6E7480, Tag.ACID | Tag.FLUORIDE),
	CERAMIC("ceramic", 0xD9CFBE, Tag.ACID | Tag.FLUORIDE | Tag.OXIDIZER | Tag.BASE);

	/**
	 * 侵蚀标签。取值直接引用 {@link ChemicalTags} 的类别位，桶耐蚀与物质分类从此只有一份真值。
	 * 放在接口里是为了让枚举常量参数能引用它们（常量变量）。
	 */
	public interface Tag {
		/** 酸（含酸性盐、酸性氧化物）。 */
		int ACID = ChemicalTags.ATTACK_ACID;
		/** 氟化物 —— 会啃玻璃。 */
		int FLUORIDE = ChemicalTags.ATTACK_FLUORIDE;
		/** 氧化剂。 */
		int OXIDIZER = ChemicalTags.ATTACK_OXIDIZER;
		/** 碱（含碱性盐、碱性氧化物）。 */
		int BASE = ChemicalTags.ATTACK_BASE;
	}

	private final String id;
	private final int color;
	private final int resists;

	BucketMaterial(String id, int color, int resists) {
		this.id = id;
		this.color = color;
		this.resists = resists;
	}

	public String id() {
		return this.id;
	}

	/** 外壳显示色（贴图生成与界面提示用）。 */
	public int color() {
		return this.color;
	}

	/** 物品名的键，如 {@code item.geometallurgy.bucket.material.lead}。 */
	public String nameKey() {
		return "item.geometallurgy.bucket.material." + this.id;
	}

	/** 这种材质装得住这种物质吗。 */
	public boolean holds(String chemicalId) {
		return (attackMask(chemicalId) & ~this.resists) == 0;
	}

	/** 这种材质装得住这一整本账吗。 */
	public boolean holds(MolStock stock) {
		if (stock == null || stock.isEmpty()) {
			return true;
		}
		for (String id : stock.view().keySet()) {
			if (!holds(id)) {
				return false;
			}
		}
		return true;
	}

	/** 某种物质带的全部侵蚀标签（0 = 温和）。类别来自 {@link ChemicalTags}。 */
	public static int attackMask(String chemicalId) {
		return ChemicalTags.attackMask(chemicalId);
	}

	/** 装这种物质最便宜的材质 —— 创造物品栏发药剂、配方提示都用它。 */
	public static BucketMaterial cheapestFor(String chemicalId) {
		for (BucketMaterial material : values()) {
			if (material.holds(chemicalId)) {
				return material;
			}
		}
		return CERAMIC;
	}

	/** 按 id 找材质；不认识返回 {@link #IRON}。 */
	public static BucketMaterial byId(String id) {
		for (BucketMaterial material : values()) {
			if (material.id.equals(id)) {
				return material;
			}
		}
		return IRON;
	}
}
