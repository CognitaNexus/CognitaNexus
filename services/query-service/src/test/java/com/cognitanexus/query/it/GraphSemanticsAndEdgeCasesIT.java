package com.cognitanexus.query.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cognitanexus.query.graph.application.CollaborationGraphRepository;
import com.cognitanexus.query.graph.application.CollaborationGraphService;
import com.cognitanexus.query.graph.config.Neo4jGraphConfiguration;
import com.cognitanexus.query.graph.config.Neo4jGraphProperties;
import com.cognitanexus.query.graph.domain.GraphCollaborationEdge;
import com.cognitanexus.query.graph.domain.GraphFilterCriteria;
import com.cognitanexus.query.graph.domain.GraphPathResult;
import com.cognitanexus.query.graph.domain.GraphScholarNode;
import com.cognitanexus.query.graph.domain.GraphSubgraphResult;
import com.cognitanexus.query.graph.domain.GraphWorkEvidence;
import com.cognitanexus.query.graph.infrastructure.Neo4jCollaborationGraphRepository;
import com.cognitanexus.query.graph.infrastructure.Neo4jGraphProjectionStore;
import com.cognitanexus.query.graph.infrastructure.Neo4jGraphSchemaStore;
import com.cognitanexus.query.graph.infrastructure.Neo4jSessionRunner;
import com.cognitanexus.query.graph.projection.application.GraphBackfillResult;
import com.cognitanexus.query.graph.projection.application.GraphBackfillService;
import com.cognitanexus.query.graph.projection.application.GraphEventProjectionResult;
import com.cognitanexus.query.graph.projection.application.GraphEventProjector;
import com.cognitanexus.query.graph.projection.application.GraphProjectionCheckpointStore;
import com.cognitanexus.query.graph.projection.application.GraphProjectionProperties;
import com.cognitanexus.query.graph.projection.application.GraphProjectionRetryPolicy;
import com.cognitanexus.query.graph.projection.application.GraphProjectionSource;
import com.cognitanexus.query.graph.projection.application.GraphProjectionSynchronizer;
import com.cognitanexus.query.graph.projection.application.GraphSyncOutcome;
import com.cognitanexus.query.graph.projection.domain.GraphBackfillStatus;
import com.cognitanexus.query.graph.projection.infrastructure.GraphProjectionCheckpointAdapter;
import com.cognitanexus.query.graph.projection.infrastructure.GraphProjectionCheckpointMapper;
import com.cognitanexus.query.graph.projection.infrastructure.GraphProjectionSourceAdapter;
import com.cognitanexus.query.graph.projection.infrastructure.GraphProjectionSourceMapper;
import com.cognitanexus.query.graph.schema.Neo4jSchemaInitializer;
import com.cognitanexus.query.graph.schema.Neo4jSchemaStatementSource;
import com.cognitanexus.query.platform.persistence.JsonCodec;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mybatis.spring.SqlSessionTemplate;
import org.neo4j.driver.Driver;

