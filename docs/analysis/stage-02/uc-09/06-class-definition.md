# UC-09 分析类职责、属性、关系与分析机制

**版本：** V1.0（2026-10-21）
**方法：** 教材 §5.5.1 职责、§5.5.2 属性、§5.5.3 关系、§5.5.4 分析机制。
**范围：** 只定义 UC-09 能够证实的部分；跨用例统一关系不在此擅自补齐。

## 1. 定义原则

1. “做（Do）”型职责说明对象能执行/协调什么；“知道（Know）”型职责说明对象必须知道/提供什么。
2. 属性只写业务信息，不写数据库字段、主键类型、索引或 API 参数；检索索引与相似度算法不在此定义。
3. 关系只写有消息或正式业务约束支持的；无依据的跨用例关系留待 R5。
4. 控制类没有长期业务属性；其批次/处置上下文是单次用例的分析状态。
5. 类型细化必填项、字段优先级、相似度阈值、候选年份窗、候选截断长度、处理项状态机与复核比例等未冻结项不伪装成类定义，统一标 `待确认`（B-01～B-10）。

## 2. DedupGovernanceUI（Boundary）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Boundary | §5.4.2；M1/M2/M52/M53/M62；M7–M8、M15、M17、M23、M30、M33、M37、M39、M41、M45、M50–M51、M55–M56、M60–M61、M66、M70、M72、M79、M84、M88、M94、M96 |
| Do | 接收“已到达的来源记录批次”与“处置动作＋理由”；显示批次结论（命中/新建/候选/异常计数）、候选清单与逐项证据、候选规模提示、异常原因与缺项、字段冲突与双方来源、日期/映射的异常或部分成功、拒绝（无处置权限）、失败与重试 | E1、E14–E16、A1、A3–A8、A11–A13、A16 |
| 候选分析操作（`/`） | `/接收治理批次`、`/显示批次结论与待核验计数`、`/显示处理项与逐项证据`、`/接收处置动作与理由`、`/显示异常或部分成功`、`/显示拒绝或失败` | M1–M2、M8、M15、M17、M23、M30、M33、M37、M39、M41、M45、M50–M51、M55–M56、M60–M62、M66、M70、M72、M79、M84、M88、M94–M96 |
| Know | 当前会话的展示状态：批次引用、处理项引用、候选/异常/冲突内容、处置动作与理由、结果类型；不持久保存原值、规范值、实体归属、阈值或审计 | 分析推导 |
| 业务属性 | 无持久业务属性；批次/处理项选择、动作与理由、结果展示是短期请求数据 | §5.5.2 |
| 关系 | 单向关联 `DedupCoordinator`（M2/M53/M62 请求；其余返回） | 04 消息表；VOPC |
| 不能承担 | 不能判定最小字段、标识合法性、命中唯一性、候选证据、版本证据、合并守卫或处置权限；入口是否显示不构成权限 | CL-01～CL-04；DD-02～DD-04；FR14；NF03 |
| 分析机制 | 结果类型可区分（异常/待核验/忽略/已处理/拒绝/失败）；待核验与异常内容不进入公开视图；批量结果以计数披露而非“全部通过”；不回显凭据 | NF01；BR-IMP-01/10；§6.2；§11.4 |

