# UC-15 VOPC（参与类类图）与关系依据

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-VOPC-19-shortest-path.puml`](diagrams/DGM-VOPC-19-shortest-path.puml)
**导出：** [SVG](diagrams/generated/DGM-VOPC-19-shortest-path.svg) · [PNG](diagrams/generated/DGM-VOPC-19-shortest-path.png)
**方法：** 教材 §5.4.4：VOPC 只来自本用例实现的顺序图对象，关系来自消息和业务约束；不是全局 BCE。

## 1. 参与类集合

VOPC 只包含以下 7 个类：

| 类 | 类型 | 来自顺序图对象 | 事件流依据 |
|---|---|---|---|
| `CollaborationNetworkUI` | Boundary | M1、M40（接收）；M38、M44、M47、M50、M52（返回）；M39、M45、M48、M53（展示给访客） | E1、E17–E19；A1–A7、A13、A18–A20 |
| `CollaborationPathCoordinator` | Control | M2、M41（接收）；M3–M5、M8、M11、M14、M15、M18、M19、M22、M25、M29、M37、M46、M49、M51（自消息）；M6、M9、M12、M16、M20、M23、M26、M28、M31、M33、M35、M42（发出请求） | E2–E18、E20；A1–A20 |
| `Scholar` | Entity | M12、M23、M42（接收，只读）；M13、M24、M43（应答） | E9–E10、E14、E19；A3–A6、A15 |
| `WorkEntity` | Entity | M6、M33（接收，只读）；M7、M34（应答） | E5–E6、E11–E12、E16；A8、A14、A16、A17 |
| `Authorship` | Entity | M9（接收，只读）；M10（应答） | E7–E8、E11–E12；A9、A10、A16 |
| `CollaborationEdge` | Entity（派生／论文子图投影） | M16、M20（接收，本次查询内构建）；M26、M31（接收，只读）；M17、M21、M27、M32（应答） | E11–E16；A8、A10、A13、A16、A17 |
| `PathResult` | Entity（读时投影） | M28、M35（接收，本次查询内构建）；M30、M36（应答） | E14–E17；A6、A7、A11–A13、A18、A19 |

参与者“访客”不进入 VOPC：参与者不是系统内部类（教材 §5.4.4）。身份与归属治理方、成果治理与来源方、机构/领域分类维护方是外部前置，本 UC 无消息，也不进入 VOPC；UC-14 是上游用例，不是参与者。

## 2. 关系与来源

| 关系 | 类型 | 来自消息/约束 | 方向/角色 | 多重性 | 说明 |
|---|---|---|---|---|---|
| `CollaborationNetworkUI --> CollaborationPathCoordinator` | 单向关联/消息关系 | M2/M41 请求；M38/M44/M47/M50/M52 返回 | UI 请求；Coordinator 返回结果 | 不定义持久多重性 | UI 无持久业务属性；按消息方向表达一次请求的协作 |
| `Coordinator ..> WorkEntity` | 单向**只读**依赖 | M6 读论文身份、公开生效状态、数据类别、发生时间、发生时领域分类与映射版本；M33 读证据公开字段 | 读取论文资格、筛选口径与证据公开字段 | 不定义持久多重性 | 虚线表示只读：不改成果字段、数据类别、发生时间、领域分类与版本关系 |
| `Coordinator ..> Authorship` | 单向**只读**依赖 | M9 读论文作者角色、顺序、关系状态、公开性、角色证据、发生时机构证据 | 读取参与关系资格与筛选证据 | 不定义持久多重性 | 虚线表示只读：不改关系状态、不新增参与关系 |
| `Coordinator ..> Scholar` | 单向**只读**依赖 | M12 读作者 canonical 身份；M23 读端点身份；M42 读导航目标身份与合并指向 | 读取节点/端点身份与合并解析 | 不定义持久多重性 | 虚线表示只读：不合并身份、不改公开范围 |
| `Coordinator ..> CollaborationEdge` | 单向依赖（本次查询内构建，其余只读） | M16/M20 建投影与重建；M26/M31 只读读取邻接与逐跳证据 | 构建与读取论文边投影 | 不定义持久多重性 | 边是本次查询的派生投影；不把它当独立事实源持久保存（§4） |
| `Coordinator ..> PathResult` | 单向依赖（本次查询内构建，其余只读） | M28 登记结果身份；M35 绑定逐跳证据 | 构建与校验路径结果 | 不定义持久多重性 | 结果是读时投影，随查询结束失效（§4） |
| `CollaborationEdge "0..*" --> "2" Scholar` | 业务关联 | IN01-R1“两名已消歧学者”；M14–M16；BR-IMP-08；SPEC §2 | 边→两端节点 | `0..*` : `2` | 每条论文边恰有两个**不同**学者端点（无序对，自环不生成）；一个学者可参与多条边 |
| `CollaborationEdge "0..*" --> "1..*" WorkEntity` | 业务关联 | IN01-R3“每一跳至少一篇可点击论文”；BR-518 细化；M18/M31；SPEC §3 | 边→合格共同论文证据 | `0..*` : `1..*` | 每条边至少一篇合格共同**论文**；专利/项目/获奖不成证据；一篇论文可为多对学者提供证据 |
| `PathResult "0..*" --> "1..*" Scholar` | 业务关联 | IN01-R2；`AC-IMPL-IN01-01`；M25/M28–M30；SPEC §4 | 路径→有序节点序列 | `0..*` : `1..*` | 不同起点/终点时节点数≥2（跳数≥1）；端点相同时按 SPEC §4 为 0 跳单节点（是否允许见 C-05）；一个学者可出现在多条路径中 |
| `PathResult "0..*" --> "0..*" CollaborationEdge` | 业务关联 | IN01-R3；`AC-IMPL-IN01-01`；M30–M32；SPEC §4 | 路径→逐跳边 | `0..*` : `0..*` | 跳数＝边数＝节点数−1；0 跳时无逐跳边；一条边可属于多条路径 |
| `Authorship "0..*" --> "1" WorkEntity` | 业务关联 | 沿用 UC-02/UC-14；M9/M11 | 一条论文参与关系属于一篇论文 | `0..*` : `1` | 参与关系不是成果的一部分：解除一位作者保留论文与其他合格关系（INV-05；§7.2） |
| `Authorship "0..*" --> "0..1" Scholar` | 业务关联 | M9–M11；FR15；BR-IMP-03；SPEC §1 | 参与关系指向确定的参与者身份 | `0..*` : `0..1` | 身份未定/待核验的关系可以只有署名原值而无确定学者；这类关系不进入论文子图（B-05） |

### 2.1 为什么控制/边界到实体的关联不标持久多重性

`CollaborationNetworkUI`/`CollaborationPathCoordinator` 到实体/派生对象的关联按**消息方向**表达协作，不是长期业务对象链接。为它们标 `1..*` 会把一次查询的协调关系误解为持久聚合。因此本 VOPC 只对业务事实之间的六条业务关联给出多重性（§2 后六行）。

### 2.2 为什么 `CollaborationEdge` 的一端是 `2`、另一端是 `1..*`

**学者端 `2`：**

- 边是**两名不同学者**的无序对；自环（合并后同一人）不生成边（IN01-R1；SPEC §2；BR-530）。
- 端点用 min/max `canonical_id` 组成唯一边键，故两端身份必须是确定的 `canonical_id`，不能是姓名或账号（SPEC §2）。
- 一个学者可出现在多条边上（边数为 `0..*`），所以只在对端标 `2`。
- **不是组合：** 学者独立存在，边不“拥有”学者。

**论文端 `1..*`：**

- 每条边**至少一篇**合格共同论文证据，否则该边不属于论文子图、也不能成为路径跳（IN01-R1/R3；BR-518 细化；SPEC §3）。
- 一篇论文可同时为多对学者提供证据（多作者论文），故论文端为 `0..*`。
- **不是组合：** 论文独立存在（解除一条作者关系不删除论文与其他合格关系，INV-05；§7.2）。

### 2.3 为什么 `PathResult` 的两条关联是 `1..*` 与 `0..*`

- **节点序列 `1..*`：** 路径是有序学者序列；不同端点时至少 2 个节点（1 跳以上）；端点相同时按 SPEC §4 为 0 跳单节点（是否允许见 C-05）。取 `1..*` 而非 `2..*`，是为了不在本包替业务决定“端点相同能否查询”。
- **逐跳边 `0..*`：** 跳数＝边数＝节点数−1；0 跳时没有逐跳边；一条边可属于多条路径。
- **不把逐跳边写成组合：** 边独立于路径存在（删掉一次查询不删除边），路径只是引用同一图版本上的边。

### 2.4 为什么若干事实写成属性而不是实体关联

| 事实 | 表达方式 | 理由 |
|---|---|---|
| 论文类型/公开生效状态/数据类别/发生时间 | `WorkEntity.workType`、`publicState`、`dataOrigin`、`occurringTime` | 都是成果公共字段（§7.1）；顺序图无独立寻址消息；这些字段只决定“是否进入论文子图”，不改变边的无向唯一性 |
| 发生时领域分类与映射版本 | `WorkEntity.fieldClassification`、`classificationMappingVersion` | 领域筛选用“发生时适用、已核验的分类及映射版本”（SPEC §3）；映射维护属管理侧（FR14/FR16），本 UC 只读引用，不建映射实体 |
| 论文作者角色与顺序 | `Authorship.roleKind`、`roleOrder` | 角色属于**关系**而不是成果或学者；单独建 `Role` 类会与 Authorship 重复表达同一事实 |
| 关系状态（自动关联/本人确认/待核验/争议/已排除） | `Authorship.relationState` | 状态与角色可分离判定（§11.3；`AC-IMPL-FR15-04`）；本 UC 只读过滤 |
| 角色证据引用与公开性 | `Authorship.roleEvidenceRefs`、`evidencePublic` | 证据挂在“学者—成果”关系上（IN01-R1“经确认可用于论文合作的作者关系”） |
| 成果发生时机构证据 | `Authorship.occurredInstitutionRef`（含发生期标识） | 机构筛选要求“边的两端在该论文上均须命中”，这是**关系级**证据；用成果级单值会丢失“同论文不同作者不同机构”的情形（SPEC §3；§11.5） |
| 署名原值（身份未定时） | `Authorship.participantDisplay` | 未解析到 Scholar 时不建边，故保留为关系上的显示值（沿用 UC-02） |
| 学者 canonical 身份与合并指向 | `Scholar.canonicalId`、`mergedIntoRef`、`publicState` | 合并链与公开状态是学者自身事实（SPEC §2；BR-530）；不另建“合并对象”类 |
| 边键、论文证据集合与 k | `CollaborationEdge.edgeKey`、`paperEvidenceWorkRefs[]`、`paperEvidenceCount`（k） | k 是**二元关系上的聚合**，不是节点属性；证据按论文 `canonical_id` 升序保存以保证可复核（BR-518 细化；SPEC §3）。若另建“证据连接实体”，会与 `Edge–Work` 关联重复 |
| 路径节点序列、跳数、逐跳引用、逐跳证据、图版本 | `PathResult.nodeSequence[]`、`hopCount`、`hopEdgeRefs[]`、`hopEvidenceCounts[]`、`graphVersionRef`、`tieBreakRule` | 这些是**一次查询结果**的组成部分，必须可逐跳寻址并按图版本复核（`AC-IMPL-IN01-01/02`）；单建 `Hop` 类会与 `PathResult→Edge` 关联重复（03 §4） |
| 筛选条件（领域/机构取值）、端点、规模信息、披露块 | `CollaborationPathCoordinator` 的单次查询分析态 | 条件是请求参数、披露是投影，不是业务事实；画成实体即等于建“结果对象/图快照”（§4；C-01） |
| 来源记录与版本 | `WorkEntity.sourceRefs[]`、`versionRefs[]`（仅追溯，不参与 k） | 来源数与版本数不放大 k（`AC-IMPL-FR12-02`；`AC-IMPL-FR16-02`）；本 UC 无 `SourceRecord`/`WorkVersion` 消息，画边会暗示 k 依赖来源/版本 |
| 机构与领域实体 | **不表达为类**（只作为条件与证据引用） | FR12 明文“仅同机构/同领域不建边”，IN01-R3 不推断；为它们建类会暗示可建边或触发合并（03 §4；07 §4.4；B-02） |

## 3. 从顺序图到 VOPC 的对应

| 顺序图对象 | VOPC 类 | 保留/删除理由 |
|---|---|---|
| 访客（Actor） | 不保留 | 参与者不是系统类；只发 M1/M40 并接收 M39/M45/M48/M53 |
| CollaborationNetworkUI | 保留 | 接收 M1/M40；返回 M38/M44/M47/M50/M52 |
| CollaborationPathCoordinator | 保留 | 承担 M2–M52 的守卫、筛选、路径与披露，并发出 M6/M9/M12/M16/M20/M23/M26/M28/M31/M33/M35/M42 |
| Scholar | 保留 | 接收 M12/M23/M42，应答 M13/M24/M43 |
| WorkEntity | 保留 | 接收 M6/M33，应答 M7/M34 |
| Authorship | 保留 | 接收 M9，应答 M10 |
| CollaborationEdge | 保留（派生投影） | 接收 M16/M20/M26/M31，应答 M17/M21/M27/M32 |
| PathResult | 保留（读时投影） | 接收 M28/M35，应答 M30/M36 |
| 无其他对象 | — | 没有 `Institution`、`Field`、`UserProfile`、`SourceRecord`、`WorkVersion`、`GraphSnapshot`、`BfsQueue`、`PathDTO` 或质量评分对象的消息 |

## 4. 排除项与理由

- `GraphSnapshot` / `GraphVersion`（作为持久对象）：本 UC 取“查询内固定图版本＋读时投影”取向；图版本以 `PathResult.graphVersionRef` 与分析态标识承载，并在披露中给出。持久快照需定义生成时机、标识规则、失效与回退，V2.2 未定义（`AC-IMPL-IN01-02`；SPEC §2；B-07/B-08；C-01；07 §5）。
- `NetworkResult` / `FilteredNetworkResult` / `PathState`（结果对象与状态机）：无路径/超规模/失效在本 UC 是**可观察分支与披露**，不是可转移的持久状态；BR-523 细化与 `DGM-STATE-03` 的结果对象取向登记为 C-01。
- `BfsFrontier` / `DistanceMap` / `Queue` / `VisitedSet`：实现层算法数据结构，不是业务分析类（SPEC §4；教材 §5.4.2）。
- `Hop` / `PathHop`：跳由 `PathResult` 的有序结构与 `PathResult→CollaborationEdge` 关联表达（§2.4），单建类会重复同一事实。
- `PathQuery` / `EndpointPair`：条件与端点是请求态（`CollaborationPathCoordinator`），不是业务事实。
- `Institution` / `Field` / `ClassificationMapping`：FR12 明文“仅同机构/同领域不建边”，IN01-R3 不推断；机构别名归并与分类映射维护属管理侧（FR14/FR16；Q-4）。本 UC 只把机构/领域作为**筛选条件**与**发生时证据引用**（§2.4；B-02）。
- `User` / `UserProfile` / `Account`：端点只用 Scholar `canonicalId`；普通主页资料与账号不是端点标识也不是合作证据（§12；`01-BCE` §5）。
- `SourceRecord` / `WorkVersion`：来源数与版本数不放大 k（`AC-IMPL-FR12-02`；`AC-IMPL-FR16-02`）。
- `DedupCandidate` / `CleanRecord`：去重/清洗属 UC-09；本 UC 只消费去重结果（BR-IMP-02）。
- `IdentityCandidate` / `MergedIdentity` / `NameCluster`：身份合并与候选属 FR15/UC-10；本 UC 只解析已生效 `canonicalId` 与合并指向（A5/A15）。
- `QualityScore` / `ImpactIndex` / `ScholarRanking` / `PathLengthLeaderboard`：基线不做质量/影响力/个人排名；路径长度只表示合作边数（BR-522 细化；SRS §2.1）。
- `StrengthThreshold` / `MinPaperEvidenceFilter`：基线未定义按强度/k 过滤或排序（BR-518 细化；UC-14 B-10 同源）。
- `AuditEvidence`：本 UC 无受保护写入；失败明细与审计查询属 FR14/UC-17。
- `Session` / `CredentialContext`：无需登录（NF01；§8）。
- DTO / 展示投影（`PathView`、`GraphLayout`、`PathTable`、`PathDTO`、`ChartData`）：交互态，不是业务事实（教材 §5.4.2/§5.5.2）。
- `GraphIndex` / `GraphCache` / `PathComputeJob` / `ShortestPathEngine`：属实现阶段或模块细化的“重算/结果生成”取向（C-01），V2.2 不要求。
- `RecommendationProfile` / `BehaviorLog`：O01/O02 延期，且不得成为 IN01 依赖（BR-IMP-12）。
- `Notification` / `SlaTimer`：通知与传播时限未定义；未更新/超时只做页面提示（NF05；B-10）。

### 4.1 `CollaborationEdge` 为什么可以保留为派生投影，而不是并入协调者

- **承载业务关系而非交互状态：** “两名学者之间存在合格共同论文”是 IN01-R1/R3 明文中的业务概念，有独立身份（学者对）、独立事实（论文证据集合）与可观察值（k）。
- **必须可被逐跳检视：** M31/M32 以“一条边”为寻址单位取该跳论文证据；若把边压成协调者内部集合，`AC-IMPL-IN01-01`（每跳证据）与 BR-518（k 与 weight 分别声明）失去承载者。
- **不因此成为事实源：** 边的每一项事实都可由“合格论文＋合格作者关系＋canonical 身份＋筛选条件”重建；本 UC 全程无写消息。**派生 ≠ 无业务含义**，也**≠ 持久化结果对象**（C-01）。

### 4.2 `PathResult` 为什么可以保留为 Entity，而不是只做返回值

- 它是**有结构、可校验的业务值**：节点序列有序、跳数＝边数、每跳证据非空、图版本固定、等长规则可说明。这些约束都需要一个可被逐跳与整体校验的载体（`AC-IMPL-IN01-01/02`；BR-518 细化）。
- 它不是持久对象：`PathResult` 的每项事实都可由同一图版本与筛选条件重新推导，查询结束即失效；重复查询返回同一条是**规则保证**（BR-UC15-08/09），不是缓存或保存。
- 若并入 `CollaborationEdge`，会丢失“有序节点序列”和“跳数＝边数”的整体约束；若并入 `Scholar`，会丢失“每跳对应哪条边与哪些论文证据”的对应关系。

## 5. VOPC 完整性检查

| 检查 | 结果 |
|---|---|
| 只包含 UC-15 参与类 | 是，7 个 |
| 每个类有事件流/消息依据 | 是，见表 1/表 3 |
| 每条关系可追溯到消息或业务约束 | 是，见表 2 |
| 不把 Actor 画成系统类 | 是；访客不进入 VOPC |
| 不用全局 BCE 冒充 VOPC | 是；本图独立于 `DGM-CLASS-01-analysis.puml` |
| 关系多重性是否有依据 | 六条业务关联均有多重性理由（§2.2/§2.3）；控制/边界到实体不定义持久多重性（§2.1） |
| 是否把 `WorkEntity` 与 `Authorship` 混为一类 | 否：论文是共享证据单位，参与关系携带角色/状态/发生时机构证据 |
| 是否把 `Scholar` 与 `User`/`UserProfile` 混为一类 | 否：端点只用 `canonicalId` |
| 是否出现专利/项目/获奖证据边 | 否：`Edge→Work` 只含论文，M15/M18 守卫排除非论文证据 |
| 是否出现同机构/同领域推断的边 | 否：无 `Edge→Institution`、`Edge→Field`；机构/领域只作条件与发生时证据 |
| 是否出现持久路径结果/图快照/状态机 | 否（§4；C-01；B-08） |
| 是否出现 BFS 数据结构/图索引/缓存/DTO | 否（§4） |
| 是否出现质量/影响力/排名/路径排行类 | 否（BR-522 语境；§4） |
| 是否出现写操作或删除边 | 否：四条实体依赖为只读虚线；`CollaborationEdge`/`PathResult` 只在本次查询内构建 |

## 6. 与全局图的关系

本 VOPC 是 UC-15 的局部静态视图，不是全系统类图。与 `DGM-CLASS-01-analysis.puml`、`01-BCE分析类模型.md` 的异同、命名合并建议与冲突见 [07-与全局模型差异](07-global-model-diff.md)。R5 统一分析类时应以本 VOPC 为输入之一，而不是用全局图反推本 UC 的参与类。
