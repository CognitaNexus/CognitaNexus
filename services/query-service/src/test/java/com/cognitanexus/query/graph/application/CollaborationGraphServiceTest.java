package com.cognitanexus.query.graph.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cognitanexus.query.graph.domain.GraphCollaborationEdge;
import com.cognitanexus.query.graph.domain.GraphFilterCriteria;
import com.cognitanexus.query.graph.domain.GraphPathResult;
import com.cognitanexus.query.graph.domain.GraphScholarNode;
import com.cognitanexus.query.graph.domain.GraphSubgraphResult;
import com.cognitanexus.query.graph.domain.GraphWorkEvidence;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 图库实现的合作网络用例：路径确定性与规模边界。
 *
 * <p>这些用例锁住的是「换存储不得改业务规则」：等长路径取字典序最小、0 跳要确认在范围内、
 * 超预算/超规模明确失败而不是返回半个图。真实 Cypher 行为由 {@code Neo4jSchemaIT} 覆盖；
 * 这里用内存假仓储单独验证与应用层规则，故障定位时能区分「规则错」与「Cypher 错」。
 */
class CollaborationGraphServiceTest {

  private FakeGraphRepository repository;
  private CollaborationGraphService service;

  @BeforeEach
  void setUp() {
    repository = new FakeGraphRepository();
    service = new CollaborationGraphService(repository, 20_000, 100_000, 1_000L);
  }

  private static GraphCollaborationEdge edge(String a, String b, String... workIds) {
    List<GraphWorkEvidence> evidence =
        java.util.Arrays.stream(workIds)
            .map(id -> new GraphWorkEvidence(id, "Title of " + id, "REAL"))
            .toList();
    return new GraphCollaborationEdge(a, b, evidence.size(), evidence);
  }

  @Test
  @DisplayName("自我网络只按种子逐层扩边，并按深度截断")
  void neighborhoodRespectsDepth() {
    repository.add(edge("a", "b", "w1"), edge("b", "c", "w2"), edge("c", "d", "w3"));

    GraphSubgraphResult oneHop = service.neighborhood("b", 1, 0, GraphFilterCriteria.realOnly());
    assertThat(oneHop.seedInScope()).isTrue();
    assertThat(nodeIds(oneHop)).containsExactly("b", "a", "c");
    assertThat(oneHop.edges())
        .extracting(GraphCollaborationEdge::pairKey)
        .containsExactly("a\u0001b", "b\u0001c");
    assertThat(oneHop.visitedNodes()).isEqualTo(3);

    GraphSubgraphResult deep = service.neighborhood("b", 9, 0, GraphFilterCriteria.realOnly());
    // 深度上限与 PG 一致：请求 9 跳按 3 跳处理，不会无界展开。
    assertThat(deep.depth()).isEqualTo(CollaborationGraphService.MAX_NEIGHBORHOOD_DEPTH);
    assertThat(nodeIds(deep)).containsExactlyInAnyOrder("a", "b", "c", "d");
  }

  @Test
  @DisplayName("种子没有合格合作边时如实返回空图，不伪造孤立节点")
  void seedWithoutEdgesIsReportedOutOfScope() {
    repository.add(edge("b", "c", "w1"));

    GraphSubgraphResult result = service.neighborhood("a", 2, 0, GraphFilterCriteria.realOnly());

    assertThat(result.seedInScope()).isFalse();
    assertThat(result.nodes()).isEmpty();
    assertThat(result.edges()).isEmpty();
    assertThat(result.note()).contains("没有合格合作边");
  }

  @Test
  @DisplayName("请求上限不能放宽服务端上限")
  void requestedNodeLimitCannotExceedServerLimit() {
    repository.add(edge("a", "b", "w1"), edge("b", "c", "w2"));

    GraphSubgraphResult result =
        service.neighborhood("a", 2, 999_999, GraphFilterCriteria.realOnly());

    assertThat(result.seedInScope()).isTrue();
    assertThat(result.nodes()).hasSize(3);
  }

