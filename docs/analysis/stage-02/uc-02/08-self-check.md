# UC-02 自检与待评审问题

**版本：** V1.0（2026-10-21）
**检查性质：** 静态文档/模型检查与 PlantUML 渲染检查；**不是**产品运行测试，不是集成/性能测试，不是教师评分，也不是人工评审签字。
**范围：** 只检查 UC-02 本目录产出。

## 1. 范围与基线检查

| 检查 | 结果 | 证据 |
|---|---|---|
| 分支 | `analysis/s01-s02-69ac` | `git branch --show-current` |
| 正式 SRS/SPEC/S01 | 未修改 | `git status --short -- docs/srs docs/spec docs/analysis/stage-01` 无本轮改动 |
| 69 条 AC | 未新增、未删除、未改号 | `verify_69_ac.py` → `PASS`，`GROUPS: {'FR': 54, 'IN01': 4, 'NF': 11}` |
| 全局 UML | 未修改 | `docs/uml/` 无本轮改动 |
| 其他 UC 目录 | 未修改 | 本轮只新增 `docs/analysis/stage-02/uc-02/` |
| 共享追踪文件/登记表/状态文件 | 未修改 | 本轮未改 `03-AC-UC-分析模型追踪.md`、`automation/*` |
| 工作区他人未提交修改 | 未覆盖、未回滚 | 原有 `docs/uml/analytics`、`docs/uml/collection`、`scripts/docs` 的既有修改保持原状 |

## 2. 教材步骤对应

| 教材步骤 | 本包证据 | 状态 |
|---|---|---|
| §5.4.1 p154–155 完善用例文档 | [01-用例规约](01-UC-02-use-case-spec.md) | 已完成（未决项见第 5 节） |
| §5.4.2 p155–161 识别分析类 | [03-BCE 分析类识别](03-bce-identification.md) | 已完成；6 个参与类 |
| §5.4.3 p161–181 分析交互 | [04-顺序图与逐消息职责](04-sequence-messages.md)、`DGM-SEQ-07-detail` | 已完成；M1–M28 |
| §5.4.4 p181–183 构造 VOPC | [05-VOPC 与关系依据](05-vopc.md)、`DGM-VOPC-03-detail` | 已完成；只从本 UC 顺序图提取 |
| §5.5.1 p188–190 职责 | [06](06-class-definition.md) Do/Know 表 | 已完成 |
| §5.5.2 p190–191 属性 | 06 各实体业务属性 | 只写本 UC 可证实部分 |
| §5.5.3 p192–200 关系/多重性 | 05 第 2 节、06 第 8 节 | 交互关联不标持久多重性；实体关系 1:0..* 有基线依据 |
| §5.5.4 p200–201 分析机制 | 06 第 9 节 | 12 项机制，含未决标注 |
| §5.5.5 p201–204 统一分析类 | [07-与全局模型差异](07-global-model-diff.md) | 只提交 R5 输入，不改全局图 |

## 3. 模型完整性检查

