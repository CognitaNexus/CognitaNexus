# UC-15 分析与现有全局模型差异

**版本：** V1.0（2026-10-21）
**比较对象：** [`DGM-CLASS-01-analysis.puml`](../../../uml/analysis/DGM-CLASS-01-analysis.puml)、[`01-BCE分析类模型.md`](../01-BCE分析类模型.md)、[`DGM-USECASE-02-analytics.puml`](../../../uml/analytics/DGM-USECASE-02-analytics.puml)、[`DGM-ACTIVITY-03-collab-path.puml`](../../../uml/analytics/DGM-ACTIVITY-03-collab-path.puml)、[`DGM-ACTIVITY-08-collab-network.puml`](../../../uml/analysis/DGM-ACTIVITY-08-collab-network.puml)、[`DGM-STATE-03-metrics-visibility.puml`](../../../uml/analytics/DGM-STATE-03-metrics-visibility.puml)、模块细化 [`analytics-graph.md`](../../../srs/modules/analytics-graph.md)（IN01-S01～S09、BR-513～530、Q-4/Q-6）、合作图规范 [`FR12-IN01`](../../../spec/FR12-IN01-合作图与最短路径规范.md)、上游 [UC-14](../uc-14/07-global-model-diff.md)、候选类登记表 [`analysis-class-registry.md`](../automation/analysis-class-registry.md)、待决清单 [`unresolved-decisions.md`](../automation/unresolved-decisions.md)、追踪表 [`03-AC-UC-分析模型追踪.md`](../03-AC-UC-分析模型追踪.md)。
**原则：** 全局图只作比对材料，不作为本 UC 识别类的反向答案；本轮不重构全局图、不修改共享登记表、不修改他人 UML 资产与模块 SRS。

## 1. 已存在且一致

| UC-15 类/概念 | 全局对应 | 一致点 | 证据 |
|---|---|---|---|
| 路径查询入口 | `AnalyticsUI`（`..>` `GraphCtl`；“+统计/排名/热点/基础网络/路径”）；`01` §2 `AnalyticsUI`（覆盖 UC-11～15）；`DGM-USECASE-02` 的 `UC-15 探索合作关系（IN01）`、`UC15 .> UC14 : <<extend>>` | 都承认“访客在公开页面按领域/机构筛选网络、查询两名学者最短路径”，且不由实体承担展示 | IN01-R1；`DGM-USECASE-02`；`01-BCE` §2 |
| 论文子图限定 | `GraphCtl` 职责“+四类基础边/论文路径”；`01` §3 `AnalyticsNetworkCoordinator` 关键守卫“非论文边不进IN01”；`DGM-CLASS-01` note“IN01 只取共同论文子图” | 都承认“FR12 四类基础图、IN01 只取论文子图”，路径不得用非论文边 | IN01-R1；`AC-IMPL-FR12-05`；BR-IMP-08/09；`01-BCE` §5.4 |
| 最短＝BFS 边数、等长按稳定 ID | `01` §3 `AnalyticsNetworkCoordinator`“筛选/BFS/等长规则”；`DGM-ACTIVITY-08`“在固定筛选图上以边数做 BFS”“等长路径按 canonical_id 序列字典序选一条”；`DGM-ACTIVITY-03`“按 canonical_id 字典序重建一条最短路径” | 距离定义、等长裁决与“强度不是距离”一致 | IN01-R2；`AC-IMPL-IN01-02`；BR-IMP-09；SPEC §4 |
| 每跳论文证据 | `DGM-ACTIVITY-08`“每跳校验共同论文证据”；`DGM-ACTIVITY-03`“每跳附当前图内合格论文证据”；`DGM-USECASE-02` 的 `INCL_PathEvidence` | 都要求逐跳可查看论文依据 | IN01-R3；BR-518 细化 |
| 无路径/端点无效/超规模可区分 | `DGM-ACTIVITY-08`“端点不可用或无路径→明确无路径/端点无效”“图规模超上限→返回超规模，不截断后伪报全范围”；`DGM-ACTIVITY-03`“提示非法条件或节点不可用”“不存在路径→明确提示并重复声明范围” | 三类反馈可观察 | `AC-IMPL-IN01-03/04`；NF01 |
| 合并后节点唯一、不出现自环 | `01` §4 `Scholar`“同名不合并；认领前仍可公开”；`DGM-ACTIVITY-03`“节点不可用（含已合并跳转）”；BR-530 | 合并解析到当前身份，不重复节点 | BR-530；`AC-IMPL-NF05-02`；SPEC §2 |
| 关系状态门槛 | `DGM-CLASS-01` 的 `Authorship` note“四类合法参与角色分别核验；仅论文作者关系进入 IN01”；`01` §5.1/§5.3；`DGM-ACTIVITY-03` note“待核验/争议/已排除关系不建对应边” | 不合格关系不进入论文子图、不作证据 | `AC-IMPL-FR12-04`；§11.3；BR-519 细化 |
| 路径不评价质量 | `BR-522` 细化；SRS §2.1 | 路径长度与论文证据数不代表质量/影响力/学术水平 | BR-522 细化；SRS §2.1 |
| 披露与结果同屏 | `DGM-USECASE-02` 的 `INCL_Caliber`（UC-11～14 的 `<<include>>`）、`INCL_Path ..> INCL_Caliber : <<include>>`；BR-514 细化 | 范围/口径/版本声明随结果披露，不由入口决定公开性 | IN01-S06 细化；BR-514 细化；NF05 |
| 规模上限不截断 | `DGM-ACTIVITY-08`“图规模超上限→返回超规模，不截断后伪报全范围”；`DGM-ACTIVITY-03`“披露规模及上限，要求收窄，不截断后伪报最短路径” | 超限必须可观察，不允许截断冒充完整结果 | `AC-IMPL-IN01-04`；SPEC §3；BR-528 细化 |

