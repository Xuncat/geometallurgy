# 合金匹配：参考 tfc_chemical 对照稿

> 本稿只做**只读研究与对照**：不碰 E:\tfc_chemical，不碰本仓库代码/配方/lang。
> 目的：用户点名「金属配方匹配，参考 E:\tfc_chemical 项目」。本稿把该项目的合金匹配机制拆开，
> 与我们的 `docs/标准锭与转换台-设计稿.md`（下称「本稿/我们的设计稿」）逐条对照，
> 给出**建议借鉴**与**明确不借鉴**。
> 所有结论都带 `文件:行`；拿不准的一律写「待确认」。

---

## 〇、可用性结论（先回答「能不能用」）

**可用。** `E:\tfc_chemical` 存在且可读（本轮实际读取其 Java 源码、配方 JSON、Python 工具与 docs）。

| 项 | 结论 | 证据 |
|---|---|---|
| 目录 | `E:\tfc_chemical`，约 43493 个文件（含 `libs/` 内嵌依赖、`build/`、`bin/` 产物） | 本轮 `glob **/*` 实测 |
| 构建方式 | **NeoForge 1.21.1 + ModDevGradle**，Java 21；`gradlew` / `settings.gradle` / `build.gradle` / `gradle.properties` | gradle.properties:20 `minecraft_version=1.21.1`、:26 `neo_version=21.1.227`、:10 `org.gradle.java.home=...jdk-21.0.11` |
| mod id | `tfcchemical`，包 `com.tfc_chemical.tfc_chemicalmod` | gradle.properties:30、:40 |
| 配方数据 | **有可直接读的 JSON**：`src/main/resources/data/tfcchemical/alloy_recipes/*.json` | 见 §1.1 |
| 配方数量 | **只有 3 个**：`bronze`、`bismuth_bronze`、`black_bronze`（`bin/`、`build/` 下是副本，不是新配方） | 本轮 `glob **/alloy_recipes/*.json` 共 9 路径 = 3 源文件 × 3 份拷贝 |
| 语言 | 纯 Java（0 个 .kt）；辅助分析工具是 Python | 本轮 glob `**/*.kt`=0；`tool/*.py` 4 个 |
| 其它文档 | `docs/DESIGN_化学冶金.md`、`docs/DESIGN_发电效率对比.md` | 本轮 glob `docs/*` |
| 小瑕疵 | 仓库根目录有个疑似误写重定向留下的小文件，名字是 `e.equals(deposit.valuableElement))` | 本轮 `glob *` 顶层实测（只报告，不建议动，不属于本轮范围） |

---

## 一、它的做法（带证据）

### 1.1 配方是数据驱动的 JSON（每种合金一个文件）

字段（`AlloyRecipe.java:9-33` 的 Codec 是权威定义）：

```
id / display_name / main_metal
rules: [ { element, min, max, weight }, ... ]
match_threshold        // 本配方自己的过关分数线
attribute_bonus        // 命中后给物品加的属性（耐久/挖掘速度……）
custom_model_data / property_overrides / target_volume
```

实例（青铜）：`bronze.json:5-9` 铜 `min 0.88 max 0.92 weight 10`、锡 `0.08~0.12 weight 10`、`match_threshold 8.0`。
铋青铜/黑铜把 `match_threshold` 抬到 `15.0`（`bismuth_bronze.json:10`、`black_bronze.json:10`），
即**不同合金的严格度不同**。

### 1.2 加载：按资源包 reload，顺序进 LinkedHashMap

`AlloyRecipeManager extends SimpleJsonResourceReloadListener`，目录名 `"alloy_recipes"`（`AlloyRecipeManager.java:13-19`）；
解析失败只打 error 不崩（:31-36）；**存档顺序 = JSON 加载顺序**（:15 `LinkedHashMap`、:24 `entrySet` 遍历）。

### 1.3 输入口径：**只拿「金属池」去匹配，杂质根本不参与**

- 两种调用点都在 `CrudeSmelterBlockEntity`：
  - 动态熔点：把 `alloyMetals` 归一化后 `findBest`（:139-155）；
  - 浇铸成锭：由 `alloyMetals / metalBefore` 构造 `metalComposition` 再 `findBest`（:237-270）。
- 杂质走**另一本账** `alloyImpurities / totalImpurityAmount`（:65-67、:180-187），
  **不进入 `getMatchScore`**。也就是说：**它的合金"命名"与纯度完全脱钩**。
- 归一化用的是「金属之和」而不是「金属 + 杂质之和」（:240、:262-265）。

### 1.4 打分函数（核心算法）

`AlloyRecipe.getMatchScore()`（`AlloyRecipe.java:35-64`）：

