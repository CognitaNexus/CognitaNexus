# UC-13 自检与待评审问题

**版本：** V1.0（2026-10-21）
**检查性质：** 静态文档/模型检查；不是产品运行测试，不是教师评分，不是人工评审签字。
**范围：** 只检查 UC-13 分析包，以及本包的渲染与文档链接结果。

## 1. 范围与基线检查

| 检查 | 结果 | 证据 |
|---|---|---|
| 分支 | `analysis/s01-s02-69ac` | `git branch --show-current` |
| 正式 SRS/SPEC/S01 | 未修改 | 本轮只新增 `docs/analysis/stage-02/uc-13/` |
| 69 条 AC | 未新增、未删除、未改号 | `python3 scripts/docs/verify_69_ac.py` → PASS（§4.2） |
| 全局 UML（`DGM-CLASS-01`）与应用图（`DGM-USECASE-02`、`DGM-ACTIVITY-02`、`DGM-STATE-03`） | 未修改 | 只读比对，差异与冲突登记见 07 §3/§4/§5 |
| 模块细化 `analytics-graph.md` | 未修改 | 只引用；FR13-S05（趋势）与 FR13-S03（机构/成果类型条件）超出基线的差异登记为 C-02/C-03（§6） |
| 其他 UC 目录（含同族 UC-11/UC-12） | 未修改 | 未写入 `uc-01`～`uc-12`、`uc-17`～`uc-22` |
| 共享追踪/登记文件 | 未修改 | `../03-AC-UC-分析模型追踪.md`、`../automation/analysis-class-registry.md`、`../automation/unresolved-decisions.md`、`../automation/progress.json` 保持原状；登记建议见 07 §6 |
| 其他成员未提交修改 | 未覆盖 | 工作区原有的 `docs/uml/analytics/DGM-ACTIVITY-03`、`docs/uml/collection/*`、`scripts/docs/*` 修改与 `docs/reports`、`docs/srs/integration` 新增文件保持原状 |

## 2. 教材步骤对应

| 教材步骤 | 本包证据 | 状态 |
|---|---|---|
| §5.4.1 p154–155 完善用例文档 | [01-UC-13用例规约](01-UC-13-use-case-spec.md) | 已完成（未决项见 §6） |
| §5.4.2 p155–161 识别分析类 | [03-BCE分析类识别](03-bce-identification.md)、[02-事件流与需求来源](02-event-flow-trace.md) | 已完成；4 个参与类 |
| §5.4.3 p161–181 分析交互 | [04-顺序图与逐消息职责](04-sequence-messages.md)、`DGM-SEQ-21-hotspots` | 已完成；M1–M39 |
| §5.4.4 p181–183 构造 VOPC | [05-VOPC与关系依据](05-vopc.md)、`DGM-VOPC-17-hotspots` | 已完成；非全局 BCE |
| §5.5.1 p188–190 职责 | [06-分析类定义](06-class-definition.md) Do/Know/候选分析操作 | 已完成 |
| §5.5.2 p190–191 属性 | 同 06 业务属性表 | 已完成本 UC 可证实部分；凭证来源、规范化范围、阈值、默认窗口、更新语义标待确认 |
| §5.5.3 p192–200 关系/多重性 | 同 05 §2、06 §6 | 一条业务关联标 `0..*`:`0..*`；控制/边界到实体不定义；九类事实属性化（05 §2.3） |
| §5.5.4 p200–201 分析机制 | 同 06 §8 | 已定义 12 项机制，并显式列出 6 项“不定义的职责”（06 §9） |
| §5.5.5 p201–204 统一分析类 | [07-与全局模型差异](07-global-model-diff.md) | 只提交 R5 输入，不重构全局图 |

## 3. 模型完整性检查

