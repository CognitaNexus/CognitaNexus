## Review

```
REVIEW_STATUS: ISSUES
SEVERITY: MAJOR
```

Scope read: `automation/instructions/REVIEWER.md` (actual path `docs/analysis/stage-02/automation/instructions/REVIEWER.md`; the task-supplied path `automation/…` does not exist), SRS V2.2 FR08 (`:193–203`), FR09-06 (`:219`), FR16 (`:311–319`), NF04 (`:428–434`), NF07 (`:450–454`), §6.2 (`:353–355`), BR-IMP-04/10 (`:376`,`:382`), trace rows 27/40/43/76/77/113/114/118/142, `DGM-STATE-01`, and all 9 UC-18 Markdown files plus both `.puml` and both generated SVGs. No files modified (so `automation/reviews/UC-18-review-R1.md` was not written; `progress.json` still shows `reviewReport: null` for UC-18).

### Correct (verified independently)
- **100 messages.** `DGM-SEQ-17-collection.puml` group spans sum to exactly 100 contiguous messages (M1–M7, M8–M15, M16–M31, M32–M45, M46–M53, M54–M62, M63–M69, M70–M86, M87–M94, M95–M100), no gaps/duplicates; SVG embeds rendered text for M59/M69/M100 (grep of `generated/DGM-SEQ-17-collection.svg:103,116,159`), viewBox `4243×6701` and VOPC `2918×1704` match `08:§4.1`; no `Syntax Error` in either SVG.
- **Success criterion.** M54–M58 + E17/E18 match §6.2 (`SRS:355` "只有计划输入都记录到结果或异常队列后才可成功"); separation of pending/exception counts at M53/M61/M62 and BR-UC18-08.
- **Exceptions never public.** M42 "不设公开可见性", A11, `05:§2.3` refuses a public-visibility field; no `WorkEntity`/`Authorship`/`Scholar`/publish message anywhere in the puml.
- **Idempotency.** M38/M39 + A12 + BR-UC18-12 align with `AC-IMPL-FR09-06` (`SRS:219`): duplicate arrival appends events, no second logical origin, no new entity.
- **Two independent sources.** BR-UC18-02/A10; `SourceDefinition.inputType` per definition (`06:§7`), `SourceDefinition 1:0..* CollectionTask` (`05:41`).
- **Six states only.** M27/M59/M65/M70/M80 map onto `collection-task-state.puml:18–23`; no added state.
- **No implementation/framework classes.** Rejection table `03:§4` (Scheduler/TaskQueue/JobRunner/RetryPolicy/Checkpoint/ImportBatch/ExceptionQueue); VOPC = 10 classes, 15 edges (9 collaboration + 6 business), matches `05:§1–§2`; no queue/scheduler/API/DB text in `06`.
- **No UC-19/09/10 creep.** Only M40/M41 touch `GovernanceCoordinator`; source retirement/increment (`02:§5`) and dedup/identity decisions are explicitly out.
- **Trace rows.** `03-AC-UC…:27,40,43` (FR08→UC-18, NF04/NF07→UC-18/19) and reverse row `:142` (FR08-01/02, NF04-01/02, NF07-01) match `01:§10` claims.

### Findings

