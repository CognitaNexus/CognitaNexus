# UC-18 Boundary / Control / Entity 识别

**版本：** V1.0（2026-10-21）
**方法：** 教材 §5.4.2：从本 UC 的事件流与顺序图消息识别 Boundary/Control/Entity；**不照抄**全局 BCE，也不把全局图当作反向答案。
**只读比对：** [`DGM-CLASS-01-analysis.puml`](../../../uml/analysis/DGM-CLASS-01-analysis.puml)、[`01-BCE分析类模型.md`](../01-BCE分析类模型.md)、[`DGM-USECASE-03`](../../../uml/collection/collection-local-usecase.puml)、[`DGM-SEQ-01`](../../../uml/analysis/DGM-SEQ-01-collection.puml)、[`DGM-STATE-01`](../../../uml/collection/collection-task-state.puml)、候选类登记表 [`analysis-class-registry.md`](../automation/analysis-class-registry.md)。

## 1. 识别原则

1. 外部交互必须有 Boundary 承接：本 UC 有**两类**外部接口——管理员的采集管理入口（`AdminConsole`）与外部获准来源的记录交换（`SourceAdapter`）；两者都不是决策者。
2. 控制类只承接“跨对象守卫、生命周期推进与计数核对”，**不承载**清洗/去重/身份归属判定。
3. 实体必须是**持久业务事实**且在本 UC 顺序图中有消息；仅出现在事件描述里、没有消息寻址的概念不建类。
4. 一切“执行框架”概念（调度、队列、线程、重试策略、检查点对象、批次对象）都不建类：它们的可观察结果已经由任务状态、检查点属性、计数与异常项表达。
5. 若一个候选类的作用只是“把待处理项装进一个容器”，它就是把流程集中化的通用实体（工作流/队列），按 FR14“不做通用工作流引擎”的同一口径拒绝。

## 2. 候选分析类

| 编号 | 类 | 类型 | 参与事件 | 职责摘要 | 为什么必须是独立类 |
|---|---|---|---|---|---|
| C1 | `AdminConsole` | Boundary | E1–E4、E6、E8、E16、E18、E19；A1–A3、A6–A8、A13 | 接收采集管理交互：打开入口、提交来源定义、创建/启动、查询进度、停止、重试、查看任务历史；显示结果、拒绝、失败、空范围、已停止、待核验/异常计数 | 来源定义/记录/任务/异常项是业务事实，不承担输入与展示；`CollectionCoordinator` 是内部协调者，不直接面向参与者。不另设 `CollectionConsole`：`01-BCE` §2 已把 UC-17/18/19 的管理入口统一为 `AdminConsole`，UC-17 也已拒绝采集处置边界独立成类 |
| C2 | `SourceAdapter` | Boundary | E11；A4 | 与获准来源交换记录与失败信息：按来源定义与快照请求本批记录（API 或文件）、返回记录或读取失败/断连反馈 | 外部来源是**外部系统**，其协议/格式/分页属接入边界；让协调者直接“取得记录”会把外部协议写入控制类。`01-BCE` §2 与 `DGM-SEQ-01` 均已设该边界，且明确“不自行决定公开/归属” |
| C3 | `CollectionCoordinator` | Control | E2–E19；A1–A14 | 账号与具体管理权限守卫；来源定义守卫与保存；任务创建/启动/停止/重试守卫；参数快照与检查点；逐条登记与移交；处置计数；完成判据核对；进度与历史只读返回；受控留痕 | 守卫同时依赖 `User`（权限）、`SourceDefinition`（有效性/许可）、`CollectionTask`（状态/历史）、`GovernanceCoordinator`（处置结果）、`GovernanceException`（异常移交）五组事实；任何单一 Entity 只能自知，Boundary 不能执行跨对象校验；且“交接失败不算完成”“重试不重做”“异常不成成果”三条守卫必须集中判定 |
| C4 | `GovernanceCoordinator` | Control（协作类，属主 UC-09/10） | E13；A5 | 接收逐条移交并返回“已登记结果/待核验”或“需异常移交”与原因 | §6.2 的成功判据要求“记录到结果或异常队列”，因此需要一个**受理方**回答“该输入是否已被处置登记”。本 UC 只定义其协作职责，不建模清洗/去重/身份归属规则（B-10 命名留 R5） |
| C5 | `User` | Entity | E2；A1；A6 | 提供账号标识、账号状态与“数据来源/采集”具体管理权限事实 | 账号状态与权限属账号事实，不属于来源、任务或留痕；本 UC 只读，不新增属性、不建权限矩阵 |
| C6 | `SourceDefinition` | Entity | E3–E4、E7、E11；A2 | 承载来源配置事实：来源身份、输入方式（API 或文件）、许可/使用范围、数据性质（真实/模拟/混合）、允许范围 | 配置与到达证据是两种事实（FR16“每条来源保留来源ID、采集/导入时间、许可或使用范围与数据性质”）。把配置并入 `SourceRecord` 会导致“每条记录重复配置”且无法在启动前判定来源是否获准；把配置写成 `SourceAdapter` 的属性会把业务许可降级为接入参数 |
| C7 | `CollectionTask` | Entity | E6–E10、E15–E19；A3、A5–A10 | 承载一次执行的状态（六状态）、参数快照、检查点、追加式任务历史（每次尝试的起止/结果/失败点/计数） | §6.2 明确要求六状态、检查点与任务历史；这些是持久事实，不是调度器状态。任务与来源定义分离后，同一来源可有多个任务（成功后另建关联任务），且任务失败不改变来源配置 |
| C8 | `SourceRecord` | Entity | E12；A12 | 承载到达证据：来源身份、逻辑出处（`source_id`＋`source_record_key`）、到达事件、原始字段值、数据性质、许可、到达时间；原值不可改写 | INV-01“原始记录永不因清洗被改写”与 FR09-06“重复到达不增有效出处”都要求它独立于任务与配置存在；把记录并入任务会使“历史成功记录保留”与“重复到达只追加事件”无法表达 |
| C9 | `GovernanceException` | Entity | E14–E15、E17；A5、A11 | 承载待处置异常项：原因、对象引用（来源记录/任务/批次范围）、待处理状态；**无公开可见性** | “异常移交”必须是可追踪的对象：§6.2 的成功判据以“记录到异常队列”为条件，`AC-IMPL-FR08-01` 要求异常“不得直接成为已公开成果”。用 `AuditEvidence` 代替会把“业务待办”与“动作留痕”混为一类（UC-09/17 已把两者分离）；用 `WorkEntity` 代替会直接违反“异常不是成果” |
| C10 | `AuditEvidence` | Entity | E5、E9、E18、E19；A1、A3–A4、A6、A14 | 承载受保护写入的追加式留痕：来源配置、启动、停止、重试、完成、失败与拒绝的操作者/对象/时间/前后值/理由；供失败定位只读读取 | BR-IMP-10 覆盖 FR05～FR10/14/15 的受保护写入；NF07-01 要求“可从日志定位任务错误”。留痕是“已发生的动作”，与“当前任务状态”“待处理异常项”都不是同一事实 |

