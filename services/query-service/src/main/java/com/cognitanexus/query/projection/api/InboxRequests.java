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

  /** 投递回执：本服务只在事件已提交入库后才返回成功。 */
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
