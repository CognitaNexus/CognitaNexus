#!/usr/bin/env bash
# =============================================================================
# 备份／恢复演练（T33，对应 AC-IMPL-NF04-01）
#
# 在隔离实例（55432）上对 core_perf / query_perf 做一次完整 dump → 恢复到新库 → 核对。
# 绝不对开发库、也不对任何共享库执行恢复：那会覆盖别人正在用的数据。
#
# 核对项（缺一项都不算恢复成功）：
#   1. 逐表行数与源库一致；
#   2. 关键唯一约束与延迟触发器还在（结构恢复不等于数据恢复，两者都要看）；
#   3. 恢复库上跑同一条公开检索，命中数与源库一致（证明索引与统计信息可用）；
#   4. 记录 dump 体积与耗时，作为恢复时长的实测值。
#
# 用法：bash ops/perf/restore-drill.sh [--src-core=core_perf] [--src-query=query_perf]
# =============================================================================
set -uo pipefail

IT_PORT=55432
PG_BIN="D:/dev-tools/pgsql/bin"
PSQL="$PG_BIN/psql.exe"
DUMP="$PG_BIN/pg_dump.exe"
RESTORE="$PG_BIN/pg_restore.exe"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="$ROOT/reports/perf/restore-$STAMP"

SRC_CORE=core_perf
SRC_QUERY=query_perf
for arg in "$@"; do
  case "$arg" in
    --src-core=*) SRC_CORE="${arg#--src-core=}" ;;
    --src-query=*) SRC_QUERY="${arg#--src-query=}" ;;
  esac
done

mkdir -p "$OUT_DIR"

psql_on() {
  local db="$1"
  shift
  PGPASSWORD=postgres_it PGCLIENTENCODING=UTF8 "$PSQL" -h 127.0.0.1 -p "$IT_PORT" -U postgres -d "$db" "$@" < /dev/null
}

counts_of() {
  local db="$1" file="$2"
  # 只统计业务表，迁移历史表单独核对其行数是否一致（版本号是恢复正确性的证据之一）。
  psql_on "$db" -At -F'|' -c "
    SELECT c.relname, (xpath('/row/c/text()', query_to_xml(
             format('SELECT count(*) AS c FROM %I', c.relname), false, true, '')))[1]::text::bigint
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind = 'r'
    ORDER BY c.relname" > "$file"
}

drill() {
  local src="$1" dst="$2" label="$3"
  echo "=== $label：$src → $dst ==="

  local dump_file="$OUT_DIR/$src.dump"
  local t0=$(date +%s)
  PGPASSWORD=postgres_it "$DUMP" -h 127.0.0.1 -p "$IT_PORT" -U postgres \
    -Fc --no-owner --no-privileges -f "$dump_file" "$src" 2> "$OUT_DIR/$src.dump.log"
  local dump_rc=$?
  local dump_s=$(( $(date +%s) - t0 ))
  echo "  dump rc=$dump_rc 用时 ${dump_s}s 体积 $(du -h "$dump_file" 2>/dev/null | cut -f1)"

  psql_on postgres -q -v ON_ERROR_STOP=1 -c "SET client_min_messages = warning" \
    -c "DROP DATABASE IF EXISTS $dst" >/dev/null 2>&1
  psql_on postgres -q -v ON_ERROR_STOP=1 -c "CREATE DATABASE $dst" >/dev/null

  t0=$(date +%s)
  PGPASSWORD=postgres_it "$RESTORE" -h 127.0.0.1 -p "$IT_PORT" -U postgres \
    -d "$dst" --no-owner --no-privileges "$dump_file" > "$OUT_DIR/$src.restore.log" 2>&1
  local restore_rc=$?
  local restore_s=$(( $(date +%s) - t0 ))
  echo "  restore rc=$restore_rc 用时 ${restore_s}s"

  counts_of "$src" "$OUT_DIR/$src.counts.tsv"
  counts_of "$dst" "$OUT_DIR/$dst.counts.tsv"
  if diff -q "$OUT_DIR/$src.counts.tsv" "$OUT_DIR/$dst.counts.tsv" >/dev/null; then
    echo "  逐表行数：一致（$(wc -l < "$OUT_DIR/$src.counts.tsv") 张表）"
  else
    echo "  逐表行数：不一致，差异如下"
    diff "$OUT_DIR/$src.counts.tsv" "$OUT_DIR/$dst.counts.tsv" | head -20
  fi

  echo "$dump_s $restore_s" > "$OUT_DIR/$src.duration.txt"
}

