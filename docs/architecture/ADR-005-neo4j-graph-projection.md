# ADR-005：以 Neo4j 承载合作网络图投影（PostgreSQL 仍是唯一事实权威）

| 项目 | 内容 |
| --- | --- |
| 状态 | **Proposed（实现已落地于 `feat/neo4j-schema`，未经团队评审、未部署）** |
| 日期 | 2026-11-25 |
| 触发来源 | SRS/AC `AC-IMPL-IN01-01/02/03`（论文合作图筛选、最短路径、逐跳证据）；FR12；NF02；架构设计说明书 §9.3；`docs/architecture/adr/` 既有 ADR-001～004 |
| 影响面 | `query-service`（新增 `com.cognitanexus.query.graph` 包、`neo4j/schema.cypher`、`cn.query.graph.*` 配置）；`compose.yaml`、`ops/compose/*`；新增 `ops/verify/test-infra-smoke.sh` |
| 不改变 | `core_db` / `ingestion_db` / `query_db` 的表结构与已应用 Flyway 迁移；公开资格与版本屏障；合作边建边口径；路径选择规则；现有 `/api/v1/public/network/*` 行为（本次仍由 PostgreSQL 提供） |
| 关联文档 | [架构设计说明书](../architecture/架构设计说明书.md) §9.3、§12；[华为云迁移准备](../ops/huaweicloud-migration-prep.md)；[ADR-001 来源暂停与撤销展示许可](adr/ADR-001-来源暂停与撤销展示许可.md) |

> **与实现的关系**：本 ADR 的「决定」部分描述目标架构；截至本分支，阶段四**基础层**已实现
> （配置、schema 初始化、读/写端口与 Neo4j 适配器、应用层路径规则、真实 Neo4j 集成测试）。
> 公开端点 **尚未** 切到图库，`NetworkService` 仍走 PostgreSQL——这是有意的分步：
> 先证明图模型与规则可落地，再以独立 Issue 做切换与压测。本文不把「代码存在」写成
> 「功能已上线」。

---

## 1. 背景与问题

合作网络的两个查询在 PostgreSQL 上是**递归/多跳**问题：

- 自我网络（`/api/v1/public/network/graph`）：以种子学者为中心逐层扩边；
- 最短合作路径（`/api/v1/public/network/path`）：双侧 BFS，最多 6 跳，并要求逐跳证据。

现有 PG 实现（`ScopedGraphLoader` + `NetworkMapper`）已经做过两轮修正：从「先读筛选范围内全图」
改为「按种子分层扩边」，并把扩边 SQL 拆成两个单列 `IN` 以稳定执行计划。这些修正解决的是
**不要在内存里装全图**，但没有改变「每一层扩边都是一次 SQL 往返 + 一次 `count(DISTINCT
achievement_id)` 聚合」这一事实。代码注释里记录的真实规模是约 **2.5 万学者 / 7.7 万条边**
（2026-10-18 实测），而 SRS 的目标是 **500 万条成果**。按当前增长，一次 3 跳自我网络在大规模
数据上需要十几到几十次扩边查询，路径查询还要乘 2（双侧），每次都重新聚合共同论文数。

图数据库的价值不在于「更时髦」，而在于把「邻接关系」变成一等存储结构：

| 维度 | PostgreSQL 现状 | 图数据库（本 ADR） |
| --- | --- | --- |
| 邻接遍历 | 每层一次 SQL + 聚合，索引扫描行数随度增长 | 邻接表按学者直接展开，O(度) |
| 路径查询 | 应用侧 BFS + 多次往返；预算难控 | 邻接展开仍在应用层，但单跳代价小且可下推过滤 |
| 高作者数成果 | 单成果 n(n-1)/2 行写入 `qry_coauthor_edge`，靠 `maxAuthorsPerAchievementForEdges=200` 隔离 | 超过阈值不物化作者对边，改二部图按需推导（§4.4） |
| 逐跳证据 | 需回表 `qry_achievement` 取标题 | 证据与边同一条关系写入，单跳可取 |

**没有证据支持的收益不会被写成结论**：本 ADR 不声称 Neo4j 一定比 PG 快。切换前必须按
§10 的验证计划测量 P95、错误率与资源用量；若测不出收益，回滚开关（§8）就是唯一动作。

