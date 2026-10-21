# UC-19 用例实现分析（管理来源更新）

**版本：** V1.0（2026-10-21）
**输入基线：** `S01 FROZEN — 69 AC BASELINE`；[SRS V2.2（69 AC）](../../../srs/CognitaNexus-Shared-SRS-V2.2-69AC.md) FR10（`AC-IMPL-FR10-01/02`）及 FR08/FR09/FR16 边界、§6.1/§6.2、§7.1/§7.2、§8、NF04/NF05/NF07、BR-IMP-01/02/04/10；[核心 SPEC V1.0](../../../spec/CognitaNexus-Core-SPEC-V1.0.md) §3（公共状态、INV-01/02/06/07/09/10）、§4（CL-07/CL-09/CL-12）、§5.5（并发与版本边界）、§8（跨模块交接）；[`DGM-STATE-01`](../../../uml/collection/collection-task-state.puml)（采集任务六状态，UC-19 复用）、[`collection-update-activity`](../../../uml/collection/collection-update-activity.puml)（采集与增量活动，只读比对）、[`DGM-SEQ-01`](../../../uml/analysis/DGM-SEQ-01-collection.puml)（既有采集入库序列，只读比对）、[`DGM-USECASE-03`](../../../uml/collection/collection-local-usecase.puml)（采集与更新局部用例）；[`02-用例模型与规约`](../02-用例模型与规约.md) 的 UC-19 条目；UC-18（直接前驱）、UC-09/10（治理属主）、UC-17（待办与审计读侧）已交付分析；`automation/analysis-class-registry.md`、`automation/unresolved-decisions.md`；教材第 5 章 §5.4–§5.5。
**范围：** 只分析 UC-19“管理来源更新”。不分析来源配置与初始采集任务（UC-18）、成果清洗与去重（UC-09）、学者身份与归属判定（UC-10）、认领与审核（UC-05/06）、后台待办与审计查询（UC-17）、公开读侧与发布（UC-01/02/03/11/14/15/16）；不重构全局类图，不修改 SRS/SPEC/S01/69 AC。
**前置关系：** UC-18 提供已获准来源定义、已到达的来源记录与任务六状态；本 UC 的产出（分类结果、检查/生效时间、待核验字段冲突与失效待办）是 UC-09/10 的输入、UC-17 待办视图的只读来源。
**状态：** UC-19 分析完成（STATIC COMPLETE，UML 2/2 渲染通过）；S02 总体状态与共享追踪文件由父 Agent 维护。

## 1. 交付内容

| 文件 | 内容 |
|---|---|
| [01-UC-19用例规约](01-UC-19-use-case-spec.md) | 参与者、目标、触发、前置/成功/失败后置、E1–E17 基本流、A1–A14 备选/异常、17 条业务规则、关联状态、AC 覆盖、B-01～B-09 待确认 |
| [02-事件流与需求来源](02-event-flow-trace.md) | E1–E17/A1–A14 的逐项来源与证据等级；后置条件来源；不纳入本 UC 的事件 |
| [03-BCE分析类识别](03-bce-identification.md) | 13 个参与类、逐类理由、拒绝候选（含调度器/队列/窗口类）、六项对象边界结论 |
| [04-顺序图与逐消息职责](04-sequence-messages.md) | M1–M111 的发送者/接收者/事件步骤/职责/依据；事件—分支—消息对照；完整性核对 |
| [05-VOPC与关系依据](05-vopc.md) | 13 个参与类、7 条业务多重性、属性替代关联的取舍、排除项 |
| [06-分析类定义](06-class-definition.md) | 每类 Do/Know、候选分析操作、业务属性、关系、17 项分析机制、与其他 UC 的边界 |
| [07-与全局模型差异](07-global-model-diff.md) | 与 `DGM-CLASS-01`、`01-BCE`、`DGM-STATE-01`、`DGM-SEQ-01`、`collection-update-activity` 的一致/命名/职责/关系差异与 R5 建议 |
| [08-自检与待评审](08-self-check.md) | 范围与基线检查、教材步骤对应、模型完整性、实际渲染/文档检查结果、NOT RUN、B-01～B-09 |

## 2. UML

| 模型 | 源 | SVG | PNG |
|---|---|---|---|
| UC-19 管理来源更新分析序列（111 条消息） | [`diagrams/DGM-SEQ-18-source-update.puml`](diagrams/DGM-SEQ-18-source-update.puml) | [`generated/DGM-SEQ-18-source-update.svg`](diagrams/generated/DGM-SEQ-18-source-update.svg) | [`generated/DGM-SEQ-18-source-update.png`](diagrams/generated/DGM-SEQ-18-source-update.png) |
| UC-19 VOPC（13 个参与类） | [`diagrams/DGM-VOPC-14-source-update.puml`](diagrams/DGM-VOPC-14-source-update.puml) | [`generated/DGM-VOPC-14-source-update.svg`](diagrams/generated/DGM-VOPC-14-source-update.svg) | [`generated/DGM-VOPC-14-source-update.png`](diagrams/generated/DGM-VOPC-14-source-update.png) |

