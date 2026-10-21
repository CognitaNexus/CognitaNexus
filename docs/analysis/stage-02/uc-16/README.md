# UC-16 用例实现分析（核对四类覆盖与来源）

**版本：** V1.0（2026-10-21）
**输入基线：** `S01 FROZEN — 69 AC BASELINE`；[SRS V2.2（69 AC）](../../../srs/CognitaNexus-Shared-SRS-V2.2-69AC.md) FR16（`AC-IMPL-FR16-01/02`）、FR02、FR09、FR11、§2.1、§6.1、§7.1、§7.2、§8、§11.4、NF01/NF02/NF07、BR-IMP-01/02/04/06/07；[核心 SPEC V1.0](../../../spec/CognitaNexus-Core-SPEC-V1.0.md) §3/§4.1/§5；教材第 5 章 §5.4–§5.5。
**范围：** 只分析 UC-16“核对四类覆盖与来源”。不分析 UC-01 检索、UC-02 详情展示、UC-03 主页、UC-08 确认纠错、UC-09/10 治理核验、UC-11～15 统计与网络、UC-17 审计、UC-18/19 采集与增量；不重构全局类图。
**上游：** [`uc-02/`](../uc-02/README.md)（`WorkEntity`/`SourceRecord`/`WorkVersion` 字段语义与详情展示）、[`uc-09/`](../uc-09/README.md)（去重实体与有效出处的计数口径）。
**状态：** UC-16 分析完成（STATIC COMPLETE，UML 2/2 渲染通过）；S02 总体状态与共享追踪文件由父 Agent 维护。

## 1. 本轮五个核心结论

1. **覆盖是三项合取**：四类（论文/专利/项目/获奖）各需“类型可识别 ＋ 类型适用字段可得（缺失标未知）＋ 至少一条合法来源”，缺任一即该项覆盖不通过并列缺项（`AC-IMPL-FR16-01`；BR-UC16-01；M13）。
2. **每条来源保留四要素**：来源 ID、采集/导入时间、许可或使用范围、数据性质，逐条可追溯；许可不明不扩大公开范围也不删除来源（FR16；§7.1；BR-UC16-03；M11/M12）。
3. **规模按去重成果实体计**：一条成果跨两个来源且有明示版本关联时仍计 **1**；来源数、版本数、参与关系数都不进入分子（`AC-IMPL-FR16-02`；BR-IMP-02/06；INV-02；M14–M18）。
4. **真实数单列、模拟/混合显式标注**：真实/模拟/混合互斥分组，未确认性质显式未知不默认真实，不以模拟冒充真实合规来源（BR-IMP-07；INV-09；SRS §2.1；M19–M21）。
5. **目标是目标、未运行是未运行**：1,000,000（功能联调）与 5,000,000（最终容量）在本轮一律标 **NOT RUN**，核对结论是**读时投影**，不持久化为核对报告实体、不改变任何业务实体（SRS §2.1、FR16、NF02；BR-UC16-08/09；M22/M25/M28）。

## 2. 交付内容

| 文件 | 内容 |
|---|---|
| [01-UC-16用例规约](01-UC-16-use-case-spec.md) | 参与者（访客、核对执行者/管理员）、目标、触发、前置/成功/失败后置、E1–E11 基本流、A1–A12 备选/异常、BR-UC16-01～15、关联状态、AC 覆盖、Q-01～Q-11 |
| [02-事件流与需求来源](02-event-flow-trace.md) | E1–E11/A1–A12 的逐项来源与证据等级（已证实/分析推导/待确认/模块细化）；不纳入本 UC 的事件 |
| [03-BCE分析类识别](03-bce-identification.md) | 5 个候选类、逐类理由、13 项拒绝候选、九条边界结论 |
| [04-顺序图与逐消息职责](04-sequence-messages.md) | M1–M28 的发送者/接收者/事件步骤/职责/依据；缺项/差异/失败分支与只读边界 |
| [05-VOPC与关系依据](05-vopc.md) | 5 个参与类、关系与多重性理由、排除项；VOPC 只来自本 UC 顺序图 |
| [06-分析类定义](06-class-definition.md) | 每类 Do/Know、候选分析操作、业务属性、类型适用字段事实边界、关系、13 项分析机制（§5.5.1–§5.5.4） |
| [07-与全局模型差异](07-global-model-diff.md) | 与 `DGM-CLASS-01-analysis.puml`、`01-BCE分析类模型.md`、`DGM-ACTIVITY-07` 的一致/命名/职责/关系差异与 R5 建议 |
| [08-自检与待评审](08-self-check.md) | 范围与基线检查、教材步骤对应、模型完整性、实际渲染/文档检查结果、Q-01～Q-11 |

## 3. UML

| 模型 | 源 | SVG | PNG |
|---|---|---|---|
| UC-16 四类覆盖与去重规模核对分析序列（28 条消息） | [`diagrams/DGM-SEQ-24-four-types-scale.puml`](diagrams/DGM-SEQ-24-four-types-scale.puml) | [`generated/DGM-SEQ-24-four-types-scale.svg`](diagrams/generated/DGM-SEQ-24-four-types-scale.svg) | [`generated/DGM-SEQ-24-four-types-scale.png`](diagrams/generated/DGM-SEQ-24-four-types-scale.png) |
| UC-16 VOPC（5 个参与类） | [`diagrams/DGM-VOPC-20-four-types-scale.puml`](diagrams/DGM-VOPC-20-four-types-scale.puml) | [`generated/DGM-VOPC-20-four-types-scale.svg`](diagrams/generated/DGM-VOPC-20-four-types-scale.svg) | [`generated/DGM-VOPC-20-four-types-scale.png`](diagrams/generated/DGM-VOPC-20-four-types-scale.png) |

## 4. 阅读顺序

先读 01 确认“覆盖怎么算、规模怎么算、什么不算通过”；再读 02 核对每个事件的证据等级；03 给出 5 个参与类与拒绝候选（含“核对结果不是实体”）；04 只描述交互，05 只从 04 提取 VOPC；06 定义职责/属性/关系/机制；07 做全局比较并提出 R5 建议；08 列出实际执行结果与未决项。

## 5. 本轮不做

- 不修改 SRS、SPEC、69 AC、S01 冻结材料、全局 UML 与其他 UC 目录；
- 不修改共享追踪文件（`03-AC-UC-分析模型追踪.md`）、跨 UC 登记表（`automation/analysis-class-registry.md`）与待决登记（`automation/unresolved-decisions.md`）；登记建议见 07 §6，由父 Agent 决定；
- 不分析检索/详情/治理/统计/网络/审计/采集（UC-01/02/09/10/11～15/17/18/19）；
- 不把全局 `DGM-CLASS-01` 或 `DGM-ACTIVITY-07` 冒充 UC-16 的实现证据；
- 不引入数据库、Java、API、框架、缓存、检索索引或部署设计；
- 不把未冻结的口径（范围声明方式、模拟补齐、数据性质第四分组、待核验是否入分子、差异维度、是否留痕）写成已批准需求；
- 不新增或改写任何 AC/BR；不执行压测，不声称规模达标。
