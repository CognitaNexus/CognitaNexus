# 接手提示词（可直接整段粘贴给下一个 AI）

> 本文件是交接用的一次性上下文包。用法：把「提示词正文」整段复制给新的 AI 会话，
> 它即可在不需要历史对话的情况下接着干活。维护要求：每次交接前更新「当前状态」一节。

---

## 提示词正文

你在接手一个已经在跑的工程仓库：`D:/code/academicplatform`，分支 `feat/v0.2-implementation`，
最近提交 `0aad3b0`（端到端测试 T1–T11 全部跑通）。

### 一、这是什么

CognitaNexus 学术成果分享平台的工程实现仓库，课程项目（北航软院「软件系统分析与设计」）。
需求基线在**另一个仓库** `https://github.com/CognitaNexus/CognitaNexus`（本机副本
`D:/code/文档/CognitaNexus-main (1)/CognitaNexus-main`）。**本仓库不复制需求正文、不改写需求语义。**

架构：三服务分库 + 前端，共四个进程。

| 服务 | 端口 | 库 | 职责 |
|---|---|---|---|
| core-service | 8081 | core_db | 权威数据、治理、合并、发布、审计、治理巡检 |
| ingestion-service | 8082 | ingestion_db | 来源登记、采集任务、抓取、暂存、交接批次 |
| query-service | 8083 | query_db | 读投影、公开检索、统计、合作网络、领域洞察 |
| frontend | 5173 | — | Vue 3 SPA，17 条路由 |

全链路：来源登记 → 采集任务 → 抓取 → 暂存 → 交接批次 → core 清洗去重入库 →
自动/人工发布 → outbox 事件 → query 投影 → 公开检索/统计/详情。

技术栈：Java 21 运行时 / Java 17 语言级别、Spring Boot 3.5、MyBatis-Plus、PostgreSQL 16、Flyway；
Vue 3 + TypeScript + Vite + Pinia。**刻意不引入** Elasticsearch、图数据库、消息队列、Kubernetes。

### 二、硬性约束（违反即返工）

1. **所有功能必须是真的**。不许用假数据、假接口、写死的返回值应付；SIMULATED 数据只能进演示口径，
   不得进默认现实视图。
2. **绝不声称未验证的事**。性能 P95、覆盖率、一致性指标，没实测就写「未执行 / 待签认」，
   不得写 PASS 或已冻结。验收数字必须能指到具体脚本与输出。
3. **不碰休眠文件**。禁止关闭休眠 / 快速启动，不得触碰 `hiberfil.sys` 这类系统文件。
4. **阶段边界**：架构阶段不产出 DDL、完整 API 定义、可运行代码；这些属构件设计阶段，需单独授权。
5. **业务红线**（架构与规范里反复强调，写代码前先看）：
   - 来源原值不可被人工修订覆盖；
   - 自动更新不得覆盖人工审核值；
   - 相似标题不得直接合并；
   - 同名不得直接合并学者（不同名且无共同稳定标识更不行）；
   - 待核验 / 争议 / 已排除的关系不得建边，也不得显示为确定合作；
   - 前端隐藏入口不构成授权（授权必须服务端校验）；
   - 无稳定标识不自动发布；模拟数据不得冒充真实。
6. **输出风格**：中文；分步骤、最简；不用表格回答计算/推导类问题；不用成对反引号包裹普通词汇
   （工程文档内标记代码标识可以）。

### 三、当前状态（2026-10-17 22:40）

任务清单 47 项：**47 项全部完成**。#22（Agentic Coding 证据文档）已补齐，
`docs/ai/` 下六类证据全部落为真实文件，README 的每个链接都指到存在的文件。

**此后开始执行《老师指导对照检查与 WorkBuddy 改进建议》的第一批任务**（WB-01 / 02 / 03 / 07，
WB-05 只交方案）。这批改动**未提交**，工作区有未提交修改。

已完成且经可执行验证的部分（括号内为实测时间与命令）：

- 单元测试 **490 项全绿**：shared-kernel 9 / core-service 305 / ingestion-service 82 / query-service 94
  （`./mvnw -B -ntp verify -Dspotless.check.skip=true` exit 0，2026-10-17 17:15）。
