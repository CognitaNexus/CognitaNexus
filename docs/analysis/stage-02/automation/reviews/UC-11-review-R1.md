## Review

```
REVIEW_STATUS: PASS
SEVERITY: MINOR
```
Scope read: `REVIEWER.md`; SRS V2.2 FR11/FR16/BR-IMP-06/07/NF02/NF05 (§4, §7, §9, §11, §12); `03-AC-UC-分析模型追踪.md` rows 30/35/38/41/86-87/110-111/115-116/135; all 9 files + both `.puml` + both SVG in `docs/analysis/stage-02/uc-11/`; cross-checked `analytics-graph.md` (BR-511–530, FR11-S08/S09/S10/S12, Q-1/Q-4/Q-6), Core SPEC (INV-02, SR-06, T35, CL-05, ID-05/11, Q-SPEC-03/08), `01-BCE分析类模型.md`, `analysis-class-registry.md`, `unresolved-decisions.md`. Review-only; no files changed.

**Correct (verified, not摘要)**
- Dedup counting: M5/M7 + BR-UC11-01 (01:136…); matches BR-IMP-02/06, `AC-IMPL-FR11-01`, `AC-IMPL-FR16-02`. Source/version/author放大 explicitly rejected (A10/A17, M48).
- real/public/effective/qualified only (BR-UC11-02, M3) and sim/mixed exclusion+label (A6, M31/M33) trace to BR-IMP-07 / `AC-IMPL-FR11-02`.
- occurred-at institution with evidence, no current-affiliation inference (BR-UC11-07, M22, A8) ↔ BR-513, SPEC SR-06/T35; same/cross-institution one-each (BR-UC11-08, M25) ↔ BR-IMP-06; unknown listed separately, excluded from ranking (BR-UC11-09, A7).
- Six-item 范围/口径/更新时间/数据性质 disclosure (BR-UC11-12, M11/M32/M43) ↔ FR11正文、BR-514.
- 49 messages: verified M1–M49 continuous, no dups, senders/receivers match 04 table and both SVG (13+15+5+4+12); VOPC has exactly the 5 message-participating classes, 2 business associations + 3 read-only dependencies, no Actor-as-class.
- No write messages (only M4/M18/M20/M23, all 只读); no quality/impact ranking (M28/M48 ↔ BR-522, SRS §2.1); no DTO/cache/task/DB/API in the model — those appear only in exclusion lists.
- C-02 handling is correct: `FR11-S08 E3`/`AC-FR11-S08-02` require Q-4 frozen, but baseline BR-IMP-06 + `AC-IMPL-FR11-01` already settle multi-institution counting; package follows the authoritative baseline and registers the conflict instead of silently overriding it (07 §5/§6, 08 §6).
- NF02 correctly not claimed (trace rows assign NF02 to UC-01/02/15 only; 08 §5 marks it NOT RUN), and `AC-IMPL-NF05-01` (统计 named in text) is offered only as supplementary evidence while trace lines 115-116 omit UC-11 — flagged for parent in 07 §6, not silently edited.

### Findings (all MINOR / report-only)

**UC11-R1-01 (MINOR) – 模拟/混合 未要求分别标识（Q/R）**
Evidence: `AC-IMPL-FR11-02` requires 演示模式必须标识 Q/R（模拟/混合两类）；BR-IMP-07 requires 真实/模拟/混合互斥标识；FR16 requires 真实/模拟/混合分组. Docs collapse them into one label: 01:73, 01:101, 01:115 (A6), 01:136 (BR-UC11-03), 04:57/puml:56 (M31) — “模拟/混合” single label, no requirement that 模拟 and 混合 be distinguished, and no 构成比例 when scope=全部 (module FR11-S12 A1).
Minimal fix: append one clause to BR-UC11-03 and A6/M31/M33: “‘模拟’与‘混合’须分别标识/分组，不得合并为单一‘演示数据’标签；范围选‘全部’时给出三类构成数量”。

