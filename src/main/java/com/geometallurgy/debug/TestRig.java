package com.geometallurgy.debug;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.geometallurgy.block.ModBlocks;
import com.geometallurgy.blockentity.BarrelBlockEntity;
import com.geometallurgy.blockentity.ElectrolysisRole;
import com.geometallurgy.blockentity.MixerBlockEntity;
import com.geometallurgy.blockentity.MixerRole;
import com.geometallurgy.blockentity.ReactionVesselBlockEntity;
import com.geometallurgy.blockentity.SmelterBlockEntity;
import com.geometallurgy.blockentity.VesselRole;
import com.geometallurgy.chemical.MolStock;
import com.geometallurgy.item.MetalBucket;
import com.geometallurgy.item.ModItems;
import com.geometallurgy.metal.Ingots;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 测试场地：在玩家脚边把一台机器连同它的储罐、六面配置、罐内料一次性摆好（见 docs/测试场地.md）。
 *
 * <p>只用服务端方块 API —— {@link ServerLevel#setBlock} + 取回方块实体、直接写它的
 * {@link MolTank} 账与六面配置，不碰任何 {@code .mca} 区域文件。
 *
 * <p>每个玩家「上一次搭过哪些方块」记在内存表里，{@code clear} 就按这份名单清场；
 * 换场地时会先清掉上一次，避免越堆越多。
 */
public final class TestRig {

	/** 场地名（也是命令的子命令与 tab 补全项）。 */
	public static final List<String> SITES = List.of("mixer", "electrolysis", "barrel", "powder", "techtree");

	/** 每个玩家最后一次搭建占用的方块位置（清场用）。 */
	private static final Map<UUID, List<BlockPos>> LAST_RIG = new HashMap<>();

	private TestRig() {
	}

	// ------------------------------------------------------------------ 对外入口

	/** 在玩家脚边（面朝方向前一格）搭一个场地。 */
	public static boolean build(ServerLevel level, ServerPlayer player, String site) {
		BlockPos machine = player.blockPosition().relative(player.getDirection());
		List<BlockPos> placed = new ArrayList<>();
		if (!buildSite(level, player, site, machine, placed)) {
			return false;
		}
		LAST_RIG.put(player.getUUID(), placed);
		return true;
	}

	/** 一次性搭出全部场地（自动搭建用）：沿 x 轴间隔 8 格排开。 */
	public static int buildAll(ServerLevel level, ServerPlayer player) {
		BlockPos start = player.blockPosition();
		List<BlockPos> placed = new ArrayList<>();
		int index = 0;
		for (String site : SITES) {
			buildSite(level, player, site, start.offset(2 + index * 8, 0, 0), placed);
			index++;
		}
		LAST_RIG.put(player.getUUID(), placed);
		return index;
	}

	/** 清掉该玩家上一次搭的场地；没有记录返回 false。 */
	public static boolean clearLast(ServerLevel level, ServerPlayer player) {
		List<BlockPos> placed = LAST_RIG.remove(player.getUUID());
		if (placed == null || placed.isEmpty()) {
			return false;
		}
		for (BlockPos pos : placed) {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}
		return true;
	}

	/** 这条场地名认不认识。 */
	public static boolean isSite(String site) {
		return SITES.contains(site);
	}

	// ------------------------------------------------------------------ 各场地

	private static boolean buildSite(ServerLevel level, ServerPlayer player, String site,
			BlockPos machine, List<BlockPos> placed) {
		switch (site) {
			case "mixer" -> buildMixer(level, machine, placed);
			case "electrolysis" -> buildElectrolysis(level, machine, placed);
			case "barrel" -> buildBarrels(level, machine, placed);
			case "powder" -> buildPowder(level, machine, placed);
			case "techtree" -> giveManual(player);
			default -> {
				return false;
			}
		}
		return true;
	}

	/**
	 * 搅拌器：北罐氯气、东罐水分别接 A / B 入口，南面陶瓷罐接出液口 —— 六面显式配好。
	 *
	 * <p>验收的是「气液自由混合 + 氯水反应」：氯气 + 水走可逆反应
	 * {@code Cl2 + H2O <-> HCl + HClO}（K=0.25），约各生成 3 mol 后停在平衡点。
	 */
	private static void buildMixer(ServerLevel level, BlockPos machine, List<BlockPos> placed) {
		place(level, placed, machine, ModBlocks.MIXER);
		placeBarrel(level, placed, machine.north(), ModBlocks.GLASS_BARREL, MolStock.of("chlorine", 15.0));
		placeBarrel(level, placed, machine.east(), ModBlocks.METAL_BARREL, MolStock.of("water", 15.0));
		placeBarrel(level, placed, machine.south(), ModBlocks.CERAMIC_BARREL, null);

		int[] roles = new int[6];
		java.util.Arrays.fill(roles, MixerRole.DISABLED.ordinal());
		roles[Direction.NORTH.ordinal()] = MixerRole.INPUT_A.ordinal();
		roles[Direction.EAST.ordinal()] = MixerRole.INPUT_B.ordinal();
		roles[Direction.SOUTH.ordinal()] = MixerRole.OUT_LIQUID.ordinal();
		if (level.getBlockEntity(machine) instanceof MixerBlockEntity mixer) {
			mixer.applyFaces(pack3(roles));
		}
	}

	/**
	 * 电解炉：北罐盐水接溶液入口，东 / 南两个空陶瓷罐接阳极 / 阴极出口，溶液出口留空。
	 *
	 * <p>预充 96000 FE（= 电解炉的满缓冲）。2026-10-17 起溶液电解逐反应计价，氯碱 260 FE/mol 反应物，
	 * 所以这一仓电够 <b>369 mol 反应物</b>（约 92 份 2NaCl + 2H₂O），进去就能看到三本账动起来。
	 */
	private static void buildElectrolysis(ServerLevel level, BlockPos machine, List<BlockPos> placed) {
		place(level, placed, machine, ModBlocks.ELECTROLYSIS_FURNACE);

		MolStock brine = MolStock.of("sodium_chloride", 15.0);
		brine.add("water", 15.0);
		placeBarrel(level, placed, machine.north(), ModBlocks.CERAMIC_BARREL, brine);
		placeBarrel(level, placed, machine.east(), ModBlocks.CERAMIC_BARREL, null);
		placeBarrel(level, placed, machine.south(), ModBlocks.CERAMIC_BARREL, null);

		int[] roles = new int[6];
		java.util.Arrays.fill(roles, ElectrolysisRole.DISABLED.ordinal());
		roles[Direction.NORTH.ordinal()] = ElectrolysisRole.SOLUTION_IN.ordinal();
		roles[Direction.EAST.ordinal()] = ElectrolysisRole.ANODE_OUT.ordinal();
		roles[Direction.SOUTH.ordinal()] = ElectrolysisRole.CATHODE_OUT.ordinal();
		if (level.getBlockEntity(machine) instanceof SmelterBlockEntity smelter) {
			smelter.applyFaces(pack3(roles));
			// 电解炉满缓冲（SmelterProfile.ELECTROLYSIS.capacity() = 96000）。旧值 64000 是按
			// 平铺单价 120 FE/mol（533 mol）写的；逐反应计价后按满缓冲给，口径与界面一致。
			smelter.energy.amount = 96_000L;
			smelter.setChanged();
		}
	}

	/**
	 * 四只储罐：铁=水、玻璃=盐酸、铅=氢氟酸、陶瓷=硝酸。
	 *
	 * <p>验收的是耐蚀与玻璃罐渲染：玻璃耐酸与氧化剂，铅耐氟化物，陶瓷全耐。
	 */
	private static void buildBarrels(ServerLevel level, BlockPos machine, List<BlockPos> placed) {
		placeBarrel(level, placed, machine, ModBlocks.METAL_BARREL, MolStock.of("water", 30.0));
		placeBarrel(level, placed, machine.east(), ModBlocks.GLASS_BARREL,
				MolStock.of("hydrochloric_acid", 30.0));
		placeBarrel(level, placed, machine.east().east(), ModBlocks.LEAD_BARREL,
				MolStock.of("hydrogen_fluoride", 15.0));
		placeBarrel(level, placed, machine.east().east().east(), ModBlocks.CERAMIC_BARREL,
				MolStock.of("nitric_acid", 30.0));
	}

	/**
	 * 金属粉场地：反应釜北面接一只箱子，箱里放 2 桶盐酸 + 1 件锌粉；产物留在槽里看成分。
	 *
	 * <p>验收的是本轮新接的线：{@link com.geometallurgy.item.MetalPowderItem} 实现
	 * {@link com.geometallurgy.item.MolCarrier} 后，机器能把粉当 mol 载具吸进去、
	 * <b>消耗一件</b>（而不是像桶那样还一只空桶），规则 5 的
	 * {@code Zn + 2 HCl -> ZnCl2 + H2} 因此在生存里能跑起来。
	 * 盐酸用玻璃桶（铁桶不耐酸，机器会拒收并报「桶不耐蚀」）。
	 *
	 * <p><b>故意不配出料面</b>：出料面在目标相为空时会回退到「槽里最多的那一相」
	 * （{@code OutputRouting.effectivePhase}），配了会赶在反应前把盐酸抽走。
	 * 所以产物就留在槽里，玩家开界面看饼图与三项 mol 读数即可。
	 */
	private static void buildPowder(ServerLevel level, BlockPos machine, List<BlockPos> placed) {
		place(level, placed, machine, ModBlocks.REACTION_VESSEL);

		// 箱子必须是真的 Container：机器 pullBucket 只从 6 邻面的 Container 里取 mol 载具，
		// 储罐是 MolTank、走 pullTank 那条路，不在这里。
		BlockPos chestPos = machine.north();
		place(level, placed, chestPos, Blocks.CHEST);
		if (level.getBlockEntity(chestPos) instanceof Container chest) {
			fill(chest, 0, MetalBucket.create(ModItems.GLASS_BUCKET,
					MolStock.of("hydrochloric_acid", MolStock.BUCKET_MOL)));
			fill(chest, 1, MetalBucket.create(ModItems.GLASS_BUCKET,
					MolStock.of("hydrochloric_acid", MolStock.BUCKET_MOL)));
			fill(chest, 2, Ingots.createPowder(Map.of("zinc", 1.0), 1));
		}

		int[] roles = new int[6];
		java.util.Arrays.fill(roles, VesselRole.DISABLED.ordinal());
		roles[Direction.NORTH.ordinal()] = VesselRole.INPUT.ordinal();
		if (level.getBlockEntity(machine) instanceof ReactionVesselBlockEntity vessel) {
			vessel.applyFaces(pack3(roles));
		}
	}

	/** 科技线场地：直接发一本任务书（科技线总图在书里）。 */
	private static void giveManual(ServerPlayer player) {
		player.getInventory().placeItemBackInInventory(
				new ItemStack(ModItems.FIELD_MANUAL), Prediction.SERVER_ONLY);
	}

	// ------------------------------------------------------------------ 验收清单（用 literal，不新增 lang 键）

	/** 玩家自己的验收说法：搭完在聊天里逐行打印。 */
	public static List<String> checklist(String site) {
		return switch (site) {
			case "mixer" -> List.of(
					"看：北罐=氯气 15 mol、东罐=水 15 mol（空手右键罐可读内容）",
					"等约 25 tick：混合槽应出现 HCl + HClO（可逆，K=0.25，约各 3 mol 后停在平衡点）",
					"南面陶瓷罐应收到液相的盐酸 / 次氯酸；比率滑轨与搅拌开关在搅拌器界面",
					"把出料面故意标成「出气」：槽里主要是液体时会自动改推最多的那一相（空相回退），不会堵死",
					"槽满时状态会写明「出料堵住：液体 xx.x mol 推不出去」，且此时输入面不再吸罐 / 桶",
					"若显示「无反应」：先看搅拌开关是否开、三个罐是否紧挨机器（面角色按世界方向记）");
			case "electrolysis" -> List.of(
					"看：北罐=盐水（15 NaCl + 15 水），东 / 南两个空陶瓷罐接阳极 / 阴极",
					"已预充 96000 FE（电解炉满缓冲；氯碱 260 FE/mol 反应物 → 够 369 mol / 约 92 份），进来就该动：溶液账出现 NaCl / H2O，阳极账 Cl2（及 O2），阴极账 H2",
					"溶液里会生成 NaOH 并留在本机溶液账（溶液出口故意没配面，方便看三本账分开）",
					"成分环页签只有 液 / 阳 / 阴 三格：进 / 锭 两格在溶液档不画（bug#35 回归点）",
					"停一停再看：断开电力应停在「没电」而进度不清零（bug 回归点）");
			case "barrel" -> List.of(
					"看：铁罐=水、玻璃罐=盐酸、铅罐=氢氟酸、陶瓷罐=硝酸，四种材质各一只",
					"玻璃罐应有内嵌流体渲染（颜色由罐内成分决定）",
					"空手右键报内容；空桶右键取 15 mol；装了料的桶右键可倒回罐里",
					"故意换错材质（如铁罐装盐酸）应显示不耐蚀并拒绝收料");
			case "powder" -> List.of(
					"看：北面箱子里是 2 桶盐酸（玻璃桶，各 15 mol）+ 1 件锌粉（0.1 mol）；出料面故意留空",
					"几 tick 后：桶应被倒空放回箱子、锌粉应被消耗（箱子第 3 格计数 −1）",
					"开反应釜界面：混合槽先出现 30 HCl，吸进锌粉后是 0.1 Zn + 30 HCl → 0.1 ZnCl2 + 0.1 H2（Zn + 2 HCl，Zn 是限量试剂）",
					"把锌粉换成一摞（Shift 堆叠）：一次只吃 1 件，不会整摞消失（bug#12 回归点）",
					"换成合金粉 / 无成分粉：机器不吸（进不了 mol 账，不是金属单质）",
					"单独倒 0.2 NaOH + 大量水进槽：不会再留下 0.05 mol Na₂O / 多出来的水（高温焙烧 2NaOH→Na₂O+H₂O 已判 ROASTING，不在常温反应釜里跑 —— bug#36 回归点）");
			case "techtree" -> List.of(
					"已发任务书：右键翻开。**顶部就是搜索框**，开书时故意不聚焦 —— 直接按 WASD 不会把字打进框里",
					"翻开是一本**摊开的书**：左页 / 右页 / 书脊；正文从左页流到右页、装不下再翻下一跨，"
							+ "段按行切、表按行切，文字不越纸框（旧的左侧导航 + 单列滚动已经没有了）",
					"书脊右侧是书签：目录 / 前言 / 工业化，开始！ / 化学，进阶！ / 附录；当前部分高亮，"
							+ "点一下先进该部分的目录页（不是直接进正文）",
					"第一跨是**总目录**：只有四行「部分名 · N 页」，点一行进该部分目录页；"
							+ "部分目录页列该部分每个小节与部分内首页码，点小节才进正文并短暂高亮",
					"整跨**只在左上角印一行面包屑**「部分名 → 小节名」，右页顶部不印标题；"
							+ "小节跨页时续页仍是同一小节",
					"页脚**按部分独立编号**「部分名 局部/部分总页数」，每部分从 1 起，左页右页各印各的；"
							+ "书首总目录页脚印「全书第 N 页」，底栏全书页码只是次要参考",
					"左下 / 右下两个箭头翻页（悬停高亮），也可以按 ←/→ 或 PageUp/PageDown、滚轮翻页",
					"搜「磁铁矿」「sodium_chloride」或「NaCl」：结果列表出现在框正下方，↑↓ 选择、Enter 或点击打开",
					"物质类结果（如「氯化钠」）回车应打开配方详情界面；矿物 / 机器 / 反应类结果应跳到那一页并高亮该条",
					"ESC 先清空搜索词；搜索词已经空了再按 ESC 才关书。点一下框或直接敲字，焦点才进框",
					"附录的 79 种物质表与 107 条反应表跨页时，续页会自动重画表头；调到 GUI scale 3 或缩小窗口，"
							+ "书会缩进屏幕、正文重新分页，仍要能翻能点",
					"顶栏「科技线」按钮仍能打开总图，附录最后一页也有一个「打开科技线总图」入口");
			default -> List.of("未知场地。");
		};
	}

	// ------------------------------------------------------------------ 小工具

	/** 放一个方块并记进清场名单。 */
	private static void place(ServerLevel level, List<BlockPos> placed, BlockPos pos, Block block) {
		BlockState state = block.defaultBlockState();
		level.setBlock(pos, state, Block.UPDATE_ALL);
		placed.add(pos);
	}

	/** 往容器某格放一件东西并标记变更（测试场地灌箱子用）。 */
	private static void fill(Container container, int slot, ItemStack stack) {
		container.setItem(slot, stack);
		container.setChanged();
	}

	/** 放一只储罐并把内容写进它的账（content 为 null 表示空罐）。 */
	private static BarrelBlockEntity placeBarrel(ServerLevel level, List<BlockPos> placed, BlockPos pos,
			Block block, MolStock content) {
		place(level, placed, pos, block);
		if (level.getBlockEntity(pos) instanceof BarrelBlockEntity barrel) {
			barrel.tank().clear();
			if (content != null) {
				for (Map.Entry<String, Double> e : content.view().entrySet()) {
					barrel.tank().add(e.getKey(), e.getValue());
				}
			}
			barrel.setChanged();
			return barrel;
		}
		return null;
	}

	/**
	 * 六面角色打包：每面 3 bit —— 与 {@code AbstractReactorBlockEntity.packRoles()} /
	 * {@code SmelterBlockEntity.packElectroRoles()} 同一种编码，不经过只够 2 bit 的
	 * {@code FaceConfig.pack}（那套只服务 4 值的 {@code PortRole}）。
	 */
	private static int pack3(int[] ordinals) {
		int packed = 0;
		for (int i = 0; i < 6 && i < ordinals.length; i++) {
			packed |= (ordinals[i] & 0x7) << (i * 3);
		}
		return packed;
	}
}
