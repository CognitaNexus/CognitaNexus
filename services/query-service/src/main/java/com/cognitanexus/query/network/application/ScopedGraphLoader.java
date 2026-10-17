package com.cognitanexus.query.network.application;

import com.cognitanexus.query.network.domain.CooperationGraph;
import com.cognitanexus.query.network.domain.CooperationGraph.CooperationEdge;
import com.cognitanexus.query.network.infrastructure.NetworkMapper;
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

/**
 * 按种子逐层扩边的合作图加载器。
 *
 * <p>为什么需要它：合作网络此前是「先把整个筛选范围内的边读进内存，再取邻域」。在真实数据规模下
 * （2.5 万位学者、7.7 万条边）这一步必然撞上节点上限，于是<b>任何</b>学者的自我网络与任意两点的
 * 合作路径都返回范围过大——功能在真实规模下等于不可用，且报错理由（「请增加筛选条件」）与真实
 * 原因（查询不该载入全图）不符。
 *
 * <p>边界仍然坚持「明确失败、不截断」：节点或边超过上限就抛 {@code GRAPH_SCOPE_TOO_LARGE}，
 * 因为截断后的图依旧连通，BFS 会给出「无路径」这个错误结论，而调用方无法区分「确实不合作」与
 * 「系统只算了一部分」。
 *
 * <p>一个刻意的取舍：扩展时按块（{@link #CHUNK}）查询，块大小远小于上限，使单条 SQL 的 IN 列表
 * 与返回行数都可控；边数上限按<b>去重后的不同边</b>统计，同一对学者由多篇论文产生的多行只算一条，
 * 与「强度=不同合格论文数」的口径一致。
 */
final class ScopedGraphLoader {

  /** 单条 SQL 的 IN 列表规模。 */
  static final int CHUNK = 400;

  private final NetworkMapper mapper;
  private final String generation;
  private final String dataNature;
  private final String topicId;
  private final String organization;
  private final int maxEdges;
  private final int maxNodes;

  private final Set<String> visited = new LinkedHashSet<>();
  private final Map<String, Map<String, Long>> adjacency = new HashMap<>();
  private final Map<String, Long> loadedEdges = new LinkedHashMap<>();

  ScopedGraphLoader(
      NetworkMapper mapper,
      String generation,
      String dataNature,
      String topicId,
      String organization,
      int maxEdges,
      int maxNodes) {
    this.mapper = mapper;
    this.generation = generation;
    this.dataNature = dataNature;
    this.topicId = topicId;
    this.organization = organization;
    this.maxEdges = maxEdges;
    this.maxNodes = maxNodes;
  }

  void addSeed(String scholarId) {
    visited.add(scholarId);
  }

  boolean contains(String scholarId) {
    return adjacency.containsKey(scholarId);
  }

  Set<String> neighbors(String scholarId) {
    Map<String, Long> row = adjacency.get(scholarId);
    return row == null ? Set.of() : row.keySet();
  }

  int nodeCount() {
    return visited.size();
  }

  int edgeCount() {
    return loadedEdges.size();
  }

  /**
   * 扩展一层。
   *
   * @param frontier 待扩展的学者集合
   * @param allowNewNodes 是否吸收新发现的学者；为 false 时只补齐这些学者之间的边（用于收尾，
   *     把「深度边界上两端都在边界层」的边补进来，且不再向外扩散）
   * @return 本层新发现的学者（稳定顺序：查询按 scholar_a, scholar_b 排序）
   */
  List<String> expand(Collection<String> frontier, boolean allowNewNodes) {
    List<String> discovered = new ArrayList<>();
    List<String> ids = new ArrayList<>(new LinkedHashSet<>(frontier));
    for (int start = 0; start < ids.size(); start += CHUNK) {
      List<String> chunk = ids.subList(start, Math.min(start + CHUNK, ids.size()));
      int remaining = maxEdges - loadedEdges.size();
      int limit = Math.max(remaining, 0) + 1;
      List<NetworkMapper.EdgeRow> rows =
          mapper.listEdgesOfScholars(generation, dataNature, topicId, organization, chunk, limit);
      boolean saturated = rows.size() >= limit;
      for (NetworkMapper.EdgeRow row : rows) {
        String a = row.scholarA();
        String b = row.scholarB();
        boolean aKnown = visited.contains(a);
        boolean bKnown = visited.contains(b);
        if (!allowNewNodes && !(aKnown && bKnown)) {
          // 收尾阶段只认两端都已在子图内的边：否则会把子图悄悄扩到 depth 之外。
          continue;
        }
        if (!aKnown) {
          visited.add(a);
          discovered.add(a);
        }
        if (!bKnown) {
          visited.add(b);
          discovered.add(b);
        }
        String key = a.compareTo(b) <= 0 ? a + "\u0001" + b : b + "\u0001" + a;
        if (loadedEdges.putIfAbsent(key, row.strength()) == null) {
          adjacency.computeIfAbsent(a, k -> new LinkedHashMap<>()).put(b, row.strength());
          adjacency.computeIfAbsent(b, k -> new LinkedHashMap<>()).put(a, row.strength());
        }
      }
      if (visited.size() > maxNodes) {
        throw new BusinessException(
            ErrorCode.GRAPH_SCOPE_TOO_LARGE,
            "以给定起点为中心的合作子图含学者超过节点上限 " + maxNodes + "，请降低深度，或增加领域/机构筛选条件");
      }
      if (loadedEdges.size() > maxEdges || (saturated && loadedEdges.size() >= maxEdges)) {
        throw new BusinessException(
            ErrorCode.GRAPH_SCOPE_TOO_LARGE, "给定范围内的合作边超过上限 " + maxEdges + " 条，请增加领域或机构筛选条件");
      }
    }
    return discovered;
  }

  /** 生成不可变快照；邻接已在构造时复制，之后不再修改。 */
  CooperationGraph snapshot(String version) {
    List<CooperationEdge> edges = new ArrayList<>(loadedEdges.size());
    for (Map.Entry<String, Long> entry : loadedEdges.entrySet()) {
      String[] parts = entry.getKey().split("\u0001", 2);
      edges.add(new CooperationEdge(parts[0], parts[1], entry.getValue()));
    }
    return CooperationGraph.of(version, edges);
  }
}
