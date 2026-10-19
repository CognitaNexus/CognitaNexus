# AI 使用日志

| 项目 | 内容 |
| --- | --- |
| 版本 | V1.0，2026-10-17 |
| 对应任务书要求 | 第三节（三）代码智能体深度应用：5 类任务的使用记录、能力边界与失效模式 |
| 记录范围 | 本仓库 v0.2 实现阶段（提交 `8fc1c41` 起，2026-10-16 至 2026-10-18；其中 2026-10-17 `0aad3b0` 之后的批次仍在工作区未提交，改动清单可用 `git status --porcelain` 核对） |
| 配套 | [AI生成-人工修正案例](AI生成-人工修正案例.md)（9 例）、[提示词资产清单](提示词资产清单.md)、[工程协作与AI协作规范](../standards/工程协作与AI协作规范.md) |

## 1 记录口径与边界

这份日志记什么、不记什么，先写在前面，因为它决定了这份日志的可信度。

1. **只记可核对的产出。** 每一条给出提交号或文件路径，读者可以自行 `git show` 或打开文件核对。没有产出的任务不登记。
2. **不记对话原文。** 开发过程中的会话不随仓库分发，也没有逐轮归档。任何「AI 当时说过什么」的复述都无法核对，因此不写。日志记录的是「用哪份模板、喂了什么上下文、产出了什么、人工在哪停下」。
3. **不写调用次数。** 没有埋点，就不写「使用 37 次」。
4. **失败与偏离照写。** 第 4 节专门记 AI 判断错、漏、以及「看起来完成了其实没有」的形态，附真实例子。
5. **时间以提交时间为准**，不按会话时间虚构。
6. **「完成」的定义是变量而非感觉**：有脚本输出或数字才算完成，否则一律写「未执行」。

## 2 五类任务的使用日志

### 2.1 项目级代码生成

