# UC-09 Boundary / Control / Entity 识别

**版本：** V1.0（2026-10-21）
**方法：** 教材 §5.4.2：从用例事件流识别边界类、控制类、实体类；不从现有全局 BCE 反向填充。
**输入：** 本目录 01 规约、02 事件流。

## 1. 识别原则

1. **Boundary**：与外部参与者交互、接收输入或显示结果的对象。
2. **Control**：协调一个用例内的校验、规范性判定、匹配/候选判定、幂等、处置守卫、失败处理和结果返回；不是实现层 Controller/Service/清洗作业。
3. **Entity**：承载业务事实、状态和持久信息；不是数据库表、不是检索索引。
4. 只有在该 UC 事件流中实际承担消息的对象才进入 VOPC。
5. 参与者（来源采集/增量任务、系统治理处理、治理管理员）不是系统分析类。
6. 仅因“以后可能用到”而出现的类不进入本 UC（阈值策略、相似度算法、采集任务、待办队列等）。

## 2. 候选分析类

| ID | 候选类 | 类型 | 从哪些事件发现 | 业务行为 | 为什么不能由其他候选直接承担 |
|---|---|---|---|---|---|
| C1 | `DedupGovernanceUI` | Boundary | E1、E14–E16、A1、A3–A8、A16 | 接收“治理批次交付”与“处置动作＋理由”；显示批次结论（命中/新建/候选/异常计数）、候选清单与逐项证据、异常原因与缺项、字段冲突与双方来源、拒绝（无权限）、失败与重试 | `CleanRecord`/`WorkEntity` 是业务实体，不承担输入与显示；`DedupCoordinator` 是内部协调者，不直接面向参与者 |
| C2 | `DedupCoordinator` | Control | E2–E17、A1–A16 | 结构校验、类型最小字段校验、规范化与标识归一、幂等定位、稳定标识匹配与证据相容判定、候选生成与证据清单、版本判定、字段合并、批次计数、处置守卫（权限/循环/版本）、合并/拆分/字段确认执行、留痕与结果返回 | 守卫同时依赖 `User`、`SourceRecord`、`CleanRecord`、`DedupCandidate`、`WorkEntity`、`WorkVersion` 六方事实；任何单一 Entity 只能自知；且必须保证“拒绝无副作用、重复不增、标题不合并、历史不覆盖” |
| C3 | `User` | Entity | E15、A16 | 提供账号标识、账号状态与“治理处置权限”事实，回答“该处置者是否有权执行合并/拆分/字段确认/忽略” | 账号状态与处置权限属账号事实，不属于 `CleanRecord`/`AuditEvidence`；`SourceRecord` 只描述来源，不描述处置者 |
| C4 | `SourceRecord` | Entity | E2、E3、E7、A2、A15 | 只读提供来源身份、来源内记录键、原始字段值、许可/使用范围、到达事件、数据性质、原站地址；重复到达时追加到达事件 | 原始证据与规范值必须分离（INV-01/CL-11）；把规范化写回来源记录会破坏“原始记录永不因清洗被改写” |
| C5 | `CleanRecord` | Entity | E2–E6、E13、A1、A4、A8、A12、E16（字段确认/修正/忽略） | 承载一条逻辑出处的清洗产物：逐字段原值与规范值、规则/字典版本、时间事件语义与精度、标识归一结果与作用域、治理状态（有效/异常待处理/待核验/已处理）、异常原因、字段冲突项与已确认值标记 | 不能并入 `SourceRecord`（原值不可改写）；不能并入 `WorkEntity`（一条来源记录可能异常/待核验而暂不归属任何实体；同一实体来自多条来源记录，规范值是逐字段的） |
| C6 | `DedupCandidate` | Entity | E9、E10、E15、E16、A3、A5–A7、A10 | 承载一次待核验判定：被判定的规范记录、候选实体清单、逐项证据（标题/年份/参与者/类型 命中或冲突）、候选规模与截断标注、匹配状态（候选待核验/确定不同/已处理）、理由与处置依据 | “待核验”要求候选与证据**与生效实体并存且可区分**；`WorkEntity` 只承载生效值；`AuditEvidence` 只记录已发生动作。候选不得由实体自己“决定是否合并” |
| C7 | `WorkEntity` | Entity | E8、E11、E12、E16、A11 | 承载去重后的成果实体：类型、规范生效值（含已人工确认值）、公开生效状态、出处集合、数据性质、合并重定向；接受新建、关联出处、字段合并、合并/拆分、写入人工确认值；**不删除** | 不能并入 `CleanRecord`（规范记录是逐来源的，实体是跨来源唯一的）；不能并入 `SourceRecord`（来源数≠实体数，INV-02）；不能并入 `WorkVersion`（版本是发布形态，不是成果身份） |
| C8 | `WorkVersion` | Entity | E11、A10 | 承载有明示证据的版本关系（版本种类、明示版本指向、关联来源、可公开性）；无证据时不存在该关系 | 版本与出处是不同事实（UC-02 已把该关系留待 UC-09/16）；把版本写进 `SourceRecord` 无法表达“同一实体的两个发布形态” |
| C9 | `AuditEvidence` | Entity | E2、E7、E8、E9、E11、E14、E16、E17、A1、A2、A9、A11、A13、A16 | 保存自动关联、新建、待核验生成、异常入队、重复到达、版本归并、合并、拆分、字段确认、修正、忽略与拒绝的可追溯留痕：动作、对象、依据、执行者类型与身份、前后值、时间；承载合并链/拆分链（追加式） | 留痕必须覆盖“未发生写入”的拒绝与异常；`CleanRecord`/`WorkEntity` 只承载当前生效事实；历史治理记录不得被覆盖（BR-382），不能仅靠实体字段保存 |

