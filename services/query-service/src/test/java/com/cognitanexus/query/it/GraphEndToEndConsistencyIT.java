package com.cognitanexus.query.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cognitanexus.query.consistency.PublicBarrierGuard;
import com.cognitanexus.query.corelink.CoreInternalClient;
import com.cognitanexus.query.graph.application.CollaborationGraphRepository;
import com.cognitanexus.query.graph.application.CollaborationGraphService;
import com.cognitanexus.query.graph.application.GraphProjectionInitializationProbe;
import com.cognitanexus.query.graph.application.GraphProjectionStore;
import com.cognitanexus.query.graph.config.Neo4jGraphProperties;
import com.cognitanexus.query.graph.domain.GraphAuthorship;
import com.cognitanexus.query.graph.domain.GraphFilterCriteria;
import com.cognitanexus.query.graph.domain.GraphProjectionMeta;
import com.cognitanexus.query.graph.domain.GraphScholarNode;
import com.cognitanexus.query.graph.infrastructure.Neo4jCollaborationGraphRepository;
import com.cognitanexus.query.graph.infrastructure.Neo4jGraphProjectionStore;
import com.cognitanexus.query.graph.infrastructure.Neo4jSessionRunner;
import com.cognitanexus.query.graph.projection.application.GraphBackfillResult;
import com.cognitanexus.query.graph.projection.application.GraphBackfillService;
import com.cognitanexus.query.graph.projection.application.GraphEventProjectionResult;
import com.cognitanexus.query.graph.projection.application.GraphEventProjector;
import com.cognitanexus.query.graph.projection.application.GraphProjectionCheckpointStore;
import com.cognitanexus.query.graph.projection.application.GraphProjectionProperties;
import com.cognitanexus.query.graph.projection.application.GraphProjectionReconciler;
import com.cognitanexus.query.graph.projection.application.GraphProjectionReconciler.ReconcileOptions;
import com.cognitanexus.query.graph.projection.application.GraphProjectionRetryPolicy;
import com.cognitanexus.query.graph.projection.application.GraphProjectionSource;
import com.cognitanexus.query.graph.projection.application.GraphProjectionSynchronizer;
import com.cognitanexus.query.graph.projection.application.GraphRepairResult;
import com.cognitanexus.query.graph.projection.domain.GraphBackfillStatus;
import com.cognitanexus.query.graph.projection.domain.GraphProjectedWork;
import com.cognitanexus.query.graph.projection.domain.GraphReconciliationReport;
import com.cognitanexus.query.graph.projection.worker.GraphProjectionWorker;
import com.cognitanexus.query.graph.schema.Neo4jSchemaInitializer;
import com.cognitanexus.query.network.api.PublicNetworkController;
import com.cognitanexus.query.network.application.GraphNetworkBackend;
import com.cognitanexus.query.network.application.NetworkGraphQueryMetrics;
import com.cognitanexus.query.network.application.NetworkGraphQueryService;
import com.cognitanexus.query.network.application.NetworkParityComparator;
import com.cognitanexus.query.network.application.NetworkQueryRouter;
import com.cognitanexus.query.network.application.NetworkService;
import com.cognitanexus.query.network.application.ProjectionWatermarkGuard;
import com.cognitanexus.query.network.config.NetworkGraphQueryProperties;
import com.cognitanexus.query.network.domain.GraphProjectionStatus;
import com.cognitanexus.query.network.domain.GraphQueryMode;
import com.cognitanexus.query.network.domain.ParityDiffReport;
import com.cognitanexus.query.network.infrastructure.NetworkMapper;
import com.cognitanexus.query.platform.persistence.JsonCodec;
import com.cognitanexus.query.platform.web.GlobalExceptionHandler;
import com.cognitanexus.query.projection.application.ProjectionProperties;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 阶段五（端到端一致性与故障恢复）：真实 PostgreSQL Outbox/Inbox → 真实投影器 → 真实 Neo4j →
 * {@code NetworkQueryRouter} 图查询/对照/降级/对账恢复的完整链路验证。
 *
 * <p>本类与 {@code NetworkGraphQueryIT} 的区别不是「再测一遍」，而是把两条链路真正接起来：
 *
 * <ul>
 *   <li>图里的数据<b>不是</b>测试直接写进 Neo4j 的，而是由生产代码 {@code GraphBackfillService} /
 *       {@code GraphEventProjector} 从 PG 读投影读出后写入的；只有这样才能证明「PG 变更 → 图可见」；
 *   <li>每一条一致性断言都是「同一请求在 PG 权威实现与图实现上逐字段相同」，差异由
 *       {@code NetworkParityComparator} 判定，不使用手写字段比较；
 *   <li>故障场景（图库不可达、投影落后、回填中断、批次失败、乱序事件、回滚）断言的是
 *       <b>可观测结果</b>：HTTP 状态、指标增量、断点里的 {@code failed_works/last_error}、对账报告码。
 * </ul>
 *
 * <p>数据隔离：独立 generation / consumer / Neo4j scope + 本次运行前缀；每个用例前只清理自己写入的
 * 行与节点。Neo4j 侧在 {@code @BeforeAll} 做一次整库清理（图是可丢弃的派生投影，测试实例
 * {@code 127.0.0.1:17687} 为隔离实例），之后所有清理都按前缀进行。
 *
 * <p>外部依赖：只有 core 的公开水位（HTTP）被替换为固定值——那是跨服务调用的桩，不是数据库；
 * PostgreSQL 与 Neo4j 全部是真实实例。实例不可达时整类跳过，<b>不把跳过报告成通过</b>。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "cn.projection.worker-enabled=false",
      "cn.query.graph.enabled=true",
      "cn.query.graph.fail-fast-on-startup=false",
      "cn.query.graph.query.mode=NEO4J_WITH_PG_FALLBACK",
      "cn.query.graph.query.shadow-compare-async=false",
      // 4 位作者 > 阈值 3：图侧只写 AUTHORED 由二部图推导，PG 侧物化全部 6 对，两侧必须一致。
      "cn.query.graph.max-authors-per-work-for-materialized-edge=3",
      "cn.query.graph.max-nodes=20000",
      "cn.query.graph.max-edges=100000",
      "cn.query.graph.budget-millis=2000",
      "cn.projection.graph-max-nodes=20000",
      "cn.projection.graph-max-edges=100000",
      "cn.projection.graph-budget-millis=2000",
      "cn.query.graph.projection.enabled=true",
      "cn.query.graph.projection.batch-size=2",
      "cn.query.graph.projection.worker-enabled=false"
    })
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GraphEndToEndConsistencyIT {

  private static final String PREFIX = "it-e2e-" + UUID.randomUUID().toString().substring(0, 8);
  private static final String GENERATION = PREFIX + "-gen";
  private static final String SCOPE = PREFIX + "-scope";
  private static final String CONSUMER = PREFIX + "-consumer";
  private static final long REVISION = 500L;

  private static final String URI =
      System.getProperty(
          "cn.neo4j.uri", System.getenv().getOrDefault("CN_NEO4J_URI", "bolt://127.0.0.1:17687"));
  private static final String USER =
      System.getProperty("cn.neo4j.user", System.getenv().getOrDefault("CN_NEO4J_USER", "neo4j"));
  private static final String PASSWORD =
      System.getProperty(
          "cn.neo4j.password",
          System.getenv().getOrDefault("CN_NEO4J_PASSWORD", "cn_test_neo4j_2026"));

  @Autowired private JdbcTemplate jdbc;
  @Autowired private NetworkService networkService;
  @Autowired private NetworkQueryRouter router;
  @Autowired private NetworkParityComparator comparator;
  @Autowired private NetworkGraphQueryMetrics metrics;
  @Autowired private PublicBarrierGuard barrierGuard;
  @Autowired private GraphNetworkBackend graphBackend;
  @Autowired private Neo4jSchemaInitializer schemaInitializer;
  @Autowired private GraphProjectionStore projectionStore;
  @Autowired private Neo4jSessionRunner runner;
  @Autowired private NetworkMapper networkMapper;
  @Autowired private ProjectionProperties projectionProperties;
  @Autowired private Neo4jGraphProperties neo4jProperties;
  @Autowired private GraphBackfillService backfillService;
  @Autowired private GraphEventProjector projector;
  @Autowired private GraphProjectionReconciler reconciler;
  @Autowired private GraphProjectionSource projectionSource;
  @Autowired private GraphProjectionCheckpointStore checkpoints;
  @Autowired private GraphProjectionInitializationProbe initializationProbe;
  @Autowired private GraphProjectionSynchronizer projectionSynchronizer;
  @Autowired private GraphProjectionProperties projectionSettings;
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  /** 公开资格屏障每次向 core 取权威水位；测试里固定返回与本地投影一致的修订。 */
  @MockitoBean private CoreInternalClient core;

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> PostgresTestDatabase.DEFAULT_URL);
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("spring.datasource.password", () -> "postgres_it");
    registry.add("spring.flyway.url", () -> PostgresTestDatabase.DEFAULT_URL);
    registry.add("spring.flyway.user", () -> "postgres");
    registry.add("spring.flyway.password", () -> "postgres_it");
    registry.add("cn.query.graph.uri", () -> URI);
    registry.add("cn.query.graph.username", () -> USER);
    registry.add("cn.query.graph.password", () -> PASSWORD);
    registry.add("cn.query.graph.scope", () -> SCOPE);
    // 独立 consumer：不与同一实例上其他 IT 的 'query' 水位互相覆盖。
    registry.add("cn.projection.consumer-id", () -> CONSUMER);
  }

  @BeforeAll
  void prepareDatabases() {
    if (!schemaInitializer.isReady()) {
      Assumptions.abort(
          "Neo4j 图库实例不可用或 schema 初始化失败（"
              + URI
              + "）。先执行 bash ops/verify/test-infra-smoke.sh up 与 ops/local/test-db.sh start 再重跑；"
              + "本类不做「通过」记录。");
    }
    assertThat(neo4jProperties.scope()).as("测试必须使用独立作用域，不能写共享 query-public").isEqualTo(SCOPE);
    jdbc.update(
        "INSERT INTO qry_generation (generation, status, ready_at, note)"
            + " VALUES (?, 'READY', now(), 'GraphEndToEndConsistencyIT')"
            + " ON CONFLICT (generation) DO NOTHING",
        GENERATION);
    // 计数级对账要求图里只有本类数据：测试实例上的图是可丢弃的派生投影。
    runner.write(
        tx -> {
          tx.run("MATCH (w:Work) DETACH DELETE w", Map.of()).consume();
          tx.run("MATCH (s:Scholar) DETACH DELETE s", Map.of()).consume();
          tx.run("MATCH (m:GraphProjectionMeta) DELETE m", Map.of()).consume();
          return null;
        });
  }

  @BeforeEach
  void reset() {
    when(core.readBarrier()).thenReturn(new CoreInternalClient.PublicBarrier(REVISION, REVISION));
    wipeGraph();
    cleanupPostgres();
    seedConsumerState();
  }

  @AfterAll
  void cleanUp() {
    wipeGraph();
    cleanupPostgres();
    jdbc.update("DELETE FROM qry_generation WHERE generation = ?", GENERATION);
  }

  // ===========================================================================
  // 投影水位 / 降级
  // ===========================================================================

  @Test
  @Order(1)
  @DisplayName("投影无水位：公开查询回退 PG 并计数；NEO4J_STRICT 下返回 503 VIEW_UPDATING 而不是伪装成功")
  void missingProjectionNeverServesGraphAndStrictReturns503() throws Exception {
    seedCollaboration("s1", "s2", "REAL", List.of(), List.of());

    Map<String, Object> pgView = networkService.neighborhood(seed("s1"), 2, 0, "REAL", null, null);
    long staleFallbacks = metric("staleProjectionFallbackCount");
    long graphServed = metric("graphServedQueries");

    Map<String, Object> routed = router.neighborhood(seed("s1"), 2, 0, "REAL", null, null);

    assertSameResponse("missing-meta", pgView, routed);
    assertThat(metric("staleProjectionFallbackCount")).isEqualTo(staleFallbacks + 1);
    assertThat(metric("graphServedQueries")).as("水位缺失时不得由图库提供服务").isEqualTo(graphServed);
    assertThat(graphBackend.status(REVISION, GENERATION).reasonCode())
        .isEqualTo(GraphProjectionStatus.PROJECTION_META_MISSING);

    NetworkQueryRouter strict =
        routerWithMode(GraphQueryMode.NEO4J_STRICT, graphBackend, new NetworkGraphQueryMetrics());
    assertThatThrownBy(() -> strict.neighborhood(seed("s1"), 1, 0, "REAL", null, null))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).errorCode())
                    .isEqualTo(ErrorCode.VIEW_UPDATING));

    // 真实 MVC 栈 + 真实全局异常处理器：严格模式下公开端点必须是 503，而不是 200 + PG 结果。
    MockMvc strictMvc =
        MockMvcBuilders.standaloneSetup(new PublicNetworkController(strict))
            .setControllerAdvice(new GlobalExceptionHandler(objectMapper))
            .build();
    strictMvc
        .perform(get("/api/v1/public/network/graph").param("scholarId", seed("s1")))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("VIEW_UPDATING"));
  }

  @Test
  @Order(2)
  @DisplayName("投影落后：ProjectionWatermarkGuard 拦截并回退 PG，绝不静默返回过期图")
  void staleProjectionIsNeverServed() {
    seedCollaboration("s1", "s2", "REAL", List.of(), List.of());
    backfillService.backfill(SCOPE);
    // 已发布修订推进到 520，但图仍停在 500：图落后一版。
    advanceProjection(REVISION);
    when(core.readBarrier())
        .thenReturn(new CoreInternalClient.PublicBarrier(REVISION + 20, REVISION + 20));
    jdbc.update(
        "UPDATE qry_consumer_state SET published_revision = ?, received_revision = ?"
            + " WHERE consumer_id = ?",
        REVISION + 20,
        REVISION + 20,
        CONSUMER);

    long staleFallbacks = metric("staleProjectionFallbackCount");
    long graphServed = metric("graphServedQueries");

    Map<String, Object> pgView = networkService.neighborhood(seed("s1"), 2, 0, "REAL", null, null);
    Map<String, Object> routed = router.neighborhood(seed("s1"), 2, 0, "REAL", null, null);

    assertSameResponse("stale-projection", pgView, routed);
    assertThat(metric("staleProjectionFallbackCount")).isEqualTo(staleFallbacks + 1);
    assertThat(metric("graphServedQueries")).isEqualTo(graphServed);
    assertThat(graphBackend.status(REVISION + 20, GENERATION).reasonCode())
        .isEqualTo(GraphProjectionStatus.PROJECTION_STALE);
  }

  @Test
  @Order(3)
  @DisplayName("公开端点在降级期间仍返回 200 与 PG 权威结果（真实 MVC 栈，非 Mock 控制器）")
  void publicEndpointReturns200DuringFallback() throws Exception {
    seedCollaboration("s1", "s2", "REAL", List.of(), List.of());

    Map<String, Object> pgView = networkService.neighborhood(seed("s1"), 1, 0, "REAL", null, null);
    assertThat(pgView.get("seedInScope")).isEqualTo(true);
    assertThat(pgView.get("depth")).isEqualTo(1);

    mockMvc
        .perform(
            get("/api/v1/public/network/graph").param("scholarId", seed("s1")).param("depth", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("OK"))
        .andExpect(jsonPath("$.data.seed").value(seed("s1")))
        .andExpect(jsonPath("$.data.seedInScope").value(true))
        .andExpect(jsonPath("$.data.depth").value(1))
        .andExpect(jsonPath("$.data.edges.length()").value(1))
        .andExpect(jsonPath("$.data.edges[0].source").value(seed("s1")))
        .andExpect(jsonPath("$.data.edges[0].target").value(seed("s2")))
        .andExpect(jsonPath("$.data.edges[0].strength").value(1))
        .andExpect(jsonPath("$.data.ruleVersion").value("NETWORK_V1"));

    mockMvc
        .perform(
            get("/api/v1/public/network/path").param("from", seed("s1")).param("to", seed("s2")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.reachable").value(true))
        .andExpect(jsonPath("$.data.hops").value(1));
  }

  // ===========================================================================
  // 回填 / 增量
  // ===========================================================================

  @Test
  @Order(4)
  @DisplayName("回填未完成时增量 Worker 不越界推高水位，查询继续走 PG；续跑完成后图才开始服务")
  void backfillInProgressBlocksWatermarkAdvance() {
    for (int i = 1; i <= 5; i++) {
      seedWork(
          "r" + i,
          "REAL",
          "回填中断论文 " + i,
          List.of(),
          List.of(),
          List.of(seed("r" + i + "-a"), seed("r" + i + "-b")));
    }

    GraphBackfillService interrupted =
        new GraphBackfillService(
            new InterruptingSource(projectionSource, 2),
            projectionSynchronizer,
            projectionStore,
            checkpoints,
            projectionSettings,
            CONSUMER);
    assertThatThrownBy(() -> interrupted.backfill(SCOPE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("模拟中断");

    var checkpoint = checkpoints.find(SCOPE).orElseThrow();
    assertThat(checkpoint.status()).isIn(GraphBackfillStatus.RUNNING, GraphBackfillStatus.FAILED);
    assertThat(checkpoint.processedWorks()).isEqualTo(2);
    long projectedBefore = projectedVersion();
    assertThat(projectedBefore).isZero();

    // 一条本应被增量消费的事件：如果 Worker 无视「回填未完成」把它吃掉，水位就会被推到 500，
    // 而图里只有 2/5 的成果——那正是「半张图冒充完整图」。
    jdbc.update(
        "UPDATE qry_contribution SET relation_state = 'SELF_CONFIRMED'"
            + " WHERE generation = ? AND achievement_id = ? AND scholar_id = ?",
        GENERATION,
        work("r1"),
        seed("r1-a"));
    appliedEvent(
        "evt-" + PREFIX + "-1",
        "CatalogChangeCommitted",
        REVISION,
        "{\"changeKind\":\"UPSERT\",\"achievementId\":\"" + work("r1") + "\"}");

    GraphProjectionWorker worker =
        new GraphProjectionWorker(
            projector,
            projectionStore,
            projectionSource,
            checkpoints,
            new GraphProjectionProperties(true, 2, 3, 0L, 100L, true, 5_000L, 200, 500, 10, true),
            SCOPE,
            CONSUMER);
    worker.catchUp();

    assertThat(worker.projectedVersion()).as("回填未完成时 Worker 必须跳过，水位停在 0").isZero();

    long staleFallbacks = metric("staleProjectionFallbackCount");
    Map<String, Object> pgView =
        networkService.neighborhood(seed("r1-a"), 1, 0, "REAL", null, null);
    Map<String, Object> routed = router.neighborhood(seed("r1-a"), 1, 0, "REAL", null, null);
    assertSameResponse("backfill-running", pgView, routed);
    assertThat(metric("staleProjectionFallbackCount")).isEqualTo(staleFallbacks + 1);

    // 续跑（真实回填服务）完成后水位追平发布修订，图查询开始对外提供。
    GraphBackfillResult resumed = backfillService.backfill(SCOPE);
    assertThat(resumed.status()).isEqualTo(GraphBackfillStatus.COMPLETED);
    assertThat(resumed.worksThisRun()).isEqualTo(3);
    assertThat(projectedVersion()).isEqualTo(REVISION);

    long graphServed = metric("graphServedQueries");
    Map<String, Object> served = router.neighborhood(seed("r1-a"), 1, 0, "REAL", null, null);
    assertSameResponse("backfill-completed", pgView, served);
    assertThat(metric("graphServedQueries")).isEqualTo(graphServed + 1);
  }

  @Test
  @Order(5)
  @DisplayName("Outbox/Inbox 重复投递幂等：同一事件重复落库只产生一次投影副作用，水位只前进一步")
  void duplicateEventDeliveryIsIdempotent() {
    seedWork("d1", "REAL", "重复投递论文", List.of(), List.of(), List.of(seed("d1"), seed("d2")));
    backfillService.backfill(SCOPE);
    long edgesBefore =
        graphCount(
            "MATCH ()-[c:COOPERATED_WITH]->() WHERE c.work_id STARTS WITH $p RETURN count(c) AS n");

    jdbc.update(
        "UPDATE qry_contribution SET relation_state = 'SELF_CONFIRMED'"
            + " WHERE generation = ? AND achievement_id = ? AND scholar_id = ?",
        GENERATION,
        work("d1"),
        seed("d1"));
    jdbc.update(
        "UPDATE qry_achievement SET aggregate_version = 60 WHERE generation = ? AND achievement_id = ?",
        GENERATION,
        work("d1"));
    String payload = "{\"changeKind\":\"UPSERT\",\"achievementId\":\"" + work("d1") + "\"}";
    appliedEvent("evt-" + PREFIX + "-dup", "CatalogChangeCommitted", REVISION + 5, payload);
    // 同一 eventId 再次投递：Inbox 唯一键 (consumer_id, event_id) 使其成为一行，不产生第二条事件。
    appliedEvent("evt-" + PREFIX + "-dup", "CatalogChangeCommitted", REVISION + 5, payload);

    Integer rows =
        jdbc.queryForObject(
            "SELECT count(*) FROM qry_inbox_event WHERE consumer_id = ? AND event_id = ?",
            Integer.class,
            CONSUMER,
            "evt-" + PREFIX + "-dup");
    assertThat(rows).as("重复投递必须只落一行").isEqualTo(1);

    GraphEventProjectionResult first = projector.applyPending(SCOPE, 50);
    assertThat(first.eventsRead()).isEqualTo(1);
    assertThat(first.projectedRevision()).isEqualTo(REVISION + 5);
    assertThat(first.failedWorks()).isZero();

    long edgesAfterFirst =
        graphCount(
            "MATCH ()-[c:COOPERATED_WITH]->() WHERE c.work_id STARTS WITH $p RETURN count(c) AS n");
    GraphEventProjectionResult second = projector.applyPending(SCOPE, 50);

    assertThat(second.eventsRead()).as("水位已覆盖该修订，事件不会被再次读取").isZero();
    assertThat(second.projectedRevision()).isEqualTo(REVISION + 5);
    assertThat(
            graphCount(
                "MATCH ()-[c:COOPERATED_WITH]->() WHERE c.work_id STARTS WITH $p RETURN count(c) AS n"))
        .as("重复投递不得翻倍边数")
        .isEqualTo(edgesAfterFirst)
        .isGreaterThanOrEqualTo(edgesBefore);
  }

  @Test
  @Order(6)
  @DisplayName("事件乱序/迟到：图中已是 v60 时收到 v50 旧值被版本守卫拒绝，水位不倒退")
  void lateOutOfOrderEventIsRejectedByVersionGuard() {
    seedWork("o1", "REAL", "迟到事件论文", List.of(), List.of(), List.of(seed("o1"), seed("o2")));
    backfillService.backfill(SCOPE);

    jdbc.update(
        "UPDATE qry_contribution SET relation_state = 'SELF_CONFIRMED'"
            + " WHERE generation = ? AND achievement_id = ? AND scholar_id = ?",
        GENERATION,
        work("o1"),
        seed("o1"));
    jdbc.update(
        "UPDATE qry_achievement SET aggregate_version = 60 WHERE generation = ? AND achievement_id = ?",
        GENERATION,
        work("o1"));
    projectionSynchronizer.syncWork(GENERATION, work("o1")).orElseThrow();
    long authoredAfterNewest =
        graphCount(
            "MATCH (:Scholar)-[x:AUTHORED]->() WHERE x.work_id STARTS WITH $p RETURN count(x) AS n");

    // 迟到事件：PG 聚合版本回落到 50（模拟旧快照重放），事件仍被消费。
    jdbc.update(
        "UPDATE qry_achievement SET aggregate_version = 50 WHERE generation = ? AND achievement_id = ?",
        GENERATION,
        work("o1"));
    appliedEvent(
        "evt-" + PREFIX + "-late",
        "CatalogChangeCommitted",
        REVISION + 10,
        "{\"changeKind\":\"UPSERT\",\"achievementId\":\"" + work("o1") + "\"}");

    GraphEventProjectionResult result = projector.applyPending(SCOPE, 50);

    assertThat(result.failedWorks()).isZero();
    assertThat(result.worksStale()).as("旧版本必须被记为 STALE 而不是错误").isEqualTo(1);
    assertThat(result.projectedRevision()).isEqualTo(REVISION + 10);
    assertThat(
            graphCount(
                "MATCH (:Scholar)-[x:AUTHORED]->() WHERE x.work_id STARTS WITH $p RETURN count(x) AS n"))
        .as("版本守卫必须拒绝把新值改回旧值")
        .isEqualTo(authoredAfterNewest);
    assertThat(
            graphCount(
                "MATCH (w:Work {work_id: $w}) RETURN w.source_version AS n",
                Map.of("w", work("o1"))))
        .as("图上仍应是 v60")
        .isEqualTo(60L);
  }

  @Test
  @Order(7)
  @DisplayName("回填失败→断点 FAILED→读侧拒绝→repairAndComplete 收尾→水位推进→立即由图库服务（端到端闭环）")
  void partialBatchFailureIsRecordedAndRepairable() {
    String failing = work("p3");
    seedWork("p1", "REAL", "批次论文一", List.of(), List.of(), List.of(seed("p1-a"), seed("p1-b")));
    seedWork("p2", "REAL", "批次论文二", List.of(), List.of(), List.of(seed("p2-a"), seed("p2-b")));
    seedWork("p3", "REAL", "批次论文三", List.of(), List.of(), List.of(seed("p3-a"), seed("p3-b")));

    GraphBackfillService withFaultyStore =
        new GraphBackfillService(
            projectionSource,
            new GraphProjectionSynchronizer(
                projectionSource,
                new FailingStore(projectionStore, failing),
                GraphProjectionRetryPolicy.noRetry(),
                millis -> {}),
            projectionStore,
            checkpoints,
            projectionSettings,
            CONSUMER);

    GraphBackfillResult result = withFaultyStore.backfill(SCOPE);

    var checkpoint = checkpoints.find(SCOPE).orElseThrow();
    assertThat(checkpoint.failedWorks()).as("失败成果必须单独计数").isEqualTo(1);
    assertThat(checkpoint.lastError()).contains(failing).contains("FAILED_PERMANENT");
    assertThat(result.status())
        .as("只要有一条成果失败，回填就不能标 COMPLETED（ADR-005 §5.2）")
        .isEqualTo(GraphBackfillStatus.FAILED);
    assertThat(checkpoint.status()).isEqualTo(GraphBackfillStatus.FAILED);
    assertThat(checkpoint.processedWorks()).as("processed_works 只计真正写进图的成果").isEqualTo(2);
    assertThat(
            graphCount("MATCH (w:Work {work_id: $w}) RETURN count(w) AS n", Map.of("w", failing)))
        .as("失败的成果确实没有被写进图")
        .isZero();
    assertThat(projectedVersion()).as("断点未完成时水位不得推进").isZero();

    // 读侧闭环：回填失败时图里连水位节点都没有 → 必须先报 META_MISSING（而不是错报成「落后」）。
    assertThat(graphBackend.status(REVISION, GENERATION).reasonCode())
        .isEqualTo(GraphProjectionStatus.PROJECTION_META_MISSING);

    // 危险形态：水位节点被上一轮遗留为非零，而断点仍是 FAILED/failed_works=1。
    // 此时只看「projectedVersion >= publishedRevision」会放行一张缺边的图，探针必须拦住。
    advanceProjection(REVISION);
    assertThat(graphBackend.status(REVISION, GENERATION).reasonCode())
        .as("failed_works>0 的断点必须被判为未初始化")
        .isEqualTo(GraphProjectionStatus.PROJECTION_NOT_INITIALIZED);
    long staleFallbacks = metric("staleProjectionFallbackCount");
    long graphServedBefore = metric("graphServedQueries");
    Map<String, Object> pgView =
        networkService.neighborhood(seed("p1-a"), 1, 0, "REAL", null, null);
    Map<String, Object> routed = router.neighborhood(seed("p1-a"), 1, 0, "REAL", null, null);
    assertSameResponse("failed-backfill", pgView, routed);
    assertThat(metric("staleProjectionFallbackCount")).isEqualTo(staleFallbacks + 1);
    assertThat(metric("graphServedQueries"))
        .as("断点 FAILED 时不得由图库提供服务")
        .isEqualTo(graphServedBefore);

    GraphReconciliationReport report = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());
    assertThat(report.missingWorks()).contains(failing);
    assertThat(report.findings())
        .extracting(finding -> finding.code())
        .contains("WORK_MISSING_IN_GRAPH");

    // 收尾只能通过「修复 + 复核对账零差异」发生：repair 单独调用不改断点与水位。
    GraphRepairResult repair = reconciler.repair(report);
    assertThat(repair.failedWorks()).isZero();
    assertThat(repair.repairedWorks()).isGreaterThanOrEqualTo(1);
    assertThat(
            graphCount("MATCH (w:Work {work_id: $w}) RETURN count(w) AS n", Map.of("w", failing)))
        .isEqualTo(1L);
    assertThat(checkpoints.find(SCOPE).orElseThrow().status())
        .as("repair 不负责解除封锁：断点仍须保持 FAILED")
        .isEqualTo(GraphBackfillStatus.FAILED);
    assertThat(graphBackend.status(REVISION, GENERATION).reasonCode())
        .as("单条补齐后断点仍 FAILED，读侧仍拒绝")
        .isEqualTo(GraphProjectionStatus.PROJECTION_NOT_INITIALIZED);

    // repairAndComplete：修复 → 重新对账 → 断点收尾 + 水位推进（真实 PG + 真实 Neo4j）。
    GraphRepairResult completed =
        reconciler.repairAndComplete(report, ReconcileOptions.defaults(), backfillService);
    assertThat(completed.failedWorks()).isZero();
    var repairedCheckpoint = checkpoints.find(SCOPE).orElseThrow();
    assertThat(repairedCheckpoint.status()).isEqualTo(GraphBackfillStatus.COMPLETED);
    assertThat(repairedCheckpoint.failedWorks()).isZero();
    assertThat(projectedVersion()).as("收尾后水位推进到快照修订").isEqualTo(REVISION);
    assertThat(reconciler.reconcile(SCOPE, ReconcileOptions.defaults()).consistent()).isTrue();

    // 读侧立即恢复：同一条请求现在由图库服务，结果与 PG 逐字段相同。
    assertThat(graphBackend.status(REVISION, GENERATION).reasonCode())
        .isEqualTo(GraphProjectionStatus.READY);
    long servedBefore = metric("graphServedQueries");
    Map<String, Object> healed = router.neighborhood(seed("p1-a"), 1, 0, "REAL", null, null);
    assertSameResponse("repaired-backfill", pgView, healed);
    assertThat(metric("graphServedQueries")).isEqualTo(servedBefore + 1);
  }

  @Test
  @Order(8)
  @DisplayName("初始化探针拦截：断点 MISSING/RUNNING/FAILED/跨代时读侧一律 PROJECTION_NOT_INITIALIZED，COMPLETED 才放行")
  void initializationProbeGatesGraphServing() {
    seedCollaboration("g1", "g2", "REAL", List.of(), List.of());
    backfillService.backfill(SCOPE);
    advanceProjection(REVISION);
    assertThat(graphBackend.status(REVISION, GENERATION).reasonCode())
        .as("COMPLETED 且 failed_works=0 才可服务")
        .isEqualTo(GraphProjectionStatus.READY);

    // (1) V7 断点行不存在 → MISSING（表已部署但没有该 scope 的行）。
    jdbc.update("DELETE FROM qry_graph_projection_checkpoint WHERE scope = ?", SCOPE);
    assertNotInitialized("MISSING 断点");

    // (2) RUNNING：回填正在进行，图只有半成品。
    writeCheckpoint("RUNNING", 0L, GENERATION);
    assertNotInitialized("RUNNING 断点");

    // (3) FAILED 且 failed_works>0：图缺边，缺边会被读成「这两位不合作」。
    writeCheckpoint("FAILED", 1L, GENERATION);
    assertNotInitialized("FAILED 且 failed_works=1");

    // (4) COMPLETED 但属于旧代：这份「完成」不是当前代的证据。
    writeCheckpoint("COMPLETED", 0L, GENERATION + "-old");
    assertNotInitialized("COMPLETED 但跨代");

    // (5) COMPLETED 且同代零失败：放行，查询由图库服务。
    writeCheckpoint("COMPLETED", 0L, GENERATION);
    assertThat(graphBackend.status(REVISION, GENERATION).reasonCode())
        .isEqualTo(GraphProjectionStatus.READY);
    long served = metric("graphServedQueries");
    Map<String, Object> view = router.neighborhood(seed("g1"), 1, 0, "REAL", null, null);
    assertSameResponse(
        "probe-ok", networkService.neighborhood(seed("g1"), 1, 0, "REAL", null, null), view);
    assertThat(metric("graphServedQueries")).isEqualTo(served + 1);
  }

  @Test
  @Order(9)
  @DisplayName(
      "水位/gen 守卫：projectedVersion<=0、generation 占位或跨代、projectedVersion<publishedRevision 全部拒绝服务")
  void watermarkAndGenerationGuards() {
    seedCollaboration("w1x", "w2x", "REAL", List.of(), List.of());
    backfillService.backfill(SCOPE);
    writeCheckpoint("COMPLETED", 0L, GENERATION);

    // (1) projectedVersion == 0：图里还没有任何成果被完整写入，0 >= 0 不能被读成「不落后」。
    setMeta(0L, 0L, GENERATION);
    assertThat(graphBackend.status(0L, GENERATION).reasonCode())
        .isEqualTo(GraphProjectionStatus.PROJECTION_NOT_INITIALIZED);

    // (2) generation 仍是占位值 UNINITIALIZED：无法证明图属于当前活动代。
    setMeta(REVISION, REVISION, GraphProjectionMeta.UNINITIALIZED_GENERATION);
    assertThat(graphBackend.status(REVISION, GENERATION).reasonCode())
        .isEqualTo(GraphProjectionStatus.PROJECTION_NOT_INITIALIZED);

    // (3) 权威侧 generation 为空：同样不可判定，按未初始化而不是放行。
    setMeta(REVISION, REVISION, GENERATION);
    assertThat(graphBackend.status(REVISION, null).reasonCode())
        .isEqualTo(GraphProjectionStatus.PROJECTION_NOT_INITIALIZED);

    // (4) 跨代：图停在上一代，继续服务会把旧代成果当成当前结果。
    assertThat(graphBackend.status(REVISION, GENERATION + "-next").reasonCode())
        .isEqualTo(GraphProjectionStatus.GENERATION_MISMATCH);

    // (5) projectedVersion < publishedRevision：落后一版即不得服务（严格模式 503 VIEW_UPDATING）。
    assertThat(graphBackend.status(REVISION + 1, GENERATION).reasonCode())
        .isEqualTo(GraphProjectionStatus.PROJECTION_STALE);
    // 权威侧公开水位推进到 REVISION+1：图还停在 REVISION。
    when(core.readBarrier())
        .thenReturn(new CoreInternalClient.PublicBarrier(REVISION + 1, REVISION + 1));
    NetworkQueryRouter strict =
        routerWithMode(GraphQueryMode.NEO4J_STRICT, graphBackend, new NetworkGraphQueryMetrics());
    assertThatThrownBy(() -> strict.neighborhood(seed("w1x"), 1, 0, "REAL", null, null))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).errorCode())
                    .isEqualTo(ErrorCode.VIEW_UPDATING));

    // (6) 恢复：同代、水位追平、断点 COMPLETED 后重新可服务。
    setMeta(REVISION, REVISION, GENERATION);
    assertThat(graphBackend.status(REVISION, GENERATION).reasonCode())
        .isEqualTo(GraphProjectionStatus.READY);
  }

  @Test
  @Order(14)
  @DisplayName("对账报告过期：报告生成后成果在 PG 恢复公开资格，repair 必须正向同步入图而不是按旧报告误下线")
  void staleReportResyncsInsteadOfRetiring() {
    seedWork("st1", "REAL", "先被收紧后恢复的论文", List.of(), List.of(), List.of(seed("s1"), seed("s2")));
    backfillService.backfill(SCOPE);
    advanceProjection(REVISION);

    // 报告生成时刻：PG 把该成果收紧为 RESTRICTED（图里仍是公开的旧版本）。
    jdbc.update(
        "UPDATE qry_achievement SET visibility = 'RESTRICTED', aggregate_version = 60"
            + " WHERE generation = ? AND achievement_id = ?",
        GENERATION,
        work("st1"));
    GraphReconciliationReport report = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());
    assertThat(report.orphanedWorks()).contains(work("st1"));

    // 报告与修复之间：PG 恢复公开且版本更新——旧报告已经过期。
    jdbc.update(
        "UPDATE qry_achievement SET visibility = 'PUBLIC', aggregate_version = 70"
            + " WHERE generation = ? AND achievement_id = ?",
        GENERATION,
        work("st1"));

    GraphRepairResult repair = reconciler.repair(report);
    assertThat(repair.failedWorks()).isZero();
    assertThat(repair.retiredWorks()).as("不得按过期报告把当前公开的成果下线").isZero();
    assertThat(
            graphCount(
                "MATCH (w:Work {work_id: $w}) RETURN count(w) AS n", Map.of("w", work("st1"))))
        .as("成果节点必须仍在图内")
        .isEqualTo(1L);
    assertThat(
            graphCount(
                "MATCH (w:Work {work_id: $w}) RETURN w.source_version AS n",
                Map.of("w", work("st1"))))
        .as("必须正向同步到 PG 的最新聚合版本")
        .isEqualTo(70L);
    assertThat(
            graphCount(
                "MATCH ()-[c:COOPERATED_WITH]->() WHERE c.work_id = $w RETURN count(c) AS n",
                Map.of("w", work("st1"))))
        .as("合作边必须在图内")
        .isEqualTo(1L);
    assertThat(router.shortestPath(seed("s1"), seed("s2"), "REAL", null, null).get("reachable"))
        .as("端到端查询仍应可达")
        .isEqualTo(true);
  }

  @Test
  @Order(15)
  @DisplayName("事件失败不推水位：单条成果同步失败或图内删除失败时，GraphProjectionMeta 水位原地不动、下一次从同一修订重来")
  void eventFailureNeverAdvancesWatermark() {
    seedWork("f1", "REAL", "事件论文一", List.of(), List.of(), List.of(seed("f1-a"), seed("f1-b")));
    seedWork("f2", "REAL", "事件论文二", List.of(), List.of(), List.of(seed("f2-a"), seed("f2-b")));
    backfillService.backfill(SCOPE);
    long base = projectedVersion();
    assertThat(base).isEqualTo(REVISION);

    // 两条事件：一条可以应用，另一条在同步时被注入永久错误。
    appliedEvent(
        "evt-" + PREFIX + "-ok",
        "CatalogChangeCommitted",
        REVISION + 1,
        "{\"changeKind\":\"UPSERT\",\"achievementId\":\"" + work("f1") + "\"}");
    appliedEvent(
        "evt-" + PREFIX + "-boom",
        "CatalogChangeCommitted",
        REVISION + 2,
        "{\"changeKind\":\"UPSERT\",\"achievementId\":\"" + work("f2") + "\"}");

    GraphEventProjector faulty =
        new GraphEventProjector(
            projectionSource,
            new GraphProjectionSynchronizer(
                projectionSource,
                new FailingStore(projectionStore, work("f2")),
                GraphProjectionRetryPolicy.noRetry(),
                millis -> {}),
            projectionStore,
            checkpoints,
            new JsonCodec(new ObjectMapper()),
            CONSUMER);

    GraphEventProjectionResult failed = faulty.applyPending(SCOPE, 50);

    assertThat(failed.failedWorks()).as("失败必须计数").isEqualTo(1);
    assertThat(failed.stoppedEventId()).as("必须停在失败那条事件上").isNotBlank();
    assertThat(failed.projectedRevision()).as("即使失败前的事件已应用，水位也不得推进").isEqualTo(base);
    assertThat(projectedVersion()).as("图水位节点本身必须原地不动").isEqualTo(base);

    // 删除失败同样不推水位：事件指向一个 PG 里已不存在的成果（AchievementMerged 的被合弃者）。
    appliedEvent(
        "evt-" + PREFIX + "-gone",
        "AchievementMerged",
        REVISION + 3,
        "{\"survivorId\":\"" + work("f1") + "\",\"mergedId\":\"" + work("ghost") + "\"}");
    GraphEventProjector deleteFailing =
        new GraphEventProjector(
            projectionSource,
            projectionSynchronizer,
            new DeleteFailingStore(projectionStore, work("ghost")),
            checkpoints,
            new JsonCodec(new ObjectMapper()),
            CONSUMER);

    GraphEventProjectionResult deleteFailed = deleteFailing.applyPending(SCOPE, 50);
    assertThat(deleteFailed.failedWorks()).as("删除失败必须计数").isEqualTo(1);
    assertThat(deleteFailed.projectedRevision()).isEqualTo(base);
    assertThat(projectedVersion()).isEqualTo(base);

    // 去掉故障后，同一批事件从同一修订重来并正常推进：失败没有被永久跳过。
    GraphEventProjectionResult recovered = projector.applyPending(SCOPE, 50);
    assertThat(recovered.failedWorks()).isZero();
    assertThat(recovered.projectedRevision()).isEqualTo(REVISION + 3);
    assertThat(projectedVersion()).isEqualTo(REVISION + 3);
  }

  // ===========================================================================
  // 新旧查询一致性（同一批请求，两条实现逐字段相同）
  // ===========================================================================

  @Test
  @Order(10)
  @DisplayName("一致性/多路径与全点对：自我网络与最短路径在 PG 与 Neo4j 上逐字段相同，等长路径选同一条")
  void neighborhoodAndPathParityOnFullDataset() {
    seedParityDataset();
    backfillService.backfill(SCOPE);
    advanceProjection(REVISION);

    List<String> scholars =
        List.of(seed("a"), seed("b"), seed("c"), seed("d"), seed("e"), seed("f"));
    for (String scholar : scholars) {
      for (int depth = 1; depth <= 3; depth++) {
        Map<String, Object> pgView =
            networkService.neighborhood(scholar, depth, 0, "REAL", null, null);
        Map<String, Object> graphView = router.neighborhood(scholar, depth, 0, "REAL", null, null);
        assertSameResponse("seed=" + scholar + ";depth=" + depth, pgView, graphView);
      }
    }

    Set<String> endpoints = new LinkedHashSet<>(scholars);
    endpoints.add("missing-scholar");
    for (String from : endpoints) {
      for (String to : endpoints) {
        Map<String, Object> pgView = networkService.shortestPath(from, to, "REAL", null, null);
        Map<String, Object> graphView = router.shortestPath(from, to, "REAL", null, null);
        assertSameResponse("path " + from + "->" + to, pgView, graphView);
      }
    }

    // 两条等长路径（a→b→e 与 a→c→e）：两侧必须选中同一条（规范 ID 字典序最小）。
    Map<String, Object> pgPath =
        networkService.shortestPath(seed("a"), seed("e"), "REAL", null, null);
    Map<String, Object> graphPath = router.shortestPath(seed("a"), seed("e"), "REAL", null, null);
    assertThat(pgPath.get("path")).isEqualTo(List.of(seed("a"), seed("b"), seed("e")));
    assertThat(graphPath.get("path")).isEqualTo(pgPath.get("path"));
  }

  @Test
  @Order(11)
  @DisplayName("一致性/最大深度与不连通：6 跳可达、7 跳与跨分量不可达，两侧结论与说明完全相同")
  void depthBoundaryAndDisconnectedParity() {
    List<String> chain = new ArrayList<>();
    for (int i = 1; i <= 8; i++) {
      chain.add(seed("ch" + i));
    }
    for (int i = 1; i <= 7; i++) {
      seedCollaboration("ch" + i, "ch" + (i + 1), "REAL", List.of(), List.of());
    }
    seedCollaboration("iso1", "iso2", "REAL", List.of(), List.of());
    seedCollaboration("iso3", "iso4", "REAL", List.of(), List.of());
    backfillService.backfill(SCOPE);
    advanceProjection(REVISION);

    assertThat(
            networkService.shortestPath(chain.get(0), chain.get(6), "REAL", null, null).get("hops"))
        .isEqualTo(6);
    assertThat(router.shortestPath(chain.get(0), chain.get(6), "REAL", null, null).get("hops"))
        .isEqualTo(6);
    assertSameResponse(
        "6-hops",
        networkService.shortestPath(chain.get(0), chain.get(6), "REAL", null, null),
        router.shortestPath(chain.get(0), chain.get(6), "REAL", null, null));

    assertThat(router.shortestPath(chain.get(0), chain.get(7), "REAL", null, null).get("reachable"))
        .as("7 跳超出 MAX_PATH_DEPTH=6，必须如实不可达")
        .isEqualTo(false);
    assertSameResponse(
        "7-hops",
        networkService.shortestPath(chain.get(0), chain.get(7), "REAL", null, null),
        router.shortestPath(chain.get(0), chain.get(7), "REAL", null, null));

    assertSameResponse(
        "disconnected",
        networkService.shortestPath(seed("iso1"), seed("iso3"), "REAL", null, null),
        router.shortestPath(seed("iso1"), seed("iso3"), "REAL", null, null));
    assertThat(router.shortestPath(seed("iso1"), seed("iso3"), "REAL", null, null).get("reachable"))
        .isEqualTo(false);
  }

  @Test
  @Order(12)
  @DisplayName("一致性/高作者数与强度：物化边与二部图推导给出同样的边集、强度与路径")
  void highDegreeAndStrengthParity() {
    // w6：4 位作者 > 阈值 3 → PG 物化 6 对，图只写 AUTHORED 由二部图推导。
    seedWork(
        "w6",
        "REAL",
        "高作者数论文",
        List.of("t1"),
        List.of("o1"),
        List.of(seed("a"), seed("b"), seed("c"), seed("f")));
    seedWork("w1", "REAL", "合作论文一", List.of("t1"), List.of("o1"), List.of(seed("a"), seed("b")));
    seedWork("w7", "REAL", "合作论文二", List.of("t1"), List.of(), List.of(seed("a"), seed("b")));
    backfillService.backfill(SCOPE);
    advanceProjection(REVISION);

    Map<String, Object> pgView = networkService.neighborhood(seed("a"), 1, 0, "REAL", null, null);
    Map<String, Object> graphView = router.neighborhood(seed("a"), 1, 0, "REAL", null, null);
    assertSameResponse("high-degree", pgView, graphView);
    assertThat(strengthOf(graphView, seed("a"), seed("b")))
        .as("a/b 三篇共同论文：强度是不同成果数，不是行数")
        .isEqualTo(3L);
    assertThat(strengthOf(pgView, seed("a"), seed("b"))).isEqualTo(3L);
    assertThat(strengthOf(graphView, seed("a"), seed("f"))).as("只由高作者数成果推导").isEqualTo(1L);

    Map<String, Object> pgPath =
        networkService.shortestPath(seed("a"), seed("f"), "REAL", null, null);
    Map<String, Object> graphPath = router.shortestPath(seed("a"), seed("f"), "REAL", null, null);
    assertSameResponse("high-degree-path", pgPath, graphPath);
    assertThat(graphPath.get("hops")).isEqualTo(1);
  }

  @Test
  @Order(13)
  @DisplayName("一致性/筛选与数据性质：领域/机构筛选后强度重新聚合，REAL 与 SIMULATED 严格隔离")
  void filteredAndDataNatureParity() {
    seedWork("w1", "REAL", "领域一机构一", List.of("t1"), List.of("o1"), List.of(seed("a"), seed("b")));
    seedWork("w2", "REAL", "领域一机构二", List.of("t1"), List.of("o2"), List.of(seed("a"), seed("b")));
    seedWork("w3", "REAL", "领域二机构一", List.of("t2"), List.of("o1"), List.of(seed("a"), seed("b")));
    seedWork("w5", "SIMULATED", "模拟合作", List.of("t1"), List.of(), List.of(seed("a"), seed("g")));
    backfillService.backfill(SCOPE);
    advanceProjection(REVISION);

    record Filter(String nature, String topic, String organization) {}
    List<Filter> filters =
        List.of(
            new Filter("REAL", null, null),
            new Filter("ALL", null, null),
            new Filter("SIMULATED", null, null),
            new Filter("REAL", "t1", null),
            new Filter("REAL", "t2", null),
            new Filter("REAL", "t1", "o1"));
    for (Filter filter : filters) {
      for (String scholar : List.of(seed("a"), seed("b"), seed("g"))) {
        Map<String, Object> pgView =
            networkService.neighborhood(
                scholar, 2, 0, filter.nature(), filter.topic(), filter.organization());
        Map<String, Object> graphView =
            router.neighborhood(
                scholar, 2, 0, filter.nature(), filter.topic(), filter.organization());
        assertSameResponse(
            "filter="
                + filter.nature()
                + "/"
                + filter.topic()
                + "/"
                + filter.organization()
                + ";seed="
                + scholar,
            pgView,
            graphView);
      }
    }

    // REAL 口径不得出现仅由模拟成果支撑的合作；SIMULATED 只有 1 跳直连。
    assertThat(
            strengthOf(
                router.neighborhood(seed("a"), 1, 0, "REAL", null, null), seed("a"), seed("g")))
        .isZero();
    assertThat(router.shortestPath(seed("a"), seed("g"), "REAL", null, null).get("reachable"))
        .isEqualTo(false);
    assertThat(router.shortestPath(seed("a"), seed("g"), "SIMULATED", null, null).get("hops"))
        .isEqualTo(1);
    assertThat(router.shortestPath(seed("a"), seed("b"), "SIMULATED", null, null).get("reachable"))
        .isEqualTo(false);
  }

  // ===========================================================================
  // 故障与恢复
  // ===========================================================================

  @Test
  @Order(20)
  @DisplayName("图库不可达：NEO4J_WITH_PG_FALLBACK 自动回退且计数，NEO4J_STRICT 返回 503 DEPENDENCY_UNAVAILABLE")
  void unreachableGraphFallsBackAndStrictReturns503() throws Exception {
    seedCollaboration("u1", "u2", "REAL", List.of(), List.of());
    advanceProjection(REVISION);

    try (DeadGraph dead = new DeadGraph()) {
      NetworkGraphQueryMetrics fallbackMetrics = new NetworkGraphQueryMetrics();
      NetworkQueryRouter fallback =
          routerWithMode(GraphQueryMode.NEO4J_WITH_PG_FALLBACK, dead.backend(), fallbackMetrics);

      Map<String, Object> pgView =
          networkService.neighborhood(seed("u1"), 2, 0, "REAL", null, null);
      Map<String, Object> routed = fallback.neighborhood(seed("u1"), 2, 0, "REAL", null, null);

      assertSameResponse("graph-unavailable", pgView, routed);
      assertThat(fallbackMetrics.snapshot())
          .containsEntry("graphUnavailableFallbackCount", 1L)
          .containsEntry("graphServedQueries", 0L);

      NetworkQueryRouter strict =
          routerWithMode(
              GraphQueryMode.NEO4J_STRICT, dead.backend(), new NetworkGraphQueryMetrics());
      assertThatThrownBy(() -> strict.neighborhood(seed("u1"), 1, 0, "REAL", null, null))
          .isInstanceOf(BusinessException.class)
          .satisfies(
              ex ->
                  assertThat(((BusinessException) ex).errorCode())
                      .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE));

      MockMvc strictMvc =
          MockMvcBuilders.standaloneSetup(new PublicNetworkController(strict))
              .setControllerAdvice(new GlobalExceptionHandler(objectMapper))
              .build();
      strictMvc
          .perform(
              get("/api/v1/public/network/path").param("from", seed("u1")).param("to", seed("u2")))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    }
  }

  @Test
  @Order(21)
  @DisplayName("对账与修复：检出缺失/孤立/边集/标题漂移，repair 后 consistent=true，SHADOW_COMPARE 对照一致")
  void reconcileRepairsDriftAndShadowCompareMatches() {
    seedWork("w1", "REAL", "对账论文一", List.of(), List.of(), List.of(seed("a"), seed("b"), seed("c")));
    seedWork("w2", "REAL", "对账论文二", List.of(), List.of(), List.of(seed("a"), seed("b")));
    backfillService.backfill(SCOPE);
    advanceProjection(REVISION);

    GraphReconciliationReport consistent = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());
    assertThat(consistent.findings()).isEmpty();
    assertThat(consistent.consistent()).isTrue();
    assertThat(consistent.scanTruncated()).isFalse();

    // 漂移 1：图上删掉一个成果（模拟误删/写入丢失）。
    runner.write(
        tx -> {
          tx.run("MATCH (w:Work {work_id: $w}) DETACH DELETE w", Map.of("w", work("w2"))).consume();
          return null;
        });
    // 漂移 2：图里出现 PG 中不存在的孤立成果。
    runner.write(
        tx -> {
          tx.run(
                  "CREATE (:Work {work_id: $w, title: 'orphan', is_public: true,"
                      + " data_origin: 'REAL', source_version: 1, edges_materialized: false})",
                  Map.of("w", PREFIX + "-orphan"))
              .consume();
          return null;
        });
    // 漂移 3：边的证据标题过期（节点标题已更新，边仍带旧标题）。
    jdbc.update(
        "UPDATE qry_achievement SET title = '对账论文一（改名）' WHERE generation = ? AND achievement_id = ?",
        GENERATION,
        work("w1"));
    runner.write(
        tx -> {
          tx.run(
                  "MATCH ()-[c:COOPERATED_WITH]->() WHERE c.work_id = $w SET c.work_title = '旧标题'",
                  Map.of("w", work("w1")))
              .consume();
          return null;
        });

    GraphReconciliationReport drifted = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());
    assertThat(drifted.consistent()).isFalse();
    assertThat(drifted.findings())
        .extracting(finding -> finding.code())
        .contains("WORK_MISSING_IN_GRAPH", "WORK_ORPHANED_IN_GRAPH", "EVIDENCE_TITLE_STALE");
    assertThat(drifted.missingWorks()).contains(work("w2"));
    assertThat(drifted.orphanedWorks()).contains(PREFIX + "-orphan");

    GraphRepairResult repair = reconciler.repair(drifted);
    assertThat(repair.failedWorks()).isZero();
    assertThat(repair.retiredWorks()).isEqualTo(1);
    assertThat(repair.repairedWorks()).isGreaterThanOrEqualTo(1);

    GraphReconciliationReport after = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());
    assertThat(after.findings()).as(after.summary()).isEmpty();
    assertThat(after.consistent()).isTrue();

    // SHADOW_COMPARE：PG 权威返回 + 同版本图查询逐字段对照，必须计入「一致」而不是「跳过」。
    NetworkGraphQueryMetrics shadowMetrics = new NetworkGraphQueryMetrics();
    NetworkQueryRouter shadow =
        new NetworkQueryRouter(
            networkService,
            barrierGuard,
            new NetworkGraphQueryProperties(GraphQueryMode.SHADOW_COMPARE.name(), false, 8),
            shadowMetrics,
            comparator,
            graphBackend,
            Runnable::run);
    Map<String, Object> pgView = networkService.neighborhood(seed("a"), 2, 0, "REAL", null, null);
    Map<String, Object> shadowView = shadow.neighborhood(seed("a"), 2, 0, "REAL", null, null);
    assertSameResponse("shadow", pgView, shadowView);
    assertThat(shadowMetrics.snapshot())
        .containsEntry("parityComparedCount", 1L)
        .containsEntry("parityMatchedCount", 1L)
        .containsEntry("parityMismatchedCount", 0L)
        .containsEntry("paritySkippedCount", 0L)
        .containsEntry("graphServedQueries", 0L);
  }

  @Test
  @Order(22)
  @DisplayName("回滚一致性：切回 POSTGRES_ONLY 或关闭图开关后，图谱与最短路径结果与回滚前完全一致")
  void rollbackKeepsResultsIdentical() {
    seedParityDataset();
    backfillService.backfill(SCOPE);
    advanceProjection(REVISION);

    List<String[]> pairs =
        List.of(
            new String[] {seed("a"), seed("e")},
            new String[] {seed("a"), seed("d")},
            new String[] {seed("b"), seed("f")},
            new String[] {seed("a"), "missing-scholar"});

    Map<String, Map<String, Object>> before = new java.util.LinkedHashMap<>();
    for (String scholar : List.of(seed("a"), seed("b"), seed("c"))) {
      before.put("neighborhood:" + scholar, router.neighborhood(scholar, 2, 0, "REAL", null, null));
    }
    for (String[] pair : pairs) {
      before.put(
          "path:" + pair[0] + "->" + pair[1],
          router.shortestPath(pair[0], pair[1], "REAL", null, null));
    }

    NetworkQueryRouter postgresOnly =
        new NetworkQueryRouter(
            networkService,
            barrierGuard,
            new NetworkGraphQueryProperties(GraphQueryMode.POSTGRES_ONLY.name(), false, 8),
            new NetworkGraphQueryMetrics(),
            comparator,
            graphBackend,
            Runnable::run);
    NetworkQueryRouter graphDisabled =
        new NetworkQueryRouter(
            networkService,
            barrierGuard,
            new NetworkGraphQueryProperties(GraphQueryMode.NEO4J_WITH_PG_FALLBACK.name(), false, 8),
            new NetworkGraphQueryMetrics(),
            comparator,
            null,
            Runnable::run);
    assertThat(graphDisabled.effectiveMode())
        .as("图库 Bean 缺失等价于「关掉图开关」，必须退化为 POSTGRES_ONLY")
        .isEqualTo(GraphQueryMode.POSTGRES_ONLY);

    for (String scholar : List.of(seed("a"), seed("b"), seed("c"))) {
      Map<String, Object> expected = before.get("neighborhood:" + scholar);
      assertSameResponse(
          "rollback-neighborhood:" + scholar,
          expected,
          postgresOnly.neighborhood(scholar, 2, 0, "REAL", null, null));
      assertSameResponse(
          "graph-disabled-neighborhood:" + scholar,
          expected,
          graphDisabled.neighborhood(scholar, 2, 0, "REAL", null, null));
    }
    for (String[] pair : pairs) {
      Map<String, Object> expected = before.get("path:" + pair[0] + "->" + pair[1]);
      assertSameResponse(
          "rollback-path:" + pair[0] + "->" + pair[1],
          expected,
          postgresOnly.shortestPath(pair[0], pair[1], "REAL", null, null));
      assertSameResponse(
          "graph-disabled-path:" + pair[0] + "->" + pair[1],
          expected,
          graphDisabled.shortestPath(pair[0], pair[1], "REAL", null, null));
    }
  }

  @Test
  @Order(23)
  @DisplayName("规模超限不降级：GRAPH_SCOPE_TOO_LARGE 直接抛给调用方，不静默换成慢的 PG 查询")
  void scopeTooLargeIsNotSilentlyDegraded() {
    String hub = seed("hub");
    List<String> leaves = new ArrayList<>();
    for (int i = 1; i <= 6; i++) {
      leaves.add(seed("leaf" + i));
      seedCollaboration("leaf" + i, "hub", "REAL", List.of(), List.of());
    }
    backfillService.backfill(SCOPE);
    advanceProjection(REVISION);

    CollaborationGraphService tiny =
        new CollaborationGraphService(
            new Neo4jCollaborationGraphRepository(runner, neo4jProperties), 3, 100_000, 2_000L);
    GraphNetworkBackend tinyBackend =
        new NetworkGraphQueryService(
            tiny,
            networkMapper,
            projectionProperties,
            neo4jProperties,
            new ProjectionWatermarkGuard(
                projectionStore, schemaInitializer, neo4jProperties, initializationProbe));

    NetworkGraphQueryMetrics limitedMetrics = new NetworkGraphQueryMetrics();
    NetworkQueryRouter limited =
        routerWithMode(GraphQueryMode.NEO4J_WITH_PG_FALLBACK, tinyBackend, limitedMetrics);

    assertThatThrownBy(() -> limited.neighborhood(hub, 2, 0, "REAL", null, null))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).errorCode())
                    .isEqualTo(ErrorCode.GRAPH_SCOPE_TOO_LARGE));
    assertThat(limitedMetrics.snapshot())
        .as("规模问题是业务结论，不得降级到 PG 冒充成功")
        .containsEntry("graphServedQueries", 0L)
        .containsEntry("graphUnavailableFallbackCount", 0L)
        .containsEntry("graphErrorFallbackCount", 0L)
        .containsEntry("staleProjectionFallbackCount", 0L);

    // 正常上限下同一请求可由图库回答：失败确实来自规模上限。
    assertThat(router.neighborhood(hub, 2, 0, "REAL", null, null).get("edges")).isNotNull();
  }

  @Test
  @Order(24)
  @DisplayName("预算超时不降级：GRAPH_TIMEOUT 直接抛给调用方，不返回半条路径")
  void timeoutIsNotSilentlyDegraded() {
    for (int i = 1; i <= 5; i++) {
      seedCollaboration("to" + i, "to" + (i + 1), "REAL", List.of(), List.of());
    }
    backfillService.backfill(SCOPE);
    advanceProjection(REVISION);

    CollaborationGraphService slowGraph =
        new CollaborationGraphService(
            new SlowRepository(runner, neo4jProperties, 1_100L), 20_000, 100_000, 1_000L);
    // 后端把时间预算传给图服务：预算 1000ms，而每层扩边被人为拉长到 1100ms。
    ProjectionProperties tightBudget =
        new ProjectionProperties(
            "query", 500, 5, 200, false, 2_000L, 20_000, 100_000, 1_000L, 10_000, 20, 100);
    GraphNetworkBackend slowBackend =
        new NetworkGraphQueryService(
            slowGraph,
            networkMapper,
            tightBudget,
            neo4jProperties,
            new ProjectionWatermarkGuard(
                projectionStore, schemaInitializer, neo4jProperties, initializationProbe));
    NetworkGraphQueryMetrics slowMetrics = new NetworkGraphQueryMetrics();
    NetworkQueryRouter slow =
        routerWithMode(GraphQueryMode.NEO4J_WITH_PG_FALLBACK, slowBackend, slowMetrics);

    assertThatThrownBy(() -> slow.shortestPath(seed("to1"), seed("to5"), "REAL", null, null))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).errorCode())
                    .isEqualTo(ErrorCode.GRAPH_TIMEOUT));
    assertThat(slowMetrics.snapshot())
        .containsEntry("graphServedQueries", 0L)
        .containsEntry("graphUnavailableFallbackCount", 0L);

    assertThat(router.shortestPath(seed("to1"), seed("to5"), "REAL", null, null).get("hops"))
        .as("同一数据在正常预算下 4 跳可达，超时不是数据问题")
        .isEqualTo(4);
  }

  // ===========================================================================
  // 数据准备
  // ===========================================================================

  private void seedParityDataset() {
    // 菱形 a-b-c-d 与 a-d；w9/w10 让 a→e 存在两条等长最短路径（经 b 或经 c）。
    seedWork("w1", "REAL", "合作一", List.of("t1"), List.of("o1"), List.of(seed("a"), seed("b")));
    seedWork("w2", "REAL", "合作二", List.of("t1"), List.of("o1"), List.of(seed("b"), seed("c")));
    seedWork("w3", "REAL", "合作三", List.of("t2"), List.of("o2"), List.of(seed("c"), seed("d")));
    seedWork("w4", "REAL", "合作四", List.of("t2"), List.of("o2"), List.of(seed("a"), seed("d")));
    seedWork("w5", "SIMULATED", "模拟合作", List.of("t1"), List.of(), List.of(seed("a"), seed("e")));
    seedWork(
        "w6",
        "REAL",
        "四作者论文",
        List.of("t1"),
        List.of("o1"),
        List.of(seed("a"), seed("b"), seed("c"), seed("f")));
    seedWork("w7", "REAL", "合作七", List.of("t1"), List.of(), List.of(seed("a"), seed("b")));
    seedWork("w8", "REAL", "合作八", List.of("t2"), List.of(), List.of(seed("b"), seed("d")));
    seedWork("w9", "REAL", "合作九", List.of("t1"), List.of(), List.of(seed("b"), seed("e")));
    seedWork("w10", "REAL", "合作十", List.of("t2"), List.of(), List.of(seed("c"), seed("e")));
  }

  private void seedCollaboration(
      String left, String right, String nature, List<String> topics, List<String> organizations) {
    seedWork(
        "collab-" + left + "-" + right,
        nature,
        "合作 " + left + "/" + right,
        topics,
        organizations,
        List.of(seed(left), seed(right)));
  }

  /**
   * 写入一篇成果的 PG 读投影：成果行 + 参与行 + 机构 + 领域 + 作者对边。
   *
   * <p>作者对边（{@code qry_coauthor_edge}）是 PG 权威路径读的表；图侧由 {@code AUTHORED} 推导或由
   * 投影直接物化。两侧都从同一份 PG 事实出发，一致性断言才有意义。
   */
  private void seedWork(
      String suffix,
      String nature,
      String title,
      List<String> topics,
      List<String> organizations,
      List<String> authors) {
    String workId = work(suffix);
    jdbc.update(
        "INSERT INTO qry_achievement (generation, achievement_id, type, title, title_norm,"
            + " data_nature, visibility, effective_status, integrity_status, publication_year,"
            + " aggregate_version, revision)"
            + " VALUES (?, ?, 'PAPER', ?, ?, ?, 'PUBLIC', 'EFFECTIVE', 'NONE', 2024, 1, ?)",
        GENERATION,
        workId,
        title,
        title.toLowerCase(Locale.ROOT),
        nature,
        REVISION);
    List<String> sorted = new ArrayList<>(authors);
    java.util.Collections.sort(sorted);
    int order = 1;
    for (String scholar : sorted) {
      jdbc.update(
          "INSERT INTO qry_contribution (generation, achievement_id, scholar_id, scholar_name,"
              + " role, author_order, relation_state)"
              + " VALUES (?, ?, ?, ?, 'AUTHOR', ?, 'AUTO')",
          GENERATION,
          workId,
          scholar,
          "学者 " + scholar.substring(scholar.lastIndexOf('-') + 1).toUpperCase(Locale.ROOT),
          order++);
      for (String organization : organizations) {
        jdbc.update(
            "INSERT INTO qry_contribution_org (generation, achievement_id, scholar_id, organization)"
                + " VALUES (?, ?, ?, ?)",
            GENERATION,
            workId,
            scholar,
            organization);
      }
    }
    for (int i = 0; i < sorted.size(); i++) {
      for (int j = i + 1; j < sorted.size(); j++) {
        jdbc.update(
            "INSERT INTO qry_coauthor_edge (generation, achievement_id, scholar_a, scholar_b)"
                + " VALUES (?, ?, ?, ?)",
            GENERATION,
            workId,
            sorted.get(i),
            sorted.get(j));
      }
    }
    for (String topic : topics) {
      jdbc.update(
          "INSERT INTO qry_achievement_topic (generation, achievement_id, topic_id, canonical_name)"
              + " VALUES (?, ?, ?, ?)",
          GENERATION,
          workId,
          topic,
          "领域 " + topic);
    }
  }

  private static String seed(String suffix) {
    return PREFIX + "-" + suffix;
  }

  private static String work(String suffix) {
    return PREFIX + "-w-" + suffix;
  }

  private void seedConsumerState() {
    jdbc.update(
        "INSERT INTO qry_generation (generation, status, ready_at, note)"
            + " VALUES (?, 'READY', now(), 'GraphEndToEndConsistencyIT')"
            + " ON CONFLICT (generation) DO NOTHING",
        GENERATION);
    jdbc.update(
        "INSERT INTO qry_consumer_state (consumer_id, active_generation, received_revision,"
            + " highest_seen_revision, published_revision, received_at, published_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, now(), now(), now())"
            + " ON CONFLICT (consumer_id) DO UPDATE SET"
            + " active_generation = EXCLUDED.active_generation,"
            + " received_revision = EXCLUDED.received_revision,"
            + " highest_seen_revision = EXCLUDED.highest_seen_revision,"
            + " published_revision = EXCLUDED.published_revision",
        CONSUMER,
        GENERATION,
        REVISION,
        REVISION,
        REVISION);
  }

  private void appliedEvent(String eventId, String eventType, long revision, String payload) {
    jdbc.update(
        "INSERT INTO qry_inbox_event (id, consumer_id, event_id, event_type, schema_version,"
            + " aggregate_type, aggregate_id, aggregate_version, catalog_revision, payload,"
            + " processing_state, applied_at) VALUES (?, ?, ?, ?, 1, 'ACHIEVEMENT', 'it', 1, ?,"
            + " ?::jsonb, 'APPLIED', now()) ON CONFLICT (consumer_id, event_id) DO NOTHING",
        "it-" + eventId,
        CONSUMER,
        eventId,
        eventType,
        revision,
        payload);
  }

  private void advanceProjection(long version) {
    projectionStore.advanceMeta(
        new GraphProjectionMeta(SCOPE, version, version, GENERATION, java.time.Instant.now()));
  }

  /**
   * 直接改写图水位节点。
   *
   * <p>生产代码的 {@code advanceMeta} 只允许前进，而本类要验证「水位为 0 / generation 是占位值 /
   * 跨代」这些守卫分支，就必须能把水位摆到那些状态；这里绕过写适配器直接写节点，只用于测试准备。
   */
  private void setMeta(long projectedVersion, long catalogRevision, String generation) {
    runner.write(
        tx -> {
          tx.run(
                  "MERGE (m:GraphProjectionMeta {scope: $scope}) "
                      + "SET m.projected_version = $projectedVersion, "
                      + "m.catalog_revision = $catalogRevision, "
                      + "m.generation = $generation, "
                      + "m.updated_at_epoch_ms = $updatedAtEpochMs",
                  Map.of(
                      "scope",
                      SCOPE,
                      "projectedVersion",
                      projectedVersion,
                      "catalogRevision",
                      catalogRevision,
                      "generation",
                      generation,
                      "updatedAtEpochMs",
                      System.currentTimeMillis()))
              .consume();
          return null;
        });
  }

  /** 写入/覆写本作用域的 V7 断点行（真实 PG 表，不做任何替身）。 */
  private void writeCheckpoint(String status, long failedWorks, String generation) {
    jdbc.update(
        "INSERT INTO qry_graph_projection_checkpoint (scope, status, generation, base_revision,"
            + " processed_works, processed_batches, failed_works, updated_at)"
            + " VALUES (?, ?, ?, ?, 1, 1, ?, now())"
            + " ON CONFLICT (scope) DO UPDATE SET status = EXCLUDED.status,"
            + " generation = EXCLUDED.generation, failed_works = EXCLUDED.failed_works,"
            + " updated_at = now()",
        SCOPE,
        status,
        generation,
        REVISION,
        failedWorks);
  }

  /** 断言读侧因断点状态拒绝图库服务（PROJECTION_NOT_INITIALIZED），并回退 PG。 */
  private void assertNotInitialized(String label) {
    assertThat(graphBackend.status(REVISION, GENERATION).reasonCode())
        .as(label + "：必须判为未初始化")
        .isEqualTo(GraphProjectionStatus.PROJECTION_NOT_INITIALIZED);
    long staleFallbacks = metric("staleProjectionFallbackCount");
    long served = metric("graphServedQueries");
    Map<String, Object> pgView = networkService.neighborhood(seed("g1"), 1, 0, "REAL", null, null);
    Map<String, Object> routed = router.neighborhood(seed("g1"), 1, 0, "REAL", null, null);
    assertSameResponse(label, pgView, routed);
    assertThat(metric("staleProjectionFallbackCount")).as(label).isEqualTo(staleFallbacks + 1);
    assertThat(metric("graphServedQueries")).as(label + "：不得由图提供服务").isEqualTo(served);
  }

  private long projectedVersion() {
    return projectionStore.readMeta(SCOPE).map(GraphProjectionMeta::projectedVersion).orElse(0L);
  }

  private long metric(String key) {
    Object value = metrics.snapshot().get(key);
    return value instanceof Number number ? number.longValue() : 0L;
  }

  private long graphCount(String cypher) {
    return graphCount(cypher, Map.of("p", PREFIX));
  }

  private long graphCount(String cypher, Map<String, Object> parameters) {
    return runner.query(cypher, parameters, record -> record.get("n").asLong()).get(0);
  }

  private void wipeGraph() {
    runner.write(
        tx -> {
          tx.run(
                  "MATCH (n) WHERE n.work_id STARTS WITH $p OR n.scholar_id STARTS WITH $p"
                      + " OR n.scope STARTS WITH $p DETACH DELETE n",
                  Map.of("p", PREFIX))
              .consume();
          return null;
        });
  }

  private void cleanupPostgres() {
    jdbc.update("DELETE FROM qry_inbox_event WHERE consumer_id = ?", CONSUMER);
    jdbc.update("DELETE FROM qry_graph_projection_checkpoint WHERE scope = ?", SCOPE);
    jdbc.update("DELETE FROM qry_coauthor_edge WHERE generation = ?", GENERATION);
    jdbc.update("DELETE FROM qry_achievement_topic WHERE generation = ?", GENERATION);
    jdbc.update("DELETE FROM qry_contribution_org WHERE generation = ?", GENERATION);
    jdbc.update("DELETE FROM qry_contribution WHERE generation = ?", GENERATION);
    jdbc.update("DELETE FROM qry_achievement WHERE generation = ?", GENERATION);
    jdbc.update("DELETE FROM qry_consumer_state WHERE consumer_id = ?", CONSUMER);
  }

  private NetworkQueryRouter routerWithMode(
      GraphQueryMode mode, GraphNetworkBackend backend, NetworkGraphQueryMetrics targetMetrics) {
    return new NetworkQueryRouter(
        networkService,
        barrierGuard,
        new NetworkGraphQueryProperties(mode.name(), false, 8),
        targetMetrics,
        comparator,
        backend,
        Runnable::run);
  }

  private void assertSameResponse(
      String label, Map<String, Object> postgres, Map<String, Object> graph) {
    ParityDiffReport report = comparator.compare(label, label, postgres, graph);
    assertThat(report.matched()).as(report.summary()).isTrue();
  }

  private static long strengthOf(Map<String, Object> response, String left, String right) {
    String a = left.compareTo(right) <= 0 ? left : right;
    String b = left.compareTo(right) <= 0 ? right : left;
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> edges = (List<Map<String, Object>>) response.get("edges");
    if (edges == null) {
      return 0L;
    }
    for (Map<String, Object> edge : edges) {
      if (a.equals(edge.get("source")) && b.equals(edge.get("target"))) {
        return ((Number) edge.get("strength")).longValue();
      }
    }
    return 0L;
  }

  // ===========================================================================
  // 故障注入与外部依赖替身
  // ===========================================================================

  /** 第 N 页读取时抛错：构造真实的「回填中断」，而不是靠杀进程。 */
  private static final class InterruptingSource implements GraphProjectionSource {

    private final GraphProjectionSource delegate;
    private final int failAtPage;
    private int pages;

    private InterruptingSource(GraphProjectionSource delegate, int failAtPage) {
      this.delegate = delegate;
      this.failAtPage = failAtPage;
    }

    @Override
    public Optional<Stage> readStage(String consumerId) {
      return delegate.readStage(consumerId);
    }

    @Override
    public List<GraphProjectedWork> pageWorks(String generation, String afterWorkId, int limit) {
      pages++;
      if (pages == failAtPage) {
        throw new IllegalStateException("模拟中断：第 " + pages + " 页读取被强制中止");
      }
      return delegate.pageWorks(generation, afterWorkId, limit);
    }

    @Override
    public List<GraphProjectedWork> loadWorks(String generation, Collection<String> workIds) {
      return delegate.loadWorks(generation, workIds);
    }

    @Override
    public Optional<GraphProjectedWork> loadWork(String generation, String workId) {
      return delegate.loadWork(generation, workId);
    }

    @Override
    public long countEligibleWorks(String generation, String dataOrigin) {
      return delegate.countEligibleWorks(generation, dataOrigin);
    }

    @Override
    public long countQualifiedContributions(String generation, String dataOrigin) {
      return delegate.countQualifiedContributions(generation, dataOrigin);
    }

    @Override
    public long countContributors(String generation, String dataOrigin) {
      return delegate.countContributors(generation, dataOrigin);
    }

    @Override
    public long countMaterializedPairs(String generation, int threshold, String dataOrigin) {
      return delegate.countMaterializedPairs(generation, threshold, dataOrigin);
    }

    @Override
    public List<String> pageEligibleWorkIds(
        String generation, String afterWorkId, int limit, String dataOrigin) {
      return delegate.pageEligibleWorkIds(generation, afterWorkId, limit, dataOrigin);
    }

    @Override
    public List<ContributionView> loadQualifiedContributions(
        String generation, Collection<String> workIds) {
      return delegate.loadQualifiedContributions(generation, workIds);
    }

    @Override
    public List<AppliedEvent> listAppliedEventsAfter(String consumerId, long revision, int limit) {
      return delegate.listAppliedEventsAfter(consumerId, revision, limit);
    }

    @Override
    public List<String> achievementIdsOfScholar(String generation, String scholarId) {
      return delegate.achievementIdsOfScholar(generation, scholarId);
    }
  }

  /**
   * 对单条成果注入永久错误的写端口：其余写入仍然落到真实 Neo4j。
   *
   * <p>这是故障注入而不是「用假仓储代替数据库」：正常路径的每一条写入都真实发生在图库里，
   * 只有指定成果被注入一个域错误，用来验证「批次内一条失败是否被如实记入 failed_works/last_error」。
   */
  private static final class FailingStore implements GraphProjectionStore {

    private final GraphProjectionStore delegate;
    private final String failingWorkId;

    private FailingStore(GraphProjectionStore delegate, String failingWorkId) {
      this.delegate = delegate;
      this.failingWorkId = failingWorkId;
    }

    @Override
    public int upsertScholars(Collection<GraphScholarNode> scholars) {
      return delegate.upsertScholars(scholars);
    }

    @Override
    public boolean replaceWork(
        com.cognitanexus.query.graph.domain.GraphWorkNode work, List<GraphAuthorship> authorships) {
      if (failingWorkId.equals(work.workId())) {
        throw new IllegalStateException("注入的域错误：成果数据不可投影 " + work.workId());
      }
      return delegate.replaceWork(work, authorships);
    }

    @Override
    public boolean retireWork(String workId, long sourceVersion) {
      return delegate.retireWork(workId, sourceVersion);
    }

    @Override
    public boolean deleteWork(String workId) {
      return delegate.deleteWork(workId);
    }

    @Override
    public Optional<GraphProjectionMeta> readMeta(String scope) {
      return delegate.readMeta(scope);
    }

    @Override
    public long advanceMeta(GraphProjectionMeta meta) {
      return delegate.advanceMeta(meta);
    }

    @Override
    public void resetAllForRebuild(String initialScope) {
      delegate.resetAllForRebuild(initialScope);
    }
  }

  /** 只让指定成果的 {@code deleteWork} 失败：用来验证「图内清理失败也不得推水位」这条路径。 */
  private static final class DeleteFailingStore implements GraphProjectionStore {

    private final GraphProjectionStore delegate;
    private final String failingWorkId;

    private DeleteFailingStore(GraphProjectionStore delegate, String failingWorkId) {
      this.delegate = delegate;
      this.failingWorkId = failingWorkId;
    }

    @Override
    public int upsertScholars(Collection<GraphScholarNode> scholars) {
      return delegate.upsertScholars(scholars);
    }

    @Override
    public boolean replaceWork(
        com.cognitanexus.query.graph.domain.GraphWorkNode work, List<GraphAuthorship> authorships) {
      return delegate.replaceWork(work, authorships);
    }

    @Override
    public boolean retireWork(String workId, long sourceVersion) {
      return delegate.retireWork(workId, sourceVersion);
    }

    @Override
    public boolean deleteWork(String workId) {
      if (failingWorkId.equals(workId)) {
        throw new IllegalStateException("注入的图库错误：图内清理被强制失败 " + workId);
      }
      return delegate.deleteWork(workId);
    }

    @Override
    public Optional<GraphProjectionMeta> readMeta(String scope) {
      return delegate.readMeta(scope);
    }

    @Override
    public long advanceMeta(GraphProjectionMeta meta) {
      return delegate.advanceMeta(meta);
    }

    @Override
    public void resetAllForRebuild(String initialScope) {
      delegate.resetAllForRebuild(initialScope);
    }
  }

  /** 连接被拒绝的图库后端：驱动、仓储、水位判定都是真实实现，只是指向一个不可达地址。 */
  private final class DeadGraph implements AutoCloseable {

    private final Driver driver;
    private final Neo4jGraphProperties properties;
    private final Neo4jSessionRunner runner;

    private DeadGraph() {
      properties =
          new Neo4jGraphProperties(
              true,
              "bolt://127.0.0.1:1",
              "neo4j",
              "secret",
              "neo4j",
              Duration.ofSeconds(1),
              Duration.ofSeconds(2),
              3,
              50,
              20_000,
              100_000,
              2_000L,
              SCOPE,
              false);
      driver =
          org.neo4j.driver.GraphDatabase.driver(
              java.net.URI.create("bolt://127.0.0.1:1"),
              org.neo4j.driver.AuthTokens.basic("neo4j", "secret"),
              org.neo4j.driver.Config.builder()
                  .withConnectionTimeout(1, TimeUnit.SECONDS)
                  // 驱动默认重试 30 秒：一个「连接被拒绝」的用例会白等半分钟。
                  .withMaxTransactionRetryTime(1, TimeUnit.SECONDS)
                  .withTelemetryDisabled(true)
                  .build());
      runner = new Neo4jSessionRunner(driver, properties);
    }

    private GraphNetworkBackend backend() {
      CollaborationGraphService deadGraphs =
          new CollaborationGraphService(
              new Neo4jCollaborationGraphRepository(runner, properties), 20_000, 100_000, 2_000L);
      return new NetworkGraphQueryService(
          deadGraphs,
          networkMapper,
          projectionProperties,
          properties,
          // schema 判定沿用真实初始化器的结论；每次读水位都会因连接被拒失败，
          // 从而得到 PROJECTION_META_UNREADABLE（而不是把原因错报成 schema 未就绪）。
          new ProjectionWatermarkGuard(
              new Neo4jGraphProjectionStore(runner, properties),
              schemaInitializer,
              properties,
              // 探针读的是真实 PG 断点表（可读），所以降级原因只会是图库读不到水位。
              initializationProbe));
    }

    @Override
    public void close() {
      driver.close();
    }
  }

  /** 逐层人为延迟的真实 Neo4j 仓储：用来证明时间预算按真实耗时生效。 */
  private static final class SlowRepository implements CollaborationGraphRepository {

    private final Neo4jSessionRunner target;
    private final Neo4jGraphProperties properties;
    private final long delayMillis;
    private final Neo4jCollaborationGraphRepository delegate;

    private SlowRepository(
        Neo4jSessionRunner target, Neo4jGraphProperties properties, long delayMillis) {
      this.target = target;
      this.properties = properties;
      this.delayMillis = delayMillis;
      this.delegate = new Neo4jCollaborationGraphRepository(target, properties);
    }

    @Override
    public Expansion expand(
        Collection<String> frontier, GraphFilterCriteria criteria, int maxEdges) {
      try {
        Thread.sleep(delayMillis);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("延迟注入被中断", interrupted);
      }
      return delegate.expand(frontier, criteria, maxEdges);
    }

    @Override
    public List<GraphScholarNode> findScholars(Collection<String> scholarIds) {
      return delegate.findScholars(scholarIds);
    }

    @Override
    public boolean isAvailable() {
      return delegate.isAvailable();
    }
  }
}
