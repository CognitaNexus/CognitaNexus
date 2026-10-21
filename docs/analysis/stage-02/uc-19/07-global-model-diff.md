# UC-19 分析与现有全局模型差异

**版本：** V1.0（2026-10-21）
**比较对象：** [`DGM-CLASS-01-analysis.puml`](../../../uml/analysis/DGM-CLASS-01-analysis.puml)、[`01-BCE分析类模型.md`](../01-BCE分析类模型.md)、[`DGM-USECASE-03`](../../../uml/collection/collection-local-usecase.puml)、[`DGM-STATE-01`](../../../uml/collection/collection-task-state.puml)、[`collection-update-activity`](../../../uml/collection/collection-update-activity.puml)、[`DGM-SEQ-01`](../../../uml/analysis/DGM-SEQ-01-collection.puml)、[`02-用例模型与规约.md`](../02-用例模型与规约.md)、[`05-分析序列与用例实现.md`](../05-分析序列与用例实现.md)、UC-18/09/10/17 已交付分析包、候选类登记表 [`analysis-class-registry.md`](../automation/analysis-class-registry.md)、待决清单 [`unresolved-decisions.md`](../automation/unresolved-decisions.md)、追踪表 [`03-AC-UC-分析模型追踪.md`](../03-AC-UC-分析模型追踪.md)。
**原则：** 全局图只作比对材料，不作为本 UC 识别类的反向答案；本轮不重构全局图、不修改共享登记表、不修改他人 UML 资产。

## 1. 已存在且一致

| UC-19 类/概念 | 全局对应 | 一致点 | 证据 |
|---|---|---|---|
| `AdminConsole` | `01-BCE` §2 `AdminConsole`（UC-17/18/19，“管理待办、来源、任务、审计查询”；“不负责：绕过具体权限”） | 统一管理入口；入口不等于授权 | FR10；§8；M1–M7 |
| `SourceAdapter` | `01-BCE` §2 `SourceAdapter`（UC-18/19，“与获准来源交换记录和失败信息”；“不自行决定公开/归属”） | 外部交换边界、不决策 | FR10；M22–M25 |
| `CollectionCoordinator` | `01-BCE` §3 `CollectionCoordinator`（“任务生命周期、检查点、读取、处置汇总”；守卫“队列交接失败不算成功；重试不增重复实体”） | 生命周期、检查点、重试幂等；本 UC 增补分类与时间记录职责 | FR10；§6.2；UC-18 07 |
| `CollectionTask` | 全局 `Task`；`01-BCE` §4（“可停止/重试/检查点的任务”“不等于调度器/线程”） | 任务状态、检查点、可停止/重试 | §6.2；`DGM-STATE-01`；M12/M74/M79/M83 |
| `SourceRecord` | 全局 `Source`；UC-02/09/18 已用 `SourceRecord`；`01-BCE` §4（原始来源、许可、逻辑出处、到达事件；“不等于成果实体”） | 原值不可改写、重复到达不增成果、来源证据可追溯 | INV-01；FR09-06；CL-10/CL-12；M26–M39 |
| `WorkEntity` | 全局 `Work`；`01-BCE` §4 `WorkEntity`（“去重后的四类成果规范实体”“来源数/版本数不等于成果数”） | 成果实体唯一、来源/版本不重复计数；本 UC 只读 | INV-02；DD-11；`AC-IMPL-FR10-01` |
| `WorkVersion` | 全局 `Version`；`01-BCE` §4（“有明示证据的预印本/正式版等”“无证据不合并”） | 版本须有明示证据；本 UC 只读 | DD-08；SPEC §5.5；M42/M43 |
| `User` | 全局 `User`（“+账号状态()/+对象授权校验()”） | 账号状态与服务端校验 | §8；NF03；M3/M4/M108 |
| `AuditEvidence` | 全局 `Evidence`；UC-09/10/17/18 已用 `AuditEvidence` | 受保护写入留痕可追溯 | BR-IMP-10；INV-10；M48/M54/M58/M69/M80/M85/M111 |
| 任务六状态（待执行/运行中/成功/失败/已停止/重试） | `DGM-STATE-01` 与 §6.2 完全一致 | 状态集合、转移与守卫 | §6.2；`DGM-STATE-01`；M79/M83 |
| 采集与增量同图（`collection-update-activity`） | 该图覆盖 UC-18/19：同源身份与规范化内容判定、未变只更新检查时间、变更保存源版本及前后差异、删除/撤稿/访问失败分别记录、冲突保留公开值并交待核验、成功不表示全部公开或已核验归属 | 本 UC 的 E7–E14 与该图分支同向 | `collection-update-activity`；M26–M69 |
| `02-用例模型与规约` UC-19 主成功场景 | 识别来源身份与原版本→分类→未变不造变更→变更显示前后差异→冲突保留公开值并待核验→显示汇总与轨迹 | 本 UC 的 E1–E17 逐条落地该场景，并把“无变更游标/删除报告/断连/身份不可识别/更新期间人工值变化”落为 A1/A2/A4/A8/A6 | 02-用例模型 UC-19；§2/§3 |

