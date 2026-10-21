# UC-03 VOPC（参与类类图）与关系依据

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-VOPC-04-profile.puml`](diagrams/DGM-VOPC-04-profile.puml)
**导出：** [SVG](diagrams/generated/DGM-VOPC-04-profile.svg) · [PNG](diagrams/generated/DGM-VOPC-04-profile.png)
**方法：** 教材 §5.4.4：VOPC 只来自本用例实现的顺序图对象，关系来自消息和业务约束；不是全局 BCE。

## 1. 参与类集合

VOPC 只包含以下 6 个类：

| 类 | 类型 | 来自顺序图对象 | 事件流依据 |
|---|---|---|---|
| `PublicProfileUI` | Boundary | M1、M2、M6、M8、M14、M16、M25、M27 | E1、E9、E10、A1–A6 |
| `PublicProfileCoordinator` | Control | M2–M5、M7、M9–M13、M15、M17、M19、M21–M24、M26 | E2–E9、A1–A8 |
| `UserProfile` | Entity | M9–M12 | E4、E5、A4、A8 |
| `Scholar` | Entity | M17、M18 | E3、E6、A5、A6 |
| `Authorship` | Entity | M19、M20 | E7、E8、A7 |
| `WorkEntity` | Entity | M21、M22 | E7、A2 |

访客不进入 VOPC，因为参与者不是系统内部类（教材 §5.4.4）。

## 2. 关系与来源

| 关系 | 类型 | 来自消息/约束 | 方向/角色 | 多重性 | 说明 |
|---|---|---|---|---|---|
| `PublicProfileUI --> PublicProfileCoordinator` | 单向关联/消息关系 | M2、M5、M7、M13、M15、M24、M26 | UI 发起查看请求；Coordinator 返回视图或状态 | 不定义持久多重性 | UI 不是实体；这是按消息方向的单向关联，不是持久对象链接 |
| `PublicProfileCoordinator --> UserProfile` | 单向关联/读取关系 | M9、M11 | Coordinator 读取公开资料与零成果判断 | 不定义持久多重性 | 只读；Coordinator 不拥有主页 |
| `PublicProfileCoordinator --> Scholar` | 单向关联/读取关系 | M17 | Coordinator 读取公开身份与认领/核验状态 | 不定义持久多重性 | 只读；不授予/改变认领状态 |
| `PublicProfileCoordinator --> Authorship` | 单向关联/读取关系 | M19 | Coordinator 读取公开且生效的参与关系与状态 | 不定义持久多重性 | 只读；不改写关系状态 |
| `PublicProfileCoordinator --> WorkEntity` | 单向关联/读取关系 | M21 | Coordinator 读取成果公开展示字段 | 不定义持久多重性 | 只读；不展开详情 |
| `Scholar "1" -- "0..*" Authorship` | 业务关联 | SRS §12“Scholar—WorkEntity 通过 Authorship 关联”；FR15；全局 `DGM-CLASS-01` | 学者身份参与多条成果关系 | 1—0..* | 关系状态属于 `Authorship`，不能压到 `Scholar` 上 |
| `Authorship "0..*" -- "1" WorkEntity` | 业务关联 | SRS §12；FR15；§7.2“一个作者同一论文只能形成一条本人关系”；UC-02 VOPC | 关系指向一条成果；一条成果可有多个参与者 | 0..*—1 | 每条关系独立状态；成果可有多个 Authorship |

### 2.1 为什么控制/边界到实体的单向关联不标持久多重性

`PublicProfileUI`/`PublicProfileCoordinator` 到实体的单向关联按**读取消息方向**表达，不是长期业务对象链接。为它们标多重性会把一次查看请求的协作关系误解为持久聚合关系；因此本 VOPC 不定义其持久多重性。需要业务多重性的只有 `Scholar—Authorship—WorkEntity` 链，其多重性有正式依据。

### 2.2 为什么 `UserProfile` 与 `Scholar` 之间没有关联

- FR03/`AC-IMPL-FR03-04`：同名不自动合并，页面对象与核验状态分别显示；
- BR-IMP-03：姓名不是同人证据；SRS §12：`UserProfile 与 Scholar 不是一对一强制合并`；
- 本 UC 也没有任何消息在两者之间传递：`UserProfile` 分支与 `Scholar` 分支互斥。

因此 VOPC 中**不画** `UserProfile—Scholar` 关联，也不画“同名”或“可能是同一人”的虚线；即使某 User 已认领某 Scholar，该关联属于 FR05 授权结果（UC-05/06），不是公开读取视图，也不在本 UC 参与类中表达。

### 2.3 为什么没有 `User`

`User` 是账号与所有权主体，本 UC 公开读取不需要账号状态：

- 公开读取由 `UserProfile` 自身的公开可见性回答；
- `AC-IMPL-FR04-02` 只保证停用/退出不删除学者公开档案，未规定停用账号的 UserProfile 是否仍公开（Q-09）；
- 若把 `User` 拉入序列，就必须在本 UC 擅自决定“账号状态是否影响主页公开性”。

`User 1:1 UserProfile` 已在 UC-04 VOPC 与全局图中表达，本 UC 不重复也不修改。

### 2.4 其他排除项

| 候选 | 排除理由 |
|---|---|
| `Claim` / `ScholarAuthorization` | 认领申请、证据与授权生命周期属 UC-05/06；本 UC 只读取 `Scholar` 的公开认领/核验状态标签 |
| `ReviewEvidence` / `AuditEvidence` | 私有证明与审核记录不得进入公开响应（BR-IMP-11；`AC-IMPL-NF06-01`）；本 UC 只做屏蔽 |
| `SourceRecord` / `WorkVersion` | 来源与版本属成果详情（UC-02）；档案列表只用成果公开展示字段 |
| `SearchUI` / `SearchCoordinator` | 检索属 UC-01；本 UC 从链接进入 |
| `Session` | 公开读取无需登录（FR03；NF01） |
| `ProfileUI` / `ProfileCoordinator` | 本人普通资料编辑属 UC-22 受保护写入（BR-IMP-05） |
| `IdentityMatcher` / 合并器 | 本 UC 禁止自动合并（`AC-IMPL-FR03-04`；BR-IMP-03）；同人判定属 UC-10/15 |
| `EmptyState` / `Notice` | 空态与未认领提示是派生显示状态，不是实体 |

## 3. 从顺序图到 VOPC 的对应

| 顺序图对象 | VOPC 类 | 保留/删除理由 |
|---|---|---|
| 访客（Actor） | 不保留 | 不是系统类 |
| PublicProfileUI | 保留 | 接收 M1、返回 M6/M8/M14/M16/M25/M27 |
| PublicProfileCoordinator | 保留 | 协调 M3–M5/M7/M9–M13/M15/M17/M19/M21–M24/M26 |
| UserProfile | 保留 | 接收 M9、M11 |
| Scholar | 保留 | 接收 M17 |
| Authorship | 保留 | 接收 M19 |
| WorkEntity | 保留 | 接收 M21 |
| 无其他对象 | — | 没有 User/Claim/Auth/Evidence/Source 的消息 |

## 4. VOPC 完整性检查

| 检查 | 结果 |
|---|---|
| 只包含 UC-03 参与类 | 是，6 个 |
| 每个类有事件流/消息依据 | 是，见表 1/表 3 |
| 每条关系可追溯到消息或业务约束 | 是，见表 2 |
| 不把 Actor 画成系统类 | 是 |
| 不用全局 BCE 冒充 VOPC | 是；本图独立于 `DGM-CLASS-01` |
| 多重性是否有依据 | 只有 `Scholar—Authorship—WorkEntity` 有正式依据；控制/边界向实体的读取关联不定义持久多重性 |
| 是否画了 `UserProfile—Scholar` 合并边 | 否，明确排除（§2.2） |
| 是否把 `Scholar—WorkEntity` 画成直接关联 | 否，必须经 `Authorship`（§2） |
| 是否引入设计技术 | 否 |

## 5. 与全局图的关系

本 VOPC 是 UC-03 的局部静态视图，不是全系统类图。与 `DGM-CLASS-01-analysis.puml` 的异同、命名合并与职责补充建议见 [07-与全局模型差异](07-global-model-diff.md)。R5 统一分析类时应以本 VOPC 为输入之一，而不是用全局图反推本 UC 的参与类。
