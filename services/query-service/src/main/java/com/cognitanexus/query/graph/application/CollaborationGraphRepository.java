package com.cognitanexus.query.graph.application;

import com.cognitanexus.query.graph.domain.GraphCollaborationEdge;
import com.cognitanexus.query.graph.domain.GraphFilterCriteria;
import com.cognitanexus.query.graph.domain.GraphScholarNode;
import java.util.Collection;
import java.util.List;

/**
 * 合作图读端口。
 *
 * <p>上层（应用服务、将来的 {@code /api/v1/public/network/*} 实现）只依赖本接口与领域类型：
 * 不出现 Driver、Session、Cypher 或任何图库专有语法。将来从自建 Neo4j 换成华为云 GES，
 * 需要替换的正是这个接口的实现（见 ADR-005 §9、{@code docs/ops/huaweicloud-migration-prep.md}）。
 *
 * <p>契约：
 *
 * <ul>
 *   <li>{@link #expand} 只返回<b>合格</b>合作边：两端都必须是公开生效成果的合格作者，边必须带
 *       至少一条共同成果证据。实现不得返回「无证据的邻接」；
 *   <li>返回的边可能以任一端为起点被重复发现，调用方按 {@link GraphCollaborationEdge#pairKey()}
 *       去重；实现返回 {@code truncated=true} 时必须由调用方转成明确失败，不能截断后冒充完整子图；
 *   <li>筛选条件必须原样下推：数据性质、领域、机构三条都是「成果级」条件，不能先取全图再过滤，
 *       否则边强度会按未过滤的成果计数——那正是「图里有一条边但按查询条件不该有」的来源。
 * </ul>
 */
public interface CollaborationGraphRepository {

  /**
   * 从给定学者集合向外扩一层。
   *
   * @param frontier 当前层学者 ID；空集合直接返回空结果，不发起查询
   * @param criteria 筛选条件，不允许为 null
   * @param maxEdges 本次最多返回的不同作者对数量；实现多取一行以判断是否越界
   */
  Expansion expand(Collection<String> frontier, GraphFilterCriteria criteria, int maxEdges);

  /** 按 ID 取学者节点属性（姓名/领域/机构）；不存在的 ID 不返回，不抛异常。 */
  List<GraphScholarNode> findScholars(Collection<String> scholarIds);

  /** 图库连通性探测。用于降级开关与健康检查，不做任何写入。 */
  boolean isAvailable();

  /**
   * 一层扩边结果。
   *
   * @param edges 发现的合作边（可能含两端都在 frontier 的边）
   * @param truncated 是否触及 {@code maxEdges} 上限；为 true 时上层必须明确失败
   */
  record Expansion(List<GraphCollaborationEdge> edges, boolean truncated) {

    public Expansion {
      edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public static Expansion empty() {
      return new Expansion(List.of(), false);
    }
  }
}
