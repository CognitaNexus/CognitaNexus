# UC-09 用例实现分析（核验和纠正成果数据）

**版本：** V1.0（2026-10-21）
**输入基线：** `S01 FROZEN — 69 AC BASELINE`；[SRS V2.2（69 AC）](../../../srs/CognitaNexus-Shared-SRS-V2.2-69AC.md) FR09（规范性要求与 `AC-IMPL-FR09-01`～`06`）、FR16（四类字段与规模计数）、FR15（身份边界）、NF02（去重实体容量基线）、NF07（来源可维护）、BR-IMP-02/04/06/10、§6.1、§7.1/§7.2、§8、§11.4；[核心 SPEC V1.0](../../../spec/CognitaNexus-Core-SPEC-V1.0.md) §3（公共契约/状态/INV-01～INV-10）、§4（CL-01～CL-12 清洗规则）、§5（DD-01～DD-12 去重判定）、§5.5（并发/错误/复算边界）、§10（追踪）；模块细化 [`governance-dedup.md`](../../../srs/modules/governance-dedup.md) FR09-S01～S12（细化，不反向覆盖基线）；教材第 5 章 §5.4–§5.5。
**范围：** 只分析 UC-09“核验和纠正成果数据”。不分析来源接入与采集任务（UC-18）、增量同步与恢复（UC-19）、学者身份消歧与归属改派（UC-10）、学者本人纠错申请（UC-08）、检索/详情/门户/统计展示（UC-01/UC-02/UC-03/UC-11）、后台待办与审计查询（UC-17）；不重构全局类图，不设计数据库/接口/发布流水线。
**前置关系：** UC-18/UC-19 提供“已获准接入且已到达的来源记录批次”（本 UC 的输入）；UC-02 建立 `WorkEntity`/`SourceRecord`/`WorkVersion` 的静态字段语义（本 UC 复用其字段定义，不重画详情展示）；UC-08 处理学者发起的成果纠错申请（本 UC 只提供治理侧事实，不替代其审核路径）。
**状态：** UC-09 分析完成（STATIC COMPLETE，UML 2/2 渲染通过）；S02 总体状态与共享追踪文件由父 Agent 维护。

## 1. 交付内容

| 文件 | 内容 |
|---|---|
| [01-UC-09用例规约](01-UC-09-use-case-spec.md) | 参与者、目标、触发、前置/成功/失败后置、E1–E17 基本流、A1–A16 备选/异常、业务规则、关联状态、AC 覆盖、待确认 |
| [02-事件流与需求来源](02-event-flow-trace.md) | E1–E17/A1–A16 的逐项来源与证据等级；不纳入本 UC 的事件 |
| [03-BCE分析类识别](03-bce-identification.md) | 9 个候选类、逐类理由、拒绝候选、`SourceRecord`/`CleanRecord`/`WorkEntity`/`WorkVersion`/`DedupCandidate`/`AuditEvidence` 六方边界 |
| [04-顺序图与逐消息职责](04-sequence-messages.md) | M1–M96 的发送者/接收者/事件步骤/职责/依据；异常、待核验、幂等、合并/拆分、字段冲突与失败分支 |
| [05-VOPC与关系依据](05-vopc.md) | 9 个参与类、关系与多重性理由、排除项；VOPC 只来自本 UC 顺序图 |
| [06-分析类定义](06-class-definition.md) | 每类 Do/Know、候选分析操作、业务属性、关系、分析机制（§5.5.1–§5.5.4） |
| [07-与全局模型差异](07-global-model-diff.md) | 与 `DGM-CLASS-01-analysis.puml`、`01-BCE分析类模型.md`、`DGM-ACTIVITY-06/08`、`DGM-STATE-04` 的一致/命名/职责/关系差异与 R5 建议 |
| [08-自检与待评审](08-self-check.md) | 范围与基线检查、教材步骤对应、模型完整性、实际渲染/文档检查结果、B-01～B-10 |

## 2. UML

