# UC-03 Boundary / Control / Entity 识别

**版本：** V1.0（2026-10-21）
**方法：** 教材 §5.4.2：从用例事件流识别边界类、控制类、实体类；不从现有全局 BCE 反向填充。
**输入：** 本目录 01 规约、02 事件流。

## 1. 识别原则

1. **Boundary**：与外部参与者交互、接收请求或显示结果的对象。
2. **Control**：协调一个用例内的对象解析、公开过滤、状态区分与结果返回；不是实现层 Controller/Service。
3. **Entity**：承载业务事实、状态和持久信息；不是数据库表。
4. 只有在本 UC 事件流中实际接收消息的对象才进入 VOPC。
5. 参与者“访客”不是系统分析类；系统内部对象（Scholar、成果）不是外部 Actor。
6. 仅因“以后可能用到”而出现的类不进入本 UC。

## 2. 候选分析类

| ID | 候选类 | 类型 | 从哪些事件发现 | 业务行为 | 为什么不能由其他候选直接承担 |
|---|---|---|---|---|---|
| C1 | `PublicProfileUI` | Boundary | E1、E9、E10、A1–A6 | 承接主页/档案查看请求；显示普通资料、学者身份、成果列表、空态、未认领提示、不可访问与执行失败 | `UserProfile`/`Scholar` 是业务实体，不应承担请求承接与状态显示；协调器不直接面向访客 |
| C2 | `PublicProfileCoordinator` | Control | E2、E3、E8、E9、A1–A3、A5、A7 | 按稳定标识解析对象类型；校验公开可访问；按分支读取资料/成果关系；统一执行公开/生效/许可过滤与关系状态不冒充；分离空态/不可访问/失败；不按姓名合并 | Boundary 只负责交互与显示，无法保证“一个请求只呈现一个对象”“状态不冒充”“公开最小化”；各 Entity 只知道自己，不能跨对象过滤 |
| C3 | `UserProfile` | Entity | E3、E4、E5、A4、A5、A8 | 承载唯一/稳定公开地址、公开可见性、允许公开的普通资料、零成果空态判断 | `Scholar` 是平台采集/核验的学者身份，不承载普通用户资料；`UserProfile` 不能由 `User`/`Session` 代替（公开主页≠账号） |
| C4 | `Scholar` | Entity | E3、E6、A5、A6 | 承载独立的公开学者身份（公开名称/别名）、公开状态、认领/核验状态、未认领提示依据 | `UserProfile` 是注册用户的普通主页，不因同名成为学者身份；`Scholar` 可无账号、可未认领，不能并入 `UserProfile` |
| C5 | `Authorship` | Entity | E7、E8、A7 | 承载 Scholar—成果参与关系（角色、顺序、关系状态、公开证据范围），供公开视图按状态过滤 | 关系状态是独立业务事实（自动关联/本人确认/待核验/争议/已排除），不能由 `Scholar` 或 `WorkEntity` 单边推导；否则“待核验不得显示为已确认”无法表达 |
| C6 | `WorkEntity` | Entity | E7、E8 | 提供参与关系对应成果的公开展示字段（稳定标识、标题、类型、年份等），不展开详情 | 成果事实属于成果实体，不属于学者身份；档案列表只是引用成果，不得把成果字段复制进 `Scholar` |

## 3. 逐类选择理由

### C1 PublicProfileUI（Boundary）

- 发现于 E1 打开地址、E9 返回视图、E10 阅读展示，以及 A1–A6 的状态显示。
- 它区分显示“空态 / 未认领提示 / 不可访问 / 执行失败”，但不决定公开范围或对象合并。
- 无持久业务属性；展示状态是单次请求的分析状态。
- **命名说明：** 全局图使用 `PublicUI`（UC-01/02/03/…）与 `ScholarPortalUI`（UC-03/07）；`PublicProfileUI` 是本 UC 的局部边界名，是否合并/拆分留 R5（见 07）。

### C2 PublicProfileCoordinator（Control）

- 发现于 E2 解析对象类型、E3 公开校验、E8 统一过滤、E9 组装视图，以及 A5 同名并存、A7 关系状态。
- 必须跨 `UserProfile` 与 `Scholar` 两个分支协调，并保证同一请求只呈现一个对象；任一 Entity 都不能独立保证“不按姓名合并”“不冒充已确认”。
- 三态分离（空态/不可访问/失败）需要在同一处统一裁决，否则会把“无公开成果”显示成“不存在”。
- **命名说明：** 全局图没有公开主页/档案读取的专用协调者（`SearchCtl` 负责检索、`ProfileCtl` 负责本人编辑）；本 UC 使用局部全名，R5 决定是否新增或扩展（见 07、D-11 类命名问题）。

### C3 UserProfile（Entity）

- 发现于 E4 公开普通资料、E5 零成果空态、A4 零成果、A5 同名并存、A8 公开最小化。
- 必要业务事实：唯一/稳定/可分享公开地址、公开可见性、允许公开的普通资料、零成果判定依据。
- 不能并入 `User`：账号状态与公开主页是不同业务事实（登录/停用不在本 UC；Q-09）。
- 不能并入 `Scholar`：SRS §12 明确 `UserProfile 与 Scholar 不是一对一强制合并`。

### C4 Scholar（Entity）

