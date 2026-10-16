#!/usr/bin/env bash
# 立即驱动一轮真实采集。
#
# 用途：本地演示与验收时不想等后台 CollectWorker 的自然节奏，或需要在改动来源配置后立刻
# 用真实数据验证映射结果（例如新增了引用量字段，需要确认它确实落到了 core）。
#
# 用法：bash ops/local/collect-once.sh [来源编码] [轮数]
# 默认：crossref 2 轮。轮数为 0 时只读统计、不触发抓取。
#
# 前置：core-service(8081)、ingestion-service(8082) 已启动。
# 说明：本脚本只驱动采集与交接，不做审核与发布；「已交接」不等于「已发布」。
#       需要外网可达（Crossref 公开 API）。

set -uo pipefail

CORE="${CORE:-http://127.0.0.1:8081}"
ING="${ING:-http://127.0.0.1:8082}"
SRC_CODE="${1:-crossref}"
ROUNDS="${2:-2}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR="$ROOT/target/collect-once"
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

# CSRF 必须在每次状态变更前重新取：登录成功会轮换令牌，沿用旧值会被判为不匹配。
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
code=sys.argv[1]
for s in json.loads(sys.argv[2])['data']['items']:
    if s.get('sourceCode')==code:
        print(s['id']); break
print(end='')
" "$SRC_CODE" "$SOURCES")"
if [ -z "$SRC_ID" ]; then
  echo "未找到来源 $SRC_CODE；可用来源见 $ING/api/v1/admin/ingestion/sources" >&2
  exit 1
fi
echo "来源 $SRC_CODE → $SRC_ID"

# 先把 core 的权威来源配置同步到本服务执行副本。
# 必须显式做：改了字段白名单或查询式之后不同步，采集器仍按旧白名单过滤，「改了等于没改」，
# 而表面上一切正常——这类静默不生效比报错更难发现。
TOKEN="$(csrf)"
SYNC="$(C -b "$COOKIES" -c "$COOKIES" -X POST -H "X-XSRF-TOKEN: $TOKEN" \
  "$ING/api/v1/admin/ingestion/sources/$SRC_ID/sync")"
if [ "$(json_get "$SYNC" code)" = "OK" ]; then
  echo "已同步权威配置：configVersion=$(json_get "$SYNC" data.source.configVersion)"
else
  echo "同步权威配置失败：$(json_get "$SYNC" message)" >&2
fi

TS="$(date +%s)"
TOKEN="$(csrf)"
JOB="$(C -b "$COOKIES" -c "$COOKIES" -X POST \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $TOKEN" \
  -d "{\"sourceId\":\"$SRC_ID\",\"jobKey\":\"manual-$TS\",\"reason\":\"运维手动驱动一轮真实采集\"}" \
  "$ING/api/v1/admin/ingestion/jobs")"
JOB_ID="$(json_get "$JOB" data.job.id)"
echo "任务 $JOB_ID（新建=$(json_get "$JOB" data.created)）"

if [ "$ROUNDS" -gt 0 ]; then
  TOKEN="$(csrf)"
  RUN="$(C -b "$COOKIES" -c "$COOKIES" -X POST -H "X-XSRF-TOKEN: $TOKEN" \
    "$ING/api/v1/admin/ingestion/jobs/$JOB_ID/run?maxRounds=$ROUNDS")"
  echo "驱动结果：暂存 $(json_get "$RUN" data.staged) 条，稳定键重复 $(json_get "$RUN" data.duplicated) 条，已交接 $(json_get "$RUN" data.handedOff) 条"
fi

STAT="$(C -b "$COOKIES" -c "$COOKIES" "$ING/api/v1/admin/ingestion/jobs/$JOB_ID/statistics")"
echo "累计：抓取 $(json_get "$STAT" data.totalFetched) 条，暂存 $(json_get "$STAT" data.totalStaged) 条，已交接 $(json_get "$STAT" data.totalHandedOff) 条，拒绝 $(json_get "$STAT" data.totalRejected) 条"
echo "$(json_get "$STAT" data.note)"
