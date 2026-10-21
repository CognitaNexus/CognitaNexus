# UC-14 VOPC（参与类类图）与关系依据

**版本：** V1.0（2026-10-21）
**图源：** [`DGM-VOPC-18-collaboration-network.puml`](diagrams/DGM-VOPC-18-collaboration-network.puml)
**导出：** [SVG](diagrams/generated/DGM-VOPC-18-collaboration-network.svg) · [PNG](diagrams/generated/DGM-VOPC-18-collaboration-network.png)
**方法：** 教材 §5.4.4：VOPC 只来自本用例实现的顺序图对象，关系来自消息和业务约束；不是全局 BCE。

## 1. 参与类集合

VOPC 只包含以下 6 个类：

| 类 | 类型 | 来自顺序图对象 | 事件流依据 |
|---|---|---|---|
| `CollaborationNetworkUI` | Boundary | M1、M23、M31、M37、M43（接收）；M21、M29、M35、M41、M47、M50、M52（返回）；M22、M30、M36、M42、M48、M53（展示给访客） | E1、E14–E18；A1–A3、A17、A20 |
| `CollaborationNetworkCoordinator` | Control | M2、M24、M32、M38、M44（接收）；M3、M4、M7、M10、M13、M14、M17、M18、M19、M20、M45、M46、M49、M51（自消息）；M5、M8、M11、M15、M25、M27、M33、M39（发出请求） | E2–E13、E19–E20；A1–A19 |
| `WorkEntity` | Entity | M5、M27（接收，只读）；M6、M28（应答） | E4–E5、E15；A5–A7、A12、A13、A16 |
| `Authorship` | Entity | M8（接收，只读）；M9（应答） | E6–E7；A8/A9/A11/A14/A19 |
| `Scholar` | Entity | M11、M39（接收，只读）；M12、M40（应答） | E8–E9、E17；A11/A15/A20 |
| `CollaborationEdge` | Entity（派生，只读） | M15（接收建边/加入证据）；M16（应答）；M25、M33（接收只读）；M26、M34（应答） | E10–E12、E15–E16；A12–A16 |

参与者“访客”不进入 VOPC：参与者不是系统内部类（教材 §5.4.4）。成果治理方、身份与归属治理方、来源维护方是外部前置，本 UC 无消息，也不进入 VOPC；UC-15/IN01 是下游用例，不是参与者。

## 2. 关系与来源

| 关系 | 类型 | 来自消息/约束 | 方向/角色 | 多重性 | 说明 |
|---|---|---|---|---|---|
| `CollaborationNetworkUI --> CollaborationNetworkCoordinator` | 单向关联/消息关系 | M2/M24/M32/M38/M44 请求；M21/M29/M35/M41/M47/M50/M52 返回 | UI 请求；Coordinator 返回结果 | 不定义持久多重性 | UI 无持久业务属性；按消息方向表达一次请求的协作 |
| `Coordinator ..> WorkEntity` | 单向**只读**依赖 | M5 读成果身份/类型/公开生效状态/数据类别/发生时间；M27 读证据成果公开字段 | 读取成果资格与证据公开字段 | 不定义持久多重性 | 虚线表示只读：不改成果字段、数据类别、公开状态与版本 |
| `Coordinator ..> Authorship` | 单向**只读**依赖 | M8 读参与角色、顺序、关系状态与角色证据 | 读取参与关系资格 | 不定义持久多重性 | 虚线表示只读：不改关系状态、不新增参与关系 |
| `Coordinator ..> Scholar` | 单向**只读**依赖 | M11 读 canonical 身份与公开状态；M39 读导航目标的当前身份与合并指向 | 读取节点身份与合并解析 | 不定义持久多重性 | 虚线表示只读：不合并身份、不改公开范围 |
| `Coordinator ..> CollaborationEdge` | 单向依赖（本次请求内构建、其余只读） | M15 建边/加入证据；M25/M33 只读读取证据与强度 | 构建与读取派生边 | 不定义持久多重性 | 边是本次请求的派生对象；不把它当独立事实源持久保存（§4） |
| `CollaborationEdge "0..*" --> "2" Scholar` | 业务关联 | FR12 明文“两名不同学者之间的无向边”；M13–M16；BR-IMP-08；SPEC §2 | 边→两端节点 | `0..*` : `2` | 每条边恰有两个**不同**学者端点（无序对，自环不生成）；一个学者可参与多条边 |
| `CollaborationEdge "0..*" --> "1..*" WorkEntity` | 业务关联 | FR12 明文“边可追溯至对应成果”；BR-IMP-08；M17/M18；AC-IMPL-FR12-01/02/03 | 边→共同合格成果证据 | `0..*` : `1..*` | 每条边至少一项合格共同成果；一项成果可为多对学者提供证据（多作者成果） |
| `Authorship "0..*" --> "1" WorkEntity` | 业务关联 | 沿用 UC-02/UC-09 已定义；M8/M10 | 一条参与关系属于一项成果 | `0..*` : `1` | 参与关系不是成果的一部分：解除一位参与者不删除成果与其他合格关系（INV-05；§6.4） |
| `Authorship "0..*" --> "0..1" Scholar` | 业务关联 | M8/M10/M11；FR15；BR-IMP-03 | 参与关系指向确定的参与者身份 | `0..*` : `0..1` | 身份未定/待核验的关系可以只有署名原值而无确定学者；这类关系不参与建边（B-06） |

