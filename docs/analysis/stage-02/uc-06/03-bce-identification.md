# UC-06 Boundary / Control / Entity 识别

**版本：** V1.0（2026-10-21）
**方法：** 教材 §5.4.2：从事件流识别边界类、控制类、实体类；不从全局 BCE 反向填充。
**输入：** 本目录 01 规约、02 事件流。

## 1. 识别原则

1. **Boundary**：与外部参与者交互、接收输入或显示结果的对象。
2. **Control**：协调一个用例内的校验、决定顺序、失败处理与结果返回；不是实现层 Controller/Service。
3. **Entity**：承载业务事实、状态和持久信息；不是数据库表。
4. 只有在本 UC 事件流中实际承担消息的对象才进入 VOPC。
5. 参与者“有权审核的管理员”不是系统分析类；“登录申请人”只以只读结果为主体，其查询路径属 UC-05，不进入本 UC 系统类。
6. 仅因“以后可能用到”或“全局图里有”而出现的类不进入本 UC。

## 2. 候选分析类

| ID | 候选类 | 类型 | 从哪些事件发现 | 业务行为 | 为什么不能由其他候选直接承担 |
|---|---|---|---|---|---|
| C1 | `ClaimReviewUI` | Boundary | E1、E4、E11、E12、A1–A9 | 接收“打开申请/查看证据/选择决定并填理由/查询决定”的输入；显示申请与证据、待补充与缺失项、争议与冲突事实、授权结果/失败、无权限、状态已变化、理由必填、决定与审计摘要 | `ClaimApplication`/`ScholarAuthorization`/`AuditEvidence` 是业务实体，不应承担审核输入与展示；`ClaimReviewCoordinator` 是内部协调者，不直接面对审核者 |
| C2 | `ClaimReviewCoordinator` | Control | E2–E11、A1–A9 | 服务端校验审核者身份/账号状态/审核权限；读取申请与私有证据；只读判定冲突；复核守卫（状态版本、理由完整、无冲突）；创建批准授权；记录状态与理由；写入审计；返回结果；支持决定查询 | Boundary 只负责交互，无法协调“权限＋状态版本＋理由＋冲突＋授权写入＋状态与审计一致”的跨对象顺序；各 Entity 只知道自己，无法独立完成决定级守卫 |
| C3 | `User` | Entity | E2（审核者）、E8（被授权主体）、E12（查询者） | 提供账号标识、账号状态与审核权限判定所需的账号主体事实；作为批准后的被授权主体（申请人） | `UserProfile` 是公开主页，不承载账号状态与权限；`Scholar` 是认领/授权目标，不承载审核者身份；审核权限不能由 UI 入口可见性代替 |
| C4 | `ClaimApplication` | Entity | E3、E7、E8–E11、A2、A3、A5–A8 | 承载申请事实与状态（待审核/待补充/通过/驳回/争议）、状态理由/缺失项、申请人/目标/证据引用、版本与决定时间；向本人提供只读决定投影 | 申请状态不属于 `User`/`Scholar` 的属性；`ReviewEvidence` 只承载材料；`ScholarAuthorization` 是批准结果，不是申请状态；`AuditEvidence` 只承载留痕 |
| C5 | `ReviewEvidence` | Entity | E4、A4 | 承载申请人提交的私有证明材料与可见范围（本人＋有权审核者）；供有权审核者核对，供补证复核读取最新提交版本 | 材料原文与可见性约束不同于申请状态（BR-IMP-11；AC-IMPL-NF06-01）；也不能并入 `Scholar`/`UserProfile`（材料不属于公开档案资料） |
| C6 | `Scholar` | Entity | E5、E8、A5 | 提供目标档案身份与已收录状态，作为授权对象被确认；**不被本 UC 创建或改写** | `UserProfile` 是普通账号主页；`ScholarAuthorization` 指向档案但不等于档案；档案字段维护属 UC-07 |
| C7 | `ScholarAuthorization` | Entity | E5（只读）、E8（创建）、A5、A6 | 承载目标 Scholar 的对象级管理授权事实：被授权主体、范围（仅目标门户）、有效性；本 UC 只在无冲突批准时创建，竞争/冲突时保留原授权 | 授权是结果，不是申请状态；不等于 `UserProfile` 所有权（注册即有）；不等于本人确认，也不使 Authorship 自动确认（SPEC ID-07） |
| C8 | `AuditEvidence` | Entity | E10、E12、A9 | 承载审核决定的留痕：决定类型、操作者、对象（申请/目标 Scholar/授权）、时间、前后值、理由；供决定查询读取 | 决定留痕的生命周期与读取权限不同于申请状态与材料；`ClaimApplication` 不能同时承载“可对本人展示的状态”与“操作者/前后值/审计读取”职责 |

