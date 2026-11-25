# 阶段六：性能与容量测试方案、资源需求评估与压测就绪报告

> 依据：课程任务书「四、性能与容量准备」、AGENTS.md §3.1（数据质量与规模必须按口径报告）、§5（发布、监控与故障处置）、ADR-005（图投影作为派生读投影）。
>
> 本文登记的是**工具、模型与就绪状态**，不是一份「已达标」的性能结论。所有未执行的规模在 §9 逐条列出，不用估算或历史数字顶替。

## 0. 先读这一段：本文能证明什么、不能证明什么

| 可以声称 | 不可以声称 |
| --- | --- |
| 已有可重跑的容量精算模型，并在本机真实读数上给出四档规模的磁盘/内存需求 | 「已经验证过 500 万规模」——**没有在本机执行**（门禁阻断，证据见 §4.3） |
| 已有覆盖 13 个公开接口、31 个场景的混合负载生成器，含分接口 P50/P95/P99 与业务断言 | 「关键接口 P95 ≤ 2s 已达标」——2026-10-17 的 100 万/100 并发实测**未达标**（见 §1.2） |
| 已有手动触发的性能压测工作流，带资源门禁、执行计划采集与证据归档 | 「合成 500 万数据 = 500 万真实收录」——数据性质全部为 `SIMULATED` |
| 已有分档容量表与目标云环境的复现命令 | 「本机 2C8G 能跑 500 万」——磁盘与内存都不够 |

AGENTS.md §3.1 的底线：**合成数据只能证明容量或故障行为，不代表百万真实收录；未执行的大规模实测不得写成通过。**

## 1. 长期验收目标与当前状态

### 1.1 目标（来自课程任务书，本分支不修改）

| 编号 | 目标 | 判定口径 |
| --- | --- | --- |
| G1 | ≥ 100 万**真实去重成果**联调 | `data_nature = 'REAL'` 的公开有效成果数 ≥ 1,000,000，并按原始抓取/暂存/入库/拒绝/去重/公开有效/可检索分层登记 |
| G2 | ≥ 500 万数据容量验证 | 去重成果 ≥ 5,000,000（合成可接受，但必须标注 `SIMULATED`），导入、索引、图投影、备份恢复全部跑通并留体积证据 |
| G3 | ≥ 100 并发活跃用户 | `tests/perf/load.mjs --vus=100`，稳态 ≥ 300s，思考时间 1000ms ±50% |
| G4 | 关键搜索与路径查询 P95 ≤ 2s | 每个关键接口（`/public/search/*`、`/public/network/*`）在**每个**并发档上独立统计，不做「总体 P95」 |
| G5 | 错误率 ≤ 1% | HTTP 错误 + 超时 + 业务失败 / 总请求，同一并发档同接口 |
| G6 | 固定环境 + 数据分布 + 查询集 + 预热 | 缺任一项的结果不构成达标证据（AGENTS.md §3.1） |

### 1.2 当前状态（如实登记）

| 目标 | 状态 | 证据 |
| --- | --- | --- |
| G1 | **未启动** | 真实采集管道尚未产生百万级 REAL 数据；`raw_source_record` / `core_achievement_source` / `core_identifier_registry` 目前为 0 行（`docs/ai/第三批-容量与恢复验证记录-2026-10-17.md` §3.2） |
| G2 | **未执行（500 万）** | 本机 2C8G/21GB 被门禁阻断（§4.3）。100 万规模曾于 2026-10-17 在 Windows/22 核机器跑通（`SIMULATED`） |
| G3 | **未达标** | 2026-10-17：100 并发、稳态 300s，成功率 **24.9%**，成果检索 P50 5,013 ms / P95 11,068 ms |
| G4 | **未达标** | 同上；且统计概览单请求 34–64 秒，慢查询把连接池占满导致级联 500 |
| G5 | **未达标** | 100 并发下 HTTP 错误以 500（连接获取失败）为主 |
| G6 | **部分具备** | 查询集、预热、数据分布已固化；缺「固定环境」——本机为服务/数据库/负载生成器同置 |

**结论**：阶段六的**测试能力已就绪**，**验收结论尚未取得**。达标路径见 §10。

## 2. 数字来源与口径

每个数字都标明来源，禁止把两类混着写：

