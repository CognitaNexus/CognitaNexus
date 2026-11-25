package com.cognitanexus.query.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cognitanexus.query.graph.application.CollaborationGraphRepository;
import com.cognitanexus.query.graph.application.CollaborationGraphService;
import com.cognitanexus.query.graph.application.GraphProjectionStore;
import com.cognitanexus.query.graph.config.Neo4jGraphConfiguration;
import com.cognitanexus.query.graph.config.Neo4jGraphProperties;
import com.cognitanexus.query.graph.domain.GraphAuthorship;
import com.cognitanexus.query.graph.domain.GraphCollaborationEdge;
import com.cognitanexus.query.graph.domain.GraphFilterCriteria;
import com.cognitanexus.query.graph.domain.GraphPathResult;
import com.cognitanexus.query.graph.domain.GraphProjectionMeta;
import com.cognitanexus.query.graph.domain.GraphScholarNode;
import com.cognitanexus.query.graph.domain.GraphWorkNode;
import com.cognitanexus.query.graph.infrastructure.GraphStoreUnavailableException;
import com.cognitanexus.query.graph.infrastructure.Neo4jCollaborationGraphRepository;
import com.cognitanexus.query.graph.infrastructure.Neo4jGraphProjectionStore;
import com.cognitanexus.query.graph.infrastructure.Neo4jGraphSchemaStore;
import com.cognitanexus.query.graph.infrastructure.Neo4jSessionRunner;
import com.cognitanexus.query.graph.schema.Neo4jSchemaInitializer;
import com.cognitanexus.query.graph.schema.Neo4jSchemaStatementSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.exceptions.ClientException;

/**
 * Neo4j schema 与图模型的真实集成验证。
 *
 * <p>本类连接本机隔离测试实例（默认 {@code bolt://127.0.0.1:17687}，可用
 * {@code -Dcn.neo4j.uri=...} 覆盖）。实例不可达时整类跳过并给出启动方式，<b>不把跳过报告成通过</b>。
 *
 * <p>验证对象是「Cypher 真的这么执行」，而不是「Java 拼接字符串正确」：
 *
 * <ul>
 *   <li>约束与索引创建幂等，唯一约束真实拒绝重复 scholar_id；
 *   <li>成果整体替换幂等、旧版本事件被版本守卫拒绝；
 *   <li>每条 {@code COOPERATED_WITH} 都带 work_id/title 证据，强度等于不同成果数；
 *   <li>超过作者数阈值的成果不物化作者对边，但二部图仍可查到合作并带回证据；
 *   <li>REAL / SIMULATED 隔离、领域筛选后强度重新聚合、成果下线下线合作边；
 *   <li>投影水位只前进。
 * </ul>
 *
 * <p>测试数据全部带本次运行的随机前缀，结束时按前缀清理，不删除实例上的其他数据。
 */
class Neo4jSchemaIT {

  private static final String URI =
      System.getProperty(
          "cn.neo4j.uri", System.getenv().getOrDefault("CN_NEO4J_URI", "bolt://127.0.0.1:17687"));
  private static final String USER =
      System.getProperty("cn.neo4j.user", System.getenv().getOrDefault("CN_NEO4J_USER", "neo4j"));
  private static final String PASSWORD =
      System.getProperty(
          "cn.neo4j.password",
          System.getenv().getOrDefault("CN_NEO4J_PASSWORD", "cn_test_neo4j_2026"));

  private static final String PREFIX = "it-" + UUID.randomUUID().toString().substring(0, 8);
  private static final String SCOPE = PREFIX + "-scope";

  private static Driver driver;
  private static Neo4jSessionRunner runner;
  private static Neo4jGraphSchemaStore schemaStore;
  private static CollaborationGraphRepository graphRepository;
  private static CollaborationGraphService graphService;
  private static GraphProjectionStore projectionStore;

