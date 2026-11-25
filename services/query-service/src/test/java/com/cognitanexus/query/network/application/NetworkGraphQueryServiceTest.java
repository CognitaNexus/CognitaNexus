package com.cognitanexus.query.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cognitanexus.query.graph.application.CollaborationGraphService;
import com.cognitanexus.query.graph.application.GraphProjectionStore;
import com.cognitanexus.query.graph.domain.GraphFilterCriteria;
import com.cognitanexus.query.graph.domain.GraphProjectionMeta;
import com.cognitanexus.query.graph.schema.Neo4jSchemaInitializer;
import com.cognitanexus.query.network.domain.NetworkRules;
import com.cognitanexus.query.network.infrastructure.NetworkMapper;
import com.cognitanexus.query.network.infrastructure.NetworkMapper.EdgeRow;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 图库版响应装配器：HTTP 契约与 PG 实现逐字段对齐。
 *
 * <p>这些用例关注的不是 Cypher（真实图库行为由集成测试覆盖），而是「图查询结果能否被装进现有
 * 响应契约」：字段名、字段集合、空值形态、节点顺序、边顺序、说明文案、深度与规模上限。
 * 任何一项偏差都会让前端在切换模式后出现「同一个页面两种形状」，而且不会抛异常。
 */
class NetworkGraphQueryServiceTest {

  private static final GraphFilterCriteria ALL = GraphFilterCriteria.all();

  @Test
  @DisplayName("自我网络：字段集合与 PG 完全一致，节点按 BFS 序、边按两端 ID 排序")
  void neighborhoodShapeMatchesPostgres() {
    List<EdgeRow> edges = List.of(edge("a", "c", 1), edge("a", "b", 2), edge("b", "d", 1));
    NetworkMapper mapper = NetworkTestFixtures.mapper(edges);
    NetworkService postgres = NetworkTestFixtures.postgres(mapper);
    NetworkGraphQueryService graph = NetworkTestFixtures.graphQueryService(edges, mapper, true);

    Map<String, Object> pgView = postgres.neighborhood("a", 2, 0, "ALL", null, null);
    Map<String, Object> graphView =
        graph.neighborhood(NetworkTestFixtures.barrier(), "a", 2, 0, ALL);

    assertThat(new TreeSet<>(graphView.keySet())).isEqualTo(new TreeSet<>(pgView.keySet()));
    assertThat(graphView.get("seedInScope")).isEqualTo(true);
    assertThat(graphView.get("depth")).isEqualTo(2);
    assertThat(graphView.get("graphVersion")).isEqualTo("g42-r120");
    assertThat(graphView.get("ruleVersion")).isEqualTo(NetworkRules.RULE_VERSION);
    assertThat(graphView.get("dataNatureScope")).isEqualTo("ALL");
    assertThat(graphView.get("scope")).isEqualTo(NetworkRules.neighborhoodScope(2));
    assertThat(graphView.get("limits"))
        .isEqualTo(Map.of("maxNodes", 20_000, "maxEdges", 100_000, "budgetMillis", 1_000L));
    assertThat(NetworkTestFixtures.nodes(graphView))
        .extracting(node -> node.get("scholarId"))
        .containsExactly("a", "b", "c", "d");
    assertThat(NetworkTestFixtures.nodes(graphView))
        .allSatisfy(
            node -> assertThat(node).containsKeys("scholarName", "qualifiedAchievementCount"));
    assertThat(NetworkTestFixtures.edges(graphView))
        .extracting(
            edge -> edge.get("source") + "-" + edge.get("target") + ":" + edge.get("strength"))
        .containsExactly("a-b:2", "a-c:1", "b-d:1");
  }

  @Test
  @DisplayName("种子没有合格边：返回空图与原因说明，不伪造孤立节点")
  void seedWithoutEdgesMatchesPostgres() {
    List<EdgeRow> edges = List.of(edge("b", "c", 1));
    NetworkMapper mapper = NetworkTestFixtures.mapper(edges);
    NetworkService postgres = NetworkTestFixtures.postgres(mapper);
    NetworkGraphQueryService graph = NetworkTestFixtures.graphQueryService(edges, mapper, true);

    Map<String, Object> pgView = postgres.neighborhood("a", 2, 0, "ALL", null, null);
    Map<String, Object> graphView =
        graph.neighborhood(NetworkTestFixtures.barrier(), "a", 2, 0, ALL);

    assertThat(new TreeSet<>(graphView.keySet())).isEqualTo(new TreeSet<>(pgView.keySet()));
    assertThat(graphView.get("seedInScope")).isEqualTo(false);
    assertThat(graphView.get("note")).isEqualTo(NetworkRules.NOTE_SEED_OUT_OF_SCOPE);
    assertThat(NetworkTestFixtures.nodes(graphView)).isEmpty();
    assertThat(graphView).doesNotContainKey("depth").doesNotContainKey("scope");
  }

