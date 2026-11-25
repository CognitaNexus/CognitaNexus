# 华为云迁移准备：PostgreSQL 权威数据 + Neo4j 图投影

| 项目 | 内容 |
| --- | --- |
| 状态 | **准备稿（Prepared，未在华为云上执行任何操作）** |
| 日期 | 2026-11-25 |
| 依据 | [ADR-005](../architecture/ADR-005-neo4j-graph-projection.md)、架构设计说明书 §12、AGENTS.md §5、SRS 容量目标（500 万条成果） |
| 适用范围 | `query_db` 的图投影迁移；`core_db` / `ingestion_db` 的迁移沿用既有 PostgreSQL 备份恢复流程 |
| 明确不做 | 本文件不创建云资源、不修改 DNS/安全组、不执行生产备份恢复、不宣称任何性能或 RTO 已达标 |

> **一句话边界**：PostgreSQL 是唯一事实权威；Neo4j 是**可全量重建**的读投影。迁移的第一目标
> 是「权威数据完整、可回滚」，第二目标才是「图查询更快」。任何一步失败都可以退回 PG 查询，
> 不靠图库里的数据救场。

---

## 1. 可移植的容器拓扑

### 1.1 文件与用途

| 文件 | 用途 | 是否已验证 |
| --- | --- | --- |
| `compose.yaml` | 开发/服务器全栈（PostgreSQL 16 + Redis ×2 + Neo4j 5.26 + 三服务 + 前端） | **未在本机验证**（本机 Docker 守护进程不可用，见 `ops/compose/README.md`） |
| `ops/compose/compose.server.yaml` | 服务器覆盖：不暴露数据库/图库端口，只保留前端入口 | 未在服务器验证 |
| `ops/compose/compose.test-infra.yaml` | 隔离测试基础设施：PostgreSQL 16.4、Redis 8.0、Neo4j 5.26 Community | 配置文件已通过 YAML 解析；**未实际启动验证**（同上） |
| `ops/verify/test-infra-smoke.sh` | 起测试基础设施、等待健康、逐项验证连通性 | 脚本已写；**未执行**（Docker 不可用） |
| `.env.example` | 全部连接参数与变量名；口令一律无默认值 | — |

### 1.2 可移植性规则

1. **镜像版本固定**：测试基础设施用 `postgres:16.4-alpine`、`redis:8.0-alpine`、
   `neo4j:5.26-community`。集成测试依赖 Flyway、`pg_trgm` 与 Neo4j 5 的 DDL 语法，
   浮动 tag 会让「昨天通过、今天失败」无法归因。
2. **连接全部走环境变量**：应用侧 `CN_NEO4J_URI/USER/PASSWORD/DATABASE`，测试侧
   `CN_TEST_*`，两者**变量名不共用**——避免「跑集成测试时把开发图库连通」。
3. **数据卷解耦**：`pgdata`、`session-data`、`claim-evidence`、`neo4jdata`、`neo4jlogs`
   分别是独立卷；测试基础设施的卷名统一带 `<project>-` 前缀，`down -v` 前能一眼确认删除范围。
4. **密钥不写死**：`compose.test-infra.yaml` 对三个口令使用 `${VAR:?}`，缺失即拒绝启动；
   `query-service` 在 `cn.query.graph.enabled=true` 且口令为空时**启动失败**，不落回默认口令。
5. **健康检查用协议探测**：PostgreSQL 用 `pg_isready`，Redis 用 `AUTH + PING`，
   Neo4j 用 `cypher-shell 'RETURN 1'`；TCP 端口可连不等于数据库可用（恢复中、初始化中都会
   接受连接）。Neo4j 首次启动较慢，`start_period: 90s`。
6. **图库不是强依赖**：`compose.yaml` 不把 Neo4j 写进 `depends_on`；图库未就绪时
   query-service 正常启动，网络查询降级到 PG。这与「PG 是唯一权威」一致。

### 1.3 本地/CI 起停

