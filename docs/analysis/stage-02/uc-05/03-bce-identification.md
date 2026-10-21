# UC-05 Boundary / Control / Entity 识别

**版本：** V1.0（2026-10-21）
**方法：** 教材 §5.4.2：从事件流识别边界类、控制类、实体类；不从全局 BCE 反向填充。
**输入：** 本目录 01 规约、02 事件流。

## 1. 识别原则

1. **Boundary**：与外部参与者交互、接收输入或显示结果的对象。
2. **Control**：协调一个用例内的校验、创建顺序、失败处理和结果返回；不是实现层 Controller/Service。
3. **Entity**：承载业务事实、状态和持久信息；不是数据库表。
4. 只有在本 UC 事件流中实际承担消息的对象才进入 VOPC。
5. 参与者“注册用户”不是系统分析类；审核管理员属 UC-06，不进入本 UC。
6. 仅因“以后可能用到”或“全局图里有”而出现的类不进入本 UC。

## 2. 候选分析类

| ID | 候选类 | 类型 | 从哪些事件发现 | 业务行为 | 为什么不能由其他候选直接承担 |
|---|---|---|---|---|---|
| C1 | `ClaimUI` | Boundary | E1、E8、E9、E11、A1–A8 | 接收“选择 Scholar + 提交证据”与“查询本人申请”输入；显示待审核/待补充/拒绝/失败/无权/无结果/本人状态 | `User`/`Scholar`/`ClaimApplication` 是业务实体，不应承担输入与展示；`ClaimCoordinator` 是内部协调者，不直接面对申请人 |
| C2 | `ClaimCoordinator` | Control | E2–E8、E10–E11、A1–A8 | 服务端校验身份/账号状态；确认目标是已收录 Scholar；校验必要证据（或标记缺失）；只读读取现有授权识别竞争；创建申请与登记私有证据；按归属过滤查询并返回状态/理由 | Boundary 只负责交互，不能协调“身份+目标类型+证据齐备+不写授权+申请与证据一致写入”；各 Entity 只知道自己，无法独立完成跨对象校验与提交顺序 |
| C3 | `User` | Entity | E2、E6、E10 | 提供账号标识与账号状态，供判定“请求者是否为可用账号”；作为申请人的账号主体与“本人申请”归属依据 | `UserProfile` 是公开主页，不承载账号状态与登录主体；`Scholar` 是认领目标，不承载申请人身份 |
| C4 | `Scholar` | Entity | E3、E5、E6 | 提供已收录学者档案身份与公开/核验状态；作为认领目标被读取；不因认领而被创建或改写 | `UserProfile` 是普通账号主页，不是平台学者身份；`ClaimApplication` 是申请记录，不是被认领对象 |
| C5 | `ClaimApplication` | Entity | E6、E8、E10、E11、A1b、A4、A8 | 承载申请事实：申请人、目标 Scholar、证据引用、状态（待审核/待补充/通过/驳回/争议）、状态理由/缺失项、竞争事实；向本人提供可读状态与决定 | 申请状态不是一个 User/Scholar 的属性；`ReviewEvidence` 只承载材料，不承载申请状态；`ScholarAuthorization` 是授权结果，不是申请 |
| C6 | `ReviewEvidence` | Entity | E7、A1b、A6、E11 | 承载申请人提交的私有证明材料与可见范围（仅本人与有权审核者）；被关联到申请 | 材料原文与可见性不能并入 `ClaimApplication`（申请状态与材料生命周期不同）；也不能并入 `Scholar`/`UserProfile`（材料不公开、不属于档案资料） |
| C7 | `ScholarAuthorization` | Entity | E5（只读） | 提供目标 Scholar 当前有效授权事实（是否存在、授权主体、生效范围），供识别竞争认领并保证不改动 | 授权是审核通过后的对象级管理权结果（UC-06 产生），不是申请状态、不是账号状态、也不是 UserProfile 所有权；本 UC 只读，不能并入 `ClaimApplication` |

## 3. 逐类选择理由

### C1 ClaimUI（Boundary）

- 发现于 E1 提交、E8 提交结果、E9 查询请求、E11 状态显示，以及 A1–A8 的结果显示。
- 不持久保存申请或证据；输入是短期请求数据。
- **命名说明：** 全局 `DGM-CLASS-01` 与 `01-BCE分析类模型.md` 已有 `ClaimUI`，本 UC 沿用同名；全局职责写“提交/查询/审核认领”，本 UC 只使用提交与查询侧，审核侧展示属 UC-06。

### C2 ClaimCoordinator（Control）

