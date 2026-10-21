# UC-06 分析与现有全局分析模型差异

**版本：** V1.0（2026-10-21）
**比较对象：**
- 全局分析类图 [`DGM-CLASS-01-analysis.puml`](../../../uml/analysis/DGM-CLASS-01-analysis.puml) 与 [`01-BCE分析类模型.md`](../01-BCE分析类模型.md)；
- 合并认领序列 [`DGM-SEQ-02-claim.puml`](../../../uml/analysis/DGM-SEQ-02-claim.puml)、认领审核活动图 [`DGM-ACTIVITY-01-claim-review.puml`](../../../uml/claim/DGM-ACTIVITY-01-claim-review.puml)、申请状态图 [`DGM-STATE-02-claim-request.puml`](../../../uml/claim/DGM-STATE-02-claim-request.puml)；
- 核心 SPEC [`CognitaNexus-Core-SPEC-V1.0.md`](../../../spec/CognitaNexus-Core-SPEC-V1.0.md) §3、§6（ID-06/ID-07/ID-12）；
- 直接前驱 [`uc-05/`](../uc-05/README.md)（提交与本人查询侧）；候选类登记表 [`analysis-class-registry.md`](../automation/analysis-class-registry.md)、待决清单 [`unresolved-decisions.md`](../automation/unresolved-decisions.md)。

**原则：** 全局图只作比对材料，不作为本 UC 识别类的反向答案；本轮不重构全局图、不修改共享追踪/登记文件。

## 1. 已存在且一致

| UC-06 类/概念 | 全局/既有对应 | 一致点 | 证据 |
|---|---|---|---|
| `ClaimReviewUI` | `01` §2 `ClaimUI`（UC-05/06“提交/查询/审核认领”）；全局 `ClaimUI`（第 15 行） | 都承接认领的审核侧交互；都不直接授予对象权 | FR05；FR14；SRS §8 |
| `ClaimReviewCoordinator` | `01` §3 `ClaimReviewCoordinator`（“证据校验、待审/补充/争议、批准授予对象权”）；合并序列同名控制类 | 名称与协调职责一致：守卫“授权失败不虚报、原授权不自动转移、不建第二主页” | FR05-01～04；BR-IMP-05 |
| `User` | 全局 `User`（第 49 行“账号状态/对象授权校验”） | 都是账号与对象授权主体，1:1 UserProfile，不因批准生成 Authorship | FR04；SRS §12 |
| `Scholar` | 全局 `Scholar`（第 58 行） | 都是授权对象与被认领档案；本 UC 不创建、不改写 | FR05；SRS §12 |
| `ClaimApplication` | `01` §4 `ClaimApplication`；全局 `Claim`（第 62 行） | 都承载申请状态与证据引用；状态与授权分离 | FR05；`DGM-STATE-02` |
| `ScholarAuthorization` | `01` §4 `ScholarAuthorization`；全局 `Auth`（第 66 行“目标Scholar/授权范围”） | 都是批准后的对象级管理权；不等于本人确认、不自动转移 | AC-IMPL-FR05-02/03；SPEC ID-07/ID-12 |
| `ReviewEvidence` | `01` §4 `ReviewEvidence`（“审核/修正/授权的可追溯决定”） | 都表示审核侧使用的论证/证据材料，且不作为公开实体信息 | BR-IMP-11；NF06；AC-IMPL-NF06-01 |
| 批准授予对象权、失败不虚报 | `01` §3 守卫；`DGM-SEQ-02`“授予目标Scholar对象管理权/授权事务失败保持原状态” | 一致：本 UC 的 M27 只在无冲突且写入成功时创建授权；A6 保持原状态 | AC-IMPL-FR05-02；`DGM-STATE-02` |
| 竞争保留原授权 | `01` §3“原授权不自动转移”；SPEC ID-12；SRS §6.3 | 一致：本 UC 只读读取并对争议留痕，不转移 | AC-IMPL-FR05-03；BR-IMP-05 |
| 审核不确认成果归属 | 全局 `Scholar -- Authorship`、`01` §5.6“审计与授权分离” | 一致：本 UC 无 Authorship 消息，M34 明确声明 | FR05；AC-IMPL-FR15-03/06；SPEC ID-07 |
| 不建第二主页 | 全局 `User "1" -- "1" UserProfile`（第 116 行）；`01` §5.1 | 一致：本 UC 无 UserProfile 消息 | AC-IMPL-FR05-04；SRS §12 |
| 决定留痕 | 全局 `Evidence`（第 90 行“操作者/理由/前后值”）、`Evidence ..> Claim : 决定留痕`（第 126 行） | 一致：本 UC 用 `AuditEvidence` 承载决定/操作者/对象/时间/前后值/理由 | FR14；BR-IMP-10；INV-10 |

