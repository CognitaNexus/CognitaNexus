# UC-06 分析类职责、属性、关系与分析机制

**版本：** V1.0（2026-10-21）
**方法：** 教材 §5.5.1 职责、§5.5.2 属性、§5.5.3 关系、§5.5.4 分析机制。
**范围：** 只定义 UC-06 能够证实的部分；跨用例统一关系不在此擅自补齐。

## 1. 定义原则

1. “做（Do）”型职责说明对象能执行/协调什么；“知道（Know）”型职责说明必须知道/提供什么。
2. 属性只写业务信息，不写数据库字段、主键类型、索引、接口或 API 参数。
3. 关系只写有消息或正式业务约束支持的；无依据的跨用例关系留待 R5。
4. 控制类没有长期业务属性；请求上下文是单次用例的分析状态。
5. 审核权限划分、证据最低集合、SLA、争议结案/授权转移/申诉、通知渠道、重试与回滚等未冻结项不伪装成类定义，统一标 `待确认`（C-01～C-12）。

## 2. ClaimReviewUI（Boundary）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Boundary | §5.4.2；M1/M2/M18/M19/M44/M45/M56/M57/M61/M62 |
| Do | 接收“打开待审核/待补充申请”“查看私有证据”“选择决定并填写理由”“提交补证复核”“查询决定”的输入；显示申请与证据、待补充与缺失项、争议与冲突事实、批准与授权范围、授权失败、无权限、状态已变化、理由必填、仍缺项、决定与审计摘要 | E1、E4、E11、E12、A1–A9 |
| 候选分析操作（`/`） | `/接收审核请求`、`/接收决定与理由`、`/显示申请与私有证据`、`/显示决定结果`、`/显示拒绝或失败`、`/显示决定与审计摘要` | M1–M62 |
| Know | 当前审核/查询会话的展示状态；不持久保存申请、材料、授权或审计 | 分析推导 |
| 业务属性 | 无持久业务属性；决定类型、理由与查询条件是短期请求数据 | §5.5.2 |
| 关系 | 单向关联 `ClaimReviewCoordinator`（M2/M19/M45/M57 请求；M6/M10/M12/M21/M25/M30/M34/M38/M42/M50/M54/M61 返回） | 04 消息表；VOPC |
| 不能承担 | 不能判定审核权限、申请状态版本、冲突、授权范围或审计内容；入口可见不构成授权 | FR14；NF03；SRS §8 |
| 分析机制 | 结果类型可区分（通过/驳回/待补充/争议/失败/无权/状态已变化/理由必填）；材料只在审核视图显示，不进入公开视图 | NF01（分析推导）；BR-IMP-11；AC-IMPL-NF06-01 |

## 3. ClaimReviewCoordinator（Control）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Control | §5.4.2；M2–M62 中除纯展示外的全部消息 |
| Do | 服务端校验审核者身份/账号状态/审核权限；读取申请状态、版本与引用；按可见范围读取私有材料；只读读取目标有效授权并判定冲突；复核决定守卫；无冲突批准时创建对象级授权；记录申请状态与理由；写入决定审计；返回结果；支持只读决定查询 | E2–E12、A1–A9 |
| 候选分析操作（`/`） | `/校验审核权限`、`/读取申请与版本`、`/读取审核材料`、`/确认目标档案`、`/读取现有授权（只读）`、`/判定未决冲突`、`/复核决定守卫`、`/创建对象级授权`、`/记录申请状态与理由`、`/记录决定审计`、`/返回决定结果`、`/查询决定与审计` | M3–M61 |
| Know | 本次审核的分析状态：请求者与权限事实、申请引用与状态/版本快照、拟审材料、目标档案事实、现有授权事实、冲突判定结论、决定类型与理由、执行结局（通过/驳回/待补充/争议/失败/无权/状态已变化/理由必填） | 分析推导 |
| 业务属性 | 无长期业务属性。分析级状态：`requestContext`、`reviewerAuthority`、`applicationSnapshot`（状态与版本）、`evidenceUnderReview`、`targetScholarRef`、`existingAuthorizationFact`、`conflictFinding`、`decisionRequest`（类型＋理由）、`outcome` | §5.5.2；不指定实现存储 |
| 关系 | 单向关联 `ClaimReviewUI`；`User`（M3/M58）；`ClaimApplication`（M7/M59 读、M23/M32/M36/M40/M48/M52 写）；`ReviewEvidence`（M11/M46）；`Scholar`（M14）；`ScholarAuthorization`（M15/M16 只读、M27 创建）；`AuditEvidence`（M24/M29/M33/M37/M41/M49/M53 写、M60 读） | 04 消息表；VOPC |
| 不能承担 | 不承载申请状态与理由（`ClaimApplication` 的 Know）；不承载材料原文与可见范围（`ReviewEvidence`）；不承载授权范围（`ScholarAuthorization`）；不承载前后值留痕（`AuditEvidence`）；不是实现层 Controller/Service | §5.4.1；§5.5.1 |
| 分析机制 | 服务端每次校验；状态/版本守卫；无冲突才批准；授权仅对象级；竞争保留原授权；决定与审计一致；失败不虚报；结果可分 | AC-IMPL-FR05-02/03；NF03；FR14；BR-IMP-05/10；`DGM-STATE-02` |

