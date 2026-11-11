#!/usr/bin/env bash
# 领域分类回填：按批推进规则推断，直到没有待处理成果。
#
# 用法：bash ops/local/backfill-topics.sh [批大小] [--dry-run]
# 前置：整栈已启动（ops/local/stack.sh），且 core 的领域推断端点可用。
#
# 为什么做成"按批推到清空"的脚本而不是一键接口：
#   单批上限 500 且整批一个事务。把它做成一个能跑十几分钟的接口，失败时既看不出停在哪，
#   也没有中途叫停的机会。脚本按批推进，每批打印产出，随时可以 Ctrl-C；重跑会从当前
#   进度继续（判不出领域的成果已记入台账，不会再被取出来）。
#
# 为什么每批都重新取 CSRF 令牌：
#   CSRF 令牌用过一次即轮换，沿用上一批的值会得到 403 ACCESS_DENIED——错误码看起来像权限
#   问题，实际只是令牌过期，极容易查错方向。

set -uo pipefail

CORE="${CORE:-http://127.0.0.1:8081}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR="$ROOT/target/backfill-topics"
rm -rf "$JAR" 2>/dev/null || true
mkdir -p "$JAR"
AJAR="$JAR/admin.cookies"

BATCH=""
DRY="false"
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY="true" ;;
    *) BATCH="$arg" ;;
  esac
done
BATCH="${BATCH:-500}"

ADMIN_USER="${CN_BOOTSTRAP_ADMIN_USERNAME:-cnadmin}"
ADMIN_PASS="${CN_BOOTSTRAP_ADMIN_PASSWORD:-cn-admin-Dev-2026}"

PY_BIN="${PY_BIN:-python}"
if ! "$PY_BIN" -c 'import sys' >/dev/null 2>&1; then
  PY_BIN="/c/Users/白皓宇/.workbuddy/binaries/python/versions/3.13.12/python.exe"
fi

# 宿主环境注入了 http_proxy，会把本机回环请求也送去代理并得到 502
C() { curl -s --noproxy '*' "$@"; }

csrf() {
  C -o /dev/null -b "$AJAR" -c "$AJAR" "$CORE/api/v1/auth/csrf"
  awk '/XSRF-TOKEN/ {print $NF}' "$AJAR" | tail -1
}

json_get() {
  "$PY_BIN" -c '
import json, sys
cur = json.loads(sys.argv[1])
for key in [p for p in sys.argv[2].split(".") if p]:
    if isinstance(cur, dict):
        cur = cur.get(key)
    else:
        cur = None
    if cur is None:
        break
print(cur if cur is not None else "")
' "$1" "$2"
}

T="$(csrf)"
LOGIN="$(C -X POST -b "$AJAR" -c "$AJAR" "$CORE/api/v1/auth/login" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $T" \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}")"
if [ "$(json_get "$LOGIN" code)" != "OK" ]; then
  echo "登录失败：$LOGIN"
  exit 1
fi

T="$(csrf)"
SUMMARY="$(C -b "$AJAR" "$CORE/api/v1/admin/core/topics/inference-summary")"
RULE="$(json_get "$SUMMARY" 'data.ruleVersion')"
echo "词表：$(json_get "$SUMMARY" 'data.taxonomy')"
echo "规则版本：$RULE"
echo "起始待处理：$(json_get "$SUMMARY" 'data.pending') 条，已有推断归属：$(json_get "$SUMMARY" 'data.inferredRows') 行"
echo "批大小：$BATCH  模式：$( [ "$DRY" = true ] && echo dryRun || echo 落库 )"
echo

ROUND=0
TOTAL_ASSIGNED=0
TOTAL_UNRESOLVED=0
TOTAL_CREATED=0
TOTAL_IDEMPOTENT=0
# 停滞保护：台账就是为了让队列必然前进而存在的。若剩余量连续多轮不降，说明台账没生效
# （例如迁移没应用），此时继续循环只会无限刷请求，必须显式停下并报错。
STALLED=0
LAST_REMAIN=""
while :; do
  ROUND=$((ROUND + 1))
  T="$(csrf)"
  BODY="$(C -X POST -b "$AJAR" -c "$AJAR" "$CORE/api/v1/admin/core/topics/infer" \
    -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $T" \
    -d "{\"limit\":$BATCH,\"dryRun\":$DRY,\"reason\":\"领域分类回填：接通学科领域数据\"}")"
  if [ "$(json_get "$BODY" code)" != "OK" ]; then
    echo "第 $ROUND 批失败：$BODY"
    exit 1
  fi
  EXAMINED="$(json_get "$BODY" 'data.examined')"
  ASSIGNED="$(json_get "$BODY" 'data.assigned')"
  UNRESOLVED="$(json_get "$BODY" 'data.unresolved')"
  CREATED="$(json_get "$BODY" 'data.createdRows')"
  IDEM="$(json_get "$BODY" 'data.idempotentRows')"
  REMAIN="$(json_get "$BODY" 'data.pendingRemaining')"
  ROWS="$(json_get "$BODY" 'data.inferredRows')"
  printf '第 %2d 批：处理 %4s  判出 %4s  判不出 %4s  新建 %4s  幂等 %s  剩余 %s  累计归属 %s 行\n' \
    "$ROUND" "$EXAMINED" "$ASSIGNED" "$UNRESOLVED" "$CREATED" "$IDEM" "$REMAIN" "$ROWS"
  TOTAL_ASSIGNED=$((TOTAL_ASSIGNED + ASSIGNED))
  TOTAL_UNRESOLVED=$((TOTAL_UNRESOLVED + UNRESOLVED))
  TOTAL_CREATED=$((TOTAL_CREATED + CREATED))
  TOTAL_IDEMPOTENT=$((TOTAL_IDEMPOTENT + IDEM))
  if [ "$EXAMINED" = "0" ] || [ -z "$EXAMINED" ]; then
    break
  fi
  if [ "$REMAIN" = "$LAST_REMAIN" ]; then
    STALLED=$((STALLED + 1))
    if [ "$STALLED" -ge 3 ]; then
      echo "待处理量连续 3 轮未下降（$REMAIN），中止：台账可能未生效，继续循环只会空转。"
      exit 1
    fi
  else
    STALLED=0
  fi
  LAST_REMAIN="$REMAIN"
done

echo
echo "=== 回填结束 ==="
echo "批数：$ROUND"
echo "判出领域的成果：$TOTAL_ASSIGNED"
echo "记为待分类的成果：$TOTAL_UNRESOLVED"
echo "新建归属行：$TOTAL_CREATED"
echo "重复（幂等）行：$TOTAL_IDEMPOTENT"
echo
T="$(csrf)"
FINAL="$(C -b "$AJAR" "$CORE/api/v1/admin/core/topics/inference-summary")"
echo "覆盖率概况：$(json_get "$FINAL" 'data.byOutcome')"
echo "归属来源盘点：$(json_get "$FINAL" 'data.byAssignmentKind')"
