## Review

REVIEW_STATUS: ISSUES
SEVERITY: MINOR

Scope read: `REVIEWER.md`, `AGENTS.md`, SRS V2.2 (FR13, FR16, NF01/02/05, BR-IMP-01…11, §2.1, §7.1/§7.2, §8), trace rows `03-AC-UC-分析模型追踪.md:93-94,137`, SPEC §4.1/§4.2/§4.4, INV-01…09, Q-SPEC-03/04, module `analytics-graph.md` FR13-S01…S09/BR-5xx, and all 8 files + both `.puml` + both `.svg` under `docs/analysis/stage-02/uc-13/`. No files modified (review-only).

### Findings

**F1 — P1 — citation to a non-existent refinement ID `FR13-S12`**
- Evidence: `01-UC-13-use-case-spec.md:88` ("模块细化 FR13-S03/S12 邻近（细化）"), `:99`, `:114`; `02-event-flow-trace.md:23`. `analytics-graph.md` defines only `FR13-S01`～`FR13-S09` (grep `FR13-S1[0-9]` → no match). The real rule for the real/simulated marker is `FR11-S12` (correctly cited as "FR11-S12/S03" in `07-global-model-diff.md:15`; `analytics-graph.md:156` "C-3 … 影响 BR-515").
- Impact: E3/E14/A6 evidence points at a rule that cannot be checked; weakens exactly the "only public real data counts" trace the review asks for.
- Minimal fix: in `01:88`, `01:99`, `01:114`, `02:23` replace "FR13-S03/S12" with "FR13-S03 E2；FR11-S12". No semantic change.

**F2 — P2 — SPEC section mis-cited for CL-05**
- Evidence: `01-UC-13-use-case-spec.md:6` cites "§4.3（CL-05 …）". CL-05 is defined in SPEC `§4.2 可执行规则表` (`CognitaNexus-Core-SPEC-V1.0.md:59-70`); §4.3 is "算法步骤与伪代码" (`:79`).
- Minimal fix: change `§4.3` → `§4.2` in `01:6` (README correctly cites CL-05 without a section number).

**F3 — P2 — sequence group labels misstate which events they contain**
- Evidence: `diagrams/DGM-SEQ-21-hotspots.puml:15` "范围判定（E1–E3）" actually holds M1–M7 = E1–E5 (E4 read and E5 eligibility guard are inside); `:25` "术语与映射凭证（E5–E7）" holds only M8–M10 = E6–E7 (E5/M7 sits in the previous group). Propagated verbatim to prose: `04-sequence-messages.md:117`.
- Impact: event→group trace shown to reviewers is off by one group; the rendered SVG carries the same labels.
- Minimal fix: relabel to "范围判定与事实读取（E1–E5）" and "术语与映射凭证（E6–E7）" in `puml:15/25` and `04:117`, then re-render the two SVGs/PNGs.

**F4 — P2 — A17/A18 rejection has no inbound request message**
- Evidence: trigger is stated in `01-UC-13-use-case-spec.md:33` ("访客提出越界请求") and as UI responsibility/operation in `06-class-definition.md:21,23` ("接收…越界请求"), but the diagram contains only Ctl self-guard M37 plus returns M38/M39 (`puml:68-70`); no Visitor→UI message carries the request. `04-sequence-messages.md:66` justifies reusing M1/M18, yet `06:23`'s evidence column lists message ranges, not M18.
- Minimal fix (choose one, smallest): set `06:21/23` evidence for "接收…越界请求" to "经 M18 条件变更路径（04 §2 注）" — or add one inbound message (e.g. `M23a 访客→HotspotUI 越界请求`) and cite it in 04/06. Either way the 01 §3 trigger, 06 operation and diagram must agree.

**F5 — P2 — `BR-IMP-06` (计数单位) never cited; rule set diverges from the shared trace row**
- Evidence: `grep BR-IMP-06` in `uc-13/` → no match, while `03-AC-UC-分析模型追踪.md:93-94` maps `AC-IMPL-FR13-01/02` to `BR-IMP-06/07`. The counting-unit rule is grounded only on `BR-IMP-02` + `AC-IMPL-FR16-02` (`01:143`, `02:29`, `puml` legend).
- Minimal fix: add `BR-IMP-06` to `BR-UC13-06` (`01:143`) and to `02:29` (E8) and the legend line in `puml`. Report-only caveat: SRS `:378` lists BR-IMP-06's own 关联 column as "FR11" only, so the trace row and the rule table disagree at baseline level — supervisor/需求方 decision, not fixable inside UC-13.