## 3. 逐类选择理由

### C1 AdminConsole（Boundary）

- **接收：** M1 打开入口、M8 提交来源定义、M16 创建并请求启动、M46 查询进度、M63 停止、M74 重试、M87 查看历史、M95 未授权请求。
- **显示：** M7 入口、M15 来源定义结果/拒绝、M29 运行中与快照、M31 拒绝启动、M53 进度、M62 成功与单列计数、M69 已停止、M73 失败与失败点、M84 重试中、M86 重试被拒、M94 任务历史、M99 拒绝。
- **不能承担：** 不判断权限、不校验来源获得许可、不推进状态、不计算完成判据；入口是否显示不构成授权（§8）。
- **命名：** 采用 `01-BCE` §2 与 `DGM-USECASE-03` 的 `AdminConsole`（UC-17/18/19 共用入口）；不另设 `CollectionConsole`（UC-17 03 §4 已拒绝；本 UC 与 UC-17 结论一致）。

### C2 SourceAdapter（Boundary）

- **接收：** M34 请求本批输入（API 或文件）；**发送：** M35 请求本批记录（按许可与允许范围）；**应答：** M37 返回本批输入与来源证据，或读取失败/断连反馈。
- **不能承担：** 不决定公开可见性、不做去重/身份判定、不写业务实体、不保存检查点。
- **为何不是实现类：** 它是**外部来源的边界**（`01-BCE` §2：与获准来源交换记录和失败信息，“不自行决定公开/归属”），不是技术适配器实现；具体协议/格式/分页属 B-03（D-10）后续设计，本层不定义。

### C3 CollectionCoordinator（Control）

- **守卫：** 账号与权限（M3–M5、M78、M97）、来源定义（M10）、启动（M22）、停止（M65）、重试（M79）、完成判据（M58）。
- **推进与登记：** 任务创建/状态推进（M23–M27、M59、M70、M80）、来源记录登记（M38）、异常项建立（M42）、计数与检查点（M44）、任务历史（M25、M45）。
- **只读返回：** 进度（M48–M52）、任务历史与失败定位（M89–M93）。
- **不承担：** 不执行清洗/去重/身份归属（M40 只移交）、不判定公开、不建成果、不改写原值、不建调度器/队列。
- **与 `GovernanceCoordinator` 的分界：** 本 UC 只问“是否已登记结果/需异常移交”，不问“为什么合并/为什么排除”。

### C4 GovernanceCoordinator（Control，协作类）

