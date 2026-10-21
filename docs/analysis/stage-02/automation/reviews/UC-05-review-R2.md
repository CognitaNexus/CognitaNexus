## Review

REVIEW_STATUS: ISSUES
SEVERITY: MAJOR

Scope: all 9 files + both `.puml` + both regenerated SVG in `docs/analysis/stage-02/uc-05/`, SRS V2.2 FR05 (§6.3 L145–159), FR15/NF06/NF03/NF01 (L297–307, L412, L426, L448), SRS §11.3 (L490), `docs/uml/claim/DGM-STATE-02-claim-request.puml`, prior `docs/analysis/stage-02/automation/reviews/UC-05-review-R1.md`. No files modified.

### Correct (verified fixes)
- **No invented AC IDs**: grep for `AC-05`/`AC-05-0*` across `uc-05/**` returns zero hits; `DGM-VOPC-06-claim.puml:24–31` legend now cites `AC-IMPL-FR05-03/04`, and the regenerated `generated/DGM-VOPC-06-claim.svg:48,51` shows the same IDs — P1-1 resolved, render in sync.
- **P2-3 coverage labels fixed**: `01` §10 rows for `AC-IMPL-FR05-03`/`04` now read “提交侧/部分覆盖”；`FR05-02` keeps 部分覆盖.
- **P2-4 outcome enumeration converged in `06`**: §2 analysis mechanism and §3 `outcome` both list the same six values（待审核/待补充/拒绝/失败/无申请/无权）.
- **P2-2 NF06 read side**: `04` §3.5/§3.6 now state the visibility guard is inherited from `ClaimApplication` ownership filtering (M28/M29) and no separate message is sent to `ReviewEvidence` — defensible and diagram-consistent.
- **模型其它面**：7 类、M1–M36、A1a/A1b、A2–A8 与 `generated/DGM-SEQ-10-claim.svg:37,43,45` 一致；`05` §2 只读虚线与排除项未回退。

### Findings (remaining, blocking)

**P1-A（P1-2 未闭环）— `待提交` 已入集但无来源、未登记，且与 `07`/`03` 冲突。**
- `01-UC-05-use-case-spec.md:101` BR-UC05-04 与 `06-class-definition.md:78,81` 现列六态（含 `待提交`），来源写 `FR05；SPEC §3；DGM-STATE-02`。
- 证据相反：FR05 规范性要求（SRS L147）与 `SPEC §3`/`DGM-STATE-02` 均只有 `待审核/待补充/通过/驳回(/已通过/已驳回)/争议`——`DGM-STATE-02-claim-request.puml:8–12` 无 `待提交` 状态、`[*]→Pending : 本人提交` 直接进入待审核；全仓库含 `待提交` 的只有 SRS §11.3（L490）。
- 同一包内仍在五态：`07-global-model-diff.md:25`（“申请状态集…一致”）、`07:92`（状态名口径行）、`03-bce-identification.md:24`（C5 状态枚举）——因此“状态集已含 `待提交` 且已界定范围”只做到一半，且新增了一条无依据的引用。
- `08-self-check.md` §6 仍止于 B-11（仅登记 `通过/驳回` vs `已通过/已驳回`），`待提交` 与 `DGM-STATE-02` 的缺口未登记；`01` §9 迁移表也没有 `待提交` 行，该状态的进入/归属因此未定义。
- 最小修复：将 `01:101`/`06:78,81` 的来源改为 `SRS §11.3`；在 `08` 新增 B-12 记录“`待提交` 为 SRS §11.3 草稿态，UC-05 不产生，`DGM-STATE-02`/`SPEC §3` 缺该状态”；`01` §9 加一行标注归属（非 UC-05）或说明未建模原因；同步 `03` C5、`07` §1/§5 的五态枚举口径。

**P1-B（P2-1 修复自相矛盾）— A1b 声称记录 `competitionFact`，但模型无读取授权消息。**
- `04-sequence-messages.md` §3.2：“A1b 同样记录目标当前授权事实（`competitionFact`），**但不读取/修改授权**”；§3.6 重复“A1b 同样记录竞争事实”。
- 与模型相反：`diagrams/DGM-SEQ-10-claim.puml`（渲染 `generated/DGM-SEQ-10-claim.svg:37`）中 A1b 只有 M13/M14，M17/M18 仅位于“证据齐备”分支；`04` §1 亦声明 A5 由 M17/M18/M19/M23 表达；M13 职责仅“保存申请人、目标、状态、缺失项与理由”；`01` §5.2 无竞争事实条目。既不读授权就不可能记录事实，实现者无法判断 A1b 是否需读 `ScholarAuthorization`。
- 最小修复（二选一，勿同时做）：(a) 删除 §3.2/§3.6 的“记录竞争事实”从句，明确 A1b 不记录 `competitionFact`，并把该事实仅归 A5/证据齐备路径（M19）；或 (b) 在 A1b 分支加 M17'/M18' 读取并写入消息表、图、`01` §5.2。

### Notes (non-blocking, only if above is touched)
- `06` §7 “向他人读取请求拒绝”与 `04` §3.6 “不向 `ReviewEvidence` 发独立消息”措辞冲突（`05` §1 仍把 A6 列为 `ReviewEvidence` 依据）——建议只在 `06`/`05` 注明该守卫由归属判定继承。
- `01` §10 `FR05-02` 行交叉引用“8 节‘部分覆盖’”在第 8 节不存在该字样；`04` §3.6 “四类结果”与 `06` 六态枚举粒度不同，建议统一措辞。

### Not independently verifiable
`08` §4.1 命令输出、HEAD `4209cf0`、PNG 目视检查无法复跑。Supervisor 应运行：`python3 scripts/docs/check_documentation.py`、`python3 scripts/docs/verify_69_ac.py`、`python3 scripts/docs/verify_scoped_srs.py`，并在改动 `.puml` 后重渲染 SVG/PNG。

**Merge verdict: BLOCK** — P1-A、P1-B 均为一次性小改，但当前包仍同时存在无依据的状态枚举与无消息支撑的行为声明，不能按 `08` §7 的 `STATIC COMPLETE` 签认。