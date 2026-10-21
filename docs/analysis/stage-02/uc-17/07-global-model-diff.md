# UC-17 分析与现有全局模型差异

**版本：** V1.0（2026-10-21）
**比较对象：** [`DGM-CLASS-01-analysis.puml`](../../../uml/analysis/DGM-CLASS-01-analysis.puml)、[`01-BCE分析类模型.md`](../01-BCE分析类模型.md)、[`DGM-USECASE-06-admin.puml`](../../../uml/admin/DGM-USECASE-06-admin.puml)、[`DGM-SEQ-01-collection.puml`](../../../uml/analysis/DGM-SEQ-01-collection.puml)、[`collection-task-state.puml`](../../../uml/collection/collection-task-state.puml)（`DGM-STATE-01` 采集任务状态）、UC-05/06/09/10 已交付分析包、候选类登记表 [`analysis-class-registry.md`](../automation/analysis-class-registry.md)、待决清单 [`unresolved-decisions.md`](../automation/unresolved-decisions.md)、追踪表 [`03-AC-UC-分析模型追踪.md`](../03-AC-UC-分析模型追踪.md)。
**原则：** 全局图只作比对材料，不作为本 UC 识别类的反向答案；本轮不重构全局图、不修改共享登记表、不修改他人 UML 资产。

## 1. 已存在且一致

| UC-17 类/概念 | 全局对应 | 一致点 | 证据 |
|---|---|---|---|
| `AdminConsole` | `01-BCE` §2 `AdminConsole`（UC-17/18/19，“管理待办、来源、任务、审计查询”；“不负责：绕过具体权限”） | 统一后台入口；入口不等于授权 | FR14；§8；M4/M5 |
| `AuditCoordinator` | `01-BCE` §3 `AuditCoordinator`（“受保护写入、审核决定、前后值/理由/时间”；关键守卫“普通用户不可看私有证明”） | 审计查询面；五要素；私有证明不可见 | FR14；`AC-IMPL-FR14-02`；M34–M40 |
| `AuditEvidence` | 全局 `Evidence`（“+操作者/理由/前后值()”；`AuditCtl ..> Evidence`）；`01-BCE` §4 `ReviewEvidence` 亦被描述为“可追溯决定” | 受保护写入留痕、可追溯 | BR-IMP-10；INV-10；M34/M36 |
| `ReviewEvidence` | 全局 `Evidence`；`01-BCE` §4 `ReviewEvidence` | 审核材料需留有可追溯引用 | BR-IMP-11；NF06；M38 |
| `CollectionTask` | 全局 `Task`（“+任务状态()/+检查点()”；`CollectCtl ..> Task`）；`01-BCE` §4 `CollectionTask` | 任务状态、检查点、异常移交；任务成功≠治理通过 | §6.2；FR08；M9/M10 |
| `User` | 全局 `User`（“+账号状态()/+对象授权校验()”） | 账号状态与服务端校验 | NF03；M3/M4 |
| `Authorship` | 全局 `Authorship`（“+角色/顺序/证据()/+独立关系状态()”） | 关系状态独立于成果与结论 | §6.4；M19/M20 |
| `ClaimApplication` / `ScholarAuthorization` | 全局 `Claim` / `Auth`；`01-BCE` §4 `ClaimApplication`/`ScholarAuthorization` | 申请状态与对象级授权分离 | §6.3；FR05；M15–M18 |
| “待办与审计统一入口、权限不统一” | `DGM-USECASE-06`：`有权管理员 -- UC-17/UC-06/UC-09/UC-10`，注释“角色关系表示职责，不用继承表达账号状态” | 后台入口按职责连接，不表达账号继承 | FR14；§8 |
| “普通用户不可看私有证明” | `01-BCE` §3 `AuditCoordinator` 守卫 | 一致 | `AC-IMPL-FR14-02`；NF06；M38–M40 |
| “历史审计保留可追踪前后值” | SPEC §3/§6.4；`01-BCE` §5 第 6 条 | 一致 | INV-10；BR-382；M35 |

## 2. 需要更名/别名统一

