# 阶段五：图查询端到端集成测试报告（Graph Integration Test Report）

> 归属 PR：`test/graph-integration` → #23，**base 已从 `feat/graph-query` 改为 `dev`**
> 基线：`origin/dev` @ `8eeb74e`（已包含 PR #19 CI 基建、#20 Neo4j Schema、#22 图投影同步、#21 图查询路由）
> 执行时间：2026-11-25 20:37（Asia/Shanghai，末次完整 `verify` 与 CI `graph-integration` 命令复跑）
> 执行环境：本机隔离测试基础设施（非生产、非共享开发库）
> 结论：**本 PR 只新增 2 个端到端集成测试类（39 例）与本文档，不含任何已合入 `dev` 的生产代码；
> 真实双库（PostgreSQL + Neo4j）下 39/39 通过；仓库既有 400 个单元测试 + 82 个集成测试全部通过；
> 0 失败、0 错误、0 跳过；Spotless 与 JaCoCo 三条门禁全部通过。**
>
> 与上一版（基于 `feat/graph-query` 的栈式 PR）的差异：
>
> 1. 分支已重置到最新 `origin/dev`，`git diff origin/dev...HEAD` 只含阶段五的 3 个文件；
> 2. 适配了 `dev` 上的最新签名/语义（`ProjectionWatermarkGuard` 增加 `GraphProjectionInitializationProbe`
>    参数、`GraphEventProjector` 增加 `GraphProjectionCheckpointStore` 参数、回填「有任何失败成果即不可
>    标 COMPLETED」、`NetworkParityComparator` 将 `withinLimits` 纳入逐字段比较）；
> 3. 新增 5 个针对 P1-1..P1-4 / D-2 / D-3 修复的跨模块联动用例（初始化探针拦截、水位/gen 守卫、
>    事件失败不推水位、报告过期需正向同步、`AchievementMerged` 删旧成果）。

---

## 1. 执行环境与命令

| 项 | 值 |
| --- | --- |
| PostgreSQL | `jdbc:postgresql://127.0.0.1:55432`（`ops/local/test-db.sh`，库 `query_it` / `query_graph_it`） |
| Neo4j | `bolt://127.0.0.1:17687`（`ops/compose/compose.test-infra.yaml`，隔离实例） |
| Neo4j 投影作用域 | 每次运行独立：`it-gs-<rand>-scope` / `it-e2e-<rand>-scope`（不写共享 `query-public`） |
| PostgreSQL generation | 每次运行独立：`gen-it-gs-<rand>` / `gen-it-e2e-<rand>`，consumer 独立 |
| JDK / Maven | Java 21，Maven Wrapper（`./mvnw`） |
| 完整验证命令 | `./mvnw -B -ntp -pl services/query-service -am verify -Dcn.it.skip=false` |
| CI 等价命令 | 见 §7（与 `.github/workflows/ci.yml` 的 `graph-integration` job 逐字一致） |

### 1.1 完整验证实测（末次运行，未跳过任何 IT）

```
[INFO] Tests run: 400, Failures: 0, Errors: 0, Skipped: 0     # surefire（单元测试）
[INFO] Tests run: 82,  Failures: 0, Errors: 0, Skipped: 0     # failsafe（真实数据库集成测试）
[INFO] All coverage checks have been met.  (analytics / discovery / network 三条 JaCoCo 门禁)
[INFO] Spotless.Java is keeping 180 files clean - 0 needs changes to be clean
[INFO] BUILD SUCCESS
```

### 1.2 集成测试构成（`*IT`，真实 PostgreSQL + 真实 Neo4j）

| 测试类 | 用例数 | 说明 |
| --- | --- | --- |
| `Neo4jSchemaIT` | 11 | `dev` 既有：schema/约束/图模型写入语义 |
| `GraphProjectionSyncIT` | 14 | `dev` 既有：回填/增量/对账/断点 |
| `NetworkGraphQueryIT` | 12 | `dev` 既有：图查询路由与水位保护 |
| `ProjectionRecoveryIT` | 4 | `dev` 既有：PG 投影接收/发布语义 |
| `SearchSnapshotIT` | 2 | `dev` 既有：检索快照 |
| **`GraphSemanticsAndEdgeCasesIT`** | **19** | **本 PR 新增：图谱正确性 11 + 业务语义 8** |
| **`GraphEndToEndConsistencyIT`** | **20** | **本 PR 新增：端到端一致性 4 + 一致性与故障恢复 12 + 端点/回滚 4** |
| 合计 | 82 | 0 失败 / 0 错误 / 0 跳过（无 `Assumptions.abort` 触发） |

