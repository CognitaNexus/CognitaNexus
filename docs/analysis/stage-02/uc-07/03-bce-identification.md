# UC-07 Boundary / Control / Entity 识别

**版本：** V1.0（2026-10-21）
**方法：** 教材 §5.4.2：从用例事件流识别边界类、控制类、实体类；不从现有全局 BCE 反向填充。
**输入：** 本目录 01 规约、02 事件流。

## 1. 识别原则

1. **Boundary**：与外部参与者交互、接收输入或显示结果的对象。
2. **Control**：协调一个用例内的授权校验、字段判定、待审登记/生效、失败处理和结果返回；不是实现层 Controller/Service。
3. **Entity**：承载业务事实、状态和持久信息；不是数据库表。
4. 只有在该 UC 事件流中实际承担消息的对象才进入 VOPC。
5. 参与者“已获授权 Scholar 管理者”“有权审核的管理员”不是系统分析类；他人的 Scholar 档案与 `UserProfile` 是被拒绝的写入目标，不是本 UC 的参与对象。
6. 仅因“以后可能用到”而出现的类不进入本 UC。

## 2. 候选分析类

| ID | 候选类 | 类型 | 从哪些事件发现 | 业务行为 | 为什么不能由其他候选直接承担 |
|---|---|---|---|---|---|
| C1 | `ScholarMaintenanceUI` | Boundary | E1、E7、E10、A1–A5、A10 | 接收“请求维护被授权档案”“提交档案事实变更”“查询本人待审状态”“提交审核决定与理由”；显示可维护字段、越权/停用拒绝、超范围拒绝、转 UC-22 说明、待审状态、已生效、已驳回、失败 | `Scholar`/`ScholarAuthorization` 是业务实体，不应承担输入/显示；`ScholarMaintenanceCoordinator` 是内部协调者，不直接面对参与者 |
| C2 | `ScholarMaintenanceCoordinator` | Control | E2–E10、A1–A9 | 服务端校验账号状态与对象级授权；确认目标档案一致；判定字段范围与变更性质；登记待审或写入生效版本；复核审核守卫；协调受控审计；返回结果 | 授权判定跨 `User`、`ScholarAuthorization`、`Scholar` 三类事实；字段范围判定同时依赖授权范围与档案事实；任何单一 Entity 只能自知，不能独立完成“授权∧范围∧目标一致”的合取 |
| C3 | `User` | Entity | E2、E8、A1、A8 | 提供账号标识与账号状态；作为授权的被授权主体（请求者）与审核者身份主体 | 账号状态与授权主体是账号事实，不属于 Scholar 档案，也不属于授权记录（授权只记录“谁被授权”，不记录账号是否停用） |
| C4 | `Scholar` | Entity | E3、E5、E6、E9、A3、A10 | 承载已收录学者档案：已核验学术身份、机构、归属与来源事实；提供当前公开字段与生效公开版本；只接受授权且经审核（或属可直接生效）的字段更新；不承载账号、不承载授权范围 | `UserProfile` 是注册用户的普通主页，不是学术身份档案；`ScholarAuthorization` 只表达“谁能管”，不表达“档案内容是什么”；两者都不能承载档案事实与生效版本 |
| C5 | `ScholarAuthorization` | Entity | E3、A2、A3 | 回答“该请求者对该目标 Scholar 是否持有有效对象级管理权、范围到哪里”；供每次受保护写入与每次审核决定只读判定 | 授权不是档案内容（`Scholar`），也不是账号状态（`User`）；`ClaimApplication` 是申请过程，批准后产生的权利事实由授权承载（FR05；SPEC ID-07） |
| C6 | `ScholarChangeRequest` | Entity | E5、E6、E7、E8、E9、A7、A10 | 保存一次待审的档案字段变更：目标档案、提交者、旧值/新值、理由与证据引用、状态（待审核/通过/驳回）、提交与决定时间、版本；为提交者提供本人待审状态、为审核者提供待审内容 | “待审不覆盖公开版”要求待审内容与公开版本**并存且可区分**；`Scholar` 承载的是已生效公开版本，若把待审内容塞进 `Scholar` 就无法表达“公开仍为旧值”；`AuditEvidence` 只记录发生过的动作与前后值，不承载“尚待决定的变更”与其生命周期 |
| C7 | `AuditEvidence` | Entity | E7、E10、A1、A2、A3、A9 | 保存受保护写入、越权尝试、待审登记、审核决定与生效的可追溯留痕：操作者身份、对象（档案/待审变更）、理由或结果、前后值、时间；不含凭据与认证材料原文 | 留痕不随档案公开，且必须覆盖“被拒绝、未发生写入”的情形；`Scholar` 只在生效时被写，`ScholarChangeRequest` 只在待审路径存在，都不能替代审计 |