1. `totalTarget = Σ 输入里「规则涉及元素」的值`；`< 0.01` 直接返回 0（:36-44）。
2. **只把规则涉及的元素重新归一化**：`normalizedTarget[e] = actual[e] / totalTarget`（:46-49）。
   ⇒ **不在 rules 里的元素被彻底无视**（关键差异，见 §四.1）。
3. 逐条规则：
   - 落在 `[min, max]` 内 ⇒ `score += weight`（:54-55）；
   - 否则若该元素 `> 0` ⇒ 罚分 `weight × (1 − |actual − center| / halfRange)`，
     `halfRange = (max−min)/2 + 0.01`，结果 `max(0, ·)`（:56-61）；
   - 该元素 `= 0` ⇒ **不加不减**（既不命中也不罚）。
4. 满分 = Σ weight。

### 1.5 命中多个配方时怎么选

`AlloyRecipeManager.findBest()`（`AlloyRecipeManager.java:45-58`）：

- 遍历所有配方，取 `score >= recipe.matchThreshold()` 中**分数最高**的；
- 比较用 `score > bestScore`（严格大于）⇒ **分数并列时第一个加载到的配方赢**（依赖文件/加载顺序，不是显式次序）；
- 一个都没有 ⇒ `Optional.empty()`，调用方回落到「按主金属命名」（`CrudeSmelterBlockEntity.java:275-279` 的 `usedMetal = mainMetal` 分支）。
- 第二个参数 `totalAmount` **声明了但完全没用**（:45 签名 vs :48-57 方法体）。

### 1.6 有没有概率 / 成功判定

**合金匹配本身没有任何概率**：匹配是确定性的，命中就把该合金的名字与加成盖到锭上
（`CrudeSmelterBlockEntity.java:410-420`：写 `ALLOY_RECIPE_NAME`、`ALLOY_BONUSES`、
动态 `ITEM_NAME`「青铜锭」、`CUSTOM_MODEL_DATA`）。**没有掷骰、没有失败销毁**。

唯一的随机性在**锻造**环节，而且惩罚的是**成品质量**不是命名：

- `purityRatio = 有价 / (有价 + 伴生)`，clamp `[0.1, 1.0]`，乘到耐久上（`ForgeBlockEntity.java:81-85, 128`）；
- 随机杂质惩罚：`if (random.nextDouble() < 杂质总量 × 0.5) 耐久 × 0.8`（:138-143）；
- 确定性杂质特效：锰 `×0.9`（:134-136）、钛 `×1.1`（:146-148）、
  以及硫/锰/砷/锑/钛/钒挂 `ToolEffects`（:183-199）。

### 1.7 「纯化 / 杂质」概念

**有，但是矿石/选矿侧的，和合金匹配是两条线。**

- 成分对象 `OreComposition` 把物质分成 `valuable / gangue / impurities / companion` 四桶
  （我们侧同名四桶见我们 `proto/OreComposition.java:14-23`，结构同源）。
- 矿床自带「伴生杂质占提取金属的 10%~30%」：`DepositType.MIN_IMPURITY=0.10 / MAX_IMPURITY=0.30`
  （tfc `api/composition/OreDepositType.java:135`、:140-149）。
- 杂质池 `impurityPool` 逐矿声明，比例由掷骰决定（tfc `OreDepositType.java:102,113,124`；
  `VeinAttributes.java:33-78` 里 `impurityTotal` 抖 ±0.10、clamp 到 0.03~0.30）。
- 杂质表 `ImpurityDefinition`：7 种，带权重区间与中文名（`ImpurityDefinition.java:8-16`）。
  **注意：我们仓库的 `proto/ImpurityDef.java:38-45` 是它的超集**（同样 7 种，多一个 `oxidic` 标记）。
- 没有「纯化」这个动作名；有「选矿/离心/电解」把杂质分走的机器（我们侧也一样）。

### 1.8 有没有「标准锭 / 标准件」这一层

**没有。** 这是与我们设计稿最大的分层差异。

- 浇铸产物 = **普通金属锭**（`getIngotItem(usedMetal)` 的 switch：copper/silver/gold/lead/zinc/tin/bismuth/nickel/iron，
  `CrudeSmelterBlockEntity.java:212-225, 394-408`）；
- 「合金身份」只是**物品组件**（名字 + 加成 + 模型数据，:410-420），不是独立注册的物品；
- 到工具时再按**合金名 → TFC 工具金属 id**写死一张 switch：
  「青铜→bronze / 铋青铜→bismuth_bronze / 黑铜→black_bronze / 其它→copper」（`ForgeBlockEntity.java:92-108`）。
- 所以它的「标准件」其实是**借 TFC 自己已有的 bronze/bismuth_bronze/black_bronze 工具 id**，
  没有「标准锭」这一层新物品。

