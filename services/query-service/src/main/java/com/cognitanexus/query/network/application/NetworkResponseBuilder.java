package com.cognitanexus.query.network.application;

import com.cognitanexus.query.consistency.PublicBarrierGuard;
import com.cognitanexus.query.network.domain.CooperationGraph.CooperationEdge;
import com.cognitanexus.query.network.domain.NetworkRules;
import com.cognitanexus.query.network.infrastructure.NetworkMapper;
import com.cognitanexus.query.platform.scope.DataNatureScope;
import com.cognitanexus.query.projection.application.ProjectionProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 合作网络响应体的唯一构造入口。
 *
 * <p>为什么要把响应字段的拼装抽出来：现在有两条查询实现（PostgreSQL 与图投影），它们必须返回
 * <b>逐字段同名、同值</b>的结构。若各写一份 {@code put("graphVersion", ...)}，某天有人只在一侧
 * 加了 {@code pathNodes}、改了 {@code dataNatureScope} 的回显方式，或把 {@code ruleVersion}
 * 换成另一个常量，调用方看到的是两条形状不同的响应，而两边都能跑、测试也未必发现。
 *
 * <p>因此字段名、版本字段、限额回显、节点视图与边视图只在这里定义一次；{@link NetworkService}
 * 与 {@link NetworkGraphQueryService} 都调用本类。这里不做任何查询，也不做规则判断——只负责把
 * 已有结论按固定形状写进响应，避免把「业务分支」也搬进来。
 */
final class NetworkResponseBuilder {

  private static final String NATURE_ALL = "ALL";

  private NetworkResponseBuilder() {}

  /**
   * 响应公共前缀。
   *
   * <p>{@code graphVersion} 由「活动代 + 已发布修订」组成：调用方据此判断两次响应是否来自同一
   * 已发布版本，而不是按时间戳猜。{@code dataNatureScope} 回显归一化后的生效值（{@code ALL} 表示
   * 不限定），不是原始参数——原始参数可能与实际生效口径不同。
   */
  static Map<String, Object> base(
      PublicBarrierGuard.BarrierSnapshot barrier,
      String nature,
      String topicId,
      String organization,
      ProjectionProperties properties) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("graphVersion", "g" + barrier.activeGeneration() + "-r" + barrier.publishedRevision());
    data.put("ruleVersion", NetworkRules.RULE_VERSION);
    data.put("publishedRevision", barrier.publishedRevision());
    data.put("generatedAt", Instant.now());
    data.put(
        "dataNatureScope",
        nature == null || nature.isBlank() ? NATURE_ALL : DataNatureScope.label(nature));
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
    data.put("edgeRule", NetworkRules.EDGE_RULE);
    return data;
  }

  /**
   * 节点视图。
   *
   * <p>姓名与「合格成果数」来自读取投影（{@code qry_contribution}）而不是图库节点：图库里的
   * 姓名是投影副本、节点上也没有这个计数，而响应契约要求两者一致（ADR-005 §6.1：图库不承担
   * 「谁是权威值」）。节点顺序由调用方给出（BFS 顺序），不在此重排。
   */
  static List<Map<String, Object>> nodeViews(
      NetworkMapper mapper, String generation, List<String> ids) {
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

  /** 边视图：只暴露两端与强度，不在此处展开证据列表（契约保持原样）。 */
  static Map<String, Object> edgeView(CooperationEdge edge) {
    return edgeView(edge.scholarA(), edge.scholarB(), edge.strength());
  }

  /** 图库结果的边视图：与 PG 实现共用同一字段名与顺序。 */
  static Map<String, Object> edgeView(
      com.cognitanexus.query.graph.domain.GraphCollaborationEdge edge) {
    return edgeView(edge.scholarA(), edge.scholarB(), edge.strength());
  }

  private static Map<String, Object> edgeView(String source, String target, long strength) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("source", source);
    view.put("target", target);
    view.put("strength", strength);
    return view;
  }

  /** 本次查询触及的规模。 */
  static Map<String, Object> withinLimits(int nodes, int edges) {
    return Map.of("nodes", nodes, "edges", edges);
  }
}
