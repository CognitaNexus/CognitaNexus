# 阶段一环境检查报告（归档）

| 项目 | 内容 |
| --- | --- |
| 报告类型 | 阶段一【服务器环境准备与验收】检查报告（归档版） |
| 原始执行 | environment-agent，零 sudo、零容器守护进程 |
| 实测时间窗 | 2026-11-25 10:20–11:05 CST |
| 归档时间 | 2026-11-25，随 `chore/ci-foundation` 一并入库 |
| 本机角色 | 开发/验证机（2 vCPU / 7.4 GiB / 无 Swap），**不是生产服务器** |
| 结论 | 隔离测试实例（PostgreSQL 16 / Redis 7 / Neo4j 5）可用且已跑通全部集成测试；测试拓扑仍与目标部署拓扑不同，不得据此声称已部署 |

> **口径声明（先读这一段）**
>
> 1. 本文所有数值都来自 2026-11-25 的一次实测，命令见各节；未执行的项目逐项标注「未验证」。
> 2. 「目标是华为云 ECS/RDS/OBS/CSS」是**目标拓扑**，本机只是验证机。本机跑通 ≠ 已部署。
> 3. 三个隔离实例由**原生二进制**进程管理（`/home/admin/cn-test-runtime/`），不是 Docker 容器；
>    它们与 `compose.yaml` 的容器实例「同版本、同端口语义」，但形态不同，报告里分开写。
> 4. Redis 实例是 **7.0.15**，而 `compose.yaml` 里是 `redis:8-alpine`：**版本不等价**，
>    仅用于会话/缓存类测试，不得用于证明 8.x 行为。
> 5. Neo4j 目前**没有接入任何业务代码**（仓库零 `org.neo4j` 依赖）。它只是为「图能力」预留的
>    验证实例；按 AGENTS.md §1，引入它需要 ADR 与范围裁决，详见第 9 节。

---

## 1. 硬件、OS 与资源

| 项 | 实测值 | 采集命令 |
| --- | --- | --- |
| OS | Ubuntu 24.04.5 LTS (noble)，kernel `6.8.0-139-generic` x86_64 | `lsb_release -a` / `uname -a` |
| CPU | 2 vCPU（Intel Xeon Platinum，1 core/socket × 2 threads） | `nproc` / `lscpu` |
| 内存 | 总量 7.4 GiB，任务开始时 available 4.7 GiB | `free -h` |
| Swap | 0 B（`/proc/swaps` 为空）→ 构建与测试必须串行 | `cat /proc/swaps` |
| 磁盘 | `/dev/vda3` 40 G，已用 14 G，可用 24 G | `df -h /` |
| 磁盘类型 | `lsblk` 报 `ROTA=1`（virtio 以 rotational 上报，**未做 fio 校验，不得称 SSD**） | `lsblk -d -o NAME,ROTA,SIZE,MODEL` |
| cgroup 内存上限 | `/sys/fs/cgroup/memory.max` 为空，未取得上限；以 `free` 的 7.4 GiB 为基线 | 同上 |
| 项目默认端口 | `5432/6379/6380/7474/7687/8080-8083` 全部无监听（故隔离端口不与默认端口冲突） | `ss -ltn` |

## 2. 工具链

| 工具 | 版本 / 状态 | 备注 |
| --- | --- | --- |
| Git | 2.43.0 | 可用 |
| Java | OpenJDK 21.0.13.1 | `JAVA_HOME` 未设置，需显式传入 |
| Maven | 3.9.16（仅 wrapper），`mvn` 不在 PATH | **`./mvnw` 原先无执行位（git 记录 100644）→ `chore/ci-foundation` 已改为 100755** |
| Node / npm | v22.23.3 / 10.9.9 | 可用 |
| Docker | client 29.9.2 / Compose v5.6.1 | `admin` 不在 `docker` 组，`docker ps` = permission denied |
| sudo | `sudo -n true` → 需口令 | 本会话零 sudo |
| 系统 PG/Redis/Neo4j | 均不存在 | 决定了必须自建运行时 |

