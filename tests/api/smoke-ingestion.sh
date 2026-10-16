#!/usr/bin/env bash
# ingestion-service 采集链路端到端自检
#
# 覆盖：管理端点鉴权委托 core（未登录 401 / 缺 CSRF 403 / 具能力 200）、
#       core 权威来源登记、ingestion 执行副本登记、权威配置版本同步、任务创建幂等、
#       有界执行、暂存与交接计数、core 侧落地核对、以及三条负向规则。
#
# 用法：bash tests/api/smoke-ingestion.sh
# 前置：ops/local/start-infra.sh 已执行；core-service(8081) 与 ingestion-service(8082) 已启动。
# 说明：本脚本会真实抓取 Crossref 公开 API，因此需要外网可达；网络不可达时第 9 节会失败，
#       这是脚本的正确行为——它不会把「抓不到数据」报告成通过。

set -uo pipefail

CORE="${CORE:-http://127.0.0.1:8081}"
ING="${ING:-http://127.0.0.1:8082}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR="$ROOT/target/smoke-ingestion"
rm -rf "$JAR" 2>/dev/null || true
mkdir -p "$JAR"
AJAR="$JAR/admin.cookies"

PASS=0
FAIL=0

# 宿主环境注入了 http_proxy，会把本机回环请求也送去代理并得到 502；
# 自检一律显式绕过代理，否则测的就不是本机服务。
C() { curl -s --noproxy '*' "$@"; }

PY_BIN="${PY_BIN:-python}"
if ! "$PY_BIN" -c 'import sys' >/dev/null 2>&1; then
  PY_BIN="/c/Users/白皓宇/.workbuddy/binaries/python/versions/3.13.12/python.exe"
fi

ADMIN_USER="${CN_BOOTSTRAP_ADMIN_USERNAME:-cnadmin}"
ADMIN_PASS="${CN_BOOTSTRAP_ADMIN_PASSWORD:-cn-admin-Dev-2026}"

SOURCE_CODE="${SOURCE_CODE:-crossref}"
TS="$(date +%s)"

say() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

check() {
  local desc="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    printf '  \033[32mPASS\033[0m  %-56s %s\n' "$desc" "$actual"
    PASS=$((PASS + 1))
  else
    printf '  \033[31mFAIL\033[0m  %-56s 期望 %s，实际 %s\n' "$desc" "$expected" "$actual"
    FAIL=$((FAIL + 1))
  fi
}

check_true() {
  local desc="$1" actual="$2"
  case "$actual" in
    true | True | 1 | yes) check "$desc" 'true' 'true' ;;
    *) check "$desc" 'true' "$actual" ;;
  esac
}

# 取 JSON 字段：json_get <json> <点分路径>
json_get() {
  printf '%s' "$1" | "$PY_BIN" -c '
import json, sys
path = [p for p in sys.argv[1].split(".") if p]
try:
    cur = json.load(sys.stdin)
except Exception:
    print("")
    raise SystemExit
for key in path:
    if isinstance(cur, dict):
        cur = cur.get(key)
    elif isinstance(cur, list):
        try:
            cur = cur[int(key)]
        except Exception:
            cur = None
    else:
        cur = None
    if cur is None:
        break
if cur is None:
    print("")
elif isinstance(cur, str):
    print(cur)
else:
    # 非字符串一律按 JSON 输出：布尔要输出 true/false 而不是 Python 的 True/False，
    # 否则断言里的既定值会与实际值「看起来一样但判等失败」。
    print(json.dumps(cur, ensure_ascii=False))
' "$2"
}

# 列表长度：list_len <json> <点分路径>
list_len() {
  printf '%s' "$1" | "$PY_BIN" -c '
import json, sys
cur = json.load(sys.stdin)
for key in [p for p in sys.argv[1].split(".") if p]:
    cur = cur.get(key) if isinstance(cur, dict) else None
    if cur is None:
        break
print(len(cur) if isinstance(cur, list) else -1)
' "$2"
}

