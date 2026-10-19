#!/usr/bin/env bash
# 备份恢复演练：把当前开发库 dump 出来，恢复到隔离实例上的新库，并逐表核对。
#
# 为什么需要这一条（NF04「完成并记录至少一次备份恢复测试」）：
#   `ops/backup/pg-backup.sh` 只证明「文件生成了」。文件能不能装回去、装回去之后行数是否一致、
#   索引与约束是否还在，只有真恢复一次才知道。备份脚本本身不是证据，恢复后的核对才是。
#
# 三条安全默认：
#   1. 只在 55432 的隔离实例上恢复（`core_drill`），绝不碰 5432 上正在服务的库；
#   2. 每次先 drop 再 create 演练库：上一次演练的残留会让「行数一致」变成假通过；
#   3. 任何一张表行数不一致就返回非零，不打印「恢复完成」。
#
# 用法：
#   bash ops/verify/backup-restore-drill.sh
#   SRC_PASSWORD=... bash ops/verify/backup-restore-drill.sh
#
# 前置：本机原生 PostgreSQL（5432）在运行；`ops/local/test-db.sh` 的数据目录已初始化。
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1

PG_BIN="${PG_BIN:-D:/dev-tools/pgsql/bin}"
PSQL="$PG_BIN/psql.exe"
DUMP_BIN="$PG_BIN/pg_dump.exe"
RESTORE_BIN="$PG_BIN/pg_restore.exe"
CREATEDB="$PG_BIN/createdb.exe"
DROPDB="$PG_BIN/dropdb.exe"

SRC_HOST="${SRC_HOST:-127.0.0.1}"
SRC_PORT="${SRC_PORT:-5432}"
SRC_DB="${SRC_DB:-core_db}"
SRC_USER="${SRC_USER:-core_app}"
SRC_PASSWORD="${SRC_PASSWORD:-cn_core_app_dev}"

DRILL_PORT="${DRILL_PORT:-55432}"
DRILL_DB="${DRILL_DB:-core_drill}"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="${OUT_DIR:-reports/backup-drill/${STAMP}}"

# 核对表：只挑「数据回来了才有意义」的表。迁移本身会建表，核对「表存在」证明不了任何事。
TABLES="core_achievement core_scholar core_contribution core_achievement_source \
core_identifier_registry core_submission core_citation_observation core_citation_reference \
core_scholar_name_variant iam_account aud_audit_event"

mkdir -p "$OUT_DIR" || exit 2

say() { echo "[$(date +%H:%M:%S)] $*"; }

count_on() {
  local host="$1" port="$2" db="$3" user="$4" password="$5" table="$6"
  PGPASSWORD="$password" PGCLIENTENCODING=UTF8 "$PSQL" -h "$host" -p "$port" -U "$user" -d "$db" \
    -At -c "SELECT count(*) FROM ${table};" 2>/dev/null
}

# ---------- 1. 备份 ----------
say "备份 ${SRC_DB}（${SRC_HOST}:${SRC_PORT}）→ ${OUT_DIR}"
START=$(date +%s)
if ! PGPASSWORD="$SRC_PASSWORD" "$DUMP_BIN" -h "$SRC_HOST" -p "$SRC_PORT" -U "$SRC_USER" \
      -d "$SRC_DB" -Fc -Z6 -f "${OUT_DIR}/${SRC_DB}.dump"; then
  say "备份失败"
  exit 3
fi
DUMP_SECONDS=$(( $(date +%s) - START ))
DUMP_BYTES=$(wc -c <"${OUT_DIR}/${SRC_DB}.dump" | tr -d ' ')
say "备份完成：${DUMP_SECONDS}s / ${DUMP_BYTES} 字节"

# ---------- 2. 隔离实例 ----------
if ! PGPASSWORD=x "$PSQL" -h 127.0.0.1 -p "$DRILL_PORT" -U postgres -d postgres -Atc "SELECT 1" >/dev/null 2>&1; then
  say "启动隔离实例（${DRILL_PORT}）"
  bash ops/local/test-db.sh start >/dev/null 2>&1
  sleep 2
