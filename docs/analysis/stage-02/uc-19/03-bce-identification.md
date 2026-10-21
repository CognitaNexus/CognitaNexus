# UC-19 Boundary / Control / Entity 识别

**版本：** V1.0（2026-10-21）
**方法：** 教材 §5.4.2：从本 UC 的事件流与顺序图消息识别 Boundary/Control/Entity；**不照抄**全局 BCE，也不把全局图当作反向答案。
**只读比对：** [`DGM-CLASS-01-analysis.puml`](../../../uml/analysis/DGM-CLASS-01-analysis.puml)、[`01-BCE分析类模型.md`](../01-BCE分析类模型.md)、[`DGM-USECASE-03`](../../../uml/collection/collection-local-usecase.puml)、[`DGM-STATE-01`](../../../uml/collection/collection-task-state.puml)、[`collection-update-activity`](../../../uml/collection/collection-update-activity.puml)、[`DGM-SEQ-01`](../../../uml/analysis/DGM-SEQ-01-collection.puml)、UC-18/09/10/17 已交付分析包、候选类登记表 [`analysis-class-registry.md`](../automation/analysis-class-registry.md)、待决清单 [`unresolved-decisions.md`](../automation/unresolved-decisions.md)。

## 1. 识别原则

1. 外部交互必须有 Boundary 承接：本 UC 沿用 UC-18 的两个外部接口——管理员的来源更新入口（`AdminConsole`）与外部获准来源的记录/报告交换（`SourceAdapter`）；两者都不是决策者。
2. 控制类只承接“跨对象守卫、分类、时间记录与计数核对”，**不承载**清洗/去重/身份归属判定，也不决定公开资格。
3. 实体必须是**持久业务事实**且在本 UC 顺序图中有消息；仅出现在事件描述里、没有消息寻址的概念不建类。
4. 一切“执行框架/比较框架”概念（调度、队列、线程、重试策略、检查点对象、增量窗口/游标对象、逐条差异对象）都不建类：它们的可观察结果已由任务状态、检查点属性、变更集及其逐条分类项表达。
5. 若一个候选类的作用只是“把本轮差异装进一个容器”，它就是把流程集中化的通用实体（工作流/队列），按 FR14 的同一口径拒绝；差异细节作为 `IncrementalChangeSet` 的属性保留。

## 2. 候选分析类