| 编号 | 时间 | 依据（模板 / 上下文） | 产出（可核对） | 人工检查点与结论 |
| --- | --- | --- | --- | --- |
| L-01 | 2026-10-16 15:31（`8fc1c41`） | implement-feature；AGENTS.md、[编码规范](../standards/编码规范.md)、[协作规范](../standards/工程协作与AI协作规范.md)、架构说明书 V0.2 | 仓库骨架与协作约定：AGENTS.md、docs 结构、四模块 Maven 结构、契约目录 | 人工确认了阶段边界：架构阶段不产出 DDL 与可运行代码，构件资产等实现阶段授权后再建；因此首次提交只有规范与骨架，没有表结构与业务代码 |
| L-02 | 2026-10-16 22:03（`f72972a`） | implement-feature；架构说明书开发视图、编码规范 §2–§8、核心 SPEC 条款 | v0.2 全栈实现：三服务（core / ingestion / query）、40 个 Flyway 迁移、前端 SPA、容器化与端到端验证 | 人工检查点设在迁移与契约：新增迁移版本号只增不改，事件 schema 先定再并行写生产者与消费者。检查结果：事件类型 7 种、变更种类 3 种先固化为常量，两个服务各自声明而不是共享 Java 模块（架构禁止服务间业务模块依赖） |
| L-03 | 2026-10-16 23:20（`cf46deb`） | implement-feature；编码规范 §7（前端）、既有详情页组件 | 详情页字段对齐、引用导出、响应式布局与键盘可达性 | 人工确认「未知字段必须有永不缺失的标签」这条在详情页落地：年份用 `yearKnown` + `publicationYearLabel`，引用量用 `citationNote` 说明来源口径 |
| L-04 | 2026-10-17 12:01（`675d46b`） | implement-feature；SPEC §6（消歧与关系）、迁移 `V9__candidate_rebased_status.sql` | FR07 参与关系处置：`ContributionRelationService`（本人确认 AUTO→SELF_CONFIRMED、异议→DISPUTED、撤回→AUTO；治理端 EXCLUDE / CONFIRM / RESTORE）、两页前端、`tests/api/smoke-relations.py` 14 项 | 人工检查点设在状态前置条件：每条迁移都要求当前状态匹配，受影响 0 行按 `VERSION_CONFLICT` 报错，不允许「静默跳过」。结果为 14 项自检全过（含未登录 401、无理由异议两条负向） |
| L-05 | 2026-10-17 12:01（`675d46b`） | continue-code；既有 `CatalogChangePublisher` | 事件载荷装配收敛到唯一装配点，`SourceIntakeService` 与 `AdminCatalogService` 改为薄转发 | 人工发现「自动发布事件带论文指标、人工确认发布事件不带」的字段集分叉，判定为不变量失守而非风格问题。这是 C-09 的直接前因 |
| L-06 | 2026-10-17 12:01（`675d46b`） | fix-defect；运行时异常栈 | 文件导入专利链路修复：`AchievementMapper.insertPatent` 与 `PatentRow` 改用 `String[]`（`List` 触发 ClassCastException），`application.yml` 注册 `type-handlers-package`，专利详情补 `publicationDate` | 人工确认了红线行为：SIMULATED 专利即使人工确认也被拒（`BUSINESS_RULE_VIOLATION`）——这是正确行为，不允许为「演示方便」放开 |
| L-07 | 2026-10-17 12:26（`b6638e1`） | implement-feature；数据质量需求项、既有异常台账 | `DataQualityScanService`（DQ_SCAN_V1）7 类检测项，只读探测、只登记/复核异常 | 人工检查点设在幂等：同一问题按 `dedupeKey` 只占一条 OPEN 待办，二轮跑 `created=0 refreshed=3`。判定为可接受 |
| L-08 | 2026-10-17 15:18（`0aad3b0`） | implement-feature；契约目录、既有自检脚本 | 新增 6 套自检（contract 12 / perf 17 / projection-consistency 15 / merge-aftermath 8 / redlines 24 / portal 27），扩展 3 套 | 人工检查点设在「测试能不能红」：每套脚本至少一条故意构造的失败路径（越权、受限实体、非法参数），并在性能脚本文件头写明「本结果不得用于宣称验收达标」 |
| L-27 | 2026-10-18（12 项补齐批次） | implement-feature；SRS V1.1 差距盘点、ADR-001 方案 A、既有自检脚本 | 按设计书补齐 12 项：详情页四类成果字段与来源清单（FR02）、采集任务停止/重试与 `STOPPED` 终态（FR08）、统一待办入口（FR14）、角色撤销与认证版本提升（FR04）、关键词热点接口与页面（FR13）、材料访问控制收敛（A08）、部署与备份恢复脚本及手册、`smoke-fr-additions.py` 27 项 | 人工检查点：每项改动都能指到 FR/AC-IMPL 编号；方案 A 的实现记录只写实测过的内容，未堵住的路径（禁用登记 → 直接启用采集）如实写「能绕过」；热点页「18 个领域」这类数字与真实库核对后才写进报告 |
| L-34 | 2026-10-18（第五批：功能补齐） | implement-feature；SRS 缺口清单、既有异常台账与治理链路 | 公众纠错入口：`PublicCorrectionService`（9 个字段枚举、三层限流、去重键、合并跳转、指纹）、`POST /api/v1/public/achievements/{id}/correction-reports`、详情页「数据有问题？」面板、治理页展示报告原文与累计次数、待办 `correctionsOpen`；`PublicCorrectionServiceTest` 10 项 + `PublicCorrectionControllerTest` 5 项 + `smoke-corrections.py` 33 项 | 人工定的三条边界：① 匿名报告只进 `gov_anomaly`，核实后走既有修订/裁决路径，**不得直接改字段**；② 限流先于存在性判断，否则可用状态码差异探测受限成果；③ 单成果配额键不拼来源，否则「同来源×同成果」会让来源级窗口永远先触发、规则永不生效 |
| L-35 | 2026-10-18（第五批） | implement-feature；既有检索导出、账户与门户链路 | CSV 转义单点化到共享内核 `Csv`（RFC 4180、CRLF、null→空、UTF-8 BOM），检索导出与「我的成果」导出共用；新增 `GET /api/v1/account/contributions/export`（11 列、含未确认行、不分页、超 5000 行拒绝）；`CsvTest` 5 项 + `smoke-portal-export.py` 15 项 | 人工要求导出**拒绝而非截断**：缺行的成果清单可能一年后才发现；并要求断言里核对「行数=库中关系数」，不只看表头 |
| L-36 | 2026-10-18（第五批） | implement-feature；学者档案页、既有关系表 | 学者指标只给计数：`scholarMetricsOf`（`worksTotal` / `worksWithYear` / `worksWithoutYear` / `byYear` / `topCoauthors` 10 位 / `basis` / `note`），前端「年度分布」「主要合作者」两个 panel；`smoke-scholar-metrics.py` 24 项逐项与 SQL 对账 | 人工否掉了 h-index / 影响因子 / 综合评分：没有权威数据源时派生指标等于编造。合作者要求两端关系都合格、共同作学者未被合并，并特意选「2 合格 + 16 待核验」的学者验证同名不串账 |