| 标记 | 含义 | 可被如何引用 |
| --- | --- | --- |
| `MEASURED` | 2026-10-17 在 100 万 `SIMULATED` 数据上真实读到的 `pg_total_relation_size`、行数与压测分位 | 可作为基线，但环境不同（22 核 Windows、PG 默认参数）不可直接搬到 2C8G |
| `ESTIMATE` | 由 `MEASURED` 的每行字节外推，或由 Neo4j 5.26 固定记录格式推出的字节数 | 只能作为规划值，**必须**在对应规模实测后回填标定 |
| `GATE` | 由 `capacity-model.mjs` 依据当前主机真实读数（`statfs` / `os.totalmem`）算出的布尔结论 | 可直接作为「能不能在这台机器上跑」的结论 |

换算口径（与 `df -h` / `du -sh` 一致）：1 GiB = 1024³ B，1 GB = 1024³ B（本文统一写 GB 表示 GiB）。

- **PG 体积** = `pg_total_relation_size`（堆 + 索引，含 GIN/trgm），WAL 与索引重建临时文件单独一行。
- **Neo4j 体积** = 节点记录 + 关系记录 + 属性/字符串存储 + 索引条目；事务日志按 store 的 35% 预留（导入期同量级增长，Neo4j 默认保留 7 天）。
- **每行字节基线**（`MEASURED`，100 万实测反推）：

| 表 | 堆 B/行 | 索引 B/行 |
| --- | ---: | ---: |
| `qry_achievement` | 375.4 | 107.0 |
| `qry_contribution` | 101.0 | 93.7 |
| `qry_coauthor_edge` | 84.5 | 106.4 |
| `qry_contribution_org` | 76.9 | 76.2 |
| `qry_achievement_topic` | 84.3 | 65.4 |

- **作者数分布**（阶段六默认 `graph-heavy`，与 `tests/perf/scale/seed-scale-query.sql` 逐字一致）：单作者 6% / 2–3 位 66% / 4–5 位 23% / 6–10 位 4% / 11–20 位 1% ⇒ 3.22 位作者/成果、5.32 条合格合作边/成果。上限刻意压在 20（= Neo4j 物化阈值），使 PG 与 Neo4j 的边集在随机部分**逐条相同**，影子对照不会因为阈值差异报假差异。
- **模型标定自检**（每次运行都会打印，超差则以退出码 3 结束）：

| 指标 | 模型 | 实测 | 偏差 | 结论 |
| --- | ---: | ---: | ---: | --- |
| 参与关系行数（100 万） | 3,000,000 | 2,999,440 | 0.02% | 通过 |
| 合作边行数（100 万，base 档） | 1,778,333 | 1,724,226 | 3.14% | 通过 |
| 五张投影表合计体积（100 万） | 1.72 GB | 1.72 GB | 0% | 通过 |

## 3. 四档规模容量精算

以下全部由 `node tests/perf/capacity-model.mjs` 生成（`graph-heavy` 档，含固定结构夹具 896 条成果）。复现命令见 §6.1。

### 3.1 实体数（`ESTIMATE`，夹具为固定附加量）

| 阶段 | 去重成果（随机 n + 夹具） | 学者 | 作者关系 | PG 合作边 | Neo4j `AUTHORED` | Neo4j `COOPERATED_WITH` | 领域归属行 | 发生机构行 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 万 | 10,000 + 896 = 10,896 | 14,065 | 41,542 | 209,847 | 34,397 | 53,848 | 15,796 | 20,771 |
| 10 万 | 100,000 + 896 = 100,896 | 62,365 | 331,342 | 688,688 | 274,351 | 532,688 | 149,896 | 165,671 |
| 100 万 | 1,000,000 + 896 = 1,000,896 | 545,365 | 3,229,342 | 5,477,093 | 2,673,895 | 5,321,093 | 1,490,896 | 1,614,671 |
| 500 万 | 5,000,000 + 896 = 5,000,896 | 2,692,031 | 16,109,342 | 26,758,893 | 13,338,535 | 26,602,893 | 7,450,896 | 8,054,671 |

`Neo4j AUTHORED < 作者关系` 是刻意的：只有 `PUBLIC + EFFECTIVE` 的成果才进图（100 万实测公开有效占比 82.8%）。`Neo4j COOPERATED_WITH` 略少于 PG 合作边，差值是作者数 > 20 的高作者数夹具（走二部图推导，不物化）。

### 3.2 PostgreSQL 体积（`ESTIMATE`，按每行字节外推）