> 说明：本报告的「实测结果」全部来自上面的真实运行，不是预期值抄写；耗时取自
> `services/query-service/target/failsafe-reports/TEST-*.xml` 的 `testcase@time`。

---

## 2. 新增测试的定位（为什么必须真实数据库）

`GraphSemanticsAndEdgeCasesIT` 与 `GraphEndToEndConsistencyIT` 的共同前提是：**图里的数据不是测试直接写进
Neo4j 的**，而是由生产代码 `GraphBackfillService` / `GraphEventProjector` 从 PostgreSQL 读投影表
（`qry_achievement` / `qry_contribution` / `qry_achievement_topic` / `qry_contribution_org` /
`qry_inbox_event` / `qry_graph_projection_checkpoint`）读出后写入的。因此每条断言验证的是
「PG 事实 → 图投影写侧 → 查询读侧」整条链路，而不是「Java 里的 BFS 逻辑」。

| 断言类型 | 只有真库才能证明的原因 |
| --- | --- |
| `C(4,2)=6`、`C(22,2)=231` 不物化 | 结论落在 Cypher `MERGE` 与物化阈值判定上，边真的建了才数得出来 |
| 等长最短路径确定性 | 需要真实邻接集合与真实 Cypher 返回顺序；假仓储只能证明「我按我造的边遍历」 |
| 撤销公开 / 成果合并后边消失 | 需要真实 `qry_inbox_event` → `GraphEventProjector` → `retireWork`/`deleteWork` 与真实关系删除 |
| 初始化探针拦截 | 需要真实 `qry_graph_projection_checkpoint`（V7）被读路径的 `PostgresGraphProjectionInitializationProbe` 读到 |
| 回填失败 → 对账 → 收尾闭环 | 需要真实断点行、真实图缺口、真实 `repairAndComplete` 与真实水位节点同时成立 |
| 规模/预算错误 | 必须有真实规模与真实耗时才能触发 `GRAPH_SCOPE_TOO_LARGE` / `GRAPH_TIMEOUT` |
| 对账 `consistent=true` | 需要 PG SQL 计数与 Cypher 计数在真实数据上逐类对齐 |

### 2.1 覆盖边界（与 `dev` 上既有 IT 的分工）

| 关注点 | 既有类 | 本 PR 新增类的增量 |
| --- | --- | --- |
| 单测语义（阈值、守卫分支） | `*Test` | 不重复 |
| 写侧（回填/增量/对账/断点） | `GraphProjectionSyncIT` | **写侧 × 读侧联动**：同一批数据先由写侧灌入，再断言读侧路由的 HTTP 码/指标/逐字段一致 |
| 读侧（路由/水位/严格模式） | `NetworkGraphQueryIT` | **读侧读到的水位/断点由真实写侧产生**，不手写 Neo4j 节点 |
| 图谱算法正确性 | — | 11 个算法/图形态用例（环、不连通、等长路径、高度数、过滤、超限、超时） |
| 业务语义 | — | 8 个语义用例（未公开、不合格关系、认领、幂等、撤销、证据、REAL/SIMULATED、成果合并） |

---

## 3. 场景矩阵（逐项实测）

### (1) 图谱正确性（11 项，`GraphSemanticsAndEdgeCasesIT`）

