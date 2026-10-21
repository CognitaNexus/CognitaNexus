# UC-18 自检与待评审问题

**版本：** V1.0（2026-10-21）
**检查性质：** 静态文档/模型检查；不是产品运行测试，不是教师评分，不是人工评审签字。
**范围：** 只检查 UC-18 分析包，以及本包的渲染与文档链接结果。

## 1. 范围与基线检查

| 检查 | 结果 | 证据 |
|---|---|---|
| 分支 | `analysis/s01-s02-69ac` | `git branch --show-current` |
| 正式 SRS/SPEC/S01 | 未修改 | 本轮只新增 `docs/analysis/stage-02/uc-18/` |
| 69 条 AC | 未新增、未删除、未改号 | `python3 scripts/docs/verify_69_ac.py` → PASS（§4.2） |
| 全局 UML / 用例图 / 状态图 / 既有序列 | 未修改 | 只读比对，差异与建议见 07 §2/§3/§5 |
| 其他 UC 目录 | 未修改 | 未写入 `uc-01`～`uc-10`、`uc-17`、`uc-22` |
| 共享追踪/登记文件 | 未修改 | `03-AC-UC-分析模型追踪.md`、`automation/analysis-class-registry.md`、`automation/unresolved-decisions.md`、`automation/progress.json` 保持原状；登记建议见 07 §6 |
| 工作区既有未提交修改 | 未覆盖 | `docs/uml/analytics`、`docs/uml/collection`、`scripts/docs` 的既有修改与 `docs/reports`、`docs/srs/integration` 的新增文件保持原状 |

## 2. 教材步骤对应

| 教材步骤 | 本包证据 | 状态 |
|---|---|---|
| §5.4.1 p154–155 完善用例文档 | [01-UC-18用例规约](01-UC-18-use-case-spec.md) | 已完成（未决项见 §6） |
| §5.4.2 p155–161 识别分析类 | [03-BCE分析类识别](03-bce-identification.md)、[02-事件流与需求来源](02-event-flow-trace.md) | 已完成；10 个参与类 |
| §5.4.3 p161–181 分析交互 | [04-顺序图与逐消息职责](04-sequence-messages.md)、`DGM-SEQ-17-collection` | 已完成；M1–M100 |
| §5.4.4 p181–183 构造 VOPC | [05-VOPC与关系依据](05-vopc.md)、`DGM-VOPC-13-collection` | 已完成；非全局 BCE |
| §5.5.1 p188–190 职责 | [06-分析类定义](06-class-definition.md) Do/Know/候选分析操作 | 已完成 |
| §5.5.2 p190–191 属性 | 同 06 业务属性表 | 已完成本 UC 可证实部分；重试上限、检查点间隔、计数口径、历史保留标待确认 |
| §5.5.3 p192–200 关系/多重性 | 同 05 §2、06 §12 | 5 条业务事实关联＋1 条操作者关联（共 6 条）标多重性；其余按消息方向，不定义持久多重性 |
| §5.5.4 p200–201 分析机制 | 同 06 §13 | 已定义 17 项机制 |
| §5.5.5 p201–204 统一分析类 | [07-与全局模型差异](07-global-model-diff.md) | 只提交 R5 输入，不重构全局图 |

## 3. 模型完整性检查

| 检查 | 结果 | 说明 |
|---|---|---|
| UC-18 参与者 | 主要：管理员（具“数据来源/采集”具体权限）；次要：获准数据来源（外部）、未授权请求者；协作方：治理处理（UC-09/10）；只读被查询对象 | 依 FR08 参与者、§8 权限表、`DGM-USECASE-03` |
| 主流程编号 | E1–E19 | 每步在 01/02 有来源 |
| 备选/异常 | A1–A14 | 均有正式来源或标注分析推导/待确认 |
| BCE 候选 | 10 个：`AdminConsole`/`SourceAdapter`/`CollectionCoordinator`/`GovernanceCoordinator`/`User`/`SourceDefinition`/`CollectionTask`/`SourceRecord`/`GovernanceException`/`AuditEvidence` | 见 03 §2 |
| 被拒绝候选 | `Scheduler`/`TaskQueue`/`JobRunner`/`RetryPolicy`/`Checkpoint`/`TaskRun`/`ImportBatch`/`ExceptionQueue`、接入实现类、`CollectionConsole`、治理与判定类、发布类、工作流类、第二套日志、策略/矩阵类、备份恢复类、`Session`、DTO | 见 03 §4 |
| 顺序图消息 | M1–M100 | 每条有发送者/接收者/事件步骤/职责/依据；编号与名称脚本核对（§4.4） |
| VOPC 类数 | 10 | 与顺序图对象集合一致（§4.4/05 §1） |
| 是否出现调度器/队列/运行器/重试策略/检查点类 | 否 | 03 §4；05 §4；图例 |
| 是否创建/更新/发布成果 | 否 | 无 `WorkEntity`/`Authorship`/公开视图消息；A11 |
| 异常项是否有公开可见性 | 否 | M42/M43；A11；06 §10 |
| 是否执行去重/身份归属判定 | 否 | 对 `GovernanceCoordinator` 只有 M40/M41 |
| 成功判据是否唯一且可观察 | 是 | M54–M58（计划输入全部记录到结果或异常队列） |
| 待核验/异常计数是否独立披露且措辞受限 | 是 | M60–M62；BR-UC18-08 |
| 重试是否保留历史、不重做、可定位失败点 | 是 | M76–M81；M89–M94；A6 |
| 重复到达是否只追加事件、不新增逻辑出处 | 是 | M38/M39；A12；BR-UC18-12 |
| 状态是否只用 §6.2 六状态 | 是 | M27/M59/M65/M70/M80；无新增状态 |
| 权限失败是否无业务变更 | 是 | M95–M100；A1；BR-UC18-03 |
| 是否自造重试上限/检查点间隔/阈值 | 否 | B-01/B-02 标待确认；无具体数值 |
| 待确认项是否被写成已确认规则 | 否 | B-01～B-11 就近标注 |
| 技术实现 | 未引入数据库/Java/API/框架/索引/队列/调度器 | 只有分析职责 |

