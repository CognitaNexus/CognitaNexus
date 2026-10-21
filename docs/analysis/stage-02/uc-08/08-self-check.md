# UC-08 自检与待评审问题

**版本：** V1.0（2026-10-21）
**检查性质：** 静态文档/模型检查；不是产品运行测试，不是教师评分，不是人工评审签字。
**范围：** 只检查 UC-08 分析包，以及本包的渲染与文档链接结果。

## 1. 范围与基线检查

| 检查 | 结果 | 证据 |
|---|---|---|
| 分支 | `analysis/s01-s02-69ac` | `git branch --show-current` |
| 正式 SRS/SPEC/S01 | 未修改 | 本轮只新增 `docs/analysis/stage-02/uc-08/`；`git status --short` 中不出现 `docs/srs`、`docs/spec`、`docs/analysis/stage-01` 的新改动 |
| 69 条 AC | 未新增、未删除、未改号 | `python3 scripts/docs/verify_69_ac.py` → PASS（§4.2） |
| 全局 UML / 认领序列 / 归属判定序列 / 关系状态图 / 用例图 | 未修改 | 只读比对，见 07 |
| 其他 UC 目录 | 未修改 | 未写入 `uc-01`–`uc-07`、`uc-22` |
| 共享追踪/登记文件 | 未修改 | `03-AC-UC-分析模型追踪.md`、`automation/analysis-class-registry.md`、`automation/unresolved-decisions.md` 保持原状；登记建议见 07 §6 |
| 其他成员未提交修改 | 未覆盖 | 工作区原有的 `docs/uml/analytics`、`docs/uml/collection`、`scripts/docs` 修改保持原状 |

## 2. 教材步骤对应

| 教材步骤 | 本包证据 | 状态 |
|---|---|---|
| §5.4.1 p154–155 完善用例文档 | [01-UC-08用例规约](01-UC-08-use-case-spec.md) | 已完成（未决项见 §6） |
| §5.4.2 p155–161 识别分析类 | [03-BCE分析类识别](03-bce-identification.md)、[02-事件流与需求来源](02-event-flow-trace.md) | 已完成；11 个参与类 |
| §5.4.3 p161–181 分析交互 | [04-顺序图与逐消息职责](04-sequence-messages.md)、`DGM-SEQ-13-authorship-correction` | 已完成；M1–M76 |
| §5.4.4 p181–183 构造 VOPC | [05-VOPC与关系依据](05-vopc.md)、`DGM-VOPC-09-authorship-correction` | 已完成；非全局 BCE |
| §5.5.1 p188–190 职责 | [06-分析类定义](06-class-definition.md) Do/Know/候选分析操作 | 已完成 |
| §5.5.2 p190–191 属性 | 同 06 业务属性表 | 已完成本 UC 可证实部分；共享字段清单、请求状态集合、可核验最低集合标待确认 |
| §5.5.3 p192–200 关系/多重性 | 同 05 §2、06 §13 | 六条业务关联标 `0..*:1`/`1:0..*`；`Auth→Scholar` 沿用未定；控制/边界到实体不定义 |
| §5.5.4 p200–201 分析机制 | 同 06 §14 | 已定义 13 项机制 |
| §5.5.5 p201–204 统一分析类 | [07-与全局模型差异](07-global-model-diff.md) | 只提交 R5 输入，不重构全局图 |

## 3. 模型完整性检查

