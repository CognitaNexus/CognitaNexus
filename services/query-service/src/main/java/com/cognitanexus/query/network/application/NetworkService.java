package com.cognitanexus.query.network.application;

import com.cognitanexus.query.consistency.PublicBarrierGuard;
import com.cognitanexus.query.network.domain.CooperationGraph;
import com.cognitanexus.query.network.domain.CooperationGraph.CooperationEdge;
import com.cognitanexus.query.network.infrastructure.NetworkMapper;
import com.cognitanexus.query.platform.scope.DataNatureScope;
import com.cognitanexus.query.projection.application.ProjectionProperties;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 合作网络用例。
 *
 * <p>依据：架构 V0.2 §9.3。
 *
 * <p>范围与预算规则（不满足时明确失败，不截断）：
 *
 * <ul>
 *   <li>子图节点上限与边上限来自配置，超限返回 {@code GRAPH_SCOPE_TOO_LARGE}；
 *   <li>路径计算有时间预算，超时返回 {@code GRAPH_TIMEOUT}；
 *   <li>最短路径按跳数 BFS，邻居按稳定 ID 升序访问，等长时取 ID 序列字典序最小的一条，
 *       且只返回一条，不枚举全部等长路径。
 * </ul>
 *
 * <p>为什么坚持「明确失败」而不是截断：截断后图仍然是连通的假象，BFS 会给出「无路径」，
 * 而真实原因是有边被丢掉了。用户无法区分「确实不合作」与「系统只算了一部分」。
 */
@Service
public class NetworkService {

  private static final Logger log = LoggerFactory.getLogger(NetworkService.class);

  /** 图规则版本。建边口径或路径选择规则变化时提升。 */
  public static final String RULE_VERSION = "NETWORK_V1";

  private static final String NATURE_ALL = "ALL";
  private static final int MAX_DEPTH = 3;

  private final NetworkMapper mapper;
  private final PublicBarrierGuard guard;
  private final ProjectionProperties properties;

  public NetworkService(
      NetworkMapper mapper, PublicBarrierGuard guard, ProjectionProperties properties) {
    this.mapper = mapper;
    this.guard = guard;
    this.properties = properties;
  }

  /** 自我网络：某学者限定深度内的合作子图。 */
  public Map<String, Object> neighborhood(
      String scholarId,
      int depth,
      int maxNodesRequested,
      String dataNature,
      String topicId,
      String organization) {
    PublicBarrierGuard.BarrierSnapshot barrier = guard.assertPubliclyServed();
    String generation = barrier.activeGeneration();
    String nature = normalizeNature(dataNature);
    int depthValue = Math.min(Math.max(depth, 1), MAX_DEPTH);
    int maxNodes =
        Math.min(
            maxNodesRequested <= 0 ? properties.graphMaxNodes() : maxNodesRequested,
            properties.graphMaxNodes());

    CooperationGraph graph =
        loadGraph(generation, nature, topicId, organization, properties.graphMaxEdges());
    if (!graph.contains(scholarId)) {
      // 该学者不在子图内：可能是未公开、关系不合格，或筛选条件把他排除了。如实返回空图并说明，
      // 不伪造一个「他自己」的孤立节点——那会让人以为他有档案但没合作者。
      Map<String, Object> data = emptyGraph(barrier, nature, topicId, organization);
      data.put("seed", scholarId);
      data.put("seedInScope", false);
      data.put("note", "该学者在当前范围内没有合格合作边，可能原因：成果未公开或未生效、关系非 AUTO/SELF_CONFIRMED、" + "或当前筛选条件将其排除。");
      return data;
    }
    CooperationGraph.SubGraph subGraph = graph.neighborhood(scholarId, depthValue, maxNodes);
    if (subGraph == null) {
      throw new BusinessException(
          ErrorCode.GRAPH_SCOPE_TOO_LARGE,
          "以该学者为中心的 " + depthValue + " 跳子图超过节点上限 " + maxNodes + "，请降低深度或增加领域/机构筛选条件");
    }
    List<String> ids = subGraph.nodeIds();
    Map<String, Object> data = base(barrier, nature, topicId, organization);
    data.put("seed", scholarId);
    data.put("seedInScope", true);
    data.put("depth", depthValue);
    data.put("nodes", nodeViews(generation, ids));
    List<Map<String, Object>> edges = new ArrayList<>();
    for (CooperationEdge edge : subGraph.edges()) {
      edges.add(edgeView(edge));
    }
    data.put("edges", edges);
    data.put("withinLimits", Map.of("nodes", graph.nodeCount(), "edges", graph.edgeCount()));
    return data;
  }

