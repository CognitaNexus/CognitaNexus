## Review

REVIEW_STATUS: ISSUES
SEVERITY: MAJOR

**Scope read:** all 9 files of `docs/analysis/stage-02/uc-22/` + both `.puml` + regenerated `DGM-SEQ-09-profile-edit.svg`; SRS V2.2 `:119-125,161-183` (FR03/FR06/FR07 AC), `:383`; prior `automation/reviews/UC-22-review-R1.md` (actual path `docs/analysis/stage-02/automation/reviews/`); `unresolved-decisions.md:20`.

**Correct (verified fixes)**
- **F2 fixed** — `06:66` now reads `privateExcludedData`：默认不公开的账号信息/联系方式（不进入投影）；认证材料属UC-05/06 ReviewEvidence，凭据不在UserProfile数据范围. No credential/review-material attribute remains on `UserProfile`; consistent with `03:51`, `05 §4`.
- **F3 fixed** — `puml:36` (`alt 保存失败（A4）` before M18) **and** `puml:41` (`alt 公开投影生成失败（A4）` after M18), both returning M16/M17; `04:42` M16 basis now `SPEC §8；NF01`; `01 §7 A4` basis now `SPEC §8；分析推导`. `04:123` A4 coverage is now reachable.
- **F4 fixed** — `01 §10` marks `AC-IMPL-FR06-01` (“边界：…验证在 UC-07”), `AC-IMPL-FR03-01` and `AC-IMPL-FR03-02` (“边界，不主张覆盖”) consistent with the visitor/portal-manager wording of `SRS:119,121` and AC-IMPL-FR06-01 (`SRS:167`).
- **F5 mostly fixed** — `01 §5.1-6`, `06 §6` audit attributes and `04:42` failure basis no longer rest on `AC-IMPL-FR06-02/04`.
- **F6 fixed** — `unresolved-decisions.md:20` now registers `D-13 | UC-22/17 | 审计证据类命名（AuditEvidence / Evidence / ReviewEvidence）`; `01 §11 B-07` cites D-13.
- Artifact freshness: SVG regenerated and contains the new structure (`…svg:41-53`: `M18`, `alt 公开投影生成失败（A4）`, `else 投影成功`, `alt … 审核（A5·B-01 待确认）`, `M19`, `M21 返回已提交待审；公开版未变更`), matching current `.puml`. A5 result wording no longer says “已公开生效”.

**Finding**

| ID | Sev | Location | Evidence | Minimal fix |
|---|---|---|---|---|
| F1-residual | MAJOR (P1) | `diagrams/DGM-SEQ-09-profile-edit.puml:40-45`; `04-sequence-messages.md:88` (also `:82`, `08:126`) | The A5 gate is still drawn **after** projection generation: `puml:40 M18 生成公开投影` → `puml:44 else 投影成功` → `puml:45 alt 公共资料策略要求审核（A5）` → `puml:46 M19 公开版保持旧值`. So in the review-required case the projection is regenerated from the values just written at M15 (`puml:35`), i.e. pending values still reach the projection step, and M19 then asserts “公开版保持旧值” — the exact FR06 “待审版本不覆盖公开版” / BR-IMP-10 hazard R1 F1 named. The package’s own text contradicts the diagram: `04:88` states “若策略要求审核，则 M18 不执行、M19 登记待审”, and `04:124`/`04 §4` map A5 to M19 only. R1’s minimal fix (place M18 inside `else 即时生效`) was applied as a branch split + relabel, not as the required reordering. | One-line reorder: move `puml:40` M18 (and its `alt 公开投影生成失败（A4）` block) into `else 即时生效`, so A5 registers the pending version without any projection of pending values; keep `puml:45` A5 `alt` immediately after M15. Alternatively, if M18 must stay upstream, relabel it as “按策略判定并生成候选投影（未生效）”, state that the public projection is unchanged in A5, and rewrite `04:88` to match — but then M21/M22 must not follow a projection that already published. After editing, re-render both SVG/PNG and fix `opt`→`alt` at `04:82` and `08:126`. |

Non-blocking leftovers (P2, report-only): `02:34` A4 row and `02 §4` last row still cite `AC-IMPL-FR06-02` for failure/无变化; `04:102` and `06 §6` Do still attribute the *success* audit to `AC-IMPL-FR06-04` (that AC covers the rejected cross-user write, `SRS:173`).

**Supervisor-run verification required:** `plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-22/diagrams/DGM-SEQ-09-profile-edit.puml` (+`-tpng`), then `python3 scripts/docs/verify_69_ac.py`, `verify_scoped_srs.py`, `check_documentation.py`. I executed none.

**Merge verdict:** BLOCK — F2–F6 verified fixed; F1 remains MAJOR on one ordering/label line plus the `04:88` contradiction, a single narrow edit with re-render.