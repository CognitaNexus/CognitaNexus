# UC-08 分析与现有全局模型差异

**版本：** V1.0（2026-10-21）
**比较对象：** [`DGM-CLASS-01-analysis.puml`](../../../uml/analysis/DGM-CLASS-01-analysis.puml)、[`01-BCE分析类模型.md`](../01-BCE分析类模型.md)、[`DGM-SEQ-02-claim.puml`](../../../uml/analysis/DGM-SEQ-02-claim.puml)、[`relation-decision-sequence.puml`](../../../uml/governance/relation-decision-sequence.puml)、[`scholar-achievement-relation-state.puml`](../../../uml/governance/scholar-achievement-relation-state.puml)、局部用例图 [`DGM-USECASE-05-claim.puml`](../../../uml/claim/DGM-USECASE-05-claim.puml)、认领状态图 [`DGM-STATE-02-claim-request.puml`](../../../uml/claim/DGM-STATE-02-claim-request.puml)、候选类登记表 [`analysis-class-registry.md`](../automation/analysis-class-registry.md)。
**原则：** 全局图只作比对材料，不作为本 UC 识别类的反向答案；本轮不重构全局图、不修改共享登记表。

## 1. 已存在且一致

| UC-08 类/概念 | 全局对应 | 一致点 | 证据 |
|---|---|---|---|
| `Authorship` | 全局 `Authorship`（“角色/顺序/证据()”“独立关系状态()”，第 82–87 行；`Scholar -- Authorship -- Work` 第 124–125 行） | 都承认参与关系是独立实体，携带角色、顺序、证据与独立状态；管理员审核不等于本人确认 | FR15；SPEC ID-05/ID-06 |
| `WorkEntity` | 全局 `Work`（“去重规范值()/成果类型()/生效状态()”） | 都是去重后的成果实体；来源数、版本数不等于成果数 | INV-02；FR09/FR16 |
| `SourceRecord` | 全局 `Source`（“原值/来源/许可()”“逻辑出处()”） | 都表达来源侧原值与许可，且与成果分离 | §7.1/§7.2；CL-11 |
| `Scholar` | 全局 `Scholar`（“已核验身份()/公开学者档案()”） | 都是独立学术身份档案，同名不合并，可无账号 | FR03；BR-IMP-03；SRS §12 |
| `ScholarAuthorization` | 全局 `Auth`（“目标Scholar()/授权范围()”；`Auth -- Scholar`） | 都表达“User 对目标 Scholar 的对象级管理权”；本 UC 只读 | FR05；`AC-IMPL-FR05-02` |
| `User` | 全局 `User`（账号状态/对象授权校验） | 都是账号、状态与授权主体 | FR04；SRS §8 |
| `AuditEvidence` | 全局 `Evidence`（“操作者/理由/前后值()”）与 `AuditCtl ..> Evidence` | 都要求受保护写入与决定留痕 | BR-IMP-10；INV-10 |
| `ReviewEvidence` | `01` §4 `ReviewEvidence`（“关联 Claim/Authorship/更正”） | 都承认存在“材料/决定证据”对象，且可关联 Authorship | BR-IMP-11；FR15-S08 |
| “解除不删成果” | `Authorship` 注记“解除不删成果”与 `Work`/`Source` 分离；`DGM-STATE-05` 注记“BR-359” | 与本 UC BR-UC08-09 一致 | `AC-IMPL-FR07-01`；FR15-S11 |
| 单次确认不扩散 | `DGM-SEQ-02` 注记“管理员批准不等于本人确认；不批量确认其他 Authorship”；`01` §5.3 | 与本 UC BR-UC08-03 一致 | `AC-IMPL-FR15-06`；SPEC ID-06/ID-07 |

## 2. 需要更名/别名统一

