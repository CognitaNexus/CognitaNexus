# S02 需求分析（用例分析）交付

**版本：** V1.2（2026-10-21）
**输入基线：** [`S01 FROZEN — 69 AC BASELINE`](../stage-01/09-S01冻结记录.md)
**状态：** `S02 PARTIAL — UC-04 R1, UC-01 R2 COMPLETE; REMAINING UCs PENDING`（产品运行测试 `NOT RUN`；CodeArts `NOT VERIFIED`）

## 1. 课程阶段与范围

S02采用教材第5章 §5.4–§5.5 的用例分析与分析类方法，并结合项目《SRS 编写与需求建模规范》对需求分析阶段的要求：核心业务类图、关键时序图、活动/状态模型。教师独立任务书未提供，详见 [`../课程要求与交付追踪矩阵.md`](../课程要求与交付追踪矩阵.md)。

本期分析范围：UC-01～19、UC-22，共20个；UC-20/21为Optional延期，不建立任何Must依赖。

## 2. 交付清单

| 文件 | 内容 | 状态 |
|---|---|---|
| [01-BCE分析类模型](01-BCE分析类模型.md) | Boundary/Control/Entity职责、关系、边界 | 历史交付；全局BCE，不是逐UC VOPC |
| [02-用例模型与规约](02-用例模型与规约.md) | 22个UC清单/关系；13个关键UC详细规约；其余摘要 | 历史交付；字段不全，见2.1 |
| [03-AC-UC-分析模型追踪](03-AC-UC-分析模型追踪.md) | 69条AC逐条→UC→模型→场景；反向UC→AC | 历史交付；UC-04追踪已补，其余待R5 |
| [04-活动图与状态图](04-活动图与状态图.md) | 4张新增活动图+2张复用；3个真实生命周期状态图 | 历史交付；覆盖不完整 |
| [05-分析序列与用例实现](05-分析序列与用例实现.md) | 4张分析序列、5组Use-Case Realization职责 | 历史交付；非逐UC分析 |
| [06-自检与评审](06-自检与评审.md) | 课程、AC、UML、文档、Git检查 | 历史自检；已按专项审查更正为PARTIAL |
| [UC-04 用例实现分析](uc-04/README.md) | 规约、事件流来源、BCE、序列、VOPC、类定义、全局差异、自检 | UC-04 R1 已完成 |
| [UC-01 用例实现分析](uc-01/README.md) | 规约、事件流来源、BCE、序列、VOPC、类定义、全局差异、自检 | UC-01 R2 已完成 |

## 2.1 状态修订与已知缺口（2026-10-21）

2026-10-20的`S02 DELIVERED`保留为历史交付声明，不再作为当前状态。专项审查确认以下缺口：

- 教材§5.4.4的逐用例VOPC此前为0个；UC-04 R1、UC-01 R2各补1个，其余18个本期UC尚未补；
- 13个详细UC的规约字段不完整，7个摘要UC没有行为分析；
- §5.5.2属性、§5.5.3关系/多重性和§5.5.5统一分析类综合过程不完整；
- 全局`DGM-CLASS-01`是BCE总览，不能替代任何UC的VOPC；
- 教师评分/人工验收仍未提供。

本目录原有文档保留，不删除历史交付事实；当前状态以本修订和[06](06-自检与评审.md)为准。UC-04 R1、UC-01 R2是当前已修复的用例；其他UC不得据此声称已完成。

## 3. UML 源与导出

全局/既有图源位于 `docs/uml/`；UC-04/UC-01专项图源位于本目录对应子目录，导出均在各自 `generated/`：