/**
 * 阶段五（图谱正确性与业务语义）：真实 PostgreSQL → 真实 Neo4j → 真实 Cypher 的端到端验证。
 *
 * <p>本类补齐「图查询本身的正确性」与「业务语义是否真的挡住了不该建边的关系」这两类结论。它们
 * 都不能用假实现证明：
 *
 * <ul>
 *   <li>「4 位作者产生 C(4,2)=6 对边」「22 位作者不物化但二部图仍可推导」——结论落在 Cypher 的
 *       {@code MERGE} 与物化阈值判定上，只有真的建了边才能数出来；
 *   <li>「等长最短路径取字典序最小」——需要真实邻接集合，假仓储只能证明「我按我造的边遍历」；
 *   <li>「撤销公开后边被彻底删除」——需要真实的邮箱事件 → 投影器 → {@code retireWork} 链路；
 *   <li>「超出节点/边上限必须显式失败」——必须有真实规模才能触发。
 * </ul>
 *
 * <p>数据隔离：所有成果标识与学者标识都以本次运行前缀 {@code it-gs-xxxx} 开头，PostgreSQL 使用
 * 独立数据库 {@code query_graph_it}（与其他 IT 共用库但使用<b>独立 generation 与 consumer</b>），
 * 每个用例前按前缀清理图节点与本代 PG 行，不 TRUNCATE 共享表、不删除别人的图数据。
 *
 * <p>依赖：PostgreSQL {@code 127.0.0.1:55432}（{@code ops/local/test-db.sh start}）与 Neo4j
 * {@code bolt://127.0.0.1:17687}（{@code ops/verify/test-infra-smoke.sh up}）。任一不可达时整类跳过
 * 并给出启动方式，<b>不把跳过报告成通过</b>。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GraphSemanticsAndEdgeCasesIT {

  private static final String URI =
      System.getProperty(
          "cn.neo4j.uri", System.getenv().getOrDefault("CN_NEO4J_URI", "bolt://127.0.0.1:17687"));
  private static final String USER =
      System.getProperty("cn.neo4j.user", System.getenv().getOrDefault("CN_NEO4J_USER", "neo4j"));
  private static final String PASSWORD =
      System.getProperty(
          "cn.neo4j.password",
          System.getenv().getOrDefault("CN_NEO4J_PASSWORD", "cn_test_neo4j_2026"));

  private static final String PG_HOST = "127.0.0.1";
  private static final int PG_PORT = 55432;
  private static final String DB_NAME = "query_graph_it";
  private static final String DB_URL =
      "jdbc:postgresql://" + PG_HOST + ":" + PG_PORT + "/" + DB_NAME;

  private static final String PREFIX = "it-gs-" + UUID.randomUUID().toString().substring(0, 8);
  private static final String GEN = "gen-" + PREFIX;
  private static final String SCOPE = PREFIX + "-scope";
  private static final String CONSUMER = "consumer-" + PREFIX;
  private static final long PUBLISHED_REVISION = 100L;

  /** 物化阈值取 20（生产默认值）：22 位作者的高作者数成果才能触发「二部图推导」分支。 */
  private static final int MATERIALIZE_THRESHOLD = 20;

  private static HikariDataSource dataSource;
  private static Driver driver;
  private static Neo4jSessionRunner runner;
  private static Neo4jGraphProperties neo4jProperties;
  private static Neo4jGraphProjectionStore store;
  private static Neo4jCollaborationGraphRepository repository;
  private static CollaborationGraphService graph;
  private static GraphProjectionSource source;
  private static GraphProjectionCheckpointMapper checkpointMapper;
  private static GraphProjectionSynchronizer synchronizer;
  private static GraphProjectionCheckpointStore checkpoints;
  private static GraphBackfillService backfill;
  private static GraphEventProjector projector;

  @BeforeAll
  static void setUp() {
    neo4jProperties =
        new Neo4jGraphProperties(
            true,
            URI,
            USER,
            PASSWORD,
            "neo4j",
            Duration.ofSeconds(5),
            Duration.ofSeconds(20),
            MATERIALIZE_THRESHOLD,
            50,
            20_000,
            100_000,
            2_000L,
            SCOPE,
            true);
    try {
      driver = new Neo4jGraphConfiguration().neo4jDriver(neo4jProperties);
      driver.verifyConnectivity();
    } catch (RuntimeException ex) {
      Assumptions.abort(
          "Neo4j 集成测试实例不可用（"
              + URI
              + "）："
              + ex.getClass().getSimpleName()
              + "。启动方式见 ops/compose/compose.test-infra.yaml 与 ops/verify/test-infra-smoke.sh。");
    }
    runner = new Neo4jSessionRunner(driver, neo4jProperties);
    Neo4jSchemaInitializer initializer =
        new Neo4jSchemaInitializer(
            new Neo4jGraphSchemaStore(runner), new Neo4jSchemaStatementSource(), true);
    initializer.initialize();
    initializer.initialize();
    assertThat(initializer.isReady()).as("schema 初始化必须幂等成功：缺唯一约束的图会把同一位学者变成两个节点").isTrue();

    store = new Neo4jGraphProjectionStore(runner, neo4jProperties);
    repository = new Neo4jCollaborationGraphRepository(runner, neo4jProperties);
    graph =
        new CollaborationGraphService(
            repository,
            neo4jProperties.maxNodes(),
            neo4jProperties.maxEdges(),
            neo4jProperties.budgetMillis());

    ensureDatabase();
    migrate();
    dataSource = newPool();
    SqlSessionTemplate template = new SqlSessionTemplate(sqlSessionFactory(dataSource));
    GraphProjectionSourceMapper sourceMapper =
        template.getMapper(GraphProjectionSourceMapper.class);
    checkpointMapper = template.getMapper(GraphProjectionCheckpointMapper.class);
    source = new GraphProjectionSourceAdapter(sourceMapper, new JsonCodec(new ObjectMapper()));
    checkpoints = new GraphProjectionCheckpointAdapter(checkpointMapper);
    GraphProjectionProperties properties =
        new GraphProjectionProperties(true, 2, 3, 0L, 100L, false, 5_000L, 200, 500, 10, true);
    synchronizer =
        new GraphProjectionSynchronizer(
            source, store, GraphProjectionRetryPolicy.noRetry(), millis -> {});
    backfill =
        new GraphBackfillService(source, synchronizer, store, checkpoints, properties, CONSUMER);
    projector =
        new GraphEventProjector(
            source, synchronizer, store, checkpoints, new JsonCodec(new ObjectMapper()), CONSUMER);
  }

  @AfterAll
  static void tearDown() {
    try {
      wipeGraph();
      cleanupPostgres();
    } finally {
      if (dataSource != null) {
        dataSource.close();
      }
      if (driver != null) {
        driver.close();
      }
    }
  }

  @BeforeEach
  void reset() {
    wipeGraph();
    cleanupPostgres();
    seedConsumerState();
  }

  // ===========================================================================
  // (1) 图谱正确性
  // ===========================================================================

  @Test
  @Order(1)
  @DisplayName("图谱正确性/单学者：无合作边时 seedInScope=false，不伪造孤立节点或 0 跳可达")
  void singleScholarWithoutCollaborationHasNoEdges() {
    String solo = scholar("solo");
    work("w-0101", "独著论文", 10L, "PUBLIC", "EFFECTIVE", authors(author(solo, "AUTO")));

    assertThat(backfill.backfill(SCOPE).status()).isEqualTo(GraphBackfillStatus.COMPLETED);

    GraphSubgraphResult neighborhood =
        graph.neighborhood(solo, 1, 0, GraphFilterCriteria.realOnly());
    assertThat(neighborhood.seedInScope()).as("没有任何合格合作边时不能声称「他有合作者」").isFalse();
    assertThat(neighborhood.edges()).isEmpty();
    assertThat(neighborhood.nodes()).isEmpty();
    assertThat(neighborhood.note()).contains("没有合格合作边");

    assertThat(repository.expand(List.of(solo), GraphFilterCriteria.realOnly(), 100).edges())
        .isEmpty();
    assertThat(graph.shortestPath(solo, solo, GraphFilterCriteria.realOnly(), 2_000L).reachable())
        .as("独著学者自己到自己也不得按 0 跳可达返回")
        .isFalse();
  }

  @Test
  @Order(2)
  @DisplayName("图谱正确性/两学者：共同论文产生 1 条合作边，强度=1 且带 work_id + work_title 证据")
  void twoScholarCoauthorshipProducesSingleEdgeWithEvidence() {
    String a = scholar("a");
    String b = scholar("b");
    String workId =
        work(
            "w-0201",
            "两学者合作论文",
            10L,
            "PUBLIC",
            "EFFECTIVE",
            authors(author(a, "AUTO"), author(b, "SELF_CONFIRMED")));

    backfill.backfill(SCOPE);

    CollaborationGraphRepository.Expansion expansion =
        repository.expand(List.of(a), GraphFilterCriteria.realOnly(), 100);
    assertThat(expansion.truncated()).isFalse();
    assertThat(expansion.edges()).hasSize(1);
    GraphCollaborationEdge edge = expansion.edges().get(0);
    assertThat(edge.scholarA()).isEqualTo(a);
    assertThat(edge.scholarB()).isEqualTo(b);
    assertThat(edge.strength()).isEqualTo(1);
    assertThat(edge.evidence()).hasSize(1);
    GraphWorkEvidence evidence = edge.evidence().get(0);
    assertThat(evidence.workId()).isEqualTo(workId);
    assertThat(evidence.title()).isEqualTo("两学者合作论文");
    assertThat(evidence.dataOrigin()).isEqualTo("REAL");

    GraphPathResult path = graph.shortestPath(a, b, GraphFilterCriteria.realOnly(), 2_000L);
    assertThat(path.reachable()).isTrue();
    assertThat(path.hops()).isEqualTo(1);
    assertThat(path.path()).containsExactly(a, b);
    assertThat(path.hopEdges())
        .singleElement()
        .satisfies(hop -> assertThat(hop.evidence().get(0).workId()).isEqualTo(workId));
  }

  @Test
  @Order(3)
  @DisplayName("图谱正确性/多人共同论文：4 人 → C(4,2)=6 对边，5 人 → 10 对边，多篇共同论文强度累加")
  void multiAuthorWorkMaterializesAllPairsAndWeightsAccumulate() {
    String[] four = {scholar("m1"), scholar("m2"), scholar("m3"), scholar("m4")};
    String[] five = {scholar("m1"), scholar("m2"), scholar("m3"), scholar("m4"), scholar("m5")};
    work("w-0301", "四人合作论文", 10L, "PUBLIC", "EFFECTIVE", authorsOf(four));
    work("w-0302", "五人合作论文", 11L, "PUBLIC", "EFFECTIVE", authorsOf(five));
    work(
        "w-0303",
        "两人再次合作",
        12L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(four[0], "AUTO"), author(four[1], "AUTO")));

    backfill.backfill(SCOPE);

    // 4 人成果 6 对 + 5 人成果 10 对 + 2 人成果 1 对 = 17 条物化边（同一作者对可重复承载不同成果）。
    assertThat(
            countEdges(
                "MATCH ()-[c:COOPERATED_WITH]->() WHERE c.work_id STARTS WITH $p "
                    + "RETURN count(c) AS n",
                Map.of("p", PREFIX)))
        .as("4 人 C(4,2)=6、5 人 C(5,2)=10、2 人 1，合计 17 条边")
        .isEqualTo(17L);

    GraphCollaborationEdge firstSecond = edgeBetween(four[0], four[1]);
    assertThat(firstSecond.strength())
        .as("m1/m2 在 w-0301、w-0302、w-0303 三篇共同论文上合作，强度是不同成果数")
        .isEqualTo(3);
    assertThat(firstSecond.evidence())
        .extracting(GraphWorkEvidence::workId)
        .containsExactlyElementsOf(
            List.of(PREFIX + "-w-0301", PREFIX + "-w-0302", PREFIX + "-w-0303"));
    assertThat(edgeBetween(four[0], scholar("m5")).strength()).isEqualTo(1);
  }

  @Test
  @Order(4)
  @DisplayName("图谱正确性/多条等长最短路径：严格按规范 ID 字典序最小确定性选择，重复查询结果不变")
  void equalLengthShortestPathsChooseLexicographicallySmallestDeterministically() {
    String start = scholar("p0");
    String smaller = scholar("p1");
    String larger = scholar("p2");
    String target = scholar("p3");
    work(
        "w-0401",
        "起点到 p1",
        10L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(start, "AUTO"), author(smaller, "AUTO")));
    work(
        "w-0402",
        "p1 到终点",
        11L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(smaller, "AUTO"), author(target, "AUTO")));
    work(
        "w-0403",
        "起点到 p2",
        12L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(start, "AUTO"), author(larger, "AUTO")));
    work(
        "w-0404",
        "p2 到终点",
        13L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(larger, "AUTO"), author(target, "AUTO")));

    backfill.backfill(SCOPE);

    // 两条等长路径：p0→p1→p3 与 p0→p2→p3；p1 < p2，必须恒选前者。
    List<String> expected = List.of(start, smaller, target);
    for (int attempt = 0; attempt < 5; attempt++) {
      GraphPathResult path =
          graph.shortestPath(start, target, GraphFilterCriteria.realOnly(), 2_000L);
      assertThat(path.reachable()).isTrue();
      assertThat(path.hops()).isEqualTo(2);
      assertThat(path.path())
          .as("等长最短路径必须取规范 ID 字典序最小的一条（第 %s 次查询）", attempt + 1)
          .containsExactlyElementsOf(expected);
    }
    GraphPathResult reversed =
        graph.shortestPath(target, start, GraphFilterCriteria.realOnly(), 2_000L);
    assertThat(reversed.path())
        .as("反向查询也必须落到同一条规范路径，而不是另一条等长路径")
        .containsExactlyElementsOf(List.of(target, smaller, start));
  }

  @Test
  @Order(5)
  @DisplayName("图谱正确性/不连通图：两个连通分量之间 reachable=false，不伪造路径也不谎称全图不连通")
  void disconnectedComponentsHaveNoFabricatedPath() {
    String leftA = scholar("d1");
    String leftB = scholar("d2");
    String rightA = scholar("d3");
    String rightB = scholar("d4");
    work(
        "w-0501",
        "左分量",
        10L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(leftA, "AUTO"), author(leftB, "AUTO")));
    work(
        "w-0502",
        "右分量",
        11L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(rightA, "AUTO"), author(rightB, "AUTO")));

    backfill.backfill(SCOPE);

    GraphPathResult cross =
        graph.shortestPath(leftA, rightA, GraphFilterCriteria.realOnly(), 2_000L);
    assertThat(cross.reachable()).isFalse();
    assertThat(cross.hops()).isNull();
    assertThat(cross.path()).isEmpty();
    assertThat(cross.hopEdges()).isEmpty();
    assertThat(cross.note()).as("不可达必须说明是「N 跳内未找到」，不能断言全图不连通").contains("6 跳范围内未找到");

    assertThat(graph.shortestPath(leftA, leftB, GraphFilterCriteria.realOnly(), 2_000L).reachable())
        .as("分量内部仍然可达：不可达结论不能把整张图说成空的")
        .isTrue();
    assertThat(graph.neighborhood(leftA, 3, 0, GraphFilterCriteria.realOnly()).nodes())
        .extracting(GraphScholarNode::scholarId)
        .containsExactlyInAnyOrder(leftA, leftB);
  }

  @Test
  @Order(6)
  @DisplayName("图谱正确性/最大路径深度：6 跳可达、7 跳超出 MAX_PATH_DEPTH=6 不可达，depth=99 自动截断为 3")
  void pathDepthBoundaryAndNeighborhoodDepthClamp() {
    List<String> chain = new ArrayList<>();
    for (int i = 1; i <= 8; i++) {
      chain.add(scholar("c" + i));
    }
    for (int i = 1; i <= 7; i++) {
      work(
          "w-06" + i,
          "链式论文 " + i,
          10L + i,
          "PUBLIC",
          "EFFECTIVE",
          authors(author(chain.get(i - 1), "AUTO"), author(chain.get(i), "AUTO")));
    }

    backfill.backfill(SCOPE);

    GraphPathResult sixHops =
        graph.shortestPath(chain.get(0), chain.get(6), GraphFilterCriteria.realOnly(), 2_000L);
    assertThat(sixHops.reachable()).as("6 跳恰好在上限内").isTrue();
    assertThat(sixHops.hops()).isEqualTo(6);
    assertThat(sixHops.path()).hasSize(7);

    GraphPathResult sevenHops =
        graph.shortestPath(chain.get(0), chain.get(7), GraphFilterCriteria.realOnly(), 2_000L);
    assertThat(sevenHops.reachable()).as("7 跳超出 MAX_PATH_DEPTH=6，必须如实返回不可达").isFalse();
    assertThat(sevenHops.note()).contains("6 跳范围内未找到");

    GraphSubgraphResult deep =
        graph.neighborhood(chain.get(0), 99, 0, GraphFilterCriteria.realOnly());
    assertThat(deep.depth()).as("depth=99 必须被截断为 MAX_NEIGHBORHOOD_DEPTH=3").isEqualTo(3);
    assertThat(deep.nodes())
        .extracting(GraphScholarNode::scholarId)
        .containsExactlyInAnyOrder(chain.get(0), chain.get(1), chain.get(2), chain.get(3));
    assertThat(deep.edges()).hasSize(3);
  }

  @Test
  @Order(7)
  @DisplayName("图谱正确性/环：三角环与带弦四元环下 BFS 不重复计节点、不死循环，最短路径取更短的那条")
  void cyclesDoNotLoopOrDuplicateNodes() {
    String t1 = scholar("t1");
    String t2 = scholar("t2");
    String t3 = scholar("t3");
    work(
        "w-0701",
        "三角一",
        10L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(t1, "AUTO"), author(t2, "AUTO")));
    work(
        "w-0702",
        "三角二",
        11L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(t2, "AUTO"), author(t3, "AUTO")));
    work(
        "w-0703",
        "三角三",
        12L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(t3, "AUTO"), author(t1, "AUTO")));

    String q1 = scholar("q1");
    String q2 = scholar("q2");
    String q3 = scholar("q3");
    String q4 = scholar("q4");
    work(
        "w-0711",
        "四元环一",
        13L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(q1, "AUTO"), author(q2, "AUTO")));
    work(
        "w-0712",
        "四元环二",
        14L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(q2, "AUTO"), author(q3, "AUTO")));
    work(
        "w-0713",
        "四元环三",
        15L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(q3, "AUTO"), author(q4, "AUTO")));
    work(
        "w-0714",
        "四元环四",
        16L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(q4, "AUTO"), author(q1, "AUTO")));
    work(
        "w-0715",
        "四元环弦",
        17L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(q1, "AUTO"), author(q3, "AUTO")));

    backfill.backfill(SCOPE);

    GraphSubgraphResult triangle = graph.neighborhood(t1, 3, 0, GraphFilterCriteria.realOnly());
    assertThat(triangle.nodes()).extracting(GraphScholarNode::scholarId).hasSize(3);
    assertThat(triangle.nodes()).extracting(GraphScholarNode::scholarId).doesNotHaveDuplicates();
    assertThat(triangle.edges()).hasSize(3);
    assertThat(triangle.visitedNodes())
        .as("三角形里 visitedNodes 只能是 3：重复计节点说明 BFS 没有按已访问集合剪枝")
        .isEqualTo(3);
    assertThat(triangle.loadedEdges()).isEqualTo(3);

    GraphSubgraphResult chorded = graph.neighborhood(q1, 3, 0, GraphFilterCriteria.realOnly());
    assertThat(chorded.nodes()).hasSize(4);
    assertThat(chorded.nodes()).extracting(GraphScholarNode::scholarId).doesNotHaveDuplicates();
    assertThat(chorded.edges()).hasSize(5);

    // 带弦之后 q1→q3 是 1 跳（弦），不是绕环 2 跳。
    GraphPathResult shortcut = graph.shortestPath(q1, q3, GraphFilterCriteria.realOnly(), 2_000L);
    assertThat(shortcut.hops()).isEqualTo(1);
    assertThat(shortcut.path()).containsExactly(q1, q3);
    assertThat(graph.shortestPath(q1, q4, GraphFilterCriteria.realOnly(), 2_000L).hops())
        .isEqualTo(1);
    assertThat(graph.shortestPath(q2, q4, GraphFilterCriteria.realOnly(), 2_000L).hops())
        .as("q2→q4 只能绕环：2 跳，且不得因环而死循环")
        .isEqualTo(2);
  }

  @Test
  @Order(8)
  @DisplayName("图谱正确性/高度数节点：22 位作者 > 物化阈值 20 时不产生 231 条边爆炸，二部图推导结果完整")
  void highDegreeWorkUsesBipartiteTraversalWithoutEdgeExplosion() {
    List<String> authors = new ArrayList<>();
    for (int i = 1; i <= 22; i++) {
      authors.add(scholar("h" + i));
    }
    String workId =
        work(
            "w-0801",
            "超大协作论文",
            10L,
            "PUBLIC",
            "EFFECTIVE",
            authorsOf(authors.toArray(String[]::new)));

    backfill.backfill(SCOPE);

    assertThat(
            countEdges(
                "MATCH ()-[c:COOPERATED_WITH]->() WHERE c.work_id = $w RETURN count(c) AS n",
                Map.of("w", workId)))
        .as("超过物化阈值必须一条作者对边都不写（否则 C(22,2)=231 条边爆炸）")
        .isZero();
    assertThat(
            countEdges(
                "MATCH (:Scholar)-[a:AUTHORED]->(:Work {work_id: $w}) RETURN count(a) AS n",
                Map.of("w", workId)))
        .isEqualTo(22L);

    CollaborationGraphRepository.Expansion expansion =
        repository.expand(List.of(authors.get(0)), GraphFilterCriteria.realOnly(), 100);
    assertThat(expansion.truncated()).isFalse();
    assertThat(expansion.edges()).hasSize(21);
    assertThat(expansion.edges())
        .allSatisfy(
            edge -> {
              assertThat(edge.strength()).isEqualTo(1);
              assertThat(edge.evidence())
                  .singleElement()
                  .satisfies(
                      evidence -> {
                        assertThat(evidence.workId()).isEqualTo(workId);
                        assertThat(evidence.title()).isEqualTo("超大协作论文");
                      });
            });

    GraphPathResult derived =
        graph.shortestPath(authors.get(0), authors.get(21), GraphFilterCriteria.realOnly(), 2_000L);
    assertThat(derived.reachable()).isTrue();
    assertThat(derived.hops()).as("二部图推导下同一篇成果的合作者仍是 1 跳").isEqualTo(1);
    assertThat(derived.hopEdges())
        .singleElement()
        .satisfies(hop -> assertThat(hop.evidence().get(0).workId()).isEqualTo(workId));
  }

  @Test
  @Order(9)
  @DisplayName("图谱正确性/过滤子图：按领域与机构筛选后边强度按命中成果重新聚合，不含未命中成果")
  void filteredSubgraphRecomputesStrengthFromMatchingWorks() {
    String a = scholar("f1");
    String b = scholar("f2");
    work(
        "w-0901",
        "领域一机构一",
        10L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(a, "AUTO"), author(b, "AUTO")),
        List.of("topic-1"),
        List.of("org-1"));
    work(
        "w-0902",
        "领域一机构二",
        11L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(a, "AUTO"), author(b, "AUTO")),
        List.of("topic-1"),
        List.of("org-2"));
    work(
        "w-0903",
        "领域二机构一",
        12L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(a, "AUTO"), author(b, "AUTO")),
        List.of("topic-2"),
        List.of("org-1"));

    backfill.backfill(SCOPE);

    GraphCollaborationEdge unfiltered = edgeBetween(a, b, GraphFilterCriteria.realOnly());
    assertThat(unfiltered.strength()).isEqualTo(3);

    GraphCollaborationEdge byTopic =
        edgeBetween(a, b, GraphFilterCriteria.realOnly().withTopic("topic-1"));
    assertThat(byTopic.strength()).isEqualTo(2);
    assertThat(byTopic.evidence())
        .extracting(GraphWorkEvidence::workId)
        .containsExactly(PREFIX + "-w-0901", PREFIX + "-w-0902");

    GraphCollaborationEdge byOrganization =
        edgeBetween(a, b, GraphFilterCriteria.realOnly().withOrganization("org-1"));
    assertThat(byOrganization.strength()).isEqualTo(2);
    assertThat(byOrganization.evidence())
        .extracting(GraphWorkEvidence::workId)
        .containsExactly(PREFIX + "-w-0901", PREFIX + "-w-0903");

    GraphCollaborationEdge both =
        edgeBetween(
            a, b, GraphFilterCriteria.realOnly().withTopic("topic-2").withOrganization("org-1"));
    assertThat(both.strength()).isEqualTo(1);
    assertThat(both.evidence())
        .extracting(GraphWorkEvidence::workId)
        .containsExactly(PREFIX + "-w-0903");

    // 未命中任何成果的筛选条件返回空图，而不是回落到未筛选结果。
    GraphSubgraphResult none =
        graph.neighborhood(a, 1, 0, GraphFilterCriteria.realOnly().withTopic("topic-missing"));
    assertThat(none.seedInScope()).isFalse();
    assertThat(none.edges()).isEmpty();
  }

  @Test
  @Order(10)
  @DisplayName("图谱正确性/超出限制：超过 maxNodes 或 maxEdges 抛 GRAPH_SCOPE_TOO_LARGE，不返回截断残图")
  void nodeAndEdgeLimitsRaiseScopeTooLargeWithoutTruncation() {
    String hub = scholar("hub");
    List<String> leaves = new ArrayList<>();
    for (int i = 1; i <= 10; i++) {
      String leaf = scholar("leaf" + i);
      leaves.add(leaf);
      work(
          "w-10" + String.format("%02d", i),
          "星形论文 " + i,
          10L + i,
          "PUBLIC",
          "EFFECTIVE",
          authors(author(hub, "AUTO"), author(leaf, "AUTO")));
    }

    backfill.backfill(SCOPE);

    CollaborationGraphService tinyNodes =
        new CollaborationGraphService(repository, 5, 1_000, 2_000L);
    assertThatThrownBy(() -> tinyNodes.neighborhood(hub, 2, 0, GraphFilterCriteria.realOnly()))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex -> {
              BusinessException business = (BusinessException) ex;
              assertThat(business.errorCode()).isEqualTo(ErrorCode.GRAPH_SCOPE_TOO_LARGE);
              assertThat(business.getMessage()).contains("上限");
            });

    CollaborationGraphService tinyEdges =
        new CollaborationGraphService(repository, 1_000, 3, 2_000L);
    assertThatThrownBy(() -> tinyEdges.neighborhood(hub, 2, 0, GraphFilterCriteria.realOnly()))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).errorCode())
                    .isEqualTo(ErrorCode.GRAPH_SCOPE_TOO_LARGE));

    CollaborationGraphService tinyPathEdges =
        new CollaborationGraphService(repository, 1_000, 2, 2_000L);
    assertThatThrownBy(
            () ->
                tinyPathEdges.shortestPath(
                    hub, leaves.get(0), GraphFilterCriteria.realOnly(), 2_000L))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).errorCode())
                    .isEqualTo(ErrorCode.GRAPH_SCOPE_TOO_LARGE));

    // 对照：正常上限下同一查询可用，说明失败确实来自上限而不是数据缺失。
    assertThat(graph.neighborhood(hub, 2, 0, GraphFilterCriteria.realOnly()).edges()).hasSize(10);
  }

  @Test
  @Order(11)
  @DisplayName("图谱正确性/超时：超出时间预算抛 GRAPH_TIMEOUT，不返回半条路径或空结果冒充成功")
  void graphTimeoutRaisesGraphTimeoutWithoutPartialPath() {
    List<String> chain = new ArrayList<>();
    for (int i = 1; i <= 5; i++) {
      chain.add(scholar("to" + i));
    }
    for (int i = 1; i <= 4; i++) {
      work(
          "w-11" + i,
          "超时链 " + i,
          10L + i,
          "PUBLIC",
          "EFFECTIVE",
          authors(author(chain.get(i - 1), "AUTO"), author(chain.get(i), "AUTO")));
    }

    backfill.backfill(SCOPE);

    // 真实 Neo4j 仓储 + 人为放大的逐层延迟：证明超时判定真的按时间预算生效，而不是靠数据为空。
    CollaborationGraphRepository slow = new SlowGraphRepository(repository, 15L);
    CollaborationGraphService slowGraph = new CollaborationGraphService(slow, 20_000, 100_000, 1L);

    assertThatThrownBy(
            () ->
                slowGraph.shortestPath(
                    chain.get(0), chain.get(4), GraphFilterCriteria.realOnly(), 1L))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex -> {
              BusinessException business = (BusinessException) ex;
              assertThat(business.errorCode()).isEqualTo(ErrorCode.GRAPH_TIMEOUT);
              assertThat(business.getMessage()).contains("时间预算");
            });

    assertThat(
            graph
                .shortestPath(chain.get(0), chain.get(4), GraphFilterCriteria.realOnly(), 2_000L)
                .hops())
        .as("同一份数据在正常预算下 4 跳可达：超时不是数据问题")
        .isEqualTo(4);
  }

  // ===========================================================================
  // (2) 业务语义
  // ===========================================================================

  @Test
  @Order(20)
  @DisplayName("业务语义/未公开成果：visibility!=PUBLIC 或 effective_status!=EFFECTIVE 不参与公开合作")
  void nonPublicOrIneffectiveWorksAreExcludedFromGraph() {
    String a = scholar("v1");
    String b = scholar("v2");
    String c = scholar("v3");
    String d = scholar("v4");
    work(
        "w-2001",
        "公开生效论文",
        10L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(a, "AUTO"), author(b, "AUTO")));
    work(
        "w-2002",
        "已收紧论文",
        11L,
        "RESTRICTED",
        "EFFECTIVE",
        authors(author(a, "AUTO"), author(c, "AUTO")));
    work(
        "w-2003",
        "已失效论文",
        12L,
        "PUBLIC",
        "SUPERSEDED",
        authors(author(a, "AUTO"), author(d, "AUTO")));

    backfill.backfill(SCOPE);

    assertThat(
            countEdges(
                "MATCH (w:Work) WHERE w.work_id STARTS WITH $p RETURN count(w) AS n",
                Map.of("p", PREFIX)))
        .as("只有合格成果进图，不合格成果连墓碑都不应有（它们从未被投影过）")
        .isEqualTo(1L);
    assertThat(
            countEdges(
                "MATCH ()-[c:COOPERATED_WITH]->() WHERE c.work_id STARTS WITH $p RETURN count(c) AS n",
                Map.of("p", PREFIX)))
        .isEqualTo(1L);

    assertThat(graph.neighborhood(a, 2, 0, GraphFilterCriteria.realOnly()).nodes())
        .extracting(GraphScholarNode::scholarId)
        .containsExactlyInAnyOrder(a, b);
    assertThat(graph.shortestPath(a, c, GraphFilterCriteria.realOnly(), 2_000L).reachable())
        .as("未公开成果不得产生可达路径")
        .isFalse();
    assertThat(graph.shortestPath(a, d, GraphFilterCriteria.realOnly(), 2_000L).reachable())
        .as("未生效成果不得产生可达路径")
        .isFalse();
  }

  @Test
  @Order(21)
  @DisplayName("业务语义/不合格关系：PENDING_VERIFY 与 DISPUTED 不建参与边，也不参与建合作边")
  void unqualifiedRelationStatesDoNotCreateEdges() {
    String a = scholar("r1");
    String b = scholar("r2");
    String pending = scholar("r3");
    String disputed = scholar("r4");
    work(
        "w-2101",
        "四种关系状态的论文",
        10L,
        "PUBLIC",
        "EFFECTIVE",
        authors(
            author(a, "AUTO"),
            author(b, "SELF_CONFIRMED"),
            author(pending, "PENDING_VERIFY"),
            author(disputed, "DISPUTED")));

    backfill.backfill(SCOPE);

    assertThat(
            countEdges(
                "MATCH (:Scholar)-[x:AUTHORED]->(:Work {work_id: $w}) RETURN count(x) AS n",
                Map.of("w", PREFIX + "-w-2101")))
        .as("只有 AUTO 与 SELF_CONFIRMED 建参与边")
        .isEqualTo(2L);
    assertThat(
            countEdges(
                "MATCH ()-[c:COOPERATED_WITH]->() WHERE c.work_id = $w RETURN count(c) AS n",
                Map.of("w", PREFIX + "-w-2101")))
        .as("2 位合格作者 → 1 条作者对边；待核验/争议关系不得被算成确定合作")
        .isEqualTo(1L);
    assertThat(edgeBetween(a, b).strength()).isEqualTo(1);
    assertThat(graph.shortestPath(a, pending, GraphFilterCriteria.realOnly(), 2_000L).reachable())
        .isFalse();
    assertThat(graph.shortestPath(a, disputed, GraphFilterCriteria.realOnly(), 2_000L).reachable())
        .isFalse();
  }

  @Test
  @Order(22)
  @DisplayName("业务语义/认领状态变化：待核验→自我确认后同步入图建边，撤销为已排除后边被移除")
  void claimStateTransitionsAddAndRemoveEdges() {
    String a = scholar("k1");
    String b = scholar("k2");
    String workId = PREFIX + "-w-2201";
    work(
        "w-2201",
        "认领状态论文",
        10L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(a, "PENDING_VERIFY"), author(b, "PENDING_VERIFY")));

    backfill.backfill(SCOPE);
    assertThat(cooperatedEdgesOf(workId)).as("两条关系都待核验时不得建边").isZero();
    assertThat(graph.neighborhood(a, 1, 0, GraphFilterCriteria.realOnly()).seedInScope())
        .as("没有合格关系时种子不在范围内")
        .isFalse();

    // 第一位作者确认：仍不足以形成合作（第二位还是待核验）。
    update(
        "UPDATE qry_contribution SET relation_state = 'SELF_CONFIRMED' "
            + "WHERE generation = ? AND achievement_id = ? AND scholar_id = ?",
        GEN,
        workId,
        a);
    update(
        "UPDATE qry_achievement SET aggregate_version = 20 WHERE generation = ? AND achievement_id = ?",
        GEN,
        workId);
    assertThat(synchronizer.syncWork(GEN, workId).orElseThrow().action())
        .isEqualTo(GraphSyncOutcome.Action.APPLIED);
    assertThat(cooperatedEdgesOf(workId)).isZero();

    // 第二位作者也确认：合作成立，图中出现 1 条边。
    update(
        "UPDATE qry_contribution SET relation_state = 'SELF_CONFIRMED' "
            + "WHERE generation = ? AND achievement_id = ? AND scholar_id = ?",
        GEN,
        workId,
        b);
    update(
        "UPDATE qry_achievement SET aggregate_version = 30 WHERE generation = ? AND achievement_id = ?",
        GEN,
        workId);
    assertThat(synchronizer.syncWork(GEN, workId).orElseThrow().action())
        .isEqualTo(GraphSyncOutcome.Action.APPLIED);
    assertThat(cooperatedEdgesOf(workId)).as("确认后同步入图建边").isEqualTo(1L);
    assertThat(graph.shortestPath(a, b, GraphFilterCriteria.realOnly(), 2_000L).reachable())
        .isTrue();

    // 关系被撤销为「已排除」：边必须从图里消失，路径不再可达。
    update(
        "UPDATE qry_contribution SET relation_state = 'EXCLUDED' "
            + "WHERE generation = ? AND achievement_id = ? AND scholar_id = ?",
        GEN,
        workId,
        a);
    update(
        "UPDATE qry_achievement SET aggregate_version = 40 WHERE generation = ? AND achievement_id = ?",
        GEN,
        workId);
    assertThat(synchronizer.syncWork(GEN, workId).orElseThrow().action())
        .isEqualTo(GraphSyncOutcome.Action.APPLIED);
    assertThat(cooperatedEdgesOf(workId)).as("撤销后合作边必须被删除").isZero();
    assertThat(graph.shortestPath(a, b, GraphFilterCriteria.realOnly(), 2_000L).reachable())
        .isFalse();
    assertThat(graph.neighborhood(b, 1, 0, GraphFilterCriteria.realOnly()).seedInScope()).isFalse();
  }

  @Test
  @Order(23)
  @DisplayName("业务语义/重复导入幂等：同一成果多次导入不翻倍边数与强度，重复回填不再产生副作用")
  void duplicateImportIsIdempotentForEdgesAndStrength() {
    String a = scholar("i1");
    String b = scholar("i2");
    String workId = PREFIX + "-w-2301";
    work(
        "w-2301",
        "幂等论文",
        10L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(a, "AUTO"), author(b, "AUTO")));
    work(
        "w-2302",
        "幂等论文二",
        11L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(a, "AUTO"), author(b, "AUTO")));

    assertThat(backfill.backfill(SCOPE).status()).isEqualTo(GraphBackfillStatus.COMPLETED);
    long edgesAfterFirst =
        countEdges(
            "MATCH ()-[c:COOPERATED_WITH]->() WHERE c.work_id STARTS WITH $p RETURN count(c) AS n",
            Map.of("p", PREFIX));
    long authoredAfterFirst =
        countEdges(
            "MATCH (:Scholar)-[x:AUTHORED]->() WHERE x.work_id STARTS WITH $p RETURN count(x) AS n",
            Map.of("p", PREFIX));

    // 同一版本重复投递：整体替换再次执行（结果幂等），边数与强度均不翻倍。
    GraphSyncOutcome replay = synchronizer.syncWork(GEN, workId).orElseThrow();
    assertThat(replay.action()).isEqualTo(GraphSyncOutcome.Action.APPLIED);
    assertThat(cooperatedEdgesOf(workId)).isEqualTo(1L);
    assertThat(edgeBetween(a, b).strength()).as("重复导入不得把强度从 2 翻成 4").isEqualTo(2);

    // 更旧的版本才是被版本守卫拒绝的那种：不产生任何副作用，不把新值改回旧值。
    update(
        "UPDATE qry_achievement SET aggregate_version = 9 WHERE generation = ? AND achievement_id = ?",
        GEN,
        workId);
    GraphSyncOutcome stale = synchronizer.syncWork(GEN, workId).orElseThrow();
    assertThat(stale.action()).isEqualTo(GraphSyncOutcome.Action.STALE_IGNORED);
    assertThat(edgeBetween(a, b).strength()).isEqualTo(2);

    // 重复回填：断点已 COMPLETED，直接返回，不重扫。
    GraphBackfillResult second = backfill.backfill(SCOPE);
    assertThat(second.status()).isEqualTo(GraphBackfillStatus.COMPLETED);
    assertThat(second.worksThisRun()).isZero();
    assertThat(
            countEdges(
                "MATCH ()-[c:COOPERATED_WITH]->() WHERE c.work_id STARTS WITH $p RETURN count(c) AS n",
                Map.of("p", PREFIX)))
        .isEqualTo(edgesAfterFirst);
    assertThat(
            countEdges(
                "MATCH (:Scholar)-[x:AUTHORED]->() WHERE x.work_id STARTS WITH $p RETURN count(x) AS n",
                Map.of("p", PREFIX)))
        .isEqualTo(authoredAfterFirst);
  }

  @Test
  @Order(24)
  @DisplayName("业务语义/撤销公开：PublicVisibilityTightened 触发退场，合作边彻底移除、查询不再可达")
  void tightenedVisibilityRetiresEdgesAndMakesPeersUnreachable() {
    String a = scholar("e1");
    String b = scholar("e2");
    String workId = "w-2401";
    work(
        workId,
        "即将撤销的论文",
        10L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(a, "AUTO"), author(b, "AUTO")));
    backfill.backfill(SCOPE);
    assertThat(cooperatedEdgesOf(PREFIX + "-" + workId)).isEqualTo(1L);

    update(
        "UPDATE qry_achievement SET visibility = 'RESTRICTED', aggregate_version = 20 "
            + "WHERE generation = ? AND achievement_id = ?",
        GEN,
        PREFIX + "-" + workId);
    appliedEvent(
        "evt-" + PREFIX + "-tighten",
        "PublicVisibilityTightened",
        PUBLISHED_REVISION + 10,
        "{\"tightenedAchievementIds\":[\"" + PREFIX + "-" + workId + "\"]}");

    GraphEventProjectionResult result = projector.applyPending(SCOPE, 50);

    assertThat(result.failedWorks()).isZero();
    assertThat(result.worksRetired()).isEqualTo(1);
    assertThat(result.watermarkAdvanced()).isTrue();
    assertThat(cooperatedEdgesOf(PREFIX + "-" + workId)).as("退场必须删净幽灵边").isZero();
    assertThat(isPublic(PREFIX + "-" + workId)).as("墓碑保留但不再公开").isFalse();
    assertThat(graph.shortestPath(a, b, GraphFilterCriteria.realOnly(), 2_000L).reachable())
        .isFalse();
    assertThat(graph.neighborhood(a, 1, 0, GraphFilterCriteria.realOnly()).seedInScope()).isFalse();
  }

  @Test
  @Order(25)
  @DisplayName("业务语义/证据可追溯：每条合作边都关联具体 work_id 与 work_title，并与 PG 标题逐条一致")
  void everyEdgeCarriesTraceableWorkEvidence() {
    String a = scholar("g1");
    String b = scholar("g2");
    String c = scholar("g3");
    Map<String, String> titles = new LinkedHashMap<>();
    titles.put(PREFIX + "-w-2501", "可追溯论文一");
    titles.put(PREFIX + "-w-2502", "可追溯论文二");
    titles.put(PREFIX + "-w-2503", "可追溯论文三");
    work(
        "w-2501",
        titles.get(PREFIX + "-w-2501"),
        10L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(a, "AUTO"), author(b, "AUTO")));
    work(
        "w-2502",
        titles.get(PREFIX + "-w-2502"),
        11L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(b, "AUTO"), author(c, "AUTO")));
    work(
        "w-2503",
        titles.get(PREFIX + "-w-2503"),
        12L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(a, "AUTO"), author(c, "AUTO")));

    backfill.backfill(SCOPE);

    List<Map<String, Object>> edges =
        runner.query(
            "MATCH (:Scholar)-[c:COOPERATED_WITH]->() WHERE c.work_id STARTS WITH $p "
                + "RETURN c.work_id AS workId, c.work_title AS title",
            Map.of("p", PREFIX),
            record ->
                Map.of(
                    "workId", record.get("workId").asString(),
                    "title", record.get("title").asString()));
    assertThat(edges).hasSize(3);
    for (Map<String, Object> edge : edges) {
      String workId = (String) edge.get("workId");
      assertThat(titles).containsKey(workId);
      assertThat(edge.get("title")).as("边证据标题必须与 PG 成果标题一致").isEqualTo(titles.get(workId));
    }

    // 二部图侧同样可追溯：每条 AUTHORED 都能对应到具体成果。
    assertThat(
            countEdges(
                "MATCH (:Scholar)-[x:AUTHORED]->(w:Work) WHERE x.work_id STARTS WITH $p "
                    + "AND w.work_id = x.work_id RETURN count(x) AS n",
                Map.of("p", PREFIX)))
        .isEqualTo(6L);
  }

  @Test
  @Order(26)
  @DisplayName("业务语义/REAL 与 SIMULATED 隔离：REAL 口径绝不混入模拟成果，ALL/SIMULATED 口径按需显式放宽")
  void realAndSimulatedDataAreIsolated() {
    String a = scholar("n1");
    String b = scholar("n2");
    String c = scholar("n3");
    work(
        "w-2601",
        "真实合作论文",
        10L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(a, "AUTO"), author(b, "AUTO")));
    work(
        "w-2602",
        "模拟合作论文",
        11L,
        "PUBLIC",
        "EFFECTIVE",
        authors(author(a, "AUTO"), author(c, "AUTO")));
    // 模拟数据在 PG 侧是同一张表的一行，只是 data_nature 不同：这正是「隔离」要验证的口径。
    update(
        "UPDATE qry_achievement SET data_nature = 'SIMULATED' WHERE generation = ? AND achievement_id = ?",
        GEN,
        PREFIX + "-w-2602");

    backfill.backfill(SCOPE);

    assertThat(graph.shortestPath(a, c, GraphFilterCriteria.realOnly(), 2_000L).reachable())
        .as("REAL 口径不得出现仅由模拟成果支撑的合作")
        .isFalse();
    assertThat(
            graph
                .shortestPath(a, c, new GraphFilterCriteria("ALL", null, null), 2_000L)
                .reachable())
        .as("ALL 口径必须显式包含模拟成果")
        .isTrue();
    assertThat(
            graph
                .shortestPath(a, c, new GraphFilterCriteria("SIMULATED", null, null), 2_000L)
                .reachable())
        .isTrue();
    assertThat(
            graph
                .shortestPath(a, b, new GraphFilterCriteria("SIMULATED", null, null), 2_000L)
                .reachable())
        .as("SIMULATED 口径不得混入真实成果")
        .isFalse();

    GraphCollaborationEdge simulated =
        edgeBetween(a, c, new GraphFilterCriteria("SIMULATED", null, null));
    assertThat(simulated.evidence())
        .singleElement()
        .satisfies(evidence -> assertThat(evidence.dataOrigin()).isEqualTo("SIMULATED"));
    assertThat(edgeBetween(a, b, GraphFilterCriteria.realOnly()).evidence())
        .singleElement()
        .satisfies(evidence -> assertThat(evidence.dataOrigin()).isEqualTo("REAL"));
  }

  @Test
  @Order(27)
  @DisplayName("业务语义/成果合并：AchievementMerged 删掉 PG 旧成果后，图侧 deleteWork 彻底清除旧节点与合作边，旧合作路径立即消失")
  void achievementMergedDeletesOldWorkNodeAndEdges() {
    String a = scholar("m1");
    String b = scholar("m2");
    String c = scholar("m3");
    // 幸存者：a-b；被合并者：b-c。合并后 b-c 那条合作不再存在，a→c 应当不可达。
    String survivor =
        work(
            "w-2701",
            "幸存成果",
            10L,
            "PUBLIC",
            "EFFECTIVE",
            authors(author(a, "AUTO"), author(b, "AUTO")));
    String merged =
        work(
            "w-2702",
            "被合并成果",
            11L,
            "PUBLIC",
            "EFFECTIVE",
            authors(author(b, "AUTO"), author(c, "AUTO")));

    backfill.backfill(SCOPE);
    assertThat(cooperatedEdgesOf(merged)).isEqualTo(1L);
    GraphPathResult before = graph.shortestPath(a, c, GraphFilterCriteria.realOnly(), 2_000L);
    assertThat(before.reachable()).as("合并前 a→b→c 应可达").isTrue();
    assertThat(before.path()).containsExactly(a, b, c);

    // core 的 AchievementMerged 已应用到 PG 投影：被合并者及其参与/合作行被删除。
    execute(
        "DELETE FROM qry_coauthor_edge WHERE generation = ? AND achievement_id = ?", GEN, merged);
    execute(
        "DELETE FROM qry_contribution WHERE generation = ? AND achievement_id = ?", GEN, merged);
    execute("DELETE FROM qry_achievement WHERE generation = ? AND achievement_id = ?", GEN, merged);

    appliedEvent(
        "evt-" + PREFIX + "-merge",
        "AchievementMerged",
        PUBLISHED_REVISION + 20,
        "{\"survivorId\":\"" + survivor + "\",\"mergedId\":\"" + merged + "\"}");

    GraphEventProjectionResult result = projector.applyPending(SCOPE, 50);

    assertThat(result.failedWorks()).isZero();
    assertThat(result.worksDeleted()).as("PG 里已不存在的成果必须用 deleteWork 而非逆场墓碑").isEqualTo(1);
    assertThat(result.watermarkAdvanced()).isTrue();
    assertThat(countEdges("MATCH (w:Work {work_id: $w}) RETURN count(w) AS n", Map.of("w", merged)))
        .as("旧成果节点必须彻底删除，不能留墓碑进网络")
        .isZero();
    assertThat(cooperatedEdgesOf(merged)).as("旧成果的合作边必须一并删除").isZero();
    assertThat(
            countEdges(
                "MATCH (:Scholar)-[x:AUTHORED]->() WHERE x.work_id = $w RETURN count(x) AS n",
                Map.of("w", merged)))
        .as("旧成果的参与边必须一并删除")
        .isZero();
    assertThat(
            countEdges("MATCH (w:Work {work_id: $w}) RETURN count(w) AS n", Map.of("w", survivor)))
        .as("幸存成果必须仍在图内")
        .isEqualTo(1L);
    assertThat(cooperatedEdgesOf(survivor)).isEqualTo(1L);

    // 端到端查询：旧合作路径立即消失（不是等下一轮人工对账才消失）。
    assertThat(graph.shortestPath(a, c, GraphFilterCriteria.realOnly(), 2_000L).reachable())
        .as("合并后 a 与 c 不再有合作路径")
        .isFalse();
    assertThat(graph.shortestPath(a, b, GraphFilterCriteria.realOnly(), 2_000L).hops())
        .as("幸存成果的边不受影响")
        .isEqualTo(1);
  }

  // ===========================================================================
  // 数据准备与清理
  // ===========================================================================

  /** 一条参与关系：学者标识 + 关系状态。 */
  private record Authoriship(String scholarId, String relationState) {}

  private static Authoriship author(String scholarId, String relationState) {
    return new Authoriship(scholarId, relationState);
  }

  private static List<Authoriship> authors(Authoriship... items) {
    return List.of(items);
  }

  private static List<Authoriship> authorsOf(String... scholarIds) {
    List<Authoriship> list = new ArrayList<>(scholarIds.length);
    for (String scholarId : scholarIds) {
      list.add(author(scholarId, "AUTO"));
    }
    return list;
  }

  private static String scholar(String suffix) {
    return PREFIX + "-" + suffix;
  }

  private static String work(
      String shortId,
      String title,
      long version,
      String visibility,
      String effectiveStatus,
      List<Authoriship> authors,
      List<String> topics,
      List<String> organizations) {
    String workId = PREFIX + "-" + shortId;
    insertAchievement(workId, title, version, visibility, effectiveStatus, "REAL");
    int order = 1;
    for (Authoriship authorship : authors) {
      insertContribution(
          workId,
          authorship.scholarId(),
          "学者 " + authorship.scholarId(),
          authorship.relationState(),
          order++);
      for (String organization : organizations) {
        insertOrganization(workId, authorship.scholarId(), organization);
      }
    }
    for (String topic : topics) {
      insertTopic(workId, topic);
    }
    return workId;
  }

  private static String work(
      String shortId,
      String title,
      long version,
      String visibility,
      String effectiveStatus,
      List<Authoriship> authors) {
    return work(
        shortId, title, version, visibility, effectiveStatus, authors, List.of(), List.of());
  }

  private static void insertAchievement(
      String workId,
      String title,
      long version,
      String visibility,
      String effectiveStatus,
      String nature) {
    execute(
        "INSERT INTO qry_achievement (generation, achievement_id, type, title, title_norm, data_nature,"
            + " visibility, effective_status, integrity_status, publication_year, aggregate_version, revision)"
            + " VALUES (?, ?, 'PAPER', ?, ?, ?, ?, ?, 'NONE', 2024, ?, ?)",
        GEN,
        workId,
        title,
        title.toLowerCase(java.util.Locale.ROOT),
        nature,
        visibility,
        effectiveStatus,
        version,
        version);
  }

  private static void insertContribution(
      String workId, String scholarId, String name, String relationState, int order) {
    execute(
        "INSERT INTO qry_contribution (generation, achievement_id, scholar_id, scholar_name, role,"
            + " author_order, relation_state) VALUES (?, ?, ?, ?, 'AUTHOR', ?, ?)"
            + " ON CONFLICT (generation, achievement_id, scholar_id) DO UPDATE SET"
            + " relation_state = EXCLUDED.relation_state, author_order = EXCLUDED.author_order",
        GEN,
        workId,
        scholarId,
        name,
        order,
        relationState);
  }

  private static void insertTopic(String workId, String topicId) {
    execute(
        "INSERT INTO qry_achievement_topic (generation, achievement_id, topic_id, canonical_name)"
            + " VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
        GEN,
        workId,
        topicId,
        "领域 " + topicId);
  }

  private static void insertOrganization(String workId, String scholarId, String organization) {
    execute(
        "INSERT INTO qry_contribution_org (generation, achievement_id, scholar_id, organization)"
            + " VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
        GEN,
        workId,
        scholarId,
        organization);
  }

  private static void seedConsumerState() {
    execute(
        "INSERT INTO qry_generation (generation, status, ready_at, note) VALUES (?, 'READY', now(), 'IT')"
            + " ON CONFLICT (generation) DO NOTHING",
        GEN);
    execute(
        "INSERT INTO qry_consumer_state (consumer_id, active_generation, received_revision, published_revision)"
            + " VALUES (?, ?, ?, ?) ON CONFLICT (consumer_id) DO UPDATE SET"
            + " active_generation = EXCLUDED.active_generation,"
            + " received_revision = EXCLUDED.received_revision,"
            + " published_revision = EXCLUDED.published_revision",
        CONSUMER,
        GEN,
        PUBLISHED_REVISION,
        PUBLISHED_REVISION);
  }

  private static void appliedEvent(
      String eventId, String eventType, long revision, String payload) {
    execute(
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

  private static GraphCollaborationEdge edgeBetween(String left, String right) {
    return edgeBetween(left, right, GraphFilterCriteria.realOnly());
  }

  private static GraphCollaborationEdge edgeBetween(
      String left, String right, GraphFilterCriteria criteria) {
    for (GraphCollaborationEdge edge : repository.expand(List.of(left), criteria, 100).edges()) {
      boolean matches =
          (edge.scholarA().equals(left) && edge.scholarB().equals(right))
              || (edge.scholarA().equals(right) && edge.scholarB().equals(left));
      if (matches) {
        return edge;
      }
    }
    throw new AssertionError("图中不存在合作边：" + left + " / " + right);
  }

  private static long cooperatedEdgesOf(String workId) {
    return countEdges(
        "MATCH ()-[c:COOPERATED_WITH]->() WHERE c.work_id = $w RETURN count(c) AS n",
        Map.of("w", workId));
  }

  private static boolean isPublic(String workId) {
    return runner
        .query(
            "MATCH (w:Work {work_id: $w}) RETURN w.is_public AS flag",
            Map.of("w", workId),
            record -> record.get("flag").asBoolean())
        .get(0);
  }

  private static long countEdges(String cypher, Map<String, Object> parameters) {
    return runner.query(cypher, parameters, record -> record.get("n").asLong()).get(0);
  }

  private static void execute(String sql, Object... parameters) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.execute();
    } catch (SQLException ex) {
      throw new IllegalStateException("IT 数据准备失败：" + sql, ex);
    }
  }

  private static void update(String sql, Object... parameters) {
    execute(sql, parameters);
  }

  /** 只清本测试前缀的图数据：不删除同一实例上其他环境/作用域的投影。 */
  private static void wipeGraph() {
    runner.write(
        tx -> {
          tx.run(
                  "MATCH (n) WHERE n.work_id STARTS WITH $p OR n.scholar_id STARTS WITH $p "
                      + "OR n.scope STARTS WITH $p DETACH DELETE n",
                  Map.of("p", PREFIX))
              .consume();
          return null;
        });
  }

  private static void cleanupPostgres() {
    try {
      execute("DELETE FROM qry_inbox_event WHERE consumer_id = ?", CONSUMER);
      execute("DELETE FROM qry_graph_projection_checkpoint WHERE scope = ?", SCOPE);
      execute("DELETE FROM qry_coauthor_edge WHERE generation = ?", GEN);
      execute("DELETE FROM qry_achievement_topic WHERE generation = ?", GEN);
      execute("DELETE FROM qry_contribution_org WHERE generation = ?", GEN);
      execute("DELETE FROM qry_contribution WHERE generation = ?", GEN);
      execute("DELETE FROM qry_achievement WHERE generation = ?", GEN);
      execute("DELETE FROM qry_consumer_state WHERE consumer_id = ?", CONSUMER);
      execute("DELETE FROM qry_generation WHERE generation = ?", GEN);
    } catch (RuntimeException ignored) {
      // 库可能尚未迁移（首次运行 setUp 阶段）：此时没有可清理的数据。
    }
  }

  private static void ensureDatabase() {
    String adminUrl = "jdbc:postgresql://" + PG_HOST + ":" + PG_PORT + "/postgres";
    try (Connection connection =
        java.sql.DriverManager.getConnection(adminUrl, "postgres", "postgres_it")) {
      try (PreparedStatement query =
          connection.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
        query.setString(1, DB_NAME);
        try (ResultSet result = query.executeQuery()) {
          if (result.next()) {
            return;
          }
        }
      }
      try (PreparedStatement create = connection.prepareStatement("CREATE DATABASE " + DB_NAME)) {
        create.execute();
      }
    } catch (SQLException ex) {
      Assumptions.abort(
          "集成测试 PostgreSQL 不可用（"
              + PG_HOST
              + ":"
              + PG_PORT
              + "）："
              + ex.getMessage()
              + "。先执行 bash ops/local/test-db.sh start。");
    }
  }

  private static void migrate() {
    Flyway.configure()
        .dataSource(DB_URL, "postgres", "postgres_it")
        .locations("classpath:db/migration")
        .baselineOnMigrate(false)
        .validateOnMigrate(true)
        .cleanDisabled(true)
        .load()
        .migrate();
  }

  private static HikariDataSource newPool() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(DB_URL);
    config.setUsername("postgres");
    config.setPassword("postgres_it");
    config.setMaximumPoolSize(4);
    config.setConnectionTimeout(3_000);
    config.setPoolName("it-graph-semantics");
    return new HikariDataSource(config);
  }

  private static SqlSessionFactory sqlSessionFactory(HikariDataSource dataSource) {
    Configuration configuration =
        new Configuration(new Environment("it", new JdbcTransactionFactory(), dataSource));
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setArgNameBasedConstructorAutoMapping(true);
    configuration.addMapper(GraphProjectionSourceMapper.class);
    configuration.addMapper(GraphProjectionCheckpointMapper.class);
    return new SqlSessionFactoryBuilder().build(configuration);
  }

  /** 真实 Neo4j 仓储 + 逐层人为延迟：用来证明时间预算真的生效（而不是靠造空图绕过）。 */
  private static final class SlowGraphRepository implements CollaborationGraphRepository {

    private final CollaborationGraphRepository delegate;
    private final long delayMillis;

    private SlowGraphRepository(CollaborationGraphRepository delegate, long delayMillis) {
      this.delegate = delegate;
      this.delayMillis = delayMillis;
    }

    @Override
    public Expansion expand(
        Collection<String> frontier, GraphFilterCriteria criteria, int maxEdges) {
      sleep(delayMillis);
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

    private static void sleep(long millis) {
      try {
        Thread.sleep(millis);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("延迟注入被中断", interrupted);
      }
    }
  }
}