| 本 UC 名称 | 全局名称 | 差异 | R5 建议 |
|---|---|---|---|
| `AdminConsole` | `DGM-CLASS-01`：`AdminUI`（`..> CollectCtl/GovernCtl/AuditCtl` 三条）；`01-BCE` §2：`AdminConsole`（UC-17/18/19） | **两套写法并存**：类图用 `AdminUI` 且同时连治理与采集协调者；01 文档用 `AdminConsole` 并限定为入口 | R5 统一为 `AdminConsole`，并在边界职责中写明“入口与导航，不执行处置” |
| `AuditCoordinator` | `DGM-CLASS-01`：`AuditCtl`；`01-BCE` §3：`AuditCoordinator` | 命名差异（D-11 类） | R5 统一全名；避免与 `AuditCtl` 两套并存 |
| `AuditEvidence` | 全局 `Evidence` | 命名与语义差异（D-13）：`Evidence` 一名多义（材料＋留痕） | R5 统一为 `AuditEvidence`（动作留痕）＋`ReviewEvidence`（材料） |
| `ReviewEvidence` | 全局 `Evidence`；`01-BCE` §4 描述为“审核/修正/授权的可追溯决定” | **语义差异：** 全局把材料和留痕混为一类 | R5 拆分为 `ReviewEvidence`（材料＋可见范围）与 `AuditEvidence`（动作留痕），并修正 `01-BCE` §4 描述 |
| `CollectionTask` / `ClaimApplication` / `ScholarAuthorization` | 全局 `Task` / `Claim` / `Auth` | 命名差异（D-11） | R5 统一 |

## 3. 职责差异/需要补充

| 项目 | 本 UC 证据 | 全局现状 | 差异/影响 | R5 处理 |
|---|---|---|---|---|
| 后台入口的“只读聚合”职责 | M9–M24；BR-UC17-04 | `01-BCE` §2 只写“管理待办、来源、任务、审计查询”，未说明待办是派生只读视图还是可写队列 | 可能被实现为通用工单/工作流 | 在 `AdminConsole`/`AuditCoordinator` 职责中写明“只读派生、不推进状态” |
| 六类待办的来源对象 | M9/M11/M13/M15/M17/M19 | 全局 `AdminUI ..> CollectCtl/GovernCtl/AuditCtl` 只表达“连到协调者”，不表达待办来源 | 待办可能被实现为跨 UC 直接读写 | 明确“只读六类属主对象状态；写操作回属主 UC” |
| 不执行处置（导航边界） | M25–M28；BR-UC17-05 | 全局边界与三个协调者相连，易被读成“后台可统一处置” | 处置入口与决定可能被集中到后台 | 在职责中加“仅导航；批准/驳回/合并/拆分/授权/重试属属主 UC” |
| 具体管理权限的类别级裁剪 | M4/M5/M31；A3 | `01-BCE` §2 只写“不负责绕过具体权限”，未表达“类别级可见性” | 可能出现“是管理员即可看全部”的实现 | 在 `User` 职责写“类别/范围级权限事实”；在协调者写“按类别裁剪” |
| 审计五要素核对与“留痕不完整” | M36/M37；A6 | 全局 `AuditCtl` 只写“决定/前后值留痕”，无“核对缺要素”职责 | 审计可能只展示不核对，缺要素被静默跳过 | 在 `AuditCoordinator` 补“核对五要素并暴露缺口，不补写” |
| 审计只读、留痕不可改 | M43；A9 | 全局无“审计不可写”说明 | 可能提供“编辑/删除留痕”入口 | 在协调者与 `AuditEvidence` 职责中写明“追加式、只读查询” |
| 材料原件隔离 | M38–M40；A5 | `01-BCE` §3 守卫已写“普通用户不可看私有证明”，但未说明“审计结果只含引用与可见范围” | 审计可能直接回显材料原文 | 在协调者职责补“原件仅有权审核者按需可见，审计只给引用与范围” |
| 敏感字段遮蔽 | M41/M42；A14 | `01-BCE` 无 | 留痕可能含凭据/私人联系方式 | 补“安全审计不含秘密”（已由 NF03 支撑） |
| 待核验计数独立 | M9/M10；BR-UC17-13 | `01-BCE` `CollectionTask` 未提“待核验计数独立于成功” | 任务成功可能被展示为治理完成 | 在 `CollectionTask`/`AdminConsole` 职责补“异常与待核验计数独立” |
| 查询结果范围裁剪声明 | M32；A4 | 全局无 | 管理员可能看到越权范围且无提示 | 在协调者职责补“裁剪并显式声明” |
| 保留期限/遮蔽清单未冻结 | M33；A12 | 全局无；D-09 已登记 | 可能被实现为到期删除或无限制保留 | 分析层不建策略类，留 R5/需求确认 |

