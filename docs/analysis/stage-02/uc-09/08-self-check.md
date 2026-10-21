# UC-09 自检与待评审问题

**版本：** V1.0（2026-10-21）
**检查性质：** 静态文档/模型检查；不是产品运行测试，不是教师评分，不是人工评审签字。
**范围：** 只检查 UC-09 分析包，以及本包的渲染与文档链接结果。

## 1. 范围与基线检查

| 检查 | 结果 | 证据 |
|---|---|---|
| 分支 | `analysis/s01-s02-69ac` | `git branch --show-current` |
| 正式 SRS/SPEC/S01 | 未修改 | 本轮只新增 `docs/analysis/stage-02/uc-09/` |
| 69 条 AC | 未新增、未删除、未改号 | `python3 scripts/docs/verify_69_ac.py` → PASS（§4.2） |
| 全局 UML / 治理活动图 / 处理项状态图 | 未修改 | 只读比对，差异与冲突登记见 07 §3/§5 |
| 模块细化 `governance-dedup.md` | 未修改 | 只引用；其中 `AC-FR09-S07-02` 与现行 AC/SPEC 的冲突登记见 §6 |
| 其他 UC 目录 | 未修改 | 未写入 `uc-01`～`uc-08`、`uc-22` |
| 共享追踪/登记文件 | 未修改 | `03-AC-UC-分析模型追踪.md`、`automation/analysis-class-registry.md`、`automation/unresolved-decisions.md` 保持原状；登记建议见 07 §6 |
| 其他成员未提交修改 | 未覆盖 | 工作区原有的 `docs/uml/analytics`、`docs/uml/collection`、`scripts/docs` 修改保持原状 |

## 2. 教材步骤对应

| 教材步骤 | 本包证据 | 状态 |
|---|---|---|
| §5.4.1 p154–155 完善用例文档 | [01-UC-09用例规约](01-UC-09-use-case-spec.md) | 已完成（未决项见 §6） |
| §5.4.2 p155–161 识别分析类 | [03-BCE分析类识别](03-bce-identification.md)、[02-事件流与需求来源](02-event-flow-trace.md) | 已完成；9 个参与类 |
| §5.4.3 p161–181 分析交互 | [04-顺序图与逐消息职责](04-sequence-messages.md)、`DGM-SEQ-14-dedup` | 已完成；M1–M96 |
| §5.4.4 p181–183 构造 VOPC | [05-VOPC与关系依据](05-vopc.md)、`DGM-VOPC-10-dedup` | 已完成；非全局 BCE |
| §5.5.1 p188–190 职责 | [06-分析类定义](06-class-definition.md) Do/Know/候选分析操作 | 已完成 |
| §5.5.2 p190–191 属性 | 同 06 业务属性表 | 已完成本 UC 可证实部分；类型必填项、字段优先级、阈值、候选参数标待确认 |
| §5.5.3 p192–200 关系/多重性 | 同 05 §2、06 §11 | 六条业务关联标 `1:0..*`/`0..*:1`；控制/边界到实体不定义；三条关系改由属性表达（05 §2.3） |
| §5.5.4 p200–201 分析机制 | 同 06 §12 | 已定义 16 项机制 |
| §5.5.5 p201–204 统一分析类 | [07-与全局模型差异](07-global-model-diff.md) | 只提交 R5 输入，不重构全局图 |

## 3. 模型完整性检查

