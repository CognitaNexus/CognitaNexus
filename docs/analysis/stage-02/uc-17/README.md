# UC-17 用例实现分析（后台待办与审计）

**版本：** V1.0（2026-10-21）
**输入基线：** `S01 FROZEN — 69 AC BASELINE`；[SRS V2.2（69 AC）](../../../srs/CognitaNexus-Shared-SRS-V2.2-69AC.md) FR14（`AC-IMPL-FR14-01/02`）及 FR05-03/FR08-01/FR10-02 边界、NF01/NF03/NF06/NF07、BR-IMP-01/05/10/11、§3.2、§6.1～§6.4、§7.1/§7.2、§8、§12；[核心 SPEC V1.0](../../../spec/CognitaNexus-Core-SPEC-V1.0.md) §3（公共状态、INV-01/05/06/08/09/10）、§6.2、§6.3、§6.4；[`DGM-USECASE-06`](../../../uml/admin/DGM-USECASE-06-admin.puml)；[`01-BCE分析类模型`](../01-BCE分析类模型.md)；UC-05/06/09/10 已交付分析（只读参考）；教材第 5 章 §5.4–§5.5。
**范围：** 只分析 UC-17“后台待办与审计”。不分析采集任务执行（UC-18）、增量与恢复（UC-19）、成果清洗去重（UC-09）、身份与归属判定（UC-10）、认领申请与审核（UC-05/06）、 Scholar 档案维护（UC-07）、本人确认/补录/纠错（UC-08）、普通主页编辑与公开展示（UC-22/UC-03/UC-01/UC-02/UC-11/UC-14/UC-15）；不重构全局类图。
**前置关系：** 六类待办与留痕由属主 UC 产生；本 UC 只读聚合与核对，不执行其决定。
**状态：** UC-17 分析完成（STATIC COMPLETE，UML 2/2 渲染通过）；S02 总体状态与共享追踪文件由父 Agent 维护。

## 1. 交付内容

| 文件 | 内容 |
|---|---|
| [01-UC-17用例规约](01-UC-17-use-case-spec.md) | 参与者、目标、触发、前置/成功/失败后置、E1–E18 基本流、A1–A14 备选/异常、16 条业务规则、关联状态、AC 覆盖、B-01～B-10 待确认 |
| [02-事件流与需求来源](02-event-flow-trace.md) | E1–E18/A1–A14 的逐项来源与证据等级；后置条件来源；不纳入本 UC 的事件 |
| [03-BCE分析类识别](03-bce-identification.md) | 11 个候选类、逐类理由、拒绝候选（含通用工作流实体）、六项对象边界结论 |
| [04-顺序图与逐消息职责](04-sequence-messages.md) | M1–M57 的发送者/接收者/事件步骤/职责/依据；事件—分支—消息对照；完整性核对 |
| [05-VOPC与关系依据](05-vopc.md) | 11 个参与类、11 条关系、唯一的业务多重性 `0..*:0..1`、属性替代关联的取舍、排除项 |
| [06-分析类定义](06-class-definition.md) | 每类 Do/Know、候选分析操作、业务属性、关系、16 项分析机制、与其他 UC 的边界 |
| [07-与全局模型差异](07-global-model-diff.md) | 与 `DGM-CLASS-01`、`01-BCE`、`DGM-USECASE-06`、`DGM-SEQ-01`/`DGM-STATE-01` 的一致/命名/职责/关系差异与 R5 建议 |
| [08-自检与待评审](08-self-check.md) | 范围与基线检查、教材步骤对应、模型完整性、实际渲染/文档检查结果、NOT RUN、B-01～B-10 |

## 2. UML

| 模型 | 源 | SVG | PNG |
|---|---|---|---|
| UC-17 后台待办与审计分析序列（57 条消息） | [`diagrams/DGM-SEQ-16-admin-audit.puml`](diagrams/DGM-SEQ-16-admin-audit.puml) | [`generated/DGM-SEQ-16-admin-audit.svg`](diagrams/generated/DGM-SEQ-16-admin-audit.svg) | [`generated/DGM-SEQ-16-admin-audit.png`](diagrams/generated/DGM-SEQ-16-admin-audit.png) |
| UC-17 VOPC（11 个参与类） | [`diagrams/DGM-VOPC-12-admin-audit.puml`](diagrams/DGM-VOPC-12-admin-audit.puml) | [`generated/DGM-VOPC-12-admin-audit.svg`](diagrams/generated/DGM-VOPC-12-admin-audit.svg) | [`generated/DGM-VOPC-12-admin-audit.png`](diagrams/generated/DGM-VOPC-12-admin-audit.png) |