## 3. 隔离测试实例（实测，运行中）

统一入口：`/home/admin/cn-test-runtime/bin/{up,down,status}.sh`。
`status.sh` 用真实命令判定三项健康，**rc=0 才算就绪**：

```
PostgreSQL 16  : PostgreSQL 16.15 (Ubuntu 16.15-0ubuntu0.24.04.1) ... dbs=core_it,ingestion_it,query_it
Redis          : PONG (redis_version:7.0.15)
Neo4j 5        : 5.26.34 community bolt=bolt://127.0.0.1:17687
Neo4j cypher   : RETURN 1 -> 1
监听地址收敛   : 127.0.0.1:16379 127.0.0.1:55432 [::ffff:127.0.0.1]:17474 [::ffff:127.0.0.1]:17687
rc=0
```

### 3.1 PostgreSQL 16.15 @ `127.0.0.1:55432`

- 取得方式：`apt-get download postgresql-16 postgresql-client-16 ...` + `dpkg-deb -x`，无需 root。
- 初始化：`initdb -D pgdata/it -U postgres --auth=trust --encoding=UTF8 --locale=C`（trust 仅绑 127.0.0.1）。
- 角色：`postgres`（口令 `postgres_it`）与 `core_owner/core_app/ingestion_owner/ingestion_app/query_owner/query_app`
  （均 LOGIN，口令 `<role>_it`）——与 `PostgresTestDatabase.newPool()` 的 `user + "_it"` 约定一致。
- 库：`core_it`(core_owner) / `ingestion_it`(ingestion_owner) / `query_it`(query_owner)。

### 3.2 Redis 7.0.15 @ `127.0.0.1:16379`

- `redis-server --port 16379 --bind 127.0.0.1 --save '' --appendonly no`；`redis-cli ping` → `PONG`。
- 已知告警：`WARNING Memory overcommit must be enabled!`（需 root 打开 `vm.overcommit_memory`）。
  本实例无持久化、仅测试用途，**不修**，登记为风险。

### 3.3 Neo4j 5.26.34 Community @ `127.0.0.1:17474` (HTTP) / `17687` (Bolt)

- 官方 tarball 渠道全部被拒（`dist.neo4j.org` 403 / S3 000），改为**无 daemon 的 OCI 拉取**：
  经 `dockerproxy.net` 取 `library/neo4j:5.26-community` 的 manifest 与 5 个 blob（约 339 MB），
  逐层 `tar -xzf` 到 `neo4j-rootfs/`。
- 镜像化差异修正三处：`data`/`logs` 符号链接换成本地目录；`neo4j.conf` 重复声明 `server.https.enabled` 删除；
  `advertised_address` 与实际监听端口对齐。
- 初始口令：`neo4j-admin dbms set-initial-password`（测试口令，非生产）。
- 健康：`GET /` 返回 `neo4j_version=5.26.34 / edition=community / bolt_direct=bolt://127.0.0.1:17687`；
  `cypher-shell ... "RETURN 1"` → `1`。

### 3.4 与 CI 的对应关系（本次新增）

阶段一在本机自建实例；CI 里用 GitHub-hosted runner 的**临时 service 容器**复现同一套检查：

| 维度 | 阶段一本机 | CI（`.github/workflows/ci.yml`） |
| --- | --- | --- |
| PostgreSQL | 16.15 原生二进制，`127.0.0.1:55432` | `postgres:16-alpine` service 容器，`55432:5432` + healthcheck |
| 角色/库 | `test-db.sh` 建角色与三个 `*_it` 库 | `docker exec psql` 建同样的角色与库（同约定 `user_it` 口令） |
| Redis | 7.0.15，`127.0.0.1:16379` | `redis:7-alpine` service 容器，`16379:6379`（cross-service job） |
| Neo4j | 5.26.34，`17474/17687` | `neo4j:5.26-community` service 容器，`17474:7474`/`17687:7687` + cypher-shell healthcheck |
| 图烟雾测试 | 手工 cypher-shell | `ops/verify/neo4j-ci-smoke.sh`（约束/索引/导入/查询/最短路径，失败即非 0） |