## 3. 逐类选择理由

### C1 ScholarMaintenanceUI（Boundary）

- 发现于 E1 提交变更、E7 返回待审结果、E10 返回决定结果，并承担 A1–A4 的拒绝显示、A5 的生效显示、A10 中“待审不入公开”的可见范围提示。
- 无持久业务属性；编辑内容、待审状态展示与决定输入是单次交互数据。
- **命名说明：** 全局 BCE 文本（`01-BCE分析类模型.md` §2）用 `ScholarPortalUI` 覆盖 UC-03/07 的“展示/维护授权 Scholar 档案”，但全局分析类图（`DGM-CLASS-01-analysis.puml`）没有该边界。本 UC 用 `ScholarMaintenanceUI` 明确“维护（写）”语义，与 UC-03 的读边界 `PublicProfileUI` 分离；合并/改名留 R5（B-11；见 07）。

### C2 ScholarMaintenanceCoordinator（Control）

- 发现于 E2–E4 的守卫、E5 的性质判定、E6/E9 的待审登记与生效、E8 的审核守卫、E7/E10 的审计与结果返回，以及 A1–A9 的失败/拒绝处理。
- 需要跨 `User`、`ScholarAuthorization`、`Scholar` 协调，并保证“拒绝时不写业务实体”“待审不覆盖公开版”“生效必须留痕”。
- 字段范围判定要同时使用“授权范围”“是否 Scholar 学术事实”“是否普通 UserProfile 字段”，不能由任一 Entity 单独裁决。
- **命名说明：** 全局图只有 `ProfileCtl ..> Scholar`（普通主页协调器指向 Scholar），没有维护档案的专用协调者。本 UC 使用 `ScholarMaintenanceCoordinator`，并建议 R5 把 `ProfileCtl ..> Scholar` 的维护职责迁出（见 07 §4.1）。

### C3 User（Entity）

- 发现于 E2 账号状态校验、E8 审核者权限与身份校验、A1 停用拒绝、A8 无权限拒绝。
- 必要业务事实：账号标识、账号状态；`ScholarAuthorization` 以“被授权主体”引用它（`0..* : 1`）。
- 不能并入 `ScholarAuthorization`：停用账号的受保护请求必须被拒绝（`AC-IMPL-NF03-01`），而授权记录本身不承载账号可用性。
- 不能并入 `Scholar`：学者档案可无账号，账号状态不是档案公开状态（FR05；SRS §12）。
- 本 UC **不新增**账号属性；审核权限划分沿用 UC-06 C-01（待确认）。

### C4 Scholar（Entity）

- 发现于 E3 目标一致性确认、E5/E6 的当前公开值、E9 生效版本生成、A3 越权对象、A10 公开读取依据。
- 必要业务事实：稳定身份引用、公开状态、身份核验状态、已核验学术身份（名称/别名）、机构与任职事实、归属事实、来源事实（原值引用）、当前**生效公开版本**。
- 不能并入 `UserProfile`：`UserProfile` 是注册用户的唯一普通主页；`Scholar` 可无账号、可未认领仍公开（FR03；SRS §12；BR-IMP-03）。
- 不能并入 `ScholarAuthorization`：授权只表达“谁可管、范围多大”，不承载档案内容；把档案字段放进授权会导致同一档案出现多份互不一致的内容。
- **不覆盖原始来源事实**：本 UC 只更新公开规范值；原始来源事实与原值保留（§7.2；BR-IMP-04；SPEC INV-01）。
- **不承载 Authorship**：档案维护不改参与关系状态（BR-UC07-07；`AC-IMPL-FR15-06`）。

### C5 ScholarAuthorization（Entity）

- 发现于 E3 有效授权与范围读取、A2 无授权拒绝、A3 超范围拒绝。
- 必要业务事实：目标 Scholar 引用、被授权主体（→`User`）、范围（仅目标 Scholar/门户，粒度待确认 B-05）、有效性、生效时间。
- 不能并入 `User`：注册用户的账号身份不等于对某 Scholar 的管理权；普通 `UserProfile` 所有权与 Scholar 管理权是两种权利（BR-IMP-05）。
- 不能并入 `ClaimApplication`：申请是过程与决定记录，授权是批准后产生的权利事实；`AC-IMPL-FR05-02` 要求“新授权仅对目标门户有效”。
- 本 UC **只读**：不创建（创建属 UC-06 M27）、不修改、不转移、不撤销（BR-IMP-05；SPEC ID-12）。