## 3. DedupCoordinator（Control）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Control | §5.4.2；M2–M7、M9–M50、M53–M55、M57–M60、M63–M95 |
| Do | 结构校验与类型最小字段校验；建立规范记录并保留原值/精度；按作用域归一标识；幂等定位；稳定标识匹配与证据相容判定；候选生成与逐项证据；版本判定；字段合并与冲突登记；批次计数；处置权限/循环/版本守卫；合并、拆分、确认不同、确认新实体、字段确认、修正、忽略的执行；留痕与结果返回 | E2–E17、A1–A16 |
| 候选分析操作（`/`） | `/校验最小字段`、`/生成规范记录`、`/归一标识`、`/判定重复到达`、`/按标识匹配同类型实体`、`/判定证据相容`、`/生成候选与证据`、`/判定版本证据`、`/合并字段并登记冲突`、`/核对计数不变量`、`/校验处置权限`、`/守卫合并与拆分`、`/执行处置`、`/追加治理留痕`、`/返回结果` | M3–M7、M9–M14、M18–M35、M42–M44、M46–M50、M54–M79、M80–M84、M85–M95 |
| Know | 本次批次/处置的分析状态：`batchRef`、`recordRefs`、`requestContext`（系统/管理员）、`processingItemRef`、`allowedIdentifiers`（按类型与作用域）、`matchHits`、`evidenceFinding`、`candidateRef`、`versionEvidenceFinding`、`mergeGuard`、`actionKind`、`reason`、`beforeAfter`、`counters`（实体数/有效出处数/到达事件/异常/待核验）、`outcome` | 分析推导 |
| 业务属性 | 无长期业务属性。分析级状态：`batchRef`、`recordRefs`、`requestContext`、`processingItemRef`、`matchHits`、`candidateRef`、`actionKind`、`reason`、`counters`、`outcome` | §5.5.2；不指定实现存储 |
| 关系 | 与 `DedupGovernanceUI`、`User`（M54）、`SourceRecord`（M3/M19/M20）、`CleanRecord`（M5/M9/M11–M14/M16/M18/M59/M86/M89/M92）、`DedupCandidate`（M31/M35/M36/M38/M40/M48/M58/M64/M68/M77/M82）、`WorkEntity`（M24–M28/M42/M43/M67/M74–M76/M80/M81/M85）、`WorkVersion`（M46）、`AuditEvidence`（M6/M22/M29/M32/M44/M47/M57/M65/M69/M73/M78/M83/M87/M91/M93）的单向关联 | 04 消息表；VOPC |
| 不能承担 | 不承载原值（`SourceRecord`）、规范值（`CleanRecord`）、候选证据与状态机（`DedupCandidate`）、成果生效值（`WorkEntity`）、版本证据（`WorkVersion`）、留痕（`AuditEvidence`）、账号与权限（`User`）；不是采集任务调度器或实现层 Service | §5.4.1；§5.5.1 |
| 分析机制 | 见 §14：最小字段门槛、原值/规范值分离、时间精度守卫、标识作用域、类型隔离、唯一性守卫、候选与标题不合并、版本证据门槛、幂等计数、已确认值优先、合并/拆分守卫、追加式留痕、可见性、权限、结构性不扩张、跨视图一致 | CL/DD 规则；FR09-S01～S12；B-01～B-09 |

**待确认：** 类型细化必填项（B-01）、字段优先级（B-02）、相似度阈值与年份窗（B-03/B-04）、疑似版本表达（B-05）、处理项状态机与待办边界（B-06）。

## 4. User（Entity）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Entity | §5.4.2；M54；E15；A16 |
| Do | 提供账号标识、账号状态与“治理处置权限”事实，回答“该处置者是否有权执行合并/拆分/字段确认/忽略” | NF03；FR14；FR09-S09/S11 权限规则；`AC-IMPL-NF03-01` |
| 候选分析操作（`/`） | `/校验账号状态`、`/提供治理处置权限事实` | M54；A16 |
| Know | 自己的账号标识、账号状态、是否具备治理处置权限（合并/拆分/字段确认） | FR14；SRS §8；UC-04 06（账号属性沿用） |
| 业务属性 | `accountIdentifier`：账号标识（沿用 UC-04，本 UC 不新增）；`accountStatus`：可用/停用；`governanceScope`：治理处置权限事实（等级/范围划分口径待确认） | FR04；NF03；SRS §8 |
| 关系 | 被 `DedupCoordinator` 在 M54 读取；不建立 `User—Scholar`/`User—WorkEntity` 关联 | 05 §2；SRS §12（UserProfile≠Scholar） |
| 不能混淆 | 不是 `Scholar`（学者身份）；治理处置权限≠门户对象授权（后者是 `ScholarAuthorization`，属 UC-05/06/08）；也不是审计记录中的“操作者”字段（那是 `AuditEvidence.actorIdentity`） | FR05；BR-IMP-03/05；BR-IMP-10 |
| 分析机制 | 持久性；服务端每次校验账号状态与权限；拒绝也留痕 | NF03；FR14；A16 |

