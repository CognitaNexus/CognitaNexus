## Review

REVIEW_STATUS: ISSUES
SEVERITY: MAJOR
(`automation/instructions/REVIEWER.md` lives at `docs/analysis/stage-02/automation/instructions/REVIEWER.md`; I did not write `automation/reviews/UC-22-review-RN.md` because this task is read-only — report returned instead.)

**Scope read:** all 9 files of `docs/analysis/stage-02/uc-22/`, both `.puml`, both `.svg`/`.png`; SRS V2.2 `:119-125`, `:161-189`, `:383`, `:444-448`, `:508-510`; trace rows `03-AC-UC-分析模型追踪.md:56-72,117,144`; SPEC `:45` (INV-08/INV-10), `:372` (Q-SPEC-06/A06); globals `DGM-CLASS-01-analysis.puml:95-127`, `DGM-SEQ-04-profile.puml:27-38`, `DGM-ACTIVITY-06-edit-profile.puml:20-35`, `01-BCE分析类模型.md:24,37,59`, `unresolved-decisions.md:15-18`, `analysis-class-registry.md:16-17`.

**Correct (verified)**
- AC completeness: all 10 ACs in the UC‑22 reverse row (`trace:144`) appear in `01 §10`; FR06‑01…05/FR03‑01…04/NF06‑01 present, no 70th AC invented (`01 §11`, `02 §6`).
- Event/flow traceability: E1–E6, A1–A7 each mapped to SRS/AC with "已证实/分析推导/待确认" grading (`02 §2-3`), and `04 §2` gives sender/receiver/step/priority/basis per M1–M22.
- BCE derived from events, not copied from global: `03 §1,§4` rejects Scholar/Auth/Claim/Authorship/WorkEntity/second UserProfile/Session/PublicProfileUI/AuditCoordinator with reasons; global `ProfileCtl ..> Scholar` (`DGM-CLASS-01:107`) not imported.
- VOPC == sequence participants (5: ProfileUI, ProfileCoordinator, User, UserProfile, AuditEvidence); only `User 1—1 UserProfile` carries multiplicity, justified; audit object reference kept as attribute, no invented edge (`05 §2.1-2.2`).
- Owner-only writes / direct-call rejection: M3–M5 guard precedes M15, A2 + audit M11 (`puml` alt A1/A2), matches `AC-IMPL-FR06-04` and SRS §8. No second profile, no Scholar/Claim/Authorship creation messages anywhere.
- Ordinary vs Scholar facts: M12/M13/M14 only rejects+hands off; no Scholar message; matches FR06 sentence and `AC-IMPL-FR06-01/02`.
- Scope: no UC‑03/05/06/07/08/17 flows imported; no DB/API/framework design (grep clean; only "不写数据库…" disclaimers).
- Artifacts real: SVG/PNG exist; SVG contains M1–M22 labels incl. `M19`, `M22`, `alt 保存或公开投影生成失败（A4）`, `opt 公共资料策略要求审核（A5·B-01 待确认）` (`DGM-SEQ-09-profile-edit.svg:17,37,42-47`); watchdog diff shows only uc‑22 files untracked, consistent with `08 §1`.

**Findings**

