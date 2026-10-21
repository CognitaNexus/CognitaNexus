# UC-17 VOPC（参与类类图）与关系依据

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-VOPC-12-admin-audit.puml`](diagrams/DGM-VOPC-12-admin-audit.puml)
**导出：** [SVG](diagrams/generated/DGM-VOPC-12-admin-audit.svg) · [PNG](diagrams/generated/DGM-VOPC-12-admin-audit.png)
**方法：** 教材 §5.4.4：VOPC 只来自本用例实现的顺序图对象，关系来自消息和业务约束；不是全局 BCE。

## 1. 参与类集合

VOPC 只包含以下 11 个类：

| 类 | 类型 | 来自顺序图对象 | 事件流依据 |
|---|---|---|---|
| `AdminConsole` | Boundary | M1、M25、M29、M49（接收）；M8、M24、M27、M28、M44、M45、M46、M48、M53、M56（返回） | E1、E3、E10–E12、E18；A1–A3、A7、A8、A11 |
| `AuditCoordinator` | Control | M2–M7、M9、M11、M13、M15、M17、M19、M21–M23、M30–M47、M50–M52、M54–M55、M57 | E2–E18；A1–A14 |
| `User` | Entity | M3、M4、M31、M51（接收） | E2、E13；A1–A3 |
| `AuditEvidence` | Entity | M34（接收，只读）、M35（应答）、M47、M54、M57（接收） | E14、E18；A1、A2、A9、A13 |
| `ReviewEvidence` | Entity | M38（接收，只读）、M39（应答） | E16、E18；A5、A14 |
| `CollectionTask` | Entity | M9（接收，只读）、M10（应答） | E4 |
| `DedupCandidate` | Entity | M11（接收，只读）、M12（应答） | E5 |
| `IdentityCandidate` | Entity | M13（接收，只读）、M14（应答） | E6 |
| `ClaimApplication` | Entity | M15（接收，只读）、M16（应答） | E7 |
| `ScholarAuthorization` | Entity | M17（接收，只读）、M18（应答） | E8 |
| `Authorship` | Entity | M19（接收，只读）、M20（应答） | E9 |

两位参与者（管理员、普通用户／未授权请求者）不进入 VOPC：参与者不是系统内部类（教材 §5.4.4）。本 UC 无“系统参与者”边界类；系统职责由 `AuditCoordinator` 与各属主 UC 的协调者实现。

## 2. 关系与来源

| 关系 | 类型 | 来自消息/约束 | 方向/角色 | 多重性 | 说明 |
|---|---|---|---|---|---|
| `AdminConsole --> AuditCoordinator` | 单向关联/消息关系 | M2 请求；M7/M23 返回；M30 查询；M44/M45/M46 返回；M50 转交 | UI 请求；Coordinator 返回 | 不定义持久多重性 | UI 无持久业务属性；按消息方向表达一次用例会话的协作 |
| `AuditCoordinator --> User` | 单向关联/只读校验关系 | M3/M4/M31/M51 | 索取账号状态与具体管理权限事实 | 不定义持久多重性 | 协调器不是账号容器；不新增账号属性、不定义角色继承（B-01） |
| `AuditCoordinator ..> CollectionTask` | 单向**只读**依赖 | M9/M10 | 读取任务状态、失败点、待核验计数 | 不定义持久多重性 | 虚线表示只读：不启动/停止/重试、不改来源配置（UC-18/19） |
| `AuditCoordinator ..> DedupCandidate` | 单向**只读**依赖 | M11/M12 | 读取治理待处理项状态与计数 | 不定义持久多重性 | 不判定、不合并/拆分、不改字段（UC-09） |
| `AuditCoordinator ..> IdentityCandidate` | 单向**只读**依赖 | M13/M14 | 读取身份/归属候选与争议状态 | 不定义持久多重性 | 不判定、不改归属（UC-10） |
| `AuditCoordinator ..> ClaimApplication` | 单向**只读**依赖 | M15/M16 | 读取申请状态与计数 | 不定义持久多重性 | 不审核、不驳回（UC-05/06） |
| `AuditCoordinator ..> ScholarAuthorization` | 单向**只读**依赖 | M17/M18 | 读取有效授权与争议入口事实 | 不定义持久多重性 | 不授予/转移/撤销（UC-06；`AC-IMPL-FR05-03`） |
| `AuditCoordinator ..> Authorship` | 单向**只读**依赖 | M19/M20 | 读取关系待核验/争议状态 | 不定义持久多重性 | 不改关系状态、不写 `本人确认`（UC-08/10） |
| `AuditCoordinator --> AuditEvidence` | 单向关联/读-追加关系 | M34 只读读取；M47/M54/M57 追加查询访问/拒绝/越权留痕 | 读取留痕并追加受控访问留痕 | 不定义持久多重性 | 不修改/删除留痕（M43；INV-10）；不补写关键事件（A6） |
| `AuditCoordinator ..> ReviewEvidence` | 单向**只读**依赖 | M38/M39 | 读取材料引用与可见范围 | 不定义持久多重性 | 虚线表示只读：不返回原件、不改变可见范围 |
| `AuditEvidence "0..*" --> "0..1" User` | 业务关联 | BR-IMP-10（操作者）；M34/M54/M57；SPEC §3 执行者类型 | 操作者 | `0..*` : `0..1` | 一条留痕有一个操作者身份；系统自动动作无账号（UC-09/10 的“执行者类型=系统”同源） |

### 2.1 为什么控制/边界到实体的关联不标持久多重性

`AdminConsole`/`AuditCoordinator` 到实体的关联按**消息方向**表达协作，不是长期业务对象链接。为它们标 `1..*` 会把一次用例的协调关系误解为持久聚合、甚至误读为“后台持有全部待办”。因此本 VOPC 只对唯一一条业务事实关联给出多重性：`AuditEvidence 0..* : 0..1 User`。

### 2.2 为什么留痕→操作者标 `0..1` 而不是 `1`

- BR-IMP-10 要求“受保护写入有身份、理由、前后值和时间”，但 SPEC §3 与 UC-09/10 的既有分析把执行者区分为**系统自动动作**与**人工动作**；系统动作没有账号主体。
- 若标 `1`，则必须为“系统”虚构一个账号，违反 User≠系统 的既有结论；若完全不画，则无法表达“管理员批准关系修正”的可定位性（`AC-IMPL-FR14-02` 的核心）。
- 因此保留该边并标 `0..*` : `0..1`，同时在 `AuditEvidence.actorType` 中记录“账号/系统”。

### 2.3 为什么若干事实写成属性而不是实体关联

| 事实 | 表达方式 | 理由 |
|---|---|---|
| 留痕指向的业务对象（申请/授权/关系/候选/任务） | `AuditEvidence.targetRefs`＋`targetType`＋前后值 | 审计必须覆盖被拒绝、未写入、已删除引用的对象；本 UC 没有 `AuditEvidence → 各对象` 的消息，画边会与各属主 UC 的关系重复（与 UC-09/10 同一取舍） |
| 待办视图（类别、计数、属主 UC、对象引用） | `AuditCoordinator` 的分析状态 `pendingView` | 待办不是持久业务事实；建成实体会引入工作流状态（BR-UC17-04；03 §4） |
| 材料的可见范围 | `ReviewEvidence.visibilityScope`（沿用 UC-05/06/08） | M38 只读；审计结果只携带范围声明 |
| 权限事实 | `User.governancePermissions`（类别/范围/可查审计） | 权限矩阵与角色继承未冻结（B-01），不画角色/权限实体 |
| `ScholarAuthorization` 的业务关联 | 由 UC-06/08 定义（`0..*:1 User`；`→Scholar` 多重性未定，SPEC ID-12） | 本 UC 只读，重复画边会暗示本 UC 参与授权建立/变更 |
| 六类待办来源彼此的关系（申请→授权、关系→成果等） | 不画 | 这些关系已由 UC-05/06/08/09/10 定义；本 UC 不增加新的业务约束 |

## 3. 从顺序图到 VOPC 的对应

| 顺序图对象 | VOPC 类 | 保留/删除理由 |
|---|---|---|
| 管理员（Actor） | 不保留 | 参与者不是系统类；发起 M1/M25/M29 |
| 普通用户／未授权请求者（Actor） | 不保留 | 参与者不是系统类；只触发 M49 与接收 M53/M56 |
| AdminConsole | 保留 | 接收 M1/M25/M29/M49，返回 M8/M24/M27/M28/M44/M45/M46/M48/M53/M56 |
| AuditCoordinator | 保留 | 承担 M2–M7、M9–M23、M30–M52、M54–M55、M57 的守卫、聚合、核对与留痕 |
| User | 保留 | 接收 M3/M4/M31/M51 |
| AuditEvidence | 保留 | 接收 M34/M47/M54/M57，应答 M35 |
| ReviewEvidence | 保留 | 接收 M38，应答 M39 |
| CollectionTask | 保留 | 接收 M9，应答 M10 |
| DedupCandidate | 保留 | 接收 M11，应答 M12 |
| IdentityCandidate | 保留 | 接收 M13，应答 M14 |
| ClaimApplication | 保留 | 接收 M15，应答 M16 |
| ScholarAuthorization | 保留 | 接收 M17，应答 M18 |
| Authorship | 保留 | 接收 M19，应答 M20 |
| 无其他对象 | — | 没有 `PendingTodo`/`WorkflowEngine`/`ApprovalStep`/`Assignee`/`SlaTimer`/`CorrectionRequest`/`ScholarChangeRequest`/`SourceRecord`/`WorkEntity`/`CleanRecord`/`AttributionCandidate`/`Scholar`/`UserProfile`/`Session` 的消息 |

## 4. 排除项与理由

- `PendingTodo` / `TodoItem` / `UnifiedQueue` / `WorkItem`：**通用工作流实体**，会把“受理事/处理中/办结/超期/处理人”引入分析模型，直接违反 FR14“不做通用工作流引擎”；本 UC 的待办是只读派生投影（03 §4；BR-UC17-04）。
- `WorkflowEngine` / `ApprovalChain` / `ApprovalStep` / `RoutingRule` / `Assignee` / `SlaTimer`：基线无审批链、指派与时限定义；`AC-IMPL-FR14-01/02` 亦不要求（FR14；B-01/B-06）。
- `AdminCoordinator`（第二个控制类）：职责与 `AuditCoordinator` 重叠且会集中六类处置；本 UC 只保留一个控制类（03 §3 C2）。
- `DedupGovernanceUI` / `IdentityVerificationUI` / `ClaimReviewUI` / `ScholarMaintenanceUI` / `AchievementCorrectionUI` / `CollectionConsole`：属主 UC 的**处置**边界，不在本 UC 的导航之外出现（A11）。
- `ReviewEvidence` 之外的 `Evidence`/`AuditLog`：一名多义或同义重复（D-13；03 §4）。
- `CorrectionRequest` / `ScholarChangeRequest`：成果变更与档案待审变更的申请对象属 UC-07/08；本 UC 只导航、不读取其状态（B-07）。
- `SourceRecord` / `WorkEntity` / `WorkVersion` / `CleanRecord` / `Scholar` / `AttributionCandidate` / `UserProfile`：本 UC 无消息；审计对象以引用表达。
- `MappingDictionary`（机构/领域分类）：基线与 SPEC 只有字典概念，无独立业务对象与 AC（B-08）。
- `SensitiveFieldCatalog` / `FieldMaskPolicy` / `RetentionPolicy` / `AuditReport` / `AuditExport`：遮蔽清单、保留期限与导出范围未冻结（D-09；B-03/B-04/B-05），且导出是展示/交付形态。
- `AuditIndex` / `LogStore` / `QueryPlan` / `Cache`：检索与存储实现属后续阶段。
- `Session` / `CredentialContext`：已认证请求上下文；基线未定义会话对象（B-09）。
- 展示投影/DTO（`TodoView`/`AuditResultView`）：交互态，不是业务事实。

## 5. VOPC 完整性检查

| 检查 | 结果 |
|---|---|
| 只包含 UC-17 参与类 | 是，11 个 |
| 每个类有事件流/消息依据 | 是，见表 1/表 3 |
| 每条关系可追溯到消息或业务约束 | 是，见表 2 |
| 不把 Actor 画成系统类 | 是；两位参与者不进入 VOPC |
| 不用全局 BCE 冒充 VOPC | 是；本图独立于 `DGM-CLASS-01-analysis.puml` |
| 关系多重性是否有依据 | 仅 1 条业务关联有依据（§2.2）；其余按消息方向，不定义持久多重性（§2.1） |
| 是否出现通用待办/工作流实体 | 否：无 `PendingTodo`/`WorkflowEngine`/`Assignee`/`SlaTimer` |
| 是否对属主对象有写边 | 否：六类来源全部为虚线只读依赖（M9/M11/M13/M15/M17/M19） |
| 是否执行任何处置决定 | 否：无“批准/驳回/合并/拆分/授权/改归属/重试采集”消息或边 |
| 是否把 `ReviewEvidence` 与 `AuditEvidence` 混为一类 | 否：材料（可见范围）与留痕（动作）分离 |
| 是否把 `User` 与 `Scholar`/`ScholarAuthorization` 混为一类 | 否：账号/权限、学术档案、对象级授权三者分离 |
| 是否把审计写成可写/可修 | 否：`M34` 只读，`M43` 拒绝写回，`M47/M54/M57` 仅追加受控访问留痕 |
| 是否引入设计技术（表/接口/队列/索引/缓存） | 否 |

## 6. 与全局图的关系

本 VOPC 是 UC-17 的局部静态视图，不是全系统类图。与 `DGM-CLASS-01-analysis.puml` 的异同、命名合并建议见[07-与全局模型差异](07-global-model-diff.md)。R5 统一分析类时应以本 VOPC 为输入之一，而不是用全局图反推本 UC 的参与类；特别是**不应**据全局 `AdminUI → CollectCtl/GovernCtl/AuditCtl` 的边把本 UC 扩成处置协调者。
