# UC-01 用例实现分析（S02-R2）

**版本：** V1.0（2026-10-21）
**输入基线：** `S01 FROZEN — 69 AC BASELINE`；SRS V2.2（69 AC）；教材第5章§5.4–§5.5。
**范围：** 只分析 UC-01“公开成果检索”。不分析 UC-02 详情、统计、网络或其他 UC；不重构全局类图。
**状态：** UC-01 分析完成；S02 总体仍为 `PARTIAL`。

## 1. 交付内容

| 文件 | 内容 |
|---|---|
| [01-UC-01用例规约](01-UC-01-use-case-spec.md) | 参与者、目标、触发、前后置、主流程、备选/异常、业务规则、AC |
| [02-事件流与需求来源](02-event-flow-trace.md) | E1–E9/A1–A4 的逐项来源与证据性质 |
| [03-BCE分析类识别](03-bce-identification.md) | 候选类、发现步骤、选择/拒绝理由、边界 |
| [04-顺序图与逐消息职责](04-sequence-messages.md) | 分析序列、逐消息职责与依据 |
| [05-VOPC与关系依据](05-vopc.md) | 参与类、关系、无多重性/排除项说明 |
| [06-分析类定义](06-class-definition.md) | Do/Know、候选分析操作、属性、关系、机制 |
| [07-与全局模型差异](07-global-model-diff.md) | 与 `DGM-CLASS-01-analysis.puml` 的异同和R5建议 |
| [08-自检与待评审](08-self-check.md) | 检查结果、Q清单、评审边界 |

## 2. UML

| 模型 | 源 | SVG | PNG |
|---|---|---|---|
| UC-01 检索分析序列 | `diagrams/DGM-SEQ-06-search.puml` | `generated/DGM-SEQ-06-search.svg` | `generated/DGM-SEQ-06-search.png` |
| UC-01 VOPC | `diagrams/DGM-VOPC-02-search.puml` | `generated/DGM-VOPC-02-search.svg` | `generated/DGM-VOPC-02-search.png` |

## 3. 阅读顺序

先读01确认范围，再读02核对来源；03给出4个参与类的识别理由，04只描述交互，05只从04提取VOPC；06定义类，07做全局比较，08列未决项。

## 4. 本轮不做

- 不修改SRS、SPEC、69 AC或S01冻结材料；
- 不分析UC-02详情、UC-03主页、UC-14网络等其他UC；
- 不把全局`DGM-CLASS-01`冒充UC-01 VOPC；
- 不引入数据库、Java、API、框架或部署设计；
- 不把未冻结的排序字段集合、分页大小、快照机制写成既定需求。