## 2. 命名/别名差异

| 本 UC 名称 | 全局/既有名称 | 差异 | R5 建议 |
|---|---|---|---|
| `ClaimReviewUI` | 全局 `ClaimUI`（第 15 行）；`01` §2 `ClaimUI`（UC-05/06） | 一名覆盖三个交互面（提交、本人查询、审核）；本 UC 与 UC-05 用了两个 Boundary | 保留 `ClaimUI`（申请人侧）与 `ClaimReviewUI`（审核侧）两名，或在 `ClaimUI` 内显式分“提交/查询/审核”三组职责；禁止把“决定与理由”输入塞进申请人侧界面 |
| `ClaimReviewCoordinator` | 全局 `ClaimCtl`（第 29 行“证据/授权/争议”）；`01` §3 `ClaimReviewCoordinator`；UC-05 `ClaimCoordinator` | 三名一概念，且全局把提交与审核合在一个控制类 | R5 决定：单一 `ClaimCoordinator` 承担提交/查询/审核，或拆为 `ClaimCoordinator`（提交/查询，UC-05）＋`ClaimReviewCoordinator`（审核/授权，UC-06）；两侧职责必须显式写出（C-09；D-11；UC-05 B-09） |
| `ClaimApplication` | 全局 `Claim`（第 62 行）；`01` §4 `ClaimApplication` | 图与文档两名并存 | 统一为 `ClaimApplication`，`Claim` 作为别名（与 UC-05 一致） |
| `ScholarAuthorization` | 全局 `Auth`（第 66 行）；`01` §4 `ScholarAuthorization` | 缩写 vs 全名 | 统一为 `ScholarAuthorization`，避免与“认证/登录”混淆 |
| `AuditEvidence` | 全局 `Evidence`（第 90 行）；`01` §4 `ReviewEvidence`（“审核/修正/授权的可追溯决定”）；UC-22 `AuditEvidence` | `ReviewEvidence` 一词在 `01` 中同时指材料与决定记录；全局只有一个 `Evidence` | R5 拆分命名：认领材料 → `ReviewEvidence`；受保护写入/审核决定留痕 → `AuditEvidence`（C-08；D-13） |
| 状态名 `通过/驳回` | `DGM-STATE-02` 与 SPEC §3 用 `已通过/已驳回`；FR05 用 `通过/驳回` | 同一语义两种写法 | 统一枚举后再改图/表（C-10；UC-05 B-11） |

## 3. 职责差异/需要补充