## 2. 需要更名/别名统一

| 本 UC 名称 | 全局名称 | 差异 | R5 建议 |
|---|---|---|---|
| `CollectionCoordinator` | `DGM-CLASS-01`：`CollectCtl`；`01-BCE` §3：`CollectionCoordinator` | 长名/缩写两套并存 | R5 统一为 `CollectionCoordinator`（D-11 类） |
| `CollectionTask` | 全局 `Task`；`01-BCE` §4：`CollectionTask` | 命名差异 | R5 统一为 `CollectionTask`，并在职责注明“不等于调度器/线程” |
| `SourceRecord` | 全局 `Source`；UC-02/09/18 已用 `SourceRecord` | 命名差异（既有） | 沿用 `SourceRecord`（登记表已定“以 `SourceRecord` 为正式名”） |
| `WorkEntity` / `WorkVersion` | 全局 `Work` / `Version`；UC-02/09 已用 `WorkEntity`/`WorkVersion` | 命名差异（既有） | 沿用长名（登记表同结论） |
| `AuditEvidence` | 全局 `Evidence` | 一名多义（材料＋留痕，D-13） | 沿用 `AuditEvidence`（UC-09/10/17/18 同结论） |
| `GovernanceCoordinator` | 全局 `GovernCtl`；UC-09 `DedupCoordinator`；UC-10 `IdentityVerificationCoordinator` | 三种写法并存 | R5 决定“一个总治理协调者还是按治理域拆分”，并统一命名（D-11；B-09） |
| `AdminConsole` | 全局 `AdminUI` | 命名差异（UC-17/18 已记录） | R5 统一（同 UC-17/18 结论） |
| `IncrementalChangeSet`（新） | 全局无；`SourceUI ../` 无对应 | 全局把增量语义隐含在 `CollectCtl` 与 `Source` 中 | R5 新增；并在 `CollectionTask` 职责写明“不含本轮分类结果” |
| `GovernanceException` | 全局无；全局异常多以 `Evidence` 承载 | 全局把“业务待办”与“动作留痕”混用 | R5 新增（沿用 UC-18 结论），并与 UC-09/UC-17 统一单一所有者 |

## 3. 职责差异/需要补充

