package com.cognitanexus.query.projection.application;

import com.cognitanexus.query.platform.util.Identifiers;
import com.cognitanexus.query.projection.api.InboxRequests.BatchReceipt;
import com.cognitanexus.query.projection.api.InboxRequests.EventRequest;
import com.cognitanexus.query.projection.application.ProjectionRepositories.InboxStore;
import com.cognitanexus.query.projection.domain.ConsumerProgress;
import com.cognitanexus.query.projection.domain.InboxEventRecord;
import com.cognitanexus.sharedkernel.BusinessException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Inbox 接收用例。
 *
 * <p>依据：架构 V0.2 §6.1、§6.3。顺序是「先提交入库、后计算投影、最后 ACK」：
 *
 * <ol>
 *   <li>在独立事务内幂等写入事件并推进接收水位；
 *   <li>事务外按连续修订顺序应用投影（可能访问 core，因此不能在事务内做）；
 *   <li>全部完成后才向 core 返回成功。若投影应用失败也不回滚接收——事件已持久化，下一个调度
 *       周期会继续尝试，而返回失败会让 core 重投同一批事件（幂等，不会重复执行）。
 * </ol>
 *
 * <p>批次内出现重复 eventId 时先就地折叠：同一批次里同一个事件出现两次，说明投递方或代理有
 * 重放行为，落库前折叠可以避免把「一次投递」变成两次尝试计数。
 */
@Service
public class InboxReceiver {

  private static final Logger log = LoggerFactory.getLogger(InboxReceiver.class);

  private final InboxStore inbox;
  private final InboxUnitOfWork unitOfWork;
  private final ProjectionPublisher publisher;
  private final ProjectionProperties properties;

  public InboxReceiver(
      InboxStore inbox,
      InboxUnitOfWork unitOfWork,
      ProjectionPublisher publisher,
      ProjectionProperties properties) {
    this.inbox = inbox;
    this.unitOfWork = unitOfWork;
    this.publisher = publisher;
    this.properties = properties;
  }

  public BatchReceipt receive(String consumerId, List<EventRequest> events) {
    if (!properties.consumerId().equals(consumerId)) {
      throw BusinessException.invalidArgument(
          "consumerId 与本服务配置不一致：本服务是 " + properties.consumerId() + " 的接收端");
    }
    List<InboxEventRecord> records = new ArrayList<>(events.size());
    Set<String> seenInBatch = new HashSet<>();
    for (EventRequest event : events) {
      if (event.catalogRevision() == null || event.catalogRevision() <= 0) {
        throw BusinessException.invalidArgument("事件缺少有效的 catalogRevision，无法排序与推进水位");
      }
      if (!seenInBatch.add(event.eventId())) {
        continue;
      }
      records.add(
          new InboxEventRecord(
              Identifiers.next("ibe"),
              consumerId,
              event.eventId(),
              event.eventType(),
              event.schemaVersion(),
              event.aggregateType() == null ? "UNKNOWN" : event.aggregateType(),
              event.aggregateId() == null ? "" : event.aggregateId(),
              event.aggregateVersion() == null ? 1L : event.aggregateVersion(),
              event.catalogRevision(),
              parseInstant(event.occurredAt()),
              event.traceId(),
              event.payload() == null ? java.util.Map.of() : event.payload(),
              0));
    }

    int inserted = unitOfWork.persist(consumerId, records);
    int duplicated = records.size() - inserted;

    ProjectionPublisher.PublishOutcome outcome =
        publisher.publishPending(consumerId, properties.publishBatchPerBatch());

    ConsumerProgress progress = inbox.readProgress(consumerId);
    boolean pendingRemaining = progress.receivedRevision() > progress.publishedRevision();
    if (pendingRemaining) {
      // 明确报告「已接收但尚未发布」：回执里的 received 只代表持久接收，
      // 若不说清这一点，调用方会把「已接收」读成「已生效」。
      log.info(
          "事件已持久接收但投影尚未追平 consumer={} received={} published={}",
          consumerId,
          progress.receivedRevision(),
          progress.publishedRevision());
    }
    return new BatchReceipt(
        records.size(),
        duplicated,
        outcome.applied(),
        outcome.isolated(),
        pendingRemaining,
        progress.receivedRevision(),
        progress.publishedRevision(),
        "received 表示已持久接收，不等于已发布；publishedRevision 之前的内容才可对外查询");
  }

  /**
   * 事件发生时刻解析。
   *
   * <p>解析失败返回 null 而不是退化为 now()：occurredAt 是「事件记录时刻」，填成处理时刻会让
   * 传播延迟的度量凭空变好，而这正是容量验收要测的量。
   */
  private static Instant parseInstant(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (RuntimeException ex) {
      return null;
    }
  }
}
