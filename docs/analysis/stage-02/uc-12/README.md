# UC-12 用例实现分析（查看机构排名）

**版本：** V1.0（2026-10-21）
**输入基线：** `S01 FROZEN — 69 AC BASELINE`（[09-S01冻结记录](../../stage-01/09-S01冻结记录.md)）；[SRS V2.2（69 AC）](../../../srs/CognitaNexus-Shared-SRS-V2.2-69AC.md) FR11（`AC-IMPL-FR11-01/02`）及 FR16（`AC-IMPL-FR16-02` 去重口径边界）、FR15（归属生效边界）、NF01/NF05、BR-IMP-01/02/06/07/10/11、§2.1、§6.1、§7.1/§7.2、§8、§11.4；[核心 SPEC V1.0](../../../spec/CognitaNexus-Core-SPEC-V1.0.md) §3（INV-01/02/04/06/09）、§4.3（SR-06）、§7.2（T35）、Q-SPEC-03/04/08；模块细化 [`analytics-graph.md`](../../../srs/modules/analytics-graph.md) FR11-S05/S06/S08/S09/S10/S12（细化，不反向覆盖基线）；教材第 5 章 §5.4–§5.5。
**范围：** 只分析 UC-12“查看机构排名”：访客在公开、可复核的前提下，按**去重成果实体**查看机构维度计数排名——机构归属只取**有证据的成果发生时机构**；同一成果同一规范机构计一次、不同机构各计一次；机构未知单列且不进入已知名次；机构别名/历史名按已生效规范名归并为一行；默认现实视图只纳入真实、公开、生效、合格数据，模拟/混合只在显式演示模式展示并标识；同屏披露筛选条件、计数单位、归属与归并规则、未知处理、并列规则、分母/覆盖率、数据性质与更新时间；不作质量、影响力或机构优劣结论。
**不在本 UC：** 类型/年度/领域分布统计（UC-11）、领域热点（UC-13）、合作网络与最短路径（UC-14/UC-15）、成果检索与详情（UC-01/UC-02）、公开主页与学者档案（UC-03）、成果去重与清洗（UC-09）、身份与归属判定/纠正（UC-08/UC-10）、机构规范名与分类映射维护（FR14/FR16）、后台审计（UC-17）。
**前置关系：** UC-09/UC-18/UC-19 提供已去重成果实体与来源事实（本 UC 不判定实体唯一性）；UC-08/UC-10 提供生效归属（本 UC 只读生效结果）；FR14/FR16 的维护方提供已生效机构规范名、别名与合并链（本 UC 只读，不改写）。
**状态：** UC-12 分析完成（STATIC COMPLETE，UML 2/2 渲染通过）；S02 总体状态与共享追踪文件由父 Agent 维护。

## 1. 交付内容

| 文件 | 内容 |
|---|---|
| [01-UC-12用例规约](01-UC-12-use-case-spec.md) | 参与者、目标、触发、前置/成功/失败后置、E1–E14 基本流、A1–A19 备选/异常、19 条业务规则、关联状态、AC 覆盖、B-01～B-12 与 C-01/C-02 |
| [02-事件流与需求来源](02-event-flow-trace.md) | E1–E14/A1–A19 的逐项来源与证据等级；后置条件来源；不纳入本 UC 的事件 |
| [03-BCE分析类识别](03-bce-identification.md) | 5 个候选类、逐类理由、拒绝候选、`WorkEntity`/`Institution`/`Authorship`/排名视图投影等九项对象边界 |
| [04-顺序图与逐消息职责](04-sequence-messages.md) | M1–M46 的发送者/接收者/事件步骤/职责/依据；事件—分支—消息对照；空/失败/未更新/类别/规模/越界分支 |
| [05-VOPC与关系依据](05-vopc.md) | 5 个参与类、2 条业务关联与多重性理由、属性化事实取舍、排除项；VOPC 只来自本 UC 顺序图 |
| [06-分析类定义](06-class-definition.md) | 每类 Do/Know、候选分析操作、业务属性、关系、分析机制（§5.5.1–§5.5.4）；13 项机制汇总 |
| [07-与全局模型差异](07-global-model-diff.md) | 与 `DGM-CLASS-01-analysis.puml`、`01-BCE分析类模型.md`、`DGM-ACTIVITY-04`、`DGM-ACTIVITY-02`、`DGM-STATE-03`、`DGM-USECASE-02`、模块细化、候选类登记表、待决清单的差异与 R5 建议；冲突 C-01/C-02 |
| [08-自检与待评审](08-self-check.md) | 范围与基线检查、教材步骤对应、模型完整性、实际渲染/文档检查结果、NOT RUN、B-01～B-12 与 C-01/C-02 |

