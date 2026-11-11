#!/usr/bin/env bash
# 合并影响预览的端到端自检
#
# 覆盖：预览能取到真实候选的两端、响应结构齐备、三个会计等式成立、只读性（预览前后台账与
#       候选数不变）、参数校验，以及最关键的「预览说会迁移几条，实际合并就迁几条」——
#       真跑一次合并，用台账快照对照预览数字。
#
# 为什么单独一支脚本：合并是本平台唯一不可逆的动作，而它的后果大多只在合并之后才可见。
# 预览如果只是「长得像那么回事」（字段齐、不报错、数字随便给），比没有预览更危险：
# 治理人员会照着它做决定。所以要拿真数据核对数字，并且真的执行一次合并来对照。
#
# 用法：bash tests/api/smoke-merge-preview.sh [base_url]
# 前置：整栈已启动（bash ops/local/stack.sh）。
# 副作用：会真的合并一条候选的两端并写入台账。这是刻意的——只有真执行过，才能证明
#         预览的数字与执行结果一致。

set -uo pipefail

BASE="${1:-http://127.0.0.1:8081}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAR="$ROOT/target/smoke-merge-preview"
CHECKER="$ROOT/tests/api/check_merge_preview.py"
PYTHON="$(command -v python3 || command -v python)"
rm -rf "$JAR" 2>/dev/null || true
mkdir -p "$JAR"
COOKIES="$JAR/admin.cookies"
PSQL="${PSQL_BIN:-D:/dev-tools/pgsql/bin/psql.exe}"

ADMIN_USER="${CN_BOOTSTRAP_ADMIN_USERNAME:-cnadmin}"
ADMIN_PASS="${CN_BOOTSTRAP_ADMIN_PASSWORD:-cn-admin-Dev-2026}"

say() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }
check() {
  local desc="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    printf '  \033[32mPASS\033[0m  %-58s %s\n' "$desc" "$actual"
  else
    printf '  \033[31mFAIL\033[0m  %-58s 期望 %s，实际 %s\n' "$desc" "$expected" "$actual"
    FAILED=1
  fi
}

csrf_for() {
  local jar="$1"
  curl -s -o /dev/null -b "$jar" -c "$jar" "$BASE/api/v1/auth/csrf"
  awk '/XSRF-TOKEN/ {print $NF}' "$jar" | tail -1
}
status_of() {
  local method="$1" path="$2" jar="$3"
  shift 3
  curl -s -o /dev/null -w '%{http_code}' -X "$method" -b "$jar" -c "$jar" "$BASE$path" "$@"
}
get() { curl -s -b "$COOKIES" -c "$COOKIES" "$BASE$1"; }
# 直查 core 库做独立核对。这里是全英文 SQL：psql 在 Windows 下用 -c 传中文会按 ANSI 码页
# 转 argv，UTF-8 的中文在到达服务端前就已经坏了。
core_sql() {
  PGPASSWORD=cn_core_app_dev PGCLIENTENCODING=UTF8 MSYS_NO_PATHCONV=1 \
    "$PSQL" -h 127.0.0.1 -p 5432 -U core_app -d core_db -At -c "$1" 2>/dev/null | tr -d '\r'
}
field() { "$PYTHON" "$CHECKER" field "$@"; }

PREVIEW_PATH="/api/v1/admin/core/governance/achievements/merge-preview"
FAILED=0

say '1. 服务可达'
check 'actuator/health' '200' "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/actuator/health")"

say '2. 管理员登录'
TOKEN="$(csrf_for "$COOKIES")"
check '管理员登录 200' '200' "$(curl -s -o "$JAR/login.json" -w '%{http_code}' -X POST \
  -b "$COOKIES" -c "$COOKIES" \
  -H "X-XSRF-TOKEN: $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" "$BASE/api/v1/auth/login")"

say '3. 取一条可合并的真实待治理候选作为样例'
# 逐条试预览、取第一条 canMerge 为真的：本脚本要证明「预览的数字与执行结果一致」，
# 因此样例必须是一条真的能合并的候选。遇到已被合并的候选就跳过（这类候选的预览会在
# 步骤 7 末尾单独验证：它必须给出阻断原因）。
get '/api/v1/admin/core/governance/candidates/workbench?page=1&size=20' > "$JAR/workbench.json"
OPEN_BEFORE="$(field "$JAR/workbench.json" meta.openTotal)"
LEFT=""
RIGHT=""
FIRST_LEFT="$(field "$JAR/workbench.json" data.0.leftAchievementId)"
FIRST_RIGHT="$(field "$JAR/workbench.json" data.0.rightAchievementId)"
index=0
while [ "$index" -lt 20 ]; do
  candidate_left="$(field "$JAR/workbench.json" "data.$index.leftAchievementId")"
  candidate_right="$(field "$JAR/workbench.json" "data.$index.rightAchievementId")"
  if [ -z "$candidate_left" ] || [ -z "$candidate_right" ]; then
    break
  fi
  get "$PREVIEW_PATH?survivorId=$candidate_left&mergedId=$candidate_right" > "$JAR/preview.json"
  if [ "$(field "$JAR/preview.json" data.canMerge)" = 'True' ]; then
    LEFT="$candidate_left"
    RIGHT="$candidate_right"
    break
  fi
  index=$((index + 1))