```bash
export CN_TEST_PG_SUPERUSER_PASSWORD='<test-only>'
export CN_TEST_REDIS_PASSWORD='<test-only>'
export CN_TEST_NEO4J_PASSWORD='<至少 8 位>'

bash ops/verify/test-infra-smoke.sh up        # 起服务 + 健康等待 + 三项连通性检查
bash ops/verify/test-infra-smoke.sh check     # 只检查
bash ops/verify/test-infra-smoke.sh down      # 停止，保留卷
bash ops/verify/test-infra-smoke.sh purge --yes   # 停止并删除测试卷（不可恢复）
```

`docker compose` 路径前缀为仓库根；生产服务器使用
`docker compose -f compose.yaml -f ops/compose/compose.server.yaml up -d`，
其中 `ops/compose/compose.server.yaml` 把 Neo4j 的 `ports` 覆盖为空并设置 `restart: always`。

---

## 2. 目标拓扑（华为云）

### 2.1 首选：RDS for PostgreSQL + ECS 自建 Neo4j

```text
公网/ELB（仅 443）
   │
   ├─ ECS: nginx + 三个 Spring Boot 服务（或容器）
   │      ├─ core-service ──┐
   │      ├─ ingestion-service
   │      └─ query-service ──┼─ 内网 5432 ─→ RDS for PostgreSQL 16
   │                         └─ 内网 7687 ─→ ECS: Neo4j 5.26 Community（独立数据盘）
   └─ OBS（可选）：备份归档
```

| 组件 | 规格起点 | 说明 |
| --- | --- | --- |
| RDS for PostgreSQL | 8 vCPU / 32 GB，SSD 500 GB 起 | 三库分实例或同实例分库；开启 PITR 与自动备份 |
| ECS（应用） | 4 vCPU / 16 GB × 2 | 与 RDS 同 VPC、同可用区优先 |
| ECS（Neo4j） | 8 vCPU / 32 GB，SSD 1 TB 起 | 堆 8–12 GB、pagecache 12–16 GB；单机 Community，主备用快照+重建而非集群 |
| 网络 | VPC 内网 + 安全组 | Bolt/HTTP **不暴露公网**；RDS 只允许应用安全组 |
| 凭据 | KMS / 凭据管理 | 图库与数据库口令不进镜像、不进仓库 |

**为什么先选自建 Neo4j 而不是 GES**：现有图模型、Cypher、约束/索引与集成测试都围绕
Neo4j 5 验证；GES 的事务、唯一约束与查询语言支持需要用真实实例逐项确认（§6），
在确认之前把它作为首选会把「迁移」变成「重写 + 重新验证」两件事同时发生。

### 2.2 容量与增长

- 图节点数 ≈ 公开学者数 + 公开成果数（IR01 口径的去重成果）；
- `COOPERATED_WITH` 关系数 ≈ Σ 每篇成果在阈值内的作者对数（阈值默认 20，单篇最多 190）；
  超过阈值的成果不物化，关系数不会随超大作者数二次增长；
- 图数据可压缩/可重建，**不进入备份保留策略**；需要的是「重建时间」而不是「图备份」。

---

## 3. PostgreSQL 备份与恢复流程

### 3.1 备份（沿用现有脚本）

```bash
# 容器模式（docker compose 中的 postgres）
bash ops/backup/pg-backup.sh --out /data/backups

# 宿主原生实例
CN_BACKUP_MODE=host bash ops/backup/pg-backup.sh --out /data/backups
```

脚本产出 `backups/<时间戳>/`：三个库的 `-Fc` dump、校验和、以及关键表行数清单
（`core_achievement`、`qry_achievement`、`qry_contribution`、`qry_coauthor_edge` 等）。
**清单是恢复验收的依据**：表存在不等于数据回来了。

### 3.2 恢复到新实例（演练步骤）

1. 在隔离环境创建空的 PostgreSQL 16 实例（不得指向生产）；
2. `pg_restore -Fc -j 4 -d query_db query_db.dump`（core/ingestion 同理）；
3. 用备份清单逐表核对行数；对 `qry_coauthor_edge` 额外核对
   `count(DISTINCT (generation, achievement_id, scholar_a, scholar_b))`；
4. 启动 query-service（`CN_QUERY_GRAPH_ENABLED=false`），调用
   `/internal/v1/consistency` 或只读健康端点，确认 `publishedRevision` 与清单一致；
