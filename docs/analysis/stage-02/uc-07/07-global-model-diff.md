# UC-07 分析与现有全局模型差异

**版本：** V1.0（2026-10-21）
**比较对象：** [`DGM-CLASS-01-analysis.puml`](../../../uml/analysis/DGM-CLASS-01-analysis.puml)、[`01-BCE分析类模型.md`](../01-BCE分析类模型.md)、合并序列 [`DGM-SEQ-04-profile.puml`](../../../uml/analysis/DGM-SEQ-04-profile.puml)、主页活动图 [`DGM-ACTIVITY-06-edit-profile.puml`](../../../uml/profile/DGM-ACTIVITY-06-edit-profile.puml)、局部用例图 [`DGM-USECASE-07-profile.puml`](../../../uml/profile/DGM-USECASE-07-profile.puml)、认领状态图 [`DGM-STATE-02-claim-request.puml`](../../../uml/claim/DGM-STATE-02-claim-request.puml)、候选类登记表 [`analysis-class-registry.md`](../automation/analysis-class-registry.md)。
**原则：** 全局图只作比对材料，不作为本 UC 识别类的反向答案；本轮不重构全局图、不修改共享登记表。

## 1. 已存在且一致

| UC-07 类/概念 | 全局对应 | 一致点 | 证据 |
|---|---|---|---|
| `ScholarMaintenanceUI` | `01` §2 `ScholarPortalUI`（“展示/维护授权 Scholar 档案”） | 都承认存在“维护已授权 Scholar 档案”的独立交互边界，且与普通主页编辑（`ProfileUI`）分离 | FR06；`01` §2 |
| `ScholarMaintenanceCoordinator` | `ProfileCtl` 的关键守卫“区普通资料与学术事实”；`DGM-ACTIVITY-06` 的学术分支 | 都承认档案维护与普通编辑是两条路径 | FR06；`01` §3 |
| `User` | 全局 `User`（账号状态/对象授权校验） | 都是账号、状态与授权主体；不因获授权变成 Scholar | FR04；SRS §12 |
| `Scholar` | 全局 `Scholar`（已核验身份/公开学者档案） | 都是平台采集/核验的独立学术身份；认领前仍可公开 | FR03；FR05；SRS §12 |
| `ScholarAuthorization` | 全局 `Auth`（目标 Scholar/授权范围） | 都表达“User 对目标 Scholar 的对象级管理权” | FR05；`AC-IMPL-FR05-02`；BR-IMP-05 |
| `Auth -- Scholar`（目标档案） | 全局第 120 行 `Auth -- Scholar : 目标档案` | 关系方向一致；多重性均未在本 UC 断言（沿用 UC-06 未定） | 05 §2.2 |
| `AuditEvidence` | 全局 `Evidence`（操作者/理由/前后值）与 `AuditCtl ..> Evidence` | 都要求受保护写入留痕 | BR-IMP-10；`AC-IMPL-FR06-02` |
| 学术事实不写普通主页 | `01` §5.2“UserProfile≠Scholar”；`SRS §12`；`DGM-ACTIVITY-06` 学术分支 | 一致：本 UC 不写 `UserProfile`、不合并同名 | FR06；SRS §12 |
| 认领批准才产生授权 | `DGM-USECASE-07` 第 27 行 `Claim ..> MaintainScholar : 仅批准后授权` | 一致：本 UC 以已生效授权为前置，不自行授予 | `AC-IMPL-FR05-02`；SPEC ID-07 |
| 待审不覆盖公开版 | `SRS §6.4`“重要字段待审旧值继续公开；通过才生成生效版本”；BR-IMP-10 | 一致（全局图无对应元素，见 §3） | FR06；`AC-IMPL-FR06-01` |

## 2. 需要更名/别名统一

