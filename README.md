# CognitaNexus 学术成果分享平台

学术成果的采集、去重治理、学者认领、检索、统计和合作网络平台。**本仓库以工程实施为主。课程文档以 2026-11-11 提供的《软件系统分析与设计.zip》为定稿依据；公开发布前需筛选其中涉及个人信息的附件。参见 [定稿目录说明](docs/README.md)。**

## 当前版本

- **v1 工程基线**：由 `dev` 的 2026-10-24 快照整理而来；2026-11-11 首次纳入 `main`。这是代码版本整理，**不代表全部 69 AC 已验收**。
- 后端：Java 21 / Spring Boot 3.5 / Maven，`core-service`、`ingestion-service`、`query-service` 三个可独立部署单元。
- 前端：Vue 3 / TypeScript / Vite。数据：PostgreSQL（`core_db`、`ingestion_db`、`query_db`，各服务独立归属）与 Redis（会话 / 缓存）。
- `core` 负责业务事实与授权；`ingestion` 负责采集和交接；`query` 负责检索、统计、网络及读投影。跨服务同步沿用 Outbox / Inbox。
- R1–R7 是逻辑能力编号，不等于七个独立微服务。华为云 ECS/RDS/OBS/CSS/DCS 拓扑属于目标方案，不能当作已经部署的事实。

## 开发入口

```bash
git clone https://github.com/CognitaNexus/CognitaNexus.git
cd CognitaNexus
./mvnw -B verify
cd frontend && npm ci && npm run build
```

部署资产：`compose.yaml`、`ops/compose/`（容器部署候选），`ops/deploy/`（已有 systemd / Nginx 方案）。部署前复制 `.env.example` 为 `.env` 并设置非默认口令、访问令牌；**不要将凭据提交仓库**。Compose 的具体目标机器与端到端部署仍需在目标环境重新验证。

本地启停与只读健康检查：见 [RESTART.md](RESTART.md)；部署时以 `ops/` 下的实际配置、环境变量及验证结果为准，历史运维结论不可代替现状。

## 主要目录

| 目录 | 用途 |
| --- | --- |
| `services/core-service/` | 账号、授权、认领、成果与治理 |
| `services/ingestion-service/` | 采集源适配器、任务与交接 |
| `services/query-service/` | 查询、统计、合作网络与投影 |
| `libraries/shared-kernel/` | 跨服务最小共享类型 |
| `frontend/` | 用户端和管理端 |
| `ops/` | 环境、迁移、导入/备份和运维 |
| `tests/` | 接口、浏览器与性能验证 |
| `docs/` | 已确认的文档基线索引、必要工程约束与架构决策，见 [导航](docs/README.md) |

## 需求与当前问题

- 课程定稿：用户提供的 2026-11-11 压缩包，其中 SRS 为 V2.1（69 条 AC）；旧 [`analysis/s01-s02-69ac`](https://github.com/CognitaNexus/CognitaNexus/tree/analysis/s01-s02-69ac) 分支为历史分析成果（SRS V2.2），不自动覆盖课程定稿。
- 需要改进：真实数据规模、增量导入、治理积压、100 万数据下的查询性能，以及部署验证；历史测试结果不等于新环境通过。
- [开发 Issues](https://github.com/CognitaNexus/CognitaNexus/issues)；[分支与 PR 规范](docs/BRANCHING.md)。

**请不要在本仓库发布账号口令、SSH 密钥、原始私有证明材料或生产环境信息。**