## 2. 需要更名/别名统一

| 本 UC 名称 | 全局/同族名称 | 差异 | R5 建议 |
|---|---|---|---|
| `CollaborationNetworkUI`（本 UC 复用上游网络视图入口） | `AnalyticsUI`（`DGM-CLASS-01`）；`01` §2 同时写 `PublicPortalUI`（覆盖 UC-15）与 `AnalyticsUI`（覆盖 UC-11～15）；同族 UC-11 `StatisticsUI`、UC-12 `InstitutionRankUI`、UC-13 `HotspotUI`、UC-14 `CollaborationNetworkUI` | 全局把“统计/排名/热点/网络/路径”合在一个边界上，且 `01` 与 `DGM-CLASS-01` 名称本身不一致；上游 UC-14 已登记 `CollaborationNetworkUI` 并注明“后续参与 UC-15” | R5 三选一并保持全站一致：①`AnalyticsUI` 内显式区分五类能力；②网络与路径共用同一视图边界（本 UC 当前采用，见 B-11）；③为路径/筛选独立边界。同时统一 `AnalyticsUI`/`PublicPortalUI` 命名（D-11） |
| `CollaborationPathCoordinator` | 全局 `GraphCtl`（“+四类基础边/论文路径”）；`01` §3 `AnalyticsNetworkCoordinator`（“四类合格基础图、论文子图、筛选/BFS/等长规则”） | 全局把**基础建边**与**论文子图筛选/路径**放在同一个协调者；上游 UC-14 用 `CollaborationNetworkCoordinator` 只做建边 | R5 拆清两类职责：基础网络建边（UC-14）≠ 论文子图筛选与最短路径（UC-15，即本类）；禁止把 BFS/图版本/1 万-10 万上限塞进基础网络协调者，也禁止把四类成果建边塞进路径协调者 |
| `PathResult` | 全局**无对应类**（`GraphCtl` 只有职责文字“论文路径”；`DGM-CLASS-01` 无路径/结果类） | 没有承载“有序节点序列＋跳数＋逐跳边与论文证据＋图版本”的对象，`AC-IMPL-IN01-01/02` 的“逐跳证据”“同版本同条件同一条”无承载者；实现时易把路径写成页面临时字符串 | R5 新增 `PathResult`（读时投影）：节点序列/跳数＝边数/逐跳边引用/逐跳 k/图版本/等长规则；并注明它不是持久结果对象（C-01） |
| `CollaborationEdge`（论文子图投影） | 全局**无对应类**；上游 UC-14 已建议新增四类派生边 | 同一派生关系在两个图范围上的投影：UC-14 的 `weight` 含四类成果，本 UC 的 k 只含论文（BR-518 要求分别声明） | R5 用一个类承载两种范围，并在职责中写明“基础范围 weight＝四类去重成果数；论文子图范围 k＝去重论文数；两者分别声明”；**不得**把 k 当作 `weight` 的子集或替代 |
| `WorkEntity.fieldClassification` / `classificationMappingVersion` | 全局 `Work` 无发生时领域分类与映射版本；登记表 `WorkEntity` 行只有 `field` | 筛选要求“发生时适用、已核验的分类及映射版本”（SPEC §3），与登记表 `field` 的语义（可能为当前值）不同 | R5 在 `Work` 职责补“发生时领域分类＋映射版本”，并注明不得用当前值回填历史（与 UC-12/UC-14 同一建议） |
| `Authorship.occurredInstitutionRef` | 全局 `Authorship` 只有“+角色/顺序/证据()/+独立关系状态()”；登记表把机构写成 `WorkEntity.occurredInstitution` | 机构筛选中“边的两端在该论文上均须命中”，是**关系级**发生期证据；成果级单值会丢失多作者多机构情形 | R5 在 `Authorship` 补发生期机构证据引用，并统一与 `WorkEntity.occurredInstitution` 的口径（D-11；B-02） |
| `Scholar.mergedIntoRef` | 全局 `Scholar` 无合并指向 | 端点合并后需解析到当前 `canonicalId`，不得重复节点或自环（BR-530；IN01-S07） | R5 在 `Scholar` 责任中补 merge 解析契约（与 UC-10 的 `IdentityCandidate` 分工） |
| UC-15 用例名 | 模块细化“UC-15 探索合作关系（IN01）”；SRS/统一清单“筛选网络及论文最短路径”（追踪表与 progress 用后者） | 名称一致，但模块名较抽象，易被读成“浏览网络”而非“筛选＋路径” | R5/需求侧统一为“筛选合作网络及查询论文最短路径”，并在用例说明中标明“只在论文子图” |

