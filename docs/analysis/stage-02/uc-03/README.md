# UC-03 用例实现分析（查看公开个人主页/学者档案）

**版本：** V1.0（2026-10-21）
**输入基线：** `S01 FROZEN — 69 AC BASELINE`；[SRS V2.2（69 AC）](../../../srs/CognitaNexus-Shared-SRS-V2.2-69AC.md) FR03/FR04/FR15/NF01/NF06、BR-IMP-01/03/05/11、§7.2、§12；[核心 SPEC V1.0](../../../spec/CognitaNexus-Core-SPEC-V1.0.md) ID-01/ID-06、SR-12；教材第 5 章 §5.4–§5.5。
**范围：** 只分析 UC-03“查看公开个人主页/学者档案”。不分析 UC-01 检索、UC-02 成果详情、UC-05/06 认领与审核、UC-07 学者档案维护、UC-10 身份核验、UC-22 本人主页编辑；不重构全局类图。
**状态：** UC-03 分析完成（STATIC COMPLETE，UML 2/2 渲染通过）；S02 总体状态与共享追踪文件由父 Agent 维护。

## 1. 交付内容

| 文件 | 内容 |
|---|---|
| [01-UC-03用例规约](01-UC-03-use-case-spec.md) | 参与者（访客）、目标、触发、前置/成功/失败后置、E1–E10 基本流、A1–A8 备选/异常、业务规则、状态、AC 覆盖、待确认 |
| [02-事件流与需求来源](02-event-flow-trace.md) | E1–E10/A1–A8 的逐项来源与证据等级；不纳入本 UC 的事件 |
| [03-BCE分析类识别](03-bce-identification.md) | 6 个候选类、逐类理由、拒绝候选、UserProfile≠Scholar≠Authorship≠WorkEntity 边界 |
| [04-顺序图与逐消息职责](04-sequence-messages.md) | M1–M27 的发送者/接收者/事件步骤/职责/依据；零成果/未认领/待核验/不可访问/失败分支 |
| [05-VOPC与关系依据](05-vopc.md) | 6 个参与类、关系与多重性理由、排除项；VOPC 只来自本 UC 顺序图 |
| [06-分析类定义](06-class-definition.md) | 每类 Do/Know、候选分析操作、业务属性、关系、分析机制（§5.5.1–§5.5.4） |
| [07-与全局模型差异](07-global-model-diff.md) | 与 `DGM-CLASS-01-analysis.puml` 的一致/命名/职责/关系差异与 R5 建议 |
| [08-自检与待评审](08-self-check.md) | 范围与基线检查、教材步骤对应、模型完整性、实际渲染/文档检查结果、Q-01～Q-09 |

## 2. UML

| 模型 | 源 | SVG | PNG |
|---|---|---|---|
| UC-03 公开主页/学者档案分析序列（27 条消息） | [`diagrams/DGM-SEQ-08-profile.puml`](diagrams/DGM-SEQ-08-profile.puml) | [`generated/DGM-SEQ-08-profile.svg`](diagrams/generated/DGM-SEQ-08-profile.svg) | [`generated/DGM-SEQ-08-profile.png`](diagrams/generated/DGM-SEQ-08-profile.png) |
| UC-03 VOPC（6 个参与类） | [`diagrams/DGM-VOPC-04-profile.puml`](diagrams/DGM-VOPC-04-profile.puml) | [`generated/DGM-VOPC-04-profile.svg`](diagrams/generated/DGM-VOPC-04-profile.svg) | [`generated/DGM-VOPC-04-profile.png`](diagrams/generated/DGM-VOPC-04-profile.png) |

## 3. 阅读顺序

先读 01 确认业务边界（UserProfile 与 Scholar 是两个对象），再读 02 核对每个事件的证据；03 给出 6 个参与类与拒绝候选；04 只描述交互，05 只从 04 提取 VOPC；06 定义类的职责/属性/关系/机制；07 做全局比较并提出 R5 建议；08 列出实际执行结果与未决项。

## 4. 本轮的三个核心结论

1. **零成果不是错误**：任何注册用户的 UserProfile 正常打开并显示“暂无公开成果”空态，不返回 404（`AC-IMPL-FR03-03`）。
2. **主页 ≠ 学者档案**：UserProfile 与同名 Scholar 是两个页面对象、两个核验/认领状态，不自动合并、不把 UserProfile 呈现为已认证学者（`AC-IMPL-FR03-04`；BR-IMP-03；SRS §12）。
3. **只读且只公开合格关系**：待核验/争议/已排除关系不得显示为已确认合作或本人确认；公开响应不含凭据、审核材料与私有证明（`AC-IMPL-FR03-02`、`AC-IMPL-NF06-01`；BR-IMP-01/11）。

## 5. 本轮不做

- 不修改 SRS、SPEC、69 AC、S01 冻结材料、全局 UML 与其他 UC 目录；
- 不修改共享追踪文件（`03-AC-UC-分析模型追踪.md`）、跨 UC 类登记表与状态文件；
- 不把 UC-22 主页编辑、UC-05/06 认领/审核、UC-02 成果详情、UC-10 身份核验并入本 UC 流程；
- 不把全局 `DGM-CLASS-01` 冒充 UC-03 VOPC；
- 不引入数据库、Java、API、框架、缓存、部署或界面布局设计；
- 不把未冻结的公开字段集合、空态文案适用范围、待核验关系展示口径写成已批准需求（见 08 Q-01～Q-09）；
- 不新增或改写任何 AC/BR。
