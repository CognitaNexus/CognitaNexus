#!/usr/bin/env bash
# 实测「暂停采集」与「撤销展示许可」是否真的拆开了（ADR-001 方案 A）。
#
# 这个脚本跑的不是默认构建里的验收用例，而是需要隔离库与上万条造数的探针：
#   - 方案 A 之前，它断言的是「冲突仍在」（暂停即下线、5001 只收紧 5000、第二来源停用仍算依据）；
#   - 方案 A 之后，它断言的是 SRS 口径（暂停不动可见性、撤权受理即阻断、判据看许可不看采集开关）。
# 探针全绿 = 三条语义都对；任何一条红 = 两种意图又粘回一起了。
#
# 为什么不接进 CI 门禁：它要求本机 55432 的隔离实例与约两万条造数，属于「上线前人工复核」，
# 不是每次提交都要跑的用例。CI 上跑它只会得到「实例没起」的红。
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1

# 先取回输出再判断：直接接 `| grep -q` 时 grep 会提前退出，上游收到 SIGPIPE 返回 141，
# 在 pipefail 下会被误判成「实例没起」。
db_status=$(bash ops/local/test-db.sh status 2>&1)
if ! printf '%s' "$db_status" | grep -q "集成测试实例运行中"; then
  echo "需要 55432 的隔离实例：先执行 bash ops/local/test-db.sh start" >&2
  exit 2
fi

echo "== 运行展示许可探针（连接 55432/core_it）"
./mvnw -B -ntp -pl services/core-service test \
  -Dtest=SourceDisplayLicenseProbe -DfailIfNoTests=false -o 2>&1 \
  | grep -aE "Tests run:|BUILD" | sed 's/^\[INFO\] //'

# 探针结论从 surefire XML 读，不直接从 Maven 输出里截：Windows 下 JVM 的 System.out
# 用系统码页（GBK），经管道进 grep/sed 再进 UTF-8 终端就是乱码；XML 是 UTF-8，读出来是原文。
echo "== 探针输出（取自 surefire XML，避免控制台编码干扰）"
python - <<'PY'
import glob
import io

found = False
for path in glob.glob(
    "services/core-service/target/surefire-reports/TEST-*SourceDisplayLicenseProbe.xml"
):
    for line in io.open(path, encoding="utf-8").read().splitlines():
        if "[PROBE" in line:
            print(line[line.index("[PROBE") :].strip())
            found = True
if not found:
    print("未在 surefire 报告里找到探针输出；先确认上面的 Tests run 是否真的执行了 5 项。")
PY

cat <<'TXT'

== 如何读这个结果
  Tests run: 5, Failures: 0 表示方案 A 的五条语义同时成立：
    A1 暂停采集不动可见性，导出仍包含这些成果；
    A2 许可一置 REVOKED，导出立即不含（与逐批收紧进度无关）；
    A3 5001 条撤权目标/处理/残留三者对得上，作业 SUCCEEDED；
    A4 判据看展示许可，不看采集开关（暂停的来源仍算有效依据）；
    A5 恢复只放开有归因的收紧，无归因的（人工限制）宁可少恢复。
  任一条红都要先看 ADR-001：多半是两种意图又被同一个布尔承担了。
TXT
