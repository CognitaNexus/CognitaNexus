#!/usr/bin/env bash
# =============================================================================
# CI 测试证据门禁：测试「没跑」必须红，而不是绿
# =============================================================================
#
# 背景：本仓库的集成测试（*IT.java，failsafe）默认是**跳过**的
# （root pom 的 cn.it.skip=true，见那里注释：常规构建不该被「本机有没有测试库」绑死）。
# 这个默认值本身合理，但它带来一个 CI 特有的失效模式：
#
#   · 漏传 -Dcn.it.skip=false / -Pci  → Maven 不跑 IT，构建仍然 BUILD SUCCESS；
#   · 测试被 @Disabled 或条件跳过       → 构建仍然 BUILD SUCCESS；
#   · 覆盖率规则 include 写错、匹配到空集合 → JaCoCo 判「通过」（空集合永远达标）；
#   · 模块被 -pl 漏选                  → 构建成功，只是那一块根本没测。
#
# 以上四种「绿」在报告里都长得和「全部通过」一样。所以 CI 不能只看退出码，必须核对
# **报告本身**：源码里有几个测试类，报告里就必须有几个；IT 的 skipped 必须是 0；
# 覆盖率门禁的 include 必须真的匹配到非空包集合。任何一条不满足，本脚本以非零码退出。
#
# 用法：
#   bash ops/verify/ci-test-report-gate.sh                      # 全部模块：单测 + IT + 覆盖率
#   bash ops/verify/ci-test-report-gate.sh --modules services/core-service --unit --it --coverage
#   bash ops/verify/ci-test-report-gate.sh --modules services/query-service --it --coverage
#   bash ops/verify/ci-test-report-gate.sh --modules services/core-service --unit --subset \
#        --min-tests 20 --require PublicSearchControllerTest
#
# 选项：
#   --modules a,b        检查哪些模块（默认读根 pom 的 <module> 列表）
#   --unit               要求单测证据（surefire）
#   --it                 要求集成测试证据（failsafe）
#   --coverage           要求覆盖率门禁证据（JaCoCo XML）
#   --min-tests N        单测/IT 各自至少 N 个用例（默认 1）
#   --subset             只要求「有 N 个用例」，不要求源码里每个测试类都有报告
#                        （用于按 -Dtest 只跑子集的 job）
#   --require A,B        额外要求这些类（简单名或全限定名）出现在报告里
#   --it-select P,Q      只核对匹配的 IT 源码类（简单类名或全限定名，支持 * 通配）
#                        精确类名必须存在；多个通配模式合计至少命中一个 IT 类
# =============================================================================

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

PYTHON=$(command -v python3 || true)
if [ -z "$PYTHON" ]; then
  echo "找不到 python3：测试证据核对依赖它解析 JUnit/JaCoCo XML" >&2
  exit 2
fi

exec "$PYTHON" - "$@" <<'PY'
import fnmatch
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.getcwd()


def usage(msg=None):
    if msg:
        print("参数错误：%s" % msg, file=sys.stderr)
    print(
        "用法：ci-test-report-gate.sh [--modules a,b] [--unit] [--it] [--coverage] "
        "[--min-tests N] [--subset] [--require A,B] [--it-select P,Q]",
        file=sys.stderr,
    )
    raise SystemExit(2)


args = sys.argv[1:]
modules = None
want_unit = want_it = want_cov = False
subset = False
min_tests = 1
require = []
it_select = []

i = 0
while i < len(args):
    a = args[i]
    if a == "--modules":
        i += 1
        if i >= len(args):
            usage("--modules 缺少取值")
        modules = [m.strip().rstrip("/") for m in args[i].split(",") if m.strip()]
    elif a == "--unit":
        want_unit = True
    elif a == "--it":
        want_it = True
    elif a == "--coverage":
        want_cov = True
    elif a == "--subset":
        subset = True
    elif a == "--min-tests":
        i += 1
        if i >= len(args):
            usage("--min-tests 缺少取值")
        try:
            min_tests = int(args[i])
        except ValueError:
            usage("--min-tests 必须是整数")
    elif a == "--require":
        i += 1
        if i >= len(args):
            usage("--require 缺少取值")
        require.extend(c.strip() for c in args[i].split(",") if c.strip())
    elif a == "--it-select":
        i += 1
        if i >= len(args):
            usage("--it-select 缺少取值")
        it_select.extend(c.strip() for c in args[i].split(",") if c.strip())
    elif a in ("-h", "--help"):
        usage()
    else:
        usage("未知参数 %s" % a)
    i += 1

if not (want_unit or want_it or want_cov):
    want_unit = want_it = want_cov = True
if it_select and not want_it:
    usage("--it-select 需要同时指定 --it")

if modules is None:
    # 默认模块列表来自根 pom 的 <module>，避免脚本与 pom 各自维护一份清单。
    root = ET.parse(os.path.join(ROOT, "pom.xml")).getroot()
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    modules = [m.text.strip() for m in root.findall("m:modules/m:module", ns)]