| 检查 | 结果 | 说明 |
|---|---|---|
| UC-09 参与者 | 主要：治理管理员；次要：系统（治理处理）；触发方：来源采集/增量任务（UC-18/19）；只读边界：访客/登录用户/门户管理者（本 UC 不交互） | 依 FR09 参与者“系统/治理管理员”、§6.1、FR14、§8 |
| 主流程编号 | E1–E17 | 每步在 01/02 有来源 |
| 备选/异常 | A1–A16 | 均有正式来源或标注分析推导/待确认 |
| BCE 候选 | 9 个：`DedupGovernanceUI`/`DedupCoordinator`/`User`/`SourceRecord`/`CleanRecord`/`DedupCandidate`/`WorkEntity`/`WorkVersion`/`AuditEvidence` | 见 03 §2 |
| 被拒绝候选 | 采集任务/来源接入/检查点、增量变更集、Scholar/身份候选/归属改派、Authorship/CorrectionRequest/ClaimApplication、ReviewEvidence、检索/详情/门户/统计/网络类、PendingTodo/DisputeCase/Notification/SlaTimer、独立 MergeRecord/GovernanceProcessingItem、FieldPolicy/SimilarityScorer/ThresholdPolicy、MappingDictionary、PublicationSubmission、EvidenceFile、Session、AuditCoordinator、展示投影/DTO | 见 03 §4 |
| 顺序图消息 | M1–M96 | 每条有发送者/接收者/事件步骤/职责/依据；编号与名称脚本核对（§4.4） |
| VOPC 类数 | 9 | 与顺序图对象集合一致（§4.4/05 §3） |
| VOPC 是否含采集任务/身份/纠错/待办/展示对象 | 否 | 只含 UI/Coordinator/User/SourceRecord/CleanRecord/DedupCandidate/WorkEntity/WorkVersion/AuditEvidence |
| 最小字段缺失是否异常且不公开 | 是 | M4/M5/M7；A1；BR-UC09-01 |
| 缺字段是否造值 | 否 | M11 标“未知”并区分空白/缺失/确认为无 |
| 仅年份是否补日期 | 否 | M12 明确不补 1 月 1 日；A8 |
| 采集时间是否冒充发生时间 | 否 | M12 明确分离；CL-07 |
| 未知辖区标识是否成稳定键 | 否 | M13 保留原值标待核验；后续走无标识候选路径（M34） |
| 是否跨类型自动合并 | 否 | M24 同类型查找；A3 |
| 稳定标识自动关联是否有唯一性守卫 | 是 | M26（同类型∧无矛盾∧无争议）+ M31（多命中列候选） |
| 无稳定标识是否只生成候选 | 是 | M34–M41；M38 明确历史自动合并方案未批准 |
| 标题相似是否可合并 | 否（有反证消息） | M36 标记；A5；`AC-IMPL-FR09-02` |
| 重复到达是否增加实体或有效出处 | 否 | M21/M23；`distinct` 两计数不变 |
| 版本归并是否增加实体数 | 否 | M46/M47 |
| 版本无证据是否保持独立 | 是 | M48；A10 |
| 已人工确认值是否被静默覆盖 | 否 | M28 冲突项；M85 仅管理员确认后写入 |
| 合并是否保留全部出处/版本与旧标识 | 是 | M74/M75；M79 |
| 合并是否有循环/部分成功守卫 | 是 | M71/M72/M73 |
| 拆分是否抹除历史 | 否 | M80–M84；FR09-S12 A3 |
| 是否改写来源原值/删除来源 | 否 | M3/M19 只读；M20 只追加到达事件 |
| 是否删除成果实体 | 否 | 合并只标“已合并”并重定向（M75） |
| 是否做学者身份判定/写关系状态 | 否 | 无 `Scholar`/`Authorship` 消息；A14 边界 |
| 是否采集/重试/增量识别 | 否 | 只有 M1 批次交付（外部输入） |
| 是否发布/下架/投稿 | 否 | 无对应消息；03 §4 拒绝该候选 |
| 异常/待核验/拒绝/失败/已处置是否分开 | 是 | A1/A8、A3–A7/A10、A16、A11/A13、E16 |
| 是否自造阈值或权重 | 否 | B-03/B-04 标待确认；未写具体数值 |
| 待确认项是否被写成已确认规则 | 否 | B-01～B-10 就近标注 |
| 技术实现 | 未引入数据库/Java/API/框架/索引/队列 | 只有分析职责 |

## 4. 实际执行的验证

### 4.1 PlantUML 渲染

实际执行（仓库根目录）：

```bash
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-09/diagrams/DGM-SEQ-14-dedup.puml docs/analysis/stage-02/uc-09/diagrams/DGM-VOPC-10-dedup.puml
PLANTUML_LIMIT_SIZE=8192 plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-09/diagrams/DGM-SEQ-14-dedup.puml docs/analysis/stage-02/uc-09/diagrams/DGM-VOPC-10-dedup.puml
```

结果（两条命令均 `exit=0`，无 PlantUML 语法错误图）：

