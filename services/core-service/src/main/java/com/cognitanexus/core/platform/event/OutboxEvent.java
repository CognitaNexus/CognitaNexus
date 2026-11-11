package com.cognitanexus.core.platform.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outbox 事件正文。
 *
 * <p>依据：架构 V0.2 §6.3 事件最小契约。字段语义：
 *
 * <ul>
 *   <li>eventId：全局唯一消息标识，重试时保持不变；
 *   <li>eventType / schemaVersion：与业务实体版本不混用，未知版本在接收端转隔离待处理；
 *   <li>aggregateId / aggregateVersion：用于防止旧事件覆盖新版本；
 *   <li>catalogRevision：核心公开目录的单调提交次序，由事务内行锁保证与提交序一致；
 *   <li>occurredAt / traceId：事件记录时刻与追踪信息，不是外部来源更新时间；
 *   <li>payload：完整的受影响公开投影，不含口令、材料与私人联系方式。
 * </ul>
 */
public record OutboxEvent(
    String eventId,
    String eventType,
    int schemaVersion,
    String aggregateType,
    String aggregateId,
    long aggregateVersion,
    long catalogRevision,
    Instant occurredAt,
    String traceId,
    Map<String, Object> payload) {

  public OutboxEvent {
    payload = payload == null ? Map.of() : Map.copyOf(payload);
  }

  public static Builder builder(String eventType, String aggregateType, String aggregateId) {
    return new Builder(eventType, aggregateType, aggregateId);
  }

  public Map<String, Object> toWire() {
    Map<String, Object> wire = new LinkedHashMap<>();
    wire.put("eventId", eventId);
    wire.put("eventType", eventType);
    wire.put("schemaVersion", schemaVersion);
    wire.put("aggregateType", aggregateType);
    wire.put("aggregateId", aggregateId);
    wire.put("aggregateVersion", aggregateVersion);
    wire.put("catalogRevision", catalogRevision);
    wire.put("occurredAt", occurredAt == null ? null : occurredAt.toString());
    wire.put("traceId", traceId);
    wire.put("payload", payload);
    return wire;
  }

  /** 事件构造器。catalogRevision 与 occurredAt 由写入端在事务内确定，不由业务代码猜。 */
  public static final class Builder {
    private final String eventType;
    private final String aggregateType;
    private final String aggregateId;
    private int schemaVersion = 1;
    private long aggregateVersion = 1;
    private Map<String, Object> payload = new LinkedHashMap<>();

    private Builder(String eventType, String aggregateType, String aggregateId) {
      this.eventType = eventType;
      this.aggregateType = aggregateType;
      this.aggregateId = aggregateId;
    }

    public Builder schemaVersion(int value) {
      this.schemaVersion = value;
      return this;
    }

    public Builder aggregateVersion(long value) {
      this.aggregateVersion = value;
      return this;
    }

    public Builder payload(Map<String, Object> value) {
      this.payload = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
      return this;
    }

    public Builder put(String key, Object value) {
      this.payload.put(key, value);
      return this;
    }

    public String eventType() {
      return eventType;
    }

    public String aggregateType() {
      return aggregateType;
    }

    public String aggregateId() {
      return aggregateId;
    }

    public int schemaVersionValue() {
      return schemaVersion;
    }

    public long aggregateVersionValue() {
      return aggregateVersion;
    }

    public Map<String, Object> payloadValue() {
      return payload;
    }
  }
}