csrf_for() {
  local base="$1" jar="$2"
  C -o /dev/null -b "$jar" -c "$jar" "$base/api/v1/auth/csrf"
  awk '/XSRF-TOKEN/ {print $NF}' "$jar" | tail -1
}

CSRF=""
# 重新取一次当前 CSRF 令牌。
# 必须在每次状态变更之前重新取：登录成功会轮换 CSRF 令牌，沿用登录前的值会被判为不匹配，
# 表现为「缺 CSRF」的 403，掩盖真正的业务结论。
api_csrf() { CSRF="$(csrf_for "$CORE" "$AJAR")"; }

status_of() {
  local method="$1" url="$2" jar="$3"
  shift 3
  C -o /dev/null -w '%{http_code}' -X "$method" -b "$jar" -c "$jar" "$url" "$@"
}

body_of() {
  local method="$1" url="$2" jar="$3"
  shift 3
  C -X "$method" -b "$jar" -c "$jar" "$url" "$@"
}

say '1. 服务可达性'
check 'core /actuator/health' '200' "$(C -o /dev/null -w '%{http_code}' "$CORE/actuator/health")"
check 'ingestion /actuator/health' '200' "$(C -o /dev/null -w '%{http_code}' "$ING/actuator/health")"

say '2. 管理端点未登录时拒绝'
check '未登录 GET /sources' '401' "$(C -o /dev/null -w '%{http_code}' "$ING/api/v1/admin/ingestion/sources")"

say '3. 登录 core 后采集管理可用'
CSRF="$(csrf_for "$CORE" "$AJAR")"
LOGIN_BODY="$(body_of POST "$CORE/api/v1/auth/login" "$AJAR" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $CSRF" \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}")"
check '登录平台管理员' 'OK' "$(json_get "$LOGIN_BODY" code)"
api_csrf
CAPS="$(body_of GET "$ING/api/v1/admin/ingestion/capabilities" "$AJAR")"
check '具采集管理能力读取能力清单' 'OK' "$(json_get "$CAPS" code)"
check '已实现适配器为 CROSSREF' 'CROSSREF' "$(json_get "$CAPS" 'data.adapterKinds.0.kind')"
check '已实现适配器为 OPENALEX' 'OPENALEX' "$(json_get "$CAPS" 'data.adapterKinds.1.kind')"

say '4. 状态变更缺 CSRF 时拒绝'
check '无 CSRF 建任务' '403' "$(C -o /dev/null -w '%{http_code}' -X POST -b "$AJAR" -c "$AJAR" \
  -H 'Content-Type: application/json' -d '{}' "$ING/api/v1/admin/ingestion/jobs")"

say '5. 登记 core 权威来源（许可与字段白名单的权威方）'
api_csrf
CORE_SOURCE_BODY="$(body_of POST "$CORE/api/v1/admin/core/sources" "$AJAR" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $CSRF" \
  -d "{\"sourceCode\":\"$SOURCE_CODE\",\"displayName\":\"Crossref 元数据\",\
\"licenseScope\":\"Crossref 公开 REST API 元数据许可\",\"baseUrl\":\"https://api.crossref.org/works\",\
\"allowedFields\":[\"kind\",\"title\",\"doi\",\"venue\",\"publicationYear\",\"publishedDate\",\
\"abstractText\",\"sourceCategory\",\"topics\",\"authors\",\"sourceUrl\"],\
\"enabled\":true,\"licenseReviewed\":true}")"
CORE_SOURCE_CODE="$(json_get "$CORE_SOURCE_BODY" code)"
case "$CORE_SOURCE_CODE" in
  OK | VERSION_CONFLICT) check 'core 来源已登记（或此前已存在）' 'true' 'true' ;;
  *) check 'core 来源已登记（或此前已存在）' 'OK/VERSION_CONFLICT' "$CORE_SOURCE_CODE" ;;
esac

