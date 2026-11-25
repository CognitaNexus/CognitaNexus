package com.cognitanexus.query.graph.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cognitanexus.query.graph.domain.GraphCollaborationEdge;
import com.cognitanexus.query.graph.domain.GraphFilterCriteria;
import com.cognitanexus.query.graph.domain.GraphPathResult;
import com.cognitanexus.query.graph.domain.GraphScholarNode;
import com.cognitanexus.query.graph.domain.GraphSubgraphResult;
import com.cognitanexus.query.graph.domain.GraphWorkEvidence;
import com.cognitanexus.query.network.domain.NetworkRules;
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
    // 深度上限与 PG 一致：请求 9 跳按 NetworkRules 上限处理，不会无界展开。
    assertThat(deep.depth()).isEqualTo(NetworkRules.MAX_NEIGHBORHOOD_DEPTH);
    assertThat(nodeIds(deep)).containsExactlyInAnyOrder("a", "b", "c", "d");
  }

  @Test
  @DisplayName("深度上限只有 NetworkRules 一处定义：图实现不复制数值，行为与常量严格一致")
  void depthLimitsComeFromNetworkRulesOnly() {
    // 数值契约：两个实现引用同一常量。改动规则时只改 NetworkRules；若有人在这里重新
    // 声明 `3`/`6`，结构与行为断言会同时失败。
    assertThat(NetworkRules.MAX_NEIGHBORHOOD_DEPTH).isEqualTo(3);
    assertThat(NetworkRules.MAX_PATH_DEPTH).isEqualTo(6);

    // 结构契约：本类不得再声明自己的深度常量（F20-1 的根因就是两份字面量）。
    assertThat(
            java.util.Arrays.stream(CollaborationGraphService.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .toList())
        .doesNotContain("MAX_NEIGHBORHOOD_DEPTH", "MAX_PATH_DEPTH", "MAX_DEPTH");

    // 行为契约：请求深度截断到常量上限；路径在 MAX_PATH_DEPTH 内可达，再多一跳即不可达。
    repository.add(chain(NetworkRules.MAX_PATH_DEPTH + 2).toArray(GraphCollaborationEdge[]::new));
    GraphSubgraphResult deep = service.neighborhood("n0", 99, 0, GraphFilterCriteria.realOnly());
    assertThat(deep.depth()).isEqualTo(NetworkRules.MAX_NEIGHBORHOOD_DEPTH);
    assertThat(
            service
                .shortestPath(
                    "n0", "n" + NetworkRules.MAX_PATH_DEPTH, GraphFilterCriteria.realOnly(), 1_000L)
                .path())
        .hasSize(NetworkRules.MAX_PATH_DEPTH + 1);
    assertThat(
            service
                .shortestPath(
                    "n0",
                    "n" + (NetworkRules.MAX_PATH_DEPTH + 1),
                    GraphFilterCriteria.realOnly(),
                    1_000L)
                .reachable())
        .isFalse();
  }

  /** 生成一条 n0-n1-... 的链，用于把「深度上限」变成可执行断言。 */
  private static List<GraphCollaborationEdge> chain(int hops) {
    List<GraphCollaborationEdge> edges = new ArrayList<>();
    for (int i = 1; i <= hops; i++) {
      edges.add(edge("n" + (i - 1), "n" + i, "w" + i));
    }
    return edges;
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
  @DisplayName("回归：路径结果带出实际载入的搜索子图规模（双侧：节点取大、边求和）")
  void pathResultCarriesLoadedSubgraphSize() {
    repository.add(
        edge("s", "b", "w1"), edge("b", "t", "w2"), edge("s", "c", "w3"), edge("c", "t", "w4"));

    GraphPathResult result = service.shortestPath("s", "t", GraphFilterCriteria.realOnly(), 1_000L);

    // 起点侧扩到第 2 跳发现 t：载入 s/b/c/t 4 个节点、s-b/b-t/s-c/c-t 4 条边；
    // 终点侧只需覆盖 distance-1=1 跳：载入 t/b/c 3 个节点、b-t/c-t 2 条边。
    // 合并口径与 PG（ScopedGraphLoader 双侧）一致：节点 max(4,3)=4、边 4+2=6。
    assertThat(result.loadedNodes()).isEqualTo(4);
    assertThat(result.loadedEdges()).isEqualTo(6);
    // 路径本身的大小与加载规模是两件事：3 个路径节点、2 跳。
    assertThat(result.path()).hasSize(3);
    assertThat(result.hopEdges()).hasSize(2);
  }

  @Test
  @DisplayName("回归：0 跳与不可达结果同样带出载入规模，不是 1 个节点/0 条边")
  void zeroHopAndUnreachableCarryLoadedSize() {
    repository.add(edge("a", "b", "w1"), edge("a", "c", "w2"), edge("b", "d", "w3"));

    GraphPathResult zeroHop =
        service.shortestPath("a", "a", GraphFilterCriteria.realOnly(), 1_000L);
    assertThat(zeroHop.reachable()).isTrue();
    // 种子那一步就把 a 的邻居全部载入：a/b/c 3 个节点、a-b/a-c 2 条边。
    assertThat(zeroHop.loadedNodes()).isEqualTo(3);
    assertThat(zeroHop.loadedEdges()).isEqualTo(2);

    GraphPathResult unreachable =
        service.shortestPath("a", "lonely", GraphFilterCriteria.realOnly(), 1_000L);
    assertThat(unreachable.reachable()).isFalse();
    assertThat(unreachable.loadedNodes()).isEqualTo(4);
    assertThat(unreachable.loadedEdges()).isEqualTo(3);
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
    assertThat(result.note()).contains(NetworkRules.MAX_PATH_DEPTH + " 跳范围内未找到合作路径");
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