| 本 UC 名称 | 登记表名称 | 处理 |
|---|---|---|
| `CollaborationNetworkUI` | 登记表**已有** UC-14 行，“后续参与 UC-15” | 本包复用；由父 Agent 决定是否把“UC-15”写实到该行 |
| `CollaborationPathCoordinator` | 无 | 建议新增一行（首次发现 UC-15）：职责＝论文子图筛选＋BFS 最短路径＋逐跳论文证据；关键守卫＝只用论文边、长度＝边数、等长字典序、超限拒绝、不推断 |
| `PathResult` | 无 | 建议新增一行（读时投影，不持久）：`nodeSequence/hopCount/hopEdgeRefs/hopEvidenceCounts/graphVersionRef/tieBreakRule/outcome` |
| `CollaborationEdge` | 登记表已有 UC-14 行 | 建议在该行补“论文子图投影：证据仅论文、计数为 k，与 weight 分别声明” |

## 3. 职责差异/需要补充

| 项目 | 本 UC 证据 | 全局现状 | 差异/影响 | R5 处理 |
|---|---|---|---|---|
| 建边与路径分离 | M15–M22 与 M51；IN01-R1；`AC-IMPL-FR12-05` | `GraphCtl` 同时承担“四类基础边/论文路径”；`01` §3 的 `AnalyticsNetworkCoordinator` 同时写“四类合格基础图、论文子图、筛选/BFS/等长规则” | 若按全局图实现，路径规则（图版本、1 万/10 万上限、论文限定、等长裁决）可能反渗进四类基础网络，导致基础网络被裁成论文图或被施加上限 | R5 拆分两类职责并各写守卫（与 UC-14 同一建议） |
| 筛选口径在发生时 | M5/M6/M19；BR-513 细化；SPEC §3；§11.5 | `GraphCtl`/`AnalyticsNetworkCoordinator` 未写筛选口径；`DGM-USECASE-02` 的 `INCL_Filter` 只写“按领域/机构/时间窗口筛选网络” | 最易出错点：用学者当前机构或当前领域回填历史，导致筛出本不该出现的历史边 | 在协调者职责写明“机构＝发生时证据、领域＝发生时分类与映射版本、未知不命中、不用当前值回填” |
| 先筛后建边 | M19–M21；SPEC §3；BR-UC15-12 | 全局未区分“筛选后重建边/k”与“仅隐藏节点边” | 若仅隐藏，k 会保留被筛掉的论文，导致逐跳证据计数与结果不符 | 在职责中写明“先筛论文与作者证据，再重建边与 k” |
| k 与 weight 分离 | M17/M18/M35；BR-518 细化；IN01-S04-02 | 全局无边/证据对象，也没有“两种计数分别声明”的要求 | 若混用，会把 FR12 的 3（2 论文＋1 专利）显示成本跳证据数 | 在 `CollaborationEdge` 与披露职责中写“k 只计论文；weight 属基础网络；分别声明” |
| 每跳证据非空 | M18/M31–M36；IN01-R3 | `DGM-ACTIVITY-08` 已写“每跳校验共同论文证据”；`GraphCtl` 未写 | 空证据跳会成为“看似最短但无依据”的路径 | 在协调者与 `PathResult` 职责写“每跳 k≥1；否则该边不属于论文子图” |
| 等长规则的展示 | M29/M37/M39；`AC-IMPL-IN01-02` | `DGM-ACTIVITY-08`/`DGM-ACTIVITY-03` 只有“按 canonical_id 字典序选一条”，未要求说明规则 | 只给结果不说明规则时，访客无法复核“为什么是这条” | 在披露职责与 UI 职责补“同屏说明等长裁决规则” |
| 图版本与生效版本 | M4/M46–M48；`AC-IMPL-IN01-02`；NF05 | `GraphCtl` 无图版本职责；`DGM-STATE-03` 以结果状态为中心；SPEC §2 提到“旧快照” | 图版本可能是查询参数标识，也可能是持久快照；两种实现的验收与失效语义不同 | R5/评审先定图版本对象化程度（C-01/B-08），再据此写职责；本 UC 按“查询内固定版本＋读时投影”实现 |
| 超规模与展示上限 | M22；`AC-IMPL-IN01-04`；SPEC §3；Q-6 | 全局只说“超限拒绝”；模块 Q-6 另提 150/400 展示上限候选 | 1 万/10 万（测试图规模/超限分支）与 150/400（展示上限）若混用，会导致“合法图被判超规模”或“截断后仍称完整” | 分别声明两种上限的用途；展示子集须标“部分结果”（B-03/C-06） |
| 筛选项（是否含年份/多值） | M3/M19；B-01 | `DGM-USECASE-02` 的 `INCL_Filter` 写“按领域/机构/时间窗口筛选”；基线 IN01-R1 只写领域/机构 | 若把时间窗口当已批准条件，会与基线不一致；若拒绝年份，又与既有用例图不一致 | 待需求确认（B-01）；本 UC 以基线为准（领域/机构），年份条件不建模 |
| 端点相同的行为 | M25；SPEC §4；`DGM-ACTIVITY-03` | 模块 IN01-S03 A1 要求“提示起点与终点相同，不返回路径” | 两种行为都可验收，但结果可观察性不同 | 待确认（C-05）；本 UC 按 SPEC/活动图建模 0 跳 |
| 等长路径总条数 | M29/M37；B-09 | 模块 IN01-S08/BR-525 要求“全部列出或说明规则与总条数”；基线只要求一条＋规则 | 若要求总条数，需额外计算等长路径总数（可能影响性能目标） | 待确认（B-09/C-02）；本 UC 只保证“一条＋说明规则” |

