# 接手提示词（可直接整段粘贴给下一个 AI）

> 本文件是交接用的一次性上下文包。用法：把「提示词正文」整段复制给新的 AI 会话，
> 它即可在不需要历史对话的情况下接着干活。维护要求：每次交接前更新「当前状态」一节。
> **本文件的数字只写实测得到的值，改了代码就要重跑再改数字，不允许按印象填写。**

---

## 提示词正文

你在接手一个已经在跑的工程仓库：`D:/code/academicplatform`，分支 `feat/v0.2-implementation`，
最近提交 `e071e94`（此后 64 个文件改动仍在工作区未提交）。

### 一、这是什么

CognitaNexus 学术成果分享平台的工程实现仓库，课程项目（北航软院「软件系统分析与设计」）。
需求基线在**另一个仓库** `https://github.com/CognitaNexus/CognitaNexus`（本机副本
`D:/code/文档/CognitaNexus-main (1)/CognitaNexus-main`）。**本仓库不复制需求正文、不改写需求语义。**

架构：三服务分库 + 前端，共四个进程。

| 服务 | 端口 | 库 | 职责 |
|---|---|---|---|
| core-service | 8081 | core_db | 权威数据、治理、合并、发布、审计、治理巡检、账户与门户 |
| ingestion-service | 8082 | ingestion_db | 来源登记、采集任务、抓取、暂存、交接批次 |
| query-service | 8083 | query_db | 读投影、公开检索、统计、合作网络、领域洞察 |
| frontend | 5173 | — | Vue 3 SPA，20 条路由（`grep -c "path:" frontend/src/router/index.ts`） |

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
   - 无稳定标识不自动发布；模拟数据不得冒充真实；
   - 公众纠错只登记线索，不直接改数据（核实后走既有修订/裁决路径）。
6. **输出风格**：中文；分步骤、最简；不用表格回答计算/推导类问题；不用成对反引号包裹普通词汇
   （工程文档内标记代码标识可以）。

### 三、当前状态（2026-10-18）

**功能补齐进行中，尚未进入非功能阶段（部署、CI/CD 等由用户后续决定）。** 用户当前要求是
「按专业学术平台的标准把功能补全，文档也要一并修整，重点解决功能性问题」。

已完成且经可执行验证的部分（括号内为实测口径）：

- **单元测试 705 项全绿**：shared-kernel 14 / core-service 439 / ingestion-service 109 / query-service 143（计数取 `TEST-*.xml` 根节点 `tests` 属性，字符串数 `<testcase` 会多算 1 项）。
- **集成测试 34 项全绿**：core 28 / query 6（`ops/local/test-db.sh` 起原生 PG 55432，Failsafe 跑 `*IT.java`）。注意 `test-db.sh start` 在沙箱里活不过一次调用，start、verify、stop 必须在同一次调用内。
- 计数口径：只认 `target/surefire-reports/TEST-*.xml` 的 `<testcase>` 节点数。`*.txt` 汇总行对参数化
  用例会写 `Tests run: 0`，按 txt 累加会明显算少。
- 覆盖率门禁：四条 JaCoCo 门禁覆盖 9 个包，全部通过；`governance/api` 曾 78.00% 低于 80% 被拦，
  补 controller 测试后 88.71%；`governance/application` 93.97%；`query/discovery` 三包在第九批
  首次纳入门禁后为 100%（此前 api 0%、application 38.36%）。门禁的「不是空集合恒真」由
  `bash ops/verify-coverage-gates.sh` 反证（阈值提到 1.01 必须失败，四条均实测失败）。
- 接口与端到端自检（`tests/api/`，第五批重跑的真实结果，均 0 失败）：
  core 21、ingestion 41、query 90、contract 12、redlines 24、claim-evidence 27、notifications 26
  （合并通知 1 项 SKIP）、corrections 33、portal-export 15、scholar-metrics 24。
  其余脚本本轮未重跑，项数取自脚本自身断言：relations 14 / dq-scan 19 / portal 27 / perf 17 /
  merge-aftermath 8 / merge-visibility 15 / projection-consistency 15 / e2e-pipeline / merge-preview。
  逐项状态见 `docs/ai/测试与覆盖率报告.md`。
- 前端：`npm run build`（vue-tsc + vite）通过；`npm test` 11 项纯函数用例通过；逐页截图与运行期
  异常采集、批量引用导出的真实 DOM 断言已入库为 `tests/ui/`（`npm run check`，依赖整栈与真实 Chrome）。

**第五批功能补齐的内容**（详细映射见 `docs/ai/SPEC-代码对应关系表.md` §12–§14）：

