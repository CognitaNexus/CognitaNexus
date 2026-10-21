# UC-08 用例实现分析（确认、补录和纠错成果）

**版本：** V1.0（2026-10-21）
**输入基线：** `S01 FROZEN — 69 AC BASELINE`；[SRS V2.2（69 AC）](../../../srs/CognitaNexus-Shared-SRS-V2.2-69AC.md) FR07/FR15/FR12/NF03/NF05/NF06、BR-IMP-01/02/03/04/05/08/10/11、§6.4、§7.1/§7.2、§8、§11.3/§11.4；[核心 SPEC V1.0](../../../spec/CognitaNexus-Core-SPEC-V1.0.md) §3（公共状态/INV-01/INV-04/INV-05/INV-06/INV-10）、§4（CL-01/CL-08/CL-09）、§6.2（ID-05/ID-06/ID-09/ID-10/ID-11）、§6.4（`ON_PERSON_CONFIRM`/`ON_REMOVE_RELATION`）；模块 SRS `scholar-disambiguation.md` FR15-S07～S12（细化，不反向覆盖基线）；教材第 5 章 §5.4–§5.5。
**范围：** 只分析 UC-08“确认、补录和纠错成果”。不分析 UC-05/UC-06 认领申请与认领审核、UC-07 已授权 Scholar 档案维护、UC-09/UC-10 清洗去重与身份消歧/归属改派、UC-14/UC-15 合作网络与最短路径、UC-17 后台审计与待办、UC-02 成果详情展示、UC-22 普通主页编辑；不重构全局类图。
**前置关系：** UC-05/UC-06 产生唯一的 Scholar 对象级授权（本 UC 只读）；UC-02 建立 `WorkEntity` / `Authorship` / `SourceRecord` 的静态视图（本 UC 复用其字段语义，不重画采集与去重）。二者只作为前置与边界参与。
**状态：** UC-08 分析完成（STATIC COMPLETE，UML 2/2 渲染通过）；S02 总体状态与共享追踪文件由父 Agent 维护。

## 1. 交付内容

| 文件 | 内容 |
|---|---|
| [01-UC-08用例规约](01-UC-08-use-case-spec.md) | 参与者、目标、触发、前置/成功/失败后置、E1–E15 基本流、A1–A14 备选/异常、业务规则、状态、AC 覆盖、待确认 |
| [02-事件流与需求来源](02-event-flow-trace.md) | E1–E15/A1–A14 的逐项来源与证据等级；不纳入本 UC 的事件 |
| [03-BCE分析类识别](03-bce-identification.md) | 11 个候选类、逐类理由、拒绝候选、`WorkEntity`/`Authorship`/`Scholar`/`SourceRecord`/`ReviewEvidence`/待审纠错请求 六方边界 |
| [04-顺序图与逐消息职责](04-sequence-messages.md) | M1–M76 的发送者/接收者/事件步骤/职责/依据；越权/已排除/争议/不可核验/写入失败/版本冲突/审核分支 |
| [05-VOPC与关系依据](05-vopc.md) | 11 个参与类、关系与多重性理由、排除项；VOPC 只来自本 UC 顺序图 |
| [06-分析类定义](06-class-definition.md) | 每类 Do/Know、候选分析操作、业务属性、关系、分析机制（§5.5.1–§5.5.4） |
| [07-与全局模型差异](07-global-model-diff.md) | 与 `DGM-CLASS-01-analysis.puml`、`DGM-SEQ-02-claim.puml`、`relation-decision-sequence.puml`、`scholar-achievement-relation-state.puml`、`DGM-USECASE-05`、`DGM-STATE-02` 的一致/命名/职责/关系差异与 R5 建议 |
| [08-自检与待评审](08-self-check.md) | 范围与基线检查、教材步骤对应、模型完整性、实际渲染/文档检查结果、B-01～B-10 |

## 2. UML

