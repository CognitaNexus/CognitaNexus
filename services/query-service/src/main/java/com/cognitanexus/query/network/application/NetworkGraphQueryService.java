package com.cognitanexus.query.network.application;

import com.cognitanexus.query.consistency.PublicBarrierGuard;
import com.cognitanexus.query.graph.application.CollaborationGraphService;
import com.cognitanexus.query.graph.config.Neo4jGraphProperties;
import com.cognitanexus.query.graph.domain.GraphCollaborationEdge;
import com.cognitanexus.query.graph.domain.GraphFilterCriteria;
import com.cognitanexus.query.graph.domain.GraphPathResult;
import com.cognitanexus.query.graph.domain.GraphSubgraphResult;
import com.cognitanexus.query.network.domain.GraphProjectionStatus;
import com.cognitanexus.query.network.domain.NetworkRules;
import com.cognitanexus.query.network.infrastructure.NetworkMapper;
import com.cognitanexus.query.projection.application.ProjectionProperties;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 图库版合作网络的响应装配器（{@link GraphNetworkBackend} 的 Neo4j 实现）。
 *
 * <p>本类承担的正是「迁移查询、不迁移业务口径」这件事：
 *
 * <ul>
 *   <li>拓扑与路径来自 {@link CollaborationGraphService}（与 PG 版 {@link NetworkService} 共用
 *       {@code NetworkRules} 的深度、路径选择与文案）；
 *   <li>响应字段由 {@link NetworkResponseBuilder} 统一拼装：{@code graphVersion}、{@code ruleVersion}、
 *       {@code publishedRevision}、{@code limits}、{@code edgeRule} 等与 PG 逐字段一致；
 *   <li>节点姓名与「合格成果数」仍取自读取投影（{@code qry_contribution}）：图库节点只有姓名副本、
 *       没有这个计数，而响应契约要求两者与 PG 相同（ADR-005 §6.1 明确图库不承担权威值）；
 *   <li>可用性判定由 {@link ProjectionWatermarkGuard} 给出，本类不自行放宽「水位落后」。
 * </ul>
 *
 * <p><b>{@code withinLimits} 的数值口径</b>：自我网络直接使用图服务统计的「实际载入」节点/边数
 * （与 PG 加载器同义）。路径查询的图实现按需扩边、不保留加载计数，因此那里报告的是<b>构成答案的
 * 规模</b>（路径节点数 / 逐跳边数），键与 PG 完全一致。对照器把该诊断字段排除在一致性比较之外
 * （见 {@link NetworkParityComparator}），避免用「加载了多少」冒充业务结论。
 */
public class NetworkGraphQueryService implements GraphNetworkBackend {

  private static final Logger log = LoggerFactory.getLogger(NetworkGraphQueryService.class);

  private final CollaborationGraphService graphs;
  private final NetworkMapper mapper;
  private final ProjectionProperties projectionProperties;
  private final ProjectionWatermarkGuard watermarkGuard;

  public NetworkGraphQueryService(
      CollaborationGraphService graphs,
      NetworkMapper mapper,
      ProjectionProperties projectionProperties,
      Neo4jGraphProperties graphProperties,
      ProjectionWatermarkGuard watermarkGuard) {
    this.graphs = Objects.requireNonNull(graphs, "graphs");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.projectionProperties =
        Objects.requireNonNull(projectionProperties, "projectionProperties");
    this.watermarkGuard = Objects.requireNonNull(watermarkGuard, "watermarkGuard");
    warnOnLimitMismatch(projectionProperties, graphProperties);
  }