## 4. 关系差异

### 4.1 `AdminUI ..> CollectCtl / GovernCtl / AuditCtl`（全局图）

- 全局把后台边界**同时**连到采集、治理、审计三个协调者，图上无法区分“导航”与“执行”。
- 本 UC 的 `AdminConsole` 只连一个协调者 `AuditCoordinator`；对六类属主对象**只有只读依赖**，处置通过导航回到属主 UC。
- **R5 建议：** 保留 `AdminConsole → AuditCoordinator`；把 `AdminUI → CollectCtl/GovernCtl` 改为“导航关系”或在注记中说明“入口不执行处置”，避免把后台画成统一处置中心。

### 4.2 `AuditCtl ..> Evidence`（全局图）

- 本 UC 需要**读**留痕（M34）并**追加**自身受控访问/拒绝/越权留痕（M47/M54/M57），因此保留关联；但必须注明“不修改/删除既有留痕”。
- **R5 建议：** 边注明读＋受控追加，并在 `Evidence` 职责写明追加式。

### 4.3 `Evidence ..> Claim` / `Evidence ..> Authorship`（全局图）

- 全局以关联表达“留痕指向对象”；本 UC 用 `AuditEvidence.targetRefs`＋`targetType` 属性表达，理由是审计必须覆盖被拒绝、未写入与已删除引用的对象，且对象类型跨 UC（与 UC-09/10 同一取舍）。
- **R5 建议：** 统一为“属性引用”，或在全局图注记“审计可指向多类对象”。

### 4.4 新增业务关联（全局无）

- `AuditEvidence 0..* : 0..1 User`（操作者；系统动作无账号）。
- **R5 建议：** 新增该边并保留 `0..1`；若评审要求“凡留痕必有操作者账号”，需先修正“系统自动动作”的执行者类型（SPEC §3；UC-09/10），否则会为系统虚构账号。

### 4.5 未新增的边（有意省略）

- `AdminConsole → CollectionTask/DedupCandidate/...`：待办来源由 `AuditCoordinator` 只读访问，边界不直接连业务实体（与 UC-10 的 UI→Ctl 结构一致）。
- 六类属主对象之间的既有关系（申请→授权、候选→学者/关系、关系→成果、任务→来源）不重复画。

## 5. 与现有用例图/活动图/状态图的一致性

| 全局资产 | 与本 UC 的一致点 | 需登记/需修正的差异 |
|---|---|---|
| [`DGM-USECASE-06`](../../../uml/admin/DGM-USECASE-06-admin.puml) | `有权管理员` 连接 UC-17/UC-06/UC-09/UC-10；注释“角色关系表示职责，不用继承表达账号状态”与本 UC“不引入角色继承”一致 | 该图只列四个 UC，未画 UC-05/07/08/18/19；本 UC 的六类待办依赖这些 UC 的产物。**R5 建议：** 在用例图或注记中补全“后台待办聚合来源”，否则易被理解为后台只服务四个 UC |
| [`DGM-SEQ-01`](../../../uml/analysis/DGM-SEQ-01-collection.puml)（采集入库与异常移交）及 [`DGM-STATE-01`](../../../uml/collection/collection-task-state.puml)（采集任务六状态） | 任务状态集合、异常移交与本 UC M9/M10 读取一致；`DGM-STATE-01` 图例已写明“成功不等于全部公开；单列待核验、异常及未公开数量”“交接失败不算完成”，与本 UC BR-UC17-13 完全一致；“重试/停止/新建关联任务”的转移与本 UC“不启动/停止/重试，仅展示失败点与恢复入口”也不冲突 | **无冲突，无需修改。** 本 UC 不新增任务状态、不画状态机，也不覆盖任何转移 |
| [`DGM-STATE-02`](../../../uml/claim/DGM-STATE-02-claim-request.puml) / [`DGM-STATE-05`](../../../uml/governance/scholar-achievement-relation-state.puml) | 申请状态与关系五状态与本 UC M15/M19 只读一致 | 本 UC 不新增“待办状态”，不覆盖这些状态机 |
| [`01-BCE分析类模型`](../01-BCE分析类模型.md) §5 第 6 条 | “审计与授权分离：`ClaimReviewCoordinator` 授予对象权，但不把管理员批准写成本人确认” | 一致；但 §4 对 `ReviewEvidence` 的描述（“可追溯决定”）与 UC-05/06/08/10/17 的“材料＋可见范围”不一致，建议 R5 修正（D-13） |
| UC-05/06/09/10 已交付分析 | 均把“受控待办/待办队列”载体推给 FR14/UC-17（UC-05 B-06；UC-06 C-05；UC-09 §5；UC-10 05 §4） | 本 UC 现在给出结论：**待办是只读派生投影，不建通用工作流实体**。各 UC 的 `PendingTodo`/`ConflictQueue` 拒绝理由因此获得一致口径；无需回改其交付 |