| 本 UC 名称 | 全局名称 | 差异 | R5 建议 |
|---|---|---|---|
| `AchievementCorrectionUI` | `01` §2 `ScholarPortalUI`（UC-03/07）与 `ClaimUI`（UC-05/06）之间；`DGM-CLASS-01` **无同类边界** | 全局把“成果关系确认/补录/纠错”的写入口分散在门户与认领边界，且识别不到本 UC 的待审查询与审核输入 | R5 增设“成果关系纠错/补录”写边界（或明确 `ScholarPortalUI` 同时承担该职责并写出两套守卫）；读边界仍属 `PublicUI`/`WorkDetailUI` |
| `AchievementCorrectionCoordinator` | 全局 `GovernCtl`（“清洗/去重/候选”）与 `ClaimCtl`（“证据/授权/争议”） | 本 UC 的协调者不跑清洗/去重、不做认领；它做“本人确认＋共享字段/解除的待审与生效” | R5 在 `GovernCtl`/`ClaimCtl` 之外定义成果确认与纠错协调职责，或明确其归属并补齐守卫；不允许把“本人确认”挂在 `ClaimCtl` |
| `CorrectionRequest` | 无全局对应（`Claim` 是认领；`Evidence` 是留痕） | 全局无法表达“未生效的成果字段/解除请求 + 本人可查 + 版本” | R5 新增“待审纠错请求”概念，并与 UC-07 `ScholarChangeRequest` 评估是否统一为“待审变更请求”超类 |
| `ReviewEvidence` vs `AuditEvidence` vs `SourceRecord` | 全局 `Evidence` 一名多义；`01` §4 只列 `ReviewEvidence`；全局 `Source` 独立 | 三类证据在全局被压缩为 `Evidence` 一个类 | R5 拆分：`SourceRecord`（来源原值）、`ReviewEvidence`（材料＋可见范围）、`AuditEvidence`（动作留痕）；见 D-13 |
| `WorkEntity` | 全局 `Work` | 命名差异（登记表 D-11 已登记） | R5 统一为 `WorkEntity` 或 `Work`，二选一 |
| `SourceRecord` | 全局 `Source` | 命名差异（登记表 D-11 已登记） | R5 统一 |

## 3. 职责差异/需要补充

| 项目 | 本 UC 证据 | 全局现状 | 差异/影响 | R5 处理 |
|---|---|---|---|---|
| 本人确认只改一条关系且不扩散 | M15/M23/M27；`AC-IMPL-FR15-06`；SPEC ID-06/ID-07 | `Authorship` 有“独立关系状态()”，但无“确认只作用一条、其 N 条不升格”的职责表达；`ClaimCtl` 只有“证据/授权/争议” | 若按全局图实现，确认可能被写成“按学者批量置本人确认” | 在确认协调职责中写明“单一关系作用域 + 不扩散核对”，并在 `Authorship` 增加“追加本人确认证据/置争议/置已排除”操作 |
| 状态优先级与不可自动恢复 | M15/M16/M19；BR-356；`AC-IMPL-FR15-04`；§6.4 | 全局 `Authorship` 注记提到“独立关系状态”，未写优先级与“已排除不可被自动恢复” | 低优先级证据可能覆盖已排除 | 在 `Authorship` 或治理协调者职责中写明五状态优先级与拒绝规则 |
| 共享字段/误归属/解除必经审核 | M30/M40/M52–M76；`AC-IMPL-FR07-01/02`；BR-IMP-10；§6.4 | 全局 `Work` 只有“去重规范值()/生效状态()”，没有“未生效请求”和“批准后写入字段”的职责；`GovernCtl ..> Work` 只表达清洗/候选 | 待审内容可能被直接写进公开字段，或审核通过后不生效 | 新增待审请求职责与“通过才生效”；`Work` 增加“只写入批准字段”的约束 |
| 补录须可核验、不造值、不覆盖来源原值 | M35/M36；FR07；§7.1；CL-01/CL-08；BR-IMP-04 | 全局 `Work`/`Source` 无“原值与规范值分离”的显式职责；`Source` 只有“原值/来源/许可()” | 人工补录可能覆盖来源原值或凭空补值 | `WorkEntity` 增加“只写公开规范值”；`SourceRecord` 明确“只读、不可改写”；补录协调加入最低字段判定 |
| 解除后保留成果/来源/他人关系 | M67/M68；`AC-IMPL-FR07-01`；FR15-S11；INV-05 | 全局 `Authorship` 注记有“解除不删成果”，但无“保留全部来源与其余关系”的核对职责 | 解除可能级联删除或改变他人关系 | 在解除生效职责中写明“核对保留”并补 `WorkEntity 1 → 0..* SourceRecord` 保留语义 |
| 待审/材料可见范围 | M41/M49/M56；BR-IMP-11；NF06；`AC-IMPL-NF06-01` | 全局 `Evidence` 无 `visibilityScope`；边界无视图区分 | 材料可能被公开读取 | `ReviewEvidence` 增加可见范围属性；边界明确“公开视图不含材料” |
| 证据三分（不得合并为单一标签） | M35/M41/M42；BR-355；FR15-S08 | 全局 `Evidence` 汇总“操作者/理由/前后值”，未区分材料与来源原值 | 三类证据被合并，无法回答“关系为什么成立” | 拆分类并补 `targetRefs`/`outcome`/`occurredAt`（同 UC-07 07 §3） |
| 审计覆盖“被拒绝/未写入” | M10/M13/M18/M39；BR-IMP-10 | 全局 `Evidence` 未表达拒绝类留痕 | 越权尝试无法追溯 | `AuditEvidence` 增加结果/outcome 与对象引用 |
| 不新建成果、不提供投稿/出版 | A14；BR-UC08-11；`AC-IMPL-FR07-03` | 全局有 `SourceUI`/`CollectCtl`（采集入口），无“学者不得直接新建成果”的约束 | 可能把纠错入口做成投稿入口 | 在边界与协调者职责写明“只对已收录成果补录；不新建 WorkEntity” |
| 不合并/拆分身份、不改派归属 | A11；BR-UC08-15；FR15-S10 | 全局 `GovernCtl` 覆盖“身份/归属候选”，但不区分“学者申请受理”与“管理员处置” | 职责重叠，易把改派写进本 UC | 明确 UC-08 只登记申请状态、UC-10 执行处置；R5 统一两者的协作边界 |

