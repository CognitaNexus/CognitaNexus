# UC-22 VOPC（参与类类图）与关系依据

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-VOPC-05-profile-edit.puml`](diagrams/DGM-VOPC-05-profile-edit.puml)
**导出：** [SVG](diagrams/generated/DGM-VOPC-05-profile-edit.svg) · [PNG](diagrams/generated/DGM-VOPC-05-profile-edit.png)
**方法：** 教材§5.4.4：VOPC 只来自本用例实现的顺序图对象，关系来自消息和业务约束；不是全局 BCE。

## 1. 参与类集合

VOPC 只包含以下 5 个类：

| 类 | 类型 | 来自顺序图对象 | 事件流依据 |
|---|---|---|---|
| `ProfileUI` | Boundary | M1、M2、M6、M7、M9、M10、M13、M14、M16、M17、M21、M22 | E1、E6、A1–A5 |
| `ProfileCoordinator` | Control | M2–M6、M8、M9、M11–M13、M15、M16、M19–M21 | E2–E6、A1–A5 |
| `User` | Entity | M3 | E2、A1（账号状态） |
| `UserProfile` | Entity | M4、M15、M19（请求方）；M18（应答方） | E2、E4、E5、A2–A7 |
| `AuditEvidence` | Entity | M8、M11、M20 | E6、A1、A2 |

注册用户不进入 VOPC，因为参与者不是系统内部类（教材§5.4.4）。

## 2. 关系与来源

| 关系 | 类型 | 来自消息/约束 | 方向/角色 | 多重性 | 说明 |
|---|---|---|---|---|---|
| `ProfileUI --> ProfileCoordinator` | 单向关联/消息关系 | M2、M6、M9、M13、M16、M21 | UI 请求保存；Coordinator 返回结果 | 不定义持久多重性 | UI 不是实体；按消息方向表达一次编辑会话的协作 |
| `ProfileCoordinator --> User` | 单向关联/使用关系 | M3 | Coordinator 向 User 索取账号标识与账号状态 | 不定义持久多重性 | M3 的应答在同一关联上隐式返回；不把协调器做成账号容器 |
| `ProfileCoordinator --> UserProfile` | 单向关联/使用-写入关系 | M4、M15、M19（请求）；M18（应答） | Coordinator 核验归属、写入普通字段、登记待审版本；UserProfile 返回公开投影 | 不定义持久多重性 | 同一条关联承载请求与应答；多重性只由 `User—UserProfile` 表达 |
| `User "1" — "1" UserProfile` | 业务关联 | FR04；`AC-IMPL-FR04-03`；SRS §12“User 1:1 UserProfile”；BR-IMP-05（普通 UserProfile 由所属 User 直接编辑） | User 唯一拥有 UserProfile | 1—1 | 编辑期不变量：不新建、不复制第二个主页；本 UC 不改变该关系 |
| `ProfileCoordinator --> AuditEvidence` | 单向关联/创建关系 | M8、M11、M20 | Coordinator 记录受控审计 | 不定义持久多重性 | 一次受保护写入或拒绝产生留痕；审计记录指向被写对象是其业务属性（见 06），不另画关联 |

### 2.1 为什么控制/边界到实体的关联不标持久多重性

`ProfileUI`/`ProfileCoordinator` 到实体的关联按**消息方向**表达协作，不是长期业务对象链接。为它们标 `1..*` 会把一次用例的协调关系误解为持久聚合关系；因此本 VOPC 只对 `User—UserProfile` 给出两端均为 `1` 的业务多重性。

### 2.2 为什么审计关系只画一条

`AuditEvidence` 需要指向“被写的 UserProfile/被尝试写入的对象”，但该指向由审计记录自身携带对象引用与前后值（BR-IMP-10），本 UC 顺序图中也没有 `AuditEvidence → UserProfile` 的消息。按“关系须可追溯到消息或正式业务约束”，VOPC 只保留 `ProfileCoordinator --> AuditEvidence` 的创建关系，把对象指向写成属性，避免凭空新增实体间关联。

## 3. 从顺序图到 VOPC 的对应

| 顺序图对象 | VOPC 类 | 保留/删除理由 |
|---|---|---|
| 注册用户（Actor） | 不保留 | 参与者不是系统类 |
| ProfileUI | 保留 | 接收 M1、返回 M6/M7/M9/M10/M13/M14/M16/M17/M21/M22 |
| ProfileCoordinator | 保留 | 协调 M3–M5、M12、M15、M18–M20 |
| User | 保留 | 接收 M3 |
| UserProfile | 保留 | 接收 M4/M15/M19，应答 M18 |
| AuditEvidence | 保留 | 接收 M8/M11/M20 |
| 无其他对象 | — | 没有 Scholar/Auth/Claim/Authorship/WorkEntity/Session 的消息 |

## 4. 排除项与理由

- `Scholar`：本 UC 无写消息；重要学术身份/机构/归属/来源事实只在 A3 被拒绝并转 UC-07/UC-08（FR06；`AC-IMPL-FR06-01/02`）。
- `ScholarAuthorization`/`Auth`：普通主页编辑权来自 User 对 UserProfile 的所有权，不来自 Scholar 对象授权（BR-IMP-05；FR04）。
- `Claim`、`Authorship`、`WorkEntity`：本 UC 不创建、不读取（FR05；FR07-03；`AC-IMPL-FR03-03`）。
- 第二个 `UserProfile`/`ProfileCopy`：无新建/复制消息，1:1 为编辑期不变量（FR04；`AC-IMPL-FR04-03`）。
- `PublicProfileUI`/`PublicPortalUI`：公开读取与展示属 UC-03；本 UC 只保证写侧公开投影（`AC-IMPL-FR06-05` 读侧）。
- `Session`/`CredentialContext`：已认证请求上下文是 M2 的输入；基线未定义会话对象（B-06；UC-04 Q-07）。
- `AuditCoordinator`（全局 `AuditCtl`）：审计查询与集中治理属 UC-17；本 UC 只需一次写入留痕（见 07 §4）。
- `FieldPolicy`/`PendingProfileVersion`：分别是协调器判定知识、UserProfile 的状态属性；基线未定义独立生命周期，且策略未冻结（B-01/B-02）。

## 5. VOPC 完整性检查

| 检查 | 结果 |
|---|---|
| 只包含 UC-22 参与类 | 是，5 个 |
| 每个类有事件流/消息依据 | 是，见表 1/表 3 |
| 每条关系可追溯到消息或业务约束 | 是，见表 2 |
| 不把 Actor 画成系统类 | 是 |
| 不用全局 BCE 冒充 VOPC | 是；本图独立于 `DGM-CLASS-01-analysis.puml` |
| 关系多重性是否有依据 | 只有 `User—UserProfile 1:1` 有正式依据；控制/边界到实体的关联不定义持久多重性，已说明 |
| 是否引入设计技术 | 否 |
| 是否出现“第二主页”或学术对象 | 否 |

## 6. 与全局图的关系

本 VOPC 是 UC-22 的局部静态视图，不是全系统类图。与 `DGM-CLASS-01-analysis.puml` 的异同、命名合并建议见[07-与全局模型差异](07-global-model-diff.md)。R5 统一分析类时应以本 VOPC 为输入之一，而不是用全局图反推本 UC 的参与类。
