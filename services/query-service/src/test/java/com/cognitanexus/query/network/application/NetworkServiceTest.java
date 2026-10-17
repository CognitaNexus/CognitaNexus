package com.cognitanexus.query.network.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.query.consistency.PublicBarrierGuard;
import com.cognitanexus.query.network.infrastructure.NetworkMapper;
import com.cognitanexus.query.network.infrastructure.NetworkMapper.EdgeRow;
import com.cognitanexus.query.projection.application.ProjectionProperties;
import com.cognitanexus.sharedkernel.BusinessException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 合作网络的加载策略与语义。
 *
 * <p>这些用例锁住的是两个真实故障：
 *
 * <ul>
 *   <li>曾经「先载入筛选范围内的全图再取邻域」，在 2.5 万学者 / 7.7 万边的真实数据上必然超过
 *       节点上限，任何学者的自我网络都不可用；
 *   <li>相同起点与终点也走了全图加载，于是「0 跳可达」同样被范围错误挡住。
 * </ul>
 */
class NetworkServiceTest {

  private NetworkMapper mapper;
  private PublicBarrierGuard guard;
  private NetworkService service;
  private List<EdgeRow> edges;

  @BeforeEach
  void setUp() {
    mapper = mock(NetworkMapper.class);
    guard = mock(PublicBarrierGuard.class);
    when(guard.assertPubliclyServed())
        .thenReturn(new PublicBarrierGuard.BarrierSnapshot(120L, 120L, 120L, 120L, "42"));
    when(mapper.listScholarNames(anyString(), anyList())).thenReturn(List.of());
    edges = new ArrayList<>();
    when(mapper.listEdgesOfScholars(anyString(), any(), any(), any(), anyList(), anyInt()))
        .thenAnswer(
            invocation -> {
              Collection<String> scholars = invocation.getArgument(4);
              int limit = invocation.getArgument(5);
              return edges.stream()
                  .filter(
                      row -> scholars.contains(row.scholarA()) || scholars.contains(row.scholarB()))
                  .sorted(Comparator.comparing(EdgeRow::scholarA).thenComparing(EdgeRow::scholarB))
                  .limit(limit)
                  .toList();
            });
    service = new NetworkService(mapper, guard, properties(20_000, 100_000, 1_000L));
  }