fi
if ! PGPASSWORD=x "$PSQL" -h 127.0.0.1 -p "$DRILL_PORT" -U postgres -d postgres -Atc "SELECT 1" >/dev/null 2>&1; then
  say "隔离实例不可用；本脚本不会退回到 5432 上做恢复"
  exit 2
fi

# ---------- 3. 恢复 ----------
say "重建演练库 ${DRILL_DB}"
PGPASSWORD=x "$DROPDB" -h 127.0.0.1 -p "$DRILL_PORT" -U postgres --if-exists "$DRILL_DB" >/dev/null 2>&1
PGPASSWORD=x "$CREATEDB" -h 127.0.0.1 -p "$DRILL_PORT" -U postgres "$DRILL_DB" || exit 3

say "恢复中"
START=$(date +%s)
if ! PGPASSWORD=x "$RESTORE_BIN" -h 127.0.0.1 -p "$DRILL_PORT" -U postgres -d "$DRILL_DB" \
      --no-owner --no-privileges --exit-on-error "${OUT_DIR}/${SRC_DB}.dump" \
      >"${OUT_DIR}/restore.log" 2>&1; then
  say "恢复失败，详见 ${OUT_DIR}/restore.log"
  exit 3
fi
RESTORE_SECONDS=$(( $(date +%s) - START ))
say "恢复完成：${RESTORE_SECONDS}s"

# ---------- 4. 逐表核对 ----------
say "逐表核对行数"
MISMATCH=0
{
  echo "# 备份恢复演练"
  echo "time=${STAMP}"
  echo "source=${SRC_DB}@${SRC_HOST}:${SRC_PORT}"
  echo "target=${DRILL_DB}@127.0.0.1:${DRILL_PORT}"
  echo "dump_bytes=${DUMP_BYTES}"
  echo "dump_seconds=${DUMP_SECONDS}"
  echo "restore_seconds=${RESTORE_SECONDS}"
  echo ""
  printf "%-32s %12s %12s %s\n" "table" "source" "restored" "result"
} >"${OUT_DIR}/manifest.txt"

for table in $TABLES; do
  src=$(count_on "$SRC_HOST" "$SRC_PORT" "$SRC_DB" "$SRC_USER" "$SRC_PASSWORD" "$table")
  dst=$(count_on 127.0.0.1 "$DRILL_PORT" "$DRILL_DB" postgres x "$table")
  if [ -z "$src" ] || [ -z "$dst" ]; then
    printf "%-32s %12s %12s %s\n" "$table" "${src:-?}" "${dst:-?}" "MISSING" >>"${OUT_DIR}/manifest.txt"
    MISMATCH=$((MISMATCH + 1))
    continue
  fi
  if [ "$src" = "$dst" ]; then
    result="OK"
  else
    result="MISMATCH"
    MISMATCH=$((MISMATCH + 1))
  fi
  printf "%-32s %12s %12s %s\n" "$table" "$src" "$dst" "$result" >>"${OUT_DIR}/manifest.txt"
done

sed -n '1,40p' "${OUT_DIR}/manifest.txt"

# ---------- 5. 结构和索引是否也在 ----------
say "核对结构与索引"
{
  echo ""
  echo "# 结构核对（恢复库）"
  echo "indexes=$(PGPASSWORD=x "$PSQL" -h 127.0.0.1 -p "$DRILL_PORT" -U postgres -d "$DRILL_DB" -Atc \
    "SELECT count(*) FROM pg_indexes WHERE schemaname='public';")"
  echo "constraints=$(PGPASSWORD=x "$PSQL" -h 127.0.0.1 -p "$DRILL_PORT" -U postgres -d "$DRILL_DB" -Atc \
    "SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace WHERE n.nspname='public';")"
  echo "flyway_version=$(PGPASSWORD=x "$PSQL" -h 127.0.0.1 -p "$DRILL_PORT" -U postgres -d "$DRILL_DB" -Atc \
    "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;")"
} >>"${OUT_DIR}/manifest.txt"
tail -4 "${OUT_DIR}/manifest.txt"

if [ "$MISMATCH" -ne 0 ]; then
  say "核对失败：${MISMATCH} 张表行数不一致或缺失"
  exit 4
fi

say "演练通过：${OUT_DIR}/manifest.txt"
exit 0
