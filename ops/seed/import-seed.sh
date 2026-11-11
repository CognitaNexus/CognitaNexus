#!/usr/bin/env bash
# 把离线种子数据包导入一个空库，并重建 query 侧投影（部署前功能项一的后半段）。
#
# 前提：目标库的 schema 已经由 Flyway 建好（应用启动即迁移），且目录表为空。
# 本脚本不建表、不改结构，只灌数据。
#
# 为什么坚持「目标表必须为空」：
#   种子包是初始化手段，不是增量补数工具。往已有数据的库里灌，会得到两套主键都不冲突但语义
#   重叠的成果（同一篇论文的两条记录），而且没有任何字段能事后区分「哪条是种子」。要补数请走
#   治理端的导入流程，那里有查重与合并台账。
#
# 为什么在一个事务里 COPY 完再提交：
#   18 张表之间有 20 多个外键。中途失败留下「成果进了、参与关系没进」的半包，比一条没进更糟
#   ——它看起来像有数据，点开每一条都是空作者。单事务保证要么全进要么全不进。
#
# 用法：
#   bash ops/seed/import-seed.sh                          # 导入 ops/seed/dist
#   bash ops/seed/import-seed.sh --dir /data/seed         # 指定种子目录
#   bash ops/seed/import-seed.sh --skip-rebuild           # 只灌核心库，投影稍后手工重建
#   bash ops/seed/import-seed.sh --allow-nonempty         # 允许非空（危险，仅排查用）
#
# 退出码：0 成功；2 前置条件不满足；3 导入失败（已回滚）；4 导入后核对不一致。
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 2

PG_BIN="${PG_BIN:-/d/dev-tools/pgsql/bin}"
DST_HOST="${DST_HOST:-127.0.0.1}"
DST_PORT="${DST_PORT:-5432}"
DST_DB="${DST_DB:-core_db}"
DST_USER="${DST_USER:-core_owner}"
DST_PASSWORD="${DST_PASSWORD:-cn_core_owner_dev}"

QUERY_BASE="${QUERY_BASE:-http://127.0.0.1:8083}"
SERVICE_NAME="${SERVICE_NAME:-core}"
SERVICE_TOKEN="${SERVICE_TOKEN:-cn_query_service_dev}"

SEED_DIR="ops/seed/dist"
DO_REBUILD=1
ALLOW_NONEMPTY=0

while [ $# -gt 0 ]; do
  case "$1" in
    --dir)
      SEED_DIR="$2"
      shift 2
      ;;
    --skip-rebuild)
      DO_REBUILD=0
      shift
      ;;
    --allow-nonempty)
      ALLOW_NONEMPTY=1
      shift
      ;;
    *)
      echo "未知参数：$1" >&2
      exit 2
      ;;
  esac
done

PSQL="$PG_BIN/psql.exe"
[ -x "$PSQL" ] || PSQL="$PG_BIN/psql"
if [ ! -x "$PSQL" ]; then
  echo "找不到 psql（PG_BIN=$PG_BIN）" >&2
  exit 2
fi

run_psql() {
  PGPASSWORD="$DST_PASSWORD" PGCLIENTENCODING=UTF8 "$PSQL" \
    -h "$DST_HOST" -p "$DST_PORT" -U "$DST_USER" -d "$DST_DB" -v ON_ERROR_STOP=1 "$@"
}

MANIFEST="$SEED_DIR/manifest.properties"
if [ ! -f "$MANIFEST" ]; then
  echo "找不到 $MANIFEST；先用 export-seed.sh 导出" >&2
  exit 2
fi

get() { awk -F= -v k="$1" '$1 == k { print substr($0, length(k) + 2); exit }' "$MANIFEST"; }

FORMAT="$(get format)"
[ "$FORMAT" = "cn-seed/1" ] || {
  echo "种子包格式不认识：$FORMAT" >&2
  exit 2
}

if ! run_psql -Atc "SELECT 1" >/dev/null 2>&1; then
  echo "连不上 $DST_USER@$DST_HOST:$DST_PORT/$DST_DB" >&2
  exit 2
fi

# ---------------------------------------------------------------------------
# 1. schema 版本核对
# ---------------------------------------------------------------------------
SEED_FLYWAY="$(get flywayVersion)"
DST_FLYWAY="$(run_psql -Atc "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")"
if [ -z "$DST_FLYWAY" ]; then
  echo "目标库没有 Flyway 记录：先启动一次应用让它建表，再导入种子" >&2
  exit 2
fi
if [ "$SEED_FLYWAY" != "$DST_FLYWAY" ]; then
  echo "schema 版本不一致：种子 v$SEED_FLYWAY / 目标 v$DST_FLYWAY" >&2
  echo "列顺序在迁移间可能变过，此时按 CSV 列对位灌数据会串列。请用同版本导出的种子。" >&2
  exit 2
fi

# ---------------------------------------------------------------------------
# 2. 目标表必须为空
# ---------------------------------------------------------------------------
# 按 manifest 里出现的顺序取表名（导出时已排好依赖顺序）。
# 注意 awk 的 pattern 默认匹配整行，这里必须显式对 $1 判断——整行形如
# `table.x.file=y.csv.gz`，拿 $0 去匹配 `\.file$` 永远不成立。
TABLES="$(awk -F= '$1 ~ /^table\./ && $1 ~ /\.file$/ { name = $1; sub(/^table\./, "", name); sub(/\.file$/, "", name); print name }' "$MANIFEST")"
if [ -z "$TABLES" ]; then
  echo "manifest 里没有表清单" >&2
  exit 2