| 检查 | 结果 | 说明 |
|---|---|---|
| UC-08 参与者 | 主要：已获授权学者（用例图“门户管理者”）；次要：有权审核的管理员；只读边界：访客/登录用户 | 依 FR07 参与者、§11.4、`DGM-USECASE-05` |
| 主流程编号 | E1–E15 | 每步在 01/02 有来源 |
| 备选/异常 | A1–A14 | 均有正式来源或标注分析推导/待确认 |
| BCE 候选 | 11 个：`AchievementCorrectionUI`/`AchievementCorrectionCoordinator`/`User`/`ScholarAuthorization`/`Scholar`/`Authorship`/`WorkEntity`/`SourceRecord`/`CorrectionRequest`/`ReviewEvidence`/`AuditEvidence` | 见 03 §2 |
| 被拒绝候选 | `UserProfile`、`ClaimApplication`/`ClaimCoordinator`/`ClaimReviewCoordinator`、`ScholarChangeRequest`、`WorkVersion`、`CollectionTask`/`SourceAdapter`、`DedupCandidate`/`MergeDecision`、`IdentityCandidate`/`ScholarMerge`/`AliasMap`、`AttributionAssignment`、`CollaborationEdge`/`GraphCtl`、`DisputeCase`/`PendingTodo`、`Notification`/`SlaTimer`、`Session`/`CredentialContext`、`ReviewerRole`/`PermissionMatrix`、`FieldPolicy`、`PublicationSubmission`/`Manuscript`、`EvidenceFile`、独立 `EffectiveVersion`、`AuditCoordinator`、展示投影/DTO | 见 03 §4 |
| 顺序图消息 | M1–M76 | 每条有发送者/接收者/事件步骤/职责/依据；覆盖核对见 04 §3；脚本核对无缺号（§4.4） |
| VOPC 类数 | 11 | 与顺序图对象集合一致（脚本核对，§4.4） |
| VOPC 是否含 UserProfile/Claim/Version/Identity/Graph | 否 | 只含 UI/Coordinator/User/Auth/Scholar/Authorship/WorkEntity/SourceRecord/CorrectionRequest/ReviewEvidence/AuditEvidence |
| 授权是否只来自 UC-06 | 是 | `Auth` 只读（M4）；无创建/转移/撤销消息 |
| 每次受保护写入是否重新校验授权 | 是 | M3/M4/M5/M6/M7（本人路径）与 M54/M57（审核者路径）分开校验 |
| 他人学者关系/同名另一学者是否被拒 | 是 | A2（M11–M13） |
| 是否只改本人这一条关系 | 是 | M6 读取归属、M15 守卫“仅本关系”、M23 只作用一条、M27 不扩散核对、M31 拒绝他人关系 |
| 单次确认是否扩散到其余 N 条候选 | 否（有反证消息） | M15/M27/M28；`AC-IMPL-FR15-06` |
| 已排除/争议是否可被本人改为本人确认 | 否 | A3（M16–M18）；`AC-IMPL-FR15-04`；BR-356 |
| 相反归属主张是否转争议 | 是 | A7（M19–M22） |
| 共享字段/解除是否经审核 | 是 | M30→M34–M44（登记待审）→M52–M76（决定与生效） |
| 待审是否覆盖公开值 | 否 | M40 登记时公开值保持；M43/M48 返回“公开未变更”；A9 拒绝覆盖；M73–M76 驳回后公开值不变 |
| 解除是否删除成果/来源/他人关系 | 否 | M67 只置该关系已排除；M68 核对保留；M71 返回保留事实 |
| 是否新建 WorkEntity 或提供投稿/出版 | 否 | 无新建消息；A14 范围边界；BR-UC08-11 |
| 补录是否可核验、是否造值 | 是（判定存在） | M35/M36；A5；缺失标未知不造值 |
| 原始来源事实是否被覆盖 | 否 | M35 只读；M62 只写公开规范值；BR-UC08-08 |
| 是否出现“审核通过 = 本人确认” | 否 | M23 才是本人确认；M62/M67 只写字段/已排除；`AC-IMPL-FR15-03` 边界 |
| 是否做身份合并/拆分/改派 | 否 | A11 转 UC-10；BR-UC08-15 |
| 是否建立合作边 | 否 | BR-UC08-10；建边属 UC-14/UC-15 |
| 证据三分是否保持 | 是 | `SourceRecord`（只读）/`ReviewEvidence`（材料＋可见范围）/`AuditEvidence`（留痕） |
| 拒绝/失败/待审/生效是否分开表达 | 是 | A1/A2/A3/A4/A10（拒绝）、A5（不可核验）、A6（失败）、A9（状态已变化）、M43/M48（待审）、M65/M71（生效）、M75（驳回） |
| 是否自造审核等级/SLA/审批链 | 否 | B-06；03 §4 拒绝候选 |
| 待确认项是否被写成已确认规则 | 否 | B-01～B-10 均标待确认，并在 01/02/04/06/07 就近标注 |
| 技术实现 | 未引入数据库/Java/API/框架/队列 | 只有分析职责 |

## 4. 实际执行的验证

### 4.1 PlantUML 渲染

实际执行（仓库根目录）：

```bash
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-08/diagrams/DGM-SEQ-13-authorship-correction.puml
PLANTUML_LIMIT_SIZE=8192 plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-08/diagrams/DGM-SEQ-13-authorship-correction.puml
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-08/diagrams/DGM-VOPC-09-authorship-correction.puml
PLANTUML_LIMIT_SIZE=8192 plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-08/diagrams/DGM-VOPC-09-authorship-correction.puml
```

结果（四次渲染均 `exit=0`，无 PlantUML 错误图）：