| # | 场景 | 方法 | 输入 | 预期结果 | 实测结果 | 耗时 |
| --- | --- | --- | --- | --- | --- | --- |
| 1-1 | 单学者无合作关系 | `singleScholarWithoutCollaborationHasNoEdges` | 1 篇 PUBLIC/EFFECTIVE 论文，唯一作者 `solo`（AUTO）；PG→真回填 | `seedInScope=false`、0 条边、0 个节点、`solo→solo` 不按 0 跳可达 | 通过：`seedInScope=false`、`edges=[]`、`nodes=[]`、`reachable=false` | 0.165s |
| 1-2 | 两学者共同论文 | `twoScholarCoauthorshipProducesSingleEdgeWithEvidence` | 1 篇两作者论文（AUTO + SELF_CONFIRMED） | 1 条 `COOPERATED_WITH`，`strength=1`，证据 `work_id`/`work_title`/`dataOrigin=REAL` 正确 | 通过：边数 1、强度 1、证据标题与 PG 一致、逐跳证据 1 条 | 0.138s |
| 1-3 | 多人共同论文 | `multiAuthorWorkMaterializesAllPairsAndWeightsAccumulate` | 4 人论文 + 5 人论文 + 同一对作者的第 3 篇 | 总物化边 6+10+1=17；`(m1,m2)` 强度=3（不同成果数） | 通过：17 条边；强度 3、证据 w-0301/0302/0303；`(m1,m5)`=1 | 0.180s |
| 1-4 | 多条等长最短路径 | `equalLengthShortestPathsChooseLexicographicallySmallestDeterministically` | `p0-(p1|p2)-p3` 两条等长 2 跳路径（`p1<p2`） | 恒选字典序最小 `[p0,p1,p3]`；反向落到同一规范路径；重复结果一致 | 通过：5/5 次 `[p0,p1,p3]`；反向 `[p3,p1,p0]`（同一规范路径） | 0.243s |
| 1-5 | 不连通图 | `disconnectedComponentsHaveNoFabricatedPath` | 两个互不相连的 2 人分量 | 跨分量 `reachable=false`、`hops=null`、`path=[]`；分量内仍可达 | 通过：跨分量不可达且无伪造路径；分量内 1 跳；邻域恰 2 节点 | 0.115s |
| 1-6 | 最大路径深度 | `pathDepthBoundaryAndNeighborhoodDepthClamp` | 8 节点链（7 条边）；`depth=99` 的自我网络 | 6 跳可达；7 跳不可达；`depth=99` 截断为 3 | 通过：`hops=6`；7 跳 `reachable=false`；`depth=3`、4 节点、3 边 | 0.334s |
| 1-7 | 存在环的图 | `cyclesDoNotLoopOrDuplicateNodes` | 三角环 + 四元环带弦 | BFS 不死循环、节点不重复计；弦上 1 跳、对边 2 跳 | 通过：三角 `visitedNodes=3/loadedEdges=3`；带弦 4 节点 5 边 | 0.378s |
| 1-8 | 高度数节点 | `highDegreeWorkUsesBipartiteTraversalWithoutEdgeExplosion` | 22 位作者 1 篇论文（物化阈值 20） | 该成果 0 条物化边、22 条 `AUTHORED`；推导边 21 条 | 通过：`COOPERATED_WITH=0`、`AUTHORED=22`、推导 21 条强度全 1 | 0.177s |
| 1-9 | 过滤后的子图 | `filteredSubgraphRecomputesStrengthFromMatchingWorks` | 同一对作者 3 篇：`t1/o1`、`t1/o2`、`t2/o1` | 不筛=3；`topic=t1`=2；`org=o1`=2；`t2+o1`=1 | 通过：3 / 2 / 2 / 1，证据逐条匹配；无命中 → `seedInScope=false` | 0.214s |
| 1-10 | 超出节点/边限制 | `nodeAndEdgeLimitsRaiseScopeTooLargeWithoutTruncation` | 星形图 1 hub + 10 leaves；`maxNodes=5` / `maxEdges=3` / 路径 `maxEdges=2` | 三种超限均抛 `GRAPH_SCOPE_TOO_LARGE`，不返回截断残图 | 通过：3 次均为 `GRAPH_SCOPE_TOO_LARGE`；对照查询 10 条边 | 0.397s |
| 1-11 | 图查询超时 | `graphTimeoutRaisesGraphTimeoutWithoutPartialPath` | 5 节点链 + 每层 15ms 真实延迟，预算 1ms | 抛 `GRAPH_TIMEOUT`，不返回半条路径；正常预算 4 跳可达 | 通过：`GRAPH_TIMEOUT`；同数据 `hops=4` | 0.180s |

### (2) 业务语义（8 项，`GraphSemanticsAndEdgeCasesIT`）

