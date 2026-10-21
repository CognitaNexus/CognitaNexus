## Review

REVIEW_STATUS: PASS
SEVERITY: MINOR

Scope: `docs/analysis/stage-02/uc-03/` (01–08, README, both `.puml`, both `generated/*.svg`). Baselines read: SRS V2.2 lines 113–141 (FR03/FR04), 291–307 (FR15), 408–412 (NF01), 444–448 (NF06), 502–510 (§12), SPEC V1.0 §1:43 / ID-01:182 / ID-06:187 / SR-12:252, and `03-AC-UC-分析模型追踪.md` UC-03 rows (56–59, 109, 117, 127). No files modified.

**Correct (verified)**
- AC/event completeness: all six traced ACs (`FR03-01…04`, `NF01-01`, `NF06-01`) map to flow steps, alternates and messages in `01:§10`, matching trace rows 56–59/109/117 and reverse row 127; NF06-01's "他人证明被拒" clause is explicitly delegated to UC-05/06/22 (`01:§10`) rather than claimed.
- Zero-work ≠ 404: `01:§6` step 5 + A4, `04` M13/M14, `DGM-SEQ-08-profile.puml` lines 33–36, legend line 60.
- UserProfile ≠ Scholar, no same-name merge: `03:§2/§5`, `05:§2.2` (no edge, no dash line), `06:§8`, supported by SRS §12:504 "UserProfile 与 Scholar 不是一对一强制合并" and `AC-IMPL-FR03-04`.
- Unclaimed Scholar public: A6 + M17/M18/M24/M25 (`FR03-01`).
- Pending/contested Authorship not confirmed: M20 keeps the independent state, M23 forbids impersonation; status enumeration in `01:§9`, `06:§6` matches SPEC §1:43 exactly, and "管理员审核不等于本人确认" matches ID-06:187.
- No scope creep / no implementation design: M16 labels detail jump as UC-02, `01:§7` boundary row excludes UC-22/05/06; both `.puml` files contain no DB/API/framework/queue terms; `06` states are business facts.
- VOPC = sequence participants: 6/6 (`05:§1/§3` vs `04:§1`), Visitor excluded.
- Render claim is truthful for content: generated SVGs contain rendered `<text>` for M1, M23, M24–M27, VOPC class names and legends; `08:§4.2` output schema matches `scripts/docs/check_documentation.py:53` keys.
- `07` global-diagram line citations are accurate (DGM-CLASS-01 lines 16 `PublicUI`, 53 `UserProfile`, 58 `Scholar`, 98 `PublicUI ..> SearchCtl`, 116 `User 1--1 UserProfile`, 124 `Scholar--Authorship`).

**Findings**
- **P2-1** `DGM-SEQ-08-profile.puml:17–19` draws the A3 failure branch as the first sibling of the two object branches, i.e. evaluated before M9/M17, while `04:§3.2` states "M5 的失败守卫覆盖 M9–M21 的任一次实体读取失败或超时". Evidence gap is visual, not textual. Minimal fix: add one `note over Ctl : M5守卫覆盖M9–M21任一读取失败/超时`, or wrap the read region in `break`.
- **P2-2** M4 (Ctl→Ctl, "校验公开可访问") has no message that supplies the visibility fact, although `03:§2 C3` and `06:§4` define `UserProfile.publicVisibility` / `Scholar.publicState`. A2 is claimed covered by M7/M8 (`04:§4`) without a read. Minimal fix: make M4 `Ctl -> Profile/Scholar : 读取公开可见性（或不存在）` and update the M-lists in `05:§1/§3` and `04:§4`.
- **P2-3** M12/M15 assert a UserProfile works area, but the unified filter M23 is drawn only inside the Scholar branch, so a non-public/待核验 item in a UserProfile list has no guard message; `04:§4` credits A2's "成果层" only to M22 (Scholar path), and the works-area source is explicitly open (Q-03, `01:§11`, `06:§4`). Minimal fix: place M23 before M13/M15, or add `note right of Ctl : 主页成果区来源与状态过滤口径待Q-03`.
- **P2-4** `01:§5.1-1` requires "对象类型与状态标签分别显示" for the single object, and `AC-IMPL-FR03-04` requires 页面对象与核验状态区分, yet M10/M13–M16 carry no type/"非学者认证" label and `06:§4` gives UserProfile no type or verification attribute (Scholar has `claimState`/`identityState`). Minimal fix: extend M10/M15 wording with "对象类型标识（UserProfile，非学者认证）".
- **P2-5** `07:§1/§2` attributes `ScholarPortalUI` to "全局", but it exists only in `docs/analysis/stage-02/01-BCE分析类模型.md:25`; `DGM-CLASS-01-analysis.puml` contains no such class (exhaustive grep). Minimal fix: relabel the column "BCE登记表 `01` 第25行（图中无此类）".
- **P2-6** (report-only) Trace rows 56–59/109/117 still cite `DGM-USECASE-07 / CLASS-01` without `SEQ-08/VOPC-04`; `02:§6`/`08:§6` correctly assign this to the parent. No change inside `uc-03/`.

**Not verifiable here** (no shell/git): HEAD `8878e34…`, `PlantUML version 1.2020.02`, and the quoted exit codes/`errors=0` from `check_documentation.py`, `verify_69_ac.py`, `verify_scoped_srs.py`. A supervisor should run: `python3 scripts/docs/check_documentation.py`, `verify_scoped_srs.py`, `verify_69_ac.py`.

**Merge verdict: OK with notes.** No MAJOR/CRITICAL; AC baseline stays 69, and each P2 has a one-message or one-line fix inside `uc-03/`.