- 覆盖率门禁：8 个受门禁包 97.36% / 94.64% / 99.48% / 100% / 87.39% / 94.25% / 100% / 85.71%，
  合计行覆盖 95.93%（1556/1622）、分支 80.00%（352/440）；三条门禁在阈值提到 1.01 时全部失败，
  证明不是空集合恒真（`bash ops/verify-coverage-gates.sh --skip-build`，16:00 实测）。
- 接口与端到端自检（`tests/api/`，本轮重跑的真实结果）：
  `smoke-core.sh` 21、`smoke-ingestion.sh` 41、`smoke-query.sh` 90、`smoke-contract.py` 12、
  `smoke-redlines.py` 24、`smoke-projection-consistency.py` 15，均 0 失败。
  本轮新增两套并实测通过：`smoke-merge-visibility.py` 15（WB-01／T01）、
  `smoke-claim-evidence.py` 27（WB-03 材料闭环）。
  其余脚本（relations 14 / dq-scan 19 / portal 27 / perf 17 / merge-aftermath 8 / merge-preview）
  本轮未重跑，项数取自脚本自身断言，见 `docs/ai/测试与覆盖率报告.md` §4 的状态列。
- 数据规模（17:20 读数）：`core_achievement` 15313 条（公开且生效 9638）、投影当前代 9638、
  有公开成果的学者 30298。来源为 OpenAlex 等真实来源。注意 `qry_achievement` 跨代累加为 53171，
  **不能当作现库规模**。
- 前端：57 张截图（含新增的默认检索入口、学者作用域、带目标学者的门户页），
  0 控制台错误 / 0 失败请求 / 24 次窄屏无横向溢出（17:22 实测）。
- 证据文档：`docs/ai/` 下 AI 使用日志、修正案例（9 例）、测试与覆盖率报告、审查记录、提示词资产清单。

**本轮修掉的两个真实缺口**：

1. `mvn verify` 曾失败——core 的 `governance/infrastructure` 行覆盖率 73.20%，原因是数据质量巡检
   新增的适配器方法只有接口自检、没有单元测试。补 `GovernanceDataQualityAdapterTest`（13 项）后
   升到 99.48%，verify 恢复通过。
2. 第一版 `smoke-merge-visibility.py` 的夹具直接插 `PUBLIC/EFFECTIVE` 成果，被库内延迟约束
   触发器拒绝（「可发布成果必须恰好有一个类型扩展」），而 `psql` 未加 `ON_ERROR_STOP=1` 时
   退出码仍是 0，脚本「静默通过」。修法是夹具先以 `RESTRICTED/PENDING` 插入、补 `core_paper`、
   再改状态，并给所有 psql 调用加 `ON_ERROR_STOP=1`。**这类失败不会报错，只会给出假绿。**

**第一批任务的落地范围**（详见 `docs/ai/测试与覆盖率报告.md` §9）：

- WB-01：代码已在上一轮修复，本轮补真实接口验证（T01 四态 + 恢复 + 不存在），15/15。
- WB-02：检索页成果／学者双作用域、成果类型筛选、学者档案与认领入口、条件写 URL。
- WB-03：认领视图带材料元信息与 `canResubmit`／`canAttachEvidence`；申请人可按申请追加材料、
  原 `claimId` 重提；审核端可见材料清单并下载。
- WB-07：路由驱动检索、请求序号丢弃过期响应、版本失效最多自动重试一次、排序标签改后端枚举、
  关键词占位文案改为实际支持字段（原写「作者」但 SQL 不匹配作者）。
- 附带：`likePatternOf` 转义 LIKE 通配符；学者检索补 `queryVersion` 校验与非法排序拒绝。
- WB-05：只交方案 `docs/architecture/adr/ADR-001-来源暂停与撤销展示许可.md`（Proposed，代码未改）。

**随后执行了第二批任务（WB-06 / 09 / 10 / 11，含 WB-08 收尾）**，改动同样**未提交**。要点（详细记录见
`docs/ai/批次二验证记录-2026-10-17.md`，用例状态与命令在里面，不在本文重复）：

- 新建了一条不依赖 Docker 的 PostgreSQL 集成测试层：`ops/local/test-db.sh` 管第二个原生实例
  （端口 55432，数据目录在仓库内），Failsafe 跑 `*IT.java`；根 pom 的 `cn.it.skip` 默认 `true`，
  要跑集成测试必须显式 `-Dcn.it.skip=false`。本机 Docker/WSL2 被安全策略阻断，Testcontainers 不可用。
