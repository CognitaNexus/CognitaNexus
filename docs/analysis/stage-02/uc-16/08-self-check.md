# UC-16 自检与待评审问题

**版本：** V1.0（2026-10-21）
**检查性质：** 静态文档/模型检查与 PlantUML 渲染检查；**不是**产品运行测试，不是集成/性能测试，不是教师评分，也不是人工评审签字。
**范围：** 只检查 UC-16 本目录产出。

## 1. 范围与基线检查

| 检查 | 结果 | 证据 |
|---|---|---|
| 分支 | `analysis/s01-s02-69ac` | `git branch --show-current` |
| 正式 SRS/SPEC/S01 | 未修改 | `git status --short -- docs/srs docs/spec docs/analysis/stage-01` 无本轮改动 |
| 69 条 AC | 未新增、未删除、未改号 | `verify_69_ac.py` → `PASS`，`GROUPS: {'FR': 54, 'IN01': 4, 'NF': 11}` |
| 全局 UML | 未修改 | `docs/uml/` 无本轮改动 |
| 其他 UC 目录 | 未修改 | 本轮只新增 `docs/analysis/stage-02/uc-16/` |
| 共享追踪文件/登记表/待决登记/状态文件 | 未修改 | 本轮未改 `03-AC-UC-分析模型追踪.md`、`automation/*`、`02-用例模型与规约.md` |
| 工作区他人未提交修改 | 未覆盖、未回滚 | 既有 `docs/uml/analytics`、`docs/uml/collection`、`scripts/docs` 修改保持原状 |
| 暂存区 | 无暂存文件 | `git diff --cached --name-only` 为空 |

## 2. 教材步骤对应

| 教材步骤 | 本包证据 | 状态 |
|---|---|---|
| §5.4.1 p154–155 完善用例文档 | [01-用例规约](01-UC-16-use-case-spec.md) | 已完成（未决项见第 5 节） |
| §5.4.2 p155–161 识别分析类 | [03-BCE 分析类识别](03-bce-identification.md) | 已完成；5 个参与类 |
| §5.4.3 p161–181 分析交互 | [04-顺序图与逐消息职责](04-sequence-messages.md)、`DGM-SEQ-24-four-types-scale` | 已完成；M1–M28 |
| §5.4.4 p181–183 构造 VOPC | [05-VOPC 与关系依据](05-vopc.md)、`DGM-VOPC-20-four-types-scale` | 已完成；只从本 UC 顺序图提取 |
| §5.5.1 p188–190 职责 | [06](06-class-definition.md) Do/Know 表 | 已完成 |
| §5.5.2 p190–191 属性 | 06 各实体业务属性 | 只写本 UC 可证实部分 |
| §5.5.3 p192–200 关系/多重性 | 05 第 2 节、06 第 7 节 | 交互关联不标持久多重性；实体关系 `1:0..*` 有基线依据 |
| §5.5.4 p200–201 分析机制 | 06 第 8 节 | 13 项机制，含未决标注 |
| §5.5.5 p201–204 统一分析类 | [07-与全局模型差异](07-global-model-diff.md) | 只提交 R5 输入，不改全局图 |

## 3. 模型完整性检查

