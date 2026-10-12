# UML 文档管理与阶段导航

Supporting：图源为 `.puml`，同模块 `generated/` SVG 为生成副本，PNG 为可选导出。图的存在不代表已联合签认；当前行为以 [SRS V1.1 RC](../srs/CognitaNexus-Shared-SRS-V1.1-Implementation-RC.md) 为候选阅读入口。不得把旧详细图中未被采用的分支默认为当前承诺。当前四张核心图按本次任务指定用途归类；课程任务书原文未核定，不宣称阶段验收完成。

| 用途 | 图源 | 生成物 | 责任模块 |
| --- | --- | --- | --- |
| 辅助业务活动/状态图 | [DGM-ACTIVITY-02-metrics-refresh](analytics/DGM-ACTIVITY-02-metrics-refresh.puml) | [SVG](analytics/generated/DGM-ACTIVITY-02-metrics-refresh.svg) | analytics |
| 辅助业务活动/状态图 | [DGM-ACTIVITY-03-collab-path](analytics/DGM-ACTIVITY-03-collab-path.puml) | [SVG](analytics/generated/DGM-ACTIVITY-03-collab-path.svg) | analytics |
| 辅助业务活动/状态图 | [DGM-ACTIVITY-04-institution-ranking](analytics/DGM-ACTIVITY-04-institution-ranking.puml) | [SVG](analytics/generated/DGM-ACTIVITY-04-institution-ranking.svg) | analytics |
| 辅助业务活动/状态图 | [DGM-STATE-03-metrics-visibility](analytics/DGM-STATE-03-metrics-visibility.puml) | [SVG](analytics/generated/DGM-STATE-03-metrics-visibility.svg) | analytics |
| 分模块补充用例图 | [DGM-USECASE-02-analytics](analytics/DGM-USECASE-02-analytics.puml) | [SVG](analytics/generated/DGM-USECASE-02-analytics.svg) | analytics |
| 需求建模核心必需图（课程原文仍待核定） | [DGM-ACTIVITY-01-claim-review](claim/DGM-ACTIVITY-01-claim-review.puml) | [SVG](claim/generated/DGM-ACTIVITY-01-claim-review.svg) | claim |
| 需求建模核心必需图（课程原文仍待核定） | [DGM-STATE-02-claim-request](claim/DGM-STATE-02-claim-request.puml) | [SVG](claim/generated/DGM-STATE-02-claim-request.svg) | claim |
| 分模块补充用例图 | [collection-local-usecase](collection/collection-local-usecase.puml) | [SVG](collection/generated/collection-local-usecase.svg) | collection |
| 需求建模核心必需图（课程原文仍待核定） | [collection-task-state](collection/collection-task-state.puml) | [SVG](collection/generated/collection-task-state.svg) | collection |
| 辅助业务活动/状态图 | [collection-update-activity](collection/collection-update-activity.puml) | [SVG](collection/generated/collection-update-activity.svg) | collection |
| 辅助业务活动/状态图 | [dedup-candidate-activity](governance/dedup-candidate-activity.puml) | [SVG](governance/generated/dedup-candidate-activity.svg) | governance |
| 辅助业务活动/状态图 | [governance-exception-state](governance/governance-exception-state.puml) | [SVG](governance/generated/governance-exception-state.svg) | governance |
| 辅助业务活动/状态图 | [governance-overview-activity](governance/governance-overview-activity.puml) | [SVG](governance/generated/governance-overview-activity.svg) | governance |
| 辅助业务活动/状态图 | [identity-disambiguation-activity](governance/identity-disambiguation-activity.puml) | [SVG](governance/generated/identity-disambiguation-activity.svg) | governance |
| 辅助业务时序图（不等于设计时序交付） | [relation-decision-sequence](governance/relation-decision-sequence.puml) | [SVG](governance/generated/relation-decision-sequence.svg) | governance |
| 辅助业务活动/状态图 | [scholar-achievement-relation-state](governance/scholar-achievement-relation-state.puml) | [SVG](governance/generated/scholar-achievement-relation-state.svg) | governance |
| 需求建模核心必需图（课程原文仍待核定） | [overall-usecase](usecase/overall-usecase.puml) | [SVG](usecase/generated/overall-usecase.svg) | usecase |

后续需求分析的业务类图/关键时序、架构图、构件接口及数据库/UI 设计图须在对应阶段授权后建立；不预建占位，不将现有业务时序图算作完整架构或构件交付。本次不改动任何图源或生成物。静态配对/XML 与语法检查不证明业务守卫、流程和全部 SRS 引用已获两组确认。历史图与当前边界的争议交负责人联合评审。
