# UC-14 分析与现有全局模型差异

**版本：** V1.0（2026-10-21）
**比较对象：** [`DGM-CLASS-01-analysis.puml`](../../../uml/analysis/DGM-CLASS-01-analysis.puml)、[`01-BCE分析类模型.md`](../01-BCE分析类模型.md)、[`DGM-USECASE-02-analytics.puml`](../../../uml/analytics/DGM-USECASE-02-analytics.puml)、[`DGM-ACTIVITY-03-collab-path.puml`](../../../uml/analytics/DGM-ACTIVITY-03-collab-path.puml)、[`DGM-STATE-03-metrics-visibility.puml`](../../../uml/analytics/DGM-STATE-03-metrics-visibility.puml)、模块细化 [`analytics-graph.md`](../../../srs/modules/analytics-graph.md)、合作图规范 [`FR12-IN01`](../../../spec/FR12-IN01-合作图与最短路径规范.md)、同族 UC [UC-11](../uc-11/README.md)/[UC-12](../uc-12/README.md)/[UC-13](../uc-13/README.md)、候选类登记表 [`analysis-class-registry.md`](../automation/analysis-class-registry.md)、待决清单 [`unresolved-decisions.md`](../automation/unresolved-decisions.md)、追踪表 [`03-AC-UC-分析模型追踪.md`](../03-AC-UC-分析模型追踪.md)。
**原则：** 全局图只作比对材料，不作为本 UC 识别类的反向答案；本轮不重构全局图、不修改共享登记表、不修改他人 UML 资产与模块 SRS。

## 1. 已存在且一致

| UC-14 类/概念 | 全局对应 | 一致点 | 证据 |
|---|---|---|---|
| `CollaborationNetworkUI`（基础合作网络视图入口） | `AnalyticsUI`（`..>` `GraphCtl`；“+统计/排名/热点/基础网络/路径”）；`01` §2 `AnalyticsUI`（覆盖 UC-11～15）；`DGM-USECASE-02` 的 `UC-14 查看专家合作关系网络` ＋ `UC14 ..> INCL_Caliber` ＋ `INCL_Evidence ..> UC14` | 都承认“访客在公开页面查看合作网络、查看边证据”，且不由实体承担展示；披露与证据查看为入口内的可观察能力 | FR12；`DGM-USECASE-02`；`01-BCE` §2 |
| `WorkEntity`（强度贡献单位） | 全局 `Work`（“+去重规范值()/+成果类型()/+生效状态()”）；`01` §4“去重后的四类成果规范实体；来源数/版本数不等于成果数” | 去重实体是唯一成果单位；来源数/版本数不放大强度；四类成果都存在 | BR-IMP-02/08；`AC-IMPL-FR12-01/02`；`AC-IMPL-FR16-02`；INV-02 |
| `Authorship`（参与关系，四类角色＋独立状态） | 全局 `Authorship`（“+角色/顺序/证据()/+独立关系状态()”；`Scholar -- Authorship`、`Authorship -- Work`）；`01` §4“管理员审核不等于本人确认；解除不删成果” | 角色/顺序/证据与独立状态；关系不属于成果；审核≠本人确认 | FR15；§6.4；§11.3；`01-BCE` §5 |
| `Scholar`（节点身份） | 全局 `Scholar`（“+已核验身份()/+公开学者档案()”）；`01` §4“同名不合并；认领前仍可公开” | 节点是平台采集/核验的学者身份，不是账号或普通主页 | FR12 明文；BR-IMP-03；§12 |
| FR12 四类基础边 ＋ IN01 论文子图的分工 | `DGM-CLASS-01` 的 `GraphCtl`（“+四类基础边/论文路径”）与 note“FR12 基础网络可用四类合格共同成果；IN01 只取共同论文子图”；`01` §3 `AnalyticsNetworkCoordinator` | 都承认“基础网络四类成果、路径只取论文子图”，且不把两者混成一张论文图 | FR12 明文；AC-IMPL-FR12-01/05；`01-BCE` §5.4 |
| 关系状态门槛（待核验/争议/已排除不成边） | `DGM-CLASS-01` 的 `Authorship` note“四类合法参与角色分别核验；仅论文作者关系进入 IN01”；`01` §5.1/§5.3 | 都承认关系状态独立于成果状态，且不合格关系不产生合作 | AC-IMPL-FR12-04；§11.3；FR12-S05（细化） |
| 真实/模拟/混合区分 | FR11-S12/S03 与 `DGM-ACTIVITY-02` 的“真实/模拟标识与过滤”；BR-IMP-07 | 默认现实网络只取真实数据；模拟/混合必须标识 | BR-IMP-07；§7.1 |
| “强度不代表质量/影响力” | BR-522（细化）；FR12-S03（细化）；`DGM-ACTIVITY-02` 的口径披露 | 关系强度只表示共同成果实体数 | FR12-S03（细化）；SRS §2.1 |
| 统计口径与数据范围披露 | `DGM-USECASE-02` 的 `INCL_Caliber` 对 UC-11/12/13/14 全部 `<<include>>`；BR-514（细化） | 披露与结果同屏，不由页面入口决定公开性 | FR12-S01/S03/S05（细化）；NF05 |
| 关系强度与证据一致、边可追溯 | `GraphCtl` note 未写强度一致；模块 FR12-S02/S03（细化）与 BR-IMP-08 明确 | 边必须有成果证据；强度＝共同成果实体数 | BR-IMP-08；AC-IMPL-FR12-01/02/03 |