| 本 UC 名称 | 全局名称 | 差异 | R5 建议 |
|---|---|---|---|
| `ScholarMaintenanceUI` | `01` §2 `ScholarPortalUI`（UC-03/07 共用）；`DGM-CLASS-01` **无该类** | 全局 BCE 文本用同一名称覆盖“展示（读）”与“维护（写）”，但两者的授权前提完全不同（访客读 vs 对象授权写）；全局类图缺该边界 | R5 拆分为读边界（UC-03 `PublicProfileUI`）与写边界（UC-07 `ScholarMaintenanceUI`），或明确 `ScholarPortalUI` 只在两处复用同一职责模型并写出两套授权守卫 |
| `ScholarMaintenanceCoordinator` | 全局只有 `ProfileCtl ..> Scholar`（第 107 行） | 全局把“档案维护”挂在普通主页协调器上；本 UC 需要独立的维护协调者，其守卫（授权＋范围＋待审）与 `ProfileCtl`（所有权＋普通字段）不同 | R5 把第 107 行的维护职责迁到 `ScholarMaintenanceCoordinator`，`ProfileCtl` 只保留“拒绝/转交”语义（与 UC-22 07 §4.1 建议一致） |
| `AuditEvidence` | 全局 `Evidence`、`01` §4 `ReviewEvidence` | 全局一名两用：认领/审核材料 vs 受保护写入留痕 | R5 拆分：`ReviewEvidence` 专指材料，`AuditEvidence` 专指留痕（D-13） |
| `ScholarChangeRequest` | 无全局对应 | 全局没有“待审档案变更”对象；`Claim` 与 `Evidence` 都不能表达“未生效但可查”的并存内容 | R5 新增“变更申请”概念，并评估与 UC-08 的成果字段修改申请统一（见 §3） |

## 3. 职责差异/需要补充

| 项目 | 本 UC 证据 | 全局现状 | 差异/影响 | R5 处理 |
|---|---|---|---|---|
| 对象级授权每次校验（含直接调用） | M3/M4/M5/M6；`AC-IMPL-FR06-02`；NF03；SRS §8 | 全局 `ProfileCtl` 只写“所有权/普通字段”，无“授权＋范围＋目标一致”三守卫；`Auth` 无被读取的协作者 | 若按全局图实现，档案维护的越权判定会落到“隐藏入口”或实现者自选策略 | 在 `ScholarMaintenanceCoordinator` 职责中写明三守卫，并补 `Coordinator ⇢ Auth`（只读）依赖 |
| 授权主体与账号状态 | M3/M38；`AC-IMPL-NF03-01` | 全局 `User` 有“对象授权校验()”，但无协调器到 `User` 的依赖（`ProfileCtl` 亦无） | 停用账号与“谁是授权主体”缺少归属 | 在维护协调者职责中加入“校验账号状态与授权主体”，并评估补 `→ User` 依赖（同 UC-22 07 §4.2） |
| 待审变更与公开版本并存 | M20/M22/M30–M35/M44/M49；`AC-IMPL-FR06-01`；§6.4 | 全局无“待审版本/生效版本”表达，`Scholar` 只有“已核验身份/公开学者档案” | 无法表达“公开仍为旧机构 + 申请人可查待审状态”；实现易把待审直接写进公开字段 | R5 增加 `ScholarChangeRequest`（状态 `待审核/通过/驳回`），并明确 `Scholar` 只表达生效版本；不建草稿—发布—删除状态机 |
| 审批后生效 | M39–M52；§6.4；NF05 | 全局只有 `AuditCtl ..> Evidence` 与 `ClaimCtl ..> Auth`，无“批准后改变档案公开值”的职责 | 批准可能被实现成“只改状态不生效”或“生效无审计” | 在维护协调者职责中写明“通过才生成生效版本”，并要求生效与审计同批留痕（A9） |
| 来源事实不被人工覆盖 | §7.2；BR-IMP-04；SPEC INV-01/INV-06 | 全局 `Scholar` 只有“已核验身份()/公开学者档案()”，无原值/规范值区分 | “维护来源事实”可能被实现为覆盖原始来源记录 | `Scholar` 增加“保留原始来源事实引用”的职责；原值仍由 `SourceRecord` 承载 |
| 审核材料与待审内容的可见范围 | M31/M32/M34；A10；BR-IMP-11；NF06 | 全局 `Evidence` 无可见范围属性；`ScholarPortalUI` 无视图区分 | 待审内容可能被公开门户读到 | `ScholarChangeRequest` 增加可见范围语义（本人＋有权审核者）；`ScholarMaintenanceUI` 明确不进入公开视图 |
| 审计缺对象/结果/时间 | M9/M12/M16/M21/M27/M46/M50；BR-IMP-10 | 全局 `Evidence` 属性为“操作者/理由/前后值” | 无法表达“被拒绝的越权尝试”与“待审登记” | `Evidence` 建议补 `targetRefs`、`outcome`、`occurredAt`（同 UC-22 07 §3） |