- **公众纠错入口**：匿名访客可在成果详情页报告字段错误。只登记到 `gov_anomaly`
  （`kind=PUBLIC_CORRECTION_REPORT`），核实后走既有修订/裁决路径。三层防灌：来源级 5 次/10 分钟、
  单成果全局 20 条/24 小时（键不拼来源）、同来源 1 分钟内同内容只并入不累加。去重键
  `achievementId|FIELD|SHA-256(归一化内容)` 前 12 位，未处理期间只占一条待办。限流先于存在性判断，
  「不存在」与「不可见」同一个 404。治理页展示报告原文与累计次数，待办 `correctionsOpen` 是
  `anomaliesOpen` 的子集（不重复相加）。
- **学者档案指标**：年份分布（未知年份单列）、主要合作者 10 位（两端关系都合格、共同作学者未合并）。
  **不派生 h-index / 影响因子 / 综合评分**——没有权威数据源时派生指标等于编造，`note` 字段明写这一点。
- **CSV 导出**：转义单点化到共享内核 `Csv`（RFC 4180、CRLF、null→空字段、UTF-8 BOM），检索导出与
  「我的成果」导出共用。门户导出 11 列、含未确认行、不分页、**超 5000 行拒绝而非截断**。
- **站内通知**：通知中心与合并通知闭环（`smoke-notifications.py` 26 项，1 项按条件 SKIP）。
- **学术诚信声明（第六批）**：`CONCERN` / `CORRECTION` / `RETRACTION` 三类声明挂在成果上，
  **不改成果任何原始字段**；撤稿不换下架——成果仍可检索、详情仍可打开，但列表与详情都带标记；
  同一成果同一类型只能一条生效声明（部分唯一索引兜底），撤销必须写理由并留撤销人；
  学者指标单列 `worksRetracted`，不从 `worksTotal` 扣除。链路贯穿 core → outbox 载荷
  `integrityStatus` → `qry_achievement.integrity_status` → 检索响应 `integrity` 三字段 → 前端徽标。
  治理入口在治理页底部「学术诚信声明」面板。
- **引用导出（第七批）**：`GET /api/v1/public/achievements/{id}/citations` 一次给全 BibTeX / RIS /
  GB-T 7714 / APA，`GET .../citation?format=` 下载附件。文本与告警**都在服务端生成**（ADR-004），
  前端只展示、复制、下载。三条硬规则：缺失事实不编造（年份未知用 `n.d.` / 「出版年不详」，
  BibTeX 直接省略 `year`）；不替用户猜姓名结构（不拆姓／名，APA 的简化作为该格式专属告警）；
  撤稿进文本（`note` 加撤稿说明），且**撤稿后引用端点仍返回 200**。告警分两层：
  `warnings`（元数据：缺年份、署名顺序未知、待核验归属、已排除关系、撤稿、模拟数据）与
  `formats[].warnings`（格式：APA 不拆姓名）。出处与生成时刻是 `provenance` / `exportedOn`
  两个字段，**不写进引用正文**——引用会被粘进参考文献表，非书目信息不该跟着进去。

- **批量引用导出（第八批）**：检索页勾选若干成果 → 一份文献库文件。两个入口都是 GET：
  `GET /api/v1/public/citations/batch?ids=..&format=..` 给预览（JSON，含整份正文与逐条文本），
  `GET .../batch/download` 给文件。**读操作不用 POST**：平台的一次性 CSRF 令牌校验通过后即失效，
  让预览消耗写令牌会让紧接着的下载莫名 403（这条是真实自检抓到的，不是设计推演）。四条硬规则：
  ① 同一文件内引用键唯一，撞键时后者追加 `a/b/c…`、第一个保持原样，并回 `CITE_KEY_DISAMBIGUATED`；
  ② 条目文本与单条端点逐字一致（`[1]` 这类编号由装配统一加，不属于条目本身）；
  ③ 单条不可公开只跳过并报数，原因统一 `NOT_AVAILABLE`（不区分「不存在」与「不可公开」）；
  ④ 合并过的成果会被重定向到终点实体，同时勾「原记录」与「幸存记录」时按解析后实体分组去重，
  旧记录记为 `DUPLICATE_TARGET`。上限 100 条，超过 422 拒绝而非截断。

**这轮修掉的三个真实缺陷**（案例见 `docs/ai/AI生成-人工修正案例.md` C-10、C-11）：