| 阶段 | 表与索引（基线外推） | V5/V6 新增索引（trgm/lower） | 其他投影表 | WAL 预留（落库 45%） | 索引重建临时文件 | **规划用量** |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 万 | 0.05 GB | 0.00 GB | 0.03 GB | 0.03 GB | 0.02 GB | **0.14 GB** |
| 10 万 | 0.27 GB | 0.03 GB | 0.03 GB | 0.13 GB | 0.07 GB | **0.54 GB** |
| 100 万 | 2.45 GB | 0.25 GB | 0.08 GB | 1.21 GB | 0.58 GB | **4.57 GB** |
| 500 万 | 12.11 GB | 1.24 GB | 0.27 GB | 6.01 GB | 2.86 GB | **22.50 GB** |

100 万下的分表体积（`ESTIMATE`）：`qry_achievement` 0.45 GB、`qry_contribution` 0.59 GB、`qry_coauthor_edge` 0.97 GB、`qry_contribution_org` 0.23 GB、`qry_achievement_topic` 0.21 GB。

> V5/V6 的 trigram 与表达式索引是在 2026-10-17 实测**之后**加入的，历史报告的表体积不含它们。直接拿历史数字当「今天的 schema」会系统性低估约 0.25 GB（100 万）/ 1.24 GB（500 万）。该系数是 `ESTIMATE`，1 万阶段应以 `pg_indexes` + `pg_relation_size` 实测回填。

### 3.3 Neo4j 体积（`ESTIMATE`，Neo4j 5.26 Community 固定记录格式）

| 阶段 | 节点存储 | 关系存储 | 索引 | store 合计 | 事务日志（35%） | **磁盘合计** |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 万 | 0.00 GB | 0.02 GB | 0.01 GB | 0.03 GB | 0.01 GB | **0.04 GB** |
| 10 万 | 0.03 GB | 0.14 GB | 0.06 GB | 0.24 GB | 0.08 GB | **0.32 GB** |
| 100 万 | 0.32 GB | 1.44 GB | 0.56 GB | 2.32 GB | 0.81 GB | **3.13 GB** |
| 500 万 | 1.57 GB | 7.19 GB | 2.82 GB | 11.58 GB | 4.05 GB | **15.63 GB** |

### 3.4 内存需求（`ESTIMATE`：让该阶段**能跑起来**的下限与推荐值）

| 阶段 | `shared_buffers` | `work_mem`×6 | Neo4j pagecache | Neo4j heap | 服务 JVM + 负载生成器 + 系统 | **推荐合计** | **最低合计** |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 万 | 0.25 GB | 0.05 GB | 0.25 GB | 0.50 GB | 2.75 GB | **3.55 GB** | **4.05 GB** |
| 10 万 | 0.25 GB | 0.05 GB | 0.25 GB | 0.50 GB | 2.75 GB | **3.80 GB** | **4.05 GB** |
| 100 万 | 0.50 GB | 0.05 GB | 2.25 GB | 0.75 GB | 2.75 GB | **6.30 GB** | **4.30 GB** |
| 500 万 | 2.25 GB | 0.05 GB | 11.50 GB | 4.00 GB | 2.75 GB | **20.55 GB** | **10.05 GB** |

「最低合计」= pagecache 只装得下约 1/4 store、heap 压到 2 GB 的同机最低配置。**该配置下的 P95 不得当作容量结论**——缓存命中率不足时延迟由磁盘 IO 主导。

「服务 JVM + 负载生成器 + 系统」= query-service 0.75 + core-service 0.75 + 负载生成器 0.35 + 操作系统与页缓存回写余量 0.90 = 2.75 GB。

### 3.5 四档汇总判定（本机 2 vCPU / 7.4 GiB / 0 Swap / 20.3 GB 可用）

| 阶段 | 磁盘合计（PG 规划 + Neo4j） | 含 4 GB 余量后 | 内存推荐 | 内存最低 | 本机判定 |
| --- | ---: | ---: | ---: | ---: | --- |
| 1 万 | 0.17 GB | 4.17 GB | 3.55 GB | 4.05 GB | 可实测（推荐配置） |
| 10 万 | 0.86 GB | 4.86 GB | 3.80 GB | 4.05 GB | 可实测（推荐配置） |
| 100 万 | 7.70 GB | 11.70 GB | 6.30 GB | 4.30 GB | 可实测（磁盘余量仅 8.6 GB，导入/压测期间不得并行生成测试数据卷） |
| 500 万 | **38.12 GB** | **42.12 GB** | **20.55 GB** | **10.05 GB** | **阻断**：磁盘缺 21.8 GB，内存（7.4×0.9=6.66 GB）低于最低 10.05 GB |

## 4. 本机（2 vCPU / 7.4 GiB / 0 Swap / 20.3 GB 可用）的资源事实

