# UC-01 分析与现有全局分析类差异

**版本：** V1.0（2026-10-21）
**比较对象：** [`DGM-CLASS-01-analysis.puml`](../../../uml/analysis/DGM-CLASS-01-analysis.puml)、[`01-BCE分析类模型.md`](../01-BCE分析类模型.md)。
**原则：** 全局图只作比对材料，不作为UC-01类识别的反向答案；本轮不重构全局图。

## 1. 已存在且一致

| UC-01类/概念 | 全局图对应 | 一致点 | 证据 |
|---|---|---|---|
| `SearchUI` | `SearchUI`（全局图第17行） | 都承接检索输入/结果展示 | FR01；UC-01 |
| `SearchCoordinator`职责 | 全局`SearchCtl`（第32–34行） | 都负责公开过滤和稳定排序 | FR01；AC-01/02 |
| `WorkEntity` | 全局`Work`（第74–78行） | 都是公开成果/去重实体；不是来源记录 | FR01；FR02/16 |
| `Scholar` | 全局`Scholar`（第58–61行） | 都是平台学者身份，不是UserProfile | FR03；SRS §12 |
| 成果/学者不混排 | 全局有独立`Work`/`Scholar`，无强制关联 | 范围互斥，无Work—Scholar检索关系 | AC-01 |
| 查询只读 | 全局有`SearchCtl`，无写职责 | 不创建/修改业务实体 | FR01 |

## 2. 命名/别名差异

| 本UC名称 | 全局名称 | 差异 | R5建议 |
|---|---|---|---|
| `SearchCoordinator` | `SearchCtl` | 全名 vs 缩写 | 统一为可读全名，或建立别名表；不要在不同UC VOPC中混用 |
| `WorkEntity` | `Work` | 实体名不一致 | 以全局`Work`为正式名，`WorkEntity`作为本UC别名；R5统一 |
| `SearchUI` | `SearchUI` | 一致 | 保留 |

## 3. 职责/关系缺口

| 项目 | 本UC证据 | 全局现状 | 差异/影响 | R5处理 |
|---|---|---|---|---|
| 查询范围 | AC-01：成果/学者范围互斥 | 全局`SearchCtl`只有公开过滤/稳定排序，未明确范围 | 缺少范围隔离职责 | 在`SearchCtl`职责中加入范围选择/互斥 |
| 条件交集 | FR01：类型/年份/领域/发生时机构取交集 | 全局`SearchCtl`未列出 | 搜索结果条件执行不完整 | 加入条件交集与年份两端含 |
| 稳定排序/分页 | FR01；AC-02 | 全局有稳定排序，但未明确稳定ID破并列/分页上下文 | 页间不重不漏无模型支撑 | 加入稳定ID平局键与上下文职责 |
| 空/失败分离 | AC-03；NF01 | 全局`SearchCtl`未明确 | 可能把空结果与失败混同 | 在协调器职责中区分 |
| Scholar查询关系 | M9 | 全局`SearchCtl`图线主要指向`Work`；未见`SearchCtl→Scholar` | 学者范围查询缺关系 | R5补`SearchCtl→Scholar`或说明由公共查询职责覆盖 |
| 查询上下文 | FR01“切页保留查询上下文” | 全局无`SearchContext`类/属性 | 上下文没有独立类，当前作为控制器状态 | R5决定是否需要值对象；本UC不擅自造类 |

> 全局`DGM-CLASS-01`第110行有`SearchCtl ..> Work`；本UC需要同时查询Scholar。是否新增`SearchCtl→Scholar`是R5的统一模型问题，本UC VOPC已如实表达。

## 4. 不能确定的跨用例关系

| 关系 | 为什么不能在本UC确定 | 留待 |
|---|---|---|
| Work—SourceRecord/WorkVersion | 检索结果不读取来源/版本详情 | UC-02/16、R5 |
| Scholar—UserProfile | 公开检索不打开主页，也不合并账号 | UC-03、R5 |
| Scholar—Authorship | 检索学者不等同于读取参与关系 | UC-08/10/14、R5 |
| 查询上下文是否独立类 | FR01只要求保留上下文，未要求独立业务对象 | R5/设计 |
| 检索与统计/网络共享查询模型 | 本UC只做公开检索，不分析派生统计/网络 | UC-11～15、R5 |

## 5. 追踪文件差异

`03-AC-UC-分析模型追踪.md`的UC-01行原模型为`DGM-USECASE-04/ACT-07/SEQ-03`。本轮为UC-01补`SEQ-06/VOPC-02`；只改UC-01相关行（FR01、NF01、NF02），不批量改其他UC。

## 6. 差异结论

建议R5统一分析类时：

1. 统一`SearchUI`名称；
2. 统一`SearchCtl`/`SearchCoordinator`名称；
3. 统一`Work`/`WorkEntity`名称；
4. 在`SearchCtl`职责中补范围选择、条件交集、稳定ID/分页上下文、空/失败分离；
5. 补`SearchCtl→Scholar`的查询关系，或明确公共查询抽象；
6. 不把`SearchContext`/`SearchResult`强行升级为全局实体。

本轮**不直接修改** `DGM-CLASS-01-analysis.puml`。
