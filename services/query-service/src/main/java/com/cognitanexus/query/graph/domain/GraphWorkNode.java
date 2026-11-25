package com.cognitanexus.query.graph.domain;

import java.util.List;

/**
 * 图投影中的合格学术成果节点（论文/专利/项目/奖励）。
 *
 * <p>只有满足「真实、公开、生效」的成果才会进入图投影：{@code isPublic=false} 的节点是<b>墓碑</b>，
 * 用于保留「曾经投影过、现在不再公开」这一事实并让边随之下线；所有图查询都必须带
 * {@code w.is_public = true}，否则已撤销的成果会重新出现在合作网络里。
 *
 * <p>{@code topicIds} / {@code organizations} 是查询筛选用的投影字段。机构取「成果发生时的机构」，
 * 不是学者当前任职机构（架构 §9.2）；缺失即未知，不从别处补齐。
 */
public record GraphWorkNode(
    String workId,
    String title,
    boolean isPublic,
    String dataOrigin,
    long sourceVersion,
    List<String> topicIds,
    List<String> organizations) {

  public GraphWorkNode {
    workId = GraphIdentifiers.require(workId, "workId");
    dataOrigin = GraphIdentifiers.require(dataOrigin, "dataOrigin");
    title = title == null ? "" : title;
    topicIds = topicIds == null ? List.of() : List.copyOf(topicIds);
    organizations = organizations == null ? List.of() : List.copyOf(organizations);
    sourceVersion = Math.max(sourceVersion, 0L);
  }
}
