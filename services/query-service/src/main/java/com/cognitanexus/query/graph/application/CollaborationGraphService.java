package com.cognitanexus.query.graph.application;

import com.cognitanexus.query.graph.domain.GraphCollaborationEdge;
import com.cognitanexus.query.graph.domain.GraphFilterCriteria;
import com.cognitanexus.query.graph.domain.GraphPathResult;
import com.cognitanexus.query.graph.domain.GraphScholarNode;
import com.cognitanexus.query.graph.domain.GraphSubgraphResult;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 合作图用例（图库实现）：自我网络与最短合作路径。
 *
 * <p>本类刻意与 {@code NetworkService} 保持<b>同一套规则</b>，因为它替换的是一次查询路径，
 * 不是业务口径：
 *
 * <ul>
 *   <li>自我网络按种子逐层扩边，不把筛选范围内全图读进内存；节点/边超上限返回
 *       {@code GRAPH_SCOPE_TOO_LARGE}，不截断后冒充完整子图；
 *   <li>路径按跳数 BFS，深度上限 6，超时间预算返回 {@code GRAPH_TIMEOUT}；
 *   <li>等长最短路径取规范 ID 序列字典序最小的一条，只返回一条；邻居按 ID 升序访问，
 *       结果与遍历顺序、存储实现无关；
 *   <li>起点与终点相同（0 跳）只有在范围内确实存在合格合作边时才返回可达，不把任何 ID
 *       都当成「自己到自己可达」。
 * </ul>
 *
 * <p>为什么把 BFS 放在应用层而不是写成一条 Cypher {@code shortestPath}：Neo4j 的
 * {@code shortestPath} 在存在多条等长路径时返回哪一条没有稳定约定，而本项目要求同一查询
 * 在任意时刻给出同一条路径。把选择规则留在应用层，既与 PG 实现可逐例比对，也让将来换
 * 华为云 GES 时只需替换邻接扩边，不必重新证明路径规则。
 */
public final class CollaborationGraphService {

  /** 自我网络最大深度，与 {@code NetworkService} 一致。 */
  public static final int MAX_NEIGHBORHOOD_DEPTH = 3;

  /** 最短路径搜索深度上限，与 {@code NetworkService} 一致。 */
  public static final int MAX_PATH_DEPTH = 6;

  private final CollaborationGraphRepository repository;
  private final int maxNodes;
  private final int maxEdges;
  private final long budgetMillis;

  public CollaborationGraphService(
      CollaborationGraphRepository repository, int maxNodes, int maxEdges, long budgetMillis) {
    this.repository = repository;
    this.maxNodes = maxNodes;
    this.maxEdges = maxEdges;
    this.budgetMillis = budgetMillis;
  }

