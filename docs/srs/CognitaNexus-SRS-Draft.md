# CognitaNexus 六人组 SRS 联合整合草案（Draft）

**版次：** Integration Draft 2026-10-11 ｜ **状态：** 待六人联合评审、后续十一人协调，不是最终《共同 SRS V1.0》  
**规范依据：** [V1.9 需求基线](../requirements/学术成果分享平台需求分析与可行性报告.md) · [SRS 编写规范](CognitaNexus_SRS编写与需求建模规范_V1.0.md)  
**模块导航：** [六人分册及格式约定](modules/README.md) · [差异审查与待决表](../reports/srs/joint-review-draft-2026-10-11.md)

本草案采用**单一入口 + 需求分册作为正式内容来源**的整合结构，避免把成员06未经共同审核的整套生成稿当成统一正文；不得据此认为本文自动解决了所有跨模块待决项。成员03 FR09/FR15、成员06 FR14/NF 已进入联合整合候选。

## 1. 引言
本阶段目标是评审六人组覆盖、术语、业务状态、验收及公共规则的一致性。FR01～FR16、IN01、O01、O02、NF01～NF08 以各负责成员草稿为细化输入，课程硬性要求与 V1.9 仍是上位基线。

## 2. 系统总体说明
以学者为中心聚合四类成果元数据，提供检索、学者门户、认领纠错、采集数据治理、统计与合作路径。明确不包括一期全文托管/下载代理、投稿审稿、付费交易或社交私信。

## 3. 用例模型
- [组级总体用例图](../uml/usecase/overall-usecase.puml) 由组长维护；不能直接采用成员06全系统替代图。
- [成员02 局部用例图](../uml/collection/collection-local-usecase.puml)
- [成员03 UC09/UC10 原稿](review/member03-governance-usecases.md)
- [成员04 门户/认领用例](modules/scholar-claim.md)
- [成员05 统计/网络用例](modules/analytics-graph.md)

**待统一：** `UC-08` 在成员02与04中同号异义；成员03 `UC-09/UC-10` 与成员02/05引用之间未完成全局排他校验。见联合评审 P0-01。

## 4. 功能需求规格

| 主章节 | 正式内容来源（均 Draft） | 约束 |
| --- | --- | --- |
| FR01～FR02 | [成员01：检索与成果详情](modules/search-recommendation.md) | 来源跳转不等于托管下载 |
| FR03～FR07 | [成员04：学者门户、认领、管理授权](modules/scholar-claim.md) | 认领 ≠ 本人确认成果 |
| FR08、FR10、FR16 | [成员02：采集、增量更新、来源追踪](modules/collection-update.md) | 成功/待核验口径待一致化 |
| FR09 | [成员03：清洗/去重](modules/governance-dedup.md) | 不仅靠标题相似直接归并 |
| FR11～FR13、IN01 | [成员05：统计与合作网络](modules/analytics-graph.md) | IN01 路径逐跳共同论文证据 |
| FR14 | [成员06：管理后台与审计](modules/admin-audit.md) | 不重写其他模块的审核流程 |
| FR15 | [成员03：学者身份与成果归属](modules/scholar-disambiguation.md) | 不凭姓名合并，不以审核冒充本人确认 |

## 5. 创新与可选需求
[IN01](modules/analytics-graph.md) 必须具有领域/机构筛选、两名学者最短合作路径、逐跳论文依据与当前范围无路径反馈。[O01、O02](modules/search-recommendation.md) 保持 Optional，且 O02 需明确授权，不能变成一期 Must 的前提条件。

## 6. 业务流程与状态模型
- [采集六状态](../uml/collection/collection-task-state.puml)；[认领活动](../uml/claim/DGM-ACTIVITY-01-claim-review.puml)；[认领申请状态](../uml/claim/DGM-STATE-02-claim-request.puml)。
- [成员03 数据治理/消歧图](../uml/governance/)：5 张本阶段参考模型 + 1 张后续需求分析时序参考，重编号前不得覆盖其他部门图。
- [成员05 数据分析图](../uml/analytics/)：与上述治理图暂按模块路径区分，不宣布全局图号已唯一。

## 7. 数据需求与业务规则
去重成果实体不等于来源记录；一项成果多作者参与关系独立；来源多版本和不同原始出处必须保留；自动更新不可静默覆盖已审核/本人确认结果；模拟数据不能伪装成真实证据。

[成员03 公共术语与候选规则](review/member03-governance-rules.md)只作为合并评审输入，`BR-xx` 的全组编号及状态语义尚待会议冻结。

## 8. 权限与安全需求
公开浏览和管理员/门户管理权分离；管理员审核授权不代表学术认定；跨门户操作和受控原始证据访问必须进行对象级授权检查。正式明细见[成员04](modules/scholar-claim.md)和[成员06](modules/admin-audit.md)。

## 9. 非功能需求
[成员06 NF01～NF08 独立分册](modules/nonfunctional.md)承接 V1.9 约束。NF02 P95≤2s、NF05 更新生效后 60s 一致性均为**待评审**；不可报告为已测得或已验收。

## 10. 验收与追踪
已关联各成员的子需求及验收草稿；总体追踪和UC/BR/DGM跨模块编号统一仍待评审。[成员02追踪](modules/collection-update-traceability.md)；[成员03追踪/UC](review/member03-governance-usecases.md)；[联合审查表](../reports/srs/joint-review-draft-2026-10-11.md)。

**冻结门槛：** 六成员逐项确认 → 关键业务分歧裁决 → 同步图/AC/SPEC/追踪 → 编号链接和 PlantUML 检查 → 11 人联合评审 → 形成共同 SRS V1.0。没有签字与实测证据时，不标为通过。