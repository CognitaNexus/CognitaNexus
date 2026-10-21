# UC-10 自检与待评审问题

**版本：** V1.0（2026-10-21）
**检查性质：** 静态文档/模型检查；不是产品运行测试，不是教师评分，不是人工评审签字。
**范围：** 只检查 UC-10 分析包，以及本包的渲染与文档链接结果。

## 1. 范围与基线检查

| 检查 | 结果 | 证据 |
|---|---|---|
| 分支 | `analysis/s01-s02-69ac` | `git branch --show-current` |
| 正式 SRS/SPEC/S01 | 未修改 | 本轮只新增 `docs/analysis/stage-02/uc-10/` |
| 69 条 AC | 未新增、未删除、未改号 | `python3 scripts/docs/verify_69_ac.py` → PASS（§4.2） |
| 全局 UML / 治理活动图 / 归属判定序列 / 关系状态图 | 未修改 | 只读比对，差异与冲突登记见 07 §3/§5 |
| 模块细化 `scholar-disambiguation.md` | 未修改 | 只引用；`FR15-S03` 与 SPEC ID-02/§6.5 的差异登记为 C-01（§6） |
| 其他 UC 目录 | 未修改 | 未写入 `uc-01`～`uc-09`、`uc-22` |
| 共享追踪/登记文件 | 未修改 | `03-AC-UC-分析模型追踪.md`、`automation/analysis-class-registry.md`、`automation/unresolved-decisions.md`、`automation/progress.json` 保持原状；登记建议见 07 §6 |
| 其他成员未提交修改 | 未覆盖 | 工作区原有的 `docs/uml/analytics`、`docs/uml/collection`、`scripts/docs` 修改与 `docs/reports`、`docs/srs/integration` 新增文件保持原状 |

## 2. 教材步骤对应

| 教材步骤 | 本包证据 | 状态 |
|---|---|---|
| §5.4.1 p154–155 完善用例文档 | [01-UC-10用例规约](01-UC-10-use-case-spec.md) | 已完成（未决项见 §6） |
| §5.4.2 p155–161 识别分析类 | [03-BCE分析类识别](03-bce-identification.md)、[02-事件流与需求来源](02-event-flow-trace.md) | 已完成；12 个参与类 |
| §5.4.3 p161–181 分析交互 | [04-顺序图与逐消息职责](04-sequence-messages.md)、`DGM-SEQ-15-identity` | 已完成；M1–M103 |
| §5.4.4 p181–183 构造 VOPC | [05-VOPC与关系依据](05-vopc.md)、`DGM-VOPC-11-identity` | 已完成；非全局 BCE |
| §5.5.1 p188–190 职责 | [06-分析类定义](06-class-definition.md) Do/Know/候选分析操作 | 已完成 |
| §5.5.2 p190–191 属性 | 同 06 业务属性表 | 已完成本 UC 可证实部分；阈值、候选规模、状态集合、时限标待确认 |
| §5.5.3 p192–200 关系/多重性 | 同 05 §2、06 §14 | 五条业务关联标 `2..*`/`1..*`/`1`/`0..*`；控制/边界到实体不定义；三条候选属性化（05 §2.3） |
| §5.5.4 p200–201 分析机制 | 同 06 §15 | 已定义 16 项机制 |
| §5.5.5 p201–204 统一分析类 | [07-与全局模型差异](07-global-model-diff.md) | 只提交 R5 输入，不重构全局图 |

## 3. 模型完整性检查