  /**
   * 规模上限不一致必须可见。
   *
   * <p>{@code cn.query.graph.max-nodes/max-edges/budget-millis} 与 {@code cn.projection.graph-*} 是
   * 同一条业务上限的两种配置出口（ADR-005 §6.3 要求两者一致）。若它们不同，图实现会在比 PG 更低的
   * 阈值上抛 {@code GRAPH_SCOPE_TOO_LARGE}——同一请求换模式就从「有结果」变成「范围过大」，而响应
   * 里没有任何字段说明原因。这里不拒绝启动（改配置要重启，反而扩大故障），但必须每次启动都可见。
   */
  private static void warnOnLimitMismatch(
      ProjectionProperties projection, Neo4jGraphProperties graph) {
    if (graph == null) {
      return;
    }
    List<String> mismatches = new ArrayList<>();
    if (graph.maxNodes() != projection.graphMaxNodes()) {
      mismatches.add("maxNodes(" + graph.maxNodes() + " != " + projection.graphMaxNodes() + ")");
    }
    if (graph.maxEdges() != projection.graphMaxEdges()) {
      mismatches.add("maxEdges(" + graph.maxEdges() + " != " + projection.graphMaxEdges() + ")");
    }
    if (graph.budgetMillis() != projection.graphBudgetMillis()) {
      mismatches.add(
          "budgetMillis(" + graph.budgetMillis() + " != " + projection.graphBudgetMillis() + ")");
    }
    if (!mismatches.isEmpty()) {
      log.warn(
          "图查询规模上限与 PostgreSQL 路径不一致，同一请求换模式可能得到不同的范围错误：{}。"
              + "请让 cn.query.graph.max-nodes/max-edges/budget-millis 与 cn.projection.graph-* 保持一致",
          String.join(", ", mismatches));
    }
  }

  @Override
  public GraphProjectionStatus status(long authoritativeRevision, String authoritativeGeneration) {
    return watermarkGuard.evaluate(authoritativeRevision, authoritativeGeneration);
  }

  @Override
  public Map<String, Object> neighborhood(
      PublicBarrierGuard.BarrierSnapshot barrier,
      String scholarId,
      int depth,
      int maxNodesRequested,
      GraphFilterCriteria criteria) {
    GraphFilterCriteria effective = criteria == null ? GraphFilterCriteria.realOnly() : criteria;
    String generation = barrier.activeGeneration();
    GraphSubgraphResult result =
        graphs.neighborhood(scholarId, depth, maxNodesRequested, effective);
    Map<String, Object> data = base(barrier, effective);
    data.put("seed", scholarId);
    if (!result.seedInScope()) {
      // 与 PG 一致：空图也带 nodes/edges 两个空列表，并说明「不能确定他有合作」而不是「查无此人」。
      data.put("seedInScope", false);
      data.put("nodes", List.of());
      data.put("edges", List.of());
      data.put("note", NetworkRules.NOTE_SEED_OUT_OF_SCOPE);
      return data;
    }
    data.put("seedInScope", true);
    data.put("depth", result.depth());
    List<String> orderedIds = bfsOrder(scholarId, result.edges(), result.depth());
    data.put("nodes", NetworkResponseBuilder.nodeViews(mapper, generation, orderedIds));
    data.put("edges", edgeViews(result.edges()));
    data.put(
        "withinLimits",
        NetworkResponseBuilder.withinLimits(result.visitedNodes(), result.loadedEdges()));
    data.put("scope", NetworkRules.neighborhoodScope(result.depth()));
    return data;
  }