failures = []
print("== 测试证据核对 ==")
print("  模块：%s" % ", ".join(modules))
print(
    "  检查项：%s"
    % ", ".join(
        [n for n, on in (("单测", want_unit), ("集成测试", want_it), ("覆盖率", want_cov)) if on]
    )
)
print()

REPORT_DIR = {"unit": "surefire-reports", "it": "failsafe-reports"}
SUFFIX = {"unit": "Test.java", "it": "IT.java"}


def java_test_classes(module, suffix):
    """源码里的测试类全限定名（相对 module/src/test/java）。"""
    base = os.path.join(ROOT, module, "src", "test", "java")
    found = {}
    for dirpath, _dirnames, filenames in os.walk(base):
        for name in filenames:
            if not name.endswith(suffix):
                continue
            rel = os.path.relpath(os.path.join(dirpath, name), base)
            fqcn = rel[: -len(".java")].replace(os.sep, ".")
            found[fqcn] = os.path.join(dirpath, name)
    return found


def read_suite(path):
    """返回 (tests, failures, errors, skipped)；解析失败返回 None。"""
    try:
        tree = ET.parse(path)
    except Exception as exc:  # noqa: BLE001 - 报告坏了本身就是失败
        print("  !! 无法解析报告 %s：%s" % (path, exc), file=sys.stderr)
        return None
    suites = []
    root = tree.getroot()
    if root.tag == "testsuite":
        suites.append(root)
    suites.extend(root.findall("testsuite"))
    if not suites:
        return None
    totals = [0, 0, 0, 0]
    for s in suites:
        # 用例数用 testcase 元素数而不是 tests 属性：JUnit5 的聚合报告里两者偶有不一致
        # （实测 GovernanceServiceTest 的 tests=71、testcase=72），以元素数为准才能与
        # Maven 的 "Tests run" 汇总行对齐 —— 两处数字对不上会让人怀疑门禁在算错东西。
        cases = len(s.findall("testcase"))
        totals[0] += cases if cases else int(s.get("tests") or 0)
        totals[1] += int(s.get("failures") or 0)
        totals[2] += int(s.get("errors") or 0)
        totals[3] += int(s.get("skipped") or 0)
    return tuple(totals)


def matches_it_pattern(fqcn, pattern):
    return fnmatch.fnmatchcase(fqcn.rsplit(".", 1)[-1], pattern) or fnmatch.fnmatchcase(
        fqcn, pattern
    )


collected = {"unit": {}, "it": {}}
matched_it_patterns = set()
totals = {"unit": [0, 0, 0, 0], "it": [0, 0, 0, 0]}

for module in modules:
    for kind in ("unit", "it"):
        if kind == "unit" and not want_unit:
            continue
        if kind == "it" and not want_it:
            continue
        report_dir = os.path.join(ROOT, module, "target", REPORT_DIR[kind])
        classes = java_test_classes(module, SUFFIX[kind])
        if kind == "it" and it_select:
            selected = {}
            for fqcn, path in classes.items():
                matching = [p for p in it_select if matches_it_pattern(fqcn, p)]
                if matching:
                    selected[fqcn] = path
                    matched_it_patterns.update(matching)
            classes = selected
        if not classes:
            # 模块本来就没有这一类测试（例如 ingestion-service 没有 IT）不算失败。
            continue
        for fqcn in sorted(classes):
            report = os.path.join(report_dir, "TEST-%s.xml" % fqcn)
            if not os.path.exists(report):
                # --subset 用于「按 -Dtest 只跑一部分」的 job（如 api-contract）：
                # 没跑的类不算失败，但 --require 列出的类仍然必须出现。
                if subset:
                    continue
                failures.append(
                    "%s：源码有 %s 但没有任何 %s 报告（用例根本没跑）"
                    % (module, fqcn, "单测" if kind == "unit" else "集成测试")
                )
                continue
            suite = read_suite(report)
            if suite is None:
                failures.append("%s：%s 的报告无法解析" % (module, fqcn))
                continue
            tests, fails, errors, skipped = suite
            collected[kind][fqcn] = suite
            for idx, value in enumerate(suite):
                totals[kind][idx] += value
            if tests == 0:
                failures.append("%s：%s 报告里 0 个用例" % (module, fqcn))
            if fails or errors:
                failures.append(
                    "%s：%s 有 %d 失败 / %d 错误（构建本该已失败）" % (module, fqcn, fails, errors)
                )
            if kind == "it" and skipped:
                failures.append(
                    "%s：%s 有 %d 个集成测试被跳过；IT 不允许静默跳过" % (module, fqcn, skipped)
                )

        if not subset and not os.path.isdir(report_dir):
            failures.append(
                "%s：缺少 %s 目录，%s 一份都没产出"
                % (
                    module,
                    REPORT_DIR[kind],
                    "单测" if kind == "unit" else "集成测试",
                )
            )

if it_select and not matched_it_patterns:
    failures.append("--it-select 未匹配任何 IT 源码类")
for pattern in it_select:
    # 多个通配模式是 OR 关系：允许某个模式暂无测试类。
    # 精确类名仍需逐个命中，防止 CI 配置拼写错误造成假绿。
    if not any(ch in pattern for ch in "*?[") and pattern not in matched_it_patterns:
        failures.append("--it-select 指定类 %s 不存在" % pattern)