verify_query_side() {
  echo "=== 恢复库核对：结构约束 ==="
  # 唯一索引与触发器都是「数据对了但规则丢了」这类事故的现场；只对行数不够。
  psql_on query_perf_restore -At -F'|' -c "
    SELECT 'index', indexname FROM pg_indexes
     WHERE schemaname = 'public' AND indexname IN
       ('pk_qry_achievement','ix_qry_achievement_title_trgm','pk_qry_coauthor_edge')
    UNION ALL
    SELECT 'fk', conname FROM pg_constraint
     WHERE conname LIKE 'qry_%' AND contype = 'f'
    ORDER BY 1,2"

  echo "=== 恢复库核对：同一条公开检索的命中数 ==="
  local sql="SELECT count(*) FROM qry_achievement
             WHERE generation = 'gen0001' AND effective_status = 'EFFECTIVE'
               AND visibility = 'PUBLIC' AND data_nature = 'SIMULATED'"
  local a b
  a=$(psql_on query_perf -At -c "$sql" | tr -d '\r')
  b=$(psql_on query_perf_restore -At -c "$sql" | tr -d '\r')
  echo "  源库=$a 恢复库=$b $([ "$a" = "$b" ] && echo 一致 || echo 不一致)"
  echo "$a $b" > "$OUT_DIR/query-hitcount.txt"

  echo "=== 恢复库核对：时间线/统计信息可用性（同一查询的耗时对比）==="
  psql_on query_perf -c "EXPLAIN (ANALYZE, BUFFERS) $sql" | grep -E "Execution Time" || true
  psql_on query_perf_restore -c "EXPLAIN (ANALYZE, BUFFERS) $sql" | grep -E "Execution Time" || true
}

verify_core_side() {
  echo "=== 恢复库核对：core 关键约束与延迟触发器 ==="
  psql_on core_perf_restore -At -F'|' -c "
    SELECT 'uk', indexname FROM pg_indexes
     WHERE schemaname = 'public' AND indexname IN
       ('uk_core_paper_doi_norm','uk_iam_portal_authority_active')
    UNION ALL
    SELECT 'trigger', tgname FROM pg_trigger
     WHERE NOT tgisinternal AND tgname IN
       ('ct_core_achievement_require_extension','ct_core_paper_doi_unique')
    ORDER BY 1,2"
  # 延迟触发器必须在恢复库里仍然「拒绝违规提交」，否则恢复出来的库只是一份好看的数据。
  # 两个坑都在首次演练踩过：
  #   1. 含中文的 SQL 不能走 -c：Windows 下 argv 按 ANSI 码页转码，UTF-8 中文直接破坏；
  #   2. 延迟触发器只在提交时检查，不能用 DO 块包住——块里的 RAISE 会先于提交执行，
  #      检查根本没机会发生，看起来「通过了」其实是没测到。必须是一条独立的自动提交 INSERT。
  local check_sql="$OUT_DIR/core-trigger-check.sql"
  cat > "$check_sql" <<'SQL'
INSERT INTO core_achievement (id, type, title, title_norm, data_nature, visibility, effective_status)
VALUES ('restore-check-1','PAPER','恢复校验用','恢复校验用','SIMULATED','PUBLIC','EFFECTIVE');
SQL
  local trigger_out
  trigger_out=$(psql_on core_perf_restore -q -f "$check_sql" 2>&1)
  if printf '%s' "$trigger_out" | grep -q "必须恰好有一个类型扩展"; then
    echo "  延迟触发器在恢复库生效（违规提交被拒绝）"
  else
    echo "  [warn] 恢复库未按预期拒绝缺类型扩展的提交：$trigger_out"
  fi
  echo "  残留行数（必须为 0）：$(psql_on core_perf_restore -At -c "SELECT count(*) FROM core_achievement WHERE id = 'restore-check-1'" | tr -d '\r')"
}

echo "演练输出目录：$OUT_DIR"
drill "$SRC_CORE" "${SRC_CORE}_restore" "core"
drill "$SRC_QUERY" "${SRC_QUERY}_restore" "query"
verify_core_side
verify_query_side
echo "=== 完成，结果在 $OUT_DIR ==="