## 4. 关系差异

### 4.1 `ProfileCtl ..> Scholar`（全局图第 107 行）

- 本 UC 顺序图中**没有** `ProfileCoordinator → Scholar` 的消息；本 UC 的写入路径由 `ScholarMaintenanceCoordinator → Scholar` 承担（M5/M24/M45）。
- 合并序列 `DGM-SEQ-04-profile.puml` 第 35 行把 `Profile -> Scholar : 转对象授权与学术审核` 画在 `opt 试图改 Scholar 身份/机构/归属` 分支内。若该箭头被读成“普通主页协调器可以写 Scholar”，就与本 UC 的授权守卫冲突。
- **R5 建议：** 第 107 行改为“拒绝/转交”语义（不写入、不改核验状态），把实际维护依赖落到 `ScholarMaintenanceCoordinator`；不能保留可被读成“普通编辑可写档案”的箭头。

### 4.2 `ScholarAuthorization → Scholar` 多重性

- 全局第 120 行未标多重性；本 UC 同样**不标**（SPEC ID-12 的并列管理者/转移程序待评审；沿用 UC-06 C-04 与 05 §2.2）。
- 不支持把该关系画成 `1:1` 或 `0..1:1`：基线没有“每个 Scholar 只能有一个管理者”的规定。

### 4.3 `ScholarChangeRequest — Scholar`（本 UC 新增）

- 全局无该边。本 UC 依据 `AC-IMPL-FR06-01` 与 §6.4 画 `0..*` → `1`：一条待审变更指向一个档案；同一档案是否可并存多条待审变更待确认（B-09）。
- 不建议把它画成 `Scholar` 的自反“版本”关系：本 UC 已把“生效版本”作为 `Scholar` 的当前值，把“未生效内容”作为独立对象，避免同一对象内部出现两套可见性。

### 4.4 `Scholar — UserProfile` / `Scholar — Authorship`

- 全局已有 `Scholar -- Authorship -- Work` 与 `User — Claim — Scholar`，但**没有** `Scholar — UserProfile` 边；本 UC 同样不建立（SRS §12；BR-IMP-03）。
- 本 UC 也不建立/修改 `Scholar — Authorship`：档案维护不改参与关系状态（`AC-IMPL-FR15-06`；BR-UC07-07）。

### 4.5 审计与实体

- 全局只有 `AuditCtl ..> Evidence`、`Evidence ..> Claim/Authorship`；本 UC 把对象指向写成 `AuditEvidence.targetRefs` 属性（05 §2.3），避免与全局 `Evidence` 关系冲突；R5 再统一留痕对象引用的表达方式。

## 5. 与局部用例图/活动图/状态图的一致性

| 全局资产 | 与本 UC 的一致点 | 需注意的差异 |
|---|---|---|
| [`DGM-USECASE-07-profile.puml`](../../../uml/profile/DGM-USECASE-07-profile.puml) | `已授权 Scholar 管理者 → UC-07`；`Claim ..> MaintainScholar : 仅批准后授权`；图例“普通 UserProfile 与 Scholar 是两个对象” | 用例图未表达“审核管理员参与待审决定的批准”；本 UC 依 FR06 参与者与 `02-用例模型与规约.md` UC-07 第 4 步把它作为次要参与者，不改用例图 |
| [`DGM-ACTIVITY-06-edit-profile.puml`](../../../uml/profile/DGM-ACTIVITY-06-edit-profile.puml) | 学术字段分支“转 UC-07/UC-08 的对象授权与审核流程；本次普通编辑不直接写入学术核验状态” | 活动图属 UC-22，未展开 UC-07 内部（授权/范围/待审/审批）；本 UC 独立建模，不修改该图 |
| [`DGM-SEQ-04-profile.puml`](../../../uml/analysis/DGM-SEQ-04-profile.puml) | 越权/停用拒绝、受控审计、普通编辑不改核验状态 | 第 34–36 行 `opt` 把“转对象授权与学术审核”画在 UC-04/22 合并序列内；本 UC 的 `DGM-SEQ-12` 才是该转交的实际实现，合并序列不应被视为 UC-07 实现 |
| [`DGM-STATE-02-claim-request.puml`](../../../uml/claim/DGM-STATE-02-claim-request.puml) | 认领申请状态与授权生成 | 本 UC 的 `ScholarChangeRequest` 状态（`待审核/通过/驳回`）与认领申请状态（含 `待补充/争议`）是两套状态机，不得共用图或混用状态名（类比 §11.3 中“认领申请状态与参与关系状态是两套独立状态”的原则） |
| [`scholar-achievement-relation-state.puml`](../../../uml/governance/scholar-achievement-relation-state.puml) | 参与关系状态独立于管理员审核事件 | 本 UC 不改变任何参与关系状态（`AC-IMPL-FR15-06`；ID-06/ID-07） |