---

## 2. 决定摘要

1. **PostgreSQL 仍是唯一事实权威与事务权威**。三个库全部保留，合作边仍由 `qry_coauthor_edge`
   表达，Flyway 迁移不改；图的任何内容都能从 PG 重建。
2. **引入 Neo4j 5.26 Community 作为可选读投影**，由 `cn.query.graph.enabled` 控制，默认关闭。
3. 图模型：`Scholar`、`Work` 节点；`AUTHORED`、`COOPERATED_WITH` 关系；`GraphProjectionMeta`
   水位节点。每条 `COOPERATED_WITH` 只代表**一篇**共同合格成果，携带 `work_id` 与 `work_title`。
4. 作者数超过阈值（默认 20，硬上限 200）的成果**不物化作者对边**，只写 `AUTHORED`，
   由二部图按需推导；不存在「无证据的合作边」。
5. 同步复用现有 Outbox/Inbox 语义：按成果整体替换、版本守卫、水位只前进；迟到事件忽略。
6. 查询一致性：公开资格与版本屏障仍以 PG/core 为准；图水位落后即降级回 PG；筛选、数据性质、
   路径规则与 PG 实现共用 `NetworkRules` 常量。
7. 故障与回滚：图库不可用、投影落后、部分损坏一律**降级/明确失败**，绝不返回不完整图冒充结果；
   配置开关一键回 PG，图可随时全量重建。
8. 迁移华为云：目标形态为 **RDS for PostgreSQL + ECS 自建 Neo4j**；若改用 GES，替换
   `CollaborationGraphRepository` / `GraphProjectionStore` / `GraphSchemaStore` 三个适配器，
   不改领域模型与业务规则（§9）。

---

## 3. 数据归属：PG 权威，图派生

| 数据 | 权威位置 | 图中的角色 | 可否从 PG 重建 |
| --- | --- | --- | --- |
| 账号/会话/角色、认领与授权、审计 | `core_db` | 不进入图 | —（与图无关） |
| 学者档案（姓名、领域、机构） | `core_db`（权威）→ `query_db.qry_contribution*` | `Scholar` 节点属性 | 是 |
| 成果（标题、公开资格、数据性质、生效状态） | `core_db` → `query_db.qry_achievement` | `Work` 节点属性 | 是 |
| 参与关系与确认状态 | `core_db` → `query_db.qry_contribution` | `AUTHORED.relation_state` | 是 |
| 合作边与证据 | `query_db.qry_coauthor_edge` + `qry_achievement` | `COOPERATED_WITH` + `work_id/title` | 是 |
| 发布水位/generation | `query_db.qry_consumer_state` / `qry_generation` | `GraphProjectionMeta` | 是（重新推进） |

**为什么不在图里做权威写入**：认领批准、成果状态变更、来源撤权都要求同一本地事务内同时保证
业务不变量与审计（AGENTS.md §1）。把它拆到图库就变成跨存储事务，而没有分布式事务的补偿设计
只会得到一个「看起来最终一致」的授权模型。因此图的写入永远来自「已经提交的 PG 投影/事件」，
图库自身没有业务写入入口。

**允许的图内写入**：只有投影（Backfill / Outbox 消费者）写 `Scholar`/`Work`/关系/水位。
运维 `resetForRebuild` 只用于全量重建，且必须先把查询切到 PG 降级路径（§7、§8）。

---

## 4. 图模型

### 4.1 节点

```text
(:Scholar {
  scholar_id, scholar_name, domain, organization,
  data_origin, source_version
})

(:Work {
  work_id, title, is_public, data_origin, source_version,
  topic_ids, organizations, author_count, edges_materialized,
  updated_at_epoch_ms
})

(:GraphProjectionMeta {
  scope, projected_version, catalog_revision, generation, updated_at_epoch_ms
})
```

- 标识一律用**字符串**（架构 §9.3）。路径并列比较用规范 ID 的 Unicode 字典序，不用区域排序，
  也不转数字。
- `Work.is_public=false` 是**墓碑**：成果曾投影、现在不再公开。保留它是为了在来源撤销后仍能
  追溯「这条边为什么消失」；所有查询必须显式 `is_public = true`。
