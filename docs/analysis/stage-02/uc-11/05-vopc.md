# UC-11 VOPC（参与类类图）与关系依据

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-VOPC-15-statistics.puml`](diagrams/DGM-VOPC-15-statistics.puml)
**导出：** [SVG](diagrams/generated/DGM-VOPC-15-statistics.svg) · [PNG](diagrams/generated/DGM-VOPC-15-statistics.png)
**方法：** 教材 §5.4.4：VOPC 只来自本用例实现的顺序图对象，关系来自消息和业务约束；不是全局 BCE。

## 1. 参与类集合

VOPC 只包含以下 5 个类：

| 类 | 类型 | 来自顺序图对象 | 事件流依据 |
|---|---|---|---|
| `StatisticsUI` | Boundary | M1、M14、M29、M34（接收）；M12、M16、M27、M32、M36、M39、M41、M43、M45、M47、M49（返回） | E1、E5–E7、E11–E14；A1–A19 |
| `StatisticsCoordinator` | Control | M2、M15、M30、M35（接收）；M3、M6–M11、M22、M25–M26、M31、M38、M40、M42、M44、M46、M48（自消息）；M4、M18、M20、M23（发出只读请求） | E2–E4、E7–E10、E12–E14；A1–A19 |
| `WorkEntity` | Entity | M4、M18（接收，只读）；M5、M19（应答） | E2、E8、E12；A7、A8、A10、A12、A13 |
| `Authorship` | Entity | M20（接收，只读）；M21（应答） | E8；A17 |
| `Institution` | Entity | M23（接收，只读）；M24（应答） | E9；A7–A9 |

参与者“访客”不进入 VOPC：参与者不是系统内部类（教材 §5.4.4）。“口径与分类映射维护方”是外部前置，本 UC 无消息，也不进入 VOPC。

## 2. 关系与来源

| 关系 | 类型 | 来自消息/约束 | 方向/角色 | 多重性 | 说明 |
|---|---|---|---|---|---|
| `StatisticsUI --> StatisticsCoordinator` | 单向关联/消息关系 | M2/M15/M30/M35 请求；M12/M16/M27/M32/M36/M39/M41/M43/M45/M47/M49 返回 | UI 请求；Coordinator 返回结果 | 不定义持久多重性 | UI 无持久业务属性；按消息方向表达一次请求的协作 |
| `Coordinator ..> WorkEntity` | 单向**只读**依赖 | M4 读共享字段；M18 读发生时机构归属事实 | 读取计数单位与共享字段 | 不定义持久多重性 | 虚线表示只读：不写、不删除、不合并、不改公开状态 |
| `Coordinator ..> Authorship` | 单向**只读**依赖 | M20 | 读取参与者角色/顺序/多机构原值 | 不定义持久多重性 | 虚线表示只读：不写关系状态；不按作者数计数 |
| `Coordinator ..> Institution` | 单向**只读**依赖 | M23 | 读取规范名/别名/合并映射 | 不定义持久多重性 | 虚线表示只读：不新增机构、不改别名、不合并机构 |
| `Authorship "0..*" --> "1" WorkEntity` | 业务关联 | BR-353；ID-05/ID-11；M20/M18 | 归属成果 | `0..*` : `1` | 一项成果可有多条参与关系；一条关系只属于一个成果（沿用 UC-02/UC-03/UC-10） |
| `WorkEntity "0..*" --> "0..*" Institution` | 业务关联 | BR-IMP-06；BR-513；FR11-S08；M18/M23 | 成果发生时机构归属（有证据） | `0..*` : `0..*` | 一项成果可有零个（缺失/无证据→机构未知）或多个发生时机构；一个机构可对应多项成果 |

### 2.1 为什么控制/边界到实体的关联不标持久多重性

`StatisticsUI`/`StatisticsCoordinator` 到实体的关联按**消息方向**表达协作，不是长期业务对象链接。为它们标 `1..*` 会把一次统计请求的协调关系误解为持久聚合。因此本 VOPC 只对业务事实之间的关联给出多重性：`Authorship→WorkEntity`、`WorkEntity→Institution` 两条。

### 2.2 为什么 `WorkEntity—Institution` 的两端都是 `0..*`

- **零个：** BR-IMP-06 允许成果的机构归属“缺失/未知”并被单列；BR-513 要求缺失标为机构未知、不得用学者当前单位替代。因此“有证据的发生时机构”可以为 0 个。
- **多个：** `AC-IMPL-FR11-01` 的给定条件是“同成果跨源重复且涉及机构甲乙，甲乙各 +1”；BR-IMP-06 明确“成果发生时多机构各一次、单机构一次”。因此一项成果可关联多个机构。
- **不是组合：** 机构身份不随成果存亡变化（机构是独立事实），故不标组合菱形；机构合并链由 `Institution` 自身属性与已生效映射表达。
- **同一机构在同一成果上只计一次：** 该约束是**计数规则**（BR-IMP-06），不是多重性——关联集合会先按规范机构去重再计数；把它写成 `1` 会与“多机构各一次”和真实数据（同名机构多写法）冲突。

### 2.3 为什么若干事实写成属性而不是实体关联

| 事实 | 表达方式 | 理由 |
|---|---|---|
| 参与者级发生时机构原值（多机构原值） | `Authorship.participantAffiliationRaw[]`＋`occurringDate`＋`evidenceRefs` | 逐参与者、逐来源的原始署名事实（ID-05）；若另画 `Authorship→Institution` 边，会与 `WorkEntity→Institution` 重复表达同一归属结果，且顺序图没有该消息（M20 一次读取后由协调器归并） |
| 成果级“发生时机构归属事实” | `WorkEntity.occurredAffiliationFacts[]`（原值/规范引用/发生时语义/证据引用/未映射标记） | 这是“成果 × 机构”的取值集合，是 `WorkEntity—Institution` 关联的属性化证据；不另设 `InstitutionAffiliation` 实体（03 §4） |
| 领域取值 | `WorkEntity.fieldRefs[]` | 领域分类层级与映射未冻结（Q-SPEC-03；C-7），不能建 `Field`/`FieldTaxonomy` 类；只引用已生效分类结果并允许“待分类” |
| 数据性质（真实/模拟/混合） | `WorkEntity.dataOrigin` | 成果共享字段（§7.1）；单个成果只有一个数据性质标识（BR-IMP-07 互斥） |
| 来源与版本追溯 | `WorkEntity.sourceRefs[]`、`versionRefs[]`（仅追溯，不计数） | 来源数与版本数不参与计数（BR-IMP-02/06）；本 UC 无 `SourceRecord`/`WorkVersion` 消息，画边会暗示计数依赖 |
| 统计视图投影（范围/条件/计数/声明/更新时间） | `StatisticsCoordinator` 的单次请求分析态 | 读时投影，不是业务事实；画成实体即为 C-01 所指的“持久统计结果”（05 §4） |
| 口径与分类映射版本 | 外部前置（FR14/FR16 的已生效维护结果） | V2.2 未定义口径版本对象与失效规则（B-02/B-06）；画边会把未冻结对象写成依赖 |

## 3. 从顺序图到 VOPC 的对应

| 顺序图对象 | VOPC 类 | 保留/删除理由 |
|---|---|---|
| 访客（Actor） | 不保留 | 参与者不是系统类；只发 M1/M14/M29/M34 并接收返回 |
| StatisticsUI | 保留 | 接收 M1/M14/M29/M34；返回 M12/M16/M27/M32/M36/M39/M41/M43/M45/M47/M49 |
| StatisticsCoordinator | 保留 | 承担 M2–M11、M22、M25–M26、M31、M38、M40、M42、M44、M46、M48 全部守卫与计算，并发出 M4/M18/M20/M23 只读请求 |
| WorkEntity | 保留 | 接收 M4/M18，应答 M5/M19 |
| Authorship | 保留 | 接收 M20，应答 M21 |
| Institution | 保留 | 接收 M23，应答 M24 |
| 无其他对象 | — | 没有 `SourceRecord`、`WorkVersion`、`Scholar`、`StatisticsResult`、DTO 或 `AuditEvidence` 的消息 |

## 4. 排除项与理由

- `StatisticsProjection` / `StatisticsResult` / `StatisticsSnapshot` / 口径版本：统计视图是读时投影；持久结果对象需同时定义版本、生效状态、失效与回退，V2.2 未定义（C-01；B-06）。投影字段作为 `StatisticsCoordinator` 的分析态，见 06 §3。
- `SourceRecord` / `WorkVersion`：来源数与版本数不参与计数（BR-IMP-02/06；`AC-IMPL-FR16-02`）；来源追溯属 UC-02/UC-16，无独立消息。
- `Scholar` / `User` / `UserProfile`：V2.2 FR11 不要求学者维度统计；机构归属不得由学者当前单位推断（BR-513；SR-06），故也不建 `Scholar—Institution` 边。
- `Field` / `FieldTaxonomy` / `DomainDictionary` / `InstitutionCategory`：领域层级与机构类别维度未冻结（Q-SPEC-03；C-7；B-02/B-08）。
- `QualityScore` / `ImpactIndex` / `RankingPolicy`：基线不做质量或影响力评价（BR-522；SRS §2.1）。
- `InstitutionAffiliation`（独立实体）：归属事实属性化（05 §2.3；03 §4）。
- `StatisticsJob` / `MetricsRefreshTask` / `CacheEntry` / 聚合引擎：属实现或模块细化的“结果生成”取向（C-01），V2.2 不要求。
- `AuditEvidence`：本 UC 无受保护写入；统计失败明细与审计查询属 FR14/UC-17。
- `Session` / `CredentialContext`：统计无需登录（NF01；BR-526）。
- DTO / 展示投影（`RankingView`、`ChartData`、`StatisticsDTO`）：交互态，不是业务事实（教材 §5.4.2/§5.5.2）。

## 5. VOPC 完整性检查

| 检查 | 结果 |
|---|---|
| 只包含 UC-11 参与类 | 是，5 个 |
| 每个类有事件流/消息依据 | 是，见表 1/表 3 |
| 每条关系可追溯到消息或业务约束 | 是，见表 2 |
| 不把 Actor 画成系统类 | 是；访客不进入 VOPC |
| 不用全局 BCE 冒充 VOPC | 是；本图独立于 `DGM-CLASS-01-analysis.puml` |
| 关系多重性是否有依据 | 两条业务关联有依据（§2.2）；控制/边界到实体不定义持久多重性（§2.1） |
| 是否把 `WorkEntity` 与 `Authorship` 混为一类 | 否：成果实体是计数单位，关系是参与事实且不计数 |
| 是否把 `Institution` 与“学者当前任职”混为一类 | 否：机构身份只表达规范名/别名/合并链，成果归属取发生时证据 |
| 是否把“机构归属事实”画成第三个重复实体 | 否：属性化＋一条关联（§2.3） |
| 是否出现持久统计结果/快照/口径版本类 | 否（§4；C-01） |
| 是否出现质量评分或影响力排序类 | 否（BR-522） |
| 是否出现写操作或删除边 | 否：三条实体关联全为只读依赖（虚线） |
| 是否引入设计技术（表/接口/队列/索引/缓存/DTO） | 否 |

## 6. 与全局图的关系

本 VOPC 是 UC-11 的局部静态视图，不是全系统类图。与 `DGM-CLASS-01-analysis.puml` 的异同、命名合并建议见[07-与全局模型差异](07-global-model-diff.md)。R5 统一分析类时应以本 VOPC 为输入之一，而不是用全局图反推本 UC 的参与类。
