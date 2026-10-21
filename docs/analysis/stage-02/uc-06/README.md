# UC-06 用例实现分析（S02-R1）

**版本：** V1.0（2026-10-21）
**输入基线：** `S01 FROZEN — 69 AC BASELINE`；SRS V2.2（69 AC）FR05/FR04/FR14/FR15/NF03/NF06；核心 SPEC §3、§6（ID-07/ID-12）；教材第 5 章 §5.4.1–§5.4.4、§5.5.1–§5.5.4。
**范围：** 只分析 UC-06“审核 Scholar 认领”。不分析 UC-05 提交/查询（直接前驱，只引用其产物）、UC-07 已授权档案维护、UC-08/UC-10 成果归属纠错、UC-17 后台待办与审计查询、UC-22 普通主页编辑；不重构全局类图。
**状态：** UC-06 分析完成；S02 总体仍为 `PARTIAL`。

## 1. 本目录回答什么

按教材用例实现四步执行，并补齐 §5.5 的类定义：

1. §5.4.1 完善用例文档（01）；
2. §5.4.2 从事件流识别 Boundary/Control/Entity（02、03）；
3. §5.4.3 用分析序列分配职责（04）；
4. §5.4.4 只从本 UC 序列构造 VOPC（05）；
5. §5.5.1–§5.5.4 定义职责、属性、关系与分析机制（06）；
6. 与现有全局分析类比较，只登记差异（07），最后自检（08）。

本 UC 只回答“有权审核的管理员如何处理待审核/待补充认领申请，并把决定、授权与审计落到可追溯的记录上”。

## 2. 交付文件

| 文件 | 内容 |
|---|---|
| [01-UC-06用例规约](01-UC-06-use-case-spec.md) | 参与者、目标、触发、前置/后置、主流程 E1–E12、备选 A1–A9、业务规则、状态、AC 覆盖 |
| [02-事件流与需求来源](02-event-flow-trace.md) | 每个 E/A 的 SRS/SPEC/AC 来源与证据等级；不纳入本 UC 的事件 |
| [03-BCE分析类识别](03-bce-identification.md) | 候选类、发现步骤、选择/拒绝理由、User/UserProfile/Scholar/Claim/Auth/Evidence 边界 |
| [04-顺序图与逐消息职责](04-sequence-messages.md) | 顺序图、M1–M62 逐消息职责与依据、关键协作说明 |
| [05-VOPC与关系依据](05-vopc.md) | 参与类集合、关系来源、多重性/不标多重性理由、排除项 |
| [06-分析类定义](06-class-definition.md) | Do/Know 职责、候选分析操作、业务属性、关系、分析机制 |
| [07-与全局模型差异](07-global-model-diff.md) | 与 `DGM-CLASS-01-analysis.puml`、合并认领序列、`DGM-ACTIVITY-01`、`DGM-STATE-02` 的一致与差异，R5 建议 |
| [08-自检与待评审](08-self-check.md) | 静态检查、实际渲染与文档检查结果、待确认 C-01–C-12 |

## 3. UML

| 模型 | 源 | SVG | PNG |
|---|---|---|---|
| UC-06 审核决定分析序列 | [`diagrams/DGM-SEQ-11-claim-review.puml`](diagrams/DGM-SEQ-11-claim-review.puml) | [`generated/DGM-SEQ-11-claim-review.svg`](diagrams/generated/DGM-SEQ-11-claim-review.svg) | [`generated/DGM-SEQ-11-claim-review.png`](diagrams/generated/DGM-SEQ-11-claim-review.png) |
| UC-06 VOPC | [`diagrams/DGM-VOPC-07-claim-review.puml`](diagrams/DGM-VOPC-07-claim-review.puml) | [`generated/DGM-VOPC-07-claim-review.svg`](diagrams/generated/DGM-VOPC-07-claim-review.svg) | [`generated/DGM-VOPC-07-claim-review.png`](diagrams/generated/DGM-VOPC-07-claim-review.png) |

## 4. 阅读顺序

先读 01 确认边界（审核决定，不含提交/查询、不含档案维护），再读 02 核对来源；03 给出 8 个参与类的识别理由与拒绝候选，04 只描述交互，05 只从 04 提取 VOPC；06 定义类，07 做全局比较，08 列未决问题。

## 5. 本轮明确不做

- 不修改正式 SRS、SPEC、69 AC、S01 冻结基线、SRS §6.3 状态口径；
- 不修改其他 UC 目录、共享追踪文件、`automation/analysis-class-registry.md`、`unresolved-decisions.md`、全局 `DGM-CLASS-01-analysis.puml`；
- 不把全局 `DGM-CLASS-01` 或合并序列 `DGM-SEQ-02-claim` 当作 UC-06 VOPC；
- 不重画 UC-05 的提交与本人查询（E9–E11），不重复其证据齐备性校验；
- 不写 UC-07 的学者档案字段维护、UC-08/UC-10 的 Authorship 确认与纠错；
- 不引入数据库、Java、API、框架、缓存、队列实现或部署设计；
- 不自造审核员等级/审批链、审核 SLA、申诉程序、通知渠道（SRS 未定义，登记为待确认）；
- 不把“受控待办”实现为独立分析类（其载体属 FR14/UC-17，登记为待确认）。
