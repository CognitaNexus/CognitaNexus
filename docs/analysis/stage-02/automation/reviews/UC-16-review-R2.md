REVIEW_STATUS: ISSUES
SEVERITY: MAJOR

## Review
Scope: `uc-16/01`, `04`, `08`, `diagrams/DGM-SEQ-24-four-types-scale.puml`, generated SVG header, vs `automation/reviews/UC-16-review-R1.md`. Read-only; nothing modified.

- Correct (F2/F3/F5/F6 fixes, partial): M25 now carries range+cutoff, by-type counts, uncertain-version rule and generation time (`04:52`, puml M25 line). Alt label now includes A4 (`puml "alt 存在缺项或计数不一致（A1/A2/A3/A4/A8）"`). M22 label no longer bundles NOT RUN/外链 (`04:§2 M22`, puml M22). Failure-guard note added (`04:§4` footnote, puml legend).

- Finding: **P0/P1 (MAJOR) — F1 not actually fixed: visitor still receives no coverage conclusion.**
  - `04:§2` M24 receiver column = `核对执行者（管理员）`, M26 receiver column = `核对执行者（管理员）`; only the *message name* carries “（接收者为发起核对的核对者）”.
  - `puml` still routes `UI --> Checker : M24 …` and `UI --> Checker : M26 …`; the parenthetical “发起核对的核对者：访客或核对执行者” is label text, not a receiver.
  - Consequence unchanged from R1: for a visitor-initiated check (M1), the success path delivers only M27 (external link); `AC-IMPL-FR16-01` “四类可识别且显示合法来源” and `01:§5.1.1` still have no message to the primary actor. Model asserts dual routing but models single recipient — an unverifiable claim in a sequence diagram.
  - Smallest fix: set M24/M26 receiver to `核对执行者（管理员）/访客` in both `puml` (or use one shared recipient label for “发起本次核对的核对者”) and the `04` 接收者 column; drop or keep the name parenthetical for consistency with M8.

- Non-blocking residuals (not blockers; status of the other R1 items):
  - F2 residue: `01 E10` still omits “本次数据范围与截止时间” although `01:§5.1.5` declares it (M25 has it) — P2 trace gap.
  - F3 residue: `04` M24 event-steps column still `A1–A3、A8` (no A4) and `04:§3.4` 缺项 list omits field-missing — P2, now inconsistent with puml/M13.
  - F4 residue: guard is note-only; diagram structure wraps failure alt around M5/M6 only, so M12/M15/M17/M20 failures remain unmodeled while `04:§4`/legend assert M5–M20 coverage — P2 claim-without-model.
  - F5 residue: `04:§3.6` still says “M22 把 1,000,000 与 5,000,000 作为目标值携带”, contradicting the cleaned M22 (M22 依据 also still lists BR-UC16-08) — P2.
  - F6 not fixed: `08:§4.1` records 3082×2277 and `08:§6` records 3732×2306, while the submitted SVG header is `width=3734.375px height=2307.8125px viewBox="0 0 3734 2307"`; the same document now states two different sizes — P2 verification-record defect.

- Merge verdict: **BLOCK** — F1 (participant-observability of `AC-IMPL-FR16-01`) remains open; F2–F6 residuals are P2 and need not block once F1 is fixed. Re-run `python3 scripts/docs/check_documentation.py` and `python3 scripts/docs/verify_69_ac.py` after the receiver edit, and re-render both diagrams so `08` dimensions match the SVG/PNG actually shipped.