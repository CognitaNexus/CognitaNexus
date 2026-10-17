package com.cognitanexus.query.projection.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Inbox 批量投递请求体。
 *
 * <p>依据：架构 V0.2 §6.3。字段与 core 投递器发出的结构逐字对应。刻意不把 payload 声明为强类型：
 * 未知事件类型与更高 schemaVersion 的事件必须能落库并被隔离待处理，强类型化会在解析阶段就把
 * 它们变成 400，接收端随后又会对投递方声称「已接收」——那是最坏的一种失败形态。
 */
public record InboxRequests() {

  public record BatchRequest(
      @NotNull String consumerId, @NotEmpty @Valid List<EventRequest> events) {}

  public record EventRequest(
      @NotNull String eventId,
      @NotNull String eventType,
      @NotNull Integer schemaVersion,
      String aggregateType,
      String aggregateId,
      Long aggregateVersion,
      @NotNull Long catalogRevision,
      String occurredAt,
      String traceId,
      java.util.Map<String, Object> payload) {}

  /**
   * 投递回执：本服务只在事件已提交入库后才返回成功。
   *
   * <p>字段口径必须严格区分，否则「收到」会被读成「生效」：
   *
   * <ul>
   *   <li>received：本批<b>实际落库</b>的条数。整批重复时为 0，不等于请求里的条数；
   *   <li>duplicated：本批中已存在（被唯一键拦下）的条数，received + duplicated 才是请求条数；
   *   <li>applied：本批结束后被投影应用的条数，可能包含此前积压的事件；
   *   <li>pendingRemaining：还有已落库但未发布的内容。判据是「见过的最大修订」大于已发布水位，
   *       因此缺口未补齐（received 与 published 都停在缺口前）时仍为 true——那种情形下后继事件
   *       确实在库里等着，只是等的是永远不会自己到来的前序修订。
   * </ul>
   */
  public record BatchReceipt(
      int received,
      int duplicated,
      int applied,
      int isolated,
      boolean pendingRemaining,
      long receivedRevision,
      long publishedRevision,
      String note) {}
}