## 3. 逐类选择理由

### C1 DedupGovernanceUI（Boundary）

- 发现于 E1 批次交付、E14 批次结论、E15 处理项与证据、E16 处置动作输入、A1/A3–A8 的异常/候选/缺项提示、A16 的拒绝显示。
- 无持久业务属性；批次/处理项选择、处置动作与理由、结果展示属单次交互数据。
- **为什么只有一个边界（不拆“批次入口”与“核验界面”）：** 批次交付与人工核验共享同一治理结论集合（批次计数、候选、异常），且交付方是外部系统参与者而非人工角色；拆分会形成两个边界共享同一展示状态。该取舍登记为 R5 建议（07 §3）。
- **命名说明：** 全局 BCE 文本用 `AdminConsole` 覆盖 UC-17/18/19 的待办/来源/任务/审计，用 `SourceUI` 覆盖来源接入，用 `GovernCtl` 覆盖清洗/去重/候选；全局图**没有**本 UC 的治理处置边界。本 UC 用 `DedupGovernanceUI` 明确“治理批次结论 + 候选核验 + 合并/拆分/字段确认”的写入口语义，与读侧 `PublicUI`/`SearchUI` 分离；合并/改名留 R5（B-10；见 07 §2）。

### C2 DedupCoordinator（Control）

- 发现于 E2–E17 的校验、规范化、匹配、候选、版本、合并、计数、守卫、执行与留痕，以及 A1–A16 的失败与拒绝处理。
- 需要跨六类实体协调，并保证：①异常记录不进入公开实体；②重复不增计数；③无稳定标识不自动合并；④版本必带证据；⑤已确认值不被覆盖；⑥历史不被覆盖、失败不留部分合并。
- 判定顺序（类型隔离 → 标识精确匹配 → 候选 → 版本 → 计数）跨越多个对象，不能下推到单个实体，否则无法同时满足“不任选候选/不跨类型/不猜辖区/不改原值”。
- **命名说明：** 全局只有 `GovernCtl`（“清洗/去重/候选”）与 `CollectCtl`（“任务/检查点/重试”）。本 UC 的协调者只做记录级治理，不控制任务生命周期；建议 R5 把 `GovernCtl` 的职责明确拆成“治理判定（本 UC）”与“任务控制（UC-18/19）”（见 07 §4）。

### C3 User（Entity）

- 发现于 E15 处置权限校验、A16 停用/无权限拒绝。
- 必要业务事实：账号标识、账号状态、治理处置权限；不新增账号属性，权限划分沿用 FR14 与 NF03 的“是否具备对应权限”。
- 不能并入 `AuditEvidence`：留痕记录“谁做了什么”，不能作为权限判定依据（事后记录≠事前授权）。
- 不能并入 `SourceRecord`：来源的身份与许可不是平台账号。

### C4 SourceRecord（Entity）