## 6. 追踪文件与登记表（只提建议，不改文件）

| 文件 | 现状 | 建议（由父 Agent 决定） |
|---|---|---|
| [`03-AC-UC-分析模型追踪.md`](../03-AC-UC-分析模型追踪.md) | FR14 两行“分析模型”列只有 `DGM-USECASE-06 / CLASS-01`；UC-17 反向行只有 FR14-01/02、NF03-01 | 为 FR14 两行与 UC-17 反向行增补 `SEQ-16 / VOPC-12`。NF06/NF07 是否增列 UC-17 由父 Agent 决定（本 UC 只提供“原件不外泄/错误可定位”的边界证据，不主张覆盖 UC-03/05/06/22 与 UC-18/19） |
| [`analysis-class-registry.md`](../automation/analysis-class-registry.md) | 有 `AuditEvidence`（首次 UC-22，后续 UC-17）、`ReviewEvidence`、`SourceRecord`、`ClaimApplication`、`ScholarAuthorization`、`Authorship`、`User` 行；无入口/协调者/任务/候选行 | 增量登记 `AdminConsole`（Boundary）、`AuditCoordinator`（Control）；`CollectionTask`/`DedupCandidate`/`IdentityCandidate` 以“只读待办来源”角色登记；`AuditEvidence` 行补“UC-17 只读核对＋受控访问留痕”；`ReviewEvidence` 行补“审计只读引用与可见范围” |
| [`unresolved-decisions.md`](../automation/unresolved-decisions.md) | D-09 已登记“审计保留期限/敏感字段遮蔽范围” | 可评估新增：B-01（管理权限粒度与审计查询权限独立性）、B-02/B-06（待办载体与计数/排序口径）、B-05（查询访问留痕与导出）、B-07（成果变更待办状态来源）、B-08（分类入口范围）。本包不直接修改该文件 |
| 全局 `DGM-CLASS-01-analysis.puml` | `AdminUI`/`AuditCtl`/`Evidence` 命名并存；`AdminUI` 同时连治理与采集 | 按 §2–§4 在 R5 统一；本 UC 不修改全局图 |

## 7. 差异结论

UC-17 分析支持以下全局模型调整建议：

1. **统一后台入口命名与职责**：`AdminConsole`（不是 `AdminUI`）＝入口与导航；`AuditCoordinator`（不是 `AuditCtl`）＝权限裁剪、只读待办聚合、审计核对；
2. **明确后台不执行处置**：全局 `AdminUI → CollectCtl/GovernCtl` 应改为导航语义或加注记，避免把六类处置收进后台；
3. **待办不是实体**：不新增 `PendingTodo`/`WorkflowEngine`/`ApprovalChain`/`Assignee`/`SlaTimer`，待办为六类属主对象状态的只读派生投影（FR14“不做通用工作流引擎”）；
4. **拆分 `Evidence`**：`ReviewEvidence`（材料＋可见范围）与 `AuditEvidence`（动作留痕）分离，并修正 `01-BCE` §4 对 `ReviewEvidence` 的描述（D-13）；
5. **新增操作者关联**：`AuditEvidence 0..* : 0..1 User`，保留“系统动作无账号”的执行者类型；
6. **补齐权限与遮蔽口径**：类别级具体管理权限、审计查询权限独立性、保留期限与遮蔽清单仍待需求确认（B-01/B-03/B-04；D-09）。

本轮**不直接修改** `DGM-CLASS-01-analysis.puml`、`DGM-USECASE-06`、`DGM-SEQ-01`、`DGM-STATE-01/02/05`、`01-BCE分析类模型.md` 与共享追踪/登记文件。
