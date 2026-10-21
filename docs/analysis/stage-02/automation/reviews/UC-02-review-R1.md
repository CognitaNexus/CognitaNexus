## Review

REVIEW_STATUS: ISSUES
SEVERITY: MINOR

**Scope read:** all of `docs/analysis/stage-02/uc-02/` (README, 01–08), both `.puml`, both rendered `.svg`/`.png` (viewed), plus SRS V2.2 FR02 (`SRS:101-111`), FR16 (`:311-321`), NF01/NF02 (`:408-420`), §2.1/§2.2 (`:36,40`), §7 BR-IMP (`:373-383`), §7.1/§7.2/§8/§6.4, SPEC V1.0 §3 (`:35-40`), invariants (`:45`), DD-08/DD-11 (`:131,134,318`), and trace rows `03-AC-UC-分析模型追踪.md:54-55,103-104,126`.

**Correct (verified, not assumed)**
- **AC coverage is exact and non-inventive.** `01:139-141` claims FR02-01/02, FR16-01/02, NF01-01, NF02 (reference only) — identical to the UC-02 ownership rows at `追踪:126`; `01:143` explicitly refuses to claim FR16-02's 规模核对 action (UC-16). No new/rewritten AC or BR found.
- **BCE/VOPC discipline.** VOPC class set = exactly the 6 sequence participants (`05:9-16`, `DGM-VOPC-03` render shows 6 classes); 访客/原站 kept as actors, not classes (`DGM-SEQ-07:6-7`, legend). 12 rejected candidates with reasons (`03:68-80`).
- **Entity separation is grounded, not asserted:** WorkEntity≠SourceRecord (`AC-IMPL-FR02-01`, INV-02 `SPEC:45`), ≠WorkVersion (FR02 + DD-08), ≠Authorship (`SRS §7.2`, INV-05) — `03:22-30`, `05:28-32`, `06:44-84`. No `Work—Scholar` edge, with rationale (`05:2.4`).
- **不可访问 vs 执行失败 vs 外链失败 separated** in messages M5/M6, M7/M8, M27 (`04:24-32,54`), guards on `SRS:412`.
- **No full-text promise:** only M23 hands over a URL (`04:50`, `SRS:36`); "全文托管" explicitly excluded (`02:75`).
- **No implementation design:** repo-wide grep for 数据库/API/Java/DTO/SQL/缓存 in `uc-02/` returns only disclaimers; attributes are business-level only (`06:1-10`).
- **Citations check out:** §7.1 fields, §8 permission row, BR-IMP-01/02/04/07/10/11, INV-01/02/05, DD-08/DD-11, SPEC §3 `data_origin`/`time_events[]`/`source_url、rights`/“缺类型或名称不得…”, §2.2 “外部来源只提供…原站地址” all match source lines.
- **Renders are real:** no `Syntax Error` in either SVG; PNG shows M1–M28, both `alt` blocks and the legend legibly.

**Findings**

| ID | Severn | Location | Evidence | Minimal fix |
|---|---|---|---|---|
| R1 | MINOR | `01:63-73` | 需求来源 column of every basic-flow row ends in a self-citation (`…；E1`, `…；E11`), i.e. each event cites itself as its source. | Delete the trailing `；En` tokens in rows E1–E11 (sources FR02/SRS/AC remain). |
| R2 | MINOR | `DGM-SEQ-07:21-22`; `04:44` | `alt 读取依赖失败或超时（A3）` sits directly after M4, whose label is only “成果事实 / 不存在·非公开·待审·无效” — no message can produce the guard's condition; and A3 guards only M3, so read failures/timeouts in M9–M18 (SourceRecord/WorkVersion/Authorship) are unmodeled although A3 is generic and NF01 forbids failure posing as “不存在/无来源”. | Append “/读取失败或超时” to M4, and add one line in `04 §3.1` that M5/M6 is reachable from any entity read M3–M18 (or wrap M9–M18 in a `group 读取失败/超时`). Re-render. |
| R3 | MINOR | `DGM-SEQ-07:47-51`; `04:52-56` | E11 is a *basic-flow* step (`01:73`), but M26/M27/M28 are nested only inside `alt 原站外链失效（A2）`; the success path of the external site returns nowhere. A basic step cannot live under an exception guard. | Move M26 outside the `alt` (basic flow after M25) and keep only M27/M28 guarded by A2; re-render. |
| R4 | MINOR | `05:13,25,69`; `06:23,30,31,91`; `DGM-VOPC-03:22,33` | M27's sender/receiver are WorkDetailUI→访客 (`04:54`), yet it is listed as a UI↔Coordinator message and printed on the `UI -> Ctl` VOPC edge (“M2/M5/M7/M20/M27”). Same defect class as UC-04 F3. | Drop M27 from those lists/edge labels (keep M2/M5/M7/M20); re-render VOPC. |
| R5 | MINOR | `01:85` (A7); `06 §6`; `08 Q-03` | SPEC DD-08 (`SPEC:131`) requires 无明示版本关系时“保持独立**并标疑似**” (also `SPEC:318` T20). UC-02 cites DD-08 only for “不显示版本关系” and Q-03 presents 疑似 as fully unfrozen — understating an existing SPEC obligation. | In Q-03 record that SPEC DD-08/T20 already mandate a 疑似 flag internally, and scope the question to “该标记是否在公开详情呈现、对谁呈现”. |
| R6 | MINOR | `06:18-19`; `04 §3.1` | NF01 body (`SRS:410`) also requires “加载…和处理中可区分”；UC-02 models only 不可访问/执行失败/外链失败, and neither covers nor excludes a 处理中/加载 state (though `AC-IMPL-NF01-01` only tests empty/denied/failed). | Add “处理中/加载” to WorkDetailUI's state list (`06:18`) plus one sentence in `04 §3.1`, or register the exclusion as a Q item. |
| R7 | MINOR | `06:44` | SRS §7.1 lists 作者顺序 inside 论文专属字段; `06` moves role/order to Authorship with a good rationale but leaves no Q entry, so the deviation is only in prose. | Add Q-13 “作者顺序/角色归属：§7.1 列为论文专属字段 vs 分析层归 Authorship” to `01 §11` + `08 §5`. |
| R8 | P2 (parent-owned) | `docs/analysis/stage-02/README.md:51-58` | Diagram index stops at `SEQ-06`/`VOPC-02`; `DGM-SEQ-07`/`DGM-VOPC-03` are unregistered (IDs themselves are unique). | Parent to append two rows; no UC-02 edit. |

**Not verified (no shell):** `plantuml`, `check_documentation.py`, `verify_69_ac.py` were not re-run; `08:135-146`'s quoted outputs (1284 links, 30 pairs, PASS) rest on the author's record. Renders were instead confirmed from the committed SVG/PNG. No MAJOR/CRITICAL issue found → `AUTO_REVIEW_PASS` (MINOR list only).

**Merge verdict: OK with notes** (fold R2–R5 before the S02 package freeze; R1/R6/R7 are text-only).