| ID | Sev | Location | Evidence | Minimal fix |
|---|---|---|---|---|
| F1 | P1 | `DGM-SEQ-09` messages M15/M18/M19/M21/M22; `04 §3.4`; `01 §7 A5` | In the diagram M18 ("生成公开投影") always executes right after M15, and `opt A5` M19 only afterwards restores "公开版保持旧值"; M21 "返回保存成功与公开生效结果" and M22 "已按公共资料策略公开生效" still fire inside the same `else 保存成功` block. So in the A5 case the pending values do pass through the public projection and the owner is told "已公开生效", contradicting FR06 "待审版本不覆盖公开版" / BR-IMP-10 and `04 §3.4` ("待审内容不得提前出现在公开投影中"). | Split after M15: `alt 策略要求审核（A5）` → M19 登记待审版本（公开版不变）→ M20 → new return "已提交待审，公开版未变更，可查待审状态"; `else 即时生效` → M18 → M20 → M21 → M22. Adjust `04 § 2/3.4/§4` and `01 §7 A5` wording to match. |
| F2 | P1 | `06 §5` 业务属性 `privateExcludedData` | Attribute reads "默认不公开的账号信息/联系方式/**认证材料/凭据**（不进入投影）", i.e. asserts UserProfile holds credentials and review materials. Contradicts BR-IMP-11 (`SRS:383` 不默认采集/证明材料本人和有权审核者可见), NF03, and the package's own `03 §4` ("凭据本身按 NF03 从不进入分析与公开响应" — CredentialContext rejected for that reason). | Restrict the attribute to fields a UserProfile may legitimately retain (账号信息/联系方式) and rewrite the rest as a projection-exclusion rule; state that 认证材料属 UC‑05/06 `ReviewEvidence`、凭据不在 UserProfile 数据范围。 |
| F3 | P2 | `DGM-SEQ-09` `alt …（A4）` before M18; `01 §7 A4`; `04 §2 M16` | The A4 label/condition claims "保存或公开投影生成失败", but the branch is placed before M18, so a projection-generation failure has no reachable path (M18 is a plain reply with no `alt`). Coverage table `04 §4` still records A4 as covered. | Either relabel A4 as 保存失败 and add a second failure `alt` after M18 (reusing M16/M17), or move M18 before the A4 `alt`. |
| F4 | P2 | `01 §10` rows for `AC-IMPL-FR06-01`, `FR03-01`, `FR03-02` | These ACs are visitor/portal-manager observable behaviors (`SRS:167,119,121`); UC‑22 events (M1–M22) cannot verify them; the doc's own footnote only demotes FR07‑03/NF03‑01. | Mark these three rows "边界，不主张覆盖（验证在 UC‑03/UC‑07）", same style as the existing footnote. |
| F5 | P2 | `01 §5.1-6`, `01 §5.2-1`, `02 §3 A4`, `04 §2 M16/M17` | Over-broad citations: success-write audit is attributed to `AC-IMPL-FR06-04` (that AC covers the *rejected* cross-user write, `SRS:173`), and save/projection failure "保持旧值" to `AC-IMPL-FR06-02` (authorization AC, `SRS:169`). Correct bases are BR-IMP-10/INV-10 and SPEC §8. | Replace those two AC citations with BR-IMP-10/INV-10 (audit) and SPEC §8+NF01 (failure), keeping the AC only for the rejection scenarios. |
| F6 | P2 | `01 §11 B-07`, `07 §2` | Claims the `AuditEvidence` vs `Evidence`/`ReviewEvidence` naming conflict is covered by "D-11/D-12", but `unresolved-decisions.md:17-18` registers D‑11 (SearchCtl/Work/AccountUI/RegCtl) and D‑12 (User—Authorization—Scholar + UI/Coordinator) only — evidence-class naming is unregistered. | Have the parent register a new decision ID for evidence/audit naming, or reword B‑07 to "未登记，建议登记". |

**Open actions for parent (not package defects; package already discloses them as B‑08/`07 §6`)**
- `03-AC-UC-分析模型追踪.md:68-72` still list only `SEQ-04` for FR06‑01…05 (no `SEQ-09/VOPC-05`); reverse row `:144` lacks FR07‑03/NF03‑01; `analysis-class-registry.md` has no `ProfileUI`/`ProfileCoordinator`/`AuditEvidence` rows (only `PublicProfileUI/Coordinator`, lines 16‑17).
- `08 §4` PASS lines are plausible but I did not execute anything: supervisor should run `python3 scripts/docs/verify_69_ac.py`, `verify_scoped_srs.py`, `check_documentation.py`, and re-render both `.puml` after the F1/F3 edits (render claims in `08 §4.2` match the artifacts I inspected).

**Merge verdict:** BLOCK — F1 and F2 (MAJOR) must be fixed in `DGM-SEQ-09`/`04`/`01` (F1) and `06 §5` (F2); F3–F6 are report-only and can be batched with the same edit.