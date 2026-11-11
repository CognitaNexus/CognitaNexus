#!/usr/bin/env bash
# CognitaNexus 本地开发环境启动（原生二进制模式）
#
# 说明：本机 Docker Desktop 依赖 WSL2，而 WSL 被安全策略拦截，故本地开发
# 改用 D 盘原生二进制运行 PostgreSQL 16 与 Redis 8；服务器部署仍使用
# ops/compose 下的 Docker Compose 拓扑（架构 V0.2 §12.1）。
#
# 用法：bash ops/local/start-infra.sh

set -euo pipefail

PG_HOME="D:/dev-tools/pgsql"
PG_DATA="D:/dev-tools/pgdata"
REDIS_HOME="D:/dev-tools/redis"
REDIS_DATA="D:/dev-tools/redis-data"
PG_PORT=5432

echo "=== 1. PostgreSQL 16 ==="
if "$PG_HOME/bin/pg_ctl.exe" -D "$PG_DATA" status >/dev/null 2>&1; then
  echo "  已在运行"
else
  "$PG_HOME/bin/pg_ctl.exe" -D "$PG_DATA" -l "$PG_DATA/server.log" -o "-p $PG_PORT" start
  sleep 3
  echo "  已启动 (端口 $PG_PORT)"
fi

echo "=== 2. redis-session (6379) ==="
if "$REDIS_HOME/redis-cli.exe" -p 6379 -a cn_session_local_dev --no-auth-warning ping 2>/dev/null | grep -q PONG; then
  echo "  已在运行"
else
  ( cd "$REDIS_HOME" && nohup ./redis-server.exe redis-session.conf >/dev/null 2>&1 & )
  sleep 2
  echo "  已启动"
fi

echo "=== 3. redis-cache (6380) ==="
if "$REDIS_HOME/redis-cli.exe" -p 6380 -a cn_cache_local_dev --no-auth-warning ping 2>/dev/null | grep -q PONG; then
  echo "  已在运行"
else
  ( cd "$REDIS_HOME" && nohup ./redis-server.exe redis-cache.conf >/dev/null 2>&1 & )
  sleep 2
  echo "  已启动"
fi

mkdir -p "$REDIS_DATA/session" "$REDIS_DATA/cache"

echo "=== 4. 连通性自检 ==="
PGPASSWORD='cn_core_app_dev' "$PG_HOME/bin/psql.exe" -h 127.0.0.1 -p $PG_PORT -U core_app -d core_db -Atc "select 'core_db OK'"
PGPASSWORD='cn_ingestion_app_dev' "$PG_HOME/bin/psql.exe" -h 127.0.0.1 -p $PG_PORT -U ingestion_app -d ingestion_db -Atc "select 'ingestion_db OK'"
PGPASSWORD='cn_query_app_dev' "$PG_HOME/bin/psql.exe" -h 127.0.0.1 -p $PG_PORT -U query_app -d query_db -Atc "select 'query_db OK'"
"$REDIS_HOME/redis-cli.exe" -p 6379 -a cn_session_local_dev --no-auth-warning ping
"$REDIS_HOME/redis-cli.exe" -p 6380 -a cn_cache_local_dev --no-auth-warning ping
echo "=== 基础设施就绪 ==="
