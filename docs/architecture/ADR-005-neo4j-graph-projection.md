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
运维 `resetAllForRebuild(scope)` 会清空**整库**，只用于独占实例上的全量重建，且必须先把查询切到
PG 降级路径（§7、§8）。方法名里的 `All` 就是为了避免被误读成「按作用域局部重建」。

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

**事件对应的成果已不在 PG 投影中时必须清理图内残留**：`AchievementMerged` 的 payload 为
`{survivorId, survivorTitle, mergedId, changeKind=MERGE}`，`ProjectionApplier` 会删除被合并者
（`mergedId`）的读投影行。增量同步该 `workId` 时 `loadWork` 返回空，若只记一句「留待对账」，旧成果
节点与它的 `AUTHORED`/`COOPERATED_WITH` 会永久留在合作网络里（幽灵边）——对账要等下一次人工执行才会
发现。因此这种情况直接 `deleteWork(workId)`（PG 里连行都没有，没有可信版本可写，见 §7）。

**失败即停，水位一步都不推**：任何一条成果的写入或清理失败都让本轮 `projected_version` 保持原值，
即使失败之前的若干事件已经成功应用。把水位推进到最后一个成功事件，等于把失败那条永久跳过。

**同一成果必须串行消费**：`replaceWork` 是「先删后建」的读改写序列（删 `AUTHORED`/`COOPERATED_WITH`
→ 按当前作者集合重建）。同一 `workId` 的两个版本如果被两个消费者并发应用，即使各自通过版本守卫，
也可能交错成「版本 v2 删边 → v1 写入 → v2 重新写入」的中间态，出现重复边或残留旧作者。因此：

- 增量路径按 `workId` 分区/串行（同一成果同时只有一个消费者在应用），这是投影端的**前置条件**，
  不由 `source_version` 守卫替你完成；
- Backfill 与增量消费者不得并发写同一 `workId`：重建期间读路径已在 PG（§7、§8），先完成回填再切回；
- 版本守卫是「迟到/重放」的兜底，不承担「并发写同一成果」的互斥职责。

**海量作者成果的事件**：`max-authors-per-achievement-for-edges=200` 的既有隔离逻辑保持不变
（超限事件隔离并记录，不静默截断）。图投影对超阈值成果仍写 `AUTHORED` 与二部图证据，不因
「不物化边」而丢作者。

### 5.2 Backfill 分批与断点

- 按 `work_id` 稳定排序分页读取 `qry_contribution`（每批 ≤ 500 成果），每批：
  1. `upsertScholars`（批量 MERGE，版本守卫）；
  2. `replaceWork`（成果整体替换，含物化判定）；
  3. 每批结束写一行回填断点（游标 = 本批最后一条 `work_id`），**全部灌完后**才把
     `GraphProjectionMeta.projected_version` 推进到本次快照的 `published_revision`。
- **断点与水位分开存**（`feat/graph-projection` 引入的 Flyway `V7__graph_projection_checkpoint.sql`，
  表 `qry_graph_projection_checkpoint`，每个 `scope` 一行）：
  - 水位（Neo4j `GraphProjectionMeta.projected_version`）回答「图能不能用于公开查询」，必须只前进、
    语义极简；
  - 断点回答「回填做到哪了」：`status ∈ {RUNNING, COMPLETED, FAILED}`、`generation`、`cursor_work_id`、
    `base_revision`（快照锚点）、`processed_works`（只计 APPLIED/RETIRED/STALE_IGNORED）、
    `failed_works`、`last_error`、时间戳。
  - 为什么不能把断点塞进水位：一次写断点失败会变成「水位回退」，而水位回退会让已下线的合作边
    重新被当成最新数据；反过来，回填半途就把水位推到 `published_revision`，读路径会把「只灌了一半」
    的图当成完整图使用。因此 `GraphProjectionWorker` 的增量追赶在断点未 `COMPLETED` 时**跳过**本轮
    （`RUNNING` 与 `FAILED` 都算未完成）。
- 游标用 `work_id` 而不是数字下标/内存 offset：分页按 `work_id` 稳定升序，插入新成果不会让游标错位；
  重复扫描的批次因幂等而安全；进程重启后仍能回答「哪些成果已真正写入」。
- 失败语义：单条成果的永久失败**不**中止整批（否则真正的问题数据永远轮不到修复），其标识与原因进
  `failed_works`/`last_error`，游标继续前进；漏掉的点由对账后的定向修复（§6.2 `repair`）单点补齐。
  失败数超过 `max-failed-works`（默认 100）时整次运行转 `FAILED`——「跑完了但有几万条没进图」不算成功。
