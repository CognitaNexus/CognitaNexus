package com.cognitanexus.query.graph.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cognitanexus.query.consistency.PublicBarrierGuard;
import com.cognitanexus.query.graph.domain.GraphCollaborationEdge;
import com.cognitanexus.query.graph.domain.GraphFilterCriteria;
import com.cognitanexus.query.graph.domain.GraphPathResult;
import com.cognitanexus.query.graph.domain.GraphScholarNode;
import com.cognitanexus.query.graph.domain.GraphSubgraphResult;
import com.cognitanexus.query.graph.domain.GraphWorkEvidence;
import com.cognitanexus.query.network.application.NetworkService;
import com.cognitanexus.query.network.infrastructure.NetworkMapper;
import com.cognitanexus.query.network.infrastructure.NetworkMapper.EdgeRow;
import com.cognitanexus.query.projection.application.ProjectionProperties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PG 实现与图投影实现的规则一致性。
 *
 * <p>为什么需要逐例对比：引入图数据库最容易造成的不是「图查不出来」，而是<b>同样的输入给出
 * 不同的路径</b>。这种差异不会抛异常，界面上两条路都「看起来对」，但用户按同一条件刷新会看到
 * 不同结果，逐跳证据也对不上。本测试用同一份边集合分别喂给 {@link NetworkService}（模拟 PG
 * Mapper）与 {@link CollaborationGraphService}（内存假仓储），逐对起点终点比较可达性、跳数与
 * 完整路径，并比较每位学者的自我网络节点/边集合。
 *
 * <p>未来若有人改动其中一侧的 BFS 或等长路径选择而不改另一侧，这里会立即失败。
 */
class NetworkGraphParityTest {

  @Test
  @DisplayName("菱形等长路径：两侧都取字典序最小的一条")
  void diamondParity() {
    assertPathParity(
        List.of(edge("s", "b", 1), edge("b", "t", 1), edge("s", "c", 1), edge("c", "t", 1)));
  }

  @Test
  @DisplayName("链式图：跳数、路径与不可达边界一致")
  void chainParity() {
    assertPathParity(
        List.of(edge("a", "b", 2), edge("b", "c", 1), edge("c", "d", 3), edge("d", "e", 1)));
  }

  @Test
  @DisplayName("星形图：中心到所有叶子 1 跳，叶子之间 2 跳且路径经过中心")
  void starParity() {
    List<EdgeRow> edges = new ArrayList<>();
    for (int i = 1; i <= 6; i++) {
      edges.add(edge("hub", "leaf" + i, 1));
    }
    assertPathParity(edges);
  }

  @Test
  @DisplayName("非连通图：两侧都返回不可达，而不是编造路径")
  void disconnectedParity() {
    assertPathParity(
        List.of(edge("a", "b", 1), edge("b", "c", 1), edge("x", "y", 1), edge("y", "z", 1)));
  }

  @Test
  @DisplayName("混合拓扑：多篇共同论文的强度、深度与最短路径全部一致")
  void mixedTopologyParity() {
    assertPathParity(
        List.of(
            edge("p", "q", 3),
            edge("q", "r", 1),
            edge("p", "r", 1),
            edge("r", "s", 2),
            edge("q", "s", 1),
            edge("s", "t", 1),
            edge("t", "u", 1),
            edge("u", "v", 1),
            edge("v", "w", 1),
            edge("w", "x", 1)));
  }

  private void assertPathParity(List<EdgeRow> edges) {
    NetworkService pg = pgService(edges);
    CollaborationGraphService graph = graphService(edges);
    Set<String> nodes = new LinkedHashSet<>();
    for (EdgeRow edge : edges) {
      nodes.add(edge.scholarA());
      nodes.add(edge.scholarB());
    }

    for (String from : nodes) {
      for (String to : nodes) {
        Map<String, Object> pgResult = pg.shortestPath(from, to, "ALL", null, null);
        GraphPathResult graphResult =
            graph.shortestPath(from, to, GraphFilterCriteria.all(), 1_000L);

        String label = from + " -> " + to;
        assertThat(graphResult.reachable())
            .as(label + " 可达性应与 PG 一致")
            .isEqualTo(pgResult.get("reachable"));
        assertThat(graphResult.hops()).as(label + " 跳数应与 PG 一致").isEqualTo(pgResult.get("hops"));
        assertThat(graphResult.path())
            .as(label + " 路径应与 PG 完全一致（等长时字典序最小）")
            .isEqualTo(pgResult.get("path"));
      }
    }

    for (String seed : nodes) {
      for (int depth = 1; depth <= 3; depth++) {
        Map<String, Object> pgResult = pg.neighborhood(seed, depth, 0, "ALL", null, null);
        GraphSubgraphResult graphResult =
            graph.neighborhood(seed, depth, 0, GraphFilterCriteria.all());

        String label = seed + " depth=" + depth;
        assertThat(graphResult.seedInScope())
            .as(label + " seedInScope 应与 PG 一致")
            .isEqualTo(pgResult.get("seedInScope"));
        assertThat(graphNodeIds(graphResult))
            .as(label + " 节点集合应与 PG 一致")
            .isEqualTo(pgNodeIds(pgResult));
        assertThat(graphEdgeStrengths(graphResult))
            .as(label + " 边与强度应与 PG 一致")
            .isEqualTo(pgEdgeStrengths(pgResult));
      }
    }
  }

