## Review

REVIEW_STATUS: PASS WITH NOTES
SEVERITY: MINOR

Scope: 9 md + 2 `.puml` + 2 regenerated SVG/PNG in `docs/analysis/stage-02/uc-05/`, SRS V2.2 L147 (FR05) and L490 (§11.3), `docs/uml/claim/DGM-STATE-02-claim-request.puml`, R1/R2 reviews. No files modified.

**P1-A — CLOSED (substance).** All enumerations are now five-state and evidence-correct: `01:101` (BR-UC05-04, source `FR05；SPEC §3；DGM-STATE-02`), `01:60,79`, `03:24,62,65`, `06:78,81,84`, `07:25,92`, `04:65`, `DGM-SEQ-10-claim.puml:72`. `01:111` now states the gap correctly and I verified it: FR05 L147 and SPEC/DGM-STATE-02 list only the five states; `DGM-STATE-02.puml:7` has `[*] --> Pending : 本人提交`, no `待提交`; the only occurrence is SRS §11.3 (L490). No invented `AC-05*` IDs (grep: zero hits).

**P1-B — CLOSED.** `04:82` now reads “A1b 不读取/不记录 `competitionFact`；竞争事实仅在证据齐备的 A5 路径由 M17/M18 读取并随 M19 记录”. `04` §3.6 (L104–110) no longer claims A1b records the fact; M13 (L43) saves only 申请人/目标/状态/缺失项; M17/M18 sit only in the 证据齐备 branch of `DGM-SEQ-10-claim.puml:42-45` and `generated/DGM-SEQ-10-claim.svg:43-45`. Diagram and text agree.

**Regeneration in sync.** SVG embedded sources match the current `.puml` byte-for-byte in content (SEQ embedded `@startuml`→`@enduml` spans svg lines 2–86 = puml 86 lines; VOPC svg 17–56, including `0..*:1` legend and `AC-IMPL-FR05-03/04` at svg 48,51). PNGs present for both.

### Findings

- **P1 (non-blocking, bookkeeping) — B-12 referenced but never registered.** `01:111` says “差异登记为 B-12”, yet `08-self-check.md` §6 stops at B-11 (`08:139`) and `08:152` plus `06:151` both assert the open-item set is “B-01～B-11”. Grep across `docs/analysis/stage-02` finds `B-12` only in `01:111`. The deferred item therefore has no registry row for the R5 handoff, and three files contradict `01`. Smallest fix: add a B-12 row to `08` §6 (“`待提交` 为 SRS §11.3 草稿态，UC-05 不产生，`DGM-STATE-02`/SPEC §3 缺该状态”), then update the range strings at `08:152` and `06:151` to B-12.
- **P2 —** `README:16` still says “待确认 B-01–B-08” (08 now holds B-01–B-11).
- **P2 —** `01:131` cites “8 节‘部分覆盖’”, but no such wording exists in §8 (R2 note, unchanged).
- **P2 —** `07` §1/§5 tabulate only the B-11 naming gap; consider adding the §11.3 `待提交` gap for consistency with `01:111`.

No MAJOR/CRITICAL findings remain: no evidence-free claims about state sets, no behavior asserted without supporting messages, no invented AC IDs, no authorization writes, VOPC 7 classes and M1–M36 consistent with the rendered diagram.

**Merge verdict: OK with notes** — the P1 is a one-line registry addition; the R2 blockers are substantively resolved.

Supervisor must run: `python3 scripts/docs/check_documentation.py`, `python3 scripts/docs/verify_69_ac.py`, `python3 scripts/docs/verify_scoped_srs.py`. I could not re-verify `08` §4.1 command output, HEAD `4209cf0`, or PNG visual inspection.