**待确认：** 审核权限划分（C-01）、证据最低集合（C-02）、SLA（C-03）、并列管理者（C-04）、争议结案与授权转移/申诉（C-05）、补证重提口径（C-07）、授权失败重试（C-12）。

## 4. User（Entity）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Entity | §5.4.2；M3、M58；E2/E12；A1 |
| Do | 提供账号标识、账号状态与“是否具备审核认领权限”的账号主体事实；作为批准后的被授权主体（申请人） | FR14；NF03；AC-IMPL-NF03-01；SRS §8 |
| 候选分析操作（`/`） | `/校验账号状态`、`/提供审核权限事实` | M3、M58；A1 |
| Know | 自己的账号标识、账号状态、审核认领的权限事实 | SRS §8；FR14；UC-04 06 |
| 业务属性 | `accountIdentifier`：账号标识（沿用 UC-04，本 UC 不新增）；`accountStatus`：可用/停用；`reviewScope`：可审核认领的权限范围（划分口径待 C-01） | FR04；AC-IMPL-NF03-01；SRS §8 |
| 关系 | 被 `ClaimApplication` 以“申请人”引用（`0..*:1`）；被 `ScholarAuthorization` 以“被授权主体”引用（`0..*:1`）；`User—UserProfile` 1:1 由 UC-04 建立，本 UC 不读不写 | 05 §2；FR04 |
| 不能混淆 | User 不是 Scholar；账号状态不是申请状态；审核权限不等于“门户管理权”（后者由批准产生）；批准后申请人获得的是 Scholar 对象级管理权，不是账号身份变化 | FR05；SRS §12；BR-IMP-05 |
| 分析机制 | 持久性；服务端账号状态与权限校验；稳定标识 | FR14；NF03；SRS §8 |

**本 UC 不新增 User 的账号属性**：只引入“审核权限事实”这一业务信息，其划分未冻结（C-01）；不引入角色层级。

## 5. ClaimApplication（Entity）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Entity | §5.4.2；M7、M23、M28、M32、M36、M40、M48、M52、M59 |
| Do | 保存一次认领申请（申请人、目标、证据引用、状态、理由/缺失项、版本、决定时间）；支持状态迁移与“保持原状态”；向本人提供只读决定投影 | FR05；AC-IMPL-FR05-01/02；`DGM-STATE-02` |
| 候选分析操作（`/`） | `/提供状态与版本`、`/记录状态与理由`、`/记录争议`、`/保持原状态`、`/提供可读决定投影` | M7、M23、M28、M32、M36、M40、M48、M52、M59 |
| Know | 申请人引用、目标 Scholar 引用、证据引用、当前状态（`待审核/待补充/通过/驳回/争议`）、状态理由/缺失项、状态版本/更新时间、决定时间、（由 UC-05 记录的）提交时间与竞争事实 | FR05；AC-IMPL-FR05-01/03；`DGM-STATE-02` |
| 业务属性 | `applicationRef`、`applicantRef`、`targetScholarRef`、`evidenceRefs`、`status`、`statusReason`、`stateVersion`（状态版本/更新时间，供过期校验）、`decidedAt`；沿用 UC-05 的 `submittedAt`、`competitionFact` | FR05；`DGM-STATE-02`；BR-IMP-10 |
| 关系 | `0..* → 1 User`（申请人/被授权主体）；`0..* → 1 Scholar`（认领目标）；`→ ReviewEvidence`（材料，多重性未定） | 05 §2；FR05；AC-IMPL-FR05-03 |
| 不能混淆 | 不是 `ScholarAuthorization`（授权只在批准成功时产生）；不是 `AuditEvidence`（审计含操作者/前后值且读取受限）；不是 Authorship 状态；不是 Scholar 档案字段 | AC-IMPL-FR05-02/03；FR14；SPEC ID-07 |
| 分析机制 | 持久性；状态机守卫（取值来自 FR05/`DGM-STATE-02`）；版本守卫防覆盖；决定理由必填；本人可读、他人拒绝 | FR05；`DGM-STATE-02`；NF03；AC-IMPL-FR05-02 |

