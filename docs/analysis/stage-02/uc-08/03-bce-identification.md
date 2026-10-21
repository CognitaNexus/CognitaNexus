# UC-08 Boundary / Control / Entity 识别

**版本：** V1.0（2026-10-21）
**方法：** 教材 §5.4.2：从用例事件流识别边界类、控制类、实体类；不从现有全局 BCE 反向填充。
**输入：** 本目录 01 规约、02 事件流。

## 1. 识别原则

1. **Boundary**：与外部参与者交互、接收输入或显示结果的对象。
2. **Control**：协调一个用例内的授权/状态守卫、待审登记与生效、失败处理和结果返回；不是实现层 Controller/Service。
3. **Entity**：承载业务事实、状态和持久信息；不是数据库表。
4. 只有在该 UC 事件流中实际承担消息的对象才进入 VOPC。
5. 参与者“已获授权学者”“有权审核的管理员”不是系统分析类；他人的 `Authorship`、未收录成果是被拒绝的写入目标，不是本 UC 的参与对象。
6. 仅因“以后可能用到”而出现的类不进入本 UC。

## 2. 候选分析类

| ID | 候选类 | 类型 | 从哪些事件发现 | 业务行为 | 为什么不能由其他候选直接承担 |
|---|---|---|---|---|---|
| C1 | `AchievementCorrectionUI` | Boundary | E1、E8、E12、E15、A1–A7、A9/A10、A14 | 接收“选定成果与本人关系”“选择确认/补录/纠错”“提交审核决定与理由”“查询本人请求”；显示可确认/可申请范围、拒绝（未认证/越权/他人关系）、已排除/争议提示、缺项与不可核验、待审状态与“公开未变更”、生效/驳回结果 | `Authorship`/`WorkEntity` 是业务实体，不应承担输入与显示；`AchievementCorrectionCoordinator` 是内部协调者，不直接面对参与者 |
| C2 | `AchievementCorrectionCoordinator` | Control | E2–E15、A1–A12 | 服务端校验账号状态与对象级授权；确认目标学者一致与“仅本条关系”；判定状态优先级、操作类型与对象范围；核验补录可追溯性与最低字段；登记待审请求或执行本人确认；复核审核守卫；应用生效（字段/解除）；协调证据与审计；返回结果 | 守卫同时依赖 `User`、`ScholarAuthorization`、`Scholar`、`Authorship`、`WorkEntity`、`SourceRecord` 六方事实，任何单一 Entity 只能自知；且必须保证“拒绝无副作用、待审不覆盖公开值、解除不删成果” |
| C3 | `User` | Entity | E2、E13、A1、A10 | 提供账号标识与账号状态；作为授权被授权主体（请求者）与审核者身份主体 | 账号状态与审核权限属账号事实，不属于 `Scholar`（可无账号）、不属于 `ScholarAuthorization`（只记“谁被授权”）、不属于 `Authorship` |
| C4 | `ScholarAuthorization` | Entity | E3、A2 | 回答“该请求者对该目标 Scholar 是否持有有效对象级授权、范围到哪里”；每次受保护写入只读判定 | 授权不是身份档案（`Scholar`）、不是账号状态（`User`）、不是申请过程（`ClaimApplication`）；把授权写进 `Authorship` 会使同一关系出现多份授权事实 |
| C5 | `Scholar` | Entity | E4、A2、A11 | 承载已收录学者身份事实；回答“关系归属的学者是不是授权所指的那一位”；提供本人的相关关系集合供守卫只读比对 | `User` 是账号；`UserProfile` 是普通主页；`ScholarAuthorization` 只表达“谁能管”；三者都不能表达“这条关系属于哪位学者” |
| C6 | `Authorship` | Entity | E4、E6、E7、E14、A3、A4、A7、A13 | 承载一条“学者—成果”参与关系：角色、顺序、证据引用、独立确认状态与状态历史；接受本人确认、争议与解除 | 关系状态不属于 `WorkEntity`（成果整体状态）也不属于 `Scholar`（身份事实）；同一成果的多位参与者必须分别成条（BR-353；ID-05） |
| C7 | `WorkEntity` | Entity | E9、E11、E14、A4、A12、A14 | 承载去重后的成果共享字段与生效公开值；只接受审核通过后的字段写入；**不得**因关系解除被删除 | `Authorship` 只表达某一个参与者的关系；`SourceRecord` 只表达某一来源的原值；把共享字段放进 `Authorship` 会产生同一成果多份互相矛盾的题名/标识 |
| C8 | `SourceRecord` | Entity | E10、E14 | 只读提供来源原值、到达时间与许可，用于核验补录并保证原值不被覆盖；解除时仍全部保留 | 来源记录是来源侧原始证据；把人工补录写进来源记录会违反 INV-01“原始记录永不因清洗被改写”；`WorkEntity` 只承载规范值 |
| C9 | `CorrectionRequest` | Entity | E11、E12、E13、E14、E15、A5、A9、A11 | 保存一次待审纠错请求：类型、目标成果、涉及的本人员关系、字段级前后值、理由、证据引用、提交者、状态（`待审核/通过/驳回`）、状态版本与时间；为本人提供状态、为审核者提供待审内容 | “待审不替代公开版本”要求未生效内容与公开值**并存且可区分**；`WorkEntity`/`Authorship` 只表达生效值；`AuditEvidence` 只记录已发生动作 |
| C10 | `ReviewEvidence` | Entity | E12、E13、E14、A5、A12 | 承载提交给审核的证据材料与可见范围（本人＋有权审核者）；供有权审核者核对，向本人提供摘要 | 材料原文与可见性约束不同于请求状态（BR-IMP-11；`AC-IMPL-NF06-01`）；材料不是来源记录（不是来源侧原始事实），也不是审计留痕（审计记录“谁在何时做了什么决定”） |
| C11 | `AuditEvidence` | Entity | E8、E12、E15、A1–A3、A6、A7、A9 | 保存本人确认、越权/越界尝试、争议登记、待审登记、审核决定与生效的可追溯留痕：操作者、对象、理由或结果、前后值、时间；不含材料原文与凭据 | 留痕不随成果公开，且必须覆盖“被拒绝、未发生写入”的情形；`CorrectionRequest` 只在待审路径存在，`Authorship` 只在状态变化时被写 |