| 编号 | 类 | 类型 | 参与事件 | 职责摘要 | 为什么必须是独立类 |
|---|---|---|---|---|---|
| C1 | `AdminConsole` | Boundary | E1–E3、E17；A1、A9–A13 | 接收打开/上次概览、选择已核验来源域与更新范围、指定回溯范围、查看汇总与轨迹、越界请求；显示入口、基线/局限、冲突与失效结论、成功（限定措辞）、失败与恢复入口、汇总与轨迹、拒绝 | 来源定义/记录/任务/变更集/待办/成果是业务事实，不承担输入与展示；`CollectionCoordinator` 是内部协调者，不直接面向参与者。UC-17 已把 UC-17/18/19 的管理入口统一为 `AdminConsole`，不另设 `SourceUpdateUI` |
| C2 | `SourceAdapter` | Boundary | E5；A2/A4 | 与获准来源交换本轮记录、来源侧变更报告、删除/撤稿报告与失败反馈；不决定公开、归属或去重 | 外部来源是**外部系统**，协议/格式/分页属接入边界；让协调者直接“取得记录与删除报告”会把外部协议写入控制类。`01-BCE` §2 与 `DGM-SEQ-01` 均已设该边界（“不自行决定公开/归属”） |
| C3 | `CollectionCoordinator` | Control | E1–E17；A1–A14 | 账号与具体管理权限守卫；来源域与基线守卫；变更集建立；逐条定位、身份守卫与分类；新增移交、未变记账、变更差异与冲突分派、失效登记；版本证据判定；检查点与幂等核对；完成判据；失败与恢复；只读汇总；受控留痕 | 守卫同时依赖 `User`（权限）、`SourceDefinition`（已获准/来源域）、`CollectionTask`（状态/检查点）、`SourceRecord`（原值/观察版本）、`WorkEntity`（审核值）、`WorkVersion`（版本证据）、`IncrementalChangeSet`（本轮结果）、`GovernanceException`（待办）八组事实；任何单一 Entity 只能自知，Boundary 不能执行跨对象校验；且“人工值不被覆盖”“冲突入待办”“失败不删史”“复跑幂等”必须集中判定 |
| C4 | `GovernanceCoordinator` | Control（协作类，属主 UC-09/10） | E8、E12、E13；A10 | 接收新增记录、无冲突变更与版本证据，返回“已登记结果/待核验”“已按规则生效/需人工确认”或“需异常移交”与原因 | 本 UC 不拥有清洗/去重/字段优先级/身份归属规则，但“生效”与“新增处置”必须由属主回答是否已受理；本类只承接移交/回执，不承载规则（命名与粒度留 R5，B-09） |
| C5 | `User` | Entity | E2；A11 | 提供账号标识、账号状态与“数据来源/采集”具体管理权限事实 | 账号与权限属账号事实，不属于来源、任务或留痕；本 UC 只读，不新增属性、不建权限矩阵 |
| C6 | `SourceDefinition` | Entity | E3；A1（前置守卫） | 提供来源已获准、来源域已核验、输入方式、许可/使用范围、数据性质与允许范围（**只读**） | 配置与到达证据是两种事实（FR16“每条来源保留来源ID、采集/导入时间、许可或使用范围与数据性质”）；把配置并入 `SourceRecord` 会使每条记录重复配置，且无法在更新前判定“已核验来源域” |
| C7 | `CollectionTask` | Entity | E4、E15、E16；A4 | 承载一次执行的状态（六状态）、参数快照、安全检查点、追加式任务历史与失败点 | §6.2 要求六状态、检查点与任务历史；这些是持久事实，不是调度器状态。变更集回答“本轮比较了什么”，任务回答“这次执行到哪一步、失败在哪”，两者不可合并 |
| C8 | `SourceRecord` | Entity | E6–E10、E14；A2/A3/A7/A8 | 承载到达证据：来源身份、逻辑出处、到达事件（含观察版本）、原始字段值、数据性质、许可、到达时间、最近检查时间；原值不可改写、不删除 | INV-01“原始记录永不因清洗被改写”与 FR10“按来源ID与上次版本区分”都要求它独立存在；把观察版本或原值放进变更集会失去“上次版本”基线，把检查时间写进成果会混淆来源证据与成果 |
| C9 | `IncrementalChangeSet` | Entity | E5、E11–E16；A1–A7 | 承载本轮比较结果：来源域、更新范围、基线引用、检查时间、来源报告更新时间、生效时间、逐条分类（新增/未变/变更/冲突/失效/异常）与前后差异引用、互斥计数、待办引用、失败状态与未完成项 | FR10 要求“区分新增/未变/变更/失效”并“保留检查与生效时间”；任务只承载执行状态与检查点，无法表达“每条输入被如何分类、何时生效、哪些进待办”；把分类塞进 `SourceRecord` 会把逐次比较结果写进不可改写的到达证据（违反 INV-01） |
| C10 | `WorkEntity` | Entity | E10、E12、E14；A5/A6/A9/A12 | 只读提供去重成果的当前生效值、已人工审核字段与公开生效状态；回答“该字段是否已被人工审核”“既有成果与出处是否存在” | “不覆盖人工审核字段”（INV-06/CL-09）与“公开仍为 B”（`AC-IMPL-FR10-01`）要求一个跨来源唯一的成果事实作为比较对象；它不能由本 UC 写入（去重/字段合并属 UC-09），因此只读 |
| C11 | `WorkVersion` | Entity | E10；A10 | 只读提供既有版本关系与来源明示版本证据；回答“该成果是否已有版本关系、本次是否有明示证据” | 版本与出处是不同事实（UC-02/09 已定 `WorkEntity 1:0..* WorkVersion`）；把版本证据塞进变更集会让“版本关系”变成逐次比较的临时结论，无法表达“无证据不建立、有证据才存在”（DD-08） |
| C12 | `GovernanceException` | Entity | E11、E14；A5/A6/A2/A8 | 承载待治理待办：原因（字段冲突/并发冲突/失效待核验/身份或记录键不可确认/版本证据缺失待核验）、对象引用、冲突字段与双方证据、待处理状态；**无公开可见性** | FR10“并发冲突进入治理待办”与 `AC-IMPL-FR10-01`“出现待核验字段冲突”要求冲突**可被治理按对象打开并处置**；用 `AuditEvidence` 代替会把“业务待办”与“动作留痕”混为一类（UC-18/09/17 已把两者分离），用 `WorkEntity` 代替会直接违反“不得覆盖人工审核值” |
| C13 | `AuditEvidence` | Entity | E11、E12、E14、E16；A4/A6/A11/A14 | 承载受保护写入的追加式留痕：冲突待办建立、生效、放弃覆盖、失效登记、完成、失败与拒绝的操作者/对象/时间/前后值/理由；供轨迹与错误定位只读读取 | BR-IMP-10 覆盖 FR05～FR10/14/15 的受保护写入；NF07-01 要求“可从日志定位任务错误”。留痕是“已发生的动作”，与“当前任务状态”“本轮分类结果”“待处理待办”都不是同一事实 |