## 4. 关系差异

### 4.1 `AnalyticsUI ..> GraphCtl`（全局图）

- 全局把统计、排名、热点、网络、路径的读侧统一指向 `GraphCtl`；本 UC 的路径与筛选必须**只**依赖论文子图，若直接沿用该依赖，会把四类基础网络与路径规则混在一条依赖上。
- **R5 建议：** 保留 `AnalyticsUI` 作为读侧边界时可拆多条依赖（分布统计／机构排名／热点／基础网络／路径），并明确“路径与筛选不调用基础网络建边职责，只读取其论文证据投影”。

### 4.2 `Work -- Source`、`Work -- Version`、`Scholar -- Authorship`、`Authorship -- Work`（全局未标多重性）

- 本 UC 沿用 `WorkEntity 1 : 0..* SourceRecord`、`WorkEntity 1 : 0..* WorkVersion`、`Authorship 0..* : 1 WorkEntity`、`Authorship 0..* : 0..1 Scholar`，但**不**把来源/版本画入本 VOPC：来源数与版本数不参与 k（`AC-IMPL-FR12-02`；`AC-IMPL-FR16-02`）。
- **R5 建议：** 保留既有边并注明“IN01 路径证据按去重论文计，不按来源/版本计”；`Authorship→Scholar` 需明确 `0..1`（身份未定时不进入论文子图）。

