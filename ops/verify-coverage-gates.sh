#!/usr/bin/env bash
# 覆盖率门禁自检：既要看比例，也要确认门禁真的会拦
#
# 背景：任务书要求「行覆盖率 ≥80% 的核心模块不少于 3 个」。这条要求最难的地方不是把数字做到 80%，
# 而是**确认门禁真的在拦**。JaCoCo 的 includes 是相对 element 匹配的，写错 element 或写错模式，
# 规则会匹配到空集合，而空集合一律判为「通过」——构建一片绿，实际一道都没拦。本项目踩过两次：
#
#   1. `<element>BUNDLE</element>` + 包名 include：BUNDLE 下 includes 比的是 bundle 名
#      （core-service），包名永远匹配不上；
#   2. `<element>PACKAGE</element>` + `包名.*`：JaCoCo 译通配符时 `*` → `.*`，而模式里的「.」
#      是正则的「任意字符」不是字面点号，于是 `pkg.*` 实际要求「pkg 后面还有字符」，只会匹配
#      子包；对一个没有子包的包（如 catalog.domain）就等于空集合，静默判通过。
#
# 所以本脚本做两件事：
#   ① 打印受门禁包的真实行覆盖率（人要看的是这个数）；
#   ② 把每条门禁阈值临时提到不可能达到的 1.01 再跑一次，**必须失败**。若仍判通过，就说明这条
#      门禁没匹配到任何包，是假门禁，脚本以非零码退出。
#
# 用法：bash ops/verify-coverage-gates.sh [--skip-build]
#   --skip-build  跳过 mvn verify，直接用现有的 target/site/jacoco 数据做检查

set -euo pipefail

cd "$(dirname "$0")/.."

MVN="./mvnw"
SKIP_BUILD=0
if [ "${1:-}" = "--skip-build" ]; then
  SKIP_BUILD=1
fi

# 受门禁的模块与门禁 execution id。新增门禁时必须同时登记到这里，否则自检不会覆盖它。
GATES=(
  "services/core-service:jacoco-check-governance"
  "services/core-service:jacoco-check-catalog-domain"
  "services/query-service:jacoco-check-analytics"
  "services/query-service:jacoco-check-discovery"
)

PYTHON=$(command -v python3 || command -v python)
if [ -z "$PYTHON" ]; then
  echo "找不到 python，无法解析 jacoco 报告" >&2
  exit 2
fi

if [ "$SKIP_BUILD" -eq 0 ]; then
  echo "== 运行 mvn verify（需要产物与覆盖率数据）=="
  "$MVN" -q verify
fi

echo
echo "== 受门禁模块的真实行覆盖率 =="
"$PYTHON" - <<'PY'
import os
import xml.etree.ElementTree as ET

GATES = {
    "services/core-service": ("com/cognitanexus/core/governance", "com/cognitanexus/core/catalog/domain"),
    "services/query-service": ("com/cognitanexus/query/analytics", "com/cognitanexus/query/discovery"),
}
THRESHOLD = 0.80

low = []
for module, prefixes in GATES.items():
    report = os.path.join(module, "target/site/jacoco/jacoco.xml")
    if not os.path.exists(report):
        print("  %s 缺少覆盖率报告：%s" % (module, report))
        low.append(module)
        continue
    root = ET.parse(report).getroot()
    for package in root.findall("package"):
        name = package.get("name").replace("/", ".")
        if not any(name.startswith(prefix.replace("/", ".")) for prefix in prefixes):
            continue
        counters = {c.get("type"): (int(c.get("missed")), int(c.get("covered"))) for c in package.findall("counter")}
        missed, covered = counters.get("LINE", (0, 0))
        total = missed + covered
        if total == 0:
            continue
        ratio = covered / total
        flag = "OK " if ratio >= THRESHOLD else "低 "
        print("  %s %-50s LINE %6.2f%%  (%d/%d)" % (flag, name, ratio * 100, covered, total))
        if ratio < THRESHOLD:
            low.append(name)
if low:
    raise SystemExit("以下包低于 %.0f%%：%s" % (THRESHOLD * 100, ", ".join(low)))
PY

echo
echo "== 反证：把阈值提到 1.01，每条门禁都必须失败 =="
FAILED=0
for gate in "${GATES[@]}"; do
  module="${gate%%:*}"
  execution="${gate##*:}"
  output=$("$MVN" -q -pl "$module" "jacoco:check@$execution" -Djacoco.line.coverage.minimum=1.01 2>&1 || true)
  if echo "$output" | grep -q "have not been met"; then
    echo "  OK  $module/$execution 在 1.01 下失败 —— 门禁拦到了实际包"
  else
    echo "  假  $module/$execution 在 1.01 下仍判通过 —— 规则没匹配到任何包，是真门禁的假版本"
    echo "$output" | tail -5
    FAILED=1
  fi
done

echo
if [ "$FAILED" -ne 0 ]; then
  echo "结果：存在假门禁，必须修 pom 里的 element / includes 模式。" >&2
  exit 1
fi
echo "结果：${#GATES[@]} 条门禁全部真实生效，且覆盖率均不低于 80%。"
