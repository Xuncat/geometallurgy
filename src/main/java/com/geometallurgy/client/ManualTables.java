package com.geometallurgy.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.geometallurgy.blockentity.AbstractReactorBlockEntity;
import com.geometallurgy.blockentity.BatteryBlockEntity;
import com.geometallurgy.blockentity.CentrifugeBlockEntity;
import com.geometallurgy.blockentity.CoalGeneratorBlockEntity;
import com.geometallurgy.blockentity.CrusherBlockEntity;
import com.geometallurgy.blockentity.ElectrolysisRole;
import com.geometallurgy.blockentity.FaceRole;
import com.geometallurgy.blockentity.FluidVeinExtractorBlockEntity;
import com.geometallurgy.blockentity.MagneticSeparatorBlockEntity;
import com.geometallurgy.blockentity.MixerRole;
import com.geometallurgy.blockentity.PortRole;
import com.geometallurgy.blockentity.SmelterBlockEntity;
import com.geometallurgy.blockentity.SmelterProfile;
import com.geometallurgy.blockentity.VeinExtractorBlockEntity;
import com.geometallurgy.blockentity.VesselRole;
import com.geometallurgy.chemical.Chemical;
import com.geometallurgy.chemical.Machine;
import com.geometallurgy.chemical.MolBalance;
import com.geometallurgy.chemical.MolRecipes;
import com.geometallurgy.chemical.ProductionCost;
import com.geometallurgy.chemical.ReactionGraph;
import com.geometallurgy.manual.TechTree;
import com.geometallurgy.metal.Metallurgy;
import com.geometallurgy.metal.SmeltRecipe;
import com.geometallurgy.metal.SmeltRoute;
import com.geometallurgy.proto.OreBodyShape;
import com.geometallurgy.proto.OreMineral;
import com.geometallurgy.proto.OreTier;
import com.geometallurgy.slurry.SlurryPart;
import com.geometallurgy.vein.EpochClock;

import net.minecraft.network.chat.Component;

/**
 * 任务书里<b>由代码现算</b>的那几张表 —— 一个字都不许手抄。
 *
 * <table border="1">
 *   <tr><th>表</th><th>数据源</th></tr>
 *   <tr><td>34 种矿物表（名字/形状/稀有度星级/主产出/伴生）</td>
 *       <td>{@link OreMineral#values()}（形状、主成分、伴生相）+ {@link OreTier}（S/G/B 档位与星级）</td></tr>
 *   <tr><td>档位表 / 形状表</td><td>{@link OreTier.Spawn}·{@link OreTier.Grade}·{@link OreTier.Body}、{@link OreBodyShape}</td></tr>
 *   <tr><td>纪元表</td><td>{@link EpochClock} 的 public 常量</td></tr>
 *   <tr><td>79 种物质表</td><td>{@link Chemical#ids()} + {@link Chemical#of(String)}</td></tr>
 *   <tr><td>107 条反应表</td><td>{@link MolRecipes#all()}（系数/类型/机器/速率/电耗）</td></tr>
 *   <tr><td>溶液电解单价表</td><td>{@link MolRecipes#all()} 里 {@code Kind.ELECTROLYSIS} 的 {@code fePerMol()}</td></tr>
 *   <tr><td>冶炼逐配方电耗表</td><td>{@link Metallurgy#routable()} × {@link Metallurgy#recipe(SlurryPart, SmeltRoute)} 的 {@code powerFePerTick}</td></tr>
 *   <tr><td>耗电一览</td><td>{@link ProductionCost#MACHINE_STEP} + 各机器方块实体的 public 常量 + 三台炉子的 {@link SmelterProfile}</td></tr>
 *   <tr><td>机器参数表（输入/输出/耗电/面角色/槽位）</td>
 *       <td>输入输出取 {@link ReactionGraph#allEdges()}，耗电 / 槽位取机器方块实体的 public 常量，
 *           面角色取 {@link VesselRole}·{@link MixerRole}·{@link ElectrolysisRole}·{@link PortRole} 的取值</td></tr>
 *   <tr><td>反应条件表</td><td>{@link Machine#defaultsFor(MolBalance.Kind)}</td></tr>
 * </table>
 *
 * <p>数值格式化只在这里做（{@link #num(double)}）：书里出现的每个数字都来自上面的来源，
 * 没有一处是照着文档抄的 —— 改了常量，下一次打开任务书就是新的。
 */
public final class ManualTables {

	private ManualTables() {
	}

	// ------------------------------------------------------------------ 分页派发表