## 5. SourceRecord（Entity）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Entity | §5.4.2；M3、M19、M20；E2、E3、E7；A2、A15 |
| Do | 只读提供来源身份、来源内记录键、原始字段值、许可/使用范围、到达事件、数据性质与原站地址；在重复到达时追加到达事件；**不接受**原值改写或删除 | INV-01；CL-10/CL-12；FR16；`AC-IMPL-NF07-01`（边界） |
| 候选分析操作（`/`） | `/提供来源身份与原值`、`/提供许可与数据性质`、`/定位同一逻辑出处`、`/追加到达事件`、`/拒绝原值改写与删除` | M3、M19、M20；A2、A15 |
| Know | `sourceId`、`sourceDomain`、`sourceRecordKey`、`rawValues`、`rightsScope`、到达事件序列与时间、`dataOrigin`、`originalUrl`、可达性状态（只读参考） | §7.1/§7.2；FR16；SPEC §3；CL-11 |
| 业务属性 | `sourceId`；`sourceDomain`；`sourceRecordKey`（逻辑出处键）；`rawValues`（按字段的原始值，永不因清洗改写）；`rightsScope`（许可或使用范围）；`arrivalAt`／`arrivalEvents`（首次与重复到达）；`dataOrigin`（真实/模拟/混合）；`originalUrl` | §7.1；FR16；INV-01；CL-10 |
| 关系 | `1 → 0..* CleanRecord`（规范化来源）；被 `WorkEntity` 以“出处”引用（`1` : `0..*`）；被 `WorkVersion` 以“明示版本指向”引用（`0..*` : `1`） | 05 §2；INV-02；DD-08 |
| 不能混淆 | 不是 `CleanRecord`（规范值）；不是 `WorkEntity`（去重实体）；不是 `WorkVersion`（发布形态）；不是 `CollectionTask`（任务） | INV-01/INV-02；DD-08；§6.2 |
| 分析机制 | 持久性与不可改写（原值）；只追加到达事件；一次事件不物理删除历史（CL-12）；重复到达不增加实体或有效出处（DD-01/DD-11） | INV-01；CL-10/CL-12；`AC-IMPL-FR09-03/06` |

## 6. CleanRecord（Entity）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Entity | §5.4.2；M5、M9、M11–M14、M16、M18、M59、M86、M89、M92；E2–E6、E13；A1、A4、A8、A12 |
| Do | 为一条逻辑出处保存清洗产物：逐字段原值与规范值、规则/字典版本、时间事件语义与精度、标识归一结果与作用域；承载治理状态（有效/异常待处理/待核验/已处理）、异常原因、字段冲突项与“已确认”标记；接受关闭冲突项、修正规范值与忽略 | CL-02/CL-06/CL-08/CL-09/CL-11；SPEC §4.1；FR09-S01/S03/S05/S11 |
| 候选分析操作（`/`） | `/记录逐字段原值与规范值`、`/记录时间事件与精度`、`/记录标识作用域与归一键`、`/标未知或异常原因`、`/置治理状态`、`/登记与关闭字段冲突项`、`/修正规范值` | M5、M9、M11–M14、M16、M18、M86、M89、M92 |
| Know | 所属来源记录与出处键；逐字段：`rawValue`、`normalizedValue`、`ruleRef`、`dictVersion`；时间事件：`eventType`、`value`、`precision`、`rawString`；标识：`scheme`、`jurisdiction/namespace`、`normalizedKey`、`identifierStatus`（可用/未知辖区/争议）；`governanceState`；`exceptionReason`；`fieldConflictItems[]`（字段、已确认值、新证据、处理项引用、状态、累积证据）；`matchOutcome`（命中/新建/待核验/确定不同）；`dataOrigin` | CL-02/CL-03/CL-04/CL-06/CL-08/CL-09/CL-11；SPEC §3 公共状态（治理）；FR09-S05/S11 |
| 业务属性 | `cleanRecordRef`；`sourceRecordRef`；`sourceRecordKey`；`fieldValues[]`；`timeEvents[]`；`identifiers[]`；`governanceState`（`有效/异常待处理/待核验/已处理`）；`exceptionReason`；`fieldConflictItems[]`；`matchOutcome`；`dataOrigin` | SPEC §3/§4.1；FR09-S03/S05/S11；B-06 |
| 关系 | `0..* → 1 SourceRecord`（规范化来源）；被 `DedupCandidate` 以“待核验记录”引用（`0..*` : `1`） | 05 §2；DD-04～DD-07 |
| 不能混淆 | 不是 `SourceRecord`（原值不可改写）；不是 `WorkEntity`（跨来源唯一的生效值）；不是 `DedupCandidate`（候选只存在于需要比对的场景，异常记录从不进入匹配）；不是 `AuditEvidence`（动作留痕） | INV-01；CL-11；DD 判定输出 |
| 分析机制 | 持久性；原值与规范值分离；逐字段可回放；异常与待核验可被管理员打开；冲突项累积而不静默覆盖 | CL-09/CL-11；FR09-S11；`AC-IMPL-FR09-04` |