- 全量实测（2026-10-17 19:20 前后）：`./mvnw -B -ntp verify -Dcn.it.skip=false
  -Dfailsafe.failIfNoSpecifiedTests=false` exit 0；**单元 491**（9/305/82/95）＋**集成 20**（core 18 / query 2）；
  三条 JaCoCo 门禁通过；`spotless:check` 通过（格式化了 9 个存量违规文件）。
- WB-09：同 DOI 但证据矛盾（类型／年份／作者完全不相交／题名明显不同）现在登记 `METADATA_CONFLICT`
  异常，不采信矛盾字段、不刷新指标；并发首采用 `pg_advisory_xact_lock` 串行化。
- WB-10：白名单在解析前生效（四个出口都验证）；配置收紧只影响后续入库，**重放不能清洗历史字段**，
  历史违规字段只给只读诊断，未授权清库。说明见 `docs/ai/来源许可与字段白名单说明.md`。
- WB-06：`SearchService` 两个入口改为 `REPEATABLE_READ`，混合快照已用真实数据库复现并修复。
- WB-08：零跳改为「在范围内才算可达」，范围外 `reachable=false`、`hops=null`。
- 浏览器实测 6 个场景（默认入口、双作用域、未知排序、极大页码、`%_` 关键词）：0 未捕获异常，
  非法参数一律 400 + 可读提示；每页有一次 401（匿名会话探测，预期）。

**第二批结束时仍明确未做的**（不要当成已完成）：跨服务重复／乱序／毒事件（T32）、备份恢复（T33）、
越权与 CSRF 实测（T34）、性能与容量（T36）、人工标注样本复核（T35）、四类成果专属字段与参与角色映射
（T28 后半）、网络逐跳论文证据（T20）、多数认领与审核端到端用例（T07–T13）。逐项见验证记录 §5。

**随后执行了第三批任务（容量与恢复：T10／T11／T12／T32／T33）**，改动同样**未提交**。详细记录见
`docs/ai/第三批-容量与恢复验证记录-2026-10-17.md`（环境、原始输出、未达成项都在里面）：

- A 阶段（规则验证）：`ClaimConcurrencyIT` 5 项，在真实 PG 上用「延迟触发器 + 双线程」受控制造重叠，
  证明部分唯一索引 `uk_iam_portal_authority_active` 才是互斥的执行者（删索引后同一语句直接 42P10 反证）。
  该组用例先红后绿，抓到并修掉一个真实缺口：并发路径原来不写审计，事后无法区分「确有并发」与「人工改判」。
- B 阶段（业务联调）：固定种子合成 100 万去重成果（`tests/perf/seed-core.sql` / `seed-query.sql`），
  分层计数、9 条与真实 SQL 同形的 `EXPLAIN (ANALYZE, BUFFERS)`；两个统计类查询在 100 万口径下 5,877 ms / 4,779 ms，
  **未提交任何索引迁移**（先给证据，扩容前再定）。
- C 阶段（容量）：10/20/50/100 VU 四档，100 VU 稳态 5 分钟。**NF02 未达成**：统计概览单请求 34–64 秒 →
  query 连接池（16）被打满 → 3,848 次 `CannotGetJdbcConnectionException` → 76 次超时取消。100 VU 的 P95
  反而低于 50 VU，因为请求在 5 秒连接超时处快速失败，这是「失败得快」不是「更快」。
- T32：`ProjectionRecoveryIT` 4 项，抓到 `InboxReceiver` 回执的两个口径错误（`received` 把本批条数当落库条数、
  `pendingRemaining` 在缺口停摆时回答「没有积压」），已修。
- T33：`ops/perf/restore-drill.sh`，100 万数据 dump→restore→逐表核对：core 36 表、query 12 表行数一致，
  约束/延迟触发器/索引都在，同一检索命中数 828,174 = 828,174。
- 全量实测（21:19）：`./mvnw -B -ntp verify -Dcn.it.skip=false -Dfailsafe.failIfNoSpecifiedTests=false`
  exit 0；**单元 491**（9/305/82/95）＋**集成 29**（core 23 / query 6）；三条 JaCoCo 门禁通过；`spotless:check` 通过。
- 第三批结束时仍明确未做：500 万规模、300/1000 并发、真实许可数据源容量、CPU/GC/IO 资源曲线、
  浏览器级并发、跨服务传播延迟、记录级重试退避。压测与演练都在隔离部署（18081/18083 + 55432）上做，
  未占用共享开发库，也未对任何公网站点施压。