- 发现于 E2 读取来源身份与类型线索、E3 读取原值、E7 幂等定位、A2 追加到达事件、A15 不删除历史。
- 必要业务事实：`sourceId`、`sourceDomain`、`sourceRecordKey`、`rawValues`、`rightsScope`、`arrivalAt`/到达事件、`dataOrigin`、`originalUrl`（沿用 UC-02 字段）。
- 不能并入 `CleanRecord`：原值与规范值必须分离并可回放（INV-01；CL-11）；重复到达只追加到达事件。
- 本 UC **只读**原值（唯一写入是追加到达事件），**不删除**。

### C5 CleanRecord（Entity）

- 发现于 E2–E6 的校验与规范化、E13 未知标记、E16 的字段确认/修正/忽略、A1/A4/A8/A12 的异常与部分成功。
- 必要性来自三个同时成立的可观察要求：①“原始/规范值、适配规则版本、映射字典版本、出处、审核决定可回放追溯”（CL-11）；②“异常记录”与“待核验记录”都必须可被管理员打开并查看原因与原始内容（FR09-S11）；③“已确认值优先、冲突不静默覆盖”需要逐字段记录确认状态（CL-09）。三者都无法由不可改写的 `SourceRecord` 或跨来源唯一的 `WorkEntity` 表达。
- 不能并入 `DedupCandidate`：候选只在“需要比对实体”时存在；异常记录（缺名称/类型不支持/日期非法）从不进入匹配，却仍需要规范记录与原因。
- **待确认：** 模块细化 FR09-S11 的“处理项”五态（待处理/处理中/已处理/已忽略/已转人工复核）是否应作为独立对象；本 UC 用 `CleanRecord.governanceState`＋`DedupCandidate.matchState` 表达可观察子集，队列/超期/转交属 UC-17（B-06）。

### C6 DedupCandidate（Entity）

- 发现于 E9 生成、E10 待核验、E15 读取、E16 处置、A3/A5–A7/A10 的候选与证据分支。
- 必要性来自 `AC-IMPL-FR09-02` 与 SPEC DD-04/DD-07/DD-12：必须能回答“这条记录有哪些候选、每项证据是命中还是冲突、候选有多少、为什么没自动合并”，且该判定在管理员处置前**不改动**任何实体。
- 不能并入 `WorkEntity`：候选指向多个实体且可以没有实体（确认不同/确认新实体）。
- 不能并入 `AuditEvidence`：候选是“待决定的内容＋状态”，审计是“已发生的动作”。
- **待确认：** 疑似版本关联是否作为候选子类型（B-05）；候选截断长度与排序展示规则（B-04）。

### C7 WorkEntity（Entity）

- 发现于 E8 查找与关联、E12 字段合并、E11 版本归属、E16 合并/拆分/字段确认、A11 循环与回滚。
- 必要业务事实：`stableId`（canonical id）、`workType`、规范生效字段（含 DOI/申请号/项目号、载体、时间事件）、`confirmedValues`（已人工确认字段）、`publicState`、`dataOrigin`、`redirectRefs`（合并/拆分重定向）、出处集合。
- 不能并入 `CleanRecord`：实体是跨来源唯一；规范记录是逐来源的（INV-02）。
- 不能并入 `DedupCandidate`：实体是生效事实，候选是未生效判定。
- 本 UC **不删除**实体；合并使来源实体变“已合并”，拆分以新记录表达（DD-09/DD-10）。

### C8 WorkVersion（Entity）

- 发现于 E11 的版本判定与 A10 的疑似版本。
- 必要业务事实：`versionKind`、`versionEvidence`（明示版本指向）、`linkedSourceId`、`publicOk`（沿用 UC-02）。
- 不能并入 `WorkEntity`：版本是“发布形态”，一个实体可有 0..* 版本；无明示证据时该关系**不存在**（DD-08）。
- 不能并入 `SourceRecord`：出处是“信息从哪来”，版本是“同一成果的哪种发布形态”。

### C9 AuditEvidence（Entity）