5. 记录：备份时间、恢复耗时、校验结果、操作者、失败项。**未演练的 RTO 不算承诺。**

### 3.3 华为云上

- RDS 自动备份 + PITR：保留期按课程/项目要求设置（建议 ≥ 7 天）；
- 跨区域备份或定期 `pg_dump` 到 OBS：用于「实例整体误删」场景；
- 恢复演练在独立 VPC/实例进行，不复用生产安全组；
- 迁移切换前做一次全量备份并记录 commit/迁移版本（Flyway `V1`–`V6`），
  切换后不修改已应用的迁移版本（AGENTS.md §2.3）。

---

## 4. Neo4j 图谱从 PostgreSQL 全量重建

图不备份、只重建。流程固定为「先降级、再清空、分批灌、核对、切换」：

### 4.1 前置

```bash
# 1) 查询切回 PG（图库不再被读）
CN_QUERY_GRAPH_ENABLED=false   # 重启 query-service 或滚动更新
# 2) 确认没有在途图查询（观察日志/指标中的 graph 请求为 0）
# 3) 记录基线：PG 侧公开成果数、合作边数、published_revision
```

### 4.2 建 schema（幂等）

```bash
cypher-shell -a "$CN_NEO4J_URI" -u "$CN_NEO4J_USER" -p "$CN_NEO4J_PASSWORD" \
  < services/query-service/src/main/resources/neo4j/schema.cypher
```

或由 query-service 以 `cn.query.graph.enabled=true` 启动时自动执行（`Neo4jSchemaInitializer`，
启动两次也安全）。执行后必须确认三个唯一约束存在：

```cypher
SHOW CONSTRAINTS YIELD name RETURN name;   -- 应包含 scholar_id_unique / work_id_unique / graph_projection_meta_scope_unique
```

### 4.3 清空并灌图

`GraphProjectionStore.resetForRebuild(scope)` 会清空图库并重置水位（Community 单库单作用域，
**独占实例**才可执行）。随后按成果分页：

1. 从 `qry_contribution` 读取一批（≤500）成果的合格作者（`relation_state IN
   ('AUTO','SELF_CONFIRMED')`）；
2. 批量 `upsertScholars`；
3. 每篇 `replaceWork`（写 `Work`、`AUTHORED`；作者数 ≤ 阈值时写 `COOPERATED_WITH` 证据边）；
4. 本批结束 `advanceMeta(projected_version = 本批最大 source_version)`。

断点即 `GraphProjectionMeta.projected_version`。重复批次因幂等而安全；版本更旧的事件被忽略。
> 调度器与对账工具为后续 Issue；当前可用 `Neo4jSchemaIT` 验证单成果/单批次语义，
> 手写脚本必须复用 `GraphProjectionStore` 端口，不得直接拼 Cypher 写业务数据。

### 4.4 对账（必须全绿才切换）

PostgreSQL：

```sql
-- 公开成果数
SELECT count(*) FROM qry_achievement
WHERE generation = :gen AND effective_status='EFFECTIVE' AND visibility='PUBLIC';

-- 合格合作边（按成果-作者对）
SELECT count(*) FROM qry_coauthor_edge e
JOIN qry_achievement a
  ON a.generation=e.generation AND a.achievement_id=e.achievement_id
WHERE e.generation=:gen AND a.effective_status='EFFECTIVE' AND a.visibility='PUBLIC';
```

Neo4j：

```cypher
MATCH (w:Work) WHERE w.is_public = true RETURN count(w) AS publicWorks;
MATCH ()-[c:COOPERATED_WITH]->() WHERE c.is_valid = true RETURN count(c) AS materializedPairs;
MATCH (m:GraphProjectionMeta {scope: $scope})
RETURN m.projected_version AS projectedVersion, m.generation AS generation;
```

合格判定：

- `publicWorks` = PG 公开成果数；
- `materializedPairs` = PG 中「作者数 ≤ 阈值」的成果作者对数之和
  （超阈值成果按设计不物化，差额必须能被 PG 查询解释，不能笼统算「允许误差」）；