### C6 ScholarChangeRequest（Entity）

- 发现于 E5 性质判定后的待审登记、E6 公开版保持、E7 本人待审查询、E8 审核者读取待审内容、E9 决定结果、A7 版本冲突、A10 不可公开。
- 必要性来自`AC-IMPL-FR06-01`的三个可观察事实同时成立：①“提交重要机构变更”；②“尚未审批→公开仍为旧机构”；③“申请人可查待审状态”。第③项要求待审状态在请求结束后仍可被本人读取；第②项要求待审内容与公开版本并存。这两项无法由 `Scholar` 的单一公开字段集合或一次性的协调器上下文表达。
- 不能并入 `ClaimApplication`：认领申请的主体是“User 认领 Scholar”，本变更的主体是“已授权管理者修改档案字段”；两者的状态集合（`待审核/待补充/通过/驳回/争议` vs `待审核/通过/驳回`）与守卫都不同（FR05 vs FR06；`DGM-STATE-02` vs §6.4）。
- 不能并入 `AuditEvidence`：审计记录的是“已发生的动作与前后值”，不承载“尚待决定的内容与其生命周期”。
- **待确认：** 是否允许同一档案多条待审并存/并发提交（B-09）；与 UC-08 的“成果字段修改申请”是否统一为同一分析概念（B-11，R5）。

### C7 AuditEvidence（Entity）

- 发现于 E7（待审登记留痕）、E10（决定与生效留痕）、A1/A2/A3（拒绝与越权尝试留痕）、A9（无审计不报生效）。
- 必要业务事实：操作者身份（管理者/审核者）、对象引用（目标 Scholar、待审变更）、理由或结果、前后值（公开字段值的变更、待审状态的变更）、时间；不含凭据与认证材料原文。
- 不能由 `Scholar` 承担：审计覆盖“被拒绝、未发生写入”的情形，且不随档案公开（BR-IMP-01/11）。
- 不能由 `ScholarChangeRequest` 承担：驳回/拒绝/越权尝试并不都产生待审变更；审计还须覆盖生效动作本身。
- **命名说明：** 全局图用 `Evidence`、全局 BCE 表用 `ReviewEvidence`；本 UC 沿用 UC-22/UC-06 的 `AuditEvidence` 表示受保护写入留痕，与认领/审核材料分离；统一留 R5（D-13）。

## 4. 被拒绝的候选类

| 候选 | 拒绝理由 | 证据/后续 |
|---|---|---|
| `UserProfile` / 第二个 `UserProfile` | 本 UC 无任何 UserProfile 消息：普通字段维护属 UC-22；A4 只做“不写入 + 引导”说明；不创建第二个主页 | FR06；SRS §12；BR-IMP-05；UC-22 |
| `ClaimApplication` / `ClaimCoordinator` | 本 UC 不做认领：不创建申请、不读申请状态；授权来源在 UC-05/06 | FR05；`AC-IMPL-FR05-04` |
| `Authorship` / `WorkEntity` / `WorkVersion` | 档案维护不改参与关系与成果；归属纠正属 UC-08/FR15；无任何消息 | `AC-IMPL-FR15-06`；FR07；SPEC ID-06/ID-07 |
| `SourceRecord` | 原始来源事实是来源侧证据，本 UC 只保留引用并禁止覆盖，不写来源记录 | §7.2；BR-IMP-04；SPEC INV-01 |
| `ReviewEvidence` | 本 UC 的待审变更只需引用已有证据材料（`evidenceRefs` 属性），材料提交/可见范围的完整处理属 UC-05/06 与 B-07；不新增材料对象 | BR-IMP-11；NF06；D-06 |
| `PublicProfileUI` / `PublicProfileCoordinator` | 公开读取与展示属 UC-03；本 UC 只保证写侧“待审不入公开” | UC-03；BR-IMP-01 |
| `Session` / `CredentialContext` | “已认证请求上下文”是本 UC 的输入而非业务实体；基线未定义会话对象生命周期 | NF03；SRS §8；B-08 |
| `ReviewerRole` / `PermissionMatrix` / `ApprovalChain` | 基线只区分“是否具备对应审核权限”，不定义等级、审批链；不得自造 | FR14；NF03；UC-06 C-01 |
| `ConflictQueue` / `PendingTodo` / `DisputeCase` | 待办与争议载体的分析与界面属 UC-17；本 UC 只产生待审变更状态 | FR14；UC-06 C-05 |
| `Notification` / `SlaTimer` | 通知渠道与 SLA 未定义（D-06） | 分析推导；D-06 |
| `ScholarMerge` / `IdentityAlias` | 同名合并属 FR15 消歧治理（UC-09/10），本 UC 不改身份归并 | BR-IMP-03；SPEC ID-01～ID-04 |
| `FieldPolicy` / `FieldCatalogue` | 字段分类知识分别落在请求（协调器上下文）与档案可维护字段集合上；基线未定义独立字段策略对象，且分类口径未冻结 | FR06；B-01/B-02 |
| `EffectiveVersion` / `PublicVersion`（独立类） | §6.4 的“生效版本”在 UC-07 中以 `Scholar` 的当前公开字段与版本标识表达；无消息寻址独立版本对象，拆分留实现阶段 | §6.4；NF05；B-03 |
| `AuditCoordinator`（全局 `AuditCtl`） | 本 UC 只需一次写入留痕；审计查询与集中治理属 UC-17 | BR-IMP-10；FR14 |
| `MaintenanceView` / `DTO` / `EditDraft` | 展示投影/表单草稿是交互态，不是业务事实 | 教材 §5.4.2、§5.5.2 |

