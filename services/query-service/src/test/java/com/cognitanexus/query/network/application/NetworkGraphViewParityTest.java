package com.cognitanexus.query.network.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognitanexus.query.graph.domain.GraphFilterCriteria;
import com.cognitanexus.query.network.domain.ParityDiffReport;
import com.cognitanexus.query.network.infrastructure.NetworkMapper;
import com.cognitanexus.query.network.infrastructure.NetworkMapper.EdgeRow;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PG 与图实现的<b>响应级</b>一致性（100% 逐字段）。
 *
 * <p>与既有 {@code NetworkGraphParityTest} 的区别：那个测试比较领域对象（节点集合、边强度、路径序列），
 * 本测试比较两条实现<b>真正返回给调用方的响应 Map</b>——包括字段集合、空值形态、说明文案、节点
 * 顺序、边顺序与限额回显。业务规则一致但响应形状不同，同样会让前端在切换模式后出问题。
 *
 * <p>覆盖的输入：菱形等长路径、链、星形、非连通、混合拓扑、多篇共同论文的强度、单点（0 跳）、
 * 不存在的端点、深度 1～3 的自我网络，以及带领域/机构筛选的同一批查询。
 *
 * <p>真实 Cypher 与真实表结构的一致性由 {@code NetworkGraphQueryIT} 用两个真实数据库验证；
 * 这里用内存假仓储把「应用层规则与响应装配」单独锁住，故障定位时能区分「规则/装配错」与「Cypher 错」。
 */
class NetworkGraphViewParityTest {

  private final NetworkParityComparator comparator = new NetworkParityComparator();

  @Test
  @DisplayName("菱形等长路径（s-b-t 与 s-c-t）：两条实现取同一条并给出同一响应")
  void diamondParity() {
    assertParity(
        List.of(edge("s", "b", 1), edge("b", "t", 1), edge("s", "c", 1), edge("c", "t", 1)));
  }

  @Test
  @DisplayName("链式图：跳数、路径、深度截止与不可达边界一致")
  void chainParity() {
    assertParity(
        List.of(edge("a", "b", 2), edge("b", "c", 1), edge("c", "d", 3), edge("d", "e", 1)));
  }

  @Test
  @DisplayName("星形图：中心到叶子 1 跳，叶子之间 2 跳且经过中心")
  void starParity() {
    List<EdgeRow> edges = new ArrayList<>();
    for (int i = 1; i <= 6; i++) {
      edges.add(edge("hub", "leaf" + i, i));
    }
    assertParity(edges);
  }

  @Test
  @DisplayName("非连通图：两侧都返回不可达，不编造路径")
  void disconnectedParity() {
    assertParity(
        List.of(edge("a", "b", 1), edge("b", "c", 1), edge("x", "y", 2), edge("y", "z", 1)));
  }

  @Test
  @DisplayName("混合拓扑：多篇共同论文强度、深度边界、等长路径全部一致")
  void mixedTopologyParity() {
    assertParity(
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

  @Test
  @DisplayName("带领域/机构筛选时响应（含回显字段）逐字段一致")
  void filteredQueriesParity() {
    List<EdgeRow> edges = List.of(edge("a", "b", 2), edge("b", "c", 1), edge("a", "c", 1));
    NetworkMapper mapper = NetworkTestFixtures.mapper(edges);
    NetworkService postgres = NetworkTestFixtures.postgres(mapper);
    NetworkGraphQueryService graph = NetworkTestFixtures.graphQueryService(edges, mapper, true);
    GraphFilterCriteria criteria = new GraphFilterCriteria("REAL", "topic-1", "org-1");

    for (String seed : List.of("a", "b", "c")) {
      Map<String, Object> pgView = postgres.neighborhood(seed, 2, 0, "REAL", "topic-1", "org-1");
      Map<String, Object> graphView =
          graph.neighborhood(NetworkTestFixtures.barrier(), seed, 2, 0, criteria);
      ParityDiffReport report =
          comparator.compare("neighborhood", "filtered seed=" + seed, pgView, graphView);
      assertThat(report.matched()).as(report.summary()).isTrue();
      assertThat(graphView.get("topicId")).isEqualTo("topic-1");
      assertThat(graphView.get("organization")).isEqualTo("org-1");
    }
  }

  private void assertParity(List<EdgeRow> edges) {
    NetworkMapper mapper = NetworkTestFixtures.mapper(edges);
    NetworkService postgres = NetworkTestFixtures.postgres(mapper);
    NetworkGraphQueryService graph = NetworkTestFixtures.graphQueryService(edges, mapper, true);
    Set<String> nodes = new LinkedHashSet<>();
    for (EdgeRow edge : edges) {
      nodes.add(edge.scholarA());
      nodes.add(edge.scholarB());
    }

    for (String from : nodes) {
      for (String to : nodes) {
        Map<String, Object> pgView = postgres.shortestPath(from, to, "ALL", null, null);
        Map<String, Object> graphView =
            graph.shortestPath(NetworkTestFixtures.barrier(), from, to, GraphFilterCriteria.all());
        ParityDiffReport report =
            comparator.compare("shortestPath", from + "->" + to, pgView, graphView);
        assertThat(report.matched()).as(report.summary()).isTrue();
      }
    }

    for (String seed : nodes) {
      for (int depth = 1; depth <= 3; depth++) {
        Map<String, Object> pgView = postgres.neighborhood(seed, depth, 0, "ALL", null, null);
        Map<String, Object> graphView =
            graph.neighborhood(
                NetworkTestFixtures.barrier(), seed, depth, 0, GraphFilterCriteria.all());
        ParityDiffReport report =
            comparator.compare(
                "neighborhood", "seed=" + seed + ";depth=" + depth, pgView, graphView);
        assertThat(report.matched()).as(report.summary()).isTrue();
      }
    }
  }

  private static EdgeRow edge(String a, String b, int strength) {
    return NetworkTestFixtures.edge(a, b, strength);
  }
}
