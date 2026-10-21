## REVIEW_STATUS: ISSUES
## SEVERITY: MAJOR

### Scope read
`AGENTS.md`; `docs/analysis/stage-02/automation/instructions/{REVIEWER,ANALYST}.md`; `automation/analysis-class-registry.md`; SRS V2.2 `docs/srs/CognitaNexus-Shared-SRS-V2.2-69AC.md` (FR03 §113–127, FR04 §129–143, BR-IMP §373–383, NF03 §422–426, §12 §500–510); SPEC `CognitaNexus-Core-SPEC-V1.0.md` (INV table §45, no registration section); S01 `analysis/stage-01/README.md:10`, `05-分析机制.md:7–9`; trace `analysis/stage-02/03-AC-UC-分析模型追踪.md`; global `DGM-CLASS-01-analysis.puml`, `01-BCE分析类模型.md:49,53`; all 9 UC-04 docs + both `.puml` + generated SVG. Read-only; nothing modified.

## Review

**Correct (verified, not assumed)**
- Event flow E1–E9 / A1–A7 map to real baseline text: FR04 line 131 (一次性创建 User+唯一UserProfile、幂等), AC-IMPL-FR04-03 line 139, AC-IMPL-FR03-03 line 123 (零成果非404), AC-IMPL-FR03-04 line 125 (同名不合并), BR-IMP-05 line 377 (UserProfile 由所属 User 直接编辑), §12 lines 504–508. No invented AC; no 70th AC claimed (`02:76` explicit).
- BCE reasoning is genuinely event-flow-derived (`03:19–58`), not back-filled from the global graph; User/UserProfile/Scholar/Claim/Auth/Authorship/Work separation is stated with evidence and Scholar/Authorization/Claim/Authorship/WorkEntity/Session/AddressAllocator are rejected with reasons (`03:79–92`), matching FR04 "不创建Scholar" and BR-IMP-05.
- VOPC == sequence objects exactly (4 classes, `05:11–17`, `DGM-VOPC-01:9–12`); only `User 1 — 1 UserProfile` carries a multiplicity and it is sourced (SRS §12 line 504, AC-FR04-03); control/boundary links deliberately carry none (`05:27–31`) — no evidence-free `1..*` fill.
- No implementation design: no DB/table/key-type/API/framework/message-bus anywhere; atomicity/idempotency/persistence are phrased as business mechanisms (`01:66` note, `04:56–58`, `06:§7`). SPEC has no registration rules, so no SPEC rule was silently re-decided.
- Sequence branches separate non-validation (A1), duplicate/retry (A2), creation/address failure (A3) and zero-result empty state (A4 via M15); public-read/protected-write and cross-UC behaviour are explicitly kept out of the diagram (`04:§3.5–3.6`).
- Diagram exports are real and in sync: `diagrams/generated/DGM-SEQ-05-register.svg` embeds the current source (M1–M17, `alt` blocks, legend) and `MD5` comment; PNG/SVG for both models exist.
- Global-model citations check out: `07:§1` line refs to `DGM-CLASS-01-analysis.puml` 13 / 23–25 / 49–52 / 53–57 / 58–61 / 116 and to `01-BCE分析类模型.md` 49 / 53 are accurate; `07:§4` correctly flags the missing User—Auth relation.

**Findings**