## 3. 逐类选择理由

### C1 ClaimReviewUI（Boundary）

- 发现于 E1 打开申请、E4 查看证据、E11 返回结果、E12 决定查询，以及 A1–A9 的全部结果展示。
- 不持久保存申请、证据或授权；输入（决定与理由）是短期请求数据。
- **命名说明：** 全局 `DGM-CLASS-01` 与 `01-BCE分析类模型.md` 只有 `ClaimUI`（“提交/查询/审核认领”）。UC-05 已用 `ClaimUI` 表达提交/查询侧并登记 B-09；本 UC 的审核工作区有不同的输入（决定与理由）与结果（争议/授权/审计），故用独立 Boundary `ClaimReviewUI`，并把 `ClaimUI` 是否合并留到 R5（C-09、07 §2）。

### C2 ClaimReviewCoordinator（Control）

- 发现于 E2 权限校验、E3 读取申请、E4 证据核对、E5 冲突判定、E7 复核守卫、E8 授权创建、E9 状态记录、E10 审计、E11 结果返回、E12 查询。
- 需要跨 `User`、`ClaimApplication`、`ReviewEvidence`、`Scholar`、`ScholarAuthorization`、`AuditEvidence` 协调，并保持四条守卫：**只有有权审核者可操作**、**状态/版本有效才可决定**、**无冲突才可批准**、**决定与审计一致**。
- 不能拆给实体：`ClaimApplication` 不知道审核者权限与目标现有授权；`ScholarAuthorization` 不知道自己是从哪条申请、在什么冲突状态下产生的。
- **命名说明：** 全局类图用 `ClaimCtl`，`01-BCE分析类模型.md` 与合并序列 `DGM-SEQ-02` 用 `ClaimReviewCoordinator`；UC-05 用 `ClaimCoordinator` 表达提交/查询侧并登记 B-09/D-11。本 UC 沿用 `ClaimReviewCoordinator`（与既有活动/序列图同义，且不与提交侧混同），是否最终合并或拆分由 R5 决定。

### C3 User（Entity）

- 发现于 E2“审核者是否为可用账号且有审核权限”、E8“被授权主体=申请人”、E12“查询者权限”。
- 必要业务事实：账号标识、账号状态；审核权限判定所需的账号主体事实。
- 不能并入 `UserProfile`：账号状态与权限不属于公开主页资料（FR04；SRS §12）。
- 不能并入 `Scholar`：审核者与认领目标是两个对象；Scholar 可无账号。
- **本 UC 不新增 User 属性**；审核权限的具体划分（角色/对象粒度）未冻结（C-01）。

### C4 ClaimApplication（Entity）

- 发现于 E3 读取状态与版本、E7 守卫、E8–E11 各决定的状态记录、A2 过期判定，以及 A3/A5–A8 的失败与不变状态。
- 必要业务事实：申请引用、申请人引用、目标 Scholar 引用、证据引用、当前状态（`待审核/待补充/通过/驳回/争议`）、状态理由/缺失项、版本/更新时间、决定时间。
- 不能并入 `ScholarAuthorization`：申请被驳回或进入争议后仍存在，而授权只在批准成功时产生（AC-IMPL-FR05-02/03）。
- 不能并入 `AuditEvidence`：申请状态需对申请人只读可查，审计含操作者/前后值且读取受限（FR14；NF03）。
- 状态集来源：FR05；`DGM-STATE-02`；SPEC §3。本 UC 只产生 `待补充/通过/驳回/争议` 与“保持原状态”。

### C5 ReviewEvidence（Entity）