| 检查 | 结果 | 说明 |
|---|---|---|
| 参与者 | 访客（主要）、原站（次要） | 原站是外部参与者，未画成系统类 |
| 主流程 | E1–E11，共 11 步 | 每步在 02 有来源 |
| 备选/异常 | A1–A10 | 不可访问、外链失效、执行失败、字段缺失、多来源、许可不明、无版本证据、待审值、未确认参与、类型缺失 |
| BCE 候选 | 6 个：`WorkDetailUI`/`WorkDetailCoordinator`/`WorkEntity`/`SourceRecord`/`WorkVersion`/`Authorship` | 每个有事件/消息依据 |
| 拒绝候选 | Search 系、Claim/Authorization、Scholar、Organization、详情 DTO、字段字典、许可策略、SourceAdapter、行为日志等 12 项 | 见 03 第 4 节 |
| 顺序图消息 | M1–M28 | 每条有发送者/接收者/职责/依据 |
| VOPC 类数 | 6 | 与顺序图对象集合一致（访客、原站排除） |
| VOPC 是否含 Search/Claim/Auth/Scholar | 否 | 见 05 第 2.3 节 |
| VOPC 是否来自本 UC 顺序图 | 是 | 05 第 3 节逐对象映射 |
| 一条成果两条来源 | 已表达 | `AC-IMPL-FR02-01`；M13/M14/M20；`1:0..*` |
| 来源数/版本数 ≠ 成果数 | 已表达 | `AC-IMPL-FR16-02`；BR-IMP-02；INV-02 |
| 缺失字段标未知 | 已表达 | A4；SRS §7.1 |
| 时间语义分开 | 已表达 | E4/M11/M12；BR-UC02-04 |
| 外链只跳原站、不承诺全文 | 已表达 | M21–M23；BR-UC02-06 |
| 外链失效不删成果 | 已表达 | A2；M26–M28；`AC-IMPL-FR02-02` |
| 版本关系需明示证据 | 已表达 | E6/M15/M16；A7；DD-08 |
| 待确认参与不冒充已确认 | 已表达 | A9；M18；SRS §6.4/§11.3 |
| 待审/未公开/无效数据不公开 | 已表达 | 前置条件 2；A1/A8；BR-UC02-01/10 |
| 只读 | 是 | 无写消息；M28 明确不删除/不隐藏 |
| 实现技术 | 未引入 | 无数据库/Java/API/框架/缓存/部署 |
| 新 AC | 未新增 | 69 AC 不变 |

## 4. 实际执行的验证

### 4.1 PlantUML 渲染

实际执行（仓库根目录）：

```bash
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-02/diagrams/DGM-SEQ-07-detail.puml
plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-02/diagrams/DGM-SEQ-07-detail.puml
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-02/diagrams/DGM-VOPC-03-detail.puml
plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-02/diagrams/DGM-VOPC-03-detail.puml
```

结果：

| 模型 | PUML | SVG | PNG | 目视检查 |
|---|---|---|---|---|
| `DGM-SEQ-07-detail` | 有 | 有 | 有 | M1–M28 可读；A1/A2/A3 分支与“一条成果+多来源”标注可读；无 Syntax Error |
| `DGM-VOPC-03-detail` | 有 | 有 | 有 | 6 类；控制→4 实体读取关系；实体间 1:0..* 三条关系；无 Syntax Error |

两个 SVG 均可被 XML 解析，且不含 PlantUML 错误图标记。渲染成功**不**证明业务运行或需求验收通过。

### 4.2 文档静态检查

实际执行：

```bash
python3 scripts/docs/check_documentation.py
```

结果：见第 6 节实测记录（`status`、`relative_links`、`uml_svg_pairs`、`errors`）。

### 4.3 AC 基线检查

实际执行：

```bash
python3 scripts/docs/verify_69_ac.py
```

输出：

```text
PASS: 69 AC unique; SRS text matches ledger; trace has all 69; UC-01..19/22 present; A1c and scale markers present; active references resolve.
GROUPS: {'FR': 54, 'IN01': 4, 'NF': 11}
```

```bash
python3 scripts/docs/verify_scoped_srs.py
```

输出要点：`checked`/`resolved_relative_links` 正常，`human_joint_approval: NOT_VERIFIED`，`application_test: NOT_EXECUTED`，`performance_and_recovery: NOT_RUN`，`codearts: NOT_VERIFIED`。

### 4.4 未执行的验证

| 验证类型 | 状态 | 说明 |
|---|---|---|
| 单元测试 / 集成测试 | NOT RUN | 本阶段只做需求分析，无实现代码 |
| 性能与容量（NF02、`AC-IMPL-NF02-01/02`） | NOT RUN | 500 万/100 并发/5 秒超时未实测 |
| 可用性三态（`AC-IMPL-NF01-01`） | NOT RUN | 本 UC 只给出三态分离的模型依据 |
| 人工评审 / 教师验收 | 待评审 | 本轮无签字结论 |

## 5. 待评审/待确认