- 发现于 E17 的留痕、E16 各处置动作、E2/E7/E8/E9/E11/E14 的判定结果与异常入队、A1/A2/A9/A11/A13/A16 的异常/幂等/拒绝记录。
- 必要业务事实：`actionKind`（自动关联/新建/待核验生成/异常入队/重复到达/版本归并/人工合并/拆分/字段确认/字段修正/忽略/拒绝）、`targetRefs`、`basis`（标识命中、逐项证据、管理员理由）、`actorKind`＋`actorIdentity`（系统/管理员）、`beforeAfterValues`（出处数、实体归属、字段值、实体状态）、`occurredAt`；不含凭据与敏感原文。
- 不能由 `WorkEntity`/`CleanRecord` 字段承担：合并链、拆分链与撤销必须以追加记录表达，且拒绝/异常不产生实体字段变化。
- 命名沿用 UC-06/UC-08/UC-22 的 `AuditEvidence`；与 `ReviewEvidence`（认领/纠错材料）、全局 `Evidence` 的差异留 R5（D-13）。

## 4. 被拒绝的候选类

| 候选 | 拒绝理由 | 证据/后续 |
|---|---|---|
| `CollectionTask` / `SourceAdapter` / `IngestCheckpoint` / `RetryPolicy` | 采集任务、来源接入、检查点与重试属 FR08/FR10 | UC-18/UC-19（§6.2；SPEC §0.2） |
| `IncrementalChangeSet`（新增/未变/变更/失效） | 增量识别属 FR10；本 UC 消费“已到达的记录” | UC-19 |
| `Scholar` / `IdentityCandidate` / `ScholarMerge` / `AliasMap` / `AttributionAssignment` | 身份消歧、同名合并/拆分、关系改派属 FR15；同名不是同人证据 | UC-10（BR-IMP-03；SPEC ID-01～04） |
| `Authorship` / `CorrectionRequest` / `ClaimApplication` | 参与关系状态、学者纠错申请与认领审核是其他对象与其他 UC；本 UC 不写关系状态 | UC-08/UC-05/UC-06（§6.4；`DGM-STATE-05`；`DGM-STATE-02`） |
| `ReviewEvidence` | 认领/纠错提交的审核材料与可见范围；本 UC 不接收申请人材料 | UC-05/UC-06/UC-08（BR-IMP-11） |
| `SearchCoordinator` / `WorkDetailCoordinator` / `PublicProfileUI` / `AnalyticsNetworkCoordinator` / `CollaborationEdge` | 检索、详情、门户、统计与合作边是下游消费者 | UC-01/UC-02/UC-03/UC-11/UC-14 |
| `PendingTodo` / `DisputeCase` / `Notification` / `SlaTimer` / `ReviewerRole` / `PermissionMatrix` | 待办队列、超期提醒、争议结案、通知与审批链属 FR14/UC-17；SLA 未定义 | FR14；UC-17；B-06 |
| 独立 `MergeRecord` / `MergeChain` 类 | 合并链/拆分链与撤销由 `WorkEntity.redirectRefs` ＋追加式 `AuditEvidence` 表达；本 UC 的顺序图中没有任何消息寻址一个独立“合并记录”对象，单独建类会与审计重复 | DD-09/DD-10；FR09-S09/S12；M75/M78/M83（05 §2.3） |
| 独立 `GovernanceProcessingItem` 类 | 模块细化 FR09-S11 的“处理项”五态包含队列/超期/转交，属 FR14/UC-17；本 UC 只建模治理状态与匹配状态的可观察子集 | B-06；07 §3 |
| `FieldPolicy` / `FieldPriorityTable` / `SimilarityScorer` / `ThresholdPolicy` | 字段优先级顺序、相似度权重、年份窗与阈值未批准（SPEC §5.3 明确“不生效”） | B-02/B-03；Q-SPEC-01；D-07 |
| `MappingDictionary` / `TaxonomyTree` | 机构/领域映射字典由 FR14 维护；本 UC 只消费结果并在不可用时降级 | FR14；CL-05；A12 |
| `PublicationSubmission` / `Manuscript` / `PublishAction` | 基线不提供投稿/出版与发布流水线；治理不发布、不下架 | FR07；FR09 范围 |
| `EvidenceFile` / `Attachment` / `RawPayloadStore` | 存储形态属实现设计；分析层只需 `SourceRecord.rawValues` 与 `AuditEvidence` | 教材 §5.4.2 |
| `Session` / `CredentialContext` / `ServiceAccount` | “已认证系统/账号上下文”是本 UC 的输入，不是业务实体；基线未定义会话对象 | NF03；B-09 |
| `AuditCoordinator`（全局 `AuditCtl`） | 审计查询与集中治理属 UC-17；本 UC 只写留痕 | FR14；BR-IMP-10 |
| `BatchSummaryView` / `CandidateDTO` / `ProgressCounter` | 展示投影与统计中间态是交互态，不是业务事实 | §5.4.2、§5.5.2 |