  @BeforeAll
  static void setUp() {
    Neo4jGraphProperties properties =
        new Neo4jGraphProperties(
            true,
            URI,
            USER,
            PASSWORD,
            "neo4j",
            Duration.ofSeconds(5),
            Duration.ofSeconds(15),
            // 阈值取 3：便于用 4 位作者构造「超阈值」场景，同时保留真实的多作者组合逻辑。
            3,
            50,
            20_000,
            100_000,
            2_000L,
            SCOPE);
    try {
      driver = new Neo4jGraphConfiguration().neo4jDriver(properties);
      driver.verifyConnectivity();
    } catch (RuntimeException ex) {
      Assumptions.abort(
          "Neo4j 集成测试实例不可用（"
              + URI
              + "）："
              + ex.getClass().getSimpleName()
              + "。启动方式见 ops/compose/compose.test-infra.yaml 与 ops/verify/test-infra-smoke.sh。");
    }
    runner = new Neo4jSessionRunner(driver, properties);
    schemaStore = new Neo4jGraphSchemaStore(runner);
    Neo4jSchemaStatementSource source = new Neo4jSchemaStatementSource();
    // 连续初始化两次：验证启动幂等（生产每次重启都会执行一遍）。
    new Neo4jSchemaInitializer(schemaStore, source).initialize();
    new Neo4jSchemaInitializer(schemaStore, source).initialize();

    graphRepository = new Neo4jCollaborationGraphRepository(runner, properties);
    projectionStore = new Neo4jGraphProjectionStore(runner, properties);
    graphService =
        new CollaborationGraphService(
            graphRepository,
            properties.maxNodes(),
            properties.maxEdges(),
            properties.budgetMillis());
  }