- `projectedVersion >= published_revision`，且 `generation` 与 PG 活动 generation 一致；
- 抽样 20 个成果：图中 `(work_id, pair)` 与 `qry_coauthor_edge` 一一对应，`work_title` 与
  `qry_achievement.title` 一致。

任何一项不符：**不切换**，修投影后重灌；不得「先切过去再看」。

### 4.5 切换与回退

- 切换：`CN_QUERY_GRAPH_ENABLED=true` + 图水位达标；先灰度一个只读实例或按流量比例；
- 回退：`CN_QUERY_GRAPH_ENABLED=false` 重启，1 分钟内回到 PG；图数据保留但不读；
- 观察：错误率、P95、`degradedToPostgres`、图水位与 PG 修订差、Neo4j 堆/pagecache/磁盘。

---

## 5. 数据量与预估迁移时间

> **口径声明**：下表是**估算与待实测**，不是性能承诺。真实迁移时间必须在目标规格上用真实
> 数据分布测量；报告里必须同时写数据性质（REAL/SIMULATED/MIXED）与查询集。

### 5.1 当前与目标规模

| 项 | 数值 | 来源/性质 |
| --- | --- | --- |
| 当前合作图规模 | 约 2.5 万学者 / 7.7 万条边 | `ScopedGraphLoader` 注释中的 2026-10-18 实测（真实库） |
| 目标成果总量 | 500 万 | SRS 容量目标（课程口径） |
| 平均作者数 | 3（假设） | **待用真实数据统计**；直接影响作者对数 |
| 物化阈值 | 20 位作者 | 默认配置；硬上限 200 |

### 5.2 关系数估算

每篇成果的作者对数 = C(n, 2) = n(n-1)/2（仅当 n ≤ 阈值时物化）。

| 场景 | 平均作者数 | 物化关系数（500 万成果） | 备注 |
| --- | --- | --- | --- |
| 偏少 | 3 | 1 500 万 | 基线估算 |
| 中等 | 5 | 5 000 万 | 索引与磁盘需重新评估 |
| 偏多 | 8 | 1.4 亿 | 可能不适合单机 Community，需分区/降阈值/GES |

估算公式：`关系数 = Σ C(n_i, 2) · [n_i ≤ 阈值]`；实际必须用
`SELECT author_count, count(*) FROM (...)` 统计真实分布后代入，不能拿平均值替代重尾分布。

### 5.3 时间估算（待实测）

| 阶段 | 估算方法 | 待测指标 |
| --- | --- | --- |
| PG 全量导出/备份 | `pg_dump -Fc` 吞吐 | 数据量、磁盘 IOPS、并行度 |
| 图 Backfill | 关系数 ÷ 批量写入吞吐 | Neo4j 单实例批量写入关系/秒（目标 5k–20k，**未实测**） |
| 索引重建 | Neo4j 索引构建时间 | 关系数、堆/pagecache |
| 对账 | PG 聚合 + 图计数 | 查询计划、并行度 |
| 切换窗口 | 上述之和 + 回滚余量 | 业务可接受停机时间（待产品确认） |

示例（仅用于排期讨论，不作为承诺）：1 500 万关系按 10k/s 约 25 分钟写入，加上 PG 扫描、
索引与对账，单次重建按 **1–3 小时** 预留；5 000 万关系按 1.5–2 倍关系数增长并受索引影响，
建议按 **4–8 小时** 预留窗口。**上线前必须实测并回填本表。**

---

## 6. 改用华为云 GES 时需要改什么

### 6.1 能力验证清单（先做，再决定）

| 能力 | 为什么需要 | 验证方式 |
| --- | --- | --- |
| 查询语言（Cypher/Gremlin 子集） | 现有适配器用 Cypher 表达邻接、二部图、聚合 | 用本仓库 6 条关键查询逐条改写并比对结果 |
| 多语句事务 / 原子批量 | 成果整体替换（删旧边+写新边） | 并发/中断测试：半写状态是否可见 |
| 唯一约束 | `scholar_id`/`work_id` 防重复 | 重复写入是否被拒绝；不支持则需投影端保证 + 对账 |
| 关系属性索引 | 按 `work_id/pair_key` 删除与检索 | `EXPLAIN`/性能对比 |
| 数据导入方式 | 全量重建吞吐 | 批量导入 API 的限制（批次大小、超时、失败重试） |
| 备份/恢复与版本升级 | 运维 | 官方 SLA 与恢复演练 |
| 网络与鉴权 | 内网访问、IAM | 安全组/委托/凭据轮换 |
| 成本 | 与 ECS 自建对比 | 按关系数、查询量、备份估算 |

