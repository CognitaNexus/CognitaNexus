# UC-11 用例实现分析（查看成果统计）

**版本：** V1.0（2026-10-21）
**输入基线：** `S01 FROZEN — 69 AC BASELINE`；[SRS V2.2（69 AC）](../../../srs/CognitaNexus-Shared-SRS-V2.2-69AC.md) FR11（`AC-IMPL-FR11-01/02`）及 FR16（`AC-IMPL-FR16-02` 去重口径边界）、FR15（归属生效边界）、NF01/NF05/NF06、BR-IMP-01/02/06/07/10/11、§2.1、§6.1、§7.1/§7.2、§8、§11.4、§12；[核心 SPEC V1.0](../../../spec/CognitaNexus-Core-SPEC-V1.0.md) §3（INV-01/02/04/06/10）、§4.3（SR-04/SR-06）、§6.1、§7.2、Q-SPEC-03/04/08；模块细化 [`analytics-graph.md`](../../../srs/modules/analytics-graph.md) FR11-S01～S12（细化，不反向覆盖基线）；教材第 5 章 §5.4–§5.5。
**范围：** 只分析 UC-11“查看成果统计”：按**去重成果实体**统计类型/年度/领域分布，并按“成果发生时机构”做机构计数排名，同屏披露数据范围、计数口径、更新时间与数据性质。不分析 UC-12 机构排名独立视图的入口分工、UC-13 领域热点、UC-14/UC-15 合作网络与最短路径、UC-01/UC-02 检索与详情、UC-03 门户、UC-09 去重清洗、UC-08/UC-10 归属判定与纠正、UC-17 管理待办与审计；不重构全局类图。
**前置关系：** UC-09/UC-18/UC-19 提供已去重成果与来源事实（本 UC 不判定实体唯一性）；UC-10/UC-08 提供生效归属（本 UC 只读生效结果）；UC-17/FR16 提供机构规范名与领域分类映射（本 UC 只读）。
**状态：** UC-11 分析完成（STATIC COMPLETE，UML 2/2 渲染通过）；S02 总体状态与共享追踪文件由父 Agent 维护。

## 1. 交付内容

| 文件 | 内容 |
|---|---|
| [01-UC-11用例规约](01-UC-11-use-case-spec.md) | 参与者、目标、触发、前置/成功/失败后置、E1–E14 基本流、A1–A19 备选/异常、19 条业务规则、关联状态、AC 覆盖、B-01～B-09 与 C-01/C-02 |
| [02-事件流与需求来源](02-event-flow-trace.md) | E1–E14/A1–A19 的逐项来源与证据等级；后置条件来源；不纳入本 UC 的事件 |
| [03-BCE分析类识别](03-bce-identification.md) | 5 个候选类、逐类理由、拒绝候选、`WorkEntity`/`Authorship`/`Institution`/归属事实/投影五项边界 |
| [04-顺序图与逐消息职责](04-sequence-messages.md) | M1–M49 的发送者/接收者/事件步骤/职责/依据；事件—分支—消息对照；空/失败/未更新/规模/越界分支 |
| [05-VOPC与关系依据](05-vopc.md) | 5 个参与类、2 条业务关联与多重性理由、属性化事实取舍、排除项；VOPC 只来自本 UC 顺序图 |
| [06-分析类定义](06-class-definition.md) | 每类 Do/Know、候选分析操作、业务属性、关系、分析机制（§5.5.1–§5.5.4）；14 项机制汇总 |
| [07-与全局模型差异](07-global-model-diff.md) | 与 `DGM-CLASS-01-analysis.puml`、`01-BCE分析类模型.md`、`DGM-ACTIVITY-02/04`、`DGM-STATE-03`、`DGM-USECASE-02`、模块细化的差异与 R5 建议；冲突 C-01/C-02 |
| [08-自检与待评审](08-self-check.md) | 范围与基线检查、教材步骤对应、模型完整性、实际渲染/文档检查结果、NOT RUN、B-01～B-09 与 C-01/C-02 |

## 2. UML

