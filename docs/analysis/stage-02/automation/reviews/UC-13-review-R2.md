## Review

REVIEW_STATUS: PASS
SEVERITY: NONE (no MAJOR/CRITICAL; 4 non-blocking notes)

Scope read: `uc-13/01,02,04,06`, `diagrams/DGM-SEQ-21-hotspots.puml`, `diagrams/generated/*.svg`(+PNG list), `08-self-check.md`, `automation/reviews/UC-13-review-R1.md`, plus contract checks in `srs/modules/analytics-graph.md` (FR13-S04, FR11-S12, BR-515) and `spec/CognitaNexus-Core-SPEC-V1.0.md` §4.2, SRS V2.2 `:378`. No files modified.

**Correct — all six prior findings verified resolved**
- **F1 fixed.** No `FR13-S1x` anywhere under `uc-13/` (grep `FR13-S1[0-9]` → no matches). Real/simulated marker now cites `FR11-S12` (exists: `analytics-graph.md:1244`; consistent with `07-global-model-diff.md:15`) at `01:88` (E3), `01:99` (E14), `01:114` (A6), `02:23`.
- **F2 fixed.** `01:6` now cites **§4.2**; SPEC `:62` is “可执行规则表” and `:70` holds CL-05 (“机构仅通过已审别名字典映射，未知则未映射；领域同理”) — matches the paraphrase used.
- **F3 fixed.** Group labels now `范围判定与事实读取（E1–E5）` / `术语与映射凭证（E6–E7）` (`puml:15,25`), propagated to `04:117` and to both rendered artifacts (`DGM-SEQ-21-hotspots.svg:16,26`). All 6 groups cross-check clean against `04 §3` (G1=M1–M7=E1–E5; G2=M8–M10=E6–E7; G3=M11–M17=E8–E12; G4=M18–M22=E13–E14; G5=M23–M28=E15; G6=M29–M39=E16/A2–A4/A16–A18). No stale `E1–E3`/`E5–E7` string survives.
- **F4 resolved (disclosed reuse path).** `04:66` note: “M29–M39 … 复用 M18 … 与 M1 … 触发路径；不另设 Actor 消息”; `06:21` Do + `06:23` now cite M18/M38/M39, and `01:43`(§3 item 4), `01:120`(A18), `02:59`, `03:21/30`, `puml:68-69` all name the same handoff targets (UC-11/UC-12/UC-14/15). Consistent across 01/02/03/04/06/图.
- **F5 fixed in prose.** `BR-IMP-02/06` now at `01:79`(§5.1.2), `01:143`(BR-UC13-06), `01:118`(A10), `01:126`(A18), `02:29`(E8), `02:44`. Semantically sound: SRS `:378` BR-IMP-06 = “平台实体按去重成果计数…”.
- **F6 fixed.** `01:122` A14 now separates the two rules — display order “计数相同者按规范词升序排列”, rank numbering “名次相同、下一名次按并列数跳号” — matching FR13 明文 + `FR13-S04 A1`/`AC-FR13-S04-02` (`analytics-graph.md:2183,2226`), i.e. the juxtaposition is intentional, not contradictory.
- Rendering claim true: SVG embeds the post-fix group labels; `08:75` records the relabel as “仅缩短标签”.

**Finding (notes, none blocking — P2 only)**
1. `puml` legend “计数单位…（BR-IMP-02；AC-IMPL-FR16-02 边界）” and `04:47` (M11) still omit BR-IMP-06, so diagram/message evidence is narrower than `01:143`/`02:29`. Smallest fix: append `/06` to both.
2. Literal `BR-IMP-06` still appears nowhere in `uc-13/` (grep → no match); it is only the shorthand `BR-IMP-02/06`. Recomputation-by-grep requires expanding to the literal ID at `01:143`/`02:29`.
3. `06:21` Do asserts receiving “越界请求”, but the candidate-operation row `06:23` has no `/接收越界请求` (only `/拒绝越界请求` on Ctl); add one op or drop the claim from Do.
4. `01:139` BR-UC13-07 (rule table) still says only “按规范词升序破并列”, without the 跳号 clause; A14 carries the disambiguation, but a reader of §8 alone could still infer distinct ranks.

**Merge verdict: OK with notes.** All six R1 findings are closed with matching artifacts; residuals are wording/citation hygiene at P2. Suggested re-checks by supervisor: `python3 scripts/docs/check_documentation.py`, `python3 scripts/docs/verify_scoped_srs.py`, `python3 scripts/docs/verify_69_ac.py` (not run here — no shell access). Pre-existing open item from R1 stands: SRS `:378` binds BR-IMP-06 to FR11 while the shared trace row binds it to `AC-IMPL-FR13-01/02` — owner decision, outside UC-13.