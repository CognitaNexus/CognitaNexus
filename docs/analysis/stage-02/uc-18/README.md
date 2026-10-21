# UC-18 用例实现分析（管理来源及采集任务）

**版本：** V1.0（2026-10-21）
**输入基线：** `S01 FROZEN — 69 AC BASELINE`；[SRS V2.2（69 AC）](../../../srs/CognitaNexus-Shared-SRS-V2.2-69AC.md) FR08（`AC-IMPL-FR08-01/02`）及 FR09/FR10/FR16 边界、§6.2（采集任务六状态与守卫）、§6.1、§7.1/§7.2、§8、NF01/NF03/NF04/NF07、BR-IMP-01/02/04/10/11、INV-01/02/07/09/10；[核心 SPEC V1.0](../../../spec/CognitaNexus-Core-SPEC-V1.0.md) §3（公共状态、不变量）、§4.1（必填边界）、§8（跨模块交接）；[`DGM-USECASE-03`](../../../uml/collection/collection-local-usecase.puml)（采集与更新局部用例）、[`DGM-STATE-01`](../../../uml/collection/collection-task-state.puml)（采集任务六状态）、[`DGM-SEQ-01`](../../../uml/analysis/DGM-SEQ-01-collection.puml)（既有采集入库序列）、[`collection-update-activity`](../../../uml/collection/collection-update-activity.puml)（采集与增量活动）；[`01-BCE分析类模型`](../01-BCE分析类模型.md)；UC-09/17 已交付分析（只读参考）；教材第 5 章 §5.4–§5.5。
**范围：** 只分析 UC-18“管理来源及采集任务”。不分析来源更新与增量变更（UC-19）、成果清洗与去重（UC-09）、学者身份与归属判定（UC-10）、认领与审核（UC-05/06）、后台待办与审计查询（UC-17）、公开读侧与发布（UC-01/02/03/11/14/15/16）；不重构全局类图，不修改 SRS/SPEC/S01/69 AC。
**前置关系：** 本 UC 的产出（`SourceRecord`、异常项、任务结论）是 UC-09/10 的输入；任务状态与待核验/异常计数是 UC-17 待办的只读来源。
**状态：** UC-18 分析完成（STATIC COMPLETE，UML 2/2 渲染通过）；S02 总体状态与共享追踪文件由父 Agent 维护。

## 1. 交付内容

| 文件 | 内容 |
|---|---|
| [01-UC-18用例规约](01-UC-18-use-case-spec.md) | 参与者、目标、触发、前置/成功/失败后置、E1–E19 基本流、A1–A14 备选/异常、16 条业务规则、关联状态、AC 覆盖、B-01～B-11 待确认 |
| [02-事件流与需求来源](02-event-flow-trace.md) | E1–E19/A1–A14 的逐项来源与证据等级；后置条件来源；不纳入本 UC 的事件 |
| [03-BCE分析类识别](03-bce-identification.md) | 10 个候选类、逐类理由、拒绝候选（含调度器/队列/检查点类）、六项对象边界结论 |
| [04-顺序图与逐消息职责](04-sequence-messages.md) | M1–M100 的发送者/接收者/事件步骤/职责/依据；事件—分支—消息对照；完整性核对 |
| [05-VOPC与关系依据](05-vopc.md) | 10 个参与类、15 条关系、6 条业务多重性、属性替代关联的取舍、排除项 |
| [06-分析类定义](06-class-definition.md) | 每类 Do/Know、候选分析操作、业务属性、关系、17 项分析机制、与其他 UC 的边界 |
| [07-与全局模型差异](07-global-model-diff.md) | 与 `DGM-CLASS-01`、`01-BCE`、`DGM-USECASE-03`、`DGM-STATE-01`、`DGM-SEQ-01` 的一致/命名/职责/关系差异与 R5 建议 |
| [08-自检与待评审](08-self-check.md) | 范围与基线检查、教材步骤对应、模型完整性、实际渲染/文档检查结果、NOT RUN、B-01～B-11 |

## 2. UML

