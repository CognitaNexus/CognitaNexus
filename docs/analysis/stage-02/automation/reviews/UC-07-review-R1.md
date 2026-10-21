## Review

```
REVIEW_STATUS: PASS (no MAJOR/CRITICAL)
SEVERITY: MINOR
CONCLUSION: AUTO_REVIEW_PASS — 4 minor (P2/report-only) items; nothing blocks.
```

**Review basis read directly:** `docs/analysis/stage-02/automation/instructions/REVIEWER.md` (task path `automation/instructions/REVIEWER.md` does not exist; found under `docs/analysis/stage-02/`), SRS V2.2 §4 FR05 L145–159 / FR06 L161–177 / FR15 L291–309 / NF03 L422–426 / NF06 L444–448, §6.4 L361, §7.2 L390, §8 L394–401, §11.3/11.4 L490–492, §12 L500–510, BR-IMP-01/03/04/05/10/11 L373–383, SPEC V1.0 §3 INV-01/06/08/10 L45, §6.2 ID-06/07/10/12 L187–193, trace rows `03-AC-UC-分析模型追踪.md:25,68–72,112,131,144`, `02-用例模型与规约.md:32,188–204`, plus all 9 md + 2 `.puml` + 2 SVG/PNG in `uc-07/`. Review-only honored: nothing modified (REVIEWER.md would permit `automation/reviews/UC-07-review-R1.md`; no-edit wins, content returned here).

**Correct (verified, not taken on trust)**
- **Write authorization per write & scope.** `DGM-SEQ-12…puml:12–18` puts M3(User)/M4(Auth, read-only)/M5(Scholar)/M6 conjunction before any write; A1/A2/A3/A8 reject with "无任何写入/目标档案无变更" (`puml:23–45`); approval path re-validates identity/account/permission at M38–M39 (`puml:74–76`). Scope is checked twice: M4 scope read + M13 field-scope (`puml:18,35`). Matches SRS §8 row "维护指定门户 … 账号状态＋角色＋对象许可"; `AC-IMPL-FR06-02`.
- **UserProfile vs Scholar facts kept apart.** Zero `UserProfile` messages in either diagram; A4 only routes (M17–M18); `05-vopc.md:79,86` and `06-class-definition.md:130,156` exclude it; `07-global-model-diff.md:95–99` records no `Scholar—UserProfile` edge. Consistent with SRS §12 L504–506, BR-IMP-05.
- **Pending change cannot overwrite public.** M20 writes only to `ScholarChangeRequest`, public `Scholar` untouched (M33 "公开版仍为旧值"); approve-only apply at M45 "仅变更范围内字段"; reject path M49–M51 keeps old value. Tracks §6.4 L361, BR-IMP-10, `FR06-01`.
- **No second profile / Claim / Auth transfer / Authorship.** Absent from both diagrams; `06-class-definition.md:135` (Auth "本 UC 不写入"), `BR-UC07-07/08`, matching `AC-IMPL-FR05-04`, `AC-IMPL-FR15-06`, SPEC ID-07/ID-12.
- **Audit.** M9/M12/M16/M21/M27/M46/M50; `AuditEvidence` attributes = actor/targets/reason/before-after/time, no secrets (`06:143–155`) — exactly BR-IMP-10 + INV-10; A9 forbids reporting effect without trace.
- **No UC-06/08/22 creep.** Approval here is archive-change review (state set `待审核/通过/驳回`, `06:130`), explicitly separated from claim states (`DGM-STATE-02`); ownership ambiguity registered as B-10 (`01:238`) and consistent with `02-用例模型与规约.md:192–198` (step 4 = 审核通过后生成生效版本, secondary actor 有权管理员) and SRS §11.4.
- **No implementation design.** Grep of the package returns only disclaimers (e.g. `04:6`, `03:10–11`, `06:10`, `01:92`); attributes are business-level.
- **Scope discipline.** `watchdog_diff` shows the whole package as untracked additions only; no edit to `03-AC-UC-分析模型追踪.md`, `automation/analysis-class-registry.md`, `unresolved-decisions.md`, SRS/SPEC/global UML. Pre-existing modifications (`docs/uml/analytics`, `docs/uml/collection`, `scripts/docs`) untouched, as `08 §1` claims.
- **Traceability accuracy spot-checked:** FR06-01…05 → UC-07/UC-22 rows (`03:68–72`) and UC-07 reverse row (`03:131`) match `01 §10`; diagram IDs SEQ-12/VOPC-08 are unique in repo and match `progress.json:123–124`; global citations are real — `DGM-CLASS-01-analysis.puml:107` `ProfileCtl ..> Scholar`, `:120` `Auth -- Scholar`, `:54–56` User "对象授权校验()", `DGM-SEQ-04-profile.puml:34–36`; SVG viewBox 3264×4076 and 2329×1343 match `08 §4.1`, no "Syntax Error", legend text present.