fi

NONEMPTY=""
for table in $TABLES; do
  case "$table" in
    evt_revision_counter) continue ;; # 水位表允许有初始行，导入时按种子覆盖
    *[!a-z_]*)
      echo "manifest 里的表名不合法：$table" >&2
      exit 2
      ;;
  esac
  rows="$(run_psql -Atc "SELECT count(*) FROM $table")"
  if [ "$rows" != "0" ]; then
    NONEMPTY="$NONEMPTY $table($rows)"
  fi
done
if [ -n "$NONEMPTY" ] && [ "$ALLOW_NONEMPTY" -eq 0 ]; then
  echo "目标库不是空的，拒绝导入：$NONEMPTY" >&2
  echo "种子是初始化手段，不是补数工具。确认要混入请加 --allow-nonempty。" >&2
  exit 2
fi

# ---------------------------------------------------------------------------
# 3. 校验文件完整性（sha256），解压到临时目录
# ---------------------------------------------------------------------------
# 临时目录必须放在纯 ASCII 路径下：Windows 版 psql 按本地编码（GBK）解析 \copy 的路径，
# 而 bash 传的是 UTF-8 字节。用户名含中文时，Temp 路径会被解成乱码，\copy 报
# 「No such file or directory」——文件其实就在那儿。这是实测踩过的坑。
mkdir -p target
WORK="$(mktemp -d "target/seed-import.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

for table in $TABLES; do
  file="$(get "table.$table.file")"
  want="$(get "table.$table.sha256")"
  src="$SEED_DIR/$file"
  if [ ! -f "$src" ]; then
    echo "缺文件：$src" >&2
    exit 2
  fi
  got="$(sha256sum "$src" | cut -d' ' -f1)"
  if [ "$got" != "$want" ]; then
    echo "校验和不符：$src（种子包被改动或传输损坏）" >&2
    exit 2
  fi
  gzip -dc "$src" >"$WORK/$table.csv" || exit 2
done

# ---------------------------------------------------------------------------
# 4. 单事务导入
# ---------------------------------------------------------------------------
WORK_ABS="$(cygpath -m "$WORK")"
SQL_FILE="$WORK/import.sql"
{
  echo "\\set ON_ERROR_STOP on"
  echo "BEGIN;"
  for table in $TABLES; do
    columns="$(get "table.$table.columns")"
    # 水位表在迁移里已经有初始行（id=1），直接 COPY 会撞主键。这一张表先清空再覆盖：
    # 语义就是「采用种子包的水位起点」。能走到这里的库必须满足「除水位表外全空」，
    # 因此清空不会抹掉任何真实事件——这一点由第 2 步的空库校验保证。
    if [ "$table" = "evt_revision_counter" ]; then
      echo "DELETE FROM evt_revision_counter;"
    fi
    printf "\\\\copy %s (%s) FROM '%s/%s.csv' WITH (FORMAT csv, HEADER true, NULL '')\n" \
      "$table" "$columns" "$WORK_ABS" "$table"
  done
  echo "COMMIT;"
} >"$SQL_FILE"

echo "导入 $DST_DB（种子 v$SEED_FLYWAY，$(awk -F= '$1 ~ /^table\..*\.rows$/ { s += $2 } END { print s }' "$MANIFEST") 行）"
if ! run_psql -q -f "$SQL_FILE"; then
  echo "导入失败，事务已回滚" >&2
  exit 3
fi

# ---------------------------------------------------------------------------
# 5. 逐表核对行数
# ---------------------------------------------------------------------------
MISMATCH=""
for table in $TABLES; do
  want="$(get "table.$table.rows")"
  got="$(run_psql -Atc "SELECT count(*) FROM $table")"
  if [ "$want" != "$got" ]; then
    MISMATCH="$MISMATCH $table(期望$want/实际$got)"
  fi
done
if [ -n "$MISMATCH" ]; then
  echo "导入后行数不一致：$MISMATCH" >&2
  exit 4
fi
echo "行数核对通过：$(echo "$TABLES" | wc -l) 张表与 manifest 一致"

# ---------------------------------------------------------------------------
# 6. 重建 query 侧投影
# ---------------------------------------------------------------------------
# 种子直接写 core 表，没有产生 outbox 事件，投影不会自己动。必须显式重建，否则公开站点
# 打开是空的——而 core 里明明有数据，这种「数据在但查不到」最难排查。
if [ "$DO_REBUILD" -eq 1 ]; then
  echo "触发投影重建 $QUERY_BASE ..."
  resp="$(curl -sS --noproxy '*' -X POST \
    -H "X-Service-Name: $SERVICE_NAME" \
    -H "X-Service-Token: $SERVICE_TOKEN" \
    -w '\n%{http_code}' \
    "$QUERY_BASE/internal/v1/maintenance/rebuild" 2>&1)"
  code="$(printf '%s' "$resp" | tail -n1)"
  body="$(printf '%s' "$resp" | sed '$d')"
  if [ "$code" != "200" ]; then
    echo "投影重建失败（HTTP $code）：$body" >&2
    echo "核心库数据已导入，可在 query 服务就绪后手工重试：curl -X POST -H 'X-Service-Name: core' -H 'X-Service-Token: <token>' $QUERY_BASE/internal/v1/maintenance/rebuild" >&2
    exit 3
  fi
  echo "投影重建返回：$body"
else
  echo "已跳过投影重建（--skip-rebuild）；公开站点在重建前查不到这批数据"
fi

echo "完成。种子包含 $(get limit) 条成果基线，其余表为关联数据。"
exit 0
