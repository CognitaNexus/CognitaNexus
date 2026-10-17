# 规范—代码对应关系表

本表把核心模块的规范条款与工程实现、测试逐条对应起来，供评审核对「规范先行、代码履约」。

**依据说明。** 需求基线 V1.9、双组实施候选 SRS V1.1、核心 SPEC V1.0 在需求仓库维护（`CognitaNexus/CognitaNexus` 的 `docs/spec/`、`docs/srs/`），本仓库不复写需求正文，只引用条款编号。下表「SPEC 条款」一列指向该仓库的 `CognitaNexus-Core-SPEC-V1.0.md`。

**状态说明。** 核心 SPEC V1.0 为 Review Candidate，SRS V1.1 为 Implementation RC，均未签认。因此下表只记录「实现是否已按条款编码」，不表示条款已冻结；参数类条款（相似度阈值、排序权重、证据保留期限）在本仓库一律作为可调初值处理，不写成已确认结论。

## 1 数据清洗（SPEC §4，FR09）

| SPEC 条款 | 要求要点 | 实现位置 | 测试与自检 | 验收编号 |
| --- | --- | --- | --- | --- |
| §4.2 规则表 | 字段白名单：只读规范化结构里声明的字段，来源响应中的其他内容不进入持久化 | `services/core-service/.../catalog/application/NormalizedRecord.java` | 未建专门单元测试（如实标注）；由 `tests/api/smoke-ingestion.sh` 的入库链路与代码审查覆盖 | AC-IMPL-FR09-01 |
| §4.2 规则表 | 标题/名称规范化（大小写、空白、全角半角）后落 `title_norm`/`name_norm`，供相似度索引使用 | `services/core-service/.../platform/util/TextNormalizer.java`；`services/ingestion-service/.../platform/util/TextNormalizer.java` | `services/ingestion-service/src/test/.../TextNormalizerTest.java` | AC-IMPL-FR09-01 |
| §4.2 规则表 | 标记文本剥离（HTML/实体）后再参与规范化 | `services/ingestion-service/.../platform/util/MarkupText.java` | 采集自检 `tests/api/smoke-ingestion.sh` | AC-IMPL-FR09-01 |
| §4.3 算法步骤 | 年份解析与精度：能确定年份记 YEAR，只有区间或无法确定时记 UNKNOWN 而不是猜一个值 | `services/core-service/.../catalog/domain/PublicationYear.java`、`YearPrecision.java`；迁移 `V7__year_precision_unknown.sql` | `services/core-service/src/test/.../catalog/domain/PublicationYearTest.java` | AC-IMPL-FR09-01 |
| §4.2 规则表 | 数据性质必须显式区分真实/模拟；来源未声明时缺省为 SIMULATED，不得默认当成真实 | `NormalizedRecord.dataNature()` 缺省逻辑；`shared-kernel` 的 `DataNature` | `CatalogDomainSemanticsTest`（枚举语义）；缺省逻辑本身未建专门用例，如实标注 | AC-IMPL-FR09-03 |
| §4.4 异常决策表 | 无法归一的字段进来源/候选域，不强行插入；冲突登记治理异常 | `.../catalog/application/SourceIntakeService.java`、`SourceIntakeCommands.java`；`governance` 的 `GovernanceAnomaly` | `services/core-service/src/test/.../governance/application/GovernanceServiceTest.java` | AC-IMPL-FR09-01 |
| §4.5 追踪 | 每个字段保留来源归属与前后值，可追溯 | `.../catalog/domain/FieldProvenance.java`；表 `core_field_provenance`、`core_achievement_source` | `tests/api/smoke-core.sh` | AC-IMPL-FR09-01 |

## 2 成果去重（SPEC §5，FR09）