	/**
	 * 这一页除了语言文件里的正文之外，还要追加哪些表。
	 *
	 * @param chapterId  章 id（{@code preface / industry / chem / appendix}）
	 * @param entryId    条 id
	 * @param anchorBase 这一页的锚点前缀（= {@code chapter.entry}），表的锚点由它派生
	 */
	public static List<ManualDoc.Block> extra(String chapterId, String entryId, String anchorBase) {
		List<ManualDoc.Block> out = new ArrayList<>();
		switch (chapterId + "." + entryId) {
			case "preface.intro" -> add(out, anchorBase, epochTable());
			case "preface.deposit" -> add(out, anchorBase, shapeTable());
			case "preface.abundance" -> add(out, anchorBase, tierTable());
			case "preface.ores" -> add(out, anchorBase, oreTable());
			case "industry.flow" -> add(out, anchorBase, machineSummaryTable());
			case "chem.routes" -> add(out, anchorBase, smeltTable());
			case "chem.chemistry" -> add(out, anchorBase, electrolysisPriceTable());
			case "chem.kinetics" -> add(out, anchorBase, chemistryConstantsTable());
			case "chem.roasting" -> add(out, anchorBase, kindMachineTable());
			// 附录的物质 / 反应不再是一张挤在一页里的大表（bug#47）：条目页只留公式说明与
			// 常用度排行，逐条正文由 ManualAppendix 展开成一页一条（见 ManualDoc.build）。
			case "appendix.substances" -> out.addAll(ManualAppendix.coverSubstances());
			case "appendix.reactions" -> out.addAll(ManualAppendix.coverReactions());
			case "appendix.power" -> add(out, anchorBase, powerTable());
			case "appendix.techtree" -> out.add(new ManualDoc.Link(anchorBase + "#open",
					Component.translatable("manual.geometallurgy.techtree_entry"), "tech"));
			default -> {
				Machine machine = machineOf(entryId);
				if (machine != null) {
					add(out, anchorBase, machineSpec(machine));
				} else if (specialOf(entryId) != null) {
					add(out, anchorBase, specialSpec(specialOf(entryId)));
				}
			}
		}
		return out;
	}

	private static void add(List<ManualDoc.Block> out, String anchorBase, ManualDoc.Table table) {
		out.add(table);
	}

	// ------------------------------------------------------------------ 前言：矿脉

	/** 纪元时钟的常量。 */
	public static ManualDoc.Table epochTable() {
		List<ManualDoc.Row> rows = List.of(
				row("manual.geometallurgy.spec.epoch.day", num(EpochClock.TICKS_PER_DAY) + " 刻"),
				row("manual.geometallurgy.spec.epoch.days", num(EpochClock.DAYS_PER_EPOCH) + " 游戏日"),
				row("manual.geometallurgy.spec.epoch.period", num(EpochClock.TICKS_PER_EPOCH) + " 刻"));
		return new ManualDoc.Table("preface.intro#epoch",
				Component.translatable("manual.geometallurgy.table.epoch.caption"),
				List.of(Component.translatable("manual.geometallurgy.table.epoch.item"),
						Component.translatable("manual.geometallurgy.table.epoch.value")),
				rows, new int[] { 40, 60 });
	}

	/** 三轴档位：S（出现概率）/ G（中心丰度）/ B（矿体规模）。 */
	public static ManualDoc.Table tierTable() {
		List<ManualDoc.Row> rows = new ArrayList<>();
		for (OreTier.Spawn spawn : OreTier.Spawn.values()) {
			rows.add(row(Component.literal("S" + (spawn.ordinal() + 1)),
					Component.literal(num(spawn.chance() * 100.0) + "%")));
		}
		for (OreTier.Grade grade : OreTier.Grade.values()) {
			rows.add(row(Component.literal("G" + (grade.ordinal() + 1)),
					Component.literal(num(grade.min() * 100.0) + "% ~ " + num(grade.max() * 100.0) + "%")));
		}
		for (OreTier.Body body : OreTier.Body.values()) {
			rows.add(row(Component.literal("B" + (body.ordinal() + 1)),
					Component.literal("×" + num(body.extentScale()))));
		}
		return new ManualDoc.Table("preface.abundance#tiers",
				Component.translatable("manual.geometallurgy.table.tier.caption"),
				List.of(Component.translatable("manual.geometallurgy.table.tier.axis"),
						Component.translatable("manual.geometallurgy.table.tier.value")),
				rows, new int[] { 30, 70 });
	}

	/** 七种矿体形状。 */
	public static ManualDoc.Table shapeTable() {
		List<ManualDoc.Row> rows = new ArrayList<>();
		for (OreBodyShape shape : OreBodyShape.values()) {
			rows.add(new ManualDoc.Row(List.of(
					Component.translatable(shape.translationKey()),
					Component.literal(num(shape.uRatio()) + " : " + num(shape.vRatio()) + " : " + num(shape.wRatio())
							+ (shape.cylinder() ? "（柱面）" : "")),
					Component.translatable("manual.geometallurgy.orientation." + shape.orientation().name().toLowerCase(Locale.ROOT)),
					Component.literal(num(shape.falloff()))), shape.id()));
		}
		return new ManualDoc.Table("preface.deposit#shapes",
				Component.translatable("manual.geometallurgy.table.shape.caption"),
				List.of(Component.translatable("manual.geometallurgy.table.shape.name"),
						Component.translatable("manual.geometallurgy.table.shape.axes"),
						Component.translatable("manual.geometallurgy.table.shape.orientation"),
						Component.translatable("manual.geometallurgy.table.shape.falloff")),
				rows, new int[] { 22, 30, 26, 22 });
	}