| 检查 | 结果 | 说明 |
|---|---|---|
| UC-10 参与者 | 主要：管理员；系统参与者：系统（由协调者实现）；次要：相关学者；触发方：治理管线（UC-09/18/19）；只读边界：访客/登录用户 | 依 FR15 参与者“系统/管理员”、FR15-S05/S10/S13、FR14、§8、§11.4 |
| 主流程编号 | E1–E18 | 每步在 01/02 有来源 |
| 备选/异常 | A1–A20 | 均有正式来源或标注分析推导/待确认 |
| BCE 候选 | 12 个：`IdentityVerificationUI`/`IdentityVerificationCoordinator`/`User`/`ScholarAuthorization`/`SourceRecord`/`Scholar`/`IdentityCandidate`/`AttributionCandidate`/`Authorship`/`WorkEntity`/`ReviewEvidence`/`AuditEvidence` | 见 03 §2 |
| 被拒绝候选 | `UserProfile`、`ClaimApplication`系、`ScholarChangeRequest`、`CorrectionRequest`、`WorkVersion`/`DedupCandidate`/`CleanRecord`/`MergeDecision`、`AliasMap`/`SimilarityScorer`/`ThresholdPolicy`/`ExternalIdAuthority`、独立 `MergeRecord`/`SplitRecord`、`DisputeCase`/`PendingTodo`/`ClaimConflictItem`、`Notification`/`SlaTimer`、`Session`、`ReviewerRole`、独立 `IdentityEvidence`/`AffiliationPeriod`/`ExternalIdentifier`、`CollaborationEdge`/`GraphCtl`、`PublicationSubmission`、`EvidenceFile`、独立 `EffectiveVersion`、`AuditCoordinator`、展示投影/DTO | 见 03 §4 |
| 顺序图消息 | M1–M103 | 每条有发送者/接收者/事件步骤/职责/依据；编号与名称脚本核对（§4.4） |
| VOPC 类数 | 12 | 与顺序图对象集合一致（§4.4/05 §3） |
| VOPC 是否含认领/档案维护/纠错/去重/建边/待办对象 | 否 | 只含 UI/Coordinator/User/Auth/SourceRecord/Scholar/IdentityCandidate/AttributionCandidate/Authorship/WorkEntity/ReviewEvidence/AuditEvidence |
| 学者档案与账号是否分离 | 是 | `Scholar` 可无账号；`UserProfile` 不在本 UC（BR-UC10-01） |
| 姓名/别名归一是否只产生候选 | 是 | M7/M8 只登记比对用形式与别名候选；M16 拒绝以姓名判定 |
| 同名是否被当作证据 | 否（有反证守卫） | M16；A3；BR-UC10-03 |
| 外部标识是否被当作自动确认 | 否 | M21 需（a)/(b) 且规则已批准；A4/A5 冲突转待核验；B-03 |
| 证据不足是否只出候选 | 是 | M26/M27/M29；A3/A4/A5 |
| 关系是否保留角色/顺序/来源 | 是 | M5/M18/M22；`Authorship.role/order/evidenceRefs`；`顺序未知` 显式标注 |
| 关系状态是否独立五取一并守优先级 | 是 | M22/M62/M84/M88；BR-UC10-09；`DGM-STATE-05` |
| 管理员审批是否被写成本人确认 | 否 | 无写 `本人确认` 的消息；M49/M79/M84；BR-UC10-10 |
| 一次判定是否扩散到其余 N 条关系 | 否 | M84/M88 只作用于本条；无批量写入消息 |
| 已排除是否被自动恢复 | 否（有反证消息） | M11–M13；M89；BR-UC10-11 |
| 排除/拆分是否删除成果、来源或他人关系 | 否 | M88/M89/M93/M94 明确保留；无删除消息 |
| 是否做成果去重/改共享字段/建合作边 | 否 | 无 `WorkVersion`/`DedupCandidate` 消息；M97 只声明；M99 只声明派生结果 |
| 是否做认领审核/本人确认/档案维护/主页编辑 | 否 | 03 §4 拒绝对应类；A19 边界 |
| 变更是否传播到门户/统计/FR12/IN01 并有审计 | 是（声明级） | M97–M100；逐视图实测 NOT RUN |
| 三类证据（来源/材料/留痕）是否分离 | 是 | `SourceRecord`/`ReviewEvidence`/`AuditEvidence`；05 §2.3；06 §6/§12/§13 |
| 是否自造阈值或权重 | 否 | B-03/B-04 标待确认；未写具体数值 |
| 待确认项是否被写成已确认规则 | 否 | B-01～B-10 就近标注 |
| 技术实现 | 未引入数据库/Java/API/框架/索引/队列 | 只有分析职责 |

## 4. 实际执行的验证

### 4.1 PlantUML 渲染

实际执行（仓库根目录）：

```bash
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-10/diagrams/DGM-SEQ-15-identity.puml docs/analysis/stage-02/uc-10/diagrams/DGM-VOPC-11-identity.puml
PLANTUML_LIMIT_SIZE=8192 plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-10/diagrams/DGM-SEQ-15-identity.puml docs/analysis/stage-02/uc-10/diagrams/DGM-VOPC-11-identity.puml
```

结果（两条命令均 `exit=0`，无 PlantUML 语法错误图）：

| 模型 | PUML | SVG | PNG | 目视检查 |
|---|---|---|---|---|
| UC-10 身份与归属核验序列 | 有 | 有（4787.5×7292.2；`Syntax Error` 计数 0） | 有（4786×7290；1.8 MB） | 已查看（整体首段 + 末段）：15 条生命线；M1–M103；A7 保持已排除、候选零/唯一/多分支、A1/A2 拒绝、A8/A9/A10/合并/保持独立、A17/A13、归属确认/排除/拆分与传播分组可读；中文与图例可读 |
| UC-10 VOPC | 有 | 有（5314.1×1809.4；`Syntax Error` 计数 0） | 有（5312×1808；521 KB） | 已查看（整体 + 局部放大）：12 个类；5 条业务关联的多重性 `0..*`/`2..*`/`1..*`/`1` 可读；无 `UserProfile`/`ClaimApplication`/`CorrectionRequest`/`DedupCandidate`/`CollaborationEdge`/`PendingTodo` |

