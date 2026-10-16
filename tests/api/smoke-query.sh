#!/usr/bin/env bash
# query-service 读投影 / 检索 / 统计 / 合作网络端到端自检
#
# 覆盖：三水位语义与修订空洞、公开屏障与资格核实、检索（关键词/排序白名单/深分页/查询版本）、
#       学者检索、分面、统计（未知单列、不跨来源求和）、合作网络（图与最短路径、显式降级）、
#       数据性质作用域、内部端点 fail-closed、投递健康度与死信区间。
#
# 用法：bash tests/api/smoke-query.sh
# 前置：core-service(8081)、ingestion-service(8082)、query-service(8083) 均已启动，
#       且 query 已消费完 core 的 Outbox（否则第 2 节会因积压而失败——这是脚本的正确行为）。
#
# 本脚本不做任何写操作，除了若干显式的负向断言（伪造内部请求必须被拒），因此可以反复执行。
#
# 关于断言写法：所有需要判断的字段值先取进 shell 变量，再交给 pycheck 以 argv 传入。
# 不写成 "$(python -c "... $(json_get ...) ...")"：那种嵌套要同时穿过 shell 双引号、python
# 单引号和正则里的 $ 三道转义，错一处报的都是「unexpected EOF」而不是真正缺失的引号，排查成本
# 远高于多写一行变量。

set -uo pipefail

CORE="${CORE:-http://127.0.0.1:8081}"
QRY="${QRY:-http://127.0.0.1:8083}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR="$ROOT/target/smoke-query"
rm -rf "$JAR" 2>/dev/null || true
mkdir -p "$JAR"

PASS=0
FAIL=0

# 宿主环境注入了 http_proxy，会把本机回环请求也送去代理并得到 502；
# 自检一律显式绕过代理，否则测的就不是本机服务。
C() { curl -s --noproxy '*' "$@"; }

PY_BIN="${PY_BIN:-python}"
if ! "$PY_BIN" -c 'import sys' >/dev/null 2>&1; then
  PY_BIN="/c/Users/白皓宇/.workbuddy/binaries/python/versions/3.13.12/python.exe"
fi

# 服务身份：query 的内部端点要求 X-Service-Name + X-Service-Token，且配置为空时一律拒绝。
SVC_NAME="${CN_SVC_NAME:-core}"
SVC_TOKEN="${CN_QUERY_SERVICE_TOKEN:-cn_query_service_dev}"
CORE_SVC_NAME="${CN_CORE_SVC_NAME:-query}"
CORE_SVC_TOKEN="${CN_CORE_SVC_TOKEN:-cn_query_service_dev}"

say() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }

check() {
  local desc="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    printf '  \033[32mPASS\033[0m  %-58s %s\n' "$desc" "$actual"
    PASS=$((PASS + 1))
  else
    printf '  \033[31mFAIL\033[0m  %-58s 期望 %s，实际 %s\n' "$desc" "$expected" "$actual"
    FAIL=$((FAIL + 1))
  fi
}

check_ge() {
  local desc="$1" floor="$2" actual="$3"
  # 实际值截断显示：传入的可能是整个 JSON 数组，原样打印会把终端刷满，
  # 反而看不见同一屏里其他失败项。
  local shown="$actual"
  if [ "${#shown}" -gt 60 ]; then
    shown="${shown:0:57}..."
  fi
  case "$actual" in
    '' | *[!0-9-]*) check "$desc" ">=${floor}" "$shown" ;;
    *)
      if [ "$actual" -ge "$floor" ]; then
        printf '  \033[32mPASS\033[0m  %-58s %s (>=%s)\n' "$desc" "$actual" "$floor"
        PASS=$((PASS + 1))
      else
        printf '  \033[31mFAIL\033[0m  %-58s 期望 >=%s，实际 %s\n' "$desc" "$floor" "$shown"
        FAIL=$((FAIL + 1))
      fi
      ;;
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

# 在 python 里求值并输出 true/false。
# 注意 argv 偏移：用 python -c 时 sys.argv[0] 是 "-c" 而不是脚本名，代码在 argv[1]，
# 数据从 argv[2] 起。写成 sys.argv[0] 会拿字符串 "-c" 去 exec，表现为静默的 eval-error。
pycheck() {
  "$PY_BIN" -c 'import sys
exec(sys.argv[1], {"a": sys.argv[2:]})' "$@" 2>/dev/null || echo "eval-error"
}