- **完成与推进水位的前提是零失败**：扫描到底但 `failed_works > 0` 时，断点一律记 `FAILED`
  （保留 `cursor_work_id` 与 `last_error`），且**不**调用 `advanceMeta`。理由：「扫完了」与「都进图了」
  是两件事；把带缺口的一次回填标成 `COMPLETED`，增量就会从快照修订之后接上并推进水位，读路径从此
  信任一张缺了几条成果的图，而缺口再也不会被重试（游标已越过它们）。处置路径是修复失败成果后执行
  `repair()`（对账的 `WORK_MISSING_IN_GRAPH` 指名具体成果）或 `rebuild()`，再让水位前进。
- **闭环：修复后必须能恢复 `COMPLETED`**（否则「零失败才能 COMPLETED」会变成永久封锁）。失败成果都在
  游标之后，继续 `backfill()` 不会再扫到它们，`failed_works` 只会单调累加——若没有收尾路径，一次脏数据
  就能迫使百万级整库 `rebuild()`。因此提供唯一的收尾入口
  `GraphBackfillService.markRepairedAndCompleted(scope, verification, repairedWorks)`，由
  `GraphProjectionReconciler.repairAndComplete(report, options, backfill)` 串成一次动作：
  `repair` 单点补齐 → **重新对账** → 以新报告为证据收尾。收尾要求：报告同作用域、同 generation、
  `hasDrift()==false`、且**未被截断**（`scanTruncated=false`，只对比前 N 条的「零差异」不升级为全库结论）；
  满足才把断点改回 `COMPLETED`、`failed_works` 归零、`processed_works` 补上实际写入数，并推进水位到
  快照修订（仍只前进）。任何一条不满足都保持 `FAILED`、水位不动——「修好了」不等于「可以宣称追平」。
- **增量消费的初始化前置条件**（`GraphProjectionReadiness`，调度器与投影器共用同一份规则）：必须有
  该作用域的断点、`status=COMPLETED`、`failed_works=0`、且断点 `generation` 等于 PG 活动代。缺任何一条
  都拒绝消费事件并保持水位不动——「没有断点」不等于「不需要回填」，它等于图几乎是空的。
- 重建使用**新 generation**（`GraphProjectionMeta.generation`）。全部灌完并核对行数后，才允许
  把公开读路径切到图库；切换前图查询一律走 PG（§7）。generation 变化时拒绝在旧代游标上续跑，
  正确动作是 `rebuild()`。

> 现状：本分支已提供幂等写、版本守卫、`advanceMeta`、`resetAllForRebuild(scope)`、分批回填与断点续跑
> （`GraphBackfillService`）、事件增量（`GraphEventProjector`）、增量调度（`GraphProjectionWorker`）
> 与对账/定向修复（`GraphProjectionReconciler`）。回填/对账的**对外触发入口**（HTTP/CLI）、指标告警
> 与跨进程互斥（PG advisory lock）仍属后续 Issue，本文只固定契约，不声称已完成。

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

**筛选等价的写入前提**：上表「领域/机构」两行之所以能在两条路径上给出同一结果，是因为投影写入
时 `Work.topic_ids` **必须等于** PG 中该成果的 `qry_achievement_topic` 集合，
`Work.organizations` **必须等于** `qry_contribution_org` 集合（同一成果的全部机构，不是「第一作者
机构」也不是学者档案的当前机构）。这两个属性不是「可选的展示字段」，而是筛选下推的唯一依据：
少一个主题、多一个机构，带筛选的查询就会在图上比 PG 少/多出一批成果，边强度随之偏离，而响应
里没有任何字段能提示这种偏差。因此 Backfill/投影写入与 `qry_achievement_topic`、
`qry_contribution_org` 的对账属于验收项（ADR §10、对账工具后续 Issue），映射规则变化需要显式
提升 `rule_version` 并重建投影。

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
| 启动时图库不可达 | `Neo4jSchemaInitializer` 启动执行失败 | 默认 `cn.query.graph.fail-fast-on-startup=false`：记 `log.error`、`isReady()=false`，**进程照常启动**并把合作网络查询降级回 PG；置 `true` 时启动失败（仅用于首次上线/升级 schema 的一次性检查） |
| 图库不可达 / 认证失败 | `CollaborationGraphRepository.isAvailable()`、驱动异常 | 本次查询走 PG；健康指示器 `graphAvailable=false`、`degradedToPostgres=true`；不返回空图 |
| schema 未就绪（缺唯一约束） | `Neo4jSchemaInitializer.isReady()` | 走 PG；不允许在缺唯一约束的图上服务查询（合并/重放会产生重复节点） |
| 单条 Cypher 超时 | 驱动 `queryTimeout` | 本次查询走 PG；记录告警（含 scope、耗时） |
| 投影水位落后 `published_revision` | `GraphProjectionMeta.behind()` | 走 PG；不得拿旧图冒充当前结果 |
| 图数据部分损坏（证据缺标题、节点缺失） | 适配器校验 | **抛错**并降级；由对账/重建修复，不跳过坏边 |
| 重建进行中 | `generation` 不一致 / 运维标记 | 走 PG，直到核对通过并切换 |
| 图规模超限 | 节点/边计数 | 明确失败 `GRAPH_SCOPE_TOO_LARGE`，**不降级**：降级会让同一请求在大图上悄悄变成慢查询，掩盖真实规模问题 |

