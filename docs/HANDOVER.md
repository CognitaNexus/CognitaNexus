# CognitaNexus v1 工程交接

> 整理时间：2026-11-11。保留功能入口与已知缺口，删除旧机器地址、登录口令、绝对路径和不适用于所有成员的临时操作建议。历史详版可从 `backup/dev-first-release-20261111` 查看；那份历史信息不代表当前运行环境。

## 当前架构
- `core-service`（默认 8081）：身份、认领、成果权威数据、治理、授权/审计。
- `ingestion-service`（默认 8082）：公开来源采集、字段映射、任务与交接。
- `query-service`（默认 8083）：检索、统计、网络、读投影。
- `frontend`：Vue 3 / Vite，Nginx 可对外代理三服务。
- PostgreSQL 三库：`core_db`、`ingestion_db`、`query_db`；Redis 分会话与缓存。
- 写侧 Outbox → 查询侧 Inbox/投影，发布水位与公开资格由现有服务处理。

## 快速验证
```bash
./mvnw -B verify
cd frontend && npm ci && npm run build && cd ..
# 需本地测试环境，不得指向生产数据库
bash ops/verify/seed-roundtrip.sh
```

可选的本地 Compose：`cp .env.example .env` → 配置非默认密钥与管理员口令 → `docker compose config` → `docker compose up -d --build`。请先核对目标环境；原交接材料并未证明 Compose 在任何目标机端到端成功。

## 数据与性能（历史样本，不是 2026-11-11 实时读数）
2026-10-24 的工程记录：`core_achievement` 28,616、公开有效 19,201、待处理候选 28,407。2026-10-17 的 100 万 **SIMULATED** 数据、100 VU 容量实验**未达到** P95/成功率要求。真实增量采集与深分页、治理队列、性能和华为云适配仍需完成验证。

## 交接检查项
1. 核对运行环境、部署版本 SHA、服务健康、数据库 Flyway 历史和实际备份状态。
2. 在隔离环境运行单测、接口与权限测试；生产写入脚本禁止作为 smoke 测试。
3. 只从受控密钥管理读取凭据；不要拷贝个人目录里的口令文件到仓库。
4. 核对采集任务、回退/暂停状态、来源许可、Outbox/Inbox 水位与治理积压。
5. 涉及服务器/云服务变更先出迁移与回滚方案，不默认目前已迁移华为云。

详细任务见 GitHub Issues #6–#16，代码与需求追踪见 [README](../README.md) 和 [SRS V2.2 / 69 AC](https://github.com/CognitaNexus/CognitaNexus/tree/analysis/s01-s02-69ac)。