**待确认：** `待补充→驳回/争议` 无既有边（C-06）；状态名口径 `通过/驳回` vs `已通过/已驳回`（C-10）。

## 6. ReviewEvidence（Entity）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Entity | §5.4.2；M11、M46 |
| Do | 保存申请人提交的私有证明材料并关联申请；维持“仅本人与有权审核者可见”；向有权审核者提供材料、向补证复核提供最新提交版本 | BR-IMP-11；NF06；AC-IMPL-NF06-01 |
| 候选分析操作（`/`） | `/按范围提供材料`、`/提供最新提交版本`、`/判定可见范围` | M11、M46 |
| Know | 材料类别/内容引用、提交者、所属申请、可见范围；不含凭据与秘密 | BR-IMP-11；NF03；AC-IMPL-NF06-01 |
| 业务属性 | `evidenceKind`（类别清单待 C-02）、`materialRef`、`submittedByRef`、`attachedApplicationRef`、`visibilityScope`（本人＋有权审核者） | BR-IMP-11；NF06 |
| 关系 | 被 `ClaimApplication` 引用（多重性未定，05 §2.2） | 05 §2；D-06 |
| 不能混淆 | 不是公开档案资料（不进入检索/详情/公开门户）；不是决定留痕（后者是 `AuditEvidence`）；不因审核通过而公开 | BR-IMP-01；BR-IMP-11；AC-IMPL-NF06-01 |
| 分析机制 | 持久性（受控）；私有可见性；最小化；只读使用（本 UC 不新增/删除材料） | BR-IMP-11；NF03；NF06 |

**待确认：** 证据最低集合与“可核验”口径（C-02）；补证材料版本/替换（C-07）；留存与脱敏（D-09）。

## 7. Scholar（Entity）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Entity | §5.4.2；M14 |
| Do | 提供已收录学者档案身份与状态，供确认“授权对象仍是同一目标档案”；被只读使用，不被本 UC 创建或改写 | FR05；SRS §12 |
| 候选分析操作（`/`） | `/提供档案身份与已收录状态` | M14 |
| Know | 稳定身份引用、公开状态（认领前后均可公开读）、身份核验状态 | FR05；SRS §8；SRS §12 |
| 业务属性 | `stableId`、`publicState`、`identityState`；本 UC **不新增**账号绑定或管理字段 | FR05；SRS §12 |
| 关系 | 被 `ClaimApplication` 以“认领目标”引用（`0..*:1`）；被 `ScholarAuthorization` 以“目标档案”引用（多重性未定） | 05 §2 |
| 不能混淆 | 不是 UserProfile（普通主页）；同名不合并；认领不改写档案字段（维护属 UC-07）；本 UC 不创建 Scholar；批准后“管理权”属于 `ScholarAuthorization`，不是档案自身的属性 | FR03；BR-IMP-03；FR05；AC-IMPL-FR05-04；SPEC ID-07 |
| 分析机制 | 持久性；只读；认领前后均可公开读；同名不等于同一学者 | FR05；BR-IMP-03；SRS §8 |