| SPEC 条款 | 要求要点 | 实现位置 | 测试与自检 | 验收编号 |
| --- | --- | --- | --- | --- |
| §5.2 判定顺序 | **仅标题相似不得自动合并**，只产生候选；候选与规范实体分开存储 | `.../governance/application/GovernanceService.java#reportTitleSimilarity`；表 `gov_dedup_candidate` | `GovernanceServiceTest`「候选登记：相似度只产生候选，不产生合并」 | AC-IMPL-FR09-02 |
| §5.2 判定顺序 | 左右实体按标识字典序落库，使 (A,B) 与 (B,A) 命中同一唯一约束 | 同上；唯一约束 `uk_gov_dedup_candidate` | `GovernanceServiceTest` 候选登记用例 | AC-IMPL-FR09-02 |
| §5.2 判定顺序 | 相似度检索走三元组索引，不做全表扫描 | 迁移 `V5__title_similarity_index.sql`（pg_trgm GIN 索引） | `ops/local/backfill-candidate-similarity.sh` | AC-IMPL-FR09-02 |
| §5.5 并发与复算 | 候选裁决不覆盖既有结论：重复裁决返回 0 行影响 | `.../governance/infrastructure/GovernanceMapper.java#resolveCandidate`（条件 `status='OPEN'`） | `GovernanceRepositoryAdapterTest`；`tests/api/smoke-merge-preview.sh` 第 9 节 409 断言 | AC-IMPL-FR09-02 |
| §5.5 并发与复算 | 合并必须留台账（旧标识重定向、关系与来源前后快照），只追加 | `.../governance/application/GovernanceService.java#mergeAchievements`；表 `gov_merge_ledger` | `tests/api/smoke-merge-preview.sh` 第 7 节（真跑合并对照台账） | AC-IMPL-FR09-03 |
| §5.5 并发与复算 | 合并回答了这一对候选的问题，候选必须随合并一并裁决，不留死条目 | `GovernanceMapper.resolveOpenCandidatesBetween`；`GovernanceService.mergeAchievements` | `tests/api/smoke-merge-preview.sh` 第 8 节（总数核对） | AC-IMPL-FR09-03 |
| §5.5 并发与复算 | 端点已被并走的候选必须能改判到最终实体，不得逼迫写下「判定为不同实体」 | `GovernanceService.rebaseCandidate`；迁移 `V9__candidate_rebased_status.sql`；`POST /admin/core/governance/candidates/{id}/rebase` | `GovernanceServiceTest.Rebase`（7 项）；`tests/api/smoke-merge-preview.sh` 第 9 节 | AC-IMPL-FR09-03 |

## 3 学者消歧与成果归属（SPEC §6，FR15）

| SPEC 条款 | 要求要点 | 实现位置 | 测试与自检 | 验收编号 |
| --- | --- | --- | --- | --- |
| §6.2 判定与维护规则 | **同名不得直接合并学者**；合并需显式指定幸存实体与理由 | `.../governance/application/GovernanceService.java`（学者合并路径）；`.../catalog/domain/Scholar.java` | `GovernanceServiceTest`「学者合并：同名不是同人证据」 | AC-IMPL-FR15-01 |
| §6.2 判定与维护规则 | 姓名变体单独存储，不覆盖原名 | 表 `core_scholar_name_variant`；`.../catalog` 相关仓储 | `tests/api/smoke-core.sh` | AC-IMPL-FR15-01 |
| §6.3 判定决策表 | 关系状态独立存储，不互相推导；同一学者两侧重复关系合并时保留幸存侧状态 | `GovernanceMapper` 关系迁移语句；`GovernanceService.previewMerge` 的重复关系清单 | `tests/api/check_merge_preview.py`（重复清单条数与计数一致） | AC-IMPL-FR15-02 |
| §6.4 算法流程 | 认领审核通过后才允许管理本人门户；门户授权随合并迁移 | `.../portal/application/PortalService.java`；表 `iam_portal_authority`、`wf_claim_request` | `tests/api/smoke-core.sh` 认领链路 | AC-IMPL-FR15-02 |
| §6.5 待评审参数边界 | 消歧参数属候选参数，不写成已确认结论 | `docs/standards/编码规范.md` §1.4；`GovernanceService.SIMILARITY_HIGH/MID` 注释 | — | — |