**关于规模。** 当前仓库统计（2026-10-18）：Java 主代码 253 个文件、38518 行；测试代码 65 个文件、14742 行；前端 27 个 `.vue`；Flyway 迁移 48 个（`find services -name 'V*__*.sql' | wc -l`）。这是截至该日期的存量，不是单次生成量，也不用于证明质量。

### 2.2 代码续写

| 编号 | 时间 | 依据 | 产出 | 人工检查点 |
| --- | --- | --- | --- | --- |
| L-09 | 2026-10-17 12:01（`675d46b`） | continue-code；`GovernanceService` 相邻方法、`GovernanceMapper` 既有 SQL 风格 | `rebaseCandidate`（候选一端被别的治理动作并走时，改判到最终实体）、`V9__candidate_rebased_status.sql`、`POST .../candidates/{id}/rebase`、`ops/local/backfill-merged-pair-candidates.sh` | 人工要求「只续写不重构」，改动范围限在候选裁决链路；回溯脚本默认只预演，`--apply` 才写。判据窄：只匹配「候选两端恰好是某条 MERGE 台账的 survivor/merged」 |
| L-10 | 2026-10-17 12:26（`b6638e1`） | continue-code；既有巡检框架与异常登记 | 在既有框架内补 7 类检测项，含两类逐条检测与五类聚合检测 | 人工要求聚合类检测必须写清「计数归零时如何关闭待办」，否则待办只增不减。实现为自动关闭并逐条写审计 |
| L-11 | 2026-10-17 15:18（`0aad3b0`） | continue-code；既有 `AchievementMapper` 行类型 | 治理工作台两端状态从「登记时快照」改为实时批量读取（`statesOf`） | 人工判定：evidence 是登记那一刻的结论，用它渲染会把「当时的结论」当成「现在的事实」。改为实时读后，被并走的一端不会再显示为可选 |

### 2.3 代码解释

| 编号 | 时间 | 依据 | 产出 | 人工检查点 |
| --- | --- | --- | --- | --- |
| L-12 | 2026-10-17（`675d46b`，脚本入库） | explain-code；`services/*/pom.xml`、JaCoCo 插件文档 | 解释「为什么门禁会匹配空集合而一律通过」：`element=BUNDLE` 时 `includes` 比的是 bundle 名；`element=PACKAGE` 时 `包名.*` 仍匹配不到包本身（`*` 译成 `.*`，模式里的「.」是任意字符）。结论落到 `ops/verify-coverage-gates.sh` 文件头，并写进反证脚本 | 人工要求把解释变成可执行反证：把阈值提到 1.01，每条门禁都必须失败，否则就是假门禁。解释本身不作为验收 |
| L-13 | 2026-10-17（`0aad3b0` 前后） | explain-code；`AchievementRow` 与各查询 | 解释 MyBatis 构造器自动映射把「结果集缺列」当硬失败而不是可空字段，因此共用行类型不能加只有个别查询才有的列 | 人工据此把个别查询拆出独立行类型（`SimilarTitleRow` 等），并把结论写进项目记忆与相关行类型的注释；编码规范正文未新增条款，因此这里只登记为工程约定 |
| L-14 | 2026-10-17 15:18（`0aad3b0`） | explain-code；`AdminCatalogService` 注释与三个装配出口 | 得出「注释声称字段逐字一致而代码没做到」的结论，并给出三个出口的实际差异 | 人工判定注释必须改，而不是改断言迁就注释（见 C-09） |