done
if [ -n "$LEFT" ] && [ -n "$RIGHT" ]; then
  check '找到一条可合并的候选' 'yes' 'yes'
else
  check '找到一条可合并的候选' 'yes' 'no'
  LEFT="$FIRST_LEFT"
  RIGHT="$FIRST_RIGHT"
  printf '  候选两端标识仍可取，但本次无可合并样例，步骤 7 将跳过执行对照\n'
fi

say '4. 预览响应结构与会计等式'
get "$PREVIEW_PATH?survivorId=$LEFT&mergedId=$RIGHT" > "$JAR/preview.json"
"$PYTHON" "$CHECKER" preview "$JAR/preview.json" || FAILED=1
check '预览的幸存方与请求一致' "$LEFT" "$(field "$JAR/preview.json" data.survivor.id)"
check '预览的被合并方与请求一致' "$RIGHT" "$(field "$JAR/preview.json" data.merged.id)"

say '5. 只读性：预览不改台账、不改候选'
get "/api/v1/admin/core/governance/ledger?entityId=$RIGHT&limit=200" > "$JAR/ledger-before.json"
get "$PREVIEW_PATH?survivorId=$LEFT&mergedId=$RIGHT" > /dev/null
get "/api/v1/admin/core/governance/ledger?entityId=$RIGHT&limit=200" > "$JAR/ledger-after.json"
OPEN_AFTER="$(get '/api/v1/admin/core/governance/candidates/workbench?page=1&size=1' > "$JAR/wb2.json"; field "$JAR/wb2.json" meta.openTotal)"
# 比 data 而不是比整份响应：响应包里的 traceId 与 timestamp 每次都不同。
check '台账内容未因预览发生变化' 'True' \
  "$("$PYTHON" "$CHECKER" same-data "$JAR/ledger-before.json" "$JAR/ledger-after.json")"
check '待治理候选总数未变' "$OPEN_BEFORE" "$OPEN_AFTER"

say '6. 参数校验'
check '幸存方与被合并方相同返回 400' '400' \
  "$(status_of GET "$PREVIEW_PATH?survivorId=$LEFT&mergedId=$LEFT" "$COOKIES")"
check '缺少被合并方返回 400' '400' \
  "$(status_of GET "$PREVIEW_PATH?survivorId=$LEFT" "$COOKIES")"
check '不存在的成果返回 404' '404' \
  "$(status_of GET "$PREVIEW_PATH?survivorId=$LEFT&mergedId=ach_not_exist_xyz" "$COOKIES")"

say '7. 预览与执行一致：真跑一次合并，用台账快照对照'
CAN_MERGE="$(field "$JAR/preview.json" data.canMerge)"
if [ "$CAN_MERGE" != 'True' ]; then
  printf '  \033[33mSKIP\033[0m  该候选当前有阻断原因，跳过执行对照：%s\n' \
    "$(field "$JAR/preview.json" data.blockers.0)"
