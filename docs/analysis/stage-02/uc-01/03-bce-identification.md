# UC-01 Boundary / Control / Entity 识别

**版本：** V1.0（2026-10-21）
**方法：** 教材§5.4.2：从事件流识别边界、控制、实体类；不从全局BCE反向填充。
**输入：** 本目录01规约、02事件流。

## 1. 识别原则

1. Boundary承接访客输入和结果/失败/空态显示。
2. Control协调查询范围、条件校验、公开过滤、排序、分页和上下文。
3. Entity承载公开可检索的成果或学者事实。
4. 只有实际参与查询交互的对象进入VOPC；查询上下文是控制器的分析状态，不强行造实体。
5. 详情、主页、统计、网络对象不属于本UC。

## 2. 候选分析类

| ID | 候选类 | 类型 | 发现事件 | 业务行为 | 为什么不能由其他候选承担 |
|---|---|---|---|---|---|
| C1 | `SearchUI` | Boundary | E1、E8、E9、A1–A3 | 接收范围/关键词/筛选/排序/页码；显示结果、空态、失败、非法条件 | 实体不应处理输入/展示；控制类不直接面对访客 |
| C2 | `SearchCoordinator` | Control | E2–E9、A1–A4 | 校验条件、确定范围、固定上下文、查询成果/学者、公开过滤、排序、分页、返回 | Boundary只能显示；WorkEntity/Scholar各自只能回答自身领域，不能协调范围、跨类型分页和失败/空态 |
| C3 | `WorkEntity` | Entity | E4、E6、E7 | 提供公开有效成果、类型/年份/领域/发生时机构/稳定ID等可检索事实 | Scholar不是成果；SourceRecord是来源证据不是去重成果；不能用详情对象替代 |
| C4 | `Scholar` | Entity | E5、E6、E7 | 提供公开有效学者身份和可检索事实 | UserProfile是普通主页，不是平台学者身份；同名不合并；不能用成果替代 |

## 3. 逐类选择理由

### C1 SearchUI（Boundary）

- 发现于E1提交查询、E8显示结果、E9翻页、A1–A3显示空/失败/非法。
- 不持久保存查询条件；条件由控制器的分析上下文维护。
- 现全局图有`SearchUI`（第17行），本UC保持同名，减少R5差异。

### C2 SearchCoordinator（Control）

- 发现于E2校验、E3上下文、E4/E5范围分支、E6过滤、E7排序分页、E8/E9返回和翻页。
- 需要同时协调成果/学者两个范围，并保证公开过滤、条件交集、稳定排序和分页一致。
- 不让WorkEntity/Scholar承担跨类型协调；也不让UI承担业务校验。

### C3 WorkEntity（Entity）

- 发现于E4成果查询、E6条件校验、E7稳定ID排序。
- 必要事实：公开/有效状态、稳定ID、类型、年份、领域、发生时机构、公开可检索文本。
- 不能并入Scholar：成果和学者是不同结果类型，AC-01要求范围不混排。

### C4 Scholar（Entity）

- 发现于E5学者查询、E6公开过滤、E7稳定ID排序。
- 必要事实：公开/有效状态、稳定ID、公开可检索身份信息。
- 不能并入UserProfile：Scholar是平台采集/核验的学者身份，可无账号；普通UserProfile不是学者身份。
- 不能并入WorkEntity：学者没有成果类型/年份/机构发生事实。

## 4. 被拒绝的候选类

| 候选 | 拒绝理由 | 依据/后续 |
|---|---|---|
| `SearchContext`/`QueryContext` | FR01只要求保留查询上下文，没有要求独立生命周期、持久身份或对外行为；本UC作为`SearchCoordinator`的单次分析状态处理 | FR01；Q-02；若R5认为需要值对象，再单独建模 |
| `SearchResult`/`ResultPage`/DTO | 结果页是控制器的返回投影，不是独立业务实体 | 教材§5.4.2；分析推导 |
| `Organization`/`Field` | 机构/领域在本UC是筛选值和成果属性，没有独立查询行为或生命周期 | FR01；分析推导 |
| `SourceRecord`/`WorkVersion` | 来源和版本用于详情/去重，不是检索结果的核心实体；本UC不访问来源详情 | FR02/FR16；UC-02 |
| `User`/`UserProfile` | 公开检索不要求登录，也不读取普通个人主页 | FR01；NF01 |
| `ScholarAuthorization`/`Claim` | 检索不授权、不认领 | FR05；FR01 |
| `SearchIndex`/`IndexEngine` | 具体索引/检索引擎是实现技术，不是分析类 | 教材§5.4.2；技术边界 |
| `QueryValidator` | 校验可由协调器承担，单独类没有独立业务状态/行为证据 | 教材§5.4.2 |

## 5. 边界结论

| 边界 | 结论 | 证据 |
|---|---|---|
| 成果 vs 学者 | 两个独立结果类型；范围不混排 | AC-IMPL-FR01-01 |
| Scholar vs UserProfile | Scholar是平台学者身份；UserProfile是账号主页；不强制合并 | FR03；SRS §12；FR01 |
| WorkEntity vs SourceRecord | 检索返回去重成果，不返回来源明细 | FR01；FR02/16 |
| 查询上下文 | 控制器单次状态，不是持久实体 | FR01；分析推导 |
| 公开/待审 | 只有公开有效对象进入结果 | FR01；BR-IMP-01 |

## 6. 识别结论

本UC的参与分析类为4个：

- Boundary：`SearchUI`
- Control：`SearchCoordinator`
- Entity：`WorkEntity`、`Scholar`

不引入`SearchContext`、`SearchResult`、`Organization`、`Field`、`SourceRecord`、`User/UserProfile`、`SearchIndex`等类；这些概念要么是属性/筛选值，要么是其他UC/技术设计内容。