### 4.3 新增业务关联（全局无）

- `CollaborationEdge 0..* : 2 Scholar`（两端节点，无序对、两学者不同）；`CollaborationEdge 0..* : 1..* WorkEntity`（合格共同论文证据，仅论文）；`PathResult 0..* : 1..* Scholar`（有序节点序列）；`PathResult 0..* : 0..* CollaborationEdge`（逐跳边），理由见 05 §2.2/§2.3。
- **与 UC-14 的关系：** UC-14 已建议新增 `CollaborationEdge 0..*:2 Scholar` 与 `0..*:1..* WorkEntity`（四类证据）。本 UC 的论文投影不是另建一套同名关系，而是同一关系在论文子图范围上的使用；区别在**证据集合内容与计数口径**（论文 vs 四类），不在关联结构。
- **R5 建议：** 新增 `PathResult` 及其两条关联，并注明“跳数＝边数＝节点数−1；每跳至少一篇论文证据；同一图版本同条件返回同一条；该结果不是持久对象”；`CollaborationEdge` 的两条关联注明“两种范围分别声明计数”。

### 4.4 未画的关联（有意不画）

| 关联 | 不画理由 |
|---|---|
| `CollaborationEdge → Institution` / `→ Field` | 同机构/同领域不是合作证据（`AC-IMPL-FR12-04`；IN01-R3）；机构/领域只作为筛选条件与发生时证据引用 |
| `CollaborationEdge → SourceRecord` / `→ WorkVersion` | 来源数与版本数不放大 k（`AC-IMPL-FR12-02`；`AC-IMPL-FR16-02`） |
| `PathResult → WorkEntity`（直接关联） | 逐跳证据经 `PathResult → CollaborationEdge → WorkEntity` 表达；再加直连会重复同一事实并削弱“证据属于哪一跳”的对应（05 §2.4） |
| `Scholar → Institution`（当前任职） | 本 UC 机构筛选用发生期证据，不用当前任职；`Scholar → Institution` 会暗示可用当前任职筛选历史边（§11.5；BR-513 细化） |
| `Scholar → UserProfile` | 账号与普通主页不是端点标识，也不是合作证据（§12） |
| `CollaborationEdge → CollaborationEdge` / `PathResult → PathResult` | 边键与“等长取一条”已保证无向唯一与唯一结果；自关联会与“唯一一条/一条结果”冲突 |
| 图快照/结果对象 → 任何实体 | 投影不是业务实体（05 §4；C-01；B-08） |

## 5. 与现有用例图/活动图/状态图/模块细化的一致性（含必须登记的冲突）

