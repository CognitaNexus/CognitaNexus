# UC-17 Boundary / Control / Entity 识别

**版本：** V1.0（2026-10-21）
**方法：** 教材 §5.4.2：从本 UC 的事件流与顺序图消息识别 Boundary/Control/Entity；**不照抄**全局 BCE，也不把全局图当作反向答案。
**只读比对：** [`DGM-CLASS-01-analysis.puml`](../../../uml/analysis/DGM-CLASS-01-analysis.puml)、[`01-BCE分析类模型.md`](../01-BCE分析类模型.md)、[`DGM-USECASE-06-admin.puml`](../../../uml/admin/DGM-USECASE-06-admin.puml)、候选类登记表 [`analysis-class-registry.md`](../automation/analysis-class-registry.md)。

## 1. 识别原则

1. 每个外部交互必须有 Boundary 承接；本 UC 只有**一个**外部入口（FR14“统一入口”），因此只有一个 Boundary。
2. 控制类只承接“跨对象守卫与聚合”，且**不承载**任何属主 UC 的处置判定。
3. 实体必须是**持久业务事实**且在本 UC 顺序图中有消息；仅出现在事件描述里、没有消息寻址的概念不建类。
4. 六类待办来源对象只保留“承载待处理状态/计数”的最小集合；本 UC 对它们**只有读消息**，没有写消息。
5. 若一个候选类的作用只是“把各 UC 的待办装进一个容器”，它就是把权限与流程集中化的通用工作流实体，按 FR14 拒绝。

## 2. 候选分析类

| 编号 | 类 | 类型 | 参与事件 | 职责摘要 | 为什么必须是独立类 |
|---|---|---|---|---|---|
| C1 | `AdminConsole` | Boundary | E1–E3、E10–E13、E18、A1–A3、A11 | 接收入口请求、待办浏览、待办项选择（导航）、审计查询条件；显示入口与范围、待办视图与计数、审计结果、拒绝/越权/空结果/失败/遮蔽/留痕不完整 | 任务/候选/申请/授权/关系/留痕/材料是业务实体，不承担输入与展示；`AuditCoordinator` 是内部协调者，不直接面向参与者。FR14 要求“统一入口”，若每类各设边界则入口不统一（09/10 的 `DedupGovernanceUI`/`IdentityVerificationUI` 是**处置**界面，不是本 UC 的入口） |
| C2 | `AuditCoordinator` | Control | E2–E18、A1–A14 | 服务端校验账号状态与具体管理权限；按权限裁剪类别与范围；只读聚合六类待办；导航交接；审计查询守卫（权限/范围/只读）；五要素完整性核对；材料可见性与敏感字段遮蔽；拒绝/越权/查询访问留痕；返回结果 | 守卫同时依赖 `User`（账号与权限）、六类属主对象（状态）、`AuditEvidence`（留痕）、`ReviewEvidence`（可见范围）四组事实；任何单一 Entity 只能自知，Boundary 不能执行跨对象校验。**不并入**各属主协调者：否则处置与审计集中到一个类，违反 FR14“不做通用工作流引擎” |
| C3 | `User` | Entity | E2、E13、A1–A3 | 提供账号标识、账号状态与“具体管理权限”（类别、范围、是否可查审计）事实 | 账号状态与权限属账号事实，不属于任何业务对象；本 UC 只读，不新增属性 |
| C4 | `AuditEvidence` | Entity | E14、E15、A6、A9、A13、E18（查询访问/拒绝/越权） | 承载已发生动作的追加式留痕：事件类型、操作者、对象引用、时间、前后值、理由、结果；覆盖被拒绝与未写入的尝试；本 UC 只读核对，仅追加自身查询访问/拒绝/越权留痕 | 留痕是“已发生的动作”，与“待处理状态”（候选/申请/任务）和“提交材料”（`ReviewEvidence`）都不同；审计查询要求跨对象、跨时间的统一读取面，无法由任一业务实体自带（BR-IMP-10；INV-10） |
| C5 | `ReviewEvidence` | Entity | E16、A5、A14 | 提供证明材料的引用与可见范围；回答“谁是提交者、谁是有权审核者、原件是否可出” | 材料与留痕分离是 FR14-02/NF06 的直接要求；审计结果必须能**引用**材料而不返回原件，若材料并入 `AuditEvidence` 则无法表达“可见范围”与“原件” |
| C6 | `CollectionTask` | Entity | E4、A10、A13（间接） | 提供采集/增量任务状态（待执行/运行中/成功/失败/已停止/重试）、异常移交与待核验计数 | 任务状态是 FR08/§6.2 的持久事实；`AuditEvidence` 只记动作、不承载“当前任务状态”；本 UC 只读，不启动/停止/重试（UC-18/19） |
| C7 | `DedupCandidate` | Entity | E5、A10 | 提供异常治理待处理项（成果候选/字段异常）的状态与计数 | FR09 要求候选与生效实体并存且可区分；本 UC 只读其待处理状态，不判定、不合并（UC-09） |
| C8 | `IdentityCandidate` | Entity | E6、A10 | 提供学者核验待处理项（身份/归属候选与争议）的状态与计数 | FR15 要求身份/归属候选独立于档案与关系；本 UC 只读，不判定（UC-10） |
| C9 | `ClaimApplication` | Entity | E7、A10 | 提供认领审核待处理项（待审核/待补充/争议）的状态与计数 | 申请状态属 §6.3 的独立状态机；本 UC 只读，不审核（UC-05/06） |
| C10 | `ScholarAuthorization` | Entity | E8、A10 | 提供有效授权与竞争认领争议的入口事实（被授权主体、目标、范围、有效性） | “原授权不转移，争议进入受控待办”要求只读授权事实（`AC-IMPL-FR05-03`）；授权不是申请状态、不是留痕 |
| C11 | `Authorship` | Entity | E9、A10 | 提供成果变更/归属待处理项（待核验/争议）的状态与引用 | §6.4 的关系五状态是持久事实；本 UC 只读，不改状态（UC-08/10） |