| 项目 | 本 UC 证据 | 全局现状 | 差异/影响 | R5 处理 |
|---|---|---|---|---|
| 本轮比较结果作为独立事实 | M20/M47/M53/M57/M59/M70/M76/M84/M90；FR10“区分新增、未变、变更、失效”“保留检查与生效时间” | `01-BCE` 只有 `CollectionTask`（任务状态/检查点）；全局无变更结果类 | 若把分类与时间写进任务或来源记录，会破坏 INV-01 或无法保留多轮结果 | 新增 `IncrementalChangeSet`，并写明它不承载任务状态 |
| 三种时间分离 | `checkedAt`/`sourceReportedUpdatedAt`/`effectiveAt`；M70/M90/M97 | 全局只有“检查点时间”；`DGM-SEQ-01` 未区分检查与生效 | 可能把采集时间当成果发生时间或生效时间（CL-07 违规） | 在 `CollectionTask`/`IncrementalChangeSet` 职责写明“三种时间不可混称”，并补 `AC-IMPL-FR10-01` 的实现约束 |
| 人工审核字段优先 | M40/M41→M44→M45–M48；INV-06；CL-09 | 全局 `CollectCtl ..> Work` 未表达“只读或写”、也未表达人工值优先 | 实现层可能把增量更新直接写回成果，静默覆盖审核值 | 在 `CollectionCoordinator` 职责写明“不写 `WorkEntity`；冲突只建待办”；`GovernCtl` 承担字段规则 |
| 冲突待办的单一所有者 | M45/M55/M67；FR10“并发冲突进入治理待办” | UC-18 用 `GovernanceException`；UC-09 把字段冲突项挂在 `CleanRecord`；UC-17 待办视图读 `DedupCandidate`/`CollectionTask` | 三处对“待核验字段冲突”的承载不一致，可能导致待办不可见或重复计数 | R5 决定单一所有者（`GovernanceException` 或 `CleanRecord` 冲突项），并让 UC-17 待办视图纳入该来源 |
| 来源侧记录版本 vs 成果版本 | `SourceRecord.observedRevision`；`IncrementalChangeSet.baselineRef`；`WorkVersion` 只读 | 全局只有一个 `Version`，语义为成果发布形态 | 若把 FR10“上次版本”实现为 `WorkVersion`，会在每次更新时错误建立版本关系或误判未变 | R5 在 `Version` 职责注明“仅成果发布形态”，并在 `SourceRecord` 增加“观察版本”属性 |
| 失效/删除/撤稿三类事件 | M63（两类分别记录）；CL-12；M65–M66 | `DGM-SEQ-01` 无失效路径；`collection-update-activity` 有“删除、撤稿、访问失败分别记录” | 实现层可能把“响应缺少记录”当删除，或直接物理删除 | R5 在 `SourceRecord`/`SourceAdapter` 职责补“三类事件不混同、不删除” |
| 复跑/重叠窗口幂等 | M72–M73；A7；INV-07 | 全局 `DGM-STATE-01` 只写“重试不增实体和唯一出处” | 增量窗口重叠时可能重复建立变更或重复创建版本关系 | 在 `IncrementalChangeSet` 职责写明“以逻辑出处＋观察版本为幂等键” |
| 不覆盖人工值且不发布 | BR-UC19-07/15；M44–M58 | 全局 `CollectCtl` 与 `GovernCtl` 边界未写 | 可能把“生效”做成“发布”，或在增量阶段改公开可见性 | R5 在 `CollectCtl`/`GovernCtl` 职责区分“生效（字段值/时间）”与“发布（公开资格）” |
| 生效结果传播到各视图 | E12 只记录生效时间；NF05 声明层 | `collection-update-activity` 写“按 NF05 在检索、门户、统计反映” | 若把传播当本 UC 职责，会扩成读侧协调器 | 本 UC 只声明“下游读取同一生效版本”；传播机制与实测留 NF05/读侧（NOT RUN） |
| 不建执行/比较框架类 | 03 §4；05 §4 | 全局无调度类（`DGM-USECASE-03` 明确“调度器不作为外部参与者”） | 一致，无需修改 | 保持；R5 不新增 `Scheduler`/`TaskQueue`/`ChangeCursor` |
| 不强制实时流式同步 | FR10“采用批次复跑/安全检查点，不强制实时流式同步”；E15/A7 | 全局无实时/流式组件 | 一致；若实现层引入订阅/推送，会超出已批准范围 | R5 不新增 `StreamingSubscriber`/`ChangeFeed`/`Webhook`；增量语义由 `SEQ-18` 的批次视图表达 |

## 4. 关系差异

### 4.1 `CollectCtl ..> Task`（全局）

- 全局以一条依赖表达“任务生命周期”，未表达“任务↔本轮变更集”的一对多，也未表达“来源定义↔变更集”。
- 本 UC 增加：`SourceDefinition 1:0..* IncrementalChangeSet`、`CollectionTask 1:0..* IncrementalChangeSet`、`IncrementalChangeSet 1:0..* GovernanceException`。
- **R5 建议：** 保留 `CollectionCoordinator → CollectionTask`（生命周期），新增上述 3 条业务关联；`IncrementalChangeSet` 指向来源定义与任务，避免与 `SourceDefinition → SourceRecord` 语义重复。