### 4.1 实测读数

```
$ nproc                                  → 2
$ awk '/^MemTotal|^SwapTotal/' /proc/meminfo
MemTotal:        7707872 kB              → 7.4 GiB
SwapTotal:             0 kB              → 0 Swap（OOM 会直接 kill 进程，不会变慢）
$ df -BG --output=avail / | tail -1      → 21 GB（其中约 20.3 GB 为 bavail）
$ node tests/perf/capacity-model.mjs --avail-gb=auto --mem-gb=auto
主机读数：磁盘可用 20.3 GB（statfs），内存 7.4 GB（os.totalmem），CPU 2 核
```

### 4.2 为什么 0 Swap 比「内存小」更危险

没有 swap 意味着超配不会表现为「变慢」，而是内核直接 OOM-kill。最可能的受害者不确定（可能是 PG 的 walwriter、Neo4j 的 JVM、query-service，也可能是负载生成器），因此事后既拿不到 P95，也无法判断是哪一层先撑不住——**压测跑完却做不出结论**，比没跑更浪费。

### 4.3 500 万被前置安全门禁阻断的原始证据

```
$ node tests/perf/capacity-model.mjs --avail-gb=auto --mem-gb=auto --disk-path=. --require-scale=5m
=== 前置安全门禁（--require-scale）===
GATE: BLOCKED scale=5m stage=500万 disk_need=42.12GB avail=20.3GB mem_limit=6.66GB mem_avail=7.4GB mode=blocked
  阻断原因：磁盘不足：需要 42.12 GB（含 4 GB 安全余量），可用 20.3 GB，缺 21.82 GB
  阻断原因：内存不足：同机最低配置需 10.05 GB（shared_buffers+pagecache+heap+JVM+负载生成器+系统余量），可用 7.4 GB 的 90% = 6.66 GB
退出码 4
```

对应的数据侧需求区间（按 Neo4j 事务日志是否计入）：

- **不计事务日志**：PG 规划用量 22.50 GB + Neo4j store 11.58 GB = **34.08 GB**；
- **计入事务日志预留**：22.50 + 15.63 = **38.12 GB**；
- 再叠加 4 GB 安全余量（备份副本、`pg_dump`、索引重建临时文件之外的操作余量）后，**实际需要 38.1–42.1 GB 可用磁盘**。

> 任务书/本报告口径中的「33.5–38.1 GB」与上式的 34.08–38.12 GB 是同一件事，差异来自 Neo4j store 估算的 0.5 GB 级取整。**没有任何一种取整方式能让 500 万装进 20.3 GB。**

同时，即使磁盘够，内存也差 3.4 GB（最低 10.05 GB vs 可用 7.4 GB 的 90% = 6.66 GB）。「加一块盘」并不能解除阻断，必须同时加内存。

### 4.4 `--force` 为什么不能解决问题

脚本支持 `--force --force-reason=...`，但那只用于**预览被阻断的计划**。真实执行时会得到：导入到约 2/3 处磁盘写满 → PG 报 `No space left on device`、WAL 无法归档、Neo4j 事务日志写入失败 → 进程退出，数据库处于不可继续导入也不可直接清理的状态。这既不是「500 万容量验证」，也无法从中得出任何可引用的数字。

### 4.5 本机还能做什么（不需要新硬件）

| 能做的事 | 命令 | 产出 |
| --- | --- | --- |
| 容量模型 + 门禁自检 | `node tests/perf/capacity-model.mjs --avail-gb=auto --mem-gb=auto --require-scale=10k` | 四档精算表 + `GATE: PASS/BLOCKED` |
| 压测计划干跑（不连库） | `bash ops/perf/run-scale-benchmark.sh --dry-run --stage=100k` | 逐步命令清单 + 门禁结论 |
| 负载脚本契约校验 | `node tests/perf/load.mjs --dry-run` | 31 个场景 / 13 个接口覆盖表 |
| 结果汇总与验收判定 | `node tests/perf/perf-summary.mjs --dir=<阶段目录> --enforce` | P50/P95/P99 矩阵 + `ACCEPTANCE: PASS/FAIL` |
| 1 万 / 10 万档真实压测 | `bash ops/perf/run-scale-benchmark.sh --only=all --stage=10k --vus=10,30,50,100` | 与阶段五集成测试**错峰**执行，见 §9 风险 R5 |

## 5. 目标云环境的分档配置与复现流程

### 5.1 规格建议