| 模型 | PUML | SVG/PNG |
|---|---|---|
| 总体用例 | `usecase/overall-usecase.puml` | `usecase/generated/` |
| 个人主页/Scholar边界用例 | `profile/DGM-USECASE-07-profile.puml` | `profile/generated/` |
| 注册建主页活动 | `profile/DGM-ACTIVITY-05-register-profile.puml` | `profile/generated/` |
| 本人编辑主页活动 | `profile/DGM-ACTIVITY-06-edit-profile.puml` | `profile/generated/` |
| 检索活动 | `analysis/DGM-ACTIVITY-07-search.puml` | `analysis/generated/` |
| 合作网络/路径活动 | `analysis/DGM-ACTIVITY-08-collab-network.puml` | `analysis/generated/` |
| BCE分析类 | `analysis/DGM-CLASS-01-analysis.puml` | `analysis/generated/` |
| 采集入库序列 | `analysis/DGM-SEQ-01-collection.puml` | `analysis/generated/` |
| 认领审核序列 | `analysis/DGM-SEQ-02-claim.puml` | `analysis/generated/` |
| 检索序列 | `analysis/DGM-SEQ-03-search.puml` | `analysis/generated/` |
| 注册/主页序列 | `analysis/DGM-SEQ-04-profile.puml` | `analysis/generated/` |
| UC-04 专项注册序列 | `stage-02/uc-04/diagrams/DGM-SEQ-05-register.puml` | `stage-02/uc-04/diagrams/generated/` |
| UC-04 VOPC | `stage-02/uc-04/diagrams/DGM-VOPC-01-register.puml` | `stage-02/uc-04/diagrams/generated/` |
| UC-01 专项检索序列 | `stage-02/uc-01/diagrams/DGM-SEQ-06-search.puml` | `stage-02/uc-01/diagrams/generated/` |
| UC-01 VOPC | `stage-02/uc-01/diagrams/DGM-VOPC-02-search.puml` | `stage-02/uc-01/diagrams/generated/` |
| UC-02 专项详情序列 | `stage-02/uc-02/diagrams/DGM-SEQ-07-detail.puml` | `stage-02/uc-02/diagrams/generated/` |
| UC-02 VOPC | `stage-02/uc-02/diagrams/DGM-VOPC-03-detail.puml` | `stage-02/uc-02/diagrams/generated/` |
| UC-03 专项主页序列 | `stage-02/uc-03/diagrams/DGM-SEQ-08-profile.puml` | `stage-02/uc-03/diagrams/generated/` |
| UC-03 VOPC | `stage-02/uc-03/diagrams/DGM-VOPC-04-profile.puml` | `stage-02/uc-03/diagrams/generated/` |
| UC-22 专项编辑序列 | `stage-02/uc-22/diagrams/DGM-SEQ-09-profile-edit.puml` | `stage-02/uc-22/diagrams/generated/` |
| UC-22 VOPC | `stage-02/uc-22/diagrams/DGM-VOPC-05-profile-edit.puml` | `stage-02/uc-22/diagrams/generated/` |
| UC-05 专项认领序列 | `stage-02/uc-05/diagrams/DGM-SEQ-10-claim.puml` | `stage-02/uc-05/diagrams/generated/` |
| UC-05 VOPC | `stage-02/uc-05/diagrams/DGM-VOPC-06-claim.puml` | `stage-02/uc-05/diagrams/generated/` |
| UC-06 专项认领审核序列 | `stage-02/uc-06/diagrams/DGM-SEQ-11-claim-review.puml` | `stage-02/uc-06/diagrams/generated/` |
| UC-06 VOPC | `stage-02/uc-06/diagrams/DGM-VOPC-07-claim-review.puml` | `stage-02/uc-06/diagrams/generated/` |
| UC-07 专项学者档案维护序列 | `stage-02/uc-07/diagrams/DGM-SEQ-12-scholar-maintenance.puml` | `stage-02/uc-07/diagrams/generated/` |
| UC-07 VOPC | `stage-02/uc-07/diagrams/DGM-VOPC-08-scholar-maintenance.puml` | `stage-02/uc-07/diagrams/generated/` |
| UC-08 专项成果确认/纠错序列 | `stage-02/uc-08/diagrams/DGM-SEQ-13-authorship-correction.puml` | `stage-02/uc-08/diagrams/generated/` |
| UC-08 VOPC | `stage-02/uc-08/diagrams/DGM-VOPC-09-authorship-correction.puml` | `stage-02/uc-08/diagrams/generated/` |
| UC-09 专项去重治理序列 | `stage-02/uc-09/diagrams/DGM-SEQ-14-dedup.puml` | `stage-02/uc-09/diagrams/generated/` |
| UC-09 VOPC | `stage-02/uc-09/diagrams/DGM-VOPC-10-dedup.puml` | `stage-02/uc-09/diagrams/generated/` |
| UC-10 专项身份核验序列 | `stage-02/uc-10/diagrams/DGM-SEQ-15-identity.puml` | `stage-02/uc-10/diagrams/generated/` |
| UC-10 VOPC | `stage-02/uc-10/diagrams/DGM-VOPC-11-identity.puml` | `stage-02/uc-10/diagrams/generated/` |
| UC-17 专项后台待办/审计序列 | `stage-02/uc-17/diagrams/DGM-SEQ-16-admin-audit.puml` | `stage-02/uc-17/diagrams/generated/` |
| UC-17 VOPC | `stage-02/uc-17/diagrams/DGM-VOPC-12-admin-audit.puml` | `stage-02/uc-17/diagrams/generated/` |
| UC-18 专项采集任务序列 | `stage-02/uc-18/diagrams/DGM-SEQ-17-collection.puml` | `stage-02/uc-18/diagrams/generated/` |
| UC-18 VOPC | `stage-02/uc-18/diagrams/DGM-VOPC-13-collection.puml` | `stage-02/uc-18/diagrams/generated/` |
| UC-19 专项来源更新序列 | `stage-02/uc-19/diagrams/DGM-SEQ-18-source-update.puml` | `stage-02/uc-19/diagrams/generated/` |
| UC-19 VOPC | `stage-02/uc-19/diagrams/DGM-VOPC-14-source-update.puml` | `stage-02/uc-19/diagrams/generated/` |
| UC-11 专项统计序列 | `stage-02/uc-11/diagrams/DGM-SEQ-19-statistics.puml` | `stage-02/uc-11/diagrams/generated/` |
| UC-11 VOPC | `stage-02/uc-11/diagrams/DGM-VOPC-15-statistics.puml` | `stage-02/uc-11/diagrams/generated/` |
| UC-12 专项机构排名序列 | `stage-02/uc-12/diagrams/DGM-SEQ-20-institution-rank.puml` | `stage-02/uc-12/diagrams/generated/` |
| UC-12 VOPC | `stage-02/uc-12/diagrams/DGM-VOPC-16-institution-rank.puml` | `stage-02/uc-12/diagrams/generated/` |
| UC-13 专项热点序列 | `stage-02/uc-13/diagrams/DGM-SEQ-21-hotspots.puml` | `stage-02/uc-13/diagrams/generated/` |
| UC-13 VOPC | `stage-02/uc-13/diagrams/DGM-VOPC-17-hotspots.puml` | `stage-02/uc-13/diagrams/generated/` |
| UC-14 专项合作网络序列 | `stage-02/uc-14/diagrams/DGM-SEQ-22-collaboration-network.puml` | `stage-02/uc-14/diagrams/generated/` |
| UC-14 VOPC | `stage-02/uc-14/diagrams/DGM-VOPC-18-collaboration-network.puml` | `stage-02/uc-14/diagrams/generated/` |
| UC-15 专项最短路径序列 | `stage-02/uc-15/diagrams/DGM-SEQ-23-shortest-path.puml` | `stage-02/uc-15/diagrams/generated/` |
| UC-15 VOPC | `stage-02/uc-15/diagrams/DGM-VOPC-19-shortest-path.puml` | `stage-02/uc-15/diagrams/generated/` |
| UC-16 专项四类/规模序列 | `stage-02/uc-16/diagrams/DGM-SEQ-24-four-types-scale.puml` | `stage-02/uc-16/diagrams/generated/` |
| UC-16 VOPC | `stage-02/uc-16/diagrams/DGM-VOPC-20-four-types-scale.puml` | `stage-02/uc-16/diagrams/generated/` |