## 3. 逐类选择理由

### C1 AdminConsole（Boundary）

- **接收：** M1 打开统一后台入口、M25 选择待办项、M29 提交审计查询条件、M49 普通用户请求。
- **显示：** M8 入口与范围、M24 待办视图、M27/M28 导航结果/拒绝执行、M46 审计结果、M44/M45 空/失败、M53/M56 拒绝。
- **不能承担：** 不判断权限、不裁剪类别、不读留痕、不决定材料可见性；入口是否显示账号有权限**不构成**授权（§8）。
- **命名：** 采用 `01-BCE` §2 的 `AdminConsole`（UC-17/18/19）；全局图写作 `AdminUI`，差异见 07 §2（D-11）。

### C2 AuditCoordinator（Control）

- **守卫：** 账号状态（M3/M51）、具体管理权限与类别（M4/M5/M51）、审计查询权限与范围（M31）、审计只读（M43）。
- **聚合：** 六类只读读取（M9/M11/M13/M15/M17/M19）→ 汇总（M21/M22）→ 返回（M23）。
- **核对与遮蔽：** 五要素核对（M36）、留痕不完整异常（M37）、材料可见性（M40）、敏感字段遮蔽（M41/M42）。
- **留痕：** 追加查询访问（M47）、受控拒绝（M54）、受控越权（M57）。
- **不承担：** 不执行任何处置决定（M25–M28 只导航）；不补写/修改/删除留痕（M43）；不改变材料可见范围。
- **与 `AdminCoordinator` 的关系：** 本 UC **不引入**名为 `AdminCoordinator` 的第二个控制类。理由：其全部职责已由 `AuditCoordinator` 覆盖，若另设“管理协调者”承接六类处置，会把 UC-05～UC-10/18/19 的决定收进一个通用协调者，直接违反 FR14“不做通用工作流引擎、不执行属主 UC 决定”。

