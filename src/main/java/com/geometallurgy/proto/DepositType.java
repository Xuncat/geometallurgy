package com.geometallurgy.proto;

import java.util.List;
import java.util.Set;

import com.geometallurgy.geology.RockType;

/**
 * 矿床参数：一种虚拟原矿「在什么岩体里、以多大的概率出现、以多高的丰度、长成什么形状、带哪些杂质」。
 *
 * <p>本轮（ore balance）把两条此前糊在一起的轴拆开，见 {@link OreTier}：
 * <ul>
 *   <li>{@link OreTier.Spawn spawn} —— 一个候选矿体单元里这种矿物出现的概率。<b>只碰频率</b>，
 *       不参与丰度与几何计算。</li>
 *   <li>{@link OreTier.Grade grade} —— 矿体中心处该矿物占的体积分数区间。<b>只碰浓度</b>。</li>
 *   <li>{@link OreTier.Body body} —— 矿体规模倍率。<b>只碰体积</b>。
 *       实际延伸 = {@code extent × body.extentScale()}，再乘形状自身的半轴比例。</li>
 * </ul>
 * 三个字段彼此独立：改出现概率不会改变矿体大小，反之亦然。
 *
 * <p>其余字段照旧：
 * <ul>
 *   <li>{@code richness} —— 矿物<b>本身</b>的有价成分含量，直接按化学式算：
 *       方铅矿 PbS 是 86.6% 铅，黄铜矿 CuFeS₂ 是 34.6% 铜，自然金是 100%。</li>
 *   <li>{@code shape / extent} —— 矿体形状与基准规模（extent 是主轴半轴，单位：方块）。</li>
 * </ul>
 *
 * @param spawn        出现概率档（只碰频率）
 * @param grade        矿体中心丰度档（只碰浓度）
 * @param body         矿体规模档（只碰体积）
 * @param richness     矿物自身的有价成分含量（0~1）
 * @param valuable     有价成分的物质 id（{@code iron} / {@code copper} / {@code gold}……）
 * @param impurityPool 本矿可能夹带的杂质池，出现哪几种、各占多少由掷骰决定
 * @param hostFamilies 只在这些岩体族中出现 —— 这就是「不同基岩产出不同矿物」的根据
 * @param shape        矿体形状
 * @param extent       矿体基准规模：主轴半轴（方块），实际值再乘 {@code body} 的倍率
 * @param minDensity   矿床致密程度下限（与「矿物密度 g/cm³」不是一回事）
 * @param maxDensity   矿床致密程度上限
 * @param genesis      成因标签：sedimentary / volcanic / plutonic / metamorphic
 */
public record DepositType(
		OreTier.Spawn spawn,
		OreTier.Grade grade,
		OreTier.Body body,
		double richness,
		String valuable,
		List<String> impurityPool,
		Set<RockType.Family> hostFamilies,
		OreBodyShape shape,
		double extent,
		double minDensity,
		double maxDensity,
		String genesis) {

	/**
	 * 伴生杂质占「提取出的金属」的比例区间：难分离，10%~30% 不等。
	 *
	 * <p>这一条与矿物本身干不干净无关 —— 就算挖到的是自然金，选出来的金里也照样混着
	 * 一两成的杂质，得靠后面的冶金环节处理。
	 */
	public static final double MIN_IMPURITY = 0.10;
	public static final double MAX_IMPURITY = 0.30;

	/** 一个候选单元里本矿物出现的概率（转发 {@link #spawn}）。 */
	public double spawnChance() {
		return spawn.chance();
	}

	/** 矿体中心丰度下限（体积分数）。 */
	public double minAbundance() {
		return grade.min();
	}

	/** 矿体中心丰度上限。 */
	public double maxAbundance() {
		return grade.max();
	}

	/** 矿体中心丰度的中位值。 */
	public double avgAbundance() {
		return grade.mid();
	}

	/** 实际矿体延伸（方块）= 基准 extent × 规模档倍率。 */
	public double scaledExtent() {
		return extent * body.extentScale();
	}

	/** 该矿床能否出现在某种岩体中。 */
	public boolean canOccurIn(RockType rock) {
		return hostFamilies.contains(rock.family());
	}

	public String genesisTranslationKey() {
		return "genesis.geometallurgy." + genesis;
	}
}
