# UC-07 自检与待评审问题

**版本：** V1.0（2026-10-21）
**检查性质：** 静态文档/模型检查；不是产品运行测试，不是教师评分，不是人工评审签字。
**范围：** 只检查 UC-07 分析包，以及本包的渲染与文档链接结果。

## 1. 范围与基线检查

| 检查 | 结果 | 证据 |
|---|---|---|
| 分支 | `analysis/s01-s02-69ac` | `git branch --show-current` |
| 正式 SRS/SPEC/S01 | 未修改 | 本轮只新增 `docs/analysis/stage-02/uc-07/`；`git status --short` 中不出现 `docs/srs`、`docs/spec`、`docs/analysis/stage-01` 的新改动 |
| 69 条 AC | 未新增、未删除、未改号 | `python3 scripts/docs/verify_69_ac.py` → PASS（§4.2） |
| 全局 UML / 合并序列 / 活动图 / 用例图 / 认领状态图 | 未修改 | 只读比对，见 07 |
| 其他 UC 目录 | 未修改 | 未写入 `uc-01`–`uc-06`、`uc-22` |
| 共享追踪/登记文件 | 未修改 | `03-AC-UC-分析模型追踪.md`、`automation/analysis-class-registry.md`、`automation/unresolved-decisions.md` 保持原状；登记建议见 07 §6 |
| 其他成员未提交修改 | 未覆盖 | 工作区原有的 `docs/uml/analytics`、`docs/uml/collection`、`scripts/docs` 修改保持原状 |

## 2. 教材步骤对应

| 教材步骤 | 本包证据 | 状态 |
|---|---|---|
| §5.4.1 p154–155 完善用例文档 | [01-UC-07用例规约](01-UC-07-use-case-spec.md) | 已完成（未决项见 §6） |
| §5.4.2 p155–161 识别分析类 | [03-BCE分析类识别](03-bce-identification.md)、[02-事件流与需求来源](02-event-flow-trace.md) | 已完成；7 个参与类 |
| §5.4.3 p161–181 分析交互 | [04-顺序图与逐消息职责](04-sequence-messages.md)、`DGM-SEQ-12-scholar-maintenance` | 已完成；M1–M52 |
| §5.4.4 p181–183 构造 VOPC | [05-VOPC与关系依据](05-vopc.md)、`DGM-VOPC-08-scholar-maintenance` | 已完成；非全局 BCE |
| §5.5.1 p188–190 职责 | [06-分析类定义](06-class-definition.md) Do/Know/候选分析操作 | 已完成 |
| §5.5.2 p190–191 属性 | 同 06 业务属性表 | 已完成本 UC 可证实部分；需审核/可直接生效字段清单、范围粒度标待确认 |
| §5.5.3 p192–200 关系/多重性 | 同 05 §2、06 §9 | 仅 `Auth→User`、`Change→Scholar` 标 `0..*:1`；`Auth→Scholar` 沿用未定；控制/边界到实体不定义 |
| §5.5.4 p200–201 分析机制 | 同 06 §10 | 已定义 10 项机制 |
| §5.5.5 p201–204 统一分析类 | [07-与全局模型差异](07-global-model-diff.md) | 只提交 R5 输入，不重构全局图 |

## 3. 模型完整性检查

