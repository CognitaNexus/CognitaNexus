# UC-11 自检与待评审问题

**版本：** V1.0（2026-10-21）
**检查性质：** 静态文档/模型检查；不是产品运行测试，不是教师评分，不是人工评审签字。
**范围：** 只检查 UC-11 分析包，以及本包的渲染与文档链接结果。

## 1. 范围与基线检查

| 检查 | 结果 | 证据 |
|---|---|---|
| 分支 | `analysis/s01-s02-69ac` | `git branch --show-current` |
| 正式 SRS/SPEC/S01 | 未修改 | 本轮只新增 `docs/analysis/stage-02/uc-11/` |
| 69 条 AC | 未新增、未删除、未改号 | `python3 scripts/docs/verify_69_ac.py` → PASS（§4.2） |
| 全局 UML（`DGM-CLASS-01`）与应用图（`DGM-ACTIVITY-02/04`、`DGM-STATE-03`、`DGM-USECASE-02`） | 未修改 | 只读比对，差异与冲突登记见 07 §3/§5 |
| 模块细化 `analytics-graph.md` | 未修改 | 只引用；`FR11-S08 E3`/`AC-FR11-S08-02` 与 `BR-IMP-06` 的差异登记为 C-02（§6） |
| 其他 UC 目录 | 未修改 | 未写入 `uc-01`～`uc-10`、`uc-17`～`uc-22` |
| 共享追踪/登记文件 | 未修改 | `03-AC-UC-分析模型追踪.md`、`automation/analysis-class-registry.md`、`automation/unresolved-decisions.md`、`automation/progress.json` 保持原状；登记建议见 07 §6 |
| 其他成员未提交修改 | 未覆盖 | 工作区原有的 `docs/uml/analytics/DGM-ACTIVITY-03`、`docs/uml/collection/*`、`scripts/docs/*` 修改与 `docs/reports`、`docs/srs/integration` 新增文件保持原状 |

## 2. 教材步骤对应

| 教材步骤 | 本包证据 | 状态 |
|---|---|---|
| §5.4.1 p154–155 完善用例文档 | [01-UC-11用例规约](01-UC-11-use-case-spec.md) | 已完成（未决项见 §6） |
| §5.4.2 p155–161 识别分析类 | [03-BCE分析类识别](03-bce-identification.md)、[02-事件流与需求来源](02-event-flow-trace.md) | 已完成；5 个参与类 |
| §5.4.3 p161–181 分析交互 | [04-顺序图与逐消息职责](04-sequence-messages.md)、`DGM-SEQ-19-statistics` | 已完成；M1–M49 |
| §5.4.4 p181–183 构造 VOPC | [05-VOPC与关系依据](05-vopc.md)、`DGM-VOPC-15-statistics` | 已完成；非全局 BCE |
| §5.5.1 p188–190 职责 | [06-分析类定义](06-class-definition.md) Do/Know/候选分析操作 | 已完成 |
| §5.5.2 p190–191 属性 | 同 06 业务属性表 | 已完成本 UC 可证实部分；分类层级、阈值、上限、更新时间语义标待确认 |
| §5.5.3 p192–200 关系/多重性 | 同 05 §2、06 §7 | 两条业务关联标 `0..*`:`1` 与 `0..*`:`0..*`；控制/边界到实体不定义；五类事实属性化（05 §2.3） |
| §5.5.4 p200–201 分析机制 | 同 06 §8 | 已定义 14 项机制 |
| §5.5.5 p201–204 统一分析类 | [07-与全局模型差异](07-global-model-diff.md) | 只提交 R5 输入，不重构全局图 |

## 3. 模型完整性检查