## 3. 阅读顺序

先读 01 确认业务边界（入口统一但权限不统一、待办只读、导航不执行、审计只读、证明原件不外泄），再读 02 核对每个事件的证据等级；03 给出 11 个参与类与拒绝候选（含为何没有通用待办实体）；04 只描述交互，05 只从 04 提取 VOPC；06 定义职责/属性/关系/机制；07 做全局比较并提出 R5 建议；08 列出实际执行结果与未决项。

## 4. 本轮的六个核心结论

1. **入口统一，权限不统一**：`AdminConsole` 是唯一后台入口，覆盖采集、异常治理、学者核验、认领审核、成果变更、账号/分类/来源维护六类；每个管理员只按**类别级具体管理权限**访问，不存在默认全量的超级管理员（FR14；§8；M4/M5/M31；A3）。
2. **待办是只读派生投影，不是工作流**：待办由 `CollectionTask`/`DedupCandidate`/`IdentityCandidate`/`ClaimApplication`/`ScholarAuthorization`/`Authorship` 的状态即时派生，只有类别、计数、属主 UC 与对象引用；不定义状态机、不存处理人、不建 `PendingTodo`/`WorkflowEngine`/`ApprovalChain`/`Assignee`/`SlaTimer`（FR14“不做通用工作流引擎”；M9–M24）。
3. **导航不执行决定**：批准/驳回/合并/拆分/授权/改归属/启动重试采集/停用来源均属属主 UC；本 UC 只组装导航目标（M25–M28；A11）。
4. **审计只读、留痕追加、五要素可核对**：关键批准/拒绝/合并/拆分/授权事件须记录操作者/对象/时间/前后值/理由；查询只读、按时间排序保留追加链，缺要素标“留痕不完整”，不补写、不推断（FR14；BR-IMP-10；INV-10；M34–M37；A6/A9/A13）。
5. **证明原件不外泄**：审计结果只含材料引用与可见范围；普通用户无入口且不可见原件，原件仅提交者与有权审核者按需可见；安全审计不含凭据与秘密（`AC-IMPL-FR14-02`；NF03/NF06；M38–M42；A5/A14）。
6. **普通用户拒绝是核心负向验收**：`AC-IMPL-FR14-01` 由入口守卫（M3/M4/M5）与普通用户分支（M49–M57）覆盖——拒绝、无业务变更、结果可区分、越权尝试留痕。

## 5. 本轮明确不做

- 不修改 SRS、SPEC、69 AC、S01 冻结材料、全局 UML 与其他 UC 目录；
- 不修改 `docs/analysis/stage-02/03-AC-UC-分析模型追踪.md` 等共享追踪文件，也不改 `automation/analysis-class-registry.md`、`automation/unresolved-decisions.md`、`automation/progress.json`（登记建议见 07 §6）；
- 不把 `DGM-CLASS-01-analysis.puml` 当作本 UC VOPC；不把 `DGM-USECASE-06`、`DGM-SEQ-01` 当作本 UC 实现；
- 不分析采集任务执行（UC-18/19）、成果治理处置（UC-09）、身份与归属判定（UC-10）、认领审核（UC-06）、档案维护（UC-07）、本人纠错（UC-08）、公开展示（UC-01/02/03/11/14/15）；
- 不引入通用工作流引擎、审批链、指派、SLA 定时器、权限矩阵或角色继承；不设计数据库/API/框架/部署；
- 不把分析假设写成已批准业务规则：权限粒度（B-01）、待办载体与口径（B-02/B-06）、保留期限与遮蔽清单（B-03/B-04）、查询访问留痕与导出（B-05）、成果变更待办状态来源（B-07）、分类入口范围（B-08）均标 `待确认`。