## 2. UML

| 模型 | 源 | SVG | PNG |
|---|---|---|---|
| UC-12 机构排名分析序列（46 条消息） | [`diagrams/DGM-SEQ-20-institution-rank.puml`](diagrams/DGM-SEQ-20-institution-rank.puml) | [`generated/DGM-SEQ-20-institution-rank.svg`](diagrams/generated/DGM-SEQ-20-institution-rank.svg) | [`generated/DGM-SEQ-20-institution-rank.png`](diagrams/generated/DGM-SEQ-20-institution-rank.png) |
| UC-12 VOPC（5 个参与类） | [`diagrams/DGM-VOPC-16-institution-rank.puml`](diagrams/DGM-VOPC-16-institution-rank.puml) | [`generated/DGM-VOPC-16-institution-rank.svg`](diagrams/generated/DGM-VOPC-16-institution-rank.svg) | [`generated/DGM-VOPC-16-institution-rank.png`](diagrams/generated/DGM-VOPC-16-institution-rank.png) |

## 3. 阅读顺序

先读 01 确认业务边界（为什么计数单位是去重成果实体、机构归属为什么必须取“发生时且有证据”、机构未知为什么不能进名次、别名为什么要归并、模拟数据为什么不能进默认现实排名），再读 02 核对每个事件的证据等级；03 给出 5 个参与类与拒绝候选（含“排名视图是投影不是实体”）；04 只描述交互，05 只从 04 提取 VOPC；06 定义职责/属性/关系/机制；07 做全局比较并提出 R5 建议与冲突；08 列出实际执行结果与未决项。

## 4. 本轮的五个核心结论

1. **计数单位是去重成果实体，机构是计数维度而不是分母来源**：一成果跨多来源、已确认版本合并、多作者都只计一次；机构维度合计可以大于实体数，但单位不变（BR-IMP-02/06；`AC-IMPL-FR11-01`；`AC-IMPL-FR16-02` 边界；M5/M6/M15）。
2. **机构归属只取有证据的“成果发生时机构”**：缺失或无证据者进入“机构未知”，未知单列且不进入已知名次、也不分摊；不得用作者（学者）当前任职单位推断（BR-IMP-06；BR-513；SR-06；SPEC T35；M11/M15）。
3. **同一机构只出现一行**：机构别名/历史名按**已生效**规范名与合并链归并；本 UC 只读映射，不新增机构、不改别名、不合并机构；未映射机构保留原值并披露（FR11-S08 E2；CL-05；M12–M14；未映射是否进名次待确认 B-01）。
4. **默认现实排名只纳入真实、公开、生效、合格数据**：模拟与混合数据只在显式演示模式展示，并固定标识“不得用于描述真实世界”；缺数据性质标识不得默认当作真实（BR-IMP-07；`AC-IMPL-FR11-02`；M3/M24）。
5. **排名是读时投影，不是持久实体，也不是质量评价**：全图无写消息，不建持久“排名结果/排名快照/口径版本”；排名只表达计数与并列名次，同屏披露条件与口径，不作质量、影响力、机构优劣或个人综合学术排名结论（BR-522；SRS §2.1；05 §4）。

## 5. 本轮明确不做

- 不修改 SRS、SPEC、69 AC、S01 冻结材料、全局 UML、模块 SRS 与其他 UC 目录；
- 不修改 `docs/analysis/stage-02/03-AC-UC-分析模型追踪.md` 等共享追踪文件，也不改 `../automation/analysis-class-registry.md`、`../automation/unresolved-decisions.md`、`../automation/progress.json`（登记建议见 07 §6）；
- 不把 `DGM-CLASS-01-analysis.puml` 当作本 UC VOPC；不把 `DGM-ACTIVITY-04`/`DGM-ACTIVITY-02`/`DGM-STATE-03`/`DGM-USECASE-02` 当作本 UC 实现；
- 不分析分布统计（UC-11）、领域热点（UC-13）、合作网络与最短路径（UC-14/15）、检索与详情（UC-01/02）、去重与归属治理（UC-09/10）、后台审计（UC-17）；
- 不引入实现设计：不做数据库表、接口、聚合引擎、缓存、任务、报表或 DTO；
- 不把分析假设写成已批准规则：机构别名合并与未映射处理、机构类别维度、并列内次序、占比分母与覆盖率、样本阈值、展示上限、更新时间语义、跨机构归属口径（Q-4）均标 `待确认`（B-01～B-12、C-02）。