> 序列图 PNG 使用 `PLANTUML_LIMIT_SIZE=8192`（默认上限 4096 px，实际高 7290 px 已超默认上限）；SVG 不受该限制。
> 布局调整记录：VOPC 首版把“候选→署名来源”“候选→关系”“授权→账号”三条业务关联一并画出，导致 `AttributionCandidate` 与 `Scholar` 附近多重性标签重叠、边交叉；改为“这三条事实写成属性（05 §2.3）”并把 `ScholarAuthorization` 的业务关联留给 UC-06/UC-08 后重渲染，最终版为本包提交版。该调整只影响表达方式与布局，未改变类集合与业务结论。
> 渲染成功只证明语法与可读性，不证明业务运行或评审通过；SVG/PNG 由 PlantUML 生成，未手工修改。

### 4.2 69 AC 与基线

```text
$ python3 scripts/docs/verify_69_ac.py
PASS: 69 AC unique; SRS text matches ledger; trace has all 69; UC-01..19/22 present; A1c and scale markers present; active references resolve.
GROUPS: {'FR': 54, 'IN01': 4, 'NF': 11}
exit=0
```

### 4.3 文档检查（本包新增后）

```text
$ python3 scripts/docs/check_documentation.py
{
  "status": "STATIC_PASS_ONLY",
  "relative_links": 1652,
  "uml_svg_pairs": 30,
  "errors": [],
  "external_urls_and_anchors": "NOT_CHECKED",
  "human_approval": "NOT_VERIFIED",
  "runtime_tests": "NOT_EXECUTED"
}
```

> 基线（本包开始前）：`status: STATIC_PASS_ONLY`、`relative_links: 1598`、`uml_svg_pairs: 30`、`errors: []`。
> 本包在 `08-self-check.md` 尚未写入时（README 已链接 08）出现过 1 次中间态 `FAIL`：`relative_links: 1645`、`errors: ["docs/analysis/stage-02/uc-10/README.md: missing 08-self-check.md"]`。补写本文件并修正 01 §10 的消息引用后重跑为 `STATIC_PASS_ONLY`、`relative_links: 1652`、`errors: []`（上方为最终态）。该 FAIL 记录保留，不隐藏。
> `uml_svg_pairs: 30` 只统计 `docs/uml/` 下的源图；本包 `docs/analysis/stage-02/uc-10/diagrams/` 的 SVG/PNG 由 §4.1 的渲染命令与相对链接检查共同覆盖。

### 4.4 编号与结构脚本核对

```text
$ python3 - <<'EOF'  # 提取 puml 消息号与发送者/接收者，与 04 表逐行比对
puml messages 103 min 1 max 103 dups False
doc rows 103 missing in puml [] missing in doc []
exact name mismatches 0
sender/receiver mismatches []
parsed arrows 103
EOF
```

结论：顺序图与 04 逐消息表的消息号 M1–M103 完全一致，无缺号、无重复，名称逐条相同，发送者/接收者逐条一致。

## 5. NOT RUN / 未验证

| 项目 | 状态 | 说明 |
|---|---|---|
| `AC-IMPL-FR15-01`～`06` 的实际验收 | NOT RUN | 需求层 AC 为计划验收；本包只做静态分析 |
| 运行时行为：证据收集、归一、候选生成、合并/拆分、状态传播 | NOT RUN | 无实现代码 |
| 逐视图传播实测（详情/检索/门户/统计/FR12/IN01 同一生效版本，NF05） | NOT RUN | 无实现与实测环境；本包只声明“同一生效关系＋逐视图记录” |
| 身份判定阈值/规则的实际效果（误合并率、召回） | NOT RUN | 阈值未冻结（B-03；D-07） |
| 规模/性能（≥500 万去重实体、100 并发，NF02） | NOT RUN | 无实现与负载；本 UC 不主张覆盖 NF02 |
| 恢复后计数与审核历史核对（NF04） | NOT RUN | 无备份恢复环境 |
| 字符集/字体在生成环境的表现 | 已目视检查（本机 Noto Sans CJK），跨环境未验证 | SVG/PNG 由本机 PlantUML 1.2020.02 生成 |
| 人工评审/教师评分 | NOT VERIFIED | 自动静态检查不等于签认 |

## 6. 待评审/待确认