## 3. 逐类选择理由

### C1 AdminConsole（Boundary）

- **接收：** M1 打开来源更新入口、M8 选择来源域与范围、M17 指定回溯范围、M88 查看汇总与轨迹、M98/M102 越界请求、M106 未授权操作。
- **显示：** M7 入口、M15/M16 基线或局限、M82 成功与单列计数、M87 失败与恢复入口、M97 汇总与轨迹、M101/M105/M110 拒绝。
- **不能承担：** 不判断权限、不校验来源获准、不执行分类、不推进状态、不计算冲突、不决定公开资格；入口是否显示不构成授权（§8）。
- **命名：** 采用 `01-BCE` §2 与 UC-17/18 的 `AdminConsole`；不另设 `SourceUpdateUI`/`UpdateConsole`（避免同一管理入口被切成多份）。

### C2 SourceAdapter（Boundary）

- **接收：** M22 请求本轮输入；**发送：** M23 请求记录与来源侧变更/删除报告；**应答：** M24/M25 返回记录、报告或失败反馈。
- **不能承担：** 不分类、不写业务实体、不建变更集、不判公开/归属/去重、不定义协议格式（B-03/D-10）。
- **为何不是实现类：** 它是外部来源的边界（`01-BCE` §2：与获准来源交换记录和失败信息），不是技术适配器实现。

### C3 CollectionCoordinator（Control）

- **守卫：** 账号与权限（M3–M5、M108）、来源域与基线（M10–M14）、身份与出处（M28）、冲突（M44）、生效前重检（M49）、版本证据（M62）、完成判据（M78）。
- **推进与登记：** 变更集建立与计数（M20、M47、M53、M57、M59、M70）、来源记录追加与检查时间（M32、M36、M38、M63）、待办建立（M30、M45、M55、M67）、检查点与任务历史（M74）、完成/失败（M79、M83）。
- **只读返回：** 汇总与轨迹（M90–M96）。
- **不承担：** 不执行清洗/去重/字段优先级/身份归属（只移交）、不写 `WorkEntity`、不建 `WorkVersion`、不发布、不改写原值、不删历史、不做来源配置、不建调度器/队列。
- **与 `GovernanceCoordinator` 的分界：** 本 UC 只问“是否已受理/已按规则生效/需异常移交”，不问“为什么合并/按什么优先级”。

### C4 GovernanceCoordinator（Control，协作类）

- **本 UC 内的职责：** 接收新增记录（M34）、无冲突变更生效（M51）与版本证据（M60），返回处置结果或生效结果或“需异常移交”（M35/M52/M61）。
- **不承担：** 清洗规则、稳定ID匹配、字段优先级、候选生成、合并/拆分、身份/归属判定、版本归并、发布（FR09/FR15；UC-09/10）。
- **命名：** 沿用 `01-BCE` §3 的 `GovernanceCoordinator`；UC-09 的 `DedupCoordinator`、UC-10 的 `IdentityVerificationCoordinator` 是其职责细化，统一命名留 R5（B-09；D-11）。

### C5 User（Entity）