# 列表/字典长度：json_len <json> <点分路径>
json_len() {
  printf '%s' "$1" | "$PY_BIN" -c '
import json, sys
cur = json.load(sys.stdin)
for key in [p for p in sys.argv[1].split(".") if p]:
    cur = cur.get(key) if isinstance(cur, dict) else None
    if cur is None:
        break
print(len(cur) if isinstance(cur, (list, dict)) else -1)
' "$2"
}

status_of() { C -o /dev/null -w '%{http_code}' "$1"; }

say '1. 服务可达性'
check 'core /actuator/health' '200' "$(status_of "$CORE/actuator/health")"
check 'query /actuator/health' '200' "$(status_of "$QRY/actuator/health")"

# 修订空洞是否已收敛。
#
# 采集服务在持续写入时，core 追加的修订未必都已投递到 query，此间出现空洞是**正常中间态**：
# 连续发布门控的意义恰恰是「不跳过空洞去发布后面的修订」。所以要验证的性质是「空洞会被消化」，
# 而不是「任意瞬间都不存在空洞」。后者写成硬断言，会让自检在系统完全正常时随机变红，
# 而随机变红的自检最终一定会被人忽略——那等于没有自检。
# 等待修订空洞被消化，并返回「消化后」的那份一致性响应。
#
# 为什么要返回响应体而不只返回一个布尔值：修订空洞是持续写入下的正常中间态，
# 因此本节的每条断言都必须在同一个已收敛的观测上做。若原地只取一个布尔值，
# 后面的水位与恢复动作断言仍会读收敛前的那份响应（那时空洞可能恰好存在，
# 恢复动作理所当然非空），于是断言会随瞬时状态随机变红——这正是把
# 「任意瞬间都为空」误当成「会被消化」时留下的隐患。
gap_settled_consistency() {
  local tries="${1:-15}" body="" i=1
  while [ "$i" -le "$tries" ]; do
    body="$(C "$QRY/api/v1/public/consistency")"
    [ "$(json_get "$body" data.revisionGap.blocked)" = "false" ] && break
    sleep 2
    i=$((i + 1))
  done
  printf '%s' "$body"
}

say '2. 三水位与修订空洞'
CONS="$(gap_settled_consistency)"
check '一致性接口返回 OK' 'OK' "$(json_get "$CONS" code)"
PUB="$(json_get "$CONS" data.publishedRevision)"
RECV="$(json_get "$CONS" data.receivedRevision)"
HIGH="$(json_get "$CONS" data.highestSeenRevision)"
CORE_REV="$(json_get "$CONS" data.coreCatalogRevision)"
check 'core 可达' 'true' "$(json_get "$CONS" data.coreReachable)"
check '当前可对外服务' 'true' "$(json_get "$CONS" data.serving)"
check '修订空洞在收敛窗口内被消化' 'false' "$(json_get "$CONS" data.revisionGap.blocked)"
printf '  \033[36mINFO\033[0m  published=%s received=%s highestSeen=%s coreRevision=%s 已应用=%s\n' \
  "$PUB" "$RECV" "$HIGH" "$CORE_REV" "$(json_get "$CONS" data.stateApplied)"
check_ge '已发布水位已推进（> 0）' 1 "$PUB"
# 接收水位不得超过已发布水位：连续门控的意义就在于「收到的不能抢先被算作已发布」。
check '接收水位不超过已发布水位' 'true' "$(pycheck 'print("true" if int(a[0]) <= int(a[1]) else "false")' "$RECV" "$PUB")"
# 见过的最大修订可以超过水位（那正是待处理积压），但不允许低于已发布水位。
check '最高已见修订不低于已发布水位' 'true' "$(pycheck 'print("true" if int(a[0]) >= int(a[1]) else "false")' "$HIGH" "$PUB")"
# 空洞存在时必须给出恢复动作，不能只报一个布尔值。
GAP_ACT="$(json_get "$CONS" data.revisionGap.recovery)"
check '空洞为假时不承诺恢复动作（无谓的字段会误导）' '' "$GAP_ACT"