### C3 User（Entity）

- 只读提供 `accountStatus` 与 `governancePermission`（类别/范围/是否可查审计）。
- **不是** `Scholar`（学术身份档案），**不是** `ScholarAuthorization`（对象级门户授权）；本 UC 不新增 `role` 继承或权限矩阵（B-01）。

### C4 AuditEvidence（Entity）

- **知道：** 事件类型（批准/拒绝/合并/拆分/授权/越权/查询访问等）、操作者（账号或“系统”）、对象引用集合、时间、前后值、理由、结果（已发生/被拒绝/未写入）。
- **不做：** 不承载当前业务状态（由各属主对象承载）、不承载材料原件（`ReviewEvidence`）、不作为权限判定依据（事后记录≠事前授权，同 UC-09 C9 结论）。
- **与全局 `Evidence` 的差异：** 全局一名多义（材料＋留痕），本 UC 与 UC-05/06/08/09/10/22 一致使用 `AuditEvidence`（D-13）。

### C5 ReviewEvidence（Entity）

- 提供材料引用、提交者、可见范围（提交者＋有权审核者）、是否含私人信息。
- 审计查询只读它判断“原件能否出”；**不**把材料内容写入留痕或安全审计（NF03/NF06）。

### C6–C11 六类只读待办来源

| 类 | 只读事实 | 本 UC 明确不做 |
|---|---|---|
| `CollectionTask` | 任务状态、失败点、异常移交、待核验计数 | 不创建/启动/停止/重试，不改来源配置（UC-18/19） |
| `DedupCandidate` | 候选/异常处置项状态与计数 | 不判定、不合并/拆分、不改字段（UC-09） |
| `IdentityCandidate` | 身份/归属候选与争议状态、计数 | 不判定、不合并/拆分、不改归属（UC-10） |
| `ClaimApplication` | 待审核/待补充/争议状态与计数 | 不审核、不驳回、不要求补证（UC-06） |
| `ScholarAuthorization` | 有效授权、被授权主体、目标与范围、争议入口事实 | 不授予/转移/撤销（UC-06） |
| `Authorship` | 关系待核验/争议状态与引用 | 不改关系状态、不写 `本人确认`（UC-08/10） |

## 4. 被拒绝的候选类

