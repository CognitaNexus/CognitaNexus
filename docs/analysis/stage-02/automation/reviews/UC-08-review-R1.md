## Review

**REVIEW_STATUS: ISSUES**
**SEVERITY: MAJOR**

**Scope / read:** `docs/analysis/stage-02/uc-08/` {01,02,03,04,05,06,07,08,README} + `diagrams/DGM-SEQ-13-authorship-correction.puml`, `DGM-VOPC-09-authorship-correction.puml` + both generated SVGs; SRS V2.2 FR07/FR12/FR15/NF03/NF05 AC text (lines 179–190, 249–263, 291–307, 422–442, §11.4), `03-AC-UC-分析模型追踪.md` rows 26/41/73–75/97–102/112/115–116/132, module SRS `scholar-disambiguation.md` (BR-352/353/356/359/381, FR15-S09/10/11). `automation/instructions/REVIEWER.md` does not exist at the task path; the method file lives at `docs/analysis/stage-02/automation/instructions/REVIEWER.md` and was used instead.

### Correct (verified)
- **Message/AC traceability:** `M1–M76` each appear exactly once in the puml (all 76 labels present, including return `M48`), and every row of `04` §2 has sender/receiver/step/依据. AC mapping in `01` §10 matches the trace file: FR07-01↔M67/M68, FR07-02↔M40/M43/M73–M76, FR15-04↔A3/M16–M18, FR15-06↔M15/M27/M28, NF03-01↔A1/A2/A10, NF05-01/02 write-side only (correctly marked NOT RUN).
- **Own-Authorship-only vs shared-field review:** M6/M7 ownership guard, M30/M31 (A4) reject others' relations, shared fields + 误归属 routed to `CorrectionRequest` (M34→M44) before any effect; matches FR07 "只修改自己的参与关系…共享字段和争议归属进入审核".
- **Removal keeps work/sources/others:** M67 (only该关系→已排除), M68 "成果实体、全部来源与其余关系保留", M71; `01` §5.3.2 adds "该学者门户不再展示…其他学者门户不受影响" — consistent with AC-FR15-S11-01/02 and BR-359 (`scholar-disambiguation.md:1042`).
- **No excluded-restore / no publishing:** A3+M16–M18 reject 已排除/争议; A13 bounded; A14 + no create/delete messages for `WorkEntity`/`SourceRecord` (`04` §4) satisfy FR07 "不提供论文投稿或出版" and AC-IMPL-FR07-03's negative half.
- **No UC-06/07/09/10 creep / no implementation design:** `Auth` read-only (M4 only), `ClaimApplication`/`ScholarChangeRequest`/`IdentityCandidate`/`CollaborationEdge` rejected (`05` §4); grep for 数据库/API/Java/队列 found only disclaimers.
- **Citation spot-checks are real:** FR15-S10 A3 (`:914`) and AC-FR15-S10-05 (`:966`) do support M63/M69/M70/M74; BR-352/353/356/359 exist.

### Findings