## 8. ScholarAuthorization（Entity）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Entity | §5.4.2；M15、M16、M27 |
| Do | 回答“目标 Scholar 当前是否已有有效授权、主体与范围”；在无冲突批准成功时**新建**一条对象级管理授权；在争议/驳回/失败路径保持原状 | AC-IMPL-FR05-02/03；BR-IMP-05；SPEC ID-07/ID-12 |
| 候选分析操作（`/`） | `/查询目标当前有效授权`（只读）、`/创建对象级授权`、`/保持原授权` | M15/M16、M27、A5 |
| Know | 目标 Scholar 引用、被授权主体（→User）、对象级范围（仅目标门户/档案）、当前有效性、授权生效时间 | AC-IMPL-FR05-02/03；SRS §12 |
| 业务属性 | `targetScholarRef`、`granteeRef`、`scope`（仅目标 Scholar/门户）、`effectiveState`、`grantedAt` | AC-IMPL-FR05-02；BR-IMP-05；BR-IMP-10 |
| 关系 | `→ Scholar`（目标档案，多重性未定）；`0..* → 1 User`（被授权主体） | 05 §2；SRS §12 |
| 不能混淆 | 不是申请状态；不是 UserProfile 所有权（注册即有）；不等于本人确认；不使任何 Authorship 自动确认；不因竞争申请自动转移 | FR05；BR-IMP-05；SPEC ID-07/ID-12 |
| 分析机制 | 持久性；写入仅限无冲突批准；竞争保留原授权；范围最小化（仅目标对象）；转移/并列管理者待评审 | AC-IMPL-FR05-02/03；SPEC ID-12；C-04/C-05 |

## 9. AuditEvidence（Entity）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Entity | §5.4.2；M24、M29、M33、M37、M41、M49、M53、M60 |
| Do | 保存审核决定的留痕（决定类型、操作者、对象、时间、前后值、理由）；向有审核权限者提供该决定的审计事实 | FR14；BR-IMP-10；SPEC §3 INV-10 |
| 候选分析操作（`/`） | `/记录决定留痕`、`/提供决定前后值`、`/按对象查询决定审计` | M24/M29/M33/M37/M41/M49/M53、M60 |
| Know | 决定类型（批准/驳回/待补充/争议/复核）、操作者标识、对象引用（申请/目标 Scholar/授权）、发生时间、前后值（申请状态、授权有无）、理由 | FR14“操作者/对象/时间/前后值/原因”；BR-IMP-10；INV-10 |
| 业务属性 | `decisionKind`、`actorIdentity`、`targetRefs`、`occurredAt`、`beforeAfterValues`、`reason` | FR14；BR-IMP-10；INV-10 |
| 关系 | 被 `ClaimReviewCoordinator` 写入与读取；对象引用作为属性（不另画实体关联） | 05 §2.4 |
| 不能混淆 | 不是申请状态（申请状态对本人可读，审计读取受限）；不是申请人提交的材料（`ReviewEvidence`）；不含凭据/秘密（NF03） | FR14；NF03；AC-IMPL-NF06-01 |
| 分析机制 | 持久性；受控写入与读取；前后值必留；无审计不报生效（A9）；命名与 `Evidence`/`ReviewEvidence` 统一待 R5（C-08） | BR-IMP-10；INV-10；D-13 |

## 10. 关系定义汇总

