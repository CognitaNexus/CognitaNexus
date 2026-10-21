# UC-02 分析与现有全局分析类差异

**版本：** V1.0（2026-10-21）
**比较对象：** [`DGM-CLASS-01-analysis.puml`](../../../uml/analysis/DGM-CLASS-01-analysis.puml)、[`01-BCE分析类模型.md`](../01-BCE分析类模型.md)、[`analysis-class-registry.md`](../automation/analysis-class-registry.md)。
**原则：** 全局图只作比对材料，不作为 UC-02 类识别的反向答案；本轮不重构全局图，只登记 R5（第 6 阶段）建议。

## 1. 已存在且一致

| UC-02 类/概念 | 全局图对应 | 一致点 | 证据 |
|---|---|---|---|
| `WorkEntity` | `Work`（Entity） | 都是去重后的四类成果规范实体；都不是来源记录 | FR02/FR16；BR-IMP-02 |
| `SourceRecord` | `Source`（Entity） | 都表达来源证据、原值/来源/许可、逻辑出处 | FR16；INV-01/02 |
| `WorkVersion` | `Version`（Entity） | 都表达“有证据的版本关系”，无证据不合并 | FR02；DD-08 |
| `Authorship` | `Authorship`（Entity） | 都表达学者—成果参与关系（角色/顺序/证据/状态），都强调“管理员审核不等于本人确认” | FR15；SRS §6.4/§11.3 |
| 来源—成果—版本分离 | 全局 `Source -- Work`、`Work -- Version` | 全局无合并类，方向一致 | FR16；INV-02 |
| 参与关系独立于成果 | 全局 `Scholar -- Authorship`、`Authorship -- Work` | 参与关系不是成果属性；解除不删成果 | INV-05；§7.2 |
| 公开详情只读 | 全局无详情写职责（写职责在 GovernCtl/AuditCtl 等） | 详情读取不修改实体 | FR02 执行前提 |

## 2. 命名/别名差异

| 本 UC 名称 | 全局名称 | 差异 | R5 建议 |
|---|---|---|---|
| `WorkDetailUI` | 无直接对应；相关：`PublicUI`（图）/`PublicPortalUI`（表）、`SearchUI`（图/表） | 全局把“公开详情”分散在 Public/Search 两个边界名下，且图与表命名已不一致 | 明确一个“公开详情边界”正式名；`WorkDetailUI` 可并入 Public 系或保留独立 |
| `WorkDetailCoordinator` | `SearchCtl`（图）/`SearchCoordinator`（表、登记表标“UC-01/02”） | 全局把详情读取并入检索控制器 | 建议新增公开详情协调器，或在全局把该控制器更名为“公开读取协调器”并把检索/详情职责并列；需联合评审（本 UC 不擅改） |
| `WorkEntity` | `Work` | 实体名不一致 | 以全局 `Work` 为正式名，`WorkEntity` 作为本 UC 别名（与 UC-01 处理一致） |
| `SourceRecord` | `Source` | 实体名不一致 | 统一为 `SourceRecord` 或登记别名表 |
| `WorkVersion` | `Version` | 实体名不一致 | 统一为 `WorkVersion`，避免与“版本号/图版本”混淆 |
| `Authorship` | `Authorship` | 一致 | 保留 |

> 全局边界名自身存在两套写法（图内 `PublicUI`/`AdminUI`/`SourceUI` 与 01 文档表的 `PublicPortalUI`/`AdminConsole`/`SourceAdapter`）。这是既有不一致，本 UC 只登记，不改。

## 3. 职责/关系缺口