	/** 34 种矿物：名字 / 形状 / 稀有度星级 / 主产出 / 伴生。 */
	public static ManualDoc.Table oreTable() {
		List<ManualDoc.Row> rows = new ArrayList<>();
		for (OreMineral mineral : OreMineral.values()) {
			List<Component> companions = new ArrayList<>();
			for (String id : mineral.companions().keySet()) {
				companions.add(Component.translatable("substance.geometallurgy." + id));
			}
			String tierCode = "S" + (mineral.deposit().spawn().ordinal() + 1)
					+ "·G" + (mineral.deposit().grade().ordinal() + 1)
					+ "·B" + (mineral.deposit().body().ordinal() + 1);
			rows.add(new ManualDoc.Row(List.of(
					Component.translatable(mineral.translationKey()),
					Component.translatable(mineral.shape().translationKey()),
					Component.literal(starText(mineral) + " " + tierCode),
					Component.translatable("substance.geometallurgy." + mineral.valuable()),
					companions.isEmpty() ? Component.literal("—") : join(companions)),
					mineral.id()));
		}
		return new ManualDoc.Table("preface.ores#table",
				Component.translatable("manual.geometallurgy.table.ore.caption"),
				List.of(Component.translatable("manual.geometallurgy.table.ore.name"),
						Component.translatable("manual.geometallurgy.table.ore.shape"),
						Component.translatable("manual.geometallurgy.table.ore.rarity"),
						Component.translatable("manual.geometallurgy.table.ore.valuable"),
						Component.translatable("manual.geometallurgy.table.ore.companion")),
				rows, new int[] { 20, 16, 24, 18, 22 });
	}

	/**
	 * 稀有度星级：只用 {@link OreTier} 的两轴算 —— {@code 出现概率 × 中心丰度中值}。
	 *
	 * <p>最常见的（S1×G1）得 1 星，最稀有的（S5×G5）得 5 星，中间按对数刻度分档。
	 * 这是<b>唯一的星级口径</b>，书里与这里不会漂移。
	 */
	public static int rarityStars(OreMineral mineral) {
		double p = mineral.deposit().spawn().chance() * mineral.deposit().grade().mid();
		double best = OreTier.Spawn.S1.chance() * OreTier.Grade.G1.mid();
		double ratio = Math.log(best / p) / Math.log(OreTier.RARITY_SPREAD);
		int stars = 1 + (int) Math.floor(ratio * 4.999);
		return Math.clamp(stars, 1, 5);
	}

	/** 星级 → ★/☆ 串。 */
	public static String rarityStars(int stars) {
		return "★".repeat(stars) + "☆".repeat(5 - stars);
	}

	/** 某矿的星级串。 */
	public static String starText(OreMineral mineral) {
		return rarityStars(rarityStars(mineral));
	}

	// ------------------------------------------------------------------ 工业化：机器

	/** 条 id → 桥接图里的机器；不是桥接机器返回 {@code null}。 */
	private static Machine machineOf(String entryId) {
		return switch (entryId) {
			case "vein_extractor" -> Machine.VEIN_EXTRACTOR;
			case "crusher" -> Machine.CRUSHER;
			case "separator" -> Machine.MAGNETIC_SEPARATOR;
			case "centrifuge" -> Machine.CENTRIFUGE;
			case "vessel" -> Machine.REACTION_VESSEL;
			case "mixer" -> Machine.MIXER;
			case "reduction" -> Machine.REDUCTION_FURNACE;
			case "electrolysis" -> Machine.ELECTROLYSIS_FURNACE;
			default -> null;
		};
	}

	/** 没有 {@link Machine} 枚举值的几台机器（发电机 / 蓄电池 / 流体挖掘机 / 置换炉）。 */
	private static String specialOf(String entryId) {
		return switch (entryId) {
			case "generator", "battery", "fluid_extractor", "displacement" -> entryId;
			default -> null;
		};
	}

	/** 工艺总览：每台机器的耗电与「进什么、出什么」。 */
	public static ManualDoc.Table machineSummaryTable() {
		List<ManualDoc.Row> rows = new ArrayList<>();
		for (Machine machine : Machine.values()) {
			rows.add(machineRow(machine));
		}
		rows.add(new ManualDoc.Row(List.of(
				Component.translatable("block.geometallurgy.coal_generator"),
				Component.literal("输出 " + num(CoalGeneratorBlockEntity.ENERGY_PER_TICK) + " FE/t"),
				Component.translatable("manual.geometallurgy.machine.coal_generator.flow")), "coal_generator"));
		rows.add(new ManualDoc.Row(List.of(
				Component.translatable("block.geometallurgy.fluid_vein_extractor"),
				Component.literal(num(FluidVeinExtractorBlockEntity.ENERGY_PER_TICK) + " FE/t"),
				Component.translatable("manual.geometallurgy.machine.fluid_extractor.flow")), "fluid_vein_extractor"));
		rows.add(new ManualDoc.Row(List.of(
				Component.translatable("block.geometallurgy.displacement_furnace"),
				Component.literal("0 FE/t"),
				Component.translatable("manual.geometallurgy.machine.displacement.flow")), "displacement_furnace"));
		return new ManualDoc.Table("industry.flow#machines",
				Component.translatable("manual.geometallurgy.table.machine.caption"),
				List.of(Component.translatable("manual.geometallurgy.table.machine.name"),
						Component.translatable("manual.geometallurgy.table.machine.power"),
						Component.translatable("manual.geometallurgy.table.machine.flow")),
				rows, new int[] { 24, 20, 56 });
	}

