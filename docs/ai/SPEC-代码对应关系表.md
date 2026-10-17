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

## 7 来源展示许可（ADR-001 方案 A，2026-10-18 增补）

方案与决策依据见 [ADR-001](../architecture/adr/ADR-001-来源暂停与撤销展示许可.md)；实现记录与未解决项在其 §8。

| 条款 | 要求要点 | 实现位置 | 测试与自检 | 验收编号 |
| --- | --- | --- | --- | --- |
| AC-FR01-S01-02 / BR-606 | 来源暂停采集**不得**改变历史成果的可读性 | `.../catalog/application/SourceAdminService.java#setCollectionPaused`；旧入口 `setEnabled` 已标记 `@Deprecated` 且语义收敛为「只切采集开关」 | `SourceDisplayLicenseProbe` A1（真实库）；`tests/api/smoke-core.sh` | AC-IMPL-NF07-01 |
| BR-606 | 撤销展示许可后公开读**立即**不再返回依赖该来源的成果，不等逐批收紧跑完 | `.../catalog/infrastructure/AchievementMapper.java#PUBLIC_SOURCE_LICENSE_CONDITION`（导出与计数共用同一常量） | 探针 A2 | AC-IMPL-NF07-01 |
| AC-IMPL-NF07-01 | 「有效依据」的判据看**展示许可**，不看采集开关 | 同上；迁移 `V11__source_display_license.sql` | 探针 A4 | AC-IMPL-NF07-01 |
| 架构 §7.2 | 大规模撤权不得「一次事务里最多改 5000 条」：作业可中断、可续跑，完成判据是残留为 0 | 表 `core_source_display_revocation`；`SourceAdminService#revokeDisplayLicense`、`resumeJob`；批大小 500、单次上限 400 批 | 探针 A3（5001 条，`target=5001`/`tightened=5001`/残留 0/`SUCCEEDED`） | AC-IMPL-NF07-01 |
| BR-606 | 恢复许可只放开「因本次撤权被收紧」的对象，不碰因治理未通过或人工限制而受限的成果 | `core_achievement.display_restriction_source_id`（部分索引）；`SourceAdminService#restoreDisplayLicense` | 探针 A5 | AC-IMPL-NF07-01 |
| 编码规范 §1.4 | 撤权必须留时间点与理由，事后可复核 | 库级 CHECK `ck_raw_source_config_display_license_changed`、`ck_core_source_revocation_reason`（V11） | 迁移即断言；`tests/api/smoke-core.sh` | AC-IMPL-NF07-01 |

## 8 统一待办与账户安全（FR14 / FR04 / A08，2026-10-18 增补）

| 条款 | 要求要点 | 实现位置 | 测试与自检 | 验收编号 |
| --- | --- | --- | --- | --- |
| FR14 | 采集、异常治理、学者核验、认领审核、成果变更、账号维护要有**统一入口**，而不是散在四处 | `.../catalog/api/AdminCatalogController.java#todos`（`GET /admin/core/todos`）；前端 `frontend/src/views/TodosView.vue` | `AdminCatalogTodosTest`（4 项） | AC-IMPL-FR14-01 |
| FR14 | 待办只统计「需要人做决定」的事项；采集失败与投递积压属运行状态，不计入 | 同上（`todoNote` 随数据返回说明口径） | `AdminCatalogTodosTest.todoTotalCountsOnlyHumanDecisions` | AC-IMPL-FR14-01 |
| FR14 | 无该类审核能力时计数为 `null` 而不是 0（「没有待办」与「你看不到」是两件事） | 同上（`Capabilities.has` 分支） | 同上；前端 `display()` 把 null 渲染为「无权限查看」 | AC-IMPL-FR14-01 |
| FR04 | 角色授予与撤销都必须提升认证版本，否则旧会话仍按旧角色鉴权 | `.../identity/application/AccountAdminService.java#grantRole/revokeRole`；`.../infrastructure/AccountMapper.java#bumpAuthVersion` | `AccountAdminServiceTest`（14 项：授予、重复授予幂等、撤销、撤自己、撤 USER、缺理由、并发被撤、能力校验、审计人取自上下文、停用） | AC-IMPL-FR04-04 |
| FR04 | 撤销角色三条守卫：不能撤自己（会把人锁在门外）、`USER` 是基础身份不可撤（禁止登录应走停用）、账号没有该角色时按冲突拒绝且**不**提升认证版本 | 同上 | 同上 | AC-IMPL-FR04-04 |
| A08 | 认领材料下载不得用「404 不存在 / 403 无权」的差别泄露材料存在性 | `.../portal/application/PortalService.java#downloadEvidence`、`listEvidenceForClaim`（两种失败同码同文案） | `PortalEvidenceAccessTest`（8 项，含「拒绝发生在读文件之前」与「两种失败报文一致」） | AC-IMPL-FR05-04 |

## 9 采集任务停止与重试（SRS §6.2 六状态，2026-10-18 增补）

| 条款 | 要求要点 | 实现位置 | 测试与自检 | 验收编号 |
| --- | --- | --- | --- | --- |
| SRS §6.2 | 采集状态含 `STOPPED`：终止而不是暂停，保留检查点但**没有**恢复路径 | `.../ingestion/collect/domain/JobStatus.java`；迁移 `V9__job_stop_and_retry.sql`（ingestion）；`CollectJobService#stop` | `CollectJobServiceTest`（4 项：停止保检查点且不可恢复、已成功不可停止、重试不动检查点、只有 FAILED 可重试） | AC-IMPL-FR08-03 |
| FR08 | 人工重试只从 `FAILED` 出发，不动检查点与已抓条数；重试次数单独计数（`retry_count`）而不是复用退避计数 `consecutive_failures` | `CollectMapper#requeueFailedJob`；`CollectJob.retryCount` | 同上 | AC-IMPL-FR08-03 |

