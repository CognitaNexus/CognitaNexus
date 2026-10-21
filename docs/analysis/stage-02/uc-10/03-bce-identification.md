# UC-10 Boundary / Control / Entity 识别

**版本：** V1.0（2026-10-21）
**方法：** 教材 §5.4.2：从用例事件流识别边界类、控制类、实体类；不从现有全局 BCE 反向填充。
**输入：** 本目录 01 规约、02 事件流。

## 1. 识别原则

1. **Boundary**：与外部参与者交互、接收输入或显示结果的对象。
2. **Control**：协调本用例内的证据收集、归一、候选生成、判定守卫、生效与留痕；不是实现层 Controller/Service。
3. **Entity**：承载业务事实、状态和持久信息；不是数据库表。
4. 只有在本 UC 事件流中实际承担消息的对象才进入 VOPC。
5. 参与者“管理员”“相关学者”“系统”“治理管线”不是系统分析类；被拒绝的写入目标（`UserProfile`、`ClaimApplication`、他人的关系）不是本 UC 的参与对象。
6. 仅因“以后可能用到”而出现的类不进入本 UC。

## 2. 候选分析类

| ID | 候选类 | 类型 | 从哪些事件发现 | 业务行为 | 为什么不能由其他候选直接承担 |
|---|---|---|---|---|---|
| C1 | `IdentityVerificationUI` | Boundary | E1、E11–E13、E18；A1–A3、A7、A8–A10、A12、A17 | 接收“待归属署名事实”“候选/争议处理项打开”“判定与理由”“学者说明材料”；显示候选成员与逐项证据、影响范围、可判定动作、拒绝（未认证/越权/版本已变）、保持已排除、候选过多、争议/拆分结果与未生效提示 | `Scholar`/`IdentityCandidate` 是业务实体，不应承担输入与显示；协调者是内部对象，不直接面对参与者 |
| C2 | `IdentityVerificationCoordinator` | Control | E2–E18；A1–A20 | 服务端校验账号状态与治理处置权限；只读来源原值；收集/追加身份证据；姓名归一；召回候选；保守判定守卫；生成候选；判定复核与执行；状态写入；传播声明；留痕与结果返回 | 守卫同时依赖 `User`、`ScholarAuthorization`、`SourceRecord`、`Scholar`、`IdentityCandidate`、`AttributionCandidate`、`Authorship`、`WorkEntity` 八类事实；任何单一实体只能自知，且必须保证“拒绝无副作用、候选不合并、审核不冒充本人确认、已排除不恢复” |
| C3 | `User` | Entity | E11、A1、A2 | 提供账号标识、账号状态与治理处置权限事实 | 账号与权限属账号事实，不属于可无账号存在的 `Scholar`，也不属于只记“谁被授权管理哪个档案”的 `ScholarAuthorization` |
| C4 | `ScholarAuthorization` | Entity | E11、A13 | 回答“该档案是否已有有效门户管理授权、被授权主体是谁、范围到哪里”，用于判断“判定不因此转移/撤销授权”与是否需要冲突处理项 | 授权不是身份档案（`Scholar`）、不是账号（`User`）；把授权并入档案会使“判定改变档案”顺手改掉管理权（违反 BR-349） |
| C5 | `SourceRecord` | Entity | E2、E10、E15 | 只读提供署名原值、机构原值、外部标识原值、许可、到达时间与可达性；保证人工判定不覆盖原值 | 来源记录是来源侧原始证据；把规范化或判定结果写回来源会违反 INV-01；`Scholar` 只承载规范/证据项，不承载原值 |
| C6 | `Scholar` | Entity | E3、E4、E6、E9、E11、E15 | 承载学术身份档案：稳定引用、公开状态、`identityState`、姓名表达/别名（含来源）、机构关系及时间、外部标识（含命名空间/可信度）、合作者、领域、证据集合（追加式）、合并链；接受新建、合并、拆分与身份状态变化 | `User` 是账号、`UserProfile` 是普通主页；`Authorship` 只表达一条参与关系；`IdentityCandidate` 只表达“可能同一人”的待核验组合，不承载档案事实 |
| C7 | `IdentityCandidate` | Entity | E10、E11、E13、E14、E15 | 保存身份候选组：成员档案（≥2）、逐项证据命中/冲突、排序依据（仅供查看顺序）、候选规模提示、状态（`待核验/已确认（合并）/已排除（保持独立）/争议中`）、排除依据 | “候选不合并、证据不足不出结论”要求候选与档案事实**并存且可区分**；`Scholar` 只能表达“已经成立的档案”，不能表达“尚不确定是否同一人”的组合；`AuditEvidence` 只记录已发生动作 |
| C8 | `AttributionCandidate` | Entity | E10、E11、E13、E15 | 保存归属候选：署名原值、所属成果与来源引用、角色与顺序、候选学者集合、逐项证据命中/冲突、状态（`待核验/已确认归属/已排除/争议`）与依据；确认后才促成关系的建立或改派 | 归属未定时不能建立确定关系；`Authorship` 表达“归属已定、证据强弱由状态表达”（FR15-S07 E1 的 `待核验` 关系），而“归属未定”必须另有载体，否则会出现一条指向错误学者的关系（B-05） |
| C9 | `Authorship` | Entity | E5、E8、E11、E15、E16 | 承载一条“学者—成果”参与关系：角色、顺序、来源证据引用、独立状态与状态历史；接受 `自动关联/待核验/争议/已排除` 的写入（**不接受**本 UC 写 `本人确认`） | 关系状态不属于 `WorkEntity`（成果整体状态）也不属于 `Scholar`（身份事实）；同一成果的多位参与者必须分别成条（FR15-S07；BR-353；SPEC ID-05/ID-11） |
| C10 | `WorkEntity` | Entity | E11、E17 | 只读提供目标成果引用、类型与公开状态，用于计算影响范围；接收“按生效关系更新参与展示”的声明 | 成果实体是共享对象，其存在不依赖某位参与者；来源数不等于成果数（INV-02）。身份判定不得合并/删除成果或改共享字段（BR-341） |
| C11 | `ReviewEvidence` | Entity | E12、E13、E18 | 承载相关学者或审核方提交的说明与佐证材料及可见范围（提交者＋有权审核者）；供有权审核者核对，向本人提供摘要 | 材料的产生者、许可与可见性不同于来源原值（`SourceRecord`），也不同于动作留痕（`AuditEvidence`）；`AC-IMPL-NF06-01` 要求普通用户不得读取他人材料 |
| C12 | `AuditEvidence` | Entity | E3、E5、E8、E9、E10、E11、E12、E13、E15、E17、E18；A1–A3、A5、A7–A13、A16、A17、A20 | 保存证据剔除登记、自动关联依据、候选生成、拒绝/越权、争议登记、合并/排除/拆分判定、生效与传播结果的可追溯留痕（操作者/对象/理由/前后值/时间），不含材料原文与凭据 | 留痕必须覆盖“被拒绝、未写入”的尝试，且不进入公开视图；候选与关系只在其状态变化时被写，无法承担“尝试但未发生”的事实 |