### 1.9 它有我们缺的「离线审计器」

`tool/alloy_fairness_core.py` 是 Java 打分的**逐行镜像**（注释明写「复制 Java 端 `AlloyRecipe.getMatchScore()` 逻辑」，
`alloy_fairness_core.py:52-89`），再对全部配方排序、检查最高分是否过该配方阈值（:156-188）、
并检查「主金属一致性」（:190-199）。`tool/ai_judge.py:2,10-12,36-37` 包着它当「命名法院」。
样例输入 `tool/alloy_to_check.json` = `{copper 0.85, tin 0.10, zinc 0.05}`。
⇒ **纯函数可离线重放**这条设计原则，我们设计稿 §3.2 已经写了，但没有落地成可跑脚本。

---

## 二、与我们的设计稿的异同

| 维度 | tfc_chemical | 我们的设计稿 | 同/异 |
|---|---|---|---|
| 配方形态 | JSON + `SimpleJsonResourceReloadListener` | 待实现（§3.2 的 `record Spec`） | 同类思路 |
| 匹配判据 | **打分 ≥ 该配方阈值**（可部分命中） | **窗口全约束通过 + A>70% 门槛**（全有或全无） | **异** |
| 未在规则里的元素 | 重新归一化后**完全无视**（`AlloyRecipe.java:46-49`） | 一律算**有害杂质**加算扣百分点（§3.1） | **异（关键）** |
| 纯度/杂质 | **不参与**匹配，只影响锻造质量 | 参与 **P 公式**（乘性稀释 + 加性有害） | **异** |
| 成功概率 | 无（确定性命名） | P 公式 + 失败销毁输入（§3.4） | **异** |
| 命中多个 | 取最高分，并列取先加载者 | 取 P 最高，并列取与固定成分 L1 最近，再取表序（§3.6） | **半同**（都需并列裁决，我们多两档） |
| 同族区分 | 靠 `main_metal` + 阈值，**无窗口互斥保证** | **窗口互斥**是硬要求 + 启动期自检（§2.5） | **异** |
| 产物分层 | 普通锭 + 组件身份 | **独立物品 id 的标准锭**（§0.1 规则 1） | **异（刻意）** |
| 反算/预览 | UI 侧不算，浇铸后才知道 | 入槽前预览 P + 拆解（§4.1 UI 3/5） | **异（我们更强）** |
| 离线审计 | 有 Python 镜像（§1.9） | 只有纸面伪码（§3.2） | **我们缺** |
| 单位 | 质量分数、千分位最大余数归一化 | 锭 1% 网格 / 物种千分位（§1.2） | 同源、精度不同 |

---

## 三、建议**直接借鉴**的点

1. **每配方自己的严松参数**（`match_threshold` 逐配方不同，`bronze.json:9` vs `bismuth_bronze.json:10`）。
   理由：我们已有「每锭自己的窗口 ⇒ 各自 A_min ⇒ 各自 P 下限」（§2.4），本质同构；
   但可以把它显式化成「每锭一个严格度参数」，供 UI 解释与平衡调参。
2. **可离线重放的纯函数镜像 + 脚本**（`alloy_fairness_core.py` 全篇）。
   理由：它是这个项目唯一能把「匹配是否公平」批量验证的手段；我们 §3.2 已规定纯类，
   建议实现轮前先落一个 `Spec.match(comp)` 的离线 harness（照 `docs/测试场地.md:25` 先核实 API 的先例）。
3. **「主金属一致性」检查**（`alloy_fairness_core.py:190-199`）。
   理由：自动匹配若把「铜为主的料」匹配到铝基规格，是必然的玩家困惑；我们可以在启动期审计里加一条
   「规格的 mainMetal ∉ 窗口元素集」WARN。低成本、高收益。
4. **「金属 + 杂质拼成一个序列再做最大余数归一化」**（tfc `CrudeSmelterBlockEntity.java:345-360`）。
   理由：它的注释明写「旧实现逐项 Math.round，29% 的成分总和不等于 1.0」——
   这正是我们 §3.4 浮点陷阱的**同族 bug**；我们 `proto/CompositionMath.java` 已有 `largestRemainderRound`，
   建议在转换台的成分落盘处**显式复用**，不要逐项 round。
5. **杂质表带「权重区间」而不是定值**（`ImpurityDefinition.java:8-16`）。
   理由：我们 `proto/ImpurityDef.java:38-45` 已经是它的超集（多 `oxidic`），
   说明这条已经吸收；本稿只确认**不需要再改**，并把 `oxidic`（碳热还原消去）当作我们的增量优势记住。
