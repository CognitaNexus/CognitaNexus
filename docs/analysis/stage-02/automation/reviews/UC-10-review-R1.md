## Review

```
REVIEW_STATUS: ISSUES
SEVERITY: MAJOR
```
Scope: read `automation/instructions/REVIEWER.md` (at `docs/analysis/stage-02/automation/instructions/REVIEWER.md`), SRS V2.2 FR03/FR09/FR12/FR15/NF03/NF05/NF06/§7-§8-§11-§12, `03-AC-UC-分析模型追踪.md` UC-10 rows (L34, L97–102, L134), Core SPEC §6.2–§6.5, `docs/srs/modules/scholar-disambiguation.md` FR15-S01–S13, and all ten `uc-10/` files plus both `.puml` and both SVGs. Read-only; nothing modified.

### Correct (verified against source)
- **Same-name ≠ same person**: guards M16 (`DGM-SEQ-15-identity.puml`, alt at 候选; 01 E6/E7) + A3 + BR-UC10-03 correctly anchor `AC-IMPL-FR15-01/03` and BR-342/SPEC ID-03.
- **Thresholds not invented**: E7/M21 makes auto-judgement conditional on "规则已批准"; B-03/B-04 leave 阈值/候选规模 open; only the *unapproved* "60 秒" is quoted and explicitly stripped of force (01 §11 B-02 vs NF05 L438) — correct handling.
- **Candidate vs confirmed kept apart**: `IdentityCandidate`(≥2 档案, `2..*`) vs `AttributionCandidate`(署名, `1..*`) vs merged conclusion; M29 refuses silent assignment; VOPC multiplicity `2..*`/`1..*` justified at 05 §2.2.
- **Admin approval ≠ self-confirm**: no message writes `本人确认` (M49/M79/M84); `ON_ADMIN_REVIEW` per SPEC L221; BR-UC10-10 and `AC-IMPL-FR15-06` handled as boundary, not claimed.
- **Excluded not restored**: M11–M13 guard precedes recall (M14), M89 re-checks after exclusion, matching SRS L303 / SPEC `previouslyExcluded` (L216) / FR15-S09 E1 (module L827).
- **Propagation/audit**: M97–M101 declare single effective version + per-view timing without asserting the unapproved 60 s target; NF05-01/02 marked NOT RUN in 08 §5.
- **103 messages / 12 VOPC classes**: both SVGs are genuine PlantUML 1.2020.02 output with no `Syntax Error` and contain M6/M103; VOPC has exactly the 12 message-bearing classes; P2P2 message sets in 04 §4/05 §3 reconcile to 103.
- **No scope creep / no implementation design**: `UserProfile`, `ClaimApplication`, `ScholarChangeRequest`, `CorrectionRequest`, `WorkVersion/DedupCandidate/CleanRecord`, `CollaborationEdge`, `PendingTodo` all rejected with reasons (03 §4, 05 §4) and A19 hands them off; no DB/API/queue/framework constructs.
- **C-01 honestly registered**: module FR15-S03 (module L266, L303) auto-merge-on-(a)/(b) vs SPEC ID-02/§6.5 (SPEC L183, L229) tension is recorded (07 §5, 08 §6) instead of silently resolved.

### Findings
**F1 — P1: two rule IDs are cited for the wrong clause (traceability table mislabels its own evidence).**
- `01-UC-10-use-case-spec.md:107` (E9) and `02-event-flow-trace.md:27` (E9) and `04-sequence-messages.md:48` (M17) cite **"FR15-S04 A1（"无候选新建"）"**. FR15-S04 A1 actually reads "候选涉及两个以上档案——系统生成一个候选组" (`docs/srs/modules/scholar-disambiguation.md:375`); the "无候选新建" wording lives in FR15-S07 E1 (same file `:655`). The package uses the same ID correctly elsewhere for the `2..*` multiplicity (`05-vopc.md` §2.2, `06-class-definition.md:196`, VOPC legend), so one ID carries two meanings inside one deliverable.
- `01-UC-10-use-case-spec.md:131` (A8), `02-event-flow-trace.md:49` (A8), `04-sequence-messages.md:91` (M60) cite **"FR15-S05 A1/E4；BR-348"**. The "新证据到达…不自动升级" behavior is FR15-S04 **E4** (`:384`), not S05 E4 (which is 已认领门户/管理权, `:475`); BR-348 is 争议期间禁止自动变更 and does not support the 补充证据 path.
- Minimal fix (one line each, no analysis change): E9 → `FR15-S07 E1（"按 FR15-S04 允许的无候选新建"）；AC-IMPL-FR03-01`; A8 → `FR15-S05 A1；FR15-S04 E4；B-02（时限未冻结）`, deleting the S05-E4 / BR-348 tags. These are the only citation errors I found; A1–A7, A9–A20 and E1–E8/E10–E18 spot-checks all matched their cited clause.

**F2 — P2: VOPC omits the M6 audit edge message.**
`diagrams/DGM-VOPC-11-identity.puml:47` lists `M12/M19/…/M100` (18 IDs) on `Ctl --> Audit`, but 04 §4, `05-vopc.md` §1 and `06-class-definition.md` §3/§14 all count **19** audit messages starting with M6 (私人联系方式剔除). Fix: prepend `M6/` to that edge label and re-render SVG+PNG (legend line "其余为受控审计" can stay).

**F3 — P2: authorization-invariance (A13) is not shown on the identity-merge branch.**
`DGM-SEQ-15-identity.puml` reads `ScholarAuthorization` only at M78 inside 归属候选判定 (group 3), while A13/BR-UC10-14 and 01 §5.3-9 claim the invariant for "判定或纠正" generally, and FR15-S05 E4 (module `:475`) governs identity 判定 including 确认同一人. The 合并 branch (M69–M74) has no authorization read and no conflict-item record; 06 §15 "授权不转移" rows also point at M78 alone. Minimal fix: add one read-only message in the 确认同一人 branch (e.g. `Ctl ..> Auth : M70a 只读确认既有授权不随合并转移` + audit line) and cite it in 04/05/06 — no new class needed.

**F4 — P2: propagation group drawn unconditionally after rejection branches.**
Groups 3–4 (M75–M103) are top-level `group`s, so the diagram reads as "after A1/A2 拒绝, A10 版本冲突, A8 仍待核验, A17 转更高权限, propagation M97–M101 still runs", contradicting 01 §5.4 ("失败：无业务写入") and 04 §3 ("留痕成功才报成功"). Minimal fix: wrap 生效传播与结果 in `opt 判定已写入并生效`, or add one legend line "本组仅在判定成功生效后执行".

**F5 — P2 (wording):** `06-class-definition.md:201` writes `Authorisation → User / → Scholar` (British spelling, inconsistent with `ScholarAuthorization` everywhere else); `04-sequence-messages.md:98` (M98) says the coordinator updates "归属状态与公开投影", which brushes UC-07's archive display fields that 01 §9 explicitly forbids. Tighten to "归属/待核验状态投影，不含档案展示字段".

### Requirements questions (not mine to decide)
B-05 (`AttributionCandidate` independent class) and C-01 (module FR15-S03 vs SPEC ID-02/§6.5) remain open in the package; C-01 also needs the shared `unresolved-decisions.md` registration the parent owns. No new PENDING_DECISION found beyond these already-registered items.

**Merge verdict: OK with notes** — F1 must be corrected (three one-line citation edits) before this package is used as FR15 traceability input; F2–F5 are cheap re-render/wording fixes, and none affect the business conclusions or the 69-AC baseline.