| 检查 | 结果 | 说明 |
|---|---|---|
| UC-07 参与者 | 主要：已获授权 Scholar 管理者；次要：有权审核的管理员；只读边界：访客/登录用户 | 依 FR06 参与者与 `02-用例模型与规约.md` UC-07；审核入口归属见 B-10 |
| 主流程编号 | E1–E10 | 每步在 01/02 有来源 |
| 备选/异常 | A1–A10 | 均有正式来源或标注分析推导 |
| BCE 候选 | 7 个：`ScholarMaintenanceUI`/`ScholarMaintenanceCoordinator`/`User`/`Scholar`/`ScholarAuthorization`/`ScholarChangeRequest`/`AuditEvidence` | 见 03 §2 |
| 被拒绝候选 | UserProfile/第二个 UserProfile、ClaimApplication/ClaimCoordinator、Authorship/WorkEntity/WorkVersion、SourceRecord、ReviewEvidence、PublicProfileUI/Coordinator、Session、ReviewerRole/PermissionMatrix/ApprovalChain、ConflictQueue/PendingTodo/DisputeCase、Notification/SlaTimer、ScholarMerge、FieldPolicy、独立 EffectiveVersion、AuditCoordinator、MaintenanceView/EditDraft | 见 03 §4 |
| 顺序图消息 | M1–M52 | 每条有发送者/接收者/事件步骤/职责/依据；覆盖核对见 04 §4；脚本核对 M1–M52 无缺号（§4.4） |
| VOPC 类数 | 7 | 与顺序图对象集合一致（脚本核对，§4.4） |
| VOPC 是否含 UserProfile/Claim/Work | 否 | 只含维护 UI/Coordinator/User/Scholar/Auth/Change/Audit |
| 授权是否只来自 UC-06 | 是 | `Auth` 只读（M4）；无创建/转移/撤销消息 |
| 每次受保护写入是否重新校验授权 | 是 | M3/M4/M5/M6（管理者路径）与 M38/M39（审核者路径）分开校验 |
| 他人档案/同名另一 Scholar 是否被拒 | 是 | A2（M10–M12）；A3（M14–M16） |
| 授权失效/超范围（stale scope）是否被拒 | 是 | A3；`AC-IMPL-FR05-02`；`AC-IMPL-FR06-02` |
| 普通 UserProfile 字段是否被误写 | 否 | A4（M17–M18）；无 UserProfile 消息 |
| 待审是否覆盖公开版 | 否 | M20 登记时公开版保持旧值；M33/M34 返回“公开未变更”；A7 拒绝覆盖 |
| 申请人可查待审状态 | 是 | M30–M35（本人可见范围） |
| 批准是否只在审核后 | 是 | M39–M52；驳回时 M49–M52 公开不变 |
| 是否创建第二主页/Scholar/Claim | 否 | 无相应消息；BR-UC07-08 |
| 是否自动确认 Authorship | 否 | 无 Authorship/WorkEntity 消息；BR-UC07-07；`AC-IMPL-FR15-06` |
| 是否覆盖原始来源事实 | 否 | `Scholar.sourceFactRefs` 只读；M24/M45 只写公开规范值；§7.2；BR-IMP-04 |
| 拒绝/失败/待审/生效是否分开表达 | 是 | A1/A2/A3/A8（拒绝）、A6（失败）、A7（状态已变化）、M22/M33（待审）、M28/M47（生效） |
| 是否含 UC-06 认领审核 / UC-08 成果纠错 | 否 | 认领审核只在“授权来源”与前置信中引用；成果归属明确排除 |
| 是否自造审核等级/SLA/审批链/授权转移 | 否 | B-04/B-07/B-10；03 §4 拒绝候选 |
| 待确认项是否被写成已确认规则 | 否 | B-01～B-11 均标待确认，并在 01/02/04/06/07 就近标注 |
| 技术实现 | 未引入数据库/Java/API/框架/队列 | 只有分析职责 |

## 4. 实际执行的验证

### 4.1 PlantUML 渲染

实际执行（仓库根目录）：

```bash
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-07/diagrams/DGM-SEQ-12-scholar-maintenance.puml
PLANTUML_LIMIT_SIZE=8192 plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-07/diagrams/DGM-SEQ-12-scholar-maintenance.puml
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-07/diagrams/DGM-VOPC-08-scholar-maintenance.puml
plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-07/diagrams/DGM-VOPC-08-scholar-maintenance.puml
```

结果：

