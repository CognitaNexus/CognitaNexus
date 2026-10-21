```
REVIEW_STATUS: ISSUES
SEVERITY: MAJOR
```

## Review

Scope read: all 9 UC-18 Markdown files, `DGM-SEQ-17-collection.puml` + `DGM-VOPC-13-collection.puml` and both regenerated SVGs, SRS V2.2 FR08 (`:193–203`) / §6.2 (`:353`), `DGM-STATE-01`, prior `automation/reviews/UC-18-review-R1.md`. No files modified.

### Correct (fixes verified in place)
- **F1 (partly):** `01:113` A3 now "已停止为终态，仅可另建关联任务"; `04:100` M69 and `DGM-SEQ-17…puml:115` / `SVG:116` match. Consistent with `01:52`, `01:119` A9, `01:134` BR-UC18-05, and frozen `collection-task-state.puml:25` (`Stopped --> [*]`, no Stopped→Retry).
- **Stop-transition note:** `01:191` now records that `DGM-STATE-01` also defines 待执行→已停止 / 重试→已停止 and that SEQ-17 expands only 运行中→已停止 (matches `collection-task-state.puml:16,23`).
- **F5 (state-apply wording):** `04:183` note distinguishes reply-applied transitions (M27/M59/M70) from command-style stop/retry (M65/M80); `04:§3` row A9 unchanged and correct.
- **F2 (partly):** `05:44` and `06 §12` now give `SourceRecord 0..1 : 0..* GovernanceException`, consistent with `06:133` `targetRefs`(来源记录/任务/批次范围) and `03:§C9` ("读取或交接失败").
- **F3/F4:** `07` closing notes register trace row 64 (NF04-02 → deployment/ops) and the `GovernanceException` vs `CleanRecord.governanceState`/`DGM-STATE-04` ownership item as R5 input.
- **F6 (partly):** `05:15` ("接收 M34/M36"), `06 §3` ("M34/M35/M36/M37") now inventory M36.
- **Unchanged & still correct:** M1–M100 contiguous and matching 04; success criterion M54–M58 (§6.2); exception non-publicity (M42/A11); idempotency M38/M39/A12; 10-class VOPC; no scheduler/queue/UC-19/09/10 creep.

### Findings

**1. MAJOR — `GovernanceException` source side still force-filled to `1` in the model and two justifications**
- Location: `diagrams/DGM-VOPC-13-collection.puml:38` (`Rec --> Exc : 异常指向来源记录 1:0..*`), rendered `diagrams/generated/DGM-VOPC-13-collection.svg:26,64` (same label), `05-vopc.md:59` (§2.2 row `SourceRecord 1 : 0..* GovernanceException`), `07-global-model-diff.md:§4.1` list.
- Evidence: directly contradicts the fixed rows `05:44` and `06 §12` (`0..1 : 0..*`), `06:133` (`targetRefs`: 来源记录/**任务**/**批次范围**), and `03:§C9` (exception may arise from "读取或交接失败" with no record). `05:59` still argues "异常项必须指向具体来源记录", i.e. the exact claim R1 F2 rejected. The diagram — the artifact an implementer/model reviewer reads — still forbids record-less exceptions.
- Smallest fix: change the edge label to `0..1:0..*` (or drop the multiplicity from that edge), rewrite `05:59` row to `SourceRecord 0..1 : 0..* …` with the §2 rationale ("异常可无具体来源记录；同一记录可有多条异常项"), fix `07 §4.1`, re-render VOPC (label-only).

**2. MAJOR — stopped task still described as retryable in the event-flow trace**
- Location: `02-event-flow-trace.md:45` (A3): "运行中→已停止；保留完成项与未完成范围；**可重试或另建任务**", marked "已证实".
- Evidence: contradicts `01:113`, `01:119`, `01:134`, `04:100` and the frozen `collection-task-state.puml:25` (`Stopped --> [*]`, no Stopped→Retry). The document also labels it 已证实, which over-claims a forbidden transition; an implementer reading the trace table would build Stopped→Retry.
- Smallest fix: replace with "已停止为终态，仅可另建关联任务" (one phrase), matching `01:113`/`04:100`.

### Non-blocking residue (P2, notes only)
`03-bce-identification.md:§3 C2` still lists "接收 M34 / 发送 M35 / 应答 M37" without M36, so that per-object inventory stays non-exhaustive (F6 residual); add "接收 M36（记录或失败反馈）". Also re-run `python3 scripts/docs/check_documentation.py` and `verify_69_ac.py` after any edit — not executed here.

### Merge verdict
**BLOCK** — two concrete self-contradictions remain (both against the frozen `DGM-STATE-01` / the package's own `0..1` relation). Both fixes are label/sentence-level; after them the package passes.