| 候选 | 拒绝理由 | 依据 |
|---|---|---|
| `WorkflowEngine` / `ApprovalChain` / `ApprovalStep` / `RoutingRule` / `Assignee` / `SlaTimer` | FR14 明确“不做通用工作流引擎”；基线无审批链、指派、时限与责任人定义 | FR14；B-01/B-06 |
| `TodoItem` / `PendingTodo` / `AdminTodo` / `UnifiedQueue`（通用待办实体） | 待办在本 UC 是**只读派生投影**；建实体就等于引入“受理/处理中/办结/超期”状态与处理人，属通用工作流；与 `AC-IMPL-FR14-01/02` 无关 | FR14；BR-UC17-04；UC-05/06/09/10 均拒绝同类候选 |
| `PendingCount` / `TodoSnapshot` | 计数与快照是查询结果的分析态，不是持久业务事实；刷新口径未冻结 | B-02/B-06 |
| `AdminCoordinator`（第二个控制类） | 职责与 `AuditCoordinator` 重叠且会集中处置权；见 §3 C2 | FR14；BR-UC17-05 |
| `DedupGovernanceUI` / `IdentityVerificationUI` / `ClaimReviewUI` / `ScholarMaintenanceUI` / `AchievementCorrectionUI` / `CollectionConsole` | 它们是**处置**边界，属 UC-09/10/06/07/08/18/19；本 UC 只提供统一入口与导航 | FR14 统一入口；A11 |
| `AuditorRole` / `SuperAdmin` / `RoleHierarchy` / `PermissionMatrix` | 基线只区分“是否具备该具体管理权限”；角色继承与权限矩阵未冻结 | FR14；NF03；§8；B-01 |
| `AuditLog`（独立于 `AuditEvidence` 的第二套日志） | 同义重复；留痕唯一由 `AuditEvidence` 表达 | BR-IMP-10；INV-10 |
| `AuditQuery` / `AuditReport` / `AuditExport` / `AuditResultView` | 查询条件、结果与导出是交互态/展示投影，不是业务事实 | §5.4.2；B-05 |
| `FieldMaskPolicy` / `SensitiveFieldCatalog` / `RetentionPolicy` | 遮蔽清单与保留期限未冻结（D-09）；写成实体等于把候选策略当批准规则 | D-09；B-03/B-04 |
| `EvidenceFile` / `Attachment` / `StorageRef` | 材料存储形态属实现设计 | 教材 §5.4.2 |
| `Session` / `CredentialContext` | 已认证请求上下文；基线未定义会话对象 | B-09（沿用其他 UC） |
| `Notification` / `Reminder` | 通知渠道与提醒时限未定义 | B-02 |
| `SourceRecord` / `WorkEntity` / `WorkVersion` / `CleanRecord` / `AttributionCandidate` / `CorrectionRequest` / `ScholarChangeRequest` / `Scholar` / `UserProfile` | 本 UC 顺序图中没有对它们的消息：采集/治理/判定/变更/档案内容属属主 UC；审计对象以引用表达 | 03 §1 原则 3；B-07 |
| `MappingDictionary`（机构/领域分类） | 基线只有“分类”入口字样与字典概念，无独立业务对象与 AC | FR14；B-08 |
| 独立 `AuditIndex` / `LogStore` / `QueryPlan` | 检索与存储实现属后续阶段 | 教材 §5.4.2 |

## 5. 对象边界结论

1. **统一入口只有一个 Boundary，统一权限不是“一个权限”。** `AdminConsole` 承接全部入口交互，授权由 `AuditCoordinator` 逐类别校验（FR14；§8；M4/M5/M31）。
2. **待办不是实体。** 待办视图 = 六类属主对象状态的只读派生投影；不定义状态、不持久化处理进度、不建通用工作流（BR-UC17-04）。
3. **决定不在本 UC。** 本 UC 只有导航交接；批准/驳回/合并/拆分/授权/改归属/重试采集均属属主 UC（BR-UC17-05；A11）。
4. **三种证据面分离。** `CollectionTask`/`DedupCandidate`/`IdentityCandidate`/`ClaimApplication`/`ScholarAuthorization`/`Authorship`（当前业务状态）≠ `ReviewEvidence`（提交材料＋可见范围）≠ `AuditEvidence`（已发生动作留痕）（FR14-02；BR-IMP-10/11）。
5. **审计是只读核对，不是修复。** 缺要素标异常，不补写；留痕不可覆盖/删除（INV-10；A6/A9）。
6. **证明原件不外泄。** 审计结果只含引用与可见范围；普通用户无入口、不可见原件（`AC-IMPL-FR14-02`；NF06；M38–M40）。

## 6. 识别结论

- Boundary：`AdminConsole`（1）
- Control：`AuditCoordinator`（1）
- Entity：`User`、`AuditEvidence`、`ReviewEvidence`、`CollectionTask`、`DedupCandidate`、`IdentityCandidate`、`ClaimApplication`、`ScholarAuthorization`、`Authorship`（9）

合计 **11 个参与类**，全部在 04 顺序图中有消息证明；`PendingTodo` 系、工作流引擎系、第二控制类 `AdminCoordinator`、角色矩阵、保留/遮蔽策略类均被拒绝（§4）。该集合将在 04/05 接受“每条消息都有依据、每个类都有消息”的双向检验。
