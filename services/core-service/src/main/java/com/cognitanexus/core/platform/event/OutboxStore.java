package com.cognitanexus.core.platform.event;

import java.time.Instant;
import java.util.List;

/**
 * 事务内事件写入端口。
 *
 * <p>依据：架构 V0.2 §6.1、§6.3。凡影响公开检索/统计/图的核心事务，必须在同一本地事务内
 * 写 Outbox，不依赖内存监听器或 afterCommit 回调——进程在提交与投递之间崩溃时，只有落库的
 * Outbox 能在重启后继续投递。
 */
public interface OutboxStore {

  /**
   * 追加一个事件。
   *
   * <p>在事务内推进目录修订计数器并写入事件正文与每个接收者的投递状态；
   * 调用方必须已处于事务中。
   *
   * @return 本次事件占用的 catalogRevision
   */
  long append(OutboxEvent.Builder builder);

  /** 读取当前权威目录修订值。 */
  long currentRevision();

  /** 读取权威公开水位。query 公开请求据此判断是否需要返回 503 VIEW_UPDATING。 */
  long requiredPublicRevision();

  /** 收紧公开可见性：把权威公开水位推进到当前修订值。 */
  long tightenPublicVisibility();

  /** 供投递器使用：待投递事件及其目标接收者。 */
  record PendingDelivery(
      String deliveryId,
      String consumerId,
      String outboxId,
      String eventId,
      String eventType,
      int schemaVersion,
      String aggregateType,
      String aggregateId,
      long aggregateVersion,
      long catalogRevision,
      Instant occurredAt,
      String traceId,
      String payloadJson,
      int attempts) {}

  List<String> consumers();

  /** 在短事务中领取一批待投递事件；返回后事务即结束，HTTP 发送在事务外完成。 */
  List<PendingDelivery> claimPending(String consumerId, int limit, int backoffSeconds);

  void markDelivered(String deliveryId);

  /** 记录一次失败并写入退避时刻；attempts 已达上限时转为 FAILED，等待人工重放。 */
  void markFailed(String deliveryId, String error, int backoffSeconds, int maxAttempts);

  /** 某接收者的待投递事件数（积压指标）。 */
  long pendingCount(String consumerId);

  /** 某接收者最老的未达事件修订值；0 表示无积压。 */
  long oldestPendingRevision(String consumerId);

  /** 某接收者已转为 FAILED、等待人工重放的事件数。 */
  long failedCount(String consumerId);

  /** 某接收者死信覆盖的修订区间；无死信时两者均为 0。 */
  record FailedRange(long oldestRevision, long newestRevision, long count) {

    public boolean empty() {
      return count == 0;
    }
  }

  /** 死信覆盖的修订区间，用于判断「这一段的变更是否已经缺失」。 */
  FailedRange failedRange(String consumerId);

  /** 死信明细：某条事件在某个接收者上被判定永久失败。 */
  record FailedDelivery(
      String deliveryId,
      long catalogRevision,
      String eventId,
      String eventType,
      int attempts,
      String lastError,
      Instant lastAttemptAt) {}

  /** 死信明细，最多 limit 条，按修订升序。 */
  List<FailedDelivery> failedDeliveries(String consumerId, int limit);

  /**
   * 把该接收者修订不超过 upToRevision 的死信重新排回队列。
   *
   * <p>仅在事件正文仍在 Outbox（只追加、从无删除）时成立，因此重投不会丢内容，只是重跑一遍。
   * 消费方按 (consumerId, eventId) 去重，重复投递安全。
   *
   * @return 实际重投的投递行数
   */
  int requeueFailed(String consumerId, long upToRevision);
}