## 2. 需要更名/别名统一

| 本 UC 名称 | 全局/同族名称 | 差异 | R5 建议 |
|---|---|---|---|
| `CollaborationNetworkUI` | `AnalyticsUI`（`DGM-CLASS-01`；`01` §2 `PublicPortalUI` 也覆盖 UC-14/15）；同族 UC-11 `StatisticsUI`、UC-12 `InstitutionRankingUI`、UC-13 `HotspotUI` | 全局把“统计/排名/热点/网络/路径”合在一个 `AnalyticsUI` 上（`01` 写 `PublicPortalUI`，`DGM-CLASS-01` 写 `AnalyticsUI`，两处本身不一致）；同族三个 UC 已各自使用独立边界名 | R5 决定：①`AnalyticsUI` 内显式区分“分布统计/机构排名/热点/网络与路径”，或②网络与路径共用同一视图边界，或③保留独立 `CollaborationNetworkUI`。三种方案都不得改变本 UC 的业务规则；同时统一 `AnalyticsUI`/`PublicPortalUI` 命名（D-11） |
| `CollaborationNetworkCoordinator` | 全局 `GraphCtl`（“+四类基础边/论文路径”）；`01` §3 `AnalyticsNetworkCoordinator`（“四类合格基础图、论文子图、筛选/BFS/等长规则”） | 全局把**建边**与**论文子图筛选/路径**放在同一个协调者；本 UC 只承担建边与证据/披露，路径与筛选属 UC-15 | R5 在分析域内拆清：分布统计 ≠ 机构排名 ≠ 热点 ≠ **基础网络建边** ≠ **论文子图筛选与路径**；禁止把 BFS/图版本塞进基础网络协调者，也禁止把四类成果建边塞进论文路径协调者 |
| `CollaborationEdge` | 全局**无对应类**（`GraphCtl` 只有职责文字“四类基础边”；`DGM-CLASS-01` 无 edge/strength 类） | 没有承载“学者对＋强度＋证据集合”的对象，`AC-IMPL-FR12-01/02/03` 的“边可回溯、强度＝证据条数”无承载者，实现时容易把强度写成节点属性或临时算在页面上 | R5 新增派生 `CollaborationEdge`（读侧最小职责：边键/两端 canonical_id/证据成果集合/强度），或明确由 `GraphCtl` 内的显式“边与证据集合”职责承担并写成可验证契约。**不得**因此新增持久“图快照/结果版本”类（见 §3 C-01） |
| `WorkEntity.dataOrigin`、`evidencePublicFields` | 全局 `Work` 无数据类别属性、无证据展示字段集合 | `BR-IMP-07` 要求默认现实网络只取真实；证据清单要求标题/类型/时间/来源/归属状态五项公开字段 | R5 在 `Work` 职责中补 `dataOrigin`（与 UC-11/12/13 同一建议，可统一收录一次）与“证据清单公开字段（缺失标未知）” |
| `Authorship.roleKind`/`participantRef`/`roleEvidenceRefs` | 全局 `Authorship` 只有“+角色/顺序/证据()/+独立关系状态()”；登记表 `Authorship` 行有 `role`/`relationState`/`evidencePublic` | 本 UC 需要区分**四类角色**并允许“参与者身份未定”（`0..1` Scholar）；全局未写明四类角色与身份未定状态 | R5 在 `Authorship` 注记四类角色与“身份未定时不参与建边”，并明确 `Authorship→Scholar` 的多重性（§4.3） |
| `Scholar.mergedIntoRef` | 全局 `Scholar` 无合并指向；`DGM-CLASS-01` 无合并链 | 合并后需解析到当前 `canonicalId`，不得重复节点或生成自环（BR-530） | R5 在 `Scholar` 责任中补 merge 解析契约（与 UC-10 的 `IdentityCandidate` 分工） |
| UC-14 用例名 | 模块细化“UC-14 查看专家合作关系网络”；SRS/统一清单“查看四类共同成果合作网络”（追踪表与 progress 用后者） | 名称一致，但模块名省略“四类共同成果”，易被误读为论文网络 | R5/需求侧统一为“查看四类共同成果合作网络”，并在用例说明中标明 IN01 论文子图为下游 |

