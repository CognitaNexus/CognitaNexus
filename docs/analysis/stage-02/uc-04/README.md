# UC-04 用例实现分析（S02-R1）

**版本：** V1.0（2026-10-21）
**输入基线：** `S01 FROZEN — 69 AC BASELINE`；SRS V2.2（69 AC）；A1c；教材第5章§5.4–§5.5。
**范围：** 只分析 UC-04“账号注册并自动生成公开个人主页”。不分析其他19个本期UC，不重构全局类图。
**状态：** UC-04 分析完成；S02 总体仍为 `PARTIAL`，其余UC待R2以后逐项处理。

## 1. 本目录回答什么

本包按教材用例实现的四步执行：

1. §5.4.1 完善用例文档；
2. §5.4.2 从事件流识别 Boundary/Control/Entity；
3. §5.4.3 用分析序列分配职责；
4. §5.4.4 从序列图构造本用例的 VOPC；
5. §5.5.1–§5.5.4 定义职责、属性、关系和分析机制；
6. 与现有全局分析类比较，只登记差异，不直接重构全局图。

## 2. 交付文件

| 文件 | 内容 |
|---|---|
| [01-UC-04用例规约](01-UC-04-use-case-spec.md) | 参与者、目标、前置/后置、主流程、备选、业务规则、状态、AC |
| [02-事件流与需求来源](02-event-flow-trace.md) | 每个事件步骤的来源、证据性质与待确认项 |
| [03-BCE分析类识别](03-bce-identification.md) | 候选类、发现步骤、选择/拒绝理由、User/UserProfile/Scholar边界 |
| [04-顺序图与逐消息职责](04-sequence-messages.md) | 顺序图、逐消息发送者/接收者/职责/依据/假设 |
| [05-VOPC与关系依据](05-vopc.md) | 参与类集合、关系来源、多重性与排除项 |
| [06-分析类定义](06-class-definition.md) | Do/Know职责、业务属性、关系、分析机制 |
| [07-与全局模型差异](07-global-model-diff.md) | 与 `DGM-CLASS-01-analysis.puml` 的一致、冲突、更名/拆分建议 |
| [08-自检与待评审](08-self-check.md) | 静态检查、渲染检查、未执行项、人工决议问题 |

## 3. UML

| 模型 | 源 | SVG | PNG |
|---|---|---|---|
| UC-04 注册分析序列 | `diagrams/DGM-SEQ-05-register.puml` | `diagrams/generated/DGM-SEQ-05-register.svg` | `diagrams/generated/DGM-SEQ-05-register.png` |
| UC-04 VOPC | `diagrams/DGM-VOPC-01-register.puml` | `diagrams/generated/DGM-VOPC-01-register.svg` | `diagrams/generated/DGM-VOPC-01-register.png` |

## 4. 阅读顺序

先读01确认业务边界，再读02核对来源；03给出类候选，04给出交互与消息，05只从04提取VOPC；06定义类，07做全局比较，08列未决问题。

## 5. 本轮明确不做

- 不修改正式SRS、SPEC、69 AC或S01冻结基线；
- 不修改其他UC的规约或VOPC；
- 不把全局 `DGM-CLASS-01` 当作UC-04 VOPC；
- 不引入Java/数据库/API/框架/部署设计；
- 不把分析假设写成已批准业务规则。