	private static ManualDoc.Row machineRow(Machine machine) {
		String blockKey = blockKeyOf(machine);
		Set<String> in = new LinkedHashSet<>();
		Set<String> outSet = new LinkedHashSet<>();
		for (ReactionGraph.Edge edge : ReactionGraph.allEdges()) {
			if (!edge.machines().contains(machine)) {
				continue;
			}
			in.addAll(edge.reactants());
			outSet.addAll(edge.products());
		}
		Component flow = Component.literal(list(in) + " → " + list(outSet));
		return new ManualDoc.Row(List.of(Component.translatable(blockKey),
				Component.literal(powerText(machine)), flow), machine.name().toLowerCase(Locale.ROOT));
	}

	/** 一台机器的参数表：输入 / 输出 / 耗电 / 面角色 / 槽位。 */
	public static ManualDoc.Table machineSpec(Machine machine) {
		String blockKey = blockKeyOf(machine);
		Set<String> in = new LinkedHashSet<>();
		Set<String> outSet = new LinkedHashSet<>();
		for (ReactionGraph.Edge edge : ReactionGraph.allEdges()) {
			if (edge.machines().contains(machine)) {
				in.addAll(edge.reactants());
				outSet.addAll(edge.products());
			}
		}
		List<ManualDoc.Row> rows = new ArrayList<>();
		rows.add(row("manual.geometallurgy.spec.input", list(in)));
		rows.add(row("manual.geometallurgy.spec.output", list(outSet)));
		rows.add(row("manual.geometallurgy.spec.power", powerText(machine)));
		rows.add(row("manual.geometallurgy.spec.faces", facesText(machine)));
		rows.add(row("manual.geometallurgy.spec.slots", slotsText(machine)));
		return new ManualDoc.Table("industry." + machine.name().toLowerCase(Locale.ROOT) + "#spec",
				Component.translatable("manual.geometallurgy.table.spec.caption", Component.translatable(blockKey)),
				List.of(Component.translatable("manual.geometallurgy.table.spec.item"),
						Component.translatable("manual.geometallurgy.table.spec.value")),
				rows, new int[] { 22, 78 });
	}

	private static ManualDoc.Table specialSpec(String kind) {
		List<ManualDoc.Row> rows = new ArrayList<>();
		String blockKey;
		switch (kind) {
			case "generator" -> {
				blockKey = "block.geometallurgy.coal_generator";
				rows.add(row("manual.geometallurgy.spec.input", text("manual.geometallurgy.machine.coal_generator.in")));
				rows.add(row("manual.geometallurgy.spec.output", text("manual.geometallurgy.machine.coal_generator.out")));
				rows.add(row("manual.geometallurgy.spec.power",
						Component.literal(num(CoalGeneratorBlockEntity.ENERGY_PER_TICK) + " FE/t；缓冲 "
								+ num(CoalGeneratorBlockEntity.CAPACITY) + " FE；单刻最多外送 "
								+ num(CoalGeneratorBlockEntity.MAX_EXTRACT) + " FE")));
				rows.add(row("manual.geometallurgy.spec.faces", text("manual.geometallurgy.machine.coal_generator.faces")));
				rows.add(row("manual.geometallurgy.spec.slots",
						Component.literal("燃料格 1 + 空桶格 1")));
			}
			case "battery" -> {
				blockKey = "block.geometallurgy.battery";
				rows.add(row("manual.geometallurgy.spec.input", text("manual.geometallurgy.machine.battery.in")));
				rows.add(row("manual.geometallurgy.spec.output", text("manual.geometallurgy.machine.battery.out")));
				rows.add(row("manual.geometallurgy.spec.power",
						Component.literal("档位 " + rates() + " FE/t；缓冲 " + num(BatteryBlockEntity.CAPACITY)
								+ " FE；损耗 " + BatteryBlockEntity.LOSS_NUMERATOR + "/" + BatteryBlockEntity.LOSS_DENOMINATOR
								+ "；半径 " + BatteryBlockEntity.RANGE + "；最多 " + BatteryBlockEntity.MAX_DEVICES + " 台")));
				rows.add(row("manual.geometallurgy.spec.faces", text("manual.geometallurgy.machine.battery.faces")));
				rows.add(row("manual.geometallurgy.spec.slots", Component.literal("无")));
			}
			case "fluid_extractor" -> {
				blockKey = "block.geometallurgy.fluid_vein_extractor";
				rows.add(row("manual.geometallurgy.spec.input", text("manual.geometallurgy.machine.fluid_extractor.in")));
				rows.add(row("manual.geometallurgy.spec.output", text("manual.geometallurgy.machine.fluid_extractor.out")));
				rows.add(row("manual.geometallurgy.spec.power",
						Component.literal(num(FluidVeinExtractorBlockEntity.ENERGY_PER_TICK) + " FE/t；每 "
								+ FluidVeinExtractorBlockEntity.HARVEST_TICKS + " 刻出 "
								+ num(FluidVeinExtractorBlockEntity.MILLIBUCKETS_PER_HARVEST) + " mB")));
				rows.add(row("manual.geometallurgy.spec.faces", text("manual.geometallurgy.machine.fluid_extractor.faces")));
				rows.add(row("manual.geometallurgy.spec.slots", Component.literal("钻头格 1 + 内部储罐")));
			}
			default -> {
				blockKey = "block.geometallurgy.displacement_furnace";
				rows.add(row("manual.geometallurgy.spec.input", text("manual.geometallurgy.machine.displacement.in")));
				rows.add(row("manual.geometallurgy.spec.output", text("manual.geometallurgy.machine.displacement.out")));
				rows.add(row("manual.geometallurgy.spec.power",
						Component.literal("0 FE/t（" + SmelterProfile.DISPLACEMENT.ticksPerBatch() + " 刻 / 炉次）")));
				rows.add(row("manual.geometallurgy.spec.faces", text("manual.geometallurgy.machine.smelter.faces")));
				rows.add(row("manual.geometallurgy.spec.slots",
						Component.literal("辅助剂 / 阴极 / 产物共 " + SmelterBlockEntity.SLOT_COUNT + " 格")));
			}
		}
		return new ManualDoc.Table("industry." + kind + "#spec",
				Component.translatable("manual.geometallurgy.table.spec.caption", Component.translatable(blockKey)),
				List.of(Component.translatable("manual.geometallurgy.table.spec.item"),
						Component.translatable("manual.geometallurgy.table.spec.value")),
				rows, new int[] { 22, 78 });
	}