| 模型 | 源 | SVG | PNG |
|---|---|---|---|
| UC-11 成果统计分析序列（49 条消息） | [`diagrams/DGM-SEQ-19-statistics.puml`](diagrams/DGM-SEQ-19-statistics.puml) | [`generated/DGM-SEQ-19-statistics.svg`](diagrams/generated/DGM-SEQ-19-statistics.svg) | [`generated/DGM-SEQ-19-statistics.png`](diagrams/generated/DGM-SEQ-19-statistics.png) |
| UC-11 VOPC（5 个参与类） | [`diagrams/DGM-VOPC-15-statistics.puml`](diagrams/DGM-VOPC-15-statistics.puml) | [`generated/DGM-VOPC-15-statistics.svg`](diagrams/generated/DGM-VOPC-15-statistics.svg) | [`generated/DGM-VOPC-15-statistics.png`](diagrams/generated/DGM-VOPC-15-statistics.png) |

## 3. 阅读顺序

先读 01 确认业务边界（计数单位为什么是去重成果实体、机构归属为什么必须取“发生时”且未知不进名次、模拟数据为什么不能进默认现实视图），再读 02 核对每个事件的证据等级；03 给出 5 个参与类与拒绝候选（含“投影不是实体”）；04 只描述交互，05 只从 04 提取 VOPC；06 定义职责/属性/关系/机制；07 做全局比较并提出 R5 建议与冲突；08 列出实际执行结果与未决项。

## 4. 本轮的五个核心结论

1. **计数单位是去重成果实体，不是来源、版本或作者**：一成果跨多来源、已确认版本合并、多作者都只计一次；未确认版本按现有实体分别计并披露待确认数（BR-IMP-02/06；`AC-IMPL-FR11-01`；`AC-IMPL-FR16-02` 边界；M5/M7）。
2. **默认现实视图只纳入真实、公开、生效、合格数据**：模拟与混合数据只在显式演示模式下展示，并固定标识“不得用于描述真实世界”；缺数据性质标识不计入真实（BR-IMP-07；`AC-IMPL-FR11-02`；FR11-S12；M3/M31）。
3. **机构排名只采用有证据的成果发生时机构**：同一成果在同一机构计一次、不同机构各计一次，机构未知单列且不进入已知名次；不得用学者当前任职单位推断（BR-IMP-06；BR-513；SR-06；`AC-IMPL-FR11-01`；M18–M25）。
4. **可复核来自“披露＋回显”**：数据范围、时间范围、统计条件、计数口径、更新时间与数据性质六项同屏，条件变更后同步刷新；条目可下钻回显同条件供检索复核（FR11 规范性要求；BR-514；FR11-S01/S11；M11/M32/M34–M37）。
5. **统计是读时投影，不是持久实体，也不构成质量评价**：全图无写消息，不建持久“统计结果/口径版本”；机构排名只表达计数与并列规则，不作质量、影响力、机构优劣或个人综合学术排名结论（BR-522；SRS §2.1；05 §4；C-01）。

## 5. 本轮明确不做

- 不修改 SRS、SPEC、69 AC、S01 冻结材料、全局 UML、模块 SRS 与其他 UC 目录；
- 不修改 `docs/analysis/stage-02/03-AC-UC-分析模型追踪.md` 等共享追踪文件，也不改 `automation/analysis-class-registry.md`、`automation/unresolved-decisions.md`（登记建议见 07 §6）；
- 不把 `DGM-CLASS-01-analysis.puml` 当作本 UC VOPC；不把 `DGM-ACTIVITY-02`/`DGM-STATE-03`/`DGM-ACTIVITY-04` 当作本 UC 实现；
- 不分析领域热点（UC-13）、合作网络与最短路径（UC-14/15）、检索与详情（UC-01/02）、去重与清洗（UC-09）、归属判定与纠正（UC-08/10）、后台审计（UC-17）；
- 不引入统计实现：聚合引擎、缓存、任务/重试、报表、接口、DTO、数据库结构；
- 不把分析假设写成已批准规则：更新时间语义、分类层级与占比分母、样本阈值、展示上限、上一版结果是否保留、与 UC-12 的视图分工均标 `待确认`（B-01～B-09）。
