# UC-17 自检与待评审问题

**版本：** V1.0（2026-10-21）
**检查性质：** 静态文档/模型检查；不是产品运行测试，不是教师评分，不是人工评审签字。
**范围：** 只检查 UC-17 分析包，以及本包的渲染与文档链接结果。

## 1. 范围与基线检查

| 检查 | 结果 | 证据 |
|---|---|---|
| 分支 | `analysis/s01-s02-69ac` | `git branch --show-current` |
| 正式 SRS/SPEC/S01 | 未修改 | 本轮只新增 `docs/analysis/stage-02/uc-17/` |
| 69 条 AC | 未新增、未删除、未改号 | `python3 scripts/docs/verify_69_ac.py` → PASS（§4.2） |
| 全局 UML / 用例图 / 采集状态图 | 未修改 | 只读比对，差异与建议见 07 §2/§5 |
| 其他 UC 目录 | 未修改 | 未写入 `uc-01`～`uc-10`、`uc-22` |
| 共享追踪/登记文件 | 未修改 | `03-AC-UC-分析模型追踪.md`、`automation/analysis-class-registry.md`、`automation/unresolved-decisions.md`、`automation/progress.json` 保持原状；登记建议见 07 §6 |
| 工作区既有未提交修改 | 未覆盖 | `docs/uml/analytics`、`docs/uml/collection`、`scripts/docs` 的既有修改与 `docs/reports`、`docs/srs/integration` 的新增文件保持原状 |

## 2. 教材步骤对应

| 教材步骤 | 本包证据 | 状态 |
|---|---|---|
| §5.4.1 p154–155 完善用例文档 | [01-UC-17用例规约](01-UC-17-use-case-spec.md) | 已完成（未决项见 §6） |
| §5.4.2 p155–161 识别分析类 | [03-BCE分析类识别](03-bce-identification.md)、[02-事件流与需求来源](02-event-flow-trace.md) | 已完成；11 个参与类 |
| §5.4.3 p161–181 分析交互 | [04-顺序图与逐消息职责](04-sequence-messages.md)、`DGM-SEQ-16-admin-audit` | 已完成；M1–M57 |
| §5.4.4 p181–183 构造 VOPC | [05-VOPC与关系依据](05-vopc.md)、`DGM-VOPC-12-admin-audit` | 已完成；非全局 BCE |
| §5.5.1 p188–190 职责 | [06-分析类定义](06-class-definition.md) Do/Know/候选分析操作 | 已完成 |
| §5.5.2 p190–191 属性 | 同 06 业务属性表 | 已完成本 UC 可证实部分；权限粒度、待办口径、保留期限、遮蔽清单标待确认 |
| §5.5.3 p192–200 关系/多重性 | 同 05 §2、06 §13 | 1 条业务关联标 `0..*:0..1`；其余按消息方向，不定义持久多重性 |
| §5.5.4 p200–201 分析机制 | 同 06 §14 | 已定义 16 项机制 |
| §5.5.5 p201–204 统一分析类 | [07-与全局模型差异](07-global-model-diff.md) | 只提交 R5 输入，不重构全局图 |

## 3. 模型完整性检查