| 本 UC 名称 | 登记表名称 | 处理 |
|---|---|---|
| `CollaborationNetworkUI` | 登记表**尚无 UC-14 行**（只有 `StatisticsUI`/`InstitutionRankUI`/`HotspotUI`） | 由父 Agent 决定是否登记 `CollaborationNetworkUI`/`CollaborationNetworkCoordinator`/`CollaborationEdge`；本包不改登记表（§6） |
| `CollaborationNetworkCoordinator` | 无 | 同上 |
| `CollaborationEdge` | 无 | 同上（首次发现 UC-14） |

## 3. 职责差异/需要补充

| 项目 | 本 UC 证据 | 全局现状 | 差异/影响 | R5 处理 |
|---|---|---|---|---|
| 建边与路径分离 | M14–M18 与 M51；AC-IMPL-FR12-01/05；BR-IMP-08/09 | `GraphCtl` 同时承担“四类基础边/论文路径”；`GraphCtl` note 只写“IN01 只取共同论文子图” | 若按全局图实现，路径规则（BFS/等长/图版本/规模上限）可能反渗进基础网络，导致四类边被论文过滤或基础网络被施加上限与版本语义 | R5 拆分“基础网络建边”与“论文子图筛选/路径”两类职责，各自写守卫 |
| 成果资格与关系资格分离 | M7/M10；BR-UC14-02；§11.3；FR12-S05（细化） | `01` §5.3 只写“Authorship≠成果”；`DGM-CLASS-01` 无分层守卫 | 实现者容易“一条关系不合格 → 整项成果退出”或“成果不合格仍作为证据” | 在协调者职责写明两层资格与各自的排除披露 |
| 四类成果同等、不裁成论文图 | M7；A16；AC-IMPL-FR12-01/05 | `DGM-USECASE-02` 图例写“FR12 和 IN01 均只接受合格共同论文证据” | **C-04 冲突：** 该图例把 FR12 误设为论文限定；按它实现会丢掉专利/项目/获奖边 | R5/评审修订该图例为“FR12 四类；IN01 仅论文子图”，并在 `GraphCtl` 职责写明 |
| 同机构/同领域/相似姓名不建边 | M13；A10/A11；BR-UC14-01 | `GraphCtl` 未写该守卫 | 最常见的错误：用机构/领域/姓名补边或合并节点 | 在协调者职责与图中补显式守卫与反例（BR-IMP-03；AC-IMPL-FR12-04） |
| 每成果每对 1 强度、跨来源不累计 | M17；A12/A13；BR-UC14-03 | `GraphCtl` 未写强度去重规则；模块 FR12-S04 有（细化） | 强度可能按来源/版本/重复角色放大 | 在协调者与 `CollaborationEdge` 职责写明“强度＝去重成果数” |
| 强度＝证据清单条数 | M18/M25–M30；BR-IMP-08 | 全局无边/证据对象，也没有一致性要求 | 强度与证据不一致无法被判定为缺陷 | 新增 `CollaborationEdge`（§2）并写一致性守卫 |
| 默认只真实、模拟仅演示并标识 | M4/M45；BR-IMP-07 | `GraphCtl`/`AnalyticsUI` 无数据类别守卫 | 模拟数据可能直接进入“现实网络” | 在协调者职责补数据类别守卫与披露标识 |
| 披露项完整性（数据范围/时间范围/关系定义/强度含义/排除状态/规模/更新时间） | M19/M21/M22；FR12-S01/S03/S05（细化） | `DGM-USECASE-02` 只到“披露统计口径与数据范围”用例粒度，未列声明项 | 声明不完整会导致强度与边不可复核 | 统一声明清单（与 UC-11/12/13 同一清单，网络额外加“关系定义/强度含义/被排除关系状态”） |
| 证据清单字段与缺失标注 | M27–M30；FR12-S02（细化） | 全局与活动图无证据清单步骤 | 字段缺失可能被隐藏，导致“证据缺失却看似完整” | 在职责中补五项字段与“缺失标未知、不隐藏” |
| 无合作关系 vs 失败 vs 未更新 vs 规模超限 | M20/M46–M50；NF01；FR12-S01 E1/E2、S06（细化） | 全局以“是否存在可用统计结果”为唯一分支；`DGM-STATE-03` 以结果版本为中心 | 本包不建结果版本（C-01），需要另一种表达 | R5 决定结果对象是否保留（C-01/B-08） |
| 禁止质量/影响力/个人排名 | M22/M35/M51；BR-522（细化）；§2.1 | `GraphCtl` 与 `DGM-USECASE-02` 无固定声明 | 强度极易被读成“合作质量榜” | 在入口与结果区固定声明，并在协调者职责写拒绝项 |
| 全局缺少 FR12 建边活动图 | 本 UC 01 §6 E1–E20 | 统计域只有 `DGM-ACTIVITY-02`（指标刷新）与 `DGM-ACTIVITY-03`（IN01 路径）；**无 FR12 四类建边活动图** | 建边规则只存在于文字与 note，缺少可核查的活动流 | R5/后续阶段补“FR12 基础网络构建与证据披露”活动图（本包不新建全局图） |
| 强度是否展示 | AC-IMPL-FR12-03（必须可观察） | 模块 FR12-S03 A1 允许“评审决定不展示强度” | **C-02 冲突：** 基线与细化不一致 | 以基线为准；需求侧确认 A1 是否失效 |