- 发现于 E4 审核者查看材料、A4 补证复核读取最新提交版本。
- 必要业务事实：材料类别/内容引用、提交者、所属申请、可见范围（本人＋有权审核者）；不含凭据与秘密（NF03）。
- 不能并入 `ClaimApplication`：材料有独立的可见范围与最小化约束（BR-IMP-11；AC-IMPL-NF06-01）。
- 不能并入 `UserProfile`/`Scholar`：证据不作为公开档案资料（SRS §12；BR-IMP-01）。
- **命名说明：** UC-05 已用 `ReviewEvidence` 表达认领材料并登记 D-13；本 UC 沿用同一含义（材料），决定留痕另用 `AuditEvidence`，命名统一留待 R5（C-08）。

### C6 Scholar（Entity）

- 发现于 E5 目标档案确认、E8 授权对象、A5 冲突判定。
- 必要业务事实：稳定身份引用、公开/核验状态；本 UC 只读。
- 不能并入 `UserProfile`：认领不合并主页与学者档案（AC-IMPL-FR05-04；SRS §12）。
- 不能并入 `ScholarAuthorization`：档案独立于授权存在；未认领档案仍可公开读（SRS §8）。
- **本 UC 不写 Scholar**：机构/身份/归属事实维护属 UC-07/UC-10。

### C7 ScholarAuthorization（Entity）

- 发现于 E5 读取现有有效授权、E8 创建批准授权、A5 保留原授权、A6 写入失败。
- 必要业务事实：目标 Scholar 引用、被授权主体（→User）、对象级范围（仅目标门户）、当前有效性。
- 不能并入 `ClaimApplication`：竞争申请与原授权是两件事，AC-IMPL-FR05-03 要求原授权保留（BR-IMP-05；SPEC ID-12）。
- 不能并入 `UserProfile` 所有权：主页所有权来自注册（UC-04/UC-22），Scholar 管理权必须经审核产生（FR05；BR-IMP-05）。
- **边界：** 同一 Scholar 是否允许并列多个管理者未冻结（C-04）；本 UC 只在“无未决冲突”时创建。

### C8 AuditEvidence（Entity）

- 发现于 E10 写入审计、E12 读取决定审计、A9 审计失败约束。
- 必要业务事实：决定类型、操作者标识、对象引用（申请/目标 Scholar/授权）、时间、前后值（申请状态、授权有无）、理由。
- 不能并入 `ClaimApplication`：审计的读取权限（审核/管理侧）与被申请人可读的申请状态不同（FR14；NF03；AC-IMPL-FR14-02 边界）。
- 不能并入 `ReviewEvidence`：材料是申请人提交的证明，审计是审核者的决定留痕，二者的产生者、可见范围与保留要求不同（BR-IMP-11 vs FR14/BR-IMP-10）。
- **命名说明：** 与 UC-22 的 `AuditEvidence` 同名同义（受保护写入留痕）；全局图只有 `Evidence`（“操作者/理由/前后值”）。D-13 已登记命名统一需求，本 UC 不改全局图（C-08）。

## 4. 被拒绝的候选类