| 编号 | 问题 | 为什么不能自行决定 | 当前处理 |
|---|---|---|---|
| B-01 | 身份候选/归属候选的状态集合（是否含“待补充证据”独立取值）、争议结案载体、与认领冲突处理项是否同一对象 | FR15-S04/S05/S13 只要求“进入待核验队列/可标记争议/请求补充证据”，队列与结案属 FR14/UC-17 | 按 `待核验/已确认（合并或归属）/已排除/争议中` 建模；其余标待确认 |
| B-02 | 处理时限与责任人；“60 秒”是否作为本轮指标 | NF05 明确该量化目标不在本轮已批准清单；FR15-S12 只写“约定时限” | 只要求“最终一致、不自动批准、超时不等于通过” |
| B-03 | 外部标识充分性、姓名相似阈值、共同作者数量门槛、自动判定规则是否已批准 | SPEC §6.5 明确列待联合评审；D-07；Q-SPEC-01 | 条件（a)/(b) 写成守卫；规则未批准一律转候选；不写具体数值 |
| B-04 | 候选组上限、截断长度、“候选过多”判定值、排序依据披露方式 | FR15-S04 E1 只要求“限制规模并标注” | 只写“限制规模并标注、不默认选第一个” |
| B-05 | 归属候选是否应作为独立分析类，还是并入 `Authorship(待核验)`＋候选属性 | FR15-S07 E1 允许“新档案＋待核验关系”，`AC-IMPL-FR15-01` 又要求“成果关系进入候选”；两者关系未冻结 | 本包保留 `AttributionCandidate` 并说明分工（03 §3 C8；05 §2.2）；若评审否定，需收缩类集合 |
| B-06 | 拆分/改派后无法确定的关系的再判定入口与时限 | BR-358 只要求置 `待核验` | 只保证“不擅自分配、置待核验” |
| B-07 | 门户管理授权转移条件与申诉程序 | SPEC ID-12 明确待评审 | 只保证“不自动转移、生成冲突处理项、通知不含他人私人信息” |
| B-08 | UC-08 与 UC-10 在“改派/拆分”上的入口分工 | FR15-S10 的关联用例同时列 UC-08 与 UC-10 | 本 UC 按主参与者＝管理员判为处置方；UC-08 只保留申请与状态 |
| B-09 | 会话/凭据是否成为独立分析对象（沿用 UC-04/07/08/09） | 基线未定义 | 作为请求上下文，不建模 |
| B-10 | 命名与追踪：`IdentityVerificationUI`/`IdentityVerificationCoordinator` 与 `AdminUI`/`AdminConsole`/`GovernCtl`；`IdentityCandidate`/`AttributionCandidate` 全局无对应；`Evidence` 一名多义；追踪表增补 `SEQ-15/VOPC-11` | 全局命名统一与共享追踪文件由 R5/父 Agent 维护 | 07 §2/§6 提建议，本包不改全局图与共享文件 |
| C-01 | **文本/模型冲突：** 模块细化 `FR15-S03` 主流程 2–3 与 `DGM-ACTIVITY-07` 的“条件（a)/(b）成立→判定同一人并合并档案”，同 `SPEC ID-02`（“标识一致仅支持候选；生效须已批准规则或人工核验”）、`SPEC §6.5`（“外部 ID 的充分性…不得做成默认自动确认；自动规则未冻结时转人工待核验”）以及 `FR15-S04`（证据不足只出候选）不一致；`DGM-SEQ-01` 同样未体现该守卫 | 基线优先级：SRS V2.2/69 AC 与 SPEC 为现行基线；模块 SRS 与既有活动/序列图为细化与既有资产，本 UC 无权修改 | 本 UC 以现行基线为准：条件成立也需“规则已批准”，否则转候选（M21；A3/A4）；冲突登记于 07 §3/§5，交负责人评审并修正资产 |

## 7. 结论

```text
UC-10 R1: STATIC COMPLETE (check_documentation status=STATIC_PASS_ONLY, errors=0)
UML RENDER: 2/2 OK (SVG+PNG, no syntax error)
AC BASELINE: 69 UNCHANGED (verify_69_ac PASS)
MESSAGE CONSISTENCY: M1-M103 names+senders+receivers exact match
RUNTIME TEST: NOT RUN
HUMAN REVIEW: 待评审
S02 STATUS: PARTIAL（总状态与共享追踪由父 Agent 维护）
```

UC-10 已按教材 §5.4.1–§5.4.4 与 §5.5.1–§5.5.4 完成一次可追溯的用例实现分析，并产出独立于全局 BCE 的 VOPC。B-01～B-10 与 C-01 仍需需求确认或 R5 处理；在评审人确认前，不把 UC-10 标为“业务已批准/教师已验收”。
