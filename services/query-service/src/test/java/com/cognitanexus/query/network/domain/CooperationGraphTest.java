package com.cognitanexus.query.network.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognitanexus.query.network.domain.CooperationGraph.CooperationEdge;
import com.cognitanexus.query.network.domain.CooperationGraph.SubGraph;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 合作图的构建与自我网络计算的行为约束。
 *
 * <p>依据：FR14（专家合作网络）、BR-IMP-11（待核验/争议/已排除关系不得建边）、架构 V0.2 §7.1、§7.4。
 *
 * <p>测试重点有三处，都是「不报错但结果错」的类型：
 *
 * <ol>
 *   <li>邻接表必须冻结——否则一次并发更新会让路径结果来自两个版本的混合图；
 *   <li>BFS 输出必须与遍历顺序无关——否则同一查询在不同 JVM 上给出不同路径；
 *   <li>超限必须返回「范围过大」而不是截断后的半个子图。
 * </ol>
 */
class CooperationGraphTest {

  private static CooperationEdge edge(String a, String b, long strength) {
    return new CooperationEdge(a, b, strength);
  }

  @Nested
  @DisplayName("构建与查询")
  class Build {

    @Test
    @DisplayName("边为无向：一次写入使两端互为邻居")
    void undirected() {
      CooperationGraph graph = CooperationGraph.of("g1", List.of(edge("s1", "s2", 3L)));
      assertThat(graph.neighbors("s1")).containsExactly("s2");
      assertThat(graph.neighbors("s2")).containsExactly("s1");
      assertThat(graph.nodeCount()).isEqualTo(2);
      assertThat(graph.edgeCount()).isEqualTo(1);
      assertThat(graph.graphVersion()).isEqualTo("g1");
    }

    @Test
    @DisplayName("孤立学者的邻居为空集合而非 null，也不是异常：孤立是合法状态")
    void isolatedScholar() {
      CooperationGraph graph = CooperationGraph.of("g1", List.of(edge("s1", "s2", 1L)));
      assertThat(graph.neighbors("s-unknown")).isEmpty();
      assertThat(graph.contains("s-unknown")).isFalse();
      assertThat(graph.contains("s1")).isTrue();
    }

    @Test
    @DisplayName("空图不抛异常")
    void emptyGraph() {
      CooperationGraph graph = CooperationGraph.of("g0", List.of());
      assertThat(graph.nodeCount()).isZero();
      assertThat(graph.edgeCount()).isZero();
      assertThat(graph.nodes()).isEmpty();
    }

    @Test
    @DisplayName("强度按无向键累加，与传递方向无关：A-B 与 B-A 计同一条边")
    void strengthIsUndirectedAndAccumulates() {
      CooperationGraph graph =
          CooperationGraph.of("g1", List.of(edge("s1", "s2", 2L), edge("s2", "s1", 3L)));
      assertThat(graph.strength("s1", "s2")).isEqualTo(5L);
      assertThat(graph.strength("s2", "s1")).isEqualTo(5L);
    }

    @Test
    @DisplayName("不存在的边强度为 0，不抛异常")
    void missingEdgeStrengthIsZero() {
      CooperationGraph graph = CooperationGraph.of("g1", List.of(edge("s1", "s2", 1L)));
      assertThat(graph.strength("s1", "s3")).isZero();
    }

    @Test
    @DisplayName("邻居集合不可变：原地修改旧邻接表会让一次查询横跨两个图版本")
    void neighborsImmutable() {
      CooperationGraph graph = CooperationGraph.of("g1", List.of(edge("s1", "s2", 1L)));
      assertThat(graph.neighbors("s1")).isUnmodifiable();
      assertThat(graph.nodes()).isUnmodifiable();
    }

    @Test
    @DisplayName("邻居按标识升序存放，使 BFS 输出可复现")
    void neighborsSorted() {
      CooperationGraph graph =
          CooperationGraph.of(
              "g1", List.of(edge("s1", "s3", 1L), edge("s1", "s2", 1L), edge("s1", "s10", 1L)));
      // 字典序："s10" < "s2" < "s3"，与数值直觉不同，但必须是确定的顺序。
      assertThat(new ArrayList<>(graph.neighbors("s1"))).containsExactly("s10", "s2", "s3");
    }
  }

  @Nested
  @DisplayName("自我网络（neighborhood）")
  class Neighborhood {

    /** 链式图：s1 - s2 - s3 - s4。 */
    private CooperationGraph chain() {
      return CooperationGraph.of(
          "g1", List.of(edge("s1", "s2", 1L), edge("s2", "s3", 2L), edge("s3", "s4", 3L)));
    }