## 3. 逐类选择理由

### C1 IdentityVerificationUI（Boundary）

- 发现于 E1 交付、E11–E13 打开与提交、E18 结果返回；并承担 A1/A2 的拒绝显示、A3/A7 的“仅候选/保持已排除”、A8–A10 的“仍待核验/已标记争议/状态已变化”、A12 的“候选过多”、A17 的“待更高权限确认”。
- 无持久业务属性；所选候选/关系、判定类型与理由、结果展示是单次交互数据。
- **命名说明：** 全局 BCE 文本与 `DGM-CLASS-01-analysis.puml` 只有 `AdminUI`（`..>` CollectCtl/GovernCtl/AuditCtl）与 `01` §2 的 `AdminConsole`，没有区分“治理核验写入口”。本 UC 用 `IdentityVerificationUI` 明确“身份与归属核验写入口”语义，与读边界 `PublicProfileUI`/`WorkDetailUI` 及 UC-09 的 `DedupGovernanceUI` 分离；合并入 `AdminUI`/`AdminConsole` 的建议留 R5（B-10；07 §2）。

### C2 IdentityVerificationCoordinator（Control）

- 发现于 E2 只读取证、E3/E4 证据追加与归一、E5 排除检查、E6 召回、E7 保守判定、E10 候选生成、E11–E16 判定与状态写入、E17 传播、E18 留痕。
- 需要跨八类实体协调，并保证：①拒绝时不写业务实体；②候选不合并、不建立确定归属；③人工核验只写 `自动关联`，绝不写 `本人确认`；④已排除不被自动恢复；⑤合并/拆分先守卫、失败整体回滚；⑥生效必须留痕并传播。
- 状态优先级判定（已排除 > 争议 > 待核验 > 本人确认 > 自动关联）必须同时读取关系状态、冲突标记与既有关系，不能下推给单一实体。
- **命名说明：** 全局 `GovernCtl`（“+清洗/去重/候选”）与 `ClaimCtl`（“+证据/授权/争议”）都没有“学者身份/归属核验”的明确职责。本 UC 使用 `IdentityVerificationCoordinator`，建议 R5 在两者之间划清边界（07 §3/§4）。