### 4.2 `CollectCtl ..> Work`、`GovernCtl ..> Work`（全局）

- 全局两条依赖都未区分读/写。本 UC 对 `WorkEntity` 只有读消息（M40/M49/M65），写入与字段规则属 UC-09 的 `GovernCtl`。
- **R5 建议：** 在 `CollectCtl → Work` 标注“只读（审核值/公开状态/存在确认）”；字段级写入仍由 `GovernCtl` 承担，避免两处写同一字段。

### 4.3 `AdminUI ..> CollectCtl / GovernCtl`（全局）

- 全局把后台边界同时连到采集与治理，图上无法区分“导航”与“执行”。本 UC 的 `AdminConsole` 只连 `CollectionCoordinator`，治理只通过移交/回执表达。
- **R5 建议：** 保留 `AdminConsole → CollectionCoordinator`；`AdminUI → GovernCtl` 改为导航语义或加注记。

### 4.4 `Task -- Source : 批次结果`（全局）

- 全局以一条 `Task–Source` 关联表达“批次结果”。本 UC 需要“来源定义→变更集→待办”的归因链，且 `SourceRecord` 与变更集是多对多（一条记录出现在多轮更新中）。
- **R5 建议：** 保留 `CollectionTask → SourceRecord`（首次登记，沿用 UC-18），**不**新增 `SourceRecord–IncrementalChangeSet` 关联；后者用 `changeItems[]` 属性引用逻辑出处，避免多对多边与历史误读。

### 4.5 新增/沿用的业务关联

- 沿用：`SourceDefinition 1:0..* SourceRecord`、`SourceRecord 0..1:0..* GovernanceException`、`WorkEntity 1:0..* WorkVersion`、`AuditEvidence 0..*:0..1 User`（均有前序 UC 依据）。
- 新增：`SourceDefinition 1:0..* IncrementalChangeSet`、`CollectionTask 1:0..* IncrementalChangeSet`、`IncrementalChangeSet 1:0..* GovernanceException`。
- **R5 建议：** 新增 3 条并在 `IncrementalChangeSet` 职责注明“不保存原值副本、不承载任务状态”。

### 4.6 未新增的边（有意省略）

- `CollectionCoordinator → WorkEntity` 未标可写：本 UC 不写成果（BR-UC19-15）。
- `WorkEntity → GovernanceException`：待办指向成果字段用 `targetRefs`（含 `confirmedValue`/`incomingValue`），不画边以避免与 `SourceRecord → GovernanceException` 重复。
- `IncrementalChangeSet → WorkEntity/WorkVersion`：变更集只记分类与差异引用，不建立到成果的持久边（否则暗示本 UC 可改成果/版本）。
- `SourceAdapter → SourceRecord/IncrementalChangeSet`：边界只交换记录与报告，不写业务实体。
- `GovernanceCoordinator → WorkEntity/WorkVersion`：治理侧写入与版本归并属 UC-09，本 UC 不重复画。

## 5. 与现有用例图/状态图/活动图/序列图的一致性