  @Test
  @DisplayName("深度按 NetworkRules 上限截断，请求上限不能放宽服务端上限")
  void depthIsClampedBySharedRules() {
    List<EdgeRow> edges = List.of(edge("a", "b", 1), edge("b", "c", 1), edge("c", "d", 1));
    NetworkMapper mapper = NetworkTestFixtures.mapper(edges);
    NetworkGraphQueryService graph = NetworkTestFixtures.graphQueryService(edges, mapper, true);

    Map<String, Object> view = graph.neighborhood(NetworkTestFixtures.barrier(), "a", 99, 0, ALL);

    assertThat(view.get("depth")).isEqualTo(NetworkRules.MAX_NEIGHBORHOOD_DEPTH);
    assertThat(NetworkTestFixtures.nodes(view)).hasSize(4);
  }

  @Test
  @DisplayName("节点超限：明确失败而不是返回半个子图")
  void nodeLimitFailsExplicitly() {
    List<EdgeRow> edges = List.of(edge("a", "b", 1), edge("a", "c", 1), edge("a", "d", 1));
    Neo4jSchemaInitializer initializer = mock(Neo4jSchemaInitializer.class);
    when(initializer.isReady()).thenReturn(true);
    GraphProjectionStore store = mock(GraphProjectionStore.class);
    when(store.readMeta("query-public"))
        .thenReturn(
            Optional.of(new GraphProjectionMeta("query-public", 120L, 120L, "42", Instant.now())));
    CollaborationGraphService tiny = NetworkTestFixtures.graphService(edges, 2, 100_000, 1_000L);
    NetworkGraphQueryService service =
        new NetworkGraphQueryService(
            tiny,
            NetworkTestFixtures.mapper(edges),
            NetworkTestFixtures.projectionProperties(),
            NetworkTestFixtures.graphProperties(),
            new ProjectionWatermarkGuard(
                store,
                initializer,
                NetworkTestFixtures.graphProperties(),
                NetworkTestFixtures.initializationProbe()));

    assertThatThrownBy(() -> service.neighborhood(NetworkTestFixtures.barrier(), "a", 1, 0, ALL))
        .isInstanceOf(BusinessException.class)
        .extracting(exception -> ((BusinessException) exception).errorCode())
        .isEqualTo(ErrorCode.GRAPH_SCOPE_TOO_LARGE);
  }

  @Test
  @DisplayName("最短路径：字段集合与 PG 一致，逐跳证据不进入响应，说明文案来自共享常量")
  void pathShapeMatchesPostgres() {
    List<EdgeRow> edges =
        List.of(edge("s", "b", 1), edge("b", "t", 2), edge("s", "c", 1), edge("c", "t", 1));
    NetworkMapper mapper = NetworkTestFixtures.mapper(edges);
    NetworkService postgres = NetworkTestFixtures.postgres(mapper);
    NetworkGraphQueryService graph = NetworkTestFixtures.graphQueryService(edges, mapper, true);

    Map<String, Object> pgView = postgres.shortestPath("s", "t", "ALL", null, null);
    Map<String, Object> graphView =
        graph.shortestPath(NetworkTestFixtures.barrier(), "s", "t", ALL);

    assertThat(new TreeSet<>(graphView.keySet())).isEqualTo(new TreeSet<>(pgView.keySet()));
    // 等长路径取字典序最小：s-b-t 而非 s-c-t。
    assertThat(graphView.get("path")).isEqualTo(List.of("s", "b", "t"));
    assertThat(graphView.get("hops")).isEqualTo(2);
    assertThat(graphView.get("reachable")).isEqualTo(true);
    assertThat(graphView.get("pathRule")).isEqualTo(NetworkRules.PATH_RULE);
    assertThat(graphView.get("scope")).isEqualTo(NetworkRules.pathScope(2));
    assertThat(graphView).doesNotContainKey("note");
    assertThat(NetworkTestFixtures.pathNodes(graphView))
        .extracting(node -> node.get("scholarId"))
        .containsExactly("s", "b", "t");
  }