| 模型 | PUML | SVG | PNG | 目视检查 |
|---|---|---|---|---|
| UC-07 档案维护序列 | 有 | 有（3264×4077 px，无 Syntax Error） | 有（3263×4075） | 已查看：M1–M52、多层 `alt`/`group`、中文与图例可读；A1（未认证/停用）、A2（无有效授权）、A3（超范围）、A4（普通字段）、E6（待审）、A5（可直接生效）、A6（失败）、A7（版本已变化）、A8（无权限/理由缺失）、批准/驳回分支完整 |
| UC-07 VOPC | 有 | 有（2330×1344 px，无 Syntax Error） | 有（2328×1342） | 已查看：7 个类；`Coordinator ⇢ Auth` 只读虚线（M4）与写入实线可区分；`Auth 0..*→1 User`、`Change 0..*→1 Scholar`、`Auth→Scholar`（未定）可读；无 UserProfile/Claim/Authorship |

> 序列图 PNG 用 `PLANTUML_LIMIT_SIZE=8192` 渲染（默认上限 4096 px，实际 SVG 高 4077 px 已接近上限）；SVG 不受该限制。两次渲染均未出现 PlantUML 错误图。
>
> 渲染成功只证明语法与可读性，不证明业务运行或评审通过；SVG/PNG 由 PlantUML 生成，未手工修改。

### 4.2 69 AC 与范围化 SRS

```text
$ python3 scripts/docs/verify_69_ac.py
PASS: 69 AC unique; SRS text matches ledger; trace has all 69;
UC-01..19/22 present; A1c and scale markers present;
active references resolve.
GROUPS: {'FR': 54, 'IN01': 4, 'NF': 11}
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
```

### 4.3 文档检查（本包新增后）

```text
$ python3 scripts/docs/check_documentation.py
{
  "status": "STATIC_PASS_ONLY",
  "relative_links": 1492,
  "uml_svg_pairs": 30,
  "errors": [],
  "external_urls_and_anchors": "NOT_CHECKED",
  "human_approval": "NOT_VERIFIED",
  "runtime_tests": "NOT_EXECUTED"
}
```

> 基线（本轮开始前）为 `status: STATIC_PASS_ONLY`、`relative_links: 1442`、`uml_svg_pairs: 30`、`errors: []`。
> 本包 `08-self-check.md` 尚未创建时（已有 README 与 01–07 共 8 个 Markdown）出现过 1 次中间态 `FAIL`：`relative_links: 1485`、`errors: ["docs/analysis/stage-02/uc-07/README.md: missing 08-self-check.md"]`（README 链接到尚未写入的 08）。补写本文件后重跑为 `STATIC_PASS_ONLY`、`relative_links: 1492`、`errors: []`（上方为最终态）。该 FAIL 记录保留，不隐藏。
> `uml_svg_pairs: 30` 只统计 `docs/uml/` 下的源图；本包 `docs/analysis/stage-02/uc-07/diagrams/` 的 SVG/PNG 由 §4.1 的渲染命令与相对链接检查共同覆盖。

### 4.4 编号与结构脚本核对

```text
$ python3 - <<'EOF'  # 提取 puml 消息号并与 04 表逐行比对
puml M count 52 min 1 max 52
missing []
doc rows 52 first/last 1 52
missing in doc []
EOF
```

结论：顺序图与 04 逐消息表的消息号 M1–M52 完全一致，无缺号、无重复。

## 5. NOT RUN / 未验证

| 项目 | 状态 | 说明 |
|---|---|---|
| AC-IMPL-FR06-01～05、NF03-01、NF06-01 的实际验收 | NOT RUN | 需求层 AC 计划验收；本包只做静态分析 |
| 运行时行为、越权接口实测、并发待审冲突 | NOT RUN | 无实现代码 |
| 生效版本在检索/门户/统计的传播实测（NF05） | NOT RUN | 无实现与负载 |
| 性能/容量（NF02/IN01） | NOT RUN | 与本 UC 无直接指标 |
| 人工评审/教师评分 | NOT VERIFIED | 自动静态检查不等于签认 |

## 6. 待评审/待确认

