package com.geometallurgy.blockentity;

import java.util.Set;

import com.geometallurgy.chemical.ChemicalTags;
import com.geometallurgy.metal.Metallurgy;
import com.geometallurgy.metal.Reagent;
import com.geometallurgy.metal.SmeltRoute;

import net.minecraft.world.item.ItemStack;

/**
 * 一台冶炼炉的静态参数。
 *
 * <p>三台炉子的方块实体是<b>同一个类</b>，差别全在这个记录里：走哪条化学路线、烧多少电、
 * 一个炉次要多久、辅助剂是哪一类。这样三台机器的循环、界面、存档逻辑只有一份，
 * 日后改数值也只用改这一张表。
 *
 * @param id            机器 id，用来取方块名（{@code block.geometallurgy.<id>}）
 * @param route         化学路线
 * @param energyPerTick <b>基准功率</b>（FE/t）：逐配方功率的锚点，也是标签兜底配方的功率。
 *                      真正扣的电是配方自带的绝对功率（{@code SmeltRecipe.powerFePerTick}），
 *                      见 {@link SmelterBlockEntity#currentDraw()}
 * @param maxDraw       单刻耗电上限
 * @param capacity      内部电缓冲
 * @param ticksPerBatch 一个炉次的刻数
 * @param reagent       辅助剂种类
 */
public record SmelterProfile(
		String id,
		SmeltRoute route,
		long energyPerTick,
		long maxDraw,
		long capacity,
		int ticksPerBatch,
		ReagentKind reagent) {

	/** 这台炉子往槽里收什么。 */
	public enum ReagentKind {
		/**
		 * 还原炉：<b>碳系永远可用</b>（煤炭 / 木炭 / 碳粉 / 碳棒，见 {@link Reagent#isCarbonFamily}），
		 * 金属只是可选的替代 —— 还原不刚需金属（用户 2026-10-17 口径：置换才需要金属）；
		 * <b>桶装 CO / H₂ 也是合法还原剂</b>（用户 2026-10-18 口径：气体载具就用桶），
		 * 按 mol 计当量，抽走气体、空桶留下（见 {@link Reagent#isGasReductant}）。
		 */
		REDUCTANT,
		/**
		 * 置换炉：<b>只有它</b>要求金属，且必须比目标金属活泼。
		 */
		DISPLACEMENT,
		/** 电解炉：电极，按耐久消耗。 */
		ELECTRODE;

		/**
		 * 这个槽收不收这件东西 —— 三台炉子的准入规则只有这一处。
		 *
		 * <p>还原 / 置换的分工就是这一条：还原路认碳系（金属可选），置换路认金属。
		 */
		public boolean accepts(ItemStack stack) {
			return switch (this) {
				case REDUCTANT -> Reagent.isReductant(stack);
				case DISPLACEMENT -> Reagent.metalOf(stack) != null;
				case ELECTRODE -> Reagent.isElectrode(stack);
			};
		}
	}

	/**
	 * 这台炉子按物质标签收不收料 —— 中转 {@link SmeltRoute#acceptsTagged(Set)}。
	 *
	 * <p>还原炉因此对任何氧化物通用放行（{@link ChemicalTags.Tag#OXIDE}，难还原氧化物除外），
	 * 不必再为每种氧化物往 {@link com.geometallurgy.metal.Metallurgy} 的路线表里补一行。
	 */
	public boolean acceptsTagged(Set<ChemicalTags.Tag> tags) {
		return this.route.acceptsTagged(tags);
	}

	/**
	 * 外部单刻最多能往这台炉子的缓冲里推多少电。
	 *
	 * <p><b>口径：{@link #maxDraw} 是唯一权威。</b>一台炉子单刻最多花 {@code maxDraw} 电，
	 * 就没有理由允许邻机用更快的速度往里灌 —— 缓冲是拿来抹平「来料 / 电网抖动」的，
	 * 不是拿来突破本机功率额定的。此前这里写 {@code energyPerTick × 4}，三台炉子都高出
	 * maxDraw（还原 160>96、电解 320>300、置换 64>40），电解 320>300 最刺眼：
	 * 外部按 320 FE/t 充、本机只花 300 FE/t，入账与出账的额定永远对不上（bug#5）。
	 * 现改为直接返回 {@link #maxDraw}，两者由同一个数定义，不再各写各的。
	 *
	 * <p>未改任何功率 / 容量玩法数值：{@code energyPerTick}、{@code maxDraw}、{@code capacity}
	 * 一字未动，只是把进电上限收口到既有额度之内。
	 */
	public long insertCap() {
		return this.maxDraw;
	}

	/**
	 * 这台炉子要不要电。
	 *
	 * <p>三炉里只有<b>置换炉</b>不用电 —— 湿法置换是金属之间的电子转移，常温常压就能走，
	 * 代价是吃掉一种比目标更活泼的金属。还原炉（碳热/金属热）与电解炉都要电，
	 * 电解更是三条路里最费电的。不用电的炉子既没有电缓冲，也不接受外部推电。
	 */
	public boolean usesPower() {
		return this.route != SmeltRoute.DISPLACEMENT;
	}

	// ------------------------------------------------------------------ 三台机器
	//
	// 电耗照「火法便宜、电解极贵、湿法几乎不要钱」定：置换炉省下的电，是用更贵的金属还原剂换来的。

	/**
	 * 还原炉：碳热/金属热还原。基准 80 FE/t（旧 40），单刻上限 192（旧 96）。
	 *
	 * <p>但真正扣多少电由<b>配方</b>说了算：还原路线里那些「碳既是还原剂又是热源」的
	 * 传统火法（高炉炼铁、反射炉炼锡 / 铜 / 铅、自然金属熔炼）功率写 0 —— 它们一度电都不吃，
	 * 成本全压在还原剂当量上。电炉 / 电弧 / 焙烧那一路保留电力，量级就是这里的基准与上限。
	 */
	public static final SmelterProfile REDUCTION = new SmelterProfile(
			"reduction_furnace", SmeltRoute.REDUCTION,
			(long) Metallurgy.REDUCTION_REFERENCE_FE_PER_TICK, 192L, 24_000L, 100,
			ReagentKind.REDUCTANT);

	/**
	 * 电解炉：熔融电解。基准 160 FE/t（旧 80），单刻上限 600（旧 300）。
	 *
	 * <p>熔盐档的每条路线都自带绝对功率（{@code Metallurgy} 的路线表），本条基准只服务
	 * 标签兜底配方与文档锚点；溶液档则是<b>逐反应</b>的 {@code FE/mol}
	 * （见 {@code MolBalance.Reaction#fePerMol()}），跟这个基准无关。
	 */
	public static final SmelterProfile ELECTROLYSIS = new SmelterProfile(
			"electrolysis_furnace", SmeltRoute.ELECTROLYSIS,
			(long) Metallurgy.ELECTROLYSIS_REFERENCE_FE_PER_TICK, 600L, 96_000L, 120,
			ReagentKind.ELECTRODE);

	/** 置换炉：湿法置换。几乎不耗电，代价是吃掉一种比目标金属更活泼的金属。 */
	public static final SmelterProfile DISPLACEMENT = new SmelterProfile(
			"displacement_furnace", SmeltRoute.DISPLACEMENT,
			16L, 40L, 3_000L, 60, ReagentKind.DISPLACEMENT);

	/** 三台机器，顺序固定，供注册与界面索引复用。 */
	public static final SmelterProfile[] ALL = {REDUCTION, ELECTROLYSIS, DISPLACEMENT};
}