**UC11-R1-02 (MINOR) – 顺序图分支消息无守卫/触发**
Evidence: M16/M17 sit inside `alt`, but the trailing group (puml:63–81) contains M38–M49 as free-standing self/return messages with no `alt`/`opt` and no triggering message, so A2–A4/A11/A15–A19 read as unconditionally executed after E14 (and A2 logically belongs right after M4/M5).
Minimal fix: wrap that group in `alt`/`opt` per A-ID (or add one trigger message); no renumbering needed.

**UC11-R1-03 (MINOR) – 悬空/错指待确认编号**
Evidence: 03:32 cites `B-10`, which is defined nowhere (01 §11, 08 §6 list only B-01～B-09, C-01/C-02); 03:81 cites “B-09 类比” (B-09 = 占比分母, unrelated); 01:104 says “B-01～B-08” while B-09 exists.
Minimal fix: define B-10 (StatisticsUI/AnalyticsUI 命名合并) or delete the reference; 03:81 → cite B-08/no ref; 01:104 → B-01～B-09.

**UC11-R1-04 (MINOR) – 机构归属是否要求关系已核验 未登记**
Evidence: 06:68 says this open item is “登记于 08 §6”, but 08 §6 has no such row; the corresponding open question already exists as SPEC Q-SPEC-08 (“重复实体计数、待核验作者关系参与机构排名与合作网络资格”).
Minimal fix: add B-10/B-11 row in 01 §11 and 08 §6 citing Q-SPEC-08, or reword 06:68.

**UC11-R1-05 (MINOR) – §11.4 引用不支持其主张**
Evidence: 02:53 (A16) and 08:38 cite SRS §11.4; V2.2 §11.4 (SRS line 492) only states FR09/FR15 responsibility split and FR06/FR15 actor scope — it says nothing about 拒绝质量排名 or FR11 participants. The actual basis is §2.1 (SRS:36) + BR-522.
Minimal fix: replace `§11.4` with `§2.1`/`§8` in those two rows (and drop it from the header lists at 01:4, 02:5, README:4 if used as evidence only).

**UC11-R1-06 (MINOR) – 导出图与图源不一致**
Evidence: `diagrams/DGM-SEQ-19-statistics.puml:19/…` group titles use en dash `（E1–E5）`/`（A2–A4、A11、A15–A19）`, while the rendered `generated/DGM-SEQ-19-statistics.svg` (text + embedded source) shows `（E1-E5）`/`（A2-A4、A11、A15-A19）`; no en dash occurs anywhere in the SVG. Content/IDs identical, but 08 §4.1 asserts the exports correspond to the submitted source.
Minimal fix: re-run `plantuml -charset UTF-8 -tsvg/-tpng -o generated …/DGM-SEQ-19-statistics.puml` (and refresh 08 §4.1 dimensions if changed). VOPC SVG matches its `.puml`.

**UC11-R1-07 (MINOR) – 证据等级标注不一致**
Evidence: 02 §1 defines 已证实 = 正式 SRS/AC/BR/SPEC/S01；but A5 (FR11-S12 E1), A11 (FR11-S04-03), A14 (BR-520/FR11-S10) are graded 已证实 on module-only sources, while the same class of source for A13 is graded “细化（模块）＋分析推导”.
Minimal fix: regrade A5/A11/A14 (and BR-UC11-04/11 sources) as “细化（模块）” or add baseline basis.

### Notes for parent (not UC-11 defects)
- Shared `03-AC-UC-分析模型追踪.md` FR11 rows (86-87) still show `DGM-USECASE-02 / CLASS-01`; UC-11 correctly recommends adding `SEQ-19 / VOPC-15` (07 §6) rather than editing shared files.
- NF05-01 text names 统计, but trace lines 115-116 list only UC-08/14/15 — shared-file gap already flagged in 07 §6.

Verification I could not run (no shell): `verify_69_ac.py`, `check_documentation.py`, and the §4.4 message-consistency script; supervisor should run all three. Manual re-derivation of message counts/senders in both `.puml` and SVG matched the documented 49/5.

**Merge verdict: OK with notes** — `AUTO_REVIEW_PASS` (no MAJOR/CRITICAL; MINOR items 01–07 are small text/render fixes).