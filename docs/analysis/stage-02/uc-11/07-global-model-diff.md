# UC-11 分析与现有全局模型差异

**版本：** V1.0（2026-10-21）
**比较对象：** [`DGM-CLASS-01-analysis.puml`](../../../uml/analysis/DGM-CLASS-01-analysis.puml)、[`01-BCE分析类模型.md`](../01-BCE分析类模型.md)、[`DGM-USECASE-02-analytics.puml`](../../../uml/analytics/DGM-USECASE-02-analytics.puml)、[`DGM-ACTIVITY-02-metrics-refresh.puml`](../../../uml/analytics/DGM-ACTIVITY-02-metrics-refresh.puml)、[`DGM-ACTIVITY-04-institution-ranking.puml`](../../../uml/analytics/DGM-ACTIVITY-04-institution-ranking.puml)、[`DGM-STATE-03-metrics-visibility.puml`](../../../uml/analytics/DGM-STATE-03-metrics-visibility.puml)、模块细化 [`analytics-graph.md`](../../../srs/modules/analytics-graph.md)、候选类登记表 [`analysis-class-registry.md`](../automation/analysis-class-registry.md)、待决清单 [`unresolved-decisions.md`](../automation/unresolved-decisions.md)、追踪表 [`03-AC-UC-分析模型追踪.md`](../03-AC-UC-分析模型追踪.md)。
**原则：** 全局图只作比对材料，不作为本 UC 识别类的反向答案；本轮不重构全局图、不修改共享登记表、不修改他人 UML 资产与模块 SRS。

## 1. 已存在且一致

| UC-11 类/概念 | 全局对应 | 一致点 | 证据 |
|---|---|---|---|
| `StatisticsUI`（统计读数入口） | `AnalyticsUI`（`..>` `GraphCtl`；“+统计/排名/热点/基础网络/路径”）；`01` §2 `PublicPortalUI`（UC-01/02/03/11/12/…） | 都承认“访客在公开页面查看统计/排名”的读侧入口，且不由实体承担展示 | FR11；`DGM-USECASE-02`；`01` §2 |
| `WorkEntity`（计数单位） | 全局 `Work`（“+去重规范值()/+成果类型()/+生效状态()”）；`01` §4“去重后的四类成果规范实体；来源数/版本数不等于成果数” | 去重实体是唯一计数单位；来源数/版本数不放大计数 | BR-IMP-02；`AC-IMPL-FR11-01`；`AC-IMPL-FR16-02`；INV-02 |
| `Authorship`（参与关系，含多机构原值） | 全局 `Authorship`（“+角色/顺序/证据()/+独立关系状态()”；`Authorship -- Work`） | 角色/顺序/证据与独立状态；关系不是成果实体的一部分 | ID-05；§6.4；BR-352 |
| `Institution` 的“规范名归并”需求 | `DGM-ACTIVITY-04`（机构成果分类排名活动图，含归属口径说明）；CL-05 机构别名映射 | 都要求按机构规范名计数、并说明归属口径 | FR11-S08；CL-05；BR-513 |
| 真实/模拟/混合区分 | `DGM-ACTIVITY-02` 与 FR11-S12 的“真实/模拟标识与过滤”；`DGM-USECASE-02` 的统计域用例 | 默认结果只取真实数据；模拟/混合必须标识 | BR-IMP-07；`AC-IMPL-FR11-02` |
| “机构未知单列、不进入名次” | `DGM-ACTIVITY-04` 分支（“部分成果机构归属缺失→机构未知单列”）；BR-513 | 未知不参与排名，不与已知机构混合 | BR-IMP-06；`AC-IMPL-FR11-01` 语境 |
| “不按来源数/版本数计” | `01` §4、INV-02；`AC-IMPL-FR16-02` | 一成果跨两个来源且有版本关联，仍按去重实体计 1 | BR-IMP-02；FR16 |
| “计数排序不得表述为质量” | BR-522；SRS §2.1 | 机构排名仅表达计数，不作优劣评价 | BR-522；A16 |

