# UC-18 VOPC（参与类类图）与关系依据

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-VOPC-13-collection.puml`](diagrams/DGM-VOPC-13-collection.puml)
**导出：** [SVG](diagrams/generated/DGM-VOPC-13-collection.svg) · [PNG](diagrams/generated/DGM-VOPC-13-collection.png)
**方法：** 教材 §5.4.4：VOPC 只来自本用例实现的顺序图对象，关系来自消息和业务约束；不是全局 BCE。

## 1. 参与类集合

VOPC 只包含以下 10 个类：

| 类 | 类型 | 来自顺序图对象 | 事件流依据 |
|---|---|---|---|
| `AdminConsole` | Boundary | 接收 M1、M8、M16、M46、M63、M74、M87、M95；返回 M6、M14、M28、M30、M52、M61、M68、M72、M83、M85、M93、M98 | E1–E4、E6、E8、E10、E16、E18、E19；A1–A3、A6–A8、A13 |
| `SourceAdapter` | Boundary | 接收 M34/M36；发送 M35；返回 M37 | E11；A4 |
| `CollectionCoordinator` | Control | 发起 M3–M6、M10–M11、M13–M14、M18、M20、M22–M23、M25–M26、M28、M30、M32、M34、M38、M40、M42、M44–M45、M48、M50、M52、M54、M56、M58、M60–M61、M65、M67–M68、M71–M72、M76、M78–M80、M82–M83、M85、M89、M91、M93、M97–M98、M100；接收 M2、M5、M9–M10、M12、M17、M19、M21–M22、M24、M27、M33、M37、M39、M41、M43、M47、M49、M51、M55、M57–M59、M64、M66、M70、M75、M77、M79、M81、M88、M90、M92、M96 | E2–E19；A1–A14 |
| `GovernanceCoordinator` | Control（协作，属主 UC-09/10） | 接收 M40；返回 M41 | E13；A5 |
| `User` | Entity | 接收 M3、M4、M78、M97 | E2；A1、A6 |
| `SourceDefinition` | Entity | 接收 M11、M18；返回 M12、M19 | E3–E5、E7、E11；A2 |
| `CollectionTask` | Entity | 接收 M20、M23、M25、M32、M44、M45、M48、M54、M65、M76、M80、M89；返回 M21、M24、M27、M33、M49、M55、M59、M66、M70、M77、M81、M90 | E6–E19；A3–A10 |
| `SourceRecord` | Entity | 接收 M38；返回 M39 | E12；A12 |
| `GovernanceException` | Entity | 接收 M42、M50、M56；返回 M43、M51、M57 | E14–E15、E17；A5、A11 |
| `AuditEvidence` | Entity | 接收 M13、M26、M60、M67、M71、M82、M91、M100；返回 M92 | E5、E9、E18、E19；A1、A3、A4、A6、A14 |

三位参与者（管理员、获准数据来源、未授权请求者）不进入 VOPC：参与者不是系统内部类（教材 §5.4.4）。本 UC 无“系统参与者”边界类；系统职责由 `CollectionCoordinator` 与 `SourceAdapter` 实现。

## 2. 关系与来源

| 关系 | 类型 | 来自消息/约束 | 方向/角色 | 多重性 | 说明 |
|---|---|---|---|---|---|
| `AdminConsole --> CollectionCoordinator` | 单向关联/消息关系 | 请求 M2、M9、M17、M47、M64、M75、M88、M96；返回 M6、M14、M28、M30、M52、M61、M68、M72、M83、M85、M93、M98 | UI 请求；Coordinator 返回 | 不定义持久多重性 | UI 无持久业务属性；按消息方向表达一次用例会话的协作 |
| `CollectionCoordinator --> SourceAdapter` | 单向关联/外部交换关系 | M34 请求本批输入；M37 返回输入或失败反馈 | 请求；接收 | 不定义持久多重性 | 适配器不连业务实体：不决定公开/归属/去重、不写记录 |
| `CollectionCoordinator --> GovernanceCoordinator` | 单向关联/移交-回执关系 | M40 移交；M41 回执 | 逐条移交；返回处置结果或需异常移交 | 不定义持久多重性 | 只移交与取回，不建模清洗/去重/身份归属规则（B-10） |
| `CollectionCoordinator --> User` | 单向关联/只读校验关系 | M3、M4、M78、M97 | 索取账号状态与采集权限 | 不定义持久多重性 | 不新增账号属性、不定义权限矩阵（B-11） |
| `CollectionCoordinator --> SourceDefinition` | 单向关联/写-读关系 | M11 保存配置；M18 只读读取；M12/M19 返回 | 保存配置事实；读取启动前置 | 不定义持久多重性 | 不承载启用/停用状态机（B-06） |
| `CollectionCoordinator --> CollectionTask` | 单向关联/生命周期关系 | 创建 M23–M25；状态推进 M27/M59/M65/M70/M80；计数与检查点 M44/M45；只读 M32/M48/M54/M76/M89 | 创建、推进、记录历史、读取 | 不定义持久多重性 | 只使用 §6.2 六状态；不建调度器/队列 |
| `CollectionCoordinator --> SourceRecord` | 单向关联/受限写关系 | M38 登记（新记录或追加到达事件）；M39 回执 | 写入到达证据 | 不定义持久多重性 | 实线表示“登记＋追加到达事件”；既有原值不改写（INV-01） |
| `CollectionCoordinator --> GovernanceException` | 单向关联/创建-读取关系 | M42 建立；M50/M56 只读读取；M43/M51/M57 返回 | 建立待处理异常项；读取计数与核对 | 不定义持久多重性 | 不关闭异常项（关闭属 UC-09/10）；不设公开可见性（A11） |
| `CollectionCoordinator --> AuditEvidence` | 单向关联/追加-读取关系 | 追加 M13、M26、M60、M67、M71、M82、M100；只读 M91 | 追加受控写入留痕；读取用于失败定位 | 不定义持久多重性 | 不修改、不删除既有留痕（INV-10） |
| `SourceDefinition "1" --> "0..*" SourceRecord` | 业务关联 | FR16“每条来源保留来源ID…许可或使用范围与数据性质”；M18/M38 | 来源身份/许可/范围 | `1` : `0..*` | 每条记录来自一个来源定义；同一来源可有多次到达的记录 |
| `SourceDefinition "1" --> "0..*" CollectionTask` | 业务关联 | §6.2；`DGM-STATE-01` 图例；M20/M21 | 该来源的采集任务 | `1` : `0..*` | 同一来源可先后有多个任务（成功/停止后另建关联任务）；每个任务只按一个来源定义执行 |
| `CollectionTask "1" --> "0..*" SourceRecord` | 业务关联 | §6.2；INV-07；M38/M39 | 首次登记 | `1` : `0..*` | 一条逻辑出处只由首次登记它的任务归属；后续任务重复读到同一出处只追加到达事件，不新建记录 |
| `CollectionTask "1" --> "0..*" GovernanceException` | 业务关联 | §6.2“异常队列”；M42；A5 | 本任务产生的异常项 | `1` : `0..*` | 异常项按任务归因，用于完成判据与进度计数 |
| `SourceRecord "0..1" --> "0..*" GovernanceException` | 业务关联 | `AC-IMPL-FR08-01`；M42/M43 | 异常项可指向来源记录/任务/批次范围 | `0..1` : `0..*` | 异常可无具体来源记录（读取/交接失败）；同一记录可有多条异常项 |
| `AuditEvidence "0..*" --> "0..1" User` | 业务关联 | BR-IMP-10（操作者）；SPEC §3 执行者类型；M13/M26/M67/M82 | 操作者 | `0..*` : `0..1` | 一条留痕有一个操作者身份；系统自动动作无账号（与 UC-09/10/17 同源） |

### 2.1 为什么控制/边界到实体的关联不标持久多重性

`AdminConsole`/`SourceAdapter`/`CollectionCoordinator`/`GovernanceCoordinator` 到实体的关联按**消息方向**表达协作，不是长期业务对象链接。为它们标 `1..*` 会把一次采集执行的协调关系误解为持久聚合，甚至误读为“协调者持有全部来源记录或异常项”。因此本 VOPC 只对 5 条业务事实关联与 1 条操作者关联（共 6 条）给出多重性。

### 2.2 六条给出多重性的关联的依据（5 条业务事实＋1 条操作者）

| 多重性 | 为什么不是别的取值 |
|---|---|
| `SourceDefinition 1 : 0..* SourceRecord` | 记录必须有来源（否则许可/数据性质无法追溯，FR16）；来源可以还没有任何记录（刚配置、尚未采集），故记录侧 `0..*`。若写成 `1..*` 会要求“配置即产生记录”，与 A8（空范围/未读取）冲突。 |
| `SourceDefinition 1 : 0..* CollectionTask` | 来源定义是配置，任务是一次执行；成功后另建关联任务与失败重试都要求同一来源可对应多个任务。任务侧必须恰有一个来源定义（启动守卫 M22 依赖它），故为 `1`。 |
| `CollectionTask 1 : 0..* SourceRecord` | 任务的“首次登记”归因唯一（否则无法表达“历史成功记录保留”）。记录侧不写 `1..*`：任务可以因读取失败而零登记（A4）。 |
| `CollectionTask 1 : 0..* GovernanceException` | 异常项按任务归因以支持完成判据（M54–M58）与进度计数（M48/M50）；任务可以无异常。 |
| `SourceRecord 0..1 : 0..* GovernanceException` | 异常项优先指向具体来源记录以便定位；读取/交接失败的异常可只指向任务/批次范围，故记录侧可为 0..1；同一记录可因不同规则/尝试多次异常。 |
| `AuditEvidence 0..* : 0..1 User` | 受保护写入须有身份（BR-IMP-10），但系统自动动作（如完成/失败记录）没有账号主体；写 `1` 会为“系统”虚构账号（与 UC-09/10/17 结论一致）。 |

### 2.3 写成属性而不是实体关联的事实

| 事实 | 表达方式 | 理由 |
|---|---|---|
| 批次划分与批次计数 | `CollectionTask.batchDispositionCounts`（分析状态）＋检查点 | 批次是执行分段，批次大小未冻结（B-02）；建 `ImportBatch` 会把调度带入分析（03 §4） |
| 检查点与每次尝试的历史 | `CollectionTask.lastCheckpoint`＋`CollectionTask.attemptHistory` | §6.2 要求“保留检查点与任务历史”，但只需按任务定位；建 `Checkpoint`/`TaskRun` 会诱导实现层设计（B-02） |
| 计划输入范围 | `CollectionTask.planSnapshot`（来源定义引用＋范围＋输入方式） | 范围口径未冻结（B-04）；快照是任务属性，不是独立实体 |
| 异常项的公开可见性 | **不建模**：`GovernanceException` 无公开可见性 | 异常永远不是公开数据；建“公开状态”字段会把候选方案写成已批准规则（A11；BR-IMP-01） |
| 异常项与治理候选的关系 | 不画：由 UC-09/10 在治理侧把异常项关闭/转候选 | 本 UC 不判定、不合并、不拆分（FR09/FR15） |
| 任务与公开成果的关系 | 不画：本 UC 不创建 `WorkEntity`、不发布 | 公开资格由治理与生效规则产生（BR-IMP-01/02；INV-02） |
| 留痕指向的任务/来源定义 | `AuditEvidence.targetRefs`＋`targetType` | 审计必须覆盖被拒绝与未写入的对象；画边会与各属主关系重复（UC-06/09/10/17 同一取舍） |

## 3. 从顺序图到 VOPC 的对应

| 顺序图对象 | VOPC 类 | 保留/删除理由 |
|---|---|---|
| 管理员（Actor） | 不保留 | 参与者不是系统类；发起 M1/M8/M16/M46/M63/M74/M87 |
| 获准数据来源（Actor） | 不保留 | 参与者不是系统类；接收 M35、应答 M36 |
| 未授权请求者（Actor） | 不保留 | 参与者不是系统类；只触发 M95 与接收 M99 |
| AdminConsole | 保留 | 接收管理交互并显示结果/拒绝/失败/进度 |
| SourceAdapter | 保留 | 与外部来源交换记录与失败信息 |
| CollectionCoordinator | 保留 | 守卫、生命周期、登记、移交、计数、完成判据、留痕 |
| GovernanceCoordinator | 保留 | 接收移交并回执处置结果（协作类） |
| User | 保留 | 接收 M3/M4/M78/M97 |
| SourceDefinition | 保留 | 接收 M11/M18，应答 M12/M19 |
| CollectionTask | 保留 | 接收/应答 M20–M90 的任务状态、快照、检查点、历史与计数 |
| SourceRecord | 保留 | 接收 M38，应答 M39 |
| GovernanceException | 保留 | 接收 M42/M50/M56，应答 M43/M51/M57 |
| AuditEvidence | 保留 | 接收 M13/M26/M60/M67/M71/M82/M91/M100，应答 M92 |
| 无其他对象 | — | 没有 `Scheduler`/`TaskQueue`/`JobRunner`/`RetryPolicy`/`Checkpoint`/`ImportBatch`/`WorkEntity`/`Authorship`/`CleanRecord`/`DedupCandidate`/`IdentityCandidate`/`PendingTodo` 的消息 |

## 4. 排除项与理由

- `Scheduler` / `TaskScheduler` / `JobRunner` / `ThreadPool` / `Cron`：调度是实现机制，基线未定义调度模型（FR08；§6.2）。
- `TaskQueue` / `ImportQueue` / `ExceptionQueue`：队列是存储/投递机制；“异常队列”在本 UC 的可观察形态是 `GovernanceException` 集合（§6.2；A11）。
- `RetryPolicy` / `BackoffRule` / `RetryCounter`：重试上限未冻结（D-10）；重试是任务状态与守卫，不是策略对象。
- `Checkpoint` / `TaskRun` / `CollectionAttempt` / `ImportBatch`：检查点与尝试历史是任务内追加式属性；批次大小与间隔未冻结（B-02）。
- `ApiConnector` / `FileWatcher` / `HttpClient` / `Cursor` / `PageToken`：接入实现与分页（D-10/B-03）；外部交换由 `SourceAdapter` 承接。
- `CollectionConsole` / `SourceConfigUI`：`AdminConsole` 已是 UC-17/18/19 统一入口（`01-BCE` §2；UC-17 03 §4）。
- `DedupCoordinator` / `CleanRecord` / `DedupCandidate` / `WorkEntity` / `WorkVersion` / `IdentityVerificationCoordinator` / `IdentityCandidate` / `AttributionCandidate` / `Scholar` / `Authorship`：属 UC-09/10；本 UC 只移交并取回结果。
- `PublicWork` / `PublicVisibility` / `PublishAction`：发布与公开可见性不属本 UC（BR-IMP-01；A11）。
- `PendingTodo` / `WorkflowEngine` / `ApprovalChain` / `Assignee` / `SlaTimer`：FR14“不做通用工作流引擎”；UC-17 已确定待办为只读派生投影。
- 第二套日志（`AuditLog`/`ImportLog`/`ErrorLog`）：与 `AuditEvidence` 同义重复（BR-IMP-10；NF07-01）。
- `RightsPolicy` / `DataOriginPolicy` / `PermissionMatrix`：许可与数据性质是属性，权限矩阵未冻结（B-11）。
- `BackupJob` / `RestoreJob` / `RtoRpoPolicy`：NF04-02 属部署运维（NOT RUN）；本 UC 只保证不删历史、重试不重复。
- `Session` / `CredentialContext`：已认证请求上下文，不建模（沿用其他 UC）。
- `TaskProgressView` / `ProgressSnapshot` / `CountReport` / DTO：查询结果与展示投影，不是业务事实（B-08）。
- `MappingDictionary`：本 UC 无字段映射消息（FR09）。

## 5. VOPC 完整性检查

| 检查 | 结果 |
|---|---|
| 只包含 UC-18 参与类 | 是，10 个 |
| 每个类有事件流/消息依据 | 是，见表 1/表 3 |
| 每条关系可追溯到消息或业务约束 | 是，见表 2 |
| 不把 Actor 画成系统类 | 是；三位参与者不进入 VOPC |
| 不用全局 BCE 冒充 VOPC | 是；本图独立于 `DGM-CLASS-01-analysis.puml` |
| 关系多重性是否有依据 | 5 条业务事实关联＋1 条操作者关联（共 6 条）有依据（§2.2）；其余按消息方向，不定义持久多重性（§2.1） |
| 是否出现执行框架类 | 否：无 `Scheduler`/`TaskQueue`/`JobRunner`/`RetryPolicy`/`Checkpoint`/`ImportBatch` |
| 是否出现通用工作流实体 | 否：无 `PendingTodo`/`WorkflowEngine`/`Assignee`/`SlaTimer` |
| 是否创建/更新成果 | 否：无 `WorkEntity`/`Authorship` 消息或边 |
| 是否执行治理判定 | 否：对 `GovernanceCoordinator` 只有移交 M40 与回执 M41 |
| 是否把异常项与留痕混为一类 | 否：`GovernanceException`（业务待办）与 `AuditEvidence`（动作留痕）分离 |
| 是否把来源配置与到达证据混为一类 | 否：`SourceDefinition` 与 `SourceRecord` 分离 |
| 是否新增任务状态 | 否：只用 §6.2 六状态（M27/M59/M65/M70/M80） |
| 是否引入设计技术（表/接口/队列/索引/缓存） | 否 |

## 6. 与全局图的关系

本 VOPC 是 UC-18 的局部静态视图，不是全系统类图。与 `DGM-CLASS-01-analysis.puml` 的异同、命名合并建议见[07-与全局模型差异](07-global-model-diff.md)。R5 统一分析类时应以本 VOPC 为输入之一，而不是用全局图反推本 UC 的参与类；特别是**不应**据全局 `CollectCtl ..> Task` 把本 UC 扩成治理或发布协调者，也**不应**据 `AdminUI ..> CollectCtl/GovernCtl` 把外部来源交换并入管理入口。
