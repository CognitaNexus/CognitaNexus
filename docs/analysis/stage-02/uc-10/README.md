# UC-10 用例实现分析（核验学者身份与归属）

**版本：** V1.0（2026-10-21）
**输入基线：** `S01 FROZEN — 69 AC BASELINE`；[SRS V2.2（69 AC）](../../../srs/CognitaNexus-Shared-SRS-V2.2-69AC.md) FR15（`AC-IMPL-FR15-01`～`06`）及 FR03/FR12/FR09 边界、NF03/NF05/NF06、BR-IMP-01/03/04/05/08/10/11、§6.4、§7.1/§7.2、§8、§11.3/§11.4/§12；[核心 SPEC V1.0](../../../spec/CognitaNexus-Core-SPEC-V1.0.md) §3（INV-01/02/04/05/06/10）、§6.1、§6.2（ID-01～ID-12）、§6.3、§6.4、§6.5；模块 SRS [`scholar-disambiguation.md`](../../../srs/modules/scholar-disambiguation.md) FR15-S01～S13（细化，不反向覆盖基线）；教材第 5 章 §5.4–§5.5。
**范围：** 只分析 UC-10“核验学者身份与归属”。不分析 UC-05/UC-06 认领申请与认领审核、UC-07 已授权 Scholar 档案维护、UC-08 本人确认/补录/纠错、UC-09 清洗去重、UC-14/UC-15 合作网络与最短路径、UC-17 后台待办与审计查询、UC-22 普通主页编辑；不重构全局类图。
**前置关系：** UC-09 提供已去重成果与来源参与者事实（本 UC 不判定成果实体唯一性）；UC-18/19 提供采集与增量到达（前置）；UC-05/UC-06 产生对象级授权（本 UC 只读）。三者只作为前置与边界参与。
**状态：** UC-10 分析完成（STATIC COMPLETE，UML 2/2 渲染通过）；S02 总体状态与共享追踪文件由父 Agent 维护。

## 1. 交付内容

| 文件 | 内容 |
|---|---|
| [01-UC-10用例规约](01-UC-10-use-case-spec.md) | 参与者、目标、触发、前置/成功/失败后置、E1–E18 基本流、A1–A20 备选/异常、20 条业务规则、关联状态、AC 覆盖、B-01～B-10 待确认 |
| [02-事件流与需求来源](02-event-flow-trace.md) | E1–E18/A1–A20 的逐项来源与证据等级；后置条件来源；不纳入本 UC 的事件 |
| [03-BCE分析类识别](03-bce-identification.md) | 12 个候选类、逐类理由、拒绝候选、`Scholar`/`Authorship`/`WorkEntity`/`SourceRecord`/`IdentityCandidate`/`AttributionCandidate`/`ReviewEvidence`/`AuditEvidence` 八项边界 |
| [04-顺序图与逐消息职责](04-sequence-messages.md) | M1–M103 的发送者/接收者/事件步骤/职责/依据；事件—分支—消息对照；越权/已排除/争议/候选过多/版本冲突/拆分分支 |
| [05-VOPC与关系依据](05-vopc.md) | 12 个参与类、5 条业务关联与多重性理由、属性替代关联的取舍、排除项；VOPC 只来自本 UC 顺序图 |
| [06-分析类定义](06-class-definition.md) | 每类 Do/Know、候选分析操作、业务属性、关系、分析机制（§5.5.1–§5.5.4）；16 项机制汇总 |
| [07-与全局模型差异](07-global-model-diff.md) | 与 `DGM-CLASS-01-analysis.puml`、`01-BCE分析类模型.md`、`DGM-ACTIVITY-07`、`DGM-SEQ-01`、`DGM-STATE-05`、`scholar-disambiguation.md` 的一致/命名/职责/关系差异与 R5 建议；冲突 C-01 |
| [08-自检与待评审](08-self-check.md) | 范围与基线检查、教材步骤对应、模型完整性、实际渲染/文档检查结果、NOT RUN、B-01～B-10 与 C-01 |

## 2. UML

| 模型 | 源 | SVG | PNG |
|---|---|---|---|
| UC-10 身份与归属核验分析序列（103 条消息） | [`diagrams/DGM-SEQ-15-identity.puml`](diagrams/DGM-SEQ-15-identity.puml) | [`generated/DGM-SEQ-15-identity.svg`](diagrams/generated/DGM-SEQ-15-identity.svg) | [`generated/DGM-SEQ-15-identity.png`](diagrams/generated/DGM-SEQ-15-identity.png) |
| UC-10 VOPC（12 个参与类） | [`diagrams/DGM-VOPC-11-identity.puml`](diagrams/DGM-VOPC-11-identity.puml) | [`generated/DGM-VOPC-11-identity.svg`](diagrams/generated/DGM-VOPC-11-identity.svg) | [`generated/DGM-VOPC-11-identity.png`](diagrams/generated/DGM-VOPC-11-identity.png) |

