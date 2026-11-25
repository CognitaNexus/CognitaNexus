package com.cognitanexus.query.network.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cognitanexus.query.consistency.PublicBarrierGuard;
import com.cognitanexus.query.graph.application.CollaborationGraphRepository;
import com.cognitanexus.query.graph.application.CollaborationGraphService;
import com.cognitanexus.query.graph.application.GraphProjectionInitializationProbe;
import com.cognitanexus.query.graph.application.GraphProjectionStore;
import com.cognitanexus.query.graph.config.Neo4jGraphProperties;
import com.cognitanexus.query.graph.domain.GraphCollaborationEdge;
import com.cognitanexus.query.graph.domain.GraphFilterCriteria;
import com.cognitanexus.query.graph.domain.GraphScholarNode;
import com.cognitanexus.query.graph.domain.GraphWorkEvidence;
import com.cognitanexus.query.graph.schema.Neo4jSchemaInitializer;
import com.cognitanexus.query.network.infrastructure.NetworkMapper;
import com.cognitanexus.query.network.infrastructure.NetworkMapper.EdgeRow;
import com.cognitanexus.query.projection.application.ProjectionProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 合作网络单元测试的共用假件。
 *
 * <p>目的：让「同一份边集合分别喂给 PG 实现与图实现」成为三行代码的事。若每个测试各写一份 Mapper
 * 桩，PG 侧与图侧看到的边集合就可能不同，一致性断言会因此变成自证。这里的假件只模拟存储行为，
 * 不复制任何业务规则：BFS、深度、等长路径选择都由被测类自己执行。
 */
final class NetworkTestFixtures {

  private NetworkTestFixtures() {}

  static ProjectionProperties projectionProperties() {
    return projectionProperties(20_000, 100_000, 1_000L);
  }

  static ProjectionProperties projectionProperties(int maxNodes, int maxEdges, long budget) {
    return new ProjectionProperties(
        "query", 500, 5, 200, false, 2_000L, maxNodes, maxEdges, budget, 10_000, 20, 100);
  }

  static Neo4jGraphProperties graphProperties() {
    return graphProperties(20_000, 100_000, 1_000L);
  }

  static Neo4jGraphProperties graphProperties(int maxNodes, int maxEdges, long budget) {
    return new Neo4jGraphProperties(
        true,
        "bolt://127.0.0.1:7687",
        "neo4j",
        "secret",
        "neo4j",
        Duration.ofSeconds(3),
        Duration.ofSeconds(10),
        20,
        50,
        maxNodes,
        maxEdges,
        budget,
        "query-public",
        false);
  }

  static PublicBarrierGuard.BarrierSnapshot barrier() {
    return barrier(120L, "42");
  }

  static PublicBarrierGuard.BarrierSnapshot barrier(long publishedRevision, String generation) {
    return new PublicBarrierGuard.BarrierSnapshot(
        publishedRevision, publishedRevision, publishedRevision, publishedRevision, generation);
  }

  static EdgeRow edge(String a, String b, int strength) {
    return new EdgeRow(a, b, strength);
  }

  /** PG 侧 Mapper 桩：按 frontier 过滤边，行为与真实 SQL 的「两端各查一支再合并」等价。 */
  static NetworkMapper mapper(List<EdgeRow> edges) {
    NetworkMapper mapper = mock(NetworkMapper.class);
    when(mapper.listScholarNames(anyString(), anyList()))
        .thenAnswer(
            invocation -> {
              Collection<String> ids = invocation.getArgument(1);
              List<NetworkMapper.ScholarNameRow> rows = new ArrayList<>();
              for (String id : ids) {
                rows.add(new NetworkMapper.ScholarNameRow(id, "Name-" + id, 1L));
              }
              return rows;
            });
    when(mapper.listEdgesWhereScholarA(anyString(), any(), any(), any(), anyList(), anyInt()))
        .thenAnswer(
            invocation ->
                select(edges, invocation.getArgument(4), invocation.getArgument(5), true));
    when(mapper.listEdgesWhereScholarB(anyString(), any(), any(), any(), anyList(), anyInt()))
        .thenAnswer(
            invocation ->
                select(edges, invocation.getArgument(4), invocation.getArgument(5), false));
    return mapper;
  }

  private static List<EdgeRow> select(
      List<EdgeRow> edges, Collection<String> scholars, int limit, boolean matchA) {
    return edges.stream()
        .filter(row -> scholars.contains(matchA ? row.scholarA() : row.scholarB()))
        .sorted(Comparator.comparing(EdgeRow::scholarA).thenComparing(EdgeRow::scholarB))
        .limit(limit)
        .toList();
  }

  static NetworkService postgres(NetworkMapper mapper) {
    PublicBarrierGuard guard = mock(PublicBarrierGuard.class);
    when(guard.assertPubliclyServed()).thenReturn(barrier());
    return new NetworkService(mapper, guard, projectionProperties());
  }

  static NetworkService postgres(
      NetworkMapper mapper, ProjectionProperties properties, PublicBarrierGuard guard) {
    return new NetworkService(mapper, guard, properties);
  }

  static CollaborationGraphService graphService(List<EdgeRow> edges) {
    return graphService(edges, 20_000, 100_000, 1_000L);
  }

