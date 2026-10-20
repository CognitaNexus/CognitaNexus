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

## S02 需求分析新增图（2026-10-20）

| 用途 | 图源 | 生成物 | 追踪 |
| --- | --- | --- | --- |
| 个人主页/Scholar边界用例 | [DGM-USECASE-07-profile](profile/DGM-USECASE-07-profile.puml) | [SVG](profile/generated/DGM-USECASE-07-profile.svg) | UC-03/04/05/06/07/22 |
| 注册并建主页活动 | [DGM-ACTIVITY-05-register-profile](profile/DGM-ACTIVITY-05-register-profile.puml) | [SVG](profile/generated/DGM-ACTIVITY-05-register-profile.svg) | UC-04；FR03/FR04 |
| 编辑本人普通主页活动 | [DGM-ACTIVITY-06-edit-profile](profile/DGM-ACTIVITY-06-edit-profile.puml) | [SVG](profile/generated/DGM-ACTIVITY-06-edit-profile.svg) | UC-22；FR06 |
| 检索活动 | [DGM-ACTIVITY-07-search](analysis/DGM-ACTIVITY-07-search.puml) | [SVG](analysis/generated/DGM-ACTIVITY-07-search.svg) | UC-01/02；FR01/FR02 |
| 四类合作网/论文路径活动 | [DGM-ACTIVITY-08-collab-network](analysis/DGM-ACTIVITY-08-collab-network.puml) | [SVG](analysis/generated/DGM-ACTIVITY-08-collab-network.svg) | UC-14/15；FR12/IN01 |
| BCE分析类 | [DGM-CLASS-01-analysis](analysis/DGM-CLASS-01-analysis.puml) | [SVG](analysis/generated/DGM-CLASS-01-analysis.svg) | 69 AC / S02 |
| 采集入库序列 | [DGM-SEQ-01-collection](analysis/DGM-SEQ-01-collection.puml) | [SVG](analysis/generated/DGM-SEQ-01-collection.svg) | UC-18/19 |
| 认领审核序列 | [DGM-SEQ-02-claim](analysis/DGM-SEQ-02-claim.puml) | [SVG](analysis/generated/DGM-SEQ-02-claim.svg) | UC-05/06 |
| 检索序列 | [DGM-SEQ-03-search](analysis/DGM-SEQ-03-search.puml) | [SVG](analysis/generated/DGM-SEQ-03-search.svg) | UC-01/02 |
| 注册/主页序列 | [DGM-SEQ-04-profile](analysis/DGM-SEQ-04-profile.puml) | [SVG](analysis/generated/DGM-SEQ-04-profile.svg) | UC-04/22 |

所有新增图源均已实际渲染SVG/PNG；`.puml`是唯一维护源。分析类图不含实现类/数据库表；CodeArts原生工程未操作，状态NOT VERIFIED。