### C3 User（Entity）

- 发现于 E11 的账号状态与治理处置权限校验、A1/A2 的拒绝。
- 必要事实：账号标识、账号状态、治理处置权限；`ScholarAuthorization` 以“被授权主体”引用它。
- 不能并入 `Scholar`：档案可无账号（FR05；SRS §12）；账号停用不是档案状态（`AC-IMPL-NF03-01` 要求停用账号的受保护请求被拒绝）。
- 本 UC **不新增**账号属性；治理处置权限的等级划分沿用 FR14 的待确认口径。

### C4 ScholarAuthorization（Entity）

- 发现于 E11 的“是否已认领/被授权主体”读取、A13 的“管理权不变”判定。
- 必要事实：目标 `Scholar` 引用、被授权主体、对象级范围、有效性。
- 不能并入 `Scholar`：BR-349 明确“身份合并或归属判定不得自动转移门户管理权”；若把授权当档案属性，合并/拆分就会顺带改管理权。
- 不能并入 `ClaimApplication`：申请是过程记录，授权是批准后的权利事实。
- 本 UC **只读**：不创建（属 UC-06）、不修改、不转移、不撤销（BR-IMP-05；SPEC ID-12；A13）。

### C5 SourceRecord（Entity）

- 发现于 E2 只读取证、E10 候选的证据来源、E15 执行判定时保留原值。
- 必要事实：来源标识、来源域、来源内记录键、署名/机构/标识原值、许可、到达时间。
- 不能并入 `Scholar`：原值与规范/证据项必须分离（INV-01；§7.2；BR-IMP-04）。同一档案的多条证据来自不同来源记录，若把原值写进档案，“原值不被改写”无法验证。
- 不能并入 `ReviewEvidence`：前者是平台从获准来源取得的原始证据（含许可与到达时间），后者是本次审核由提交者提供、可见范围受限的材料。
- 本 UC **只读**：不写、不删、不改原值。

### C6 Scholar（Entity）

- 发现于 E3/E4 证据追加与别名登记、E6 候选召回、E9 新建、E11 证据读取、E15 合并/拆分、E17 门户投影更新。
- 必要性来自四条相互独立的要求：①“学者档案与账号分离、可未认领公开”（FR15 规范性要求；FR03）；②“证据追加式保存、逐项可追溯到来源与到达时间”（FR15-S01）；③“同名不自动合并、证据不足不出结论”（BR-342；SPEC ID-03）；④“合并证据取并集、拆分按证据归属并保留合并链”（BR-347；FR15-S10 A2）。
- 不能并入 `User`/`UserProfile`：账号停用不删除档案；普通主页不是学术身份（FR03；`AC-IMPL-FR03-04`）。
- 不能并入 `Authorship`：身份事实跨多条关系共享，关系只表达“参与了哪项成果”（BR-353）。
- 不能并入 `IdentityCandidate`：候选是“可能同一人”的待核验组合；档案是已成立的身份事实，候选可以关闭而档案不变。

### C7 IdentityCandidate（Entity）

- 发现于 E10 生成、E11 读取、E13 判定、E14 版本守卫、E15 状态写入（补充证据/争议/保持独立/合并）。
- 必要性：`AC-IMPL-FR15-01/03` 要求“不自动并成一人”且“待人工核验”，意味着“档案仍为两个”与“存在一个待处置问题”必须**同时**存在；`FR15-S05 主流程 4` 还要求“关闭候选并记录排除依据，同一组合后续不再自动生成（除新证据）”。这两项都无法由 `Scholar` 的字段表达（档案字段不能记录“曾被判为不同人且不得重复建议”的组合事实）。
- 不能并入 `AttributionCandidate`：身份候选回答“这两个档案是否同一人”（成员是档案）；归属候选回答“这条署名属于谁”（对象是署名）。同一记录里两者可以并存但成员、判定动作与结果不同（见 C8）。
- **待确认：** 状态集合是否含独立的 `待补充证据`（B-01）。