## 5. 六方边界结论

| 边界 | 结论 | 本 UC 证据 |
|---|---|---|
| `SourceRecord`（来源原值） | 只读原始证据；唯一写入是追加重复到达事件；不删除、不改写、不因来源失效而消失 | INV-01；CL-10/CL-12；M3/M19/M20 |
| `CleanRecord`（规范记录） | 逐字段原值＋规范值＋规则版本＋时间精度＋标识作用域＋治理状态＋异常原因/冲突项；可回放追溯 | CL-02/CL-06/CL-08/CL-09/CL-11；M5/M9/M11/M12/M13/M18 |
| `WorkEntity`（去重成果实体） | 跨来源唯一的生效值集合与公开状态；接受新建、字段合并、合并/拆分、字段确认；不删除 | INV-02；DD-09/DD-11；M27/M28/M42/M43/M74/M80/M85 |
| `WorkVersion`（版本关系） | 只在明示版本指向时存在；版本归并不增加实体数 | DD-08；M46/M48 |
| `DedupCandidate`（待核验候选） | 候选清单＋逐项证据＋规模＋匹配状态；标题相似只产生它；管理员处置前不改动实体 | DD-04～DD-07/DD-12；`AC-IMPL-FR09-02`；M31/M35/M36/M38/M40/M48 |
| `AuditEvidence`（治理留痕） | 追加式、不可覆盖；覆盖自动判定、异常、重复到达、合并、拆分、字段确认、修正、忽略与拒绝；承载合并链/拆分链 | FR09-S12；BR-381/382；BR-IMP-10；M6/M22/M29/M78/M83/M87/M91/M93 |
| `SourceRecord` ↔ `CleanRecord` | 一条逻辑出处可因规则版本更新产生多条规范记录；原值不被覆盖 | CL-11；INV-01；05 §2 |
| `CleanRecord` ↔ `WorkEntity` | 归入关系由 `WorkEntity 1→0..* SourceRecord` 的出处 ＋ `CleanRecord.matchOutcome` 表达；异常/待核验时无归属 | DD-01/DD-11；05 §2.3 |
| `DedupCandidate` ↔ `WorkEntity` | 一条候选对照 0..* 候选实体（0 表示无实体可归，需新建或确认不同） | DD-05/DD-07/DD-12 |
| `WorkEntity` ↔ `WorkVersion` ↔ `SourceRecord` | 实体 1→0..* 版本；版本 0..*→1 明示证据来源；三者不得互相替代 | DD-08；UC-02 §13（留待 UC-09/16） |
| 治理 ↔ 身份/关系 | 本 UC 不创建/合并 `Scholar`，不写 `Authorship` 状态；原署名保留，身份处置属 UC-10 | FR15；BR-IMP-03；A14 |
| 治理 ↔ 采集/增量 | 本 UC 不控制任务、不重试采集、不做增量识别；只保证“已到达记录”的确定性结果 | §6.1/§6.2；UC-18/19 |
| 治理 ↔ 纠错申请 | 门户管理者只能提交纠错申请（UC-08）；直接处置治理项只允许治理管理员 | FR07；FR09-S05/S11 权限规则；BR-IMP-05 |

## 6. 识别结论

本 UC 的候选分析类共 9 个：

- Boundary：`DedupGovernanceUI`
- Control：`DedupCoordinator`
- Entity：`User`、`SourceRecord`、`CleanRecord`、`DedupCandidate`、`WorkEntity`、`WorkVersion`、`AuditEvidence`

`CollectionTask`/`SourceAdapter`、`IncrementalChangeSet`、`Scholar`/`IdentityCandidate`/`AttributionAssignment`、`Authorship`/`CorrectionRequest`/`ClaimApplication`、`ReviewEvidence`、检索/详情/门户/统计/网络类、`PendingTodo`/`DisputeCase`/`Notification`/`SlaTimer`、独立 `MergeRecord`/`GovernanceProcessingItem`、`FieldPolicy`/`SimilarityScorer`/`ThresholdPolicy`、`MappingDictionary`、`PublicationSubmission`、`EvidenceFile`、`Session`、`AuditCoordinator`、展示投影/DTO 均不进入本 UC VOPC。该集合将在 04 的顺序图中接受消息检验；若某类没有消息，则删除或降为属性/职责。