say '3. 公开屏障：core 权威水位'
BARRIER="$(C -H "X-Service-Name: $CORE_SVC_NAME" -H "X-Service-Token: $CORE_SVC_TOKEN" "$CORE/internal/v1/public-barrier")"
check 'core 公开水位接口返回 OK' 'OK' "$(json_get "$BARRIER" code)"
REQ_PUB="$(json_get "$BARRIER" data.requiredPublicRevision)"
BARRIER_REV="$(json_get "$BARRIER" data.catalogRevision)"
check_ge '权威目录修订已推进（> 0）' 1 "$BARRIER_REV"
check '权威公开水位不高于目录修订' 'true' \
  "$(pycheck 'print("true" if int(a[0]) <= int(a[1]) else "false")' "$REQ_PUB" "$BARRIER_REV")"

say '4. 内部端点 fail-closed'
check '无服务身份访问内部维护端点被拒' '403' "$(status_of "$QRY/internal/v1/maintenance/generations")"
check '伪造服务身份访问内部维护端点被拒' '403' \
  "$(C -o /dev/null -w '%{http_code}' -H "X-Service-Name: attacker" -H "X-Service-Token: wrong" "$QRY/internal/v1/maintenance/generations")"
check '伪造服务身份投递事件被拒' '403' \
  "$(C -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -H 'X-Service-Name: attacker' -H 'X-Service-Token: wrong' -d '{"events":[]}' "$QRY/internal/v1/inbox/batches")"
GENS="$(C -H "X-Service-Name: $SVC_NAME" -H "X-Service-Token: $SVC_TOKEN" "$QRY/internal/v1/maintenance/generations")"
check '合法服务身份可读 generation 列表' 'OK' "$(json_get "$GENS" code)"
check '活动代与一致性接口一致' "$(json_get "$CONS" data.activeGeneration)" "$(json_get "$GENS" data.activeGeneration)"

say '5. 成果检索'
SEARCH="$(C "$QRY/api/v1/public/search/achievements?size=5")"
check '检索返回 OK' 'OK' "$(json_get "$SEARCH" code)"
TOTAL="$(json_get "$SEARCH" data.total)"
check_ge '命中总数 > 0（投影中有真实成果）' 1 "$TOTAL"
check '总数口径为精确值' 'exact' "$(json_get "$SEARCH" data.totalRelation)"
check '返回条数等于请求页大小' '5' "$(json_get "$SEARCH" data.size)"
check '检索规则版本固定可追溯' 'SEARCH_V1' "$(json_get "$SEARCH" data.ruleVersion)"
check '默认作用域为 REAL（模拟数据不混入）' 'REAL' "$(json_get "$SEARCH" data.dataNatureScope)"
check '命中项带数据性质' 'REAL' "$(json_get "$SEARCH" data.items.0.dataNature)"
QV="$(json_get "$SEARCH" data.queryVersion)"
TITLE0="$(json_get "$SEARCH" data.items.0.title)"
AUTHORS0="$(json_get "$SEARCH" data.items.0.authors)"
check '查询版本形如 <条件摘要>-r<修订>' 'true' \
  "$(pycheck 'import re; print("true" if re.search(r"-r[0-9]+$", a[0]) else "false")' "$QV")"
check '命中项有非空标题' 'true' \
  "$(pycheck 'print("true" if len(a[0]) > 3 else "false")' "$TITLE0")"
check '命中项带有作者列表' 'true' \
  "$(pycheck 'print("true" if len(a[0]) > 2 else "false")' "$AUTHORS0")"

say '6. 关键词命中确实收窄结果'
KW="$(C --get --data-urlencode 'q=graph coloring' "$QRY/api/v1/public/search/achievements?size=3")"
KW_TOTAL="$(json_get "$KW" data.total)"
ALL_TOTAL="$(json_get "$(C "$QRY/api/v1/public/search/achievements?size=1")" data.total)"
check_ge '关键词命中 > 0' 1 "$KW_TOTAL"
check '关键词命中少于全集（筛选确实生效）' 'true' \
  "$(pycheck 'print("true" if int(a[0]) < int(a[1]) else "false")' "$KW_TOTAL" "$ALL_TOTAL")"

say '7. 排序白名单与深分页'
SORTED="$(C "$QRY/api/v1/public/search/achievements?sort=year_desc&size=2")"
check '白名单内排序被接受' 'OK' "$(json_get "$SORTED" code)"
check '白名单内排序被回显' 'year_desc' "$(json_get "$SORTED" data.appliedSort)"
check '白名单外排序被拒' 'INVALID_ARGUMENT' \
  "$(json_get "$(C "$QRY/api/v1/public/search/achievements?sort=title%27%20--")" code)"