**随后执行了第四批任务（WB-13 采集更新周期、WB-14 交付证据）**，改动同样**未提交**：

- WB-13：把三个此前混在一起的「周期」拆成可分别指出的东西——调度轮询（`cn.collect.worker-interval-ms`）、
  失败退避（`ing_collect_job.next_attempt_at`，V5）、来源业务更新周期（`V8__source_update_plan.sql`：
  `new_record_interval_minutes` / `field_refresh_interval_minutes` / `update_plan_note` / `next_due_at`）。
  周期默认空置（`NULL` = 未设定，行为与引入前完全一致），设周期必须写依据（库级 CHECK），
  周期只挡「还没开始的执行」（`total_fetched > 0 OR next_due_at <= now()`）。
  新增 `PUT /api/v1/admin/ingestion/sources/{id}/update-plan`。
- 429／503 的 `Retry-After` 现在优先于本地指数退避（`SourceFetchException` → `decideBackoff`），
  仍受 `max-backoff-seconds` 封顶，运行记录里留 `backoffBasis`。
- 验证：`SourceFetchExceptionTest`（6）、`CrossrefAdapterStubTest`（7，真实 HTTP 替身：429／503／超限／
  非法 JSON／连接不可用／分页不跳不重）、`CollectExecutionServiceTest` 增 5 项（Retry-After 优先、
  封顶、0 秒、依据落库）、`SourceRunConfigUpdatePlanTest`（4）、`CollectMapperSqlTest` 增门控断言（4）；
  真实库：`bash ops/verify/collect-update-plan.sh`（V1–V8 全跑 + 7 条断言，含「去掉门控条件会多取出
  未到期任务」的反证）。
- WB-14：`docs/ai/总体用例图复核-2026-10-17.md`（21 个用例对照实现与验证状态，4 条偏差待评审，
  不改需求仓库）；`AI生成-人工修正案例.md` 增 3 个真实分歧案例；README 的验证状态按第三批实测改写
  （容量未达标、集成 29 项）；`SPEC-代码对应关系表.md` 增 §6.1 三种周期的映射。
- 第四批仍明确未做：到点自动创建采集任务（需先定幂等键与人工建任务的关系）、用真实来源数据标定周期、
  采集端到端（真服务 + 替身源）、真实用户短任务验证（需真人）。

**已知未修（全部在 `docs/ai/测试与覆盖率报告.md` §7、§9.5 登记，不要当成已完成）**：

1. `spotless:check` 在存量代码上检出 9 个文件的格式偏差（core 2 个、query 7 个）。索引有效时
   （增量检查）`mvn verify` 会通过，索引失效后会在格式这一步失败。本轮只格式化了本次改动的文件，
   存量未动。要让 `mvn verify` 在干净环境一次通过，需要人工决定是否跑 `spotless:apply`。
2. 编码规范 §8.3 要求的三个核心模块之一是 query/discovery，它**没有纳入门禁**，实测行覆盖率
   13.50%（22/163），`discovery.api` 为 0%。这是覆盖率口径上的真实缺口，不是「已达标」。
3. Testcontainers 依赖在 pom 里，但没有任何测试类使用它：490 项全是 Mockito 单元测试，
   没有真实 PostgreSQL 的集成测试层。
4. 前端没有 lint、没有组件单元测试、没有 Playwright E2E（`frontend/package.json` 只有
   dev/build/preview/typecheck）。逐页断言脚本 `cn-shoot.mjs` 在 workspace 目录，**不在仓库内**。
5. 500 万可检索成果 / 100 并发的容量验证未执行：k6 未接入，`tests/perf/` 目录未建。
   `tests/api/smoke-perf.py` 是本地单用户串行基线，文件头明确写了「不得据此宣称验收达标」。
   连跑两次**各有 1 条链路 P95 超过 2 秒工程上限**（第一次领域洞察 2110ms，第二次合作网络 2 跳 5723ms），
   两次都是 15/17。抖动未归因，见 `docs/ai/测试与覆盖率报告.md` §4.1。
6. **WB-06 的一致快照未修**：`SearchService` 仍是默认隔离级别，`count`、列表、作者、领域四条 SQL
   分四次读取，「总量与列表来自不同修订却标同一版本」的风险仍然存在。学者检索现在会拒绝过期
   `queryVersion`，但那只是「拒绝旧 token」，不等于「一次请求内一致快照」。
