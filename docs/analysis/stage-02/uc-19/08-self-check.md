# UC-19 自检与待评审问题

**版本：** V1.0（2026-10-21）
**检查性质：** 静态文档/模型检查；不是产品运行测试，不是教师评分，不是人工评审签字。
**范围：** 只检查 UC-19 分析包，以及本包的渲染与文档链接结果。

## 1. 范围与基线检查

| 检查 | 结果 | 证据 |
|---|---|---|
| 分支 | `analysis/s01-s02-69ac` | `git branch --show-current` |
| 正式 SRS/SPEC/S01 | 未修改 | 本轮只新增 `docs/analysis/stage-02/uc-19/` |
| 69 条 AC | 未新增、未删除、未改号 | `python3 scripts/docs/verify_69_ac.py` → PASS（§4.2） |
| 全局 UML / 用例图 / 状态图 / 既有序列 | 未修改 | 只读比对，差异与建议见 07 §2/§3/§5 |
| 其他 UC 目录 | 未修改 | 未写入 `uc-01`～`uc-18`、`uc-22` |
| 共享追踪/登记文件 | 未修改 | `03-AC-UC-分析模型追踪.md`、`automation/analysis-class-registry.md`、`automation/unresolved-decisions.md`、`automation/progress.json` 保持原状；登记建议见 07 §6 |
| 工作区既有未提交修改 | 未覆盖 | `docs/uml/analytics`、`docs/uml/collection`、`scripts/docs` 的既有修改与 `docs/reports`、`docs/srs/integration` 的新增文件保持原状 |

## 2. 教材步骤对应

| 教材步骤 | 本包证据 | 状态 |
|---|---|---|
| §5.4.1 p154–155 完善用例文档 | [01-UC-19用例规约](01-UC-19-use-case-spec.md) | 已完成（未决项见 §6） |
| §5.4.2 p155–161 识别分析类 | [03-BCE分析类识别](03-bce-identification.md)、[02-事件流与需求来源](02-event-flow-trace.md) | 已完成；13 个参与类 |
| §5.4.3 p161–181 分析交互 | [04-顺序图与逐消息职责](04-sequence-messages.md)、`DGM-SEQ-18-source-update` | 已完成；M1–M111 |
| §5.4.4 p181–183 构造 VOPC | [05-VOPC与关系依据](05-vopc.md)、`DGM-VOPC-14-source-update` | 已完成；非全局 BCE |
| §5.5.1 p188–190 职责 | [06-分析类定义](06-class-definition.md) Do/Know/候选分析操作 | 已完成 |
| §5.5.2 p190–191 属性 | 同 06 业务属性表 | 已完成本 UC 可证实部分；检查点间隔、退化比较规则、计数口径、保留期限标待确认 |
| §5.5.3 p192–200 关系/多重性 | 同 05 §2、06 §2–§15 | 6 条业务关联＋1 条操作者关联（共 7 条）标多重性；其余按消息方向，不定义持久多重性 |
| §5.5.4 p200–201 分析机制 | 同 06 §16 | 已定义 17 项机制 |
| §5.5.5 p201–204 统一分析类 | [07-与全局模型差异](07-global-model-diff.md) | 只提交 R5 输入，不重构全局图 |

## 3. 模型完整性检查