## 10 关键词热点（FR13，2026-10-18 增补）

| 条款 | 要求要点 | 实现位置 | 测试与自检 | 验收编号 |
| --- | --- | --- | --- | --- |
| FR13 | 只统计论文、只统计有发表年份的成果；专利/项目/获奖不参与热点排序 | `.../query/analytics/infrastructure/AnalyticsMapper.java#topicHotspots`（`a.type='PAPER'`、`publication_year IS NOT NULL`） | `services/query-service/src/test/.../KeywordHotspotTest`（10 项） | AC-IMPL-FR13-01 |
| FR13 | 领域为多对多，占比之和可超过 100%，必须随数据写明分母 | `AnalyticsService#keywordHotspots`（`sampleSize` + `shareOfSample` + `note`） | 同上「占比以窗口内全部公开论文为分母」 | AC-IMPL-FR13-01 |
| FR13 | 样本不足时明确「不构成热点结论」，不把排名当结论 | 同上（`sparse` + 分段 note） | 同上「样本不足 5 篇」「样本足够但没有领域标注」 | AC-IMPL-FR13-01 |
| 编码规范 §1.4 | 热点口径与总览不同，规则版本必须分开（`KEYWORD_HOTSPOT_V1` ≠ `ANALYTICS_V1`） | `AnalyticsService.HOTSPOT_RULE_VERSION` | 同上「规则版本与总览分开」 | — |
| FR13 | 计数相同时按领域规范词升序破并列，结果与数据加载顺序无关（可复现） | `AnalyticsMapper#topicHotspots`（`ORDER BY achievements DESC, lower(coalesce(...)) ASC`） | 同上（规则文本断言） | AC-IMPL-FR13-01 |

## 11 成果详情与来源清单（FR02，2026-10-18 增补）

| 条款 | 要求要点 | 实现位置 | 测试与自检 | 验收编号 |
| --- | --- | --- | --- | --- |
| FR02 | 成果详情要让读者能回到来源：来源名、记录号、许可范围、数据性质、来源更新时间、原站外链 | `PublicCatalogQueryService#sourceViews`；`frontend/src/views/AchievementDetailView.vue`「来源」面板 | 未建专门单元测试（如实标注）；由 `tests/api/smoke-core.sh` 与页面逐条核对 | AC-IMPL-FR02-02 |
| FR02 | 外链只取来源给的 `sourceUrl` 或 DOI 解析地址，**不拼** `base_url + record_id`（会 404）；来源未给地址时如实写「来源未提供可跳转地址」 | `AchievementMapper#findSourceRefs`（`COALESCE(NULLIF(payload->>'sourceUrl',''), 'https://doi.org/'||doi)`） | 同上 | AC-IMPL-FR02-02 |
| FR02 | 许可为 `REVOKED` 的来源不出现在公开来源清单里 | `PublicCatalogQueryService#sourceViews`（过滤非 `GRANTED`） | 探针 A2 的同一判据（公开导出不含） | AC-IMPL-FR02-02 |
| FR02 | 四类成果各自的关键字段不混用：专利（申请号/公开号）、项目（资助机构，**不展示经费**）、获奖（颁奖机构） | `AchievementDetailView.vue`（`patent`/`project`/`award` 分块） | 页面核对；类型缺失时只显示该类型对应的块 | AC-IMPL-FR02-01 |

## 12 规范与代码的开发顺序（SDD 证据）

| 环节 | 做法 | 可核对位置 |
| --- | --- | --- |
| 编码前先有规范 | 每项改动先关联 FR / AC-IMPL 编号与 SPEC 条款，再动代码；无编号的功能只登记待确认问题 | `docs/standards/编码规范.md` §10.1；本表各行的「SPEC 条款」列 |
| 规范纳入版本管理 | SPEC / SRS 在需求仓库随版本提交（V1.0 / V1.1），本仓库只引用编号 | 需求仓库 `docs/spec/`、`docs/srs/` |
| 规范评审记录存档 | 评审报告与决策登记在需求仓库 | 需求仓库 `docs/spec/spec-review-report.md`、`docs/srs/integration/decision-register-2026-10-12.md` |
| 规范—代码对应 | 本表；新功能或改口径时同步更新本表 | 本文件 |

## 13 未覆盖与待办

1. 检索排序的权重参数、相似度阈值仍为候选值，待 SPEC 联合评审后回填。
2. 本仓库目前没有 `core` 侧解析入库（Intake）的专门单元测试，该段规则由接口自检与代码审查覆盖；表中已逐行如实标注，不把接口自检写成单元测试结论。
3. 100 万规模已实测但**未达标**（见 `docs/ai/第三批-容量与恢复验证记录-2026-10-17.md`）；500 万规模与 300／1000 并发未执行。不得把「跑过 100 万」写成「容量验证通过」。
4. 采集端到端（真实服务 + 替身来源）未跑：本轮只验证了适配器与执行服务两层，见 §6.1。
5. 来源许可恢复（`RUNNING_RESTORE`）只在探针里跑过小样本，百万级恢复的耗时与中断续跑**未实测**；见 ADR-001 §8.4。
6. 「登记为停用 + 许可未审核 → 直接恢复采集」这条路径**未堵住**：许可审核状态在采集侧，核心服务看不到。详见 ADR-001 §8.4 第 1 条，属跨服务契约变更，需单独评审。
7. 详情页「来源清单」没有单元测试，只有接口自检与页面核对；这是本轮明确留下的缺口，不写成「已覆盖」。