### 2.1 为什么控制/边界到实体的关联不标持久多重性

`CollaborationNetworkUI`/`CollaborationNetworkCoordinator` 到实体/派生边的关联按**消息方向**表达协作，不是长期业务对象链接。为它们标 `1..*` 会把一次网络请求的协调关系误解为持久聚合。因此本 VOPC 只对业务事实之间的四条业务关联给出多重性（§2 后四行）。

### 2.2 为什么 `CollaborationEdge` 的两端是 `2` 与 `1..*`

**学者端 `2`：**

- 边是**两名不同学者**的无序对；自环（合并后同一人）不生成边（FR12 明文；SPEC §2；BR-530）。
- 端点用 min/max `canonical_id` 组成唯一边键，故两端身份必须是确定的 `canonical_id`，不能是姓名或账号（SPEC §2）。
- 一个学者可出现在多条边上（边数为 `0..*`），所以只在对端标 `2`，不在学者端标 `1`。
- **不是组合：** 学者独立存在（无合作也存在于学者档案中），边不“拥有”学者；因此不用实心菱形。

**成果端 `1..*`：**

- 每条边**至少一项**合格共同成果证据，否则该边不成立（BR-IMP-08；AC-IMPL-FR12-01/02/03）。
- 一项成果可同时为多对学者提供证据（例如三位以上作者），故成果端为 `0..*`（成果本身不要求一定参与某条边）。
- **不是组合：** 成果独立存在（解除一位参与关系不删除成果与其他合法关系，INV-05；§6.4）。

### 2.3 为什么若干事实写成属性而不是实体关联

| 事实 | 表达方式 | 理由 |
|---|---|---|
| 成果类型/公开生效状态/数据类别/发生时间 | `WorkEntity.workType`、`publicState`、`dataOrigin`、`occurringTime` | 都是成果公共字段（§7.1）；顺序图无独立寻址消息；类型只影响“是否合格”与证据标注，不改变边的无向唯一性 |
| 参与角色与顺序（四类） | `Authorship.role`、`order` | 角色属于**关系**而不是成果或学者（FR15；§7.1；SPEC §1）；单独建 `Role` 类会与 Authorship 重复表达同一事实 |
| 关系状态（自动关联/本人确认/待核验/争议/已排除） | `Authorship.relationState` | 状态与角色可分离判定（§11.3；AC-IMPL-FR15-04）；本 UC 只读过滤 |
| 角色证据引用与公开性 | `Authorship.roleEvidenceRefs`、`evidencePublic` | 证据挂在“学者—成果”关系上，不由成果或学者承载（FR12 明文“已核验参与关系”） |
| 署名原值（身份未定时） | `Authorship.participantDisplay` | 未解析到 Scholar 时不建边，故保留为关系上的显示值，不建“署名实体”（沿用 UC-02） |
| 学者 canonical 身份与合并指向 | `Scholar.canonicalId`、`mergedIntoRef`、`publicState` | 合并链与公开状态是学者自身事实（SPEC §2；BR-530）；不另建“合并对象”类（UC-10 定义的候选对象不在本 UC 使用） |
| 边键、强度与证据集合 | `CollaborationEdge.edgeKey`、`weight`、`evidenceWorkRefs[]` | 强度是**二元关系上的聚合**，不是节点属性；证据按成果 `canonical_id` 升序保存以保证可复核（BR-IMP-08；SPEC §2）。若另建“证据连接实体”，会与 `Edge–Work` 关联重复 |
| 成果来源与版本 | `WorkEntity.sourceRefs[]`、`versionRefs[]`（仅追溯，不参与强度） | 来源数与版本数不增加强度（AC-IMPL-FR12-02；BR-IMP-02）；本 UC 无 `SourceRecord`/`WorkVersion` 消息，画边会暗示强度依赖来源/版本 |
| 披露块与规模信息（数据范围/时间范围/关系定义/强度含义/排除状态/节点边数/更新时间） | `CollaborationNetworkCoordinator` 的单次请求分析态 | 披露是本次请求的投影，不是业务事实；画成实体即等于建“网络结果对象”（§4；C-01） |
| 机构与领域 | **不表达**（既非属性也非关联） | FR12 明文“仅同机构/同领域不建边”，且本 UC 无筛选条件；记录机构/领域会暗示可用作建边或过滤依据（07 §5 C-03） |

