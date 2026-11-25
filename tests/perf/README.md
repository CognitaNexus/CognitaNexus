# tests/perf —— 容量与并发测试资产

第三批（容量与恢复）建立，阶段六（性能与容量准备）扩展为分级套件。目的不是「跑一次好看的数」，而是让每次都留下可比对的数据量、环境、查询集与原始结果。

阶段六的方案、容量精算表、门禁阻断证据与执行清单见
**`docs/ops/phase6-performance-capacity-plan.md`**；手动触发的压测工作流见
**`.github/workflows/perf-scale.yml`**（只有 `workflow_dispatch`，不随 push/PR 跑）。

| 文件 | 作用 |
| --- | --- |
| `capacity-model.mjs` | 四档规模（1 万/10 万/100 万/500 万）容量精算：实体数、PG 表+索引+WAL、Neo4j store+索引+tx、内存；`--avail-gb=auto --mem-gb=auto` 读主机真实值；`--require-scale` 是前置安全门禁（退出码 4 = 资源不足）；内置标定自检，模型与实测基线漂移时退出码 3 |
| `load.mjs` | 活跃用户混合负载生成器：31 场景 / 13 接口、分接口与分场景 P50/P95/P99、RPS、错误分类、业务断言 |
| `perf-summary.mjs` | 把一个阶段目录汇总成人读报告 + 验收判定（关键接口 P95 ≤ 2s、错误率 ≤ 1%，缺并发档即判未通过） |
| `explain-graph-and-search.sh` | 采集 PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)` 与 Neo4j `PROFILE`（算子树 + dbHits），输出摘要 |
| `neo4j-project-load.mjs` | PG 导出 TSV → Neo4j 批量装载，记录行/秒、store 体积、实测节点/关系数，并可只跑 `--only=profile` |
| `scale/seed-scale-query.sql` | 造 n 条成果 + 参与关系 + 全景投影（全部 `SIMULATED`，`-v n= -v pool= -v gen= -v seed=`） |
| `scale/fixtures-graph.sql` | 环 / 链 / 等长路径 / 树 / 孤立点 / 枢纽 / 高作者数成果的结构夹具（`SIMULATED`） |
| `scale/pg-index-toggle.sql` | 导入前摘索引、导入后重建，两者分别计时（带快照与回滚） |
| `scale/counts-scale.sql` | 分层计数与逐表体积（`pg_total_relation_size`） |
| `scale/explain-plans.sql` | 与检索/统计/网络实际发出的 SQL 同形的固定查询集 |
| `scale/export-graph-source.sql` | PG → TSV 导出；`data_origin` 直接取自 `data_nature`，故图中不可能出现 `REAL` 节点 |
| `seed-core.sql` | 往 core 造合成规范实体（固定种子）。`psql -v n=1000000 -v seed=0.42 -1 -f` |
| `seed-query.sql` | 往 query 投影造同一批成果、参与关系、领域、机构、合作边（同一 id、同一分布） |
| `counts.core.sql` / `counts.query.sql` | 分层计数与表体积（第三批版本，按 `generation` 全库口径） |
| `explain.sql` | 第三批的固定查询集（含 `EXPLAIN ANALYZE`），供历史对照 |

编排脚本在 `ops/perf/`：`run-scale-benchmark.sh`（预检 → 建库 → 造数 → 索引 → 计数 → 计划 → 图投影 → 压测 → 汇总，支持 `--dry-run` 干跑）、`stage-b.sh`（第三批的独立部署流程）、`restore-drill.sh`（备份恢复演练）。

## 几个不能省的口径

1. **合成数据全部 `SIMULATED`**。默认 `dataNature=REAL` 时一条都查不到，这是数据性质隔离在生效。容量探索必须显式 `dataNature=ALL`，报告里也要写明是演示口径。
2. **`n` 是「可检索去重成果数」**，不是原始行数。原始来源记录、来源归属、稳定标识注册表**没有造**——批量造假来源会制造伪多源，宁可留空并写明。
3. **活跃用户 ≠ RPS**。一个 VU 在思考时间之外循环执行一次任务；脚本输出里的 RPS 是稳定段平均值。
4. **P95 要在同一负载阶段内比较**。负载升高时 P95 可能下降，那是连接获取超时导致的快速失败，不是变快。
5. **造数要检查标题不是同一条**。曾因标量子查询只求值一次，10 万行标题完全相同，检索退化成全命中／零命中，`EXPLAIN` 看起来像索引问题。

## 跑之前

- 先干跑：`node tests/perf/capacity-model.mjs --avail-gb=auto --mem-gb=auto --require-scale=10k`
  与 `bash ops/perf/run-scale-benchmark.sh --dry-run --stage=10k`，两者都不连数据库。
- 独立实例：`bash ops/local/test-db.sh start`（端口 55432）。
- 服务需要 Redis：`bash ops/local/start-infra.sh`（沙箱下必须以后台任务承载，否则进程组被回收）。
- `ops/perf/stage-b.sh up` 是前台阻塞的，调用方要用后台任务承载。
- 不要与本机其他测试抢 55432/17687：压测会造数、重建索引、清空作用域内的图节点。
- 跑完用 `node tests/perf/perf-summary.mjs --dir=<阶段目录> --enforce` 出结论；
  缺并发档或关键接口超阈时它以非零码退出，不要手工挑好看的数写报告。
