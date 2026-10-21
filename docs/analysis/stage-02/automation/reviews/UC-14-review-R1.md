## Review

```
REVIEW_STATUS: ISSUES
SEVERITY: MAJOR
```

Scope: `docs/analysis/stage-02/uc-14/` (README, 01–08, both `.puml`, both rendered `.svg`/`.png`), read against SRS V2.2 FR12/FR15/FR16/NF01/NF05/BR-IMP-01/02/03/07/08/10/11/12, `docs/spec/FR12-IN01-合作图与最短路径规范.md` §1–§5, `CognitaNexus-Core-SPEC-V1.0.md` INV-05/Q-SPEC-04, `analytics-graph.md` FR12-S01～S09/BR-514～530, trace rows 38–42/65–66 of `03-AC-UC-分析模型追踪.md`. No files modified.

### Verified correct (evidence)

- **Four work types build edges with qualified roles**: `01` E5/E10, `04` M7/M14, `A16`, `BR-UC14-15`, SEQ legend "四类成果同等参与：论文…专利…项目…获奖…"; matches FR12 §正文 (SRS:251) and AC-IMPL-FR12-01/05. `05` §2.3 keeps `Authorship.roleKind` as the four-kind carrier; `Edge` stores no role, so role cannot silently narrow to papers.
- **Only real/public/qualified works**: `M4` (公开/生效/真实, 缺标识不计入真实), `M7`; BR-IMP-01/07 correctly cited.
- **Same institution/field/name never creates edges**: `M13` + `A10/A11/A19` + `BR-UC14-01/20`; `05` §2.3/§5 and `06` §8 explicitly delete `Edge→Institution`, `Edge→Field`, `Scholar→Institution`; FR12 明文 "仅同机构、同领域或相似姓名不产生合作边" honoured.
- **Canonical pairing**: `M11–M14` use only `canonical_id`, min/max edge key, merge resolution with loop/invalid-target exception, self-loop forbidden — matches SPEC §2 verbatim.
- **One contribution per deduplicated work per pair, no cross-source accumulation**: `M17`, `A12/A13/A14`, `BR-UC14-03/04`; consistent with SRS §7.2 ("重复来源不增加统计或图关系强度") and AC-IMPL-FR12-02/03, and with SPEC §2 `edge.weight = distinct work count`.
- **Strength = evidence count; every edge traceable**: `M18`, `M25–M30`, `BR-UC14-05`, VOPC `Edge 0..* -- 1..* Work`; A9 correctly keeps an edge when other qualified evidence remains (SPEC T38 shape).
- **Pending/contested/excluded produce no strength/evidence**: `M10`, `A8/A9`, `BR-UC14-07`; matches SRS §6.4 "待核验/争议/已排除不得成为确定合作边" and §11.3.
- **Base network not paper-only (IN01 boundary)**: `M7`, `A16`, `A18`, `M51`, `06` §9, `07` §3 C-04 (correctly identifies `DGM-USECASE-02-analytics.puml:70` legend conflict and defers it).
- **No shortest path here**: path/BFS objects rejected in `03` §4, `05` §4, `06` §11; per-hop evidence and graph version absent; handoff only in M51–M53.
- **No writes / no implementation design**: only `M5/M8/M11/M27/M39` are reads; `M15` mutates only the in-request derived edge. Grep for 数据库/索引/缓存/API/DTO/接口/图数据库 in the package returns only exclusion statements.
- **Message/VOPC consistency**: `.puml` has M1–M53 (4+3+3+3+5+4+8+6+6+6+5 = 53, no dups), 7 lifelines, 14 Coordinator self-messages exactly as listed in `05` §1; senders/receivers of M1–M53 match `04` §2 row-by-row on spot-check; VOPC has the same 6 internal classes and the 4 business associations `0..*:2`, `0..*:1..*`, `0..*:1`, `0..*:0..1` claimed in `05` §2/`06` §8.
- **Render/export integrity**: `generated/` contains SVG+PNG for both diagrams; SVG viewBox `0 0 3114 5257` and `0 0 1409 1650` match `08` §4.1 claims; SVG embedded source strings equal the current `.puml` text (e.g. M20 "D-08 待确认", legend "1 万节点/10 万边"), so exports are not stale.
- **Citation spot-checks all resolve**: `AC-IMPL-FR16-02` (:319), `IN01-R1/R2/R3` (:327/:329/:331), `AC-IMPL-IN01-01～04` (:335–341), `AC-IMPL-NF01-01` (:412), `BR-IMP-12` (:384), `INV-05`/`Q-SPEC-04` (SPEC:45/:370), FR12-S03 A1 (:1491), FR12-S06 收窄"加领域或机构条件" (:1694), FR12-S07 两选一 (:1749/:1762), BR-514–530 (:3419–3433), D-07/D-08/D-11, registry `Authorship` row, `01-BCE` AnalyticsUI/AnalyticsNetworkCoordinator, `DGM-CLASS-01:139–141` GraphCtl note, trace rows 38–42/65–66.

### Findings

