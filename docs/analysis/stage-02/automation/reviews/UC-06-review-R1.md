## Review

```
REVIEW_STATUS: ISSUES
SEVERITY: MAJOR
CONCLUSION: REVIEW_BLOCKED (4 localized fixes; no rewrite needed)
```

**Scope read (unmodified):** `automation/instructions/REVIEWER.md`; SRS V2.2 FR05 L145–157, FR04 L131, FR14 L281, FR15 L293–307, NF03 L426, NF06 L448, §6.3 L359, §8 L40, §12 L504, BR-IMP-01/05/10/11 L373/377/382/383; SPEC L45 (INV-10), L187/188/193 (ID-06/07/12), L324 (T26); `docs/uml/claim/DGM-STATE-02-claim-request.puml`; trace rows `03-AC-UC…md` L64–67/112/117/130; all 9 `.md` + 2 `.puml` + 4 generated files in `uc-06/`; cross-checked `uc-05/01` L69–92, `uc-05/04` M15–M22, `DGM-CLASS-01-analysis.puml`, `01-BCE分析类模型.md`.

### Correct (verified)
- **62 messages complete and traceable.** `DGM-SEQ-11…puml:19–103` contains M1–M62 contiguous, no gaps/duplicates; `04` §2 gives sender/receiver/event-step/source for each; every E1–E12 and A1–A8 has ≥1 message (A9 is note-only, see P2-1).
- **BCE genuinely derived from the event flow**, not copied from the global graph: `03` §2 C1–C8 each cite 发现事件; `03` §4 rejects UserProfile/Authorship/Work/Version/Source/ConflictQueue/Notification/Role with reasons. 8 lifelines (`puml:12–22`) = 8 VOPC classes (`VOPC:15–26`) = 8 in `06`.
- **Approval grants target-scope Auth only; no second profile, no Authorship.** M27 "被授权=申请人；目标=该 Scholar/门户；仅此范围"; zero messages to `UserProfile`/`Authorship`/`WorkEntity` (verified by grep over both `.puml`); M34 boundary assertion. Matches AC-IMPL-FR05-02/04 (L153/157) and SPEC ID-07 (L188).
- **Conflict preserves original Auth.** M15/M16 read-only, A5 → M23/M25 "原授权不变", no revoke/transfer message. Matches AC-IMPL-FR05-03 (L155), BR-IMP-05 (L377), SPEC ID-12 (L193).
- **Audit.** Write paths M24/M29/M33/M37/M41/M49/M53 + read M60; content = 决定/操作者/对象/时间/前后值/理由 per FR14 L281 and INV-10 (SPEC L45).
- **No UC-05/07/08 creep, no implementation design.** `01` §1, `02` §5, `03` §4, `05` §4, `06` §10 exclude submit/self-query, archive maintenance, Authorship; attributes stay business-level (`materialRef`/`visibilityScope` declared non-storage, `06` §1); no DB/API/framework tokens anywhere.
- **Citations accurate.** I re-derived `07`'s global-model line refs from `DGM-CLASS-01-analysis.puml`: ClaimUI L15, ClaimCtl L29, User L49, Scholar L58, Claim L62, Auth L66, Evidence L90, ClaimCtl..>Auth L109, User–UserProfile L116, User–Claim L117, Claim–Scholar L118, Claim..>Auth L119, Auth–Scholar L120, Evidence..>Claim L126 — all correct. No invented AC IDs (only FR05-01..04, FR04-02, FR14-02, FR15-03/06, NF03-01, NF06-01, all exist).
- **Additions only.** `watchdog_diff`: all 16 `uc-06/*` files untracked; no tracked edits to SRS/SPEC/global UML by this package (other tracked changes are pre-existing, unrelated).

### Findings

