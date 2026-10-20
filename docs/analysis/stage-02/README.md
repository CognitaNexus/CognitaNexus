# S02 需求分析（用例分析）交付

**版本：** V1.0（2026-10-20）  
**输入基线：** [`S01 FROZEN — 69 AC BASELINE`](../stage-01/09-S01冻结记录.md)  
**状态：** `S02 DELIVERED`（课程分析成果已完成；产品运行测试 `NOT RUN`；CodeArts `NOT VERIFIED`）

## 1. 课程阶段与范围

S02采用教材第5章 §5.4–§5.5 的用例分析与分析类方法，并结合项目《SRS 编写与需求建模规范》对需求分析阶段的要求：核心业务类图、关键时序图、活动/状态模型。教师独立任务书未提供，详见 [`../课程要求与交付追踪矩阵.md`](../课程要求与交付追踪矩阵.md)。

本期分析范围：UC-01～19、UC-22，共20个；UC-20/21为Optional延期，不建立任何Must依赖。

## 2. 交付清单

| 文件 | 内容 | 状态 |
|---|---|---|
| [01-BCE分析类模型](01-BCE分析类模型.md) | Boundary/Control/Entity职责、关系、边界 | 已完成 |
| [02-用例模型与规约](02-用例模型与规约.md) | 22个UC清单/关系；13个关键UC详细规约；其余摘要 | 已完成 |
| [03-AC-UC-分析模型追踪](03-AC-UC-分析模型追踪.md) | 69条AC逐条→UC→模型→场景；反向UC→AC | 已完成 |
| [04-活动图与状态图](04-活动图与状态图.md) | 4张新增活动图+2张复用；3个真实生命周期状态图 | 已完成 |
| [05-分析序列与用例实现](05-分析序列与用例实现.md) | 4张分析序列、5组Use-Case Realization职责 | 已完成 |
| [06-自检与评审](06-自检与评审.md) | 课程、AC、UML、文档、Git检查 | 已完成 |

## 3. UML 源与导出

全部图源位于 `docs/uml/`，导出在对应 `generated/`：

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

复用并核对：认领活动图、认领申请状态图、采集任务状态图、Authorship关系状态图。

## 4. 关键业务边界

- **注册即主页**：注册成功创建 User + 唯一 UserProfile；零成果/非学者同样有公开主页；UC-22编辑普通资料无需Scholar认领。
- **UserProfile与Scholar分离**：同名不自动合并；认领只授予目标Scholar对象权，不建第二主页，不批量确认Authorship。
- **FR12/IN01**：基础网络四类合格共同成果；IN01只在合格共同论文子图求最短路径；共同专利/项目/获奖不能成为路径边。
- **规模**：功能联调≥100万、最终容量≥500万、固定环境100并发；均为目标，未实测。

## 5. 验证状态

- 69 AC数量/唯一性/正文一致性：自动脚本检查。
- UC-01～19、UC-22：规约与追踪存在；UC-20/21仅延期。
- PUML：实际渲染SVG/PNG；XML可解析；目视检查。
- 文档链接/编号：静态检查。
- 软件测试/算法执行/容量/并发/恢复/覆盖率：**NOT RUN**。
- 教师评分/人工课堂验收：**未提供，不能虚构PASS**。

## 6. 后续

S02内完成的是分析模型，不是架构/数据库/API实现设计。后续阶段若改变业务语义、UC或69 AC，必须回到需求变更流程；不得直接改图替代需求批准。