### 2.4 单元测试

| 编号 | 时间 | 依据 | 产出 | 人工检查点 |
| --- | --- | --- | --- | --- |
| L-15 | 2026-10-16 至 2026-10-17（`f72972a`…`0aad3b0`） | write-unit-tests；核心模块与治理裁决逻辑 | 41 个测试类；`0aad3b0` 时点的用例数记录为 473（shared 9 / core 291 / ingestion 82 / query 91）。用例以行为命名，`@DisplayName` 写「这条用例守住的是什么」 | 人工要求反例必须真的会红：写完把实现改坏再跑一次。门禁反证脚本是同一思路 |
| L-16 | 2026-10-17（`0aad3b0` 之后、本轮补齐） | write-unit-tests；`GovernanceRepositoryAdapter` 的质量探测与异常去重出口 | `GovernanceDataQualityAdapterTest` 13 项：逐条钉住「哪个出口走哪个 SQL」、上限原样下传、聚合计数保持 long、查不到返回 empty、探测异常不被吞成空集合。加完后共 42 个测试类、487 项（core 305） | 本次检查点：先 `mvn verify` 发现 `governance.infrastructure` 行覆盖率 73.20% 低于 80%，判定为真实缺口（新增巡检适配器只有接口自检、没有单测），补测后升到 99.48%，门禁恢复通过 |
| L-28 | 2026-10-18（12 项补齐批次） | write-unit-tests；权限、待办、热点、材料访问四处新代码 | 6 个测试类 41 项：`AccountAdminServiceTest` 14（角色变更必须提升认证版本）、`AdminCatalogTodosTest` 4（`null` 与 0 的区别）、`PortalEvidenceAccessTest` 8（404/403 不得泄露存在性）、`KeywordHotspotTest` 11（口径随数据返回）、`PublicAnalyticsControllerTest` +2、`CollectJobServiceTest` 4。总计 559 项 | 门禁再次起作用：加完 `GET /keywords` 后 `mvn verify` 在 `analytics.api` 覆盖率 66% 上失败（控制器无用例），补 2 项后恢复 100% |

### 2.5 调试自愈（缺陷修复）