## 6. 追踪文件与登记表（只提建议，不改文件）

| 文件 | 现状 | 建议（由父 Agent 决定） |
|---|---|---|
| [`03-AC-UC-分析模型追踪.md`](../03-AC-UC-分析模型追踪.md) | FR06 五行“分析模型”列只列 `DGM-USECASE-07 / ACT-06 / SEQ-04`；UC-07 反向行列出 `AC-IMPL-FR06-01`～`05`、`AC-IMPL-NF03-01` | 增补 `SEQ-12 / VOPC-08`；`SEQ-04` 只作为 UC-04/UC-22 的合并序列，不再充当 UC-07 的实现证据 |
| [`analysis-class-registry.md`](../automation/analysis-class-registry.md) | 已有 `Scholar`、`ScholarAuthorization`、`AuditEvidence` 行；无维护边界/协调者/待审变更行 | 增量登记 `ScholarMaintenanceUI`（别名 `ScholarPortalUI`，全局类图缺该类）、`ScholarMaintenanceCoordinator`（替代 `ProfileCtl ..> Scholar` 的维护职责）、`ScholarChangeRequest`（新概念，待与 UC-08 变更申请统一评估）；`Scholar` 行补“生效版本/待审并存、原始来源事实只读” |
| [`unresolved-decisions.md`](../automation/unresolved-decisions.md) | D-06（认领证据最低集合）、D-09（审计保留）、D-11/D-12/D-13（命名与关系） | 可评估新增一条“学者档案变更的需审核字段清单与授权范围粒度”（本 UC B-01/B-02/B-05）；本包不直接修改该文件 |
| 全局 `DGM-CLASS-01-analysis.puml` | `ProfileCtl ..> Scholar`（第 107 行）、`Auth -- Scholar`（第 120 行）、`Evidence` 属性缺对象/结果/时间、无待审变更 | 按 §3–§4 建议在 R5 统一分析类时处理；本 UC 不直接修改全局图 |

## 7. 差异结论

UC-07 分析支持以下全局模型调整建议：

1. 保留 `Scholar`、`ScholarAuthorization`、`User`、`AuditEvidence` 与 `User 1—1 UserProfile`；`Scholar` 与 `UserProfile` 继续无关联；
2. 为“维护已授权 Scholar 档案”引入独立协调者，职责包含“账号状态＋对象授权＋范围＋目标一致”三守卫、“待审登记/审批后生效”、“原始来源事实不被覆盖”、“受保护写入与拒绝均留痕”；
3. 把 `ProfileCtl ..> Scholar` 改为“拒绝/转交”语义，避免暗示普通编辑可写学术档案；实际维护依赖落到 UC-07 协调者；
4. 新增“待审档案变更”对象（`待审核/通过/驳回`），与认领申请状态机分离，并与 `Scholar` 的生效版本并存；
5. 保持 `ScholarAuthorization → Scholar` 多重性未定（SPEC ID-12），不在本 UC 断言 `1:1`；
6. 拆分 `Evidence`/`ReviewEvidence`/`AuditEvidence` 命名，补齐留痕的对象引用、结果与时间；
7. 需审核字段清单、授权范围粒度、传播时限与待审并存规则仍需需求确认（B-01/B-02/B-03/B-05/B-09）。

本轮**不直接修改** `DGM-CLASS-01-analysis.puml`、`DGM-SEQ-04-profile.puml`、`DGM-ACTIVITY-06-edit-profile.puml`、`DGM-USECASE-07-profile.puml`、`DGM-STATE-02-claim-request.puml` 与共享追踪/登记文件。