| 全局/细化资产 | 与本 UC 的一致点 | 需登记/需修正的差异 |
|---|---|---|
| [`DGM-USECASE-02`](../../../uml/analytics/DGM-USECASE-02-analytics.puml)（统计域用例图） | `UC-15 探索合作关系（IN01）`、`UC15 .> UC14 : <<extend>>`；`INCL_Filter`（按领域/机构/时间窗口筛选网络）、`INCL_Path`（查询两名学者间最短合作路径）、`INCL_PathEvidence`（沿路径逐跳查看论文依据）与本 UC E1/E12/E15/E16 一致；`INCL_Path ..> INCL_Caliber : <<include>>` 与本 UC E17 一致；`INCL_Evidence` 只挂 UC-14，说明“边证据”与“路径证据”是两个动作 | **C-04（文本冲突，继承 UC-14 C-04）：** 图例第 3 条“FR12 和 IN01 均只接受合格共同论文证据，不使用非论文成果建边”与基线“FR12 四类共同成果”冲突；本 UC 侧口径正确（IN01 只论文），但该图例仍需修订。**B-01：** `INCL_Filter` 的“时间窗口”条件未在基线 IN01-R1 出现，需确认是否成立。**B-09：** 图未表达“等长只取一条并说明规则”，`INCL_Path` 的粒度不足以体现 `AC-IMPL-IN01-02` |
| [`DGM-ACTIVITY-03`](../../../uml/analytics/DGM-ACTIVITY-03-collab-path.puml)（模块 IN01 活动图） | “设置发生时领域/机构筛选”“只采用真实公开论文和合格参与关系”“同一去重论文对作者对只贡献1”“起点等于终点→0 跳”“从目标 BFS”“按 canonical_id 字典序重建一条”“每跳附当前图内合格论文证据”“节点>10000 或边>100000→披露规模及上限，不截断后伪报”与本 UC E5/E8/E15/E16/E13/A6 一致 | **差异：** ①未表达 k 与 FR12 `weight` 分别声明（B-06）；②未表达“未知机构/领域不命中、不用当前任职回填”（B-02/B-04）；③未表达“等长裁决规则须向访客说明”（BR-UC15-08）；④把“条件非法”与“节点不可用”合为一个提示，而 `AC-IMPL-IN01-03` 要求“无路径/节点不可用”可区分（BR-UC15-14）。本 UC 补齐，建议后续修订该图 |
| [`DGM-ACTIVITY-08`](../../../uml/analysis/DGM-ACTIVITY-08-collab-network.puml)（基础网络与 IN01 路径活动图） | 与基线口径一致：基础网络四类；IN01 只取合格共同论文子图；“共同专利/项目/获奖不得成为路径边”；“排除同机构/同领域推断、待核验、争议与已排除”；“在固定筛选图上以边数做 BFS”；“等长路径按 canonical_id 序列字典序选一条”；“每跳校验共同论文证据”；“图规模超上限→返回超规模，不截断后伪报全范围” | **缺口：** 未表达“k 与 weight 分别声明”“等长规则说明须同屏”“端点不存在/未公开/已合并分类提示”“图版本标识与失效反馈”。本包不修改该图，登记为后续修订建议 |
| [`DGM-STATE-03`](../../../uml/analytics/DGM-STATE-03-metrics-visibility.puml)（统计结果可见性状态） | 状态含义（无可用结果/计算中/已生效/待更新/更新失败/已失效）与本 UC 可观察结果（无匹配边/无路径/已结果/未更新/失败/版本失效）可对应 | **C-01（取向差异）：** 本包不建可转移状态的持久结果对象；若评审要求保留结果版本，则 `PathResult.outcome` 需改为该状态机投影。本 UC 不修改该图 |
| 模块细化 IN01-S01（按领域筛选） | 领域筛选、范围与语义声明、无结果提示与本 UC E12/E17/A2 一致 | A1“任一领域命中”语义未在基线出现（B-01）；E2“分类映射覆盖率”披露属分析推导（B-02/B-04） |
| 模块细化 IN01-S02（按机构筛选） | 机构筛选、发生时归属（BR-513）、口径声明与本 UC E12/E17 一致 | **C-03（范围差异）：** 该细化把筛选挂在**基础合作网络**上；基线 IN01-R1 与 SPEC §3 只在论文子图筛选。本 UC 以基线为准。另："相关"口径候选 Q-4 未决（B-02） |
| 模块细化 IN01-S03（最短路径查询） | BFS 边数、长度标注、起点终点校验与本 UC E14/E15 一致 | **C-05：** A1“起点与终点相同→提示、不返回路径”与 SPEC §4/`DGM-ACTIVITY-03` 的 0 跳冲突；待确认 |
| 模块细化 IN01-S04（路径论文证据） | 逐跳论文清单（标题/类型/时间/来源/归属状态）、去重条数 k、详情入口、非论文不得替代与本 UC E16 一致 | A1“查看全部跳”属界面细化，本 UC 不定义分页与展示条数；`k≠weight` 已由 BR-518 支撑 |
| 模块细化 IN01-S05（无路径提示） | 明确无路径提示、同屏重复范围、不空图/不报错页、不无限加载与本 UC A7/M49 一致 | A1“展示两学者直接合作者”属可选增强，基线未要求，本 UC 不建模 |
| 模块细化 IN01-S06（范围与不可推断） | 范围声明、“范围外视为不存在、不推断”、固定声明与本 UC E17/M39/M51 一致 | 无实质差异 |
| 模块细化 IN01-S07（节点不可用与合并） | 不存在/未公开/已合并分类提示、合并后门户入口、不重复节点、提示不泄露未公开信息与本 UC A3–A5/A15 一致 | 无实质差异 |
| 模块细化 IN01-S08（等长路径展示） | 等长识别、规则说明、长度标注与本 UC M29 一致 | **C-02（文本冲突）：** “全部列出，或按约定规则展示并说明规则与总条数”与基线 `IN01-R2`/`AC-IMPL-IN01-02`“字典序选择一条”不同；本 UC 以基线为准，总条数披露待确认（B-09） |
| 模块细化 IN01-S09（创新独立性） | “关闭 IN01 全部增强后 FR12 仍须完整可用与可验收”与本 UC“不改写基础网络”一致 | 无实质差异；支撑 BR-UC15-20 |
| 模块细化 BR-513/514/518/519/524/525/526/528/530 | BR-513（发生时机构）、BR-514（披露）、BR-518（k 与 weight 分别声明）、BR-519（关系资格）、BR-524（范围外不存在）、BR-525（长度＝边数）、BR-526（公开与最小化）、BR-528（规模上限）、BR-530（合并后唯一）均与本 UC 一致 | BR-525 的“全部列出或说明总条数”见 C-02；BR-528 与 Q-6 的展示上限口径见 C-06/B-03；BR-523 的“上一版可用结果”见 C-01/B-08 |
| 模块细化 Q-4/Q-6 | Q-4 机构口径候选、Q-6 展示上限候选均为未决参数 | 本 UC 不采用候选为已批准规则：Q-4 → B-02，Q-6 → B-03/C-06 |
| [`01-BCE分析类模型.md`](../01-BCE分析类模型.md) §2/§3/§4/§5.4 | `AnalyticsUI` 覆盖 UC-11～15；`AnalyticsNetworkCoordinator` 关键守卫“同机构/同领域不建边；非论文边不进IN01；超限拒绝”；§5.4“FR12四类／IN01论文”与本 UC 一致 | `01` 未列 `PathResult`，未写“k 与 weight 分别声明”“等长规则说明”“发生时筛选口径”“图版本标识”；`AnalyticsUI`（`DGM-CLASS-01`）与 `PublicPortalUI`（`01`）命名不一致 → 建议 R5 统一并补齐 |
| 合作图规范 [`FR12-IN01`](../../../spec/FR12-IN01-合作图与最短路径规范.md) §1–§5 | 资格分层、canonical 边键、min/max、自环、论文子图、筛选口径（发生时）、规模上限、BFS＋字典序重建、逐跳证据、T37～T40 样例与本 UC 一致 | §3 自述“该细化为本次整改 RC，须在复审中确认”→ 筛选细则按待确认（B-01/B-02）；§2“旧快照仍使用旧快照 ID”隐含快照对象（C-01/B-08） |