- 只读提供 `accountStatus` 与 `collectionPermission`。
- **不是** `Scholar`（学术身份），**不是** `ScholarAuthorization`（门户对象授权）；不新增角色继承或权限矩阵（§8）。

### C6 SourceDefinition（Entity，只读）

- **知道：** 来源身份与来源域、输入方式（API/文件）、许可/使用范围、数据性质（真实/模拟/混合）、允许范围、是否已获准（`approvedForUse`）。
- **不做：** 本 UC 不写入、不停用、不更换；不承载到达记录与任务状态（B-06）。
- **为何仍参与：** FR10 明确“在**已核验来源域**”，若无该事实则无法判定“本轮更新是否被允许”。

### C7 CollectionTask（Entity）

- **知道：** 六状态、参数快照、安全检查点（位置/时间）、任务历史（每次尝试：起止、结果、失败点、分类计数）、失败点。
- **不做：** 不承载本轮逐条分类与生效时间（属变更集）、不调度执行、不判定异常去向。
- **与变更集的分界：** 任务回答“执行到哪、失败在哪、能否续跑”；变更集回答“本轮每条输入被如何分类、何时生效、哪些待办”。

### C8 SourceRecord（Entity）

- **知道：** 来源身份、`sourceId + sourceRecordKey`、到达事件（首次/变更观察，含到达时间与观察版本）、`rawValues`、数据性质、许可、`arrivalAt`、`lastCheckedAt`、`observedRevision`。
- **不做：** 不改写原值（INV-01）、不承载清洗结果（UC-09 `CleanRecord`）、不承载公开状态、不承载本轮分类结论（属变更集）。
- **为什么需要观察版本与最近检查时间：** FR10“按来源ID与上次版本区分”与“保留检查时间”要求**逐条**保存可比较基线与检查时刻；二者是观察元数据，不改写原值，也不把分类结论写进入达证据。

### C9 IncrementalChangeSet（Entity）

- **知道：** 来源域、更新范围、基线引用（含回溯范围标记）、`checkedAt`、`sourceReportedUpdatedAt`、`effectiveAt`、逐条分类与差异引用、互斥计数、待办引用、失败状态与未完成项。
- **不做：** 不承载任务状态、不建成果、不判公开、不保存原值副本（原值仍在 `SourceRecord` 的到达事件中）。
- **为什么不并入 `CollectionTask`：** 任务可能有多次尝试与多个批次；变更集是“本轮比较”的结果，需独立保留三类时间、逐条分类与待办引用，且复跑时需保留上一轮结果作对比（A7）。
- **为什么不并入 `SourceRecord`：** 分类与生效时间随每次更新变化，写进入达证据会改写历史（违反 INV-01）；一条来源记录会出现在多次更新中，需要多份比较结果。
- **为什么不建 `ChangeItem`/`FieldDiff` 类：** 逐条分类项与字段前后值只在单一变更集内被定位与计数，用属性（`changeItems[]`、`beforeAfterDiffRefs`）表达即可；单独建类会把“本轮明细”实体化，诱导实现层设计（B-08 计数口径也未冻结）。

### C10 WorkEntity（Entity，只读）

- **知道：** 当前生效值、已人工审核字段标记、公开生效状态、既有出处。
- **不做：** 本 UC 不写入、不覆盖、不新建、不合并、不拆分、不改变公开资格。
- **为什么仍参与：** `AC-IMPL-FR10-01` 的比较对象就是“已审核标题 B”；没有该只读事实就无法判定冲突，也无法证明“公开仍为 B”。

### C11 WorkVersion（Entity，只读）

- **知道：** 版本种类、明示版本证据、关联来源、可公开性。
- **不做：** 本 UC 不建立版本关系、不改版本清单；建立规则属 UC-09 DD-08。
- **为什么仍参与：** E13 必须回答“是否已有版本关系、本次是否有明示证据”；否则“版本只在有证据时存在”无法被核对（A10）。

### C12 GovernanceException（Entity）