| 编号 | 时间 | 依据 | 产出 | 人工检查点 |
| --- | --- | --- | --- | --- |
| L-17 | 2026-10-17 15:18（`0aad3b0`） | fix-defect；越权反例断言 | 治理读接口 16 个方法补 `Capabilities.require(actor, CAP_GOVERNANCE_MANAGE)`（C-01） | 人工判定「读比写安全」是错的：治理候选本身是敏感数据 |
| L-18 | 2026-10-17 15:18（`0aad3b0`） | fix-defect；`smoke-merge-aftermath.py` 旧标识链路 | 重定向改为「先解析终点 → 校验终点资格 → 组装完整详情 → 追加跳转说明」（C-02） | 人工要求「不存在」与「不可见」对外不可区分，避免状态码探测 |
| L-19 | 2026-10-17 15:18（`0aad3b0`） | fix-defect；`smoke-projection-consistency.py` 边数差 37492 vs 37388 | `ProjectionApplier` 对不可见成果写空集合并删边（C-03） | 人工判定以公开读路径口径为准，不以「哪个数字更接近真值」为准 |
| L-20 | 2026-10-17 15:18（`0aad3b0`） | fix-defect；`smoke-perf.py` 逐链路 P50/P95 | 三服务 Hikari 加 `connection-init-sql: SET plan_cache_mode = force_custom_plan`（C-04）；行构造器 `count(DISTINCT (a,b))` 改子查询（C-05） | 人工要求给出修复前后两组数字（领域洞察 5111ms→640ms、网络 2 跳 7432ms→787ms），不接受「变快了」 |
| L-21 | 2026-10-17 15:18（`0aad3b0`） | fix-defect；`smoke-contract.py` 错误码矩阵 | query 与 ingestion 补 `MethodArgumentTypeMismatchException`（C-06）；`SearchView.vue` 判空同时挡 `undefined`（C-07） | 人工判定前端崩溃的根因是「值未知」与「字段不存在」在报文上无法区分，因此两处都要改 |
| L-22 | 2026-10-17 15:18（`0aad3b0`） | fix-defect；`smoke-portal.py` 撤销后恢复路径 | `confirmPublication` 放行 `WITHDRAWN` 重新确认（C-08） | 人工判定撤销不是终态：治理端需要能纠正自己的误操作 |
| L-23 | 2026-10-17 12:26（`b6638e1`） | fix-defect；浏览器连点两次巡检一成一败 | 前端 `http.ts` 每次写请求前重取 CSRF 令牌 + 写请求串行 + 403 重取重试一次 | 人工判定这是「令牌是一次性的」与「前端只读 Cookie 旧值」之间的契约错配，不是偶发网络问题 |
| L-29 | 2026-10-18 | fix-defect；材料下载的 404/403 差别 | `PortalService.downloadEvidence` 把「不存在」与「无权」收敛成同码同文案（原实现先查材料再判权限，未授权者可凭状态码差别反推材料存在性）；拒绝发生在读文件之前 | 人工判定运维排查少一条区分信息是可接受代价，这类纠纷应走审计而不是对外错误码 |
| L-30 | 2026-10-18 | fix-defect；`CoreSchemaInvariantsIT` 集成测试失败 | 期望迁移清单从写死的 `V1..V10` 改为现读 `classpath:db/migration` 的文件清单——写死清单在新增 V11 后必红，且与用例原意（防跳号/防手工改库）不符 | 人工判定「补一个 11」是治标：下一次加 V12 还会红 |
| L-31 | 2026-10-18 | fix-defect；`smoke-fr-additions.py` 热点页数字 | `topicsWithKeywords` 原来取返回行数，`limit=2` 时会把「18 个领域有标注」说成「只有 2 个」；改为单独 count 查询并新增 `returnedTopics`，前端分开显示 | 人工判定这类「数字在默认参数下恰好正确」的错误最值得用接口自检钉住 |
| L-32 | 2026-10-18 | fix-defect；逐页截图 390px 溢出 | 治理台宽表格在手机宽度下把页面撑出 149px 横向溢出；窄屏媒体查询里让 `.panel-body` 横向滚动，复测 0 溢出 | 人工判定压缩列宽会破坏「两端标题」的可读性，滚动是正确取舍 |
| L-37 | 2026-10-18（第五批） | fix-defect；`smoke-portal-export.py` 无授权导出返回 401 而不是 403 | core 与 query 两侧 `GlobalExceptionHandler` 从返回 `ResponseEntity` 改为 `void` + `ObjectMapper` 直写：`@RestControllerAdvice` 返回实体时 Spring 按 `Accept` 选转换器，`Accept: text/csv` 下没有转换器能产出 `ApiResponse` → 406 → ERROR 派发 → 认证过滤器不处理 ERROR 派发 → 客户端看到「请先登录」，真实原因（403）被顶掉 | 人工判定这是跨切面缺陷而非单个接口问题：任何下载类接口都会中招，因此两个服务一起改，并把故障链写进类注释 |
| L-38 | 2026-10-18（第五批） | fix-defect；`PublicCorrectionServiceTest.rateLimitsPerAchievement` 全量 verify 失败 | 单成果配额键原来拼了来源地址，变成「同来源×同成果」，来源级窗口（5 次/10 分钟）永远先触发，20 次/24 小时的规则永不生效；改为 `ach|<achievementId>` 全局计数 | 人工要求把代价写进注释：全局配额封住慢速滴灌，但也意味着单个成果的配额可被他人恶意消耗。配置类规则写完必须反证「改了它行为真的变」 |