## 6. 追踪文件与登记表（只提建议，不改文件）

| 文件 | 现状 | 建议（由父 Agent 决定） |
|---|---|---|
| [`03-AC-UC-分析模型追踪.md`](../03-AC-UC-分析模型追踪.md) | IN01 四行（55～58）主 UC 为 UC-15，模型列 `DGM-USECASE-02 / ACT-08 / CLASS-01`；NF02 两行（60～61）含 UC-15；NF05 两行（65～66）含 UC-15 | 可增补本 UC 的 `SEQ-23 / VOPC-19`；注意追踪表引用的 `ACT-08` 实际对应 `DGM-ACTIVITY-08-collab-network`（基础网络＋IN01 路径），模块侧另有 `DGM-ACTIVITY-03`，建议登记“两张活动图并存”的对应关系 |
| [`analysis-class-registry.md`](../automation/analysis-class-registry.md) | `CollaborationNetworkUI` 行已注“后续参与 UC-15”；`CollaborationEdge` 行为四类边；无 `CollaborationPathCoordinator`/`PathResult` | 建议新增 `CollaborationPathCoordinator`（Control）与 `PathResult`（读时投影）两行；在 `CollaborationEdge` 行补“论文子图投影、k 与 weight 分别声明”；在 `WorkEntity` 行补 `fieldClassification`/`classificationMappingVersion`；在 `Authorship` 行补 `occurredInstitutionRef` |
| [`unresolved-decisions.md`](../automation/unresolved-decisions.md) | 已有 D-08（图版本/超规模阈值实现口径）、D-11（命名统一） | 可评估新增：筛选维度与取值语义（B-01）、机构与领域口径（B-02，与 Q-4/D-04）、规模上限用途区分（B-03/C-06）、图版本对象化与上一版结果（B-08/C-01）、等长总条数披露（B-09）、端点相同行为（C-05）；本包不直接修改该文件 |
| 全局 `DGM-CLASS-01-analysis.puml` | `AnalyticsUI ..> GraphCtl`；`GraphCtl` 无 `CollaborationEdge`、无 `PathResult`、无筛选口径与图版本守卫 | 按 §2–§4 在 R5 统一分析类时处理；本 UC 不修改全局图 |
| `DGM-USECASE-02` / `DGM-ACTIVITY-03` / `DGM-ACTIVITY-08` / `DGM-STATE-03` | 见 §5 | 建议 R5/评审处理图例冲突（C-04）、活动图缺口（k/weight、未知口径、规则说明、端点分类）、结果对象取向（C-01）；本 UC 不改动这些图 |
| 模块细化 `analytics-graph.md` | IN01-S01/S02（筛选范围）、IN01-S03 A1（端点相同）、IN01-S08/BR-525（全部列出或总条数）、Q-6（展示上限）与基线存在差异 | 由负责人评审确认 C-02/C-03/C-05/C-06；本 UC 不改模块 SRS |