  @Test
  @DisplayName("回归：最短路径的 withinLimits 报告搜索子图规模，与 PG 逐字段一致（不是路径节点数/跳数）")
  void pathWithinLimitsMatchesPostgres() {
    List<EdgeRow> edges =
        List.of(edge("s", "b", 1), edge("b", "t", 2), edge("s", "c", 1), edge("c", "t", 1));
    NetworkMapper mapper = NetworkTestFixtures.mapper(edges);
    NetworkService postgres = NetworkTestFixtures.postgres(mapper);
    NetworkGraphQueryService graph = NetworkTestFixtures.graphQueryService(edges, mapper, true);

    // 可达：双侧各载入了一部分子图，节点取两侧较大值、边取两侧之和——与 ScopedGraphLoader 同口径。
    Map<String, Object> pgView = postgres.shortestPath("s", "t", "ALL", null, null);
    Map<String, Object> graphView =
        graph.shortestPath(NetworkTestFixtures.barrier(), "s", "t", ALL);
    assertThat(graphView.get("withinLimits")).isEqualTo(pgView.get("withinLimits"));
    assertThat(pgView.get("withinLimits")).isEqualTo(Map.of("nodes", 4, "edges", 6));

    // 0 跳：规模是种子那一步实际载入的邻居，不是「1 个节点、0 条边」。
    Map<String, Object> pgZero = postgres.shortestPath("s", "s", "ALL", null, null);
    Map<String, Object> graphZero =
        graph.shortestPath(NetworkTestFixtures.barrier(), "s", "s", ALL);
    assertThat(graphZero.get("withinLimits")).isEqualTo(pgZero.get("withinLimits"));

    // 不可达：搜索规模同样要如实报告。
    Map<String, Object> pgMissing = postgres.shortestPath("s", "missing", "ALL", null, null);
    Map<String, Object> graphMissing =
        graph.shortestPath(NetworkTestFixtures.barrier(), "s", "missing", ALL);
    assertThat(graphMissing.get("withinLimits")).isEqualTo(pgMissing.get("withinLimits"));
  }

  @Test
  @DisplayName("回归：自我网络的 withinLimits 与 PG 同口径（实际载入的节点/去重边）")
  void neighborhoodWithinLimitsMatchesPostgres() {
    List<EdgeRow> edges = List.of(edge("a", "c", 1), edge("a", "b", 2), edge("b", "d", 1));
    NetworkMapper mapper = NetworkTestFixtures.mapper(edges);
    NetworkService postgres = NetworkTestFixtures.postgres(mapper);
    NetworkGraphQueryService graph = NetworkTestFixtures.graphQueryService(edges, mapper, true);

    for (int depth = 1; depth <= 3; depth++) {
      Map<String, Object> pgView = postgres.neighborhood("a", depth, 0, "ALL", null, null);
      Map<String, Object> graphView =
          graph.neighborhood(NetworkTestFixtures.barrier(), "a", depth, 0, ALL);
      assertThat(graphView.get("withinLimits"))
          .as("depth=" + depth)
          .isEqualTo(pgView.get("withinLimits"));
    }
  }

  @Test
  @DisplayName("不可达：说明「N 跳内未找到」，不带 pathNodes/pathRule/scope，与 PG 一致")
  void unreachableShapeMatchesPostgres() {
    List<EdgeRow> edges = List.of(edge("a", "b", 1), edge("b", "c", 1));
    NetworkMapper mapper = NetworkTestFixtures.mapper(edges);
    NetworkService postgres = NetworkTestFixtures.postgres(mapper);
    NetworkGraphQueryService graph = NetworkTestFixtures.graphQueryService(edges, mapper, true);

    Map<String, Object> pgView = postgres.shortestPath("a", "missing", "ALL", null, null);
    Map<String, Object> graphView =
        graph.shortestPath(NetworkTestFixtures.barrier(), "a", "missing", ALL);

    assertThat(new TreeSet<>(graphView.keySet())).isEqualTo(new TreeSet<>(pgView.keySet()));
    assertThat(graphView.get("reachable")).isEqualTo(false);
    assertThat(graphView.get("hops")).isNull();
    assertThat(graphView.get("note"))
        .isEqualTo(NetworkRules.noteUnreachable(NetworkRules.MAX_PATH_DEPTH));
    assertThat(graphView).doesNotContainKey("pathNodes").doesNotContainKey("pathRule");
  }

  @Test
  @DisplayName("起终点相同且在范围内：0 跳，形状与 PG 一致（含 note 与 0 跳规则）")
  void zeroHopInScopeMatchesPostgres() {
    List<EdgeRow> edges = List.of(edge("a", "b", 1));
    NetworkMapper mapper = NetworkTestFixtures.mapper(edges);
    NetworkService postgres = NetworkTestFixtures.postgres(mapper);
    NetworkGraphQueryService graph = NetworkTestFixtures.graphQueryService(edges, mapper, true);

    Map<String, Object> pgView = postgres.shortestPath("a", "a", "ALL", null, null);
    Map<String, Object> graphView =
        graph.shortestPath(NetworkTestFixtures.barrier(), "a", "a", ALL);

    assertThat(new TreeSet<>(graphView.keySet())).isEqualTo(new TreeSet<>(pgView.keySet()));
    assertThat(graphView.get("reachable")).isEqualTo(true);
    assertThat(graphView.get("hops")).isEqualTo(0);
    assertThat(graphView.get("path")).isEqualTo(List.of("a"));
    assertThat(graphView.get("note")).isEqualTo(NetworkRules.NOTE_ZERO_HOP_REACHABLE);
    assertThat(graphView.get("pathRule")).isEqualTo(NetworkRules.PATH_RULE_ZERO_HOP);
  }