| 检查 | 结果 | 说明 |
|---|---|---|
| 参与者 | 访客（主要）、核对执行者/管理员（次要） | FR16 的“系统”是执行主体，未画成系统类；“原站”不参与本 UC（Q-02） |
| 主流程 | E1–E11，共 11 步 | 每步在 02 有来源与证据等级 |
| 备选/异常 | A1–A12 | 缺类样例、类型不属四类、来源不合法、字段缺失、跨源+版本计数、无版本证据、性质未确认、计数不一致、失败/超时、未声明范围、待核验存在、来源停用 |
| BCE 候选 | 5 个：`CoverageCheckUI`/`CoverageCheckCoordinator`/`WorkEntity`/`SourceRecord`/`WorkVersion` | 每个有事件/消息依据 |
| 拒绝候选 | 核对报告/规模结果/目标阈值/类型模式/检索/统计/详情/参与关系/治理/采集/审计/会话/DTO 等 13 项 | 见 03 §4 |
| 顺序图消息 | M1–M28 | 每条有发送者/接收者/职责/依据 |
| VOPC 类数 | 5 | 与顺序图对象集合一致（三个参与者生命线、结果投影排除） |
| VOPC 是否来自本 UC 顺序图 | 是 | 05 §3 逐对象映射 |
| 四类可识别 + 类型适用字段 + 合法来源 | 已表达 | `AC-IMPL-FR16-01`；E3–E6；M5–M13；BR-UC16-01 |
| 每条来源四要素（ID/采集时间/许可/性质） | 已表达 | FR16；E5；M11/M12；BR-UC16-03 |
| 跨两来源+版本仍计 1 个实体 | 已表达 | `AC-IMPL-FR16-02`；E7/E8；M14–M18；BR-UC16-05/06 |
| 真实数单列、模拟/混合显式标注 | 已表达 | BR-IMP-07；INV-09；E9；M19–M21；BR-UC16-07 |
| 1,000,000/5,000,000 目标标 NOT RUN | 已表达 | SRS §2.1/FR16/NF02；M22/M25/M26；BR-UC16-08 |
| 缺失字段标未知 | 已表达 | §7.1；A4；M10；BR-UC16-04 |
| 只提供原站外链、不取全文 | 已表达 | FR02；SRS §2.1；M22/M27；BR-UC16-12 |
| 核对结果是读时投影、非持久报告 | 已表达 | E10；M25/M28；BR-UC16-09；03 §4、05 §4 |
| 类型适用字段事实与来源/版本事实区分 | 已表达 | 06 §4.1；05 §2.4；03 §3 |
| 只读 | 是 | 无写消息；M28 明确不发出创建/修改/删除 |
| 实现技术 | 未引入 | 无数据库/Java/API/框架/缓存/部署 |
| 新 AC/BR | 未新增、未改写 | 只新增本 UC 内部 `BR-UC16-*` 规则编号，不进入基线 |

## 4. 实际执行的验证

### 4.1 PlantUML 渲染

实际执行（仓库根目录，`PLANTUML_LIMIT_SIZE=8192`，PlantUML 1.2020.02）：

```bash
export PLANTUML_LIMIT_SIZE=8192
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-16/diagrams/DGM-SEQ-24-four-types-scale.puml
plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-16/diagrams/DGM-SEQ-24-four-types-scale.puml
plantuml -charset UTF-8 -tsvg -o generated docs/analysis/stage-02/uc-16/diagrams/DGM-VOPC-20-four-types-scale.puml
plantuml -charset UTF-8 -tpng -o generated docs/analysis/stage-02/uc-16/diagrams/DGM-VOPC-20-four-types-scale.puml
```

结果：

| 模型 | PUML | SVG | PNG（像素） | 目视检查 |
|---|---|---|---|---|
| `DGM-SEQ-24-four-types-scale` | 有 | 有 | 有（3082 × 2277） | M1–M28 可读；A9 失败分支、缺项/差异分支、`alt` 嵌套清晰；中文与括号内标注未截断；无 Syntax Error |
| `DGM-VOPC-20-four-types-scale` | 有 | 有 | 有（1067 × 1211） | 5 个类；控制→3 个实体的只读依赖（虚线）；实体间 `1:0..*` 两条关系；legend 未溢出；无 Syntax Error |

两个 SVG 均可被 XML 解析，且不含 PlantUML 错误图标记（`SyntaxError`）。渲染成功**不**证明业务运行、规模达标或需求验收通过。

### 4.2 文档静态检查

实际执行：

```bash
python3 scripts/docs/check_documentation.py
```

结果见第 6 节实测记录（`status`、`relative_links`、`uml_svg_pairs`、`errors`）。

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

输出要点：相对链接可解析；`human_joint_approval: NOT_VERIFIED`；`application_test: NOT_EXECUTED`；`performance_and_recovery: NOT_RUN`；`codearts: NOT_VERIFIED`。

### 4.4 未执行的验证

| 验证类型 | 状态 | 说明 |
|---|---|---|
| 单元测试 / 集成测试 | NOT RUN | 本阶段只做需求分析，无实现代码 |
| 四类覆盖的端到端核对（`AC-IMPL-FR16-01`） | NOT RUN | 无实现与数据，本包只给出可观察判定与消息依据 |
| 去重规模核对（`AC-IMPL-FR16-02`） | NOT RUN | 无实现与数据；计数口径只作模型约束 |
| 1,000,000 功能联调规模 | **NOT RUN** | 目标值，未执行 |
| 5,000,000 最终容量与 100 活跃并发（NF02） | **NOT RUN** | 未压测，未记录 P50/P95/P99、吞吐、错误率、硬件与索引证据 |
| 可用性三态（`AC-IMPL-NF01-01`） | NOT RUN | 本 UC 只给出“执行失败 ≠ 零覆盖”的模型依据 |
| 人工评审 / 教师验收 | 待评审 | 本轮无签字结论 |

## 5. 待评审/待确认