## 4. 关系差异

### 4.1 `GovernCtl ..> Authorship`（全局图第 113 行）

- 全局把 `Authorship` 的写入挂在治理协调者上；本 UC 的顺序图中 `Authorship` 的**生效状态变化**由 `AchievementCorrectionCoordinator` 承担（M19/M23/M67），治理只负责建立关系与候选（UC-09/UC-10）。
- **R5 建议：** 保留 `GovernCtl ..> Authorship` 用于“建立/改派/排除”的治理处置，同时新增本 UC 的确认/解除写入依赖，并在职责文字中区分“治理判定”与“本人确认＋审核后生效”，避免两个协调者都能自由改写关系状态。

### 4.2 `Authorship -- Work` / `Scholar -- Authorship`

- 全局均未标多重性；本 UC 依 BR-353、ID-05/ID-11 把 `Authorship → WorkEntity`、`Authorship → Scholar` 都标为 `0..*` : `1`（05 §2.2）。
- 登记表 `Authorship` 行把 `Scholar` 一侧写成“后续关联”并整行标“R5 确认”，记录的是跨用例表达未统一，与“一条关系只归于一位学者”不冲突；**R5 建议**统一为 `0..*:1` 并同步登记表。

### 4.3 `Auth -- Scholar` 多重性

- 全局未标；本 UC 同样**不标**（SPEC ID-12 的并列管理者/转移程序待评审，沿用 UC-06/UC-07）。

### 4.4 `CorrectionRequest` 的新关系

- 全局无该类与边；本 UC 画 `CorrectionRequest 0..* → 1 WorkEntity` 与 `0..* → 0..1 Authorship`。
- 不建议把待审内容画成 `WorkEntity` 的自反“版本”边：本 UC 已把“生效值”放在 `WorkEntity`，把“未生效内容”放在独立对象，避免同一对象内部出现两套可见性（与 UC-07 07 §4.3 同构）。

### 4.5 `WorkEntity — SourceRecord`

- 全局 `Source -- Work : 来源证据`（第 122 行）未标多重性；本 UC 沿用 UC-02 的 `1 : 0..*`（一项成果可多来源），并强调“解除关系不删除来源”。

### 4.6 审计与实体

- 全局只有 `AuditCtl ..> Evidence` 与 `Evidence ..> Claim/Authorship`；本 UC 把对象指向写成 `AuditEvidence.targetRefs` 属性（05 §2.3），避免与全局 `Evidence` 关系冲突；R5 再统一留痕对象引用表达。

## 5. 与局部用例图/状态图/序列图的一致性

| 全局资产 | 与本 UC 的一致点 | 需注意的差异 |
|---|---|---|
| [`DGM-USECASE-05-claim.puml`](../../../uml/claim/DGM-USECASE-05-claim.puml) | `门户管理者 -- Correct`、`有权管理员 -- Correct`；UC-08 名称“确认补录与纠错成果”；注记“认证及授权为守卫，不画作强制调用用例” | 用例图未区分“本人确认（直接生效）”与“共享字段/解除（待审）”两条路径；本 UC 依 FR07 与 AC 建模，不改用例图 |
| [`relation-decision-sequence.puml`](../../../uml/governance/relation-decision-sequence.puml) | “相关学者提交本人确认／纠错／解除关联申请”“校验申请范围与授权状态”“争议置为争议禁止自动变更”“留痕失败不得表现为成功”“门户/检索/统计一致” | 该图是 FR15 归属判定与传播的预置资产（主 UC-10），参与者粒度是“治理判定/治理留痕/门户/检索/统计”，不含 `WorkEntity`/`CorrectionRequest`；本 UC 的 `DGM-SEQ-13` 才是 UC-08 的实现证据 |
| [`scholar-achievement-relation-state.puml`](../../../uml/governance/scholar-achievement-relation-state.puml) | 五状态与合法转移；`自动关联/待核验 → 本人确认`；`本人确认 → 已排除（学者申请解除并审核通过）`；`→ 争议`；已排除注记“不删成果、出处与他人关系” | 该图未表达“待核验关系在无冲突时是否需审核才能确认”（B-02）；本 UC 按“无冲突可直接确认、有冲突转争议”处理并标待确认 |
| [`DGM-STATE-02-claim-request.puml`](../../../uml/claim/DGM-STATE-02-claim-request.puml) | 认领申请状态与授权生成 | 本 UC 的 `CorrectionRequest` 状态（`待审核/通过/驳回`）与认领申请状态（含 `待补充/争议`）是两套状态机，不得共用（§11.3 同理） |
| [`DGM-SEQ-02-claim.puml`](../../../uml/analysis/DGM-SEQ-02-claim.puml) | 越权/停用拒绝、受控审计、批准不等于本人确认 | 该图不含成果字段纠正、关系解除与共享字段审核；追踪表把 `SEQ-02` 列为本 UC 分析模型属历史索引，建议改指 `SEQ-13` |