## 4 检索匹配与排序（SPEC §7，FR01）

| SPEC 条款 | 要求要点 | 实现位置 | 测试与自检 | 验收编号 |
| --- | --- | --- | --- | --- |
| §7.2 检索规则表 | 检索只命中具备公开资格的成果（真实 × 公开 × 生效 × 未合并） | `services/query-service/.../discovery/application/SearchService.java`、`infrastructure/SearchMapper.java`（资格条件在 SQL 中） | `SearchQueryTest`（查询构造）；资格过滤由 `tests/api/smoke-query.sh` 端到端核对 | AC-IMPL-FR01-01 |
| §7.2 检索规则表 | 过滤条件与分页稳定，排序键白名单 | `.../discovery/application/SearchCriteria.java`、`SearchQuery.java` | `SearchQueryTest`；`tests/api/smoke-query.sh` | AC-IMPL-FR01-02 |
| §7.4 排序伪代码 | 排序权重属候选参数，先给可用初值，不声称已定 | `SearchQuery` 排序分支；`docs/standards/编码规范.md` §1.4 | — | — |
| §7.5 追踪 | 检索结果回到 core 的公开详情，详情口径以 core 为准 | `.../corelink`（query 侧）；core 的 `PublicCatalogQueryService` | `tests/api/smoke-query.sh` 跨服务对照 | AC-IMPL-FR01-01 |

## 5 统计分析（FR16 相关，统计计算）

| 要求要点 | 实现位置 | 测试与自检 |
| --- | --- | --- |
| 机构成果分类排名、领域热点等统计只统计公开资格成立的数据 | `services/query-service/.../analytics/application/AnalyticsService.java`、`infrastructure/AnalyticsMapper.java` | `services/query-service/src/test/.../AnalyticsServiceTest`；`tests/api/smoke-query.sh` |
| 合作网络：共现分析、子图规模限制，超限明确报错而不是截断冒充 | `.../network/application/NetworkService.java`、`domain/CooperationGraph.java` | `CooperationGraphTest`；`tests/api/smoke-query.sh` |
| 发布屏障：统计只在公开版本一致时返回，否则明确回退而不是给旧数 | `.../consistency/PublicBarrierGuard.java`、`PublicConsistencyController.java` | `tests/api/smoke-query.sh` |

## 6 跨模块交接与失败回退（SPEC §8）

| SPEC 条款 | 要求要点 | 实现位置 | 测试与自检 |
| --- | --- | --- | --- |
| §8 不变量 | 交接至少一次投递，消费端幂等 | `evt_outbox` / `evt_outbox_delivery` / `evt_idempotency_record`；`.../core/platform/event/` | `tests/api/smoke-ingestion.sh`、`smoke-query.sh` |
| §8 不变量 | 三水位（采集水位、交接水位、投影水位）各自可查 | 采集侧 `ing_collect_job`、`CollectUnitOfWork`；投影侧 `evt_revision_counter` | `tests/api/smoke-ingestion.sh` |
| §8 失败回退 | 采集失败按退避重试，不在固定周期上无限重打上游 | `services/ingestion-service/.../collect/application/CollectExecutionService.java#backoffSeconds`；迁移 `V5__fetch_backoff.sql` | `CollectExecutionServiceTest`（9 项）、`CollectMapperSqlTest`（3 项）、`tests/api/smoke-ingestion.sh` 第 13 节 |

## 6.1 采集更新周期与限流退避（WB-13，对应 AC-IMPL-FR08-02 / FR09-03 / FR10-02）

三个「周期」在本仓库里必须能被分别指出，混成一个数字就会出现「每 2 秒抓一次学术来源」或
「每 7 天更新一次」（后者没有任何出处）：