**Finding: P1 — Unconfirmed time range is baked in as an unconditional edge-building prerequisite.**
Location: `01-UC-14-use-case-spec.md:102` (E5), `:151` (BR-UC14-02), `:47` section header of 06 §10 (`06-class-definition.md:135`), `04-sequence-messages.md:34` (M7) and `:92`, `diagrams/DGM-SEQ-22-collaboration-network.puml:28` (M7) and `:103` (legend), `README.md:37`.
Evidence: these state "只有公开、生效、真实（默认）**且时间可定位**的成果进入建边候选" as a qualification guard, citing FR12 明文/BR-IMP-01/AC-IMPL-FR12-01/05 — none of which mentions occurrence time. It contradicts the package's own `01:54` ("已核验发生时间（可未知）") and `01:131` (A7: exclusion only "若声明时间范围"), and it contradicts the governing SPEC: `BUILD` in `FR12-IN01-方案` §4 selects "qualified real public papers and eligible authorships" with no time predicate, and §3 says unknown 机构/领域 merely "不命中特定筛选". The package itself flags the underlying condition as unconfirmed (`01:208` B-03, `08:177`), so an unconfirmed rule is promoted to a hard guard. Consequence if B-03 is rejected: any patent/project/award with unknown 申请/起止/年度 date permanently loses its edge, contradicting AC-IMPL-FR12-01 (甲丙 patent edge must exist) and FR12 "四类同等建边".
Smallest fix: in E5, M7, BR-UC14-02, 06 §10 and the SEQ legend replace "且时间可定位" with "；时间未知不因时间被排除建边，仅当已声明时间范围时不命中该范围并披露（A7；B-03）", keeping the existing `待确认 B-03` marker; re-render the two SEq/legend targets.

**Finding: P2 — `06` §4 claims inherited `WorkEntity` attributes that are neither inherited nor agreed.**
Location: `06-class-definition.md:53` ("沿用 UC-01/UC-02/UC-11/UC-12/UC-13：`canonicalWorkId`、`workType`、`publicState`、`dataOrigin`、`occurringTime`…").
Evidence: `docs/analysis/stage-02/automation/analysis-class-registry.md:10` lists `WorkEntity` attributes as `stableId, publicState, type, year, field, occurredInstitution, searchableText` — no `canonicalWorkId`/`workType`/`occurringTime`; grep across `docs/analysis/stage-02` shows these names appear only in UC-14. And `07-global-model-diff.md` §2/§6 says "全局 `Work` 无数据类别属性" and recommends R5 补 `dataOrigin`, contradicting "沿用 … dataOrigin".
Smallest fix: change the row to "本 UC 提议规范名（`canonicalWorkId`/`workType`/`dataOrigin`/`occurringTime`），与登记表 `stableId`/`type`/`year` 的别名统一留 R5（D-11）；`publicState`/`sourceRefs`/`versionRefs` 沿用"。

**Finding: P2 — `07` asserts the global BCE model lacks a guard it actually contains.**
Location: `07-global-model-diff.md:44` ("同机构/同领域/相似姓名不建边 … `GraphCtl` 与 `01` §3 未写该守卫") and the `01-BCE` row of §5.
Evidence: `docs/analysis/stage-02/01-BCE分析类模型.md:42` reads "`AnalyticsNetworkCoordinator` | 四类合格基础图、论文子图、筛选/BFS/等长规则 | 同机构/同领域不建边；非论文边不进IN01；超限拒绝".
Smallest fix: keep the gap claim only for `GraphCtl` and for "相似姓名不建边 / 反例披露", and note `01` §3 already carries "同机构/同领域不建边".

**Finding: P2 — Exclusion list over-attributed to "FR12 明文".**
Location: `01-UC-14-use-case-spec.md:150` (BR-UC14-01), `README.md:37`, `03-bce-identification.md:15`(§1.7).
Evidence: these cite "FR12 明文" for "同机构、同领域、相似姓名、**同一来源**或**账号资料**" all not creating edges. FR12 (SRS:251) lists only 同机构/同领域/相似姓名; 账号/普通主页 is supported by §12 (`SRS:506`) not FR12; "同一来源不产生边" has no baseline text (only BR-IMP-02/§7.2 remove source-count inflation, which is a strength rule, not an edge-existence rule).
Smallest fix: re-split the citation as "FR12 明文（同机构/同领域/相似姓名）；§12（账号与普通主页）；同一来源不建边为分析推导", as `02` already does for other 推导 items.

**Finding: P2 — Alternative-flow display closure gaps in the sequence.**
Location: `04-sequence-messages.md:22` (M3), `:38` (M21), `:99` (M51–M53), `.puml:17/:59/:97–101`.
Evidence: (a) A1 (`01:127`) requires a rejection prompt "数据类别无效／时间范围无效", but A1 maps only to Coordinator self-message M3 and no returning/display message, unlike A2/A3 (M50), A17 (M20→M21) and A18 (M52/M53); (b) M21 enumerates outcome "（已形成／无合作关系／失败／未更新）" while `06` §3 `outcome` also contains 规模超限/拒绝; (c) E20's visitor request has no actor message — M51–M53 hang off "复用 M1/M43 的触发路径", which is defensible for A2/A3 but not for an out-of-scope query that is not a view-condition change.
Smallest fix: add one actor→UI message ("访客请求最短路径/机构-领域筛选/排名（E20）") before M51, add "／规模超限／拒绝" to M21's outcome list, and extend the note under `04` §2 so A1's rejection reuses the M21/M50 return path; then re-render SEQ (rendered SVG currently mirrors the same text).

### Merge verdict

**OK with notes.** No P0; P1-01 must be fixed before the package is treated as frozen analysis (it changes which AC-IMPL-FR12-01 edges exist and conflicts with A7/SPEC ℹ4), the four P2s are local wording/citation corrections. Per `REVIEWER.md` ("PASS 仅表示没有 MAJOR/CRITICAL") this artifact cannot be marked `AUTO_REVIEW_PASS` until P1-01 is corrected; overall conclusion `REVIEW_BLOCKED` for that single item only. Supervisor re-check command after the fix: `plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-14/diagrams/DGM-SEQ-22-collaboration-network.puml` plus `python3 scripts/docs/check_documentation.py`.