**待确认：** 模块细化 FR09-S11 的“处理项”五态是否独立建模（B-06）；类型细化必填项（B-01）；字段优先级（B-02）。

## 7. DedupCandidate（Entity）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Entity | §5.4.2；M31、M35、M36、M38、M40、M48、M58、M64、M68、M77、M82；E9、E10；A3、A5–A7、A10 |
| Do | 保存一次待核验判定：被判定的规范记录、候选实体清单、逐项证据（标题/年份/参与者/类型：命中或冲突）、候选规模与截断标注、匹配状态与理由；为管理员提供待核验内容与版本；在处置后置为确定不同/已处理 | DD-04～DD-07/DD-12；FR09-S07/S11；`AC-IMPL-FR09-02` |
| 候选分析操作（`/`） | `/登记候选与逐项证据`、`/标注候选规模（截断/过多）`、`/标注疑似版本关联`、`/提供候选与证据`、`/置确定不同`、`/置已处理` | M31、M35、M36、M38、M40、M48、M58、M64、M68、M77、M82 |
| Know | 被判定的 `CleanRecord` 引用；`candidateEntityRefs[]`；`evidenceItems[]`（维度、候选值、命中/冲突、依据）；`candidateCount` 与截断标注；`matchState`（`候选待核验/确定不同/已处理`）；`reason`；`stateVersion`；处置 `decision`（合并/拆分/确认不同/确认新实体/忽略）与理由 | DD-04～DD-07/DD-12；SPEC §3 公共状态（匹配）；FR09-S07 |
| 业务属性 | `candidateRef`；`cleanRecordRef`；`candidateEntityRefs[]`；`evidenceItems[]`；`candidateCount`；`truncationNote`；`matchState`；`reason`；`stateVersion`；`decision`；`decidedByRef`；`decidedAt` | FR09-S07/S11；DD-12；B-04/B-05 |
| 关系 | `0..* → 1 CleanRecord`（待核验记录）；`1 → 0..* WorkEntity`（候选实体清单） | 05 §2；DD-05/DD-07/DD-12 |
| 不能混淆 | 不是 `WorkEntity`（生效实体）；不是 `AuditEvidence`（动作留痕）；不是“处理项”本身（异常记录无候选；B-06）；标题相似只产生它 | DD-05～DD-07；`AC-IMPL-FR09-02` |
| 分析机制 | 持久性；候选与生效实体并存且隔离；不默认选第一个（候选过多时标注）；无稳定标识不自动合并；管理员处置前置状态版本守卫 | DD-06/DD-07/DD-12；FR09-S11 E2；A6/A7 |

**待确认：** 疑似版本关联是否作为候选子类型（B-05）；候选排序与截断长度（B-04）；相似度阈值（B-03）。

