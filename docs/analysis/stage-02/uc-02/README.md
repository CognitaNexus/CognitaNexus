# UC-02 用例实现分析（查看成果详情与来源）

**版本：** V1.0（2026-10-21）
**输入基线：** `S01 FROZEN — 69 AC BASELINE`；[SRS V2.2（69 AC）](../../../srs/CognitaNexus-Shared-SRS-V2.2-69AC.md) FR02/FR16/§7.1/§7.2/§8/§9；教材第 5 章 §5.4–§5.5。
**范围：** 只分析 UC-02“查看成果详情与来源”。不分析 UC-01 检索、UC-03 主页、UC-08 确认纠错、UC-09/10 治理核验、UC-16 规模核对、UC-11～15 统计与网络；不重构全局类图。
**状态：** UC-02 分析完成（STATIC COMPLETE，UML 2/2 渲染通过）；S02 总体状态与追踪文件由父 Agent 维护。

## 1. 交付内容

| 文件 | 内容 |
|---|---|
| [01-UC-02用例规约](01-UC-02-use-case-spec.md) | 参与者（访客、原站）、目标、触发、前后置、E1–E11 基本流、A1–A10 备选/异常、业务规则、状态读用、AC 覆盖、待确认 |
| [02-事件流与需求来源](02-event-flow-trace.md) | E1–E11/A1–A10 的逐项来源与证据等级；不纳入本 UC 的事件 |
| [03-BCE分析类识别](03-bce-identification.md) | 6 个候选类、逐类理由、12 项拒绝候选、边界结论 |
| [04-顺序图与逐消息职责](04-sequence-messages.md) | M1–M28 的发送者/接收者/事件步骤/职责/依据；空/失败/不可访问/外链失败分支 |
| [05-VOPC与关系依据](05-vopc.md) | 6 个参与类、关系与多重性理由、排除项 |
| [06-分析类定义](06-class-definition.md) | 每类 Do/Know、候选分析操作、业务属性、关系、分析机制（§5.5.1–§5.5.4） |
| [07-与全局模型差异](07-global-model-diff.md) | 与 `DGM-CLASS-01-analysis.puml` 的一致/命名/职责/关系差异与 R5 建议 |
| [08-自检与待评审](08-self-check.md) | 范围与基线检查、教材步骤对应、模型完整性、实际渲染/文档检查结果、Q-01～Q-10 |

## 2. UML

| 模型 | 源 | SVG | PNG |
|---|---|---|---|
| UC-02 详情与来源分析序列（28 条消息） | [`diagrams/DGM-SEQ-07-detail.puml`](diagrams/DGM-SEQ-07-detail.puml) | [`generated/DGM-SEQ-07-detail.svg`](diagrams/generated/DGM-SEQ-07-detail.svg) | [`generated/DGM-SEQ-07-detail.png`](diagrams/generated/DGM-SEQ-07-detail.png) |
| UC-02 VOPC（6 个参与类） | [`diagrams/DGM-VOPC-03-detail.puml`](diagrams/DGM-VOPC-03-detail.puml) | [`generated/DGM-VOPC-03-detail.svg`](diagrams/generated/DGM-VOPC-03-detail.svg) | [`generated/DGM-VOPC-03-detail.png`](diagrams/generated/DGM-VOPC-03-detail.png) |

## 3. 阅读顺序

先读 01 确认范围与边界，再读 02 核对每个事件的来源；03 给出 6 个参与类的识别与拒绝理由；04 只描述交互，05 只从 04 提取 VOPC；06 定义类的职责/属性/关系/机制；07 做全局比较并提出 R5 建议；08 列出实际执行结果与未决项。

## 4. 本轮不做

- 不修改 SRS、SPEC、69 AC、S01 冻结材料、全局 UML 与其他 UC 目录；
- 不修改共享追踪文件（`03-AC-UC-分析模型追踪.md`）、跨 UC 登记表与状态文件；
- 不分析 UC-01 检索、UC-03 主页、UC-08 确认纠错、UC-09/10 治理、UC-16 规模核对、UC-11～15 统计与网络；
- 不把全局 `DGM-CLASS-01` 冒充 UC-02 VOPC；
- 不引入数据库、Java、API、框架、缓存、部署或界面布局设计；
- 不把未冻结的展示口径（多来源顺序、疑似版本、疑似参与、外链文案/重试）写成已批准需求；
- 不新增或改写任何 AC/BR。
