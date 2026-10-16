#!/usr/bin/env bash
# core-service 接口端到端自检
#
# 覆盖：CSRF、注册、登录、会话、能力授权、越权拒绝、登出后的会话撤销、
#       登录失败节流、平台管理员引导账号可用性。
#
# 用法：bash tests/api/smoke-core.sh [base_url]
# 前置：ops/local/start-infra.sh 已执行；core-service 已启动。

set -uo pipefail

BASE="${1:-http://127.0.0.1:8081}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# 工作目录放在 target/ 下：该目录不入库，且避免沙箱对系统临时目录删除的限制
JAR="$ROOT/target/smoke-core"
rm -rf "$JAR" 2>/dev/null || true
mkdir -p "$JAR"
ADMIN_JAR="$JAR/admin.cookies"
USER_JAR="$JAR/user.cookies"
PASS=0
FAIL=0

# 引导管理员凭据（与 application.yml 中 cn.bootstrap 默认值一致，可用环境变量覆盖）
ADMIN_USER="${CN_BOOTSTRAP_ADMIN_USERNAME:-cnadmin}"
ADMIN_PASS="${CN_BOOTSTRAP_ADMIN_PASSWORD:-cn-admin-Dev-2026}"

say() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

check() {
  local desc="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    printf '  \033[32mPASS\033[0m  %-52s %s\n' "$desc" "$actual"
    PASS=$((PASS + 1))
  else
    printf '  \033[31mFAIL\033[0m  %-52s 期望 %s，实际 %s\n' "$desc" "$expected" "$actual"
    FAIL=$((FAIL + 1))
  fi
}

# 取 CSRF 令牌：先请求一次，再从 cookie 文件读出 XSRF-TOKEN。
# 必须带 -b 让 curl 先载入既有 Cookie，否则 -c 会用本次响应覆盖整个 jar，
# 把会话 Cookie 一并丢掉——表现为后续请求莫名变成未登录。
csrf_for() {
  local jar="$1"
  curl -s -o /dev/null -b "$jar" -c "$jar" "$BASE/api/v1/auth/csrf"
  awk '/XSRF-TOKEN/ {print $NF}' "$jar" | tail -1
}

# 仅取 HTTP 状态码
status_of() {
  local method="$1" path="$2" jar="$3"
  shift 3
  curl -s -o /dev/null -w '%{http_code}' -X "$method" -b "$jar" -c "$jar" "$BASE$path" "$@"
}

# 取响应体
body_of() {
  local method="$1" path="$2" jar="$3"
  shift 3
  curl -s -X "$method" -b "$jar" -c "$jar" "$BASE$path" "$@"
}

TS="$(date +%s)"
NEW_USER="smoke_${TS: -8}"
NEW_PASS="Smoke-Pass-${TS}"

say '1. 服务可达性'
check 'actuator/health' '200' "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/actuator/health")"

say '2. CSRF 令牌下发'
TOKEN="$(csrf_for "$ADMIN_JAR")"
if [ -n "$TOKEN" ]; then check 'XSRF-TOKEN cookie 已下发' 'yes' 'yes'; else check 'XSRF-TOKEN cookie 已下发' 'yes' 'no'; fi

say '3. 注册普通用户'
REG_STATUS="$(curl -s -o "$JAR/reg.json" -w '%{http_code}' -X POST \
  -b "$ADMIN_JAR" -c "$ADMIN_JAR" \
  -H "X-XSRF-TOKEN: $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$NEW_USER\",\"password\":\"$NEW_PASS\",\"displayName\":\"自检用户\"}" \
  "$BASE/api/v1/auth/register")"