| 阶段 | 建议规格 | 磁盘 | 主要约束 |
| --- | --- | --- | --- |
| 1 万 / 10 万 | 2 vCPU / 8 GiB | 40 GB SSD（可用 ≥ 12 GB） | 无；与开发机同级即可 |
| 100 万 | 4 vCPU / 16 GiB | 100 GB SSD（可用 ≥ 30 GB） | 磁盘 11.7 GB + 余量；pagecache 建议 2.25 GB |
| 500 万 | **8 vCPU / 32 GiB** | **100 GB SSD（可用 ≥ 45 GB）** | 磁盘 42.1 GB（含余量）；pagecache 11.5 GB + heap 4 GB |

以华为云 ECS（8 vCPU / 32 GiB / 100 GB SSD，Ubuntu 22.04）为例。

### 5.2 数据库与服务参数（500 万档）

```conf
# postgresql.conf（8C32G 上的压测实例，不是生产建议）
shared_buffers = 4GB
effective_cache_size = 20GB
work_mem = 32MB
maintenance_work_mem = 1GB
max_wal_size = 8GB
max_connections = 200
max_parallel_workers_per_gather = 4
```

```properties
# Neo4j（neo4j.conf）
server.memory.pagecache.size=11G
server.memory.heap.initial_size=4G
server.memory.heap.max_size=4G
db.tx_log.rotation.retention_policy=1 days   # 仅导入窗口内缩短，导入完成后恢复 7 days
```

```bash
# 服务 JVM
export QUERY_HEAP=1536m        # ops/perf/run-scale-benchmark.sh 读取
```

### 5.3 在云上一键复现（500 万）

```bash
# 0) 依赖
sudo apt-get update && sudo apt-get install -y openjdk-21-jdk maven nodejs npm postgresql-client curl
git clone https://github.com/CognitaNexus/CognitaNexus.git && cd CognitaNexus
git checkout dev && git pull

# 1) 目标：本机 PG + Neo4j 各起一个独立压测实例（不碰开发库）
#    PG 监听 127.0.0.1:55432，Neo4j 监听 17687/17474，与脚本默认值一致
bash ops/local/test-db.sh start           # 或自建实例后 export PG_IT_PORT=<端口>

# 2) 预检：只算容量，不导入
node tests/perf/capacity-model.mjs --avail-gb=auto --mem-gb=auto \
  --disk-path=/var/lib/postgresql --require-scale=5m

# 3) 干跑：确认命令与 jar 路径
bash ops/perf/run-scale-benchmark.sh --dry-run --stage=5m

# 4) 构建服务 jar（压测需要真实服务在线）
./mvnw -B -ntp -Pci -DskipTests -pl services/query-service,services/core-service -am package

# 5) 全流程：导入 → 索引 → 计数 → 执行计划 → 图投影 → 10/30/50/100 并发压测 → 汇总
export CN_TEST_NEO4J_PASSWORD=<压测实例口令>
export PG_PASSWORD=<压测实例口令>
export CN_PERF_REPORTS="$PWD/reports/perf/$(date +%Y%m%d-%H%M%S)"
bash ops/perf/run-scale-benchmark.sh --only=all --stage=5m --vus=10,30,50,100 \
  --warmup=120 --seconds=300 --graph-mode=NEO4J_WITH_PG_FALLBACK --disk-headroom-gb=4

# 6) 验收判定（阈值写死在脚本里：P95 ≤ 2s、错误率 ≤ 1%，不可放宽）
node tests/perf/perf-summary.mjs --dir="$CN_PERF_REPORTS/5m" \
  --expect-vus=10,30,50,100 --enforce
```

第 5 步的产物（`$CN_PERF_REPORTS/5m/`）：

| 文件 | 内容 |
| --- | --- |
| `capacity-model.json` / `.md` | 容量精算与门禁结论 |
| `preflight.env` | 磁盘/内存/swap 读数与判定 |
| `timings.env` | 造数、索引构建、夹具、导出、图投影耗时（秒） |
| `counts.txt` | 分层计数 + 各表 `pg_total_relation_size` + 库体积 |
| `plans/sql-plans.txt` `.tsv` `summary.md` | `EXPLAIN (ANALYZE, BUFFERS)` 原文与逐语句耗时 |
| `graph-load.log` | Neo4j 装载速率、store 体积、实测节点/关系数 |
| `cypher-profiles.txt` | Cypher `PROFILE` 算子树与 dbHits |
| `load-{10,30,50,100}.json` | 分接口 P50/P95/P99、RPS、错误分类、环境快照 |
| `resources-*.tsv` | 每秒 `MemAvailable` / PG RSS / Neo4j RSS / JVM RSS / load1 / 磁盘 |
| `summary.md` / `perf-summary.md` | 人读汇总与 `ACCEPTANCE: PASS/FAIL` |