## 4. 原有项目基线（本机实测）

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 编译 | `sh ./mvnw -B test-compile` | BUILD SUCCESS（约 2:55） |
| 单测 + 门禁 | `sh ./mvnw -B verify` | BUILD SUCCESS（约 2:47）；**927** 单测全通过；4 条 JaCoCo 包级门禁达标；spotless 通过 |
| 集成测试 | `sh ./mvnw -B -fae verify -Dcn.it.skip=false -Dspotless.check.skip=true` | 8 个 `*IT.java` / **34** 个用例全绿（连续 3 轮 + 实例冷重启后 1 轮） |
| 前端 | `npm ci` / `npm run build` / `npm test` | 全绿；单测 11/11 |

单测分布：shared-kernel 14 ｜ core 586 ｜ ingestion 167 ｜ query 160。

集成测试分布（34 用例）：

| IT 类 | 用例数 |
| --- | --- |
| `core.it.CoreSchemaInvariantsIT` | 8 |
| `core.it.ClaimConcurrencyIT` | 5 |
| `core.it.IntegrityNoticeSchemaIT` | 5 |
| `core.it.SourceEvidenceConflictIT` | 4 |
| `core.it.SourceFieldWhitelistIT` | 3 |
| `core.it.SourceIntakeDisambiguationIT` | 3 |
| `query.it.ProjectionRecoveryIT` | 4 |
| `query.it.SearchSnapshotIT` | 2 |

`chore/ci-foundation` 在 Git worktree 中复跑了其中最关键的两条命令（见 PR 的「实际运行」）：
`./mvnw -B verify`（927 单测）与 `-Pci verify`（core 28 IT、query 6 IT），结果一致。

## 5. 网络可达性矩阵（2026-11-25 实测）

| 目标 | 结果 | 用途 / 结论 |
| --- | --- | --- |
| `mirrors.cloud.aliyuncs.com/ubuntu/` | 200 | 取 PG/Redis deb |
| `repo.maven.apache.org/maven2/` | 200 | Maven 依赖 |
| `registry.npmjs.org` / `registry.npmmirror.com` | 200 | 前端依赖 / 审计 |
| `api.github.com` | 200 | — |
| `dist.neo4j.org`、`neo4j.com/artifact.php` | 403 | 官方 tarball 渠道不可用 |
| `registry-1.docker.io` / `auth.docker.io` | 000（超时） | Docker Hub 直连不可用 |
| `dockerproxy.net/v2/` | 200 | ✅ 本次 Neo4j 镜像落地通道 |

> 说明：这条矩阵只描述**本机**网络。GitHub-hosted runner 直接从 Docker Hub / Maven Central 拉取，
> 不受本机限制；因此 CI 用官方镜像，而本机用「无 daemon 的 OCI 拉取 + 原生二进制」绕行。

## 6. 阶段一 9 项验收（按任务描述反推，逐项判定）

> 仓库内没有「阶段一 9 项」原文，以下按任务描述反推；如与任务书冲突以原文为准。