| 模型 | 源 | SVG | PNG |
|---|---|---|---|
| UC-18 管理来源及采集任务分析序列（100 条消息） | [`diagrams/DGM-SEQ-17-collection.puml`](diagrams/DGM-SEQ-17-collection.puml) | [`generated/DGM-SEQ-17-collection.svg`](diagrams/generated/DGM-SEQ-17-collection.svg) | [`generated/DGM-SEQ-17-collection.png`](diagrams/generated/DGM-SEQ-17-collection.png) |
| UC-18 VOPC（10 个参与类） | [`diagrams/DGM-VOPC-13-collection.puml`](diagrams/DGM-VOPC-13-collection.puml) | [`generated/DGM-VOPC-13-collection.svg`](diagrams/generated/DGM-VOPC-13-collection.svg) | [`generated/DGM-VOPC-13-collection.png`](diagrams/generated/DGM-VOPC-13-collection.png) |

## 3. 阅读顺序

先读 01 确认业务边界（配置 vs 到达证据 vs 任务 vs 异常项、成功判据、异常项不是成果、重试历史），再读 02 核对每个事件的证据等级；03 给出 10 个参与类与拒绝候选（含为何不建调度器/队列/检查点类）；04 只描述交互，05 只从 04 提取 VOPC；06 定义职责/属性/关系/机制；07 做全局比较并提出 R5 建议；08 列出实际执行结果与未决项。

## 4. 本轮的六个核心结论

1. **四种事实必须分开**：`SourceDefinition`（配置：来源身份／输入方式 API 或文件／许可与使用范围／数据性质）≠ `SourceRecord`（到达证据：原值／到达时间）≠ `CollectionTask`（一次执行的状态／参数快照／检查点／任务历史）≠ `GovernanceException`（待处置异常项）。把任两者合并都会破坏 INV-01（原值不可改写）、§6.2（任务历史）或“异常不是成果”（FR08-01）。
2. **一期只验证两种独立且获准的来源输入，API 或文件任选**：输入方式用 `SourceDefinition.inputType` 属性区分，不为每种接入方式建子类；每个来源定义各自建任务，单来源任务失败/停止不改变另一来源的任务与其已采集结果（FR08；BR-IMP-04；A10）。
3. **成功判据是“计划输入全部记录到结果或异常队列”，不是“读到末尾”**：治理交接失败或存在未记录项一律不算完成（§6.2；M54–M58；A5）。任务停留在成功之前的任何“读到全部记录”都不构成成功。
4. **待核验与异常数量单列，结论措辞受约束**：成功只表示处理与异常移交完毕，必须单列待核验/异常数量，不得表述为人工治理已完成、全部已核验或全部公开（FR08 规范性要求；§6.2；`AC-IMPL-FR08-01`；M60–M62）。
5. **异常项没有公开可见性，本 UC 不建成果**：`GovernanceException` 只承载原因、对象引用与待处理状态；本 UC 不创建/更新 `WorkEntity`/`Authorship`、不发布，异常须经 UC-09/10 治理与生效规则（`AC-IMPL-FR08-01`；BR-IMP-01/02；A11）。
6. **重试保留历史、续跑不重复**：检查点与任务历史追加保存；重试从失败点续跑、不重做已完成项、不新增第二条逻辑出处、不创建重复实体，失败点可定位（`AC-IMPL-FR08-02`；INV-07；M76–M81；A6/A12）。

## 5. 本轮明确不做

- 不修改 SRS、SPEC、69 AC、S01 冻结材料、全局 UML 与其他 UC 目录；
- 不修改 `docs/analysis/stage-02/03-AC-UC-分析模型追踪.md` 等共享追踪文件，也不改 `automation/analysis-class-registry.md`、`automation/unresolved-decisions.md`、`automation/progress.json`（登记建议见 07 §6）；
- 不把 `DGM-CLASS-01-analysis.puml` 当作本 UC VOPC；不把 `DGM-SEQ-01`、`DGM-STATE-01`、`collection-update-activity` 当作本 UC 的实现，只作只读比对；
- 不分析来源更新与增量变更、来源停用/更换（UC-19）、成果清洗去重（UC-09）、学者身份与归属判定（UC-10）、发布与公开可见性（治理与读侧）；
- 不引入调度器、任务队列、作业运行器、重试策略、检查点类、异常队列类等实现类；不设计数据库/API/框架/部署；
- 不把分析假设写成已批准业务规则：重试上限与检查点间隔/批次大小（D-10）、计划输入口径、空范围是否成功、计数口径、来源配置处置边界均标 `待确认`（B-01～B-11）。