| 模型 | 源 | SVG | PNG |
|---|---|---|---|
| UC-09 确定性清洗与去重治理分析序列（96 条消息） | [`diagrams/DGM-SEQ-14-dedup.puml`](diagrams/DGM-SEQ-14-dedup.puml) | [`generated/DGM-SEQ-14-dedup.svg`](diagrams/generated/DGM-SEQ-14-dedup.svg) | [`generated/DGM-SEQ-14-dedup.png`](diagrams/generated/DGM-SEQ-14-dedup.png) |
| UC-09 VOPC（9 个参与类） | [`diagrams/DGM-VOPC-10-dedup.puml`](diagrams/DGM-VOPC-10-dedup.puml) | [`generated/DGM-VOPC-10-dedup.svg`](diagrams/generated/DGM-VOPC-10-dedup.svg) | [`generated/DGM-VOPC-10-dedup.png`](diagrams/generated/DGM-VOPC-10-dedup.png) |

## 3. 阅读顺序

先读 01 确认“谁能处置、什么能自动、什么必须待核验、什么不能被改写”；再读 02 核对每个事件的证据等级；03 给出 9 个参与类与拒绝候选（含六方对象区分）；04 只描述交互，05 只从 04 提取 VOPC；06 定义职责/属性/关系/机制；07 做全局比较并提出 R5 建议；08 列出实际执行结果与未决项。

## 4. 本轮的六个核心结论

1. **自动关联只有一条路径**：同类型 ＋ 稳定标识按作用域归一后精确且唯一命中 ＋ 证据相容（无矛盾证据、无争议标记）才可自动关联既有 `WorkEntity`；命中多个、类型不一致或标识争议一律待核验，不任选其一（FR09；`AC-IMPL-FR09-01`；BR-IMP-02；DD-02～DD-04；M24–M33）。
2. **无稳定标识只出候选**：缺稳定标识时以同类型＋标题比对键＋年份窗召回，逐项列出标题/年份/参与者/类型证据；标题相似本身不构成合并依据；“四项证据自动合并”是历史提案，现行未批准、不可执行（`AC-IMPL-FR09-02`；DD-05～DD-07；Q-SPEC-01；M34–M41）。
3. **原值与精度不可改写**：`SourceRecord` 原值永不因清洗被改写，规范值与原值逐字段并存；时间携带事件语义与精度，仅年份不得补 1 月 1 日，采集时间不得冒充发表/申请/立项/获奖时间；缺字段标“未知”，区分空白/缺失/确认为无，不造值（INV-01；CL-02/06/07/08/11；`AC-IMPL-FR09-04`；M9/M11/M12/M13）。
4. **幂等由两个计数保证**：`distinct(生效 WorkEntity canonical_id)` 与 `distinct(source_id, source_record_key)` 有效出处数在重复批次、重试与增量重叠下都不增加，只有到达事件可增加；“批次处理完成”不等于“治理全部通过”（DD-01/DD-11；`AC-IMPL-FR09-03/06`；BR-IMP-02/06；M19–M23、M49）。
5. **版本归并须明示证据**：只有来源明示版本指向时才把预印本/正式版归入同一实体的版本清单，实体数不增；无明示证据保持独立并标疑似版本（DD-08；`AC-IMPL-FR09-05`；FR16；M46–M48）。
6. **人工合并/拆分与字段确认必须留痕且不改写历史**：合并保留全部唯一出处与版本、旧标识可溯源重定向、禁止循环与部分成功；拆分写新记录、重建来源映射、不抹除原合并链；已人工确认值此后不得被来源更新静默覆盖（BR-IMP-04；DD-09/DD-10；FR09-S09/S12；M71–M94）。

## 5. 本轮明确不做

- 不修改 SRS、SPEC、69 AC、S01 冻结材料、全局 UML 与其他 UC 目录；
- 不修改 `docs/analysis/stage-02/03-AC-UC-分析模型追踪.md` 等共享追踪文件，也不改 `automation/analysis-class-registry.md`、`automation/unresolved-decisions.md`（登记建议见 07 §6）；
- 不把 `DGM-CLASS-01-analysis.puml` 当作本 UC VOPC；不把 `DGM-ACTIVITY-06/08`、`DGM-STATE-04` 当作本 UC 的实现证据（只作比对材料）；
- 不分析来源接入/采集任务/重试（UC-18）、增量识别与恢复（UC-19）、学者身份消歧与归属改派（UC-10）、学者纠错申请（UC-08）、公开展示（UC-01/02/03/11）、后台待办与审计查询（UC-17）；
- 不引入 Java/数据库/API/框架/检索索引/发布流水线设计；
- 不把分析假设写成已批准规则：类型细化必填项、字段优先级、相似度阈值、候选年份窗、候选截断长度、复核比例、处理项状态机与 SLA 均标 `待确认`（B-01～B-10；D-07）。