  @Override
  public Map<String, Object> shortestPath(
      PublicBarrierGuard.BarrierSnapshot barrier,
      String from,
      String to,
      GraphFilterCriteria criteria) {
    GraphFilterCriteria effective = criteria == null ? GraphFilterCriteria.realOnly() : criteria;
    String generation = barrier.activeGeneration();
    GraphPathResult result =
        graphs.shortestPath(from, to, effective, projectionProperties.graphBudgetMillis());
    Map<String, Object> data = base(barrier, effective);
    data.put("from", from);
    data.put("to", to);

    if (from.equals(to)) {
      data.put("reachable", result.reachable());
      data.put("hops", result.reachable() ? 0 : null);
      data.put("path", result.reachable() ? List.of(from) : List.of());
      data.put(
          "pathNodes",
          result.reachable()
              ? NetworkResponseBuilder.nodeViews(mapper, generation, List.of(from))
              : List.of());
      data.put(
          "withinLimits",
          NetworkResponseBuilder.withinLimits(result.path().size(), result.hopEdges().size()));
      data.put("note", result.note());
      data.put("pathRule", NetworkRules.PATH_RULE_ZERO_HOP);
      return data;
    }

    if (!result.reachable()) {
      // 与 PG 一致：不可达结果不带 pathNodes / pathRule / scope，只说明「N 跳内未找到」。
      data.put("reachable", false);
      data.put("hops", null);
      data.put("path", List.of());
      data.put("note", result.note());
      data.put(
          "withinLimits",
          NetworkResponseBuilder.withinLimits(result.path().size(), result.hopEdges().size()));
      return data;
    }

    data.put("reachable", true);
    data.put("hops", result.hops());
    data.put("path", result.path());
    data.put("pathNodes", NetworkResponseBuilder.nodeViews(mapper, generation, result.path()));
    data.put(
        "withinLimits",
        NetworkResponseBuilder.withinLimits(result.path().size(), result.hopEdges().size()));
    data.put("pathRule", NetworkRules.PATH_RULE);
    data.put("scope", NetworkRules.pathScope(result.hops()));
    return data;
  }

  private Map<String, Object> base(
      PublicBarrierGuard.BarrierSnapshot barrier, GraphFilterCriteria criteria) {
    return NetworkResponseBuilder.base(
        barrier,
        criteria.dataNature(),
        criteria.topicId(),
        criteria.organization(),
        projectionProperties);
  }

  private static List<Map<String, Object>> edgeViews(List<GraphCollaborationEdge> edges) {
    List<GraphCollaborationEdge> sorted = new ArrayList<>(edges);
    sorted.sort(
        Comparator.comparing(GraphCollaborationEdge::scholarA)
            .thenComparing(GraphCollaborationEdge::scholarB));
    List<Map<String, Object>> views = new ArrayList<>(sorted.size());
    for (GraphCollaborationEdge edge : sorted) {
      views.add(NetworkResponseBuilder.edgeView(edge));
    }
    return views;
  }

  /**
   * 按 PG 实现的规则重排节点：BFS 层次序、同层按规范 ID 升序。
   *
   * <p>为什么要重排而不是直接用服务返回的节点顺序：PG 的邻接表是 {@code TreeSet}（邻居升序），
   * 图服务的邻接表按发现顺序插入，两者节点集合相同但顺序可能不同。顺序属于契约的一部分——
   * 调用方按同一请求比对两次响应时，顺序抖动会被读成「图在变」。这里用结果边集重新跑一次
   * 与 PG 完全相同的 BFS，既锁住顺序，也不依赖图服务的内部实现细节。
   */
  private static List<String> bfsOrder(String seed, List<GraphCollaborationEdge> edges, int depth) {
    Map<String, TreeSet<String>> adjacency = new HashMap<>();
    for (GraphCollaborationEdge edge : edges) {
      adjacency.computeIfAbsent(edge.scholarA(), key -> new TreeSet<>()).add(edge.scholarB());
      adjacency.computeIfAbsent(edge.scholarB(), key -> new TreeSet<>()).add(edge.scholarA());
    }
    List<String> order = new ArrayList<>();
    Map<String, Integer> level = new HashMap<>();
    ArrayDeque<String> queue = new ArrayDeque<>();
    order.add(seed);
    level.put(seed, 0);
    queue.add(seed);
    while (!queue.isEmpty()) {
      String current = queue.poll();
      int currentLevel = level.get(current);
      if (currentLevel >= depth) {
        continue;
      }
      for (String neighbor : adjacency.getOrDefault(current, new TreeSet<>())) {
        if (!level.containsKey(neighbor)) {
          level.put(neighbor, currentLevel + 1);
          order.add(neighbor);
          queue.add(neighbor);
        }
      }
    }
    return order;
  }
}