for kind, label in (("unit", "单测"), ("it", "集成测试")):
    if kind == "unit" and not want_unit:
        continue
    if kind == "it" and not want_it:
        continue
    tests, fails, errors, skipped = totals[kind]
    print(
        "  %s：%d 个用例 / %d 失败 / %d 错误 / %d 跳过%s"
        % (label, tests, fails, errors, skipped, "（子集运行，仅统计已产出的报告）" if subset else "")
    )
    if tests < min_tests:
        failures.append(
            "%s只跑了 %d 个用例，低于要求的 %d 个" % (label, tests, min_tests)
        )

for cls in require:
    hit = any(fqcn == cls or fqcn.endswith("." + cls) for fqcn in
              list(collected["unit"]) + list(collected["it"]))
    if not hit:
        failures.append("要求出现的测试类 %s 未产出任何报告" % cls)

if want_cov:
    print()
    print("== 覆盖率门禁核对（报告侧，独立于 Maven 的 check）==")
    root_pom = ET.parse(os.path.join(ROOT, "pom.xml")).getroot()
    threshold = 0.80
    for prop in root_pom.findall("properties/*"):
        if prop.tag.endswith("jacoco.line.coverage.minimum") and prop.text:
            threshold = float(prop.text)
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    gated = 0
    seen_any = False
    for module in modules:
        pom = os.path.join(ROOT, module, "pom.xml")
        if not os.path.exists(pom):
            continue
        proot = ET.parse(pom).getroot()
        includes = []
        for plugin in proot.findall(".//m:build/m:plugins/m:plugin", ns):
            if plugin.find("m:artifactId", ns) is None:
                continue
            if plugin.find("m:artifactId", ns).text != "jacoco-maven-plugin":
                continue
            for execution in plugin.findall("m:executions/m:execution", ns):
                goals = [g.text for g in execution.findall("m:goals/m:goal", ns)]
                if "check" not in goals:
                    continue
                includes.extend(
                    inc.text.strip()
                    for inc in execution.findall(
                        "m:configuration/m:rules/m:rule/m:includes/m:include", ns
                    )
                    if inc.text
                )
        if not includes:
            continue
        seen_any = True
        report = os.path.join(ROOT, module, "target", "site", "jacoco", "jacoco.xml")
        if not os.path.exists(report):
            failures.append("%s：声明了覆盖率门禁却没有 jacoco.xml（report 未生成）" % module)
            continue
        jroot = ET.parse(report).getroot()
        packages = {}
        for pkg in jroot.findall("package"):
            counters = {
                c.get("type"): (int(c.get("missed")), int(c.get("covered")))
                for c in pkg.findall("counter")
            }
            packages[pkg.get("name").replace("/", ".")] = counters.get("LINE", (0, 0))
        for pattern in includes:
            # JaCoCo 的 include 模式里 * 是通配；这里只处理「包名 + 可选尾部 *」的写法，
            # 与 pom 里的实际写法一致（见 core/query pom 的注释）。
            prefix = pattern.rstrip("*").rstrip(".")
            matched = [
                (name, counter)
                for name, counter in packages.items()
                if name == prefix or name.startswith(prefix + ".")
            ]
            if not matched:
                failures.append(
                    "%s：门禁 include「%s」匹配不到任何包 —— 空集合永远判通过，这是假门禁"
                    % (module, pattern)
                )
                continue
            covered = sum(c[1] for _n, c in matched)
            missed = sum(c[0] for _n, c in matched)
            total = covered + missed
            if total == 0:
                failures.append("%s：门禁 include「%s」匹配到的包没有行数据" % (module, pattern))
                continue
            ratio = covered / total
            gated += 1
            flag = "OK " if ratio >= threshold else "低 "
            print(
                "  %s %-46s LINE %6.2f%%  (%d/%d)  阈值 %.0f%%"
                % (flag, pattern, ratio * 100, covered, total, threshold * 100)
            )
            if ratio < threshold:
                failures.append(
                    "%s：%s 行覆盖率 %.2f%% 低于 %.0f%%"
                    % (module, pattern, ratio * 100, threshold * 100)
                )
    if not seen_any and modules:
        # 没有声明门禁本身就值得停下来看一眼：这一层等于没有。
        failures.append("所选模块里没有任何 JaCoCo check 门禁（覆盖率没有任何约束）")
    else:
        print("  共核对 %d 条门禁，阈值 %.0f%%" % (gated, threshold * 100))

print()
if failures:
    print("结果：失败 —— 测试证据不完整或覆盖率不达标（%d 项）" % len(failures), file=sys.stderr)
    for item in failures:
        print("  - %s" % item, file=sys.stderr)
    raise SystemExit(1)
if subset:
    print("结果：通过 —— 已产出的报告无失败/跳过，--require 指定的类全部出现")
else:
    print("结果：通过 —— 报告与源码一一对应，无跳过，覆盖率门禁真实生效")
PY