### C8 AttributionCandidate（Entity）

- 发现于 E10 生成、E11 读取、E13 判定、E15 状态写入（确认归属/排除）。
- 必要性：`AC-IMPL-FR15-01` 明确“成果关系进入候选”，`FR15-S07 E1` 又只覆盖“无任何候选档案→新建档案＋关系待核验”。当存在 **≥2 候选档案且无法判定** 时，既不能新建重复档案，也不能把关系直接挂到某一档案；必须有一个对象记录“这条署名与这些候选学者的比对结果”。
- 不能并入 `Authorship`：关系一旦建立就指向一位学者；把多候选写进关系会使“一条关系只归于一位学者”（BR-353）失效。本分析区分：`AttributionCandidate`＝归属未定；`Authorship(待核验)`＝归属已定但证据不足（FR15-S07 E1）。
- 不能并入 `IdentityCandidate`：见 C7。
- **待确认（B-05）：** 若评审认为可用“`Authorship(待核验)`＋候选属性”替代本类，本类可降为属性；本包保留独立类并说明理由。

### C9 Authorship（Entity）

- 发现于 E5 既有状态查询、E8 建立/更新、E11 影响范围读取、E15/E16 状态写入（`自动关联`/`待核验`/`争议`/`已排除`）。
- 必要性：`FR15-S07` 要求每位参与者一条关系并保留角色与顺序；`§6.4`/`BR-356` 要求五状态与优先级；`FR15-S11` 要求解除只终止一条关系。三者分别要求“按参与者分行”“关系自带状态”“关系与成果可分离”。
- 不能并入 `WorkEntity`：成果共享实体不依赖某位参与者（INV-04/05）。
- 不能并入 `Scholar`：状态是关系事实不是身份事实；同一位学者在不同成果上状态不同。
- **本 UC 的写入边界：** 只写 `自动关联`（系统保守判定或管理员核验后）、`待核验`、`争议`、`已排除`；**不写** `本人确认`（`AC-IMPL-FR15-03/06`；SPEC ID-06/ID-10）。

### C10 WorkEntity（Entity）

- 发现于 E11 影响范围计算、E17 生效关系传播。
- 必要性：影响范围必须按“受影响成果”计算（门户展示、统计口径、FR12/IN01 派生）；同时必须证明本 UC 不合并、不删除、不改共享字段（BR-341；INV-02/04）。
- 本 UC **只读**目标引用与公开状态，只声明“按生效关系更新参与展示”，不写字段、不新建、不删除、不合并。

### C11 ReviewEvidence（Entity）

- 发现于 E12 材料登记、E13 判定时读取、E18 本人摘要。
- 沿用 UC-05/UC-06/UC-08 同名类的含义（材料＋可见范围），不新增第二个材料类。
- 不能并入 `SourceRecord`：材料可包含本人说明与凭证，未必是获准来源记录，且必须带可见范围。
- 不能并入 `AuditEvidence`：材料是“证据”，审计是“动作留痕”；`AC-IMPL-NF06-01` 要求普通用户不得读取他人材料。
- **边界：** 提交材料**不**改变关系状态，**不**构成本人确认（A18；`AC-IMPL-FR15-03`）。

### C12 AuditEvidence（Entity）

- 发现于 E3 剔除登记、E8/E9/E10 判定依据、E11–E13 拒绝与请求、E15 合并/排除/拆分、E17 生效传播、E18 结果。
- 必要性：审计必须覆盖“被拒绝、未发生写入”的尝试，且不随公开视图展示（BR-IMP-01/11），这既不是候选状态也不是关系状态。
- 命名沿用 UC-06/UC-09/UC-22 的 `AuditEvidence`；与 `ReviewEvidence`（材料）及全局 `Evidence` 的差异留 R5（D-13）。

## 4. 被拒绝的候选类

