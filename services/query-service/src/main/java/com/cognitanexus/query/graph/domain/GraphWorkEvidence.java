package com.cognitanexus.query.graph.domain;

/**
 * 一条合作边的证据：某篇合格成果把两位学者连在一起。
 *
 * <p>依据：架构 §9.3「维护论文→作者对贡献→边→证据的可逆关系」、IN01「逐跳有证据」。
 * 每条 {@code COOPERATED_WITH} 边都必须能回答「凭什么说他们合作过」，因此证据至少包含
 * {@code workId} 与 {@code title}。缺少标题的证据在界面上无法核对，等于没有证据。
 */
public record GraphWorkEvidence(String workId, String title, String dataOrigin) {

  public GraphWorkEvidence {
    workId = GraphIdentifiers.require(workId, "workId");
    title = title == null ? "" : title;
    if (title.isBlank()) {
      throw new IllegalArgumentException("证据必须带成果标题，workId=" + workId);
    }
    dataOrigin = dataOrigin == null ? null : dataOrigin;
  }
}