	/** 耗电文字：全部来自机器方块实体的 public 常量。 */
	static String powerText(Machine machine) {
		return switch (machine) {
			case VEIN_EXTRACTOR -> num(VeinExtractorBlockEntity.ENERGY_PER_TICK) + " FE/t；每 "
					+ VeinExtractorBlockEntity.HARVEST_TICKS + " 刻抽一次（"
					+ num(VeinExtractorBlockEntity.ENERGY_PER_TICK * VeinExtractorBlockEntity.HARVEST_TICKS) + " FE/件）";
			case CRUSHER -> "磨料 " + num(CrusherBlockEntity.BASE_ENERGY_PER_MB) + "×(1+莫氏/4) FE/mB，"
					+ num(CrusherBlockEntity.RATE) + " mB/t，单刻上限 " + num(CrusherBlockEntity.MAX_DRAW)
					+ "；整件研磨 " + num(CrusherBlockEntity.GRIND_DRAW) + " FE/t";
			case CENTRIFUGE -> "额定 " + num(CentrifugeBlockEntity.NOMINAL_POWER) + " FE/t，"
					+ num(CentrifugeBlockEntity.RATE) + " mB/t，单刻上限 " + num(CentrifugeBlockEntity.MAX_DRAW);
			case MAGNETIC_SEPARATOR -> "场强 1.0 = " + num(MagneticSeparatorBlockEntity.NOMINAL_POWER) + " FE/t，"
					+ num(MagneticSeparatorBlockEntity.RATE) + " mB/t，单刻上限 " + num(MagneticSeparatorBlockEntity.MAX_DRAW);
			case REACTION_VESSEL, MIXER -> "0 FE/t";
			case REDUCTION_FURNACE -> "逐配方（基准 " + num(SmelterProfile.REDUCTION.energyPerTick()) + " FE/t，上限 "
					+ num(SmelterProfile.REDUCTION.maxDraw()) + "）";
			case ELECTROLYSIS_FURNACE -> "溶液档逐反应 FE/mol；熔盐档逐配方（基准 "
					+ num(SmelterProfile.ELECTROLYSIS.energyPerTick()) + " FE/t，上限 "
					+ num(SmelterProfile.ELECTROLYSIS.maxDraw()) + "）";
		};
	}

	/** 面角色：把该机器自己的角色枚举逐个列出来（每台机器一套枚举，没有共同接口）。 */
	private static String facesText(Machine machine) {
		return switch (machine) {
			case VEIN_EXTRACTOR, CRUSHER, CENTRIFUGE -> portRoles();
			case MAGNETIC_SEPARATOR -> faceRoles();
			case REACTION_VESSEL -> vesselRoles();
			case MIXER -> mixerRoles();
			case ELECTROLYSIS_FURNACE -> electrolysisRoles();
			case REDUCTION_FURNACE -> Component.translatable(
					"manual.geometallurgy.machine.smelter.faces").getString();
		};
	}

	private static String portRoles() {
		List<Component> out = new ArrayList<>();
		for (PortRole role : PortRole.values()) {
			out.add(Component.translatableWithFallback(role.translationKey(), role.name()));
		}
		return join(out).getString();
	}

	private static String faceRoles() {
		List<Component> out = new ArrayList<>();
		for (FaceRole role : FaceRole.values()) {
			out.add(Component.translatableWithFallback(role.translationKey(), role.name()));
		}
		return join(out).getString();
	}

	private static String vesselRoles() {
		List<Component> out = new ArrayList<>();
		for (VesselRole role : VesselRole.values()) {
			out.add(Component.translatableWithFallback(role.translationKey(), role.name()));
		}
		return join(out).getString();
	}