- `Work.topic_ids` / `organizations` 是**成果级**筛选字段。机构取成果发生时机构，缺失即未知，
  不用学者当前机构补齐（架构 §9.2）。
- `Work.edges_materialized` 表示该成果的作者对边是否已物化（§4.4）。
- 约束与索引见 `services/query-service/src/main/resources/neo4j/schema.cypher`：
  `scholar_id` / `work_id` / `GraphProjectionMeta.scope` 三个唯一约束；
  领域、机构、数据性质、公开位、来源版本、`AUTHORED.work_id`、`COOPERATED_WITH.work_id/pair_key/
  data_origin` 索引。全部 `IF NOT EXISTS`，启动幂等。

### 4.2 关系

```text
(:Scholar)-[:AUTHORED {work_id, work_title, relation_state, author_order}]->(:Work)

(:Scholar)-[:COOPERATED_WITH {
  work_id, work_title, pair_key, data_origin,
  source_version, rule_version, is_valid
}]->(:Scholar)
```

- `AUTHORED` 只写 **AUTO / SELF_CONFIRMED**；待核验、争议、已排除的参与关系不进入图。
  图存储层对非合格状态直接抛错，不做「悄悄跳过」——静默跳过会让复核时无法区分「没有这个作者」
  与「这个作者未被确认」。
- **`COOPERATED_WITH` 一条关系 = 一篇共同合格成果的一个无序作者对**。这直接对齐
  `qry_coauthor_edge(generation, achievement_id, scholar_a, scholar_b)`：边强度 = 该对作者在
  关系上的 `count(DISTINCT work_id)`，更新时按成果整体删除重建，不靠累计加一。
- 两端写入时按规范 ID 升序（`pair_key = a + \u0001 + b`），避免同一对因写入方向不同产生两条关系。
  读取时两端方向都可能出现，由适配器按 `pair_key` 合并，强度按 `work_id` 去重后计数——镜像
  方向不会被算成两次合作。
- 每条关系都带 `work_id` 与 `work_title`。**没有证据的关系不会被写入**：`GraphCollaborationEdge`
  的构造器拒绝空证据，适配器遇到缺标题的证据直接抛错并要求从 PG 重建。这是「不得为所有作者
  组合无条件生成无证据合作边」的代码级落实，不只是一条文档约定。

### 4.3 为什么仍然需要 PG 的边表

`qry_coauthor_edge` 不是「旧实现的遗留」，而是图投影的**唯一可重放源**与对账基准：

- 全量重建按它灌图；增量投影按成果事件重写对应行；
- 对账工具（后续 Issue）比较「PG 中该成果的作者对数」与「图中该 `work_id` 的关系数」；
- 图库损坏或版本升级时，删图重灌即可，不依赖任何图内备份。

### 4.4 高作者数成果的边爆炸：阈值 + 二部图

一篇 n 位作者的成果有 n(n-1)/2 个无序作者对：

| n | 作者对数 |
| --- | --- |
| 5 | 10 |
| 20（默认阈值） | 190 |
| 50 | 1 225 |
| 200（硬上限） | 19 900 |
| 1 000 | 499 500 |

大科学协作论文（数百至上千作者）会在一篇成果上产生几十万条边，既撑爆图库也撑爆内存索引。
处理方式：

1. `cn.query.graph.max-authors-per-work-for-materialized-edge`（默认 **20**，硬上限 **200**，
   与 PG 侧 `max-authors-per-achievement-for-edges` 的口径对齐）；
2. 作者数 ≤ 阈值：写 `AUTHORED` + 每对一条 `COOPERATED_WITH`（n=20 时最多 190 条）；
3. 作者数 > 阈值：**只写 `AUTHORED`，不写作者对关系**，在 `Work` 上置
   `edges_materialized=false`。查询时由二部图
   `(a:Scholar)-[:AUTHORED]->(w:Work)<-[:AUTHORED]-(b:Scholar)` 按需推导共同作者，
   强度仍按 `count(DISTINCT w.work_id)` 聚合，证据（`work_id/title`）随结果返回。
4. 两条路径的并集正好覆盖全部合格成果：物化边覆盖低作者数成果，二部图覆盖高作者数成果，
   二者不重叠，合并时按 `work_id` 去重。