| 检查 | 结果 | 说明 |
|---|---|---|
| UC-11 参与者 | 主要：访客；系统参与者：系统（由协调者实现）；次要（外部前置）：口径与分类映射维护方；触发方：治理与解析管线（UC-09/18/19/10） | 依 FR11 参与者、FR14/FR16、§8、§11.4 |
| 主流程编号 | E1–E14 | 每步在 01/02 有来源 |
| 备选/异常 | A1–A19 | 均有正式来源或标注细化/分析推导/待确认 |
| BCE 候选 | 5 个：`StatisticsUI`/`StatisticsCoordinator`/`WorkEntity`/`Authorship`/`Institution` | 见 03 §2 |
| 被拒绝候选 | 持久统计结果/快照、DTO/展示投影、口径版本、`SourceRecord`、`WorkVersion`/`DedupCandidate`/`CleanRecord`、`Scholar`/`User`/`UserProfile`、`Field` 分类树、机构类别维度、质量评分/排名策略、独立 `InstitutionAffiliation`、统计任务/缓存/聚合引擎、`AuditEvidence`、`Session`、`Notification` | 见 03 §4 |
| 顺序图消息 | M1–M49 | 每条有发送者/接收者/事件步骤/职责/依据；编号与名称、收发方脚本核对（§4.4） |
| VOPC 类数 | 5 | 与顺序图对象集合一致（§4.4；05 §3） |
| VOPC 是否含持久统计结果/DTO/来源/版本/学者/评分类 | 否 | 只含 UI/Coordinator/`WorkEntity`/`Authorship`/`Institution` |
| 计数单位是否为去重成果实体 | 是 | M5/M7；BR-UC11-01 |
| 是否按来源数/版本数/作者数计数 | 否（有反证守卫） | M5/M7/M48；A10/A17 |
| 默认现实视图是否只取真实且合格数据 | 是 | M3/M31；BR-UC11-02 |
| 模拟/混合是否被显式标识且默认排除 | 是 | M31/M32/M33；A5/A6/A19；`AC-IMPL-FR11-02` |
| 机构归属是否只取有证据的发生时机构 | 是 | M22；A8；BR-UC11-07 |
| 同一成果同机构是否只计一次、跨机构各一次 | 是 | M25；BR-UC11-08；`AC-IMPL-FR11-01` |
| 机构未知是否单列且不进已知名次 | 是 | M25/M44/M45；BR-UC11-09 |
| 是否出现质量/影响力/机构优劣/个人排名结论 | 否（有拒绝与固定声明） | M28/M48；BR-UC11-17 |
| 是否披露范围/口径/更新时间/数据性质 | 是（六项同屏） | M11/M12/M32/M43；BR-UC11-12 |
| 是否有写操作或持久统计结果 | 否 | 全图四条只读消息（M4/M18/M20/M23）；04 §4；05 §4 |
| 是否引入设计技术 | 未引入数据库/接口/队列/索引/缓存/DTO | 只有分析职责 |
| 待确认项是否被写成已确认规则 | 否 | B-01～B-09、C-01/C-02 就近标注 |

## 4. 实际执行的验证

### 4.1 PlantUML 渲染

实际执行（仓库根目录）：

```bash
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-11/diagrams/DGM-SEQ-19-statistics.puml docs/analysis/stage-02/uc-11/diagrams/DGM-VOPC-15-statistics.puml
PLANTUML_LIMIT_SIZE=8192 plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-11/diagrams/DGM-SEQ-19-statistics.puml docs/analysis/stage-02/uc-11/diagrams/DGM-VOPC-15-statistics.puml
```

结果（两条命令均 `exit=0`，无 PlantUML 语法错误图）：

| 模型 | PUML | SVG | PNG | 目视检查 |
|---|---|---|---|---|
| UC-11 成果统计分析序列 | 有 | 有（3293.75×3846.875；`Syntax Error` 计数 0） | 有（3292×3845；约 878 KB） | 已查看（整体）：6 条生命线；M1–M49；分组“基准视图/机构维度/条件与数据性质/复核入口/状态与范围守卫”可读；alt 条件非法分支与 M18–M28 合法分支可读；中文与图例可读 |
| UC-11 VOPC | 有 | 有（1740.625×1715.625；`Syntax Error` 计数 0） | 有（1739×1714；约 244 KB） | 已查看（整体）：5 个类；`Authorship 0..*→1 WorkEntity`、`WorkEntity 0..*→0..* Institution` 多重性可读；三条只读虚线依赖可读；无 `StatisticsResult`/`SourceRecord`/`WorkVersion`/`Scholar`/DTO |