| 关系 | 角色/方向 | 多重性 | 来源 | 状态 |
|---|---|---|---|---|
| ClaimReviewUI → ClaimReviewCoordinator | UI 请求/返回 | 不定义持久多重性 | M2/M19/M45/M57；M6/M10/M12/M21/M25/M30/M34/M38/M42/M50/M54/M61 | 已定义（单向关联） |
| ClaimReviewCoordinator → ClaimApplication | 读取/记录状态与理由 | 不定义持久多重性 | M7/M59；M23/M32/M36/M40/M48/M52 | 已定义（单向关联） |
| ClaimReviewCoordinator → ReviewEvidence | 只读材料 | 不定义持久多重性 | M11/M46 | 已定义（单向关联，只读） |
| ClaimReviewCoordinator → Scholar | 只读确认目标档案 | 不定义持久多重性 | M14 | 已定义（单向关联，只读） |
| ClaimReviewCoordinator ⇢ ScholarAuthorization | 只读现有授权 | 不定义持久多重性 | M15/M16 | 已定义（虚线只读） |
| ClaimReviewCoordinator → ScholarAuthorization | 创建批准授权 | 不定义持久多重性 | M27 | 已定义（单向创建，仅无冲突批准） |
| ClaimReviewCoordinator → User | 校验身份/账号状态/权限 | 不定义持久多重性 | M3/M58 | 已定义（单向关联） |
| ClaimReviewCoordinator → AuditEvidence | 写入/读取决定留痕 | 不定义持久多重性 | M24/M29/M33/M37/M41/M49/M53；M60 | 已定义（单向关联） |
| ClaimApplication 0..* → 1 User | 申请人/被授权主体 | `0..*` : `1` | FR05；SRS §8；M7/M58/M27 | 已定义（业务关联） |
| ClaimApplication 0..* → 1 Scholar | 认领目标 | `0..*` : `1` | FR05；AC-IMPL-FR05-03 | 已定义（业务关联） |
| ClaimApplication → ReviewEvidence | 私有材料 | **未定** | M11/M46；D-06 | 已定义（多重性待确认） |
| ScholarAuthorization → Scholar | 目标档案 | **未定** | M15/M27；SPEC ID-12；C-04 | 已定义（多重性待确认） |
| ScholarAuthorization 0..* → 1 User | 被授权主体 | `0..*` : `1` | M27；SRS §12 | 已定义（业务关联） |
| UserProfile — Scholar | 主页↔档案 | **本 UC 不建立** | 认领不合并主页与学者档案 | 留给 UC-04/22、R5 |
| ClaimApplication/Auth → Authorship / WorkEntity | 成果归属 | **本 UC 不建立** | 批准≠本人确认 | 留给 UC-08/10 |
| AuditEvidence → 被写对象 | 对象引用 | 属性而非关联 | FR14；UC-22 同处理 | 已定义（属性） |

## 11. 分析机制汇总

| 机制 | 作用对象 | 业务问题 | 本 UC 约束 | 未决定/后续 |
|---|---|---|---|---|
| 服务端强制校验 | Coordinator、User | 无审核权限者操作、停用账号 | 每次审核与查询都校验身份、账号状态、权限；入口隐藏不代替拒绝 | 权限划分粒度（C-01） |
| 状态与版本守卫 | Coordinator、ClaimApplication | 覆盖他人决定、审核已过期申请 | 只有 `待审核/待补充` 且版本未变才可决定 | 版本机制实现（后续阶段） |
| 冲突守卫 | Coordinator、Auth、Scholar | 有既有管理者时批准导致权利冲突 | 无未决冲突才批准；冲突→记录争议 | 冲突判定阈值/证据充分性（C-02/C-04） |
| 对象级授权范围 | Auth、Scholar | 授权被扩大为“全平台/全部成果” | 授权范围仅目标 Scholar/门户 | 并列管理者（C-04） |
| 竞争保留原授权 | Auth、Coordinator | 竞争剥夺既有管理者 | 只读读取；不撤销/修改/转移 | 转移与申诉程序（C-05） |
| 不建第二主页 | 全体 | 批准生成/合并主页 | 无 UserProfile 消息 | — |
| 不确认 Authorship | 全体 | 批准被当成本人确认 | 无 Authorship/WorkEntity 消息；M34 明确声明 | 本人确认属 UC-08/10 |
| 决定留痕与前后值 | Audit、Coordinator | 决定不可追溯 | 每条改变状态的路径都写审计；无审计不报生效 | 审计保留期限/遮蔽（D-09） |
| 私有材料可见范围 | ReviewEvidence、UI | 材料泄漏 | 仅本人与有权审核者；审核视图不外泄 | 留存/脱敏细则（D-09） |
| 失败不虚报、结果可分 | Coordinator、UI | 授权失败被显示为通过 | 通过/驳回/待补充/争议/失败/无权/状态已变化分开 | 具体文案未冻结 |
| 未定义项不伪造 | 全体 | 自造角色层级/SLA/通知/转移 | 不建相应类与规则 | C-01/C-03/C-05/C-11 |

## 12. 与 08 的待确认对应

C-01（审核权限划分）、C-02（证据最低集合）、C-03（SLA）、C-04（并列管理者）、C-05（争议结案/授权转移/申诉）、C-06（待补充能否直接驳回/争议）、C-07（补证重提口径）、C-08（审计类命名，D-13）、C-09（控制类命名/拆分，B-09/D-11）、C-10（状态名口径，B-11）、C-11（通知）、C-12（授权失败重试/审计失败回滚）——详见[08-自检与待评审](08-self-check.md)。