check '注册返回 200' '200' "$REG_STATUS"
REGISTERED_ID="$(sed -n 's/.*"accountId":"\([^"]*\)".*/\1/p' "$JAR/reg.json")"
if [ -n "$REGISTERED_ID" ]; then check '返回新账号标识' 'yes' 'yes'; else check '返回新账号标识' 'yes' 'no'; fi

say '4. 无令牌的状态变更请求应被拒绝'
check '缺 CSRF 令牌的登录返回 403' '403' \
  "$(curl -s -o /dev/null -w '%{http_code}' -X POST -b "$ADMIN_JAR" -c "$ADMIN_JAR" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" "$BASE/api/v1/auth/login")"

say '5. 平台管理员登录'
# 登录会更换会话标识，需重新取一次令牌
TOKEN="$(csrf_for "$ADMIN_JAR")"
LOGIN_STATUS="$(curl -s -o "$JAR/login.json" -w '%{http_code}' -X POST \
  -b "$ADMIN_JAR" -c "$ADMIN_JAR" \
  -H "X-XSRF-TOKEN: $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" "$BASE/api/v1/auth/login")"
check '管理员登录 200' '200' "$LOGIN_STATUS"
check '会话 Cookie 已下发' 'yes' \
  "$(grep -qE 'SESSION|JSESSIONID' "$ADMIN_JAR" && echo yes || echo no)"
ADMIN_ID="$(sed -n 's/.*"accountId":"\([^"]*\)".*/\1/p' "$JAR/login.json")"

say '6. 当前身份'
ME_BODY="$(body_of GET /api/v1/auth/me "$ADMIN_JAR")"
check 'me 返回 200 且含 PLATFORM_ADMIN' 'yes' \
  "$(echo "$ME_BODY" | grep -q 'PLATFORM_ADMIN' && echo yes || echo no)"

say '7. 能力授权：管理员可读账号列表'
check '管理员访问账号列表 200' '200' "$(status_of GET /api/v1/admin/core/accounts "$ADMIN_JAR")"

say '8. 普通用户登录并越权访问'
TOKEN="$(csrf_for "$USER_JAR")"
USER_LOGIN="$(curl -s -o /dev/null -w '%{http_code}' -X POST -b "$USER_JAR" -c "$USER_JAR" \
  -H "X-XSRF-TOKEN: $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$NEW_USER\",\"password\":\"$NEW_PASS\"}" "$BASE/api/v1/auth/login")"
check '普通用户登录 200' '200' "$USER_LOGIN"
check '普通用户访问账号列表被拒 403' '403' "$(status_of GET /api/v1/admin/core/accounts "$USER_JAR")"

say '9. 登出与会话撤销'
TOKEN="$(csrf_for "$USER_JAR")"
check '登出 200' '200' "$(status_of POST /api/v1/auth/logout "$USER_JAR" -H "X-XSRF-TOKEN: $TOKEN")"
check '登出后 /me 返回 401' '401' "$(status_of GET /api/v1/auth/me "$USER_JAR")"

say '10. 登录失败节流与锁定'
# 短窗限流阈值为每分钟 12 次（LoginThrottle.MAX_ATTEMPTS_PER_WINDOW），
# 这里故意发 14 次以确定跨过阈值
LOCK_JAR="$JAR/lock.cookies"
LOCK_USER="lock_${TS: -8}"
TOKEN="$(csrf_for "$ADMIN_JAR")"
curl -s -o /dev/null -X POST -b "$ADMIN_JAR" -c "$ADMIN_JAR" \
  -H "X-XSRF-TOKEN: $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$LOCK_USER\",\"password\":\"Lock-Pass-${TS}\",\"displayName\":\"锁定测试\"}" \
  "$BASE/api/v1/auth/register"
TOKEN="$(csrf_for "$LOCK_JAR")"
LAST_CODE=""
for i in $(seq 1 14); do
  LAST_CODE="$(curl -s -o /dev/null -w '%{http_code}' -X POST -b "$LOCK_JAR" -c "$LOCK_JAR" \
    -H "X-XSRF-TOKEN: $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$LOCK_USER\",\"password\":\"wrong-pass-$i\"}" "$BASE/api/v1/auth/login")"
done
check '连续错误口令最终被限流 429' '429' "$LAST_CODE"

say '11. 统一响应外壳'
ERR_BODY="$(body_of GET /api/v1/admin/core/accounts "$USER_JAR")"
check '错误响应含 code 字段' 'yes' "$(echo "$ERR_BODY" | grep -q '"code"' && echo yes || echo no)"
check '错误响应含 traceId 字段' 'yes' "$(echo "$ERR_BODY" | grep -q '"traceId"' && echo yes || echo no)"

say '12. 内部接口令牌校验（缺令牌应被拒）'
check '无服务令牌访问 /internal/v1 被拒' '403' \
  "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/internal/v1/public-barrier")"

say '13. 审计读取'
#
# 这一节是一个真实缺陷的回归守卫。缺陷形态：审计 SQL 的可选条件写成
# 「#{p} IS NULL OR col = #{p}」，参数为空时 PostgreSQL 推不出 $1 的类型，
# 直接 500（could not determine data type of parameter $1）。触发条件是「不带任何筛选」——
# 恰好是审计页打开时的默认请求；带了条件反而不触发，所以只按条件查询的验证测不出它。
# 因此这里必须同时断言「不带条件」与「带条件」两条路径。
check '不带任何筛选的审计查询 200' '200' \
  "$(status_of GET '/api/v1/admin/core/audit?page=1&size=20' "$ADMIN_JAR")"
check '按 targetType 筛选的审计查询 200' '200' \
  "$(status_of GET '/api/v1/admin/core/audit?page=1&size=20&targetType=ACCOUNT' "$ADMIN_JAR")"
AUDIT_BODY="$(body_of GET '/api/v1/admin/core/audit?page=1&size=20' "$ADMIN_JAR")"
check '审计响应含 total 元数据' 'yes' \
  "$(printf '%s' "$AUDIT_BODY" | grep -q '"total"' && echo yes || echo no)"
# 第 9 节已把普通用户登出；这里测的是「已登录但无能力」的 403，所以先重新登录。
TOKEN="$(csrf_for "$USER_JAR")"
curl -s -o /dev/null -X POST -b "$USER_JAR" -c "$USER_JAR" \
  -H "X-XSRF-TOKEN: $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$NEW_USER\",\"password\":\"$NEW_PASS\"}" "$BASE/api/v1/auth/login"
check '普通用户读审计被拒 403' '403' \
  "$(status_of GET '/api/v1/admin/core/audit?page=1&size=20' "$USER_JAR")"

printf '\n\033[1m== 结果 ==\033[0m\n  通过 %d，失败 %d\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
