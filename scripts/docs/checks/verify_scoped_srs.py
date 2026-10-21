#!/usr/bin/env python3
"""Static checks for the current 69-AC SRS and historical scope trace.

Read-only. This does not claim runtime test, teacher review or platform verification.
"""
import json
import re
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[3]
srs_path = root / "docs/srs/CognitaNexus-Shared-SRS-V2.2-69AC.md"
ledger_path = root / "docs/srs/integration/69-ac-baseline.json"
legacy_trace_path = root / "docs/srs/integration/engineering-scope-trace-v1.1-rc.json"
srs = srs_path.read_text(encoding="utf-8")
ledger = json.loads(ledger_path.read_text(encoding="utf-8"))

chapters = re.findall(r"^# (\d+)\. ", srs, flags=re.MULTILINE)
assert chapters == [str(x) for x in range(1, 14)], chapters
FR = re.findall(r"^### (FR\d{2}) ", srs, flags=re.MULTILINE)
NF = re.findall(r"^### (NF\d{2}) ", srs, flags=re.MULTILINE)
assert FR == ["FR%02d" % i for i in range(1, 17)], FR
assert NF == ["NF%02d" % i for i in range(1, 9)], NF
assert "## 5.1 IN01" in srs

ids = re.findall(r"^- \*\*(AC-IMPL-(?:FR|NF|IN)\d{2}-\d{2})\*\*：", srs, flags=re.MULTILINE)
assert len(ids) == 69 and len(set(ids)) == 69, (len(ids), len(set(ids)))
assert ledger["count"] == 69 and len(ledger["rows"]) == 69
assert {x["id"] for x in ledger["rows"]} == set(ids)
for row in ledger["rows"]:
    assert row["runtime_status"] in {"NOT RUN", "计划验收，未执行"}

for parent, count in {"FR09": 6, "FR12": 5, "FR15": 6, "IN01": 4, "NF02": 2}.items():
    assert sum(x.startswith("AC-IMPL-" + parent + "-") for x in ids) == count

# Final decisions: A1c and scale (target values, not measurements).
for token in ("UserProfile", "UC-22", "四类合格共同成果", "IN01", "只使用合格共同论文子图"):
    assert token in srs, token
for token in ("100万", "500万", "100并发"):
    assert token in srs, token
assert "1,000,000" in srs and "5,000,000" in srs

# Historical 124/287 scope trace remains provenance, not current AC equivalence.
if legacy_trace_path.exists():
    trace = json.loads(legacy_trace_path.read_text(encoding="utf-8"))
    reqs, cases = trace.get("requirements", []), trace.get("acceptance_cases", [])
    if reqs:
        assert len(reqs) == 124 and len({x["id"] for x in reqs}) == 124
    if cases:
        assert len(cases) == 287 and len({x["old_id"] for x in cases}) == 287
        assert all(x["link_type"] == "PARENT_SCOPE_REFERENCE_NOT_ASSERTED_EQUIVALENCE" for x in cases)

# Relative links in current SRS must resolve.
paths = set()
for match in re.findall(r"!?(?:\[[^\]]*\])\(([^)]+)\)", srs):
    dest = match.split("#", 1)[0]
    if not dest or dest.startswith(("http://", "https://", "mailto:", "#")):
        continue
    target = (srs_path.parent / dest).resolve()
    assert target.exists(), (dest, target)
    paths.add(dest)

for p in [
    "docs/uml/profile/DGM-USECASE-07-profile.puml",
    "docs/uml/profile/DGM-ACTIVITY-05-register-profile.puml",
    "docs/uml/analysis/DGM-CLASS-01-analysis.puml",
    "docs/uml/analysis/DGM-ACTIVITY-08-collab-network.puml",
]:
    assert (root / p).exists(), p

summary = {
    "status": "DOCUMENT_STATIC_PASS_ONLY",
    "chapters": len(chapters),
    "functional_requirements": len(FR),
    "nonfunctional_requirements": len(NF),
    "authoritative_ac": len(ids),
    "legacy_scope_trace_checked": legacy_trace_path.exists(),
    "resolved_relative_links": len(paths),
    "a1c_scope": "checked",
    "scale_targets": "100万联调 / 500万最终 / 固定环境100并发",
    "human_joint_approval": "NOT_VERIFIED",
    "application_test": "NOT_EXECUTED",
    "performance_and_recovery": "NOT_RUN",
    "codearts": "NOT_VERIFIED",
}
print(json.dumps(summary, ensure_ascii=False, indent=2))
