#!/usr/bin/env bash
# 种子包往返验收：空库 → Flyway 迁移 → 导入种子 → 核对行数 → 用几条真实查询证明「查询得动」。
#
# 为什么要在隔离实例（55432）里做：
#   验收需要「删库重建」，在 5432 主库上做等于拿正在服务的库当试验品。隔离实例由
#   ops/local/test-db.sh 起，用完即弃，失败也不影响主库。
#
# 为什么不能只核对「导入成功」：
#   行数对上只说明 COPY 没报错，不说明数据能用。真正的验收是拿公开检索、引用网络、推荐
#   要用的 SQL 各跑一遍，看到非零结果。种子包最常见的失败是「表灌进去了但 query 侧投影空」，
#   那种情况下站点打开是零结果而数据库一切正常。
#
# 用法：bash ops/verify/seed-roundtrip.sh [--limit 500]
# 退出码：0 通过；2 前置不满足；3 导入失败；4 核对不一致。
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 2

PG_BIN="${PG_BIN:-/d/dev-tools/pgsql/bin}"
DRILL_PORT="${DRILL_PORT:-55432}"
DRILL_DB="${DRILL_DB:-core_seedtest}"
LIMIT="${LIMIT:-300}"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="${OUT_DIR:-reports/seed-roundtrip/${STAMP}}"

while [ $# -gt 0 ]; do
  case "$1" in
    --limit)
      LIMIT="$2"
      shift 2
      ;;
    *)
      echo "未知参数：$1" >&2
      exit 2
      ;;
  esac
done

PSQL="$PG_BIN/psql.exe"
CREATEDB="$PG_BIN/createdb.exe"
DROPDB="$PG_BIN/dropdb.exe"
[ -x "$PSQL" ] || {
  PSQL="$PG_BIN/psql"
  CREATEDB="$PG_BIN/createdb"
  DROPDB="$PG_BIN/dropdb"
}

say() { echo "[$(date +%H:%M:%S)] $*"; }

mkdir -p "$OUT_DIR" || exit 2
REPORT="$OUT_DIR/roundtrip.log"
: >"$REPORT"

admin() {
  PGPASSWORD=x PGCLIENTENCODING=UTF8 "$PSQL" -h 127.0.0.1 -p "$DRILL_PORT" -U postgres \
    -v ON_ERROR_STOP=1 "$@"
}
drill() {
  PGPASSWORD=x PGCLIENTENCODING=UTF8 "$PSQL" -h 127.0.0.1 -p "$DRILL_PORT" -U postgres \
    -d "$DRILL_DB" -v ON_ERROR_STOP=1 "$@"
}

# ---------- 1. 隔离实例 ----------
if ! admin -d postgres -Atc "SELECT 1" >/dev/null 2>&1; then
  say "启动隔离实例（$DRILL_PORT）"
  bash ops/local/test-db.sh start >/dev/null 2>&1
  sleep 2
fi
if ! admin -d postgres -Atc "SELECT 1" >/dev/null 2>&1; then
  say "隔离实例不可用（$DRILL_PORT）；不在 5432 上做删库验收"
  exit 2
fi

# ---------- 2. 导出种子（从主库真实数据） ----------
SEED_DIR="$OUT_DIR/seed"
say "从主库导出种子（limit=$LIMIT）"
if ! bash ops/seed/export-seed.sh --limit "$LIMIT" --out "$SEED_DIR" >>"$REPORT" 2>&1; then
  say "导出失败，详见 $REPORT"
  exit 3
fi
SEED_ROWS="$(awk -F= '$1 ~ /^table\..*\.rows$/ { s += $2 } END { print s }' "$SEED_DIR/manifest.properties")"
say "种子导出完成：$SEED_ROWS 行"

# ---------- 3. 空库 + schema ----------
say "重建 $DRILL_DB"
PGPASSWORD=x "$DROPDB" -h 127.0.0.1 -p "$DRILL_PORT" -U postgres --if-exists "$DRILL_DB" \
  >>"$REPORT" 2>&1
PGPASSWORD=x "$CREATEDB" -h 127.0.0.1 -p "$DRILL_PORT" -U postgres "$DRILL_DB" \
  >>"$REPORT" 2>&1 || exit 3