7. 并发业务不变量（两审核者同时裁决、两账号同时批准同一门户）未用真实双事务验证；
   浏览器端请求竞态只有人工核验，没有自动断言。
8. 存量代码另有两条设计层欠缺：投影导出与事件载荷的字段集靠纪律保证同源（无编译期约束）；
   工作台里「被合并方牵涉的第三方候选」没有一键改判入口。


### 四、已修的真实缺陷（写文档时的素材，不要编新的）

1. **治理读接口无能力校验**：任何登录用户都能读候选工作台、台账、合并预览（含两端标题与相似度），
   还能调合并接口。16 个方法补 `Capabilities.require(actor, CAP_GOVERNANCE_MANAGE)`。
   教训：「登录」不等于「授权」。
2. **合并重定向绕过公开资格**：拿到旧标识即可读到仍处 `PENDING_GOVERNANCE/RESTRICTED` 的幸存实体，
   且只返回空壳视图。改为「先解析合并链终点 → 对终点做资格校验 → 组装完整详情 → 追加跳转说明」。
3. **撤销后投影残留**：增量事件仍按载荷写参与关系与领域，重建只导公开快照，导致
   「重建结果 ≠ 增量结果」（37492 vs 37388）。修 `ProjectionApplier`：不可见成果写空集合且删边。
4. **JDBC 通用执行计划导致 14 倍退化**：同一 PreparedStatement 第 6 次执行切通用计划，
   领域洞察查询 18ms → 262ms。三服务 Hikari 加 `connection-init-sql: SET plan_cache_mode = force_custom_plan`，
   领域洞察 5111ms → 640ms，网络 2 跳 7432ms → 787ms。
5. **`count(DISTINCT (a,b))` 行构造器比子查询 DISTINCT 慢 3 倍**（617ms vs 218ms）。
6. **`?size=abc` 被兜底成 500**：query 与 ingestion 的 `GlobalExceptionHandler` 缺
   `MethodArgumentTypeMismatchException`（core 本来就有，三服务行为不一致）。
7. **只在默认入口出现的渲染崩溃**：`SearchView.vue` 用 `item.citationCount !== null` 判空，
   而服务端 `non_null` 序列化会把 null 字段整段丢掉 → 前端拿到 `undefined` →
   `undefined.toLocaleString()` 抛异常 → 整页永久停在「正在读取…」。
   浏览排序首屏恰好是 PROJECT 类成果（本就没有引用量/DOI），所以「不带关键词打开检索页」必现；
   而旧截图脚本一直用 `?q=graph`，首条是论文，这条崩溃长期不可见。
8. **撤销发布后无恢复入口**：`confirmPublication` 放行 `WITHDRAWN` 后可重新确认恢复。
9. **注释声称的不变量代码没做到**：`AdminCatalogService` 曾说公开投影的两个出口「字段名逐字一致」，
   实际不一致；契约脚本的逐字段比对才发现。已改为三个出口共用 `publicProjectionView`，
   并在注释里写明当年真实出过的故障（导出少两个字段 → 重建后 6515 条公开成果被当成非公开行）。

以上 9 例已在 `docs/ai/AI生成-人工修正案例.md` 中逐条展开（现象、发现方式、AI 初版错在哪、
人工判据、修法、回归证据）。

### 五、环境与启动（踩过两次的硬坑）

- **整栈只有一个入口**：`bash ops/local/stack.sh`。它把 PostgreSQL、两个 Redis、三个服务、
  前端 dev server 收在同一个进程组里并**前台阻塞**。普通终端直接执行，Ctrl-C 停整栈；
  **沙箱 / AI 会话下必须由调用方以「后台任务」方式承载**——沙箱是「一次调用一个进程组」，
  前台调用一返回整组即被回收（现象：日志里有 Started，端口不监听）。
  `bash ops/local/stack.sh stop` 优雅停整栈；`status` 看状态。
- **单服务启动在沙箱下不成立**：`service.sh start core` 会 nohup 派生后立刻返回，承载调用一结束
  整组被回收。改后端一律走 `stack.sh`。
- **构建前必须停栈**：jar 被运行中的服务锁住，`repackage` 会以「Unable to rename ... .jar.original」失败。
  顺序是 `stack.sh stop` → 构建 → `stack.sh start`。