- **本 UC 内的职责：** 接收逐条移交（M40）、返回处置结果或“需异常移交”与原因（M41）。
- **不承担：** 清洗规则、稳定ID匹配、候选生成、合并/拆分、身份/归属判定、发布（FR09/FR15；UC-09/10）。
- **为什么出现在本 UC 的模型里：** §6.2 的成功判据以“记录到结果”为条件，必须有受理方返回“已登记结果”；否则任务无法区分“读到记录”与“记录已完成处置”。
- **命名：** 沿用 `01-BCE` §3 的 `GovernanceCoordinator`；UC-09 的 `DedupCoordinator`、UC-10 的 `IdentityVerificationCoordinator` 是其职责细化，统一命名留 R5（D-11；B-10）。

### C5 User（Entity）

- 只读提供 `accountStatus` 与 `collectionPermission`（是否具备“数据来源/采集”具体管理权限）。
- **不是** `Scholar`（学术身份），**不是** `ScholarAuthorization`（门户对象授权）；不新增角色继承或权限矩阵（§8；B-11）。

### C6 SourceDefinition（Entity）

- **知道：** 来源身份（来源ID/来源域）、输入方式（API 或文件）、许可/使用范围、数据性质（真实/模拟/混合）、允许范围，以及“是否可作为启动前置”的判定事实。
- **不做：** 不保存到达记录（属 `SourceRecord`）、不保存任务状态（属 `CollectionTask`）、不承载启用/停用状态机（停用/更换归 UC-19，B-06）。
- **为何不合并：** 见 §2 C6 与 §5 结论 1。

### C7 CollectionTask（Entity）

- **知道：** 六状态、参数快照（来源定义引用＋范围＋输入方式）、检查点（位置与时间）、追加式任务历史（每次尝试：起止、结果、失败点、处置计数）、关联任务引用（成功后另建）。
- **不做：** 不调度执行、不持有记录内容、不判定异常去向。
- **为何不建 `Checkpoint`/`TaskRun`/`ImportBatch`：** 检查点与尝试历史是**单一任务内追加式**的事实，只需任务标识即可定位；检查点间隔、批次大小未冻结（B-02）。单独建类会把执行分段当作业务对象，并诱导实现层设计。

### C8 SourceRecord（Entity）

- **知道：** 来源身份、逻辑出处、到达事件（首次/重复，含到达时间）、原始字段值、数据性质、许可。
- **不做：** 不改写原值（INV-01）、不承载清洗结果（UC-09 的 `CleanRecord`）、不承载公开状态。
- **与 UC-02/09 的关系：** `SourceRecord` 在 UC-02 作为工作详情出处、在 UC-09 作为清洗输入；本 UC 是它的**写入侧起点**，属性沿用既有定义，不新增语义。

### C9 GovernanceException（Entity）

- **知道：** 异常原因（字段缺失/类型不可识别/许可或范围冲突/读取或交接失败等归因）、对象引用（来源记录、任务、批次范围）、待处理状态。
- **不做：** **无公开可见性**；本 UC 不创建/更新 `WorkEntity`/`Authorship`、不发布；关闭与判定属 UC-09/10。
- **与相邻概念的区别：** 不是 `AuditEvidence`（动作留痕）、不是 `CleanRecord`（清洗产物）、不是 `DedupCandidate`/`IdentityCandidate`（治理候选）、不是 `PendingTodo`（后台投影）。

### C10 AuditEvidence（Entity）

- **知道：** 事件类型（来源配置/启动/停止/重试/完成/失败/拒绝）、操作者（账号或系统）、对象引用（来源定义/任务）、时间、前后值、理由、结果。
- **不做：** 不承载当前状态（`CollectionTask`）、不承载异常项（`GovernanceException`）、不承载材料（`ReviewEvidence`）；不改写、不删除。

## 4. 被拒绝的候选类