## 3. 逐类选择理由

### C1 AchievementCorrectionUI（Boundary）

- 发现于 E1 选定与提交、E8/E12/E15 返回结果，并承担 A1/A2/A4 的拒绝显示、A3 的“需管理员处置”、A5 的缺项提示、A7 的争议提示、A9/A10 的“状态已变化/无权限”提示、A14 的“不提供投稿/出版”说明。
- 无持久业务属性；所选成果/关系、操作类型、决定输入、请求状态展示是单次交互数据。
- **命名说明：** 全局 BCE 文本（`01-BCE分析类模型.md` §2）用 `ScholarPortalUI` 覆盖 UC-03/07 的档案展示/维护，用 `ClaimUI` 覆盖认领；全局分析类图（`DGM-CLASS-01-analysis.puml`）**没有**本 UC 的写边界。本 UC 用 `AchievementCorrectionUI` 明确“成果关系确认/补录/纠错的写与待审查询”语义，与读边界 `PublicProfileUI`/`WorkDetailUI` 分离；合并/改名留 R5（B-10；见 07）。
- **为什么本 UC 只有一个边界（不拆 `…ReviewUI`）：** 审核决定的输入对象、可见范围与结果返回口径与本人操作一致，且 UC-06（认领审核）之所以独立成边界，是因为认领审核是**独立用例**（主参与者不同、对象不同）。本 UC 的审核是同一对象（`CorrectionRequest`）的后半段流程，拆分会产生两个边界共享同一展示状态。该取舍登记为 R5 建议（07 §3）。

### C2 AchievementCorrectionCoordinator（Control）