| 候选 | 拒绝理由 | 依据/后续 |
|---|---|---|
| `UserProfile`（第二个主页 / 主页合并目标 / 审批后建主页） | 本 UC 没有任何发往 UserProfile 的消息；批准只新增 Scholar 授权，不建第二个主页、不迁移/合并主页 | AC-IMPL-FR05-04；BR-IMP-05；SRS §12；见 05 §4 |
| `Authorship` | 批准不确认、不创建、不修改任何成果参与关系；管理员审批事件不等于本人确认 | FR05；FR15；AC-IMPL-FR15-03/06；SPEC ID-07 |
| `WorkEntity` / `WorkVersion` / `SourceRecord` | 认领审核不读写成果与来源事实；归属纠错属 UC-08/UC-10 | FR09/FR15；02 §5 |
| `ClaimCoordinator`（提交/查询侧控制类） | 提交与本人查询属 UC-05；插入本 UC 会把提交侧证据校验与归属查询重新画一遍 | UC-05；C-09 |
| `ConflictQueue` / `ControlledTodo` / `DisputeCase` | AC 只要求“争议进入受控待办”；待办入口属 FR14/UC-17，载体与结案程序未冻结；本 UC 以申请状态＋审计表达“争议已受控” | AC-IMPL-FR05-03；C-05 |
| `ClaimDecision` / `DecisionRecord` | 决定的事实由 `ClaimApplication`（状态与理由）＋`AuditEvidence`（操作者/前后值/理由）承载，独立类会重复同一事实且无独立生命周期证据 | 教材 §5.5.1；06 §2/§8 |
| `Notification` / `Notice` / `SlaTimer` | SRS 全文未定义通知渠道、内容与审核 SLA | 02 §5；C-03/C-11 |
| `ReviewerRole` / `PermissionMatrix` / `ApprovalChain` | 基线只写“管理员按具体权限”“不同管理员仍遵守对应权限”，未定义等级/审批链；不得自造角色层级 | SRS §2.2/§8；FR14；C-01 |
| `ScholarMerge` / `IdentityMerge` | 认领不是身份合并；同名/别名处置属 FR15 治理侧；本 UC 只处理“存在未决身份冲突时不得批准” | FR15；BR-IMP-03；SRS §12 |
| `EvidenceFile` / `Attachment` / `UploadStore` | 材料存储形态属实现设计；分析层只需 `ReviewEvidence` 与可见范围 | 教材 §5.4.2；技术边界 |
| `ReviewTask` / `ReviewQueueItem` | 审核待办的列表与排序属 FR14/UC-17；本 UC 的输入只是一条既有申请 | FR14；02 §5 |
| `ReviewContext` / `DecisionCommand` / `DecisionView` | 单次请求/返回投影是控制类的分析状态，无独立业务生命周期 | 教材 §5.4.2；06 §3 |
| `Session` / `CredentialContext` | 已认证请求上下文是 M2 的输入；基线未定义会话对象 | UC-04 Q-07；UC-22 B-06 |
| `Admin` / `Reviewer`（作为系统类） | 审核管理员是外部参与者，不画成系统类 | 教材 §5.4.2；02 §5 |
| 第二个 `Scholar`（“认领即创建学者档案”） | 目标是**已收录** Scholar；本 UC 无创建 Scholar 的消息 | FR05；SRS §12；A-级风险见 07 §3 |

## 5. User / UserProfile / Scholar / Claim / Auth / Evidence 边界结论

| 边界 | 结论 | 本 UC 证据 |
|---|---|---|
| User ↔ UserProfile | 1:1 且注册即存在（UC-04）；本 UC 不读、不写、不新建主页 | AC-IMPL-FR05-04；SRS §12 |
| UserProfile ↔ Scholar | 分离；认领不合并、不迁移、不把主页当学者档案 | AC-IMPL-FR05-04；BR-IMP-03；SRS §12 |
| User ↔ Scholar | 无直接关系；只有经批准的 `ScholarAuthorization`（及申请记录）才能关联 | FR05；BR-IMP-05；SRS §12“当且仅当FR05通过审核” |
| ClaimApplication ↔ ScholarAuthorization | 申请是过程记录，授权是批准结果；本 UC 创建授权但**不改写原授权**，申请状态与授权存在性分别记录 | AC-IMPL-FR05-02/03；SPEC ID-07 |
| ClaimApplication ↔ ReviewEvidence | 材料归属申请；材料私有，申请状态对本人可读 | BR-IMP-11；AC-IMPL-NF06-01 |
| ClaimApplication/Auth ↔ AuditEvidence | 审计记录决定与授权的前后值，读取权限独立（FR14 管理侧） | FR14；BR-IMP-10；INV-10 |
| Claim/Auth ↔ Authorship | 无关系；批准不等于本人确认，不升级任何候选关系 | FR05；AC-IMPL-FR15-03/06；SPEC ID-07 |
| 竞争申请 ↔ 原授权 | 竞争不剥夺既有管理者；原授权保留、不转移 | AC-IMPL-FR05-03；BR-IMP-05；SPEC ID-12 |

## 6. 识别结论

本 UC 的参与分析类为 8 个：

- Boundary：`ClaimReviewUI`
- Control：`ClaimReviewCoordinator`
- Entity：`User`、`ClaimApplication`、`ReviewEvidence`、`Scholar`、`ScholarAuthorization`、`AuditEvidence`

`UserProfile`、`Authorship`、`WorkEntity`、`ConflictQueue`、`ClaimDecision`、`Notification`、`ReviewerRole`、`ClaimCoordinator`、`Session`、`Admin` 均不进入本 UC VOPC。该集合在 04 的顺序图中接受消息检验；无消息者删除或降为属性/职责。