## 4. 关系差异

### 4.1 `AnalyticsUI ..> GraphCtl`（全局图）

- 全局把统计、排名、热点、网络、路径的读侧统一指向 `GraphCtl`；本 UC 的基础网络**不计算路径**，若直接沿用该依赖，会让基础网络视图继承路径规则（图版本、节点/边上限、论文限定）。
- **R5 建议：** 保留 `AnalyticsUI` 作为读侧边界时可拆多条依赖（分布统计／机构排名／热点／基础网络／路径），并明确“基础网络不调用路径与筛选职责”。

### 4.2 `Work -- Source`、`Work -- Version`、`Scholar -- Authorship`、`Authorship -- Work`（全局未标多重性）

- 本 UC 沿用 `WorkEntity 1 : 0..* SourceRecord`、`WorkEntity 1 : 0..* WorkVersion`、`Authorship 0..* : 1 WorkEntity`、`Authorship 0..* : 0..1 Scholar`，但**不**把来源/版本画入本 VOPC：来源数与版本数不参与强度（AC-IMPL-FR12-02；`AC-IMPL-FR16-02`）。
- **R5 建议：** 保留既有边并注明“合作网络按去重实体计强度，不按来源/版本计”；`Authorship→Scholar` 需明确 `0..1`（身份未定时不建边）。

### 4.3 新增业务关联（全局无）