  /**
   * 最短合作路径（IN01）。
   *
   * <p>两次 BFS 分别得到源到各点与各点到目标的跳数，随后从源出发贪心取「跳数单调递增且仍在
   * 最短路径上」的最小 ID 邻居。这样得到的是所有最短路径中 ID 序列字典序最小的一条，
   * 并且与邻居的遍历顺序无关——单纯按 BFS 首次触达重建路径只能保证「一条最短路径」，
   * 不同运行可能给出不同结果。
   */
  public Map<String, Object> shortestPath(
      String from, String to, String dataNature, String topicId, String organization) {
    PublicBarrierGuard.BarrierSnapshot barrier = guard.assertPubliclyServed();
    long startedAt = System.currentTimeMillis();
    String generation = barrier.activeGeneration();
    String nature = normalizeNature(dataNature);

    CooperationGraph graph =
        loadGraph(generation, nature, topicId, organization, properties.graphMaxEdges());
    Map<String, Object> data = base(barrier, nature, topicId, organization);
    data.put("from", from);
    data.put("to", to);

    if (from.equals(to)) {
      data.put("reachable", true);
      data.put("hops", 0);
      data.put("path", List.of(from));
      data.put("note", "起点与终点相同，按 0 跳返回。");
      return data;
    }
    if (!graph.contains(from) || !graph.contains(to)) {
      data.put("reachable", false);
      data.put("hops", null);
      data.put("path", List.of());
      data.put(
          "note",
          "起点或终点在当前范围内没有合格合作边，因此不存在路径。" + "「无路径」与「超出范围」是两件事：后者会返回 GRAPH_SCOPE_TOO_LARGE 而不是这里的结果。");
      return data;
    }

    Map<String, Integer> fromSource = breadthFirst(graph, from, startedAt);
    Map<String, Integer> toTarget = breadthFirst(graph, to, startedAt);
    Integer distance = fromSource.get(to);
    if (distance == null) {
      data.put("reachable", false);
      data.put("hops", null);
      data.put("path", List.of());
      data.put("note", "在给定筛选范围内两点不连通。");
      return data;
    }

    List<String> path = new ArrayList<>();
    String current = from;
    path.add(current);
    int guardCounter = 0;
    while (!current.equals(to)) {
      if (++guardCounter > distance + 1) {
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "路径重建未在预期跳数内完成，已放弃返回");
      }
      int nextDistance = fromSource.get(current) + 1;
      String next = null;
      for (String neighbor : graph.neighbors(current)) {
        Integer neighborFromSource = fromSource.get(neighbor);
        Integer neighborToTarget = toTarget.get(neighbor);
        if (neighborFromSource == null || neighborToTarget == null) {
          continue;
        }
        // 仍在最短路径上：源侧跳数恰好递增，且剩余跳数可接到目标
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

    data.put("reachable", true);
    data.put("hops", distance);
    data.put("path", path);
    data.put("pathNodes", nodeViews(generation, path));
    data.put("withinLimits", Map.of("nodes", graph.nodeCount(), "edges", graph.edgeCount()));
    data.put("pathRule", "跳数最少；等长时取规范 ID 序列字典序最小的一条；只返回一条，不枚举全部等长路径");
    return data;
  }

  /** BFS 距离；超过时间预算抛 GRAPH_TIMEOUT，不返回部分结果。 */
  private Map<String, Integer> breadthFirst(CooperationGraph graph, String source, long startedAt) {
    Map<String, Integer> distance = new HashMap<>();
    ArrayDeque<String> queue = new ArrayDeque<>();
    distance.put(source, 0);
    queue.add(source);
    while (!queue.isEmpty()) {
      if (System.currentTimeMillis() - startedAt > properties.graphBudgetMillis()) {
        throw new BusinessException(
            ErrorCode.GRAPH_TIMEOUT,
            "路径计算超过时间预算 " + properties.graphBudgetMillis() + " 毫秒，请增加领域或机构筛选条件以缩小子图范围");
      }
      String current = queue.poll();
      for (String neighbor : graph.neighbors(current)) {
        if (!distance.containsKey(neighbor)) {
          distance.put(neighbor, distance.get(current) + 1);
          queue.add(neighbor);
        }
      }
    }
    return distance;
  }

  /**
   * 载入子图。
   *
   * <p>先数节点再取边：节点数超限直接失败，避免把边表整个读进内存后才发现范围过大。取边时多取
   * 一行用于判断是否越界（{@code limit + 1}），因为「刚好等于上限」与「超过上限」必须区分开。
   */
  private CooperationGraph loadGraph(
      String generation, String nature, String topicId, String organization, int maxEdges) {
    long nodes = mapper.countNodes(generation, nature, topicId, organization);
    if (nodes > properties.graphMaxNodes()) {
      throw new BusinessException(
          ErrorCode.GRAPH_SCOPE_TOO_LARGE,
          "当前筛选范围内的合作子图含 " + nodes + " 个学者，超过节点上限 " + properties.graphMaxNodes() + "，请增加领域或机构筛选条件");
    }
    List<NetworkMapper.EdgeRow> rows =
        mapper.listEdges(generation, nature, topicId, organization, maxEdges + 1);
    if (rows.size() > maxEdges) {
      throw new BusinessException(
          ErrorCode.GRAPH_SCOPE_TOO_LARGE, "当前筛选范围内的合作边超过上限 " + maxEdges + " 条，请增加领域或机构筛选条件");
    }
    List<CooperationEdge> edges = new ArrayList<>(rows.size());
    for (NetworkMapper.EdgeRow row : rows) {
      edges.add(new CooperationEdge(row.scholarA(), row.scholarB(), row.strength()));
    }
    log.debug("载入合作子图 generation={} 节点={} 边={}", generation, nodes, edges.size());
    return CooperationGraph.of("g" + generation, edges);
  }

  private List<Map<String, Object>> nodeViews(String generation, List<String> ids) {
    List<Map<String, Object>> nodes = new ArrayList<>();
    if (ids.isEmpty()) {
      return nodes;
    }
    Map<String, NetworkMapper.ScholarNameRow> names = new LinkedHashMap<>();
    for (NetworkMapper.ScholarNameRow row : mapper.listScholarNames(generation, ids)) {
      names.put(row.scholarId(), row);
    }
    for (String id : ids) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("scholarId", id);
      NetworkMapper.ScholarNameRow row = names.get(id);
      view.put("scholarName", row == null ? null : row.scholarName());
      view.put("qualifiedAchievementCount", row == null ? 0L : row.achievementCount());
      nodes.add(view);
    }
    return nodes;
  }

