# BUG 台账（geometallurgy）

> 一条 = 一个 `# bug#N` 段。查：`node tools/bugfind.mjs 关键词1 关键词2`（多词是"都要命中"）。  
> 新坑一律往这里追加，编号只增不改。

# bug#1

症状：部分方块挖了不掉落，且用镐挖没有速度加成。  
根因：方块用了 requiresCorrectToolForDrops()，但没进 #minecraft:mineable/pickaxe，也没掉落表。  
修法：13 个方块全部补标签 + loot_table/blocks/*.json。  
文件：data/minecraft/tags/block/mineable/pickaxe.json、data/geometallurgy/loot_table/

# bug#2

症状：反应釜挖了不掉自己；药剂桶在客户端没有物品定义（图标异常）。  
根因：reaction_vessel 缺掉落表，metal_bucket / reaction_vessel 缺 items/*.json 客户端定义。  
修法：补掉落表 + items/*.json（26.x 的物品定义是 {"model":{...}}）。

# bug#3

症状：空药剂桶放进机器输入被判非法。  
根因：空桶没有 mol 单位标签（measure），路由按标签拒收。  
修法：ModItems 里给桶注册时统一打 .component(MEASURE, mol)。

# bug#4

症状：按内容物给桶上色没有可用 API。  
根因：26.x 里 Fabric 的 ColorProviderRegistry 已删除（只剩 BlockColorRegistry）。  
修法：走原版 minecraft:custom_model_data 染色源：把颜色写进 CustomModelData 组件，物品模型用 tints。  
文件：item/MetalBucket.java、assets/geometallurgy/items/*\_bucket.json

# bug#5

症状：满桶和空桶长得一样（染色模型没生效）。  
根因：items/metal_bucket.json 引用了 models/item/metal_bucket_filled.json，但那个文件根本不存在（只有计划，没落盘）。  
修法：补 metal_bucket_filled.json（layer0 = 可染色流体层、layer1 = 镂空桶壳）+ 重写 items 定义（has_component 判定）。

# bug#6

症状：反应釜/搅拌器界面里输出相按钮左边冒出一个半截怪字。  
根因：「催化剂」标签画在 x=26，输出相按钮从 x=30 起，标签整块被盖住。  
修法：槽 (8,32) → 标签 (30,37) → 按钮右对齐 (100,32)。

# bug#7

症状：六面配置格那排文字互相压在一起，像整排错位。  
根因：格子只有 27 px，却要塞「上 关闭」「上 A 液入」。  
修法：格子只写方位（后果/角色看底色），完整名字走 setTooltipForNextFrame 悬浮提示；所有机器统一。

# bug#8

症状：装满条空着时像一块灰，看不出是进度条。  
根因：只画了底色，没有边框。  
修法：外框 + 内槽 + 填充三段；缓存条统一改竖直条（同流体挖掘机/磁选机）。

# bug#9

症状：储罐用桶抽液，桶拿到料、罐子不减，反复抽"取之不竭"；日志显示 抽出=15 但 抽料后=15。  
根因：MolStock.add 开头写成 if (mol <= 0.0) return;，负数（=扣料）被吞 → take() 变成"说自己扣了、其实没扣"。  
全模组的扣料（桶/储罐/机器过户/反应消耗）一起静默失效。  
修法：恢复"负数就是扣"，sum ≤ 0 直接删键（顺带钳住负数），并在注释里留警告。  
文件：chemical/MolStock.java（**改这里之前想清楚 take 依赖它**）  
教训：定位手法 = 在扣减前后各打一行 totalMol()，"扣了但账没变"就等于锁定 take。

# bug#10

症状：桶往储罐倒料，桶里的内容不减少（凭空生料）。  
根因：MolTank.pour 会"就地从入参扣料"，代码又拿扣过的入参算"桶里还剩多少"，同一份料扣两遍。  
修法：倒之前先 content.copy() 留底，剩余 = 底账 − 实际倒出。

# bug#11

症状：反应釜/搅拌器往储罐送料，邻罐进了料、本机没扣（凭空生料）。  
根因：与 bug#10 同源——pushTank 拿 pour 之后就地的 load 当"倒之前那份"算比例，除数为 0 或残量。  
修法：send = load.copy() 留底，ratio = accepted / sent.totalMol()。  
文件：blockentity/AbstractReactorBlockEntity.java

# bug#12

症状：拿一摞（16 只）空桶去抽，一次把整摞标成满桶，罐子只少 15 mol → 看似取之不竭。  
根因：一摞桶共用同一个 bucket_stock 组件，直接改整摞 = 一次倒/灌 16 只的量。  
修法：一次只处理一只（oneAtATime 守卫 + 提示）；香草式"从摞里拆一只再塞回背包"留作后续。  
文件：block/BarrelBlock.java

# bug#13

症状：挖掉带料的储罐，掉一地物品且主线程转死 → 内存溢出崩游戏。  
根因：破坏钩子里 while (!rest.isEmpty()) 分桶吐出，没有上界（且 pour 的 limit 变负会把"扣"变成"加"，越加越多）。  
修法：三处收紧——先 clear() 再分装（幂等）+ 硬上限 8 + 残余塞最后一只桶；pour 的 limit 钳非负并夹容量。  
后续改版：不再分桶吐，改走"隐式组件 + 掉落表 copy_components"，掉的就是**储罐本身**（内容跟着走）。  
文件：block/BarrelBlock.java、blockentity/BarrelBlockEntity.java、loot_table/blocks/*\_barrel.json

# bug#14

症状：储罐贴图异常：方块状态边角缺口；掉落物/物品栏图标的尺寸与贴图都不对。  
根因：一是贴图是"圆角剪影 + 透明四角"却贴在整块 cube 上（模型与贴图尺寸不一致）；  
二是方块模型只有 textures+elements，缺 "parent": "minecraft:block/block"，物品形态没有 display 变换。  
修法：贴图改满幅（无透明角）；四个模型补 parent: minecraft:block/block。

# bug#15

症状：玻璃储罐贴着别的方块时能透视过去。  
根因：模型用 minecraft:block/cube，六个面带 cullface，相邻面被剔除。  
修法：四罐改显式 elements 且**不写 cullface**；玻璃加 .noOcclusion()；半透明走模型材质 force_translucent（26.4 没有 GlassBlock，玻璃类是 TransparentBlock）。  
文件：models/block/*\_barrel.json、block/ModBlocks.java

# bug#16

症状：饼图/溶液成分浮层里显示的是 substance.geometallurgy.hydrogen 这种原始键，而且所有扇形颜色一样。  
根因：成分项走了 SlurryPie.of(String) 那条"物质（Substance）"通路，而化学物质的名字键在 chemical.geometallurgy.*、颜色要取 Chemical.colorOf(id)。  
修法：直接构造 SlurryPie.Slice(color=0xFF000000|Chemical.colorOf(id), name=chemical.geometallurgy.<id>, fraction) —— 反应釜/搅拌器已经是这么写的，电解炉的溶液饼图要照改。

# bug#17

症状：电解炉换成独立面角色后，溶液入口静默失效 —— 面明明点成「溶液入」，邻罐的溶液一点也抽不进来；编译通过、界面也照常显示。  
根因：`pullSolution` 还按共用的 `PortRole.INPUT` 判面，而电解路的 `roles` 数组现在恒为 `DISABLED`，这条分支永远不命中。同一次漏改还留在 `pushSolution`（`PortRole.OUTPUT / BYPRODUCT`）。  
修法：两条都改判 `electroRoles[side]` 与 `ElectrolysisRole.SOLUTION_IN / SOLUTION_OUT`。  
文件：blockentity/SmelterBlockEntity.java  
教训：两套面角色并存时，每个搬运循环都要点名用哪一套；「编译过」不等于「换对了枚举」。查法 = 全仓 grep 该方法的角色判断，别只看报错。

# bug#18

症状：电解炉的熔盐档六个面都没有 I/O —— 矿粉进不来、渣排不出去，一炉都开不了；同一台机器的溶液档却正常。  
根因：给电解路引入独立 `ElectrolysisRole` 时，`pullInput` / `pushSlag` / `slurryAt` 一见到 `usesSolution()` 就直接 return，注释写的是「电解路没有粉进 / 渣出」；但 `usesSolution()` 指的是**整台电解炉**（能力），不是溶液档（模式）。熔盐档仍走同一个 `input` / `slag` 罐与 batch 状态机，于是 I/O 被整条短路，界面看着也照常。  
修法：`ElectrolysisRole` 增 `POWDER_IN` / `SLAG_OUT`（7 值仍 3 bit），`pullInput` / `pushSlag` / `slurryAt` 改判 `electroRoles`：电解炉看 `POWDER_IN` / `SLAG_OUT`，还原 / 置换两路仍看 `PortRole.INPUT` / `BYPRODUCT`；漏斗面同步放行 `POWDER_IN`。未动 `PortRole` 与另两条路线。  
文件：blockentity/ElectrolysisRole.java、blockentity/SmelterBlockEntity.java、menu/SmelterMenu.java、client/SmelterScreen.java  
教训：「这台机器是不是电解炉」（能力）与「现在按哪一档跑」（模式）是两个问题。用前者去 gate 后者的 I/O，就会像这次一样把一整档静默关掉。

# bug#19

症状：电解水 `2 H2O -> 2 H2 + O2` 需要两整份水，剩 1.x mol 时反应判定「料不够」，余料永久卡死；其它反应同理，搅拌器的「精准配比」实际仍是整数份量化。  
根因：`Reactor.runAll` 用 `MolBalance.runs` 求「够几份」，该方法对限制试剂比例向下取整（`< 1` 即 0），只能按整条方程推进；整数系数被当成了最小步长，不足一份的余料永远跑不动。  
修法：整数系数仍是质量守恒契约，新增**分数份推进** —— `MolBalance.limiting` 返回精确的限制试剂比例，`MolBalance.quantize` 向下量化到千分位（与 `MolStock` 记账分辨率对齐，长时间不漂移）；`Reactor.runAll` 取 min(限制试剂比例, ratePerTick 份数上限, 产物容量余量) 再量化，低于 `MolBalance.MIN_PROGRESS`（千分之一份）直接跳过、不空转；`Outcome` 增 `progress`（分数份之和），`reactionsRun` 记为「推进过的反应条数」（旧 2/3 参构造器保留）。反应釜 / 搅拌器与电解炉各传自己的槽容量（`TANK_CAPACITY` / `SOLUTION_CAPACITY`），产物净增会撑破账时按余量压缩推进。旧 `MolBalance.runs` 保持整数倍语义，`react` / `consume` 不变。  
文件：chemical/MolBalance.java、chemical/Reactor.java、blockentity/AbstractReactorBlockEntity.java、blockentity/SmelterBlockEntity.java

# bug#20

症状：反应只朝一个方向跑，产物无限堆积；`N2 + 3 H2 -> 2 NH3`、`CaCO3 -> CaO + CO2` 这类真实可逆反应不会停下来。  
根因：`MolBalance.Reaction` 只有单向定义，没有可逆标记与平衡判据；`Reactor.runAll` 每 tick 只看限制试剂，产物再堆积也继续正向推进。  
修法：`Reaction` 增 `boolean reversible` + `double equilibriumRatio`（产物 mol / 反应物 mol 的粗代理，默认 1.0），旧 3/4/5 参构造器全部保留（默认不可逆，未标记者行为逐 bit 不变）。`Reactor` 对可逆反应每 tick 比 `Q = Σ产物mol / Σ反应物mol` 与 `K`：`Q < K` 正向、`Q > K` 逆向（新增 `applyReverse`，消耗产物、生成反应物，复用同一套分数份 + 限速 + 产物容量机制）、`|Q-K| <= 5% K` 进死区不动；且只推进到平衡点、不越过 K（`MolBalance.progressToEquilibrium`），避免整份推进在平衡点两侧震荡。质量守恒校验对方向对称，逆向边不另配平。标可逆 5 条：合成氨 K=0.25、CaCO3 / MgCO3 焙烧 K=6、Al(OH)3 焙烧 K=6、NaHCO3 分解 K=8；电解 / 燃烧式化合 / 置换 / HF+SiO2 / 接触法 SO2→SO3 维持单向。`Cl2+H2O<->HClO+HCl` 未做（`Chemical` 无 HClO 物种，本任务不新增物种）。  
文件：chemical/MolBalance.java、chemical/Reactor.java、chemical/MolRecipes.java、docs/科技线-v1.md

# bug#21

症状：把氯气桶和水桶倒进搅拌器不会混合 —— 混合槽里始终没有内容、界面报「无反应」；用户口径「容器内部无法自由混合」。
根因：两个独立问题叠加。
      一（混合路径卡料）：`MixerBlockEntity.mix()` 只用 `batch = min(room, A/aFrac, B/bFrac)` 按比率兑一批。
        一侧为空时 `B/bFrac = 0` 直接把 batch 归零；比率不整除时余料也永远出不来。玩家把两只桶放进
        同一个输入面（只有一本缓冲）时表现最明显：整批料永久卡在 A 缓冲里，混合槽永远是空的。
      二（反应表缺边）：`Cl2 + H2O <-> HCl + HClO` 根本不在 `MolRecipes`，且 `Chemical` 里没有 HClO 物种；
        即便混合正确，也匹配不到任何反应，看起来就是「没混」。
修法：
      一：把混合算法抽到纯类 `MolBlend`（只碰 `MolStock`，可离线复算），分两段 —— 先按比率兑「均衡段」，
        再把两侧余料自由兑完，只受混合槽容量约束；比率仍决定均衡段组成，但不再是把料锁死的闸门。
        `MixerBlockEntity.mix()` 只做转发。桶→缓冲这条路径本身没有拒收气体/液体（`BucketMaterial.holds`
        只查「这只桶装不装得住自己的内容」），所以卡料点只在 mix()。
      二：表尾追加 `hypochlorous_acid`（HClO，M=52.5，LIQUID 溶液，近无色淡黄绿），`ChemicalTags` 去掉
        公式误推的 OXIDE；`MolRecipes` 追加可逆反应 `Cl2 + H2O <-> HCl + HClO`（STIRRING，只有搅拌器），
        粗平衡比 K=0.25（与合成氨同档）。质量守恒：71+18 = 36.5+52.5 = 89，启动期校验通过。
      离线复算：15 mol Cl2 + 15 mol 水 → 12 Cl2 + 12 H2O + 3 HCl + 3 HClO，Q=0.25=K 停住；
        产物过量则反向回推到同一平衡；不搅拌时不发生。2000 组随机料、9407 次反应步，最大质量漂移 1.8e-12 g（浮点误差）。
文件：blockentity/MixerBlockEntity.java、chemical/MolBlend.java（新）、chemical/Chemical.java、
      chemical/ChemicalTags.java、chemical/MolRecipes.java、docs/科技线-v1.md

# bug#22

症状：反应几乎瞬间完成（机器上限 1.0 份/tick = 20 条方程/秒），小批量 1~3 tick 就推完；
多步链条里的中间体活不过一瞬间，「次级反应」根本没机会发生。用户报：「加一个反应需要时间吧，
现在反应是瞬间进行的，这样有些次级反应可能无法进行」。
根因：bug#19 只解决了「分数推进」，但每条反应没有自己的**速率 / 耗时**概念 ——
Reactor.runAll 把机器的 ratePerTick 直接当成每条反应的上限，于是所有反应同速且速度极高，
不存在「中间体攒一攒」的时间尺度。
修法：MolBalance.Reaction 增 rate 字段（份/tick；旧 3/4/5/7 参构造器全部保留、默认
DEFAULT_RATE = 0.25，新增 withRate）；Reactor.runAll 增末参 machineFactor，
有效推进 = min(限制试剂, ratePerTick × reaction.rate() × machineFactor × modifiers, 容量余量)；
modifiers = 催化剂在位 ×4（催化剂仍是存在性闸门）+ 搅拌中 ×1.25（搅拌仍是闸门），
整机系数留口（三台机器当前都传 1.0）；新增极慢反应量化兜底 Reactor.slowFloorShouldLift
（只有「料够 + 容量够 + 纯粹速率太低」才把进度抬到 MIN_PROGRESS，容量 / 料不够不抬）。
44 条反应逐条标速率，速率表与选值理由见 docs/科技线-v1.md §十二。
文件：chemical/MolBalance.java、chemical/Reactor.java、chemical/MolRecipes.java、
      blockentity/AbstractReactorBlockEntity.java、debug/TestRig.java、docs/科技线-v1.md

# bug#23

症状：搅拌器状态「出料堵住」（`ReactorStatus.OUTPUT_FULL`），混合槽 90 mol 满、
      成分气体 8.9%（氯气）+ 液体 91.2%（水 / NaOH / HCl / HClO）；出料面只推该面配的那一相
      （如「出气」），91% 的液体永远推不出去，槽一直满、整机冻住；状态文案也说不清是哪一相堵、堵了多少。
根因：三个问题叠在一起。
      一（只推配相）：`pushFaces` 取 `outputPhaseOf(role)` 后直接抽该相；配的相在槽里为空时
        `pushTank` / `pushBucket` 都返回 false，这一面等于闲置，整机被槽里最多的另一相堵死。
      二（诊断缺失）：`OUTPUT_FULL` 只是一个枚举，界面拿不到「哪一相、多少 mol」。
      三（满槽仍吸料）：`pullFaces` 只看输入缓冲的容量、不看混合槽余量；槽满时输入面仍从邻罐 / 桶
        继续往缓冲里灌（缓冲再被 `mix()` 挡住），docs/科技线-v1.md §13.1 要求的「满槽完全停止吸料」没做到。
修法：
      一：出料相态决策抽成纯类 `chemical/OutputRouting`（只碰 `MolStock` / `Chemical`，可离线复算）——
        面配的相在槽里有料（≥ `MIN_MOL`）时按相出（保留玩家故意分相的路由）；配的相几乎为空时
        回退到槽里实际最多的一相；`blocked()` 给出「堵住的是哪一相、多少 mol」。
      二：`AbstractReactorBlockEntity.react()` 判 `OUTPUT_FULL` 时用 `OutputRouting.blocked` 记下
        `blockedPhase` / `blockedMol`，经 `ContainerData` 新两格 `D_BLOCK_PHASE`（相序号 + 1）+
        `D_BLOCK_MOL`（mol×100，钳 16 位）同步；菜单暴露 `blockedPhase()` / `blockedMol()`；
        `AbstractReactorScreen` 在 `OUTPUT_FULL` 时改用 `gui.geometallurgy.reactor_output_full`
        （带相名与 mol），不再是笼统的短句。
      三：`pullFaces` 开头加「槽余量 ≤ `MIN_MOL` 直接 return」——满槽时输入面不再从 `MolTank` / 桶吸料，
        缓冲也不再被喂。
      四（电解支路）：`SmelterBlockEntity` 对溶液 / 阳极 / 阴极三本账做同一套计算，满账时报出堵住的相与
        mol，经 `BLOCK_PHASE_INDEX` / `BLOCK_MOL_INDEX` 同步；`SmelterScreen` 在炉次进度条里显示
        `gui.geometallurgy.smelter_vessel_full`。
      纯类离线复算见当轮 throwaway harness（已删除）。
文件：chemical/OutputRouting.java（新）、blockentity/AbstractReactorBlockEntity.java、
      blockentity/SmelterBlockEntity.java、menu/AbstractReactorMenu.java、menu/SmelterMenu.java、
      client/AbstractReactorScreen.java、client/SmelterScreen.java、
      assets/geometallurgy/lang/zh_cn.json、assets/geometallurgy/lang/en_us.json、docs/科技线-v1.md

---

## 待修（用户 2026-10-16 报，未编号；修完并入 bug#N 系列）

# open#4 没有"固体/气体混进液体里搅拌"的配方
症状：把固体或气体与液体放一起搅拌，没有对应的溶解/吸收配方。
初判：`Chemical.Info` 已有 `solution` 标记与 `isSolvent`（水），但**没有从"任意气体/固体 + 溶剂"生成溶液的通路**；
      现有只有零星手写（`NH3 + H2O -> NH4OH`、`SO3 + H2O -> H2SO4`）。
建议修法：把"溶解"做成**由标签/公式推导的一类边**（气体或固体 + 水 → 其水合物/酸/碱），标记 `Kind.STIRRING`（需要搅拌加速），
          与 TRACK B 的标签体系同一批落地；溶解度/饱和可以先简化成"能溶就拿走，溶剂不足就按比例"。

# bug#24

症状：科技线总图一打开就崩 —— `ExceptionInInitializerError`，根因 NPE：`TechTree.SMELTING is null`。  
根因：类初始化是**文本顺序**执行的。本轮为「按工艺阶段分列」新增的静态字段 `SMELTING` 声明在 `static {}` **之后**，
      而 static 块里建节点时会调 `stageOf(id)` → `SMELTING.contains(id)`，此刻字段仍是 null。
      编译完全通过；只有真正加载 `TechTree`（点开科技线界面 / 烘任务书那一页）才会炸。  
修法：把 `SMELTING` 的声明移到 static 块之前，并在字段处留一行顺序约束注释。  
文件：manual/TechTree.java  
教训：类初始化块里用到的静态字段必须声明在块**之前**；「能编译」与「能加载」是两回事。
      一次性 harness 在 JVM 里直接读 `TechTree.NODES` 才第一次暴露它 —— 这类崩溃编译期看不出来。

# bug#25

症状：按用户口径给 `Chemical` 加金属单质物种（Fe/Cu/Zn/Mn/Al）后，规则 5（金属 + 酸 → 盐 + H2）
      **一条边都不生成**，而且跳过清单里连一条相关记录都没有（静默消失）；此前没有金属物种时
      它至少会打一条 `NOT_APPLICABLE`。  
根因：`ChemicalData.cationOf` 只从「离子电荷 > 0」的记号里挑阳离子。单质（如 Zn）的公式解析
      给它的单元电荷是 **0**（单质氧化态为 0），于是 `cationOf("zinc")` 返回 null；
      `ReactionRules.ruleMetalAcid` 拿到 null 就 `continue`，**既不生成也不记 Skip**。
      上一轮 §15.9 只单独验证了 `isAboveHydrogen("Zn")` 这个闸门函数，
      没验证整条规则能不能跑到闸门，所以「活动性闸门已实现」的结论一直没人推翻。  
修法：`ChemicalData.cationOf` 增加单质金属特判 —— 公式只含一种元素且该元素是金属时，
      阳离子就是该元素本身（价取常见价）。同时把规则 5 的 (规则, 物种) 级禁用查提前到酸循环里，
      并登记铁（稀酸给 FeCl2，表里只有 FeCl3）、硝酸 / 次氯酸（氧化性酸）、
      硫化氢 / 碳酸（弱酸）为禁用，免得将来补了盐就拼出假反应。  
文件：chemical/ChemicalData.java、chemical/ReactionRules.java、chemical/Chemical.java、
      chemical/MolRecipes.java、docs/科技线-v1.md §十七  
教训：闸门函数「单独正确」不等于规则「能跑到闸门」。规则里 `if (x == null) continue;`
      这种静默出口必须配一条 Skip 记录，否则新物种上线后消失得无声无息。

# bug#26

症状：反应釜 / 搅拌器的输入面上，箱子里若是一摞（>1）装了料的金属桶，机器一次把整摞倒空（`c.setItem` 写回整摞空桶），却只往槽里记 15 mol —— 整摞的料凭空消失；输出面反过来，一摞空桶被整摞标成满桶，槽里只扣 15 mol —— 凭空生料。  
根因：`pullBucket` / `pushBucket` 只读 `carrier.stock(stack)` 那个共享内容组件，不看清 `stack.getCount()`；一摞桶共用同一个 `bucket_stock`，整摞一起改写就等于一次倒 / 灌 16 只。与 bug#12（右键储罐）是同一个病根，只是那条只在玩家交互路径上加了 `oneAtATime`，机器两条路径漏了。  
修法：非消耗品载具（桶）在机器两条路径上都要求 `stack.getCount() == 1`，否则跳过；消耗品（金属粉，`consumedOnEmpty()==true`）的账按件现推、只减一件，不受此限。  
文件：blockentity/AbstractReactorBlockEntity.java  
教训：「一摞共享一个组件」的守卫要出现在**所有**改写该组件的地方，不能只挡玩家右键那条最显眼的路径。

# bug#27

症状：科技线图上「铁精矿 --→ 铁粉」直连 —— 铁 / 锰 / 铝三条链都从精矿一步跳到金属粉，铜 / 锌从原矿一步跳到金属粉。用户指出真链是「矿 → 精矿 →（粉碎机）矿粉 →（焙烧 / 浸出）化合物 →（还原炉 / 熔盐电解）金属锭 →（粉碎机）金属粉」。  
根因：`ReactionGraph.buildBridge()` 把 5 条「矿 / 精矿 --(CRUSHER)--> iron / manganese / aluminum / copper / zinc」当成了「矿 / 精矿 → 金属」的捷径。两处错：（一）粉碎机只把料磨细，永远不会把矿变成金属；（二）这 5 个 mol 物种是**金属粉**（`MetalPowderItem` + `PowderContent`），真正的来源是「金属锭 → 粉碎机」。当年 `Machine` 里没有还原炉值，作者就把整条冶金链收在「→ 金属粉」一步、机器借了粉碎机。  
修法：桥接表按真实步骤拆成五段 —— 新增 7 条「精矿 →（粉碎机）精矿粉」、6 条「化合物 / 矿 →（还原炉 / 电解炉）金属锭」、5 条「金属锭 →（粉碎机）金属粉」；已有「精矿 → 化合物」的起点从精矿换成精矿粉，宿主机器与化学式不变。`Machine` 追加 `REDUCTION_FURNACE`（追加在枚举**末尾**，旧值序号一字未动），`defaultsFor(Kind)` 一字未动，化学反应没有一条换机器。`TechTree.stageOf` 把 `*_powder` / `*_ingot` 归冶炼列，`TechTree.machineKey` 与 `ReactionDetailScreen.machineKey` 补还原炉分支。  
文件：chemical/ReactionGraph.java、chemical/Machine.java、manual/TechTree.java、client/ReactionDetailScreen.java、assets/geometallurgy/lang/zh_cn.json、assets/geometallurgy/lang/en_us.json、docs/科技线-v1.md §十九  
教训：桥接边必须与真实机器步骤一一对应；「机器没有对应枚举值」不是把两步并成一步的理由 —— 该补枚举值，而不是让粉碎机去干还原炉的活。一次性 harness 实测修正后 120 / 120 节点、79 / 79 化学物种可达，「精矿直连金属」违规边 0 条。
# bug#28

症状：稀有矿物在生存里**采不到** —— 自然金一次 100 mB 的开采只产出约 0.08 mB 金，锡石 0.35 mB/次；而磁铁矿 17.4 mB/次。一台机器摆在金矿脉正中心跑一天，也攒不出可观的金。用户口径：「出矿量极度不平衡，稀有矿物太少了」。  
根因：出现概率与丰度**没有分开**，且丰度直接照抄真实品位。（一）`OreBody.MIN/MAX_APPEAR_CHANCE = 0.25/0.40` 是**全体矿物共用**的常数 —— 自然金与石英脉的出现频率完全一样，稀有度根本没进频率这一维；（二）中心丰度直接取真实矿床品位，从石英脉 90% 到钻石 0.002%，跨度 4.5×10⁴；（三）矿体体积（extent × 形状半轴）再差约 300×。三者相乘，期望矿石量跨度 5.7×10⁴，稀有端低于任何设备的会计精度。  
修法：拆成三条独立的轴、各自离散成 5 档，见 `proto/OreTier`：`Spawn`（出现概率，只碰频率）/ `Grade`（中心丰度，只碰浓度）/ `Body`（矿体规模倍率，只碰体积）。34 行矿表每行显式选档 `dep(S?, G?, B?, ...)`，贵金属档刻意上调（真实 1~5 g/t 在方块尺度不可玩），稀有度改由出现概率承担。`OreBody` 删掉共用概率常数，extent 乘 `body.extentScale()`、丰度取 `grade.pick()`。期望矿石量跨度 5.7×10⁴ → 267×（去钻石 116×），稀有度指数 2976× → 55×，自然金金属产出 +55×。  
文件：proto/OreTier.java（新增）、proto/DepositType.java、proto/OreMineral.java、vein/OreBody.java、docs/科技线-v1.md §二十  
教训：**出现频率与浓度必须各自成轴**。把所有稀有度压到「浓度」一维上，会让稀有矿要么浓度高得不合理、要么低到采不到；离散成档再加一层压缩，才能既保住地质直觉又让稀有矿可玩。另外：本 mod 的矿不写进区块，改生成阵不会只影响新地块 —— 已摆下的机器保留旧矿脉直到下个 epoch 重勘（详见 §20.5）。
  



# bug#29

症状：离心机 / 磁选机在输入罐为空、状态 IDLE 时也持续扣电，且扣多少与处理量无关 ——
      离心机每刻最多吃 `MAX_DRAW=192` FE、磁选机 `MAX_DRAW=256` FE，只要缓冲里有电就扣。  
根因：两条 `tick()` 都是「先扣电、后判状态」：
      `long drawn = Math.min(this.energy.amount, MAX_DRAW); this.energy.amount -= drawn;`
      这一句在 `separate()` 之前无条件执行。离心机没有任何「空转也吃电」的注释 / 口径；
      磁选机有显式注释（「磁场不会因为这一刻没电就停」，耗电 = 场强），场强 `= drawn/64`
      会随上游供电一路到 `MAX_DRAW/64=4.0`，超出理想窗口 `FIELD_WEAK=0.5~FIELD_STRONG=1.5`。  
定性（2026-10-16 用户口径，本轮据此拆分）：
      **离心机 = 缺陷，已修**：改为「有浆可分层 + 两个产物罐都收得下」才取电（wantsWork 闸门）；
        空罐 / 进的是粉 / 产物堵住一律 0 FE，`power` 平滑衰减到 0（转速读数随之归零）。
      **磁选机 = 特性，保留**：用户原话「磁选机空转扣电是特性，因为之后会有永磁磁铁升级」。
        线圈通电即耗电正是「场强 = 本刻耗电 / 64」这条口径的代价，不改；已在 `tick()` 加显式
        注释与 `TODO(永磁磁铁升级)`，避免后来者把它当 bug#29 一起「修」掉。  
修法：`CentrifugeBlockEntity.tick()` 增 `wantsWork` 闸门；`MagneticSeparatorBlockEntity.tick()` 只加注释。  
文件：blockentity/CentrifugeBlockEntity.java、blockentity/MagneticSeparatorBlockEntity.java  
备注：本缺陷由产线成本模型分析时发现（`docs/产线成本账.md` §七），非本轮产物；
      磁选机的行为有显式注释，经用户确认为有意为之。

# bug#30

症状：矿脉抽取机只要缓冲里有电就每刻扣 256 FE 并推进进度；产物缓冲九格全满时也不停，
      一直空烧电，直到下游把产物取走。  
根因：`tick()` 只判 `energy.amount >= ENERGY_PER_TICK`，不判产物缓冲收不收得下；
      `progress` 到 `HARVEST_TICKS` 时 `produce()` 才因 `canAddItem` 失败而把进度卡在满值，
      但那一 tick 的电已经扣了，之后的每一 tick 也照扣。  
修法：`tick()` 增 `outputFull()` 闸门 —— 九格都连一件都放不下时不扣电、不推进，与流体版的
      `evaluate()` / `Status.OUTPUT_FULL` 停机口径对齐；只看槽位余量，不提前掷产物、不扰动随机序列。  
文件：blockentity/VeinExtractorBlockEntity.java  
备注：流体碎矿挖掘机（`FluidVeinExtractorBlockEntity`）**原本就正确** —— `evaluate()` 依次判
      NO_POWER / NO_DRILL / DRILL_TOO_SOFT / OUTPUT_FULL，只有 `RUNNING` 才扣电，无需改动。

# bug#31

症状：溶液电解「先反应、后扣不起」：一次推进多 mol 而缓冲余额不够时，只扣余额、少收电费，
      却按全额推进了反应 —— 等于凭空做功。  
根因：`tickSolution()` 只判 `energy.amount >= SOLUTION_FE_PER_MOL`（够 1 mol 的钱），
      然后让 `Reactor.runAll` 按整机速率跑完本轮，最后 `energy -= min(energy, cost)` 截断欠费。  
修法：把「这一刻买得起的反应物 mol」= `energy / SOLUTION_FE_PER_MOL` 作为预算交给反应器；
      `Reactor.runAll` 新增 `molBudget` 参数，按剩余预算压缩每条反应的推进份数
      （bug#19 的分数份推进），预算不足以推进一个 `MIN_PROGRESS` 就跳过。
      扣费 = `round(molReacted × 120)`，不再有免费的那一段；`SOLUTION_FE_PER_MOL=120` 未动。  
文件：chemical/Reactor.java、blockentity/SmelterBlockEntity.java

# bug#32

症状：`CrusherBlockEntity.NOMINAL_POWER = 64.0` 是死常量，全仓库无人引用（grep 仅命中声明本身）。  
根因：粉碎机耗电完全按「实际磨掉的 mB × 8×(1+莫氏/4)」算，本机根本没有「额定功率」这个概念；
      该常量是从离心机 / 磁选机复制过来的残留，会让读者以为粉碎机也看额定功率。  
修法：删除该常量，并在 `MAX_DRAW` 注释里点明「粉碎机没有额定功率」。（未改动任何数值）  
文件：blockentity/CrusherBlockEntity.java

# bug#33

症状：两台矿脉抽取机的界面能量读数会显示成一个**巨大的正数**、能量条永远是满的；
      服务端扣电正常，只有界面（客户端）读数错。  
根因：`ContainerData` 由 `ClientboundContainerSetDataPacket` 按 `writeShort` 发出（16 位有符号），
      而本轮用户调参把 `ENERGY_PER_TICK` 从 256 提到 1024，缓冲容量随之从 `256×100 = 25600`
      变成 `1024×100 = 102400` —— **超过 32767**。`readData(0)` 直接回 `(int) energy.amount`，
      102400 被截成 36864（short 视角 -28672），两个菜单又用 `& 0xFFFFFFFFL` 当无符号读回来，
      于是界面拿到 4294938624。旧容量 25600 < 32767，所以这个坑在本轮之前不会触发。  
修法：给两台机器各加一个同步刻度 `ENERGY_SYNC_DIVISOR = 4`（102400 / 4 = 25600 ≤ 32767）：
      方块实体 `readData(0)` 传 `energy / 刻度`，两个菜单乘回刻度；界面（能量条 / 数字）一行未改，
      `CAPACITY` 仍是 FE 原值。与 `SmelterBlockEntity.ENERGY_SYNC_DIVISOR = 10` 同一套做法。  
文件：blockentity/VeinExtractorBlockEntity.java、blockentity/FluidVeinExtractorBlockEntity.java、
      menu/VeinExtractorMenu.java、menu/FluidVeinExtractorMenu.java  
备注：**这类截断是静默的**（不抛异常、不报日志），只有在界面读数明显离谱时才会被发现 ——
      日后任何「把某个 ContainerData 量级抬过 32767」的改动都要先除一个刻度（参见
      `SmelterBlockEntity` 里那条「电解炉产量曾经显示成 -18 mB」的注释）。

# bug#34

症状：游戏内《任务书》里置换炉那段写「只耗 16 FE/t，一个炉次 60 刻，是三条路线里最省电的」，
      而置换炉其实**一度电都不接** —— 玩家会以为要给它拉电线。  
根因：`SmelterProfile.DISPLACEMENT` 的 `energyPerTick = 16` 是记录里留下的字段，
      但 `usesPower()` 对置换路返回 `false`（路线 == DISPLACEMENT），方块实体因此建的是
      **0 容量、0 进电上限**的 `SimpleEnergyStorage`，`tick()` 也按 0 扣；文案照抄了那个没人读的字段。  
修法：改写该条文案为「不接电（0 FE/t），一个炉次 60 刻，代价是吃掉一种比目标更活泼的金属」，
      中英双语同步（`manual.geometallurgy.start.displacement.l1`）；**代码行为一字未改**。  
文件：src/main/resources/assets/geometallurgy/lang/zh_cn.json、en_us.json  
备注：与 bug#32（`NOMINAL_POWER` 死常量）同源 —— **"没人读的字段"迟早会以文案/界面的形式骗到人**。
      本轮顺手把《任务书》里其余会随调参失真的数字一起对齐（抽取机 256 → 1024、粉碎机 8 → 16、
      磁选机 64 → 128 与窗口 32~96 → 64~192、还原炉 / 电解炉改成逐配方口径）。

# bug#35

症状：电解炉**没切到熔盐档**时（溶液档），成分环右侧那一列页签里仍然画着「进」「锭」两格
      —— 玩家看到的正是「进和锭两个槽位在没有切换到电解模式也存在」。  
根因：`SmelterScreen.tabCount()` 对溶液档写死 5（进 / 锭 / 液 / 阳 / 阴），页签位置与
      `pieTab` 语义 id 是同一个数；溶液档本来只有溶液 / 阳极 / 阴极三本账，没有矿粉进料
      （矿粉罐已按 `solutionMode()` 不画）也没有产物锭（产物槽是熔盐 batch 的），
      所以「进 / 锭」既是多余页签、切过去也只会看到一份空的熔盐账。  
修法：页签位置与语义 id 拆开 —— 新增 `tabIds()`，溶液档映射成
      `{TAB_SOLUTION, TAB_ANODE, TAB_CATHODE}`（3 格），熔盐 / 还原 / 置换仍是
      `{TAB_INPUT, TAB_PRODUCT}`（2 格）；`drawTabs` / `tabKey` / `tabColor` / `mouseClicked`
      一律过 `tabId(index)`。切档时把停在对面档位的 `pieTab` 收回默认（溶液档 → 液，熔盐 → 锭）。  
文件：client/SmelterScreen.java  
备注：**这不是 item 槽位**（阳极 / 阴极两个真物品槽是电解电极，两档都在，属有意），
      是页签集合按档切换。真物品槽的增删不能按 `solutionMode()` 做 —— 该方法在客户端
      构造菜单时读到的 `ContainerData` 还是全 0，服务端与客户端会数出不同的槽数而错位。

# bug#36

症状：反应釜的混合槽永远停着一小撮 `氧化钠`（截图占 0.1%）与多出来的水，
      状态一直显示「反应中」，怎么排都排不掉。（用户：「水和氧化钠没有完全消去」）。  
根因：表里有一对**非可逆的互逆反应**同槽共存 —— 规则 1 生成的
      `Na2O + H2O -> 2 NaOH`（快，rate 0.8）与显式的 `2 NaOH -> Na2O + H2O`（慢，rate 0.05）。
      每 tick 快反应先把上一 tick 留下的 Na2O 合掉，慢反应随后又造出 0.05 mol 新 Na2O：
      账面固定留下 `0.05 mol` 的 Na2O（50 mol 槽的 0.1%）+ 0.05 mol 水，且两条反应永远在烧。
      一次性 harness 实测（已删）：`33.2 NaCl + 16.55 H2O + 0.2 NaOH` 稳定在
      `NaCl 66.466% / 水 33.233% / NaOH 0.200% / Na2O 0.100%` —— 与截图逐项吻合。  
修法：`MolBalance.Kind` 追加 `ROASTING`（高温焙烧 / 分解，追加在枚举末尾，旧 ordinal 不动），
      `Machine.defaultsFor(ROASTING) = {REDUCTION_FURNACE}`；把 `2 NaOH -> Na2O + H2O` 判成
      `ROASTING`。`Reactor.VESSEL_KINDS` / `ELECTROLYSIS_KINDS` 都不含它 —— 密闭反应釜里水蒸气
      排不走，焙烧侧本就不成立。修后同一份料稳定在
      `NaCl 66.466% / 水 33.133% / NaOH 0.400%`、**Na2O = 0**，质量前后完全相等（2248.10 g）。  
文件：chemical/MolBalance.java、chemical/Machine.java、chemical/MolRecipes.java、
      chemical/Reactor.java、client/TechTreeScreen.java、client/ReactionDetailScreen.java  
备注：**不必改 `MolStock` / epsilon / 可逆平衡** —— 这不是「量化残渣被吞」，是两条方向相反的单向
      反应在互相抵消。harness 另测了全部 107 条反应，非可逆的精确互逆对只剩
      NaOH/Na₂O 这一对（修后其焙烧侧已移出釜子）与 HF 那一对（搅拌器 / 电解炉分属两台机器，
      同槽不会撞上）。

# bug#37

症状：还原炉被当成「刚需金属」—— 插一根活动性不够的金属就报「试剂不对路」，
      玩家读成「还原必须用金属」。（用户：「置换才需要金属，还原不刚需金属」）。  
根因：`SmelterBlockEntity.reagentFits` 对还原炉与置换炉**共用**同一条活动性闸门：
      槽里只要是金属，就要求它比产物金属活泼，否则 `WRONG_REAGENT`。可还原炉的基础还原剂
      是碳系（高炉 / 反射炉本来就是碳既还原又供热），金属只是可选替代；「挑金属」这件事
      只属于湿法置换。  
修法：模型收口到 `SmelterProfile.ReagentKind.accepts(ItemStack)` 一处 —— 还原炉认
      `Reagent.isReductant`（新增别名 `isCarbonFamily`，碳系恒可 + 金属可选），
      置换炉认 `Reagent.metalOf != null`，电解炉认电极；`SmelterBlockEntity.acceptsReagent`
      与 `SmelterMenu` 的客户端空壳都改调它。`reagentFits` 只对**置换炉**做活动性判定，
      还原炉直接放行（碳系与金属都不再被活动性卡住）。  
文件：metal/Reagent.java、blockentity/SmelterProfile.java、blockentity/SmelterBlockEntity.java、
      menu/SmelterMenu.java、metal/MetalSeries.java、
      assets/geometallurgy/lang/zh_cn.json、en_us.json、docs/耗电一览.md §五.8  
备注：这是**口径修正**，不是放宽化学 —— 置换炉仍是「金属必须比目标活泼」；
      还原路的碳系还原剂一直可用，本轮把它在准入与文案里都点成主路。

# bug#38

症状：任务书里同一台机器的说明连着写了两遍 —— 蓄电池、离心机、磁力筛机、无线感应器最明显
      （「19×19×19 范围内的无线电网中心…」之后又出现「作用：19×19×19 范围内无线电网的中心…」）。  
根因：2026-10-14 把 11 个小章合并成「工业化，开始！」这一章时，同一台机器在新旧两批条目里
      各留了一份，l1~l3 与 l4~l6 是同义重述；界面按语言文件的键逐段画，两段都画出来。  
修法：删掉重复段，并把只存在于重复段里的信息并进保留的那一段（最多 16 台 / 切点 1.5~8.0
      默认 3.0 / 粉料的有效磁场窗口更窄）；`sensor` 的 l2 与 l1 重复，删除后把 l3/l4 顺移成
      l2/l3。中英双语同步。  
文件：assets/geometallurgy/lang/zh_cn.json、en_us.json（本轮手册重排见 docs/科技线-v1.md §二十二）  
备注：段数由语言文件的**连续**键数决定（`client/ManualText`），所以删中间一段必须把后面的键
      重新编号 —— 否则数段会停在缺口处（`paragraphCount` 从 l1 往上数，缺 l1 就是 0 段），
      后面的段落全部消失。本次一次误删就撞上了这条，已从 `docs/.manual-backup` 恢复。

# bug#39

症状：长时间运行时全模组的质量账缓慢漂移 —— 全表 soup（79 物种各 50 mol）跑 20000 tick 凭空多出
      +13.856420 g（123208 次反应 / 6858.116 份方程），与机器转速无关，挂机越久偏得越多。
根因：Chemical 的摩尔质量全是手写常量，同一元素的原子量在不同条目里各取各的整 —— Ca 在
      CaO / CaF2 / CaSO4 里分别是 40 / 40.07 / 40.14；Al 在 Al2O3 是 27、在 AlCl3 是 26.8；
      C 单质 12.01 而 CO / CO2 用 12；Mn 单质 54.94 vs MnCl2 里的 55；Zn 65.38 vs ZnCl2 里的 65.8。
      isMassBalanced 的 <1 g 容差让这些亚克级残差混过启动校验；整数系数本身守恒，但每推一份
      就按残差改变一次总质量 —— 修前有 15 条反应残差非零（t#35 +0.07 / t#37 −0.01 / t#38 −0.22 /
      t#66 +0.14 / t#67 +0.07 / t#69 −0.40 / t#74 +0.01 / t#80 +0.14 / t#81 +0.07 / t#87 −0.20 /
      t#96 +0.14 / t#97 +0.07 / t#103 −0.08 / t#104 +0.06 / t#105 −0.36 g/份；编号是反应表行号，
      docs/科技线-v1.md §12.4 记的 #38/#35/#37 就是前三条）。
修法：新增纯类 chemical/MolarMass.java 作原子量的唯一来源（一张原子量表 + 公式解析：括号 / 下标 /
      · 水合物 / 稀土伪元素 RE·L·HRE）。Chemical.gas/liquid/solid 不再收手写摩尔质量，改调
      MolarMass.of(公式) 现算；重稀土（公式里的 H 是伪元素）走 heavyLiquid / heavySolid（H→HRE）。
      12 个自相矛盾的物种被统一：iron_oxide 160→159.7、iron_chloride 162.5→162.35、carbon 12.01→12、
      calcium_fluoride 78.07→78、calcium_sulfate 136.14→136、magnesium_carbonate 84.31→84、
      magnesium_oxide 40.30→40、calcium_phosphate 310.17→310、manganese 54.94→55、aluminum 26.98→27、
      zinc_chloride 136.3→136.38、aluminum_chloride 133.3→133.5；前 15 种物质一字未动、没有重排。
      稀土伪元素取 RE=152 / L=144 / HRE=160，满足 2·RE = L + HRE —— 这是全表唯一的非原子级配平
      反应 2 RECl3 → LCl3 + HCl3 所依赖的质量关系（它仍是唯一的 atom-count 不守恒边）。
验证：一次性 harness（已删）—— 修前 15/107 条残差非零、同一 soup 漂移 +13.856420 g；
      修后 0/107 条残差非零、同一 soup 漂移 +0.000000000 g、逐反应 20000 tick 长跑最大 |g/份|
      = 3.5e-13 g（浮点噪声）。
文件：chemical/MolarMass.java（新）、chemical/Chemical.java、docs/科技线-v1.md §二十三

# bug#40

症状：potassium_manganate（K2MnO4）的公式解析 chargeResolved=false —— 它进得了显式反应表，
      却没有阳离子 / 酸根（cationOf / anionOf 都是 null），也不参与规则生成。
根因：ChemicalData 的酸根表把 MnO4 固定登记成 −1（高锰酸根）。K2MnO4 里 MnO4 是 −2（锰酸根），
      K⁺×2 与 −1 无法配平电荷，解析器按「绝不猜价态」返回无解。
修法：ChemicalData 新增物种级「记号电荷覆盖」表 UNIT_CHARGE_OVERRIDES（chargeOverride() 登记 /
      unitChargeOverrides() 审计），解析时 unitCharge() 先查物种覆盖再回落到全局酸根表；显式登记
      potassium_manganate 的 MnO4 = −2。高锰酸钾 KMnO4 不动，仍走全局表 −1。ChemicalData 保持纯类
      （无 net.minecraft import）。
验证：同一 harness —— K2MnO4 resolved=true、charge=0、cation=K⁺、anion=MnO4²⁻；KMnO4 仍
      resolved=true、anion=MnO4⁻（无回归）；反应表条数 107（显式 57 + 生成 50）修前修后一致 ——
      锰酸钾解析成功后没有新增规则边（SALT_BY_ION 的 K|MnO4 已被高锰酸钾占位；且它是盐，不是
      氧化物 / 氢氧化物 / 碳酸盐，规则不适用）。
文件：chemical/ChemicalData.java、docs/科技线-v1.md §二十三

# bug#41

症状：任务书（bug#38 那轮重排）被改成「左侧四部分页签 + 右侧单列滚动」的通用文档浏览器 ——
      用户实机反馈「这个结构，很明显不对啊，之前的书本结构都没有了」。重排前是一本摊开的书
      （左右两页 + 书脊 + 页角箭头 + 页码），重排后书名 / 章节 / 正文全挤在一个全屏面板里，
      看不出是「书」。  
根因：§二十二重排把界面从「书本」换成了「分区浏览器」：`ManualScreen` 用 `NAV_W` 画左侧
      四部分页签与目录、右侧一条 `contentHeight` 长列 + 滚动条，旧的按页模型整个丢掉
      （旧模型在备份 jar `geometallurgy-20261017-123606.jar` 里：`BOOK_W=312 / BOOK_H=196`、
      左右页 `x=8..150 / 162..304`、画法全是 `fill`，没有任何 GUI 贴图）。  
修法：保留 §二十二加入的全部内容（四部分 / 顶部搜索 / 现算数据表 / 科技线入口），
      只把呈现换回书本：新增 `client/ManualPager.java` 做流式分页（自然段按行切、表格按行切且
      续页补表头），`ManualScreen` 按旧字节码量回的配色与几何画封皮 / 左右纸页 / 书脊 /
      纸张弯折阴影，四部分改成书脊右侧书签，第一跨是可点目录，页码 + 页角翻页箭头 +
      `←/→` / `PageUp/PageDown` / 滚轮，正文不越纸框。  
文件：client/ManualPager.java（新）、client/ManualScreen.java、
      assets/geometallurgy/lang/{zh_cn,en_us}.json、debug/TestRig.java、
      docs/科技线-v1.md §二十四  
备注：备份 jar 只用 `javap -p -c` 量常量与旧分页算法，没有搬回任何字节码。

# bug#42

症状：任务书（bug#41 恢复的摊开书本）每个物理页各自印一行页眉「部分名」——左右两页都印，
      同一跨里部分名出现两次；小节名又紧跟着作为正文标题重复一次。用户实机反馈
      「全部去掉，整跨只在左上角印一行面包屑，右页顶部不印任何标题」。
根因：bug#41 的分页器把「当前部分标题」存进每个 `Page.header`，`ManualScreen#drawOnePage`
      逐页画页眉 —— 是「每页一个标题」的旧式页眉，不是「整跨一行导航」。
修法：`ManualPager` 去掉 `Page.header`，改成 `Page.crumb`（部分名 → 小节名），在建页那一刻
      按「这一页从哪个小节接上」定下，跨页续页保持同一小节；`ManualScreen` 只在整跨左上角
      印一次面包屑（允许跨书脊排到右页），右页不再印任何标题。
验证：`.\gradlew.bat build --console=plain` BUILD SUCCESSFUL。
文件：client/ManualPager.java、client/ManualScreen.java、
      assets/geometallurgy/lang/{zh_cn,en_us}.json、docs/科技线-v1.md §二十五
备注：§二十五同时按用户要求把页码改成按部分独立编号、目录改成两级目录；本节只记页眉重复
      这一处真缺陷。

# bug#43

症状：蓄电池界面「输入接口 / 输出接口」一切换，19³ 内接入无线感应器的机器的充放行为就跟着变；
      用户原话「切换到输出界面时，蓄电池不接受电力，输入界面，不输出电力」。  
根因：页签状态是**机器状态**、不是纯显示 —— `BatteryBlockEntity.mode`（`Mode.INPUT/OUTPUT`）
      被 `saveAdditional` 持久化、经 `readData(0)` / ContainerData 同步，而
      `serverTick` 直接 `mode == OUTPUT ? pushOut : pullIn` 二选一，`visibleDevices` 又按
      `mode` 过滤设备表 —— 切页签改的是传输方向本身。
      （无线感应器 `WirelessSensorBlock` 是**没有方块实体**的纯标记方块：不存电、不传电、没有面角色，
      也没有任何读取 `mode` 的路径 —— 所谓「接收器行为改变」实际是蓄电池自己在按 `mode` 变。）  
修法：蓄电池**没有方向**。新增纯判定 `EnergyLinkPolicy`（`canDraw/canFeed/visible`，不碰 Minecraft）；
      `serverTick` 每刻同时 `pushOut` + `pullIn`；`visibleDevices` 改成「能收或能给」都列；
      `mode` / `Mode` 仅保留为旧存档兼容字段，读进来不参与任何传输判定；`setMode` / `mode()` 删除；
      `BatteryMenu` 拒绝一切 `100+` 旧模式按钮；`BatteryScreen` 顶部改成不可点的
      「储能 · 双向（同时充放）」角色条（新键 `gui.geometallurgy.battery.role`），不再有可切页签。
      逐台限功率（每设备一档）仍是真配置、与方向无关，保留。  
验证：`EnergyLinkPolicy` 纯类脱机 harness 跑完 `(supportsInsertion, supportsExtraction)` 四种组合
      真值表 **ALL PASS**（generator = 可抽不可灌、consumer = 可灌不可抽、双向机两向都通、空邻居不可见）；
      `.gradlew.bat build --console=plain` **BUILD SUCCESSFUL**。  
文件：blockentity/EnergyLinkPolicy.java（新）、blockentity/BatteryBlockEntity.java、
      menu/BatteryMenu.java、client/BatteryScreen.java、
      assets/geometallurgy/lang/{zh_cn,en_us}.json、docs/科技线-v1.md §二十六

# bug#44

症状：任务书字体「花」，用户要「书与笔那种正常字体」。  
根因：正文 / 表格 / 标题 / 面包屑 / 页码 / 书签 / 搜索框与结果**本来就都用默认字体**
      （`this.font`），没有自定义字体资源；「花」来自每行都带**投影描边** —— 26.x 的
      5 参数 `GuiGraphicsExtractor.text(...)` 会补 `dropShadow=true`（javap 已核：
      5 参重载以 `iconst_1` 调 6 参重载），而原版书与笔走 `ActiveTextCollector`，没有这层描边。  
修法：`ManualScreen` 全部 12 处文字绘制改显式 `false`，搜索框补 `EditBox.setTextShadow(false)`；
      字体、配色（INK / INK_DIM / HEAD_INK…）、排版、分页、尺寸一律未动。  
验证：`.gradlew.bat build --console=plain` **BUILD SUCCESSFUL**；未实机看画面。  
文件：client/ManualScreen.java、docs/科技线-v1.md §二十六

# bug#45

症状：矿物探测仪报出「最近的<某种>矿脉中心」以及坐标，玩家按坐标去挖 / 摆机器，矿脉中心
      没有任何矿物（用户 2026-10-18 实机，赤铁矿；并追问「矿脉会流动吗，能加虚拟日历吗」）。  
根因：**同一处矿脉被两套纪元基准各生成了一遍**。`OreBody` 是（种子, 单元坐标, epoch）的
      纯函数，epoch 进哈希也进岩体场，差 1 就完全是另一个矿体。探测仪 `Prospector` 用
      **单元纪元**（`EpochClock.epoch(gameTime, cellX*2, cellZ*2)`，每区块一个确定性偏移）；
      而挖掘机 `FluidVeinExtractorBlockEntity` 与 `VeinDeposit.at(level,pos)` 用**全局纪元**
      （`globalEpoch = floorDiv(gameTime, 240000)`，没有偏移）。两者约三成的时候差 1，
      于是探测仪报的是纪元 N 的矿体中心，机器在同一坐标按纪元 N-1 重算，得到纯原岩。  
二次缺陷：`MineralProspectorItem` 把坐标取整到 8 格（`round(v/8)*8`），而矿体最小半轴只有
      1.5 格 —— 即使纪元对了，也约一成的报告坐标落在矿体之外。  
修法：新增 `EpochClock.epochOfCell(...)`，把纪元基准统一到**矿体单元**（单元起始区块；
      不能按方块自己所在区块算，因为 32 格单元横跨两个 16 格区块）；`VeinDeposit.at`、
      `FluidVeinExtractorBlockEntity`、`Prospector` 全部改用它。`Reading` 增加
      `ticksUntilReroll`；探测坐标改成直接报中心整数方块；新增 `/geometallurgy calendar`
      与探测仪界面底部一行虚拟日历。存档格式、功率常数、矿档数值一字未改。  
验证：一次性 harness（已删），种子 20261018、玩家 (0,64,0)、gameTime=228000：探测仪中心
      (16.85,54.33,-15.12)、peak 35.6%；旧基准下该点 `veins=[]`，新基准下
      `hematite=0.4025 / chalcopyrite=0.0162 / fluorite=0.0711`。玩家周围 9267 次赤铁矿读数：
      旧基准 27.1% 中心为空，新基准 0%；旧 8 格取整 10.0% 落空，直接报中心方块 0%。  
文件：vein/EpochClock.java、vein/VeinDeposit.java、prospect/Prospector.java、
      blockentity/FluidVeinExtractorBlockEntity.java、item/MineralProspectorItem.java、
      menu/ProspectorMenu.java、client/ProspectorScreen.java、debug/TestRigCommand.java、
      assets/geometallurgy/lang/{zh_cn,en_us}.json、docs/科技线-v1.md §二十七  
备注：旧式固体抽取机 `VeinExtractorBlockEntity` 仍走无纪元的 `VeinGenerator`，是另一套旧矿脉，
      不在本次虚拟矿脉链路内。

# bug#46

症状：任务书之外的机器界面（蓄电池能量读数 / 无线列表 / 损耗、探测仪、反应釜 / 搅拌器、
      三台炉子、离心机 / 磁筛机 / 粉碎机 / 挖掘机、科技线、配方详情）文字都带投影描边，
      深色底小字号看着「花糊」（用户 2026-10-18 反馈 A）。  
根因：bug#44 只把 `ManualScreen` 的 12 处改成显式 `false`；26.x 的 5 参
      `GuiGraphicsExtractor.text(...)` 对**所有**界面都以 `dropShadow = true` 调 6 参重载。  
修法：新增共享助手 `client/UiText`（Component / String / FormattedCharSequence 三个重载，
      一律显式 `false`；另有 `centered` / `clipped`），13 个界面共 **94 处**五参
      `extractor.text(...)` 全部改走 `UiText.text(...)`，ManualScreen 的 12 处六参调用一并统一
      （全模组 106 处调用点，加 UiText 内部转发共 107）。字体资源、配色、坐标、折行、裁剪、
      布局尺寸一律未动；唯一的手写输入控件 `EditBox` 早已 `setTextShadow(false)`（bug#44）。  
验证：`.\gradlew.bat build --console=plain` **BUILD SUCCESSFUL**；`grep "extractor\.text\("`
      在 `src/main` 下只剩 `UiText.java` 的 3 处「显式 false」实现。未实机看画面。  
文件：client/UiText.java（新）、AbstractReactorScreen / BatteryScreen / CentrifugeScreen /
      CoalGeneratorScreen / CrusherScreen / FluidVeinExtractorScreen / MagneticSeparatorScreen /
      ManualScreen / ProspectorScreen / ReactionDetailScreen / SlurryPie / SmelterScreen /
      TechTreeScreen / VeinExtractorScreen

# bug#47

症状：任务书附录的「物质表 / 反应表」显示不全 —— 物质表名字/id/化学/相/摩/溶六列挤在一页，
      id 被截成 `hyd` / `sodi`、化学式被截成 `H2`；反应表「方程」列被截（「二氧化碳 +」
      后面看不见）、类/机器/速/电也挤（用户 2026-10-18 反馈 B）。  
根因：两条都在 `client/ManualPager`：① `columnWidths` 把每列抬到至少 12 px、最后一列吃余数，
      权重和大于页宽时列宽总和溢出，多出来的列整块落到纸外；② 单元格只裁不折 ——
      `clipped()` 用 `plainSubstrByWidth` 按列宽硬截。  
修法：`columnWidths` 改成按权重分配、每列至少 1 px、最后一列拿余数，**Σ 恰好 = 页宽**；
      单元格改用 `font.split` 折行，行高取该行各列折行后的最大行数，整行连一页都放不下时
      **按文本行切页**（续页补表头），行的热区跟它落在哪张纸就画在哪张纸；可点行 `link()`
      与小节标题 `beginSection()` 同样改成折行，长方程不再被纸边吃掉后半截。  
      与此同时，两张最大最挤的表（79 物质 / 107 反应）按用户口径改成**一条一页**
      （`client/ManualAppendix`，同轮反馈 C），附录不再有六列大表。  
验证：`.\gradlew.bat build --console=plain` **BUILD SUCCESSFUL**；未实机看画面。  
文件：client/ManualPager.java、client/ManualAppendix.java（新）、client/ManualDoc.java、
      client/ManualScreen.java、client/ManualSearch.java、client/ManualTables.java、
      assets/geometallurgy/lang/{zh_cn,en_us}.json、docs/任务书-目录底稿.md