1. **无授权下载返回 401 而不是 403**：`@RestControllerAdvice` 返回 `ResponseEntity` 时 Spring 按
   `Accept` 选转换器，下载接口的 `Accept: text/csv` 下没有转换器能产出 `ApiResponse` → 406 →
   ERROR 派发 → 认证过滤器不处理 ERROR 派发 → 客户端看到「请先登录」。两侧改为 `void` + ObjectMapper 直写。
2. **限流规则永不生效**：单成果配额键多拼了来源地址，变成「同来源×同成果」，来源级窗口永远先触发。
3. **覆盖率门禁被拦**：新增 controller 无单测，`governance/api` 78.00% < 80%。补 5 项后 88.71%。

**已知未修（不要当成已完成）**：

1. `spotless:check` 在存量代码上检出 **9 个文件**的格式偏差（core 2、query 7）。增量索引有效时
   `mvn verify` 会跳过它们，索引失效转全量检查时会红。要让干净环境一次通过，需人工决定是否
   `spotless:apply`。`-DspotlessFiles` 是**正则**且只对 check 生效。
2. ~~编码规范 §8.3 指定 query/discovery 为三个核心模块之一，但它没有纳入覆盖率门禁~~
   ——第九批已补：新增 `jacoco-check-discovery`，三包行覆盖 100%，1.01 反证确认门禁真实生效。
   但「100% 行覆盖」不等于 SQL 有覆盖：`SearchMapper` 的注解 SQL 仍只由 `tests/api/smoke-query.sh`
   在真实库上验证，而那些脚本不在 `verify` 流水线内。
3. 公众纠错无浏览器级 E2E；门户导出「超 5000 行拒绝」路径未构造（代码有，断言缺）；纠错限流是
   **单实例内存窗口**，多实例部署需网关限流或共享计数器。
4. 前端没有 lint、没有组件单元测试、没有 Playwright E2E（`package.json` 只有 dev/build/preview/typecheck）。
   逐页断言脚本已入库为 `tests/ui/`（第九批），但仍是**人工触发**、不入 CI，且没有视觉回归基线。
5. 容量：100 万口径下 **NF02 未达成**（详见下方「容量与恢复」）。500 万 / 300 / 1000 并发未做。
6. 投影快照导出与事件载荷的字段集靠同一装配点保证同源（无编译期约束）；将来加字段两个出口必须同时改。
7. 工作台里「被合并方牵涉的第三方候选」只能驳回或先解析到最终实体后另建候选，没有「改判为与幸存成果相关」
   的一键入口（预览的 warnings 已如实说明）。
8. 未开工的延伸功能：成果订阅/关注、机构维度可比指标、编辑建议的版本化修订流、通知中心按 kind 分组与批量已读。
9. 沉淀在两份 ADR 里待人工拍板的事：ADR-001 的方案取舍、ADR-002 的六条认领核验判据（签认前不改代码）。
10. 诚信声明没有浏览器级 E2E；投影重建与增量未对 `integrity_status` 做逐值比对；声明不影响检索排序，
    也不改变「已撤稿成果是否参与合作网络」（口径变更需先评审）。

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
   实际不一致；契约脚本的逐字段比对才发现。已改为三个出口共用 `publicProjectionView`。
10. **无授权下载返回 401，真实 403 被顶掉**：错误报文依赖 `Accept` 协商，406 触发 ERROR 派发，
    安全过滤器不处理 ERROR 派发。两侧异常处理器改为直写响应体（见 C-10）。
11. **限流规则永不生效**：键里多拼一个维度，让另一条更严的规则永远先触发（见 C-11）。
12. **`ClaimConcurrencyIT` 抓到并发路径不写审计**：无法事后区分「确有并发」与「人工改判」。
13. **`ProjectionRecoveryIT` 抓到 Inbox 回执两个口径错误**：`received` 把本批条数当落库条数、
    `pendingRemaining` 在缺口停摆时回答「没有积压」。

C-01～C-11 已在 `docs/ai/AI生成-人工修正案例.md` 中逐条展开（现象、发现方式、AI 初版错在哪、
人工判据、修法、回归证据）；C-12、C-13 见 `docs/ai/第三批-容量与恢复验证记录-2026-10-17.md`。

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
- 回环请求一律 `curl --noproxy '*'`；Git Bash 里 URL 含 `/core/...` 需 `MSYS_NO_PATHCONV=1`；
  `-c` 存 cookie 要写 `D:/xxx` 形式，否则退出码 23。
- **psql 在 Windows 上不能用 `-c` 传中文**：argv 按 ANSI 码页（CP936）转出，UTF-8 中文在这步就坏了，
  服务端报 `invalid byte sequence for encoding "UTF8"`。含中文的 SQL 一律走 stdin
  （脚本里 `psql -f -` + heredoc，脚本本身存 UTF-8），并设 `PGCLIENTENCODING=UTF8`。
  跑 `.sql` 文件同样要设（迁移里的中文注释会报错）。范式见 `ops/local/backfill-merged-pair-candidates.sh`。