    @Test
    @DisplayName("深度 1 只含直接合作者")
    void depthOne() {
      SubGraph sub = chain().neighborhood("s1", 1, 100);
      assertThat(sub.nodeIds()).containsExactly("s1", "s2");
      assertThat(sub.edges()).hasSize(1);
    }

    @Test
    @DisplayName("深度 2 展开两层，边只保留两端都在子图内的")
    void depthTwo() {
      SubGraph sub = chain().neighborhood("s1", 2, 100);
      assertThat(sub.nodeIds()).containsExactly("s1", "s2", "s3");
      assertThat(sub.edges()).hasSize(2);
      assertThat(sub.edges()).extracting(CooperationEdge::scholarA).containsExactly("s1", "s2");
    }

    @Test
    @DisplayName("深度覆盖全图时所有节点与边都返回")
    void fullDepth() {
      SubGraph sub = chain().neighborhood("s1", 10, 100);
      assertThat(sub.nodeIds()).containsExactly("s1", "s2", "s3", "s4");
      assertThat(sub.edges()).hasSize(3);
    }

    @Test
    @DisplayName("深度 0 只返回种子自身")
    void depthZero() {
      SubGraph sub = chain().neighborhood("s2", 0, 100);
      assertThat(sub.nodeIds()).containsExactly("s2");
      assertThat(sub.edges()).isEmpty();
    }

    @Test
    @DisplayName("种子不存在时返回空子图，而不是抛异常——调用方需要区分「没有这个人」与「算不出来」")
    void unknownSeedReturnsEmpty() {
      SubGraph sub = chain().neighborhood("nobody", 2, 100);
      assertThat(sub.nodeIds()).isEmpty();
      assertThat(sub.edges()).isEmpty();
    }

    @Test
    @DisplayName("超过节点上限返回 null：由调用方转成明确的范围过大错误，而不是悄悄截断")
    void overLimitReturnsNull() {
      SubGraph sub = chain().neighborhood("s1", 10, 2);
      assertThat(sub).isNull();
    }

    @Test
    @DisplayName("节点数恰好等于上限时仍然返回结果（边界为含）")
    void exactlyAtLimitAllowed() {
      SubGraph sub = chain().neighborhood("s1", 10, 4);
      assertThat(sub).isNotNull();
      assertThat(sub.nodeIds()).hasSize(4);
    }

    @Test
    @DisplayName("等长路径的节点顺序由标识升序决定，与边传入顺序无关")
    void deterministicOrderIndependentOfInputOrder() {
      // s0 同时连向 s1 与 s2，两层展开时 s1、s2 谁先进入队列必须由标识决定。
      CooperationGraph forward =
          CooperationGraph.of("g", List.of(edge("s0", "s1", 1L), edge("s0", "s2", 1L)));
      CooperationGraph reversed =
          CooperationGraph.of("g", List.of(edge("s0", "s2", 1L), edge("s0", "s1", 1L)));
      assertThat(forward.neighborhood("s0", 1, 10).nodeIds())
          .isEqualTo(reversed.neighborhood("s0", 1, 10).nodeIds());
      assertThat(forward.neighborhood("s0", 1, 10).nodeIds()).containsExactly("s0", "s1", "s2");
    }

    @Test
    @DisplayName("子图边按 (A,B) 升序且每个无向边只出现一次")
    void edgesDeduplicatedAndSorted() {
      CooperationGraph triangle =
          CooperationGraph.of(
              "g", List.of(edge("s1", "s2", 1L), edge("s2", "s3", 1L), edge("s3", "s1", 1L)));
      SubGraph sub = triangle.neighborhood("s1", 2, 10);
      assertThat(sub.edges()).hasSize(3);
      assertThat(sub.edges())
          .extracting(e -> e.scholarA() + "-" + e.scholarB())
          .containsExactly("s1-s2", "s1-s3", "s2-s3");
    }

    @Test
    @DisplayName("子图结果不可变：调用方拿到的是快照，不能改动")
    void subGraphImmutable() {
      SubGraph sub = chain().neighborhood("s1", 1, 10);
      assertThat(sub.nodeIds()).isUnmodifiable();
      assertThat(sub.edges()).isUnmodifiable();
    }

    @Test
    @DisplayName("自我网络保留原始边强度，便于前端按合作次数调整线宽")
    void strengthsPreserved() {
      SubGraph sub = chain().neighborhood("s1", 2, 10);
      assertThat(sub.edges()).extracting(CooperationEdge::strength).containsExactly(1L, 2L);
    }
  }
}