| 项目 | 本 UC 证据 | 全局现状 | 差异/影响 | R5 处理 |
|---|---|---|---|---|
| 审核权限校验的对象 | M3/M58 每次请求校验身份、账号状态与审核权限（FR14；NF03；SRS §8） | 全局 `ClaimCtl` 无到 `User` 的依赖；`01` §3 只说“批准授予对象权” | 无权限者可执行决定、或前端隐藏即当作授权 | `ClaimCtl` 补“校验审核者身份/账号状态/审核权限”（与 UC-05 的补项合并） |
| 申请状态/版本守卫 | M7/M8/M20；`DGM-STATE-02`“审核必须校验当前版本和权限” | 全局无版本守卫表达 | 并发下可覆盖他人决定 | 在控制类职责写明“校验当前版本，过期拒绝且不改状态” |
| 冲突守卫与“不得批准” | M15–M17/M20/A5；AC-IMPL-FR05-02/03 | 全局只有 `Claim ..> Auth : 批准后授予`，无“先判冲突” | 有既有管理者时仍可能批准，导致权利冲突 | 明确“批准前置：无未决冲突”；冲突→记录争议 |
| 决定集合与理由必填 | M18/M20/M21（A8）；`DGM-STATE-02`“有权且理由完整” | 全局 `Claim` 只有状态，无“理由必填” | 决定可能无理由入库，审计不完整 | 在 `Claim`/控制类职责写“状态变更须含理由” |
| 授权范围最小化 | M27 只授予目标 Scholar/门户；M34 明示边界 | 全局 `Auth` 写“目标Scholar/授权范围”，未写“仅对象级、不含全平台/全部成果” | 授权可能被放大为全库管理权 | 在 `Auth.scope` 与职责中写明对象级范围 |
| 补证复核放行 | M44–M55；`DGM-STATE-02` `待补充→待审核` | 全局活动图把“补证后重新提交”画成申请人侧停止流程 | “谁放行回待审核”无归属，可能出现无人推进 | 明确“本人重提（UC-05）＋审核者复核齐备（UC-06）”两段（C-07） |
| 材料可见范围 | M11/M12/M46；BR-IMP-11；NF06 | 全局 `Claim +证据材料()` 未写可见范围 | 材料可能被公开或跨用户读取 | 在 `Claim`/`ReviewEvidence` 职责写“仅本人与有权审核者” |
| 审计前后值 | M24/M29/M33/M37/M41/M49/M53；FR14；INV-10 | 全局 `Evidence` 只写“操作者/理由/前后值” | 未规定“无审计不报生效” | 在控制类/审计类写“决定生效须伴随审计” |
| 审核角色层级/SLA/通知 | 本 UC 未建模（C-01/C-03/C-11） | 全局也无 | 一致：都不凭空造 | 维持；需求未冻结前不补类 |

## 4. 关系差异

### 4.1 `User -- Claim : 提交`（全局第 117 行）

- 本 UC 需要“申请 → 申请人”的明确角色，并在批准时以同一引用确定**被授权主体**。
- 一致方向；本 UC 沿用 UC-05 的 `0..*:1` 与角色名“申请人/被授权主体”。
- R5 建议：全局改为 `User "1" -- "0..*" ClaimApplication : 提交/申请人`。

### 4.2 `Claim -- Scholar : 认领目标`（第 118 行）

- 一致：申请指向既有 Scholar；本 UC 补 `0..*:1`，并用于 M14 确认目标与 M27 授权对象。
- R5 建议：全局补多重性与角色名“认领目标”。

### 4.3 `Claim ..> Auth : 批准后授予`（第 119 行）与 `ClaimCtl ..> Auth`（第 109 行）

- 本 UC 的语义更精确：`ClaimReviewCoordinator ⇢ Auth`（只读，M15/M16）与 `ClaimReviewCoordinator → Auth`（创建，M27，仅无冲突批准）；`ClaimApplication` 本身不创建授权。
- 风险：若把 `Claim ..> Auth` 读成“申请即产生授权”，会违反 FR05“无冲突审核通过后授予”与 AC-IMPL-FR05-02/03。
- R5 建议：把该依赖的触发条件写明为“审核批准且无未决冲突（UC-06/M27）”，并在 UC-05 视图中限定为只读。

### 4.4 `Auth -- Scholar : 目标档案`（第 120 行）

- 一致：授权指向目标档案；本 UC 以目标 Scholar 为读取键与授权对象。
- 差异：同一 Scholar 是否允许并列管理者、授权历史如何保留未定（SPEC ID-12；C-04），本 UC 不标多重性。
- R5 建议：待需求确认后再标多重性，并在关系角色上注明“对象级”。

### 4.5 `Evidence ..> Claim : 决定留痕`（第 126 行）

- 方向一致；本 UC 用 `AuditEvidence` 承载决定的**前后值与操作者**，把该关系视为“审计对象的属性引用”，不画成实体关联（05 §2.4）。
- 差异：全局 `Evidence` 一词同时覆盖材料与留痕；本 UC 明确拆为 `ReviewEvidence` + `AuditEvidence`。
- R5 建议：拆分并统一命名（D-13/C-08）。