- 发现于 E2/E3/E4/E5 的校验与只读读取、E6/E7 的申请与证据登记、E8 的结果返回，E10/E11 的查询与归属过滤，以及 A1–A8 的失败/拒绝处理。
- 需要跨 `User`、`Scholar`、`ClaimApplication`（申请）与 `ReviewEvidence` 协调，并且必须保持“提交阶段不写授权”“缺证不进入审核”“他人不可读”三条守卫。
- 证据齐备性判断同时需要申请内容与状态/缺失项口径，不能由单个 Entity 完成。
- **命名说明：** 全局 `DGM-CLASS-01` 用 `ClaimCtl`，`01-BCE分析类模型.md` 用 `ClaimReviewCoordinator`（职责含审核批准授予对象权）。本 UC 只实现提交/查询侧，故用 `ClaimCoordinator`；`ClaimCtl`/`ClaimReviewCoordinator` 的统一与是否拆分留到 R5（见 07 §2、08 B-09）。

### C3 User（Entity）

- 发现于 E2 身份与账号状态校验、E6 申请人与归属、E10 查询归属核验。
- 必要业务事实：账号标识、账号状态；作为申请人的账号主体。
- 不能并入 `UserProfile`：账号状态与登录主体不属于公开主页资料（FR04；SRS §12）。
- 不能并入 `Scholar`：Scholar 可无账号，且是认领目标（SRS §12）。
- 本 UC **不新增** User 属性；会话/凭据是否成为独立对象沿用 UC-04/UC-22 的待确认项。

### C4 Scholar（Entity）

- 发现于 E3“目标是已收录 Scholar”、E5 读取目标当前授权、E6 建立申请目标。
- 必要业务事实：稳定身份引用、公开/核验状态（认领前仍可公开读）。
- 不能并入 `UserProfile`：普通主页与学者档案是两个对象，认领不合并、不迁移主页（SRS §12；AC-IMPL-FR05-04）。
- 不能并入 `ClaimApplication`：Scholar 是申请指向的对象，独立于任何一次申请存在。
- **本 UC 不修改 Scholar**：无写消息；学者档案维护属 UC-07。

### C5 ClaimApplication（Entity）

- 发现于 E6 创建、E8 返回、E10 归属核验、E11 状态读取，以及 A1b 待补充、A4 提交失败、A8 无结果。
- 必要业务事实：申请人引用、目标 Scholar 引用、证据引用、状态（`待审核/待补充/通过/驳回/争议`）、状态理由/缺失项、竞争事实。
- 不能并入 `ScholarAuthorization`：申请是“请求被授权”的过程记录，授权是“已获授权”的结果；申请被驳回/争议时仍然存在（FR05；SPEC §3）。
- 不能并入 `Authorship`：Authorship 是学者—成果参与关系，认领申请不确认任何成果归属（FR05；SPEC ID-07）。
- 状态集来源：FR05 规范性要求 + 核心 SPEC §3 + `DGM-STATE-02`；本 UC 只产生 `待审核`（及 A1b 的 `待补充`）。

### C6 ReviewEvidence（Entity）

- 发现于 E7 登记私有材料、A1b 保存已交材料、A6 拒绝他人读取、E11 不回显他人证据原文。
- 必要业务事实：材料类别/摘要引用、提交者、关联申请、可见范围（本人与有权审核者）；不含凭据与秘密（NF03）。
- 不能并入 `ClaimApplication`：材料是私有证据、有独立可见性约束（BR-IMP-11；AC-IMPL-NF06-01），申请状态则需对本人可读。
- 不能并入 `UserProfile`/`Scholar`：证据不作为公开档案资料（BR-IMP-11；“普通资料不作学术证据”，SRS §12）。
- **命名说明：** `01-BCE分析类模型.md` 已有 `ReviewEvidence`（“审核/修正/授权的可追溯决定”），全局图只有 `Evidence`。本 UC 用它承载认领提交材料；与 UC-22 的 `AuditEvidence`、全局 `Evidence` 的命名统一登记为 D-13（见 07 §2、08 B-05）。

### C7 ScholarAuthorization（Entity）

- 发现于 E5 只读读取目标当前有效授权（识别竞争认领），以及 A5“原授权不转移”。
- 必要业务事实：目标 Scholar、被授权主体、对象级范围（仅目标门户）、当前有效状态；本 UC 只读。
- 不能并入 `ClaimApplication`：竞争申请与原授权是两件事，AC-IMPL-FR05-03 要求原授权保留（BR-IMP-05；SPEC ID-12）。
- 不能并入 `UserProfile` 的所有权：UserProfile 所有权来自注册（UC-04/UC-22），Scholar 管理权必须经审核产生（FR05；BR-IMP-05）。
- **本 UC 不创建、不修改、不转移该对象**：无创建/写入消息；授权由 UC-06 批准后产生（`DGM-SEQ-02`、`DGM-ACTIVITY-01`）。

## 4. 被拒绝的候选类