| 编号 | 问题 | 为什么不能自行决定 | 当前处理 |
|---|---|---|---|
| Q-01 | 外链失效提示文案、是否重试、平台是否主动探测外链可用性 | SRS 只要求“外链明确失败”；与全局 D-05 同源 | A2 只保证“元数据可读+失败明示+不删成果” |
| Q-02 | 对外是否细分“不存在/无权/待审”不可访问状态 | NF01 要求状态可区分，粒度未冻结 | 分析层只要求“可区分且不泄露字段” |
| Q-03 | 无明示证据时公开详情是否呈现“疑似版本”标记、对谁呈现 | FR02 只说“有证据才显示版本关系”；SPEC DD-08/T20 已要求内部标疑似 | A7 公开侧暂不显示；内部疑似标记按 SPEC 保留 |
| Q-04 | 多来源展示顺序 / 是否标“主来源” | SRS 只要求全部可公开来源可追溯 | 只要求逐条可追溯 |
| Q-05 | 待核验/争议参与是否以“疑似参与”展示；详情是否内联学者门户入口 | 前者 SRS 只禁止“已确认”表达；后者属 UC-03 边界 | A9 只禁止冒充已确认；不引入 Scholar |
| Q-06 | 成果若无任何公开许可来源，是否整体不可公开 | FR02 执行前提未给结论 | 前置条件 4 只要求“至少一条公开许可来源”，实现口径待评审 |
| Q-07 | 来源级数据性质与成果级“真实/模拟/混合”的聚合口径 | BR-IMP-07 只规定互斥标识 | 分别建模，聚合规则待定 |
| Q-08 | 缺失字段是否展示缺失原因/最后核验时间 | SRS §7.1 只要求标“未知” | A4 只标未知 |
| Q-09 | 有待审新值时是否提示“有待审更新” | SRS §6.4 只要求旧值继续公开 | A8 只做值屏蔽 |
| Q-10 | NF02 超时/容量在详情路径的适用方式 | 性能目标未实测 | 只作约束引用，NOT RUN |
| Q-11 | 详情协调职责最终归属（独立控制器 vs 扩展 `SearchCtl`） | 属全局分析类统一（R5，与 D-11/D-12 同类） | 07 登记建议，不擅改全局图 |
| Q-12 | 机构/团队（承担单位、获奖团队）是否独立成类 | 影响 FR11/FR12，跨模块 | 06 暂按成果类型字段承载 |
| Q-13 | 作者角色/顺序归 `Authorship` 还是按 §7.1 归论文专属字段 | 影响全局分析类属性归属 | 本 UC 按关系事实归 `Authorship`；R5 统一 |

## 6. 实测记录

```text
$ plantuml -charset UTF-8 -tsvg/-tpng -o generated <两个 .puml>
rendered: DGM-SEQ-07-detail.svg/.png, DGM-VOPC-03-detail.svg/.png
SVG XML parse: 2/2 OK, 0 个 Syntax Error 图

$ python3 scripts/docs/check_documentation.py
{"status": "STATIC_PASS_ONLY", "relative_links": 1284, "uml_svg_pairs": 30, "errors": [], "local_source_dependencies": []}

说明：`uml_svg_pairs=30` 只统计 `docs/uml/**` 下每个 .puml 对应的 `.svg` 对；UC-02 两张图不在该目录，
已另外用 XML 解析与目视检查验证（见 4.1）。`errors=[]` 表示本目录所有相对链接均可解析。

$ python3 scripts/docs/verify_69_ac.py
PASS: 69 AC unique; SRS text matches ledger; trace has all 69; GROUPS {'FR': 54, 'IN01': 4, 'NF': 11}
```

## 7. 结论

```text
UC-02: STATIC COMPLETE
UML RENDER: 2/2 OK
AC BASELINE: 69 UNCHANGED
RUNTIME TEST: NOT RUN
HUMAN REVIEW: 待评审
```

UC-02 已按教材 §5.4.1–§5.4.4、§5.5.1–§5.5.4 完成一次可追溯的用例实现分析，并产出独立 VOPC（只来自本 UC 顺序图）。Q-01～Q-12 未解决前，不把 UC-02 标为“业务已批准/教师已验收”。