- `CollaborationEdge 0..* : 2 Scholar`（两端节点，无序对、两学者不同）与 `CollaborationEdge 0..* : 1..* WorkEntity`（共同合格成果证据），理由见 05 §2.2。
- **R5 建议：** 新增该派生类与两条关联并注明“边必须可回溯到至少一项合格共同成果；强度＝证据规模；同一无序对只有一条边；自环不生成”，同时注明“该边不作为持久事实源”。

### 4.4 未画的关联（有意不画）

| 关联 | 不画理由 |
|---|---|
| `CollaborationEdge → Institution` / `→ Field` | 同机构/同领域不是合作证据（AC-IMPL-FR12-04）；机构/领域筛选属 UC-15（C-03） |
| `Scholar → Institution`（当前任职） | 本 UC 不用任职信息建边或筛选；11.5 明确“缺失不得回填学者当前机构作为历史事实” |
| `CollaborationEdge → SourceRecord` / `→ WorkVersion` | 来源数与版本数不放大强度（AC-IMPL-FR12-02） |
| `Scholar → UserProfile` | 账号与普通主页不是网络节点，也不是合作证据（§12） |
| `CollaborationEdge → CollaborationEdge`（自环/多重边） | 边键已保证无向唯一、无自环；自关联会与“唯一一条边”冲突 |
| 网络结果投影 → 任何实体 | 投影不是业务实体（05 §4；C-01） |

## 5. 与现有用例图/活动图/状态图/模块细化的一致性（含必须登记的冲突）

