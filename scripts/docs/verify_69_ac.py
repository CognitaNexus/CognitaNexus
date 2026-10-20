#!/usr/bin/env python3
"""Verify the frozen 69-AC baseline, SRS, trace and course-analysis artifacts.

Read-only. Exit code 1 on any inconsistency.
"""
from __future__ import annotations
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "docs/srs/integration/69-ac-baseline.json"
SRS = ROOT / "docs/srs/CognitaNexus-Shared-SRS-V2.2-69AC.md"
TRACE = ROOT / "docs/analysis/stage-02/03-AC-UC-分析模型追踪.md"
USECASE = ROOT / "docs/analysis/stage-02/02-用例模型与规约.md"
MAIN = ROOT / "docs/requirements/学术成果分享平台需求分析与可行性报告.md"
REQ_MATRIX = ROOT / "docs/analysis/课程要求与交付追踪矩阵.md"

def norm(text: str) -> str:
    text = re.sub(r"[（(](?:NOT RUN|尚未执行)[）)]", "", text)
    return re.sub(r"\s+", "", text).strip("。；; ")

def fail(msg: str) -> None:
    print("FAIL:", msg)

def main() -> int:
    errors: list[str] = []
    ledger = json.loads(BASE.read_text(encoding="utf-8"))
    rows = ledger.get("rows", [])
    ids = [r["id"] for r in rows]
    texts = {r["id"]: r["text"] for r in rows}
    if ledger.get("count") != 69 or len(rows) != 69:
        errors.append(f"ledger count != 69 ({ledger.get('count')}, rows={len(rows)})")
    if len(ids) != len(set(ids)):
        errors.append("duplicate AC IDs in ledger")
    if set(ids) != set(texts):
        errors.append("ledger ID/text map mismatch")
    groups = {"FR": 0, "IN01": 0, "NF": 0}
    for i in ids:
        p = i.split("-")[2]
        groups["FR" if p.startswith("FR") else ("IN01" if p == "IN01" else "NF")] += 1
    if groups != {"FR": 54, "IN01": 4, "NF": 11}:
        errors.append(f"unexpected group counts: {groups}")

    srs = SRS.read_text(encoding="utf-8")
    srs_defs = dict(re.findall(r"^- \*\*(AC-IMPL-[A-Z]+\d+-\d+)\*\*：(.+)$", srs, re.M))
    if len(srs_defs) != 69 or set(srs_defs) != set(ids):
        errors.append(f"SRS definitions mismatch: count={len(srs_defs)}, missing={sorted(set(ids)-set(srs_defs))}, extra={sorted(set(srs_defs)-set(ids))}")
    for i in ids:
        if i in srs_defs and norm(srs_defs[i]) != norm(texts[i]):
            errors.append(f"text mismatch for {i}: SRS vs ledger")

    trace = TRACE.read_text(encoding="utf-8")
    trace_rows = re.findall(r"^\|\s*\d+\s*\|\s*`(AC-IMPL-[A-Z]+\d+-\d+)`\s*\|", trace, re.M)
    if len(trace_rows) != 69 or set(trace_rows) != set(ids):
        errors.append(f"trace rows mismatch: {len(trace_rows)}, missing={sorted(set(ids)-set(trace_rows))}, extra={sorted(set(trace_rows)-set(ids))}")

    usecase = USECASE.read_text(encoding="utf-8")
    for n in list(range(1, 20)) + [22]:
        uc = f"UC-{n:02d}"
        if f"| {uc} |" not in usecase:
            errors.append(f"missing use-case row {uc}")
    for n in (20, 21):
        uc = f"UC-{n:02d}"
        if f"| {uc} |" not in usecase:
            errors.append(f"missing deferred use-case row {uc}")
    for marker in ("User 1:1 UserProfile", "四类合格共同成果", "IN01", "只使用合格共同论文子图", "UC-22"):
        if marker not in usecase and marker not in srs:
            errors.append(f"missing A1c/scope marker: {marker}")

    for name, content in (("SRS", srs), ("main report", MAIN.read_text(encoding="utf-8"))):
        for token in ("100万", "500万", "100并发"):
            if token not in content:
                errors.append(f"{name} missing scale token {token}")
    # Require the old formal scale targets to be explicitly historical/absent in current statements.
    main_text = MAIN.read_text(encoding="utf-8")
    if "最终容量≥10万" in main_text or "功能联调基线不少于1万" in main_text:
        errors.append("main report still states obsolete scale as current")

    # Active artifact references must resolve to the 69 baseline; historical files are excluded.
    active = [
        SRS,
        TRACE,
        USECASE,
        MAIN,
        ROOT / "docs/analysis/stage-01/02-需求基线与冲突登记.md",
        ROOT / "docs/analysis/stage-01/03-用例工作索引.md",
        ROOT / "docs/analysis/stage-01/04-候选架构与理由.md",
        ROOT / "docs/analysis/stage-01/05-分析机制.md",
        ROOT / "docs/analysis/stage-01/06-关键抽象与证据.md",
        ROOT / "docs/analysis/stage-02/01-BCE分析类模型.md",
        ROOT / "docs/analysis/stage-02/02-用例模型与规约.md",
        ROOT / "docs/analysis/stage-02/04-活动图与状态图.md",
        ROOT / "docs/analysis/stage-02/05-分析序列与用例实现.md",
        ROOT / "docs/analysis/stage-02/README.md",
    ]
    for path in active:
        content = path.read_text(encoding="utf-8")
        refs = set(re.findall(r"AC-IMPL-[A-Z]+\d+-\d+", content))
        unknown = sorted(refs - set(ids))
        if unknown:
            errors.append(f"unknown AC refs in {path.relative_to(ROOT)}: {unknown}")

    for path in (TRACE, USECASE):
        if path.read_text(encoding="utf-8").count("AC-IMPL-") < 69 and path == TRACE:
            errors.append(f"trace does not mention all ACs: {path}")

    if errors:
        for e in errors:
            fail(e)
        return 1
    print("PASS: 69 AC unique; SRS text matches ledger; trace has all 69; UC-01..19/22 present; A1c and scale markers present; active references resolve.")
    print(f"GROUPS: {groups}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