- **知道：** 原因（字段冲突/并发冲突/失效待核验/身份或记录键不可确认/版本证据缺失待核验）、对象引用（来源记录/变更集/成果与字段）、冲突字段与双方证据（人工审核值、来源新值）、待处理状态、治理域。
- **不做：** **无公开可见性**；不关闭、不判定（关闭与处置属 UC-09/10 与 FR14 权限范围）；不承载动作留痕（`AuditEvidence`）。
- **与相邻概念的区别：** 不是 `AuditEvidence`（动作）、不是 `CleanRecord`（清洗产物）、不是 `DedupCandidate`/`IdentityCandidate`（治理候选）、不是 `PendingTodo`（后台只读投影）。
- **已知归属冲突：** UC-09 把字段冲突项挂在 `CleanRecord`，UC-17 的待办视图读取 `DedupCandidate`/`CollectionTask` 而不含本类；单一所有者未定 → 登记 R5（B-09；见 07 §6）。

### C13 AuditEvidence（Entity）

- **知道：** 事件类型（冲突待办/生效/放弃覆盖/失效登记/完成/失败/拒绝）、操作者（账号或系统）、对象引用与类型、时间、前后值、理由、结果。
- **不做：** 不承载当前状态、分类结果或待办；不改写、不删除。
- **为什么仍是实体：** BR-IMP-10/INV-10 与 NF07-01 要求跨对象、跨时间的追加式留痕与错误定位；变更集与任务历史都不能替代“谁在何时做了什么”的留痕（尤其拒绝与放弃覆盖）。

## 4. 被拒绝的候选类

| 候选 | 拒绝理由 | 依据 |
|---|---|---|
| `Scheduler` / `TaskScheduler` / `JobRunner` / `Cron` | 调度是实现机制；基线只要求“启动/进度/停止/失败恢复/重试与任务状态”，未定义调度模型 | FR08/FR10；§6.2；教材 §5.4.2 |
| `TaskQueue` / `ImportQueue` / `ExceptionQueue`（队列类） | 队列是存储/投递机制；“异常队列”的可观察形态是 `GovernanceException` 集合 | §6.2；UC-18 03 §4 |
| `RetryPolicy` / `BackoffRule` / `RetryCounter` | 重试上限未冻结（D-10）；重试是任务状态与守卫，不是策略对象 | D-10；`DGM-STATE-01` |
| `Checkpoint` / `TaskRun` / `UpdateAttempt` / `Batch` | 检查点与尝试历史是单一任务内追加式事实，用 `CollectionTask` 属性表达；检查点间隔与批次大小未冻结（B-01） | §6.2；B-01；教材 §5.4.2 |
| `StreamingSubscriber` / `ChangeFeed` / `Webhook` / `EventStream`（实时同步类） | FR10 明确“采用批次复跑/安全检查点，不强制实时流式同步”；本 UC 以一轮/批次的比较与检查点推进，实时订阅与推送无需求依据 | FR10；教材 §5.4.2 |
| `IncrementalWindow` / `ChangeCursor` / `SyncCursor` / `Baseline`（独立类） | “上次版本/基线”是可比较的**属性**（`SourceRecord.observedRevision`＋`IncrementalChangeSet.baselineRef`）；建类会把游标机制当业务对象，并诱导实现设计（B-02/B-03） | FR10；B-02；教材 §5.4.2 |
| `ChangeItem` / `FieldDiff` / `ChangeLog` | 逐条分类与前后差异在单一变更集内定位，用变更集属性表达；单独建类会把“本轮明细”实体化 | FR10；B-08 |
| `ApiConnector` / `FileWatcher` / `HttpClient` / `Cursor` / `PageToken` | 接入协议与分页属实现设计（D-10/B-03）；外部交换由 `SourceAdapter` 承接 | D-10；B-03 |
| `SourceUpdateUI` / `UpdateConsole` / `SyncUI` | `AdminConsole` 已是 UC-17/18/19 的统一管理入口；再设一类会把入口切成多份并与 UC-17/18 结论冲突 | `01-BCE` §2；UC-17/18 03 §4 |
| `DedupCoordinator` / `CleanRecord` / `DedupCandidate` / `FieldPriorityRule` | 清洗、去重、字段优先级与候选属 UC-09；本 UC 只移交并取回结果 | FR09；UC-09 |
| `IdentityVerificationCoordinator` / `IdentityCandidate` / `AttributionCandidate` / `Scholar` / `Authorship` | 身份与归属判定属 UC-10；本 UC 不判定、不改关系状态 | FR15；UC-10 |
| `PublicWork` / `PublicVisibility` / `PublishAction` / `DelistAction` | 发布、下架与公开可见性不属本 UC；失效报告不改变公开资格（A9/A12） | BR-IMP-01；CL-12；NF05 |
| `PendingTodo` / `WorkflowEngine` / `ApprovalChain` / `Assignee` / `SlaTimer` | 通用工作流实体；FR14“不做通用工作流引擎”，UC-17 已确定待办为只读派生投影 | FR14；UC-17 03 §4 |
| `AuditLog` / `ImportLog` / `ErrorLog`（第二套日志） | 与 `AuditEvidence` 同义重复；NF07-01 的“日志定位”由任务历史＋受控留痕提供 | BR-IMP-10；NF07-01 |
| `RightsPolicy` / `DataOriginPolicy` / `PermissionMatrix` | 许可/数据性质是来源定义与记录的业务属性；权限矩阵未冻结 | FR16；§8 |
| `BackupJob` / `RestoreJob` / `RtoRpoPolicy` | 备份恢复演练属部署运维（NF04-02，NOT RUN）；本 UC 只保证不删历史、复跑不重复 | NF04-02；§5 不纳入事件 |
| `Session` / `CredentialContext` | 已认证请求上下文；基线未定义会话对象（沿用其他 UC） | 教材 §5.4.2 |
| `ChangeSummaryView` / `ProgressSnapshot` / `CountReport` / DTO | 汇总、轨迹与计数是查询结果/展示投影，不是持久业务事实 | §5.4.2；B-08 |
| `VersionEvidence`（独立类） | 明示版本证据是 `WorkVersion` 与来源记录的属性，判定规则属 UC-09 DD-08；本 UC 只记录证据并移交 | DD-08；UC-09 |