| 项目 | 本 UC 证据 | 全局现状 | 差异/影响 | R5 处理 |
|---|---|---|---|---|
| 详情读取职责 | E2–E9；M3–M20 | `SearchCtl` 职责只写“公开过滤/稳定排序” | 详情组装无明确控制职责 | 新增详情协调职责或扩展公开读取控制器 |
| 一条成果多来源呈现 | `AC-IMPL-FR02-01`；`AC-IMPL-FR16-02` | 全局 `Source -- Work` 未标多重性 | “两条来源一条成果”缺静态表达 | 标 `Work 1 — 0..* Source` |
| 版本证据门槛 | FR02“须有证据才显示版本关系”；DD-08 | 全局 `Work -- Version : 明示版本` 无多重性、无门槛说明 | 门槛可能被实现忽略 | 标 `1 — 0..*` 并注明“无明示证据不建立” |
| 参与关系多重性 | §7.2；INV-05 | 全局 `Authorship -- Work` 无多重性 | 计数/删除语义不显式 | 标 `Work 1 — 0..* Authorship` |
| 缺失字段标未知 | SRS §7.1；`AC-IMPL-FR02-01` | 全局无该机制 | 展示规则缺模型支撑 | 在 Work 职责/机制中补“缺失标未知” |
| 时间语义分离 | SRS §7.1；SPEC `time_events[]` | 全局 `Work` 只有“去重规范值/成果类型/生效状态” | 采集时间与发生时间可能混称 | 在 Work 属性中区分发生时间与更新时间 |
| 外链只跳原站、失败不删成果 | FR02；`AC-IMPL-FR02-02` | 全局无外链职责表述 | 边界职责缺“只提供原站地址” | 在公开详情边界职责中补 |
| 不可访问/失败/外链失败三分 | NF01；`AC-IMPL-NF01-01` | 全局边界只写“展示…错误/空态” | 三类状态可能合并 | 在边界职责中显式三分 |
| 来源级许可与数据性质 | FR16；SRS §7.1；BR-IMP-07 | 全局 `Source` 有“原值/来源/许可”，`Work` 无数据性质字段 | 数据性质归属不清 | 明确“来源级属性 + 成果级互斥标识”的聚合口径（Q-07） |

## 4. 不能在本 UC 确定的跨用例关系

| 关系 | 为什么不能在本 UC 确定 | 留待 |
|---|---|---|
| WorkEntity — Scholar（直接关联） | 参与经 Authorship 表达；直接连会绕过关系状态 | R5/UC-03 |
| 机构/团队（承担单位、获奖团队）是否独立实体 | 本 UC 无机构读取消息；但 FR11 统计与 FR12 建边需要机构/领域事实 | UC-11/UC-14、R5 |
| SourceRecord — WorkVersion | 版本与出处是不同事实，本 UC 无消息 | UC-09/UC-16、R5 |
| 详情边界与学者门户/统计视图共享 | 多视图共享同一生效数据的要求（NF05）不在本 UC | UC-03/UC-11、R5 |
| 详情与规模核对共享视图 | 规模核对（按去重实体计数）是 UC-16 | UC-16 |
| 确认/纠错入口 | 详情是否提供“本人确认”入口属写用例 | UC-08（本 UC 不建关系） |

## 5. 追踪文件差异

`03-AC-UC-分析模型追踪.md` 中 FR02 行原模型为 `DGM-USECASE-04 / ACT-07 / SEQ-03`，FR16 行为 `DGM-USECASE-04 / ACT-07`。本轮为 UC-02 产出 `DGM-SEQ-07`（`DGM-VOPC-03`）。该共享追踪文件的更新由父 Agent 负责，本 UC 不直接修改。

## 6. 差异结论（R5 输入）

建议 R5 统一分析类时：

1. 明确“公开详情边界”的唯一正式名，解决 `PublicUI`/`PublicPortalUI`/`SearchUI`/`WorkDetailUI` 的归属冲突；
2. 决定详情读取职责归属：新增详情协调器，或把 `SearchCtl` 更名扩展为公开读取控制器（二者都需联合评审，不在本 UC 决定）；
3. 统一 `Work`/`WorkEntity`、`Source`/`SourceRecord`、`Version`/`WorkVersion` 命名并登记别名；
4. 为 `Work—Source`、`Work—Version`、`Work—Authorship` 补 `1 : 0..*` 多重性，并注明版本关系的证据门槛；
5. 在公开详情边界/控制器职责中补：缺失标未知、时间语义分离、外链只跳原站、外链失败不删成果、三类状态分离；
6. 明确“来源级数据性质”与“成果级真实/模拟/混合标识”的聚合口径（Q-07）；
7. 决定机构/团队事实是否独立成类（影响 FR11/FR12），本 UC 暂按类型字段承载。

本轮**不直接修改** `DGM-CLASS-01-analysis.puml`、`01-BCE分析类模型.md`、`analysis-class-registry.md` 或 AC/UC 追踪表；登记表增量由父 Agent 在批次收口时统一写入。
