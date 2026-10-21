# UC-16 VOPC（参与类类图）与关系依据

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-VOPC-20-four-types-scale.puml`](diagrams/DGM-VOPC-20-four-types-scale.puml)
**导出：** [SVG](diagrams/generated/DGM-VOPC-20-four-types-scale.svg) · [PNG](diagrams/generated/DGM-VOPC-20-four-types-scale.png)
**方法：** 教材 §5.4.4：VOPC 只来自本用例实现的顺序图对象；关系来自消息与业务约束；不等于全局 BCE 图。

## 1. 参与类集合

| 类 | 类型 | 来自顺序图消息 | 事件流依据 |
|---|---|---|---|
| `CoverageCheckUI` | Boundary | M1/M2（接收）；M3（发出）；M7/M23/M25（接收）；M8/M24/M26/M27（发出） | E1、E10、E11；A1–A4、A8–A10 |
| `CoverageCheckCoordinator` | Control | M3（接收）；M4/M13/M18/M21/M22/M28（自消息）；M5/M9/M11/M14/M16/M19（发出只读请求）；M7/M23/M25（返回） | E1–E11；A1–A12 |
| `WorkEntity` | Entity | M5/M9/M14/M19（接收，只读）；M6/M10/M15/M20（应答） | E3、E4、E7、E9；A2、A4、A5、A7、A11 |
| `SourceRecord` | Entity | M11（接收，只读）；M12（应答） | E5；A3、A7、A12 |
| `WorkVersion` | Entity | M16（接收，只读）；M17（应答） | E8；A5、A6 |

访客与核对执行者（管理员）不进入 VOPC：两者是参与者，不是系统内部类（教材 §5.4.4）。核对结果投影不进入 VOPC：它是 M25 的返回内容，不是业务事实。

## 2. 关系与来源

| 关系 | 类型 | 来自消息/约束 | 方向/角色 | 多重性 | 说明 |
|---|---|---|---|---|---|
| `CoverageCheckUI --> CoverageCheckCoordinator` | 单向关联/消息关系 | M3 请求；M7/M23/M25 返回 | UI 提交核对请求；协调器返回失败/缺项/投影 | 不定义持久多重性 | 边界—控制交互关联，不是持久对象链接 |
| `CoverageCheckCoordinator ..> WorkEntity` | 单向**只读**依赖 | M5、M9、M14、M19 | 读取可识别事实、类型适用字段、去重实体计数、数据性质分组 | 不定义持久多重性 | 虚线表示只读：不改字段、不改公开状态、不合并/拆分实体 |
| `CoverageCheckCoordinator ..> SourceRecord` | 单向**只读**依赖 | M11 | 读取许可与来源级事实 | 不定义持久多重性 | 虚线表示只读：不改许可、不改原值、不删除来源 |
| `CoverageCheckCoordinator ..> WorkVersion` | 单向**只读**依赖 | M16 | 读取明示版本证据 | 不定义持久多重性 | 虚线表示只读：不新建版本关系、不归并实体 |
| `WorkEntity "1" — SourceRecord "0..*"` | 业务关联（来源证据） | 无直接消息；由 FR16、`AC-IMPL-FR16-02`、BR-IMP-02、INV-02 约束 | 成果聚合其来源记录 | `1` : `0..*` | 一条成果可有多条来源；来源数不进入规模分子 |
| `WorkEntity "1" — WorkVersion "0..*"` | 业务关联（明示版本证据） | FR02“须有证据才显示版本关系”；DD-08；`AC-IMPL-FR16-02` | 成果拥有版本清单 | `1` : `0..*` | 无明示证据不建立该关系；版本数不进入规模分子 |

### 2.1 为什么边界/控制到实体的关联不标多重性

M5/M9/M14/M19/M11/M16 都是一次核对请求内的读取交互，不是长期对象包含关系。若给 `CoverageCheckCoordinator ..> WorkEntity` 标 `1..*`，会把“一次核对的协调”误解为“协调器持久拥有成果集”。协调器只持有本次核对的声明与中间计数（见 [06](06-class-definition.md) §3）。

### 2.2 为什么实体之间要标 `1 : 0..*`

- `WorkEntity — SourceRecord`：`AC-IMPL-FR16-02` 的 Given 是“一成果跨两个来源”，FR16 要求每条来源保留来源 ID/采集时间/许可/数据性质。因此必须能表达一条成果对应多条来源记录；同时“来源数不进入分子”这一计数规则**不是**多重性，而是 M18 的守卫（BR-UC16-05）。
- `WorkEntity — WorkVersion`：FR02 只允许有明示证据的版本关系，DD-08 规定无明示证据保持独立，故另一端可为 `0..*`（含 0）。同样，“版本数不进入分子”是 M18 的计数规则，不写进多重性。
- 两条关系在本 UC 中**只被读取**，其建立与维护属 UC-09（清洗/去重/版本归并）。

### 2.3 为什么没有 `Authorship`、`Scholar`、`Institution`、`CleanRecord`

| 排除项 | 理由 |
|---|---|
| `Authorship` | 覆盖判定不使用参与关系事实；规模分子 = 去重实体数，不随参与关系数量变化（BR-UC16-05；INV-02）。本 UC 无该消息，画边会暗示“作者数参与计数” |
| `Scholar` / `User` / `UserProfile` | 本 UC 不做身份/账号判断；访客公开核对无需登录（NF01；SRS §8） |
| `Institution` | 机构归属是 FR11 排名与 FR12 建边的维度；FR16 的覆盖判定不使用机构维度。项目承担单位/获奖团队按 §7.1 作为 `WorkEntity` 的类型适用字段事实 |
| `CleanRecord` / `DedupCandidate` | 治理写侧事实；本 UC 只读治理结论与计数口径（UC-09） |
| `CollectionTask` / `SourceDefinition` | 采集任务状态不得用于推断覆盖通过（§6.1/§6.2；BR-UC16-13） |
| `AuditEvidence` | 本 UC 无受保护写入；核对是否留痕未冻结（Q-09） |
| 核对报告/规模结果/目标阈值 | 读时投影与需求常量，不是业务事实（第 4 节） |

### 2.4 为什么“类型适用字段事实”是属性而不是关联

§7.1 为四类各定义了一组适用字段（论文 DOI/发表年份/载体；专利申请号/申请日/公开日；项目编号/起止/承担单位；获奖年度/奖项/获奖团队）。这些字段事实：

1. 只在该类型下适用（换类型不适用），属**实体自身的类型范围属性**；
2. 不随来源数量增加（不是来源事实），也不随版本变化（不是版本事实）；
3. 本 UC 没有对“字段目录/类型模式”的独立查询消息。

因此按 `WorkEntity.typeFieldFacts` 建模，并在 [06](06-class-definition.md) §4 说明其逐类型语义与“缺失标未知”约束；不建 `TypeFieldFact`/`TypeSchema` 关联或类。

### 2.5 为什么没有 `SourceRecord — WorkVersion` 关系

版本与出处是不同层事实：同一版本可有多个来源，同一来源也可承载版本信息。本 UC 只需回答“是否有明示版本证据”（`WorkVersion`）与“每条来源是否合法”（`SourceRecord`），没有“某版本由某来源承载”的读取消息。该关系是否在分析层建立留待 UC-09/UC-18 与 R5（[07](07-global-model-diff.md) §4）。

## 3. 从顺序图到 VOPC

| 顺序图对象 | VOPC 类 | 保留/删除理由 |
|---|---|---|
| 访客（Actor） | 不保留 | 参与者不是系统类；只发 M1 并接收 M8/M27 |
| 核对执行者（管理员，Actor） | 不保留 | 参与者不是系统类；只发 M2 并接收 M24/M26 |
| CoverageCheckUI | 保留 | M1/M2/M3/M7/M8/M23–M27 |
| CoverageCheckCoordinator | 保留 | M3–M7、M9–M26、M28 的守卫与计算，并发出 M5/M9/M11/M14/M16/M19 只读请求 |
| WorkEntity | 保留 | M5/M6/M9/M10/M14/M15/M19/M20 |
| SourceRecord | 保留 | M11/M12 |
| WorkVersion | 保留 | M16/M17 |
| 核对结果投影（M25 的返回内容） | 不保留 | 读时投影；无持久身份、无独立行为 |
| 其他对象 | 不保留 | 无消息；不为对称而新增 |

## 4. 排除项与理由

- **核对报告/规模结果实体**（`CoverageReport`、`ScaleCheckResult`、`CoverageSnapshot`、`AcceptanceReport`）：持久化将要求定义范围版本、生效状态、失效与回退、留痕，V2.2 与 SPEC 均未定义；本 UC 按 BR-UC16-09 把结论作为 `CoverageCheckCoordinator` 的单次请求分析态（Q-09）。
- **目标阈值对象**（`ScaleTarget`、`Threshold`）：1,000,000/5,000,000 是需求目标值，由协调器在投影中声明并标 NOT RUN（M22；BR-UC16-08），不是可维护实体。
- **字段目录/类型模式**（`TypeSchema`、`FieldDictionary`、`TypeFieldCatalog`）：见 §2.4。
- **统计与网络对象**（`StatisticsCoordinator`、`Institution`、`CollaborationEdge`）：维度聚合与建边属 FR11/FR12/IN01（UC-11～UC-15）。
- **检索与详情对象**（`SearchUI`、`SearchCoordinator`、`WorkDetailUI`、`WorkDetailCoordinator`）：见 [07](07-global-model-diff.md) §3；本 UC 不检索、不组装单条详情。
- **治理与采集对象**（`CleanRecord`、`DedupCandidate`、`CollectionTask`、`SourceAdapter`）：写侧/接入侧事实，本 UC 只读结论（FR09 属 UC-09；FR08/FR10 属 UC-18/19）。
- **审计与待办**（`AuditEvidence`、`AdminTodo`）：核对结论是否留痕未冻结（Q-09）；待办队列属 UC-17。
- **DTO/视图**（`CoverageView`、`GroupedCount`、`DifferenceList`）：交互态投影，不是业务事实（教材 §5.4.2/§5.5.2）。
- **会话/凭据**（`Session`、`CredentialContext`）：访客公开核对无需登录（NF01；SRS §8）；核对执行者的权限校验属通用发布/管理约束，不作为本 UC 业务对象（Q-02）。

## 5. VOPC 完整性检查

| 检查 | 结果 |
|---|---|
| 只包含 UC-16 参与类 | 是，5 个 |
| 每个类有事件/消息依据 | 是 |
| 每条关系可追溯到消息或业务约束 | 是；实体间两条关系由 FR16/AC/BR/INV/DD 约束支撑 |
| 不把 Actor 画成系统类 | 是；访客、核对执行者均未进入 VOPC |
| 不用全局 BCE 冒充 VOPC | 是，独立于 `DGM-CLASS-01-analysis.puml` |
| 是否强行引入 Search/Statistics/Detail/Authorship/治理类 | 否 |
| 是否引入实现技术 | 否（无数据库/API/框架/缓存） |
| 是否把来源数/版本数表达成成果数 | 否：`1 : 0..*` 只表达聚合；计数规则在 M18/BR-UC16-05 |
| 是否把核对结论持久化为实体 | 否：投影作为协调器分析态 |

## 6. 与全局图关系

本 VOPC 是 UC-16 局部静态视图。与全局 `DGM-CLASS-01-analysis.puml` 的命名、职责与关系差异见 [07](07-global-model-diff.md)。R5 统一分析类时应以本 VOPC 为输入之一；本轮不修改全局图。