| # | 场景 | 方法 | 输入 | 预期结果 | 实测结果 | 耗时 |
| --- | --- | --- | --- | --- | --- | --- |
| 2-1 | 未公开成果 | `nonPublicOrIneffectiveWorksAreExcludedFromGraph` | PUBLIC/EFFECTIVE、RESTRICTED/EFFECTIVE、PUBLIC/SUPERSEDED 三篇 | 只有合格成果进图（1 节点 1 边）；不合资格连墓碑都没有 | 通过：Work 1、边 1、邻域 `{v1,v2}`、两条路径不可达 | 0.210s |
| 2-2 | 不合格关系 | `unqualifiedRelationStatesDoNotCreateEdges` | 4 位作者：AUTO / SELF_CONFIRMED / PENDING_VERIFY / DISPUTED | 仅 2 条 `AUTHORED`、1 条合作边 | 通过：`AUTHORED=2`、边 1 强度 1；两条路径不可达 | 0.153s |
| 2-3 | 认领状态变化 | `claimStateTransitionsAddAndRemoveEdges` | 双 PENDING_VERIFY → 1 位 SELF_CONFIRMED → 2 位 → 1 位 EXCLUDED（版本递增重同步） | 0 → 0 → 1（可达）→ 0（不可达） | 通过：边数 0/0/1/0，动作 `APPLIED`，可达性同步变化 | 0.141s |
| 2-4 | 重复导入幂等 | `duplicateImportIsIdempotentForEdgesAndStrength` | 同一对作者 2 篇；同版本重放 + 旧版本回放 + 重复回填 | 同版本幂等、旧版本 `STALE_IGNORED`、重复回填 `worksThisRun=0` | 通过：幂等、`STALE_IGNORED`、0 条重扫、计数不变 | 0.143s |
| 2-5 | 撤销公开 | `tightenedVisibilityRetiresEdgesAndMakesPeersUnreachable` | 真实 `PublicVisibilityTightened`（rev=110）+ PG 收紧 RESTRICTED | `worksRetired=1`、水位前进、合作边归零、墓碑 `is_public=false` | 通过：`worksRetired=1`、边 0、`is_public=false`、不可达 | 0.113s |
| 2-6 | 证据可追溯 | `everyEdgeCarriesTraceableWorkEvidence` | 3 篇论文（a-b、b-c、a-c） | 每条边的 `work_id` 存在于 PG 且 `work_title` 逐条相等；`AUTHORED=6` | 通过：3 条边标题全匹配；`AUTHORED=6` | 0.121s |
| 2-7 | REAL/SIMULATED 隔离 | `realAndSimulatedDataAreIsolated` | 1 篇 REAL（a-b）+ 1 篇 SIMULATED（a-c） | REAL 无 `a-c`；ALL/SIMULATED 有；SIMULATED 看不到 `a-b` | 通过：REAL 不可达、ALL/SIMULATED 1 跳、SIMULATED `a-b` 不可达 | 0.185s |
| 2-8 | 成果合并 | `achievementMergedDeletesOldWorkNodeAndEdges` | `a-b`（幸存者 w-2701）+ `b-c`（被合并者 w-2702）；PG 删除被合并者后投递 `AchievementMerged{survivorId,mergedId}` | `worksDeleted=1`、水位前进、旧 `Work` 节点/`AUTHORED`/`COOPERATED_WITH` 全部消失、`a→c` 立即不可达、幸存边不受影响 | 通过：`worksDeleted=1`、旧节点 0、旧合作边 0、旧参与边 0、`a→c` 不可达、`a→b` 仍 1 跳 | 0.174s |

### (3) 端到端一致性与故障恢复（12 项，`GraphEndToEndConsistencyIT`）

