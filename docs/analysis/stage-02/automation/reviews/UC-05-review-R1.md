## Review

REVIEW_STATUS: ISSUES
SEVERITY: MAJOR

Reviewed (no files modified, per instruction; `REVIEWER.md` would normally write `automation/reviews/UC-05-review-RN.md` — no-edit instruction took precedence, so the review is returned here): `automation/instructions/REVIEWER.md`, SRS V2.2 FR03/FR04/FR05/FR06/FR15 (§6.3, §8 L396–402, §11.3 L490, BR-IMP-03/05/10/11 L374–383), NF03/NF06 ACs (L426/L448), NF01-01 (L412), SPEC ID-07/ID-12/T26 (L188–193, L324), `DGM-STATE-02`, trace rows (03-AC-UC L24, 39, 42, 64–67, 112, 117, 129), all 8 files + 2 `.puml` + rendered SVG in `docs/analysis/stage-02/uc-05/`.

### Correct (verified)
- **BCE from event flow, not global copy**: 7 classes in `03-bce-identification.md` §2 match exactly the 7 lifelines in `diagrams/DGM-SEQ-10-claim.puml`; rejected candidates (`UserProfile`, `Authorship`, `ConflictQueue`, `ClaimReviewController`, …) are listed with reasons (§4).
- **Message traceability**: M1–M36 each carry 事件步骤 + 依据 (`04-sequence-messages.md` §2); rendered SVG contains all 36 labels and the A1a/A1b/A2–A4/A6–A8 branches (verified in `generated/DGM-SEQ-10-claim.svg`).
- **Claim vs Auth vs UserProfile vs Authorship**: M17/M18 read-only, M19 records `待审核` only, M23 "原授权未变更"; no create/modify message to `ScholarAuthorization`; `05-vopc.md` §2.3 and §4 exclude `UserProfile`/`Authorship`/`WorkEntity`; matches AC-IMPL-FR05-03/04, BR-IMP-05, SPEC ID-07/ID-12.
- **Missing evidence (AC-IMPL-FR05-01)**: both baseline-allowed outcomes kept (A1a reject / A1b 待补充) and flagged B-01/B-02 instead of silently chosen — correct handling of an unfrozen rule.
- **No UC-06 content**: 通过/驳回/争议 appear only as read values (M35); state transitions split in `01` §9 against `DGM-STATE-02`. **No implementation design**: only Do/Know, analysis mechanisms, `materialRef`/`visibilityScope` explicitly declared non-storage (`06` §1,§7).
- **Citations check out**: BR-IMP-11 → NF03/NF06; SPEC ID-12 → 原授权不自动转移; §8 row 399 ("本人认领申请/查看私有结果：本人提交/读") exists as quoted. AC set in `01` §10 = trace row L129 exactly.
- Working tree confirms only `uc-05/**` additions from this task (`watchdog_diff --stat`: no SRS/global-UML edits).

### Findings
**P1-1 — Invented AC IDs in the VOPC.** `diagrams/DGM-VOPC-06-claim.puml:32,35` (rendered `generated/DGM-VOPC-06-claim.svg:48,51`) cite `AC-05-03`/`AC-05-04`. Those IDs exist nowhere in the baseline (only `AC-IMPL-FR05-03/04`; `grep AC-05-0` hits only this figure). Violates AGENTS.md §4 "编号必须可追踪且定义唯一". Fix: substitute official IDs in the legend and re-render SVG+PNG.

**P1-2 — State set contradicts SRS §11.3 without registration.** `01` §8 BR-UC05-04 and `06` §6 declare the claim state set as `待审核/待补充/通过/驳回/争议`; SRS §11.3 (L490) enumerates `待提交/待审核/待补充/通过/驳回/争议`. `08` B-11 only registers the `通过/驳回` vs `已通过/已驳回` naming gap. Fix: extend BR-UC05-04/B-11 (or add B-12) to record `待提交` (SRS §11.3 draft state, not produced by UC-05, absent from `DGM-STATE-02`); do not edit the SRS.

**P2-1 — A1b path leaves competition unrecorded.** M13 (`puml` A1b branch) creates `待补充` without M17/M18 and without `competitionFact`, yet `06` §6 lists `competitionFact` as a claim attribute and A5/`04` §3.3 assert competition is recorded at submit. AC-IMPL-FR05-03 for a missing-evidence competing submission is thus unstated. Fix: one line in `04` §3.3 / `01` §5.2 stating whether A1b records competition (else fold into B-06).

**P2-2 — NF06-01 clause 1 has no evidence-side message.** AC-IMPL-NF06-01 ("普通用户打开其他申请证明时被拒绝") is modeled only as application-ownership filtering (M28/M29); the query group sends nothing to `ReviewEvidence`, though `06` §7 gives it "向他人读取请求拒绝" and `03` C6 / `05` §1 cite A6/E11 as its discovery events. Fix: add the visibility-check message (e.g. `Ctl -> Evidence : 校验可见范围/拒绝他人读取`) or state in `04` §3.5 that the guard is inherited without a message, and align `05` §1 attribution.

**P2-3 — Coverage labels inconsistent.** `01` §10 marks FR05-02 "部分覆盖" but claims plain 覆盖 for FR05-03/04, whose approval-side effects ("新授权仅对目标门户有效", "仅新增经批准的关联/管理授权") are UC-06. Fix: label FR05-03/04 as 提交侧/部分覆盖.

**P2-4 — UI outcome count inconsistent.** `06` §2 "结果五态可区分（待审核/待补充/拒绝/失败/无申请）" vs `06` §3 outcome list of six (adds 无权) vs `04` §3.6 four categories. Fix: one enumeration used consistently.

**P2-5 — Stage index stale.** `docs/analysis/stage-02/README.md` §2/§3 lists UC-01–04/UC-22 diagrams (`SEQ-09`, `VOPC-05`) but has no UC-05 row and still states "UC-04 R1, UC-01 R2 COMPLETE", while `uc-05/08` claims `STATIC COMPLETE`. Fix: add UC-05 + `DGM-SEQ-10`/`DGM-VOPC-06` rows, or record the deferral explicitly in `08` §1 (which currently only says shared files are unmodified).

### Not independently verifiable
`08` §4.1 command outputs, `git branch`/HEAD `4209cf0`, and the PNG visual check cannot be re-run here (`watchdog_diff` excludes committed ranges). Supervisor should run: `python3 scripts/docs/check_documentation.py`, `python3 scripts/docs/verify_69_ac.py`, `python3 scripts/docs/verify_scoped_srs.py`, and re-render both `.puml` after fixes.

Merge verdict: **OK with notes** — no P0; P1-1 and P1-2 are one-line fixes that should land before UC-05 is signed off.