package com.cognitanexus.query.projection.domain;

import java.time.Instant;
import java.util.Map;

/**
 * Inbox 中一条已持久接收的事件。
 *
 * <p>依据：架构 V0.2 §6.3。payload 以原始 Map 保存而不是反序列化成强类型对象：未知事件类型与
 * 提升后的 schemaVersion 都必须能原样保留并转隔离待处理。若在这里强转，解析失败就等同于丢弃，
 * 而丢弃之后系统还会宣称「已追平水位」——这是架构明确禁止的。
 *
 * @param catalogRevision core 公开目录的单调提交次序，也是本服务排序与推进水位的唯一依据
 */
public record InboxEventRecord(
    String id,
    String consumerId,
    String eventId,
    String eventType,
    int schemaVersion,
    String aggregateType,
    String aggregateId,
    long aggregateVersion,
    long catalogRevision,
    Instant occurredAt,
    String traceId,
    Map<String, Object> payload,
    int attempts) {

  public InboxEventRecord {
    payload = payload == null ? Map.of() : payload;
  }

  public String stringOf(String key) {
    Object value = payload.get(key);
    return value instanceof String text ? text : null;
  }

  public Long longOf(String key) {
    Object value = payload.get(key);
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text && !text.isBlank()) {
      try {
        return Long.parseLong(text.trim());
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    return null;
  }
}
