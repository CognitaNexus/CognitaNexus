#!/usr/bin/env bash
# 全部接口自检脚本的统一运行器
#
# 为什么需要它：tests/api/ 下有三十来个脚本，之前只能「记得跑哪几个就跑哪几个」。
# 「功能都正常」这句话，如果覆盖范围是手工挑选的，就等于没有覆盖范围——漏跑的那几个恰好
# 没人知道它们是不是坏了。本脚本**按目录列清单**，新增脚本自动纳入，不存在「忘了跑」。
#
# 用法：
#   bash tests/api/run-all.sh              # 跑全部
#   bash tests/api/run-all.sh submit merge # 只跑名字里含这些词的
#   ONLY=portal bash tests/api/run-all.sh  # 同上，环境变量写法
#
# 前置：本地整栈已启动（bash ops/local/stack.sh），主库有真实数据。
#
# ⚠️ 这些脚本会写本地库（合并、审批、提交、纠错、公告……），且刻意不做清理：
#    审计记录的 actor 被删掉反而破坏追溯。绝不要对线上或任何有价值的库执行。
#
# 单脚本超时 420 秒。超时与失败都记为失败，并保留该脚本的完整日志：
#   ops/local/run/smoke/<脚本名>.log
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_DIR="$ROOT/ops/local/run/smoke"
TIMEOUT="${TIMEOUT:-420}"
mkdir -p "$LOG_DIR"

FILTERS=("$@")
if [ ${#FILTERS[@]} -eq 0 ] && [ -n "${ONLY:-}" ]; then
  # shellcheck disable=SC2206
  FILTERS=(${ONLY//,/ })
fi

match() {
  local name="$1"
  [ ${#FILTERS[@]} -eq 0 ] && return 0
  local f
  for f in "${FILTERS[@]}"; do
    case "$name" in *"$f"*) return 0 ;; esac
  done
  return 1
}

# 清单按文件名排序，保证「同一份代码跑两次，顺序一致」。
# smoke-core.sh 排在最前：它验的是登录、会话、越权、节流这些**其它脚本全都依赖**的地基，
# 地基坏了后面的失败全是噪声，先跑它能让报告一眼看出是「根因」还是「连带」。
SCRIPTS=()
for f in "$ROOT"/tests/api/smoke-*.sh "$ROOT"/tests/api/smoke-*.py "$ROOT"/tests/api/verify-*.py; do
  [ -f "$f" ] || continue
  SCRIPTS+=("$(basename "$f")")
done
mapfile -t SCRIPTS < <(printf '%s\n' "${SCRIPTS[@]}" | sort)

PASSED=()
FAILED=()
SKIPPED=()
START_ALL=$(date +%s)

echo "=== 接口自检 ==="
echo "脚本目录：$ROOT/tests/api"
echo "日志目录：$LOG_DIR"
echo "单脚本超时：${TIMEOUT}s"
echo "筛选：${FILTERS[*]:-（全部）}"
echo

for name in "${SCRIPTS[@]}"; do
  if ! match "$name"; then
    SKIPPED+=("$name")
    continue
  fi
  path="$ROOT/tests/api/$name"
  log="$LOG_DIR/${name%.*}.log"
  case "$name" in
    *.py) cmd=(python "$path") ;;
    *.sh) cmd=(bash "$path") ;;
    *) continue ;;
  esac

  printf '→ %-32s ' "$name"
  t0=$(date +%s)
  timeout "$TIMEOUT" "${cmd[@]}" >"$log" 2>&1
  rc=$?
  dt=$(( $(date +%s) - t0 ))

  # 结果行取「通过 N，失败 M」这种汇总；没有汇总行就退回看退出码。
  # 只报退出码会掩盖「脚本自己跑崩了但退出码是 0」；只报汇总行会掩盖超时。
  summary=$(grep -aE '通过 +[0-9]+.*失败 +[0-9]+|PASS[^0-9]*[0-9]+.*FAIL|全部通过' "$log" | tail -1 | tr -d '\r')
  if [ "$rc" -eq 124 ]; then
    printf 'TIMEOUT(%ss)\n' "$dt"
    FAILED+=("$name|超时 ${TIMEOUT}s")
  elif [ "$rc" -ne 0 ]; then
    printf 'FAIL(rc=%s, %ss) %s\n' "$rc" "$dt" "${summary:0:80}"
    FAILED+=("$name|rc=$rc ${summary:0:100}")
  else
    printf 'OK(%ss) %s\n' "$dt" "${summary:0:80}"
    PASSED+=("$name|${summary:0:100}")
  fi
done

TOTAL=$(( $(date +%s) - START_ALL ))
echo
echo "=== 汇总（总耗时 ${TOTAL}s）==="
echo "通过 ${#PASSED[@]}，失败 ${#FAILED[@]}，未选 ${#SKIPPED[@]}"
if [ ${#FAILED[@]} -gt 0 ]; then
  echo
  echo "失败明细："
  for r in "${FAILED[@]}"; do echo "  ✗ ${r%%|*} — ${r#*|}"; done
fi
if [ ${#PASSED[@]} -gt 0 ]; then
  echo
  echo "通过明细："
  for r in "${PASSED[@]}"; do echo "  ✓ ${r%%|*} — ${r#*|}"; done
fi

[ ${#FAILED[@]} -eq 0 ]
