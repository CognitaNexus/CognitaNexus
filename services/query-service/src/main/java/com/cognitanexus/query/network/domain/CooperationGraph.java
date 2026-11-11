package com.cognitanexus.query.network.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 合作图的不可变快照。
 *
 * <p>依据：架构 V0.2 §7.1「合作图内存对象不可原地修改，按图版本建立不可变快照；不能把旧邻接表
 * 标成新版本」。
 *
 * <p>因此本类型在构造时把邻接表完整复制成只读结构，之后不提供任何修改方法。这样做不是为了
 * 「防御性编程」，而是为了消除一个具体错误：如果邻接表可原地修改，一个正在计算最短路径的请求
 * 与一次图更新并发时，路径结果会来自两个不同版本的混合图，而返回的 graphVersion 只有一个——
 * 调用方无法知道这条路径对应哪个时刻。
 *
 * <p>邻居按规范 ID 升序存放（{@link String#compareTo}），使 BFS 的输出与遍历顺序无关：
 * 「等长路径取 ID 序列字典序最小的一条」要求邻居访问顺序固定，用哈希集合的顺序会让同样的
 * 查询在不同 JVM 运行中给出不同路径。
 */
public final class CooperationGraph {

  private final String graphVersion;
  private final Map<String, Set<String>> adjacency;
  private final Map<String, Long> strengths;
  private final int edgeCount;

  private CooperationGraph(
      String graphVersion,
      Map<String, Set<String>> adjacency,
      Map<String, Long> strengths,
      int edgeCount) {
    this.graphVersion = graphVersion;
    this.adjacency = adjacency;
    this.strengths = strengths;
    this.edgeCount = edgeCount;
  }

  /** 由边集合构建不可变快照。 */
  public static CooperationGraph of(String graphVersion, Collection<CooperationEdge> edges) {
    Map<String, Set<String>> adjacency = new HashMap<>();
    Map<String, Long> strengths = new HashMap<>();
    int counted = 0;
    for (CooperationEdge edge : edges) {
      addNeighbor(adjacency, edge.scholarA(), edge.scholarB());
      addNeighbor(adjacency, edge.scholarB(), edge.scholarA());
      strengths.merge(edgeKey(edge.scholarA(), edge.scholarB()), edge.strength(), Long::sum);
      counted++;
    }
    Map<String, Set<String>> frozen = new HashMap<>(adjacency.size());
    adjacency.forEach(
        (node, neighbors) -> frozen.put(node, java.util.Collections.unmodifiableSet(neighbors)));
    return new CooperationGraph(
        graphVersion,
        java.util.Collections.unmodifiableMap(frozen),
        java.util.Collections.unmodifiableMap(strengths),
        counted);
  }

  public String graphVersion() {
    return graphVersion;
  }

  public int nodeCount() {
    return adjacency.size();
  }

  public int edgeCount() {
    return edgeCount;
  }

  public Set<String> nodes() {
    return adjacency.keySet();
  }

  /** 邻居；不存在该节点时返回空集合，不抛异常——「孤立学者」是合法状态。 */
  public Set<String> neighbors(String scholarId) {
    Set<String> neighbors = adjacency.get(scholarId);
    return neighbors == null ? Set.of() : neighbors;
  }

  public boolean contains(String scholarId) {
    return adjacency.containsKey(scholarId);
  }

  public long strength(String left, String right) {
    Long value = strengths.get(edgeKey(left, right));
    return value == null ? 0L : value;
  }

  /**
   * 从种子节点出发、限定深度的自我网络。
   *
   * <p>返回 nodes 与 edges 两个列表；节点顺序按 BFS 层次与 ID 稳定输出，使响应可比对。
   * 超出节点上限时返回 null，由调用方转成明确的范围过大错误，而不是截断后返回半个子图。
   */
  public SubGraph neighborhood(String seed, int depth, int maxNodes) {
    if (!contains(seed)) {
      return new SubGraph(List.of(), List.of());
    }
    List<String> order = new ArrayList<>();
    Map<String, Integer> level = new HashMap<>();
    java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
    order.add(seed);
    level.put(seed, 0);
    queue.add(seed);
    while (!queue.isEmpty()) {
      String current = queue.poll();
      int currentLevel = level.get(current);
      if (currentLevel >= depth) {
        continue;
      }
      for (String neighbor : neighbors(current)) {
        if (!level.containsKey(neighbor)) {
          level.put(neighbor, currentLevel + 1);
          order.add(neighbor);
          queue.add(neighbor);
          if (order.size() > maxNodes) {
            return null;
          }
        }
      }
    }
    Set<String> selected = new LinkedHashSet<>(order);
    List<CooperationEdge> edges = new ArrayList<>();
    for (String node : order) {
      for (String neighbor : neighbors(node)) {
        if (selected.contains(neighbor) && node.compareTo(neighbor) < 0) {
          edges.add(new CooperationEdge(node, neighbor, strength(node, neighbor)));
        }
      }
    }
    edges.sort(
        (left, right) -> {
          int byA = left.scholarA().compareTo(right.scholarA());
          return byA != 0 ? byA : left.scholarB().compareTo(right.scholarB());
        });
    return new SubGraph(order, edges);
  }

  private static void addNeighbor(Map<String, Set<String>> adjacency, String from, String to) {
    adjacency.computeIfAbsent(from, key -> new TreeSet<>()).add(to);
  }

  private static String edgeKey(String left, String right) {
    return left.compareTo(right) <= 0 ? left + "\u0001" + right : right + "\u0001" + left;
  }

  /** 子图结果。越界情形由调用方转成明确错误，因此结果本身不需要「已截断」标记。 */
  public record SubGraph(List<String> nodeIds, List<CooperationEdge> edges) {

    public SubGraph {
      nodeIds = List.copyOf(nodeIds);
      edges = List.copyOf(edges);
    }
  }

  /** 一条无向合作边及强度（不同合格论文数）。 */
  public record CooperationEdge(String scholarA, String scholarB, long strength) {}
}