**健康端点必须是 UP，降级只写在 details**：`neo4jGraphHealthIndicator` 恒返回 `Health.up()`，
用 `graphAvailable` / `schemaReady` / `degradedToPostgres` 表达图库状态。如果它在图库故障时返回
`DOWN`，Spring Boot 的 `/actuator/health` 聚合会给 HTTP 503，部署探针随即把**整个 query-service**
判为不健康并摘除——一个可选图库的故障就此变成全部公开查询不可用，恰好与本节的降级承诺相反。
探针自身抛异常同样按「图库不可用」处理，健康检查不得成为新的故障点。

**启动期不得拒绝降级**：图库是派生读投影，启动瞬间连不上属于预期故障。默认只记 `log.error`
并保持 `schemaReady=false`，公开查询继续走 PG；运维可用 `CN_NEO4J_FAIL_FAST_ON_STARTUP=true`
临时要求快速失败（例如 schema 升级窗口只想在确认健康后才接流量）。`initialize()` 始终对异常
向上抛，测试与 CLI 不被启动容错掩埋。

**物化合作边的一致性由 `retireWork` 与水位守卫共同保证**：成果下线/不再公开时 `retireWork`
清墓碑并删除该成果的全部 `COOPERATED_WITH`（保留 `AUTHORED` 作为追溯与二部图分支的基础）；仅靠
版本守卫不够——守卫只拒绝旧版本，不会删除已经写下的边。两者缺一：缺 `retireWork` 会留下「成果
已不公开、合作边仍在」的幽灵边；缺水位守卫则会在回填落后时拿旧图冒充当前结果。对账时二者都
要比（`(work_id, pair)` 数量与 `projected_version` vs `published_revision`，§8.2）。

**修复前必须重读 PG 当前状态**：对账报告是扫描那一刻的快照。`repair()` 处理每条孤立成果前重新
`loadWork(generation, workId)`：PG 里存在且仍合格 → **不退场**，改为正向同步（报告已过期，退场会下线
一个当前公开的成果，下一轮对账再把它当缺口补回来，形成抖动）；存在但不合格 → 用 PG 真实
`aggregate_version` 退场（留墓碑）；不存在 → `deleteWork`。

「降级」只发生在**基础设施/版本/就绪**问题；**业务规模**问题必须明确失败，二者不能混为一谈。

可观测性：健康端点暴露 `scope/database/graphAvailable/schemaReady/degradedToPostgres`；投影水位、
边数、证据截断计数（后续对账工具）进入运维指标。禁止用「重启好了」结束故障（AGENTS.md §5）。

---

## 8. 回滚

### 8.1 一键回 PG

```bash
CN_QUERY_GRAPH_ENABLED=false   # 重启 query-service
```

- 默认就是关闭；关闭时不创建 Driver、不连图库、不执行 DDL，公开网络端点完全走现有 PG 实现；
- 不需要改代码、不需要回滚 Flyway：`feat/graph-projection` 新增的 `V7__graph_projection_checkpoint.sql`
  只新增一张断点表（`qry_graph_projection_checkpoint`），不修改任何已应用版本、不删除既有表或数据；
  停用投影后它成为无引用的空表，可保留（向前修复，不做 `DROP`）。回填断点与 Neo4j 水位分表存储，
  因此「关掉投影」不会留下让水位/断点互相污染的状态；
