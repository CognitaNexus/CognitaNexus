# UC-07 VOPC（参与类类图）与关系依据

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-VOPC-08-scholar-maintenance.puml`](diagrams/DGM-VOPC-08-scholar-maintenance.puml)
**导出：** [SVG](diagrams/generated/DGM-VOPC-08-scholar-maintenance.svg) · [PNG](diagrams/generated/DGM-VOPC-08-scholar-maintenance.png)
**方法：** 教材 §5.4.4：VOPC 只来自本用例实现的顺序图对象，关系来自消息和业务约束；不是全局 BCE。

## 1. 参与类集合

VOPC 只包含以下 7 个类：

| 类 | 类型 | 来自顺序图对象 | 事件流依据 |
|---|---|---|---|
| `ScholarMaintenanceUI` | Boundary | M1、M2、M7、M8、M10、M11、M14、M15、M17、M18、M22、M23、M25、M26、M28–M31、M34–M37、M40–M43、M47、M48、M51、M52 | E1、E7、E10、A1–A6、A8 |
| `ScholarMaintenanceCoordinator` | Control | M2–M7、M9、M10、M12–M14、M16–M17、M19–M22、M24、M25、M27、M28、M31–M34、M37–M40、M42、M44–M47、M49–M51 | E2–E10、A1–A9 |
| `User` | Entity | M3、M38（接收方） | E2、E8、A1、A8 |
| `Scholar` | Entity | M5（接收）；M24、M45（接收） | E3、E5、E6、A5、A10 |
| `ScholarAuthorization` | Entity | M4（接收，只读） | E3、A2、A3 |
| `ScholarChangeRequest` | Entity | M20、M32、M44、M49（接收）；M33（应答） | E6–E9、A7、A10 |
| `AuditEvidence` | Entity | M9、M12、M16、M21、M27、M46、M50（接收） | E7、E10、A1–A3、A9 |

两位参与者（已获授权 Scholar 管理者、有权审核的管理员）不进入 VOPC：参与者不是系统内部类（教材 §5.4.4）。

## 2. 关系与来源

| 关系 | 类型 | 来自消息/约束 | 方向/角色 | 多重性 | 说明 |
|---|---|---|---|---|---|
| `ScholarMaintenanceUI --> ScholarMaintenanceCoordinator` | 单向关联/消息关系 | M2 请求；M7/M10/M14/M17/M22/M25/M28/M31/M34/M37/M40/M42/M47/M51 返回 | UI 请求维护；Coordinator 返回结果 | 不定义持久多重性 | UI 无持久业务属性；按消息方向表达一次维护会话的协作 |
| `ScholarMaintenanceCoordinator --> User` | 单向关联/使用关系 | M3、M38 | Coordinator 索取账号标识、账号状态与审核权限事实 | 不定义持久多重性 | 应答在同一关联上隐式返回；协调器不是账号容器 |
| `ScholarMaintenanceCoordinator ..> ScholarAuthorization` | 单向**只读**依赖 | M4 | 读取有效对象级授权与范围，不改动 | 不定义持久多重性 | 虚线表示只读：本 UC 不创建/转移/撤销授权（BR-IMP-05；SPEC ID-12） |
| `ScholarMaintenanceCoordinator --> Scholar` | 单向关联/使用-写入关系 | M5（读当前公开事实与生效版本）；M24、M45（写入并生成生效版本） | 读 → 判定；写 → 生效版本 | 不定义持久多重性 | 同一关联承载读取与写入；写入仅限该变更范围字段（BR-IMP-04；§7.2） |
| `ScholarMaintenanceCoordinator --> ScholarChangeRequest` | 单向关联/创建-记录关系 | M20 登记；M32 读取；M44/M49 记录决定；M33 应答 | 创建待审变更、读取状态、记录决定 | 不定义持久多重性 | 待审变更由本 UC 创建并推进状态；状态集合 `待审核/通过/驳回` |
| `ScholarMaintenanceCoordinator --> AuditEvidence` | 单向关联/创建关系 | M9、M12、M16、M21、M27、M46、M50 | 记录受保护写入、越权尝试与决定留痕 | 不定义持久多重性 | 审计记录指向被写对象是其业务属性（见 06），不另画关联 |
| `ScholarAuthorization "0..*" --> "1" User` | 业务关联 | FR05；SRS §12“当且仅当FR05通过审核，建立明确User—ScholarAuth/Claim授权记录”；`AC-IMPL-FR05-02`；UC-06 §2 | 被授权主体 | `0..*` : `1` | 一个 User 可被授予多条（不同 Scholar 的）授权；一条授权只有一个被授权主体 |
| `ScholarAuthorization --> Scholar` | 业务关联 | FR05；`AC-IMPL-FR05-02`“新授权仅对目标门户有效”；BR-IMP-05；M4 | 目标档案 | **未定** | 见 §2.2：同一 Scholar 是否允许并列多个有效管理者由 SPEC ID-12 待评审（沿用 UC-06 C-04） |
| `ScholarChangeRequest "0..*" --> "1" Scholar` | 业务关联 | FR06“待审版本不覆盖公开版”；`AC-IMPL-FR06-01`；§6.4；M20 | 待审变更的目标档案 | `0..*` : `1` | 一条待审变更只指向一个档案；一个档案是否可并存多条待审变更待确认（B-09），故左端按可能值 `0..*`，不据此断言并发规则 |

### 2.1 为什么控制/边界到实体的关联不标持久多重性

`ScholarMaintenanceUI`/`ScholarMaintenanceCoordinator` 到实体的关联按**消息方向**表达协作，不是长期业务对象链接。为它们标 `1..*` 会把一次用例的协调关系误解为持久聚合关系。因此本 VOPC 只对三类业务事实给出多重性：授权→被授权主体（`0..*:1`）、待审变更→目标档案（`0..*:1`），以及沿用未定的授权→目标档案。

### 2.2 为什么 `ScholarAuthorization --> Scholar` 不标多重性

- `AC-IMPL-FR05-02` 只规定“新授权仅对目标门户有效”，未规定同一 Scholar 能否同时存在多名有效管理者；
- SPEC ID-12 明确“原管理人权限不得因冲突认领请求自动转移；授权转移条件、申诉程序待评审”；
- UC-06（`VOPC-07`）已把该多重性登记为未定（C-04）。

本 UC 的判定只依赖“该请求者对该目标是否存在有效授权”，与“是否只能有一个管理者”无关，因此不擅自补 `1` 或 `0..1`。

### 2.3 为什么审计只画一条关联

`AuditEvidence` 需要指向被写/被尝试写入的对象（`Scholar`、`ScholarChangeRequest`），但该指向由审计记录自身携带对象引用与前后值（BR-IMP-10）；本 UC 顺序图中也没有 `AuditEvidence → Scholar`/`→ Change` 的消息。按“关系须可追溯到消息或正式业务约束”，VOPC 只保留 `Coordinator --> AuditEvidence` 的创建关系，把对象指向写成属性（与 UC-22/UC-06 一致）。

## 3. 从顺序图到 VOPC 的对应

| 顺序图对象 | VOPC 类 | 保留/删除理由 |
|---|---|---|
| 已获授权 Scholar 管理者（Actor） | 不保留 | 参与者不是系统类 |
| 有权审核的管理员（Actor） | 不保留 | 参与者不是系统类 |
| ScholarMaintenanceUI | 保留 | 接收 M1，返回 M7/M8/M10/M11/M14/M15/M17/M18/M22/M23/M25/M26/M28/M29/M30/M34/M35/M36/M37/M40–M43/M47/M48/M51/M52 |
| ScholarMaintenanceCoordinator | 保留 | 协调 M3–M6、M13、M19–M21、M24、M27、M31–M32、M38–M39、M44–M46、M49–M50 |
| User | 保留 | 接收 M3、M38 |
| Scholar | 保留 | 接收 M5、M24、M45 |
| ScholarAuthorization | 保留 | 接收 M4（只读） |
| ScholarChangeRequest | 保留 | 接收 M20、M32、M44、M49，应答 M33 |
| AuditEvidence | 保留 | 接收 M9/M12/M16/M21/M27/M46/M50 |
| 无其他对象 | — | 没有 UserProfile/ClaimApplication/Authorship/WorkEntity/SourceRecord/Session 的消息 |

## 4. 排除项与理由

- `UserProfile` / 第二个 `UserProfile`：本 UC 无写消息；普通字段维护属 UC-22（FR06；SRS §12；A4 只做引导）。
- `ClaimApplication` / `ClaimCoordinator`：认领与认领审核属 UC-05/06；本 UC 不创建、不读取（FR05；`AC-IMPL-FR05-04`）。
- `Authorship` / `WorkEntity` / `WorkVersion`：本 UC 不确认、不解除、不新建参与关系；归属纠正属 UC-08/FR15（`AC-IMPL-FR15-06`；BR-UC07-07）。
- `SourceRecord`：原始来源事实由来源侧承载，本 UC 只保留引用并禁止覆盖（§7.2；BR-IMP-04）。
- `ReviewEvidence`：待审变更只引用既有证据（`evidenceRefs` 属性）；材料提交与可见范围细则属 UC-05/06 与 D-06。
- `PublicProfileUI` / `PublicProfileCoordinator`：公开读取与展示属 UC-03；本 UC 只保证写侧“待审不入公开”。
- `Session` / `CredentialContext`：已认证请求上下文是 M2/M31/M37 的输入；基线未定义会话对象（B-08）。
- `ReviewerRole` / `PermissionMatrix` / `ApprovalChain`：基线只区分“是否具备对应审核权限”（FR14；NF03；UC-06 C-01）。
- `ConflictQueue` / `PendingTodo` / `DisputeCase` / `Notification`：待办与通知载体属 FR14/UC-17，SLA 未定义（D-06）。
- `ScholarMerge` / `IdentityAlias`：身份归并与别名治理属 FR15 消歧流程（UC-09/10）。
- `FieldPolicy` / 独立 `EffectiveVersion`：字段分类知识落在协调器上下文与 `Scholar` 可维护字段集合上；生效版本以 `Scholar` 的当前公开字段与版本标识表达，无消息寻址独立版本对象（§6.4；NF05；B-03）。
- `AuditCoordinator`（全局 `AuditCtl`）：审计查询与集中治理属 UC-17；本 UC 只需一次写入留痕（FR14；BR-IMP-10）。

## 5. VOPC 完整性检查

| 检查 | 结果 |
|---|---|
| 只包含 UC-07 参与类 | 是，7 个 |
| 每个类有事件流/消息依据 | 是，见表 1/表 3 |
| 每条关系可追溯到消息或业务约束 | 是，见表 2 |
| 不把 Actor 画成系统类 | 是；两位参与者不进入 VOPC |
| 不用全局 BCE 冒充 VOPC | 是；本图独立于 `DGM-CLASS-01-analysis.puml` |
| 关系多重性是否有依据 | `Auth→User 0..*:1` 与 `Change→Scholar 0..*:1` 有依据；`Auth→Scholar` 沿用未定；控制/边界到实体不定义持久多重性（见 §2.1–§2.2） |
| 是否引入设计技术 | 否 |
| 是否出现第二主页/认领/Authorship | 否 |
| 是否把待审变更与认领申请混为一类 | 否；两者是不同对象、不同状态集合 |

## 6. 与全局图的关系

本 VOPC 是 UC-07 的局部静态视图，不是全系统类图。与 `DGM-CLASS-01-analysis.puml` 的异同、命名合并建议见[07-与全局模型差异](07-global-model-diff.md)。R5 统一分析类时应以本 VOPC 为输入之一，而不是用全局图反推本 UC 的参与类。