### 4.6 本 UC 新增的关系（全局没有）

| 关系 | 依据 | R5 建议 |
|---|---|---|
| `ScholarAuthorization "0..*" --> "1" User : 被授权主体` | M27“被授权=申请人”；SRS §12“User—ScholarAuth 授权记录” | 全局 `User--Auth` 补角色名与 `0..*:1`，并注明“由审核批准产生”（UC-06） |
| `ClaimReviewCoordinator --> AuditEvidence` | M24/M29/M33/M37/M41/M49/M53/M60；FR14；BR-IMP-10 | 与全局 `AuditCtl ..> Evidence` 并存时，明确“决定留痕由审核控制类触发，审计查询归 `AuditCtl`（UC-17）” |

### 4.7 本 UC 不建立的关系

- `User—Scholar`：批准通过 `ScholarAuthorization` 表达，不画直接关联（FR05；BR-IMP-05）。
- `UserProfile—Scholar`：认领不合并主页与档案（AC-IMPL-FR05-04；SRS §12）。
- `Claim—Authorship` / `Auth—Authorship`：批准不等于本人确认，不自动确认任何关系（FR05；AC-IMPL-FR15-03/06；SPEC ID-07）。

## 5. 与合并序列、活动图、状态图的差异

| 对象 | 既有内容 | 本 UC 处理 | 说明 |
|---|---|---|---|
| `DGM-SEQ-02-claim`（合并序列） | 一个序列同时画申请人提交与管理员审核，含 `Ctl -> Auth : 授予目标Scholar对象管理权`、`Ctl -> Profile : 仅展示批准关联，不创建第二主页`、`Ctl -> Evidence : 留痕` | 本 UC 只取审核段，并补 A2（过期）、A4（补证复核）、A6（授权失败）、A8（理由缺失）、E12（决定查询）；`Profile` 消息不入图 | 合并序列的 `Profile` 消息表达“结果展示”，属公开门户读取（UC-03/UC-07），不是本 UC 的写操作；本 UC 以“无 UserProfile 消息”表达同一约束 |
| `DGM-ACTIVITY-01-claim-review` | 管理员核验→补证/争议/批准驳回；授权失败保持先前状态；已驳回不撤销原授权 | 本 UC 逐条映射：A3（补证）、A5（争议）、A7（驳回）、A6（授权失败）、A4（补证后再核验） | 活动图未画“过期审核”与“理由缺失”，本 UC 按 `DGM-STATE-02` 守卫补 A2/A8 |
| `DGM-STATE-02-claim-request` | `Pending→Supplement/Pending→Approved/Rejected/Dispute`；`Supplement→Pending`（本人重提）；`Dispute→Supplement/Pending/Rejected` | 本 UC 覆盖 `待审核→待补充/通过/驳回/争议`、`待补充→待审核`（A4a）与“保持原状态”（A2/A6/A4b）；**不展开** `争议→…` 结案边 | `DGM-STATE-02` 未定义 `待补充→驳回/争议`，本 UC 不新增（C-06）；争议结案涉及授权解封/转移与申诉程序，待评审（SPEC ID-12；C-05） |
| 核心 SPEC §3 | 公共状态互不混同；INV-10“所有生效决定均留证据、时刻、操作者类型” | 本 UC 不混用申请状态与 Authorship 关系状态；A9 由 INV-10 推出 | 一致 |
| 核心 SPEC ID-06/ID-07/ID-12 | 管理员审核事件独立、不冒充本人确认；门户认领只授予门户管理权；原管理人员权限不因冲突自动转移，转移/申诉待评审 | 本 UC 的 BR-UC06-06/07 与 A5 直接对齐 | 一致 |
| SRS §6.3 | `待审核→待补充→待审核` 或 `待审核→已通过/已驳回/争议`；争议交人工结论，不支持原授权自动转移；门户已有授权则继续显示为已认领 | 本 UC 状态迁移与“竞争不使门户变为未认领”一致 | 一致 |

> SPEC T26 已命名“冲突待办/队列”作为竞争认领处置机制；本 UC 的 `ClaimApplication` + 争议状态只表达业务事实，不把队列实现写成分析类；R5 再统一。