### 2.6 代码审查与文档

| 编号 | 时间 | 依据 | 产出 | 人工检查点 |
| --- | --- | --- | --- | --- |
| L-24 | 2026-10-17 15:18（`0aad3b0`） | review-diff；`git diff` 全量 | 提交前自审：格式、判空、注释与代码一致性、断言是否会红 | 审查结论见 [审查记录-AI审查与人工审查对比](审查记录-AI审查与人工审查对比.md)；其中 C-01 与 C-07 是 AI 审查漏报、由测试与人工补上的 |
| L-25 | 2026-10-17 15:22（未提交时点） | 交接包；本轮全部状态 | `docs/ai/handover-prompt.md`：一份自包含的接手提示词 | 人工要求「当前状态」一节每次交接前更新，并把已修缺陷作为案例素材而不是「成绩清单」 |
| L-26 | 2026-10-17 15:50 前后 | 本组文档；任务书第三节六条要求 | 补齐 `docs/ai/` 五份证据文件（AI 使用日志、修正案例、测试与覆盖率报告、审查记录、提示词资产清单） | 人工要求 README 里每个链接都指到真实存在的文件；不写「已完成」而无对应文件 |
| L-33 | 2026-10-18（12 项补齐批次收尾） | 本轮全部状态；ADR-001、SPEC 对应表、测试报告 | ADR-001 状态改为 Accepted 并新增 §8 实现记录（含探针原样输出与 5 条未解决项）；SPEC 对应表新增 §7–§11（来源许可、待办与账户安全、停止重试、热点、来源清单）；测试报告升 V1.1（559 单元 + 29 集成 + 六套自检 + 探针 + 逐页截图）；新增 `docs/ops/部署与运维手册.md`、`ops/backup/pg-backup.sh`、`ops/restore/pg-restore.sh`、`ops/verify/compose-smoke.sh` | 人工检查点：文档里每个数字都附获取命令；探针输出原样粘贴不删改；「已解决/未解决/未执行」分开写，不把上一轮的旧结论搬来当本轮的 |
| L-39 | 2026-10-18（第五批收尾） | review-diff；`docs/ai/` 全量、`git status` | SPEC 对应表新增 §12 公众纠错、§13 学者指标、§14 CSV 导出统一转义；测试报告追加 §14（全量计数、覆盖率被拦记录、十个自检脚本逐项结果、三个真实缺陷、未覆盖项）；交接提示词与 AI 使用日志按本批实测数字改写 | 人工检查点：上一轮交接文档里「无集成测试」「tests/perf 未建」「存量 26 个 spotless 违规」三条已被后续批次推翻，本轮逐条改成现状（集成 29 项、隔离压测脚本已在仓库、spotless 存量 9 个文件），而不是在旧结论后面再追加一段 |
| L-40 | 2026-10-18（第七批） | implement-feature；FR02 引用导出 | 新增 `CitationFormat` / `CitationAuthor` / `CitationSubject` / `CitationWarning` / `CitationFormatter`（纯函数）、`CatalogCitationService` / `CitationBundle`、`PublicCitationController`（两个入口 + 手工写响应）；`PublicCatalogQueryService` 抽出 `requirePublicTarget` 供引用复用 | 人工检查点：把「公开资格判定」抽出来而不是复制一份。理由写在方法注释里——重定向跳终点 + 资格作用在终点实体这两条出过问题，两处各写一份，修一处漏一处就是公开读旁路 |
| L-41 | 2026-10-18（第七批） | review-diff；`frontend/src/composables/useCitation.ts`、`frontend/tests/useCitation.test.ts` | 发现前端已有一份四种格式的渲染实现，逐条核对后确认四个缺陷：`%` 未转义（BibTeX 注释起始符，题名含 `%` 会截断）、无作者时 `note` 字段写两次、引用键取「姓名最后一个空格段」（中文取到「三」）、作者列表把 `PENDING_VERIFY` 的署名者删掉。改由服务端生成，前端缩成状态装配 | 人工要求把第 3 条的旧测试原样记进案例库：那份测试写着「按实现的实际行为断言，防止未来静默改变格式」——测试固化了缺陷。**测试只证明代码没变，不证明行为是对的** |
| L-42 | 2026-10-18（第八批） | implement-feature；FR02 批量导出（ADR-004 §2.8/§2.9） | 新增 `CitationCollectionFormatter`（`uniqueCiteKeys` 撞键加后缀 + `document` 四格式成篇）、`CitationBatch`、`CatalogCitationService#exportBatch`、`PublicCitationExportController`（两个 GET 入口）、`CitationViews`（告警报文统一拼装）；前端 `useCitationBatch` + 检索页勾选与预览面板；`CitationFormatter#format(subject, format, citeKey)` 使改过的键真的写进条目 | 本轮判断（用户已授权连续推进，未逐项确认）：撞键必须改键**并且回告警**，不只改键。理由是读者按旧键去自己的 .bib 里找会找不到——静默改键比撞键更难发现 |
| L-43 | 2026-10-18（第八批） | real-test；`tests/api/smoke-citation-batch.py` 首轮 37 项 25 失败 | 真实自检抓到两个问题：① 预览原设计走 POST，匿名调用被 CSRF 拒绝（403「无权执行该操作」）——平台的一次性令牌在校验通过后立即失效，让一次「看一眼会得到什么」的读操作消耗写令牌，会让紧接着的下载莫名 403；改为 GET，两个入口同形。② 同时勾选「已合并的原记录」与「幸存记录」时，两条解析成同一实体，文件里出现两份相同引用；改为按解析后的实体分组去重，优先保留未经重定向的 id，被合并的那条记为 `DUPLICATE_TARGET` | 本轮判断：去重时**记成重复项的是旧记录而不是幸存记录**。初版按出现顺序去重，「那条活着的被跳过了」读起来是错的；这条只有把真实库里的合并链拿出来跑才会暴露，单元测试的假数据两条 id 顺序恰好相反 |
| L-44 | 2026-10-18（第九批） | test-coverage；`services/query-service` 的 JaCoCo 报告与 `ops/verify-coverage-gates.sh` | 发现编码规范 §8.3 点名的 `query/discovery` **从未有过覆盖率门禁**（pom 里只有 analytics 一条），实测 api 0%、application 38.36%、infrastructure 60%。新增 `jacoco-check-discovery`（沿用 `element=PACKAGE` + `包名*` 的写法），补 `SearchServiceSearchTest`（17 项）与 `PublicSearchControllerTest`（9 项），三包达 100%；把 execution id 登记进自检脚本的 `GATES` 并重做 1.01 反证（四条均失败） | 本轮判断：门禁与它的自检清单必须同时登记。此前「三条门禁都通过」是实话，但三条之外的模块没有任何约束——**没被登记进 GATES 的模块，通过与否没人知道** |
| L-45 | 2026-10-18（第九批） | repo-hygiene；`tests/ui/` | 把仅存在于 node workspace 的 `cn-shoot.mjs`（887 行）与 `cn-batch-citation.mjs`（144 行）收进仓库为 `tests/ui/`，硬编码的浏览器路径、服务地址、截图目录改成 `CN_*` 环境变量，依赖独立成 `tests/ui/package.json`（只 `puppeteer-core`，不污染前端依赖），并写了 README 说明前置、可覆盖项与「这是人工自检不是 CI 门禁」；从仓库目录重跑：批量导出 13/13、逐页断言全绿 | 本轮判断：脚本入库后必须**从新位置重跑一遍**再写结论。只把文件搬进仓库不构成「可复现」——路径一改就可能因为取不到截图目录或依赖解析失败而静默降级 |