| 全局/细化资产 | 与本 UC 的一致点 | 需登记/需修正的差异 |
|---|---|---|
| [`DGM-USECASE-02`](../../../uml/analytics/DGM-USECASE-02-analytics.puml)（统计域用例图） | `UC-14 查看专家合作关系网络` 对应 FR12；`INCL_Caliber`（披露统计口径与数据范围）与本 UC E13/E14 一致；`INCL_Evidence` 作为 UC-14 的 `<<extend>>` 与本 UC E15 一致；`UC15 .> UC14 : <<extend>>` 与本包“路径/筛选属 UC-15”一致；`INCL_Filter` 只挂 UC-15 与本包一致 | **C-04（文本冲突）：** 图例第 3 条写“FR12 和 IN01 均只接受合格共同论文证据，不使用非论文成果建边”，与基线 FR12“四类共同成果建边”矛盾。本 UC 以基线为准（A16）。**建议 R5/评审修订该图例。** 另：图中未画 UC-14 的“数据类别（真实/演示）切换”与“无合作关系/规模超限”分支，属细化缺口 |
| [`DGM-ACTIVITY-03`](../../../uml/analytics/DGM-ACTIVITY-03-collab-path.puml)（合作路径查询） | “只采用真实公开论文和合格参与关系”“同一去重论文对作者对只贡献 1”“待核验/争议/已排除不建边”“规模上限不截断”与本 UC 的强度去重、状态门槛、规模守卫一致（但该图范围为 IN01） | **缺口：** 该图只覆盖 IN01 论文子图，没有 FR12 四类建边的活动流；本 UC 的 E3–E13 无对应全局活动图。建议后续补图，本包不新建 |
| [`DGM-STATE-03`](../../../uml/analytics/DGM-STATE-03-metrics-visibility.puml)（统计结果可见性状态） | 状态含义（无可用结果/计算中/已生效/待更新/更新失败/已失效）与本 UC 的可观察结果（无合作关系/已形成/未更新/失败）可对应 | **C-01（取向差异）：** 本包不建可转移状态的持久网络结果对象；若评审要求保留结果版本，则本 UC 的 `outcome` 分析态需改为该状态机投影。本 UC 不修改该图 |
| 模块细化 FR12-S01（基础视图） | 节点/边定义、关系定义披露、A1 数据类别切换与本 UC E3/E13/E14/E18 一致 | A1 的“真实／模拟／全部”三选与本 UC“现实/演示”二态的关系见 B-04；“时间范围”披露的默认窗未定义（B-03） |
| 模块细化 FR12-S02（成果依据） | 五项字段、条数、详情入口与本 UC E15 一致 | 分页与“时间倒序”属界面细化，本 UC 不定义 |
| 模块细化 FR12-S03（强度定义） | 强度＝共同成果实体数、与清单条数一致、固定“不代表质量”声明与本 UC E11/E12/E14/E16 一致 | **C-02（文本冲突）：** 备选 A1“评审决定不展示关系强度”与 `AC-IMPL-FR12-03`（强度累计为 2）冲突；本 UC 以基线为准 |
| 模块细化 FR12-S04（去重与对称性） | 每成果一条边一次计数、无向唯一、合并后无自环与本 UC E10/E11/E9 一致 | 细化中的“合并后触发统计重算”属结果生成取向（C-01） |
| 模块细化 FR12-S05（节点数据范围与公开性） | 待核验/争议/已排除不参与强度、口径说明列出排除状态与本 UC E7/E13 一致 | 备选 A1“虚线或单独图层提示不合格关系”未决（B-09） |
| 模块细化 FR12-S06（规模上限） | 超限提示与收窄、子集须声明与本 UC E13/A17 一致 | 上限未冻结（Q-6；D-08）；收窄举例“加领域或机构条件”超出基线 FR12 条件集合 → **C-03**；IN01 的 1 万/10 万不能直接套用到四类基础图 |
| 模块细化 FR12-S07（孤立学者） | 不虚构连线、口径说明写出处理方式、区分“无合作关系/数据缺失”与本 UC E13/A2 一致 | “不显示”与“单独标注”二选一未冻结（B-05） |
| 模块细化 FR12-S08（更新与一致性） | 与检索/门户/统计一致、未更新提示与本 UC E19 一致 | **C-01 同源：** “上一版可用网络＋结果状态机”与读时投影取向不同；NF05 的 60 秒目标未批准 |
| 模块细化 FR12-S09（节点与边导航） | 门户/详情入口、合并跳转、明确不可用与本 UC E17 一致 | 无实质差异（属细化） |
| 模块细化 BR-514～BR-530 | BR-516/517/518/519/522/526/528/530 与本 UC 强度去重、无向唯一、可追溯、状态门槛、声明、规模、合并一致 | BR-515/BR-523/BR-527 的结果对象与重算取向属 C-01；BR-524/525（筛选与路径）属 UC-15 |
| [`01-BCE分析类模型.md`](../01-BCE分析类模型.md) §2/§3/§4 | `AnalyticsUI` 覆盖 UC-11～15；`AnalyticsNetworkCoordinator` 覆盖 FR12/IN01；`Work`/`Authorship`/`Scholar` 职责与本 UC 一致；§5.4“FR12四类/IN01论文”与本 UC 一致 | `01` 未列 `CollaborationEdge`、未写“相似姓名不建边”、`Work` 无 `dataOrigin`、`Authorship` 无四类角色与身份未定说明；`AnalyticsUI`（`DGM-CLASS-01`）与 `PublicPortalUI`（`01`）命名不一致 → 建议 R5 统一并补齐 |
| 合作图规范 [`FR12-IN01`](../../../spec/FR12-IN01-合作图与最短路径规范.md) §1/§2 | 资格分层、角色证据、canonical 边键、min/max、自环、每成果 1 强度、证据按成果升序与本 UC E5–E12 一致 | §3 的“筛选与规模边界”主要面向 IN01 论文子图；其对 FR12 基础图的适用部分（节点/边上限、图版本）需评审确认（B-02/B-08） |

## 6. 追踪文件与登记表（只提建议，不改文件）