- 发现于 E2–E4 的守卫、E5/E6 的判定、E7/E14 的写入、E11 的待审登记、E13 的审核守卫、E8/E12/E15 的审计与结果返回，以及 A1–A12 的失败与拒绝处理。
- 需要跨六类实体协调，并保证：①拒绝时不写业务实体；②待审不覆盖公开值；③解除不删成果/来源/他人关系；④单次确认不扩散；⑤生效必须留痕。
- 状态优先级判定（已排除>争议>待核验>本人确认>自动关联）必须同时读取关系状态与冲突标记，不能由 `Authorship` 自己“决定是否可被确认”——否则会把业务守卫下推到实体，无法覆盖“越权/他人关系/证据不可核验”等跨对象条件。
- **命名说明：** 全局图只有 `GovernCtl`（清洗/去重/候选）与 `ClaimCtl`（证据/授权/争议），没有“学者确认/补录/纠错”的协调者。本 UC 使用 `AchievementCorrectionCoordinator`，建议 R5 在 `GovernCtl` 与 `ClaimCtl` 之间明确该职责（见 07 §4）。

### C3 User（Entity）

- 发现于 E2 账号状态校验、E13 审核者身份与审核权限校验、A1 停用拒绝、A10 无权限拒绝。
- 必要业务事实：账号标识、账号状态、审核权限事实；`ScholarAuthorization` 以“被授权主体”引用它（`0..*:1`）。
- 不能并入 `Scholar`：学者档案可无账号（FR05；SRS §12），账号停用不是档案公开状态。
- 不能并入 `ScholarAuthorization`：授权记录不承载账号可用性（`AC-IMPL-NF03-01` 要求停用账号的受保护请求被拒绝）。
- 本 UC **不新增**账号属性；审核权限划分沿用 UC-06 的待确认口径。

### C4 ScholarAuthorization（Entity）

- 发现于 E3 授权读取、A2 无授权拒绝。
- 必要业务事实：目标 `Scholar` 引用、被授权主体（→`User`）、对象级范围、有效性、生效时间。
- 不能并入 `ClaimApplication`：申请是过程与决定记录，授权是批准后的权利事实（`AC-IMPL-FR05-02`）。
- 本 UC **只读**：不创建（属 UC-06）、不修改、不转移、不撤销（BR-IMP-05；SPEC ID-12）。

### C5 Scholar（Entity）

- 发现于 E4 目标一致性确认与“本人相关关系”读取、A2 同名另一学者拒绝、A11 改派边界。
- 必要业务事实：稳定身份引用、公开状态、身份核验状态（只读参考）、以及与 `Authorship` 的归属关系。
- 不能并入 `Authorship`：`Authorship` 只承载一条关系的角色/顺序/状态；学者身份与公开状态属于 `Scholar`，且同一学者跨多条关系共享。
- 不能并入 `User`/`UserProfile`：Scholar 可无账号、可未认领仍公开；普通主页不是学术身份（FR03；BR-IMP-03；SRS §12）。
- 本 UC **不写** `Scholar` 档案字段（属 UC-07）、不合并/拆分身份（属 UC-09/UC-10，A11）。

### C6 Authorship（Entity）

- 发现于 E4 读取、E6/E7 确认、E14 解除、A3 已排除/争议、A4 他人关系、A7 争议、A13 不恢复。
- 必要性来自三条相互独立的要求：①“只修改自己的参与关系不影响他人”→ 关系必须按参与者分行；②“同一关系状态五取一且优先级固定”→ 关系必须携带独立状态与状态历史；③“解除一人关联不删除共享成果、其来源或其他合法参与关系”→ 关系与成果、来源必须是可分离的对象。
- 不能并入 `WorkEntity`：成果是共享实体，其存在不依赖某一位参与者（INV-04/INV-05；FR15-S11 A3“唯一参与者解除后仍保留”）。
- 不能并入 `SourceRecord`：来源是“这条信息从哪来”，关系是“这位学者与这项成果的参与事实”，二者生命周期不同（来源可失效而关系保留，FR15-S08 E1）。
- 不能作为 `CorrectionRequest`：待审内容未生效，关系状态是生效事实；两者必须并存。

