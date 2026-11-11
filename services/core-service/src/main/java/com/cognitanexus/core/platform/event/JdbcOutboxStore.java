package com.cognitanexus.core.platform.event;

import com.cognitanexus.core.platform.persistence.JsonCodec;
import com.cognitanexus.core.platform.util.Identifiers;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 事务内写入与投递领取实现。
 *
 * <p>依据：架构 V0.2 §6.1、§6.3。写入使用 MANDATORY 传播：没有外层事务就直接失败，
 * 避免出现「事件已落库但业务回滚」的幽灵事件。
 */
@Component
public class JdbcOutboxStore implements OutboxStore {

  private final OutboxMapper outboxMapper;
  private final CatalogRevisionMapper revisionMapper;
  private final JsonCodec json;
  private final OutboxProperties properties;

  public JdbcOutboxStore(
      OutboxMapper outboxMapper,
      CatalogRevisionMapper revisionMapper,
      JsonCodec json,
      OutboxProperties properties) {
    this.outboxMapper = outboxMapper;
    this.revisionMapper = revisionMapper;
    this.json = json;
    this.properties = properties;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public long append(OutboxEvent.Builder builder) {
    long revision = revisionMapper.bumpRevision();
    String eventId = Identifiers.event();
    String outboxId = Identifiers.next("obx");
    outboxMapper.insertOutbox(
        outboxId,
        eventId,
        builder.eventType(),
        builder.schemaVersionValue(),
        builder.aggregateType(),
        builder.aggregateId(),
        builder.aggregateVersionValue(),
        revision,
        TraceIdHolder.current(),
        json.write(builder.payloadValue()));
    for (String consumer : consumers()) {
      outboxMapper.insertDelivery(Identifiers.next("obd"), outboxId, consumer);
    }
    return revision;
  }

  @Override
  @Transactional(readOnly = true)
  public long currentRevision() {
    return revisionMapper.currentRevision();
  }

  @Override
  @Transactional(readOnly = true)
  public long requiredPublicRevision() {
    return revisionMapper.requiredPublicRevision();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public long tightenPublicVisibility() {
    return revisionMapper.tightenPublicVisibility();
  }

  @Override
  public List<String> consumers() {
    return List.copyOf(properties.getConsumerEndpoints().keySet());
  }

  /**
   * 领取一批待投递事件。
   *
   * <p>领取与租约延长在同一个短事务内完成；HTTP 发送发生在事务外（架构 §6.1：事务内不访问外部 HTTP）。
   */
  @Override
  @Transactional
  public List<PendingDelivery> claimPending(String consumerId, int limit, int backoffSeconds) {
    List<OutboxMapper.OutboxRowDelivery> rows = outboxMapper.claimPending(consumerId, limit);
    if (rows.isEmpty()) {
      return List.of();
    }
    int leaseSeconds = (int) Math.max(1, properties.getLease().toSeconds());
    for (OutboxMapper.OutboxRowDelivery row : rows) {
      outboxMapper.markAttempt(row.deliveryId(), leaseSeconds);
    }
    return rows.stream()
        .map(
            row ->
                new PendingDelivery(
                    row.deliveryId(),
                    row.consumerId(),
                    row.outboxId(),
                    row.eventId(),
                    row.eventType(),
                    row.schemaVersion(),
                    row.aggregateType(),
                    row.aggregateId(),
                    row.aggregateVersion(),
                    row.catalogRevision(),
                    row.occurredAt(),
                    row.traceId(),
                    row.payload(),
                    row.attempts() + 1))
        .toList();
  }

  @Override
  @Transactional
  public void markDelivered(String deliveryId) {
    outboxMapper.markDelivered(deliveryId);
  }

  @Override
  @Transactional
  public void markFailed(String deliveryId, String error, int backoffSeconds, int maxAttempts) {
    outboxMapper.markFailed(deliveryId, truncate(error), Math.max(1, backoffSeconds), maxAttempts);
  }

  @Override
  @Transactional(readOnly = true)
  public long pendingCount(String consumerId) {
    return outboxMapper.pendingCount(consumerId);
  }

  @Override
  @Transactional(readOnly = true)
  public long oldestPendingRevision(String consumerId) {
    return outboxMapper.oldestPendingRevision(consumerId);
  }

  @Override
  @Transactional(readOnly = true)
  public long failedCount(String consumerId) {
    return outboxMapper.failedCount(consumerId);
  }

  @Override
  @Transactional(readOnly = true)
  public FailedRange failedRange(String consumerId) {
    long count = outboxMapper.failedCount(consumerId);
    if (count == 0) {
      return new FailedRange(0, 0, 0);
    }
    return new FailedRange(
        outboxMapper.oldestFailedRevision(consumerId),
        outboxMapper.newestFailedRevision(consumerId),
        count);
  }

  @Override
  @Transactional(readOnly = true)
  public List<FailedDelivery> failedDeliveries(String consumerId, int limit) {
    return outboxMapper.listFailedDeliveries(consumerId, Math.max(1, limit)).stream()
        .map(
            row ->
                new FailedDelivery(
                    row.deliveryId(),
                    row.catalogRevision(),
                    row.eventId(),
                    row.eventType(),
                    row.attempts(),
                    row.lastError(),
                    row.lastAttemptAt()))
        .toList();
  }

  @Override
  @Transactional
  public int requeueFailed(String consumerId, long upToRevision) {
    return outboxMapper.requeueFailedDeliveries(consumerId, upToRevision);
  }

  private String truncate(String error) {
    if (error == null) {
      return null;
    }
    return error.length() <= 500 ? error : error.substring(0, 500);
  }

  OutboxMapper mapper() {
    return outboxMapper;
  }
}