因此「防爆」不是丢弃数据，也不是只取前 N 位作者：**高作者数成果里的每一位作者仍然可被查询、
每一对共同作者仍然可被证明**，只是不为它们预先物化全部组合。

### 4.5 证据上限

单条边随响应返回的证据条数由 `max-evidence-work-ids-per-edge`（默认 50）限制。强度仍是真实
计数；`GraphCollaborationEdge.evidenceTruncated()` 显式表达「证据被截断」，调用方不得从
`evidence.size()` 反推强度。持久化侧每条关系只带一篇成果的证据，不做无界聚合。

---

## 5. 同步：复用 Outbox/Inbox、版本水位与幂等

### 5.1 事件来源

图投影只消费**已经提交到 `query_db` 的投影结果**，有两条等价入口：

| 入口 | 用途 | 幂等键 | 水位 |
| --- | --- | --- | --- |
| Outbox → Inbox（现有 `ProjectionApplier`） | 增量 | `(consumer_id, event_id)` + 成果 `aggregate_version` | `qry_consumer_state.published_revision` |
| Backfill（从 `qry_contribution` / `qry_coauthor_edge` 分页扫描） | 初次/重建 | 成果 `work_id + source_version` | `GraphProjectionMeta.projected_version` |

**顺序保证**：图投影以成果为单位整体替换，同一成果的事件按 `source_version` 比较。版本更旧的
事件被守卫拒绝（`replaceWork` 返回 `false`，无副作用）。因此「重复、乱序、迟到」都不会把新值
改回旧值；同一事件重放两次的结果与一次相同。

**海量作者成果的事件**：`max-authors-per-achievement-for-edges=200` 的既有隔离逻辑保持不变
（超限事件隔离并记录，不静默截断）。图投影对超阈值成果仍写 `AUTHORED` 与二部图证据，不因
「不物化边」而丢作者。

### 5.2 Backfill 分批与断点

- 按 `work_id` 稳定排序分页读取 `qry_contribution`（每批 ≤ 500 成果），每批：
  1. `upsertScholars`（批量 MERGE，版本守卫）；
  2. `replaceWork`（成果整体替换，含物化判定）；
  3. 批量结束后 `advanceMeta` 推进 `projected_version` 到本批最大 `source_version`。
- 断点就是 `GraphProjectionMeta.projected_version`：进程中断后从该水位之后继续；重复扫描的
  批次因幂等而安全。**不使用内存 offset**——进程重启后 offset 无法解释「哪些成果已应用」。
- 重建使用**新 generation**（`GraphProjectionMeta.generation`）。全部灌完并核对行数后，才允许
  把公开读路径切到图库；切换前图查询一律走 PG（§7）。

> 现状：本分支提供 `GraphProjectionStore` 的幂等写、版本守卫与 `advanceMeta` 原语，
> 以及 `resetForRebuild`。Backfill 调度器与 Outbox 接线属于后续 Issue（需与 Inbox 重放和
> 隔离事件处理一起评审），本文只固定契约，不声称已完成。

### 5.3 水位与 generation

`GraphProjectionMeta(scope, projected_version, catalog_revision, generation, updated_at)`：

- `projected_version` 只前进，旧值写入被忽略；
- `generation` 与 PG 的活动 generation 对应：PG 重建切换后图必须跟着换代，否则会出现
  「图里是旧代成果、PG 里是新代」的混合视图；
- `scope` 唯一约束保证一个公开投影作用域只有一个水位节点。Community 版单库，一个实例只服务
  一个公开作用域；多环境用不同实例隔离（不靠 db 名，Community 不支持多库）。

---

## 6. 查询一致性

### 6.1 公开资格与版本屏障仍由 PG/core 决定

图库**不参与**「谁能被看见」的判断：

1. 每次公开请求仍调用 `PublicBarrierGuard.assertPubliclyServed()`：读取 core 权威公开水位，
   本地 `published_revision` 落后即返回 503 `VIEW_UPDATING`；
2. 图查询前额外检查 `GraphProjectionMeta.projected_version >= published_revision`；
   落后则**降级回 PostgreSQL**，不返回旧图；
3. 图库不可达同样降级（§7）。

图里的 `is_public`/`data_origin` 只用于让「已投影的正确图」自洽，不是授权依据。

### 6.2 筛选条件