## 3. 阅读顺序

先读 01 确认业务边界（谁能判定、同名为何不能合并、候选与合并的区别、审核为何不是本人确认、已排除为何不恢复），再读 02 核对每个事件的证据等级；03 给出 12 个参与类与拒绝候选（含八项对象边界）；04 只描述交互，05 只从 04 提取 VOPC；06 定义职责/属性/关系/机制；07 做全局比较并提出 R5 建议；08 列出实际执行结果与未决项。

## 4. 本轮的六个核心结论

1. **学者档案与账号分离，同名不是证据**：`Scholar` 可无账号、可未认领公开；`UserProfile` 与同名档案不合并；姓名／别名归一（大小写、空格、全角半角、间隔点、顺序、缩写）只生成**比对用形式与候选**，不替换展示名、不构成同人判定（FR03；FR15-S02；BR-342；SPEC ID-01/ID-03；M7/M8/M16）。
2. **保守判定与人工核验，证据不足只出候选**：条件（a）同类型外部标识一致或（b）机构一致∧合作者∧领域支持**且规则已批准**时才可定位唯一档案；否则生成 `IdentityCandidate`（档案组合）与 `AttributionCandidate`（署名归属），不合并、不建立确定关系、不静默分配；阈值与规则批准状态标 `待确认`（FR15-S03/S04；SPEC ID-02、§6.5；`AC-IMPL-FR15-01/03`；M21/M26/M27/M29）。
3. **关系保留角色/顺序/来源与独立状态**：每位参与者一条 `Authorship`，同一学者×成果的重复证据归入同一逻辑关系；状态五取一且优先级固定（已排除>争议>待核验>本人确认>自动关联）（FR15-S07/S09；BR-353/356；SPEC ID-05/ID-11；M18/M22/M23）。
4. **管理员审批 ≠ 本人确认**：人工核验归属只写 `自动关联`＋**独立管理员审核证据**；本 UC 没有任何写 `本人确认` 的消息，也不因一次判定升级该学者其余 N 条关系（SPEC ID-06/ID-10；§6.4 `ON_ADMIN_REVIEW`；`AC-IMPL-FR15-03/06`；M49/M76/M84）。
5. **已排除不自动恢复，修正生效可追溯并传播**：已排除关系在再次采集时保持已排除并记录被拒绝的变更；排除只终止一条关系，成果、全部来源与其他合法关系保留；生效后门户/详情/检索/统计/FR12/IN01 使用同一生效关系并逐视图记录传播时间（`AC-IMPL-FR15-04/05`；FR15-S11/S12；NF05；M11–M13/M88–M89/M97–M99）。
6. **证据三类分离且不越界**：`SourceRecord`（原值，只读）、`ReviewEvidence`（材料＋可见范围，不构成本人确认）、`AuditEvidence`（动作留痕，覆盖被拒绝的尝试）三分离；本 UC 不做认领审核、不维护档案字段、不编辑普通主页、不去重成果、不建合作边、不做本人确认（FR15-S08；SPEC ID-10；BR-IMP-11；INV-01/INV-10；A19）。

## 5. 本轮明确不做

- 不修改 SRS、SPEC、69 AC、S01 冻结材料、全局 UML 与其他 UC 目录；
- 不修改 `docs/analysis/stage-02/03-AC-UC-分析模型追踪.md` 等共享追踪文件，也不改 `automation/analysis-class-registry.md`、`automation/unresolved-decisions.md`（登记建议见 07 §6）；
- 不把 `DGM-CLASS-01-analysis.puml` 当作本 UC VOPC；不把 `DGM-SEQ-01`、`DGM-ACTIVITY-07` 当作本 UC 实现；
- 不分析认领审核（UC-06）、本人确认/补录/纠错（UC-08）、成果去重（UC-09）、合作网络建边（UC-14）、最短路径（UC-15）、后台审计（UC-17）；
- 不引入 Java/数据库/API/框架/部署设计；
- 不把分析假设写成已批准业务规则：身份阈值与规则批准状态、候选规模、时限、候选状态集合、归属候选表达方式与授权转移条件均标 `待确认`（B-01～B-10）。