| # | 场景 | 方法 | 输入 | 预期结果 | 实测结果 | 耗时 |
| --- | --- | --- | --- | --- | --- | --- |
| 3-1 | 投影无水位 | `missingProjectionNeverServesGraphAndStrictReturns503` | 无 `GraphProjectionMeta` | 回退 PG、`staleProjectionFallbackCount+1`、`PROJECTION_META_MISSING`；严格模式 503 `VIEW_UPDATING` | 通过：响应=PG、计数+1、原因码一致；真实 MVC 栈 503 | 0.225s |
| 3-2 | 投影落后 | `staleProjectionIsNeverServed` | 已发布修订 520、图水位 500 | 回退 PG、`staleProjectionFallbackCount+1`、`PROJECTION_STALE` | 通过：响应=PG、计数+1、原因码 `PROJECTION_STALE` | 0.184s |
| 3-3 | 降级期间公开接口 | `publicEndpointReturns200DuringFallback` | 真实 MVC 栈 `GET /api/v1/public/network/graph`、`.../path` | 200 + `code=OK`，字段与 PG 一致 | 通过：200，`edges[0]={s1,s2,1}`、`ruleVersion=NETWORK_V1`、`path.hops=1` | 0.140s |
| 3-4 | 回填中断不越界推水位 | `backfillInProgressBlocksWatermarkAdvance` | 5 篇论文、批大小 2、第 2 页强制中断；`GraphProjectionWorker.catchUp()`；再续跑 | 中断后断点未完成、水位 0；Worker 跳过；读侧回退 PG；续跑后水位=500 且图开始服务 | 通过：`processed_works=2`、Worker 后水位 0、回退+1；续跑 `worksThisRun=3`、水位 500、`graphServedQueries+1` | 0.473s |
| 3-5 | Outbox/Inbox 重复投递 | `duplicateEventDeliveryIsIdempotent` | 同一 `eventId` 投递两次 + 两次 `applyPending` | Inbox 只有 1 行；首次 `eventsRead=1`、水位 rev+5；第二次 `eventsRead=0`；边数不翻倍 | 通过：行数 1、第二次 0 事件、边数不变 | 0.200s |
| 3-6 | 事件乱序/迟到 | `lateOutOfOrderEventIsRejectedByVersionGuard` | 图已写 v60；PG 回落 v50 并投递 rev+10 | `worksStale=1`、水位前进、图上仍 v60 | 通过：`worksStale=1`、`source_version=60`、`AUTHORED` 不变 | 0.185s |
| 3-7 | **回填失败 → 对账 → 收尾闭环** | `partialBatchFailureIsRecordedAndRepairable` | 3 篇论文、批大小 2，对 `w-p3` 注入域错误 | ① 断点 `FAILED`、`failed_works=1`、`last_error` 含成果号与 `FAILED_PERMANENT`、`processed_works=2`、水位不推；② 读侧先 `PROJECTION_META_MISSING`，补旧水位后 `PROJECTION_NOT_INITIALIZED` 并回退 PG；③ `repair` 单点补齐后断点仍 `FAILED`；④ `repairAndComplete` 后断点 `COMPLETED`、`failed_works=0`、水位=500；⑤ 同一请求立即由图库服务且与 PG 逐字段一致 | 通过：① 全部成立；② 两个原因码与回退计数+1 成立；③ 补齐后仍 `FAILED`、读侧仍拒绝；④ `COMPLETED`、`failed_works=0`、水位 500；⑤ `READY` + `graphServedQueries+1` | 0.251s |
| 3-8 | **初始化探针拦截** | `initializationProbeGatesGraphServing` | V7 断点四种非合格形态：行缺失（MISSING）、`RUNNING`、`FAILED`+`failed_works=1`、`COMPLETED` 但 generation 为旧代 | 四种均 `PROJECTION_NOT_INITIALIZED`、回退 PG、`graphServedQueries` 不变；恢复 `COMPLETED`+同代+0 失败后 `READY` 且由图服务 | 通过：4/4 原因码一致、回退计数逐次 +1、图服务计数不变；恢复后 `READY` 且 `graphServedQueries+1` | 0.165s |
| 3-9 | **水位/gen 守卫** | `watermarkAndGenerationGuards` | `projectedVersion=0`；generation=`UNINITIALIZED`；权威 gen 为空；跨代；`projectedVersion<publishedRevision` | 依次 `PROJECTION_NOT_INITIALIZED`×3、`GENERATION_MISMATCH`、`PROJECTION_STALE`；严格模式 503 `VIEW_UPDATING`；恢复后可服务 | 通过：5 个原因码全部命中；严格模式抛 `VIEW_UPDATING`；恢复后 `READY` | 0.177s |
| 3-10 | **事件失败不推水位（P1-4）** | `eventFailureNeverAdvancesWatermark` | 两条事件，第二条注入同步失败；另一轮事件指向 PG 已不存在的成果并注入 `deleteWork` 失败 | `failedWorks=1`、`stoppedEventId` 非空、`projectedRevision==fromRevision`、`GraphProjectionMeta` 原地不动；故障消除后同一批事件从同一修订重来并推进到 rev+3 | 通过：两轮均 `failedWorks=1` 且水位不变；恢复轮 `failedWorks=0`、水位 rev+3 | 0.396s |
| 3-11 | Neo4j 不可达 | `unreachableGraphFallsBackAndStrictReturns503` | 连接被拒绝的真实驱动（`bolt://127.0.0.1:1`） | 回退模式结果=PG、`graphUnavailableFallbackCount=1`；严格模式 `DEPENDENCY_UNAVAILABLE` 且端点 503 | 通过：响应=PG、计数正确；503 + `code=DEPENDENCY_UNAVAILABLE` | 5.037s |
| 3-12 | 对账修复 + 影子对照 | `reconcileRepairsDriftAndShadowCompareMatches` | 3 类漂移：删节点、造孤立节点、边证据标题过期 | 报 `WORK_MISSING_IN_GRAPH`/`WORK_ORPHANED_IN_GRAPH`/`EVIDENCE_TITLE_STALE`；`repair` 后 `consistent=true`；`SHADOW_COMPARE` 计入 `parityMatched=1` | 通过：三类码均报出、`retired=1`、修复后一致；`parityMatched=1`/`mismatched=0`/`skipped=0` | 0.332s |

### (4) 端点、回滚与规模/预算边界（4 项，`GraphEndToEndConsistencyIT`）

