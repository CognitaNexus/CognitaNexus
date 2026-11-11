#!/usr/bin/env bash
# 重置本地三个业务库的模式（保留库与角色，只重建 schema）
#
# 用途：本地开发阶段需要从零重放 Flyway 迁移、或需要清空演示数据时使用。
# 不做的事：不删除数据库本身、不删除角色、不触碰其他库。生产环境切勿执行。
#
# 用法：bash ops/local/reset-databases.sh [--yes]

set -euo pipefail

PG_HOME="D:/dev-tools/pgsql"
PG_HOST=127.0.0.1
PG_PORT=5432

if [ "${1:-}" != "--yes" ]; then
  echo "该操作会清空 core_db / ingestion_db / query_db 的 public 模式（含全部业务数据）。"
  echo "确认请重新执行：bash ops/local/reset-databases.sh --yes"
  exit 1
fi

reset_one() {
  local db="$1" owner="$2" owner_pw="$3"
  echo "  重置 $db ..."
  PGPASSWORD="$owner_pw" "$PG_HOME/bin/psql.exe" -h "$PG_HOST" -p "$PG_PORT" -U "$owner" -d "$db" \
    -v ON_ERROR_STOP=1 -q \
    -c "DROP SCHEMA IF EXISTS public CASCADE" \
    -c "CREATE SCHEMA public AUTHORIZATION $owner" \
    -c "GRANT ALL ON SCHEMA public TO $owner"
}

echo "=== 重置本地数据库模式 ==="
reset_one core_db core_owner cn_core_owner_dev
reset_one ingestion_db ingestion_owner cn_ingestion_owner_dev
reset_one query_db query_owner cn_query_owner_dev
echo "=== 完成：下次启动服务时 Flyway 会从 V1 重新迁移 ==="
