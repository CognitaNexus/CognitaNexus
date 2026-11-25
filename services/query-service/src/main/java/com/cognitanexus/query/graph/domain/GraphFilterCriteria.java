package com.cognitanexus.query.graph.domain;

/**
 * 合作网络查询的筛选条件。
 *
 * <p>归一化直接复用 {@link com.cognitanexus.query.platform.scope.DataNatureScope}，不在这里
 * 另写一份「空值是否等于放宽」的判断：那条规则曾经在检索、统计、合作网络各写一遍，只要有一处
 * 写反，模拟数据就会被算进真实口径，且响应仍然声称自己是 REAL。
 *
 * <p>{@code topicId} 与 {@code organization} 是<b>成果级</b>筛选（先限定论文资格，再取作者），
 * 与现有 PG 查询的 EXISTS 子句口径一致；不能改成学者级筛选，否则同一条件在两条实现路径上
 * 会给出不同的图。
 */
public record GraphFilterCriteria(String dataNature, String topicId, String organization) {

  public GraphFilterCriteria {
    // null 表示不限制（ALL）；REAL 是默认现实口径。非法取值由 DataNatureScope 抛 INVALID_ARGUMENT。
    dataNature = com.cognitanexus.query.platform.scope.DataNatureScope.normalize(dataNature);
    topicId = GraphIdentifiers.blankToNull(topicId);
    organization = GraphIdentifiers.blankToNull(organization);
  }

  /** 默认现实口径：只含 REAL 数据，不限领域与机构。 */
  public static GraphFilterCriteria realOnly() {
    return new GraphFilterCriteria("REAL", null, null);
  }

  /** 演示口径：不限数据性质，仅用于演示与容量测试。 */
  public static GraphFilterCriteria all() {
    return new GraphFilterCriteria("ALL", null, null);
  }

  public GraphFilterCriteria withTopic(String newTopicId) {
    return new GraphFilterCriteria(dataNature, newTopicId, organization);
  }

  public GraphFilterCriteria withOrganization(String newOrganization) {
    return new GraphFilterCriteria(dataNature, topicId, newOrganization);
  }

  /** 是否带成果级筛选。带筛选时边强度必须按合格成果重新聚合，不能直接复用物化边的强度。 */
  public boolean hasWorkLevelFilter() {
    return topicId != null || organization != null;
  }

  /** 供响应回显的作用域标签，必须回显生效值而不是原始参数。 */
  public String dataNatureLabel() {
    return com.cognitanexus.query.platform.scope.DataNatureScope.label(dataNature);
  }
}