say '6. 负向：许可未批准不允许启用'
api_csrf
BAD_BODY="$(body_of POST "$ING/api/v1/admin/ingestion/sources" "$AJAR" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $CSRF" \
  -d "{\"sourceCode\":\"crossref-bad-$TS\",\"adapterKind\":\"CROSSREF\",\
\"queryExpression\":\"test\",\"licenseReviewState\":\"PENDING_REVIEW\",\"enabled\":true}")"
check '未批准许可 + enabled=true 被拒' 'BUSINESS_RULE_VIOLATION' "$(json_get "$BAD_BODY" code)"

say '7. 负向：未实现的适配器种类被拒'
api_csrf
UNIMPL_BODY="$(body_of POST "$ING/api/v1/admin/ingestion/sources" "$AJAR" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $CSRF" \
  -d "{\"sourceCode\":\"s2-$TS\",\"adapterKind\":\"SEMANTIC_SCHOLAR\",\
\"queryExpression\":\"test\",\"licenseReviewState\":\"APPROVED\"}")"
check '未实现种类被拒' 'INVALID_ARGUMENT' "$(json_get "$UNIMPL_BODY" code)"

say '8. 登记 ingestion 执行副本并同步 core 权威配置'
api_csrf
SRC_BODY="$(body_of POST "$ING/api/v1/admin/ingestion/sources" "$AJAR" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $CSRF" \
  -d "{\"sourceCode\":\"$SOURCE_CODE\",\"displayName\":\"Crossref 采集执行副本\",\
\"adapterKind\":\"CROSSREF\",\"baseEndpoint\":\"https://api.crossref.org/works\",\
\"queryExpression\":\"graph neural network\",\"pageSize\":10,\
\"licenseReviewState\":\"APPROVED\",\"licenseNote\":\"来源为公开元数据 API\",\
\"rateLimitPerMinute\":30,\"enabled\":true}")"
SRC_CODE="$(json_get "$SRC_BODY" code)"
SRC_ID="$(json_get "$SRC_BODY" data.id)"
case "$SRC_CODE" in
  OK) check '执行副本已登记' 'OK' "$SRC_CODE" ;;
  VERSION_CONFLICT)
    SRC_ID="$(json_get "$(body_of GET "$ING/api/v1/admin/ingestion/sources" "$AJAR")" 'data.items.0.id')"
    check '执行副本已存在，复用既有配置' 'true' 'true'
    ;;
  *) check '执行副本已登记' 'OK' "$SRC_CODE" ;;
esac
check_true '取得来源运行配置 id' "$([ -n "$SRC_ID" ] && echo true || echo false)"

api_csrf
SYNC_BODY="$(body_of POST "$ING/api/v1/admin/ingestion/sources/$SRC_ID/sync" "$AJAR" \
  -H "X-XSRF-TOKEN: $CSRF")"
check '同步 core 权威配置' 'OK' "$(json_get "$SYNC_BODY" code)"
AUTH_VERSION="$(json_get "$SYNC_BODY" data.authoritativeConfigVersion)"
SYNCED_VERSION="$(json_get "$SYNC_BODY" data.syncedConfigVersion)"
check '同步版本与权威版本一致' "$AUTH_VERSION" "$SYNCED_VERSION"

api_csrf
SYNC_AGAIN="$(body_of POST "$ING/api/v1/admin/ingestion/sources/$SRC_ID/sync" "$AJAR" \
  -H "X-XSRF-TOKEN: $CSRF")"
check '重复同步不产生写入' 'false' "$(json_get "$SYNC_AGAIN" data.changed)"

say '9. 创建任务（幂等）并驱动真实抓取'
JOB_KEY="smoke-$TS"
api_csrf
JOB_BODY="$(body_of POST "$ING/api/v1/admin/ingestion/jobs" "$AJAR" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $CSRF" \
  -d "{\"sourceId\":\"$SRC_ID\",\"jobKey\":\"$JOB_KEY\",\"reason\":\"端到端自检\"}")"