  private static Map<String, Object> edgeView(CooperationEdge edge) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("source", edge.scholarA());
    view.put("target", edge.scholarB());
    view.put("strength", edge.strength());
    return view;
  }

  private Map<String, Object> emptyGraph(
      PublicBarrierGuard.BarrierSnapshot barrier,
      String nature,
      String topicId,
      String organization) {
    Map<String, Object> data = base(barrier, nature, topicId, organization);
    data.put("nodes", List.of());
    data.put("edges", List.of());
    return data;
  }

  private Map<String, Object> base(
      PublicBarrierGuard.BarrierSnapshot barrier,
      String nature,
      String topicId,
      String organization) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("graphVersion", "g" + barrier.activeGeneration() + "-r" + barrier.publishedRevision());
    data.put("ruleVersion", RULE_VERSION);
    data.put("publishedRevision", barrier.publishedRevision());
    data.put("generatedAt", Instant.now());
    data.put("dataNatureScope", nature == null ? NATURE_ALL : nature);
    data.put("topicId", topicId);
    data.put("organization", organization);
    data.put(
        "limits",
        Map.of(
            "maxNodes",
            properties.graphMaxNodes(),
            "maxEdges",
            properties.graphMaxEdges(),
            "budgetMillis",
            properties.graphBudgetMillis()));
    data.put(
        "edgeRule",
        "仅真实、公开、生效、关系合格（AUTO / SELF_CONFIRMED）的共同论文建边；" + "待核验/争议/已排除关系不参与确定合作；边强度为不同合格论文数");
    return data;
  }

  /** 委托共享规则，不在此另立一份：同一判据写两遍，迟早会有一处被改反。 */
  private static String normalizeNature(String raw) {
    return DataNatureScope.normalize(raw);
  }
}