## 8. WorkEntity（Entity）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Entity | §5.4.2；M24、M25、M27、M28、M42、M43、M67、M74–M76、M80、M81、M85；E8、E9、E11、E12、E16；A11 |
| Do | 按标识索引返回同类型命中集合；接受新建、关联出处、字段合并（已确认值优先、冲突登记）、合并/拆分、写入人工确认值；提供当前规范生效值与出处集合；**不接受**删除或绕过守卫的合并 | DD-02/DD-03/DD-09/DD-11；CL-09；FR09-S09 |
| 候选分析操作（`/`） | `/按标识返回同类型命中`、`/新建实体`、`/关联出处`、`/合并字段并登记冲突`、`/合并到目标实体`、`/标已合并并重定向`、`/拆分为实体`、`/写入人工确认值` | M24、M25、M27、M28、M42、M43、M67、M74–M76、M80、M81、M85 |
| Know | `stableId`（canonical id，全平台计数单位）；`workType`；规范生效字段（题名、DOI/申请号/项目号、载体、时间事件、机构/团队等，按 §7.1 类型化）；`confirmedValues`（已人工确认字段及其确认者/时间）；`publicState`；`dataOrigin`；`redirectRefs`（合并/拆分重定向与合并链指针）；出处集合与版本集合的引用 | §7.1；FR16；BR-IMP-01/04；DD-09/DD-11 |
| 业务属性 | `stableId`；`workType`（论文/专利/项目/获奖）；`canonicalFields`（类型适用字段，缺失记“未知”）；`confirmedValues`（字段→确认值＋确认者/时间/依据）；`publicState`；`dataOrigin`；`redirectRefs[]`（旧标识→目标实体，含合并/拆分记录引用）；`sourceRefs[]`；`versionRefs[]` | §7.1；FR16；CL-08/CL-09；DD-08/DD-09；`AC-IMPL-FR09-04` |
| 关系 | `1 → 0..* SourceRecord`（出处，全部保留）；`1 → 0..* WorkVersion`（明示版本）；被 `DedupCandidate` 以“候选实体”引用（`1` : `0..*`） | 05 §2；INV-02；DD-08/DD-11 |
| 不能混淆 | 不是 `CleanRecord`（逐来源规范记录）；不是 `DedupCandidate`（未生效判定）；不是 `WorkVersion`（发布形态）；不是 `Authorship`（参与关系）；来源数/版本数不等于实体数 | INV-02；DD-08/DD-11；UC-02 §13 |
| 分析机制 | 持久性；去重实体唯一；字段优先级与已确认值优先；合并保留全部出处/版本与旧标识重定向；禁止循环与部分成功；不删除 | CL-09；BR-IMP-02/04；DD-09；FR09-S09 |

**待确认：** 字段优先级顺序与可确认字段范围（B-02）；合并/拆分后下游重算范围（B-07）。

## 9. WorkVersion（Entity）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Entity | §5.4.2；M46；E11；A10 |
| Do | 保存一条有明示证据的版本关系（版本种类、明示版本指向、证据来源、可公开性）；无证据时不存在该关系，也不接受“疑似即合并” | DD-08；FR09-S08；`AC-IMPL-FR09-05` |
| 候选分析操作（`/`） | `/记录明示版本关系与证据`、`/拒绝无证据的版本归并` | M46；A10 |
| Know | `versionKind`（预印本/修订/正式发表等，完整枚举未冻结）；`versionEvidence`（来源中的明示指向）；`linkedSourceId`（证据来源）；`publicOk` | DD-08；FR09-S08；UC-02（`versionKind`/`versionEvidence`/`linkedSourceId`/`publicOk`） |
| 业务属性 | `versionRef`；`versionKind`；`versionEvidence`；`linkedSourceId`；`publicOk` | 同上；B-05 |
| 关系 | `0..* → 1 WorkEntity`（属于一个成果实体）；`0..* → 1 SourceRecord`（明示证据来源） | 05 §2；DD-08 |
| 不能混淆 | 不是 `WorkEntity`（版本数不增实体数）；不是 `SourceRecord`（出处 vs 发布形态）；不是 `DedupCandidate`（疑似不是证据） | DD-08；`AC-IMPL-FR09-05`；`AC-IMPL-FR16-02` |
| 分析机制 | 证据门槛；版本归并不增加实体数；无证据保持独立并标疑似 | DD-08；FR09-S08；A10 |

