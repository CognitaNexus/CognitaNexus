# UC-12 VOPC（参与类类图）与关系依据

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-VOPC-16-institution-rank.puml`](diagrams/DGM-VOPC-16-institution-rank.puml)
**导出：** [SVG](diagrams/generated/DGM-VOPC-16-institution-rank.svg) · [PNG](diagrams/generated/DGM-VOPC-16-institution-rank.png)
**方法：** 教材 §5.4.4：VOPC 只来自本用例实现的顺序图对象，关系来自消息和业务约束；不是全局 BCE。

## 1. 参与类集合

VOPC 只包含以下 5 个类：

| 类 | 类型 | 来自顺序图对象 | 事件流依据 |
|---|---|---|---|
| `InstitutionRankingUI` | Boundary | M1、M21、M27、M31（接收）；M19、M25、M29、M33、M36、M38、M40、M42、M44、M46（返回） | E1、E11–E14；A1–A4、A13、A16–A19 |
| `InstitutionRankingCoordinator` | Control | M2、M22、M28、M32（接收）；M3、M6、M11、M14、M15、M16、M17、M18、M23、M24、M35、M37、M39、M41、M43、M45（自消息）；M4、M7、M9、M12（发出只读请求） | E2–E10、E12–E14；A1–A19 |
| `WorkEntity` | Entity | M4、M7（接收，只读）；M5、M8（应答） | E2、E4、E12；A7–A10、A12 |
| `Institution` | Entity | M12（接收，只读）；M13（应答） | E6；A9–A11 |
| `Authorship` | Entity | M9（接收，只读）；M10（应答） | E4；A18 |

参与者“访客”不进入 VOPC：参与者不是系统内部类（教材 §5.4.4）。“机构规范名与分类映射维护方”是外部前置，本 UC 无消息，也不进入 VOPC。

## 2. 关系与来源

| 关系 | 类型 | 来自消息/约束 | 方向/角色 | 多重性 | 说明 |
|---|---|---|---|---|---|
| `InstitutionRankingUI --> InstitutionRankingCoordinator` | 单向关联/消息关系 | M2/M22/M28/M32 请求；M19/M25/M29/M33/M36/M38/M40/M42/M44/M46 返回 | UI 请求；Coordinator 返回结果 | 不定义持久多重性 | UI 无持久业务属性；按消息方向表达一次请求的协作 |
| `Coordinator ..> WorkEntity` | 单向**只读**依赖 | M4 读共享字段与范围；M7 读发生时机构归属事实与证据 | 读取计数单位、共享字段与机构归属事实 | 不定义持久多重性 | 虚线表示只读：不写、不删除、不合并、不改公开状态 |
| `Coordinator ..> Institution` | 单向**只读**依赖 | M12 | 读取规范名/别名/合并链/未映射标记 | 不定义持久多重性 | 虚线表示只读：不新增机构、不改别名、不合并机构 |
| `Coordinator ..> Authorship` | 单向**只读**依赖 | M9 | 读取参与者角色/顺序/多机构署名原值 | 不定义持久多重性 | 虚线表示只读：不写关系状态；不按作者数计数 |
| `Authorship "0..*" --> "1" WorkEntity` | 业务关联 | ID-05/ID-11；§7.2；M9/M4 | 归属成果 | `0..*` : `1` | 一项成果可有多条参与关系；一条关系只属于一个成果（沿用 UC-02/UC-03/UC-10/UC-11） |
| `WorkEntity "0..*" --> "0..*" Institution` | 业务关联 | BR-IMP-06；BR-513；FR11-S08；M7/M12 | 成果发生时机构归属（有证据） | `0..*` : `0..*` | 一项成果可有零个（缺失/无证据→机构未知）或多个发生时机构；一个机构可对应多项成果 |

### 2.1 为什么控制/边界到实体的关联不标持久多重性

`InstitutionRankingUI`/`InstitutionRankingCoordinator` 到实体的关联按**消息方向**表达协作，不是长期业务对象链接。为它们标 `1..*` 会把一次排名请求的协调关系误解为持久聚合。因此本 VOPC 只对业务事实之间的关联给出多重性：`Authorship→WorkEntity`、`WorkEntity→Institution` 两条。

### 2.2 为什么 `WorkEntity—Institution` 的两端都是 `0..*`

- **零个：** BR-IMP-06 允许成果的机构归属“缺失/未知”并被单列；BR-513 要求缺失标为机构未知、不得用作者当前单位替代。因此“有证据的发生时机构”可以为 0 个。
- **多个：** `AC-IMPL-FR11-01` 的给定条件是“同成果跨源重复且涉及机构甲乙，甲乙各 +1”；BR-IMP-06 明确“成果发生时多机构各一次、单机构一次”。因此一项成果可关联多个机构。
- **不是组合：** 机构身份不随成果存亡变化（机构是独立事实），故不标组合菱形；机构合并链由 `Institution` 自身属性与已生效映射表达。
- **同一机构在同一成果上只计一次：** 该约束是**计数规则**（BR-IMP-06；BR-UC12-06），不是多重性——关联集合会先按规范机构去重再计数；把它写成 `1` 会与“多机构各一次”和真实数据（同一机构多写法）冲突。
- **未映射/未知不建边：** 无证据或未映射的归属不产生到具体 `Institution` 的有效归属，而是进入“机构未知”与未映射披露（BR-UC12-07/08）。

### 2.3 为什么若干事实写成属性而不是实体关联

| 事实 | 表达方式 | 理由 |
|---|---|---|
| 参与者级署名机构原值（多机构原值） | `Authorship.participantAffiliationRaw[]`＋`occurringDate`＋`evidenceRefs` | 逐参与者、逐来源的原始署名事实（ID-05）；若另画 `Authorship→Institution` 边，会与 `WorkEntity→Institution` 重复表达同一归属结果，且顺序图没有该消息（M9 一次读取后由协调器归并） |
| 成果级“发生时机构归属事实” | `WorkEntity.occurredAffiliationFacts[]`（原值/规范引用/发生时语义/证据引用/未映射标记） | 这是“成果 × 机构”的取值集合，是 `WorkEntity—Institution` 关联的属性化证据；不另设 `InstitutionAffiliation` 实体（03 §4） |
| 机构别名与合并链 | `Institution.aliases[]`、`Institution.mergedIntoRef` | 合并链是机构身份的历史事实；画成自关联会把一次归并解释为持久结构关系，而顺序图只有 M12 一次读取 |
| 领域取值 | `WorkEntity.fieldRefs[]` | 领域分类层级与映射未冻结（Q-SPEC-03；C-7），不能建 `Field`/`FieldTaxonomy` 类；只引用已生效分类结果并用于筛选 |
| 数据性质（真实/模拟/混合） | `WorkEntity.dataOrigin` | 成果共享字段（§7.1）；单个成果只有一个数据性质标识（BR-IMP-07 互斥） |
| 来源与版本追溯 | `WorkEntity.sourceRefs[]`、`versionRefs[]`（仅追溯，不计数） | 来源数与版本数不参与计数（BR-IMP-02/06）；本 UC 无 `SourceRecord`/`WorkVersion` 消息，画边会暗示计数依赖 |
| 排名视图投影（条件/计数/名次/覆盖率/未知数/未映射数/声明/更新时间） | `InstitutionRankingCoordinator` 的单次请求分析态 | 读时投影，不是业务事实；画成实体即为 C-01 所指的“持久排名结果”（05 §4） |
| 机构类别体系 | 外部前置（FR14/FR16 的已生效维护结果）；未冻结时不提供维度 | Q-SPEC-03 未冻结；画边会把未定义对象写成依赖（A13/B-02） |
| 作者当前任职（学者任职期） | **不表达**：本 UC 明确不使用 | 机构归属只取有证据的成果发生时机构（BR-513；SR-06；T35）；画 `Scholar—Institution` 边会给出“可用任职期补全归属”的错误暗示 |

## 3. 从顺序图到 VOPC 的对应

| 顺序图对象 | VOPC 类 | 保留/删除理由 |
|---|---|---|
| 访客（Actor） | 不保留 | 参与者不是系统类；只发 M1/M21/M27/M31 并接收返回 |
| InstitutionRankingUI | 保留 | 接收 M1/M21/M27/M31；返回 M19/M25/M29/M33/M36/M38/M40/M42/M44/M46 |
| InstitutionRankingCoordinator | 保留 | 承担 M2–M19、M22–M46 的全部守卫、计数、名次与声明，并发出 M4/M7/M9/M12 只读请求 |
| WorkEntity | 保留 | 接收 M4/M7，应答 M5/M8 |
| Institution | 保留 | 接收 M12，应答 M13 |
| Authorship | 保留 | 接收 M9，应答 M10 |
| 无其他对象 | — | 没有 `SourceRecord`、`WorkVersion`、`Scholar`、`RankingResult`、`InstitutionCategory`、DTO 或 `AuditEvidence` 的消息 |

## 4. 排除项与理由

- `RankingResult` / `InstitutionRankingSnapshot` / `CaliberVersion`：排名视图是读时投影；持久结果对象需同时定义版本、生效状态、失效与回退，V2.2 未定义（C-01；B-12）。投影字段作为 `InstitutionRankingCoordinator` 的分析态，见 06 §3。
- `SourceRecord` / `WorkVersion`：来源数与版本数不参与计数（BR-IMP-02/06；`AC-IMPL-FR16-02`）；来源追溯属 UC-02/UC-16，无独立消息。
- `Scholar` / `User` / `UserProfile` / `AffiliationPeriod`：机构排名不按学者维度；机构归属不得由作者当前单位推断（BR-513；SR-06；T35），故也不建 `Scholar—Institution` 边；个人综合学术排名基线不做（SRS §2.1）。
- `Field` / `FieldTaxonomy` / `DomainDictionary`：领域层级与映射未冻结（Q-SPEC-03；C-7），只作筛选取值引用。
- `InstitutionCategory` / `InstitutionClass`：机构类别体系未冻结（FR11-S05；B-02；A13）。
- `QualityScore` / `ImpactIndex` / `InstitutionRating` / `ScholarRanking`：基线不做质量/影响力/评级/个人排名（BR-522；SRS §2.1）。
- `InstitutionAffiliation`（独立实体）：归属事实属性化（05 §2.3；03 §4）。
- `RankingJob` / `MetricsRefreshTask` / `CacheEntry` / 聚合引擎：属实现或模块细化的“结果生成”取向（C-01），V2.2 不要求。
- `AuditEvidence`：本 UC 无受保护写入；排名失败明细与审计查询属 FR14/UC-17。
- `Session` / `CredentialContext`：机构排名无需登录（NF01；BR-526）。
- DTO / 展示投影（`RankingView`、`RankingTable`、`RankingDTO`）：交互态，不是业务事实（教材 §5.4.2/§5.5.2）。

## 5. VOPC 完整性检查

| 检查 | 结果 |
|---|---|
| 只包含 UC-12 参与类 | 是，5 个 |
| 每个类有事件流/消息依据 | 是，见表 1/表 3 |
| 每条关系可追溯到消息或业务约束 | 是，见表 2 |
| 不把 Actor 画成系统类 | 是；访客不进入 VOPC |
| 不用全局 BCE 冒充 VOPC | 是；本图独立于 `DGM-CLASS-01-analysis.puml` |
| 关系多重性是否有依据 | 两条业务关联有依据（§2.2）；控制/边界到实体不定义持久多重性（§2.1） |
| 是否把 `WorkEntity` 与 `Authorship` 混为一类 | 否：成果实体是计数单位，关系是参与事实且不计数 |
| 是否把 `Institution` 与“作者当前任职”混为一类 | 否：机构身份只表达规范名/别名/合并链；成果归属取发生时证据 |
| 是否画了 `Scholar—Institution` 或任职期关联 | 否（有意不画，§2.3） |
| 是否把“机构归属事实”画成第三个重复实体 | 否：属性化＋一条关联（§2.3） |
| 是否出现持久排名结果/快照/口径版本类 | 否（§4；C-01） |
| 是否出现质量评分、机构评级或影响力排序类 | 否（BR-522） |
| 是否出现写操作或删除边 | 否：三条实体关联全为只读依赖（虚线） |
| 是否引入设计技术（表/接口/队列/索引/缓存/DTO） | 否 |

## 6. 与全局图的关系

本 VOPC 是 UC-12 的局部静态视图，不是全系统类图。与 `DGM-CLASS-01-analysis.puml` 的异同、命名合并建议见 [07-与全局模型差异](07-global-model-diff.md)。R5 统一分析类时应以本 VOPC 为输入之一，而不是用全局图反推本 UC 的参与类。