| 编号 | 问题 | 为什么不能自行决定 | 当前处理 |
|---|---|---|---|
| B-01 | 需审核字段清单与“重要变更”判定口径：Scholar 的已核验身份、机构、归属、来源事实是否一律需审核 | FR06 只以 `AC-IMPL-FR06-01` 的“重要机构变更”为锚点；其余未冻结；自定会新增业务规则 | 基本流按“待审”路径展开；A5 保留“可直接生效”分支并标注；不预设结论 |
| B-02 | 可直接生效的 Scholar 公开字段清单与字段级公开白名单 | FR06 未列举 Scholar 字段；§7.1 只列成果字段 | `Scholar` 属性按 FR06 表述归类；字段级清单标待确认，不新增强制字段 |
| B-03 | 生效版本传播口径与时限（是否含历史 60 秒目标） | NF05 只要求“最终使用同一生效数据”，并明确未通过的量化目标不得当硬指标 | 顺序图只写“使用同一生效版本”（M47），不指定时限 |
| B-04 | 同一 Scholar 是否允许并列多个管理者 | SPEC ID-12 待评审（沿用 UC-06 C-04） | `Auth→Scholar` 多重性标“未定” |
| B-05 | 授权范围粒度（门户级 / 字段级） | `AC-IMPL-FR05-02` 只说“仅对目标门户有效” | A3 以“范围覆盖字段与对象”为准，粒度标待确认 |
| B-06 | 混合提交（同时含 Scholar 事实与普通 UserProfile 字段）的处置 | 基线未规定整体拒绝或部分写入 | A4 只规定“不写普通字段”，混合处置标待确认 |
| B-07 | 审核证据最低集合与“可核验”口径（已登记 D-06） | 需求未冻结 | `ScholarChangeRequest.evidenceRefs` 只作引用，不定义最低集合 |
| B-08 | 会话/凭据是否成为独立分析对象 | 基线未定义（沿用 UC-04/UC-22 B-06） | 作为请求上下文，不建模 |
| B-09 | 同一档案是否允许多条待审变更并存、并发提交与冲突处置 | §6.4 未定义 | `Change 0..*→1 Scholar` 表达可能值；A7 按“版本守卫 + 拒绝覆盖”处理并标待确认 |
| B-10 | 档案变更审核的入口归属（UC-07 内 vs UC-17 管理后台） | FR06 列“审核管理员”为参与者，但后台入口分配未冻结 | 依 FR06 参与者与 `02-用例模型与规约.md` UC-07 第 4 步在本 UC 内表达审批分支；若评审改归 UC-17，本 UC 保留“待审登记 + 生效应用”分工 |
| B-11 | 命名与追踪：`ScholarMaintenanceUI` vs `ScholarPortalUI`；`ScholarMaintenanceCoordinator` vs `ProfileCtl ..> Scholar`；`ScholarChangeRequest` vs UC-08 变更申请；`AuditEvidence` vs `Evidence`/`ReviewEvidence`；追踪表是否增补 `SEQ-12`/`VOPC-08` | 全局命名统一与共享追踪文件由 R5/父 Agent 维护 | 07 §2/§6 提建议，本包不改全局图与共享文件 |

## 7. 结论

```text
UC-07 R1: STATIC COMPLETE (check_documentation status=STATIC_PASS_ONLY, errors=0)
UML RENDER: 2/2 OK (SVG+PNG, no syntax error)
AC BASELINE: 69 UNCHANGED (verify_69_ac PASS)
RUNTIME TEST: NOT RUN
HUMAN REVIEW: 待评审
S02 STATUS: PARTIAL（总状态与共享追踪由父 Agent 维护）
```

UC-07 已按教材 §5.4.1–§5.4.4 与 §5.5.1–§5.5.4 完成一次可追溯的用例实现分析，并产出独立于全局 BCE 的 VOPC。B-01～B-11 仍需需求确认或 R5 处理；在评审人确认前，不把 UC-07 标为“业务已批准/教师已验收”。