| ID | Sev | Location | Evidence | Minimal fix |
|---|---|---|---|---|
| F1 | **MAJOR** | `01:113` (A3), `04:100` (M69), `DGM-SEQ-17…puml:115` | A3/M69 say a stopped task is "可重试或另建关联任务". This contradicts the same package's `01:52` ("成功或已停止的任务不再推进，须另建关联任务"), `01:119` (A9, "成功/已停止是终态"), `01:134` (BR-UC18-05), and the frozen `collection-task-state.puml:25` (`Stopped --> [*]`, **no** Stopped→Retry). An implementer following A3/M69 would build a transition the frozen state model forbids. Related gap: stop is modeled only from 运行中 (`04:166`), while `collection-task-state.puml:16,23` also defines 待执行→已停止 and 重试→已停止. | Change A3/M69 wording to "已停止为终态，仅可另建关联任务"; add one sentence to `01:§9`/`04:§4.6` that 待执行/重试 的停止转移沿用 `DGM-STATE-01`（本图只画运行中→已停止）; re-render SEQ-17 (label-only edit). |
| F2 | **MAJOR** | `05:44`, `05:59`, `DGM-VOPC-13…puml` (`Rec --> Exc : … 1:0..*`) | `SourceRecord 1 : 0..* GovernanceException` is force-filled: the same package defines `GovernanceException.targetRefs`(来源记录/**任务**/**批次范围**) at `06:133` and `03:27`, and admits reason "读取或交接失败" (`03:§C9`). If an exception may target a task or batch range, the record side cannot be `1`. `05:59`'s justification ("异常项必须指向具体来源记录") contradicts `06:133`. | Either change to `0..1` in `05:44/59` + VOPC edge label, or narrow `06:133`/`03:27` to "来源记录（唯一）" — one token/sentence; VOPC label-only re-render. |
| F3 | MINOR | `01:§10`, `08:§5`, `07:§6` | Trace row `64` (`AC-IMPL-NF04-02` → UC-18、UC-19, NOT RUN) is not reflected: `01:§10` omits NF04-02 and delegates RTO/RPO to deployment/ops, and `07:§6` does not register the ownership mismatch as an open item (it only proposes adding `SEQ-17/VOPC-13`). | Add one row/line to `07:§6`: "trace row 64 assigns NF04-02 to UC-18/19; this package defers RTO/RPO to deployment — parent to re-assign or confirm." No model change. |
| F4 | MINOR | `07:§5` ("UC-09/10/17 … 无冲突") | UC-09 represents exceptions as `CleanRecord.governanceState`（异常待处理）with `DedupCandidate 0..*:1 CleanRecord` (`uc-09/03-bce-identification.md:24,39`), and its `07:92` explicitly leaves "是否把'处理项'升为独立分析类" to R5 (`DGM-STATE-04`). UC-18 declares a standalone `GovernanceException` (exception may exist for a record that has no `CleanRecord`) and asserts no conflict. | Replace "无冲突" with a registered R5 item: `GovernanceException` vs `CleanRecord.governanceState` / `DGM-STATE-04 处理项` — which is the single owner of "异常待处理". |
| F5 | MINOR | `04:§2` M27/M59/M70 vs M65/M80; `04:166` | Same kind of transition is expressed inconsistently: M65/M80 are command arrows (`Ctl -> Task`), while M27/M59/M70 are reply arrows from the entity (`Task --> Ctl`, e.g. `puml:57,102`), so the task appears to transition proactively after a guard the coordinator evaluated. | Add one clarifying line in `04:§4`: 状态由 `CollectionTask` 在收到守卫结果后应用并以应答消息回报（M27/M59/M70），停止/重试为指令式（M65/M80）. No redraw needed. |
| F6 | MINOR | `03:41` (C2), `05:15`, `06:32/34` | `SourceAdapter` inventory lists only M34/M35/M37; **M36** (record/failure feedback from 获准数据来源) is omitted, so the per-object message inventory is not exhaustive for the adapter's inbound edge. | Add "接收 M36" to the three rows. |

### Not verifiable here (supervisor must run)
- `python3 scripts/docs/check_documentation.py` — confirm `errors: []` and `relative_links: 1789` as claimed in `08:§4.3` (I verified by hand that all distinct relative targets used in the package resolve, including `../../../spec/CognitaNexus-Core-SPEC-V1.0.md`, `../automation/*`, `../02/03/04/05-*.md`, and the 4 diagram files).
- `python3 scripts/docs/verify_69_ac.py` (69 AC unchanged) and `python3 scripts/docs/verify_scoped_srs.py` (listed in AGENTS.md §9 but not run in `08`; it only checks fixed `docs/uml/...` paths, so no UC-18 impact).
- PNG visual check not performed (SVG structure/dimensions and embedded rendered text only).

### Verdict
**REVIEW_BLOCKED (merge verdict: BLOCK)** — F1 and F2 are concrete self-contradictions inside the reviewed target, one of them against the frozen `DGM-STATE-01`. Both fixes are label/sentence-level (01:113, 04:100, 05:44/59, 06:133 or 03:27, plus two label-only re-renders); after them and the F3–F6 one-liners, the package should pass. Everything else checked — 100 messages, success criterion, count separation, exception non-publicity, idempotency, two-source independence, six-state reuse, absence of scheduler/queue/UC-19/09/10 scope creep — is correct.