- 发现于 E6 公开身份与认领/核验状态、A6 未认领提示、A5 同名区分。
- 必要业务事实：公开学者身份（公开名称/别名）、公开状态、认领状态、身份核验状态。
- 可无账号、可未认领仍公开（FR03；`AC-IMPL-FR03-01`）。
- 不能并入 `UserProfile`：同名不是同人证据（BR-IMP-03；SPEC ID-01）。

### C5 Authorship（Entity）

- 发现于 E7 读取参与关系、E8 过滤、A7 待核验/争议/已排除。
- 必要业务事实：参与角色、顺序、关系状态、可公开的证据范围。
- 不能并入 `Scholar`：一位学者有多条参与关系，且每条关系有独立状态。
- 不能并入 `WorkEntity`：同一成果有多位参与者，关系状态属于关系而非成果。

### C6 WorkEntity（Entity）

- 发现于 E7 读取成果展示字段。
- 必要业务事实：稳定标识、标题、类型、年份等公开展示字段（详情字段与来源属 UC-02）。
- 不能并入 `Scholar`：成果事实独立于身份；一位学者的档案通过 `Authorship` 引用多个成果。
- 不能并入 `Authorship`：关系不等于成果；`Authorship` 只携带角色/顺序/状态/证据。

## 4. 被拒绝的候选类

| 候选 | 拒绝理由 | 证据/后续 |
|---|---|---|
| `User`（账号） | 本 UC 公开读取不需要账号状态；`AC-IMPL-FR04-02` 只保证停用/退出不删除学者公开档案，未规定停用账号的 UserProfile 是否仍公开。若加入 `User` 就需擅自决定该规则 | FR04-02；Q-09 |
| `ProfileUI` / `ProfileCoordinator` | 全局 `ProfileUI/ProfileCtl` 的职责是本人普通资料编辑与所有权校验，属 UC-22 受保护写入 | BR-IMP-05；FR06；UC-22 |
| `Claim` / `ScholarAuthorization` | 本 UC 不读取申请、证据与授权记录，也不展示审核材料；只读取 `Scholar` 自身的公开认领/核验状态标签 | FR05；`AC-IMPL-NF06-01`；Q-06 |
| `ReviewEvidence` / `AuditEvidence` | 私有证明材料与审核记录不得进入公开响应；本 UC 只做屏蔽，不读取证据对象 | BR-IMP-11；`AC-IMPL-NF06-01` |
| `SearchUI` / `SearchCoordinator` | 检索是 UC-01；本 UC 从结果链接进入，不承担条件、排序、分页 | UC-01；FR01 |
| `Session` | 公开读取无需登录；`AC-IMPL-FR03-01` 参与者为访客 | FR03；NF01 |
| `SourceRecord` | 来源明细属成果详情（UC-02）；档案列表只需要成果公开展示字段 | FR02；UC-02 |
| `IdentityMatcher` / `MergeCoordinator` | 本 UC 明确禁止自动合并；同人/异人判定与合并属身份核验 | `AC-IMPL-FR03-04`；BR-IMP-03；UC-10/15 |
| `ProfileView` / `DTO` | 公开视图是对象属性的展示投影，不需要独立业务身份 | 教材 §5.4.2；本 UC 事件流 |
| `EmptyState` / `Notices` | 空态与未认领提示是派生显示状态，不是有独立生命周期的业务实体 | `AC-IMPL-FR03-01/03`；Q-02/Q-06 |

## 5. UserProfile / Scholar / Authorship / WorkEntity 边界结论

| 边界 | 结论 | 本 UC 证据 |
|---|---|---|
| UserProfile ↔ Scholar | 两个独立对象；同名不合并、不互相冒充、不互相推导 | FR03；`AC-IMPL-FR03-04`；BR-IMP-03；SRS §12 |
| UserProfile ↔ WorkEntity | **不建立直接关系**；主页零成果是空态，成果区来源口径待 Q-03；不得由普通资料推导学术身份或合作边 | FR03；`AC-IMPL-FR03-03`；SRS §12 |
| Scholar ↔ WorkEntity | **不建立直接关系**；参与关系必须经 `Authorship` 表达 | SRS §12“Scholar—WorkEntity 通过 Authorship 关联” |
| Scholar ↔ Authorship | 1 : 0..*；一位学者有多条参与关系，每条独立状态 | SRS §12；FR15；SPEC ID-06 |
| Authorship ↔ WorkEntity | 0..* : 1；每条关系指向一个成果，成果可有多个参与者 | SRS §12；FR15；UC-02 VOPC |
| Scholar ↔ Claim/Auth | 本 UC 不建立、不读取；认领状态只作为 `Scholar` 的公开状态标签 | FR05；UC-05/06；Q-06 |
| UserProfile ↔ User | 1:1 来自 FR04/`AC-IMPL-FR04-03`，但 `User` 不参与本 UC 序列（见第 4 节） | FR04；Q-09 |

## 6. 识别结论

本 UC 的候选分析类为 6 个：

- Boundary：`PublicProfileUI`
- Control：`PublicProfileCoordinator`
- Entity：`UserProfile`、`Scholar`、`Authorship`、`WorkEntity`

`User`、`ProfileUI/ProfileCoordinator`、`Claim`、`ScholarAuthorization`、`ReviewEvidence`、`SearchUI/SearchCoordinator`、`Session`、`SourceRecord`、`IdentityMatcher`、`ProfileView`、`EmptyState` 均不进入本 UC VOPC。该集合将在 04 的顺序图中接受消息检验；若某类没有消息，则删除或降为属性/职责。