| ID | Sev | Location | Evidence | Minimal fix |
|---|---|---|---|---|
| R1 | **P1** | `DGM-VOPC-09…puml:27,31`; `05-vopc.md:34,38`; `06-class-definition.md:172,176`; `08-self-check.md:85` | Docs specify `..>` dashed read-only for `Ctl→Auth` (M4) and `Ctl→Source` (M35) ("虚线表示只读", "虚线只读依赖"). The puml uses solid `-->`, and the generated VOPC SVG contains **no** `stroke-dasharray` at all (case-insensitive grep: no matches; the SEQ SVG does have dashes). `08` §4.1 claims a visually verified "只读虚线与写入实线可区分" — that verification statement is false. | Change puml:27 and :31 to `..>` and re-render SVG+PNG; or, if solid is intended, rewrite `05:34,38`, `06:172,176`, `08:85` to "实线＋（只读）标签". Do not leave the claim in `08` uncorrected. |
| R2 | P2 | `04-sequence-messages.md:76–82`; `01` §6 (E1–E15), `02` §2 | M45–M51 are tagged event step "E12（事后查询）", but no such step exists: `01:33` lists the query as a trigger, yet neither `01` §6 nor `02` §2 defines a query event. Step reference is therefore dangling (依据 column does carry AC-IMPL-FR07-02). | Add `E16 本人查询本人请求状态与处理过程` to `01` §6 and `02` §2 (source: AC-IMPL-FR07-02 "处理过程可查", NF06, BR-IMP-11) and retag M45–M51 to E16. |
| R3 | P2 | `DGM-SEQ-13…puml:52–56` | M23 (write state) and M24 (audit) are drawn unconditionally, then `alt 确认证据或状态写入失败（A6）` is nested after them — the diagram implies write-then-fail, contradicting `01` A6 "关系状态保持原值；不产生半完成写入" and `04` §3 (A6 guard "写入成功"). | Wrap M23/M24 in `alt 写入失败 → M25/M26 else 写入成功 → M23/M24/M27/M28/M29`. |
| R4 | P2 | `DGM-SEQ-13…puml:39–62` and `:64–86` | After M14 classifies the operation, 确认 and 补录/纠错 are two **sequential** `alt` blocks (no `else`), so the figure reads as "both branch groups execute once each" rather than one exclusive choice. | Merge into a single `alt 确认 … else 补录/纠错 … end` under M14. |
| R5 | P2 | `DGM-SEQ-13…puml:140` | Legend cites "（B-01/B-02/B-06）" for 待审请求状态集合 / 可直写字段清单 / 审核证据最低集合, but `01` §11 defines these as **B-01/B-04/B-05** (B-02 = 确认范围, B-06 = SLA/责任人). | Replace with `B-01/B-04/B-05`; re-render. |
| R6 | P2 | `01-UC-08-use-case-spec.md:171` | `AC-IMPL-FR07-03` is labelled "核心，负向" while the package only supplies negative evidence ("不新建 WorkEntity/Authorship"); the AC's observable (主页/成果分区空态) belongs to UC-03 (`01` §1 只读边界; `05` §4 excludes `UserProfile`). Inconsistent with the "边界，反向证据" labelling used for FR15-01/03. | Relabel to "边界，负向证据" and state the empty-state display is UC-03's. |
| R7 | P2 | `01-UC-08-use-case-spec.md:52` | §5.1.5 says the relation "由 `待核验` 进入 `本人确认`", but §9 (Authorship row) and `02` E7 / `DGM-STATE-05` allow `自动关联 → 本人确认` as well. | Rewrite as "由 `自动关联/待核验` 进入 `本人确认`（确认范围条件见 B-02）". |
| R8 | P2 | `DGM-VOPC-09…puml:22` (UI→Ctl label) | The label groups M46 and M53 under "…返回结果", but `04:77` and `04` M53 row define them as UI→Coordinator requests. | Move M46/M53 onto the request line of the same label. |

### Requirement questions (not decided here)
None new. B-01–B-10 are correctly kept as unfrozen and are not written as approved rules. `01` §10 honestly flags that FR15-01/03, FR12-04, NF06-01 are boundary/reverse evidence only.

### Commands a supervisor must run (not executed here)
`plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-08/diagrams/DGM-VOPC-09-authorship-correction.puml` (+ `PLANTUML_LIMIT_SIZE=8192 … -tpng`) after the R1/R5 edits, then `python3 scripts/docs/check_documentation.py` and `python3 scripts/docs/verify_69_ac.py` to confirm the `08` §4.2–4.3 claims still hold.

### Conclusion
`AUTO_REVIEW_PASS` not applicable: R1 must be fixed (VOPC arrow notation and the inaccurate `08` §4.1 verification claim); R2–R8 are non-blocking corrections. No `PENDING_DECISION` item is raised against the requirement baseline.

**Merge verdict: BLOCK** — only for R1; after R1 (+R5 re-render) and the optional P2 text fixes, the verdict becomes OK with notes.