| # | 场景 | 方法 | 输入 | 预期结果 | 实测结果 | 耗时 |
| --- | --- | --- | --- | --- | --- | --- |
| 4-1 | 回滚一致性 | `rollbackKeepsResultsIdentical` | 先取图模式结果（3 个自我网络 + 4 条点对路径），再切 `POSTGRES_ONLY` 与 `graphBackend=null` | 两种回滚结果与回滚前逐字段一致；`graphBackend=null` 生效模式为 `POSTGRES_ONLY` | 通过：14 组响应全部 `parityMatched`；生效模式 `POSTGRES_ONLY` | 0.464s |
| 4-2 | 规模超限不降级 | `scopeTooLargeIsNotSilentlyDegraded` | 1 hub + 6 leaves；图服务 `maxNodes=3` | 直接抛 `GRAPH_SCOPE_TOO_LARGE`；回退/错误计数全 0 | 通过：错误码正确；`graphServed/graphUnavailable/graphError/stale` 均 0 | 0.242s |
| 4-3 | 预算超时不降级 | `timeoutIsNotSilentlyDegraded` | 5 节点链 + 每层 1100ms 延迟 + 预算 1000ms | 直接抛 `GRAPH_TIMEOUT`，不降级；正常预算 4 跳可达 | 通过：`GRAPH_TIMEOUT`、回退计数 0；同数据 `hops=4` | 1.303s |

### (5) 一致性（PG 与 Neo4j **逐字段**相同，含 `withinLimits`）

> `dev` 的 `NetworkParityComparator` 已把 `withinLimits.loadedNodes` / `withinLimits.loadedEdges` 纳入逐字段比较，
> 仅排除 `generatedAt`（时间戳不可比）。本节所有断言都走该比较器，不允许手写字段白名单。

| # | 场景 | 方法 | 输入 | 预期结果 | 实测结果 | 耗时 |
| --- | --- | --- | --- | --- | --- | --- |
| P-1 | 全点对一致性 | `neighborhoodAndPathParityOnFullDataset` | 10 篇论文（含 SIMULATED、4 作者、多篇共同论文）；6 种子 × 深度 1–3；7×7 点对 | 每组 `withinLimits` 与 `notes` 在内逐字段一致；等长路径两侧同选 `[a,b,e]` | 通过：18 组自我网络 + 49 组路径全部 `parityMatched`；`path=[a,b,e]` 两侧相同 | 1.735s |
| P-2 | 深度边界与不连通 | `depthBoundaryAndDisconnectedParity` | 8 节点链 + 两个不连通分量 | 6 跳 `hops=6`；7 跳 `reachable=false`；跨分量两侧不可达且说明相同 | 通过：4 组响应全部 `parityMatched` | 0.618s |
| P-3 | 高作者数与强度 | `highDegreeAndStrengthParity` | 4 作者论文（阈值 3）+ 同一对作者 2 篇 | 边集/强度/路径一致；`(a,b)`=3；`(a,f)`=1（二部图推导） | 通过：自我网络与路径均一致，强度 3 / 1 | 0.227s |
| P-4 | 筛选与数据性质 | `filteredAndDataNatureParity` | 6 种筛选（REAL/ALL/SIMULATED × topic/org）× 3 种子 | 每组一致；REAL 看不到模拟合作；SIMULATED 1 跳 | 通过：18 组响应全部一致；隔离结论正确 | 1.001s |
| P-5 | **报告过期不得误下线** | `staleReportResyncsInsteadOfRetiring` | 对账报告生成时 PG 为 RESTRICTED；修复前 PG 恢复 PUBLIC 且 `aggregate_version=70` | `repair` 的 `retiredWorks=0`、节点仍在、`source_version` 同步到 70、合作边仍在、端到端可达 | 通过：`retiredWorks=0`、节点 1、`source_version=70`、边 1、`s1→s2` 可达 | 0.363s |

---

## 4. 与 `dev` 最新修复（P1-1..P1-4 / D-1..D-3）的端到端回归对应