  static CollaborationGraphService graphService(
      List<EdgeRow> edges, int maxNodes, int maxEdges, long budget) {
    return new CollaborationGraphService(
        new InMemoryGraphRepository(edges), maxNodes, maxEdges, budget);
  }

  static NetworkGraphQueryService graphQueryService(
      List<EdgeRow> edges, NetworkMapper mapper, boolean schemaReady) {
    return graphQueryService(edges, mapper, projectionProperties(), graphProperties(), schemaReady);
  }

  static NetworkGraphQueryService graphQueryService(
      List<EdgeRow> edges,
      NetworkMapper mapper,
      ProjectionProperties projectionProperties,
      Neo4jGraphProperties graphProperties,
      boolean schemaReady) {
    return new NetworkGraphQueryService(
        graphService(
            edges,
            graphProperties.maxNodes(),
            graphProperties.maxEdges(),
            graphProperties.budgetMillis()),
        mapper,
        projectionProperties,
        graphProperties,
        guard(schemaReady));
  }

  static ProjectionWatermarkGuard guard(boolean schemaReady) {
    GraphProjectionStore store = mock(GraphProjectionStore.class);
    Neo4jSchemaInitializer initializer = mock(Neo4jSchemaInitializer.class);
    when(initializer.isReady()).thenReturn(schemaReady);
    return new ProjectionWatermarkGuard(
        store, initializer, graphProperties(), initializationProbe());
  }

  /**
   * 默认探针：本部署没有初始化状态记录（{@code Optional.empty()}）。
   *
   * <p>此时水位保护只做元数据完整性判定。需要验证回填就绪时不完整的图被拦住时，显式把状态注入
   * 这个桩（见 {@code ProjectionWatermarkGuardTest}）。
   */
  static GraphProjectionInitializationProbe initializationProbe() {
    GraphProjectionInitializationProbe probe = mock(GraphProjectionInitializationProbe.class);
    when(probe.inspect(anyString())).thenReturn(Optional.empty());
    return probe;
  }

  static GraphCollaborationEdge graphEdge(String a, String b, int strength) {
    List<GraphWorkEvidence> evidence = new ArrayList<>();
    for (int i = 0; i < Math.max(strength, 1); i++) {
      evidence.add(new GraphWorkEvidence("w-" + a + "-" + b + "-" + i, "Title " + i, "REAL"));
    }
    int effective = Math.max(strength, 1);
    return new GraphCollaborationEdge(a, b, effective, evidence);
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> nodes(Map<String, Object> response) {
    Object value = response.get("nodes");
    return value == null ? List.of() : (List<Map<String, Object>>) value;
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> edges(Map<String, Object> response) {
    Object value = response.get("edges");
    return value == null ? List.of() : (List<Map<String, Object>>) value;
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> pathNodes(Map<String, Object> response) {
    Object value = response.get("pathNodes");
    return value == null ? List.of() : (List<Map<String, Object>>) value;
  }

  /** 内存图仓储：只按 frontier 过滤边，不做任何业务口径判断。 */
  static final class InMemoryGraphRepository implements CollaborationGraphRepository {

    private final Map<String, GraphCollaborationEdge> byPair = new LinkedHashMap<>();
    private final boolean available;
    private int expandCalls;
    private RuntimeException failure;

    InMemoryGraphRepository(List<EdgeRow> rows) {
      this(rows, true);
    }

    InMemoryGraphRepository(List<EdgeRow> rows, boolean available) {
      for (EdgeRow row : rows) {
        GraphCollaborationEdge edge =
            graphEdge(row.scholarA(), row.scholarB(), (int) row.strength());
        byPair.put(edge.pairKey(), edge);
      }
      this.available = available;
    }

    void failWith(RuntimeException failure) {
      this.failure = failure;
    }

    int expandCalls() {
      return expandCalls;
    }

    @Override
    public Expansion expand(
        Collection<String> frontier, GraphFilterCriteria criteria, int maxEdges) {
      expandCalls++;
      if (failure != null) {
        throw failure;
      }
      if (frontier == null || frontier.isEmpty()) {
        return Expansion.empty();
      }
      List<GraphCollaborationEdge> found = new ArrayList<>();
      for (GraphCollaborationEdge edge : byPair.values()) {
        if (frontier.contains(edge.scholarA()) || frontier.contains(edge.scholarB())) {
          found.add(edge);
        }
      }
      found.sort(
          Comparator.comparing(GraphCollaborationEdge::scholarA)
              .thenComparing(GraphCollaborationEdge::scholarB));
      boolean truncated = found.size() > maxEdges;
      if (truncated) {
        found = found.subList(0, Math.max(maxEdges, 0));
      }
      return new Expansion(found, truncated);
    }

    @Override
    public List<GraphScholarNode> findScholars(Collection<String> scholarIds) {
      List<GraphScholarNode> nodes = new ArrayList<>();
      for (String id : scholarIds) {
        nodes.add(new GraphScholarNode(id, "Name-" + id, null, null, "REAL", 1L));
      }
      return nodes;
    }

    @Override
    public boolean isAvailable() {
      return available;
    }
  }
}
