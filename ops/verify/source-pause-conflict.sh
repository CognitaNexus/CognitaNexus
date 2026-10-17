#!/usr/bin/env bash
# 实测「来源暂停采集」与「撤销展示许可」的语义冲突（ADR-001 §2.1／§2.2／§2.4）。
#
# 这个脚本跑的不是验收用例，是取证探针：它断言的是**当前实现做了什么**，而 SRS 要求相反
# （AC-FR01-S01-02、BR-606：来源停用后既有公开成果仍可读）。所以：
#
#   - 探针全绿  = 冲突仍然存在，ADR-001 未被采纳或未被实现；
#   - 探针变红  = 实现已经改了，需要把断言改写成 SRS 口径，并把 ADR-001 状态推进。
#
# 不要把它接进 CI 门禁：门禁绿会让人以为系统符合需求。
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1

# 先取回输出再判断：直接接 `| grep -q` 时 grep 会提前退出，上游收到 SIGPIPE 返回 141，
# 在 pipefail 下会被误判成「实例没起」。
db_status=$(bash ops/local/test-db.sh status 2>&1)
if ! printf '%s' "$db_status" | grep -q "集成测试实例运行中"; then
  echo "需要 55432 的隔离实例：先执行 bash ops/local/test-db.sh start" >&2
  exit 2
fi

echo "== 运行语义冲突探针（连接 55432/core_it）"
./mvnw -B -ntp -pl services/core-service test \
  -Dtest=SourcePauseConflictProbe -DfailIfNoTests=false -o 2>&1 \
  | grep -aE "Tests run:|BUILD" | sed 's/^\[INFO\] //'

# 探针的三行结论从 surefire XML 读，不直接从 Maven 输出里截：Windows 下 JVM 的 System.out
# 用系统码页（GBK），经管道进 grep/sed 再进 UTF-8 终端就是乱码；XML 是 UTF-8，读出来是原文。
echo "== 探针输出（取自 surefire XML，避免控制台编码干扰）"
python - <<'PY'
import glob
import io

found = False
for path in glob.glob("services/core-service/target/surefire-reports/TEST-*SourcePauseConflictProbe.xml"):
    for line in io.open(path, encoding="utf-8").read().splitlines():
        if "[PROBE" in line:
            print(line[line.index("[PROBE") :].strip())
            found = True
if not found:
    print("未在 surefire 报告里找到探针输出；先确认上面的 Tests run 是否真的执行了 3 项。")
PY

cat <<'TXT'

== 如何读这个结果
  探针通过（Tests run: 3, Failures: 0）表示上面三条现状被复现，不代表系统符合需求。
  三条分别对应 ADR-001 §2.1（暂停即下线）、§2.2（5001 只收紧 5000 且响应无剩余标记）、
  §2.4（第二来源已停用仍被当作有效展示依据）。
TXT