**待确认：** 版本种类完整枚举与疑似版本的后续入口（B-05；UC-02 Q-03）。

## 10. AuditEvidence（Entity）

| 项目 | 定义 | 依据 |
|---|---|---|
| 类型 | Entity | §5.4.2；M6、M22、M29、M32、M44、M47、M57、M65、M69、M73、M78、M83、M87、M91、M93；E2、E7–E9、E11、E14、E16、E17；A1、A2、A9、A11、A13、A16 |
| Do | 记录自动关联、新建、待核验生成、异常入队、重复到达、版本归并、人工合并、拆分、字段确认、字段修正、忽略与拒绝的可追溯留痕；承载合并链/拆分链与撤销；向有权者提供审计事实（查询属 UC-17） | FR09-S12；BR-381/382；BR-IMP-10；INV-10 |
| 候选分析操作（`/`） | `/记录判定与处置留痕`、`/记录合并链与重定向依据`、`/记录拆分与撤销`、`/记录重复到达与幂等结论`、`/记录拒绝或异常` | M6、M22、M29、M32、M44、M47、M57、M65、M69、M73、M78、M83、M87、M91、M93 |
| Know | `actionKind`（自动关联/新建/待核验生成/异常入队/重复到达/版本归并/人工合并/拆分/字段确认/字段修正/忽略/拒绝）；`targetRefs`（规范记录/候选/实体/版本/字段）；`basis`（标识命中、逐项证据、管理员理由）；`actorKind`（系统/管理员）与 `actorIdentity`；`beforeAfterValues`（出处数、实体归属、字段值、治理状态）；`occurredAt`；不含凭据与敏感原文 | FR09-S12；BR-IMP-10；INV-10；NF03 |
| 业务属性 | `auditRef`；`actionKind`；`targetRefs[]`；`basis`；`actorKind`；`actorIdentity`；`beforeAfterValues`；`occurredAt` | BR-IMP-10；FR09-S12；D-13（命名统一留 R5） |
| 关系 | 由 `DedupCoordinator` 创建；指向对象的引用是 `targetRefs` 属性，不另画实体关联（05 §2.3） | M6/M22/M29/M78/M83/M87/M91/M93；05 §2.3 |
| 不能混淆 | 不是 `CleanRecord`（规范值与状态）；不是 `DedupCandidate`（待决定内容）；不是 `ReviewEvidence`（认领/纠错材料）；不进入公开响应 | FR09-S12；BR-IMP-01/11；BR-IMP-10 |
| 分析机制 | 持久性、追加式、不可覆盖；拒绝与异常也留痕；无审计不得报告成功；撤销以新记录表达；审计不含秘密 | FR09-S12 E1/E3；BR-382；BR-IMP-10；A13 |

**待确认：** 保留期限与查询权限属 FR14/UC-17（D-09）。

## 11. 关系定义汇总

