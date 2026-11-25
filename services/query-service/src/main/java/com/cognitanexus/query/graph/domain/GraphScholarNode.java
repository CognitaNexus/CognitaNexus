package com.cognitanexus.query.graph.domain;

/**
 * 图投影中的学者节点。
 *
 * <p>依据：架构 V0.2 §9.3；ADR-005。本类型是<b>投影</b>，不是学者权威档案：姓名、领域、机构
 * 都可能落后于 core 的权威值，调用方要展示「当前事实」仍应读 core 或 query 的读投影。
 *
 * <p>{@code dataOrigin} 与成果上的 {@code data_nature} 同义（REAL / SIMULATED / MIXED），
 * 用于在建边与查询时隔离模拟数据。默认现实口径只接受 REAL，绝不能因为图库缺字段而放宽。
 */
public record GraphScholarNode(
    String scholarId,
    String scholarName,
    String domain,
    String organization,
    String dataOrigin,
    long sourceVersion) {

  /** 节点数据来源版本。低于该值的投影事件视为迟到事件，不覆盖已有值。 */
  public GraphScholarNode {
    scholarId = GraphIdentifiers.require(scholarId, "scholarId");
    sourceVersion = Math.max(sourceVersion, 0L);
  }

  /** 便于投影代码构造最小节点：只强调标识时其余字段可空。 */
  public static GraphScholarNode identified(String scholarId, long sourceVersion) {
    return new GraphScholarNode(scholarId, null, null, null, null, sourceVersion);
  }
}