### 5.4 用 GitHub Actions 在云上 runner 复现

`.github/workflows/perf-scale.yml` 只有 `workflow_dispatch` 一个入口：

```
Actions → perf-scale → Run workflow
  scale            = 5m
  concurrency      = 10,30,50,100
  warmup_seconds   = 120
  duration_seconds = 300
  query_mode       = NEO4J_WITH_PG_FALLBACK
  runner_label     = <自托管/云端 8C32G runner 标签>
```

`runner_label=ubuntu-latest` 时，500 万会在第一步容量门禁就被拦下（`GATE: BLOCKED`，退出码 4），
这是**预期行为**：托管 runner 只有约 14 GB 可用磁盘。

## 6. 压测与容量套件构成

### 6.1 文件清单

| 文件 | 作用 | 校验方式 |
| --- | --- | --- |
| `tests/perf/capacity-model.mjs` | 四档容量精算 + 主机读数（`--avail-gb=auto`）+ 规模门禁（`--require-scale`）+ 标定自检 | `node --check`；`node tests/perf/capacity-model.mjs --require-scale=10k` |
| `tests/perf/load.mjs` | 31 场景 / 13 接口混合负载，分接口与分场景 P50/P95/P99、RPS、错误分类、业务断言 | `node tests/perf/load.mjs --dry-run` |
| `tests/perf/perf-summary.mjs` | 汇总阶段目录为报告 + 验收判定（P95 ≤ 2s、错误率 ≤ 1%） | `node tests/perf/perf-summary.mjs --dir=<dir> --enforce` |
| `tests/perf/explain-graph-and-search.sh` | PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)` + Neo4j `PROFILE` 采集与摘要 | `bash -n`；需 `--db=qperf_*` 才允许执行 |
| `tests/perf/neo4j-project-load.mjs` | PG 导出 TSV → Neo4j 批量装载，记录 `dbHits`、store 体积、行/秒 | `node --check` |
| `tests/perf/scale/seed-scale-query.sql` | 造 n 条成果 + 参与关系 + 全景投影（全部 `SIMULATED`） | psql 执行 |
| `tests/perf/scale/fixtures-graph.sql` | 环/链/等长路径/树/孤立点/枢纽/高作者数结构夹具（`SIMULATED`） | psql 执行 |
| `tests/perf/scale/pg-index-toggle.sql` | 导入前摘索引、导入后重建并分别计时（含快照/回滚） | psql 执行 |
| `tests/perf/scale/counts-scale.sql` | 分层计数与逐表体积 | psql 执行 |
| `tests/perf/scale/explain-plans.sql` | 与检索/统计/网络实际发出的 SQL 同形的固定查询集 | psql 执行 |
| `tests/perf/scale/export-graph-source.sql` | PG → TSV 导出（`data_origin` 取自 `data_nature`，因此只能是 `SIMULATED`） | psql 执行 |
| `ops/perf/run-scale-benchmark.sh` | 编排：预检 → 建库 → 造数 → 索引 → 计数 → 计划 → 图投影 → 压测 → 汇总 | `bash -n`；`--dry-run` |
| `.github/workflows/perf-scale.yml` | 手动触发、带门禁与证据归档的性能压测 | `python3 -c 'yaml.safe_load'`；`bash ops/verify/security-scan.sh` |

### 6.2 覆盖的接口（`load.mjs --dry-run` 可复现）

检索类（66.4% 权重）：成果检索（热/中/冷/零命中/组合/领域/机构/深翻页/REAL 口径隔离）、学者检索、分面、排序白名单、建议、CSV 导出。
网络类（19.8%）：一跳/二跳/三跳子图、环夹具、枢纽超限（必须 503 `GRAPH_SCOPE_TOO_LARGE`，不得截断）、孤立点、3 跳/6 跳路径、超深度、等长路径、自环。
统计类与屏障：概览、机构清单、关键词热点、领域洞察、一致性水位。

业务断言（不是只看状态码）：`hops = path.length - 1`、`pathNodes.length = path.length`、子图每条边两端必须在 `nodes` 内、深度必须回显、超限必须 503 而非截断、零命中必须 `total=0`、REAL 口径在 `SIMULATED` 数据上必须 `total=0`。

### 6.3 指标口径

- **RPS** = 稳态段请求数 / **实测**稳态秒数（不是标称秒数）。
- **分位** = 同并发档、同场景/同接口的样本单独计算；接口级分位由合并样本重算，不是场景分位求平均。
- **错误分类**：HTTP 错误 / 超时（5s 客户端超时）/ 业务失败（HTTP 200 但语义不自洽）三类分开，不合并成一个「错误率」。
- **预热段样本全部丢弃**，含跨界的请求。
- **冷缓存不可测**：本机无 root，不能 `drop_caches`；`--cache=rotate` 只能称「低复用率」。

## 7. 手动触发的性能工作流设计

### 7.1 为什么必须与普通 PR CI 分开

1. 500 万规模是**小时级、吃满磁盘与 CPU** 的作业，塞进 PR CI 会让所有人排队，且容易被取消。
2. 性能结论的可比性依赖固定环境，runner 规格必须由发起人显式指定（`runner_label`）并写进报告。
3. 性能不属于「这份代码能不能上」的判定。混进门禁，就会有人为了让 PR 变绿去调压测参数——这正是 AGENTS.md §3.1 反对的「用参数换绿」。

因此 `.github/workflows/perf-scale.yml` **只有 `workflow_dispatch`**，没有 `push` / `pull_request` / `schedule` 触发。

### 7.2 输入参数

| 输入 | 取值 | 默认 | 作用 |
| --- | --- | --- | --- |
| `scale` | `10k` / `100k` / `1m` / `5m` | `10k` | 去重成果数（`SIMULATED`），与容量模型档位一一对应 |
| `concurrency` | 逗号分隔，1–4 档，1..500 | `10,30,50,100` | 活跃用户档位 |
| `warmup_seconds` | 整数 | `120` | 预热秒数，样本丢弃 |
| `duration_seconds` | 整数 | `300` | 稳态秒数 |
| `query_mode` | `POSTGRES_ONLY` / `NEO4J_WITH_PG_FALLBACK` / `SHADOW_COMPARE` | `POSTGRES_ONLY` | 对应 `cn.query.graph.query.mode` |
| `runner_label` | 任意 runner 标签 | `ubuntu-latest` | 100 万/500 万 必须指向大规格自托管/云端 runner |

输入先经过白名单校验（正则 + 范围），不直接拼进 shell 命令。

### 7.3 工作流阶段

1. **参数校验** —— 白名单 + 范围；500 万 + `ubuntu-latest` 时给出显式警告。
2. **环境清点** —— 核数、内存、磁盘、Java/Node/psql 版本写入 `environment.md`（报告必须能回答「在什么机器上得到的」）。
3. **容量与资源前置安全门禁** —— `capacity-model.mjs --require-scale=<scale> --avail-gb=auto --mem-gb=auto`；非零退出即终止，**不进入导入**。
4. **服务容器** —— `postgres:16-alpine` + `neo4j:5.26-community`，tag 钉死，健康检查直接问数据库（`pg_isready` / `cypher-shell RETURN 1`），不用端口探测代替。
5. **构建 jar** —— `./mvnw -Pci -DskipTests -pl services/query-service,services/core-service -am package`。
6. **导入 + 图投影 + 计划 + 压测** —— `run-scale-benchmark.sh --only=all`，内部含预检、摘/建索引分别计时、`SIMULATED` 数据性质核对、每秒资源采样。
7. **汇总与验收判定** —— `perf-summary.mjs --enforce`，缺并发档或任一关键接口超阈即失败。
8. **产物归档** —— `reports/perf/**` 上传为 Artifact（保留 90 天）；失败时另附 PG/Neo4j 容器日志。

### 7.4 与安全门禁的一致性

`bash ops/verify/security-scan.sh` 覆盖本工作流的三类硬约束：

- 顶层 `permissions: contents: read`（无写权限）；
- 所有 `uses:` 钉 `@v4`（不接受分支名）；所有 service 镜像钉 tag（无 `:latest`）；
- 无 `pull_request_target`、无 secret 打进日志、无部署凭据。

凭据只有两个**一次性测试口令**（`cn_perf_pg_2026` / `cn_test_neo4j_2026`），写在临时 service 容器里，runner 销毁即失效，与 `ci.yml` 的 graph job 一致。

## 8. 数据性质与合规（不可省略）

1. 所有生成的成果、学者、参与关系、机构、领域、合作边、图节点与边均为 `SIMULATED`：
   - `qry_achievement.data_nature = 'SIMULATED'`；
   - 导出到 Neo4j 的 `data_origin` 直接取自 `data_nature`，因此图中不存在 `REAL` 节点；
   - 编排脚本在造数后**核对** `data_nature` 分布，发现任何非 `SIMULATED` 行立即中止（退出码 4）。
2. 默认 `dataNature=REAL` 口径下，这批数据一条都检索不到——这是数据性质隔离在生效。容量探索必须显式 `dataNature=ALL` 或 `SIMULATED`，报告里也要写「演示口径」。
3. 未造：`raw_source_record` / `core_achievement_source` / `core_identifier_registry`。批量伪造来源会制造「两个镜像文件当两个独立来源」的伪多源，宁可留空并写明。
4. 合成数据**不得**写成「真实收录量」；「≥100 万真实去重成果（G1）」只能由真实采集管道的数据兑现。

## 9. 风险、未覆盖项与不可引用清单

| 编号 | 风险 / 未覆盖项 | 影响 | 处置 |
| --- | --- | --- | --- |
| R1 | 本机 2C8G/20.3 GB 无法执行 500 万 | G2 未取得 | 换 8C32G + 100 GB SSD（§5），门禁已能提前拦住，不会带病导入 |
| R2 | Neo4j 字节数是 `ESTIMATE`，未在 500 万实测标定 | 容量表 ±20% 量级 | 1 万阶段用 `du -sb` 回填标定系数；报告须标注「未标定」 |
| R3 | V5/V6 trigram 索引体积是 `ESTIMATE`（±60%） | 100 万以上磁盘可能低估 | 1 万阶段用 `pg_indexes` + `pg_relation_size` 实测回填 |
| R4 | CI service 容器的 Neo4j store 在容器内，宿主机 `du` 不可见 | 工作流产物的 `store` 体积为空 | 报告写「未采到」，不猜数字；需要精确值时在 VM 上跑而不是容器内 |
| R5 | 本机 55432 PG / 17687 Neo4j 同时被阶段五集成测试使用 | 资源互相干扰，压测数字失真 | 压测与本机测试**错峰**执行；工作流在独立 runner 容器内跑，天然隔离 |
| R6 | 托管 runner 磁盘/内存规格固定 | 1 万/10 万 可跑，100 万勉强，500 万被阻断 | 用 `runner_label` 指向大规格 runner |
| R7 | 冷缓存不可测（无 root，不能 `drop_caches`） | 报告不能声称「冷启动 P95」 | 只报「warm」与「rotate（低复用率）」两档，并写明限制 |
| R8 | 服务、数据库、负载生成器同机 | CPU/IO 争抢，P95 偏悲观 | 报告写明同置；独立部署数据待云端双机复测 |
| R9 | 2026-10-17 的 100 万/100 并发结果**未达标** | 不能引用为阶段六通过 | §1.2 已如实登记；修复项（统计概览预计算、连接耗尽语义 503）属设计变更，需单独评审 |

**不可引用清单**：任何未在本文 §1.2 明确登记为「已执行」的运行，不得作为性能或容量证据。

## 10. 尚未执行、需要谁在什么环境执行的清单

| # | 待执行项 | 环境 | 命令 | 产出证据 |
| ---: | --- | --- | --- | --- |
| 1 | 1 万档全流程（含图投影与 4 档并发） | 本机（与阶段五测试错峰） | `bash ops/perf/run-scale-benchmark.sh --only=all --stage=10k` | `reports/perf/<stamp>/10k/` 全部文件；据此回填 Neo4j 与索引体积标定系数 |
| 2 | 10 万档全流程 | 本机或 4C16G | `--stage=100k` | 同上；验证「规模 ×10 后延迟/体积的非线性」 |
| 3 | 100 万档全流程 | 4C16G / 100 GB SSD | `--stage=1m --graph-mode=NEO4J_WITH_PG_FALLBACK` | 与 2026-10-17 基线对照（PG 路径）与新图路径对照 |
| 4 | 500 万档全流程 | 8C32G / 100 GB SSD | `--stage=5m` | G2 容量证据（标注 `SIMULATED`） |
| 5 | 关键接口 P95 ≤ 2s 与错误率 ≤ 1% 达成 | 同上，且需先完成统计概览口径修复 | `perf-summary.mjs --enforce` | `ACCEPTANCE: PASS` |
| 6 | ≥100 万真实去重成果（G1） | 生产目标环境 | 真实采集管道 | 分层计数（REAL） |

在上述执行完成之前，阶段六的正确表述是：**测试套件、容量模型、门禁与手动工作流已就绪；性能与容量验收结论尚未取得。**
