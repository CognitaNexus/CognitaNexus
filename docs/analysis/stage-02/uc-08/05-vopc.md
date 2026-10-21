# UC-08 VOPC（参与类类图）与关系依据

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-VOPC-09-authorship-correction.puml`](diagrams/DGM-VOPC-09-authorship-correction.puml)
**导出：** [SVG](diagrams/generated/DGM-VOPC-09-authorship-correction.svg) · [PNG](diagrams/generated/DGM-VOPC-09-authorship-correction.png)
**方法：** 教材 §5.4.4：VOPC 只来自本用例实现的顺序图对象，关系来自消息和业务约束；不是全局 BCE。

## 1. 参与类集合

VOPC 只包含以下 11 个类：

| 类 | 类型 | 来自顺序图对象 | 事件流依据 |
|---|---|---|---|
| `AchievementCorrectionUI` | Boundary | M1、M2、M8–M9、M11–M12、M16–M17、M21–M22、M25–M26、M28–M29、M31–M32、M37–M38、M43–M46、M50–M51、M53、M58–M61、M65–M66、M71–M72、M75–M76 | E1、E8、E12、E15、A1–A7、A9、A10 |
| `AchievementCorrectionCoordinator` | Control | M2–M7、M10、M13–M15、M18–M21、M23–M25、M27、M30、M33–M37、M39–M43、M46–M50、M53–M58、M60、M62–M65、M67–M71、M73–M75 | E2–E15、A1–A12 |
| `User` | Entity | M3、M54（接收） | E2、E13、A1、A10 |
| `ScholarAuthorization` | Entity | M4（接收，只读） | E3、A2 |
| `Scholar` | Entity | M5（接收） | E4、A2、A11 |
| `Authorship` | Entity | M6、M19、M23、M67（接收） | E4、E6、E7、E14、A3、A4、A7、A13 |
| `WorkEntity` | Entity | M34、M62（接收） | E9、E11、E14、A4、A12、A14 |
| `SourceRecord` | Entity | M35（接收，只读） | E10、E14 |
| `CorrectionRequest` | Entity | M40、M47、M55、M63、M69、M73（接收）；M48（应答） | E11–E15、A5、A9、A11 |
| `ReviewEvidence` | Entity | M41、M49、M56（接收） | E12、E13、A5、A12 |
| `AuditEvidence` | Entity | M10、M13、M18、M20、M24、M33、M39、M42、M64、M70、M74（接收） | E8、E12、E15、A1–A3、A6、A7、A9 |

两位参与者（已获授权学者、有权审核的管理员）不进入 VOPC：参与者不是系统内部类（教材 §5.4.4）。

## 2. 关系与来源

| 关系 | 类型 | 来自消息/约束 | 方向/角色 | 多重性 | 说明 |
|---|---|---|---|---|---|
| `AchievementCorrectionUI --> AchievementCorrectionCoordinator` | 单向关联/消息关系 | M2 请求；M8/M11/M16/M21/M25/M28/M31/M37/M43/M46/M50/M53/M58/M60/M65/M71/M75 返回 | UI 请求；Coordinator 返回结果 | 不定义持久多重性 | UI 无持久业务属性；按消息方向表达一次用例会话的协作 |
| `Coordinator --> User` | 单向关联/使用关系 | M3、M54 | 索取账号标识、账号状态与审核权限事实 | 不定义持久多重性 | 协调器不是账号容器 |
| `Coordinator ..> ScholarAuthorization` | 单向**只读**依赖 | M4 | 读取有效对象级授权与范围，不改动 | 不定义持久多重性 | 虚线表示只读：不创建/转移/撤销授权（BR-IMP-05；SPEC ID-12） |
| `Coordinator --> Scholar` | 单向关联/只读使用关系 | M5 | 确认关系归属学者＝授权所指学者，读取本人相关关系集合 | 不定义持久多重性 | 本 UC 不写档案字段（UC-07）、不合并/拆分身份（UC-09/UC-10） |
| `Coordinator --> Authorship` | 单向关联/读-写关系 | M6（读）；M19（置争议）、M23（本人确认）、M67（置已排除） | 读取关系状态/冲突标记；按事件改变状态并追加证据 | 不定义持久多重性 | 写入只作用于**一条**关系；不新建、不删除关系 |
| `Coordinator --> WorkEntity` | 单向关联/读-写关系 | M34（读公开/共享字段）；M62（写生效规范值） | 读取现值供范围判定；审核通过后写入批准字段 | 不定义持久多重性 | 不新建、不删除成果；原始来源事实不在此写 |
| `Coordinator ..> SourceRecord` | 单向**只读**依赖 | M35 | 读取来源原值/到达时间/许可，用于核验与保留 | 不定义持久多重性 | 虚线表示只读：不写、不删、不改原值（INV-01） |
| `Coordinator --> CorrectionRequest` | 单向关联/创建-推进关系 | M40 登记；M47/M55 读取；M63/M69/M73 记录决定；M48 应答 | 创建待审请求、读取状态、记录决定 | 不定义持久多重性 | 状态集合 `待审核/通过/驳回`（B-01） |
| `Coordinator --> ReviewEvidence` | 单向关联/创建-读取关系 | M41 记录材料与可见范围；M49/M56 读取 | 保存材料、限定可见者、供核对 | 不定义持久多重性 | 材料不进入公开视图 |
| `Coordinator --> AuditEvidence` | 单向关联/创建关系 | M10/M13/M18/M20/M24/M33/M39/M42/M64/M70/M74 | 记录受保护写入、拒绝、越权、争议、待审、决定与生效留痕 | 不定义持久多重性 | 审计指向的对象是属性，不另画实体关联（§2.3） |
| `ScholarAuthorization "0..*" --> "1" User` | 业务关联 | FR05；SRS §12；`AC-IMPL-FR05-02`；UC-06 §2 | 被授权主体 | `0..*` : `1` | 一个 User 可被授予多条（不同 Scholar 的）授权；一条授权只有一个被授权主体 |
| `ScholarAuthorization --> Scholar` | 业务关联 | FR05；`AC-IMPL-FR05-02`；BR-IMP-05；M4 | 目标学者 | **未定** | 同一 Scholar 是否允许并列多个有效管理者由 SPEC ID-12 待评审（沿用 UC-06/07） |
| `Authorship "0..*" --> "1" WorkEntity` | 业务关联 | BR-353；ID-05/ID-11；M6、M34 | 归属成果 | `0..*` : `1` | 一项成果可有多位参与者关系；一条关系只属于一个成果（沿用 UC-02） |
| `Authorship "0..*" --> "1" Scholar` | 业务关联 | BR-353“不同参与者不得合并为一条关系”；FR15-S07（每位参与者建/定位一个学者档案，未定位则待核验） | 归属学者 | `0..*` : `1` | 一位学者可有多条关系；一条关系只归于一位学者。与登记表“未定”的差异见 §2.2 |
| `WorkEntity "1" --> "0..*" SourceRecord` | 业务关联 | INV-02；§6.1/§7.2；UC-02；M35 | 出处 | `1` : `0..*` | 一项成果可有多条来源、可暂无（治理异常态）；来源不因关系解除而删除（FR15-S11） |
| `CorrectionRequest "0..*" --> "1" WorkEntity` | 业务关联 | FR07；`AC-IMPL-FR07-01/02`；M40/M55 | 目标成果 | `0..*` : `1` | 一条待审请求指向一个成果；同一成果是否允许多条待审并存待确认（B-01） |
| `CorrectionRequest "0..*" --> "0..1" Authorship` | 业务关联 | FR07“只修改自己的参与关系”；M40/M55/M67 | 涉及的本人员关系 | `0..*` : `0..1` | 解除/归属类请求指向一条本人关系；共享字段类请求可以没有具体关系（`0..1`） |

### 2.1 为什么控制/边界到实体的关联不标持久多重性

`AchievementCorrectionUI`/`AchievementCorrectionCoordinator` 到实体的关联按**消息方向**表达协作，不是长期业务对象链接。为它们标 `1..*` 会把一次用例的协调关系误解为持久聚合关系。因此本 VOPC 只对业务事实之间的关联给出多重性：`Auth→User`、`Auth→Scholar`（未定）、`Authorship→WorkEntity`、`Authorship→Scholar`、`WorkEntity→SourceRecord`、`CorrectionRequest→WorkEntity`、`CorrectionRequest→Authorship`。

### 2.2 为什么 `Authorship --> Scholar` 标 `0..*:1`（与登记表“未定”的差异）

- BR-353 明确“同一成果上不同参与者不得合并为一条关系”，ID-05 要求“多作者必须为每个作者分别形成 Authorship 候选”，因此一条关系只对应一位学者；
- FR15-S07 的异常流 E1 规定：参与者姓名无法定位时“建立学者档案并标注关系为待核验”，即关系仍指向一个学者档案（原署名由 `participantDisplay` 保留），不存在“一条关系同时属于多位学者”；
- 登记表（`analysis-class-registry.md`）的 `Authorship` 行把 `Scholar` 一侧写成“后续关联”（首次发现于 UC-02 时尚未使用），并把该行标为“R5 确认”，指的是**跨用例尚未统一关系表达方式**，不是“允许一条关系多主”。本 UC 依 BR-353 定为 `0..*:1`，并把该差异登记为 R5 待确认项（07 §4.2），不据此修改登记表。

### 2.3 为什么 `ReviewEvidence` 与 `CorrectionRequest` 之间不画实体关联、审计只画一条关联

- `CorrectionRequest` 通过 `evidenceRefs` 属性引用材料（与 UC-07 `ScholarChangeRequest.evidenceRefs` 一致），顺序图中也没有“请求→材料”的消息，材料由协调器在 M41/M49/M56 直接寻址；
- `AuditEvidence` 需要指向被写/被尝试写入的对象（关系、成果、请求），但该指向由审计记录自身携带 `targetRefs` 与前后值（BR-IMP-10）；本 UC 顺序图中没有 `AuditEvidence → Authorship/WorkEntity` 的消息。按“关系须可追溯到消息或正式业务约束”，VOPC 只保留 `Coordinator --> AuditEvidence` 的创建关系，把对象指向写成属性（与 UC-06/UC-07/UC-22 一致）。

## 3. 从顺序图到 VOPC 的对应

| 顺序图对象 | VOPC 类 | 保留/删除理由 |
|---|---|---|
| 已获授权学者（Actor） | 不保留 | 参与者不是系统类 |
| 有权审核的管理员（Actor） | 不保留 | 参与者不是系统类 |
| AchievementCorrectionUI | 保留 | 接收 M1/M45/M52，返回 M8–M9、M11–M12、M16–M17、M21–M22、M25–M26、M28–M29、M31–M32、M37–M38、M43–M44、M50–M51、M58–M61、M65–M66、M71–M72、M75–M76 |
| AchievementCorrectionCoordinator | 保留 | 协调 M3–M7、M14–M15、M27、M30、M36、M57、M68 及全部写入 |
| User | 保留 | 接收 M3、M54 |
| ScholarAuthorization | 保留 | 接收 M4（只读） |
| Scholar | 保留 | 接收 M5 |
| Authorship | 保留 | 接收 M6、M19、M23、M67 |
| WorkEntity | 保留 | 接收 M34、M62 |
| SourceRecord | 保留 | 接收 M35（只读） |
| CorrectionRequest | 保留 | 接收 M40/M47/M55/M63/M69/M73，应答 M48 |
| ReviewEvidence | 保留 | 接收 M41/M49/M56 |
| AuditEvidence | 保留 | 接收 M10/M13/M18/M20/M24/M33/M39/M42/M64/M70/M74 |
| 无其他对象 | — | 没有 UserProfile/ClaimApplication/WorkVersion/CollectionTask/IdentityCandidate/CollaborationEdge/Session 的消息 |

## 4. 排除项与理由

- `UserProfile` / 第二个 `UserProfile`：本 UC 无写消息；零成果空态由 UC-03 展示（FR07-03；SRS §12；UC-22）。
- `ClaimApplication` / `ClaimCoordinator` / `ClaimReviewCoordinator`：认领与认领审核属 UC-05/UC-06（FR05；`AC-IMPL-FR05-04`）。
- `ScholarChangeRequest`：UC-07 的“档案字段待审变更”，与本 UC 的成果关系/字段请求是不同对象、不同字段与状态集合（FR06 vs FR07）。
- `WorkVersion` / `DedupCandidate` / `MergeDecision`：版本与去重属 FR09 治理（UC-09）。
- `IdentityCandidate` / `ScholarMerge` / `AttributionAssignment`：身份消歧与归属改派/拆分属 FR15-S02～S04、S10（UC-09/UC-10；A11）。
- `CollaborationEdge` / `GraphCtl`：建边、强度与论文子图属 FR12/IN01（UC-14/UC-15）。
- `DisputeCase` / `PendingTodo` / `ConflictQueue` / `Notification`：争议结案与待办属 FR14/UC-17；SLA 未定义（D-06）。
- `Session` / `CredentialContext`：已认证请求上下文是 M2/M46/M53 的输入；基线未定义会话对象（B-09）。
- `ReviewerRole` / `PermissionMatrix` / `ApprovalChain`：基线只区分“是否具备对应审核权限”（FR14；NF03）。
- `FieldPolicy` / `SharedFieldCatalogue`：共享字段清单未冻结（B-04）；字段分类知识落在协调器上下文与 `WorkEntity` 的字段集合上。
- `PublicationSubmission` / `Manuscript`：基线不提供投稿/出版（FR07；A14）。
- `EvidenceFile` / `Attachment`：材料存储形态属实现设计。
- 独立 `EffectiveVersion`：生效值以 `WorkEntity` 当前公开字段与版本标识表达（§6.4；NF05；B-07）。
- `AuditCoordinator`（全局 `AuditCtl`）：审计查询与集中治理属 UC-17；本 UC 只需写入留痕（FR14）。

## 5. VOPC 完整性检查

| 检查 | 结果 |
|---|---|
| 只包含 UC-08 参与类 | 是，11 个 |
| 每个类有事件流/消息依据 | 是，见表 1/表 3 |
| 每条关系可追溯到消息或业务约束 | 是，见表 2 |
| 不把 Actor 画成系统类 | 是；两位参与者不进入 VOPC |
| 不用全局 BCE 冒充 VOPC | 是；本图独立于 `DGM-CLASS-01-analysis.puml` |
| 关系多重性是否有依据 | 六条业务关联有依据（见 §2.2）；`Auth→Scholar` 沿用未定；控制/边界到实体不定义持久多重性（§2.1） |
| 是否把 `WorkEntity` 与 `Authorship` 混为一类 | 否：成果共享实体与单条参与关系分离 |
| 是否把 `SourceRecord`、`ReviewEvidence`、`AuditEvidence` 混为一类 | 否：来源原值/审核材料/动作留痕三分离（§2.3） |
| 是否把待审请求与认领申请、档案待审变更混为一类 | 否：三套对象、三套状态 |
| 是否引入设计技术 | 否 |
| 是否出现新建成果、删除成果/来源、合并身份 | 否 |

## 6. 与全局图的关系

本 VOPC 是 UC-08 的局部静态视图，不是全系统类图。与 `DGM-CLASS-01-analysis.puml` 的异同、命名合并建议见[07-与全局模型差异](07-global-model-diff.md)。R5 统一分析类时应以本 VOPC 为输入之一，而不是用全局图反推本 UC 的参与类。
