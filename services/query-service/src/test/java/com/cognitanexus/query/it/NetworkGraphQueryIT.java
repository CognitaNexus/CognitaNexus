package com.cognitanexus.query.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cognitanexus.query.consistency.PublicBarrierGuard;
import com.cognitanexus.query.corelink.CoreInternalClient;
import com.cognitanexus.query.graph.application.CollaborationGraphRepository;
import com.cognitanexus.query.graph.application.CollaborationGraphService;
import com.cognitanexus.query.graph.application.GraphProjectionStore;
import com.cognitanexus.query.graph.config.Neo4jGraphProperties;
import com.cognitanexus.query.graph.domain.GraphAuthorship;
import com.cognitanexus.query.graph.domain.GraphProjectionMeta;
import com.cognitanexus.query.graph.domain.GraphScholarNode;
import com.cognitanexus.query.graph.domain.GraphWorkNode;
import com.cognitanexus.query.graph.infrastructure.Neo4jCollaborationGraphRepository;
import com.cognitanexus.query.graph.infrastructure.Neo4jGraphProjectionStore;
import com.cognitanexus.query.graph.infrastructure.Neo4jSessionRunner;
import com.cognitanexus.query.graph.schema.Neo4jSchemaInitializer;
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
import com.cognitanexus.query.projection.application.ProjectionProperties;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 合作网络「新旧查询 100% 一致 + 投影水位保护」的真实双库集成测试。
 *
 * <p>要证明的不是「图用例的 BFS 逻辑对」（那由单元测试证明），而是：<b>真实 PostgreSQL 表结构、
 * 真实 Cypher、真实 Neo4j 投影</b>三者合起来，对同一批请求给出与 PG 实现逐字段相同的响应；并且当
 * 图投影落后时绝不返回图结果。
 *
 * <p>数据刻意包含四类难点：
 *
 * <ul>
 *   <li>多条等长最短路径（A→E 可经 B 或 C），要求两侧选同一条；
 *   <li>高作者数成果（4 位作者 > 阈值 3）：PG 物化全部作者对，图只保留 {@code AUTHORED} 由二部图
 *       推导，最终边集与强度必须一致；
 *   <li>同一对作者多篇共同论文（强度是不同成果数，不是行数）；
 *   <li>REAL / SIMULATED 隔离与领域（topic）/机构（organization）筛选，筛选后强度必须重新聚合。
 * </ul>
 *
 * <p>数据隔离：本用例使用<b>独立的 generation、作用域与 ID 前缀</b>，只清理自己写入的行/节点，
 * 不 TRUNCATE 共享表、不重置图库（避免与同一测试实例上的其他用例相互干扰）。
 *
 * <p>依赖：本机 PostgreSQL 55432（{@code ops/local/test-db.sh start}）与 Neo4j 17687
 * （{@code ops/verify/test-infra-smoke.sh up}）。Neo4j 不可达时整类跳过并说明原因，
 * <b>不把跳过报告成通过</b>。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "cn.projection.worker-enabled=false",
      "cn.query.graph.enabled=true",
      // 集成测试直接核对结果：不让启动失败被容错吞掉，schema 未就绪在 @BeforeAll 里显式跳过。
      "cn.query.graph.fail-fast-on-startup=false",
      "cn.query.graph.query.mode=NEO4J_WITH_PG_FALLBACK",
      "cn.query.graph.query.shadow-compare-async=false",
      // 4 位作者的成果不物化作者对边，专门覆盖二部图推导与 PG 物化边的一致性。
      "cn.query.graph.max-authors-per-work-for-materialized-edge=3",
      "cn.query.graph.max-nodes=20000",
      "cn.query.graph.max-edges=100000",
      "cn.query.graph.budget-millis=2000"
    })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NetworkGraphQueryIT {

  private static final String PREFIX = "it-net-" + UUID.randomUUID().toString().substring(0, 8);
  private static final String GENERATION = PREFIX + "-gen";
  private static final String SCOPE = PREFIX + "-scope";
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

  private static final String A = PREFIX + "-a";
  private static final String B = PREFIX + "-b";
  private static final String C = PREFIX + "-c";
  private static final String D = PREFIX + "-d";
  private static final String E = PREFIX + "-e";
  private static final String F = PREFIX + "-f";
  private static final List<String> SCHOLARS = List.of(A, B, C, D, E, F);

  @Autowired private JdbcTemplate jdbc;
  @Autowired private NetworkService networkService;
  @Autowired private NetworkQueryRouter router;
  @Autowired private NetworkParityComparator comparator;
  @Autowired private NetworkGraphQueryMetrics metrics;
  @Autowired private PublicBarrierGuard barrierGuard;
  @Autowired private GraphNetworkBackend graphBackend;
  @Autowired private Neo4jSchemaInitializer schemaInitializer;
  @Autowired private GraphProjectionStore projectionStore;
  @Autowired private CollaborationGraphService collaborationGraphService;
  @Autowired private Neo4jSessionRunner runner;
  @Autowired private NetworkMapper networkMapper;
  @Autowired private ProjectionProperties projectionProperties;

  @Autowired private org.springframework.core.env.Environment environment;

  /** 公开资格屏障每次向 core 取权威水位；测试里固定返回与本地投影一致的值。 */
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
  }

  @BeforeEach
  void stubBarrier() {
    when(core.readBarrier()).thenReturn(new CoreInternalClient.PublicBarrier(REVISION, REVISION));
  }

  @BeforeAll
  void prepareDatabases() {
    if (!schemaInitializer.isReady()) {
      Assumptions.abort(
          "Neo4j 图库实例不可用或 schema 初始化失败（"
              + URI
              + "）。先执行 "
              + "bash ops/verify/test-infra-smoke.sh up 与 ops/local/test-db.sh start 再重跑；"
              + "本类不做「通过」记录。");
    }
    assertThat(environment.getProperty("cn.query.graph.scope"))
        .as("测试必须使用独立作用域，不能写共享 query-public")
        .isEqualTo(SCOPE);
    jdbc.update(
        """
        INSERT INTO qry_generation (generation, status, ready_at, note)
        VALUES (?, 'READY', now(), 'NetworkGraphQueryIT')
        ON CONFLICT (generation) DO NOTHING
        """,
        GENERATION);
    jdbc.update(
        """
        UPDATE qry_consumer_state SET active_generation = ?, received_revision = ?,
               highest_seen_revision = ?, published_revision = ?,
               received_at = now(), published_at = now(), updated_at = now()
        WHERE consumer_id = 'query'
        """,
        GENERATION,
        REVISION,
        REVISION,
        REVISION);

    // w1/w2/w3/w4 构成 A-B-C-D 与 A-D 的菱形；w9/w10 让 A→E 存在两条等长最短路径。
    work("w1", "REAL", List.of("t1"), List.of("o1"), List.of(A, B));
    work("w2", "REAL", List.of("t1"), List.of("o1"), List.of(B, C));
    work("w3", "REAL", List.of("t2"), List.of("o2"), List.of(C, D));
    work("w4", "REAL", List.of("t2"), List.of("o2"), List.of(A, D));
    work("w5", "SIMULATED", List.of("t1"), List.of(), List.of(A, E));
    // 4 位作者 > 阈值 3：PG 物化全部 6 个作者对，图只写 AUTHORED，由二部图推导。
    work("w6", "REAL", List.of("t1"), List.of("o1"), List.of(A, B, C, F));
    // 同一对作者的第二、第三篇共同论文：强度必须是不同成果数。
    work("w7", "REAL", List.of("t1"), List.of(), List.of(A, B));
    work("w8", "REAL", List.of("t2"), List.of(), List.of(B, D));
    work("w9", "REAL", List.of("t1"), List.of(), List.of(B, E));
    work("w10", "REAL", List.of("t2"), List.of(), List.of(C, E));

    writeGraph();
    // 初始水位刻意落后一版：先验证「落后绝不返回图结果」，再推进到一致版本做一致性对照。
    projectionStore.advanceMeta(
        new GraphProjectionMeta(SCOPE, REVISION - 1, REVISION - 1, GENERATION, Instant.now()));
  }

  @AfterAll
  void cleanUp() {
    jdbc.update(
        "UPDATE qry_consumer_state SET active_generation = 'gen0001', received_revision = 0,"
            + " highest_seen_revision = 0, published_revision = 0 WHERE consumer_id = 'query'");
    jdbc.update("DELETE FROM qry_coauthor_edge WHERE generation = ?", GENERATION);
    jdbc.update("DELETE FROM qry_contribution_org WHERE generation = ?", GENERATION);
    jdbc.update("DELETE FROM qry_achievement_topic WHERE generation = ?", GENERATION);
    jdbc.update("DELETE FROM qry_contribution WHERE generation = ?", GENERATION);
    jdbc.update("DELETE FROM qry_achievement WHERE generation = ?", GENERATION);
    jdbc.update("DELETE FROM qry_generation WHERE generation = ?", GENERATION);
    runner.write(
        tx -> {
          tx.run(
                  """
                  MATCH (n)
                  WHERE n.scholar_id STARTS WITH $prefix
                     OR n.work_id STARTS WITH $prefix
                     OR n.scope STARTS WITH $prefix
                  DETACH DELETE n
                  """,
                  Map.of("prefix", PREFIX))
              .consume();
          return null;
        });
  }

  // ===========================================================================
  // 投影水位保护
  // ===========================================================================

  @Test
  @Order(1)
  @DisplayName("投影落后一版：图模式显式回退 PG 并计数，绝不返回旧图")
  void staleProjectionFallsBackToPostgres() {
    long staleFallbacksBefore = metric("staleProjectionFallbackCount");
    long servedBefore = metric("graphServedQueries");

    Map<String, Object> pgView = networkService.neighborhood(A, 2, 0, "REAL", null, null);
    Map<String, Object> routedView = router.neighborhood(A, 2, 0, "REAL", null, null);

    assertSameResponse("stale-neighborhood", pgView, routedView);
    assertThat(metric("staleProjectionFallbackCount")).isEqualTo(staleFallbacksBefore + 1);
    assertThat(metric("graphServedQueries")).isEqualTo(servedBefore);
    assertThat(backendStatus().reasonCode()).isEqualTo(GraphProjectionStatus.PROJECTION_STALE);
  }

  @Test
  @Order(2)
  @DisplayName("水位推进到已发布修订后：判定可用，图结果开始对外提供")
  void projectionCatchesUpAndServesGraph() {
    projectionStore.advanceMeta(
        new GraphProjectionMeta(SCOPE, REVISION, REVISION, GENERATION, Instant.now()));

    GraphProjectionStatus status = graphBackend.status(REVISION, GENERATION);

    assertThat(status.graphUsable()).isTrue();
    assertThat(status.dataComplete()).isTrue();
    assertThat(status.projectedVersion()).isEqualTo(REVISION);
  }

  // ===========================================================================
  // 新旧查询 100% 一致
  // ===========================================================================

  @Test
  @Order(3)
  @DisplayName("自我网络：全部种子 × 深度 1..3 的两条实现响应逐字段一致")
  void neighborhoodParity() {
    long servedBefore = metric("graphServedQueries");

    for (String seed : SCHOLARS) {
      for (int depth = 1; depth <= 3; depth++) {
        Map<String, Object> pgView =
            networkService.neighborhood(seed, depth, 0, "REAL", null, null);
        Map<String, Object> graphView = router.neighborhood(seed, depth, 0, "REAL", null, null);
        assertSameResponse("seed=" + seed + ";depth=" + depth, pgView, graphView);
        assertThat(graphView)
            .as("图模式响应必须保留契约字段（含诊断字段）")
            .containsKeys(
                "graphVersion", "ruleVersion", "publishedRevision", "limits", "withinLimits");
        assertThat(graphView.get("graphVersion")).isEqualTo("g" + GENERATION + "-r" + REVISION);
      }
    }
    // 每位种子每个深度都真正走了图库：不是「碰巧两侧都空」。
    assertThat(metric("graphServedQueries")).isEqualTo(servedBefore + SCHOLARS.size() * 3L);
  }

  @Test
  @Order(4)
  @DisplayName("最短路径：全部点对（含等长路径、0 跳、不可达）的两条实现响应逐字段一致")
  void shortestPathParity() {
    Set<String> endpoints = new LinkedHashSet<>(SCHOLARS);
    endpoints.add("missing-scholar");

    for (String from : endpoints) {
      for (String to : endpoints) {
        Map<String, Object> pgView = networkService.shortestPath(from, to, "REAL", null, null);
        Map<String, Object> graphView = router.shortestPath(from, to, "REAL", null, null);
        assertSameResponse("path " + from + "->" + to, pgView, graphView);
      }
    }

    // 等长最短路径（A→B→E 与 A→C→E）必须选字典序最小的一条。
    Map<String, Object> tie = router.shortestPath(A, E, "REAL", null, null);
    assertThat(tie.get("hops")).isEqualTo(2);
    assertThat(tie.get("path")).isEqualTo(List.of(A, B, E));
    assertThat(networkService.shortestPath(A, E, "REAL", null, null).get("path"))
        .isEqualTo(List.of(A, B, E));

    // 0 跳：在范围内返回 0 跳，不在范围内不伪造可达。
    assertThat(router.shortestPath(A, A, "REAL", null, null).get("hops")).isEqualTo(0);
    assertThat(
            router
                .shortestPath("missing-scholar", "missing-scholar", "REAL", null, null)
                .get("reachable"))
        .isEqualTo(false);
    // 超出 6 跳搜索边界：如实说明「N 跳内未找到」，不冒充全图不连通。
    assertThat(router.shortestPath(A, "not-connected", "REAL", null, null).get("reachable"))
        .isEqualTo(false);
  }

  @Test
  @Order(5)
  @DisplayName("领域/机构/数据性质筛选：两条实现响应逐字段一致（强度按命中成果重新聚合）")
  void filterParity() {
    record Filter(String nature, String topic, String organization) {}
    List<Filter> filters =
        List.of(
            new Filter("REAL", null, null),
            new Filter("ALL", null, null),
            new Filter("SIMULATED", null, null),
            new Filter("REAL", "t1", null),
            new Filter("REAL", "t2", null),
            new Filter("REAL", "t1", "o1"));
    // 每个筛选跑 6 个种子自我网络 + 4 条代表性路径：覆盖足够，又不把双库往返扩成压测。
    List<String[]> pairs =
        List.of(new String[] {A, D}, new String[] {A, E}, new String[] {B, F}, new String[] {C, E});

    for (Filter filter : filters) {
      for (String seed : SCHOLARS) {
        Map<String, Object> pgView =
            networkService.neighborhood(
                seed, 2, 0, filter.nature(), filter.topic(), filter.organization());
        Map<String, Object> graphView =
            router.neighborhood(seed, 2, 0, filter.nature(), filter.topic(), filter.organization());
        assertSameResponse(
            "filter="
                + filter.nature()
                + "/"
                + filter.topic()
                + "/"
                + filter.organization()
                + ";seed="
                + seed,
            pgView,
            graphView);
      }
      for (String[] pair : pairs) {
        String from = pair[0];
        String to = pair[1];
        Map<String, Object> pgView =
            networkService.shortestPath(
                from, to, filter.nature(), filter.topic(), filter.organization());
        Map<String, Object> graphView =
            router.shortestPath(from, to, filter.nature(), filter.topic(), filter.organization());
        assertSameResponse(
            "filter-path="
                + filter.nature()
                + "/"
                + filter.topic()
                + "/"
                + filter.organization()
                + ";"
                + from
                + "->"
                + to,
            pgView,
            graphView);
      }
    }

    // SIMULATED 隔离：REAL 口径只能走真实成果 A-B-E（2 跳）；模拟成果 w5 只在
    // SIMULATED/ALL 口径下参与建边，因此那里才有 1 跳直连。两条实现必须给出同一结论。
    assertThat(networkService.shortestPath(A, E, "REAL", null, null).get("hops")).isEqualTo(2);
    assertThat(router.shortestPath(A, E, "REAL", null, null).get("hops")).isEqualTo(2);
    assertThat(networkService.shortestPath(A, E, "SIMULATED", null, null).get("hops")).isEqualTo(1);
    assertThat(router.shortestPath(A, E, "SIMULATED", null, null).get("hops")).isEqualTo(1);
    assertThat(networkService.shortestPath(A, E, "SIMULATED", null, null).get("path"))
        .isEqualTo(List.of(A, E));
  }

  @Test
  @Order(6)
  @DisplayName("高作者数成果 + 多篇共同论文：物化边与二部图推导给出同一强度与证据语义")
  void highAuthorWorkAndStrengthParity() {
    Map<String, Object> pgView = networkService.neighborhood(A, 1, 0, "REAL", null, null);
    Map<String, Object> graphView = router.neighborhood(A, 1, 0, "REAL", null, null);
    assertSameResponse("high-author-neighborhood", pgView, graphView);

    // A-B 三篇共同论文（w1、w6、w7）：强度是不同成果数，不是行数；A-F 只由高作者数成果 w6 推导。
    assertThat(strengthOf(graphView, A, B)).isEqualTo(3L);
    assertThat(strengthOf(pgView, A, B)).isEqualTo(3L);
    assertThat(strengthOf(graphView, A, F)).isEqualTo(1L);
    assertThat(strengthOf(pgView, A, F)).isEqualTo(1L);

    Map<String, Object> pgPath = networkService.shortestPath(A, F, "REAL", null, null);
    Map<String, Object> graphPath = router.shortestPath(A, F, "REAL", null, null);
    assertSameResponse("high-author-path", pgPath, graphPath);
    assertThat(graphPath.get("hops")).isEqualTo(1);
    assertThat(graphPath.get("path")).isEqualTo(List.of(A, F));
  }

  @Test
  @Order(7)
  @DisplayName("NEO4J_STRICT 在水位一致时由图库返回；水位落后时显式拒绝而不是静默回退")
  void strictModeBehaviour() {
    NetworkGraphQueryMetrics strictMetrics = new NetworkGraphQueryMetrics();
    NetworkQueryRouter strict =
        routerWithMode(GraphQueryMode.NEO4J_STRICT, graphBackend, strictMetrics);

    Map<String, Object> pgView = networkService.shortestPath(A, D, "REAL", null, null);
    Map<String, Object> graphView = strict.shortestPath(A, D, "REAL", null, null);
    assertSameResponse("strict-path", pgView, graphView);
    assertThat(strictMetrics.snapshot()).containsEntry("graphServedQueries", 1L);

    // 让水位落后一版：严格模式必须拒绝。
    runner.write(
        tx -> {
          tx.run(
                  "MATCH (m:GraphProjectionMeta {scope: $scope}) SET m.projected_version = $version",
                  Map.of("scope", SCOPE, "version", REVISION - 1))
              .consume();
          return null;
        });
    try {
      assertThatThrownBy(() -> strict.neighborhood(A, 1, 0, "REAL", null, null))
          .isInstanceOf(BusinessException.class)
          .extracting(exception -> ((BusinessException) exception).errorCode())
          .isEqualTo(ErrorCode.VIEW_UPDATING);
      assertThat(strictMetrics.snapshot()).containsEntry("strictRejections", 1L);
    } finally {
      runner.write(
          tx -> {
            tx.run(
                    "MATCH (m:GraphProjectionMeta {scope: $scope}) SET m.projected_version = $version",
                    Map.of("scope", SCOPE, "version", REVISION))
                .consume();
            return null;
          });
    }
  }

  @Test
  @Order(8)
  @DisplayName("图库不可达：自动回退 PG 并计数；严格模式显式 503，不返回空图")
  void unreachableGraphFallsBackToPostgres() {
    Neo4jGraphProperties deadProperties =
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
    // 刻意不用 Neo4jGraphConfiguration 的 Driver：那里的事务重试窗口是驱动默认的 30 秒，
    // 一个「连接被拒绝」的用例会白等半分钟。这里把重试窗口压到 1 秒，验证的仍是真实连接失败。
    Driver deadDriver =
        org.neo4j.driver.GraphDatabase.driver(
            java.net.URI.create("bolt://127.0.0.1:1"),
            org.neo4j.driver.AuthTokens.basic("neo4j", "secret"),
            org.neo4j.driver.Config.builder()
                .withConnectionTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
                .withMaxTransactionRetryTime(1, java.util.concurrent.TimeUnit.SECONDS)
                .withTelemetryDisabled(true)
                .build());
    try {
      Neo4jSessionRunner deadRunner = new Neo4jSessionRunner(deadDriver, deadProperties);
      CollaborationGraphRepository deadRepository =
          new Neo4jCollaborationGraphRepository(deadRunner, deadProperties);
      CollaborationGraphService deadGraphs =
          new CollaborationGraphService(deadRepository, 20_000, 100_000, 2_000L);
      Neo4jSchemaInitializer readySchema = mock(Neo4jSchemaInitializer.class);
      when(readySchema.isReady()).thenReturn(true);
      GraphProjectionStore deadStore = new Neo4jGraphProjectionStore(deadRunner, deadProperties);
      GraphNetworkBackend deadBackend =
          new NetworkGraphQueryService(
              deadGraphs,
              networkMapper,
              projectionProperties,
              deadProperties,
              new ProjectionWatermarkGuard(deadStore, readySchema, deadProperties));

      NetworkGraphQueryMetrics fallbackMetrics = new NetworkGraphQueryMetrics();
      NetworkQueryRouter fallbackRouter =
          routerWithMode(GraphQueryMode.NEO4J_WITH_PG_FALLBACK, deadBackend, fallbackMetrics);

      Map<String, Object> pgView = networkService.neighborhood(B, 2, 0, "REAL", null, null);
      Map<String, Object> routedView = fallbackRouter.neighborhood(B, 2, 0, "REAL", null, null);
      assertSameResponse("dead-graph-fallback", pgView, routedView);
      assertThat(fallbackMetrics.snapshot()).containsEntry("graphUnavailableFallbackCount", 1L);
      assertThat(fallbackMetrics.snapshot()).containsEntry("graphServedQueries", 0L);

      NetworkQueryRouter strictRouter =
          routerWithMode(GraphQueryMode.NEO4J_STRICT, deadBackend, new NetworkGraphQueryMetrics());
      assertThatThrownBy(() -> strictRouter.neighborhood(B, 1, 0, "REAL", null, null))
          .isInstanceOf(BusinessException.class)
          .extracting(exception -> ((BusinessException) exception).errorCode())
          .isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
    } finally {
      deadDriver.close();
    }
  }

  // ===========================================================================
  // 辅助
  // ===========================================================================

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

  private GraphProjectionStatus backendStatus() {
    return graphBackend.status(REVISION, GENERATION);
  }

  private long metric(String key) {
    Object value = metrics.snapshot().get(key);
    return value instanceof Number number ? number.longValue() : 0L;
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
    for (Map<String, Object> edge : edges) {
      if (a.equals(edge.get("source")) && b.equals(edge.get("target"))) {
        return ((Number) edge.get("strength")).longValue();
      }
    }
    return 0L;
  }

  /** 写入一篇成果到 PG（含作者对边、领域、机构）与图库（含参与关系与领域/机构属性）。 */
  private void work(
      String suffix,
      String nature,
      List<String> topics,
      List<String> organizations,
      List<String> authors) {
    String workId = PREFIX + "-" + suffix;
    String title = "IT Work " + suffix;
    jdbc.update(
        """
        INSERT INTO qry_achievement (generation, achievement_id, type, title, title_norm,
                                     data_nature, visibility, effective_status, publication_year,
                                     aggregate_version, revision)
        VALUES (?, ?, 'PAPER', ?, ?, ?, 'PUBLIC', 'EFFECTIVE', 2024, 1, ?)
        """,
        GENERATION,
        workId,
        title,
        title.toLowerCase(java.util.Locale.ROOT),
        nature,
        REVISION);
    int order = 1;
    List<String> sorted = new ArrayList<>(authors);
    java.util.Collections.sort(sorted);
    for (String scholar : sorted) {
      jdbc.update(
          """
          INSERT INTO qry_contribution (generation, achievement_id, scholar_id, scholar_name,
                                        role, author_order, relation_state)
          VALUES (?, ?, ?, ?, 'AUTHOR', ?, 'AUTO')
          """,
          GENERATION,
          workId,
          scholar,
          "Scholar " + scholar.substring(scholar.lastIndexOf('-') + 1).toUpperCase(),
          order++);
      for (String organization : organizations) {
        jdbc.update(
            """
            INSERT INTO qry_contribution_org (generation, achievement_id, scholar_id, organization)
            VALUES (?, ?, ?, ?)
            """,
            GENERATION,
            workId,
            scholar,
            organization);
      }
    }
    for (int i = 0; i < sorted.size(); i++) {
      for (int j = i + 1; j < sorted.size(); j++) {
        jdbc.update(
            """
            INSERT INTO qry_coauthor_edge (generation, achievement_id, scholar_a, scholar_b)
            VALUES (?, ?, ?, ?)
            """,
            GENERATION,
            workId,
            sorted.get(i),
            sorted.get(j));
      }
    }
    for (String topic : topics) {
      jdbc.update(
          """
          INSERT INTO qry_achievement_topic (generation, achievement_id, topic_id, canonical_name)
          VALUES (?, ?, ?, ?)
          """,
          GENERATION,
          workId,
          topic,
          "Topic " + topic);
    }

    projectionStore.upsertScholars(
        sorted.stream()
            .map(
                scholar ->
                    new GraphScholarNode(
                        scholar,
                        "Scholar " + scholar.substring(scholar.lastIndexOf('-') + 1).toUpperCase(),
                        null,
                        null,
                        "REAL",
                        1L))
            .toList());
    List<GraphAuthorship> authorships = new ArrayList<>();
    for (String scholar : sorted) {
      authorships.add(new GraphAuthorship(scholar, "AUTO", null));
    }
    projectionStore.replaceWork(
        new GraphWorkNode(workId, title, true, nature, 1L, topics, organizations), authorships);
  }

  /** 启动时补齐图库文档中的必要节点（与 PG 行一一对应由 {@link #work} 完成）。 */
  private void writeGraph() {
    projectionStore.upsertScholars(
        SCHOLARS.stream()
            .map(
                scholar ->
                    new GraphScholarNode(
                        scholar,
                        "Scholar " + scholar.substring(scholar.lastIndexOf('-') + 1).toUpperCase(),
                        null,
                        null,
                        "REAL",
                        1L))
            .toList());
  }
}
