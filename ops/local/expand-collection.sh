#!/usr/bin/env bash
# 扩充采集数据量：把公开来源抓到的成果数量推上去（本地验收用）。
#
# 用途：任务书验收要求「系统入库学术成果数据不少于 1 万条」。本脚本不改动采集语义，
# 只是把同一个来源的翻页推进足够远，并把每页条数调大以提高吞吐。
#
# 用法：bash ops/local/expand-collection.sh [轮数] [每页条数]
# 默认：12 轮、每页 200 条（约 2400 次抓取条目）。
#
# 前置：整栈已启动（ops/local/stack.sh start），且外网可达 Crossref。
# 说明：本脚本只驱动采集与交接，不代替 core 的治理判定；「已交接」不等于「已发布」。
#       每轮都会打印 core 侧的成果数与公开检索总数，便于判断还需要推进多少轮。

set -uo pipefail

CORE="${CORE:-http://127.0.0.1:8081}"
ING="${ING:-http://127.0.0.1:8082}"
QUERY="${QUERY:-http://127.0.0.1:8083}"
ROUNDS="${1:-12}"
PAGE_SIZE="${2:-200}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR="$ROOT/target/expand-collection"
mkdir -p "$JAR"
COOKIES="$JAR/admin.cookies"

C() { curl -s --noproxy '*' "$@"; }

PY_BIN="${PY_BIN:-python}"
if ! "$PY_BIN" -c 'import sys' >/dev/null 2>&1; then
  PY_BIN="/c/Users/白皓宇/.workbuddy/binaries/python/versions/3.13.12/python.exe"
fi

ADMIN_USER="${CN_BOOTSTRAP_ADMIN_USERNAME:-cnadmin}"
ADMIN_PASS="${CN_BOOTSTRAP_ADMIN_PASSWORD:-cn-admin-Dev-2026}"

json_get() {
  printf '%s' "$1" | "$PY_BIN" -c '
import json, sys
cur = json.load(sys.stdin)
for key in [p for p in sys.argv[1].split(".") if p]:
    cur = cur.get(key) if isinstance(cur, dict) else None
    if cur is None:
        break
print("" if cur is None else (cur if isinstance(cur, str) else json.dumps(cur, ensure_ascii=False)))
' "$2"
}

csrf() {
  C -o /dev/null -b "$COOKIES" -c "$COOKIES" "$CORE/api/v1/auth/csrf"
  awk '/XSRF-TOKEN/ {print $NF}' "$COOKIES" | tail -1
}

TOKEN="$(csrf)"
LOGIN="$(C -b "$COOKIES" -c "$COOKIES" -X POST \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $TOKEN" \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" \
  "$CORE/api/v1/auth/login")"
if [ "$(json_get "$LOGIN" code)" != "OK" ]; then
  echo "登录 core 失败：$(json_get "$LOGIN" message)" >&2
  exit 1
fi

SOURCES="$(C -b "$COOKIES" -c "$COOKIES" "$ING/api/v1/admin/ingestion/sources")"
SRC_ID="$("$PY_BIN" -c "
import json,sys
for s in json.loads(sys.argv[1])['data']['items']:
    if s.get('sourceCode')=='crossref':
        print(s['id']); break
print(end='')
" "$SOURCES")"
if [ -z "$SRC_ID" ]; then
  echo "未找到 crossref 来源，请先在管理后台登记" >&2
  exit 1
fi
echo "来源 crossref → $SRC_ID"

# 先把 core 的权威配置同步下来：不同步时本地副本版本落后，采集会按旧白名单执行。
TOKEN="$(csrf)"
C -b "$COOKIES" -c "$COOKIES" -X POST -H "X-XSRF-TOKEN: $TOKEN" \
  "$ING/api/v1/admin/ingestion/sources/$SRC_ID/sync" >/dev/null

echo "把每页条数设为 $PAGE_SIZE"
TOKEN="$(csrf)"
PATCH="$(C -b "$COOKIES" -c "$COOKIES" -X PATCH \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $TOKEN" \
  -d "{\"pageSize\":$PAGE_SIZE,\"reason\":\"扩充采集吞吐：验收要求入库成果不少于 1 万条\"}" \
  "$ING/api/v1/admin/ingestion/sources/$SRC_ID")"
if [ "$(json_get "$PATCH" code)" != "OK" ]; then
  echo "更新分页大小失败：$(json_get "$PATCH" message)" >&2
  exit 1
fi
echo "当前每页条数：$(json_get "$PATCH" data.pageSize)"

report() {
  local label="$1"
  local total
  total="$(C "$QUERY/api/v1/public/search/achievements?size=1" | "$PY_BIN" -c '
import json,sys
try:
    print(json.load(sys.stdin)["data"]["total"])
except Exception:
    print("?")
')"
  echo "  [$label] 公开检索可见成果数：$total"
}

report "起始"

TS="$(date +%s)"
TOKEN="$(csrf)"
JOB="$(C -b "$COOKIES" -c "$COOKIES" -X POST \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $TOKEN" \
  -d "{\"sourceId\":\"$SRC_ID\",\"jobKey\":\"expand-$TS\",\"reason\":\"扩充数据量至验收规模\"}" \
  "$ING/api/v1/admin/ingestion/jobs")"
JOB_ID="$(json_get "$JOB" data.job.id)"
if [ -z "$JOB_ID" ]; then
  echo "创建任务失败：$(json_get "$JOB" message)" >&2
  exit 1
fi
echo "任务 $JOB_ID（新建=$(json_get "$JOB" data.created)）"

for i in $(seq 1 "$ROUNDS"); do
  TOKEN="$(csrf)"
  RUN="$(C -b "$COOKIES" -c "$COOKIES" -X POST -H "X-XSRF-TOKEN: $TOKEN" \
    "$ING/api/v1/admin/ingestion/jobs/$JOB_ID/run?maxRounds=1")"
  echo "第 $i 轮：$(json_get "$RUN" data.message 2>/dev/null || true)$(json_get "$RUN" message)"
  if [ $((i % 3)) -eq 0 ]; then
    report "第 $i 轮后"
  fi
done

STAT="$(C -b "$COOKIES" -c "$COOKIES" "$ING/api/v1/admin/ingestion/jobs/$JOB_ID/statistics")"
echo "累计：抓取 $(json_get "$STAT" data.totalFetched) 条，暂存 $(json_get "$STAT" data.totalStaged) 条，已交接 $(json_get "$STAT" data.totalHandedOff) 条，拒绝 $(json_get "$STAT" data.totalRejected) 条"
report "结束"
echo "注意：core 侧入库与治理发布是异步的，检索可见数可能滞后于交接数。"