## 6. 追踪文件与登记表（只提建议，不改文件）

| 文件 | 现状 | 建议（由父 Agent 决定） |
|---|---|---|
| [`03-AC-UC-分析模型追踪.md`](../03-AC-UC-分析模型追踪.md) | FR07 三行与 FR15/NF03/NF05 相关行的“分析模型”列只列 `DGM-USECASE-05 / SEQ-02 / CLASS-01`、`governance-relation` 等；UC-08 反向行列出 `AC-IMPL-FR07-01～03`、`AC-IMPL-FR15-01～06`、`AC-IMPL-NF03-01`、`AC-IMPL-NF05-01/02` | 为 UC-08 增补 `SEQ-13 / VOPC-09`；`SEQ-02` 保留为认领审核序列（UC-06），不再充当 UC-08 的实现证据 |
| [`analysis-class-registry.md`](../automation/analysis-class-registry.md) | 已有 `Authorship`、`WorkEntity`、`SourceRecord`、`Scholar`、`User`、`ScholarAuthorization`、`ReviewEvidence`、`AuditEvidence` 行；无本 UC 的边界/协调者/待审请求行 | 增量登记 `AchievementCorrectionUI`、`AchievementCorrectionCoordinator`、`CorrectionRequest`；`Authorship` 行补“后续参与 UC-08”“归属 Scholar 0..*:1（依 BR-353）”；`WorkEntity` 行补“只写批准字段的生效规范值、不因解除删除”；`SourceRecord` 行补“UC-08 只读” |
| [`unresolved-decisions.md`](../automation/unresolved-decisions.md) | D-06（证据最低集合）、D-09（审计保留）、D-11/D-12/D-13（命名与关系） | 可评估新增“成果确认/纠错的共享字段清单、可核验口径与待审请求状态集合”（本 UC B-01/B-04/B-05）；本包不直接修改该文件 |
| 全局 `DGM-CLASS-01-analysis.puml` | `GovernCtl ..> Authorship`（第 113 行）、`Work`/`Source`/`Evidence` 无原值-规范值区分、无待审请求 | 按 §3–§4 在 R5 统一分析类时处理；本 UC 不直接修改全局图 |

## 7. 差异结论

UC-08 分析支持以下全局模型调整建议：

1. 保留 `Authorship`、`Work`(→`WorkEntity`)、`Source`(→`SourceRecord`)、`Scholar`、`Auth`、`User`、`AuditEvidence`(→拆分 `Evidence`) 的既有分离；
2. 为“成果关系确认/补录/纠错”引入独立写边界与协调者，职责包含“服务端对象授权＋单一关系作用域＋状态优先级＋共享字段与解除必经审核＋可核验不造值＋解除保留”；
3. 明确“本人确认”只由本人事件产生、只作用一条关系、不扩散；管理员审核通过不得写成本人确认；
4. 新增“待审纠错请求”对象（`待审核/通过/驳回`），与生效值并存，并与认领申请、档案待审变更保持三套状态机；
5. `Authorship → WorkEntity`、`Authorship → Scholar` 统一为 `0..*:1`；`Auth → Scholar` 保持未定（SPEC ID-12）；
6. 拆分 `SourceRecord`/`ReviewEvidence`/`AuditEvidence`，补齐材料可见范围、审计对象引用与结果、来源原值只读；
7. 共享字段清单、可核验最低集合、待审请求状态集合、归属改派入口分工仍需需求确认（B-01/B-03/B-04/B-05/B-08）。

本轮**不直接修改** `DGM-CLASS-01-analysis.puml`、`DGM-SEQ-02-claim.puml`、`relation-decision-sequence.puml`、`scholar-achievement-relation-state.puml`、`DGM-USECASE-05-claim.puml`、`DGM-STATE-02-claim-request.puml` 与共享追踪/登记文件。
