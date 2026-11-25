package com.cognitanexus.query.graph.domain;

import java.util.List;

/**
 * 以某位学者为种子的自我网络快照。
 *
 * <p>语义与 {@code NetworkService.neighborhood} 一致：
 *
 * <ul>
 *   <li>{@code seedInScope=false} 表示该学者在当前范围内没有合格合作边。这不是「查无此人」，
 *       而是「不能确定他有合作」——可能成果未公开、关系未确认，或被筛选条件排除；
 *   <li>{@code nodes} 按 BFS 层次与规范 ID 稳定输出，{@code edges} 按两端 ID 排序，使相同查询
 *       的响应可直接比对；
 *   <li>超出节点/边上限时由调用方转成明确失败（{@code GRAPH_SCOPE_TOO_LARGE}），绝不在结果里
 *       留一个「已截断」的半个子图——截断后的图仍然连通，BFS 会给出「无路径」的错误结论。
 * </ul>
 */
public record GraphSubgraphResult(
    String seed,
    boolean seedInScope,
    int depth,
    List<GraphScholarNode> nodes,
    List<GraphCollaborationEdge> edges,
    int visitedNodes,
    int loadedEdges,
    String note) {

  public GraphSubgraphResult {
    nodes = nodes == null ? List.of() : List.copyOf(nodes);
    edges = edges == null ? List.of() : List.copyOf(edges);
    note = note == null ? "" : note;
    if (seedInScope
        && !nodes.isEmpty()
        && nodes.stream().noneMatch(n -> n.scholarId().equals(seed))) {
      throw new IllegalArgumentException("seedInScope=true 时节点列表必须包含种子学者：" + seed);
    }
  }
}