复用并核对：认领活动图、认领申请状态图、采集任务状态图、Authorship关系状态图。

## 4. 关键业务边界

- **注册即主页**：注册成功创建 User + 唯一 UserProfile；零成果/非学者同样有公开主页；UC-22编辑普通资料无需Scholar认领。
- **UserProfile与Scholar分离**：同名不自动合并；认领只授予目标Scholar对象权，不建第二主页，不批量确认Authorship。
- **FR12/IN01**：基础网络四类合格共同成果；IN01只在合格共同论文子图求最短路径；共同专利/项目/获奖不能成为路径边。
- **规模**：功能联调≥100万、最终容量≥500万、固定环境100并发；均为目标，未实测。

## 5. 验证状态

- 69 AC数量/唯一性/正文一致性：自动脚本检查。
- UC-01～19、UC-22：规约与追踪存在；UC-20/21仅延期。
- S02完整度：**PARTIAL**；UC-04 R1、UC-01 R2已完成教材四步+VOPC，其余待后续逐项审查。
- PUML：实际渲染SVG/PNG；XML可解析；目视检查。
- 文档链接/编号：静态检查。
- 软件测试/算法执行/容量/并发/恢复/覆盖率：**NOT RUN**。
- 教师评分/人工课堂验收：**未提供，不能虚构PASS**。

## 6. 后续

S02内完成的是分析模型，不是架构/数据库/API实现设计。后续阶段若改变业务语义、UC或69 AC，必须回到需求变更流程；不得直接改图替代需求批准。

UC-04 R1证据从[uc-04/README](uc-04/README.md)进入，UC-01 R2从[uc-01/README](uc-01/README.md)进入；未获授权前不批量推进其他UC。