| 文件 | 现状 | 建议（由父 Agent 决定） |
|---|---|---|
| [`03-AC-UC-分析模型追踪.md`](../03-AC-UC-分析模型追踪.md) | FR12 五行（38～42）归属 UC-14，模型列 `DGM-USECASE-02 / ACT-08 / CLASS-01`；NF05 两行（65～66）含 UC-14 | 可增补本 UC 的 `SEQ-22 / VOPC-18`；注意追踪表引用的 `ACT-08` 在本仓库统计域实际文件名为 `DGM-ACTIVITY-03-collab-path`（IN01 路径），**FR12 建边活动流缺失**，建议登记缺口 |
| [`analysis-class-registry.md`](../automation/analysis-class-registry.md) | 无 UC-14 行；`Authorship` 行已注“后续参与 UC-14/15/16”；`Scholar` 行注“后续参与 …14/15”；`WorkEntity` 行无 `dataOrigin` | 可新增 `CollaborationNetworkUI`/`CollaborationNetworkCoordinator`/`CollaborationEdge` 三行（首次发现 UC-14），并在 `WorkEntity` 行补 `dataOrigin`、在 `Authorship` 行补 `roleKind`/`participantRef`/`roleEvidenceRefs` |
| [`unresolved-decisions.md`](../automation/unresolved-decisions.md) | 已有 D-07（自动关联阈值/人工复核）、D-08（图版本/超规模阈值实现口径）、D-11（命名统一） | 可评估新增：展示上限与适用图范围（B-02）、时间范围与默认窗（B-03）、数据类别缺标识（B-04）、无合作学者口径（B-05）、四类角色证据门槛（B-06）、图版本与上一版结果（B-08）、不合格关系可视化（B-09）、强度排序/阈值（B-10）；本包不直接修改该文件 |
| 全局 `DGM-CLASS-01-analysis.puml` | `AnalyticsUI ..> GraphCtl`；`GraphCtl` 无 `CollaborationEdge`、无数据类别与旁证守卫 | 按 §2–§4 在 R5 统一分析类时处理；本 UC 不修改全局图 |
| `DGM-USECASE-02` / `DGM-ACTIVITY-03` / `DGM-STATE-03` | 见 §5 | 建议 R5/评审处理图例冲突（C-04）、补 FR12 活动图、决定结果对象（C-01）；本 UC 不改动这些图 |
| 模块细化 `analytics-graph.md` | FR12-S03 A1（强度可不展示）、FR12-S06（收窄条件）、FR12-S08（结果对象）与基线/本包取向存在差异 | 由负责人评审确认 C-01/C-02/C-03；本 UC 不改模块 SRS |

## 7. 差异结论

UC-14 分析支持以下全局模型调整建议：

1. 保留 `Work`/`Authorship`/`Scholar` 作为建边事实来源，并补 `dataOrigin`、证据展示公开字段、四类角色与身份未定说明、合并解析契约；
2. 新增**派生** `CollaborationEdge`（边键/两端 canonical_id/证据成果集合/强度），否则强度与证据一致性无承载者；同时明确它不是持久图快照；
3. 把“基础网络建边”与“论文子图筛选/最短路径”在分析域内**拆成两组职责**，禁止路径规则反渗进四类基础网络；
4. 明确基础网络守卫：四类成果同等、同机构/同领域/相似姓名不建边、每成果每对 1 强度、边可追溯、强度＝证据条数、待核验/争议/已排除不成边；
5. 统一披露清单（数据范围/时间范围/关系定义/强度含义/排除状态/规模/更新时间）并要求范围变更后同步刷新；
6. **评审决定网络结果的对象化程度（C-01）**：保留模块细化的结果版本/状态机，或采用本包的读时投影；
7. **评审确认 C-02**：模块细化 FR12-S03 A1（可不展示强度）是否失效；
8. **评审确认 C-03**：基础网络是否引入机构/领域收窄条件（当前按基线不引入，全部交由 UC-15）；
9. **修订 C-04**：`DGM-USECASE-02` 图例“FR12 只接受论文证据”与基线冲突，应按基线改为“FR12 四类；IN01 仅论文子图”；
10. 补一张 FR12 基础网络构建与证据披露活动图（当前统计域只有 IN01 路径活动图）；
11. 展示上限、时间范围与默认窗、缺数据类别标识、无合作学者口径、角色证据门槛、图版本与上一版结果、不合格关系可视化、强度排序/阈值仍需需求确认（B-02～B-10）。

本轮**不直接修改** `DGM-CLASS-01-analysis.puml`、`01-BCE分析类模型.md`、`DGM-USECASE-02`、`DGM-ACTIVITY-03`、`DGM-STATE-03`、模块细化 `analytics-graph.md`、合作图规范与共享追踪/登记文件。