## 2. 需要更名/别名统一

| 本 UC 名称 | 全局名称 | 差异 | R5 建议 |
|---|---|---|---|
| `StatisticsUI` | `AnalyticsUI`（`DGM-CLASS-01`；`01` §2）；`PublicPortalUI`（`01` §2，覆盖 UC-11/12） | 全局把“统计分布/机构排名”与“合作网络/最短路径”合在一个 `AnalyticsUI` 上，只有 `..> GraphCtl` 一条依赖；`PublicPortalUI` 又把它并入公共门户 | R5 决定：①在 `AnalyticsUI` 内显式区分“统计读数”与“网络/路径”，或②保留独立 `StatisticsUI`（与 UC-14/15 的 `AnalyticsNetworkUI` 类边界一致） |
| `StatisticsCoordinator` | 全局 `GraphCtl`（“+四类基础边/论文路径”）；`01` §3 `AnalyticsNetworkCoordinator` | 全局没有统计分布/机构排名的协调者：`GraphCtl` 的职责是建边与路径，不是范围过滤/去重计数/机构归属/口径披露 | R5 在 `AnalyticsUI` 下拆两个协调者：统计读数（本 UC）≠ 网络与路径（UC-14/15）；禁止把去重计数与建边混在一条路径 |
| `Institution` | 全局**无对应类** | 全局没有机构身份对象，机构只能作为 `Work` 上的字符串；无法表达规范名、别名合并链与未映射标记 | R5 新增 `Institution`（读侧最小职责：规范名/别名/合并链/未映射），或明确由 FR14/FR16 的分类维护对象承担并给出只读契约 |
| `WorkEntity.occurredAffiliationFacts[]` | 全局 `Work` 无机构归属属性；注册表 `WorkEntity` 行只列 `occurredInstitution`（单值） | 注册表把“成果发生时机构”写成单值，但 `AC-IMPL-FR11-01` 与 BR-IMP-06 要求多机构各计一次 | R5 把该属性改为集合语义（含“未知/未映射”标记与证据引用），并在 `01-BCE` §4 补一句“机构归属为集合，且必须是有证据的发生时归属” |
| `WorkEntity.dataOrigin` | 全局 `Work` 无“真实/模拟/混合”属性；`01` §4 未列 | BR-IMP-07 与 `AC-IMPL-FR11-02` 要求按数据性质过滤与标识；SRS §7.1 把该标识列在成果公共字段 | R5 在 `Work` 职责中补 `dataOrigin`，并注明“默认现实视图只取真实” |
| `Authorship`（本 UC 只读） | 全局 `Authorship` | 命名一致；但全局未注明“关系条数不是统计计数单位” | R5 在 `Authorship` 注记“统计计数单位为去重成果实体，不按关系/作者数展开” |

## 3. 职责差异/需要补充