### 6.2 需要替换的代码

| 接口/组件 | 现有实现 | GES 实现要点 |
| --- | --- | --- |
| `CollaborationGraphRepository` | `Neo4jCollaborationGraphRepository` | 邻接展开、二部图、筛选下推、`pair_key` 合并语义保持不变；查询语法在适配器内改写 |
| `GraphProjectionStore` | `Neo4jGraphProjectionStore` | 按成果整体替换改为「幂等 upsert + 对账修复」（若 GES 无多语句事务）；版本守卫与水位只前进仍由应用层保证 |
| `GraphSchemaStore` | `Neo4jGraphSchemaStore` | 索引/约束创建 API 替换；不支持唯一约束时，启动校验改为「对账唯一性」 |
| `Neo4jSchemaInitializer` | 读取 `schema.cypher` | 语句来源改为 GES schema 描述；`REQUIRED_CONSTRAINTS` 改为能力检查 |
| 配置 | `cn.query.graph.*` | 增加 `cn.query.graph.provider=neo4j|ges` 与 GES 专有连接参数；默认仍为 `neo4j` |
| 同步/Backfill | Outbox + `GraphProjectionStore` | 不变；GES 适配器内部处理批量与重试 |
| 观测 | 健康指示器 / 水位 | GES SDK 指标接入；`degradedToPostgres` 语义不变 |

**不需要改**：领域模型（`GraphScholarNode/GraphWorkNode/GraphCollaborationEdge/...`）、
证据规则、阈值策略、路径选择、公开资格屏障、降级开关、`NetworkRules` 常量。

### 6.3 GES 迁移步骤（建议）

1. 在测试 VPC 创建 GES 实例，执行 §6.1 清单；任何一项不满足就先解决或放弃 GES 方案；
2. 用 PG 全量重建到 GES（不从 Neo4j 导出）；
3. 跑 `NetworkGraphParityTest` 的规则 + 真实数据抽样对账；
4. 灰度切换（`provider=ges`），保留 `CN_QUERY_GRAPH_ENABLED=false` 一键回 PG；
5. 观察 1 周后删除 Neo4j 实例；保留重建脚本与文档。

---

## 7. 验收与回滚清单

- [ ] PostgreSQL 全量备份 + 恢复到隔离实例，清单行数逐项一致（记录耗时与失败项）
- [ ] Neo4j schema 两次初始化成功，三个唯一约束与关键索引存在
- [ ] 图 Backfill 完成，对账四项全绿（公开成果数、物化作者对数、水位、抽样逐条对应）
- [ ] 同一组查询在 PG 与图库返回相同路径（含等长路径字典序规则）
- [ ] 停图库：公开网络请求降级 PG，不返回空图；`degradedToPostgres=true`
- [ ] 回滚：`CN_QUERY_GRAPH_ENABLED=false` 后 1 分钟内恢复，错误率无上升
- [ ] 容量测试：固定硬件、真实/模拟数据口径分开记录，P95/错误率/资源写进报告
- [ ] 迁移后未修改任何已应用的 Flyway 迁移版本

**未完成项不得写成通过**：本文件当前所有「迁移/性能/云资源」项均为未执行，状态以各节标注为准。

---

## 8. 参考

- [ADR-005：Neo4j 图投影](../architecture/ADR-005-neo4j-graph-projection.md)
- `ops/compose/compose.test-infra.yaml`、`ops/verify/test-infra-smoke.sh`
- `ops/backup/pg-backup.sh`、`ops/verify/backup-restore-drill.sh`、`ops/restore/`
- `services/query-service/src/main/resources/neo4j/schema.cypher`
- 架构设计说明书 §12（部署、备份、恢复、可观测性）