	private static String mixerRoles() {
		List<Component> out = new ArrayList<>();
		for (MixerRole role : MixerRole.values()) {
			out.add(Component.translatableWithFallback(role.translationKey(), role.name()));
		}
		return join(out).getString();
	}

	private static String electrolysisRoles() {
		List<Component> out = new ArrayList<>();
		for (ElectrolysisRole role : ElectrolysisRole.values()) {
			out.add(Component.translatableWithFallback(role.translationKey(), role.name()));
		}
		return join(out).getString();
	}

	private static String slotsText(Machine machine) {
		return switch (machine) {
			case VEIN_EXTRACTOR -> "产物格 " + VeinExtractorBlockEntity.OUTPUT_SLOTS;
			case CRUSHER -> "进料 " + CrusherBlockEntity.SLOT_INPUT + " / 出料 " + CrusherBlockEntity.SLOT_OUTPUT
					+ "（共 " + CrusherBlockEntity.ITEM_SLOTS + " 格）";
			case CENTRIFUGE -> "切点密度可调：" + CentrifugeBlockEntity.MIN_CUT + " ~ " + CentrifugeBlockEntity.MAX_CUT
					+ "（默认 " + CentrifugeBlockEntity.DEFAULT_CUT + "）";
			case MAGNETIC_SEPARATOR -> "进料 / 磁性出 / 非磁性出三面，无物品格";
			case REACTION_VESSEL, MIXER -> "催化剂格 " + AbstractReactorBlockEntity.ITEM_SLOTS
					+ " + 混合槽 " + num(AbstractReactorBlockEntity.TANK_CAPACITY) + " mol";
			case REDUCTION_FURNACE, ELECTROLYSIS_FURNACE -> "辅助剂 / 阴极 / 产物共 "
					+ SmelterBlockEntity.SLOT_COUNT + " 格";
		};
	}

	static String blockKeyOf(Machine machine) {
		return switch (machine) {
			case REACTION_VESSEL -> "block.geometallurgy.reaction_vessel";
			case MIXER -> "block.geometallurgy.mixer";
			case ELECTROLYSIS_FURNACE -> "block.geometallurgy.electrolysis_furnace";
			case VEIN_EXTRACTOR -> "block.geometallurgy.vein_extractor";
			case CRUSHER -> "block.geometallurgy.crusher";
			case CENTRIFUGE -> "block.geometallurgy.centrifuge";
			case MAGNETIC_SEPARATOR -> "block.geometallurgy.magnetic_separator";
			case REDUCTION_FURNACE -> "block.geometallurgy.reduction_furnace";
		};
	}