**P1-1 (MAJOR) — `待补充` is admitted into the decision branch, producing state edges that `DGM-STATE-02` and SRS §6.3 forbid.**
`puml:28` M8: "判定申请是否仍为**待审核/待补充**且版本未变"; `puml:29–32` `else 申请状态有效` then flows to M23/M32/M36/M40. `DGM-STATE-02:14–19` defines only `Supplement→Pending`; SRS §6.3 L359 states `待审核→待补充→待审核` **or** `待审核→已通过/已驳回/争议`. The package itself says otherwise (`01` §9 note: "待补充申请先经 A4 放行回 `待审核` 再作决定"; `04` §3.4 labels every decision `待审核→`; `08` §3 asserts only those transitions). It is reachable: UC-05 creates `待补充` at submission (`uc-05/04` M15, A1b) and `01` §4 前置条件 2 admits `待补充`.
*Fix:* change M8 to "仍为 `待审核`；若为 `待补充`，须先经 A4 放行回 `待审核`（否则转 A4）" in `puml:28` and `04` §2 M8 row; keep `01` §9 unchanged.

**P1-2 (MAJOR) — A4 (补证复核, M44–M55) writes `待补充→待审核` with no status/version guard.**
`01` §9 assigns that edge to this UC (A4a), and BR-UC06-02 / AC-IMPL-FR05-02 require that stale decisions not be overwritten, yet the guard is applied only at M20 for the decision segment. M45→M46 (evidence) →M47 (judgement) →M48 (write `待审核`) never re-reads the application's state/version, so a stale 复核 could regress an already-decided application (e.g. `通过→待审核`), contradicting `DGM-STATE-02` legend "审核必须校验当前版本和权限".
*Fix:* insert a guard before M47/M48 — "复核前校验申请仍为 `待补充` 且版本未变，否则按 A2 拒绝并要求刷新" — in `puml:82–85` and `04` §2 (new row or M47 amendment); mirror in `04` §3.4.

**P2-1 — A9 is note-only and unreachable as drawn.** `puml:60–61` writes status `通过` (M32) *before* audit (M33), so an audit failure leaves an effective but unaudited decision, contradicting `01` §5.5.5 and BR-UC06-10 ("审计失败…分别返回明确结果") when only a note exists (`puml:105–107`).
*Fix:* annotate the 批准 alt that audit (M33-class) commits with/ before the status write and that failure returns to the original state, or narrow BR-UC06-10 to the four modeled outcomes.

**P2-2 — mislabeled alt.** `puml:46` outer `else 批准（E8–E11）` encloses 驳回 (M36–M39), 要求补证 (M40–M43) and 争议 (M23–M26), contradicting `04` §1 which lists them as separate alternatives. *Fix:* relabel to `决定有效（E8–E11）`.

**P2-3 — garbled message text.** `04` §2 M62: "不返回材料原文**以外**的私有内容" reads inverted. *Fix:* "仅显示决定与审计摘要，不显示材料原文等私有内容".

**P2-4 — SPEC T26 not acknowledged.** SPEC L324 names 冲突队列 as the competing-claim mechanism, while `03` §4 / `01` §1 / C-05 describe the carrier as unfrozen. *Fix:* cite T26 in `07` §5 and scope C-05 to 结案/申诉/转移 only.

### Verification not performed by me
`08` §4.2/§4.3 outputs (`check_documentation.py` `relative_links: 1442`, `verify_69_ac.py` PASS, `verify_scoped_srs.py`) were not re-run; I did confirm all `uc-06` relative links resolve to existing files and that the SVGs contain the full content (M62, A9 note, legend; 3400×4667 and 2729×1684, matching `08`'s stated sizes). Supervisor should run `python3 scripts/docs/check_documentation.py`, `verify_69_ac.py`, `verify_scoped_srs.py`.

### Merge verdict
**BLOCK** — P1-1 and P1-2 are two localized text/guard edits in `DGM-SEQ-11…puml` + `04`; P2-1..P2-4 are wording/note fixes. Nothing requires rework of the BCE/VOPC structure, and the FR05/FR15/NF03/NF06 boundaries (no second profile, no Authorship, conflict preserves Auth, audit, object-scope Auth) are already correct.

*(Note: `REVIEWER.md` would have me write this to `automation/reviews/UC-06-review-R1.md`; the explicit no-modify instruction takes precedence, so it is returned here.)*