check '创建采集任务' 'OK' "$(json_get "$JOB_BODY" code)"
check '首次创建为新建' 'true' "$(json_get "$JOB_BODY" data.created)"
JOB_ID="$(json_get "$JOB_BODY" data.job.id)"

api_csrf
AGAIN_BODY="$(body_of POST "$ING/api/v1/admin/ingestion/jobs" "$AJAR" \
  -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $CSRF" \
  -d "{\"sourceId\":\"$SRC_ID\",\"jobKey\":\"$JOB_KEY\"}")"
check '同 jobKey 重复提交返回既有任务' 'false' "$(json_get "$AGAIN_BODY" data.created)"
check '重复提交返回同一任务' "$JOB_ID" "$(json_get "$AGAIN_BODY" data.job.id)"

api_csrf
RUN_BODY="$(body_of POST "$ING/api/v1/admin/ingestion/jobs/$JOB_ID/run?maxRounds=2" "$AJAR" \
  -H "X-XSRF-TOKEN: $CSRF")"
check '手动驱动采集' 'OK' "$(json_get "$RUN_BODY" code)"
STAGED="$(json_get "$RUN_BODY" data.staged)"
DUP="$(json_get "$RUN_BODY" data.duplicated)"
HANDED="$(json_get "$RUN_BODY" data.handedOff)"
printf '        本轮结果：暂存 %s 条，稳定键重复 %s 条，已交接 %s 条\n' "$STAGED" "$DUP" "$HANDED"

say '10. 运行统计口径'
STAT_BODY="$(body_of GET "$ING/api/v1/admin/ingestion/jobs/$JOB_ID/statistics" "$AJAR")"
check '读取任务统计' 'OK' "$(json_get "$STAT_BODY" code)"
TOTAL_STAGED="$(json_get "$STAT_BODY" data.totalStaged)"
TOTAL_HANDED="$(json_get "$STAT_BODY" data.totalHandedOff)"
printf '        累计：抓取 %s 条，暂存 %s 条，已交接 %s 条，拒绝 %s 条\n' \
  "$(json_get "$STAT_BODY" data.totalFetched)" "$TOTAL_STAGED" "$TOTAL_HANDED" "$(json_get "$STAT_BODY" data.totalRejected)"
# 断言用累计口径，不用「本轮」增量：后台 CollectWorker 每 2 秒抢一次租约，任务往往在脚本
# 发出手动驱动之前就已被它跑完，此时本轮回 0 是真话而不是故障。
#
# 但也不能断言「累计暂存 > 0」。暂存去重是按**来源**做的（唯一键 source_config_id +
# source_record_id + content_fingerprint），不是按任务。一旦该来源的记录已被先前任务全部
# 入库，新任务重抓同一窗口必然全部命中稳定键——它就该是 0。把「上游此刻是否还有没入库的
# 记录」写成断言，等于让一次上游内容变化把测试判红，而系统毫无问题。实测证据：新任务
# 抓取 550 条、暂存 0 条，采集日志为「轮数=40 暂存=0 重复=400」。
#
# 本节改断两条真正属于本系统的性质：计数口径自洽，以及被修过的那条核心不变量——一个任务
# 只能交接自己暂存的记录（修复前取件按来源而非按任务，兄弟任务互相领走记录，出现
# 「已交接 > 暂存」的假账，历史残留至今仍违反这条）。
# 「真实记录确实落到了 core」由第 11 节的 core 侧行数证明，不依赖本节的计数是否为 0。
TOTAL_FETCHED="$(json_get "$STAT_BODY" data.totalFetched)"
check_true '确实抓取到真实来源记录（累计抓取 > 0）' \
  "$([ "${TOTAL_FETCHED:-0}" -gt 0 ] && echo true || echo false)"
check_true '暂存不超过抓取（计数口径自洽）' \
  "$([ "${TOTAL_STAGED:-0}" -le "${TOTAL_FETCHED:-0}" ] && echo true || echo false)"