| 检查 | 结果 | 说明 |
|---|---|---|
| UC-13 参与者 | 主要：访客；系统参与者：系统（由协调者实现）；次要（外部前置）：主题登记与术语映射维护方、领域分类映射维护方；触发方：治理与解析管线（UC-09/18/19/10） | 依 FR13 参与者、FR14/FR16、§8 |
| 主流程编号 | E1–E16 | 每步在 01/02 有来源 |
| 备选/异常 | A1–A19 | 均有正式来源或标注细化/分析推导/待确认 |
| BCE 候选 | 4 个：`HotspotUI`/`HotspotCoordinator`/`WorkEntity`/`TopicTerm` | 见 03 §2 |
| 被拒绝候选 | 持久热点结果/快照、语义主题模型/聚类/词向量、自建词典、趋势与预测、质量评分与学者个人排名、`SourceRecord`、`WorkVersion`/`DedupCandidate`/`CleanRecord`、`Scholar`/`User`/`UserProfile`/`Authorship`、`Field` 分类树、独立 `Keyword`、指标口径对象、热点任务/缓存/聚合引擎、`AuditEvidence`、`Session`、`Notification` | 见 03 §4 |
| 顺序图消息 | M1–M39 | 每条有发送者/接收者/事件步骤/职责/依据；编号、名称、收发方脚本核对（§4.4） |
| VOPC 类数 | 4 | 与顺序图内部对象集合一致（§4.4；05 §3） |
| VOPC 是否含持久结果/语义模型/词典/来源/版本/学者/评分类 | 否 | 只含 UI/Coordinator/`WorkEntity`/`TopicTerm` |
| 计数源是否为“公开真实论文关键词＋已登记主题” | 是 | M5–M9；BR-UC13-02 |
| 是否用标题/摘要推断主题或统计四类成果 | 否（显式排除） | M7；A9；BR-UC13-02 |
| 是否按来源数/版本数/作者数/列表重复计数 | 否（有反证守卫） | M11；A10/A11/A18；BR-UC13-06 |
| 同义合并是否只在有凭证时发生 | 是（有守卫） | M8–M10；A12/A13；BR-UC13-04 |
| 是否引入语义相似度/词向量/主题聚类/自建词典 | 否（有拒绝守卫与排除表） | M10/M37；03 §4；06 §9 |
| 排序是否为计数降序＋规范词升序 | 是 | M12；BR-UC13-07；`AC-IMPL-FR13-01` |
| 是否披露样本量、年份窗、数据类别、字段来源、映射凭证来源、更新时间 | 是（同屏披露） | M15/M16/M21/M34；BR-UC13-10 |
| 稀疏/无关键词是否只显示“无数据/样本有限”与实际数量 | 是 | M13/M29/M30；A15；BR-UC13-09 |
| 是否出现趋势/增长率/预测/质量或影响力结论 | 否（有拒绝与固定声明） | M17/M37/M38；BR-UC13-11 |
| 是否出现按学者个人热点排名 | 否（有拒绝守卫） | M37；BR-UC13-15 |
| 是否有写操作或持久热点结果 | 否 | 全图三条只读消息（M5/M8/M25）；04 §4；05 §4 |
| 是否引入设计技术 | 未引入数据库/接口/队列/索引/缓存/DTO | 只有分析职责 |
| 待确认项是否被写成已确认规则 | 否 | B-01～B-12、C-01～C-04 就近标注 |

## 4. 实际执行的验证

### 4.1 PlantUML 渲染

实际执行（仓库根目录）：

```bash
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-13/diagrams/DGM-SEQ-21-hotspots.puml docs/analysis/stage-02/uc-13/diagrams/DGM-VOPC-17-hotspots.puml
PLANTUML_LIMIT_SIZE=8192 plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-13/diagrams/DGM-SEQ-21-hotspots.puml docs/analysis/stage-02/uc-13/diagrams/DGM-VOPC-17-hotspots.puml
```

结果（两条命令均 `exit=0`，无 PlantUML 语法错误图）：

| 模型 | PUML | SVG | PNG | 目视检查 |
|---|---|---|---|---|
| UC-13 领域热点分析序列 | 有 | 有（viewBox 0 0 4425 3342；`Syntax Error` 计数 0） | 有（4423×3341；约 947 KB） | 已查看（整体）：5 条生命线；M1–M39；分组“范围判定／术语与映射凭证／计数与披露／条件与数据类别／术语明细／状态与范围守卫”可读；中文与图例可读 |
| UC-13 VOPC | 有 | 有（viewBox 0 0 1501 1459；`Syntax Error` 计数 0） | 有（1500×1459；约 245 KB） | 已查看（整体）：4 个类；`WorkEntity 0..*→0..* TopicTerm` 多重性可读；两条只读虚线依赖可读；无 `HotspotResult`/`TopicModel`/`Field`/`Keyword`/`SourceRecord`/`WorkVersion`/`Scholar`/DTO |

