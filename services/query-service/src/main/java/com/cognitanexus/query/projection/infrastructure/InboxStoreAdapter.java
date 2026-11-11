package com.cognitanexus.query.projection.infrastructure;

import com.cognitanexus.query.platform.persistence.JsonCodec;
import com.cognitanexus.query.projection.application.ProjectionRepositories.InboxStore;
import com.cognitanexus.query.projection.domain.ConsumerProgress;
import com.cognitanexus.query.projection.domain.InboxEventRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.stereotype.Repository;

/**
 * Inbox 端口的 MyBatis 实现。
 *
 * <p>依据：编码规范 §2.2。适配器负责把持久化行对象翻译成领域记录，Mapper 只做 SQL。这样做的
 * 直接收益是：payload 的 JSON 解析只发生在一个地方，domain 层不需要知道数据库里存的是 jsonb
 * 还是 text。
 */
@Repository
public class InboxStoreAdapter implements InboxStore {

  /** 投影应用失败的重试上限。达到上限后停在 FAILED，等待人工处理而不是无限重试。 */
  private static final int FAILURE_LIMIT = 6;

  private final InboxMapper mapper;
  private final JsonCodec json;

  public InboxStoreAdapter(InboxMapper mapper, JsonCodec json) {
    this.mapper = mapper;
    this.json = json;
  }

  @Override
  public int insertIfAbsent(List<InboxEventRecord> events) {
    if (events == null || events.isEmpty()) {
      return 0;
    }
    List<InboxMapper.InboxRow> rows =
        events.stream()
            .map(
                event ->
                    new InboxMapper.InboxRow(
                        event.id(),
                        event.consumerId(),
                        event.eventId(),
                        event.eventType(),
                        event.schemaVersion(),
                        event.aggregateType(),
                        event.aggregateId(),
                        event.aggregateVersion(),
                        event.catalogRevision(),
                        event.occurredAt(),
                        event.traceId(),
                        json.write(event.payload()),
                        event.attempts()))
            .toList();
    return mapper.insertIfAbsent(rows);
  }

  @Override
  public List<InboxEventRecord> listPending(String consumerId, int limit) {
    return mapper.listPending(consumerId, limit).stream().map(this::toDomain).toList();
  }

  @Override
  public ConsumerProgress lockProgress(String consumerId) {
    return toDomain(mapper.lockProgress(consumerId));
  }

  @Override
  public ConsumerProgress readProgress(String consumerId) {
    return toDomain(mapper.readProgress(consumerId));
  }

  @Override
  public int observeHighestSeen(String consumerId, long revision) {
    return mapper.observeHighestSeen(consumerId, revision);
  }

  @Override
  public long advanceReceivedRevision(String consumerId) {
    Long advanced = mapper.advanceReceivedRevision(consumerId);
    return advanced == null ? 0L : advanced;
  }

  @Override
  public int advancePublishedRevision(String consumerId, long revision) {
    return mapper.advancePublishedRevision(consumerId, revision);
  }

  @Override
  public int markApplied(String id) {
    return mapper.markApplied(id);
  }

  @Override
  public int markFailed(String id, String error, int backoffSeconds) {
    return mapper.markRetryableFailure(id, error, backoffSeconds, FAILURE_LIMIT);
  }

  @Override
  public int markIsolated(String id, String reason) {
    return mapper.markIsolated(id, reason);
  }

  @Override
  public int rearmFailed(String consumerId, int maxAttempts) {
    return mapper.rearmFailed(consumerId, maxAttempts);
  }

  @Override
  public Map<String, Long> countByState(String consumerId) {
    return mapper.countByState(consumerId).stream()
        .collect(
            java.util.stream.Collectors.toMap(
                InboxMapper.StateCountRow::state,
                InboxMapper.StateCountRow::amount,
                (left, right) -> left));
  }

  @Override
  public List<InboxEventRecord> listAfterRevision(String consumerId, long revision, int limit) {
    return mapper.listAfterRevision(consumerId, revision, limit).stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  public long oldestIsolatedRevision(String consumerId) {
    return mapper.oldestIsolatedRevision(consumerId);
  }

  @Override
  public long firstBlockedRevisionAfter(String consumerId, long revision) {
    return mapper.firstBlockedRevisionAfter(consumerId, revision);
  }

  @Override
  public OptionalLong firstReceivedRevisionAfter(String consumerId, long revision) {
    Long value = mapper.firstReceivedRevisionAfter(consumerId, revision);
    return value == null ? OptionalLong.empty() : OptionalLong.of(value);
  }

  @Override
  public Optional<InboxEventRecord> findById(String id) {
    return Optional.ofNullable(mapper.findById(id)).map(this::toDomain);
  }

  private InboxEventRecord toDomain(InboxMapper.InboxRow row) {
    return new InboxEventRecord(
        row.id(),
        row.consumerId(),
        row.eventId(),
        row.eventType(),
        row.schemaVersion(),
        row.aggregateType(),
        row.aggregateId(),
        row.aggregateVersion(),
        row.catalogRevision(),
        row.occurredAt(),
        row.traceId(),
        json.readMap(row.payloadJson()),
        row.attempts());
  }

  private ConsumerProgress toDomain(InboxMapper.ProgressRow row) {
    if (row == null) {
      throw new IllegalStateException("消费者进度行缺失：qry_consumer_state 未被迁移正确初始化");
    }
    return new ConsumerProgress(
        row.consumerId(),
        row.activeGeneration(),
        row.receivedRevision(),
        row.highestSeenRevision(),
        row.publishedRevision(),
        row.receivedAt(),
        row.publishedAt(),
        row.updatedAt());
  }
}