### C7 WorkEntity（Entity）

- 发现于 E9 读取公开/共享字段、E11 待审期间保持旧值、E14 写入生效规范值、A4 他人关系判定（同一成果上他人关系）、A12 公开读取依据、A14 不新建成果。
- 必要业务事实：稳定成果引用、类型、共享字段（题名/标识/载体/时间等）、公开状态与生效版本；数据性质标识（真实/模拟/混合）。
- 不能并入 `Authorship`：多作者共享同一成果（ID-05/ID-11），共享字段只有一份生效值。
- 不能并入 `SourceRecord`：来源数不等于成果数（INV-02），重复来源不增加实体。
- 本 UC **不新建** `WorkEntity`（不提供投稿/出版），**不删除** `WorkEntity`（解除只改关系）。

### C8 SourceRecord（Entity）

- 发现于 E10 只读核验、E14 保留（解除不删来源）。
- 必要业务事实：来源标识、来源域、许可/使用范围、到达时间、来源内记录键、原始字段值。
- 不能并入 `WorkEntity`：原值与规范值必须分离（§7.2；INV-01/INV-06；CL-11）。
- 不能并入 `ReviewEvidence`：`SourceRecord` 是平台从获准来源取得的原始证据（含许可与到达时间），`ReviewEvidence` 是本次请求由提交者提供、可见范围受限的审核材料；二者的产生者、许可与可见性都不同。
- 本 UC **只读**：不写、不删、不改原值。

### C9 CorrectionRequest（Entity）

- 发现于 E11 登记、E12 返回、E13 读取、E14/E15 决定与生效、A5 不可核验（不登记）、A9 版本冲突、A11 边界（保留申请状态）。
- 必要性来自 `AC-IMPL-FR07-02` 的三个可观察事实同时成立：①“提交共享成果字段修改”；②“审核未通过→公开旧值不变”；③“处理过程可查”。第③项要求请求在提交后仍可被本人读取；第②项要求未生效内容与公开值并存。二者都无法由 `WorkEntity`/`Authorship` 的单一生效值集合或一次性的协调器上下文表达。
- 不能并入 `AuditEvidence`：审计记录“已发生的动作与前后值”，不承载“尚待决定的内容及其状态机”。
- 不能并入 `ReviewEvidence`：材料是证据集合，状态机与决定在请求上。
- **待确认：** 请求状态集合是否含 `待补充/争议/已撤回`（B-01）；与 UC-07 `ScholarChangeRequest` 是否统一为同一分析概念（B-10，R5）。

### C10 ReviewEvidence（Entity）

- 发现于 E12 登记材料与可见范围、E13 读取核对、E14 生效依据、A5 不可核验（材料不足）、A12 不可公开。
- 现有登记表（UC-05/UC-06）已用同名类表达“材料＋可见范围”，本 UC 沿用同一含义，不新增第二个材料类。
- 不能并入 `SourceRecord`：材料可包含本人说明与凭证，未必是获准来源记录，且必须带可见范围。
- 不能并入 `AuditEvidence`：材料是“证据”，审计是“动作留痕”；`AC-IMPL-NF06-01` 要求普通用户不得读取他人材料，而审计查询另属 UC-17。
- **待确认：** 材料最低集合与“可核验”口径（B-05；D-06）。

### C11 AuditEvidence（Entity）

- 发现于 E8（本人确认留痕）、E12（待审登记）、E15（决定与生效）、A1/A2/A3（拒绝/越权/状态不允许）、A6（不报告成功）、A7（争议登记）、A9（拒绝覆盖）。
- 必要业务事实：操作者身份与类型（本人/审核者）、对象引用（关系/成果/请求）、理由或结果、前后值（状态、字段值、请求状态）、时间；不含材料原文与凭据。
- 不能由 `Authorship`/`WorkEntity` 承担：审计必须覆盖“被拒绝、未发生写入”的情形，且不随公开视图展示（BR-IMP-01/11）。
- 命名沿用 UC-06/UC-22 的 `AuditEvidence`，与 `ReviewEvidence`（材料）、全局 `Evidence` 的差异留 R5（D-13）。