  private static Set<String> graphNodeIds(GraphSubgraphResult result) {
    return result.nodes().stream()
        .map(GraphScholarNode::scholarId)
        .collect(Collectors.toCollection(java.util.TreeSet::new));
  }

  @SuppressWarnings("unchecked")
  private static Set<String> pgNodeIds(Map<String, Object> result) {
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) result.get("nodes");
    return nodes.stream()
        .map(node -> String.valueOf(node.get("scholarId")))
        .collect(Collectors.toCollection(java.util.TreeSet::new));
  }

  private static Map<String, Long> graphEdgeStrengths(GraphSubgraphResult result) {
    Map<String, Long> strengths = new TreeMap<>();
    for (GraphCollaborationEdge edge : result.edges()) {
      strengths.put(edge.pairKey(), edge.strength());
    }
    return strengths;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Long> pgEdgeStrengths(Map<String, Object> result) {
    List<Map<String, Object>> edges = (List<Map<String, Object>>) result.get("edges");
    Map<String, Long> strengths = new TreeMap<>();
    for (Map<String, Object> edge : edges) {
      String source = String.valueOf(edge.get("source"));
      String target = String.valueOf(edge.get("target"));
      String key =
          source.compareTo(target) <= 0 ? source + "\u0001" + target : target + "\u0001" + source;
      strengths.put(key, ((Number) edge.get("strength")).longValue());
    }
    return strengths;
  }

  private static EdgeRow edge(String a, String b, int strength) {
    return new EdgeRow(a, b, strength);
  }

  private static NetworkService pgService(List<EdgeRow> edges) {
    NetworkMapper mapper = mock(NetworkMapper.class);
    PublicBarrierGuard guard = mock(PublicBarrierGuard.class);
    when(guard.assertPubliclyServed())
        .thenReturn(new PublicBarrierGuard.BarrierSnapshot(120L, 120L, 120L, 120L, "42"));
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
            invocation -> {
              Collection<String> scholars = invocation.getArgument(4);
              int limit = invocation.getArgument(5);
              return edges.stream()
                  .filter(row -> scholars.contains(row.scholarA()))
                  .sorted(Comparator.comparing(EdgeRow::scholarA).thenComparing(EdgeRow::scholarB))
                  .limit(limit)
                  .toList();
            });
    when(mapper.listEdgesWhereScholarB(anyString(), any(), any(), any(), anyList(), anyInt()))
        .thenAnswer(
            invocation -> {
              Collection<String> scholars = invocation.getArgument(4);
              int limit = invocation.getArgument(5);
              return edges.stream()
                  .filter(row -> scholars.contains(row.scholarB()))
                  .sorted(Comparator.comparing(EdgeRow::scholarA).thenComparing(EdgeRow::scholarB))
                  .limit(limit)
                  .toList();
            });
    ProjectionProperties properties =
        new ProjectionProperties(
            "query", 500, 5, 200, false, 2_000L, 20_000, 100_000, 1_000L, 10_000, 20, 100);
    return new NetworkService(mapper, guard, properties);
  }

  private static CollaborationGraphService graphService(List<EdgeRow> edges) {
    Map<String, GraphCollaborationEdge> byPair = new LinkedHashMap<>();
    for (EdgeRow row : edges) {
      List<GraphWorkEvidence> evidence = new ArrayList<>();
      for (int i = 0; i < row.strength(); i++) {
        evidence.add(
            new GraphWorkEvidence(
                "work-" + row.scholarA() + "-" + row.scholarB() + "-" + i, "Title " + i, "REAL"));
      }
      byPair.put(
          row.scholarA() + "\u0001" + row.scholarB(),
          new GraphCollaborationEdge(
              row.scholarA(), row.scholarB(), (int) row.strength(), evidence));
    }
    CollaborationGraphRepository repository =
        new CollaborationGraphRepository() {
          @Override
          public Expansion expand(
              Collection<String> frontier, GraphFilterCriteria criteria, int maxEdges) {
            List<GraphCollaborationEdge> found =
                byPair.values().stream()
                    .filter(
                        edge ->
                            frontier.contains(edge.scholarA())
                                || frontier.contains(edge.scholarB()))
                    .limit(maxEdges)
                    .toList();
            return new Expansion(found, found.size() >= maxEdges && !found.isEmpty());
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
            return true;
          }
        };
    return new CollaborationGraphService(repository, 20_000, 100_000, 1_000L);
  }
}
