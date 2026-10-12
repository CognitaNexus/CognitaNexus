#!/usr/bin/env python3
"""Static checks for the scoped shared SRS. No runtime/system pass claims."""
import json
import re
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[2]
srs_path = root / "docs/srs/CognitaNexus-Shared-SRS-V1.1-Implementation-RC.md"
trace_path = root / "docs/srs/integration/engineering-scope-trace-v1.1-rc.json"
srs = srs_path.read_text(encoding="utf-8")
trace = json.loads(trace_path.read_text(encoding="utf-8"))

chapters = re.findall(r"^## (\d+)\. ", srs, flags=re.MULTILINE)
assert chapters == [str(x) for x in range(1,11)], chapters
FR = re.findall(r"^### (FR\d{2}) ", srs, flags=re.MULTILINE)
NF = re.findall(r"^### (NF\d{2}) ", srs, flags=re.MULTILINE)
assert FR == ["FR%02d" % i for i in range(1,17)], FR
assert NF == ["NF%02d" % i for i in range(1,9)], NF
assert "### 5.1 IN01" in srs
assert "### 5.2 O01、O02（Optional" in srs

ids = re.findall(r"\*\*(AC-IMPL-(?:FR|NF|IN)\d{2}-\d{2})\*\*", srs)
assert len(ids) == 46 and len(set(ids)) == 46, (len(ids), len(set(ids)))
requirements = trace["requirements"]
cases = trace["acceptance_cases"]
assert len(requirements) == 124
assert len(cases) == 287
assert len(set(x["id"] for x in requirements)) == 124
assert len(set(x["old_id"] for x in cases)) == 287
assert all(x["implementation_parent"] in FR + NF + ["IN01"] for x in requirements)
assert all(x["reference_ac_group"] in ids for x in cases)
assert all(x["link_type"] == "PARENT_SCOPE_REFERENCE_NOT_ASSERTED_EQUIVALENCE" for x in cases)
assert all(x["new_parent"] == x["old_id"][3:7] for x in cases)
assert "仅合格共同论文" in srs and "非论文" in srs
assert "O01/O02" in srs and "延期" in srs
assert "待两组签认" in srs or "未经两组签认" in srs
assert "未执行" in srs and "Review Candidate" in srs

# File relative links: check only ordinary relative files, never remote URLs.
paths = set()
for match in re.findall(r"!?(?:\[[^\]]*\])\(([^)]+)\)", srs):
    dest = match.split("#", 1)[0]
    if not dest or dest.startswith(("http://", "https://", "mailto:", "#")):
        continue
    target = (srs_path.parent / dest).resolve()
    assert target.exists(), (dest, target)
    paths.add(dest)

for p in ["docs/uml/analytics/DGM-ACTIVITY-03-collab-path.puml",
          "docs/uml/analytics/DGM-USECASE-02-analytics.puml"]:
    source = (root / p).read_text(encoding="utf-8")
    assert "允许非论文成果" not in source

summary = {
  "status": "DOCUMENT_STATIC_PASS_ONLY",
  "chapters": len(chapters),
  "functional_requirements": len(FR),
  "nonfunctional_requirements": len(NF),
  "normative_candidate_ac": len(ids),
  "legacy_ovo_requirements_preserved": len(requirements),
  "legacy_ovo_ac_cases_preserved": len(cases),
  "resolved_relative_links": len(paths),
  "human_joint_approval": "NOT_VERIFIED",
  "application_test": "NOT_EXECUTED",
  "real_data_10000": "NOT_VERIFIED",
  "codearts_and_performance": "NOT_VERIFIED"
}
print(json.dumps(summary, ensure_ascii=False, indent=2))