- **psql 必须加 `-v ON_ERROR_STOP=1`**：不加时 SQL 报错退出码仍是 0，脚本会把「插入被触发器拒绝」
  当成功，给出假绿（第一版合并可见性夹具就这样静默通过）。
- 数据库账号：`core_app` / `ingestion_app` / `query_app`，密码 `cn_<服务>_app_dev`；
  psql 在 `D:/dev-tools/pgsql/bin/psql.exe`。
- Maven：`D:/dev-tools/apache-maven-3.9.16/bin/mvn -s /d/dev-tools/settings.xml`；
  Windows 控制台是 GBK，日志要 `iconv -f GBK -t UTF-8`。
- 集成测试用独立 PG（55432）：`bash ops/local/test-db.sh [start|stop|status]`，必须
  `./mvnw -B -ntp verify -Dcn.it.skip=false -Dfailsafe.failIfNoSpecifiedTests=false`。
  本机 Docker / WSL2 被安全策略阻断，Testcontainers 不可用。
- 压测与恢复演练用隔离部署（18081/18083 + 55432 的 `core_perf`/`query_perf`），
  **不占用共享开发库**，也不对任何公网站点施压。
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
  使配置的 `max-backoff-seconds`（默认 300）永远不生效；限流键拼错维度也让配额规则永不触发。
  写完上限类配置/规则要反证「改了它行为真的变了」，并构造一次「刚好超限」的输入。
- **JaCoCo 门禁有两种「匹配空集合 → 一律通过」的写法必须避开**：① `element=BUNDLE` 时 `includes`
  比的是 bundle 名（`core-service`），写包名永远匹配不上；② 即使 `element=PACKAGE`，`包名.*`
  仍匹配不到包本身（`*` 被译成 `.*`，模式里的「.」是正则的任意字符）。正确写法 `包名*`。
- **`@RestControllerAdvice` 不要返回 `ResponseEntity`**：错误报文会随 `Accept` 变化，下载类接口
  （`text/csv`）直接 406 → ERROR 派发 → 认证过滤器不处理 → 403 被顶成 401。一律 `void` + 直写。
- **三个服务都配了 `default-property-inclusion: non_null`**：null 字段从响应里消失，
  「值未知」与「服务端漏写字段」在报文上无法区分，前端拿到 undefined 会渲染空白。
  凡要表达「未知/无」必须另给永不缺失的字段（布尔或标签），例如年份的 `yearKnown` +
  `publicationYearLabel`。前端判空必须同时挡 `null` 与 `undefined`。
- **检索页条件以 URL 为唯一来源**；`router.replace` 前记下目标 fullPath，watch 里相等就跳过，
  否则一次操作发两次请求；连续检索必须带请求序号，回来不是最新就整段丢弃。
- **检索关键词直接拼 LIKE 会让 `%` 变通配**（输入一个 `%` 命中全库）。归一化与转义是两步，
  `SearchQuery.likePatternOf` 负责转义。
- **状态必须实时读，不能用登记时的快照**：候选的 evidence 是登记那一刻的结论，
  用它渲染工作台会把「当时的结论」当成「现在的事实」（某一端可能已被别的治理动作并走）。
- **不要把「不能做的操作」做成可点的按钮**：被并走的一端不能作为幸存实体，卡片上就把该选项关掉
  并说明只剩驳回；不可行的处置必须当场可见，而不是让人去撞错误码。
- **「合并回答了候选提出的问题」**：合并一对时要把这一对之间的 OPEN 候选裁决为 MERGED，
  否则它留在 OPEN 且因相似度最高永远压在工作台最前面。判据要窄：只裁决两端恰好是本次合并两端的那批。
- 治理页刻意只提供批量驳回、不提供批量合并；翻页/改筛选必须清空勾选。
- 认领材料只给元信息，绝不能给 `fileRef` 或磁盘路径；按 claimId 批量取材料要按「本人名下申请」过滤。

### 七、常见动作