check_true '已交接不超过暂存（只交接本任务暂存的记录）' \
  "$([ "${TOTAL_HANDED:-0}" -le "${TOTAL_STAGED:-0}" ] && echo true || echo false)"
if [ "${TOTAL_STAGED:-0}" -eq 0 ]; then
  printf '  \033[36mINFO\033[0m  本任务暂存 0 条：抓到的 %s 条全部命中既有稳定键（去重按来源，非按任务）。\n' \
    "${TOTAL_FETCHED:-?}"
  printf '  \033[36mINFO\033[0m  这是设计结果而非故障，真实落地看第 11 节 core 侧行数。\n'
fi
check '统计口径标注「已交接 ≠ 已发布」' 'true' \
  "$([ -n "$(json_get "$STAT_BODY" data.note)" ] && echo true || echo false)"

BATCHES_BODY="$(body_of GET "$ING/api/v1/admin/ingestion/jobs/$JOB_ID/batches" "$AJAR")"
check '交接批次可查' 'OK' "$(json_get "$BATCHES_BODY" code)"
printf '        批次数：%s\n' "$(json_get "$BATCHES_BODY" data.total)"

LOGS_BODY="$(body_of GET "$ING/api/v1/admin/ingestion/jobs/$JOB_ID/logs" "$AJAR")"
check '运行日志可查' 'OK' "$(json_get "$LOGS_BODY" code)"
printf '        日志条数：%s\n' "$(json_get "$LOGS_BODY" data.total)"

say '11. core 侧落地核对'
if command -v psql >/dev/null 2>&1 || [ -x /d/dev-tools/pgsql/bin/psql.exe ]; then
  PSQL="${PSQL:-/d/dev-tools/pgsql/bin/psql.exe}"
  PGPASSWORD="${CN_CORE_OWNER_PASSWORD:-cn_core_owner_dev}"
  export PGPASSWORD
  RSR="$("$PSQL" -h 127.0.0.1 -p 5432 -U core_owner -d core_db -Atc \
    "select count(*) from raw_source_record r join raw_source_config c on c.id = r.source_id where c.source_code = '$SOURCE_CODE'")"
  ACH="$("$PSQL" -h 127.0.0.1 -p 5432 -U core_owner -d core_db -Atc \
    "select count(*) from core_achievement")"
  IDEM="$("$PSQL" -h 127.0.0.1 -p 5432 -U core_owner -d core_db -Atc \
    "select count(*) from evt_outbox")"
  printf '        core 原始来源记录 %s 条；成果实体 %s 个；Outbox 事件 %s 条\n' "$RSR" "$ACH" "$IDEM"
  check_true 'core 已持久化来源原始记录' "$([ "${RSR:-0}" -gt 0 ] && echo true || echo false)"
  check_true 'core 已生成成果实体' "$([ "${ACH:-0}" -gt 0 ] && echo true || echo false)"
  check_true 'core 已写入 Outbox 事件' "$([ "${IDEM:-0}" -gt 0 ] && echo true || echo false)"
else
  printf '  \033[33mSKIP\033[0m  psql 不可用，跳过 core 侧数据库核对\n'
fi