| 候选 | 拒绝理由 | 依据/后续 |
|---|---|---|
| `UserProfile`（第二个主页/主页合并目标） | 本 UC 没有任何发往 UserProfile 的消息；认领不创建第二个主页、不把主页与 Scholar 合并 | AC-IMPL-FR05-04；SRS §12；见 05 §4 |
| `Authorship` | 认领不确认、不创建、不修改成果参与关系；审核批准不等于本人确认 | FR05；AC-IMPL-FR15-06；SPEC ID-07 |
| `WorkEntity` / `WorkVersion` / `SourceRecord` | 认领不读写成果与来源事实；成果治理属 UC-09/UC-10 | FR09/FR15；02 §5 |
| `AuditEvidence`（全局 `Evidence`） | 本 UC 的受保护写入记录由 `ClaimApplication` 自身（申请人/目标/状态/理由/时间）承载；审核决定留痕属 UC-06、后台审计查询属 UC-17 | BR-IMP-10；D-13；B-05 |
| `ClaimReviewCoordinator`（审核侧控制类） | 审核决定、授权生效、失败回滚、争议判定属 UC-06；本 UC 不复制审核职责 | FR05；`DGM-ACTIVITY-01` |
| `ConflictQueue` / `ControlledTodo` / `Dispute` | “受控待办/争议”的产生与处理属 UC-06/UC-17；本 UC 只在申请上记录“已有他人有效授权”的竞争事实 | AC-IMPL-FR05-03；B-06 |
| `Notification` / `Notice` / `SLA` | SRS 未定义通知渠道、内容与 SLA；本 UC 只保证“可查询” | 02 §5；B-07/B-08 |
| `EvidenceFile` / `UploadStore` / `Attachment` | 材料存储形态属实现设计；分析层只需 `ReviewEvidence` 与其可见范围 | 教材 §5.4.2；技术边界 |
| `ClaimRequest` / `ClaimDraft` / `SubmissionContext` | 提交请求是控制器的单次分析状态，没有独立业务生命周期与对外行为 | 教材 §5.4.2；06 §3 |
| `ClaimView` / `StatusDTO` | 状态视图是申请与证据的返回投影，不是独立业务实体 | 教材 §5.4.2；分析推导 |
| `Session` / `CredentialContext` | 已认证请求上下文是 M2/M26 的输入；基线未定义会话对象 | UC-04 Q-07；UC-22 B-06 |
| `Reviewer` / `Admin`（作为系统类） | 审核管理员是外部参与者且属 UC-06；不画成系统类 | 教材 §5.4.2；02 §5 |
| `ScholarMerge` / `IdentityMerge` | 认领不是身份合并；Scholar 身份判定与同名/别名处置属 FR15/UC-10 | FR15；BR-IMP-03；SRS §12 |

## 5. User / UserProfile / Scholar / Claim / Auth 边界结论

| 边界 | 结论 | 本 UC 证据 |
|---|---|---|
| User ↔ UserProfile | 1:1 且注册即存在（UC-04）；本 UC 不读、不写、不新建主页 | AC-IMPL-FR05-04；SRS §12 |
| UserProfile ↔ Scholar | 分离；认领不合并、不迁移、不把主页当学者档案 | AC-IMPL-FR05-04；SRS §12；BR-IMP-03 |
| User ↔ Scholar | 无直接关系；只有经审核的 `ClaimApplication`/`ScholarAuthorization` 记录才能关联 | FR05；SRS §12“当且仅当FR05通过审核”；BR-IMP-05 |
| ClaimApplication ↔ ScholarAuthorization | 申请是过程记录，授权是结果；本 UC 只创建前者、只读取后者 | FR05；SPEC §3；AC-IMPL-FR05-03 |
| ClaimApplication ↔ Authorship | 无关系；认领不确认成果归属，审核批准≠本人确认 | FR05；SPEC ID-07；AC-IMPL-FR15-06 |
| ReviewEvidence ↔ 公开数据 | 证据私有，不进入公开档案/检索/详情 | BR-IMP-11；AC-IMPL-NF06-01 |
| 同名 UserProfile 与 Scholar | 两个对象，各自独立展示与状态；不是认领依据 | AC-IMPL-FR03-04；BR-IMP-03 |

## 6. 识别结论

本 UC 的参与分析类为 7 个：

- Boundary：`ClaimUI`
- Control：`ClaimCoordinator`
- Entity：`User`、`Scholar`、`ClaimApplication`、`ReviewEvidence`、`ScholarAuthorization`（只读）

`UserProfile`、`Authorship`、`WorkEntity`、`AuditEvidence`、`ConflictQueue`、`Notification`、`ClaimDraft`、`Session`、`Reviewer` 均不进入本 UC VOPC。该集合将在 04 的顺序图中接受消息检验；无消息者删除或降为属性/职责。