> 序列图 PNG 使用 `PLANTUML_LIMIT_SIZE=8192`（默认上限 4096 px，实际宽 4423 px 超过默认值）；SVG 不受该限制。
> 渲染成功只证明语法与可读性，不证明业务运行或评审通过；SVG/PNG 由 PlantUML 生成，未手工修改。
> 布局观察（如实记录）：长守卫自消息使序列图较宽（4425 px），与 UC-11 `DGM-SEQ-19`（3292 px）、UC-12 `DGM-SEQ-20`（4195 px）同类；整体缩放预览时最左侧分组标签会显得与分组框边缘重叠，已在原始分辨率下局部裁切核对：6 个分组标题与 39 条消息文字、编号、图例均完整可读（重命名分组标题仅是缩短标签，未删减任何消息或依据）。首次渲染时 M6 文本中的连续中文引号被 PlantUML 误判为 `""` 等宽标记，已改写为 `／` 分隔并复核为单一字体渲染。

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
  "relative_links": 2045,
  "uml_svg_pairs": 30,
  "errors": [],
  "external_urls_and_anchors": "NOT_CHECKED",
  "human_approval": "NOT_VERIFIED",
  "runtime_tests": "NOT_EXECUTED"
}
```

> 基线（本包开始前）：`status: STATIC_PASS_ONLY`、`relative_links: 1986`、`uml_svg_pairs: 30`、`errors: []`。
> 中间态 FAIL（保留，不隐藏）：本包 `README.md` 已链接 `08-self-check.md` 而该文件尚未写入时，检查结果为 `status: FAIL`、`relative_links: 2038`、`errors: ["docs/analysis/stage-02/uc-13/README.md: missing 08-self-check.md"]`；补写本文件后重跑为上方最终态（`relative_links: 2045`，即本文件新增的 7 条相对链接使计数由 2038 增至 2045，`errors: []`）。
> `uml_svg_pairs: 30` 只统计 `docs/uml/` 下的源图；本包 `docs/analysis/stage-02/uc-13/diagrams/` 的 SVG/PNG 由 §4.1 的渲染命令与相对链接检查共同覆盖。

### 4.4 编号与结构脚本核对

```text
$ python3 - <<'EOF'  # 提取 puml 消息号与发送者/接收者，与 04 表逐行比对
puml messages 39 min 1 max 39 dups False
doc rows 39 missing in puml [] missing in doc []
rows with sender/receiver mismatch: []
VOPC classes 4 [('boundary','HotspotUI'),('control','HotspotCoordinator'),('entity','WorkEntity'),('entity','TopicTerm')]
business associations: [('Work','Term')]
readonly deps: ['Work','Term']
EOF
```

结论：顺序图与 04 逐消息表的消息号 M1–M39 完全一致，无缺号、无重复；表内发送者/接收者与图中对象（按别名映射 `Visitor/UI/Ctl/Work/Term` → `访客/HotspotUI/HotspotCoordinator/WorkEntity/TopicTerm`）逐条一致；VOPC 参与类 4 个，与顺序图内部对象集合一致（访客为参与者，不入 VOPC）；业务关联 1 条、只读依赖 2 条。

## 5. NOT RUN / 未验证

| 项目 | 状态 | 说明 |
|---|---|---|
| `AC-IMPL-FR13-01/02` 的实际验收 | NOT RUN | 需求层 AC 为计划验收；本包只做静态分析 |
| 运行时行为：范围过滤、统计资格、术语归并、计数、并列排序、样本判定 | NOT RUN | 无实现代码 |
| 热点计数与人工复算一致性实测（`AC-IMPL-FR13-01` 的“可复算”） | NOT RUN | 无实现与样例集 |
| 同义词映射凭证的真实数据效果与未映射比例 | NOT RUN | 凭证来源与判定门槛未冻结（B-01/B-02） |
| 样本阈值 T、展示条数与上限的真实表现 | NOT RUN | 阈值与条数未冻结（B-05/B-06） |
| 逐视图一致性与传播实测（NF05，热点/检索/门户/统计同一生效版本） | NOT RUN | 本包只声明版本核对与未更新标注 |
| 规模/性能（≥500 万去重实体、100 并发，NF02） | NOT RUN | 本 UC 不主张覆盖 NF02：热点没有已批准的独立性能阈值；NF02 的容量/并发/超时参数属整体验收，不通过本 UC 声明 |
| 字符集/字体在生成环境的表现 | 已目视检查（本机 Noto Sans CJK） | 跨环境未验证；SVG/PNG 由本机 PlantUML 1.2020.02 生成 |
| 人工评审/教师评分 | NOT VERIFIED | 自动静态检查不等于签认 |

## 6. 待评审/待确认

| 编号 | 问题 | 为什么不能自行决定 | 当前处理 |
|---|---|---|---|
| B-01 | **同义词映射凭证的来源与判定门槛**（凭证形式、维护责任、“存疑”门槛） | FR13 只写“有凭证才合并”，未定义凭证；CL-05 只给“已审映射/未映射”原则 | 按“只读已生效凭证映射、无凭证不合并”建模；不构造词典（M8–M10；A12） |
| B-02 | **文本规范化范围**（大小写/单复数/缩写/全半角/空白是否算同一规范词） | 基线只规定同义词有凭证才合并；模块细化 FR13-S07 要求覆盖（细化） | 只在已生效且可查询规则内处理，规则状态写入披露（A13） |
| B-03 | **未登记关键词的处理**（作为热点项单列，还是计入未纳入数） | 基线写“论文关键词/已登记主题”，未说明未登记关键词是否展示 | 按“规范化原写法单列并标注未登记”建模（A9） |
| B-04 | **默认年份窗** | 基线要求“明确年份窗”，默认值未定义（模块细化只要求声明默认窗） | 只要求“默认窗已声明且可复算” |
| B-05 | 样本有限阈值 T | `AC-IMPL-FR13-02` 只要求显示“样本有限” | 只要求标注并给出实际数量 |
| B-06 | 展示条数与单次展示上限 | 模块细化要求“前若干项/约定展示条数”，基线未给数值 | 只要求“按实际条数、不补占位；超限声明子集” |
| B-07 | 更新时间语义（数据截止时间/生成时间）与一致性时限 | 基线只要求披露更新时间；NF05 明确 60 秒目标不在本轮已批准清单 | 用 `dataAsOf`／`computedAt` 两个分析态字段，标待确认 |
| B-08 | 混合数据是否可进演示视图、缺数据类别标识如何单列 | BR-IMP-07 只规定互斥标识与“默认仅真实合格数据” | 缺标识归“数据类别未知”并披露（分析推导，A5） |
| B-09 | 领域是否必填、映射覆盖率分母口径 | FR13 要求“明确领域”；Q-SPEC-03 领域层级未冻结 | 领域必填（基线推论）；只要求“按已声明口径披露覆盖率” |
| B-10 | 关键词字段口径（作者关键词/索引词/题名词） | SPEC §4.1 只列“关键词”为可选证据字段 | 不自行扩大字段集合，只使用已声明字段（A8） |
| B-11 | **“出现次数”的计数单位**（3 篇论文还是 3 次出现） | `AC-IMPL-FR13-01` 的“出现 3 次”未定义口径 | 按“同一论文同一规范词只计一次”建模（M11；A11），交评审确认 |
| B-12 | 与 UC-11/UC-12 的视图分工、边界类是否合并、上一版结果是否保留 | FR11/FR13 在模块细化中同属统计能力域；登记表预测 `StatisticsUI` 扩展到 UC-13 | 建独立边界/协调者并注明合并候选（07 §2）；分工留 R5 |
| C-01 | **取向冲突：** 模块细化 FR13-S09、`DGM-STATE-03`、`DGM-ACTIVITY-02` 以“结果对象＋状态机＋上一版结果”建模；本包按“读时投影、不建持久热点结果实体”建模（任务给定的分析方向），V2.2 只要求披露范围/口径/更新时间 | 本 UC 无权修改模块 SRS 与既有图；持久结果对象需未定义的版本/失效/回退规则 | 本包按投影建模并在 05 §4、07 §5 说明；交评审决定 |
| C-02 | **文本冲突：** 模块细化 FR13-S05 要求“展示选定窗口内的变化趋势并说明比较基线”（AC-FR13-S05-01～03）；69 AC 基线 FR13 明文“不做语义主题模型、趋势预测或质量评价” | 基线优先级：SRS V2.2、69 AC 与 SPEC 为现行基线；模块 SRS 为细化 | 本 UC 按基线不提供趋势/增长率（M37/M38；A17），差异登记 07 §5，交负责人确认 FR13-S05 是否已退出本期范围 |
| C-03 | **范围差异：** 模块细化 FR13-S03 允许叠加“机构、成果类型、数据集范围（真实/模拟/全部）”条件；基线只要求“明确领域、年份窗与数据类别” | 基线优先；扩大条件集合属需求变更 | 本 UC 只实现基线三项条件（＋真实/演示切换），不引入机构与四类成果类型条件；差异登记 07 §5 |
| C-04 | **命名差异：** 全局只有 `AnalyticsUI ..> GraphCtl`；同族 UC-11 `StatisticsUI`、UC-12 `InstitutionRankingUI`；本 UC 用 `HotspotUI`/`HotspotCoordinator`，并新增 `TopicTerm` | 命名统一属 R5 全局职责，本 UC 无权改全局图与登记表 | 在 07 §2 给出三种合并方案与 R5 建议；本包不修改全局类图与登记表 |

## 7. 结论

```text
UC-13 R1: STATIC COMPLETE (check_documentation status=STATIC_PASS_ONLY, errors=0)
UML RENDER: 2/2 OK (SVG+PNG, no syntax error)
AC BASELINE: 69 UNCHANGED (verify_69_ac PASS)
MESSAGE CONSISTENCY: M1-M39 names+senders+receivers exact match
VOPC: 4 classes, 1 business association, 2 read-only dependencies
RUNTIME TEST: NOT RUN
HUMAN REVIEW: 待评审
S02 STATUS: PARTIAL（总状态与共享追踪由父 Agent 维护）
```

UC-13 已按教材 §5.4.1–§5.4.4 与 §5.5.1–§5.5.4 完成一次可追溯的用例实现分析，并产出独立于全局 BCE 的 VOPC。B-01～B-12 与 C-01～C-04 仍需需求确认或 R5 处理；在评审人确认前，不把 UC-13 标为“业务已批准/教师已验收”。