## 5. 对象边界结论

1. **五类事实分离：** 配置（`SourceDefinition`，只读）／到达证据（`SourceRecord`）／执行状态与历史（`CollectionTask`）／本轮比较结果（`IncrementalChangeSet`）／待治理待办（`GovernanceException`）。任两者合并都会破坏 INV-01、§6.2、FR10 的“上次版本”或“冲突可处置”。
2. **版本语义三分：** 来源侧记录版本（`SourceRecord.observedRevision`）≠ 本轮基线（`IncrementalChangeSet.baselineRef`）≠ 成果发布形态（`WorkVersion`）。三者混用会导致“同一版本重复建立变更”或“无证据压缩实体数”。
3. **两个边界、一个控制、一个协作控制：** `AdminConsole`（管理交互）与 `SourceAdapter`（外部交换）都不决策；`CollectionCoordinator` 集中守卫、分类、时间记录与计数核对；`GovernanceCoordinator` 只回答“是否已受理/已按规则生效/需异常移交”。
4. **成果侧只读：** 本 UC 对 `WorkEntity`/`WorkVersion` 只有读消息；不覆盖人工审核值、不合并/拆分、不建版本、不发布（BR-UC19-07/15）。
5. **失败与失效都不删历史：** 失败保留失败点/已分类结果/恢复范围；失效只登记事件并转待核验（A4；A2/A9/A12）。
6. **不建执行、比较与实时同步框架类：** 无调度器、队列、运行器、重试策略、检查点类、增量窗口/游标类、差异明细类，也无实时订阅/流式推送类（FR10 不强制实时流式同步）；这些概念的可观察结果由任务状态、检查点属性、变更集与待办表达（§4）。

## 6. 识别结论

- Boundary：`AdminConsole`、`SourceAdapter`（2）
- Control：`CollectionCoordinator`、`GovernanceCoordinator`（协作，属主 UC-09/10）（2）
- Entity：`User`、`SourceDefinition`、`CollectionTask`、`SourceRecord`、`IncrementalChangeSet`、`WorkEntity`、`WorkVersion`、`GovernanceException`、`AuditEvidence`（9）

合计 **13 个参与类**，全部在 04 顺序图中有消息证明；调度器/队列/运行器/重试策略/检查点类/窗口类/差异明细类、治理与判定类、发布类、工作流类均被拒绝（§4）。该集合将在 04/05 接受“每条消息都有依据、每个类都有消息”的双向检验。