## 3. 从顺序图到 VOPC 的对应

| 顺序图对象 | VOPC 类 | 保留/删除理由 |
|---|---|---|
| 访客（Actor） | 不保留 | 参与者不是系统类；只发 M1/M23/M31/M37/M43 并接收返回 |
| CollaborationNetworkUI | 保留 | 接收 M1/M23/M31/M37/M43；返回 M21/M29/M35/M41/M47/M50/M52 |
| CollaborationNetworkCoordinator | 保留 | 承担 M2–M21、M24–M53 的守卫、建边、强度与披露，并发出 M5/M8/M11/M15/M25/M27/M33/M39 |
| WorkEntity | 保留 | 接收 M5/M27，应答 M6/M28 |
| Authorship | 保留 | 接收 M8，应答 M9 |
| Scholar | 保留 | 接收 M11/M39，应答 M12/M40 |
| CollaborationEdge | 保留（派生） | 接收 M15/M25/M33，应答 M16/M26/M34 |
| 无其他对象 | — | 没有 `Institution`、`Field`、`UserProfile`、`SourceRecord`、`WorkVersion`、`ShortestPath`、`NetworkResult`、`QualityScore` 或 DTO 的消息 |

## 4. 排除项与理由

- `ShortestPath` / `PathResult` / `PathEvidence` / `BfsFrontier`：路径属 IN01/UC-15；本 UC 不计算路径、不排序距离、不产出逐跳论文证据（IN01-R1/R2/R3；BR-IMP-09；AC-IMPL-FR12-05；M51）。
- `NetworkResult` / `GraphSnapshot` / `GraphVersion` / `NetworkState`：本 UC 取“读时投影”取向——节点、边、强度、披露在请求内由生效事实推导；持久结果对象需同时定义版本、生效状态、失效与回退，V2.2 未定义。模块细化 FR12-S08 与 `DGM-STATE-03` 的“结果对象＋状态机＋上一版结果”取向登记为 C-01（07 §5），本包不采用。
- `Institution` / `Field` / `AffiliationPeriod`：FR12 明文“仅同机构、同领域不建边”，且按机构/领域筛选网络属 IN01/UC-15；本 UC 无对应消息（M51 只做交接）。
- `IdentityCandidate` / `MergedIdentity` / `NameCluster`：身份合并与候选属 FR15/UC-10；本 UC 只解析已生效的 `canonicalId` 与合并指向（BR-IMP-03；A11/A15）。
- `User` / `UserProfile` / `Account`：节点是 Scholar canonical 身份；普通主页资料与账号不是节点标识也不是合作证据（§12；`01-BCE` §5）。
- `SourceRecord` / `WorkVersion`：来源数与版本数不增加强度（AC-IMPL-FR12-02；`AC-IMPL-FR16-02`；05 §2.3）。
- `DedupCandidate` / `CleanRecord`：去重/清洗属 UC-09；本 UC 只消费去重结果（BR-IMP-02）。
- `QualityScore` / `ImpactIndex` / `ScholarRanking` / `StrengthLeaderboard`：基线不做质量/影响力/个人排名；强度只表示共同成果实体数（BR-522 细化；FR12-S03 细化；§2.1）。
- `AuditEvidence`：本 UC 无受保护写入；失败明细与审计查询属 FR14/UC-17。
- `Session` / `CredentialContext`：合作网络无需登录（NF01；§8）。
- DTO / 展示投影（`NetworkView`、`GraphLayout`、`NetworkTable`、`NetworkDTO`、`ChartData`）：交互态，不是业务事实（教材 §5.4.2/§5.5.2）。
- `GraphIndex` / `GraphCache` / `NetworkBuildJob` / `AggregationEngine`：属实现阶段或模块细化的“结果生成”取向（C-01），V2.2 不要求。
- `EdgeWeightThreshold` / `MinStrengthFilter`：基线未定义强度阈值或按强度过滤/排序（B-10）。
- `RecommendationProfile` / `BehaviorLog`：O01/O02 延期，且不得成为 FR12 依赖（BR-IMP-12）。
- `Notification` / `SlaTimer`：通知与传播时限未定义；未更新/失败只做页面提示（NF05；B-08）。