  @AfterAll
  static void tearDown() {
    if (runner != null) {
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
    if (driver != null) {
      driver.close();
    }
  }

  @Test
  @DisplayName("schema 初始化幂等，三类唯一约束与关键索引真实存在")
  void schemaIsIdempotentAndConstraintsExist() {
    var objects = schemaStore.readSchemaObjects();

    assertThat(objects.constraints())
        .contains("scholar_id_unique", "work_id_unique", "graph_projection_meta_scope_unique");
    assertThat(objects.indexes())
        .contains("scholar_domain", "scholar_organization", "scholar_data_origin")
        .contains("work_public", "work_data_origin", "work_source_version")
        .contains("authored_work_id", "cooperated_pair_key", "cooperated_work_id");
  }

  @Test
  @DisplayName("唯一约束真实拒绝重复 scholar_id：重复节点会让同一位学者在网络里出现两次")
  void uniqueConstraintRejectsDuplicateScholarId() {
    String scholarId = PREFIX + "-dup";
    runner.write(
        tx -> {
          tx.run("CREATE (:Scholar {scholar_id: $id})", Map.of("id", scholarId)).consume();
          return null;
        });

    assertThatThrownBy(
            () ->
                runner.write(
                    tx -> {
                      tx.run("CREATE (:Scholar {scholar_id: $id})", Map.of("id", scholarId))
                          .consume();
                      return null;
                    }))
        .isInstanceOf(GraphStoreUnavailableException.class)
        .hasRootCauseInstanceOf(ClientException.class);

    assertThat(
            count(
                "MATCH (s:Scholar {scholar_id: $id}) RETURN count(s) AS n",
                Map.of("id", scholarId)))
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("写入 Scholar/Work/AUTHORED/COOPERATED_WITH 后可按种子扩边，且每条边带共同成果证据")
  void writesModelAndTraversesWithEvidence() {
    String s1 = PREFIX + "-s1";
    String s2 = PREFIX + "-s2";
    String s3 = PREFIX + "-s3";
    String w1 = PREFIX + "-w1";
    String w2 = PREFIX + "-w2";
    writeScholars(s1, s2, s3);
    replaceWork(w1, "REAL", 10L, List.of(topic("t1")), List.of("org-1"), authors(s1, s2));
    replaceWork(w2, "REAL", 11L, List.of(topic("t2")), List.of("org-1"), authors(s2, s3));

    CollaborationGraphRepository.Expansion expansion =
        graphRepository.expand(List.of(s1), GraphFilterCriteria.realOnly(), 100);
    assertThat(expansion.truncated()).isFalse();
    assertThat(expansion.edges()).hasSize(1);
    GraphCollaborationEdge edge = expansion.edges().get(0);
    assertThat(edge.scholarA()).isEqualTo(s1);
    assertThat(edge.scholarB()).isEqualTo(s2);
    assertThat(edge.strength()).isEqualTo(1);
    assertThat(edge.evidence()).hasSize(1);
    assertThat(edge.evidence().get(0).workId()).isEqualTo(w1);
    assertThat(edge.evidence().get(0).title()).isEqualTo("Title of " + w1);

    GraphPathResult path =
        graphService.shortestPath(s1, s3, GraphFilterCriteria.realOnly(), 2_000L);
    assertThat(path.reachable()).isTrue();
    assertThat(path.hops()).isEqualTo(2);
    assertThat(path.path()).containsExactly(s1, s2, s3);
    assertThat(path.hopEdges()).hasSize(2);
    assertThat(path.hopEdges().get(0).evidence().get(0).workId()).isEqualTo(w1);
    assertThat(path.hopEdges().get(1).evidence().get(0).workId()).isEqualTo(w2);

    // 学者节点属性应可读出，供界面显示姓名。
    assertThat(graphRepository.findScholars(List.of(s1)))
        .singleElement()
        .extracting(GraphScholarNode::scholarName)
        .isEqualTo("Name " + s1);
  }

  @Test
  @DisplayName("REAL 查询看不到 SIMULATED 合作；ALL 才显式放宽")
  void dataOriginIsolation() {
    String simulatedA = PREFIX + "-sim-a";
    String simulatedB = PREFIX + "-sim-b";
    String work = PREFIX + "-w-sim";
    writeScholars(simulatedA, simulatedB);
    replaceWork(work, "SIMULATED", 1L, List.of(), List.of(), authors(simulatedA, simulatedB));

    assertThat(
            graphService
                .neighborhood(simulatedA, 1, 0, GraphFilterCriteria.realOnly())
                .seedInScope())
        .isFalse();
    assertThat(graphService.neighborhood(simulatedA, 1, 0, GraphFilterCriteria.all()).nodes())
        .extracting(GraphScholarNode::scholarId)
        .containsExactlyInAnyOrder(simulatedA, simulatedB);
  }

  @Test
  @DisplayName("高作者数成果不物化作者对边，但二部图仍可查到合作并带回证据")
  void highAuthorWorkIsNotMaterializedButStillTraversable() {
    List<String> scholars = new ArrayList<>();
    for (int i = 1; i <= 4; i++) {
      scholars.add(PREFIX + "-high-" + i);
    }
    writeScholars(scholars.toArray(String[]::new));
    String work = PREFIX + "-w-high";
    // 阈值 3，作者 4：超过阈值 → 不建 COOPERATED_WITH，但仍写 AUTHORED。
    replaceWork(work, "REAL", 1L, List.of(), List.of(), authors(scholars.toArray(String[]::new)));

    assertThat(
            count(
                "MATCH ()-[c:COOPERATED_WITH]->() WHERE c.work_id = $id RETURN count(c) AS n",
                Map.of("id", work)))
        .isZero();
    assertThat(
            count(
                "MATCH (:Scholar)-[a:AUTHORED]->(w:Work {work_id: $id}) RETURN count(a) AS n",
                Map.of("id", work)))
        .isEqualTo(4L);

    CollaborationGraphRepository.Expansion expansion =
        graphRepository.expand(List.of(scholars.get(0)), GraphFilterCriteria.realOnly(), 100);
    assertThat(expansion.edges()).hasSize(3);
    assertThat(expansion.edges())
        .allSatisfy(
            edge -> {
              assertThat(edge.strength()).isEqualTo(1);
              assertThat(edge.evidence())
                  .singleElement()
                  .satisfies(
                      evidence -> {
                        assertThat(evidence.workId()).isEqualTo(work);
                        assertThat(evidence.title()).isNotBlank();
                      });
            });

    GraphPathResult path =
        graphService.shortestPath(
            scholars.get(0), scholars.get(3), GraphFilterCriteria.realOnly(), 2_000L);
    assertThat(path.reachable()).isTrue();
    assertThat(path.hops()).isEqualTo(1);
    assertThat(path.hopEdges())
        .singleElement()
        .satisfies(edge -> assertThat(edge.evidence().get(0).workId()).isEqualTo(work));
  }

  @Test
  @DisplayName("成果整体替换幂等；旧版本事件被版本守卫拒绝且不改变已有内容")
  void replaceIsIdempotentAndVersionGuarded() {
    String a = PREFIX + "-idem-a";
    String b = PREFIX + "-idem-b";
    String work = PREFIX + "-w-idem";
    writeScholars(a, b);

    assertThat(replaceWork(work, "REAL", 5L, List.of(), List.of(), authors(a, b))).isTrue();
    // 每条关系代表「一篇成果的一个无序作者对」，因此 2 位作者对应 1 条边；镜像方向由查询聚合。
    assertThat(cooperatedEdgesOf(work)).isEqualTo(1L);

    // 同一版本重放：结果与一次完全相同，不累加强度。
    assertThat(replaceWork(work, "REAL", 5L, List.of(), List.of(), authors(a, b))).isTrue();
    assertThat(cooperatedEdgesOf(work)).isEqualTo(1L);

    // 旧版本事件：被守卫拒绝，标题与作者集合保持新值。
    assertThat(replaceWork(work, "REAL", 4L, List.of(), List.of(), authors(a))).isFalse();
    assertThat(cooperatedEdgesOf(work)).isEqualTo(1L);
    assertThat(
            count(
                "MATCH (:Scholar)-[a:AUTHORED]->(:Work {work_id: $id}) RETURN count(a) AS n",
                Map.of("id", work)))
        .isEqualTo(2L);
  }

  @Test
  @DisplayName("领域/机构筛选后边强度按命中成果重新聚合，不复用未筛选的物化强度")
  void filteredQueryRecomputesStrength() {
    String a = PREFIX + "-filter-a";
    String b = PREFIX + "-filter-b";
    String topic1Work = PREFIX + "-w-topic1";
    String topic2Work = PREFIX + "-w-topic2";
    writeScholars(a, b);
    replaceWork(topic1Work, "REAL", 1L, List.of(topic("topic-1")), List.of("org-1"), authors(a, b));
    replaceWork(topic2Work, "REAL", 2L, List.of(topic("topic-2")), List.of("org-1"), authors(a, b));

    CollaborationGraphRepository.Expansion unfiltered =
        graphRepository.expand(List.of(a), GraphFilterCriteria.realOnly(), 100);
    assertThat(unfiltered.edges())
        .singleElement()
        .satisfies(edge -> assertThat(edge.strength()).isEqualTo(2));

    CollaborationGraphRepository.Expansion filtered =
        graphRepository.expand(
            List.of(a), GraphFilterCriteria.realOnly().withTopic(topic("topic-1")), 100);
    assertThat(filtered.edges())
        .singleElement()
        .satisfies(
            edge -> {
              assertThat(edge.strength()).isEqualTo(1);
              assertThat(edge.evidence())
                  .extracting(evidence -> evidence.workId())
                  .containsExactly(topic1Work);
            });
  }

  @Test
  @DisplayName("成果下线保留墓碑但删除合作边，图查询不再返回该成果带来的合作者")
  void retireRemovesCollaborationEdges() {
    String a = PREFIX + "-retire-a";
    String b = PREFIX + "-retire-b";
    String work = PREFIX + "-w-retire";
    writeScholars(a, b);
    replaceWork(work, "REAL", 1L, List.of(), List.of(), authors(a, b));

    assertThat(projectionStore.retireWork(work, 2L)).isTrue();

    assertThat(
            count(
                "MATCH ()-[c:COOPERATED_WITH]->() WHERE c.work_id = $id RETURN count(c) AS n",
                Map.of("id", work)))
        .isZero();
    assertThat(
            count(
                "MATCH (w:Work {work_id: $id}) WHERE w.is_public = false RETURN count(w) AS n",
                Map.of("id", work)))
        .isEqualTo(1L);
    assertThat(graphRepository.expand(List.of(a), GraphFilterCriteria.realOnly(), 100).edges())
        .isEmpty();
    assertThat(graphService.neighborhood(a, 1, 0, GraphFilterCriteria.realOnly()).seedInScope())
        .isFalse();

    // 旧版本下线事件同样被守卫拒绝，避免迟到事件把已恢复的成果再次下线。
    assertThat(projectionStore.retireWork(work, 0L)).isFalse();
  }

  @Test
  @DisplayName("投影水位只前进，重放旧版本不回退")
  void projectionMetaAdvancesOnlyForward() {
    assertThat(projectionStore.readMeta(SCOPE)).isEmpty();

    long first =
        projectionStore.advanceMeta(
            new GraphProjectionMeta(SCOPE, 120L, 130L, "gen0002", Instant.now()));
    assertThat(first).isEqualTo(120L);

    long stale =
        projectionStore.advanceMeta(
            new GraphProjectionMeta(SCOPE, 100L, 100L, "gen0001", Instant.now()));
    assertThat(stale).isEqualTo(120L);

    GraphProjectionMeta meta = projectionStore.readMeta(SCOPE).orElseThrow();
    assertThat(meta.projectedVersion()).isEqualTo(120L);
    assertThat(meta.catalogRevision()).isEqualTo(130L);
    assertThat(meta.generation()).isEqualTo("gen0002");
    assertThat(meta.behind(120L)).isFalse();
    assertThat(meta.behind(121L)).isTrue();
  }

  private static boolean replaceWork(
      String workId,
      String dataOrigin,
      long sourceVersion,
      List<String> topicIds,
      List<String> organizations,
      List<GraphAuthorship> authors) {
    GraphWorkNode work =
        new GraphWorkNode(
            workId, "Title of " + workId, true, dataOrigin, sourceVersion, topicIds, organizations);
    return projectionStore.replaceWork(work, authors);
  }

  private static void writeScholars(String... scholarIds) {
    List<GraphScholarNode> nodes = new ArrayList<>();
    for (String id : scholarIds) {
      nodes.add(new GraphScholarNode(id, "Name " + id, "domain-1", "org-1", "REAL", 1L));
    }
    assertThat(projectionStore.upsertScholars(nodes)).isEqualTo(scholarIds.length);
  }

  private static List<GraphAuthorship> authors(String... scholarIds) {
    List<GraphAuthorship> authorships = new ArrayList<>();
    for (int i = 0; i < scholarIds.length; i++) {
      authorships.add(new GraphAuthorship(scholarIds[i], "AUTO", i + 1));
    }
    return authorships;
  }

  private static String topic(String topicId) {
    return PREFIX + "-" + topicId;
  }

  private static long cooperatedEdgesOf(String workId) {
    return count(
        "MATCH ()-[c:COOPERATED_WITH]->() WHERE c.work_id = $id RETURN count(c) AS n",
        Map.of("id", workId));
  }

  private static long count(String cypher, Map<String, Object> parameters) {
    return runner.query(cypher, parameters, record -> record.get("n").asLong()).get(0);
  }
}
