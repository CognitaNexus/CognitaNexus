# ops/compose — 容器化拓扑

## 这一目录的职责

承载**服务器部署**使用的容器定义。本机开发走 `ops/local` 下的原生二进制脚本（原因见下）。

| 文件 | 作用 |
| --- | --- |
| `../../compose.yaml` | 全栈基础拓扑：PostgreSQL 16 + 两个 Redis + 三个服务 + nginx |
| `compose.server.yaml` | 服务器覆盖：不外露数据库与后端端口，只保留前端一个入口 |
| `init-databases.sh` | 数据卷首启时创建三个库与六个角色（owner/app 分离） |
| `maven-settings.xml` | 容器内 Maven 构建参数（阿里云镜像，不指定本地仓库路径） |

服务镜像定义位于各自的服务目录：`services/*/Dockerfile`；前端镜像定义位于 `frontend/Dockerfile`，
配套 `frontend/nginx.conf`。

## 为什么本机不用这套拓扑

本机 Docker Desktop 依赖 WSL2，而 WSL 被安全策略拦截，容器无法启动。因此本机开发改用
D 盘原生二进制运行 PostgreSQL 16 与 Redis 8，启动脚本为 `ops/local/start-infra.sh`。

**后果必须说清楚**：`compose.yaml` 与 `compose.server.yaml` **未在本机验证过**。它们的路径前缀
约定、环境变量名、端口映射都按已在本机跑通的原生部署对齐，但「对齐了」不等于「验证了」。

## 用法

```bash
# 在仓库根执行
cp .env.example .env          # 至少填 CN_BOOTSTRAP_ADMIN_PASSWORD，其余改成非默认值
docker compose up -d --build
docker compose ps             # 等 healthcheck 全部 healthy

# 服务器
docker compose -f compose.yaml -f ops/compose/compose.server.yaml up -d --build

# 停止并保留数据
docker compose down

# 停止并删除数据（会丢全部数据，只在确认不需要时执行）
docker compose down -v
```

默认入口：`http://<host>:8080/`（`CN_WEB_PORT` 可改）。前端经 nginx 同源代理三个后端，
`/core`、`/ingest`、`/query` 三个前缀分别转发到对应服务——与开发期 vite 代理的路径约定一致。

## 首次启动会自动发生什么

1. PostgreSQL 数据卷为空 → 执行 `init-databases.sh`，创建 `core_db` / `ingestion_db` / `query_db`
   与各自的 owner/app 角色，并撤销 `PUBLIC` 连接权限。
2. 三个服务启动，各自的 Flyway 用 **owner 账号**执行迁移（`core` 有 V1–V8，`ingestion` 有 V1–V3，
   `query` 有 V1–V2）。迁移脚本内含对 app 账号的 `GRANT`，因此运行账号的权限随表结构演进一起被审计。
3. `core-service` 在库中不存在活跃平台管理员时，用 `CN_BOOTSTRAP_ADMIN_*` 创建一次引导管理员，
   之后自动跳过。

## 需要注意的三个失败模式

这三条都不是「配置写错了」，而是配错之后症状看起来像别的问题：

1. **服务间令牌不一致**。`CN_QUERY_SERVICE_TOKEN` 在 core 与 query 两侧必须是同一个值。不一致时
   事件会被 403 拒收，现象是**查询侧水位不动**，日志里没有明显异常。
2. **迁移账号与运行账号写反**。运行账号没有 DDL 权限，Flyway 用它会在启动时直接失败并退出。
   反过来（服务用 owner 账号连库）不会立刻失败，而是悄悄绕过了「运行账号无 DDL」这条约束。
3. **`CN_CACHE_REDIS_PASSWORD` 与 `CN_SESSION_REDIS_PASSWORD` 混用**。两个实例的口令不同，
   混用后症状是「登录态偶发丢失」，而不是连接被拒——另一个实例的口令校验会直接拒绝连接，
   这个则会连上但读写不到会话。

## 待实测项

以下各项在容器拓扑下**尚未实测**，不得据此宣称部署拓扑已验证：

- 镜像构建是否成功（Maven 依赖在容器内能否全部拉到）
- 三个服务的启动顺序与 healthcheck 是否如预期收敛
- nginx 代理转发与 Cookie/CSRF 在容器网络下的行为
- 前端产物在 nginx 下的路由回退（`try_files` → `index.html`）
- 服务器覆盖配置的端口收敛与资源占用