## 4. 被拒绝的候选类

| 候选 | 拒绝理由 | 证据/后续 |
|---|---|---|
| `UserProfile` / 第二个 `UserProfile` | 本 UC 无普通主页消息；零成果空态由 UC-03 展示，本 UC 只保证“不凭空生成 WorkEntity/Authorship” | FR07-03；SRS §12；UC-22 |
| `ClaimApplication` / `ClaimCoordinator` / `ClaimReviewCoordinator` | 认领申请与审核属 UC-05/UC-06；本 UC 不创建、不读申请状态；授权只读 | FR05；`AC-IMPL-FR05-04`；`DGM-STATE-02` |
| `ScholarChangeRequest` | UC-07 的“档案字段待审变更”对象；本 UC 的对象是成果关系与成果字段，字段集合、状态集合与生效路径不同 | FR06 vs FR07；UC-07 06 §7；B-10 |
| `WorkVersion` | 版本归并/版本证据属 FR09 去重治理；本 UC 不新增/合并版本 | FR09；`AC-IMPL-FR09-05/08` |
| `CollectionTask` / `SourceAdapter` | 采集任务与来源接入属 FR08；本 UC 只读来源事实 | FR08；UC-18/UC-19 |
| `DedupCandidate` / `MergeDecision` | 去重与合并决策属 FR09/UC-09；本 UC 不改实体合并 | FR09；SPEC DD-01～DD-12 |
| `IdentityCandidate` / `ScholarMerge` / `AliasMap` | 身份消歧、别名、合并/拆分属 FR15-S02～S04 与 UC-09/UC-10；本 UC 不做身份判定 | BR-IMP-03；SPEC ID-01～04；A11 |
| `AttributionAssignment`（改派目标学者） | 把关系改派到另一位学者属 FR15-S10 的人工纠正（UC-10）；本 UC 只登记申请状态 | FR15-S10；A11；B-08 |
| `CollaborationEdge` / `GraphCoordinator`（`GraphCtl`） | 建边、强度与论文子图属 FR12/IN01（UC-14/UC-15）；本 UC 只产出生效关系状态 | BR-IMP-08/09；§6.4 |
| `DisputeCase` / `ConflictQueue` / `PendingTodo` | 争议结案载体与待办队列属 FR14/UC-17；本 UC 只把关系置为“争议”并留痕 | §11.3；FR14；UC-06 C-05 |
| `Notification` / `SlaTimer` | 通知渠道与 SLA 未定义（D-06） | 分析推导；D-06 |
| `Session` / `CredentialContext` | “已认证请求上下文”是本 UC 的输入，不是业务实体；基线未定义会话对象 | NF03；SRS §8；B-09 |
| `ReviewerRole` / `PermissionMatrix` / `ApprovalChain` | 基线只区分“是否具备对应审核权限”，不定义等级与审批链 | FR14；NF03 |
| `FieldPolicy` / `SharedFieldCatalogue` | 字段分类知识落在协调器上下文与 `WorkEntity` 的共享字段集合上；共享字段清单未冻结，不能建模成已批准实体 | FR07；B-04 |
| `PublicationSubmission` / `Manuscript` / `PublishAction` | 基线明确不提供论文投稿或出版功能；本 UC 不新建成果 | FR07 规范性要求；A14 |
| `EvidenceFile` / `Attachment` / `UploadStore` | 材料存储形态属实现设计；分析层只需 `ReviewEvidence` 与可见范围 | 教材 §5.4.2；技术边界 |
| `EffectiveVersion`（独立类） | 生效值由 `WorkEntity` 的当前公开字段与版本标识表达；无消息寻址独立版本对象 | §6.4；NF05；B-07 |
| `AuditCoordinator`（全局 `AuditCtl`） | 审计查询与集中治理属 UC-17；本 UC 只需写入留痕 | FR14；BR-IMP-10 |
| `CorrectionView` / `CorrectionDTO` / `RequestDraft` | 展示投影/草稿是交互态，不是业务事实 | §5.4.2、§5.5.2 |