| 候选 | 拒绝理由 | 证据/后续 |
|---|---|---|
| `UserProfile` / 第二个公开主页 | 本 UC 无普通主页消息；`UserProfile` 与 `Scholar` 不按姓名合并，注册即唯一存在 | FR03；FR04；`AC-IMPL-FR03-04`；SRS §12 |
| `ClaimApplication` / `ClaimCoordinator` / `ClaimReviewCoordinator` | 认领申请与审核属 UC-05/UC-06；本 UC 不创建、不读申请状态；授权只读 | FR05；`AC-IMPL-FR05-02/04`；`DGM-STATE-02` |
| `ScholarChangeRequest` | UC-07 的“档案字段待审变更”对象；本 UC 不写展示字段 | FR06 vs FR15；UC-07 06 §7 |
| `CorrectionRequest` | UC-08 的成果关系纠错/补录待审对象；本 UC 的判定对象是身份候选、归属候选与关系状态 | FR07 vs FR15；`AC-IMPL-FR07-01/02` |
| `WorkVersion` / `DedupCandidate` / `MergeDecision` / `CleanRecord` | 版本、去重与清洗属 FR09/UC-09；身份合并不改变成果实体 | FR09；BR-341；UC-09 03 |
| `AliasMap` / `NameNormalizer` / `AliasDictionary` | 归一是协调器内的分析操作与 `Scholar.nameExpressions` 的事实，未冻结为独立业务对象 | FR15-S02；教材 §5.4.2 |
| `SimilarityScorer` / `ThresholdPolicy` / `ExternalIdAuthority` | 阈值与充分性未冻结（SPEC §6.5；B-03），不能建模为已批准实体 | §6.5；D-07；Q-SPEC-01 |
| `MergeRecord` / `SplitRecord`（独立类） | 合并链由 `Scholar.mergedIntoRef` 属性＋`AuditEvidence` 表达；无独立消息寻址该对象 | FR15-S03；BR-347/382；UC-09 07 §4.5 同类取舍 |
| `DisputeCase` / `ConflictQueue` / `PendingTodo` / `ClaimConflictItem` | 争议结案、待办队列与认领冲突处理项属 FR14/UC-17；本 UC 只置“争议”并记录需生成处理项 | FR15-S13；FR14；B-01 |
| `Notification` / `SlaTimer` | 通知渠道与时限未定义（B-02） | 分析推导；D-06 类比 |
| `Session` / `CredentialContext` | “已认证请求上下文”是本 UC 输入，不是业务实体 | NF03；B-09 |
| `ReviewerRole` / `PermissionMatrix` / `ApprovalChain` | 基线只区分“是否具备对应治理处置权限” | FR14；NF03 |
| `EvidenceItem` / `IdentityEvidence`（独立类） | 证据项由 `Scholar` 的追加式证据集合表达（每项带来源与到达时间）；顺序图中无独立对象寻址，升为类会造成“证据类 + 材料类 + 审计类”三重冗余 | FR15-S01；05 §2.3 |
| `AffiliationPeriod` / `ExternalIdentifier`（独立类） | 作为 `Scholar` 的结构化属性；无独立消息寻址 | FR15-S01；教材 §5.5.2 |
| `CollaborationEdge` / `GraphCoordinator` | 建边、强度与论文子图属 FR12/IN01（UC-14/UC-15）；本 UC 只产出生效关系 | BR-IMP-08/09；`AC-IMPL-FR12-04` |
| `PublicationSubmission` / `Manuscript` | 基线不提供投稿/出版 | FR07；SRS §11.4 |
| `EvidenceFile` / `Attachment` / `UploadStore` | 材料存储形态属实现设计 | 教材 §5.4.2 |
| 独立 `EffectiveVersion` | 生效值以 `Authorship` 当前状态与 `Scholar.identityState` 表达 | §6.4；NF05；B-02 |
| `AuditCoordinator`（全局 `AuditCtl`） | 审计查询与集中治理属 UC-17；本 UC 只需写入留痕 | FR14；BR-IMP-10 |
| 候选/证据展示投影（`CandidateView`/`EvidenceDTO`） | 展示投影是交互态，不是业务事实 | §5.4.2、§5.5.2 |

## 5. 八项对象边界结论