## 3 人工检查点都在哪里

检查点不是每一步都问，而是设在不可逆或代价高的位置（协作规范 §3）。本阶段的实际情况：

| 检查点 | 触发次数 | 实际停下做了什么决定 |
| --- | --- | --- |
| 不可逆写操作（合并、改判） | 每次涉及合并逻辑的改动 | 合并必须留台账、必须把同一对之间的 OPEN 候选一并裁决、被并走的一端不能当幸存实体。三条都是人工加的，AI 初版只做了「迁数据」 |
| 数据库迁移 | 40 个迁移 | 只增不改；约束变更先核对既有行是否仍合法（`V9` 加状态取值时逐条核对了既有候选的 status） |
| 跨服务契约变更 | 事件类型、字段集、错误码 | 契约先定再并行。C-06 与 C-09 都是这条检查点抓出来的 |
| 长任务分段 | 每次跨多个模块的改动 | 每段结束汇报：改动范围、已跑命令与输出、未执行项。未确认不推进下一段 |
| 验收数字 | 每次声称通过之前 | 只认脚本输出；本轮 `mvn verify` 一次失败（覆盖率门禁 73.20%）就是检查点起作用的例子——按旧印象写「门禁通过」会直接写错 |

## 4 能力边界与失效模式

以下每条都有实例，不是抽象描述。