| 模型 | PUML | SVG | PNG | 目视检查 |
|---|---|---|---|---|
| UC-08 确认/补录/纠错序列 | 有 | 有（4121×5693 px；`Syntax Error` 计数 0） | 有（1.3 MB） | 已查看（整体 + 顶部/中部裁切）：13 条生命线；M1–M76；A1/A2/A3/A7/A6/A4/A5/A9/A10 与批准（字段/解除）/驳回分支、待审查询分组可读；中文与图例可读 |
| UC-08 VOPC | 有 | 有（3841×1800 px；`Syntax Error` 计数 0） | 有（437 KB） | 已查看：11 个类；`Ctl ⇢ Auth`（M4）与 `Ctl ⇢ Source`（M35）只读虚线与写入实线可区分；`Authorship 0..*→1 WorkEntity`、`0..*→1 Scholar`、`WorkEntity 1→0..* SourceRecord`、`CorrectionRequest 0..*→1 WorkEntity`、`0..*→0..1 Authorship` 可读；无 `UserProfile`/`Claim`/`Version`/`Identity`/`CollaborationEdge` |

> 序列图 PNG 使用 `PLANTUML_LIMIT_SIZE=8192`（默认上限 4096 px，实际 SVG 高 5693 px 已超默认上限）；SVG 不受该限制。
> 首次 VOPC 布局（`top to bottom` + `package` 包裹）连线交叉较多，改为不带 `package` 并调小 `nodesep/ranksep` 后重渲染，最终版为本包提交版；该调整只影响布局，不改变类与关系。
> 渲染成功只证明语法与可读性，不证明业务运行或评审通过；SVG/PNG 由 PlantUML 生成，未手工修改。

### 4.2 69 AC 与范围化 SRS

```text
$ python3 scripts/docs/verify_69_ac.py
PASS: 69 AC unique; SRS text matches ledger; trace has all 69; UC-01..19/22 present; A1c and scale markers present; active references resolve.
GROUPS: {'FR': 54, 'IN01': 4, 'NF': 11}
exit=0
```

```text
$ python3 scripts/docs/verify_scoped_srs.py
{
  "status": "DOCUMENT_STATIC_PASS_ONLY",
  "chapters": 13,
  "functional_requirements": 16,
  "nonfunctional_requirements": 8,
  "authoritative_ac": 69,
  "legacy_scope_trace_checked": true,
  "resolved_relative_links": 9,
  "a1c_scope": "checked",
  "scale_targets": "100万联调 / 500万最终 / 固定环境100并发",
  "human_joint_approval": "NOT_VERIFIED",
  "application_test": "NOT_EXECUTED",
  "performance_and_recovery": "NOT_RUN",
  "codearts": "NOT_VERIFIED"
}
exit=0
```

### 4.3 文档检查（本包新增后）

```text
$ python3 scripts/docs/check_documentation.py
{
  "status": "STATIC_PASS_ONLY",
  "relative_links": 1545,
  "uml_svg_pairs": 30,
  "errors": [],
  "external_urls_and_anchors": "NOT_CHECKED",
  "human_approval": "NOT_VERIFIED",
  "runtime_tests": "NOT_EXECUTED"
}
```

> 基线（本轮开始前）：`status: STATIC_PASS_ONLY`、`relative_links: 1492`、`uml_svg_pairs: 30`、`errors: []`（UC-07 结束时）。
> 本包在 `08-self-check.md` 尚未写入时（README 已链接 08）出现过 1 次中间态 `FAIL`：`relative_links: 1538`、`errors: ["docs/analysis/stage-02/uc-08/README.md: missing 08-self-check.md"]`。补写本文件后重跑为 `STATIC_PASS_ONLY`、`relative_links: 1545`、`errors: []`（上方为最终态）。该 FAIL 记录保留，不隐藏。
> `uml_svg_pairs: 30` 只统计 `docs/uml/` 下的源图；本包 `docs/analysis/stage-02/uc-08/diagrams/` 的 SVG/PNG 由 §4.1 的渲染命令与相对链接检查共同覆盖。

### 4.4 编号与结构脚本核对

```text
$ python3 - <<'EOF'  # 提取 puml 消息号并与 04 表逐行比对
puml messages 76 min 1 max 76
missing in puml []
dups []
doc rows 76 first 1 last 76
missing in doc []
dups in doc []
EOF
$ python3 - <<'EOF'  # 逐条比对 puml 与 04 的消息名称
name mismatches: 6   # 均为引号样式（"…" vs “…”），语义一致
EOF
```

结论：顺序图与 04 逐消息表的消息号 M1–M76 完全一致，无缺号、无重复；名称差异仅为中英文引号写法。

