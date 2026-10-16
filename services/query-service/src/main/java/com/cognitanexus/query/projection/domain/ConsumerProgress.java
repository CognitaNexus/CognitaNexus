package com.cognitanexus.query.projection.domain;

import java.time.Instant;

/**
 * 接收者的进度水位。
 *
 * <p>依据：架构 V0.2 §6.3、§7.1。
 *
 * <ul>
 *   <li>receivedRevision：已持久接收且连续的修订。缺口之后的修订即使已落库也不推进它，
 *       以免「跳过毒事件伪造新水位」；
 *   <li>highestSeenRevision：见过的最大修订，用于观察积压与缺口宽度，不参与发布判断；
 *   <li>publishedRevision：投影已发布的连续修订。公开查询读的是这个值，它与 received 分离，
 *       是因为「网络投递成功不等于查询视图完成更新」。
 * </ul>
 *
 * @param activeGeneration 当前活动投影代；增量更新写这一代
 */
public record ConsumerProgress(
    String consumerId,
    String activeGeneration,
    long receivedRevision,
    long highestSeenRevision,
    long publishedRevision,
    Instant receivedAt,
    Instant publishedAt,
    Instant updatedAt) {

  public long receiveLag() {
    return Math.max(0, highestSeenRevision - receivedRevision);
  }

  public long publishLag() {
    return Math.max(0, receivedRevision - publishedRevision);
  }
}
