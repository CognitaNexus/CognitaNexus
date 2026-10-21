# UC-05 用例实现分析（S02-R1）

**版本：** V1.0（2026-10-21）
**输入基线：** `S01 FROZEN — 69 AC BASELINE`；SRS V2.2（69 AC）；教材第 5 章 §5.4.1–§5.4.4、§5.5.1–§5.5.4。
**范围：** 只分析 UC-05“提交和查询 Scholar 认领”。不分析 UC-06 审核决定、UC-07 已授权档案维护、UC-22 普通主页编辑、UC-08/10 成果归属；不重构全局类图。
**状态：** UC-05 分析完成；S02 总体仍为 `PARTIAL`。

## 1. 本目录回答什么

按教材用例实现的四步执行，并补齐 §5.5 的类定义：

1. §5.4.1 完善用例文档（01）；
2. §5.4.2 从事件流识别 Boundary/Control/Entity（02、03）；
3. §5.4.3 用分析序列分配职责（04）；
4. §5.4.4 只从本 UC 序列构造 VOPC（05）；
5. §5.5.1–§5.5.4 定义职责、属性、关系与分析机制（06）；
6. 与现有全局分析类比较，只登记差异（07），最后自检（08）。

## 2. 交付文件

| 文件 | 内容 |
|---|---|
| [01-UC-05用例规约](01-UC-05-use-case-spec.md) | 参与者、目标、触发、前置/后置、主流程 E1–E11、备选 A1–A8、业务规则、状态、AC 覆盖 |
| [02-事件流与需求来源](02-event-flow-trace.md) | 每个 E/A 的 SRS/SPEC/AC 来源与证据等级；不纳入本 UC 的事件 |
| [03-BCE分析类识别](03-bce-identification.md) | 候选类、发现步骤、选择/拒绝理由、User/UserProfile/Scholar/Claim/Auth 边界 |
| [04-顺序图与逐消息职责](04-sequence-messages.md) | 顺序图、M1–M36 逐消息职责与依据、关键协作说明 |
| [05-VOPC与关系依据](05-vopc.md) | 参与类集合、关系来源、多重性/不标多重性理由、排除项 |
| [06-分析类定义](06-class-definition.md) | Do/Know 职责、候选分析操作、业务属性、关系、分析机制 |
| [07-与全局模型差异](07-global-model-diff.md) | 与 `DGM-CLASS-01-analysis.puml`、合并认领序列、`DGM-STATE-02` 的一致与差异，R5 建议 |
| [08-自检与待评审](08-self-check.md) | 静态检查、实际渲染与文档检查结果、待确认 B-01–B-08 |

## 3. UML

| 模型 | 源 | SVG | PNG |
|---|---|---|---|
| UC-05 提交/查询分析序列 | [`diagrams/DGM-SEQ-10-claim.puml`](diagrams/DGM-SEQ-10-claim.puml) | [`generated/DGM-SEQ-10-claim.svg`](diagrams/generated/DGM-SEQ-10-claim.svg) | [`generated/DGM-SEQ-10-claim.png`](diagrams/generated/DGM-SEQ-10-claim.png) |
| UC-05 VOPC | [`diagrams/DGM-VOPC-06-claim.puml`](diagrams/DGM-VOPC-06-claim.puml) | [`generated/DGM-VOPC-06-claim.svg`](diagrams/generated/DGM-VOPC-06-claim.svg) | [`generated/DGM-VOPC-06-claim.png`](diagrams/generated/DGM-VOPC-06-claim.png) |

## 4. 阅读顺序

先读 01 确认业务边界（提交/查询，不含审核决定），再读 02 核对来源；03 给出 7 个参与类的识别理由与拒绝候选，04 只描述交互，05 只从 04 提取 VOPC；06 定义类，07 做全局比较，08 列未决问题。

## 5. 本轮明确不做

- 不修改正式 SRS、SPEC、69 AC 或 S01 冻结基线；
- 不修改其他 UC 目录、共享追踪文件、全局 `DGM-CLASS-01-analysis.puml`；
- 不把全局 `DGM-CLASS-01` 或合并序列 `DGM-SEQ-02-claim` 当作 UC-05 VOPC；
- 不把 UC-06 的审核决定、授权生效、争议处理、授权失败回滚写进本 UC；
- 不引入数据库、Java、API、框架、缓存或部署设计；
- 不把未冻结的证据最低集合、缺证处置策略、通知/SLA 写成已批准规则。