| 条件 | PG | 图 |
| --- | --- | --- |
| 数据性质 REAL / SIMULATED / MIXED / ALL | `qry_achievement.data_nature` | `Work.data_origin`；`ALL` = 不过滤 |
| 领域 | `EXISTS qry_achievement_topic` | `$topicId IN Work.topic_ids` |
| 机构 | `EXISTS qry_contribution_org` | `$organization IN Work.organizations` |
| 公开/生效 | `visibility='PUBLIC' AND effective_status='EFFECTIVE'` | `Work.is_public=true`（只有合格成果进入图） |
| 关系资格 | `relation_state IN ('AUTO','SELF_CONFIRMED')` | `AUTHORED.relation_state` 同集合 |

数据性质归一复用 `DataNatureScope`（一处实现）；领域/机构筛选是**成果级**条件，不允许改成
学者级。

**带领域/机构筛选的边强度必须重新聚合**：物化边的强度是「全部合格成果数」，筛选后只应统计
命中筛选的成果。适配器在 `hasWorkLevelFilter()` 为真时不用物化边，改走二部图按筛选条件聚合，
避免「边看起来比实际更强」——那等于凭空制造合作证据。

### 6.3 子图规模与明确失败

- 节点上限 20 000、边上限 100 000、路径预算 1 000 ms，与 PG 配置一致且可由
  `cn.query.graph.*` 覆盖；
- 超限返回 `GRAPH_SCOPE_TOO_LARGE`，超时返回 `GRAPH_TIMEOUT`；
- **不截断**：截断后的图仍然连通，BFS 会得出「无路径」的错误结论，而用户无法区分
  「确实不合作」与「系统只算了一部分」。

### 6.4 最短路径的确定性（与 PG 100% 一致）

`CollaborationGraphService` 与 `NetworkService` 共用常量 `NetworkRules`（`RULE_VERSION`、
最大深度 3、路径深度 6、边规则文案），并逐字复刻选择规则：

1. 从起点 BFS 到最多 6 跳，目标出现即停；
2. 从终点反向 BFS 到 `distance-1` 跳；
3. 从起点贪心：每步只在「跳数单调递增且仍在某条最短路径上」的邻居中取**规范 ID 字典序最小**
   的一个；邻居按 `TreeSet` 升序访问，结果与遍历顺序、存储实现无关；
4. 只返回一条，不枚举全部等长路径；零跳必须确认种子在范围内有合格合作边。

为什么不用 Cypher `shortestPath` / `allShortestPaths`：前者对等长路径的返回没有稳定约定；
后者在稠密图上可能枚举出大量等长路径。把选择规则留在应用层，两个实现可以逐例比对
（`NetworkGraphParityTest`），换图库时也只需替换「邻接扩边」而不必重新证明规则。

### 6.5 逐跳证据

`GraphPathResult.hopEdges` 按路径顺序给出每一跳的 `GraphCollaborationEdge`，每条边带
`workId/title`。零跳路径没有边；不可达结果不带路径或证据（构造器强制）。这满足 IN01
「逐跳有证据」，且证据来自同一发布版本（与边同一事务写入）。

---

## 7. 图库故障与投影落后：降级而不是猜

| 情形 | 检测 | 行为 |
| --- | --- | --- |
| 图库不可达 / 认证失败 | `CollaborationGraphRepository.isAvailable()`、驱动异常 | 本次查询走 PG；健康指示器 `degradedToPostgres=true`；不返回空图 |
| 单条 Cypher 超时 | 驱动 `queryTimeout` | 本次查询走 PG；记录告警（含 scope、耗时） |
| 投影水位落后 `published_revision` | `GraphProjectionMeta.behind()` | 走 PG；不得拿旧图冒充当前结果 |
| 图数据部分损坏（证据缺标题、节点缺失） | 适配器校验 | **抛错**并降级；由对账/重建修复，不跳过坏边 |
| 重建进行中 | `generation` 不一致 / 运维标记 | 走 PG，直到核对通过并切换 |
| 图规模超限 | 节点/边计数 | 明确失败 `GRAPH_SCOPE_TOO_LARGE`，**不降级**：降级会让同一请求在大图上悄悄变成慢查询，掩盖真实规模问题 |

