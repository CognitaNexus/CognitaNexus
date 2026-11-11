# 本地开发与启动检查

此文档只提供可移植入口，不公开演示账号密码或旧设备绝对路径。

## 编译与验证
```bash
./mvnw -B verify
cd frontend && npm ci && npm run build && cd ..
```

## Docker Compose（需具备 Docker 环境）
```bash
cp .env.example .env
# 修改 .env 中全部密码、管理员初始口令和服务间令牌（不要提交）
docker compose config
docker compose up -d --build
docker compose ps
docker compose logs --tail=100
```

`compose.yaml` 与 `ops/compose/compose.server.yaml` 是部署配置，不表示目标机器上已实际启动。请勿对生产环境使用测试脚本。

## 其他运行方式
原生开发脚本可参照 `ops/local/`，其中部分脚本依赖原开发者 Windows 路径，跨平台使用前需要检查、修订。已有 systemd 方案位于 `ops/deploy/`，涉及服务器权限、备份和部署操作必须由被授权成员按实际环境执行。

## 排错优先级
1. 查看进程健康检查、端口监听与 Nginx 代理前缀。
2. 检查 PostgreSQL 三库各自 Flyway 版本及 Redis 连接。
3. 检查 query 投影水位，勿把「数据在 core 但 query 尚未重建」视为导入失败。
4. 查看 Outbox/Inbox 积压与任务失败原因，避免直接重置数据库。