| 检查 | 结果 | 说明 |
|---|---|---|
| UC-17 参与者 | 主要：有权管理员；次要：普通登录用户、访客；上游：属主 UC 的管理员动作与系统事件；只读被查询对象 | 依 FR14 参与者“管理员”、§8、`AC-IMPL-FR14-01`、`DGM-USECASE-06` |
| 主流程编号 | E1–E18 | 每步在 01/02 有来源 |
| 备选/异常 | A1–A14 | 均有正式来源或标注分析推导/待确认 |
| BCE 候选 | 11 个：`AdminConsole`/`AuditCoordinator`/`User`/`AuditEvidence`/`ReviewEvidence`/`CollectionTask`/`DedupCandidate`/`IdentityCandidate`/`ClaimApplication`/`ScholarAuthorization`/`Authorship` | 见 03 §2 |
| 被拒绝候选 | `WorkflowEngine`/`ApprovalChain`/`ApprovalStep`/`RoutingRule`/`Assignee`/`SlaTimer`、`PendingTodo`系、`AdminCoordinator`（第二控制类）、属主 UC 处置边界、`RoleHierarchy`/`PermissionMatrix`、`AuditLog`/`AuditQuery`/`AuditExport`、`FieldMaskPolicy`/`RetentionPolicy`、`EvidenceFile`、`Session`、`Notification`、`SourceRecord`/`WorkEntity`/`CorrectionRequest`/`ScholarChangeRequest`/`Scholar`/`UserProfile`、`MappingDictionary`、`AuditIndex`/`QueryPlan`、DTO | 见 03 §4 |
| 顺序图消息 | M1–M57 | 每条有发送者/接收者/事件步骤/职责/依据；编号与名称脚本核对（§4.4） |
| VOPC 类数 | 11 | 与顺序图对象集合一致（§4.4/05 §3） |
| VOPC 是否含通用待办/工作流实体 | 否 | 无 `PendingTodo`/`WorkflowEngine`/`ApprovalChain`/`Assignee`/`SlaTimer` |
| 是否对属主对象有写边 | 否 | 六类来源全部为虚线只读依赖（M9/M11/M13/M15/M17/M19） |
| 是否执行任何处置决定 | 否 | 无“批准/驳回/合并/拆分/授权/改归属/重试采集”消息；M25–M28 只导航 |
| 普通用户访问是否被拒且无业务变更 | 是 | M3/M4/M5 守卫；M49–M57；A2；BR-UC17-02/03 |
| 管理员是否按具体权限裁剪（非全量） | 是 | M4/M5（类别）、M31/M32（审计范围）；A3/A4；B-01 |
| 关键事件五要素是否被核对 | 是 | M36/M37；A6；BR-UC17-07 |
| 缺要素是否被补写/推断 | 否 | M37 明确“不补写、不推断” |
| 留痕是否可覆盖/删除 | 否 | M43 守卫；INV-10；BR-UC17-08/09 |
| 证明原件是否进入审计结果 | 否 | M38–M40；A5；`AC-IMPL-FR14-02`；NF06 |
| 安全审计是否含凭据/秘密/私人联系方式 | 否（有遮蔽守卫） | M41/M42；A14；NF03；BR-IMP-11 |
| 空/失败/无权/已遮蔽/留痕不完整是否可区分 | 是 | M44/M45/M46/M56；BR-UC17-12 |
| 待核验计数是否独立于“任务成功” | 是 | M9/M10；BR-UC17-13；`DGM-STATE-01` 图例同义 |
| 授权是否被转移/撤销 | 否 | M17/M18 只读；BR-UC17-14；`AC-IMPL-FR05-03` |
| 是否自造权限矩阵/时限/阈值 | 否 | B-01/B-02/B-06 标待确认；未写具体数值或时限 |
| 待确认项是否被写成已确认规则 | 否 | B-01～B-10 就近标注 |
| 技术实现 | 未引入数据库/Java/API/框架/索引/队列/工作流引擎 | 只有分析职责 |

## 4. 实际执行的验证

### 4.1 PlantUML 渲染

实际执行（仓库根目录）：

```bash
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-17/diagrams/DGM-SEQ-16-admin-audit.puml docs/analysis/stage-02/uc-17/diagrams/DGM-VOPC-12-admin-audit.puml
PLANTUML_LIMIT_SIZE=8192 plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-17/diagrams/DGM-SEQ-16-admin-audit.puml docs/analysis/stage-02/uc-17/diagrams/DGM-VOPC-12-admin-audit.puml
```

结果（两条命令均 `exit=0`；`grep -c "Syntax Error"` 两个 SVG 均为 0）：

| 模型 | PUML | SVG | PNG | 目视检查 |
|---|---|---|---|---|
| UC-17 后台待办与审计序列 | 有 | 有（4128.1×4364.1；`Syntax Error` 计数 0） | 有（4126×4363；约 1.0 MB） | 已查看（首段＋末段）：13 条生命线；M1–M57；入口/待办/导航/审计/拒绝五组与 A1–A14 分支可读；中文与图例可读 |
| UC-17 VOPC | 有 | 有（5400×1459.4；`Syntax Error` 计数 0） | 有（5399×1458；约 0.4 MB） | 已查看（整体）：11 个类；六条只读虚线依赖与 `AuditEvidence 0..*→0..1 User` 可读；无 `PendingTodo`/`WorkflowEngine`/`CorrectionRequest` |