| 模型 | PUML | SVG | PNG | 目视检查 |
|---|---|---|---|---|
| UC-09 清洗与去重治理序列 | 有 | 有（viewBox 4106×6946；`Syntax Error` 计数 0） | 有（4104×6945；1.7 MB） | 已查看（整体 + 5 段裁切）：11 条生命线；M1–M96；A1/A8/A12/A2/A3/A5/A6/A7/A10/A16 与合并/A11/拆分/字段确认/修正/忽略分支、批次结论与留痕失败分组可读；中文与图例可读 |
| UC-09 VOPC | 有 | 有（viewBox 3918×2165；`Syntax Error` 计数 0） | 有（3917×2165；527 KB） | 已查看（整体 + 局部放大）：9 个类；6 条业务关联的多重性 `1`/`0..*` 可读；`Ctl→Source` 为实线（只读原值＋仅追回到达事件）；无 `CollectionTask`/`Scholar`/`Authorship`/`PendingTodo`/`CollaborationEdge` |

> 序列图 PNG 使用 `PLANTUML_LIMIT_SIZE=8192`（默认上限 4096 px，实际高 6945 px 已超默认上限）；SVG 不受该限制。
> 布局调整记录：VOPC 首版在 `top to bottom` 下把 `CleanRecord→WorkEntity`、`WorkEntity` 自关联与 `Version→Source` 同时画出，导致 `WorkEntity` 附近多重性标签重叠、边交叉；改为“记录归入由出处＋属性表达、合并链由属性＋审计表达”（05 §2.3）后重渲染，最终版为本包提交版。该调整只影响布局与表达方式，未改变类集合与业务结论。
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
  "relative_links": 1598,
  "uml_svg_pairs": 30,
  "errors": [],
  "external_urls_and_anchors": "NOT_CHECKED",
  "human_approval": "NOT_VERIFIED",
  "runtime_tests": "NOT_EXECUTED"
}
```

> 基线（本轮开始前）：`status: STATIC_PASS_ONLY`、`relative_links: 1545`、`uml_svg_pairs: 30`、`errors: []`。
> 本包在 `08-self-check.md` 尚未写入时（README 已链接 08）出现过 1 次中间态 `FAIL`：`relative_links: 1591`、`errors: ["docs/analysis/stage-02/uc-09/README.md: missing 08-self-check.md"]`。补写本文件后重跑为 `STATIC_PASS_ONLY`、`relative_links: 1598`、`errors: []`（上方为最终态）。该 FAIL 记录保留，不隐藏。
> `uml_svg_pairs: 30` 只统计 `docs/uml/` 下的源图；本包 `docs/analysis/stage-02/uc-09/diagrams/` 的 SVG/PNG 由 §4.1 的渲染命令与相对链接检查共同覆盖。

### 4.4 编号与结构脚本核对

```text
$ python3 - <<'EOF'  # 提取 puml 消息号并与 04 表逐行比对
puml messages 96 min 1 max 96
missing in puml []
dups []
doc rows 96 first 1 last 96
missing in doc []
dups in doc []
exact name mismatches 0
EOF
```

结论：顺序图与 04 逐消息表的消息号 M1–M96 完全一致，无缺号、无重复，名称逐条相同。

## 5. NOT RUN / 未验证

| 项目 | 状态 | 说明 |
|---|---|---|
| `AC-IMPL-FR09-01`～`06` 的实际验收 | NOT RUN | 需求层 AC 为计划验收；本包只做静态分析 |
| 运行时行为：清洗、去重、幂等、合并/拆分、字段冲突处置 | NOT RUN | 无实现代码 |
| 规模/性能：≥1,000,000 联调与 ≥5,000,000 去重实体、100 活跃并发、P50/P95/P99（NF02） | NOT RUN | 无实现与负载；本包只固定“以去重实体计数”的口径 |
| 恢复后计数核对（NF04） | NOT RUN | 无备份恢复环境 |
| 停用来源后既有成果可读（NF07） | NOT RUN | 无实现；本包只保证“不级联删除”的约束 |
| 字符集/字体在生成环境的表现 | 已目视检查（本机 Noto Sans CJK），跨环境未验证 | SVG/PNG 由本机 PlantUML 1.2020.02 生成 |
| 人工评审/教师评分 | NOT VERIFIED | 自动静态检查不等于签认 |

## 6. 待评审/待确认

| 编号 | 问题 | 为什么不能自行决定 | 当前处理 |
|---|---|---|---|
| B-01 | 类型细化必填项（项目号、专利号、获奖年度等）与“最小字段”是否含日期/参与者 | SPEC §4.1 明确列为待评审 | 只把“类型＋名称＋来源身份”作为治理最小输入（BR-UC09-01）；其余不强制 |
| B-02 | 多来源字段优先级规则与“可确认字段”范围 | FR09-S05 要求“确定的字段优先级”，未给顺序 | 只实现“已确认值优先＋冲突不覆盖＋管理员确认”（M28/M85–M88） |
| B-03 | 稳定标识冲突优先级、无标识相似度阈值、分类型误合并容忍率、人工复核比例 | SPEC §5.3 明确“不生效”；Q-SPEC-01；D-07 | 全部标待确认；本 UC 不写任何权重/阈值 |
| B-04 | 候选召回年份窗、标题比对键停用词与最短长度、候选清单截断长度 | DD-05/DD-12 参数待冻结 | 只写“限制长度并标注候选过多”（M40）；不写具体值 |
| B-05 | 疑似版本关联的表达方式与后续入口 | DD-08 只要求“标疑似” | 按“候选待核验＋不合并”处理（M48） |
| B-06 | 处理项五态（待处理/处理中/已处理/已忽略/已转人工复核）、超期与转交复核、与 UC-17 待办边界 | FR09-S11 与 DGM-STATE-04 定义处理项，但队列/超期属 FR14；SLA 未定义 | 只建模 `CleanRecord` 治理状态与 `DedupCandidate` 匹配状态；其余登记待评审 |
| B-07 | 合并/拆分后下游重算范围与生效时限（含历史 60 秒目标） | NF05 明确该量化目标不在本轮清单 | 只声明“下游读取同一去重实体”；不写时限 |
| B-08 | 来源撤回/删除/停用的记录责任在 UC-09 与 UC-18/19 之间的分工 | CL-12 只要求“分别记录且不删除” | 本 UC 只保证不级联删除并提供记录入口（A15） |
| B-09 | 会话/凭据是否成为独立分析对象（沿用 UC-04/07/08） | 基线未定义 | 作为请求上下文，不建模 |
| B-10 | 命名与追踪：`DedupGovernanceUI`/`DedupCoordinator` 与 `AdminUI`/`AdminConsole`/`GovernCtl`；`CleanRecord`/`DedupCandidate` 全局无对应；`WorkEntity`/`SourceRecord`/`WorkVersion` 命名统一；`Version→Source` 新建；追踪表增补 `SEQ-14/VOPC-10` | 全局命名统一与共享追踪文件由 R5/父 Agent 维护 | 07 §2/§6 提建议，本包不改全局图与共享文件 |
| C-01 | **文本冲突：** 模块细化 `governance-dedup.md` 的 `AC-FR09-S07-02`（四项证据一致→自动合并）与 `DGM-ACTIVITY-06`/`DGM-ACTIVITY-08` 的“证据达到自动合并条件→合并”分支，同现行 `AC-IMPL-FR09-02`＋SPEC DD-06（“缺稳定标识只生成多证据候选”）＋SPEC §5.3（阈值不生效）不一致 | 基线优先级：SRS V2.2/69 AC 为唯一现行基线；模块 SRS 与活动图为细化/既有资产，本 UC 无权修改 | 本 UC 以现行基线为准（A6；M38），不执行自动合并；冲突登记于 07 §3/§5，交由负责人评审并修正资产 |

## 7. 结论

```text
UC-09 R1: STATIC COMPLETE (check_documentation status=STATIC_PASS_ONLY, errors=0)
UML RENDER: 2/2 OK (SVG+PNG, no syntax error)
AC BASELINE: 69 UNCHANGED (verify_69_ac PASS)
RUNTIME TEST: NOT RUN
HUMAN REVIEW: 待评审
S02 STATUS: PARTIAL（总状态与共享追踪由父 Agent 维护）
```

UC-09 已按教材 §5.4.1–§5.4.4 与 §5.5.1–§5.5.4 完成一次可追溯的用例实现分析，并产出独立于全局 BCE 的 VOPC。B-01～B-10 与 C-01 仍需需求确认或 R5 处理；在评审人确认前，不把 UC-09 标为“业务已批准/教师已验收”。