  @Test
  @DisplayName("自我网络只按种子扩边，不读取筛选范围的全图")
  void neighborhoodLoadsOnlyAroundSeed() {
    edges.add(new EdgeRow("s_a", "s_b", 1));
    edges.add(new EdgeRow("s_a", "s_c", 1));
    edges.add(new EdgeRow("s_b", "s_d", 1));
    edges.add(new EdgeRow("s_x", "s_y", 1));

    Map<String, Object> view = service.neighborhood("s_a", 2, 0, "REAL", null, null);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) view.get("nodes");
    assertThat(nodes)
        .extracting(node -> node.get("scholarId"))
        .containsExactlyInAnyOrder("s_a", "s_b", "s_c", "s_d");
    assertThat(view.get("seedInScope")).isEqualTo(true);
    // 全图出口一次都不该被调用：这正是「真实规模下不可用」的根因。
    verify(mapper, never()).countNodes(anyString(), any(), any(), any());
    verify(mapper, never()).listEdges(anyString(), any(), any(), any(), anyInt());
  }

  @Test
  @DisplayName("深度边界上两端都在边界的边要补齐，且不再向外扩散")
  void depthBoundaryEdgesAreCompleted() {
    // s_a - s_b - s_c，另有一条 s_b - s_c 之外的同层边 s_c - s_d：
    // depth=1 时节点只应有 a、b；depth=2 时 c 在边界，c-d 不应把 d 带进来。
    edges.add(new EdgeRow("s_a", "s_b", 1));
    edges.add(new EdgeRow("s_b", "s_c", 1));
    edges.add(new EdgeRow("s_c", "s_d", 1));

    Map<String, Object> depth1 = service.neighborhood("s_a", 1, 0, "REAL", null, null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> nodes1 = (List<Map<String, Object>>) depth1.get("nodes");
    assertThat(nodes1)
        .extracting(node -> node.get("scholarId"))
        .containsExactlyInAnyOrder("s_a", "s_b");

    Map<String, Object> depth2 = service.neighborhood("s_a", 2, 0, "REAL", null, null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> nodes2 = (List<Map<String, Object>>) depth2.get("nodes");
    assertThat(nodes2)
        .extracting(node -> node.get("scholarId"))
        .containsExactlyInAnyOrder("s_a", "s_b", "s_c");
  }

  @Test
  @DisplayName("子图超过节点上限时明确失败，不返回半个图")
  void neighborhoodRejectsWhenNodeLimitExceeded() {
    service = new NetworkService(mapper, guard, properties(3, 100_000, 1_000L));
    edges.add(new EdgeRow("s_a", "s_b", 1));
    edges.add(new EdgeRow("s_a", "s_c", 1));
    edges.add(new EdgeRow("s_a", "s_d", 1));

    assertThatThrownBy(() -> service.neighborhood("s_a", 1, 0, "REAL", null, null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("节点上限");
  }

  @Test
  @DisplayName("缺少合格边的学者返回空图并说明原因，不伪造孤立节点")
  void scholarWithoutQualifiedEdgesGetsEmptyGraph() {
    Map<String, Object> view = service.neighborhood("s_none", 2, 0, "REAL", null, null);

    assertThat(view.get("seedInScope")).isEqualTo(false);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> nodes = (List<Map<String, Object>>) view.get("nodes");
    assertThat(nodes).isEmpty();
    assertThat(view.get("note")).asString().contains("没有合格合作边");
  }

  @Test
  @DisplayName("起点与终点相同且确有合作边时按 0 跳返回，DTO 形状与正常路径一致")
  void samePointIsZeroHopsWhenInScope() {
    edges.add(new EdgeRow("s_a", "s_b", 1));

    Map<String, Object> view = service.shortestPath("s_a", "s_a", "REAL", null, null);

    assertThat(view.get("reachable")).isEqualTo(true);
    assertThat(view.get("hops")).isEqualTo(0);
    assertThat(view.get("path")).isEqualTo(List.of("s_a"));
    // 与正常路径保持同一形状：调用方不必为零跳单独判空。
    assertThat(view).containsKey("pathNodes");
  }

  @Test
  @DisplayName("起点与终点相同但该学者不在当前范围内时，不伪造 0 跳可达")
  void samePointOutsideScopeIsNotReachable() {
    // 没有任何边：可能是未公开、关系不合格，或筛选条件把他排除了。
    // 改动前这里会返回 reachable=true 且不带 pathNodes，任意不存在的 ID 都能冒充有效路径。
    Map<String, Object> view = service.shortestPath("s_missing", "s_missing", "REAL", null, null);

    assertThat(view.get("reachable")).isEqualTo(false);
    assertThat(view.get("hops")).isNull();
    assertThat(view.get("path")).isEqualTo(List.of());
    assertThat(view.get("note")).asString().contains("没有合格公开合作边");
  }

  @Test
  @DisplayName("等长路径取 ID 序列字典序最小的一条，与加载顺序无关")
  void pathPrefersSmallestIds() {
    edges.add(new EdgeRow("s_a", "s_c", 1));
    edges.add(new EdgeRow("s_a", "s_b", 1));
    edges.add(new EdgeRow("s_b", "s_z", 1));
    edges.add(new EdgeRow("s_c", "s_z", 1));

    Map<String, Object> view = service.shortestPath("s_a", "s_z", "REAL", null, null);

    assertThat(view.get("hops")).isEqualTo(2);
    assertThat(view.get("path")).isEqualTo(List.of("s_a", "s_b", "s_z"));
  }

  @Test
  @DisplayName("超过搜索深度时如实说明「N 跳内未找到」，不冒充全图不连通")
  void unreachableWithinDepthBoundExplainsItself() {
    edges.add(new EdgeRow("s_a", "s_b", 1));
    edges.add(new EdgeRow("s_b", "s_c", 1));

    Map<String, Object> view = service.shortestPath("s_a", "s_zzz", "REAL", null, null);

    assertThat(view.get("reachable")).isEqualTo(false);
    assertThat(view.get("note")).asString().contains("6 跳");
    assertThat(view.get("note")).asString().contains("不等于全图不连通");
  }

  private static ProjectionProperties properties(int maxNodes, int maxEdges, long budget) {
    return new ProjectionProperties(
        "query", 500, 5, 20, false, 2_000L, maxNodes, maxEdges, budget, 10_000, 20, 100);
  }
}
