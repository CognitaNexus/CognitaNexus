```
REVIEW_STATUS: ISSUES
SEVERITY: MAJOR
```

## Review
Scope read: `docs/analysis/stage-02/automation/instructions/REVIEWER.md` (task-supplied `automation/instructions/REVIEWER.md` does not exist), SRS V2.2 FR10 (`:229–241`), FR08 (`:193–203`), FR16 (`:306–319`), NF04 (`:428–434`), NF07 (`:450–454`), §6.2 (`:352`), §7.1, §10.1; trace rows 34/35 (`03-AC-UC-分析模型追踪.md:84–85`), 63/64 (`:113–114`), 68 (`:118`), reverse UC-19 row (`:143`); frozen `docs/uml/collection/collection-task-state.puml` (DGM-STATE-01); all 9 files under `docs/analysis/stage-02/uc-19/` plus `DGM-SEQ-18-source-update.puml`, `DGM-VOPC-14-source-update.puml` and both generated SVGs. No files modified (review-only instruction overrides REVIEWER.md's "write `automation/reviews/UC-XX-review-RN.md`"; artifact returned here instead).

**Correct (verified, no action)**
- **111 messages / endpoints.** Structurally counted in the `.puml`: groups yield exactly M1–M111 with no gaps or duplicates (7+12+6+44+13+5+10+8+6); sender/receiver for every message matches `04:§2` (spot-verified M2/M15/M21/M24/M35/M47/M51/M59/M79/M81/M83/M95/M100/M109/M110/M111). SVG present with `viewBox="0 0 4307 7856"` (`generated/…svg:1`) and rendered labels (`:70,:131,:157,:184`), matching `08:§4.1` claims.
- **Classification, timestamps, data preservation.** 新增/未变/变更/失效 uses only source-side facts keyed on `来源ID+记录键+上次观察版本` (M26–M29; FR10), "no version ⇒ not 未变" (A3), no title-similarity equality (A8/M28). 未变 updates only check time/revision (M36; `collection-update-activity`); 变更 appends arrival events, original values never rewritten (M32/M38/M63; INV-01/CL-11). Failure (A4/M83–M87) and deletion/retraction (A2/M63–M69) never delete records and keep recovery entry — correct for `AC-IMPL-FR10-02`, CL-12, BR-IMP-04, NF04-01.
- **Human-reviewed fields & conflict→governance.** M40/M41 read-only on `WorkEntity`, M44 guard, M45 single 待核验字段冲突 todo with both values, M47/M48 record "not overwritten"; no message writes `WorkEntity`/`WorkVersion` — satisfies `AC-IMPL-FR10-01` as a package-level claim. Concurrency handled by pre-effective re-check (M49/M50→M55–M58), consistent with FR10 "并发冲突进入治理待办".
- **Rerun/idempotency & version evidence.** A7/M72–M73 keyed on logical provenance + observed revision (INV-07, FR09-06); versions only on explicit evidence (M42/M43, M59–M62, A10; DD-08) and `WorkVersion` is read-only.
- **No creep / no state invention.** No `Scheduler/TaskQueue/JobRunner/RetryPolicy/Checkpoint/IncrementalWindow/Streaming…` classes (only named as exclusions in legends, `SEQ-18.puml:199`, `VOPC-14.puml:55`); no queue/scheduler/streaming messages; task uses only the six frozen DGM-STATE-01 states (M79/M83) and the stop-transition note (`01:198`) accords with `collection-task-state.puml:16,23,25`; UC-18/09/10 and publish concerns appear only as handoff to `GovernanceCoordinator` (M34/M51/M60) and as out-of-scope rows (`02:§5`). 13-class VOPC, 7 justified multiplicities, AC claims limited to trace rows 34/35/63/64/68 (`01:§10`) with FR08-02/FR09-06/FR16-01-02 marked 边界. B-01–B-09 kept `待确认` (`01:§11`, `08:§6`).

### Finding
**F1 — MAJOR — per-item classification and `来源报告更新时间` are read back but never written**
- Location: `01-UC-19-use-case-spec.md:§5.1(1)(2)`, `06-class-definition.md:§10` (`changeItems[]`, `sourceReportedUpdatedAt`, op `/记录逐条分类`), `04-sequence-messages.md:M20/M47/M53/M57/M59/M70/M90`, `08-self-check.md:§3` row "检查/来源更新/生效时间是否分开".
- Evidence: §5.1(1) requires "每条本轮输入都有唯一分类…并记录到变更集"; §10 lists `/记录逐条分类` with evidence "M20、M47…M90". In the message set only M47/M57 (conflict), M53 (effective time), M59 (version evidence), M84 (failure) write per-item facts; M20 writes only `检查开始时间`; M70 writes aggregate counts + `检查时间`. No message writes `sourceReportedUpdatedAt`, yet M90 returns "来源更新时间" and M91 "三类时间". Consequence: M78's completion guard ("每条输入已分类且记录到结果") and M72's idempotency key have no persisted basis for 新增/未变/失效/异常, and `08:§3` over-claims by citing a read-only M90 as evidence of time separation — against FR10 "保留检查与生效时间" and CL-07.
- Smallest fix (no renumbering): relabel `M70` to "保存逐条分类（新增/未变/变更/冲突/失效/异常，含来源报告更新时间）与互斥计数、检查时间"; mirror in `01:§6` E15; add M70 to §10's op-evidence list; correct the `08:§3` row to cite the write messages; re-render SEQ SVG/PNG.

**F2 — MINOR — A8 items fall through into the four-way classification**
- Location: `diagrams/DGM-SEQ-18-source-update.puml:76–92` (`alt 来源身份与记录键可确认→M29 / else M30–M31` then an unconditional `alt 新增（E8）…else 失效报告`).
- Evidence: the second `alt` is not nested in the guard, so an unconfirmable-identity record still passes through 新增/未变/变更/失效, contradicting `01:§5.1(1)` (异常 is one of five exclusive classes) and M70's "互斥计数". `04:§3` A8 row maps only M28/M30–M31 and never states exclusion.
- Fix: nest the four-way `alt` inside the "可确认" branch (or add `else 异常（A8）` note); re-render.

**F3 — MINOR — per-class message inventories disagree with `04`**
- Location: `05-vopc.md:§1` CollectionCoordinator row ("接收 … M101、M105、M110") and IncrementalChangeSet row (receive list omits M90); `DGM-VOPC-14-source-update.puml:22` (`UI --> Ctl : 请求（M2/M9/M89/M107）`).
- Evidence: `04:§2` M101/M105 are `AdminConsole→管理员`, M110 is `AdminConsole→未授权请求者`; M90 is `CollectionCoordinator→IncrementalChangeSet` and is cited by `05:§2` and `06:§4/§10` but missing in `05:§1`; `05:§2` UI row lists M18/M99/M103 that the VOPC label omits.
- Fix: delete M101/M105/M110 from the Ctl receive list, add M90, extend the VOPC label; label-only re-render.

**F4 — MINOR — reversed-direction edge carries an order-ambiguous multiplicity**
- Location: `DGM-VOPC-14-source-update.puml:34` → `generated/…svg:27,72` ("reverse link User to Audit"), label `操作者 0..*:0..1`.
- Evidence: same diagram uses left:right order (`Def --> Rec : … 1:0..*` matching `05:§2`), so this label reads `User 0..* : Audit 0..1`, the inverse of `05:§2.2` (`AuditEvidence 0..* : User 0..1`) and `06:§15`. Text is right; the rendered diagram is misreadable.
- Fix: write `Audit --> User : 操作者 0..*:0..1` (or `User <-- Audit : 操作者 0..1:0..*`); re-render VOPC.

**F5 — P2 — this round's task is never established in 运行中**
- Location: `01:§4.4` ("任务处于允许推进的状态…运行中→成功/失败/已停止"), `04:§4.6`, M74/M79/M83.
- Evidence: M74 writes checkpoints/history into `CollectionTask` and M79/M83 assert `运行中→成功/失败`, but no message creates or starts the task; `06:§8 待确认` covers only task identity (new/retry/related). An implementer cannot tell whether the round runs on an already-running task (UC-18) or a 重试 task.
- Fix: one sentence in `01:§4.4` and `04:§4.6`: "本轮任务在此前已由 UC-18 建立并处于运行中；`待执行/重试→运行中` 不在本图展开（B-01）". Text-only.

**Unresolvable from baseline (not defects, already registered):** B-01 checkpoint/batch granularity + task identity; B-02 degraded-comparison rule; B-03 backtrack-scope form; B-04 conflict ownership/deadline; B-05 public-visibility impact of 撤稿/删除/外链失效; B-09 single owner for 待核验字段冲突 and whether trace rows gain `SEQ-18/VOPC-14` — all correctly left open and assigned to R5/parent (`07:§6`, `08:§6`).

**Supervisor must run:** `python3 scripts/docs/check_documentation.py` (expect `errors: []`), `python3 scripts/docs/verify_69_ac.py`, and after F1–F4 edits `plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-19/diagrams/*.puml` plus `grep -c "Syntax Error" .../generated/*.svg` (expect 0). PNG visual re-check not performed here (SVG text/viewBox only).

**Merge verdict: BLOCK** — the package is otherwise well-founded, but F1 is a real model/message gap against its own postconditions and FR10's time-retention requirement; F1's label-only fix plus the three small consistency fixes (F2–F4) and the one-sentence F5 note should clear it.