	private static String rates() {
		StringBuilder sb = new StringBuilder();
		for (long step : BatteryBlockEntity.RATE_STEPS) {
			if (sb.length() > 0) {
				sb.append('/');
			}
			sb.append(step);
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------ 化学

	/** 溶液电解逐反应单价。 */
	public static ManualDoc.Table electrolysisPriceTable() {
		List<ManualDoc.Row> rows = new ArrayList<>();
		for (MolBalance.Reaction reaction : MolRecipes.all()) {
			if (reaction.kind() != MolBalance.Kind.ELECTROLYSIS) {
				continue;
			}
			rows.add(new ManualDoc.Row(List.of(
					Component.literal(equation(reaction, false)),
					Component.literal(num(reaction.fePerMol()) + " FE/mol"),
					machines(reaction)), equation(reaction, false)));
		}
		return new ManualDoc.Table("chem.chemistry#prices",
				Component.translatable("manual.geometallurgy.table.elec.caption"),
				List.of(Component.translatable("manual.geometallurgy.table.rxn.equation"),
						Component.translatable("manual.geometallurgy.table.elec.price"),
						Component.translatable("manual.geometallurgy.table.rxn.machine")),
				rows, new int[] { 52, 22, 26 });
	}

	/** 动力学常量。 */
	public static ManualDoc.Table chemistryConstantsTable() {
		List<ManualDoc.Row> rows = List.of(
				row("manual.geometallurgy.const.vessel_rate", num(ProductionCost.VESSEL_RATE_PER_TICK) + " 份/t"),
				row("manual.geometallurgy.const.solution_rate", num(ProductionCost.SOLUTION_RATE_PER_TICK) + " 份/t"),
				row("manual.geometallurgy.const.catalyst", "×" + num(MolBalance.Reaction.CATALYST_RATE_BONUS)),
				row("manual.geometallurgy.const.stirring", "×" + num(MolBalance.Reaction.STIRRING_RATE_BONUS)),
				row("manual.geometallurgy.const.band", num(MolBalance.Reaction.EQUILIBRIUM_BAND * 100.0) + "%"),
				row("manual.geometallurgy.const.default_rate", num(MolBalance.Reaction.DEFAULT_RATE) + " 份/t"),
				row("manual.geometallurgy.const.min_progress", num(MolBalance.MIN_PROGRESS) + " 份"),
				row("manual.geometallurgy.const.default_price",
						num(MolBalance.Reaction.DEFAULT_FE_PER_MOL) + " FE/mol"),
				row("manual.geometallurgy.const.count", MolRecipes.count() + " 条（显式 " + MolRecipes.explicitCount()
						+ " + 规则生成 " + MolRecipes.generatedCount() + "）"));
		return new ManualDoc.Table("chem.kinetics#consts",
				Component.translatable("manual.geometallurgy.table.const.caption"),
				List.of(Component.translatable("manual.geometallurgy.table.spec.item"),
						Component.translatable("manual.geometallurgy.table.spec.value")),
				rows, new int[] { 40, 60 });
	}

	/** 反应条件 → 机器。 */
	public static ManualDoc.Table kindMachineTable() {
		List<ManualDoc.Row> rows = new ArrayList<>();
		for (MolBalance.Kind kind : MolBalance.Kind.values()) {
			List<Component> machines = new ArrayList<>();
			for (Machine machine : Machine.defaultsFor(kind)) {
				machines.add(Component.translatable(blockKeyOf(machine)));
			}
			rows.add(new ManualDoc.Row(List.of(kindName(kind), join(machines)), kind.name()));
		}
		return new ManualDoc.Table("chem.roasting#kinds",
				Component.translatable("manual.geometallurgy.table.kind.caption"),
				List.of(Component.translatable("manual.geometallurgy.table.kind.name"),
						Component.translatable("manual.geometallurgy.table.kind.machines")),
				rows, new int[] { 30, 70 });
	}

	/** 冶炼逐配方电耗（合金 / 金属的还原・电解・置换路线）。 */
	public static ManualDoc.Table smeltTable() {
		List<ManualDoc.Row> rows = new ArrayList<>();
		for (SlurryPart phase : Metallurgy.routable()) {
			for (SmeltRoute route : Metallurgy.routes(phase)) {
				SmeltRecipe recipe = Metallurgy.recipe(phase, route);
				if (recipe == null) {
					continue;
				}
				rows.add(new ManualDoc.Row(List.of(
						phase.displayName(),
						Component.translatable(route.translationKey()),
						Component.literal(num(recipe.powerFePerTick()) + " FE/t"),
						Component.literal(num(recipe.stoichiometry())),
						Component.literal(num(recipe.recovery() * 100.0) + "%")),
						phase.id() + " " + route.key()));
			}
		}
		return new ManualDoc.Table("chem.routes#smelt",
				Component.translatable("manual.geometallurgy.table.smelt.caption"),
				List.of(Component.translatable("manual.geometallurgy.table.smelt.phase"),
						Component.translatable("manual.geometallurgy.table.smelt.route"),
						Component.translatable("manual.geometallurgy.table.smelt.power"),
						Component.translatable("manual.geometallurgy.table.smelt.stoich"),
						Component.translatable("manual.geometallurgy.table.smelt.recovery")),
				rows, new int[] { 34, 22, 18, 13, 13 });
	}

	// ------------------------------------------------------------------ 附录

	/** 耗电一览。 */
	public static ManualDoc.Table powerTable() {
		List<ManualDoc.Row> rows = new ArrayList<>();
		for (Machine machine : Machine.values()) {
			ProductionCost.MachineStep step = ProductionCost.MACHINE_STEP.get(machine.name());
			if (step == null) {
				continue;
			}
			rows.add(new ManualDoc.Row(List.of(
					Component.translatable(blockKeyOf(machine)),
					Component.literal(num(step.fePerUnit()) + " FE/" + unitOf(machine)),
					Component.literal(num(step.ticksPerUnit()) + " 刻/" + unitOf(machine)),
					Component.literal(unitOf(machine))), machine.name()));
		}
		rows.add(new ManualDoc.Row(List.of(
				Component.translatable("block.geometallurgy.fluid_vein_extractor"),
				Component.literal(num(FluidVeinExtractorBlockEntity.ENERGY_PER_TICK
						* FluidVeinExtractorBlockEntity.HARVEST_TICKS / (FluidVeinExtractorBlockEntity.MILLIBUCKETS_PER_HARVEST / 1000.0))
						+ " FE/mol"),
				Component.literal(num(FluidVeinExtractorBlockEntity.HARVEST_TICKS) + " 刻/"
						+ num(FluidVeinExtractorBlockEntity.MILLIBUCKETS_PER_HARVEST) + " mB"),
				Component.literal("mol")), "fluid_vein_extractor"));
		rows.add(new ManualDoc.Row(List.of(
				Component.translatable("block.geometallurgy.coal_generator"),
				Component.literal("输出 " + num(CoalGeneratorBlockEntity.ENERGY_PER_TICK) + " FE/t"),
				Component.literal("1 煤 = " + num(CoalGeneratorBlockEntity.ENERGY_PER_FUEL) + " FE"),
				Component.literal("发电")), "coal_generator"));
		rows.add(new ManualDoc.Row(List.of(
				Component.translatable("block.geometallurgy.battery"),
				Component.literal("档位 " + rates() + " FE/t"),
				Component.literal("损耗 " + BatteryBlockEntity.LOSS_NUMERATOR + "/"
						+ BatteryBlockEntity.LOSS_DENOMINATOR + "，缓冲 " + num(BatteryBlockEntity.CAPACITY) + " FE"),
				Component.literal("FE")), "battery"));
		rows.add(new ManualDoc.Row(List.of(
				Component.translatable("block.geometallurgy.displacement_furnace"),
				Component.literal("0 FE/t"),
				Component.literal(SmelterProfile.DISPLACEMENT.ticksPerBatch() + " 刻/炉次"),
				Component.literal("炉次")), "displacement_furnace"));
		return new ManualDoc.Table("appendix.power#table",
				Component.translatable("manual.geometallurgy.table.power.caption"),
				List.of(Component.translatable("manual.geometallurgy.table.power.machine"),
						Component.translatable("manual.geometallurgy.table.power.draw"),
						Component.translatable("manual.geometallurgy.table.power.unit_ticks"),
						Component.translatable("manual.geometallurgy.table.power.unit")),
				rows, new int[] { 30, 26, 30, 14 });
	}

	private static String unitOf(Machine machine) {
		return switch (machine) {
			case VEIN_EXTRACTOR -> "件";
			case CRUSHER, CENTRIFUGE, MAGNETIC_SEPARATOR, REDUCTION_FURNACE, ELECTROLYSIS_FURNACE -> "mB";
			case REACTION_VESSEL, MIXER -> "份";
		};
	}

	// ------------------------------------------------------------------ 小工具

	private static ManualDoc.Row row(String labelKey, String value) {
		return row(Component.translatable(labelKey), Component.literal(value));
	}

	private static ManualDoc.Row row(Component label, Component value) {
		return new ManualDoc.Row(List.of(label, value));
	}

	private static ManualDoc.Row row(String labelKey, Component value) {
		return row(Component.translatable(labelKey), value);
	}

	static Component text(String key) {
		return Component.translatable(key);
	}

	/** 名字：矿用 {@code mineral.*}，其余用 {@code chemical.*} —— 与科技线同一套键。 */
	static Component name(String id) {
		if (OreMineral.byId(id) != null) {
			return Component.translatable("mineral.geometallurgy." + id);
		}
		return Component.translatableWithFallback(TechTree.nameKey(id), TechTree.nameFallback(id));
	}

	/** 物 id 集合 → 一行名字（最多 10 个，其余折成条数）。 */
	static String list(Set<String> ids) {
		List<Component> names = new ArrayList<>();
		int count = 0;
		for (String id : ids) {
			count++;
			if (names.size() < 10) {
				names.add(name(id));
			}
		}
		String text = join(names).getString();
		if (count > 10) {
			text += " 等 " + count + " 种";
		}
		return text;
	}

	static Component join(List<Component> parts) {
		Component out = Component.empty();
		for (int i = 0; i < parts.size(); i++) {
			if (i > 0) {
				out = out.copy().append(Component.literal(" · "));
			}
			out = out.copy().append(parts.get(i));
		}
		return out;
	}

	static Component machines(MolBalance.Reaction reaction) {
		List<Component> out = new ArrayList<>();
		for (Machine machine : reaction.machines()) {
			out.add(Component.translatable(blockKeyOf(machine)));
		}
		return join(out);
	}

	static Component kindName(MolBalance.Kind kind) {
		return switch (kind) {
			case THERMAL -> Component.translatableWithFallback("gui.geometallurgy.recipe.kind.thermal", "热反应");
			case STIRRING -> Component.translatableWithFallback("gui.geometallurgy.recipe.kind.stirring", "需搅拌");
			case ELECTROLYSIS -> Component.translatableWithFallback("gui.geometallurgy.recipe.kind.electrolysis", "电解");
			case ROASTING -> Component.translatableWithFallback("gui.geometallurgy.recipe.kind.roasting", "焙烧");
		};
	}

	/** 电耗一栏：容器型 0，电解按单价，焙烧走还原炉的逐配方功率。 */
	static String power(MolBalance.Reaction reaction) {
		return switch (reaction.kind()) {
			case ELECTROLYSIS -> num(reaction.fePerMol()) + " FE/mol";
			case ROASTING -> "逐配方（还原炉）";
			case THERMAL, STIRRING -> "0 FE/t";
		};
	}

	/** 方程（带系数）。{@code withNames} = 用译名，否则用物质 id。 */
	static String equation(MolBalance.Reaction reaction, boolean withNames) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < reaction.reactants().length; i++) {
			if (i > 0) {
				sb.append(" + ");
			}
			MolBalance.Reactant r = reaction.reactants()[i];
			if (r.coeff() != 1) {
				sb.append(r.coeff()).append(' ');
			}
			sb.append(withNames ? name(r.id()).getString() : r.id());
		}
		sb.append(" -> ");
		for (int i = 0; i < reaction.products().length; i++) {
			if (i > 0) {
				sb.append(" + ");
			}
			MolBalance.Product p = reaction.products()[i];
			if (p.coeff() != 1) {
				sb.append(p.coeff()).append(' ');
			}
			sb.append(withNames ? name(p.id()).getString() : p.id());
		}
		return sb.toString();
	}

	/** 纯数值格式化：整数不带小数点，其余保留两位。 */
	static String num(double value) {
		if (value == Math.rint(value) && Math.abs(value) < 1.0e12) {
			return Long.toString((long) value);
		}
		return String.format(Locale.ROOT, "%.2f", value);
	}
}