# 排序列不可能靠参数绑定实现（绑定的是值，不是标识符），只能靠白名单。这里用一个「看起来像
# 注入的字符串」验证它被显式拒绝，而不是 500、也不是被静默忽略后换个顺序返回结果：
# 静默忽略会让客户端以为排序生效了，拿到一个顺序不对却看不出问题的列表。
INJ="$(C "$QRY/api/v1/public/search/achievements?sort=1%3Bdrop%20table%20qry_achievement")"
check '像注入的排序列被显式拒绝（不 500、不静默忽略）' 'INVALID_ARGUMENT' "$(json_get "$INJ" code)"
check '被拒时不返回任何结果行' '' "$(json_get "$INJ" data.items.0.id)"
check '超出最大偏移的深分页被拒' '400' "$(status_of "$QRY/api/v1/public/search/achievements?page=100000&size=100")"
check '页大小超上限被拒' '400' "$(status_of "$QRY/api/v1/public/search/achievements?size=100000")"

say '8. 查询版本一致性'
# 这里刻意<b>不</b>断言「携带当前版本一定能继续分页」。查询版本 = 条件摘要 + 已发布修订，
# 而采集服务在持续写入，两次请求之间 publishedRevision 就可能前进，于是刚拿到的版本在第二次
# 请求时已经过期。这正是设计要的效果：宁可拒绝，也不把两个快照的行混进同一个列表——把这种
# 断言写成必然通过，等于要求「水位必须停止前进」，而那是错的。
# 可稳定复现的是拒绝行为本身，以及拒绝之后客户端能否据此自恢复。
STALE="$(C "$QRY/api/v1/public/search/achievements?size=2&queryVersion=qv0-r0")"
check '携带过期查询版本返回 QUERY_VERSION_CHANGED' 'QUERY_VERSION_CHANGED' "$(json_get "$STALE" code)"
check '版本冲突时不返回任何结果行' '' "$(json_get "$STALE" data.items.0.id)"
STALE_MSG="$(json_get "$STALE" message)"
check '冲突信息告诉客户端重发第一页（而非只报错）' 'true' \
  "$(pycheck 'print("true" if "第一页" in a[0] else "false")' "$STALE_MSG")"
# 用刚取到的版本再请求一次：结果可能是 OK（期间无写入），也可能是 QUERY_VERSION_CHANGED
# （期间有写入）。两者都可接受，但都必须是结构完整、可判断的响应——不能是 500，也不能是
# 「改了条件却照旧返回」这种看不出问题的成功。
AGAIN_CODE="$(json_get "$(C "$QRY/api/v1/public/search/achievements?size=2&queryVersion=$QV")" code)"
check '带版本重放落在两种明确结论之一' 'true' \
  "$(pycheck 'print("true" if a[0] in ("OK", "QUERY_VERSION_CHANGED") else "false")' "$AGAIN_CODE")"

say '9. 学者检索'
SCH="$(C "$QRY/api/v1/public/search/scholars?size=5")"
check '学者检索返回 OK' 'OK' "$(json_get "$SCH" code)"
check_ge '学者命中总数 > 0' 1 "$(json_get "$SCH" data.total)"
SCH_CNT="$(json_get "$SCH" data.items.0.qualifiedAchievementCount)"
SCH_NOTE="$(json_get "$SCH" data.countingNote)"
check '学者项带合格成果数' 'true' "$(pycheck 'print("true" if str(a[0]).isdigit() else "false")' "$SCH_CNT")"
check '计数口径写明只算 AUTO / SELF_CONFIRMED' 'true' \
  "$(pycheck 'print("true" if "AUTO" in a[0] and "SELF_CONFIRMED" in a[0] else "false")' "$SCH_NOTE")"

say '10. 分面与筛选项'
FAC="$(C "$QRY/api/v1/public/search/facets?limit=20")"
check '分面返回 OK' 'OK' "$(json_get "$FAC" code)"
check_ge '机构分面有数据' 1 "$(json_get "$FAC" data.organizations.0.achievement_count)"
OPTS="$(C "$QRY/api/v1/public/search/options")"
check '筛选项字典返回 OK' 'OK' "$(json_get "$OPTS" code)"
NATURES="$(json_get "$OPTS" data.dataNatures)"
check '筛选项含三值数据性质与 ALL 演示口径' 'true' \
  "$(pycheck 'print("true" if all(x in a[0] for x in ("REAL", "SIMULATED", "MIXED", "ALL")) else "false")' "$NATURES")"