## 3. 阅读顺序

先读 01 确认业务边界（配置 vs 到达证据 vs 任务 vs 变更集 vs 待办 vs 版本），再读 02 核对每个事件的证据等级；03 给出 13 个参与类与拒绝候选（含为何不建调度器/队列/增量窗口类）；04 只描述交互，05 只从 04 提取 VOPC；06 定义职责/属性/关系/机制；07 做全局比较并提出 R5 建议；08 列出实际执行结果与未决项。

## 4. 本轮的七个核心结论

1. **五类事实 + 一类版本必须分开**：`SourceDefinition`（已获准来源配置，本 UC **只读**）≠ `SourceRecord`（到达证据：原值、观察版本、到达时间）≠ `CollectionTask`（一次执行的状态、安全检查点与任务历史，与 UC-18 共用六状态）≠ `IncrementalChangeSet`（本轮比较结果：分类、基线、检查/来源更新/生效三类时间、计数、待办引用）≠ `GovernanceException`（待治理待办）；`WorkVersion`（成果发布形态）与“来源侧记录版本”（`observedRevision`）不是同一概念。
2. **分类只用来源侧事实**：在**已核验来源域**内按 `来源ID + 来源内记录键 + 上次观察版本` 区分**新增/未变/变更/失效**；无版本信息时**不得判“未变”**（A3，退化比较规则待确认），不以标题相似判同版本（A8）。
3. **三种时间分离**：检查时间（`checkedAt`）≠ 来源报告更新时间（`sourceReportedUpdatedAt`）≠ 生效时间（`effectiveAt`）；采集/检查时间不得冒充成果发生时间（CL-07；FR16；BR-IMP-04）。
4. **未变不造变更，变更不改原值**：未变只更新检查时间与观察版本（M36/M37）；变更追加到达事件并保留先前原值（M38/M39；INV-01），前后差异可回看。
5. **人工审核字段不可覆盖**：变更与已人工审核值冲突时，公开/生效值保持人工审核值，并生成**待核验字段冲突治理待办**（M44–M48；`AC-IMPL-FR10-01`；INV-06；CL-09）。本 UC **不写 `WorkEntity`**，字段级生效按治理规则执行并在生效前重检（M49–M54；A6）。
6. **失败与失效都不删历史**：来源断连 → 任务失败但保留失败点、已分类结果与恢复范围，已收录数据仍可查询（A4）；来源删除/撤稿报告 → 只登记失效事件并转待核验，不物理删除、不下架、不推断删除（A2/A9/A12；CL-12；`AC-IMPL-FR10-02`）。
7. **批次复跑幂等、版本只在有证据时存在**：同一逻辑出处同一观察版本不重复建立变更、不新增第二条逻辑出处（A7；INV-07；FR09-06）；版本关系只在来源明示证据时交治理处理，无证据保持独立待核验（E13；A10；DD-08）。

## 5. 本轮明确不做

- 不修改 SRS、SPEC、69 AC、S01 冻结材料、全局 UML 与其他 UC 目录；
- 不修改 `docs/analysis/stage-02/03-AC-UC-分析模型追踪.md` 等共享追踪文件，也不改 `automation/analysis-class-registry.md`、`automation/unresolved-decisions.md`、`automation/progress.json`（登记建议见 07 §6）；
- 不把 `DGM-CLASS-01-analysis.puml` 当作本 UC VOPC；不把 `DGM-SEQ-01`、`DGM-STATE-01`、`collection-update-activity` 当作本 UC 的实现，只作只读比对；
- 不分析来源配置与初始采集任务（UC-18）、清洗去重（UC-09）、身份与归属判定（UC-10）、发布与公开可见性（治理与读侧）；
- 不创建/合并/拆分 `WorkEntity`，不建立 `WorkVersion`，不改变公开可见性，不实现“生效结果传播到各视图”的机制（只声明 NF05 下游读取同一生效版本）；
- 不引入调度器、任务队列、作业运行器、重试策略、检查点类、增量窗口类等实现类；不设计数据库/API/框架/部署；
- 不把分析假设写成已批准业务规则：检查点间隔与批次大小（D-10）、无版本信息的退化比较规则、回溯范围口径、冲突处置责任与时限均标 `待确认`（B-01～B-09）。
