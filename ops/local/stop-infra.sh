#!/usr/bin/env bash
# CognitaNexus 本地开发环境停止
# 注意：停止只影响本地进程，不删除任何数据目录。

set -uo pipefail

PG_HOME="D:/dev-tools/pgsql"
PG_DATA="D:/dev-tools/pgdata"
REDIS_HOME="D:/dev-tools/redis"

echo "=== 停止 PostgreSQL ==="
"$PG_HOME/bin/pg_ctl.exe" -D "$PG_DATA" stop -m fast 2>&1 | tail -2

echo "=== 停止 redis-session / redis-cache ==="
"$REDIS_HOME/redis-cli.exe" -p 6379 -a cn_session_local_dev --no-auth-warning shutdown nosave 2>/dev/null || echo "  session 未运行"
"$REDIS_HOME/redis-cli.exe" -p 6380 -a cn_cache_local_dev --no-auth-warning shutdown nosave 2>/dev/null || echo "  cache 未运行"

echo "=== 完成（数据保留在 $PG_DATA 与 D:/dev-tools/redis-data）==="