**AI 稳定做得到的事。** 在给定契约与判据后，跨层实现同一件事而不遗漏分支（L-02、L-04）；把「重复三次的代码」抽成一个装配点（L-05）；把自然语言判据翻译成断言（L-15）；被指出一次根因后，在同模块其它出口上做同样的修正（C-01 的 16 个方法一次改完）；按既定格式生成大量结构性文档与迁移脚本。

**AI 稳定做不到或做不可靠的事。**

1. **自己发现「旁路」不存在。** 它按功能主流程组织代码，重定向、读接口、导出、批量入口这些旁路不在它的流程图上。C-01 与 C-02 都是旁路失守。
2. **判断两条路径是否真的等价。** 增量与重建、三个服务的错误语义，各自单看都对。C-03、C-06。
3. **区分语义等价与执行等价。** C-04、C-05 都是「写法正确但跑起来差一个数量级」。
4. **选择有代表性的测试样本。** C-07 最典型：脚本一直用 `?q=graph` 进检索页，恰好绕开必现的崩溃。AI 选样例倾向「有内容、典型」的输入，天然避开空集合与默认入口。
5. **判断一个状态是不是终态。** C-08：撤销被当成最终决定。
6. **为自己写的声明负责。** C-09：注释声称的不变量代码没做到；这类失真不会被编译、测试或运行发现。
7. **在缺乏外部断言时判断「做完了」。** 没有脚本输出时，AI 只能用自己的输出证明自己。因此本仓库要求验收必须指到脚本与数字。
8. **把配置项当成已生效。** `max-backoff-seconds` 曾被位移上限吃掉（见 [提示词资产清单](提示词资产清单.md) 对 fix-defect 模板的评估），代码与配置都在，行为却不生效。

**由此形成的使用原则。**

- 每次任务先给「现状证据」（文件路径 + 当前行为），再谈改动。没有基线的修复无法证明。
- 判据写成可执行断言，不写成形容词。「快」「一致」「安全」都要有对应的脚本或数字。
- 涉及状态机、权限、公开资格、数据性质的判断，AI 只给建议，结论由人下（Human Zone）。
- 凡声明「一致/不变/总是」，要么给断言，要么删句子。
