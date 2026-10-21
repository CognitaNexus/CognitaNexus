# UC-13 VOPC（参与类类图）与关系依据

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-VOPC-17-hotspots.puml`](diagrams/DGM-VOPC-17-hotspots.puml)
**导出：** [SVG](diagrams/generated/DGM-VOPC-17-hotspots.svg) · [PNG](diagrams/generated/DGM-VOPC-17-hotspots.png)
**方法：** 教材 §5.4.4：VOPC 只来自本用例实现的顺序图对象，关系来自消息和业务约束；不是全局 BCE。

## 1. 参与类集合

VOPC 只包含以下 4 个类：

| 类 | 类型 | 来自顺序图对象 | 事件流依据 |
|---|---|---|---|
| `HotspotUI` | Boundary | M1、M18、M23（接收）；M16、M21、M27、M30、M32、M34、M36、M38（返回）；M17、M22、M28、M39（展示给访客） | E1、E12–E13、E15；A1–A4、A15–A19 |
| `HotspotCoordinator` | Control | M2、M19、M24（接收）；M3、M4、M7、M10、M11、M12、M13、M14、M15、M20、M29、M31、M33、M35、M37（自消息）；M5、M8、M25（发出只读请求） | E2–E11、E13–E14、E16；A1–A19 |
| `WorkEntity` | Entity | M5（接收，只读）；M6（应答） | E4、E14；A5–A11 |
| `TopicTerm` | Entity | M8、M25（接收，只读）；M9、M26（应答） | E6、E15；A9、A12、A13 |

参与者“访客”不进入 VOPC：参与者不是系统内部类（教材 §5.4.4）。“主题登记与术语映射维护方”“领域分类映射维护方”是外部前置，本 UC 无消息，也不进入 VOPC。

## 2. 关系与来源

| 关系 | 类型 | 来自消息/约束 | 方向/角色 | 多重性 | 说明 |
|---|---|---|---|---|---|
| `HotspotUI --> HotspotCoordinator` | 单向关联/消息关系 | M2/M19/M24 请求；M16/M21/M27/M30/M32/M34/M36/M38 返回 | UI 请求；Coordinator 返回结果 | 不定义持久多重性 | UI 无持久业务属性；按消息方向表达一次请求的协作 |
| `Coordinator ..> WorkEntity` | 单向**只读**依赖 | M5 读公开状态、数据类别、时间语义、领域取值与论文关键词事实 | 读取计数单位与筛选取值 | 不定义持久多重性 | 虚线表示只读：不写、不合并、不改关键词原值、不改公开状态 |
| `Coordinator ..> TopicTerm` | 单向**只读**依赖 | M8 读规范词/原始写法/映射凭证；M25 读某规范词的写法明细 | 读取规范词身份与凭证 | 不定义持久多重性 | 虚线表示只读：不新增主题登记、不新增或改映射、不改规范词 |
| `WorkEntity "0..*" --> "0..*" TopicTerm` | 业务关联 | FR13 明文“论文关键词/已登记主题”“同义词映射有凭证才合并”；CL-05；M5/M8/M25 | 论文关键词事实按有凭证映射归入规范词 | `0..*` : `0..*` | 一项论文成果的关键词事实可为零个（无关键词）或多个；一个规范词可对应零项（窗口内无命中）或多项成果；关联只表达“按已生效凭证映射得到的取值关系”，未映射关键词按规范化原写法单列、不构成到规范词的有效关联 |

### 2.1 为什么控制/边界到实体的关联不标持久多重性

`HotspotUI`/`HotspotCoordinator` 到实体的关联按**消息方向**表达协作，不是长期业务对象链接。为它们标 `1..*` 会把一次热点请求的协调关系误解为持久聚合。因此本 VOPC 只对业务事实之间的关联给出多重性：`WorkEntity—TopicTerm` 一条。

### 2.2 为什么 `WorkEntity—TopicTerm` 的两端都是 `0..*`

- **成果端为零个：** 论文可以没有关键词字段（SPEC §4.4：只有缺失摘要/关键词仍为有效成果），或关键词全部未映射到任何已登记主题（A9）。此时该成果不产生到规范词的有效关联。
- **成果端为多个：** 一篇论文可有关键词列表，其中多个写法在有凭证映射下归入不同规范词（M5/M8）。
- **规范词端为零项：** 已登记主题在选定领域/年份窗内可能没有任何命中（A2；`AC-IMPL-FR13-02` 稀疏语境）。
- **规范词端为多项：** 同一规范词可对应多篇成果的多次出现，这是计数（M11）而非多重性放大。
- **不是组合：** 规范词与凭证映射是跨论文共享的独立事实，不随某篇成果存亡变化，故不标组合菱形。
- **“同一论文同一规范词只计一次”是计数规则，不是多重性：** 关联集合会先按“论文×规范词”去重再计数（M11；BR-UC13-06）；把它写成 `1` 会与“关键词列表可含多个写法”“同一写法可重复出现”冲突。
- **无凭证不建边、不合并：** 无凭证或存疑的写法不成为同一规范词的实例，而是保留为不同条目并标注（A12）；措辞写入 05 §2.3 与 06 §5。

### 2.3 为什么若干事实写成属性而不是实体关联

| 事实 | 表达方式 | 理由 |
|---|---|---|
| 论文关键词原值（含来源字段与证据引用） | `WorkEntity.keywordFacts[]`（原写法／来源字段／evidenceRefs／缺失标记） | 关键词是论文的可选证据字段值（SPEC §4.1/§4.4）；若另建 `Keyword` 实体，会与成果字段重复表达同一事实，且顺序图无独立寻址消息（03 §4） |
| 规范词下的原始写法与出现次数 | `TopicTerm.variants[]`（原写法／规范化状态／出现次数） | 写法映射与出现次数是规范词自身可回溯的明细（M25/M26）；不另建“关键词—主题”连接实体 |
| 同义词映射凭证 | `TopicTerm.synonymEvidence[]`（凭证来源／适用范围／映射状态） | 凭证挂在**写法→规范词**的关系上，不由单篇成果承载（FR13 明文；B-01） |
| 领域取值 | `WorkEntity.fieldRefs[]` | 领域分类层级与映射未冻结（Q-SPEC-03；B-09），不能建 `Field`/`FieldTaxonomy` 类；只引用已生效分类结果并允许“待分类/未映射” |
| 时间语义 | `WorkEntity.occurringYear`＋`timeSemantics` | §7.1 要求时间语义分开；年份窗只按已声明的发生时间语义判断（A7） |
| 数据类别（真实/模拟/混合） | `WorkEntity.dataOrigin` | 成果公共字段（§7.1）；单个成果只有一个数据类别标识（BR-IMP-07 互斥） |
| 来源与版本追溯 | `WorkEntity.sourceRefs[]`、`versionRefs[]`（仅追溯，不计数） | 来源数与版本数不参与计数（BR-IMP-02；`AC-IMPL-FR16-02` 边界）；本 UC 无 `SourceRecord`/`WorkVersion` 消息，画边会暗示计数依赖 |
| 热点结果投影（条件/热点项/计数/名次/样本量/未纳入数/披露/更新时间） | `HotspotCoordinator` 的单次请求分析态 | 读时投影，不是业务事实；画成实体即为 C-01 所指的“持久热点结果”（05 §4） |
| 主题登记与领域映射的维护 | 外部前置（FR14/FR16 的已生效维护结果） | 本 UC 只读；画成依赖会把维护职责误并进热点协调者（BR-UC13-16） |

## 3. 从顺序图到 VOPC 的对应

| 顺序图对象 | VOPC 类 | 保留/删除理由 |
|---|---|---|
| 访客（Actor） | 不保留 | 参与者不是系统类；只发 M1/M18/M23 并接收返回 |
| HotspotUI | 保留 | 接收 M1/M18/M23；返回 M16/M21/M27/M30/M32/M34/M36/M38 |
| HotspotCoordinator | 保留 | 承担 M2–M16、M19–M39 的守卫、计数、排序与披露，并发出 M5/M8/M25 只读请求 |
| WorkEntity | 保留 | 接收 M5，应答 M6 |
| TopicTerm | 保留 | 接收 M8/M25，应答 M9/M26 |
| 无其他对象 | — | 没有 `SourceRecord`、`WorkVersion`、`Scholar`、`HotspotResult`、`Field`、`Keyword`、语义主题模型、趋势/评分类或 DTO 的消息 |

## 4. 排除项与理由

- `HotspotResult` / `TopicSnapshot` / `CaliberVersion`：热点结果是读时投影；持久结果对象需同时定义版本、生效状态、失效与回退，V2.2 未定义（C-01；B-12）。投影字段作为 `HotspotCoordinator` 的分析态，见 06 §3。
- `TopicModel` / `SemanticTopic` / `WordEmbedding` / `KeywordCluster`：FR13 明文不做语义主题模型；引入即违反基线且不可复核（A17）。
- `Synonyms` / `Thesaurus` / `StopwordDictionary`：只在有凭证的映射下合并；本 UC 无权新建词表（B-01；A12）。
- `TrendPoint` / `GrowthRate` / `Forecast`：FR13 明文不做趋势预测；模块细化 FR13-S05 未进入基线（C-02）。
- `QualityScore` / `ImpactIndex` / `AcademicLevel` / `ScholarHotspotRanking`：基线不做质量/影响力/个人排名（FR13 明文；SRS §2.1；A17）。
- `SourceRecord` / `WorkVersion`：来源数与版本数不参与计数（BR-IMP-02；`AC-IMPL-FR16-02`）；来源追溯属 UC-02/UC-16，无独立消息。
- `Scholar` / `User` / `UserProfile` / `Authorship`：热点不按学者维度计数，也不用作者画像推断主题（BR-UC13-15）。
- `Field` / `FieldTaxonomy` / `DomainDictionary`：领域层级与映射未冻结（Q-SPEC-03；C-7），只作筛选取值引用。
- `Keyword`（独立实体）：关键词属性化在 `WorkEntity.keywordFacts[]`（§2.3；03 §4）。
- `MetricDefinition` / `CaliberVersion`：口径由 FR13 与 BR-IMP 给出；模块细化的“指标定义/口径版本”未冻结（B-07/B-12），不建类。
- `HotspotJob` / `MetricsRefreshTask` / `CacheEntry` / 聚合引擎：属实现或模块细化的“结果生成”取向（C-01），V2.2 不要求。
- `AuditEvidence`：本 UC 无受保护写入；失败明细与审计查询属 FR14/UC-17。
- `Session` / `CredentialContext`：热点无需登录（NF01；§8）。
- DTO / 展示投影（`HotspotView`、`HotspotTable`、`ChartData`、`HotspotDTO`）：交互态，不是业务事实（教材 §5.4.2/§5.5.2）。

## 5. VOPC 完整性检查

| 检查 | 结果 |
|---|---|
| 只包含 UC-13 参与类 | 是，4 个 |
| 每个类有事件流/消息依据 | 是，见表 1/表 3 |
| 每条关系可追溯到消息或业务约束 | 是，见表 2 |
| 不把 Actor 画成系统类 | 是；访客不进入 VOPC |
| 不用全局 BCE 冒充 VOPC | 是；本图独立于 `DGM-CLASS-01-analysis.puml` |
| 关系多重性是否有依据 | 一条业务关联有依据（§2.2）；控制/边界到实体不定义持久多重性（§2.1） |
| 是否把 `WorkEntity` 与 `TopicTerm` 混为一类 | 否：关键词是成果字段事实，规范词与凭证映射是主题登记事实 |
| 是否引入语义主题模型、词向量或自建词典 | 否（§4） |
| 是否出现趋势/预测/质量评分/学者个人排名类 | 否（FR13 明文；§4） |
| 是否出现持久热点结果/快照/口径版本类 | 否（§4；C-01） |
| 是否出现写操作或删除边 | 否：两条实体依赖为只读虚线 |
| 是否引入设计技术（表/接口/队列/索引/缓存/DTO） | 否 |

## 6. 与全局图的关系

本 VOPC 是 UC-13 的局部静态视图，不是全系统类图。与 `DGM-CLASS-01-analysis.puml` 的异同、命名合并建议见 [07-与全局模型差异](07-global-model-diff.md)。R5 统一分析类时应以本 VOPC 为输入之一，而不是用全局图反推本 UC 的参与类。