| 模型 | 源 | SVG | PNG |
|---|---|---|---|
| UC-08 成果确认/补录/纠错分析序列（76 条消息） | [`diagrams/DGM-SEQ-13-authorship-correction.puml`](diagrams/DGM-SEQ-13-authorship-correction.puml) | [`generated/DGM-SEQ-13-authorship-correction.svg`](diagrams/generated/DGM-SEQ-13-authorship-correction.svg) | [`generated/DGM-SEQ-13-authorship-correction.png`](diagrams/generated/DGM-SEQ-13-authorship-correction.png) |
| UC-08 VOPC（11 个参与类） | [`diagrams/DGM-VOPC-09-authorship-correction.puml`](diagrams/DGM-VOPC-09-authorship-correction.puml) | [`generated/DGM-VOPC-09-authorship-correction.svg`](diagrams/generated/DGM-VOPC-09-authorship-correction.svg) | [`generated/DGM-VOPC-09-authorship-correction.png`](diagrams/generated/DGM-VOPC-09-authorship-correction.png) |

## 3. 阅读顺序

先读 01 确认业务边界（谁能写、能写什么、什么必须审核、解除后必须保留什么），再读 02 核对每个事件的证据等级；03 给出 11 个参与类与拒绝候选（含 `WorkEntity`/`Authorship`/`Scholar`/`SourceRecord`/`ReviewEvidence`/待审请求 六方区分）；04 只描述交互，05 只从 04 提取 VOPC；06 定义职责/属性/关系/机制；07 做全局比较并提出 R5 建议；08 列出实际执行结果与未决项。

## 4. 本轮的六个核心结论

1. **确认只作用于一条 Authorship**：本人确认只把被确认的那条参与关系置为“本人确认”，并追加“本人确认”证据事件；同一学者的其余 N 条候选署名不因单次确认升级（`AC-IMPL-FR15-06`；SPEC ID-06/ID-07；§11.3；M15/M23/M27）。
2. **共享字段与他人的关系必须审核**：成果共享字段的补录/纠正、误归属申请与解除申请都先登记为待审纠错请求，公开字段与关系状态在批准前保持旧值；只有审核通过才写入生效规范值或把关系置为“已排除”（FR07；`AC-IMPL-FR07-01/02`；BR-IMP-10；M34–M44、M52–M76）。
3. **状态优先级不可被低优先级证据覆盖**：已排除 > 争议 > 待核验 > 本人确认 > 自动关联；本人不得把“已排除/争议”改为本人确认，相反归属主张会把关系置为争议并交有权审核者处置；已排除不得被后续来源自动恢复（§6.4；BR-356；`AC-IMPL-FR15-04`；A3/A7/A13）。
4. **解除只终止一条关系**：解除后 `WorkEntity`、全部 `SourceRecord` 与其余合法 `Authorship` 保留，读者仍可检索到该成果；待核验/争议/已排除不得成为确定合作边（`AC-IMPL-FR07-01`；FR15-S11/BR-359；SPEC ID-09/INV-05；§6.4；`AC-IMPL-FR12-04`；M67–M72）。
5. **不投稿、不出版、不造实体**：本 UC 只对**已收录**成果补录可核验元数据，不作为论文投稿/出版入口，也不为“零成果主页”或未收录成果凭空生成 `WorkEntity`/`Authorship`（FR07“已收录成果”；`AC-IMPL-FR07-03`；A14；B-03）。
6. **证据三分且审计独立**：`SourceRecord`（来源原值，只读）、`ReviewEvidence`（提交材料＋可见范围）、`AuditEvidence`（受保护写入与决定留痕）是三个对象，不得合并为单一“可信”标签；原始来源事实不被补录/纠正覆盖（BR-IMP-04/11；INV-01/INV-10；BR-355；§7.2）。

## 5. 本轮明确不做

- 不修改 SRS、SPEC、69 AC、S01 冻结材料、全局 UML 与其他 UC 目录；
- 不修改 `docs/analysis/stage-02/03-AC-UC-分析模型追踪.md` 等共享追踪文件，也不改 `automation/analysis-class-registry.md`、`automation/unresolved-decisions.md`（登记建议见 07 §6）；
- 不把 `DGM-CLASS-01-analysis.puml` 当作本 UC VOPC；不把 `DGM-SEQ-02-claim.puml`、`relation-decision-sequence.puml` 当作本 UC 实现；
- 不分析认领审核决定（UC-06）、档案字段维护（UC-07）、去重/消歧/归属改派与拆分（UC-09/UC-10）、合作网络建边（UC-14）、后台审计查询（UC-17）；
- 不引入 Java/数据库/API/框架/部署设计；
- 不把分析假设写成已批准业务规则：共享字段清单、待审请求状态集合、可直接确认的关系状态集合、审核证据最低集合、归属改派入口分工均标 `待确认`（B-01～B-08）。