## 7. 差异结论

UC-15 分析支持以下全局模型调整建议：

1. 在分析域内**拆清**“基础网络建边（UC-14）”与“论文子图筛选与最短路径（UC-15）”两组职责，禁止路径规则反渗进四类基础网络；
2. 新增**读时投影** `PathResult`（节点序列/跳数＝边数/逐跳边引用/逐跳 k/图版本/等长规则），否则 `AC-IMPL-IN01-01/02` 无承载者；同时明确它不是持久结果对象；
3. 明确 `CollaborationEdge` 的两种图范围：基础范围 `weight`＝四类去重成果数；论文子图范围 k＝去重论文数；**两者分别声明**（BR-518）；
4. 补 `WorkEntity` 的发生时领域分类＋映射版本、`Authorship` 的发生期机构证据、`Scholar` 的合并解析契约；
5. 把筛选口径写成可验证职责：发生时证据、未知不命中、跨维度交集、先筛后建边；
6. 把“等长规则说明”“图版本标识”“状态分类（无匹配边/无路径/端点不可用/超规模/失败/超时）”列为结果区必备披露项；
7. **评审决定图版本与结果的对象化程度（C-01/B-08）**：查询内固定版本＋读时投影，或保留持久快照/结果版本；
8. **评审确认 C-02**：模块 IN01-S08/BR-525“全部列出或说明总条数”是否仍有效（基线要求字典序一条）；
9. **评审确认 C-03**：筛选是否只作用于论文子图（本包按基线），还是扩展到四类基础网络（模块 IN01-S01/S02 口径）；
10. **评审确认 C-05**：端点相同返回 0 跳还是拒绝；
11. **评审确认 C-06/B-03**：区分 `AC-IMPL-IN01-04` 的 1 万/10 万（测试图规模与超限分支）与 Q-6 的 150/400（展示上限）；
12. **修订 C-04**：`DGM-USECASE-02` 图例“FR12 只接受论文证据”应按基线改为“FR12 四类；IN01 仅论文子图”；
13. 补/修订活动图缺口：k 与 weight 分别声明、未知机构/领域不命中、等长规则说明、端点不可用三类分类；
14. 筛选维度与取值语义（B-01）、机构与领域口径（B-02）、未知/时间未知（B-04）、作者证据门槛（B-05）、k 展示（B-06）、版本标识（B-07）、总条数（B-09）、超时阈值文案（B-10）、视图归属与命名（B-11）、端点选择（B-12）仍需需求确认。

本轮**不直接修改** `DGM-CLASS-01-analysis.puml`、`01-BCE分析类模型.md`、`DGM-USECASE-02`、`DGM-ACTIVITY-03`、`DGM-ACTIVITY-08`、`DGM-STATE-03`、模块细化 `analytics-graph.md`、合作图规范与共享追踪/登记文件。