> 序列图 PNG 使用 `PLANTUML_LIMIT_SIZE=8192`（默认上限 4096 px，实际高 3845 px 接近上限）；SVG 不受该限制。
> 布局调整记录：VOPC 首版把三条只读依赖的完整说明写进连线标签，导致 `Institution` 附近标签与连线交叉；改为短标签（“M4/M20/M23 只读”）并把说明移入图例后重渲染，最终版为本包提交版。该调整只影响标注位置，未改变类集合、消息与业务结论。
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
  "relative_links": 1926,
  "uml_svg_pairs": 30,
  "errors": [],
  "external_urls_and_anchors": "NOT_CHECKED",
  "human_approval": "NOT_VERIFIED",
  "runtime_tests": "NOT_EXECUTED"
}
```

> 基线（本包开始前）：`status: STATIC_PASS_ONLY`、`relative_links: 1869`、`uml_svg_pairs: 30`、`errors: []`。
> 中间态 FAIL（保留，不隐藏）：本包 `README.md` 已链接 `08-self-check.md` 而该文件尚未写入时，检查结果为 `status: FAIL`、`relative_links: 1919`、`errors: ["docs/analysis/stage-02/uc-11/README.md: missing 08-self-check.md"]`；补写本文件后重跑为上方最终态。
> `uml_svg_pairs: 30` 只统计 `docs/uml/` 下的源图；本包 `docs/analysis/stage-02/uc-11/diagrams/` 的 SVG/PNG 由 §4.1 的渲染命令与相对链接检查共同覆盖。

### 4.4 编号与结构脚本核对

```text
$ python3 - <<'EOF'  # 提取 puml 消息号与发送者/接收者，与 04 表逐行比对
puml messages 49 min 1 max 49 dups False
doc rows 49 missing in puml [] missing in doc []
exact name mismatches 0
sender/receiver mismatches []
EOF
```

结论：顺序图与 04 逐消息表的消息号 M1–M49 完全一致，无缺号、无重复，名称逐条相同，发送者/接收者逐条一致；VOPC 参与类 5 个，与顺序图内部对象集合一致。

## 5. NOT RUN / 未验证

| 项目 | 状态 | 说明 |
|---|---|---|
| `AC-IMPL-FR11-01/02` 的实际验收 | NOT RUN | 需求层 AC 为计划验收；本包只做静态分析 |
| 运行时行为：范围过滤、去重计数、机构归属归并、排名与并列 | NOT RUN | 无实现代码 |
| 统计与检索计数一致性实测（FR11-S11） | NOT RUN | 无实现与样例集 |
| 逐视图一致性与传播实测（NF05，详情/检索/门户/统计/网络同一生效版本） | NOT RUN | 本包只声明版本核对与未更新标注 |
| 规模/性能（≥500 万去重实体、100 并发，NF02） | NOT RUN | 本 UC 不主张覆盖 NF02 |
| 分类映射与机构别名的真实数据效果 | NOT RUN | 分类层级与映射版本未冻结（B-02） |
| 字符集/字体在生成环境的表现 | 已目视检查（本机 Noto Sans CJK），跨环境未验证 | SVG/PNG 由本机 PlantUML 1.2020.02 生成 |
| 人工评审/教师评分 | NOT VERIFIED | 自动静态检查不等于签认 |

## 6. 待评审/待确认

| 编号 | 问题 | 为什么不能自行决定 | 当前处理 |
|---|---|---|---|
| B-01 | 统计“更新时间”指数据生效截止时间还是本次计算完成时间 | FR11 只写“显示更新时间”；模块细化区分数据截止/生成时间/口径版本 | 用 `dataAsOf`／`computedAt` 两个分析态字段，标注待确认 |
| B-02 | 领域分类层级、映射与“待分类”口径 | Q-SPEC-03 未冻结；C-7 只是假设（本地分类树＋来源映射） | 不建分类树，只引用已生效分类取值并允许“待分类” |
| B-03 | 各类型“发生年份”时间字段与精度 | Q-SPEC-03 未冻结；SPEC 要求不以采集日期代替发生时间 | 只要求“按类型语义归年、缺失入时间未知” |
| B-04 | 一致性时限与测量方式 | NF05 明确 60 秒目标不在本轮已批准清单 | 只要求最终一致、落后须标注 |
| B-05 | 样本不足阈值 T 与覆盖率口径 | BR-520 只要求“低于约定阈值标注”，T 未冻结（Q-1） | 只要求标注且仍展示实际计数 |
| B-06 | 是否保留“上一版可用统计结果” | 模块细化 FR11-S09 与 `DGM-STATE-03` 要求保留并展示；V2.2 未要求持久统计结果 | 按读时投影＋失败明确建模；差异登记 C-01 |
| B-07 | 单次展示规模上限 | BR-528 要求声明上限，未给数值（Q-6） | 只要求“超限提示＋已声明子集” |
| B-08 | 与 UC-12 的视图分工，以及“机构类别排名”是否含机构类别维度 | V2.2 FR11 同时含分布统计与机构类别排名；统一 UC 清单另有 UC-12；模块细化把“分类排名”解释为按成果类型/领域过滤 | 本 UC 覆盖排名规则；视图分工与类别维度待确认 |
| B-09 | 占比分母（同条件实体总数／含未知与待分类的合计／已知机构合计） | 模块细化要求“数量与占比”，未定义基数 | 不写占比公式，只要求声明口径 |
| C-01 | **取向冲突：** 模块细化 `FR11-S09`、`DGM-ACTIVITY-02`、`DGM-STATE-03` 以“统计结果对象＋版本＋生效状态＋重试”建模；本 UC 按“读时投影、不建持久统计结果实体”建模（任务给定的分析方向），V2.2 只要求披露范围/口径/更新时间 | 本 UC 无权修改模块 SRS 与既有图；持久结果对象需要未定义的版本/失效/回退规则 | 本包按投影建模并在 05 §4、07 §5 说明；交评审决定 |
| C-02 | **文本冲突：** 模块细化 `FR11-S08` E3 与 `AC-FR11-S08-02` 规定“跨机构归属口径 Q-4 未联合冻结前不得输出正式排名、不得预定三个机构各计一次”；现行基线 `BR-IMP-06` 已明确“成果发生时多机构各一次、单机构一次、未知单列”，`AC-IMPL-FR11-01` 以“机构甲乙各 +1”为验收 | 基线优先级：SRS V2.2、69 AC 与 SPEC 为现行基线；模块 SRS 为细化 | 本 UC 以 69 AC 基线为准（M25；BR-UC11-08），差异登记 07 §5，交负责人确认 Q-4 是否已被取代 |

## 7. 结论

```text
UC-11 R1: STATIC COMPLETE (check_documentation status=STATIC_PASS_ONLY, errors=0)
UML RENDER: 2/2 OK (SVG+PNG, no syntax error)
AC BASELINE: 69 UNCHANGED (verify_69_ac PASS)
MESSAGE CONSISTENCY: M1-M49 names+senders+receivers exact match
VOPC: 5 classes, 2 business associations, 3 read-only dependencies
RUNTIME TEST: NOT RUN
HUMAN REVIEW: 待评审
S02 STATUS: PARTIAL（总状态与共享追踪由父 Agent 维护）
```

UC-11 已按教材 §5.4.1–§5.4.4 与 §5.5.1–§5.5.4 完成一次可追溯的用例实现分析，并产出独立于全局 BCE 的 VOPC。B-01～B-09 与 C-01/C-02 仍需需求确认或 R5 处理；在评审人确认前，不把 UC-11 标为“业务已批准/教师已验收”。
