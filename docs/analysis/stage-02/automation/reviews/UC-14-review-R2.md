## Review

```
REVIEW_STATUS: ISSUES
SEVERITY: MAJOR
```

Scope: `docs/analysis/stage-02/uc-14/` (01, 02, 04, 06, 07, README, both `.puml`, regenerated SVGs) against `docs/analysis/stage-02/automation/reviews/UC-14-review-R1.md`. No files modified.

### Correct (fixes verified)

- **P1 time predicate — partially fixed.** `01:102` (E5) now reads "时间未知不因时间被排除建边，仅当已声明时间范围时不命中该范围并披露（A7；B-03）"; `01:151` (BR-UC14-02) now "公开、生效、真实；时间未知不排除建边"; `04:34` (M7) and `diagrams/…puml:28` carry "时间未知不因此排除". Consistent with `01:131` A7 and SPEC §4 `BUILD`.
- **P2 attribute provenance fixed.** `06-class-definition.md:53` now says "本 UC 提议规范名 `canonicalWorkId`/`workType`/`dataOrigin`/`occurringTime`，与登记表 `stableId`/`type`/`year` 的别名统一留 R5（D-11）"; no longer claims inheritance of non-existent names.
- **P2 GraphCtl/01 §3 split fixed (partially).** `07:47` now reads "`GraphCtl` 未写该守卫；`01` §3 已写"同机构/同领域不建边"，但未写"相似姓名不建边"与反例披露" — matches `01-BCE分析类模型.md:42`.
- **P2 citation split fixed in 01.** `01:150` (BR-UC14-01) now splits "FR12 明文（同机构/同领域/相似姓名）；§12（账号与普通主页）；同一来源不建边为分析推导"; `README.md:37` dropped "FR12 明文".
- **P2 M21 outcome fixed.** `04:49` M21 now lists "（已形成／无合作关系／失败／未更新／规模超限／拒绝）", matching `06:38` `outcome`.
- **Render integrity.** SVG viewBox changed `0 0 3114 5257` → `0 0 3114 5360` and `svg:29`/`svg:55` contain the new M7/M21 text, so the SVG is a fresh render, not stale.

### Findings

**Finding: P1 — the time-predicate fix is incomplete; five sites still assert "时间可定位" as a qualification guard, including the diagram legend that the same figure's M7 now contradicts.**
Evidence (all current, samesession greps):
- `diagrams/DGM-SEQ-22-collaboration-network.puml:105` — "建边资格分两层：成果资格（公开、生效、真实、**时间可定位**）…" and the regenerated `diagrams/generated/DGM-SEQ-22-collaboration-network.svg:106` mirrors it verbatim. This is the same figure whose `puml:28`/`svg:29` M7 now says "时间未知不因此排除" → internally contradictory artifact.
- `README.md:37` — "成果须公开、生效、真实、时间可定位" (was named in R1 as a P1 location).
- `04-sequence-messages.md:92` — E5 row guard "四类成果∧公开生效∧真实∧**时间可定位**" (R1-named location).
- `06-class-definition.md:135` — §10 mechanism "成果须公开/生效/真实/**时间可定位**" (R1-named location).
- `02-event-flow-trace.md:25` — E5 trace "只有公开、生效、真实、**时间可定位**者参与".
Contract contradiction unchanged from R1: FR12 (SRS:251), `BR-IMP-01`, `AC-IMPL-FR12-01/05` and SPEC `FR12-IN01` §4 carry no time predicate; B-03 is still open (`01:208`, `08:177`). Smallest fix: replace "时间可定位" in those five strings with "时间未知不排除；仅当声明时间范围时不命中并披露（A7；B-03）", then re-render `DGM-SEQ-22` (legend and M7 must agree).

**Finding: P2 — `07:103` §5 still asserts the reverse of the corrected §3 claim.**
`07-global-model-diff.md:103` says `01-BCE` "…未写"同机构/同领域不建边"", contradicting `07:47` and `01-BCE:42` ("同机构/同领域不建边"). Smallest fix: drop that clause from the §5 row.

**Finding: P2 — citation over-attribution survives in `03`.**
`03-bce-identification.md:15` (§1.7) still cites "（FR12 明文；AC-IMPL-FR12-04）" for "同机构、同领域、相似姓名、**同一来源**或**账号资料**". Smallest fix: same split as `01:150`.

**Finding: P2 — E20 inbound path still absent, and `puml:28` M7 drops the window qualifier.**
`04:82` retains "不另设 Actor 消息"; `04:78–80` M51–M53 have no inbound actor message for an out-of-scope query. `puml:28` says only "（时间未知不因此排除）", omitting the "仅当已声明时间范围时不命中并披露" clause present in `04:34`.

### Merge verdict

**BLOCK** — one MAJOR (P1) residual: the "时间可定位" guard survives at the five sites above, and the regenerated SEQ legend contradicts its own M7, so the P1 fix cannot be accepted as complete. Three P2 wording items are report-only. Supervisor re-check after the edit: `plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-14/diagrams/DGM-SEQ-22-collaboration-network.puml` and `python3 scripts/docs/check_documentation.py` (not run by me).