| 修复点（`dev` 已合入） | 本 PR 的端到端回归落点 | 实测 |
| --- | --- | --- |
| P1-1 回填「有任何失败成果即不可 COMPLETED、不推水位」 | `partialBatchFailureIsRecordedAndRepairable` ① | 通过：`status=FAILED`、水位 0 |
| P1-2 读侧必须读 PG 断点表（`PostgresGraphProjectionInitializationProbe`） | `initializationProbeGatesGraphServing`、`partialBatchFailureIsRecordedAndRepairable` ② | 通过：MISSING/RUNNING/FAILED/跨代 4 种形态均 `PROJECTION_NOT_INITIALIZED` |
| P1-3 单条事件同步/删除失败不得推进水位 | `eventFailureNeverAdvancesWatermark` | 通过：两轮 `failedWorks=1` 且水位不变，恢复后追平 |
| P1-4 修复后收尾入口 `repairAndComplete` / `markRepairedAndCompleted`（D-2/D-3） | `partialBatchFailureIsRecordedAndRepairable` ③④⑤ | 通过：`COMPLETED`、`failed_works=0`、水位 500、读侧立即由图服务 |
| 守卫对 generation 的严格判定（占位/空/跨代） | `watermarkAndGenerationGuards` | 通过：`PROJECTION_NOT_INITIALIZED`×3 + `GENERATION_MISMATCH` |
| `withinLimits` 纳入逐字段对照 | §3(5) P-1..P-4 | 通过：全部 `parityMatched` |
| `AchievementMerged` 用 `deleteWork` 彻底删除旧成果 | `achievementMergedDeletesOldWorkNodeAndEdges` | 通过：`worksDeleted=1`、旧节点/边 0、旧路径消失 |
| 对账报告过期时正向同步而非退场 | `staleReportResyncsInsteadOfRetiring` | 通过：`retiredWorks=0`、`source_version=70` |

---

## 5. 与任务书中若干表述的对应关系（如实说明）

| 任务书表述 | 本 PR 的实现落点 |
| --- | --- |
| 「请求 `maxDepth=99` 自动截断」 | 公开 API 没有 `maxDepth` 请求参数（路径深度上限是规则常量 `NetworkRules.MAX_PATH_DEPTH=6`，不接受客户端放宽）。截断语义落在自我网络的 `depth` 参数上：`depth=99` 被截断为 `MAX_NEIGHBORHOOD_DEPTH=3`，由 `pathDepthBoundaryAndNeighborhoodDepthClamp` 断言。路径侧以「6 跳可达 / 7 跳不可达」验证同一上限。 |
| 「`GRAPH_TIMEOUT` / `GRAPH_SCOPE_TOO_LARGE` 明确抛出」 | 应用层错误码为 `GRAPH_TIMEOUT` / `GRAPH_SCOPE_TOO_LARGE`。已同时验证「服务层抛错」与「路由不降级」两处。 |
| 「`NEO4J_STRICT` 下返回 503」 | 通过真实 MVC 栈 + 真实 `GlobalExceptionHandler` 断言：投影落后/未初始化 → `503 VIEW_UPDATING`；图库不可达 → `503 DEPENDENCY_UNAVAILABLE`。 |
| 「部分处理失败记入 `failed_works` 与 `last_error`」 | 落点是 V7 表 `qry_graph_projection_checkpoint.failed_works` / `last_error`；`dev` 的最新语义是「有失败即 FAILED」，本报告 §3-7 按该语义断言。 |
| 「Outbox/Inbox 重复投递」 | 图侧消费源是 `qry_inbox_event`（APPLIED 行）。测试同时验证 Inbox 唯一键去重（1 行）与投影器水位不重复消费。 |

### 需要评审注意的故障注入（不是 Mock 数据库）

以下三处是**在真实数据库之上注入故障**，用于触发只有故障时才出现的分支；其余所有读写都发生在真实
PostgreSQL / Neo4j 上：

1. `FailingStore`：装饰真实 `Neo4jGraphProjectionStore`，仅对指定成果抛 `IllegalStateException`
   （被判为 PERMANENT 域错误）。
2. `DeleteFailingStore`：只让指定成果的 `deleteWork` 抛错，验证「图内清理失败也不得推水位」。
3. `SlowRepository` / `SlowGraphRepository`：装饰真实 `Neo4jCollaborationGraphRepository`，在每次扩边前
   增加人为延迟，让时间预算真的被超过。

`CoreInternalClient` 是唯一的 Mock：它是 core 服务的 HTTP 客户端（跨服务依赖），不是数据库；
`PublicBarrierGuard` 的语义仍由真实 `qry_inbox_event`/`qry_consumer_state` 决定。

---