| # | 验收项 | 判定 | 证据 | 缺口 / 保真度 |
| --- | --- | --- | --- | --- |
| 1 | 硬件与 OS 基线记录 | ✅ | §1 | `ROTA` 未做 fio，不得称 SSD；cgroup 上限未取到 |
| 2 | 工具链版本核对 | ✅ | §2 | `./mvnw` 原无执行位（已在本次修复） |
| 3 | PostgreSQL 16 隔离实例 | ✅ | §3.1 + 34 IT 真实迁移/事务/并发 | 原生二进制形态，非容器 |
| 4 | Redis 隔离实例 | ✅ | §3.2 `PONG` | 7.0.15 ≠ `redis:8-alpine`，不得称等价 |
| 5 | Neo4j 隔离实例 | ✅（技术）/ ⚠️（范围） | §3.3 | 仓库零 Neo4j 依赖，接入需 ADR；不接业务 env |
| 6 | 实例具备健康检查 | ⚠️ 达成但形态不同 | `status.sh` 用真实命令并返回 rc | 是进程级探针脚本，不是 compose `healthcheck:` |
| 7 | 与业务数据完全隔离 | ✅ | 端口 55432/16379/17474/17687；库名全 `*_it`；IT 内部断言 jdbc 串含 `:55432/` | 隔离强度高于「同实例换库名」 |
| 8 | 原有项目编译验证 | ✅ | §4 | 2C8G 无 Swap → 全部串行 |
| 9 | 可移植 Compose + 华为云迁移文档 | ⚠️ 部分 | 可解析 `compose.yaml`（需先注入必填变量，见 §7 D1）；测试实例复现手册见 §10 | **未产出** `ops/compose/compose.test-infra.yaml` 与华为云迁移文档；属待办交付物 |

## 7. 对审查意见 D1–D4 的勘误（已并入本报告）

| 编号 | 原报告问题 | 更正 |
| --- | --- | --- |
| D1 | `docker compose config -q` 命令不可复制（exit=1） | `compose.yaml` 的 `CN_BOOTSTRAP_ADMIN_PASSWORD` 用 `${...:?}` 强制必填，仓库刻意不提供 `.env`。正确命令：`CN_BOOTSTRAP_ADMIN_PASSWORD=x docker compose -f compose.yaml -f ops/compose/compose.server.yaml config -q`（→ 0） |
| D2 | `mem_limit` 求和自相矛盾（4.6 / 5 GiB） | 统一为 **5248 MiB ≈ 5.1 GiB**（1024+128+384+1536+1024+1024+128）。宿主机 7.4 GiB 无 Swap → 全栈容器化不安全，阶段一只容器化基础设施或不走容器 |
| D3 | 漏检 rootless Docker 与 userns | 本机存在 rootless 组件，但 `unshare --user` 被 AppArmor 拒绝（`apparmor_restrict_unprivileged_userns=1`），且 `slirp4netns/fuse-overlayfs/newuidmap` 均缺失 → **无可用 rootless 容器路径** |
| D4 | 把「白名单 sudo 执行 docker」列为低风险 | 更正：任何 docker 命令都等价 root（`--privileged -v /:/host`、任意 compose 文件）。白名单 sudo 与加入 docker 组**权限语义等价**，只是持久性不同，不构成风险降低 |

## 8. 环境检查发现的缺陷与处置

| # | 缺陷 | 影响 | 处置 |
| --- | --- | --- | --- |
| 1 | `frontend/.npmrc` 的 `cache=D:/dev-tools/npm-cache` 在 Linux 下生成 `frontend/D:/`（约 18 MB） | `git status` 变脏、构建上下文变大 | **本 PR 删除该行**（保留 registry/fund/audit），并在 CI 增加「不得出现 `D:/` 目录」的回归断言 |
| 2 | `SourceEvidenceConflictIT.concurrentFirstImportDoesNotDuplicateEntity` 存在测试侧竞态（首轮 1 次失败，其后 4 轮通过） | CI 可能出现偶发红 | **本次不修**（属产品语义之外：`ACCEPTED`/`ATTACHED` 两种顺序都满足「不重复实体」）。登记为已知风险，建议独立 PR 用 latch 固定顺序或放宽断言 |
| 3 | 集成测试默认跳过（`cn.it.skip=true`）且无任何门禁发现「IT 没跑」 | CI 可能绿着上线未验证的 SQL | 新增 `-Pci` profile + `ops/verify/ci-test-report-gate.sh`（源码每个 `*IT.java` 必须有报告、`skipped=0`、覆盖率 include 必须匹配到非空包） |
| 4 | `mvnw` 缺执行位 | 所有文档里的 `./mvnw` 都不能直接执行 | 本次 `git update-index --chmod=+x mvnw`，并在 CI 里显式 `chmod +x` |

