# CognitaNexus 六人组 SRS 模块导航与统一编写口径（Draft）

**基线：** [V1.9](../../requirements/学术成果分享平台需求分析与可行性报告.md) ｜ **模板：** [SRS 规范 V1.0](../CognitaNexus_SRS编写与需求建模规范_V1.0.md) ｜ **状态：** 仅为需求建模阶段的六人组联合评审候选，不代表正式冻结。

> **Supporting / 详细来源与追踪。** 当前唯一工程实施候选为 [共同 SRS V1.1](../CognitaNexus-Shared-SRS-V1.1-Implementation-RC.md)：Review Candidate / 未经两组正式签认 / 非 Frozen。模块保留原文、原编号与待决规则；与当前工程口径不同之处交联合评审，不反向覆盖 V1.1。核心规则同时阅读 [SPEC V1.0](../../spec/CognitaNexus-Core-SPEC-V1.0.md) 与 [V1.1 对齐说明](../../spec/implementation-alignment-V1.1-RC.md)。全局入口见 [文档导航](../../README.md)。

## 六人分工、模块正式输入

| 负责人 | 需求 | 主要源文件 | 合并策略 |
| --- | --- | --- | --- |
| 01 | FR01、FR02、O01、O02 | [搜索与推荐](search-recommendation.md) | 保留此前审核稿；06 对照稿不能替换 |
| 02 | FR08、FR10、FR16 | [采集与更新](collection-update.md) / [追踪](collection-update-traceability.md) | 保留此前审核稿及成员02更新的原生 UML |
| 03 | FR09、FR15 | [清洗去重](governance-dedup.md) / [消歧归属](scholar-disambiguation.md) | 采纳成员03原稿；图源码 [governance](../../uml/governance/)，编号已统一，业务待联合评审 |
| 04 | FR03～FR07 | [门户与认领](scholar-claim.md) | 保留此前审核稿；权限状态不得被其他模块改写 |
| 05 | FR11～FR13、IN01 | [统计与合作网络](analytics-graph.md) | 保留此前审核稿，创新最短路径逐跳仅支持**共同论文**证据 |
| 06 | FR14、NF01～NF08 | [后台与审计](admin-audit.md) / [非功能需求](nonfunctional.md) | 只采用其负责范围；他生成的整套 SRS 的其他 FR 仅是独立对照稿 |

## 统一文档口径（适用于所有模块）

1. **唯一基线**：V1.9 优先于成员草稿；NF02 P95、NF05 60s 仍为待评审目标。
2. **模块身份**：每份正式模块首页统一注明负责成员、FR/NF 范围、Draft、基线与评审入口。保留原提交的条目、AC 与版本历史，不因格式统一擅自改写业务含义。
3. **全局追踪**：FRxx-Syy / AC-... 保持所属成员原标识；UC、DGM 与公共 BR 统一采用[全局注册表](../global-id-registry.md)，旧编号仅在映射及历史说明中出现。
4. **图资产**：唯一图源在 `docs/uml/<模块>/`；其 `generated/` SVG 只作生成副本。成员03源码使用独立文件名以免覆盖成员05的图；后续统一图号须联动所有文本引用。
5. **权限与数据**：用户账号≠学者档案；认领管理授权≠学术归属确认；来源记录≠去重成果；学者—成果参与关系≠成果实体。管理员审核≠本人确认或学术质量背书。
6. **阶段边界**：本阶段交付需求/用例/业务活动与状态、验收和核心规则 SPEC 初稿，不得将未实测性能、来源许可证、代码覆盖率写成已通过。
7. **联合决定**：跨模块分歧登记在[联合评审报告](../../reports/srs/joint-review-draft-2026-10-11.md)，人工评审确认前不冻结共同 SRS。

## 本次整合的文件结构

- [历史六人共同 SRS V1.0](../CognitaNexus-SRS-V1.0.md)：Historical / 仅用于来源追踪，不作为当前开发基线
- [成员03 的用例原稿及编号差异](../review/member03-governance-usecases.md)
- [成员03 的公共规则候选](../review/member03-governance-rules.md)
- [核心规则 SPEC 第一稿](../../spec/CognitaNexus-Core-SPEC-V1.0.md)
- [联合评审议题/问题清单](../../reports/srs/joint-review-draft-2026-10-11.md)

**注意：** 此表格不是 6 名成员本人均已签字的评审记录；实际签认及决策结论以会后记录为准。