「降级」只发生在**基础设施/版本**问题；**业务规模**问题必须明确失败，二者不能混为一谈。

可观测性：健康端点暴露 `scope/database/degradedToPostgres`；投影水位、边数、证据截断计数
（后续对账工具）进入运维指标。禁止用「重启好了」结束故障（AGENTS.md §5）。

---

## 8. 回滚

### 8.1 一键回 PG

```bash
CN_QUERY_GRAPH_ENABLED=false   # 重启 query-service
```

- 默认就是关闭；关闭时不创建 Driver、不连图库、不执行 DDL，公开网络端点完全走现有 PG 实现；
- 不需要改代码、不需要回滚 Flyway（本次没有新增迁移）；
- 已写入的图数据留在卷里，重新启用后必须先用 §8.2 或对账确认水位与 PG 一致，否则仍会降级。

### 8.2 从 PG 全量重建图

1. 确认查询已在 PG 路径（开关关闭或图水位未达标）；
2. `GraphProjectionStore.resetForRebuild(scope)`：清空图并重置水位（Community 单库单作用域；
   **只能在独占实例、已确认无其他作用域时执行**）；
3. 按 §5.2 分批 Backfill（后续 Issue 提供调度器；当前可用 `Neo4jSchemaIT` / 手写脚本验证流程）；
4. 对账：比较 PG `qry_coauthor_edge` 与图中 `COOPERATED_WITH` 的 `(work_id, pair)` 数量、
   `qry_achievement` 与 `Work` 的公开数、`GraphProjectionMeta.projected_version` 与 PG
   `published_revision`；
5. 核对通过后才把读路径切回图库。

图不是备份对象：**任何图故障都用 PG 重建解决**，不从图导出恢复。恢复演练与 PostgreSQL 备份
恢复分开记录（`docs/ops/huaweicloud-migration-prep.md` §4–§5）。

### 8.3 删除图库

图库可整体丢弃：`docker compose down -v`（开发）或删除 ECS 上的 Neo4j 数据卷。业务不受影响，
因为公开查询已经默认走 PG。删除前确认 `CN_QUERY_GRAPH_ENABLED=false`，避免请求打到已删除实例
后长时间超时（依赖降级与超时，但会浪费连接与预算）。

---

## 9. 迁移华为云

完整操作清单见 [docs/ops/huaweicloud-migration-prep.md](../ops/huaweicloud-migration-prep.md)。本节只固定
架构层面的可替换点。

### 9.1 目标形态：RDS for PostgreSQL + ECS 自建 Neo4j（首选）

- **PostgreSQL**：RDS for PostgreSQL 16，三个库（core/ingestion/query）保持独立实例或独立库；
  备份、PITR、参数组按现有 `ops/backup/pg-backup.sh` 的清单核对；
- **Neo4j**：ECS 自建 5.26 Community，数据盘独立挂载，只开内网 Bolt（7687），安全组仅允许
  query-service 所在子网；
- **网络**：query-service 通过内网 DNS 访问 RDS 与 Neo4j；不把 Bolt/HTTP 暴露公网；
- **密钥**：图库口令与 PG 口令进华为云 KMS/凭据管理，不写进镜像与仓库。

### 9.2 备选：华为云 GES

若改用 GES，需要替换的是**适配器**，不是领域模型：

| 需要改 | 位置 | 说明 |
| --- | --- | --- |
| `CollaborationGraphRepository` 实现 | `graph/infrastructure/Neo4jCollaborationGraphRepository` → `GesCollaborationGraphRepository` | 用 GES SDK/HTTP API 替换 Bolt + Cypher；邻接展开与 `pair_key` 语义保持不变 |
| `GraphProjectionStore` 实现 | `Neo4jGraphProjectionStore` → `GesGraphProjectionStore` | 批量导入/更新 API；仍需按成果整体替换、版本守卫、水位只前进 |
| `GraphSchemaStore` 实现 | `Neo4jGraphSchemaStore` → `GesGraphSchemaStore` | 索引/约束创建方式不同；GES 对唯一约束的支持需先验证（若不支持，唯一性只能由投影端保证并加对账） |
| 查询语法 | 适配器内部 | GES 支持 Gremlin/Cypher 的子集与事务语义需逐条验证；**应用层不得出现任何图库语法**，这是现有端口设计的目的 |
| 同步流程 | 事件消费者 / Backfill | 若 GES 无多语句事务，需要「按成果幂等 upsert + 对账修复」替代整体替换；顺序保证改由 `projected_version` 单写者保证 |
| 迁移工具 | 一次性脚本 | 从 PG 重建到 GES，而不是从 Neo4j 导出（图是派生数据） |