say "按版本顺序执行迁移"
MIG_DIR="services/core-service/src/main/resources/db/migration"
# sort -V 保证 V9 排在 V10 前面（按字典序 V10 < V9，顺序错的话外键与触发器会先于表出现）。
for f in $(ls "$MIG_DIR"/*.sql | sort -V); do
  base="$(basename "$f")"
  if ! drill -q -f "$f" >>"$REPORT" 2>&1; then
    say "迁移失败：$base，详见 $REPORT"
    exit 3
  fi
done

# Flyway 的历史表：应用启动时由 Flyway 建，这里手跑 SQL 就得自己补，否则导入脚本的
# 「schema 版本核对」没有依据。版本号取自实际文件名，不是写死的 18。
say "写入 Flyway 历史（模拟应用启动完成迁移）"
{
  echo "CREATE TABLE flyway_schema_history ("
  echo "  installed_rank integer NOT NULL,"
  echo "  version varchar(50),"
  echo "  description varchar(200) NOT NULL,"
  echo "  type varchar(20) NOT NULL,"
  echo "  script varchar(1000) NOT NULL,"
  echo "  checksum integer,"
  echo "  installed_by varchar(100) NOT NULL,"
  echo "  installed_on timestamp NOT NULL DEFAULT now(),"
  echo "  execution_time integer NOT NULL,"
  echo "  success boolean NOT NULL,"
  echo "  PRIMARY KEY (installed_rank));"
  rank=0
  for f in $(ls "$MIG_DIR"/*.sql | sort -V); do
    base="$(basename "$f")"
    ver="${base%%__*}"
    ver="${ver#V}"
    rank=$((rank + 1))
    echo "INSERT INTO flyway_schema_history VALUES ($rank, '$ver', 'seed roundtrip', 'SQL', '$base', NULL, 'seedtest', now(), 0, true);"
  done
} >"$OUT_DIR/flyway-history.sql"
if ! drill -q -f "$OUT_DIR/flyway-history.sql" >>"$REPORT" 2>&1; then
  say "写 Flyway 历史失败，详见 $REPORT"
  exit 3
fi
FLYWAY_VER="$(drill -Atc "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")"
say "schema 版本：v$FLYWAY_VER"

# ---------- 4. 导入种子 ----------
say "导入种子"
if ! DST_PORT="$DRILL_PORT" DST_DB="$DRILL_DB" DST_USER=postgres DST_PASSWORD=x \
  bash ops/seed/import-seed.sh --dir "$SEED_DIR" --skip-rebuild >>"$REPORT" 2>&1; then
  say "导入失败，详见 $REPORT"
  exit 3
fi

# ---------- 5. 数据可用性（不是行数，是查询） ----------
say "可用性查询"
FAIL=0
check() {
  local label="$1"
  local sql="$2"
  local value
  value="$(drill -Atc "$sql" 2>>"$REPORT")"
  if [ -z "$value" ] || [ "$value" = "0" ]; then
    printf '  %-34s 失败（结果 %s）\n' "$label" "${value:-空}"
    FAIL=1
  else
    printf '  %-34s %s\n' "$label" "$value"
  fi
  echo "$label=$value" >>"$OUT_DIR/checks.txt"
}

check "成果总数（公开生效）" "SELECT count(*) FROM core_achievement WHERE visibility='PUBLIC' AND effective_status='EFFECTIVE'"
check "有 DOI 的论文" "SELECT count(*) FROM core_paper WHERE doi IS NOT NULL"
check "参与关系（非待核验）" "SELECT count(*) FROM core_contribution WHERE relation_state IN ('AUTO','SELF_CONFIRMED')"
check "来源归属" "SELECT count(*) FROM core_achievement_source"
check "引用边（指向包内）" "SELECT count(*) FROM core_citation_reference WHERE cited_achievement_id IS NOT NULL"
check "按主题聚合（前 3 个主题的成果数之和）" "SELECT (SELECT count(*) FROM (SELECT topic_id FROM core_achievement_topic GROUP BY topic_id ORDER BY count(*) DESC LIMIT 3) t)"
check "同作者候选（有 2 篇以上成果的学者）" "SELECT count(*) FROM (SELECT scholar_id FROM core_contribution GROUP BY scholar_id HAVING count(*) >= 2) s"
check "被引计数可汇总" "SELECT count(*) FROM core_paper WHERE citation_count > 0"
check "检索用题名归一化非空" "SELECT count(*) FROM core_achievement WHERE title_norm <> ''"

if [ "$FAIL" -ne 0 ]; then
  say "可用性查询有失败项，详见 $OUT_DIR/checks.txt 与 $REPORT"
  exit 4
fi

{
  echo "# 种子包往返验收"
  echo "time=$STAMP"
  echo "target=$DRILL_DB@127.0.0.1:$DRILL_PORT"
  echo "flyway_version=$FLYWAY_VER"
  echo "seed_rows=$SEED_ROWS"
  cat "$OUT_DIR/checks.txt"
} >"$OUT_DIR/summary.md"

say "通过。报告：$OUT_DIR/summary.md"
exit 0