SORTS="$(json_get "$OPTS" data.sorts)"
check '排序字典与检索实现同源' 'true' \
  "$(pycheck 'print("true" if "year_desc" in a[0] and "relevance" in a[0] else "false")' "$SORTS")"

say '11. 统计口径'
AN="$(C "$QRY/api/v1/public/analytics/overview")"
check '统计返回 OK' 'OK' "$(json_get "$AN" code)"
check_ge '统计口径中的成果数 > 0' 1 "$(json_get "$AN" data.totals.achievements)"
check_ge '统计口径中的合作者对 > 0' 1 "$(json_get "$AN" data.totals.coauthorPairs)"
check '统计规则版本固定可追溯' 'ANALYTICS_V1' "$(json_get "$AN" data.ruleVersion)"
# 未知年份与未知机构必须单列，不能塞进某个年份桶或某条机构名里：
# 「缺失」被算成「已知」会让排名与年份趋势都凭空好看。
UNK_YEAR="$(json_get "$AN" data.achievementsWithUnknownYear)"
UNK_ORG="$(json_get "$AN" data.totals.achievementsWithUnknownOrganization)"
check '未知年份单列计数存在' 'true' "$(pycheck 'print("true" if str(a[0]).isdigit() else "false")' "$UNK_YEAR")"
check '未知机构单列计数存在' 'true' "$(pycheck 'print("true" if str(a[0]).isdigit() else "false")' "$UNK_ORG")"
CIT_SRC="$(json_get "$AN" data.citationsBySource)"
check '引用按来源分组返回' 'true' "$(pycheck 'print("true" if str(a[0]).startswith("[") else "false")' "$CIT_SRC")"
check '引用分组中确有来源数据（采集层已映射引用量）' 'true' \
  "$(pycheck 'import json
rows = json.loads(a[0] or "[]")
print("true" if rows and all("citationSource" in r for r in rows) else "false")' "$CIT_SRC")"
check '引用不提供跨来源合计（无总量字段）' '' "$(json_get "$AN" data.totals.citationTotal)"
YEAR0="$(json_get "$AN" data.byYear.0.year)"
check '年份桶只含数字年份' 'true' "$(pycheck 'print("true" if str(a[0]).isdigit() else "false")' "$YEAR0")"
RULES="$(json_get "$AN" data.countingRules)"
check '计数规则逐项可读且说明机构按成果发生时计' 'true' \
  "$(pycheck 'print("true" if "机构" in a[0] and "发生时" in a[0] else "false")' "$RULES")"

say '12. 合作网络'
SCHOLARS="$(C "$QRY/api/v1/public/search/scholars?size=5")"
CENTER="$("$PY_BIN" -c '
import json, sys
items = json.load(sys.stdin)["data"]["items"]
print(items[0]["scholarId"] if items else "")
' <<<"$SCHOLARS")"
check '能从学者检索取到学者 id' 'true' \
  "$(pycheck 'print("true" if a[0].startswith("sch_") else "false")' "$CENTER")"
G="$(C "$QRY/api/v1/public/network/graph?scholarId=$CENTER&depth=1")"
check '合作图返回 OK' 'OK' "$(json_get "$G" code)"
check_ge '合作图节点数 > 1' 2 "$(json_len "$G" data.nodes)"
check_ge '合作图边数 > 1' 2 "$(json_len "$G" data.edges)"
check '合作图规则版本固定可追溯' 'NETWORK_V1' "$(json_get "$G" data.ruleVersion)"
EDGE_RULE="$(json_get "$G" data.edgeRule)"
check '建边规则写明排除待核验/争议/已排除' 'true' \
  "$(pycheck 'print("true" if all(x in a[0] for x in ("待核验", "争议", "已排除")) else "false")' "$EDGE_RULE")"
check '合作图默认作用域为 REAL' 'REAL' "$(json_get "$G" data.dataNatureScope)"
check '缺 scholarId 时被拒（不做全图导出）' '400' "$(status_of "$QRY/api/v1/public/network/graph")"
SELF="$(C "$QRY/api/v1/public/network/path?from=$CENTER&to=$CENTER")"
SELF_HOPS="$(json_get "$SELF" data.hops)"
SELF_REACH="$(json_get "$SELF" data.reachable)"
check '两点相同时可达且零跳' 'true' \
  "$(pycheck 'print("true" if a[0] == "true" and a[1] == "0" else "false")' "$SELF_REACH" "$SELF_HOPS")"
NEIGHBOR="$(json_get "$G" data.nodes.1.scholarId)"
P="$(C "$QRY/api/v1/public/network/path?from=$CENTER&to=$NEIGHBOR")"
check '到直接合作者路径可达' 'true' "$(json_get "$P" data.reachable)"
check '到直接合作者为 1 跳' '1' "$(json_get "$P" data.hops)"
P_PATH="$(json_get "$P" data.path)"
check '可达路径首尾为给定两端' 'true' \
  "$(pycheck 'import json
p = json.loads(a[0])
print("true" if p and p[0] == a[1] and p[-1] == a[2] else "false")' "$P_PATH" "$CENTER" "$NEIGHBOR")"
P_RULE="$(json_get "$P" data.pathRule)"
P_LIMITS="$(json_get "$P" data.withinLimits)"
check '路径写明选取规则与是否在限流范围内' 'true' \
  "$(pycheck 'print("true" if a[0] and a[1] != "" else "false")' "$P_RULE" "$P_LIMITS")"
NR="$(C "$QRY/api/v1/public/network/path?from=sch_0000000000000000000000000&to=$CENTER")"
NR_PATH="$(json_get "$NR" data.path)"
NR_NOTE="$(json_get "$NR" data.note)"
check '不可达时返回空路径而不编造' '[]' "$NR_PATH"
check '不可达时给出说明' 'true' "$(pycheck 'print("true" if len(a[0]) > 4 else "false")' "$NR_NOTE")"

say '13. 数据性质作用域'
ALLSCOPE="$(C "$QRY/api/v1/public/search/achievements?dataNature=ALL&size=2")"
check '显式 ALL 作用域被接受' 'OK' "$(json_get "$ALLSCOPE" code)"
check 'ALL 作用域被如实回显' 'ALL' "$(json_get "$ALLSCOPE" data.dataNatureScope)"
check 'ALL 作用域有说明其演示性质' 'true' \
  "$(pycheck 'print("true" if len(a[0]) > 4 else "false")' "$(json_get "$ALLSCOPE" data.scopeNote)")"
check '非法数据性质被拒' 'INVALID_ARGUMENT' \
  "$(json_get "$(C "$QRY/api/v1/public/search/achievements?dataNature=MAYBE")" code)"

say '14. 投递健康度与死信'
DEL="$(C -H "X-Service-Name: $CORE_SVC_NAME" -H "X-Service-Token: $CORE_SVC_TOKEN" "$CORE/internal/v1/outbox/deliveries")"
check 'core 投递健康度返回 OK' 'OK' "$(json_get "$DEL" code)"
check 'query 接收者出现在投递列表中' 'query' "$(json_get "$DEL" data.consumers.0.consumerId)"
DL_COUNT="$(json_get "$DEL" data.consumers.0.deadLetterCount)"
check 'query 无死信（重投后已清零）' '0' "$DL_COUNT"
DL="$(C -H "X-Service-Name: $CORE_SVC_NAME" -H "X-Service-Token: $CORE_SVC_TOKEN" "$CORE/internal/v1/outbox/dead-letters?consumerId=query&limit=5")"
check '死信明细可查' 'OK' "$(json_get "$DL" code)"
DL_ITEMS="$(json_get "$DL" data.items)"
check '无死信时明细为空' 'true' "$(pycheck 'import json; print("true" if json.loads(a[0] or "[]") == [] else "false")' "$DL_ITEMS")"

say '15. 公开视图不因积压而停滞'
# 先等空洞收敛，再在那一份观测上做全部断言：本节要证明的是「积压会被消化干净」，
# 而不是「任意时刻都没有积压」。在持续写入下，后者根本不该成立。
FINAL="$(gap_settled_consistency 15)"
check '最终仍可对外服务' 'true' "$(json_get "$FINAL" data.serving)"
check '最终无修订空洞' 'false' "$(json_get "$FINAL" data.revisionGap.blocked)"
LAG="$(json_get "$FINAL" data.lagBehindCoreRevision)"
printf '  \033[36mINFO\033[0m  已发布 %s，core 目录修订 %s，滞后 %s\n' \
  "$(json_get "$FINAL" data.publishedRevision)" "$(json_get "$FINAL" data.coreCatalogRevision)" "$LAG"
# 这里不断言「滞后恒为 0」。采集服务在持续写入，core 的目录修订一直在前进，
# 抓到一个恰好相等的瞬间只能说明运气好，不能证明追得上；把这种断言写成必然通过，
# 等于用一个会偶发失败的绿灯掩盖真实的传播延迟。要证明的是「跟得上且有界」：
# 滞后不超过单批可消化量级，且没有空洞——空洞才是「永远追不上」的那种坏法。
check '滞后不超过单批可消化量级' 'true' \
  "$(pycheck 'print("true" if int(a[0] or 999999) <= 2000 else "false")' "$LAG")"

# 16. 公开资格一致性：投影说可见的，权威侧必须也可见。
#
# 这一节是一个真实缺陷的回归守卫。缺陷形态：核心服务在「既有成果刷新来源指标」时外发了
# 一条自称 visibility=PUBLIC 的事件，而该成果在权威表里仍是 RESTRICTED/PENDING_GOVERNANCE。
# 结果是公开检索能搜到它、点进详情却是 404——读者看到的是「平台收录了这条」，而平台其实
# 从未确认过它。
#
# 断言的是**面向用户的不变量**而不是两侧字段相等：用户能感知到的是「搜得到就必须打得开」。
# 用检索接口取样本、用公开详情接口核对，因此这个检查不依赖任何数据库连接或字段命名，
# 换一套投影结构也依然成立。
say '16. 公开资格一致性（检索可命中 ⇒ 详情可打开）'
SAMPLE="$(C "$QRY/api/v1/public/search/achievements?size=30&dataNature=REAL")"
# Windows 上的 Python 按平台默认换行输出 CRLF，行尾的 \r 会被拼进 URL，请求根本发不出去，
# curl 返回 000——这条自检会因此把「脚本自身的问题」报成「公开资格不一致」。必须先去掉 \r，
# 否则这道回归防护本身就是个假警报源。
SAMPLE_IDS="$(printf '%s' "$SAMPLE" | "$PY_BIN" -c 'import json,sys; print("\n".join(i["id"] for i in json.load(sys.stdin)["data"]["items"]))' | tr -d '\r')"
SAMPLE_N=0
SAMPLE_BAD=0
SAMPLE_TRANSPORT=0
SAMPLE_FIRST_BAD=""
while IFS= read -r sid; do
  [ -z "$sid" ] && continue
  SAMPLE_N=$((SAMPLE_N + 1))
  code="$(C -o /dev/null -w '%{http_code}' "$CORE/api/v1/public/achievements/$sid" < /dev/null)"
  if [ "$code" != "200" ]; then
    SAMPLE_BAD=$((SAMPLE_BAD + 1))
    # 000 表示连接都没建立（id 被污染、端口不通等），与被测系统的可见性无关；
    # 单独计数是为了让失败时能一眼看出这是脚本/环境问题还是真的资格不一致。
    [ "$code" = "000" ] && SAMPLE_TRANSPORT=$((SAMPLE_TRANSPORT + 1))
    [ -z "$SAMPLE_FIRST_BAD" ] && SAMPLE_FIRST_BAD="$sid($code)"
  fi
done <<< "$SAMPLE_IDS"
check_ge '抽样条目数足够（否则本节无意义）' 10 "$SAMPLE_N"
check '检索可命中的成果在权威侧均可打开' '0' "$SAMPLE_BAD"
if [ "$SAMPLE_BAD" != "0" ]; then
  printf '  \033[33m提示\033[0m  首个不一致：%s\n' "$SAMPLE_FIRST_BAD"
  if [ "$SAMPLE_TRANSPORT" != "0" ]; then
    printf '  \033[33m提示\033[0m  %s/%s 项是连接层失败（000），优先排查自检脚本与被测端口，而不是可见性逻辑\n' "$SAMPLE_TRANSPORT" "$SAMPLE_BAD"
  fi
  printf '  \033[33m提示\033[0m  若确为 404，通常表示某个写入路径外发了与持久化状态不符的可见性，请检查事件载荷与快照导出两处的构造处\n'
fi

printf '\n\033[1m结果：通过 %s 项，失败 %s 项\033[0m\n' "$PASS" "$FAIL"
if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
exit 0
