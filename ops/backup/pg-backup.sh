#!/usr/bin/env bash
# 三个数据库的一致性备份（NF04「完成并记录至少一次备份恢复测试」的第一半）。
#
# 依据：NF04、NF07；架构设计说明书 §12.2。
#
# 为什么三个库要一起备份、且必须记下同一时刻的版本：
#   core 是权威域，query 是投影，ingestion 是采集现场。分开备份再分别恢复，会得到
#   「投影比权威新」或「采集现场指向一条已不存在的成果」这类不一致。备份目录里因此有一个
#   清单文件，记录每个库的 dump 文件名、大小、校验和与关键表的行数——恢复后要拿它逐项核对。
#
# 为什么默认用 custom 格式（-Fc）而不是纯 SQL：
#   -Fc 支持 pg_restore 的按表恢复与并行，且自带压缩；纯 SQL 只有「全有或全无」，
#   排查时无法只恢复一张表。代价是必须用 pg_restore 才能读，这一点写进了运维手册。
#
# 用法：
#   bash ops/backup/pg-backup.sh                       # 备份到 backups/<时间戳>
#   bash ops/backup/pg-backup.sh --out /data/backups   # 指定父目录
#   CN_BACKUP_MODE=host bash ops/backup/pg-backup.sh   # 连宿主实例（本机原生栈）
#
# 退出码：0 成功；2 前置条件不满足（连不上库或没有 pg_dump）；3 某个库 dump 失败。
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1

MODE="${CN_BACKUP_MODE:-docker}"
OUT_ROOT="./backups"
STAMP="$(date +%Y%m%d-%H%M%S)"

while [ $# -gt 0 ]; do
  case "$1" in
    --out)
      OUT_ROOT="$2"
      shift 2
      ;;
    --mode)
      MODE="$2"
      shift 2
      ;;
    *)
      echo "未知参数：$1" >&2
      exit 2
      ;;
  esac
done

OUT_DIR="${OUT_ROOT%/}/${STAMP}"
mkdir -p "$OUT_DIR" || exit 2

DATABASES="core_db ingestion_db query_db"
COMPOSE_SERVICE="${CN_BACKUP_COMPOSE_SERVICE:-postgres}"

# 关键表：恢复后必须逐项核对行数。只核对「表存在」没有意义——迁移会建表，
# 真正要证明的是数据回来了。
CORE_TABLES="core_achievement core_scholar core_contribution core_achievement_source iam_account"
INGESTION_TABLES="ing_collect_job ing_staged_record ing_source_run_config"
QUERY_TABLES="qry_achievement qry_contribution qry_achievement_topic qry_coauthor_edge"

case "$MODE" in
  docker)
    if ! docker compose ps --status running --services 2>/dev/null | grep -qx "$COMPOSE_SERVICE"; then
      echo "compose 里的 $COMPOSE_SERVICE 未运行；先 docker compose up -d，或改用 --mode host" >&2
      exit 2
    fi
    run_pg() { docker compose exec -T "$COMPOSE_SERVICE" "$@"; }
    ;;
  host)
    if ! command -v pg_dump >/dev/null 2>&1; then
      echo "PATH 里没有 pg_dump；本机原生栈请把 D:/dev-tools/pgsql/bin 加进 PATH" >&2
      exit 2
    fi
    # 宿主模式用连接参数而不是写死口令：口令从环境变量读，脚本里不出现任何凭据。
    export PGHOST="${PGHOST:-127.0.0.1}"
    export PGPORT="${PGPORT:-5432}"
    export PGUSER="${PGUSER:-postgres}"
    export PGPASSWORD="${PGPASSWORD:?宿主模式必须给出 PGPASSWORD（导出为环境变量，不要写在命令行）}"
    run_pg() { "$@"; }
    ;;
  *)
    echo "未知模式：$MODE（可选 docker / host）" >&2
    exit 2
    ;;
esac

MANIFEST="${OUT_DIR}/manifest.txt"
{
  echo "# CognitaNexus 备份清单"
  echo "time=${STAMP}"
  echo "mode=${MODE}"
  echo "host=$(hostname 2>/dev/null || echo unknown)"
  echo "databases=${DATABASES}"
} >"$MANIFEST"

echo "== 备份到 ${OUT_DIR}（模式 ${MODE}）"

for db in $DATABASES; do
  file="${db}.dump"
  echo "-- ${db}"
  if ! run_pg pg_dump -U "${CN_PG_DUMP_USER:-postgres}" -d "$db" -Fc -Z6 >"${OUT_DIR}/${file}"; then
    echo "备份 ${db} 失败" >&2
    exit 3
  fi
  size="$(wc -c <"${OUT_DIR}/${file}" | tr -d ' ')"
  sum="$(sha256sum "${OUT_DIR}/${file}" | cut -d' ' -f1)"
  echo "done ${db} size=${size} sha256=${sum}" >>"$MANIFEST"

  # 行数快照：用 -t 只数表，避免把视图/序列混进来。
  case "$db" in
    core_db) tables="$CORE_TABLES" ;;
    ingestion_db) tables="$INGESTION_TABLES" ;;
    query_db) tables="$QUERY_TABLES" ;;
    *) tables="" ;;
  esac
  for table in $tables; do
    count="$(run_pg psql -U "${CN_PG_DUMP_USER:-postgres}" -d "$db" -X -A -t \
      -c "SELECT count(*) FROM ${table}" 2>/dev/null | tr -d '[:space:]')"
    echo "rows ${db}.${table}=${count:-NA}" >>"$MANIFEST"
  done
done

echo "== 完成。清单：${MANIFEST}"
echo "   恢复与核对：bash ops/restore/pg-restore.sh --dir ${OUT_DIR}"