else
  TOKEN="$(csrf_for "$COOKIES")"
  check '执行合并 200' '200' "$(curl -s -o "$JAR/merge.json" -w '%{http_code}' -X POST \
    -b "$COOKIES" -c "$COOKIES" \
    -H "X-XSRF-TOKEN: $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"survivorId\":\"$LEFT\",\"mergedId\":\"$RIGHT\",\"reason\":\"合并影响预览自检：核对预览数字与实际结果一致\"}" \
    "$BASE/api/v1/admin/core/governance/achievements/merge")"
  get "/api/v1/admin/core/governance/ledger?entityId=$RIGHT&limit=5" > "$JAR/ledger.json"
  "$PYTHON" "$CHECKER" ledger "$JAR/preview.json" "$JAR/ledger.json" || FAILED=1

  # 合并后反向预览必须被阻断：previewMerge 的重定向链检查与执行路径用的是同一套判断。
  get "$PREVIEW_PATH?survivorId=$RIGHT&mergedId=$LEFT" > "$JAR/preview-after.json"
  "$PYTHON" "$CHECKER" blocked "$JAR/preview-after.json" || FAILED=1

  say '8. 一并裁决的候选：预览的数字与待治理清单的实际变化一致'
  # 这是本轮修掉的缺陷的验收点。此前合并只把两侧实体并起来，候选一律留在 OPEN：
  # 候选问的是「这两条是不是同一条」，合并已经回答了这个问题，于是那些候选既没人处置、
  # 也因为相似度最高（同标题就是 1.0）永远压在待治理列表最前面。
  # 这里用工作台返回的实时总数做独立核对——它不是预览算出来的，是服务端按表统计出来的。
  CLOSED="$(field "$JAR/preview.json" data.effects.candidatesClosedWithMerge)"
  get '/api/v1/admin/core/governance/candidates/workbench?page=1&size=1' > "$JAR/wb-after.json"
  OPEN_FINAL="$(field "$JAR/wb-after.json" meta.openTotal)"
  # 总数不能拿来做等式：采集执行器在后台持续写入新记录，也就持续产生新的相似标题候选，
  # 一次合并关掉 1 条、同时新增几十条时，总数会上升。这不是合并失败，而是这个环境里
  # 「总数」根本不是只被合并影响的量。核对改为直接问库：这一对之间还有没有 OPEN 候选。
  printf '        待治理总数：合并前 %s，合并后 %s（后台采集会持续新增，仅作参考）\n' \
    "$OPEN_BEFORE" "$OPEN_FINAL"
  PAIR_OPEN="$(core_sql "SELECT count(*) FROM gov_dedup_candidate WHERE status='OPEN' AND ((left_achievement_id='$LEFT' AND right_achievement_id='$RIGHT') OR (left_achievement_id='$RIGHT' AND right_achievement_id='$LEFT'));")"
  check '这一对之间已无 OPEN 候选（合并回答了它的问题）' '0' "$PAIR_OPEN"
  check '这一对之间的候选已被裁决（预览数字不为 0）' 'yes' \
    "$([ "${CLOSED:-0}" -gt 0 ] && echo yes || echo no)"
  # 合并后的台账里也要留痕：没有这一项时，「候选去哪了」在台账上查不到。
  check '台账快照记录了本次一并裁决的条数' "$CLOSED" \
    "$(field "$JAR/ledger.json" data.0.snapshot.candidatesClosedWithMerge)"

  say '9. 合并遗留候选的改判入口：问题转到最终实体，而不是被迫写一条不实结论'
  # 这一段验收的是这条新入口：原候选离开待办、承接候选落到最终实体那一对上、
  # 且待治理总数的变化与「是否新建了承接候选」严格对应。
  #
  # 先证明清单本身读得到。少了这一步，一旦请求被拒（例如 size 超上限被 400），
  # 后面「找不到受阻候选」会伪装成「当前没有需要改判的候选」——把一次请求失败
  # 读成业务结论，是这个脚本最容易自己骗自己的地方。
  get '/api/v1/admin/core/governance/candidates/workbench?page=1&size=100' > "$JAR/wb-blocked.json"
  check '待治理清单可读且非空' 'yes' \
    "$([ -n "$(field "$JAR/wb-blocked.json" data.0.id)" ] && echo yes || echo no)"
  RIDX=0
  BLOCKED_ID=''
  BLOCKED_LEFT=''
  BLOCKED_RIGHT=''
  while [ "$RIDX" -lt 100 ]; do
    candidate_action="$(field "$JAR/wb-blocked.json" "data.$RIDX.recommendedAction")"
    if [ -z "$candidate_action" ]; then
      break
    fi
    if [ "$candidate_action" = 'REJECT_ONLY' ]; then
      BLOCKED_ID="$(field "$JAR/wb-blocked.json" "data.$RIDX.id")"
      BLOCKED_LEFT="$(field "$JAR/wb-blocked.json" "data.$RIDX.leftAchievementId")"
      BLOCKED_RIGHT="$(field "$JAR/wb-blocked.json" "data.$RIDX.rightAchievementId")"
      break
    fi
    RIDX=$((RIDX + 1))
  done
  check '存在因端点被并走而不可合并的候选（改判的前提）' 'yes' \
    "$([ -n "$BLOCKED_ID" ] && echo yes || echo no)"
  if [ -n "$BLOCKED_ID" ]; then
    check '该候选被服务端标为只能驳回' 'REJECT_ONLY' "$candidate_action"
    LEFT_MERGED="$(field "$JAR/wb-blocked.json" "data.$RIDX.left.merged")"
    RIGHT_MERGED="$(field "$JAR/wb-blocked.json" "data.$RIDX.right.merged")"
    if [ "$LEFT_MERGED" = 'True' ] || [ "$RIGHT_MERGED" = 'True' ]; then
      SIDE_MERGED=yes
    else
      SIDE_MERGED=no
    fi
    check '两侧实时状态说明了它为什么不可合并' 'yes' "$SIDE_MERGED"
    # 每个写请求前都要重新取一次令牌：令牌用过一次即轮换，复用会得到 403，而错误码是
    # ACCESS_DENIED 而不是 CSRF_INVALID——照着错误码去查权限会查错方向。
    TOKEN="$(csrf_for "$COOKIES")"
    check '缺理由改判返回 400' '400' "$(curl -s -o /dev/null -w '%{http_code}' -X POST \
      -b "$COOKIES" -c "$COOKIES" \
      -H "X-XSRF-TOKEN: $TOKEN" -H 'Content-Type: application/json' \
      -d '{"reason":"   "}' \
      "$BASE/api/v1/admin/core/governance/candidates/$BLOCKED_ID/rebase")"
    TOKEN="$(csrf_for "$COOKIES")"
    check '改判 200' '200' "$(curl -s -o "$JAR/rebase.json" -w '%{http_code}' -X POST \
      -b "$COOKIES" -c "$COOKIES" \
      -H "X-XSRF-TOKEN: $TOKEN" -H 'Content-Type: application/json' \
      -d '{"reason":"合并影响预览自检：把遗留候选改判到最终实体"}' \
      "$BASE/api/v1/admin/core/governance/candidates/$BLOCKED_ID/rebase")"
    # 总数要在改判之前取：这条等式用的是「服务端按表统计出来的数」，不是接口自报的。
    # 原候选是否真的离开了 OPEN：直接问库。改判接口的语句带 status='OPEN' 条件，
    # 但「接口返回 200」只说明这一次请求成立，不说明写进库的状态是什么。
    get '/api/v1/admin/core/governance/candidates/workbench?page=1&size=100' > "$JAR/wb-rebased.json"
    REBASE_STATE="$(core_sql "SELECT status FROM gov_dedup_candidate WHERE id='$BLOCKED_ID';")"
    check '原候选已离开 OPEN（改判落为终态）' 'yes' \
      "$([ -n "$REBASE_STATE" ] && [ "$REBASE_STATE" != 'OPEN' ] && echo yes || echo no)"
    REBASE_STATUS="$(field "$JAR/rebase.json" data.status)"
    if [ "$REBASE_STATUS" = 'REBASED' ]; then
      REBASED_TO="$(field "$JAR/rebase.json" data.rebasedToId)"
      "$PYTHON" "$CHECKER" carrier-pair "$JAR/wb-rebased.json" "$REBASED_TO" \
        "$(field "$JAR/rebase.json" data.finalLeftId)" \
        "$(field "$JAR/rebase.json" data.finalRightId)" \
        | grep -qx True && CARRIER_OK=yes || CARRIER_OK=no
      check '承接候选就在待治理清单里，且两端正是最终实体' 'yes' "$CARRIER_OK"
    else
      printf '  两端改判后归并到同一条实体，本次不产生承接候选（状态 %s）\n' "$REBASE_STATUS"
    fi
    # 重复改判必须被拒。这条 409 同时是「原候选已经离开 OPEN」的正面证据：
    # 服务端的改判语句带 status='OPEN' 条件，只有它还停在 OPEN 上才会被拒。
    # 不去待办列表里捞它的 id——那个列表按登记时间倒序、每页有上限，捞不到既不能证明
    # 它已离开，也不能证明它还在，只会得到一条永远为真的假断言。
    TOKEN="$(csrf_for "$COOKIES")"
    check '重复改判被拒（同时证明原候选已离开 OPEN）' '409' "$(curl -s -o /dev/null -w '%{http_code}' -X POST \
      -b "$COOKIES" -c "$COOKIES" \
      -H "X-XSRF-TOKEN: $TOKEN" -H 'Content-Type: application/json' \
      -d '{"reason":"再改一次"}' \
      "$BASE/api/v1/admin/core/governance/candidates/$BLOCKED_ID/rebase")"
  fi
fi

printf '\n'
if [ "$FAILED" -ne 0 ]; then
  printf '\033[31m合并影响预览自检未全部通过\033[0m\n'
  exit 1
fi
printf '\033[32m合并影响预览自检全部通过\033[0m\n'