  /** 自我网络：种子在限定深度内的合作子图。参数语义与 PG 版本一致。 */
  public GraphSubgraphResult neighborhood(
      String scholarId, int depth, int maxNodesRequested, GraphFilterCriteria criteria) {
    if (scholarId == null || scholarId.isBlank()) {
      throw BusinessException.invalidArgument("scholarId 不能为空");
    }
    GraphFilterCriteria effective = criteria == null ? GraphFilterCriteria.realOnly() : criteria;
    int depthValue = Math.min(Math.max(depth, 1), MAX_NEIGHBORHOOD_DEPTH);
    int nodeLimit = Math.min(maxNodesRequested <= 0 ? maxNodes : maxNodesRequested, maxNodes);

    Snapshot snapshot = new Snapshot(effective, maxEdges, nodeLimit);
    snapshot.visited.add(scholarId);
    List<String> frontier = List.of(scholarId);
    for (int level = 0; level < depthValue && !frontier.isEmpty(); level++) {
      frontier = snapshot.expand(frontier, true);
    }
    if (!snapshot.adjacency.containsKey(scholarId)) {
      // 与 PG 实现一致：不伪造一个孤立节点，否则界面会显示「有档案但没有合作者」。
      return new GraphSubgraphResult(
          scholarId,
          false,
          depthValue,
          List.of(),
          List.of(),
          snapshot.visited.size(),
          snapshot.loadedEdges.size(),
          "该学者在当前范围内没有合格合作边，可能原因：成果未公开或未生效、关系非 AUTO/SELF_CONFIRMED、" + "或当前筛选条件将其排除。");
    }
    // 收尾：把两端都在深度边界上的边补进来，边界节点不再向外扩展。
    if (!frontier.isEmpty()) {
      snapshot.expand(frontier, false);
    }
    SubGraph subGraph = snapshot.subGraph(scholarId, depthValue);
    if (subGraph == null) {
      throw new BusinessException(
          ErrorCode.GRAPH_SCOPE_TOO_LARGE,
          "以该学者为中心的 " + depthValue + " 跳子图超过节点上限 " + nodeLimit + "，请降低深度或增加领域/机构筛选条件");
    }
    List<GraphScholarNode> nodes = fillMissing(nodesOf(subGraph.nodeIds()), subGraph.nodeIds());
    return new GraphSubgraphResult(
        scholarId,
        true,
        depthValue,
        nodes,
        subGraph.edges(),
        snapshot.visited.size(),
        snapshot.loadedEdges.size(),
        "按种子 " + depthValue + " 跳内实际载入的子图，不是筛选范围的全图");
  }