6. **命名/加成的数据化**（`ALLOY_RECIPE_NAME` + `ALLOY_BONUSES` + `custom_model_data`，`CrudeSmelterBlockEntity.java:410-420`）。
   理由：它的「加成表跟着配方 JSON 走」值得抄进我们的标准锭规格表（固定成分之外的 attribute 字段），
   但**物品层不抄**（见 §四.2）。

---

## 四、明确**不借鉴**的点

1. **「规则外元素重新归一化后无视」的算法**（`AlloyRecipe.java:36-49`）。
   反例：铜 30% + 锡 3.33% + 锌 66.67% —— `totalTarget` 只算铜+锡 = 33.33%，
   重新归一化后铜 90%、锡 10%，**恰好落在青铜窗口内 ⇒ 得 20 分 ⇒ 判为青铜**，
   尽管这锅料三分之二是锌。理由：与用户定死的「该合金配方内的金属合计 > 70%」门槛直接冲突，
   也会让「同族区分」失效。我们坚持窗口 + A 门槛。
2. **「合金身份 = 物品组件而非独立物品 id」**（`CrudeSmelterBlockEntity.java:212-225, 410-420`）。
   理由：我们规则 1 明确要求「标准锭是独立物品、机器按**物品 id** 拒收」
   （设计稿 §0.1 规则 1、§4.8；26.x 原版 Ingredient 只认物品集合、看不到组件，
   见我们 `recipe/IngotCastingRecipe.java:19-26`）。抄组件方案会**同时废掉这两条**。
3. **「合金匹配无概率、无失败」**（§1.6）。
   理由：与用户定死的转换台规则 2/3（P 公式、失败销毁、6400 FE/件）直接冲突。
   它的确定性命名适合「TFC 已有工具 id」的封闭集合，不适合我们「废锭出口 + 概率精炼」的玩法。
4. **「杂质完全不参与匹配」**（§1.3）。
   理由：我们的核心卖点就是「纯度决定成功率」（§0.1 规则 3、§3.4 S3 用户例 53%）。
   若照抄，转换台会退化成「只要比例对，多脏都成」，与设计目的相反。
5. **「产物回落到主金属命名」的兜底**（`CrudeSmelterBlockEntity.java:275-279`）。
   理由：这正是我们 §4.6/§4.7 要避免的行为——匹配不上应当**不合格、停机、不扣电**（规则 4），
   而不是悄悄给一个「主金属锭」。抄它会制造「我明明放的是废料，出来一个铁锭」的静默成功。
6. **用「借 TFC 已有金属 id」表达新合金**（`ForgeBlockEntity.java:92-108`）。
   理由：那张 switch 是**写死的封闭集合**（只有 3 个合金名），加第 4 种就要改代码；
   我们 v1 名单本身就在变（见设计稿修订），需要数据驱动而不是 switch。

---

## 五、证据清单（本轮只读实测）

| 主题 | 文件:行 |
|---|---|
| 配方 Codec | `E:\tfc_chemical\src\main\java\...\api\recipe\AlloyRecipe.java:9-33` |
| 打分函数 | 同上 :35-64 |
| 加载与选择 | `...\api\recipe\AlloyRecipeManager.java:13-39, 45-58` |
| 烧炉调用点 | `...\blockentity\CrudeSmelterBlockEntity.java:139-155, 237-270, 275-279, 345-360, 394-420` |
| 锻造纯度/杂质 | `...\blockentity\ForgeBlockEntity.java:81-85, 92-108, 128-148, 180-199` |
| 杂质表 | `...\api\composition\ImpurityDefinition.java:8-16` |
| 矿床杂质区间 | `...\api\composition\OreDepositType.java:102, 113, 124, 135, 140-149`；`...\api\composition\VeinAttributes.java:33-94` |
| 配方 JSON | `E:\tfc_chemical\src\main\resources\data\tfcchemical\alloy_recipes\{bronze,bismuth_bronze,black_bronze}.json` |
| 离线审计 | `E:\tfc_chemical\tool\alloy_fairness_core.py:52-89, 156-199`；`tool\ai_judge.py:1-57`；`tool\alloy_to_check.json` |
| 构建 | `E:\tfc_chemical\gradle.properties:10, 20, 26, 30, 40`；`settings.gradle` |

---

## 六、待确认 / 未验证

1. `bin/`、`build/` 下是否有**运行时生成**的新合金配方（本轮只看到 3 源的副本）——待确认。
2. 是否还有**代码里生成**的 alloy recipe（本轮全仓 grep 未发现除这 3 个之外的 `alloy_recipes` 数据源）——待确认。
3. `findBest` 的 `totalAmount` 参数是否为「预留给未来按总量缩放分数」——**代码无证据，纯猜测，建议忽略**。
4. 本轮**未运行任何构建**（只读）。