| 全局资产 | 与本 UC 的一致点 | 需登记/需修正的差异 |
|---|---|---|
| [`DGM-USECASE-03`](../../../uml/collection/collection-local-usecase.puml) | `管理员 -- UC-19`、`外部数据来源 -- UC-19`；图例“调度器不作为外部参与者”；用例名“UC-19 更新来源数据并处理变更” | 与 [`02-用例模型与规约`](../02-用例模型与规约.md) 的 UC 名“管理来源更新”用词略有差异（“更新来源数据并处理变更”vs“管理来源更新”）。**R5 建议：** 统一名称，避免同一 UC 两种叫法（UC-18 已记录同类问题） |
| [`DGM-STATE-01`](../../../uml/collection/collection-task-state.puml) | 六状态、转移与守卫完全一致；图例“失败/停止保留已完成项；重试不增实体和唯一出处”“成功/停止后另建关联任务”直接支撑 A4/A7 | **无冲突，无需修改。** 本 UC 不新增状态、不画状态机；检查点间隔与冲突处置不在图中，故也不新增转移 |
| [`collection-update-activity`](../../../uml/collection/collection-update-activity.puml) | “同源身份与规范化内容判定”“未变只更新检查时间”“变更保存源版本及前后差异”“删除/撤稿/访问失败分别记录”“冲突保留公开值及确认/排除结果”“重复同版本复用待办”“保存本批互斥处置计数”“成功不表示全部公开或已核验归属”与本 UC 同向 | ①该图同时覆盖 UC-18/19，本 UC 只取更新部分；②标题写作 `DGM-ACTIVITY-05`，与 [`04-活动图与状态图`](../04-活动图与状态图.md) 的 `DGM-ACTIVITY-05`（注册并自动建立公开主页）**编号冲突**（UC-18 已记录）；③`repeat` 循环以“本轮范围仍未读完或仍有未完成项”为条件，未表达 UC-19 的“只做增量范围”与“检查/生效时间分离”；④未画“无基线/回溯范围”与“失效待办”两条出口。**R5 建议：** 重新编号并补上述分支（本包不修改该资产） |
| [`DGM-SEQ-01`](../../../uml/analysis/DGM-SEQ-01-collection.puml) | 外部来源、任务、来源记录、异常/待核验的分工与本 UC 同向；注释已声明“采集成功不等于全部公开或全部归属已核验” | ①该图不含增量分类、检查/生效时间、人工审核值冲突与失效路径（本 UC 补齐）；②用 `Evidence` 承载异常/待核验（本包用 `GovernanceException`，见 §3）；③无 `SourceDefinition`/`IncrementalChangeSet`/`WorkVersion`。**R5 建议：** 保持该图为“采集入库”视图，增量与更新由 `SEQ-18` 表达，避免一张图承担两个用例 |
| [`01-BCE分析类模型`](../01-BCE分析类模型.md) §2/§3/§4 | `AdminConsole`/`SourceAdapter`/`CollectionCoordinator`/`CollectionTask`/`SourceRecord`/`WorkEntity`/`WorkVersion`/`AuditEvidence` 职责与本 UC 一致或可衔接 | §4 未定义 `IncrementalChangeSet`/`GovernanceException`，且未写“三种时间分离”“人工审核值优先”“失效不删除”。**R5 建议：** 补两类的独立存在理由与上述守卫 |
| UC-18 已交付分析 | UC-18 明确“来源更新与增量、来源停用/更换属 UC-19”；本 UC 复用其 `SourceDefinition`/`CollectionTask`/`SourceRecord`/`GovernanceException` 与六状态，不新增任务状态 | 无冲突。UC-18 把“来源停用/更换归属”列为 B-06 待确认，本 UC 沿用“只读来源有效性、不执行配置停用/更换”（B-06） |
| UC-09 已交付分析 | UC-09 把 `IncrementalChangeSet` 明确列为“属 UC-19”并拒绝在本 UC 建模；`WorkEntity`/`WorkVersion`/`SourceRecord` 语义沿用 | **差异：** UC-09 把“字段冲突项”挂在 `CleanRecord`，本包把冲突待办放在 `GovernanceException`；单一所有者未定 → R5（B-09）。另：UC-09 的 A9 已描述“来源更新与已人工确认值冲突”，与本 UC E11 需统一处置入口 |
| UC-17 已交付分析 | UC-17 的待办视图只读聚合六类属主对象（含 `CollectionTask`、`DedupCandidate`） | **差异：** UC-17 未把 `GovernanceException` 列为待办来源；若本 UC 的字段冲突/失效待办要在后台可见，R5 需把该来源纳入 UC-17 待办视图（或统一为 UC-09 的冲突项） |

## 6. 追踪文件与登记表（只提建议，不改文件）