say '12. 交接归属一致性（暂存该记录的任务 = 交接该批次的任务）'
#
# 这一节是一个真实缺陷的回归守卫。缺陷形态：交接取件按 source_config_id 过滤，而暂存去重
# 是按来源的（uk_ing_staged_handoff_key = 来源 + 来源记录号 + 内容指纹）。同一来源上跑多个
# 任务时，同一个来源记录只会留下一行、归先抓到它的那个任务所有，但后来的任务会按来源把它
# 领走、挂到自己的批次上。于是出现三个后果：交接计数记在没抓过这些记录的任务头上（表现为
# 「交接数 > 暂存数」这种漏斗说不通的假账）、真正抓取它们的任务永久等不到自己的记录被交接、
# 而批次归属与记录归属这两个本该相同的维度被拆开了。
#
# 断言的是**归属不变量**而不是某个计数的具体取值：记录所属任务必须等于其批次所属任务。
#
# 为什么是前瞻式而不是绝对式：修复前已经入库的记录里，有 48 条就是这样错配的，且它们的
# 「已经交接完成」这一历史动作没留下可精确回放的痕迹，无法在不猜测的前提下重算。因此本节
# 断言的是「错配数在新增交接期间不再增长」，而不是「错配数为 0」——后者要么逼着我们去改写
# 历史数据、要么逼着我们假装那 48 条不存在，两者都是造假。
if command -v psql >/dev/null 2>&1 || [ -x /d/dev-tools/pgsql/bin/psql.exe ]; then
  PSQL="${PSQL:-/d/dev-tools/pgsql/bin/psql.exe}"
  # 错配数：行所属任务 ≠ 批次所属任务
  MISMATCH_SQL="select count(*) from ing_staged_record r join ing_handoff_batch b on b.id = r.handoff_batch_id where r.job_id <> b.job_id"
  HANDED_SQL="select count(*) from ing_staged_record where handoff_state = 'HANDED_OFF'"
  ing_q() {
    PGPASSWORD="${CN_INGESTION_OWNER_PASSWORD:-cn_ingestion_owner_dev}" \
      "$PSQL" -h 127.0.0.1 -p 5432 -U ingestion_owner -d ingestion_db -Atc "$1" 2>/dev/null | tr -d '\r'
  }
  MIS0="$(ing_q "$MISMATCH_SQL")"
  HO0="$(ing_q "$HANDED_SQL")"
  printf '        基线：已交接 %s 条，其中归属错配 %s 条（历史遗留）\n' "${HO0:-?}" "${MIS0:-?}"
  # 等新的交接发生，否则「不变」是没做事的不变，证明不了任何东西。
  HO1="${HO0:-0}"
  WAITED=0
  while [ "$WAITED" -lt 20 ] && [ "${HO1:-0}" -le "${HO0:-0}" ]; do
    sleep 3
    WAITED=$((WAITED + 1))
    HO1="$(ing_q "$HANDED_SQL")"
  done
  MIS1="$(ing_q "$MISMATCH_SQL")"
  if [ "${HO1:-0}" -gt "${HO0:-0}" ]; then
    check '新增交接期间批次归属未再错配' "${MIS0:-x}" "${MIS1:-y}"
    printf '        期间新增交接 %s 条，错配数 %s → %s\n' \
      "$((HO1 - HO0))" "${MIS0:-?}" "${MIS1:-?}"
  else
    printf '  \033[33mSKIP\033[0m  等待 %s 秒内没有新的交接发生，本次无法证明归属不变量（不记为通过）\n' \
      "$((WAITED * 3))"
  fi
else
  printf '  \033[33mSKIP\033[0m  psql 不可用，跳过交接归属一致性核对\n'
fi

# 收尾：暂停本次自检创建的任务。
# 为什么必须收：本脚本每次运行都以带时间戳的 jobKey 新建一个任务，若不收，每个 RUNNING 任务
# 都会被 CollectWorker 按 2 秒一轮持续驱动（抓取失败无退避），任务越积越多、来源被反复空扫，
# 也让后续运行更难拿到静默窗口——实测一次运行就留下两个 RUNNING 任务在空转。
# 暂停而非删除：暂停是可恢复的状态变更，且不丢任何已暂存记录。
if [ -n "${JOB_ID:-}" ]; then
  api_csrf
  PAUSE_BODY="$(body_of POST "$ING/api/v1/admin/ingestion/jobs/$JOB_ID/pause" "$AJAR" \
    -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $CSRF" \
    -d '{"reason":"采集自检收尾：避免留下持续空转的任务"}')"
  check '自检收尾暂停本次任务（不留下空转任务）' 'OK' "$(json_get "$PAUSE_BODY" code)"
fi

printf '\n\033[1m== 结果 ==\033[0m 通过 %s 项，失败 %s 项\n' "$PASS" "$FAIL"
if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
exit 0