| 候选 | 拒绝理由 | 依据 |
|---|---|---|
| `Scheduler` / `TaskScheduler` / `JobRunner` / `ThreadPool` / `Cron` | 调度是实现机制；基线只要求“启动/停止/重试与状态”，未定义调度模型 | FR08；§6.2；教材 §5.4.2 |
| `TaskQueue` / `ImportQueue` / `ExceptionQueue`（队列类） | “异常队列”在本 UC 的可观察形态是 `GovernanceException` 集合；建队列类把存储/投递机制带入分析 | §6.2；BR-UC18-01；A11 |
| `RetryPolicy` / `BackoffRule` / `RetryCounter`（独立类） | 重试上限未冻结（D-10/B-01），且“重试”是任务状态与守卫，不是策略对象 | D-10；B-01；`DGM-STATE-01` |
| `Checkpoint` / `TaskRun` / `CollectionAttempt` / `ImportBatch` | 检查点与尝试历史是单一任务内追加式事实，用 `CollectionTask` 属性表达即可；批次大小与检查点间隔未冻结 | §6.2；B-02 |
| `ApiConnector` / `FileWatcher` / `HttpClient` / `Cursor` / `PageToken` | 接入协议与分页属实现设计（D-10/B-03）；外部交换统一由 `SourceAdapter` 边界承接 | D-10；B-03；教材 §5.4.2 |
| `CollectionConsole` / `SourceConfigUI`（独立采集处置边界） | `AdminConsole` 已是 UC-17/18/19 的统一管理入口；再设一类会把入口切成多份并与 UC-17 结论冲突 | `01-BCE` §2；UC-17 03 §4 |
| `DedupCoordinator` / `CleanRecord` / `DedupCandidate` / `WorkEntity` / `WorkVersion` | 清洗、去重、版本与字段合并属 UC-09；本 UC 只移交并取回处置结果 | FR09；UC-09 |
| `IdentityVerificationCoordinator` / `IdentityCandidate` / `AttributionCandidate` / `Scholar` / `Authorship` | 身份与归属判定属 UC-10；本 UC 不判定、不改关系状态 | FR15；UC-10 |
| `PublicWork` / `PublicVisibility` / `PublishAction` | 发布与公开可见性不属本 UC；异常项无公开可见性 | BR-IMP-01；A11 |
| `PendingTodo` / `WorkflowEngine` / `ApprovalChain` / `Assignee` / `SlaTimer` | 通用工作流实体；FR14“不做通用工作流引擎”，UC-17 已确定待办为只读派生投影 | FR14；UC-17 03 §4 |
| `AuditLog` / `ImportLog` / `ErrorLog`（第二套日志） | 与 `AuditEvidence` 同义重复；NF07-01 的“日志定位”由任务历史＋受控留痕提供 | BR-IMP-10；NF07-01 |
| `RightsPolicy` / `DataOriginPolicy` / `PermissionMatrix` | 许可/数据性质是来源定义与记录的业务属性；权限矩阵未冻结 | FR16；§8；B-11 |
| `MappingDictionary`（机构/领域分类） | 本 UC 无字段映射消息；映射属 UC-09 清洗输入 | FR09；教材 §5.4.2 |
| `BackupJob` / `RestoreJob` / `RtoRpoPolicy` | 备份恢复演练属部署运维（NF04-02，NOT RUN）；本 UC 只保证不删历史、重试不重复 | NF04-02；§5 不纳入事件 |
| `Session` / `CredentialContext` | 已认证请求上下文；基线未定义会话对象（沿用其他 UC） | 教材 §5.4.2 |
| `TaskProgressView` / `ProgressSnapshot` / `CountReport` / DTO | 进度与计数是查询结果的分析态/展示投影，不是持久业务事实 | §5.4.2；B-08 |

## 5. 对象边界结论

1. **四种事实分离：** 配置（`SourceDefinition`）／到达证据（`SourceRecord`）／执行状态与历史（`CollectionTask`）／待处置异常项（`GovernanceException`）。任两者合并都会破坏 INV-01、§6.2 或“异常不是成果”。
2. **两个边界、一个控制、一个协作控制：** `AdminConsole`（管理交互）与 `SourceAdapter`（外部记录交换）都不决策；`CollectionCoordinator` 集中守卫与计数；`GovernanceCoordinator` 只回答“是否已登记结果/需异常移交”，其规则属 UC-09/10。
3. **成功判据只有一个：** “计划输入全部记录到结果或异常队列”；调度、批次、读取完成度都不是判据（BR-UC18-06）。
4. **异常不是成果：** `GovernanceException` 无公开可见性，本 UC 不创建/发布成果；公开资格只能由治理与生效规则产生（A11）。
5. **重试是同一任务的恢复：** 检查点＋任务历史＋失败点定位；不重做已完成项、不新增第二条逻辑出处、不新建重复实体（BR-UC18-09/12）。
6. **不建执行框架类：** 无调度器、队列、运行器、重试策略、检查点类、批次类；这些概念的可观察结果由状态、检查点属性、计数与异常项表达（§4）。

## 6. 识别结论

- Boundary：`AdminConsole`、`SourceAdapter`（2）
- Control：`CollectionCoordinator`、`GovernanceCoordinator`（协作，属主 UC-09/10）（2）
- Entity：`User`、`SourceDefinition`、`CollectionTask`、`SourceRecord`、`GovernanceException`、`AuditEvidence`（6）

合计 **10 个参与类**，全部在 04 顺序图中有消息证明；调度器/队列/运行器/重试策略/检查点类/批次类、治理与判定类、发布类、工作流类均被拒绝（§4）。该集合将在 04/05 接受“每条消息都有依据、每个类都有消息”的双向检验。
