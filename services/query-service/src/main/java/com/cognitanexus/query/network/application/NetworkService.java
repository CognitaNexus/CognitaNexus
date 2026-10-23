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
 *   <li>图按种子逐层扩边加载（{@link ScopedGraphLoader}），不把筛选范围内的全图读进内存；
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

  /**
   * 最短路径的搜索深度上限。
   *
   * <p>合作网络的经验直径在 6 跳以内；给一个明确上限是为了让「查不到」有一个可解释的边界，
   * 而不是在超大网络上无界展开。超出时如实说明「在 N 跳内不连通」，不冒充「全图不连通」。
   */
  private static final int MAX_PATH_DEPTH = 6;

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

    ScopedGraphLoader loader =
        new ScopedGraphLoader(
            mapper,
            generation,
            nature,
            topicId,
            organization,
            properties.graphMaxEdges(),
            maxNodes);
    loader.addSeed(scholarId);
    List<String> frontier = List.of(scholarId);
    for (int level = 0; level < depthValue && !frontier.isEmpty(); level++) {
      frontier = loader.expand(frontier, true);
    }
    if (!loader.contains(scholarId)) {
      // 该学者不在子图内：可能是未公开、关系不合格，或筛选条件把他排除了。如实返回空图并说明，
      // 不伪造一个「他自己」的孤立节点——那会让人以为他有档案但没合作者。
      Map<String, Object> data = emptyGraph(barrier, nature, topicId, organization);
      data.put("seed", scholarId);
      data.put("seedInScope", false);
      data.put("note", "该学者在当前范围内没有合格合作边，可能原因：成果未公开或未生效、关系非 AUTO/SELF_CONFIRMED、" + "或当前筛选条件将其排除。");
      return data;
    }
    // 收尾：把「两端都在深度边界上」的边补进来。边界节点不再向外扩展，否则子图会越过声明的深度。
    if (!frontier.isEmpty()) {
      loader.expand(frontier, false);
    }
    CooperationGraph graph = loader.snapshot("g" + generation);
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
    data.put("withinLimits", Map.of("nodes", loader.nodeCount(), "edges", loader.edgeCount()));
    data.put("scope", "按种子 " + depthValue + " 跳内实际载入的子图，不是筛选范围的全图");
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
    Map<String, Object> data = base(barrier, nature, topicId, organization);
    data.put("from", from);
    data.put("to", to);

    if (from.equals(to)) {
      // 零跳不等于「任何字符串等于自己就是可达路径」：必须确认这个人确实在当前范围内有合格
      // 公开合作边。否则随便传一个不存在的 ID 也会得到 reachable=true，前端随后在
      // path.pathNodes 上取节点时拿到空列表，把「查无此人」显示成「他自己」。
      ScopedGraphLoader selfLoader =
          new ScopedGraphLoader(
              mapper,
              generation,
              nature,
              topicId,
              organization,
              properties.graphMaxEdges(),
              properties.graphMaxNodes());
      selfLoader.addSeed(from);
      selfLoader.expand(List.of(from), true);
      boolean inScope = selfLoader.contains(from);
      data.put("reachable", inScope);
      data.put("hops", inScope ? 0 : null);
      data.put("path", inScope ? List.of(from) : List.of());
      // 与正常路径保持同一 DTO 形状：调用方不需要为零跳单独判空。
      data.put("pathNodes", inScope ? nodeViews(generation, List.of(from)) : List.of());
      data.put(
          "withinLimits", Map.of("nodes", selfLoader.nodeCount(), "edges", selfLoader.edgeCount()));
      data.put(
          "note",
          inScope
              ? "起点与终点相同，按 0 跳返回。"
              : "起点在当前范围内没有合格公开合作边（可能成果未公开或未生效、关系非 AUTO/SELF_CONFIRMED、"
                  + "或当前筛选条件将其排除），不按 0 跳可达处理。");
      data.put("pathRule", "起点与终点相同时，只有在当前范围内存在合格合作边才返回 0 跳路径。");
      return data;
    }

    ScopedGraphLoader fromLoader =
        new ScopedGraphLoader(
            mapper,
            generation,
            nature,
            topicId,
            organization,
            properties.graphMaxEdges(),
            properties.graphMaxNodes());
    fromLoader.addSeed(from);
    Map<String, Integer> fromSource = breadthFirst(fromLoader, from, MAX_PATH_DEPTH, startedAt, to);
    if (!fromSource.containsKey(to)) {
      data.put("reachable", false);
      data.put("hops", null);
      data.put("path", List.of());
      data.put(
          "note",
          "在 "
              + MAX_PATH_DEPTH
              + " 跳范围内未找到合作路径；这不等于全图不连通，只说明最短距离超过 "
              + MAX_PATH_DEPTH
              + " 跳。起点或终点若在范围内没有合格合作边，也会得到这个结果。");
      data.put(
          "withinLimits", Map.of("nodes", fromLoader.nodeCount(), "edges", fromLoader.edgeCount()));
      return data;
    }

    int distance = fromSource.get(to);
    ScopedGraphLoader toLoader =
        new ScopedGraphLoader(
            mapper,
            generation,
            nature,
            topicId,
            organization,
            properties.graphMaxEdges(),
            properties.graphMaxNodes());
    toLoader.addSeed(to);
    // 目标侧只需覆盖「最短路径上除端点以外的节点」：它们距目标最多 distance-1 跳。
    Map<String, Integer> toTarget =
        breadthFirst(toLoader, to, Math.max(distance - 1, 0), startedAt, null);

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
      // 邻居按 ID 升序尝试：等长路径里取字典序最小的一条，与遍历顺序无关。
      for (String neighbor : new java.util.TreeSet<>(fromLoader.neighbors(current))) {
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

    data.put("reachable", true);
    data.put("hops", distance);
    data.put("path", path);
    data.put("pathNodes", nodeViews(generation, path));
    data.put(
        "withinLimits",
        Map.of(
            "nodes",
            Math.max(fromLoader.nodeCount(), toLoader.nodeCount()),
            "edges",
            fromLoader.edgeCount() + toLoader.edgeCount()));
    data.put("pathRule", "跳数最少；等长时取规范 ID 序列字典序最小的一条；只返回一条，不枚举全部等长路径");
    data.put("scope", "双侧按需扩边至第 " + distance + " 跳，不是筛选范围的全图");
    return data;
  }

  /**
   * 从起点出发的受限 BFS 距离。
   *
   * <p>只展开前 {@code maxDepth} 层，超过时间预算抛 {@code GRAPH_TIMEOUT}，不返回部分结果——
   * 「算了一半的距离」会让调用方把截断当成真实拓扑。
   *
   * <p><b>目标一旦在当前层出现就停</b>（{@code target} 非空时）。这不是优化而是正确性的前提：
   * 深度上限是 6，而一张中心学者的 1 跳子图就有 399 个节点、近 5000 条边，展开到 6 层等于把
   * 整张协作图走一遍——在 1000 毫秒预算下必然超时，于是「找 1 跳的直接合作者」这种最轻的请求
   * 也会返回 GRAPH_TIMEOUT。更深的层不可能给出更短距离，提前停不改变结果：路径重建只用
   * 距离 ≤ 最短距离的节点。{@code target} 为空表示不早停（目标侧的展开本来就有更小的深度上限）。
   */
  private Map<String, Integer> breadthFirst(
      ScopedGraphLoader loader, String source, int maxDepth, long startedAt, String target) {
    Map<String, Integer> distance = new HashMap<>();
    distance.put(source, 0);
    List<String> frontier = List.of(source);
    for (int depth = 1; depth <= maxDepth && !frontier.isEmpty(); depth++) {
      if (System.currentTimeMillis() - startedAt > properties.graphBudgetMillis()) {
        throw new BusinessException(
            ErrorCode.GRAPH_TIMEOUT,
            "路径计算超过时间预算 " + properties.graphBudgetMillis() + " 毫秒，请增加领域或机构筛选条件以缩小子图范围");
      }
      List<String> discovered = loader.expand(frontier, true);
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
