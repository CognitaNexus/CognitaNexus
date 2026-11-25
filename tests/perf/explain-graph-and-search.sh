#!/usr/bin/env bash
# =============================================================================
# 执行计划采集：PostgreSQL EXPLAIN (ANALYZE, BUFFERS) + Neo4j PROFILE
#
# 为什么单独一个脚本而不是散在编排里：执行计划是「版本之间可比」的证据。同一份固定
# 查询集、同一份采样方法、同一套提取规则，才能回答「这次 P95 变差是因为数据变多，
# 还是因为计划换了」。把它挂在压测流程里，就顺带保证了每次运行都有计划留档。
#
# 产出（--out 目录下）：
#   sql-plans.txt       原始 EXPLAIN 输出（含 Buffers，可逐节点核对）
#   sql-plans.tsv       每条语句的 Planning/Execution Time，便于做趋势对比
#   cypher-profiles.txt Neo4j PROFILE 的算子树与 dbHits
#   summary.md          给报告直接引用的摘要
#
# 用法：
#   bash tests/perf/explain-graph-and-search.sh --out=reports/perf/x/plans --gen=gen0001 --db=qperf_1m
#   bash tests/perf/explain-graph-and-search.sh --out=... --no-cypher     # 只采 SQL
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="reports/perf/plans-$(date +%Y%m%d-%H%M%S)"
GEN="gen0001"
DB=""
NATURE="${NATURE:-SIMULATED}"
WITH_CYPHER=1

for arg in "$@"; do
  case "$arg" in
    --out=*) OUT="${arg#--out=}" ;;
    --gen=*) GEN="${arg#--gen=}" ;;
    --db=*) DB="${arg#--db=}" ;;
    --nature=*) NATURE="${arg#--nature=}" ;;
    --no-cypher) WITH_CYPHER=0 ;;
    -h|--help) sed -n '2,22p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "未知参数：$arg" >&2; exit 2 ;;
  esac
done

if [ -f "${CN_RUNTIME:-/home/admin/cn-test-runtime}/bin/env.sh" ]; then
  # shellcheck disable=SC1091
  . "${CN_RUNTIME:-/home/admin/cn-test-runtime}/bin/env.sh"
fi
CN_RT="${CN_RT:-/home/admin/cn-test-runtime}"
PGROOT="${PGROOT:-$CN_RT/pgsql}"
PGBIN="${PGBIN:-$PGROOT/usr/lib/postgresql/16/bin}"
PG_HOST="${PG_HOST:-127.0.0.1}"
PG_IT_PORT="${PGIT_PORT:-${PG_IT_PORT:-55432}}"
PG_USER="${PG_USER:-postgres}"
NEO4J_HTTP_PORT="${NEO4J_HTTP_PORT:-17474}"
PSQL="$PGBIN/psql"
[ -x "$PSQL" ] || PSQL="$(command -v psql || true)"

[ -n "$DB" ] || { echo "必须给 --db=<压测库名>（避免误连开发库）" >&2; exit 2; }
case "$DB" in
  qperf_*|*_perf|*_it) : ;;
  *) echo "拒绝在非压测库上跑 EXPLAIN ANALYZE：$DB（库名必须是 qperf_* / *_perf / *_it）" >&2; exit 2 ;;
esac
[ -x "$PSQL" ] || { echo "找不到 psql（PGBIN=$PGBIN）" >&2; exit 2; }

mkdir -p "$OUT"
mkdir -p "$OUT/../"

echo "=== SQL 执行计划：db=$DB gen=$GEN nature=$NATURE ==="
PGCONNECT_TIMEOUT=5 PGCLIENTENCODING=UTF8 "$PSQL" \
  -h "$PG_HOST" -p "$PG_IT_PORT" -U "$PG_USER" -d "$DB" \
  -v gen="$GEN" -v nature="$NATURE" \
  -f "$ROOT/tests/perf/scale/explain-plans.sql" \
  > "$OUT/sql-plans.txt" 2>&1
SQL_RC=$?
if [ $SQL_RC -ne 0 ]; then
  echo "SQL 计划采集失败（rc=$SQL_RC），见 $OUT/sql-plans.txt" >&2
  tail -20 "$OUT/sql-plans.txt" >&2
fi