| 关系 | 角色/方向 | 多重性 | 来源 | 状态 |
|---|---|---|---|---|
| UI → Coordinator | 请求 / 返回结果 | 不定义持久多重性 | M2/M53/M62；其余返回 | 已定义（单向关联） |
| Coordinator → User | 校验账号状态与处置权限 | 不定义持久多重性 | M54 | 已定义（单向关联） |
| Coordinator → SourceRecord | 读原值；追加到达事件 | 不定义持久多重性 | M3/M19/M20 | 已定义（单向关联，原值只读） |
| Coordinator → CleanRecord | 建立、置状态、关闭/修正冲突项 | 不定义持久多重性 | M5/M9/M11–M14/M16/M18/M59/M86/M89/M92 | 已定义（单向关联，含写入） |
| Coordinator → DedupCandidate | 生成、读取、置已处理 | 不定义持久多重性 | M31/M35/M36/M38/M40/M48/M58/M64/M68/M77/M82 | 已定义（单向关联） |
| Coordinator → WorkEntity | 查找、新建、合并/拆分、字段写入 | 不定义持久多重性 | M24–M28/M42/M43/M67/M74–M76/M80/M81/M85 | 已定义（单向关联，含写入） |
| Coordinator → WorkVersion | 归入明示版本清单 | 不定义持久多重性 | M46 | 已定义（创建关系） |
| Coordinator → AuditEvidence | 追加治理留痕 | 不定义持久多重性 | M6/M22/M29/M32/M44/M47/M57/M65/M69/M73/M78/M83/M87/M91/M93 | 已定义（创建关系） |
| SourceRecord 1 → 0..* CleanRecord | 规范化来源 | `1` : `0..*` | CL-10/CL-11；M3/M9 | 已定义 |
| DedupCandidate 0..* → 1 CleanRecord | 待核验记录 | `0..*` : `1` | DD-04～DD-07；M31 | 已定义 |
| DedupCandidate 1 → 0..* WorkEntity | 候选实体清单 | `1` : `0..*` | DD-05/DD-07/DD-12；M35/M58 | 已定义 |
| WorkEntity 1 → 0..* SourceRecord | 出处（全部保留） | `1` : `0..*` | INV-02；BR-302；DD-11；UC-02 | 已定义（沿用 UC-02） |
| WorkEntity 1 → 0..* WorkVersion | 明示版本关系 | `1` : `0..*` | DD-08；`AC-IMPL-FR09-05`；UC-02 | 已定义（沿用 UC-02） |
| WorkVersion 0..* → 1 SourceRecord | 明示版本指向 | `0..*` : `1` | DD-08；M46；UC-02 §13（留待 UC-09/16） | 已定义（本 UC 新建，见 07 §4） |
| CleanRecord — WorkEntity | 归入关系（属性表达） | 不画实体关联 | DD-11；M27/M43 | 已说明（05 §2.3） |
| WorkEntity — WorkEntity | 合并链/旧标识重定向（属性＋审计表达） | 不画自关联 | DD-09/DD-10；M75/M78/M83 | 已说明（05 §2.3） |
| AuditEvidence — 被写对象 | 对象指向（属性 `targetRefs`） | 不画实体关联 | BR-IMP-10；05 §2.3 | 已说明 |
| WorkEntity — Authorship / Scholar | **本 UC 不建立**（身份与关系属 UC-10/UC-08） | 不定义 | FR15；BR-IMP-03 | 禁止在本 UC 擅定 |
| CleanRecord — CollectionTask / DedupCandidate — CollectionTask | **本 UC 不建立**（采集任务属 UC-18/19） | 不定义 | FR08/FR10；§6.2 | 留给 UC-18/19 |

## 12. 分析机制汇总