  @Test
  @DisplayName("起终点相同但不在范围内：不伪造 0 跳可达")
  void zeroHopOutOfScopeMatchesPostgres() {
    List<EdgeRow> edges = List.of(edge("a", "b", 1));
    NetworkMapper mapper = NetworkTestFixtures.mapper(edges);
    NetworkService postgres = NetworkTestFixtures.postgres(mapper);
    NetworkGraphQueryService graph = NetworkTestFixtures.graphQueryService(edges, mapper, true);

    Map<String, Object> pgView = postgres.shortestPath("gone", "gone", "ALL", null, null);
    Map<String, Object> graphView =
        graph.shortestPath(NetworkTestFixtures.barrier(), "gone", "gone", ALL);

    assertThat(new TreeSet<>(graphView.keySet())).isEqualTo(new TreeSet<>(pgView.keySet()));
    assertThat(graphView.get("reachable")).isEqualTo(false);
    assertThat(graphView.get("note")).isEqualTo(NetworkRules.NOTE_ZERO_HOP_OUT_OF_SCOPE);
    assertThat(NetworkTestFixtures.pathNodes(graphView)).isEmpty();
  }

  @Test
  @DisplayName("筛选条件回显生效值：topic/organization 原样、数据性质归一为标签")
  void filtersAreEchoedAsEffectiveValues() {
    NetworkMapper mapper = NetworkTestFixtures.mapper(List.of());
    NetworkGraphQueryService graph = NetworkTestFixtures.graphQueryService(List.of(), mapper, true);
    GraphFilterCriteria criteria = new GraphFilterCriteria("ALL", " t1 ", " org-1 ");

    Map<String, Object> view =
        graph.neighborhood(NetworkTestFixtures.barrier(), "a", 1, 0, criteria);

    assertThat(view.get("dataNatureScope")).isEqualTo("ALL");
    assertThat(view.get("topicId")).isEqualTo("t1");
    assertThat(view.get("organization")).isEqualTo("org-1");
  }

  @Test
  @DisplayName("可用性判定委托水位保护：水位一致时可用，落后时不可用")
  void statusDelegatesToWatermarkGuard() {
    GraphProjectionStore store = mock(GraphProjectionStore.class);
    Neo4jSchemaInitializer initializer = mock(Neo4jSchemaInitializer.class);
    when(initializer.isReady()).thenReturn(true);
    when(store.readMeta("query-public"))
        .thenReturn(
            Optional.of(new GraphProjectionMeta("query-public", 120L, 120L, "42", Instant.now())));
    NetworkGraphQueryService fresh =
        new NetworkGraphQueryService(
            NetworkTestFixtures.graphService(List.of()),
            NetworkTestFixtures.mapper(List.of()),
            NetworkTestFixtures.projectionProperties(),
            NetworkTestFixtures.graphProperties(),
            new ProjectionWatermarkGuard(
                store,
                initializer,
                NetworkTestFixtures.graphProperties(),
                NetworkTestFixtures.initializationProbe()));
    assertThat(fresh.status(120L, "42").graphUsable()).isTrue();

    when(store.readMeta("query-public"))
        .thenReturn(
            Optional.of(new GraphProjectionMeta("query-public", 10L, 10L, "42", Instant.now())));
    assertThat(fresh.status(120L, "42").graphUsable()).isFalse();
  }

  @Test
  @DisplayName("图规模配置与 PG 不一致只告警不拒绝装配：改配置需要重启，不能把服务拖垮")
  void limitMismatchDoesNotPreventConstruction() {
    NetworkGraphQueryService service =
        new NetworkGraphQueryService(
            NetworkTestFixtures.graphService(List.of()),
            NetworkTestFixtures.mapper(List.of()),
            NetworkTestFixtures.projectionProperties(),
            NetworkTestFixtures.graphProperties(10, 20, 30L),
            NetworkTestFixtures.guard(true));

    assertThat(service).isNotNull();
  }

  private static EdgeRow edge(String a, String b, int strength) {
    return NetworkTestFixtures.edge(a, b, strength);
  }
}