## 5. 六方边界结论

| 边界 | 结论 | 本 UC 证据 |
|---|---|---|
| `WorkEntity`（成果共享实体） | 只承载去重后的成果字段与生效值；本 UC 只在其上写入**审核通过**的共享字段；不新建、不删除 | `AC-IMPL-FR07-02`；§6.4；INV-02/INV-04；M34/M62 |
| `Authorship`（学者—成果关系） | 本 UC 的核心写对象：确认、争议、解除都在**一条**关系上；一位学者在同一成果上只有一条逻辑关系 | FR07；BR-353；SPEC ID-05/ID-11；M6/M19/M23/M67 |
| `Scholar`（学术身份档案） | 只用于确认“关系归属学者＝授权所指学者”和读取本人关系集合；本 UC 不写档案字段、不合并/拆分 | FR05；BR-IMP-03；UC-07/UC-09/UC-10 边界 |
| `SourceRecord`（来源原值） | 只读证据：核验补录、保留原值、解除后仍全部保留；本 UC 不写不删 | §7.2；BR-IMP-04；INV-01；M35 |
| `ReviewEvidence`（审核材料） | 提交给审核的材料与可见范围，仅本人与有权审核者可见；不是公开字段、不是来源原值 | BR-IMP-11；NF06；`AC-IMPL-NF06-01`；M41/M49/M56 |
| `CorrectionRequest`（待审纠错请求） | 未生效内容＋状态机，与生效值并存；公开值在批准前不变 | `AC-IMPL-FR07-01/02`；BR-IMP-10；§6.4；M40 |
| `AuditEvidence`（审计留痕） | 覆盖本人确认、拒绝、越权、争议、待审、决定与生效；不进入公开视图；不含材料原文 | BR-IMP-10；INV-10；NF03；M10/M13/M24/M42/M64/M70/M74 |
| `CorrectionRequest` ↔ `ClaimApplication` ↔ `ScholarChangeRequest` | 三个不同对象、三套状态：成果关系纠错（`待审核/通过/驳回`）、认领（含 `待补充/争议`）、档案字段变更 | FR05 vs FR06 vs FR07；`DGM-STATE-02` vs §6.4 |
| `Authorship` 状态 ↔ 管理授权 ↔ 认领通过 | 认领/授权通过不等于本人确认；本人确认只由本人事件产生 | SPEC ID-06/ID-07；§11.3；`AC-IMPL-FR15-03` |
| `Authorship` ↔ 合作网络 | 只有经核验的合法关系参与建边；待核验/争议/已排除不建边；建边属 UC-14/UC-15 | §6.4；BR-IMP-08；`AC-IMPL-FR12-04` |

## 6. 识别结论

本 UC 的候选分析类共 11 个：

- Boundary：`AchievementCorrectionUI`
- Control：`AchievementCorrectionCoordinator`
- Entity：`User`、`ScholarAuthorization`、`Scholar`、`Authorship`、`WorkEntity`、`SourceRecord`、`CorrectionRequest`、`ReviewEvidence`、`AuditEvidence`

`UserProfile`、`ClaimApplication`/`ClaimCoordinator`/`ClaimReviewCoordinator`、`ScholarChangeRequest`、`WorkVersion`、`CollectionTask`、`DedupCandidate`、`IdentityCandidate`/`ScholarMerge`、`AttributionAssignment`、`CollaborationEdge`/`GraphCtl`、`DisputeCase`/`PendingTodo`、`Notification`、`Session`、`ReviewerRole`、`FieldPolicy`、`PublicationSubmission`、`EvidenceFile`、独立 `EffectiveVersion`、`AuditCoordinator`、展示投影/DTO 均不进入本 UC VOPC。该集合将在 04 的顺序图中接受消息检验；若某类没有消息，则删除或降为属性/职责。