| 项目 | 本 UC 证据 | 全局现状 | 差异/影响 | R5 处理 |
|---|---|---|---|---|
| 默认现实视图守卫 | M3；BR-IMP-07；`AC-IMPL-FR11-02`；FR11 | `GraphCtl`/`AnalyticsUI` 无数据性质守卫 | 若按全局图实现，模拟数据可能直接进入“现实排名” | 在统计协调者职责写明“默认仅真实、公开、生效、合格”，演示模式需显式标识 |
| 去重计数守卫 | M5/M7；BR-IMP-02/06；FR11-S07 | `GraphCtl` 有“同一成果对同一学者对贡献 1 强度”，但无“实体计数”职责 | 统计可能按来源/版本/作者数放大 | 明确统计计数单位为 `WorkEntity`，来源/版本/关系均不参与 |
| 机构发生时归属守卫 | M22；BR-513；SR-06；SPEC T35 | `DGM-ACTIVITY-04` 有归属口径说明，但无“不得用当前单位”的显式守卫 | 最容易出现“用学者现单位统计成果”的口径错误 | 在活动图与协调者职责补显式守卫 |
| 机构规范名归并 | M23/M24；FR11-S08 E2；CL-05 | 全局无 `Institution`；活动图无归并步骤 | 同一机构多写法会产生重复行 | 新增机构只读职责并补活动图步骤 |
| 机构维度合计可大于实体数 | M25；BR-IMP-06；FR11-S08 | 全局与活动图未声明该现象 | 复核者会把它当缺陷 | 在口径声明与图中补“合计可大于实体总数”的说明 |
| 六项口径声明同屏 | M11/M32/M43；FR11；BR-514；FR11-S01 | 全局 `DGM-ACTIVITY-02` 有“披露数据范围、时间范围、计数口径与真实/模拟标识”，缺“统计条件”“更新时间”完整六项 | 声明不完整会导致不可复核 | 统一六项清单，并要求条件变更后同步刷新 |
| 空/失败/未更新/部分结果区分 | M38–M47；NF01；FR11-S09/S10；BR-523/528 | `DGM-ACTIVITY-02` 已区分“无可用结果/未更新/样本不足”，但以“结果版本”为对象 | 本包不建结果版本（C-01），需要另一种表达 | R5 决定结果版本对象是否保留（C-01/B-06） |
| 可复核与下钻 | M34–M37；FR11-S11 | `DGM-ACTIVITY-02` 未含下钻/条件回显 | 计数无法与检索对齐时无法验收 | 在活动图补“条目→同条件检索复核”分支 |
| 禁止质量/影响力表述 | M28/M48；BR-522；SRS §2.1 | 全局图无相应固定声明 | 机构排名易被读成“机构优劣” | 在统计入口与结果区固定声明 |
| 只读、不建持久统计结果 | 全图无写消息；05 §4 | `DGM-ACTIVITY-02`/`DGM-STATE-03`/模块细化 FR11-S09 建“统计结果对象＋版本＋生效状态” | **C-01 冲突（取向差异）** | R5/评审决定：保留结果版本对象，或按读时投影建模；两者不能同时默认 |

## 4. 关系差异

### 4.1 `AnalyticsUI ..> GraphCtl`（全局图）

- 全局把统计、排名、热点、网络、路径的读侧统一指向 `GraphCtl`；本 UC 的统计读数不建边、不算路径，因此不依赖 `GraphCtl`。
- **R5 建议：** 保留 `AnalyticsUI` 作为读侧边界时可拆两条依赖（统计读数 / 网络与路径），并明确“统计不调用建边/路径职责”。

### 4.2 `Work -- Source`、`Work -- Version`（全局未标多重性）

- 本 UC 沿用 `WorkEntity 1 : 0..* SourceRecord`、`WorkEntity 1 : 0..* WorkVersion`，但**不**把它们画入本 VOPC：来源数与版本数不参与计数（BR-IMP-02/06）。
- **R5 建议：** 保留既有边并注明“统计与规模核对均按实体计数，不按来源/版本计数”。

### 4.3 新增业务关联（全局无）

- `WorkEntity 0..* : 0..* Institution`（成果发生时机构归属，有证据）。两端均 `0..*` 的理由见 05 §2.2。
- **R5 建议：** 新增该关联并注明“仅含有证据的发生时归属；缺失/未映射不建边、计入机构未知”。

### 4.4 未画的关联（有意不画）

| 关联 | 不画理由 |
|---|---|
| `Authorship → Institution` | 参与者级原值作为 `Authorship` 属性，避免与 `WorkEntity→Institution` 重复表达同一归属结果（05 §2.3） |
| `Scholar → Institution`（当前任职） | 本 UC 明确**不**用学者当前单位推断成果归属（BR-513；SR-06） |
| 统计结果/投影 → 任何实体 | 投影不是业务实体（05 §4；C-01） |

## 5. 与现有活动图/状态图/模块细化的一致性（含必须登记的冲突）