- 回环请求一律 `curl --noproxy '*'`；Git Bash 里 URL 含 `/core/...` 需 `MSYS_NO_PATHCONV=1`。
- **psql 在 Windows 上不能用 `-c` 传中文**：argv 按 ANSI 码页（CP936）转出，UTF-8 中文在这步就坏了，
  服务端报 `invalid byte sequence for encoding "UTF8"`。含中文的 SQL 一律走 stdin
  （脚本里 `psql -f -` + heredoc，脚本本身存 UTF-8），并设 `PGCLIENTENCODING=UTF8`。
  范式见 `ops/local/backfill-merged-pair-candidates.sh`。
- 数据库账号：`core_app` / `ingestion_app` / `query_app`，密码 `cn_<服务>_app_dev`；
  psql 在 `D:/dev-tools/pgsql/bin/psql.exe`。
- Maven：`D:/dev-tools/apache-maven-3.9.16/bin/mvn -s /d/dev-tools/settings.xml`；
  Windows 控制台是 GBK，日志要 `iconv -f GBK -t UTF-8`。
- 前端逐页断言脚本：`C:/Users/白皓宇/.workbuddy/binaries/node/workspace/cn-shoot.mjs`
  （ESM 不认 `NODE_PATH`，脚本必须放在 workspace 目录内跑），截图落 `D:/dev-tools/cn-shots/admin`。

### 六、写代码前先看这几条（都是已踩过的）

- **MyBatis 注解 SQL 的转义**：`@Select` 里带 `<script>` 才走 XML 解析，`&lt;` 才会被还原；
  不带 `<script>` 时 `&lt;` 原样进库 → `syntax error at or near ";"`。动态条件必须配 `<script>`。
- **共用行类型不能加「只有个别查询才有的列」**：MyBatis 构造器自动映射把「结果集缺列」当硬失败，
  不是可空字段。`AchievementRow` 被多个查询共用，加列会让其他查询整体 500；个别查询用独立行类型。
- **`EffectiveStatus.publiclyEligible()` 的语义是「已经是 EFFECTIVE」**，不是「可以公开」。
  判断「是否待治理」要显式写 `== PENDING_GOVERNANCE`，用取反会拦掉唯一该走发布路径的实体。
- **`PublicationYear.year()` 是 `Integer`，不要用 `==` 比较**：两个 2023 装箱后是不同对象。
  一律 `Objects.equals`。
- **配置项可能静默失效**：`backoffSeconds` 里曾有 `1L << min(attempt-1, 6)` 位移上限，
  使配置的 `max-backoff-seconds`（默认 300）永远不生效。写完上限类配置要反证「改了它行为真的变了」。
- **JaCoCo 门禁有两种「匹配空集合 → 一律通过」的写法必须避开**：① `element=BUNDLE` 时 `includes`
  比的是 bundle 名（`core-service`），写包名永远匹配不上；② 即使 `element=PACKAGE`，`包名.*`
  仍匹配不到包本身（`*` 被译成 `.*`，模式里的「.」是正则的任意字符，要求「包名后面还有字符」，
  只能命中子包）。正确写法是 `包名*`。`ops/verify-coverage-gates.sh` 会重做这个反证。
- **三个服务都配了 `default-property-inclusion: non_null`**：null 字段从响应里消失，
  「值未知」与「服务端漏写字段」在报文上无法区分，前端拿到 undefined 会渲染空白。
  凡要表达「未知/无」必须另给永不缺失的字段（布尔或标签），例如年份的 `yearKnown` +
  `publicationYearLabel`。前端判空必须同时挡 `null` 与 `undefined`。
- **状态必须实时读，不能用登记时的快照**：候选的 evidence 是登记那一刻的结论，
  用它渲染工作台会把「当时的结论」当成「现在的事实」（某一端可能已被别的治理动作并走）。
- **不要把「不能做的操作」做成可点的按钮**：被并走的一端不能作为幸存实体，卡片上就把该选项关掉
  并说明只剩驳回；不可行的处置必须当场可见，而不是让人去撞错误码。
- **「合并回答了候选提出的问题」**：合并一对时要把这一对之间的 OPEN 候选裁决为 MERGED，
  否则它留在 OPEN 且因相似度最高永远压在工作台最前面。判据要窄：只裁决两端恰好是本次合并两端的那批。
- 治理页刻意只提供批量驳回、不提供批量合并；翻页/改筛选必须清空勾选。

### 七、常见动作