| 边界 | 结论 | 本 UC 证据 |
|---|---|---|
| `Scholar`（学术身份档案） vs `User`/`UserProfile`（账号与普通主页） | 档案可无账号、可未认领公开；账号停用不删档案；普通主页与同名档案**不合并** | FR03；`AC-IMPL-FR03-04`；`AC-IMPL-NF03-01` 类；SRS §12 |
| `Scholar`（身份事实） vs `Authorship`（参与关系） | 身份跨成果共享；关系一学者×一成果一条，自带角色/顺序/状态；合并档案要迁移关系但不能把关系当身份 | FR15-S07；BR-353；SPEC ID-05/ID-11 |
| `Scholar` vs `IdentityCandidate`（身份候选） | 候选是“可能同一人”的待核验组合，成员≥2；候选存在时档案仍为独立两个，公开结论不变 | FR15-S04；`AC-IMPL-FR15-01/03`；BR-342 |
| `AttributionCandidate`（归属候选） vs `Authorship`（关系） | 归属未定→候选（署名保留、多候选并列）；归属已定但证据不足→关系 `待核验`（FR15-S07 E1）；确认后才建立/改派关系 | `AC-IMPL-FR15-01`；FR15-S07 E1；FR15-S10；B-05 |
| `WorkEntity`（成果实体） vs `Authorship`（参与关系） | 成果共享字段与存在不依赖某位参与者；身份合并不改变成果数；本 UC 只读成果、不合并/不删除/不改字段 | INV-02/04/05；BR-341；FR15-S11 |
| `SourceRecord`（来源原值） vs `Scholar` 证据集合 | 原值只读且永不改写；证据集合是追加式的规范化证据项（带来源与到达时间）；判定结果另行留痕 | INV-01；§7.2；FR15-S01；BR-357 |
| `IdentityCandidate`/`AttributionCandidate` vs `ReviewEvidence` | 候选是“待核验问题＋逐项证据”，材料是“提交者提供的说明与佐证＋可见范围”；提交材料不改变候选状态 | FR15-S05 权限规则；`AC-IMPL-NF06-01`；A18 |
| `ReviewEvidence`（材料） vs `AuditEvidence`（留痕） | 材料是证据内容与可见性；留痕是“谁在何时做了什么判定、前后值是什么”，覆盖被拒绝的尝试且不进公开视图 | FR15-S08 三类证据分离；SPEC ID-10；BR-IMP-10/11；INV-10 |
| 管理员判定 ↔ 本人确认 | 人工核验只形成 `自动关联`＋独立审核证据；`本人确认` 只能由 UC-08 的本人事件产生 | SPEC ID-06/ID-10；§6.4 `ON_ADMIN_REVIEW`；`AC-IMPL-FR15-03/06`；FR15-S06 |
| 关系状态 ↔ 合作网络 | 只有经核验的合法关系参与建边；`待核验/争议/已排除` 不建边、不进 IN01 | §6.4；BR-IMP-08；`AC-IMPL-FR12-04` |
| 身份合并 ↔ 成果去重 | 两个独立判定：合并学者不归并成果实体，合并成果不合并学者 | BR-341；SPEC §6.5；FR09/UC-09 |

## 6. 识别结论

本 UC 的候选分析类共 12 个：

- Boundary：`IdentityVerificationUI`
- Control：`IdentityVerificationCoordinator`
- Entity：`User`、`ScholarAuthorization`（只读）、`SourceRecord`（只读）、`Scholar`、`IdentityCandidate`、`AttributionCandidate`、`Authorship`、`WorkEntity`（只读）、`ReviewEvidence`、`AuditEvidence`

`UserProfile`、`ClaimApplication`/`ClaimCoordinator`/`ClaimReviewCoordinator`、`ScholarChangeRequest`、`CorrectionRequest`、`WorkVersion`、`DedupCandidate`/`CleanRecord`/`MergeDecision`、独立 `AliasMap`/`SimilarityScorer`/`ThresholdPolicy`/`ExternalIdAuthority`、独立 `MergeRecord`/`SplitRecord`、`DisputeCase`/`PendingTodo`/`ClaimConflictItem`、`Notification`/`SlaTimer`、`Session`、`ReviewerRole`、独立 `IdentityEvidence`/`AffiliationPeriod`/`ExternalIdentifier`、`CollaborationEdge`/`GraphCtl`、`PublicationSubmission`、`EvidenceFile`、独立 `EffectiveVersion`、`AuditCoordinator`、展示投影/DTO 均不进入本 UC VOPC。该集合将在 04 的顺序图中接受消息检验；若某类没有消息，则删除或降为属性/职责。