**F6 — P2 — tie-rule wording is self-ambiguous (ordering vs equal ranks)**
- Evidence: `01:110` A14 "名次相同、下一名次按并列数跳号" and `01:139` BR-UC13-07 "按规范词升序破并列", both cited side by side without saying which governs display when counts tie (FR13 明文 vs 细化 `FR13-S04` A1, `analytics-graph.md:2187`).
- Minimal fix: one sentence in `01` §5.1 item 3 / BR-UC13-07: "排序键＝计数降序＋规范词升序（FR13）；同计数同名次、下一名次跳号（FR13-S04 A1 细化）".

### Verified correct (evidence)
- **AC/event completeness**: both FR13 ACs mapped to concrete events/messages (`01:165-166`), FR16-02 explicitly labelled "边界，部分", NF01/NF05 as 补充证据, NF02 explicitly *not* claimed (`01:167-170`, `08:112`). E1–E16 and A1–A19 each carry a source and an evidence grade (`02:16-60`); "分析推导"/"待确认" are separated (`02:8-14`).
- **BCE derivation**: 4 candidates with event-based discovery + ~20 rejected candidates (`03:13-75`); no actor drawn as class; `Keyword`/`Field`/`SourceRecord`/`WorkVersion` deliberately not classes.
- **Message traceability**: M1–M39 each have sender/receiver/event-step/duty/evidence (`04:16-54`); only three read messages (M5/M8/M25) — no write message, matching `05:52` and the IRead-only claim.
- **VOPC = sequence participants**: 4 classes vs SEQ's 5 lifelines minus the 访客 actor, explicitly justified (`05:15-21`, `05:47`); 1 business association `0..*`:`0..*` with per-end rationale and "same paper/same canonical term counts once is a rule, not a multiplicity" (`05:36-42`); control/boundary links left without forced multiplicity (`05:30`).
- **Counting qualification**: scope restricted to public/effective/real papers + registered topics (`puml:20-24`, `01:88,92`); patents/projects/awards and title/abstract inference explicitly excluded (`01:150`, `03:85`).
- **Synonym merge only with evidence**: `01:91-92,120`, guards M8–M10, no dictionary/model class (`03:64`, `05:57`).
- **Tie-break count desc → canonical asc**: `01:103,119` (E9/A14), `01:139`, M12, VOPC legend — matches FR13 wording verbatim.
- **Disclosure set** (field source, year window, scope+coverage, data category, sample size in/out, evidence source, tie rule, update time): M15/M16/M34 with `AC-IMPL-FR13-01` (`01:62,142`).
- **Sparse/no-keyword**: 无数据/样本有限 with actual counts, no placeholders/interpolation/extrapolation (M13, M29/M30, A15, BR-UC13-09) against `AC-IMPL-FR13-02`.
- **No semantic/trend/quality conclusions**: M17 fixed statement, M37 rejection, `03:65`, `06:118-121`; module `FR13-S05` trend requirement registered as conflict C-02 instead of silently implemented (`07:93`).
- **No implementation design**: no table/API/queue/index/cache/DTO; DTOs and aggregation engines explicitly rejected (`05:58-60`, `03:70`).
- **Rendering claims are true**: both SVGs are genuine PlantUML 1.2020.02 output with embedded source (`DGM-VOPC-…svg` line 1 + trailing `@enduml` block; `DGM-SEQ-…svg` contains all M1–M39 and the six groups). Residual en-dash→"-" and "-->"→"- ->" differences are PlantUML embedded-source artifacts, not a stale export (M6 shows the post-fix "／" text).
- **Refinement citations spot-checked OK**: `FR13-S01 E1`, `FR13-S02 E1/E3`, `FR13-S03 E2`, `FR13-S04 A1`, `FR13-S07 A1/AC-FR13-S07-02`, `FR13-S08`, `FR13-S09 E1/E3`, `BR-520`; SPEC §4.1 (关键词/领域 可选字段 `:55`), §4.4 (`:100`).

### Open requirement question (not fixable in UC-13)
BR-IMP-06's scope: SRS `:378` binds it to FR11, while the shared trace row binds it to `AC-IMPL-FR13-01/02`. Needs owner clarification before F5 is closed.

**Merge verdict: OK with notes** — no P0/MAJOR finding; the analysis conclusions hold. F1 (citation) should be corrected in the same revision before this package is cited as evidence; F3/F4/F6 are wording/label fixes; F5 is fix-plus-requirements-question.