## 6. 候选类登记表缺口（不修改登记表）

`analysis-class-registry.md` 现有行中，`ClaimUI`、`ClaimCoordinator`（后续参与 UC-06）、`User`（UC-05/06/22）、`Scholar`（…/07/…）、`ClaimApplication`、`ReviewEvidence`、`AuditEvidence` 已登记；**尚无** `ClaimReviewUI`、`ClaimReviewCoordinator`、`ScholarAuthorization` 作为独立审核侧类的行（`ScholarAuthorization` 已在 UC-05 的 07 文档 §6 建议登记，但登记表尚未含该行）。本 UC 补充建议如下（未改动该文件，交父 Agent 登记）：

| 正式名称 | BCE | 首次发现 UC | 后续参与 UC | 主要职责 | 必要业务属性 | 已有关系 | 未定多重性 | 别名/冲突 | R5 建议 |
|---|---|---|---|---|---|---|---|---|---|
| ClaimReviewUI | Boundary | UC-06 | UC-17（待办入口展示） | 审核上下文、证据查看、决定与理由、决定查询的输入与结果展示 | 无持久业务属性 | → ClaimReviewCoordinator | 不定义 | 全局 `ClaimUI` | 与 `ClaimUI` 分工或合并（C-09） |
| ClaimReviewCoordinator | Control | UC-06 | — | 审核权限、状态/版本、冲突、理由守卫；授权创建；状态与审计写入；决定查询 | 单次审核请求分析状态 | →UI/User/Claim/ReviewEvidence/Scholar/ScholarAuthorization/AuditEvidence | 不定义 | `ClaimCtl`/`ClaimCoordinator` | 明确提交侧与审核侧是否拆分（B-09/D-11/C-09） |
| ScholarAuthorization | Entity | UC-06（UC-05 只读） | UC-07 | 目标 Scholar 对象级管理授权事实 | targetScholarRef/granteeRef/scope/effectiveState/grantedAt | →Scholar；→User（被授权主体） | →Scholar 未定 | 全局 `Auth` | 统一全名；补 `User 0..*:1` 角色（C-04） |
| AuditEvidence（补充职责） | Entity | UC-22 | UC-06/17 | 追加“审核决定留痕：决定类型/前后值/理由” | decisionKind/actorIdentity/targetRefs/occurredAt/beforeAfterValues/reason | 被 ClaimReviewCoordinator 写入 | 不定义 | 全局 `Evidence`/`ReviewEvidence` | 拆分命名（D-13/C-08） |

## 7. 差异结论

建议 R5 统一分析类时：

1. 统一 `ClaimApplication`/`Claim`、`ScholarAuthorization`/`Auth` 的正式名与别名；
2. 明确 `ClaimCtl` / `ClaimCoordinator` / `ClaimReviewCoordinator` 是否拆分提交侧与审核侧（C-09；B-09；D-11），并让两侧职责显式；
3. 拆分 `ReviewEvidence`（材料）与 `AuditEvidence`（受保护写入/决定留痕），落实 D-13/C-08；
4. 给 `ClaimCtl`/审核控制类补：服务端审核权限校验、状态/版本守卫、无冲突才批准、对象级授权范围、竞争保留原授权、决定理由必填、材料可见范围、决定留痕；
5. 把 `Claim ..> Auth : 批准后授予` 的触发条件写成“审核批准且无未决冲突（UC-06/M27）”，并在 UC-05 视图中限定为只读；
6. 补 `User--Auth` 的“被授权主体”角色与 `0..*:1`，`Auth--Scholar` 的多重性待 C-04 确认后再标；
7. 明确 `争议→…` 结案边与授权转移/申诉程序待需求评审（SPEC ID-12；C-05），不在模型层默认“自动转移”。

本轮**不直接修改** `DGM-CLASS-01-analysis.puml`、`DGM-SEQ-02-claim.puml`、`DGM-STATE-02-claim-request.puml`、`DGM-ACTIVITY-01-claim-review.puml`、`01-BCE分析类模型.md` 或共享登记文件。