| 概念 | 含义 | 配置／落点 | 验证 |
| --- | --- | --- | --- |
| 调度轮询间隔 | 服务多久看一次任务队列 | `cn.collect.worker-interval-ms`（默认 2000 ms） | `CollectWorker`；与来源无关，故不参与任何「更新周期」判断 |
| 失败退避 | 失败后多久才允许再试 | `ing_collect_job.next_attempt_at`（V5）；`max-backoff-seconds` 封顶 | `CollectExecutionServiceTest`（退避随次数增长、上限生效）；`CollectMapperSqlTest`（查询带退避条件） |
| 来源业务更新周期 | 新记录发现／历史字段刷新的期望节奏 | `ing_source_run_config.new_record_interval_minutes` / `field_refresh_interval_minutes` / `update_plan_note` / `next_due_at`（V8） | `SourceRunConfigUpdatePlanTest`；`CollectMapperSqlTest`（门控条件在 SQL 里）；`ops/verify/collect-update-plan.sh`（真实库约束与门控，含反证） |
| 限流要求 | 来源要求等多久（429／503 + `Retry-After`） | `SourceFetchException`；`CollectExecutionService#decideBackoff` | `SourceFetchExceptionTest`；`CrossrefAdapterStubTest`（真实 HTTP 替身）；`CollectExecutionServiceTest`（`RETRY_AFTER` 优先且受封顶） |

口径说明（不是需求结论）：

- **周期默认空置**。`NULL = 未设定`，此时不按周期排期、任务仍由人工创建驱动，与引入该字段之前完全一致；
  需求与调研都没有给出各来源的更新节奏，因此不放任何默认值。
- **设周期必须写依据**，由库级 CHECK 强制（`ck_ing_source_update_plan_note`）。没有出处的数字下次无法复核。
- **周期只挡「还没开始的执行」**，不挡正在翻页的任务：`listRunnableJobIds` 的条件是
  `total_fetched > 0 OR next_due_at IS NULL OR next_due_at <= now()`。少了后半句，一个来源两个任务时
  会有一个停在半路。
- **未做**：到点自动创建任务（需要先定任务幂等键与人工建任务的关系，属待评审）；
  用真实来源数据标定各来源的周期（WB-13 要求「先从已接入来源各选一类实际数据说明理由」，本仓库
  没有获准使用的真实来源样本，故不填）。

## 7 规范与代码的开发顺序（SDD 证据）

| 环节 | 做法 | 可核对位置 |
| --- | --- | --- |
| 编码前先有规范 | 每项改动先关联 FR / AC-IMPL 编号与 SPEC 条款，再动代码；无编号的功能只登记待确认问题 | `docs/standards/编码规范.md` §10.1；本表各行的「SPEC 条款」列 |
| 规范纳入版本管理 | SPEC / SRS 在需求仓库随版本提交（V1.0 / V1.1），本仓库只引用编号 | 需求仓库 `docs/spec/`、`docs/srs/` |
| 规范评审记录存档 | 评审报告与决策登记在需求仓库 | 需求仓库 `docs/spec/spec-review-report.md`、`docs/srs/integration/decision-register-2026-10-12.md` |
| 规范—代码对应 | 本表；新功能或改口径时同步更新本表 | 本文件 |

## 8 未覆盖与待办

1. 检索排序的权重参数、相似度阈值仍为候选值，待 SPEC 联合评审后回填。
2. 本仓库目前没有 `core` 侧解析入库（Intake）的专门单元测试，该段规则由接口自检与代码审查覆盖；表中已逐行如实标注，不把接口自检写成单元测试结论。
3. 100 万规模已实测但**未达标**（见 `docs/ai/第三批-容量与恢复验证记录-2026-10-17.md`）；500 万规模与 300／1000 并发未执行。不得把「跑过 100 万」写成「容量验证通过」。
4. 采集端到端（真实服务 + 替身来源）未跑：本轮只验证了适配器与执行服务两层，见 §6.1。