# ---------------------------------------------------------------------------
# 提取：每条语句的 Planning Time / Execution Time，以及与它最近的 \echo 标签
# ---------------------------------------------------------------------------
: > "$OUT/sql-plans.tsv"
printf 'stmt\tplanning_time\texecution_time\n' >> "$OUT/sql-plans.tsv"
awk '
  /^--- / { label=$0; sub(/^--- /, "", label); sub(/ ---$/, "", label); next }
  /Planning Time:/ { plan=$0; sub(/.*: /, "", plan); sub(/ ms$/, "", plan) }
  /Execution Time:/ {
    exec=$0; sub(/.*: /, "", exec); sub(/ ms$/, "", exec);
    if (label == "") label="(unlabeled)";
    printf "%s\t%s\t%s\n", label, plan, exec;
    label=""
  }
' "$OUT/sql-plans.txt" >> "$OUT/sql-plans.tsv"

echo "SQL 语句数：$(( $(wc -l < "$OUT/sql-plans.tsv") - 1 ))（含失败项）"

# ---------------------------------------------------------------------------
# Cypher PROFILE
#
# 只在三者同时成立时运行：未 --no-cypher、有口令、Neo4j HTTP 可达。
# 缺任何一条都跳过并说明，不伪造「图库计划」。
# ---------------------------------------------------------------------------
if [ "$WITH_CYPHER" = "1" ]; then
  if [ -z "${CN_TEST_NEO4J_PASSWORD:-}" ]; then
    echo "跳过 Cypher PROFILE：未设置 CN_TEST_NEO4J_PASSWORD" | tee "$OUT/cypher-profiles.txt"
  elif ! curl --noproxy '*' -sf -m 3 "http://${PG_HOST}:${NEO4J_HTTP_PORT}/" >/dev/null 2>&1; then
    echo "跳过 Cypher PROFILE：Neo4j ${PG_HOST}:${NEO4J_HTTP_PORT} 不可达" | tee "$OUT/cypher-profiles.txt"
  else
    echo "=== Cypher PROFILE ==="
    node "$ROOT/tests/perf/neo4j-project-load.mjs" \
      --uri="http://${PG_HOST}:${NEO4J_HTTP_PORT}" \
      --user="${CN_TEST_NEO4J_USER:-neo4j}" --password="$CN_TEST_NEO4J_PASSWORD" \
      --database="${CN_NEO4J_DATABASE:-neo4j}" \
      --dir="$OUT/graph-tmp" --gen="$GEN" --only=profile \
      > "$OUT/cypher-profiles.txt" 2>&1
    RC=$?
    [ $RC -ne 0 ] && echo "Cypher PROFILE 失败（rc=$RC），见 $OUT/cypher-profiles.txt" >&2
    grep -E "^\[PROFILE\]|^  " "$OUT/cypher-profiles.txt" | head -60
  fi
else
  echo "跳过 Cypher PROFILE（--no-cypher）" > "$OUT/cypher-profiles.txt"
fi

# ---------------------------------------------------------------------------
# 摘要：把「最慢的几条」单独列出来。报告需要的是瓶颈，不是 18 条语句的完整清单。
# ---------------------------------------------------------------------------
{
  echo "# 执行计划摘要（$DB / $GEN / $NATURE）"
  echo
  echo "采集时间：$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo
  echo "## 按执行时间降序（Top 10）"
  echo
  echo '| 语句 | Planning (ms) | Execution (ms) |'
  echo '| --- | ---: | ---: |'
  tail -n +2 "$OUT/sql-plans.tsv" | sort -t$'\t' -k3 -g -r | head -10 | \
    awk -F'\t' '{ printf "| %s | %s | %s |\n", $1, $2, $3 }'
  echo
  echo "## 数据分布自检"
  echo
  echo '```'
  grep -E "^S0-" "$OUT/sql-plans.txt" | head -10
  echo '```'
  echo
  if [ -s "$OUT/cypher-profiles.txt" ]; then
    echo "## Cypher PROFILE"
    echo
    echo '```'
    grep -E "^\[PROFILE\]|^  [A-Z]" "$OUT/cypher-profiles.txt" | head -40
    echo '```'
  fi
  echo
  echo "## 说明"
  echo
  echo '- `EXPLAIN (ANALYZE, BUFFERS)` 会真正执行查询；只在压测库上运行。'
  echo '- 计划形状比耗时更值得关注：耗时随缓存与并发波动，形状变了才是真的变了。'
  echo '- 若某条语句从 Index Scan 变成 Seq Scan 且选择性没变，多半是统计信息过期（VACUUM ANALYZE 后重采）。'
} > "$OUT/summary.md"

echo "计划摘要：$OUT/summary.md"
[ $SQL_RC -ne 0 ] && exit $SQL_RC
exit 0