## 9. Neo4j 的范围说明（重要）

- 仓库**没有任何 `org.neo4j` 依赖**，合作网络目前由 PostgreSQL 的 `qry_coauthor_edge` 实现。
- 阶段一启动的 Neo4j 实例是**旁挂验证实例**：不接任何业务 env、不参与部署、不进入 `compose.yaml`。
- 按 AGENTS.md §1，引入新的图数据库需要 ADR（收益、成本、一致性、回退路径）与范围裁决。
  因此 CI 的 `graph-integration` job 现在做的是：**证明图能力的基础设施可用**
  （`ops/verify/neo4j-ci-smoke.sh`：唯一约束、索引、导入、查询、最短路径，失败即非 0），
  并为将来的 `*Graph*IT / *Neo4j*IT` 打通执行通道（当前 0 个用例，以 notice 说明，不伪装成「已验证」）。

## 10. 复现手册（零特权，照抄可用）

```bash
# 0) 一次性准备
R=/home/admin/cn-test-runtime && mkdir -p $R/{debs,pgsql,redis,logs,neo4j-layers}
cd $R/debs && apt-get download postgresql-16 postgresql-client-16 \
    postgresql-client-common postgresql-common libpq5 ssl-cert \
    redis-server redis-tools liblzf1 libjemalloc2
cd $R && for f in debs/*.deb; do case $f in *redis*|*liblzf*|*libjemalloc*) dpkg-deb -x $f redis/;; \
    *) dpkg-deb -x $f pgsql/;; esac; done

# 1) PostgreSQL（首次）
export LD_LIBRARY_PATH=$R/pgsql/usr/lib/x86_64-linux-gnu:$R/pgsql/usr/lib/postgresql/16/lib
$R/pgsql/usr/lib/postgresql/16/bin/initdb -D $R/pgdata/it -U postgres --auth=trust --encoding=UTF8 --locale=C
# 建 6 个角色（口令 <role>_it）与 core_it / ingestion_it / query_it

# 2) Neo4j（首次，无 docker；详见 §3.3）
#    manifest → 5 个 blob → 解包 → 修 data/logs 符号链接 → conf 覆盖
#    → JAVA_HOME=... bin/neo4j-admin dbms set-initial-password <测试口令>

# 3) 日常（幂等）
$R/bin/up.sh && $R/bin/status.sh     # rc=0 即三项就绪
$R/bin/down.sh                       # 保留数据目录

# 4) 验收
cd /home/admin/CognitaNexus
./mvnw -B verify
./mvnw -B -Pci -Dcn.it.skip=false -Dspotless.check.skip=true verify    # 8 个 *IT
cd frontend && npm ci && npm run build && npm test
NEO4J_PASSWORD=<测试口令> bash ops/verify/neo4j-ci-smoke.sh
```

口令（仅本机测试实例，非生产、不入库）：`postgres/postgres_it`、`<role>_it`、Neo4j `neo4j/<测试口令>`。

## 11. 风险与回滚

| 项 | 说明 |
| --- | --- |
| 实例生命周期 | `bin/down.sh` 停三项；`rm -rf /home/admin/cn-test-runtime` 即完全清除，不残留系统文件、不占默认端口 |
| 仍在运行 | PG 55432 / Redis 16379 / Neo4j 17474+17687（有意保留供后续阶段使用） |
| 已知告警 | Redis `vm.overcommit_memory` 未开（需 root；无持久化，不修） |
| 不可用于结论的场合 | 不得以本机实例证明「已部署到华为云」；不得以 Redis 7.0.15 证明 8.x 行为；不得以 Neo4j 旁挂实例证明「图能力已接入业务」 |
| 待裁决 | Neo4j 是否纳入正式架构（需 ADR）；`SourceEvidenceConflictIT` 竞态修复；阶段一第 9 项文档交付物（测试 compose + 迁移文档） |