### 4.1 `CollaborationEdge` 为什么可以保留为派生 Entity，而不是并入协调者

- **它承载的是业务关系，不是交互状态：** “两名学者的合作关系”是 FR12 明文中的业务概念（“以学者为节点、以明确的合作关系为边”），有独立身份（学者对）、独立事实（证据集合）与独立可观察值（强度）。
- **它必须可被单独检视：** E15（边→证据清单）与 E16（节点→相关边强度）都以“一条边”为寻址单位；若把它压成协调者内部集合，边就无法成为可复核对象，`AC-IMPL-FR12-01/02/03` 的“边可回溯、强度与清单一致”失去承载者。
- **它不因此成为事实源：** 边的每一项事实都可由“合格成果 + 合格参与关系 + canonical 身份”重建；本 UC 全程无写消息，不持久保存边或图版本。**派生 ≠ 无业务含义**，也**≠ 持久化结果对象**；是否保留结果对象由 C-01 评审决定（07 §5）。

## 5. VOPC 完整性检查

| 检查 | 结果 |
|---|---|
| 只包含 UC-14 参与类 | 是，6 个 |
| 每个类有事件流/消息依据 | 是，见表 1/表 3 |
| 每条关系可追溯到消息或业务约束 | 是，见表 2 |
| 不把 Actor 画成系统类 | 是；访客不进入 VOPC |
| 不用全局 BCE 冒充 VOPC | 是；本图独立于 `DGM-CLASS-01-analysis.puml` |
| 关系多重性是否有依据 | 四条业务关联均有多重性理由（§2.2）；控制/边界到实体不定义持久多重性（§2.1） |
| 是否把 `WorkEntity` 与 `Authorship` 混为一类 | 否：成果是共享实体，参与关系携带角色/顺序/状态/证据 |
| 是否把 `Scholar` 与 `User`/`UserProfile` 混为一类 | 否：节点只用 `canonicalId`；账号与普通主页不进入网络 |
| 是否出现同机构/同领域/相似姓名的边 | 否：无 `Edge→Institution`、`Edge→Field` 关联；`Authorship→Scholar` 为 `0..1` 且身份未定不建边 |
| 是否出现最短路径/图版本/路径证据类 | 否（§4） |
| 是否出现质量/影响力/排名/强度榜单类 | 否（BR-522 语境；§4） |
| 是否出现写操作或删除边 | 否：四条实体依赖为只读虚线/本次请求内构建 |
| 是否引入设计技术（表/接口/队列/索引/缓存/DTO） | 否 |

## 6. 与全局图的关系

本 VOPC 是 UC-14 的局部静态视图，不是全系统类图。与 `DGM-CLASS-01-analysis.puml`、`01-BCE分析类模型.md` 的异同、命名合并建议与冲突见 [07-与全局模型差异](07-global-model-diff.md)。R5 统一分析类时应以本 VOPC 为输入之一，而不是用全局图反推本 UC 的参与类。