## 5. NOT RUN / 未验证

| 项目 | 状态 | 说明 |
|---|---|---|
| AC-IMPL-FR07-01/02/03、FR15-01～06、NF03-01、NF05-01/02 的实际验收 | NOT RUN | 需求层 AC 计划验收；本包只做静态分析 |
| 运行时行为、越权接口实测、并发待审冲突、重复确认幂等 | NOT RUN | 无实现代码 |
| 生效关系在检索/门户/统计/合作网络的传播实测（NF05） | NOT RUN | 无实现与负载 |
| 材料可见性的越权实测（NF06/NF03） | NOT RUN | 无实现 |
| 性能/容量（NF02/IN01） | NOT RUN | 与本 UC 无直接指标 |
| 人工评审/教师评分 | NOT VERIFIED | 自动静态检查不等于签认 |

## 6. 待评审/待确认

| 编号 | 问题 | 为什么不能自行决定 | 当前处理 |
|---|---|---|---|
| B-01 | 待审纠错请求的状态集合（是否含 `待补充/争议/已撤回`）与并存/并发规则 | FR07 与 §6.4 只要求“进入审核”，未定义该对象状态机 | 按 `待审核 → 通过/驳回` 建模；并存与冲突按 A9 版本守卫处理并标待确认 |
| B-02 | 本人可直接确认的关系状态集合与“确认范围”表达 | `DGM-STATE-05` 允许 `自动关联/待核验 → 本人确认`，但“确认范围覆盖本关系”与冲突条件未量化 | 只保留“无冲突可直接确认、有冲突转争议”的可观察分支 |
| B-03 | 补录是否只针对已收录成果、能否为未收录的已发表成果新建实体 | FR07 标题“已收录成果”与 `AC-IMPL-FR07-03`“不凭空生成 WorkEntity”支持“不新建”，但未明文禁止未来扩展 | 本分析按“只补录、不新建”处理（A14）；若变更需重划与 UC-09 的边界 |
| B-04 | 共享字段清单与可直写字段清单 | FR07 只说“共享字段…进入审核”，未列举字段 | 共享字段类请求一律纳入审核；不设字段直写分支 |
| B-05 | 审核证据最低集合与“可核验”判定口径（已登记 D-06） | 需求未冻结 | M36 只判定“可追溯到来源记录或材料”；最低集合标待确认 |
| B-06 | 纠错/解除审核的责任人、SLA 与超时兜底 | §11.3 只规定“超时仅提醒、不自动批准”（D-06） | 只保证“超时不等于通过”；不建模通知与计时器 |
| B-07 | 生效传播口径与时限（含历史 60 秒目标） | NF05 明确该量化目标不在本轮清单 | 只写“最终一致、不展示待审关系” |
| B-08 | 归属改派/拆分在 UC-08 与 UC-10 的入口分工 | FR15-S10 的关联用例同时列 UC-08 与 UC-10 | 本 UC 只登记申请状态，处置转 UC-10（A11）；分工待评审 |
| B-09 | 会话/凭据是否成为独立分析对象 | 基线未定义（沿用 UC-04/UC-07） | 作为请求上下文，不建模 |
| B-10 | 命名与追踪：`AchievementCorrectionUI`/`Coordinator` 与全局 `ScholarPortalUI`/`GovernCtl`/`ClaimCtl`；`CorrectionRequest` 与 `ScholarChangeRequest`/`ClaimApplication`；`ReviewEvidence`/`AuditEvidence`/`SourceRecord`/`Evidence` 统一；追踪表是否增补 `SEQ-13/VOPC-09` | 全局命名统一与共享追踪文件由 R5/父 Agent 维护 | 07 §2/§6 提建议，本包不改全局图与共享文件 |

## 7. 结论

```text
UC-08 R1: STATIC COMPLETE (check_documentation status=STATIC_PASS_ONLY, errors=0)
UML RENDER: 2/2 OK (SVG+PNG, no syntax error)
AC BASELINE: 69 UNCHANGED (verify_69_ac PASS)
RUNTIME TEST: NOT RUN
HUMAN REVIEW: 待评审
S02 STATUS: PARTIAL（总状态与共享追踪由父 Agent 维护）
```

UC-08 已按教材 §5.4.1–§5.4.4 与 §5.5.1–§5.5.4 完成一次可追溯的用例实现分析，并产出独立于全局 BCE 的 VOPC。B-01～B-10 仍需需求确认或 R5 处理；在评审人确认前，不把 UC-08 标为“业务已批准/教师已验收”。