| 机制 | 作用对象 | 业务问题 | 本 UC 可证实的约束 | 未决定/后续 |
|---|---|---|---|---|
| 最小字段门槛 | Coordinator、CleanRecord | 缺名称/类型/来源身份的记录进入公开实体 | 异常待处理、不形成公开实体、可修正后重判 | 类型细化必填项（B-01） |
| 原值/规范值分离 | SourceRecord、CleanRecord、WorkEntity | 清洗覆盖原始事实、无法回溯 | 原值永不改写；逐字段原值＋规范值＋规则版本可回放 | 规范值展示粒度（属 UC-02） |
| 时间语义与精度守卫 | CleanRecord | 年份被补成日期、采集时间冒充发生时间 | 事件语义＋精度＋原字符串；仅年份不补月日；非法/歧义/可疑转异常/待核验 | 可疑年份判定阈值（B-01 类） |
| 标识作用域归一 | CleanRecord | 跨体系误配、未知辖区被当稳定键 | DOI 前缀/大小写无关；申请/公开/授权号按辖区＋体系；项目号限授予机构；未知辖区不成键 | 辖区/体系清单（B-03） |
| 类型隔离 | Coordinator、WorkEntity | 专利记录命中论文实体 | 跨成果类型不自动合并，类型不一致待核验 | — |
| 稳定标识唯一性守卫 | Coordinator、WorkEntity | 同一标识命中多个实体时任选 | 唯一命中∧证据相容才自动关联；多命中列全部候选 | 冲突优先级与证据相容判定细化（B-03；Q-SPEC-01） |
| 无标识候选与“标题不合并” | Coordinator、DedupCandidate | 标题相似被当作合并依据 | 只生成候选并逐项列证据；“四项证据自动合并”未批准、不执行 | 相似度阈值/年份窗（B-03/B-04） |
| 候选规模与判定证明 | DedupCandidate | 候选过多时默认选第一个、截断丢失证明 | 限制展示长度并标注“候选过多”；判定证明不因截断丢失 | 截断长度与排序（B-04） |
| 版本证据门槛 | WorkVersion、WorkEntity | 疑似版本被静默合并、实体数被压缩 | 只在明示版本指向时建立；实体数不增；无证据独立标疑似 | 疑似版本后续入口（B-05） |
| 幂等与计数不变量 | SourceRecord、WorkEntity、Coordinator | 重试/重叠窗口重复计数 | `distinct(canonical_id)` 与 `distinct(source_id,source_record_key)` 不增；到达事件可增 | 增量侧窗口定义（UC-19） |
| 已确认值优先与冲突累积 | WorkEntity、CleanRecord | 来源更新静默覆盖人工确认值 | 已确认值继续可见；新证据入同一可累积处理项；管理员确认才改写 | 字段优先级（B-02） |
| 合并/拆分守卫 | Coordinator、WorkEntity | 循环合并、部分合并、拆分抹除历史 | 类型一致∧无循环∧版本未变；保留全部出处/版本；旧标识重定向；拆分/撤销追加记录 | 重分配细则（DD-10 待评审） |
| 追加式审计与历史不覆盖 | AuditEvidence | 治理结论无法追溯、被覆盖 | 动作/对象/依据/执行者/前后值/时间；只追加；无审计不报成功 | 保留期限（D-09；UC-17） |
| 可见性最小化 | CleanRecord、DedupCandidate、AuditEvidence | 待核验/异常/冲突/审计被公开 | 不作为公开成果；公开视图只取生效实体与合法出处 | 字段级遮蔽粒度（BR-IMP-11；UC-17） |
| 权限与拒绝 | User、Coordinator | 无处置权限者合并/拆分/改字段 | 服务端每次校验账号状态＋处置权限；拒绝留痕 | 权限等级划分（FR14） |
| 结构性不扩张 | 全体 | 治理流程制造采集任务、身份判定、发布或删除 | 不采集/不调度、不做身份判定、不发布/下架、不删除成果与来源 | 与 UC-18/19/10 的入口分工（B-08） |
| 跨视图一致（声明） | WorkEntity、消费方 | 不同视图读到不同实体或待核验值 | 生效后下游读取同一去重实体；待核验不公开 | 传播时限与重算范围（B-07；NF05 实测 NOT RUN） |

## 13. 与其他 UC 的边界

- **UC-18/UC-19**：来源接入、采集任务、检查点、重试与增量识别不在本 UC；本 UC 的输入是“已到达且已登记”的记录批次，输出是可核对的计数结论。
- **UC-10**：学者身份消歧、同名合并/拆分、关系改派不在本 UC；本 UC 不创建/合并 `Scholar`，不写 `Authorship` 状态，原署名与顺序保留。
- **UC-08**：学者本人确认/补录/纠错申请与审核在 UC-08；门户管理者只能提交申请，不直接处置治理项；本 UC 的字段确认是治理侧动作，依据来源证据而非本人声明。
- **UC-02/UC-16**：字段语义、详情展示与规模核对由 UC-02/UC-16 负责；本 UC 提供 `distinct` 计数口径与“版本不增实体”的事实。
- **UC-01/UC-03/UC-11/UC-14**：检索、门户、统计与合作网络只消费生效实体与合格关系；待核验/异常/冲突项不进入公开视图。
- **UC-17**：待办队列、超期提醒、转交复核与审计查询在 UC-17；本 UC 只写入处理项状态与留痕。