| 检查 | 结果 | 说明 |
|---|---|---|
| UC-19 参与者 | 主要：管理员（具“数据来源/采集”具体权限）；次要：获准数据来源（外部）、未授权请求者；协作方：治理处理（UC-09/10）；只读被查询对象 | 依 FR10 参与者、§8 权限表、`DGM-USECASE-03` |
| 主流程编号 | E1–E17 | 每步在 01/02 有来源 |
| 备选/异常 | A1–A14 | 均有正式来源或标注分析推导/待确认 |
| BCE 候选 | 13 个：`AdminConsole`/`SourceAdapter`/`CollectionCoordinator`/`GovernanceCoordinator`/`User`/`SourceDefinition`/`CollectionTask`/`SourceRecord`/`IncrementalChangeSet`/`WorkEntity`/`WorkVersion`/`GovernanceException`/`AuditEvidence` | 见 03 §2 |
| 被拒绝候选 | `Scheduler`/`TaskQueue`/`JobRunner`/`RetryPolicy`/`Checkpoint`/`IncrementalWindow`/`ChangeCursor`/`ChangeItem`/`FieldDiff`、接入实现类、`SourceUpdateUI`、治理与判定类、发布类、工作流类、第二套日志、策略/矩阵类、备份恢复类、`Session`、DTO、`VersionEvidence` | 见 03 §4 |
| 顺序图消息 | M1–M111 | 每条有发送者/接收者/事件步骤/职责/依据；编号与名称脚本核对（§4.3） |
| VOPC 类数 | 13 | 与顺序图对象集合一致（§4.3/05 §1） |
| 是否出现调度器/队列/运行器/重试策略/检查点/窗口类 | 否 | 03 §4；05 §4；图例 |
| 是否引入实时流式同步 | 否（FR10 不强制） | 03 §4；05 §4；E15/A7；06 §16 机制 12 |
| 是否创建/更新/发布成果 | 否 | 无写 `WorkEntity`、无建 `WorkVersion`、无发布/下架消息（M40/M41/M49/M50/M65/M66/M42/M43 均只读） |
| 是否覆盖人工审核字段 | 否 | M44 守卫后只建待办（M45）与记录分类（M47）；无写成果消息 |
| 待办是否有公开可见性 | 否 | M45/M55/M67；BR-IMP-01；06 §14 |
| 是否执行去重/身份/版本归并判定 | 否 | 对 `GovernanceCoordinator` 只有 M34/M51/M60 与回执；版本关系不建立 |
| 三种版本语义是否分开 | 是 | 06 §13；`SourceRecord.observedRevision` / `baselineRef` / `WorkVersion` |
| 分类是否唯一且依据来源侧事实 | 是 | M28/M29；BR-UC19-03 |
| 检查/来源更新/生效时间是否分开 | 是 | M20 检查开始；M70 保存来源报告更新时间与检查时间；M53 生效时间；M90 只读回读三类时间；BR-UC19-04 |
| 未变是否不造业务变更 | 是 | M36/M37；BR-UC19-05 |
| 冲突是否保留审核值并转治理待办 | 是 | M44–M48；`AC-IMPL-FR10-01` |
| 失效/删除是否不删除历史 | 是 | M63–M69；M100；BR-UC19-10 |
| 失败是否保留失败状态与恢复入口 | 是 | M83–M87；`AC-IMPL-FR10-02` |
| 复跑是否幂等 | 是 | M72/M73；A7；BR-UC19-12 |
| 状态是否只用 §6.2 六状态 | 是 | M79/M83；无新增状态 |
| 权限失败是否无业务变更 | 是 | M106–M111；A11 |
| 是否自造检查点间隔/阈值/时限 | 否 | B-01～B-05 标待确认；无具体数值 |
| 待确认项是否被写成已确认规则 | 否 | B-01～B-09 就近标注 |
| 技术实现 | 未引入数据库/Java/API/框架/索引/队列/调度器 | 只有分析职责 |

## 4. 实际执行的验证

### 4.1 PlantUML 渲染

实际执行（仓库根目录）：

```bash
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-19/diagrams/DGM-SEQ-18-source-update.puml docs/analysis/stage-02/uc-19/diagrams/DGM-VOPC-14-source-update.puml
PLANTUML_LIMIT_SIZE=16384 plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-19/diagrams/DGM-SEQ-18-source-update.puml docs/analysis/stage-02/uc-19/diagrams/DGM-VOPC-14-source-update.puml
grep -c "Syntax Error" docs/analysis/stage-02/uc-19/diagrams/generated/*.svg
```

结果（两条 `plantuml` 命令 `exit=0`；`grep -c "Syntax Error"` 两个 SVG 均为 `0`，无匹配时 grep 退出码为 1，属预期）：