  /** 最短合作路径。结果包含逐跳证据，规则与 PG 版本逐字一致。 */
  public GraphPathResult shortestPath(
      String from, String to, GraphFilterCriteria criteria, long requestedBudgetMillis) {
    if (from == null || from.isBlank() || to == null || to.isBlank()) {
      throw BusinessException.invalidArgument("起点与终点不能为空");
    }
    GraphFilterCriteria effective = criteria == null ? GraphFilterCriteria.realOnly() : criteria;
    long budget = requestedBudgetMillis <= 0 ? budgetMillis : requestedBudgetMillis;
    long startedAt = System.currentTimeMillis();

    if (from.equals(to)) {
      Snapshot self = new Snapshot(effective, maxEdges, maxNodes);
      self.visited.add(from);
      self.expand(List.of(from), true);
      boolean inScope = self.adjacency.containsKey(from);
      if (!inScope) {
        return GraphPathResult.unreachable(
            "起点在当前范围内没有合格公开合作边（可能成果未公开或未生效、关系非 AUTO/SELF_CONFIRMED、" + "或当前筛选条件将其排除），不按 0 跳可达处理。");
      }
      return new GraphPathResult(true, 0, List.of(from), List.of(), "起点与终点相同，按 0 跳返回。");
    }

    Snapshot fromSide = new Snapshot(effective, maxEdges, maxNodes);
    fromSide.visited.add(from);
    Map<String, Integer> fromSource =
        breadthFirst(fromSide, from, MAX_PATH_DEPTH, startedAt, budget, to);
    if (!fromSource.containsKey(to)) {
      return GraphPathResult.unreachable(
          "在 "
              + MAX_PATH_DEPTH
              + " 跳范围内未找到合作路径；这不等于全图不连通，只说明最短距离超过 "
              + MAX_PATH_DEPTH
              + " 跳。起点或终点若在范围内没有合格合作边，也会得到这个结果。");
    }

    int distance = fromSource.get(to);
    Snapshot toSide = new Snapshot(effective, maxEdges, maxNodes);
    toSide.visited.add(to);
    Map<String, Integer> toTarget =
        breadthFirst(toSide, to, Math.max(distance - 1, 0), startedAt, budget, null);

    List<String> path = new ArrayList<>();
    String current = from;
    path.add(current);
    int guard = 0;
    while (!current.equals(to)) {
      if (++guard > distance + 1) {
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "路径重建未在预期跳数内完成，已放弃返回");
      }
      int nextDistance = fromSource.get(current) + 1;
      String next = null;
      for (String neighbor : new TreeSet<>(fromSide.neighbors(current))) {
        Integer neighborFromSource = fromSource.get(neighbor);
        Integer neighborToTarget = toTarget.get(neighbor);
        if (neighborFromSource == null || neighborToTarget == null) {
          continue;
        }
        if (neighborFromSource == nextDistance
            && neighborFromSource + neighborToTarget == distance) {
          next = neighbor;
          break;
        }
      }
      if (next == null) {
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "路径重建失败：最短路径上的下一跳不存在，图快照与距离不一致");
      }
      path.add(next);
      current = next;
    }

    List<GraphCollaborationEdge> hopEdges = new ArrayList<>();
    for (int i = 0; i < path.size() - 1; i++) {
      GraphCollaborationEdge edge = fromSide.edgeBetween(path.get(i), path.get(i + 1));
      if (edge == null) {
        // 距离表与邻接来自同一个快照，缺边意味着实现有 bug，如实失败而不是返回没有证据的路径。
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "路径重建失败：路径上的合作边缺少证据");
      }
      hopEdges.add(edge);
    }
    return new GraphPathResult(
        true,
        distance,
        path,
        hopEdges,
        "双侧按需扩边至第 " + distance + " 跳，不是筛选范围的全图；" + GraphPathResult.PATH_RULE);
  }

  private Map<String, Integer> breadthFirst(
      Snapshot snapshot, String source, int maxDepth, long startedAt, long budget, String target) {
    Map<String, Integer> distance = new HashMap<>();
    distance.put(source, 0);
    List<String> frontier = List.of(source);
    for (int depth = 1; depth <= maxDepth && !frontier.isEmpty(); depth++) {
      if (System.currentTimeMillis() - startedAt > budget) {
        throw new BusinessException(
            ErrorCode.GRAPH_TIMEOUT, "路径计算超过时间预算 " + budget + " 毫秒，请增加领域或机构筛选条件以缩小子图范围");
      }
      List<String> discovered = snapshot.expand(frontier, true);
      for (String id : discovered) {
        distance.putIfAbsent(id, depth);
      }
      frontier = discovered;
      if (target != null && distance.containsKey(target)) {
        break;
      }
    }
    return distance;
  }

  private List<GraphScholarNode> nodesOf(Collection<String> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    Map<String, GraphScholarNode> found = new LinkedHashMap<>();
    for (GraphScholarNode node : repository.findScholars(ids)) {
      found.put(node.scholarId(), node);
    }
    return new ArrayList<>(found.values());
  }

  /** 图中存在合作边但学者节点属性缺失时补齐最小节点：不能因为缺档案就让节点从子图里消失。 */
  private List<GraphScholarNode> fillMissing(
      List<GraphScholarNode> found, Collection<String> requested) {
    Map<String, GraphScholarNode> byId = new LinkedHashMap<>();
    for (GraphScholarNode node : found) {
      byId.put(node.scholarId(), node);
    }
    List<GraphScholarNode> result = new ArrayList<>();
    for (String id : requested) {
      GraphScholarNode node = byId.get(id);
      result.add(node == null ? GraphScholarNode.identified(id, 0L) : node);
    }
    return result;
  }

  /** 一次查询内的局部快照：邻接表不可原地修改，返回结果后即丢弃。 */
  private final class Snapshot {

    private final GraphFilterCriteria criteria;
    private final int edgeLimit;
    private final int nodeLimit;
    private final Set<String> visited = new LinkedHashSet<>();
    private final Map<String, Map<String, GraphCollaborationEdge>> adjacency = new HashMap<>();
    private final Map<String, GraphCollaborationEdge> loadedEdges = new LinkedHashMap<>();

    private Snapshot(GraphFilterCriteria criteria, int edgeLimit, int nodeLimit) {
      this.criteria = criteria;
      this.edgeLimit = edgeLimit;
      this.nodeLimit = nodeLimit;
    }

    private Set<String> neighbors(String scholarId) {
      Map<String, GraphCollaborationEdge> row = adjacency.get(scholarId);
      return row == null ? Set.of() : row.keySet();
    }

    private GraphCollaborationEdge edgeBetween(String left, String right) {
      Map<String, GraphCollaborationEdge> row = adjacency.get(left);
      return row == null ? null : row.get(right);
    }

    private List<String> expand(Collection<String> frontier, boolean allowNewNodes) {
      List<String> discovered = new ArrayList<>();
      List<String> ids = new ArrayList<>(new LinkedHashSet<>(frontier));
      if (ids.isEmpty()) {
        return discovered;
      }
      int remaining = Math.max(edgeLimit - loadedEdges.size(), 0);
      CollaborationGraphRepository.Expansion expansion =
          repository.expand(ids, criteria, remaining + 1);
      if (expansion.truncated()) {
        throw new BusinessException(
            ErrorCode.GRAPH_SCOPE_TOO_LARGE, "给定范围内的合作边超过上限 " + edgeLimit + " 条，请增加领域或机构筛选条件");
      }
      for (GraphCollaborationEdge edge : expansion.edges()) {
        absorb(edge, allowNewNodes, discovered);
      }
      if (visited.size() > nodeLimit) {
        throw new BusinessException(
            ErrorCode.GRAPH_SCOPE_TOO_LARGE,
            "以给定起点为中心的合作子图含学者超过节点上限 " + nodeLimit + "，请降低深度，或增加领域/机构筛选条件");
      }
      if (loadedEdges.size() > edgeLimit) {
        throw new BusinessException(
            ErrorCode.GRAPH_SCOPE_TOO_LARGE, "给定范围内的合作边超过上限 " + edgeLimit + " 条，请增加领域或机构筛选条件");
      }
      return discovered;
    }

    private void absorb(
        GraphCollaborationEdge edge, boolean allowNewNodes, List<String> discovered) {
      String a = edge.scholarA();
      String b = edge.scholarB();
      boolean aKnown = visited.contains(a);
      boolean bKnown = visited.contains(b);
      if (!allowNewNodes && !(aKnown && bKnown)) {
        return;
      }
      if (!aKnown) {
        visited.add(a);
        discovered.add(a);
      }
      if (!bKnown) {
        visited.add(b);
        discovered.add(b);
      }
      GraphCollaborationEdge existing = loadedEdges.get(edge.pairKey());
      if (existing == null) {
        loadedEdges.put(edge.pairKey(), edge);
        adjacency.computeIfAbsent(a, key -> new LinkedHashMap<>()).put(b, edge);
        adjacency.computeIfAbsent(b, key -> new LinkedHashMap<>()).put(a, edge);
      } else {
        GraphCollaborationEdge merged = GraphCollaborationEdge.merge(existing, edge);
        loadedEdges.put(merged.pairKey(), merged);
        adjacency.computeIfAbsent(a, key -> new LinkedHashMap<>()).put(b, merged);
        adjacency.computeIfAbsent(b, key -> new LinkedHashMap<>()).put(a, merged);
      }
    }

    private SubGraph subGraph(String seed, int depth) {
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
            if (order.size() > nodeLimit) {
              return null;
            }
          }
        }
      }
      Set<String> selected = new LinkedHashSet<>(order);
      List<GraphCollaborationEdge> edges = new ArrayList<>();
      for (String node : order) {
        for (String neighbor : neighbors(node)) {
          GraphCollaborationEdge edge = edgeBetween(node, neighbor);
          if (selected.contains(neighbor) && node.compareTo(neighbor) < 0 && edge != null) {
            edges.add(edge);
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
  }

  private record SubGraph(List<String> nodeIds, List<GraphCollaborationEdge> edges) {}
}