## 4. 实际执行的验证

### 4.1 PlantUML 渲染

实际执行（仓库根目录）：

```bash
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-18/diagrams/DGM-SEQ-17-collection.puml docs/analysis/stage-02/uc-18/diagrams/DGM-VOPC-13-collection.puml
PLANTUML_LIMIT_SIZE=8192 plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-18/diagrams/DGM-SEQ-17-collection.puml docs/analysis/stage-02/uc-18/diagrams/DGM-VOPC-13-collection.puml
grep -c "Syntax Error" docs/analysis/stage-02/uc-18/diagrams/generated/*.svg
```

结果（两条命令均 `exit=0`；`grep -c "Syntax Error"` 两个 SVG 均为 `0`）：

| 模型 | PUML | SVG | PNG | 目视检查 |
|---|---|---|---|---|
| UC-18 管理来源及采集任务序列 | 有 | 有（viewBox `0 0 4243 6701`；`Syntax Error` 计数 0） | 有（4242×6701；约 1.5 MB） | 已查看（整图缩览）：13 条生命线；M1–M100；入口/配置/启动/读取移交/进度/完成/停止/重试/历史/拒绝十组与 A1–A14 分支可读；中文与图例可读 |
| UC-18 VOPC | 有 | 有（viewBox `0 0 2918 1704`；`Syntax Error` 计数 0） | 有（2917×1704；约 0.4 MB） | 已查看（整图）：10 个类；9 条协作边与 6 条业务多重性边＋`AuditEvidence 0..*→0..1 User` 可读；无 `Scheduler`/`TaskQueue`/`WorkEntity` |