**不改**：`Scholar/Work/AUTHORED/COOPERATED_WITH` 语义、证据字段、阈值策略、路径规则、
公开资格屏障、降级开关。

### 9.3 迁移验收

- 图与 PG 对账一致（节点数、公开成果数、`(work_id, pair)` 数、水位）；
- 同一组查询在 PG 与图库上返回**相同路径**（`NetworkGraphParityTest` 思路扩展到真实数据抽样）；
- 图库故障演练：停掉 Neo4j，公开网络请求 P95 不超过 PG 基线的 1.2 倍，错误率不升高，
  响应中不出现空图冒充；
- 回滚演练：`CN_QUERY_GRAPH_ENABLED=false` 后 1 分钟内恢复全部功能。

---

## 10. 验证计划与当前证据

| 层级 | 内容 | 当前状态 |
| --- | --- | --- |
| 单元 | 配置默认值/自检、schema 解析与初始化幂等、领域模型不变量、路径规则、PG↔图逐例对比 | 已实现并通过（`Neo4jGraphPropertiesTest`、`Neo4jSchemaStatementSourceTest`、`Neo4jSchemaInitializerTest`、`GraphDomainModelTest`、`CollaborationGraphServiceTest`、`NetworkGraphParityTest`） |
| 集成 | 真实 Neo4j 5.26：约束/索引幂等、唯一约束拒绝重复、节点/关系写读、证据、REAL/SIMULATED 隔离、高作者数不物化、版本守卫、成果下线、水位单调 | `Neo4jSchemaIT`，连接本机 `bolt://127.0.0.1:17687` 通过 |
| 性能 | 固定硬件/数据分布的 P50/P95/P99、错误率、资源；与 PG 基线对比 | **未执行**，不得声称图库更快 |
| 一致性 | 真实数据抽样对账、故障注入、重建演练 | **未执行**（对账工具与调度器为后续 Issue） |
| 迁移 | 华为云 RDS+ECS / GES 演练 | **未执行** |

结论边界：本 ADR 证明「图模型与规则可落地、可测、可回滚」，不证明「性能已达标」。

---

## 11. 风险与未决项

| 风险 | 影响 | 缓解/未决 |
| --- | --- | --- |
| 图与 PG 双写不一致 | 用户看到两种网络 | 水位 + 对账 + 默认 PG；对账工具待实现 |
| 高作者数阈值选择 | 阈值过高仍可能边爆炸；过低二部图查询变多 | 默认 20、硬上限 200；压测后按测量调整，必须同 PG 口径一起改 |
| Cypher 结果顺序不稳定 | 证据列表/边顺序抖动 | 证据按 `workId` 排序、边按两端 ID 排序；路径选择在应用层 |
| Community 无多库/无集群 | 多作用域与高可用受限 | 一实例一作用域；高可用用 ECS 主备或评估 GES/Enterprise |
| GES 能力差异（事务/唯一约束/Cypher） | 适配器重写成本 | 先做能力验证清单，再决定是否迁移；端口层已隔离 |
| 公开端点尚未切换 | 本 ADR 的收益尚未体现 | 独立 Issue：接线 + 屏障 + 压测 + 灰度；未完成前不宣称 FR12 由 Neo4j 满足 |

---

## 12. 参考

- 架构设计说明书 §9.3（合作图与 IN01）、§7.2（公开资格与版本屏障）、§12（部署与恢复）
- `services/query-service/src/main/java/com/cognitanexus/query/network/**`（PG 现实现与规则常量）
- `services/query-service/src/main/java/com/cognitanexus/query/graph/**`（本 ADR 的实现）
- `services/query-service/src/main/resources/neo4j/schema.cypher`
- `docs/ops/huaweicloud-migration-prep.md`
- AGENTS.md §1（数据所有权）、§2（设计先于代码）、§3（测试与规模口径）、§5（发布与故障处置）