## 5. 四方边界结论

| 边界 | 结论 | 本 UC 证据 |
|---|---|---|
| `UserProfile`（普通主页） | **本 UC 不写**；昵称/头像/简介/兴趣领域/展示设置属 UC-22；两套对象不合并 | FR06；SRS §12；UC-22；A4 |
| `Scholar`（学术身份档案） | 本 UC 的**写对象**，但只写授权范围内、经审核（或属可直接生效）的学术事实；保留原始来源事实；不承载账号与授权 | FR06；§7.2；BR-IMP-04；SRS §12 |
| `ScholarAuthorization`（对象级管理权） | 本 UC 的**唯一授权依据**，只读：有效性与范围每次受保护写入都重新判定；不创建/转移/撤销 | FR05；`AC-IMPL-FR05-02`；BR-IMP-05；SRS §8；SPEC ID-12 |
| `ScholarChangeRequest`（待审变更） | 需审核变更的**并存对象**：承载未生效内容与状态，保证“公开仍为旧值 + 本人可查待审” | FR06；`AC-IMPL-FR06-01`；§6.4；BR-IMP-10 |
| `ScholarChangeRequest` ↔ `ClaimApplication` | 两个不同对象、两套状态：前者是“已授权者改档案字段”，后者是“User 认领 Scholar” | FR05 vs FR06；`DGM-STATE-02` vs §6.4 |
| `AuditEvidence` | 审计留痕，不进入公开视图，覆盖拒绝与越权尝试；与审核材料、待审内容分开 | BR-IMP-10/11；NF03；INV-10 |
| `Scholar` ↔ `UserProfile` | **无关联、不按同名合并**；维护 Scholar 不使任何普通主页变为“已认证学者” | BR-IMP-03；`AC-IMPL-FR03-04`；SRS §12 |
| `Scholar` ↔ `Authorship` | 本 UC 不建立/不改变；档案维护不确认、不解除、不提升任何参与关系 | BR-UC07-07；`AC-IMPL-FR15-06` |

## 6. 识别结论

本 UC 的候选分析类共 7 个：

- Boundary：`ScholarMaintenanceUI`
- Control：`ScholarMaintenanceCoordinator`
- Entity：`User`、`Scholar`、`ScholarAuthorization`、`ScholarChangeRequest`、`AuditEvidence`

`UserProfile`、第二个 `UserProfile`、`ClaimApplication`、`ClaimCoordinator`、`Authorship`、`WorkEntity`、`WorkVersion`、`SourceRecord`、`ReviewEvidence`、`PublicProfileUI`、`PublicProfileCoordinator`、`Session`、`ReviewerRole`/`PermissionMatrix`/`ApprovalChain`、`ConflictQueue`/`PendingTodo`/`DisputeCase`、`Notification`、`ScholarMerge`、`FieldPolicy`、独立 `EffectiveVersion`、`AuditCoordinator`、`MaintenanceView`/`EditDraft` 均不进入本 UC VOPC。该集合将在 04 的顺序图中接受消息检验；若某类没有消息，则删除或降为属性/职责。