| 文件 | 现状 | 建议（由父 Agent 决定） |
|---|---|---|
| [`03-AC-UC-分析模型追踪.md`](../03-AC-UC-分析模型追踪.md) | FR10 行“分析模型”列为 `DGM-USECASE-03 / STATE-01 / SEQ-01`；NF04/NF07 行同理；UC-19 反向行列出 5 条 AC | 为 FR10、NF04、NF07 与 UC-19 反向行增补 `SEQ-18 / VOPC-14` |
| [`analysis-class-registry.md`](../automation/analysis-class-registry.md) | 有 `SourceRecord`（UC-02→UC-09/16/18/19）、`WorkVersion`（UC-02→…/19）、`WorkEntity`、`GovernanceException`（UC-18→UC-09/10/17）、`AuditEvidence`、`CollectionTask`、`SourceDefinition`、`SourceAdapter`、`CollectionCoordinator`；**无** `IncrementalChangeSet` | 增量登记 `IncrementalChangeSet`（Entity，首次发现 UC-19）；`SourceRecord` 行补“UC-19 观察属性 `observedRevision`/`lastCheckedAt` 与失效事件”；`WorkEntity`/`WorkVersion` 行补“UC-19 只读（不覆盖审核值、不建版本）”；`GovernanceException` 行补“UC-19 字段冲突/并发冲突/失效待核验/身份不可确认待办与冲突证据属性” |
| [`unresolved-decisions.md`](../automation/unresolved-decisions.md) | D-10 已登记“来源 API/文件格式与重试上限” | 可评估登记：检查点间隔/批次大小（B-01）、无版本信息的退化比较规则（B-02）、回溯范围口径（B-03）、冲突处置责任与时限（B-04）、失效三类事件对公开资格的影响归属（B-05）、待办单一所有者（B-09）。本包不直接修改该文件 |
| 全局 `DGM-CLASS-01-analysis.puml` | `AdminUI`/`CollectCtl`/`Task`/`Source`/`Work`/`Version`/`Evidence` 命名并存；无变更集与待办类 | 按 §2–§4 在 R5 统一；本 UC 不修改全局图 |

## 7. 差异结论

UC-19 分析支持以下全局模型调整建议：

1. **新增 `IncrementalChangeSet`**：本轮比较结果（分类、基线、三类时间、计数、待办引用、失败状态）必须与任务状态和到达证据分离；
2. **区分三种“版本”**：来源侧记录版本（属性）／本轮基线（属性）／成果发布形态（`WorkVersion`）；FR10 的“上次版本”不是 `WorkVersion`；
3. **明确“只读成果”**：增量更新不写 `WorkEntity`、不建 `WorkVersion`，字段生效与版本归并归 `GovernCtl`/UC-09；
4. **统一冲突待办所有者**：`GovernanceException` 与 UC-09 `CleanRecord` 冲突项、UC-17 待办来源需在 R5 收敛为单一所有者；
5. **补齐未冻结参数**：检查点间隔/批次大小、退化比较规则、回溯范围口径、冲突处置责任与时限仍需需求确认（D-10；B-01～B-05）；
6. **不新增执行/比较框架类**：`Scheduler`/`TaskQueue`/`JobRunner`/`RetryPolicy`/`Checkpoint`/`IncrementalWindow`/`ChangeCursor`/`ChangeItem` 一律不建。

本轮**不直接修改** `DGM-CLASS-01-analysis.puml`、`DGM-USECASE-03`、`DGM-STATE-01`、`collection-update-activity`、`DGM-SEQ-01`、`01-BCE分析类模型.md` 与共享追踪/登记文件。

> 追踪差异：`03-AC-UC-分析模型追踪.md` 第 63/64 行把 `AC-IMPL-NF04-01/02` 归 UC-18/UC-19，但本包把 RTO/RPO 恢复目标留给部署/运维验证，不把未运行的恢复演练写成已覆盖，交父 Agent/R5 确认归属。
> 分析类冲突：UC-09 用 `CleanRecord` 字段冲突项、UC-17 读 `DedupCandidate`/`CollectionTask` 作待办来源，本 UC 用 `GovernanceException`；三处对“待核验字段冲突”的单一所有者未定，登记为 R5 统一项（B-09）。