| 全局/细化资产 | 与本 UC 的一致点 | 需登记/需修正的差异 |
|---|---|---|
| [`DGM-ACTIVITY-02`](../../../uml/analytics/DGM-ACTIVITY-02-metrics-refresh.puml)（统计指标生成与口径披露） | “默认只真实”“六项披露”“空/未更新/失败区分”“样本不足标注”“不展示半成品”与本 UC E1–E5、A2–A6、A14 一致 | **C-01（取向差异）：** 该图以“统计结果对象＋版本＋生效状态＋重试”为中心，本 UC 按读时投影建模（任务给定的分析方向；V2.2 只要求披露范围/口径/更新时间）。另：该图未含“统计条件六项中的条件回显”与“下钻复核”分支。**建议 R5 评审结果对象是否保留**；本 UC 不改该图 |
| [`DGM-STATE-03`](../../../uml/analytics/DGM-STATE-03-metrics-visibility.puml)（统计结果可见性状态） | 状态含义（无可用结果/计算中/已生效/待更新/更新失败/已失效）与本 UC 的“空/未更新/失败/部分”可观察结果一一对应 | **C-01 同源：** 本包不建可转移状态的持久对象，因此不复制该状态机；若评审要求保留结果版本，则本 UC 的 `outcome` 分析态需改为该状态机的投影。**本 UC 不修改该图** |
| [`DGM-ACTIVITY-04`](../../../uml/analytics/DGM-ACTIVITY-04-institution-ranking.puml)（机构排名活动图） | 泳道（访客/系统）、“机构发生时归属”“样本不足”“规模上限”“下钻复核”与本 UC E6–E11、A14/A15 一致 | 差异：①未显式写“不得用学者当前单位推断”；②未含“机构规范名/别名归并”步骤；③未声明“机构维度合计可大于实体总数”；④把跨机构分配依赖 `Q-4` 联合冻结（见 C-02）。**建议 R5 补齐并明确口径；本 UC 不改该图** |
| [`DGM-USECASE-02`](../../../uml/analytics/DGM-USECASE-02-analytics.puml)（统计域用例图） | UC-11/UC-12 与父需求 FR11 的对应与统一 UC 清单一致；UC-11 与 UC-12 之间不强制 include | 本 UC 覆盖机构排名**规则**（任务要求），UC-12 覆盖其独立视图；视图分工与追踪归属待确认（B-08） |
| 模块细化 [`analytics-graph.md`](../../../srs/modules/analytics-graph.md) FR11-S01～S12 | 六项披露、类型/年度/领域分组、去重计数、机构发生时归属、未知单列、样本不足、规模上限、可复核、真实/模拟标识与本 UC 逐条对应 | **C-02（文本冲突）：** `FR11-S08` 异常流程 E3 与 `AC-FR11-S08-02` 要求“跨机构归属口径 `Q-4` 未联合冻结前不得输出正式排名、不得预定三个机构各计一次”；而现行基线 `SRS V2.2 BR-IMP-06` 已明确“成果发生时多机构各一次、单机构一次、未知单列”，`AC-IMPL-FR11-01` 也以“机构甲乙各 +1”为验收。**本 UC 以 69 AC 基线为准**（M25；BR-UC11-08），并把该差异交评审确认 `Q-4` 是否已被 BR-IMP-06 取代 |
| 模块细化 FR11 的“学者维度统计” | 与本 UC 的机构维度同源（FR11-S08 主流程 3 提到学者维度合计） | **范围差异：** V2.2 FR11 只要求“类型/年份/领域与机构类别排名”，未要求学者维度计数；本 UC 不建模（03 §4 拒绝 `Scholar`）。若评审要求学者维度，需另立 UC 并补口径 |
| [`01-BCE分析类模型.md`](../01-BCE分析类模型.md) §2/§3 | `AnalyticsUI` 覆盖 UC-11～15；`AnalyticsNetworkCoordinator` 只负责 FR12/IN01 | `01` 未列统计协调者，也未列 `Institution`；**建议 R5 补两行并修正“AnalyticsUI 只有 GraphCtl 依赖”的描述** |

## 6. 追踪文件与登记表（只提建议，不改文件）