  @Test
  @DisplayName("节点超上限明确失败，不返回截断后的子图")
  void nodeLimitExceededFailsExplicitly() {
    repository.add(edge("a", "b", "w1"), edge("b", "c", "w2"));

    assertThatThrownBy(() -> service.neighborhood("a", 2, 1, GraphFilterCriteria.realOnly()))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).errorCode())
        .isEqualTo(ErrorCode.GRAPH_SCOPE_TOO_LARGE);
  }

  @Test
  @DisplayName("边超上限明确失败，不把截断当成「没有更多合作者」")
  void edgeLimitExceededFailsExplicitly() {
    repository.add(edge("a", "b", "w1"), edge("a", "c", "w2"), edge("a", "d", "w3"));
    CollaborationGraphService tiny = new CollaborationGraphService(repository, 20_000, 1, 1_000L);

    assertThatThrownBy(() -> tiny.neighborhood("a", 1, 0, GraphFilterCriteria.realOnly()))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).errorCode())
        .isEqualTo(ErrorCode.GRAPH_SCOPE_TOO_LARGE);
  }

  @Test
  @DisplayName("等长最短路径取规范 ID 序列字典序最小的一条，且与遍历顺序无关")
  void shortestPathBreaksTiesLexicographically() {
    repository.add(
        edge("s", "b", "w1"), edge("b", "t", "w2"), edge("s", "c", "w3"), edge("c", "t", "w4"));

    GraphPathResult result = service.shortestPath("s", "t", GraphFilterCriteria.realOnly(), 1_000L);

    assertThat(result.reachable()).isTrue();
    assertThat(result.hops()).isEqualTo(2);
    assertThat(result.path()).containsExactly("s", "b", "t");
    assertThat(result.hopEdges())
        .extracting(GraphCollaborationEdge::pairKey)
        .containsExactly("b\u0001s", "b\u0001t");
    assertThat(result.note()).contains("跳数最少", "字典序最小");
  }

  @Test
  @DisplayName("更短的路径优先于字典序更小的更长路径")
  void shortestPathPrefersFewerHops() {
    repository.add(
        edge("a", "z", "w1"),
        edge("z", "y", "w2"),
        edge("y", "b", "w3"),
        edge("a", "m", "w4"),
        edge("m", "b", "w5"));

    GraphPathResult result = service.shortestPath("a", "b", GraphFilterCriteria.realOnly(), 1_000L);

    assertThat(result.hops()).isEqualTo(2);
    assertThat(result.path()).containsExactly("a", "m", "b");
  }

  @Test
  @DisplayName("超过搜索深度上限时返回不可达并说明是「N 跳内不连通」")
  void unreachableBeyondDepthLimit() {
    List<GraphCollaborationEdge> chain = new ArrayList<>();
    String previous = "n0";
    for (int i = 1; i <= 8; i++) {
      String current = "n" + i;
      chain.add(edge(previous, current, "w" + i));
      previous = current;
    }
    repository.add(chain.toArray(new GraphCollaborationEdge[0]));

    GraphPathResult result =
        service.shortestPath("n0", "n8", GraphFilterCriteria.realOnly(), 1_000L);

    assertThat(result.reachable()).isFalse();
    assertThat(result.path()).isEmpty();
    assertThat(result.note()).contains("6 跳范围内未找到合作路径");
  }

  @Test
  @DisplayName("起点等于终点：只有在范围内有合格合作边时才按 0 跳可达")
  void zeroHopRequiresQualifiedEdge() {
    repository.add(edge("a", "b", "w1"));

    GraphPathResult inScope =
        service.shortestPath("a", "a", GraphFilterCriteria.realOnly(), 1_000L);
    assertThat(inScope.reachable()).isTrue();
    assertThat(inScope.hops()).isZero();
    assertThat(inScope.path()).containsExactly("a");

    GraphPathResult outOfScope =
        service.shortestPath("lonely", "lonely", GraphFilterCriteria.realOnly(), 1_000L);
    assertThat(outOfScope.reachable()).isFalse();
    assertThat(outOfScope.hops()).isNull();
    assertThat(outOfScope.note()).contains("不按 0 跳可达处理");
  }

  @Test
  @DisplayName("超过时间预算抛出 GRAPH_TIMEOUT，不返回部分距离")
  void budgetExceededFailsExplicitly() {
    repository.add(edge("a", "b", "w1"), edge("b", "c", "w2"));
    repository.expandDelayMillis = 20L;
    CollaborationGraphService impatient =
        new CollaborationGraphService(repository, 20_000, 100_000, 1L);

    assertThatThrownBy(() -> impatient.shortestPath("a", "c", GraphFilterCriteria.realOnly(), 0L))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).errorCode())
        .isEqualTo(ErrorCode.GRAPH_TIMEOUT);
  }

  @Test
  @DisplayName("筛选条件原样下推给仓储，应用层不自行放宽")
  void filterCriteriaArePassedThrough() {
    repository.add(edge("a", "b", "w1"));
    GraphFilterCriteria criteria = new GraphFilterCriteria("ALL", "topic-1", "org-1");

    service.neighborhood("a", 1, 0, criteria);

    assertThat(repository.lastCriteria).isEqualTo(criteria);
  }

  private static List<String> nodeIds(GraphSubgraphResult result) {
    return result.nodes().stream().map(GraphScholarNode::scholarId).toList();
  }

  /** 内存假仓储：只做一层邻接过滤，用于验证应用层规则而不涉及 Cypher。 */
  private static final class FakeGraphRepository implements CollaborationGraphRepository {

    private final List<GraphCollaborationEdge> edges = new ArrayList<>();
    private volatile long expandDelayMillis;
    private GraphFilterCriteria lastCriteria;

    private void add(GraphCollaborationEdge... newEdges) {
      edges.addAll(List.of(newEdges));
    }

    @Override
    public Expansion expand(
        Collection<String> frontier, GraphFilterCriteria criteria, int maxEdges) {
      lastCriteria = criteria;
      if (expandDelayMillis > 0) {
        try {
          Thread.sleep(expandDelayMillis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }
      List<GraphCollaborationEdge> found =
          edges.stream()
              .filter(
                  edge -> frontier.contains(edge.scholarA()) || frontier.contains(edge.scholarB()))
              .toList();
      if (found.size() > maxEdges) {
        return new Expansion(found.subList(0, maxEdges), true);
      }
      return new Expansion(found, false);
    }

    @Override
    public List<GraphScholarNode> findScholars(Collection<String> scholarIds) {
      Set<String> requested = new LinkedHashSet<>(scholarIds);
      List<GraphScholarNode> nodes = new ArrayList<>();
      for (String id : requested) {
        nodes.add(new GraphScholarNode(id, "Name-" + id, "domain", "org", "REAL", 1L));
      }
      return nodes;
    }

    @Override
    public boolean isAvailable() {
      return true;
    }
  }
}
