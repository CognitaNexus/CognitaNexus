# UC-07 用例实现分析（维护已授权 Scholar 档案）

**版本：** V1.0（2026-10-21）
**输入基线：** `S01 FROZEN — 69 AC BASELINE`；[SRS V2.2（69 AC）](../../../srs/CognitaNexus-Shared-SRS-V2.2-69AC.md) FR06/FR05/FR15/NF03/NF06、BR-IMP-01/03/04/05/10/11、§6.4、§7.2、§8、§11.3/§11.4、§12；[核心 SPEC V1.0](../../../spec/CognitaNexus-Core-SPEC-V1.0.md) §3（公共状态/不变量 INV-08/INV-10）、§6（ID-06/ID-07/ID-12）、§6.2（ID-01～ID-12）；教材第 5 章 §5.4–§5.5。
**范围：** 只分析 UC-07“维护已授权 Scholar 档案”。不分析 UC-03 公开主页/学者档案浏览、UC-04 注册、UC-05/UC-06 Scholar 认领与认领审核、UC-08 成果确认与纠错、UC-22 本人普通主页编辑、UC-17 后台审计查询；不重构全局类图。
**前置关系：** UC-05/UC-06 是唯一产生 `ScholarAuthorization` 的来源（本 UC 只读该授权）；UC-22 处理普通 `UserProfile` 字段（本 UC 不写）。两者只作为前置与边界参与，本 UC 不重画其流程。
**状态：** UC-07 分析完成（STATIC COMPLETE，UML 2/2 渲染通过）；S02 总体状态与共享追踪文件由父 Agent 维护。

## 1. 交付内容

| 文件 | 内容 |
|---|---|
| [01-UC-07用例规约](01-UC-07-use-case-spec.md) | 参与者、目标、触发、前置/成功/失败后置、E1–E10 基本流、A1–A10 备选/异常、业务规则、状态、AC 覆盖、待确认 |
| [02-事件流与需求来源](02-event-flow-trace.md) | E1–E10/A1–A10 的逐项来源与证据等级；不纳入本 UC 的事件 |
| [03-BCE分析类识别](03-bce-identification.md) | 7 个候选类、逐类理由、拒绝候选、UserProfile / Scholar / ScholarAuthorization / 待审变更 四方边界 |
| [04-顺序图与逐消息职责](04-sequence-messages.md) | M1–M52 的发送者/接收者/事件步骤/职责/依据；越权/失效授权/超范围/普通字段/待审/审核/失败分支 |
| [05-VOPC与关系依据](05-vopc.md) | 7 个参与类、关系与多重性理由、排除项；VOPC 只来自本 UC 顺序图 |
| [06-分析类定义](06-class-definition.md) | 每类 Do/Know、候选分析操作、业务属性、关系、分析机制（§5.5.1–§5.5.4） |
| [07-与全局模型差异](07-global-model-diff.md) | 与 `DGM-CLASS-01-analysis.puml`、`DGM-SEQ-04-profile.puml`、`DGM-USECASE-07` 的一致/命名/职责/关系差异与 R5 建议 |
| [08-自检与待评审](08-self-check.md) | 范围与基线检查、教材步骤对应、模型完整性、实际渲染/文档检查结果、B-01～B-11 |

## 2. UML

| 模型 | 源 | SVG | PNG |
|---|---|---|---|
| UC-07 已授权 Scholar 档案维护分析序列（52 条消息） | [`diagrams/DGM-SEQ-12-scholar-maintenance.puml`](diagrams/DGM-SEQ-12-scholar-maintenance.puml) | [`generated/DGM-SEQ-12-scholar-maintenance.svg`](diagrams/generated/DGM-SEQ-12-scholar-maintenance.svg) | [`generated/DGM-SEQ-12-scholar-maintenance.png`](diagrams/generated/DGM-SEQ-12-scholar-maintenance.png) |
| UC-07 VOPC（7 个参与类） | [`diagrams/DGM-VOPC-08-scholar-maintenance.puml`](diagrams/DGM-VOPC-08-scholar-maintenance.puml) | [`generated/DGM-VOPC-08-scholar-maintenance.svg`](diagrams/generated/DGM-VOPC-08-scholar-maintenance.svg) | [`generated/DGM-VOPC-08-scholar-maintenance.png`](diagrams/generated/DGM-VOPC-08-scholar-maintenance.png) |

## 3. 阅读顺序

先读 01 确认业务边界（谁可以写、可以写什么、什么必须待审），再读 02 核对每个事件的证据等级；03 给出 7 个参与类与拒绝候选（含 UserProfile/Scholar/ScholarAuthorization/待审变更 四方区分）；04 只描述交互，05 只从 04 提取 VOPC；06 定义类的职责/属性/关系/机制；07 做全局比较并提出 R5 建议；08 列出实际执行结果与未决项。

## 4. 本轮的五个核心结论

1. **维护权只来自 Scholar 对象级授权**：每次受保护写入在服务端校验账号状态＋授权主体＋授权范围＋目标档案一致性；他人档案、同名另一位 Scholar、已失效授权与越界字段一律拒绝且不写业务实体（BR-IMP-05；`AC-IMPL-FR06-02`；FR05；SRS §8）。
2. **维护对象是 Scholar 学术事实，不是普通 UserProfile 字段**：已核验学术身份、机构、归属、来源事实属 Scholar 档案，写入须按对象授权与审核规则；昵称/头像/简介/兴趣领域/展示设置属 `UserProfile`，属 UC-22（FR06；SRS §12）。
3. **重要变更待审、公开旧值继续**：待审版本不覆盖公开版；`AC-IMPL-FR06-01` 的“尚未审批→公开仍为旧机构→申请人可查待审状态”由“登记待审变更 + 公开版保持 + 申请人对本人待审状态可读”三步共同保证；只有审核通过才生成生效版本（FR06；BR-IMP-10；SRS §6.4）。
4. **不扩张结构与不转移权利**：不创建第二个 `UserProfile`、不创建 `Scholar`、不创建 `ClaimApplication`、不创建/转移 `ScholarAuthorization`、不自动确认或解除任何 `Authorship`（FR05；`AC-IMPL-FR05-04`；`AC-IMPL-FR15-06`；BR-IMP-05）。
5. **不覆盖原始来源事实，生效后跨视图用同一版本**：人工维护的是公开规范值，原始来源事实与审核记录独立保留（§7.2；BR-IMP-01/04）；生效后检索/门户/统计使用同一生效版本（NF05；BR-IMP-10）。传播时限与字段级清单未冻结，标 `待确认`。

## 5. 本轮明确不做

- 不修改 SRS、SPEC、69 AC、S01 冻结材料、全局 UML 与其他 UC 目录；
- 不修改 `docs/analysis/stage-02/03-AC-UC-分析模型追踪.md` 等共享追踪文件，也不改 `automation/analysis-class-registry.md`、`automation/unresolved-decisions.md`（登记建议见 07 §6）；
- 不把 `DGM-CLASS-01-analysis.puml` 当作本 UC VOPC；不把 `DGM-SEQ-04-profile.puml` 的 `Profile -> Scholar` 合并消息当成本 UC 实现；
- 不分析 UC-06 的认领审核决定、UC-08 的成果归属确认/纠错、UC-17 的审计查询与待办；
- 不引入 Java/数据库/API/框架/部署设计；
- 不把分析假设写成已批准业务规则：需审核字段清单、可直接生效字段清单、授权范围粒度、并列管理者、传播时限均标 `待确认`（B-01～B-05、B-09/B-10）。