- 已写入的图数据留在卷里，重新启用后必须先用 §8.2 或对账确认水位与 PG 一致，否则仍会降级。
- 回填进行中要停写：先确认断点（`qry_graph_projection_checkpoint.status`）为 `RUNNING/FAILED` 还是
  `COMPLETED`。停掉 Worker 只会停增量追赶，回填是一次显式调用，结束后断点转 `COMPLETED` 才允许
  读路径信任水位。

### 8.2 从 PG 全量重建图

1. 确认查询已在 PG 路径（开关关闭或图水位未达标）；
2. `GraphProjectionStore.resetAllForRebuild(scope)`：清空**整库**并重置水位。实现会在同一事务里
   先读库内的全部 `GraphProjectionMeta.scope`，只要存在本次重建之外的作用域就抛
   `IllegalStateException` 并回滚（Community 单库单作用域；**只能在独占实例、已确认无其他作用域时
   执行**）；
3. 按 §5.2 分批 Backfill：`GraphBackfillService.backfill(scope)`；每批结束把游标写进
   `qry_graph_projection_checkpoint`（V7），进程中断后从 `cursor_work_id` 续跑，而不是从 0 重扫；
4. 对账：比较 PG `qry_coauthor_edge` 与图中 `COOPERATED_WITH` 的 `(work_id, pair)` 数量、
   `qry_achievement` 与 `Work` 的公开数、`GraphProjectionMeta.projected_version` 与 PG
   `published_revision`（`GraphProjectionReconciler.reconcile`，需要修复时 `repair` 定向重同步/退场，
   但**不**直接改水位）；
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
| 单元 | 配置默认值/自检、schema 解析与初始化幂等、启动期降级与 fail-fast 开关、健康指示器恒 UP、整库重建独占性、领域模型不变量、路径规则（单一来源常量）、PG↔图逐例对比；回填/增量/对账的失败与过期报告路径（P1-1..P1-4） | 已实现并通过（`Neo4jGraphPropertiesTest`、`Neo4jSchemaStatementSourceTest`、`Neo4jSchemaInitializerTest`、`Neo4jGraphConfigurationTest`、`Neo4jGraphProjectionStoreTest`、`GraphDomainModelTest`、`CollaborationGraphServiceTest`、`NetworkGraphParityTest`、`GraphBackfillServiceTest`、`GraphEventProjectorTest`、`GraphProjectionWorkerTest`、`GraphProjectionReconcilerTest`、`GraphProjectionReadinessTest`） |
| 集成 | 真实 Neo4j 5.26：约束/索引幂等、唯一约束拒绝重复、节点/关系写读、证据、REAL/SIMULATED 隔离、高作者数不物化、版本守卫、成果下线、水位单调、跨作用域拒绝整库清空 | `Neo4jSchemaIT`（11 用例），连接本机 `bolt://127.0.0.1:17687` 通过 |
| 性能 | 固定硬件/数据分布的 P50/P95/P99、错误率、资源；与 PG 基线对比 | **未执行**，不得声称图库更快 |
| 一致性 | 真实 Neo4j + 真实 `query_db`：回填一致性、幂等/迟到/收紧、断点续跑、受控重建、四层对账与定向修复、水位不伪造追平、回填存在失败成果时断点转 FAILED 且水位不动、合并删除后的图内残留清理、无断点时拒绝增量消费、过期对账报告不得误下线成果；真实数据抽样与故障注入演练仍待补 | `GraphProjectionSyncIT`（14 用例）已通过；百万级与故障注入**未执行** |
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
| 启动降级可能掩盖长期不可用 | 图库一直没就绪但服务「看起来很健康」 | 健康 details 恒带 `schemaReady/graphAvailable/degradedToPostgres`，并对 `degradedToPostgres=true` 持续告警；首次上线用 `fail-fast-on-startup=true` 做一次性检查 |

---

## 12. 参考

- 架构设计说明书 §9.3（合作图与 IN01）、§7.2（公开资格与版本屏障）、§12（部署与恢复）
- `services/query-service/src/main/java/com/cognitanexus/query/network/**`（PG 现实现与规则常量）
- `services/query-service/src/main/java/com/cognitanexus/query/graph/**`（本 ADR 的实现）
- `services/query-service/src/main/resources/neo4j/schema.cypher`
- `docs/ops/huaweicloud-migration-prep.md`
- AGENTS.md §1（数据所有权）、§2（设计先于代码）、§3（测试与规模口径）、§5（发布与故障处置）
