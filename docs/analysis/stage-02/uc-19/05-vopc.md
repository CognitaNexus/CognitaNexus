# UC-19 VOPC（参与类类图）与关系依据

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-VOPC-14-source-update.puml`](diagrams/DGM-VOPC-14-source-update.puml)
**导出：** [SVG](diagrams/generated/DGM-VOPC-14-source-update.svg) · [PNG](diagrams/generated/DGM-VOPC-14-source-update.png)
**方法：** 教材 §5.4.4：VOPC 只来自本用例实现的顺序图对象，关系来自消息和业务约束；不是全局 BCE。

## 1. 参与类集合

VOPC 只包含以下 13 个类：

| 类 | 类型 | 来自顺序图对象 | 事件流依据 |
|---|---|---|---|
| `AdminConsole` | Boundary | 接收 M1、M8、M17、M88、M98、M102、M106；返回 M6、M15、M16、M81、M86、M96、M100、M104、M109 | E1–E3、E17；A1、A9–A13 |
| `SourceAdapter` | Boundary | 接收 M22/M24；发送 M23；返回 M25 | E5；A2/A4 |
| `CollectionCoordinator` | Control | 发起 M2–M6、M9–M10、M12、M14–M16、M18–M20、M22、M26、M28–M30、M32、M34、M36、M38、M40、M42、M44–M45、M47–M49、M51、M53–M55、M57–M60、M62–M63、M65、M67、M69–M70、M72、M74、M76、M78、M80–M81、M84–M86、M89、M90、M92、M94、M96、M99、M100、M103、M104、M107–M109、M111；接收 M2、M11、M13、M18、M21、M25、M27、M31、M33、M35、M37、M39、M41、M43、M46、M50、M52、M56、M61、M64、M66、M68、M71、M73、M75、M77、M79、M83、M87、M91、M93、M95、M97、M101、M105、M110 | E1–E17；A1–A14 |
| `GovernanceCoordinator` | Control（协作，属主 UC-09/10） | 接收 M34、M51、M60；返回 M35、M52、M61 | E8、E12、E13；A5/A10 |
| `User` | Entity | 接收 M3、M4、M108 | E2；A11 |
| `SourceDefinition` | Entity | 接收 M10；返回 M11 | E3；A1（前置守卫） |
| `CollectionTask` | Entity | 接收 M12、M74；返回 M13、M75、M79、M83 | E4、E15、E16；A4 |
| `SourceRecord` | Entity | 接收 M26、M32、M36、M38、M63；返回 M27、M33、M37、M39、M64 | E6–E10、E14；A2/A3/A7/A8 |
| `IncrementalChangeSet` | Entity | 接收 M20、M47、M53、M57、M59、M70、M72、M76、M84；返回 M21、M71、M73、M77、M91 | E5、E11–E16；A1–A7 |
| `WorkEntity` | Entity（只读） | 接收 M40、M49、M65；返回 M41、M50、M66 | E10、E12、E14；A5/A6/A9/A12 |
| `WorkVersion` | Entity（只读） | 接收 M42；返回 M43 | E10/E13；A10 |
| `GovernanceException` | Entity | 接收 M30、M45、M55、M67、M92；返回 M31、M46、M56、M68、M93 | E11、E14；A2/A5/A6/A8 |
| `AuditEvidence` | Entity | 接收 M48、M54、M58、M69、M80、M85、M94、M111；返回 M95 | E11、E12、E14、E16；A4/A6/A11/A14 |

三位参与者（管理员、获准数据来源、未授权请求者）不进入 VOPC：参与者不是系统内部类（教材 §5.4.4）。本 UC 无“系统参与者”边界类；系统职责由 `CollectionCoordinator` 与 `SourceAdapter` 实现。

## 2. 关系与来源

| 关系 | 类型 | 来自消息/约束 | 方向/角色 | 多重性 | 说明 |
|---|---|---|---|---|---|
| `AdminConsole --> CollectionCoordinator` | 单向关联/消息关系 | 请求 M2、M9、M18、M89、M99、M103、M107；返回 M6、M15、M16、M81、M86、M96、M100、M104、M109 | UI 请求；Coordinator 返回 | 不定义持久多重性 | UI 无持久业务属性；按消息方向表达一次用例会话的协作 |
| `CollectionCoordinator --> SourceAdapter` | 单向关联/外部交换关系 | M22 请求本轮输入；M25 返回输入或失败反馈 | 请求；接收 | 不定义持久多重性 | 适配器不连业务实体：不决定公开/归属/去重，不写记录与变更集 |
| `CollectionCoordinator --> GovernanceCoordinator` | 单向关联/移交-回执关系 | M34/M51/M60 移交；M35/M52/M61 回执 | 移交新增/变更生效/版本证据；返回结果或需异常移交 | 不定义持久多重性 | 只移交与取回，不建模清洗/去重/字段优先级/身份归属规则 |
| `CollectionCoordinator --> User` | 单向关联/只读校验关系 | M3、M4、M108 | 索取账号状态与采集权限 | 不定义持久多重性 | 不新增账号属性、不定义权限矩阵 |
| `CollectionCoordinator --> SourceDefinition` | 单向关联/只读关系 | M10 读取；M11 返回 | 读取已获准/来源域/许可与允许范围 | 不定义持久多重性 | **只读**：来源配置写入与停用/更换不属本 UC（B-06） |
| `CollectionCoordinator --> CollectionTask` | 单向关联/生命周期与只读关系 | 读 M12；写检查点与历史 M74；状态 M79/M83 | 读取基线与状态；保存检查点与历史 | 不定义持久多重性 | 只用 §6.2 六状态；不做任务创建与来源配置 |
| `CollectionCoordinator --> SourceRecord` | 单向关联/受限写关系 | 读 M26；写 M32（新到达事件）、M36（检查时间与观察版本）、M38（变更观察）、M63（失效事件） | 读取原值与观察版本；追加观察 | 不定义持久多重性 | 实线表示“只读原值＋追加观察/事件”；既有 `rawValues` 永不改写（INV-01） |
| `CollectionCoordinator --> IncrementalChangeSet` | 单向关联/建立-记录-读取关系 | 建立 M20；记录 M47/M53/M57/M59/M70/M84；核对与读取 M72/M76/M90 | 建立、记录分类与三类时间、幂等核对、只读读取 | 不定义持久多重性 | 不承载任务状态，不保存原值副本 |
| `CollectionCoordinator --> WorkEntity` | 单向关联/**只读**关系 | M40/M41、M49/M50、M65/M66 | 读取生效值、已人工审核字段、公开状态、存在事实 | 不定义持久多重性 | **不写入、不覆盖、不新建、不合并、不发布**（BR-UC19-15） |
| `CollectionCoordinator --> WorkVersion` | 单向关联/**只读**关系 | M42/M43 | 读取既有版本关系与明示证据 | 不定义持久多重性 | 不建立版本关系（DD-08 属 UC-09） |
| `CollectionCoordinator --> GovernanceException` | 单向关联/创建-读取关系 | 建立 M30/M45/M55/M67；只读 M92；返回 M31/M46/M56/M68/M93 | 建立待办；读取状态与计数 | 不定义持久多重性 | 不关闭待办（关闭属 UC-09/10）；不设公开可见性 |
| `CollectionCoordinator --> AuditEvidence` | 单向关联/追加-读取关系 | 追加 M48/M54/M58/M69/M80/M85/M111；只读 M94 | 追加受控写入留痕；读取用于轨迹与错误定位 | 不定义持久多重性 | 不修改、不删除既有留痕（INV-10） |
| `SourceDefinition "1" --> "0..*" SourceRecord` | 业务关联（沿用 UC-18） | FR16“每条来源保留来源ID…许可或使用范围与数据性质”；M10/M26/M32 | 来源身份/许可/范围 | `1` : `0..*` | 每条记录来自一个来源定义；同一来源可有多次到达/观察 |
| `SourceDefinition "1" --> "0..*" IncrementalChangeSet` | 业务关联 | FR10“在已核验来源域按来源ID与上次版本区分”；M10/M14/M20 | 该来源的更新检查 | `1` : `0..*` | 一个来源可有多轮更新；每轮变更集只属于一个来源定义 |
| `CollectionTask "1" --> "0..*" IncrementalChangeSet` | 业务关联 | §6.2“每次重试保留检查点与任务历史”；A7 复跑；M12/M20/M74 | 复跑/分批复检的变更集 | `1` : `0..*` | 同一任务的重试/复跑可产生多个变更集并全部保留，用于检查时间与基线可追溯 |
| `IncrementalChangeSet "1" --> "0..*" GovernanceException` | 业务关联 | FR10“并发冲突进入治理待办”；M45/M55/M67 | 本轮产生的待治理待办 | `1` : `0..*` | 待办按本轮归因以便计数与汇总；一轮可无待办 |
| `SourceRecord "0..1" --> "0..*" GovernanceException` | 业务关联（沿用 UC-18） | `AC-IMPL-FR10-01`；M45/M63/M67 | 待办可指向来源记录/变更集/成果字段 | `0..1` : `0..*` | 待办优先指向具体来源记录；读取失败类待办可只指向变更集/任务；同一记录可有多条待办 |
| `WorkEntity "1" --> "0..*" WorkVersion` | 业务关联（沿用 UC-02/09） | DD-08；`AC-IMPL-FR09-05`；M42 | 明示版本关系 | `1` : `0..*` | 本 UC 不建立该关系，只读确认“是否已有版本关系” |
| `AuditEvidence "0..*" --> "0..1" User` | 业务关联（沿用 UC-09/10/17/18） | BR-IMP-10（操作者）；SPEC §3 执行者类型；M48/M54/M80/M85 | 操作者 | `0..*` : `0..1` | 一条留痕有一个操作者身份；系统自动动作无账号 |

### 2.1 为什么控制/边界到实体的关联不标持久多重性

`AdminConsole`/`SourceAdapter`/`CollectionCoordinator`/`GovernanceCoordinator` 到实体的关联按**消息方向**表达协作，不是长期业务对象链接。为它们标 `1..*` 会把一次更新执行的协调关系误解为持久聚合，甚至误读为“协调者持有全部来源记录、变更集或待办”。因此本 VOPC 只对 7 条业务事实关联（6 条业务关联＋1 条操作者关联）给出多重性。

### 2.2 七条给出多重性的关联的依据

| 多重性 | 为什么不是别的取值 |
|---|---|
| `SourceDefinition 1 : 0..* SourceRecord` | 记录必须有来源（否则许可/数据性质无法追溯，FR16）；来源可以尚无记录（刚配置、尚未更新），故记录侧 `0..*`。沿用 UC-18，不重定义。 |
| `SourceDefinition 1 : 0..* IncrementalChangeSet` | 一个来源可被多轮更新检查；每轮变更集必须恰属于一个来源定义（更新前置守卫 M14 依赖它）。若写成 `0..*` 会允许“没有来源的变更集”，无法判定“已核验来源域”。 |
| `CollectionTask 1 : 0..* IncrementalChangeSet` | 任务可重试/复跑（§6.2；A7），每轮形成一份变更集以保留检查时间与基线；变更集侧不写 `1..*`：任务可因前置拒绝或读取失败而零变更集。 |
| `IncrementalChangeSet 1 : 0..* GovernanceException` | 待办按本轮归因以支持单列计数与汇总（M70/M92）；一轮可无待办（全为新增/未变）。 |
| `SourceRecord 0..1 : 0..* GovernanceException` | 冲突/失效待办优先指向具体来源记录以便定位；读取失败或身份不可确认类待办可只指向变更集/任务，故记录侧可为 `0..1`（沿用 UC-18 结论）。 |
| `WorkEntity 1 : 0..* WorkVersion` | 版本必须属于一个成果实体；无明示证据不建立该关系（DD-08），故版本侧 `0..*`。沿用 UC-02/09，不重定义。 |
| `AuditEvidence 0..* : 0..1 User` | 受保护写入须有身份（BR-IMP-10），但系统自动动作（如完成/失败记录）没有账号主体；写 `1` 会为“系统”虚构账号。沿用 UC-09/10/17/18 结论。 |

### 2.3 写成属性而不是实体关联的事实

| 事实 | 表达方式 | 理由 |
|---|---|---|
| 逐条分类与前后差异 | `IncrementalChangeSet.changeItems[]`（含分类、`previousObservedRevision`、`observedRevision`、差异引用、待办引用、生效时间） | 差异明细在单一变更集内定位与计数；建 `ChangeItem`/`FieldDiff` 会把本轮明细实体化并诱导实现设计（B-08） |
| 基线/上次版本 | `IncrementalChangeSet.baselineRef`＋`SourceRecord.observedRevision` | 基线是“可比较的属性”而非业务对象；建 `Baseline`/`ChangeCursor` 会把游标机制当业务对象（B-02/B-03） |
| 三类时间 | `IncrementalChangeSet.checkedAt`、`sourceReportedUpdatedAt`、`effectiveAt`；`SourceRecord.lastCheckedAt` | FR10 只要求“保留检查与生效时间”，不要求时间线对象；分开三条属性即可表达不可混称 |
| 检查点与任务历史 | `CollectionTask.lastCheckpoint`＋`attemptHistory` | §6.2 要求保留，但只需按任务定位；建 `Checkpoint`/`TaskRun` 会诱导实现层设计（B-01） |
| 待办的公开可见性 | **不建模**：`GovernanceException` 无公开可见性 | 待办永远不是公开数据；建“公开状态”字段会把候选方案写成已批准规则（BR-IMP-01） |
| 待办与治理候选/成果的关系 | 不画：由 UC-09/10 与 FR14 在治理侧处置 | 本 UC 不判定、不合并、不拆分、不发布 |
| 变更集与成果的关系 | 不画：本 UC 不写入成果、不建版本 | 成果侧只读（BR-UC19-15）；画边会暗示本 UC 可改成果 |
| 留痕指向的对象 | `AuditEvidence.targetRefs`＋`targetType` | 审计须覆盖被拒绝与未写入的对象；画边会与各属主关系重复（UC-06/09/10/17/18 同一取舍） |

## 3. 从顺序图到 VOPC 的对应

| 顺序图对象 | VOPC 类 | 保留/删除理由 |
|---|---|---|
| 管理员（Actor） | 不保留 | 参与者不是系统类；发起 M1/M8/M17/M88/M98/M102 |
| 获准数据来源（Actor） | 不保留 | 参与者不是系统类；接收 M23、应答 M24 |
| 未授权请求者（Actor） | 不保留 | 参与者不是系统类；只触发 M106 与接收 M110 |
| AdminConsole | 保留 | 接收管理交互并显示基线/局限、冲突、成功、失败、汇总与拒绝 |
| SourceAdapter | 保留 | 与外部来源交换记录、变更/删除报告与失败信息 |
| CollectionCoordinator | 保留 | 守卫、分类、时间记录、移交、待办、检查点、完成判据与留痕 |
| GovernanceCoordinator | 保留 | 接收新增/生效/版本证据移交并回执（协作类） |
| User | 保留 | 接收 M3/M4/M108 |
| SourceDefinition | 保留 | 接收 M10，应答 M11（只读） |
| CollectionTask | 保留 | 接收 M12/M74，应答 M13/M75/M79/M83 |
| SourceRecord | 保留 | 接收 M26/M32/M36/M38/M63，应答 M27/M33/M37/M39/M64 |
| IncrementalChangeSet | 保留 | 接收 M20/M47/M53/M57/M59/M70/M72/M76/M84，应答 M21/M71/M73/M77/M91 |
| WorkEntity | 保留 | 接收 M40/M49/M65，应答 M41/M50/M66（只读） |
| WorkVersion | 保留 | 接收 M42，应答 M43（只读） |
| GovernanceException | 保留 | 接收 M30/M45/M55/M67/M92，应答 M31/M46/M56/M68/M93 |
| AuditEvidence | 保留 | 接收 M48/M54/M58/M69/M80/M85/M94/M111，应答 M95 |
| 无其他对象 | — | 没有 `Scheduler`/`TaskQueue`/`JobRunner`/`RetryPolicy`/`Checkpoint`/`IncrementalWindow`/`ChangeCursor`/`ChangeItem`/`DedupCandidate`/`CleanRecord`/`IdentityCandidate`/`PublishAction`/`PendingTodo` 的消息 |

## 4. 排除项与理由

- `Scheduler` / `TaskScheduler` / `JobRunner` / `Cron`：调度是实现机制，基线未定义调度模型（FR08/FR10；§6.2）。
- `TaskQueue` / `ImportQueue` / `ExceptionQueue`：队列是存储/投递机制；“异常队列”在本 UC 的可观察形态是 `GovernanceException` 集合（§6.2）。
- `RetryPolicy` / `BackoffRule` / `RetryCounter`：重试上限未冻结（D-10）；重试是任务状态与守卫。
- `Checkpoint` / `TaskRun` / `UpdateAttempt` / `Batch`：检查点与尝试历史是任务内追加式属性；批次大小与间隔未冻结（B-01）。
- `IncrementalWindow` / `ChangeCursor` / `SyncCursor` / `Baseline`：基线是可比较属性（`observedRevision`＋`baselineRef`）；建类会把游标机制当业务对象（B-02/B-03）。
- `StreamingSubscriber` / `ChangeFeed` / `Webhook` / `EventStream`：FR10 明确“采用批次复跑/安全检查点，不强制实时流式同步”；实时订阅/推送无需求依据。
- `ChangeItem` / `FieldDiff` / `ChangeLog`：逐条分类与差异在单一变更集内定位，用属性表达（B-08）。
- `ApiConnector` / `FileWatcher` / `HttpClient` / `Cursor` / `PageToken`：接入实现与分页（D-10/B-03）；外部交换由 `SourceAdapter` 承接。
- `SourceUpdateUI` / `UpdateConsole`：`AdminConsole` 已是 UC-17/18/19 统一入口（`01-BCE` §2；UC-17/18 03 §4）。
- `DedupCoordinator` / `CleanRecord` / `DedupCandidate` / `FieldPriorityRule`：属 UC-09；本 UC 只移交并取回结果。
- `IdentityVerificationCoordinator` / `IdentityCandidate` / `AttributionCandidate` / `Scholar` / `Authorship`：属 UC-10；本 UC 不判定、不改关系状态。
- `PublicWork` / `PublicVisibility` / `PublishAction` / `DelistAction`：发布、下架与公开可见性不属本 UC（BR-IMP-01；CL-12）。
- `PendingTodo` / `WorkflowEngine` / `ApprovalChain` / `Assignee` / `SlaTimer`：FR14“不做通用工作流引擎”；UC-17 已确定待办为只读派生投影。
- 第二套日志（`AuditLog`/`ImportLog`/`ErrorLog`）：与 `AuditEvidence` 同义重复（BR-IMP-10；NF07-01）。
- `RightsPolicy` / `DataOriginPolicy` / `PermissionMatrix`：许可与数据性质是属性，权限矩阵未冻结。
- `BackupJob` / `RestoreJob` / `RtoRpoPolicy`：NF04-02 属部署运维（NOT RUN）；本 UC 只保证不删历史、复跑不重复。
- `Session` / `CredentialContext`：已认证请求上下文，不建模（沿用其他 UC）。
- `ChangeSummaryView` / `ProgressSnapshot` / `CountReport` / DTO：查询结果与展示投影，不是业务事实（B-08）。
- `VersionEvidence`：明示版本证据是 `WorkVersion` 与来源记录的属性；判定规则属 UC-09 DD-08。

## 5. VOPC 完整性检查

| 检查 | 结果 |
|---|---|
| 只包含 UC-19 参与类 | 是，13 个 |
| 每个类有事件流/消息依据 | 是，见表 1/表 3 |
| 每条关系可追溯到消息或业务约束 | 是，见表 2 |
| 不把 Actor 画成系统类 | 是；三位参与者不进入 VOPC |
| 不用全局 BCE 冒充 VOPC | 是；本图独立于 `DGM-CLASS-01-analysis.puml` |
| 关系多重性是否有依据 | 6 条业务关联＋1 条操作者关联（共 7 条）有依据（§2.2）；其余按消息方向，不定义持久多重性（§2.1） |
| 是否出现执行/比较框架类 | 否：无 `Scheduler`/`TaskQueue`/`JobRunner`/`RetryPolicy`/`Checkpoint`/`IncrementalWindow`/`ChangeItem` |
| 是否出现通用工作流实体 | 否：无 `PendingTodo`/`WorkflowEngine`/`Assignee`/`SlaTimer` |
| 是否创建/更新/发布成果 | 否：`WorkEntity`/`WorkVersion` 只有只读消息；无发布/下架消息 |
| 是否覆盖人工审核字段 | 否：无写 `WorkEntity` 的消息；冲突只建待办（M44–M48） |
| 是否执行去重/身份/版本归并判定 | 否：对 `GovernanceCoordinator` 只有 M34/M51/M60 与回执；版本关系不建立 |
| 是否把待办与留痕混为一类 | 否：`GovernanceException`（业务待办）与 `AuditEvidence`（动作留痕）分离 |
| 是否把来源配置与到达证据混为一类 | 否：`SourceDefinition`（只读配置）与 `SourceRecord`（到达证据）分离 |
| 是否把变更集与任务状态混为一类 | 否：`CollectionTask`（执行状态/检查点/历史）与 `IncrementalChangeSet`（本轮比较结果）分离 |
| 是否新增任务状态 | 否：只用 §6.2 六状态（M79/M83） |
| 是否引入设计技术（表/接口/队列/索引/缓存） | 否 |

## 6. 与全局图的关系

本 VOPC 是 UC-19 的局部静态视图，不是全系统类图。与 `DGM-CLASS-01-analysis.puml` 的异同、命名合并建议见[07-与全局模型差异](07-global-model-diff.md)。R5 统一分析类时应以本 VOPC 为输入之一，而不是用全局图反推本 UC 的参与类；特别是：

- **不应**据全局 `CollectCtl ..> Task` 把本 UC 扩成“任务创建/来源配置”协调者（那属 UC-18）；
- **不应**据全局 `GovernCtl ..> Work` 让本 UC 直接写成果字段（字段规则与合并属 UC-09）；
- **不应**据全局 `AdminUI ..> CollectCtl/GovernCtl` 把外部来源交换并入管理入口；
- **不应**把 `Version` 与“来源侧记录版本”合并（见 06 §13 的三种版本语义）。