| ID | Sev | Location | Evidence | Minimal fix |
|---|---|---|---|---|
| F1 | P1 | `08-self-check.md:51–88, 143–154` | `ANALYST.md` 图与验证 requires `python3 scripts/docs/check_documentation.py`; UC-04 records only `verify_69_ac.py` + `verify_scoped_srs.py` + plantuml. UC-01 does record it (`uc-01/08-self-check.md:70`). "STATIC COMPLETE" (`08:143`) is therefore unsupported by the mandated check. | Run that command from repo root, paste actual status/errors into `08 §4.3`, and downgrade `§6` if it reports errors. (Supervisor must run it; I cannot execute shell.) |
| F2 | P1 | `01-UC-04-use-case-spec.md §10 (AC覆盖)` | Reverse-trace assigns `AC-IMPL-NF03-01` to UC-04 (`03-AC-UC-分析模型追踪.md:112,128`) and the UC does implement its 停用/未登录拒绝 part (`01 §7 A6/A7`), but §10 lists only FR04-01..04 + FR03-03/04 — one of the 7 traced ACs is silently absent from the UC's own coverage claim. | Add a row: `AC-IMPL-NF03-01 | 未登录/停用受保护写被拒绝（A6/A7）；跨用户写入、未授权读审核材料不在本UC流程内，见UC-05/06/07 | A6；A7`. Alternatively correct the trace row — but not both silently. |
| F3 | P2 | `05-vopc.md:20`; `DGM-VOPC-01-register.puml:11` | Arrow is `UI --> Coordinator` yet labelled with M4/M7/M13/M16, which are Coordinator→UI returns (`04:26,29,42,44`; puml `--> `). One directed edge carries two opposite message sets. | Relabel `M2 请求；M4/M7/M13/M16 返回结果` (or split into `-->` / `<--`), then re-render. |
| F4 | P2 | `02-event-flow-trace.md §3 row A3` | Cites `SRS §12“一次性创建”`; the phrase 一次性创建 is in FR04 (line 131); §12 line 506 says 注册事务创建唯一UserProfile和稳定公开地址. | Change to `FR04“一次性创建”；SRS §12（注册创建唯一UserProfile+稳定地址）`. |
| F5 | P2 | `01 §7 A1` vs `04:§1, M4–M5`; puml `alt 信息不满足注册规则` | `04:§1` says the sequence covers only 主流程 + 创建失败/重复请求, omitting the A1 validation branch that the diagram actually draws. | Add "A1 校验失败" to `04 §1` scope sentence. |
| F6 | P2 | puml `M10 创建唯一UserProfile`; `04:31`; `01 §6 step 4`; `BR-UC04-01` | 04's responsibility text says "创建与User绑定的唯一主页" and the 1:1 is a claimed postcondition, but no message in the diagram establishes the User↔UserProfile binding. | Relabel the message `M10 创建与该User绑定的唯一UserProfile` (04 table + puml) and re-render; no new message needed. |
| F7 | P2 | `04:28,63`; puml `M6 (A-01)`; `08:101` | `A-01` is referenced three times as an id but never defined anywhere in the package; `08:101` calls it "M6分析假设A-01" without content. | Either add an `A-01` row (幂等识别键未冻结) to `08 §5` or delete the token from M6/§3.2/puml. |
| F8 | P2 | `01 §5.1-7`, `01 §8 BR-UC04-06`, `02 A7`, `06 §5` | "注册邮箱/联系方式默认不公开" is sourced to BR-IMP-11, which per SRS line 383 governs 不默认**采集**; the actual supports are FR06 line 163 (隐私字段默认不可见), §12 line 506, and the Q-06 review decision (`08:117`). | Cite `FR06/AC-IMPL-FR06-*；Q-06` in addition to BR-IMP-11 (keep BR-IMP-11 only for 秘密不公开). |
| F9 | P2 | `03-AC-UC-分析模型追踪.md:58–59` vs `60–63`; `07 §6` | FR03-03/FR03-04 forward rows list UC-04 but their model column lacks `SEQ-05/VOPC-01`, while FR04 rows have it; `07 §6` claims only "FR04行补上". Forward/reverse trace asymmetric for the ACs UC-04 claims to cover. | Append `SEQ-05 / VOPC-01` to rows 8–9's UC-04 coverage (parent agent owns this shared file). |
| F10 | P2 | `07 §4`; `04 §4` | `07 §4` cites "第116–120行" for User—Claim/Claim—Scholar/Claim..>Auth/Auth—Scholar; those are 117–120 (116 is the 1:1 line). `04 §4` maps "M9–M17/E3–E8", but M12–M14 are A3. | Fix the range to 117–120; add "M12–M14/A3" to the mapping row. |

**Unverifiable / for the supervisor**
- `08 §1` branch (`analysis/s01-s02-69ac`) and HEAD `cafe74b…` cannot be checked without Git access; treat as self-reported. `08 §4.2` PNG "已目视" is a human claim.
- Open decisions correctly left open rather than invented: Q-01 字段集, Q-02 幂等键/保留时长, Q-03 URL 格式, Q-06 公开字段集, Q-07 会话 (`01 §11`, `08 §7`) — none re-written as approved rules; consistent with A1c (`stage-01/README.md:10`) and S01 freeze.

**Conclusion: `REVIEW_BLOCKED`-free but not PASS** — no fabricated requirement, no scope violation, no implementation design, no Scholar/Auth contamination; two traceability/verification defects (F1, F2) must be closed first. **Merge verdict: OK with notes** for the modelling content, **BLOCK** on F1+F2 documentation closure.