| 编号 | 问题 | 为什么不能自行决定 | 当前处理 |
|---|---|---|---|
| Q-01 | 核对范围/截止时间/待核验口径的声明方式、默认值与可复用性 | FR16-S03 只要求“已声明”，未规定声明主体与形式（模块为 Review Candidate） | A10 只要求“未声明不给通过结论” |
| Q-02 | 参与者最终集合（FR16 写“系统/管理员/访客”；`02-用例模型与规约.md` 写“访客、原站”） | 属跨文档口径，需联合评审 | 本 UC 按 FR16 纳入核对执行者/管理员、排除原站；登记待评审 |
| Q-03 | 模拟样例能否补齐某类覆盖（FR16-S01 A1 允许 vs `AC-IMPL-FR16-01` 的“四类各有一条可公开样例”） | 两处来源口径不同，聚合规则未冻结 | A1：模拟覆盖功能、真实覆盖项单列不通过 |
| Q-04 | 数据性质“未确认”是否成为第四分组或并入待核验数量 | BR-IMP-07 只定义真实/模拟/混合互斥 | A7 单列并显式未知；与 UC-02 Q-07 同源 |
| Q-05 | 待核验/异常输入是否进入规模分子或分母 | FR16-S03 只要求披露数量 | A11：不入分子、数量单列 |
| Q-06 | 计数差异的定位维度与“不确定版本关联”披露粒度 | SRS 只要求“可定位差异” | A8 只要求可定位到差异项 |
| Q-07 | §7.1 的类型人员字段（作者顺序/发明人/负责人/获奖人员）与 `Authorship` 的归属 | 影响全局分析类属性归属（R5） | 沿用 UC-02：角色/顺序归 `Authorship`，机构/团队与专属标识归 `WorkEntity` 类型字段 |
| Q-08 | 模块 FR16-S03 的“≥10,000 条”与 SRS V2.2 的 1,000,000/5,000,000 冲突 | 模块为历史 V1.9 口径的 Review Candidate，不能反向覆盖基线 | 本 UC 以 SRS V2.2 为准；10,000 只作历史来源；需模块修订（待负责人决定） |
| Q-09 | 核对结论是否需要留痕/可复核历史 | 若留痕则涉及 `AuditEvidence`/FR09-S12 与 UC-17 | 本 UC 按“读时投影、不留痕”处理 |
| Q-10 | “一条真实公开来源演示链”是否为硬性通过门槛（Q-207） | 模块明确标注待确认，不得冒称硬指标 | 只如实披露真实/模拟分组，不据此判定总体验收 |
| Q-11 | NF02“可检索去重实体”与 FR16“去重后可检索成果实体”是否等同按 `publicState` 过滤 | 口径未冻结 | 按“当前公开、生效、可检索的去重实体”读取 |

## 6. 实测记录

```text
$ PLANTUML_LIMIT_SIZE=8192 plantuml -charset UTF-8 -tsvg/-tpng -o generated <两个 .puml>
rendered: DGM-SEQ-24-four-types-scale.svg/.png（）, DGM-VOPC-20-four-types-scale.svg/.png（1067×1211）
SVG XML parse: 2/2 OK, 0 个 Syntax Error 图

$ python3 scripts/docs/check_documentation.py
{"status": "STATIC_PASS_ONLY", "relative_links": 2247, "uml_svg_pairs": 30, "errors": [], "local_source_dependencies": []}

说明：`uml_svg_pairs=30` 只统计 `docs/uml/**` 下每个 .puml 对应的 `.svg` 对；UC-16 两张图不在该目录，
已另外用 XML 解析与目视检查验证（见 4.1）。`errors=[]` 表示本目录所有相对链接均可解析。

$ python3 scripts/docs/verify_69_ac.py
PASS: 69 AC unique; SRS text matches ledger; trace has all 69; UC-01..19/22 present; A1c and scale markers present; active references resolve.
GROUPS: {'FR': 54, 'IN01': 4, 'NF': 11}
```

## 7. 结论

```text
UC-16: STATIC COMPLETE
UML RENDER: 2/2 OK
AC BASELINE: 69 UNCHANGED
SCALE TARGETS (1,000,000 / 5,000,000): NOT RUN
RUNTIME TEST: NOT RUN
HUMAN REVIEW: 待评审
```

UC-16 已按教材 §5.4.1–§5.4.4、§5.5.1–§5.5.4 完成一次可追溯的用例实现分析，并产出独立 VOPC（只来自本 UC 顺序图）。Q-01～Q-11 未解决前，不把 UC-16 标为“业务已批准/规模已达标/教师已验收”。