```
# 起/停/看整栈（沙箱下用后台任务承载 start）
bash ops/local/stack.sh [start|stop|status|--no-web]

# 单元 + 集成（集成需先起 55432）
./mvnw -B -ntp verify -Dspotless.check.skip=true
./mvnw -B -ntp verify -Dcn.it.skip=false -Dfailsafe.failIfNoSpecifiedTests=false

# 覆盖率门禁 + 反证
bash ops/verify-coverage-gates.sh [--skip-build]

# 各层自检
bash tests/api/smoke-core.sh
bash tests/api/smoke-ingestion.sh
bash tests/api/smoke-query.sh
python tests/api/smoke-contract.py
python tests/api/smoke-redlines.py
python tests/api/smoke-corrections.py
python tests/api/smoke-portal-export.py
python tests/api/smoke-scholar-metrics.py
python tests/api/smoke-notifications.py
python tests/api/smoke-integrity.py
python tests/api/smoke-citations.py
python tests/api/smoke-citation-batch.py
```

```
# 前端：类型检查 + 构建 + 纯函数测试（node:test，无需浏览器）
cd frontend && npm run build && npm test

# 前端浏览器级断言（需整栈已起 + 本机 Chrome；首次先 npm install）
cd tests/ui && npm run check      # = shoot（逐页截图+异常采集）+ batch（批量引用导出）
```

工程记忆（README 之外的第二份必读）：`D:/code/academicplatform/.workbuddy/memory/MEMORY.md`
与同日期的 `YYYY-MM-DD.md` 日志。里面有上表所有坑的原始记录，写代码前应当翻一遍。

### 八、接手后第一步

**先起环境确认基线没坏，再动代码。**

1. `bash ops/local/stack.sh`（后台任务承载），`bash ops/local/stack.sh status` 等四个端口可访问；
2. 跑 `smoke-core.sh` + `smoke-query.sh` 确认没有回归（预期 21 与 90 全过）；
3. 要改后端就按「停栈 → 构建 → 起栈」的顺序，构建命令是
   `./mvnw -B -ntp verify -Dspotless.check.skip=true`（跳过格式门禁的原因见已知未修第 1 条）；
4. 完成一批工作后更新本文件的「当前状态」一节，并把 `.workbuddy/memory/YYYY-MM-DD.md` 追加一段。

**若要继续推进，按代价从低到高排的候选**：

1. ~~把 `query/discovery` 纳入覆盖率门禁并补测试~~（第九批已完成：三包 100%，门禁经 1.01 反证）；
   剩下的同类缺口是「SQL 没有集成断言」——`tests/api/` 不在 `verify` 流水线内；
2. 把逐页断言脚本 `cn-shoot.mjs` 收进仓库（现在在 workspace 目录，别人无法复核）；
3. 补门户导出「超 5000 行拒绝」的断言；给纠错限流补一条「多实例失效」的说明或改成共享计数器；
4. 前端加 lint 与最小组件测试（现在完全没有）；
5. 决定是否 `spotless:apply` 存量 9 个文件，使干净环境下一次 `mvn verify` 就能通过；
6. 容量验证（500 万可检索 / 300-1000 并发）——需要固定数据集与 k6，属独立任务。

注意：这个仓库有个明确倾向——**宁可写「未实测」，也不要写一句代码没做到的漂亮话**。
之前出过 `AdminCatalogService` 注释声称两组字段「逐字一致」而实际不一致的例子，
这类注释会骗后来的人去做错误假设，发现一处就改一处。

---

## 附：给人类看的交接速查

| 事项 | 现状 |
|---|---|
| 分支 / 最近提交 | `feat/v0.2-implementation` / `e071e94`（此后 64 个文件改动未提交） |
| 当前阶段 | 功能补齐（第五批已收尾），非功能阶段（部署/CI）未开始，等用户决定 |
| 单元测试 | 705 全绿（shared 14 / core 439 / ingestion 109 / query 143） |
| 集成测试 | 34 全绿（core 28 / query 6），原生 PG 55432 |
| 覆盖率 | 四条门禁覆盖 9 个包全部通过；governance/api 88.71%、application 93.97%；query/discovery 三包 100% |
| 接口自检 | 10 套已重跑全绿（core 21 / ingestion 41 / query 90 / contract 12 / redlines 24 / claim-evidence 27 / notifications 26 / corrections 33 / portal-export 15 / scholar-metrics 24） |
| 前端 | 20 条路由；`npm run build` 通过；纯函数 11 项通过；浏览器断言脚本在 `tests/ui/`（人工触发）；无 lint / 组件单测 |
| 已知未修 | ① 存量 9 个文件 spotless 不合规；② SQL 无集成断言（`tests/api/` 不在 verify 内）；③ 纠错限流是单实例内存窗口；④ 前端无 lint/组件单测，浏览器断言不入 CI、无视觉基线；⑤ 500 万规模与 300+ 并发未验证；⑥ 工作台「被合并方牵涉的第三方候选」无一键改判；⑦ NF02 在 100 万口径未达成 |