```
# 起/停/看整栈（沙箱下用后台任务承载 start）
bash ops/local/stack.sh [start|stop|status|--no-web]

# 单元测试
D:/dev-tools/apache-maven-3.9.16/bin/mvn -s /d/dev-tools/settings.xml -q test

# 覆盖率门禁 + 反证
bash ops/verify-coverage-gates.sh [--skip-build]

# 各层自检
bash tests/api/smoke-core.sh
bash tests/api/smoke-ingestion.sh
bash tests/api/smoke-query.sh
"C:/Users/白皓宇/.workbuddy/binaries/python/versions/3.13.12/python.exe" tests/api/smoke-e2e-pipeline.py
"C:/Users/白皓宇/.workbuddy/binaries/python/versions/3.13.12/python.exe" tests/api/smoke-redlines.py
"C:/Users/白皓宇/.workbuddy/binaries/python/versions/3.13.12/python.exe" tests/api/smoke-contract.py
"C:/Users/白皓宇/.workbuddy/binaries/python/versions/3.13.12/python.exe" tests/api/smoke-projection-consistency.py
```

工程记忆（README 之外的第二份必读）：`D:/code/academicplatform/.workbuddy/memory/MEMORY.md`
与同日期的 `YYYY-MM-DD.md` 日志。里面有上表所有坑的原始记录，写代码前应当翻一遍。

### 八、接手后第一步

**先起环境确认基线没坏，再动代码。**

1. `bash ops/local/stack.sh`（后台任务承载），`bash ops/local/stack.sh status` 等四个端口可访问；
2. 跑 `smoke-core.sh` + `smoke-query.sh` 确认没有回归（预期 21 与 90 全过）；
3. 要改后端就按「停栈 → 构建 → 起栈」的顺序，构建命令是
   `./mvnw -B -ntp verify -Dspotless.check.skip=true`（跳过格式门禁的原因见「当前状态」的已知未修第 1 条；
   若已决定格式化存量代码，去掉该参数）；
4. 完成一批工作后更新本文件的「当前状态」一节，并把 `.workbuddy/memory/YYYY-MM-DD.md` 追加一段。

**若要继续推进，按代价从低到高排的候选**：

1. 把 `query/discovery` 纳入覆盖率门禁并补测试（当前 13.50%，是 §8.3 口径里唯一没达标的选定模块）；
2. 决定是否 `spotless:apply` 存量 26 个文件，使干净环境下一次 `mvn verify` 就能通过；
3. 把逐页断言脚本 `cn-shoot.mjs` 收进仓库（现在在 workspace 目录，别人无法复核）；
4. 为三条核心链路补 Testcontainers 集成测试（依赖已在 pom 里，没有任何用例）；
5. 容量验证（500 万可检索 / 100 并发）——需要固定数据集与 k6，属于独立任务。

注意：这个仓库有个明确倾向——**宁可写「未实测」，也不要写一句代码没做到的漂亮话**。
之前出过 `AdminCatalogService` 注释声称两组字段「逐字一致」而实际不一致的例子，
这类注释会骗后来的人去做错误假设，发现一处就改一处。

---

## 附：给人类看的交接速查

| 事项 | 现状 |
|---|---|
| 分支 / 最近提交 | `feat/v0.2-implementation` / `0aad3b0`（本轮改动未提交） |
| 任务清单 | 47/47 完成（#22 证据文档已补齐） |
| 未提交内容 | `docs/ai/` 新增 5 份文档 + 更新的 README + `handover-prompt.md`；新增 `GovernanceDataQualityAdapterTest`（13 项）；根目录 `老师指导对照检查与WorkBuddy改进建议-2026-10-17.md`（非代码） |
| 单元测试 | 487 项全绿（shared 9 / core 305 / ingestion 82 / query 91） |
| 覆盖率 | 8 个受门禁包 85.71%～100%，合计 95.93%；三条门禁经 1.01 反证 |
| 数据规模 | 成果 15326（公开生效 9638）、原始记录 21408、学者 43834 |
| 已知未修 | ① 存量 26 个文件 spotless 不合规（干净环境 verify 会红在这一步）；② query/discovery 未进门禁、13.50%；③ 无 Testcontainers 集成测试；④ 前端无 lint/单测/E2E，逐页脚本不在仓库；⑤ 容量验证未执行；⑥ 投影导出与事件载荷将来加字段时两个出口必须同时改；⑦ 工作台「被合并方牵涉的第三方候选」没有一键改判入口 |
