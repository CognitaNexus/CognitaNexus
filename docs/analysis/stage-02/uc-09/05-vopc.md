# UC-09 VOPC（参与类类图）与关系依据

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-VOPC-10-dedup.puml`](diagrams/DGM-VOPC-10-dedup.puml)
**导出：** [SVG](diagrams/generated/DGM-VOPC-10-dedup.svg) · [PNG](diagrams/generated/DGM-VOPC-10-dedup.png)
**方法：** 教材 §5.4.4：VOPC 只来自本用例实现的顺序图对象，关系来自消息和业务约束；不是全局 BCE。

## 1. 参与类集合

VOPC 只包含以下 9 个类：

| 类 | 类型 | 来自顺序图对象 | 事件流依据 |
|---|---|---|---|
| `DedupGovernanceUI` | Boundary | M1、M2、M8、M15、M17、M23、M30、M33、M37、M39、M41、M45、M50、M51、M53、M55、M56、M60、M61、M62、M66、M70、M72、M79、M84、M88、M94、M95、M96 | E1、E14–E16、A1、A3–A8、A11、A13、A16 |
| `DedupCoordinator` | Control | M2–M7、M9–M50、M53–M55、M57–M60、M63–M95 | E2–E17、A1–A13、A16 |
| `User` | Entity | M54（接收） | E15、A16 |
| `SourceRecord` | Entity | M3、M19、M20（接收） | E2、E3、E7、A2、A15 |
| `CleanRecord` | Entity | M5、M9、M11、M12、M13、M14、M16、M18、M59、M86、M89、M92（接收） | E2–E6、E13、E15、E16、A1、A4、A8、A12 |
| `DedupCandidate` | Entity | M31、M35、M36、M38、M40、M48、M58、M64、M68、M77、M82（接收） | E9、E10、E15、E16、A3、A5–A7、A10 |
| `WorkEntity` | Entity | M24、M27、M28、M42、M43、M67、M74、M75、M76、M80、M81、M85（接收）；M25（应答） | E8、E9、E11、E12、E16、A11 |
| `WorkVersion` | Entity | M46（接收） | E11、A10 |
| `AuditEvidence` | Entity | M6、M22、M29、M32、M44、M47、M57、M65、M69、M73、M78、M83、M87、M91、M93（接收） | E2、E7–E9、E11、E14、E16、E17、A1、A2、A9、A11、A13、A16 |

两名参与者（来源采集/增量任务、治理管理员）不进入 VOPC：参与者不是系统内部类（教材 §5.4.4）。

## 2. 关系与来源

| 关系 | 类型 | 来自消息/约束 | 方向/角色 | 多重性 | 说明 |
|---|---|---|---|---|---|
| `DedupGovernanceUI --> DedupCoordinator` | 单向关联/消息关系 | M2、M53 请求；M7/M15/M17/M23/M30/M33/M37/M39/M41/M45/M50/M55/M60/M66/M70/M72/M79/M84/M88/M94/M95 返回 | UI 请求；Coordinator 返回结果 | 不定义持久多重性 | UI 无持久业务属性；按消息方向表达一次用例会话的协作 |
| `DedupCoordinator --> User` | 单向关联/使用关系 | M54 | 索取账号状态与治理处置权限事实 | 不定义持久多重性 | 协调器不是账号容器 |
| `DedupCoordinator --> SourceRecord` | 单向关联/**受限写**关系 | M3/M19（读原值与逻辑出处）；M20（追加重复到达事件） | 读取原始证据；仅追回到达事件 | 不定义持久多重性 | 实线表示“只读原值＋唯一写入是到达事件”；不改写 `rawValues`（INV-01；CL-10） |
| `DedupCoordinator --> CleanRecord` | 单向关联/读-写关系 | M5/M9/M11/M12/M13/M14/M16/M18（建立与置状态）；M59（读冲突项）；M86/M89/M92（关闭/修正/忽略） | 建立规范记录、置治理状态、维护冲突项 | 不定义持久多重性 | 规范值与原值并存，可回放（CL-11） |
| `DedupCoordinator --> DedupCandidate` | 单向关联/创建-推进关系 | M31/M35/M36/M38/M40/M48（生成与标记）；M58（读）；M64/M68/M77/M82（置已处理） | 生成候选与证据、读取、关闭 | 不定义持久多重性 | 候选状态：`候选待核验/确定不同/已处理`（B-06） |
| `DedupCoordinator --> WorkEntity` | 单向关联/读-写关系 | M24/M25（按标识查找）；M27/M28（关联出处与字段合并）；M42/M43/M67（新建）；M74–M76（合并）；M80/M81（拆分）；M85（人工确认值） | 查找、新建、合并/拆分、字段写入 | 不定义持久多重性 | 不删除实体；合并只标“已合并”并重定向 |
| `DedupCoordinator --> WorkVersion` | 单向关联/创建关系 | M46 | 只在明示证据时建立版本关系 | 不定义持久多重性 | 无证据不建立（DD-08） |
| `DedupCoordinator --> AuditEvidence` | 单向关联/创建关系 | M6/M22/M29/M32/M44/M47/M57/M65/M69/M73/M78/M83/M87/M91/M93 | 记录自动判定、异常、重复到达、合并、拆分、字段确认、修正、忽略与拒绝 | 不定义持久多重性 | 审计指向的对象是属性 `targetRefs`，不另画实体关联（§2.3） |
| `SourceRecord "1" --> "0..*" CleanRecord` | 业务关联 | CL-10/CL-11；FR09-S01/S05；M3/M9 | 规范化来源 | `1` : `0..*` | 一条逻辑出处按规则/字典版本可产生多条规范记录（重清洗）；每条规范记录只源于一条来源记录 |
| `DedupCandidate "0..*" --> "1" CleanRecord` | 业务关联 | DD-04～DD-07；FR09-S07/S11；M31/M35/M58 | 待核验的规范化记录 | `0..*` : `1` | 一条规范记录可先后产生多条候选（不同召回窗口/重新判定）；每条候选只判定一条记录 |
| `DedupCandidate "1" --> "0..*" WorkEntity` | 业务关联 | DD-05/DD-07/DD-12；FR09-S07；M35/M58 | 候选实体清单 | `1` : `0..*` | 一条候选可对照多个候选实体；“无候选”时该侧为空（走新建或确认不同） |
| `WorkEntity "1" --> "0..*" SourceRecord` | 业务关联 | INV-02；BR-302；DD-11；`AC-IMPL-FR09-01`；UC-02 | 出处 | `1` : `0..*` | 一项成果可有多条来源；重复来源不增加实体；来源不因合并/拆分被删除 |
| `WorkEntity "1" --> "0..*" WorkVersion` | 业务关联 | DD-08；`AC-IMPL-FR09-05`；`AC-IMPL-FR16-02`；UC-02 | 明示版本关系 | `1` : `0..*` | 无明示证据不建立该关系；版本数不增加实体数 |
| `WorkVersion "0..*" --> "1" SourceRecord` | 业务关联 | DD-08；FR09-S08；M46；UC-02 §13“留待 UC-09/16” | 明示版本指向（版本证据来源） | `0..*` : `1` | 每条版本关系必须有且只有一条可追溯的明示证据来源；同一来源记录可同时支撑两条版本关系（例如同时给出预印本与正式版指向） |

### 2.1 为什么控制/边界到实体的关联不标持久多重性

`DedupGovernanceUI`/`DedupCoordinator` 到实体的关联按**消息方向**表达协作，不是长期业务对象链接。为它们标 `1..*` 会把一次批次治理的协调关系误解为持久聚合关系。因此本 VOPC 只对业务事实之间的关联给出多重性：`SourceRecord→CleanRecord`、`DedupCandidate→CleanRecord`、`DedupCandidate→WorkEntity`、`WorkEntity→SourceRecord`、`WorkEntity→WorkVersion`、`WorkVersion→SourceRecord`。

`DedupCoordinator --> SourceRecord` 用**实线**：本 UC 对来源记录的唯一写入是“追加重复到达事件”（CL-10），`rawValues` 永不改写（INV-01）。若画成只读虚线会漏掉 A2 的幂等留痕；若标成普通可写关系又会暗示可改原值，故在标签与 06 §9 中同时写明“只读原值＋仅追回到达事件”。

### 2.2 为什么 `WorkEntity→SourceRecord`、`WorkEntity→WorkVersion`、`WorkVersion→SourceRecord` 的措辞与 UC-02 一致或衔接

- UC-02 已把 `WorkEntity 1 : 0..* SourceRecord`（出处）与 `WorkEntity 1 : 0..* WorkVersion`（明示版本）定为已定义关系，本 UC 沿用，不重新定义字段语义（只增加“合并/拆分不删除出处”的约束）。
- UC-02 的 06 §13 明确“`SourceRecord — WorkVersion`：本 UC 不建立（版本与出处是不同事实；本 UC 无消息），留待 UC-09/16”。本 UC 有 M46 的消息与 DD-08 的规则，因此**在这里建立** `WorkVersion 0..* : 1 SourceRecord`，并把该跨 UC 结论登记到 07 §4。这是“补上 UC-02 明示预留的关系”，不是改变其语义。

### 2.3 三条不画成实体关联的关系

| 未画的关系 | 为什么 | 由什么表达 |
|---|---|---|
| `CleanRecord → WorkEntity`（记录归入哪个实体） | 归入关系在语义上就是“该逻辑出处成为该实体的出处”；再画一条边会与 `WorkEntity→SourceRecord` 重复，并在“异常/待核验无归属”时产生悬空边 | `WorkEntity 1→0..* SourceRecord` ＋ `CleanRecord.matchOutcome`（命中/新建/待核验/确定不同）与 `matchedEntityRef` 属性 |
| `WorkEntity → WorkEntity`（合并链/旧ID重定向） | 合并链是**追加式历史**：来源实体标记“已合并”、旧标识重定向到目标实体；自关联会把“历史链”画成“当前指针”，且无法表达撤销与拆分 | `WorkEntity.redirectRefs`（当前重定向）＋ `AuditEvidence`（合并/拆分/撤销记录，含依据、执行者、前后归属） |
| `AuditEvidence → 被写对象`（关系/成果/候选/来源） | 审计必须覆盖“被拒绝、未发生写入”的对象，且不进入公开视图；画成实体关联会与公开模型混淆 | `AuditEvidence.targetRefs` 属性（与 UC-06/UC-07/UC-08/UC-22 一致） |

## 3. 从顺序图到 VOPC 的对应

| 顺序图对象 | VOPC 类 | 保留/删除理由 |
|---|---|---|
| 来源采集/增量任务（Actor） | 不保留 | 参与者不是系统类 |
| 治理管理员（Actor） | 不保留 | 参与者不是系统类 |
| DedupGovernanceUI | 保留 | 接收 M1/M52/M62，返回 M7–M8、M15、M17、M23、M30、M33、M37、M39、M41、M45、M50–M51、M55–M56、M60–M61、M66、M70、M72、M79、M84、M88、M94、M96 |
| DedupCoordinator | 保留 | 协调 M3–M7、M9–M50、M53–M55、M57–M60、M63–M95 及全部判定与写入 |
| User | 保留 | 接收 M54 |
| SourceRecord | 保留 | 接收 M3、M19、M20 |
| CleanRecord | 保留 | 接收 M5、M9、M11–M14、M16、M18、M59、M86、M89、M92 |
| DedupCandidate | 保留 | 接收 M31、M35、M36、M38、M40、M48、M58、M64、M68、M77、M82 |
| WorkEntity | 保留 | 接收 M24、M27、M28、M42、M43、M67、M74–M76、M80、M81、M85；应答 M25 |
| WorkVersion | 保留 | 接收 M46 |
| AuditEvidence | 保留 | 接收 M6、M22、M29、M32、M44、M47、M57、M65、M69、M73、M78、M83、M87、M91、M93 |
| 无其他对象 | — | 没有 CollectionTask/Scholar/Authorship/CorrectionRequest/PendingTodo/CollaborationEdge/Session 的消息 |

## 4. 排除项与理由

- `CollectionTask` / `SourceAdapter` / `IngestCheckpoint`：采集任务与来源接入属 FR08（UC-18）；本 UC 只消费“已到达的记录”。
- `IncrementalChangeSet`：增量识别与恢复属 FR10（UC-19）。
- `Scholar` / `IdentityCandidate` / `ScholarMerge` / `AttributionAssignment`：身份消歧与归属改派属 FR15（UC-10）；本 UC 只保留原署名（A14）。
- `Authorship` / `CorrectionRequest` / `ClaimApplication`：参与关系状态、学术纠错申请与认领审核是其他对象与其他 UC（UC-08/UC-05/UC-06）。
- `ReviewEvidence`：认领/纠错提交的审核材料与可见范围；本 UC 不接收申请人材料。
- 检索/详情/门户/统计/网络类（`SearchCoordinator`、`WorkDetailCoordinator`、`PublicProfileUI`、`AnalyticsNetworkCoordinator`、`CollaborationEdge`）：下游消费者（UC-01/UC-02/UC-03/UC-11/UC-14）。
- `PendingTodo` / `DisputeCase` / `Notification` / `SlaTimer` / `ReviewerRole`：待办队列、超期、通知与审批链属 FR14/UC-17；SLA 未定义（B-06）。
- 独立 `MergeRecord` / `MergeChain` / `GovernanceProcessingItem`：分别由 `WorkEntity.redirectRefs` ＋ `AuditEvidence`、`CleanRecord.governanceState` ＋ `DedupCandidate.matchState` 表达（§2.3；03 §4）。
- `FieldPolicy` / `SimilarityScorer` / `ThresholdPolicy`：字段优先级、相似度权重与阈值未批准（SPEC §5.3 明确“不生效”）；B-02/B-03。
- `MappingDictionary` / `TaxonomyTree`：由 FR14 维护，本 UC 只消费结果并降级（CL-05；A12）。
- `PublicationSubmission` / `Manuscript` / `PublishAction`：基线不提供投稿/出版与发布流水线。
- `EvidenceFile` / `Attachment` / `RawPayloadStore`：存储形态属实现设计。
- `Session` / `CredentialContext`：已认证系统/账号上下文是 M2/M53 的输入；基线未定义会话对象（B-09）。
- `AuditCoordinator`（全局 `AuditCtl`）：审计查询与集中治理属 UC-17；本 UC 只写留痕。
- 批次汇总视图 / DTO / 进度计数器：交互态与投影，不是业务事实。

## 5. VOPC 完整性检查

| 检查 | 结果 |
|---|---|
| 只包含 UC-09 参与类 | 是，9 个 |
| 每个类有事件流/消息依据 | 是，见表 1/表 3 |
| 每条关系可追溯到消息或业务约束 | 是，见表 2；未画的 3 条关系已说明（§2.3） |
| 不把 Actor 画成系统类 | 是；两名参与者（外部任务、管理员）不进入 VOPC |
| 不用全局 BCE 冒充 VOPC | 是；本图独立于 `DGM-CLASS-01-analysis.puml` |
| 关系多重性是否有依据 | 6 条业务关联有依据（§2、§2.2）；控制/边界到实体不定义持久多重性（§2.1） |
| 是否把 `SourceRecord` 与 `CleanRecord` 混为一类 | 否：原始证据 vs 可回放的规范记录 |
| 是否把 `WorkEntity` 与 `WorkVersion` 混为一类 | 否：成果身份 vs 发布形态（无证据不建立） |
| 是否把 `DedupCandidate` 与 `WorkEntity` 混为一类 | 否：未生效判定 vs 生效事实 |
| 是否把 `AuditEvidence` 与上面四者混为一类 | 否：动作留痕，覆盖拒绝与异常，追加式 |
| 是否引入设计技术 | 否 |
| 是否出现采集任务、身份判定、发布、删除成果 | 否 |

## 6. 与全局图的关系

本 VOPC 是 UC-09 的局部静态视图，不是全系统类图。与 `DGM-CLASS-01-analysis.puml` 的异同、命名合并建议与 `GovernCtl` 职责拆分建议见[07-与全局模型差异](07-global-model-diff.md)。R5 统一分析类时应以本 VOPC 为输入之一，而不是用全局图反推本 UC 的参与类。