| 模型 | PUML | SVG | PNG | 目视检查 |
|---|---|---|---|---|
| UC-19 管理来源更新序列 | 有 | 有（viewBox `0 0 4307 7856`；`Syntax Error` 计数 0；约 107 KB） | 有（约 1.9 MB） | 已查看（整图缩览）：16 条生命线；M1–M111；入口/来源域与基线/变更集与读取/逐条分类与处置/检查点与完成/失败与恢复/汇总与轨迹/越界拒绝/未授权拒绝九组与 A1–A14 分支可读；中文标签与图例可读 |
| UC-19 VOPC | 有 | 有（viewBox `0 0 4337 1479`；`Syntax Error` 计数 0；约 28 KB） | 有（约 0.5 MB） | 已查看（整图）：13 个类；12 条协作边与 7 条业务多重性边可读；`WorkEntity`/`WorkVersion` 只有只读边；无 `Scheduler`/`TaskQueue`/`Checkpoint`/`IncrementalWindow` |

> 布局说明：序列图较长（111 条消息）但组块顺序与事件流一致；VOPC 宽 4337 px 是因为 13 个类水平展开，长标签沿弧线放置，当前版本无压字。渲染成功只证明语法与可读性，不证明业务运行或评审通过；SVG/PNG 由 PlantUML 生成，未手工修改。

### 4.2 69 AC 与基线

```text
$ python3 scripts/docs/verify_69_ac.py
PASS: 69 AC unique; SRS text matches ledger; trace has all 69; UC-01..19/22 present; A1c and scale markers present; active references resolve.
GROUPS: {'FR': 54, 'IN01': 4, 'NF': 11}
exit=0
```

### 4.3 编号与结构脚本核对

```text
$ python3 - <<'EOF'  # 抽取 puml 消息号与发送者/接收者，与 04 表逐行比对（本轮临时核对脚本，未纳入仓库）
puml messages 111 min 1 max 111
doc rows 111
missing in puml []
missing in doc []
duplicates in puml []
exact name mismatches 0
sender/receiver mismatches 0
classes in puml 13 with messages 13
classes without messages []
EOF
```

结论：顺序图与 04 逐消息表的消息号 M1–M111 完全一致，无缺号、无重复，名称逐条相同，发送者/接收者逐条一致（参与者别名映射为“管理员/获准数据来源/未授权请求者”后比对）；13 个类均有消息。

### 4.4 文档检查（本包新增后）

```text
$ python3 scripts/docs/check_documentation.py
status = STATIC_PASS_ONLY
relative_links = 1869
uml_svg_pairs = 30
errors = []
exit=0
```

> 基线（本包开始前）：`status: STATIC_PASS_ONLY`、`relative_links: 1789`、`uml_svg_pairs: 30`、`errors: []`。本包新增 9 个 Markdown（含图源/生成物链接与包内交叉链接）后为 `relative_links: 1869`、`errors: []`。
> 中间态曾因 `08-self-check.md` 尚未落盘而出现 1 条 `missing 08-self-check.md`（当次 `relative_links: 1862`）；补齐后复跑为 `errors: []`（上方为最终态）。
> `uml_svg_pairs: 30` 只统计 `docs/uml/` 下的源图；本包 `docs/analysis/stage-02/uc-19/diagrams/` 的 SVG/PNG 由 §4.1 的渲染命令与相对链接检查共同覆盖。

## 5. NOT RUN / 未验证

| 项目 | 状态 | 说明 |
|---|---|---|
| `AC-IMPL-FR10-01/02`、`AC-IMPL-NF04-01`、`AC-IMPL-NF07-01` 的实际验收 | NOT RUN | 需求层 AC 为计划验收；本包只做静态分析 |
| 运行时行为：分类、冲突判定、生效前重检、失效登记、幂等复跑、失败恢复 | NOT RUN | 无实现代码 |
| 与真实来源（API/文件）的增量读取与删除/撤稿报告 | NOT RUN | 格式/协议未冻结（B-03；D-10） |
| 检查点间隔、批次大小、观察版本形式 | NOT RUN | 未冻结（B-01/B-02；D-10） |
| 生效结果向检索/详情/门户/统计/网络的传播与一致性 | NOT RUN | `AC-IMPL-NF05-01/02` NOT RUN；本 UC 只在声明层记录生效时间 |
| 备份恢复 RTO≤30 分钟/RPO≤24 小时 | NOT RUN | `AC-IMPL-NF04-02` 属部署运维，不属本 UC 分析范围 |
| 去重实体数/有效出处数的端到端核对 | NOT RUN | 属 UC-09/16；本 UC 只保证不重复建立变更、不新增第二条逻辑出处、不建成果 |
| 字符集/字体在生成环境的表现 | 已目视检查（本机 Noto Sans CJK），跨环境未验证 | SVG/PNG 由本机 PlantUML 生成 |
| 人工评审/教师评分 | NOT VERIFIED | 自动静态检查不等于签认 |