> 布局调整记录：VOPC 首版用默认曲线加长标签，`SourceDefinition`/`CollectionTask` 区域标签互相压叠；第二版改用 `linetype ortho` 后长标签被推到图外且“M38”标签离开所属边；最终版回到默认线型并缩短边标签（“M20–M27 检查/快照/检查点/历史/启动”“M32–M59 读取、计数、完成判据”“M65/M66 停止；M80/M81 重试”），重渲后为提交版。该调整只影响布局与标签措辞，未改变类集合、关系或业务结论。
> 序列图曾缩短 M8（“许可/使用范围”）与 M95（“未授权请求者提交采集操作”）以避免标签压住生命线/参与者名；名称同步在 04 表与本图，脚本核对一致（§4.4）。
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
relative_links = 1789
uml_svg_pairs = 30
errors = []
exit=0
```

> 基线（本包开始前）：`status: STATIC_PASS_ONLY`、`relative_links: 1714`、`uml_svg_pairs: 30`、`errors: []`。本包新增 9 个 Markdown（含图源/生成物链接与包内交叉链接）后为 `relative_links: 1789`、`errors: []`（上方为最终态；中间态曾因 `08-self-check.md` 尚未落盘而出现 1 条 missing 链接，补齐后复跑通过）。
> `uml_svg_pairs: 30` 只统计 `docs/uml/` 下的源图；本包 `docs/analysis/stage-02/uc-18/diagrams/` 的 SVG/PNG 由 §4.1 的渲染命令与相对链接检查共同覆盖。

### 4.4 编号与结构脚本核对

```text
$ python3 - <<'EOF'  # 抽取 puml 消息号与发送者/接收者，与 04 表逐行比对
puml messages 100 min 1 max 100
doc rows 100 missing in puml [] missing in doc []
exact name mismatches 0
sender/receiver mismatches []
EOF
```

结论：顺序图与 04 逐消息表的消息号 M1–M100 完全一致，无缺号、无重复，名称逐条相同，发送者/接收者逐条一致（参与者别名映射为“管理员/获准数据来源/未授权请求者”后比对）。

## 5. NOT RUN / 未验证

| 项目 | 状态 | 说明 |
|---|---|---|
| `AC-IMPL-FR08-01/02`、`AC-IMPL-NF04-01`、`AC-IMPL-NF07-01` 的实际验收 | NOT RUN | 需求层 AC 为计划验收；本包只做静态分析 |
| 运行时行为：来源读取、逐条登记、移交、完成判据、停止、重试 | NOT RUN | 无实现代码 |
| 与真实来源（API/文件）的接入连通性 | NOT RUN | 格式/协议未冻结（B-03；D-10） |
| 重试上限与检查点间隔的实际取值 | NOT RUN | 未冻结（B-01/B-02；D-10） |
| 负载/容量（百万级成果采集） | NOT RUN | 基线未定义本 UC 的量化指标；本 UC 不主张覆盖 NF02 |
| 备份恢复 RTO≤30 分钟/RPO≤24 小时 | NOT RUN | `AC-IMPL-NF04-02` 属部署运维，不属本 UC 分析范围 |
| 去重实体数/有效出处数的端到端核对 | NOT RUN | 属 UC-09/16；本 UC 只保证不新建第二条逻辑出处、不创建成果 |
| 字符集/字体在生成环境的表现 | 已目视检查（本机 Noto Sans CJK），跨环境未验证 | SVG/PNG 由本机 PlantUML 生成 |
| 人工评审/教师评分 | NOT VERIFIED | 自动静态检查不等于签认 |

## 6. 待评审/待确认

| 编号 | 问题 | 为什么不能自行决定 | 当前处理 |
|---|---|---|---|
| B-01 | 重试上限（次数/耗尽后处置） | D-10 已登记待确认；SRS/SPEC 未给数值 | 只写“未超过上限”（M79；A7），不写次数 |
| B-02 | 检查点间隔、批次大小与恢复粒度 | §6.2 只要求“保留检查点与任务历史” | 只要求“检查点可定位、可续跑”，不指定口径 |
| B-03 | 来源 API/文件格式、字段映射、分页/游标 | 属接口与实现设计（D-10） | 只用 `inputType` 表达“API 或文件任选”，不定义协议 |
| B-04 | 计划输入口径（清单快照/可枚举范围/读取窗口） | 基线未定义 | 按“启动时保存可核对范围快照”处理（分析推导） |
| B-05 | 计划输入为零时是否算成功 | §6.2 对空集合未定义 | 按“拒绝启动/明确空范围，不冒充成功”处理（A8），标待确认 |
| B-06 | 来源停用/更换的归属（UC-18 或 UC-19） | 沿用 UC-17 B-08 归 UC-19，但边界文本未逐句裁定 | 只读取来源有效性，不执行配置停用/更换 |
| B-07 | 任务历史与检查点的保留期限/可查范围（与 D-09 联动） | 未冻结 | 只要求“保留并可查”，不承诺时间窗 |
| B-08 | 进度与计数口径（记录数/批次数、待核验与异常是否合并） | 未定义 | 只要求“互斥计数＋单列待核验/异常＋只读” |
| B-09 | “记录到结果”是否等价于“治理已受理并登记处置结果” | §6.2 未逐句定义 | 按“移交受理并取得处置结果”处理（E13/E17） |
| B-10 | `GovernanceCoordinator` 的命名与粒度（vs `DedupCoordinator`/`IdentityVerificationCoordinator`） | 全局命名统一属 R5 | 只作为协作类引用，不定义其规则 |
| B-11 | 命名与登记：`CollectionTask`/`SourceDefinition`/`GovernanceException` 未登记于候选类登记表；追踪表是否增补 `SEQ-17/VOPC-13` | 共享文件由父 Agent/R5 维护 | 07 §6 提建议，本包不改共享文件与全局图 |

## 7. 结论

```text
UC-18 R1: STATIC COMPLETE (check_documentation status=STATIC_PASS_ONLY, errors=0)
UML RENDER: 2/2 OK (SVG+PNG, no syntax error)
AC BASELINE: 69 UNCHANGED (verify_69_ac PASS)
MESSAGE CONSISTENCY: M1-M100 names+senders+receivers exact match
RUNTIME TEST: NOT RUN
HUMAN REVIEW: 待评审
S02 STATUS: PARTIAL（总状态与共享追踪由父 Agent 维护）
```

UC-18 已按教材 §5.4.1–§5.4.4 与 §5.5.1–§5.5.4 完成一次可追溯的用例实现分析，并产出独立于全局 BCE 的 VOPC。B-01～B-11 仍需需求确认或 R5 处理；在评审人确认前，不把 UC-18 标为“业务已批准/教师已验收”。