**Findings (all P2 / MINOR, report-only)**

| ID | Sev | Location | Evidence | Smallest fix |
|---|---|---|---|---|
| R1-1 | P2 | `01-UC-07-use-case-spec.md:212` BR-UC07-01 vs `04-sequence-messages.md:53–57,64–68` and `08-self-check.md:48` | BR-UC07-01 asserts "每次受保护写入都在服务端校验账号状态＋授权主体＋授权范围" and `08` answers "每次受保护写入是否重新校验授权 = 是", but `04 §3.1` scopes M3–M6 to submission and the approve-apply write (M44/M45) is guarded only by the reviewer path (M38/M39). The case "提交者授权在待审期间失效/范围收窄后仍被批准生效" is neither modelled nor listed among B-01～B-11, while SRS §8/BR-IMP-05 tie archive writes to object authorization. | Either add one line to `04 §3.4`: "M44/M45 前按 BR-UC07-01 只读重读目标授权（M4 同构）" **or** narrow BR-UC07-01 to "管理者发起的每次受保护写入" and register the residual as **B-12** in `01 §11` + `08 §6` (one row each). Not a business-semantics change either way. |
| R1-2 | P2 | `diagrams/DGM-SEQ-12…puml:72–80`; `04:70–76`; `05-vopc.md:21` | M39's guard "待审变更仍为待审核且版本未变" and M45's "以该待审变更生成生效版本" require the pending change's current status/version/content, but the 审核 group has no read message to `ScholarChangeRequest` — the only `Ctl→Change` reads are M32/M33 in the separate submitter-query group. `05` cites "M32 读取" for the Change relation, which sits outside the approval path. | Add one read message before M39 (e.g. `Ctl -> Change : 读取当前状态/版本/待审变更内容`) or extend M39's duty text to name `ScholarChangeRequest` as its read source; update `04` row and VOPC label. |
| R1-3 | P2 | `07-global-model-diff.md:30` | Cites "`DGM-USECASE-07` 第 27 行 `Claim ..> MaintainScholar : 仅批准后授权`"; line 27 is `ScholarManager --> MaintainScholar`, that dependency is line **31** (`DGM-USECASE-07-profile.puml:27,31`). | Change "第 27 行" → "第 31 行". |
| R1-4 | P2 | `diagrams/DGM-VOPC-08…puml:19` | Edge label groups `M31/M37` under "返回结果" though both are UI→Ctl requests (`puml:64,71`); the same lumping is repeated in `05-vopc.md:33` and `06:34`. | Split the label: "M2/M31/M37 请求；M7/M10/…/M51 返回结果". |

**Not verified by me (supervisor to run):** `python3 scripts/docs/check_documentation.py`, `python3 scripts/docs/verify_69_ac.py`, `python3 scripts/docs/verify_scoped_srs.py`, and the M1–M52 extraction snippet in `08 §4.4`. I confirmed the final artifacts exist and the SVG dimensions/legend match, and that M1–M52 appear once each in the `.puml`; I did not re-execute the scripts, so `08`'s PASS outputs (1492 links, 69 AC, `{FR:54, IN01:4, NF:11}`) remain author-claimed.

**Merge verdict: OK with notes** — the authorization/audit/isolation requirements the task names are all satisfied with source-level proof; R1-1 is the only item worth a decision (verify-at-apply vs register B-12), and it is a one-line edit.