> 布局调整记录：VOPC 首版把 `AuditEvidence` 放在实体链最右端，`AuditEvidence → User` 的 `0..*:0..1`（操作者）边横跨整图且与多条虚线交叉；改为把 `AuditEvidence` 紧随 `User` 声明并把图例移到下方后重渲染，最终版为本包提交版。该调整只影响布局，未改变类集合与业务结论。
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
status = STATIC_PASS_ONLY
relative_links = 1714
uml_svg_pairs = 30
errors = []
```

> 基线（本包开始前）：`status: STATIC_PASS_ONLY`、`relative_links: 1652`、`uml_svg_pairs: 30`、`errors: []`。本包新增 9 个 Markdown 与 4 个生成物链接后为 `relative_links: 1714`、`errors: []`（上方为最终态）。
> `uml_svg_pairs: 30` 只统计 `docs/uml/` 下的源图；本包 `docs/analysis/stage-02/uc-17/diagrams/` 的 SVG/PNG 由 §4.1 的渲染命令与相对链接检查共同覆盖。

### 4.4 编号与结构脚本核对

```text
$ python3 - <<'EOF'  # 抽取 puml 消息号与发送者/接收者，与 04 表逐行比对
puml messages 57 min 1 max 57 dups False
doc rows 57 missing in puml [] missing in doc []
exact name mismatches 0
sender/receiver mismatches []
EOF
```

结论：顺序图与 04 逐消息表的消息号 M1–M57 完全一致，无缺号、无重复，名称逐条相同，发送者/接收者逐条一致（参与者别名映射为“管理员/普通用户／未授权请求者”后比对）。

## 5. NOT RUN / 未验证

| 项目 | 状态 | 说明 |
|---|---|---|
| `AC-IMPL-FR14-01/02`、`AC-IMPL-NF03-01` 的实际验收 | NOT RUN | 需求层 AC 为计划验收；本包只做静态分析 |
| 运行时行为：入口权限裁剪、待办聚合、导航、审计查询、遮蔽 | NOT RUN | 无实现代码 |
| 留痕五要素在实际写入路径中的完整性（跨 UC-05～UC-10/18/19） | NOT RUN | 各属主 UC 的写入留痕未实现；本 UC 只能核对格式 |
| 权限粒度矩阵（类别级/范围级/审计查询独立） | NOT RUN | 未冻结（B-01；D-09 邻域） |
| 保留期限到期处置、遮蔽字段清单的实际效果 | NOT RUN | 未冻结（D-09；B-03/B-04） |
| 审计查询自身留痕与导出的实际要求 | NOT RUN | 未冻结（B-05） |
| 性能/容量（待办计数与审计查询规模） | NOT RUN | 基线未定义本 UC 的量化指标；本 UC 不主张覆盖 NF02 |
| 字符集/字体在生成环境的表现 | 已目视检查（本机 Noto Sans CJK），跨环境未验证 | SVG/PNG 由本机 PlantUML 生成 |
| 人工评审/教师评分 | NOT VERIFIED | 自动静态检查不等于签认 |

## 6. 待评审/待确认

| 编号 | 问题 | 为什么不能自行决定 | 当前处理 |
|---|---|---|---|
| B-01 | 管理权限的类别划分粒度；审计查询权限是否独立于处置权限；范围是否按对象授权 | FR14 只写“具体管理权限”，§8 未给矩阵；SPEC 亦未定义 | 按“类别级具体权限＋审计查询权限”建模（M4/M5/M31），不定义权限矩阵与角色继承 |
| B-02 | 待办视图的载体（即时派生/快照）、刷新口径、计数与列表是否同版本 | 基线未定义待办实现形态与时限 | 只要求“只读派生、不推进状态、显示属主对象最新状态” |
| B-03 | 留痕保留期限、可查时间范围、到期处置（删除/匿名化） | D-09 已登记待确认；SRS/SPEC 未给期限 | 不实现到期处置；A12 明确“不擅自删除/匿名化” |
| B-04 | “证明原文”边界、审计结果可否含材料摘要、私人联系方式遮蔽清单 | D-09 部分覆盖；NF06 只给原则 | 只要求“不返回原件、不返回明文敏感字段” |
| B-05 | 审计查询本身是否必须留痕、是否可导出及导出权限 | 基线未规定 | 按“受控查询访问留痕”处理并标分析推导（M47）；导出不建模 |
| B-06 | 待办计数与排序口径（按类别/对象、稳定排序键、分页） | 基线未定义 | 只要求“稳定顺序、计数与状态一致” |
| B-07 | 成果变更待办是否需读取 `CorrectionRequest`/`ScholarChangeRequest` 状态 | 属主 UC-07/08 的对象状态是否成为后台待办来源未定义 | 只读 `Authorship` 待核验/争议状态，其余仅导航；若需显示申请状态需增补两个只读类并重渲 VOPC |
| B-08 | 账号/分类/来源维护入口的范围：来源停用/更换属 FR10/UC-19；“分类”无独立对象与 AC | 基线只有“分类”入口字样 | 只保留导航入口，不定义分类实体 |
| B-09 | 会话/凭据是否成为独立分析对象（沿用 UC-04/06/07/08/09/10） | 基线未定义 | 作为请求上下文，不建模 |
| B-10 | 命名与追踪：`AdminConsole`/`AuditCoordinator` 与全局 `AdminUI`/`AuditCtl`；`Evidence` 一名多义；追踪表是否增补 `SEQ-16/VOPC-12`；NF06/NF07 是否增列 UC-17 | 全局命名统一与共享追踪文件由 R5/父 Agent 维护 | 07 §2/§6 提建议，本包不改全局图与共享文件 |

## 7. 结论

```text
UC-17 R1: STATIC COMPLETE (check_documentation status=STATIC_PASS_ONLY, errors=0)
UML RENDER: 2/2 OK (SVG+PNG, no syntax error)
AC BASELINE: 69 UNCHANGED (verify_69_ac PASS)
MESSAGE CONSISTENCY: M1-M57 names+senders+receivers exact match
RUNTIME TEST: NOT RUN
HUMAN REVIEW: 待评审
S02 STATUS: PARTIAL（总状态与共享追踪由父 Agent 维护）
```

UC-17 已按教材 §5.4.1–§5.4.4 与 §5.5.1–§5.5.4 完成一次可追溯的用例实现分析，并产出独立于全局 BCE 的 VOPC。B-01～B-10 仍需需求确认或 R5 处理；在评审人确认前，不把 UC-17 标为“业务已批准/教师已验收”。