## 6. 待评审/待确认

| 编号 | 问题 | 为什么不能自行决定 | 当前处理 |
|---|---|---|---|
| B-01 | 检查点间隔、批次大小与恢复粒度；一次更新是新建任务、复跑既有任务还是新增关联任务 | §6.2 只要求“保留检查点与任务历史”；D-10 待确认 | 只要求“检查点可定位、可续跑”，不写数值；任务归属留待评审 |
| B-02 | 来源未提供版本信息时的退化比较规则（内容比较/指纹/直接转待核验） | FR10 以“上次版本”为依据，但退化规则未冻结 | 只写“不得判未变、不得凭标题相似”（A3），不指定实现 |
| B-03 | 回溯范围的口径与“无法比较”的披露文本 | 基线未定义 | 按“管理员指定回溯范围＋披露局限”处理（A1），标待确认 |
| B-04 | 冲突处置责任、时限与是否合并同一处理项 | FR10 只写“进入治理待办”；FR14 不做工作流引擎 | 只要求“单个可累积待办＋双方证据”，不写时限与责任矩阵 |
| B-05 | 撤稿/删除报告/外链失效对公开资格的影响由谁决定 | 三类事件在 CL-12 已分开，但公开资格归属未逐句裁定 | 本 UC 只登记事件并转待核验，不下架、不改公开资格（A2/A9/A12） |
| B-06 | 来源停用/更换的归属（UC-18 或 UC-19） | 沿用 UC-17 B-08、UC-18 B-06 归 FR10/UC-19，但边界文本未逐句裁定 | 只读来源有效性，不执行配置停用/更换 |
| B-07 | 检查时间/生效时间的保留期限与可查范围（与 D-09 联动） | 未冻结 | 只要求“保留并可查”，不承诺时间窗 |
| B-08 | 计数与分类口径（按记录/批次；冲突是否计入变更；异常与失效是否合并展示） | 未定义 | 只要求“互斥计数＋单列冲突/失效/异常＋只读” |
| B-09 | `IncrementalChangeSet` 未登记于候选类登记表；`GovernanceException` 与 UC-09 `CleanRecord` 冲突项、UC-17 待办来源的单一所有者；追踪表是否增补 `SEQ-18/VOPC-14` | 共享文件与全局命名由父 Agent/R5 维护 | 07 §6 提建议，本包不改共享文件与全局图 |

## 7. 结论

```text
UC-19 R1: STATIC COMPLETE (check_documentation status=STATIC_PASS_ONLY, errors=0)
UML RENDER: 2/2 OK (SVG+PNG, no syntax error)
AC BASELINE: 69 UNCHANGED (verify_69_ac PASS)
MESSAGE CONSISTENCY: M1-M111 names+senders+receivers exact match
CLASS COVERAGE: 13/13 classes have messages
RUNTIME TEST: NOT RUN
HUMAN REVIEW: 待评审
S02 STATUS: PARTIAL（总状态与共享追踪由父 Agent 维护）
```

UC-19 已按教材 §5.4.1–§5.4.4 与 §5.5.1–§5.5.4 完成一次可追溯的用例实现分析，并产出独立于全局 BCE 的 VOPC。B-01～B-09 仍需需求确认或 R5 处理；在评审人确认前，不把 UC-19 标为“业务已批准/教师已验收”。