## 6. 未覆盖与残留风险

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 百万级真实数据下的图规模/性能 | 未覆盖 | 本报告只证明正确性与故障语义，不含吞吐/P95 结论（AGENTS.md §3.1 要求另附环境与数据分布）。 |
| 跨进程并发回填互斥 | 未覆盖 | 实现只做同进程 `AtomicBoolean` 互斥，跨进程需 PG advisory lock（已在代码注释中声明，不在本 PR 范围）。 |
| Huawei Cloud CSS/GES 目标拓扑 | 未覆盖 | 本 PR 只验证 Neo4j 路径；换存储需另立 Issue 并重新证明路径规则。 |
| Neo4j 真实网络分区（半开连接、超时抖动） | 部分覆盖 | 只覆盖「连接被拒绝」；连接超时/事务重试窗口用短重试配置模拟。 |
| 前端契约 | 未覆盖 | 本 PR 只断言后端响应字段；前端页面回归在 `frontend` 的测试范围内。 |
| 同一 Neo4j 实例上的类间隔离 | 已缓解 | 各 IT 类自清理（前缀清理；两个新类额外在 `@BeforeAll` 做整库清理），`GraphProjectionSyncIT` 的「外来数据即跳过」守卫在 5 类连跑中未触发（`skipped=0`）。 |

---

## 7. CI `graph-integration` job 等价验证（本机逐字复跑）

`.github/workflows/ci.yml` 的 `graph-integration` job 执行：

```bash
./mvnw -B -ntp -Pci -Dcn.it.skip=false -DfailIfNoTests=false \
  -Dit.test='*Graph*IT,*Neo4j*IT' \
  -Dtest='NoSuchGraphUnitTest' -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dspotless.check.skip=true -Djacoco.skip=true \
  verify
bash ops/verify/ci-test-report-gate.sh --modules services/query-service \
  --it --it-select '*Graph*IT,*Neo4j*IT'
```

本机逐字复跑结果（同一个 failsafe JVM 连续执行 5 个图 IT 类）：

```
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- in Neo4jSchemaIT
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- in NetworkGraphQueryIT
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0 -- in GraphSemanticsAndEdgeCasesIT
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0 -- in GraphProjectionSyncIT
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0 -- in GraphEndToEndConsistencyIT
[INFO] Tests run: 76, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

== 测试证据核对 ==
  模块：services/query-service
  检查项：集成测试
  集成测试：76 个用例 / 0 失败 / 0 错误 / 0 跳过
结果：通过 —— 报告与源码一一对应，无跳过，覆盖率门禁真实生效
EXIT=0
```

数据库环境核对（与 CI `graph-integration` job 的 `postgres:16-alpine` 一致）：

- 两个新类连 `127.0.0.1:55432/query_graph_it`，用超级用户 `postgres/postgres_it` 自动建库并执行 V1..V7
  全部迁移（与 `GraphProjectionSyncIT` 同一策略：独立库，避免与 `query_it` 的其它 IT 相互清库）；
- CI job 已用超级用户预建 `core_it` / `query_it` 并创建 `core_owner`/`core_app`/`query_owner`/`query_app`，
  `postgres` 仍是超级用户，因此 `CREATE DATABASE query_graph_it` 成立（本机同样以超级用户建库验证通过）；
- `GraphEndToEndConsistencyIT` 用 `@SpringBootTest` 连 `query_it`（`PostgresTestDatabase.DEFAULT_URL`），
  与其既有 IT 一致的连接串与账号，不新增数据库/角色要求。

---

## 8. 复现方式

```bash
# 1) 隔离测试基础设施（PostgreSQL 55432 / Neo4j 17687）
bash ops/local/test-db.sh start
bash ops/verify/test-infra-smoke.sh up

# 2) 全量验证（单元 + 真实数据库集成 + Spotless + JaCoCo 三条门禁）
./mvnw -B -ntp -pl services/query-service -am verify -Dcn.it.skip=false

# 3) 只跑本 PR 新增的两个集成测试类
./mvnw -B -ntp -pl services/query-service -am -Pci verify -Dcn.it.skip=false \
  -Dit.test='GraphSemanticsAndEdgeCasesIT,GraphEndToEndConsistencyIT' \
  -Dtest=zzzNoUnitTest -Dsurefire.failIfNoSpecifiedTests=false

# 4) CI graph-integration job 等价复跑
./mvnw -B -ntp -Pci -Dcn.it.skip=false -DfailIfNoTests=false \
  -Dit.test='*Graph*IT,*Neo4j*IT' \
  -Dtest='NoSuchGraphUnitTest' -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dspotless.check.skip=true -Djacoco.skip=true verify
bash ops/verify/ci-test-report-gate.sh --modules services/query-service \
  --it --it-select '*Graph*IT,*Neo4j*IT'
```

若实例不可达，测试类会以 `Assumptions.abort` 跳过并打印启动方式；**跳过不会被计为通过**，
`ci-test-report-gate.sh` 对 `skipped > 0` 直接失败，因此 CI 里出现 `Skipped>0` 应视为「没有验证」。