| 文件 | 现状 | 建议（由父 Agent 决定） |
|---|---|---|
| [`03-AC-UC-分析模型追踪.md`](../03-AC-UC-分析模型追踪.md) | FR11 两行（`AC-IMPL-FR11-01/02`）的 UC 列为 UC-11/UC-12，分析模型列为 `DGM-USECASE-02 / CLASS-01`；UC-11 反向行只列两条 FR11 AC | 为 FR11 两行与 UC-11 反向行增补 `SEQ-19 / VOPC-15`；是否补 NF01/NF05 的 UC-11 列由父 Agent 决定（本 UC 只提供补充证据，不主张覆盖 UC-17/UC-08/UC-10） |
| [`analysis-class-registry.md`](../automation/analysis-class-registry.md) | 已有 `WorkEntity`、`Authorship` 行；无统计 UI/Coordinator，无 `Institution` | 增量登记 `StatisticsUI`、`StatisticsCoordinator`、`Institution`；`WorkEntity` 行补 `dataOrigin`、`occurredAffiliationFacts[]`（集合语义）、`sourceRefs/versionRefs`（不计数）；`Authorship` 行补“多机构原值是机构归属证据来源”“关系条数不是统计计数单位” |
| [`unresolved-decisions.md`](../automation/unresolved-decisions.md) | D-04“真实/模拟数据展示”与本 UC 的演示模式相关；无统计口径类待决项 | 可评估新增：更新时间语义（B-01）、领域分类层级与分母（B-02/B-09）、样本阈值（B-05）、展示上限（B-07）、结果版本对象是否保留（B-06/C-01）、与 UC-12 的视图分工（B-08）；本包不直接修改该文件 |
| 全局 `DGM-CLASS-01-analysis.puml` | `AnalyticsUI ..> GraphCtl`；无统计协调者、无 `Institution`、`Work` 无 `dataOrigin` | 按 §2–§4 在 R5 统一分析类时处理；本 UC 不修改全局图 |
| `DGM-ACTIVITY-02` / `DGM-STATE-03` / `DGM-ACTIVITY-04` | 以统计结果版本为中心；机构图未含当前单位守卫与归并步骤 | 建议 R5/评审处理（§5），本 UC 不改动这些图 |
| 模块细化 `analytics-graph.md` | `FR11-S08 E3`/`AC-FR11-S08-02` 与 `BR-IMP-06` 冲突（C-02） | 由负责人评审 `Q-4` 是否已被现行基线取代；本 UC 不改模块 SRS |

## 7. 差异结论

UC-11 分析支持以下全局模型调整建议：

1. 保留 `WorkEntity` 作为唯一计数单位，并在其职责中补“数据性质（真实/模拟/混合）”与“成果发生时机构归属事实（集合、带证据、可未知）”；
2. 新增 `Institution`（读侧最小职责：规范名/别名/合并链/未映射），否则“别名合并后不出现重复行”没有承载者；
3. 把“统计读数”从 `AnalyticsUI → GraphCtl` 的单一依赖中分离：统计分布/机构排名 ≠ 合作网络与最短路径；
4. 明确统计口径守卫：默认仅真实、公开、生效、合格；去重实体计数；机构只取有证据的发生时归属；未知单列不进名次；机构维度合计可大于实体数；
5. 统一六项披露（数据范围/时间范围/统计条件/计数口径/更新时间/数据范围-数据性质）并要求条件变更后同步刷新，补下钻复核分支；
6. **评审决定统计结果的对象化程度（C-01）**：保留模块细化的结果版本/生效状态，或采用本包的读时投影；
7. **评审确认 C-02**：`Q-4`（跨机构归属口径）是否已被 `BR-IMP-06` 取代；在此之前本 UC 按现行 69 AC 基线执行；
8. 阈值、分类层级、占比分母、上限与更新时间语义仍需需求确认（B-01～B-09）。

本轮**不直接修改** `DGM-CLASS-01-analysis.puml`、`01-BCE分析类模型.md`、`DGM-ACTIVITY-02`、`DGM-ACTIVITY-04`、`DGM-STATE-03`、`DGM-USECASE-02`、模块细化 `analytics-graph.md` 与共享追踪/登记文件。
