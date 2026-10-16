package com.cognitanexus.core.platform.event;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Outbox 正文与投递状态持久化。
 *
 * <p>依据：架构 V0.2 §6.3、§5.2。事件正文只追加，投递状态可变；本 Mapper 不提供对
 * evt_outbox 的 UPDATE/DELETE，数据库层另有 REVOKE 兜底。
 */
@Mapper
public interface OutboxMapper {

  record OutboxRow(
      String id,
      String eventId,
      String eventType,
      int schemaVersion,
      String aggregateType,
      String aggregateId,
      long aggregateVersion,
      long catalogRevision,
      Instant occurredAt,
      String traceId,
      String payload) {}

  @Insert(
      """
      INSERT INTO evt_outbox
        (id, event_id, event_type, schema_version, aggregate_type, aggregate_id,
         aggregate_version, catalog_revision, occurred_at, trace_id, payload)
      VALUES
        (#{id}, #{eventId}, #{eventType}, #{schemaVersion}, #{aggregateType}, #{aggregateId},
         #{aggregateVersion}, #{catalogRevision}, now(), #{traceId}, #{payload}::jsonb)
      """)
  void insertOutbox(
      @Param("id") String id,
      @Param("eventId") String eventId,
      @Param("eventType") String eventType,
      @Param("schemaVersion") int schemaVersion,
      @Param("aggregateType") String aggregateType,
      @Param("aggregateId") String aggregateId,
      @Param("aggregateVersion") long aggregateVersion,
      @Param("catalogRevision") long catalogRevision,
      @Param("traceId") String traceId,
      @Param("payload") String payload);

  @Insert(
      """
      INSERT INTO evt_outbox_delivery (id, outbox_id, consumer_id, status, attempts, next_attempt_at)
      VALUES (#{id}, #{outboxId}, #{consumerId}, 'PENDING', 0, now())
      ON CONFLICT (outbox_id, consumer_id) DO NOTHING
      """)
  void insertDelivery(
      @Param("id") String id,
      @Param("outboxId") String outboxId,
      @Param("consumerId") String consumerId);

  /**
   * 领取待投递事件。
   *
   * <p>三条约束同时成立才可领取：
   *
   * <ol>
   *   <li>该行自身已到重试时刻且未被他人持有租约；
   *   <li>不存在同一接收者「更旧且尚未就绪」的待投递事件——这实现队首阻塞，
   *       保证同一接收者按修订顺序投递（架构 §6.3）；
   *   <li>行锁可跳过，避免多实例投递器互相阻塞。
   * </ol>
   */
  @Select(
      """
      SELECT d.id            AS delivery_id,
             d.consumer_id   AS consumer_id,
             o.id            AS outbox_id,
             o.event_id      AS event_id,
             o.event_type    AS event_type,
             o.schema_version AS schema_version,
             o.aggregate_type AS aggregate_type,
             o.aggregate_id  AS aggregate_id,
             o.aggregate_version AS aggregate_version,
             o.catalog_revision AS catalog_revision,
             o.occurred_at   AS occurred_at,
             o.trace_id      AS trace_id,
             o.payload::text AS payload,
             d.attempts      AS attempts
      FROM evt_outbox_delivery d
      JOIN evt_outbox o ON o.id = d.outbox_id
      WHERE d.consumer_id = #{consumerId}
        AND d.status = 'PENDING'
        AND d.next_attempt_at <= now()
        AND (d.lease_until IS NULL OR d.lease_until <= now())
        AND NOT EXISTS (
              SELECT 1
              FROM evt_outbox_delivery older_d
              JOIN evt_outbox older_o ON older_o.id = older_d.outbox_id
              WHERE older_d.consumer_id = d.consumer_id
                AND older_d.status = 'PENDING'
                AND older_o.catalog_revision < o.catalog_revision
                AND (older_d.next_attempt_at > now()
                     OR (older_d.lease_until IS NOT NULL AND older_d.lease_until > now()))
            )
      ORDER BY o.catalog_revision, o.id
      LIMIT #{limit}
      FOR UPDATE OF d SKIP LOCKED
      """)
  List<OutboxRowDelivery> claimPending(
      @Param("consumerId") String consumerId, @Param("limit") int limit);

  /** 领取结果行，含投递状态字段。 */
  record OutboxRowDelivery(
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
      String payload,
      int attempts) {}

  @Update(
      """
      UPDATE evt_outbox_delivery
      SET attempts = attempts + 1,
          last_attempt_at = now(),
          lease_until = now() + (interval '1 second' * #{leaseSeconds})
      WHERE id = #{deliveryId}
      """)
  int markAttempt(@Param("deliveryId") String deliveryId, @Param("leaseSeconds") int leaseSeconds);

  @Update(
      """
      UPDATE evt_outbox_delivery
      SET status = 'DELIVERED', delivered_at = now(), lease_until = NULL, last_error = NULL
      WHERE id = #{deliveryId}
      """)
  int markDelivered(@Param("deliveryId") String deliveryId);

  @Update(
      """
      UPDATE evt_outbox_delivery
      SET status = CASE WHEN attempts >= #{maxAttempts} THEN 'FAILED' ELSE 'PENDING' END,
          lease_until = NULL,
          next_attempt_at = now() + (interval '1 second' * #{backoffSeconds}),
          last_error = #{error}
      WHERE id = #{deliveryId}
      """)
  int markFailed(
      @Param("deliveryId") String deliveryId,
      @Param("error") String error,
      @Param("backoffSeconds") int backoffSeconds,
      @Param("maxAttempts") int maxAttempts);

  @Select(
      """
      SELECT count(*) FROM evt_outbox_delivery
      WHERE consumer_id = #{consumerId} AND status = 'PENDING'
      """)
  long pendingCount(@Param("consumerId") String consumerId);

  @Select(
      """
      SELECT COALESCE(min(o.catalog_revision), 0)
      FROM evt_outbox_delivery d
      JOIN evt_outbox o ON o.id = d.outbox_id
      WHERE d.consumer_id = #{consumerId} AND d.status = 'PENDING'
      """)
  long oldestPendingRevision(@Param("consumerId") String consumerId);

  @Select(
      """
      SELECT count(*) FROM evt_outbox_delivery
      WHERE consumer_id = #{consumerId} AND status = 'FAILED'
      """)
  long failedCount(@Param("consumerId") String consumerId);

  /**
   * 死信重投：把该接收者已判 FAILED 的投递重新排回正常队列。
   *
   * <p>为什么必须存在这一步：投递次数上限（{@code cn.outbox.max-attempts}）是双刃的。有上限，
   * 消费者长时间下线时投递器不会永远敲一个死端口、也不会把积压无限期地堵在队首；但一旦某条
   * 事件被判 FAILED，它就<b>永久退出</b>投递队列，此后修订序列上出现空洞。投影侧的连续修订
   * 门控会因此永远无法推进——不是拒绝服务，而是静默停留在旧水位。恢复手段只有两种：把死信
   * 重投回队，或整体重建投影。这里提供前者，代价远低于后者。
   *
   * <p>事件正文在 evt_outbox 中只追加、从不删除，所以重投不会丢失任何内容；只需把投递状态行
   * 复位。attempts 归零而不是保留：重投的前提是「消费者现在可达了」，沿用旧计数会让下一轮失败
   * 立刻再次判死，重投就成了空转。
   *
   * <p>用 {@code upToRevision} 而非「全部」划定范围，是为了让调用方可以只重投「重建基线之前」
   * 的那一段：重建之后的修订本来就会由增量链路送达，重复投递虽然幂等但会白跑一轮。
   */
  @Update(
      """
      UPDATE evt_outbox_delivery d
      SET status = 'PENDING',
          attempts = 0,
          next_attempt_at = now(),
          lease_until = NULL,
          last_error = NULL
      FROM evt_outbox o
      WHERE o.id = d.outbox_id
        AND d.consumer_id = #{consumerId}
        AND d.status = 'FAILED'
        AND o.catalog_revision <= #{upToRevision}
      """)
  int requeueFailedDeliveries(
      @Param("consumerId") String consumerId, @Param("upToRevision") long upToRevision);

  /** 某接收者死信的最小修订值；0 表示无死信。 */
  @Select(
      """
      SELECT COALESCE(min(o.catalog_revision), 0)
      FROM evt_outbox_delivery d
      JOIN evt_outbox o ON o.id = d.outbox_id
      WHERE d.consumer_id = #{consumerId} AND d.status = 'FAILED'
      """)
  long oldestFailedRevision(@Param("consumerId") String consumerId);

  /** 某接收者死信的最大修订值；0 表示无死信。 */
  @Select(
      """
      SELECT COALESCE(max(o.catalog_revision), 0)
      FROM evt_outbox_delivery d
      JOIN evt_outbox o ON o.id = d.outbox_id
      WHERE d.consumer_id = #{consumerId} AND d.status = 'FAILED'
      """)
  long newestFailedRevision(@Param("consumerId") String consumerId);

  /** 死信明细，含最后一次失败原因，供运维定位「为什么消费者收不到」。 */
  @Select(
      """
      SELECT d.id AS delivery_id, o.catalog_revision AS catalog_revision,
             o.event_id AS event_id, o.event_type AS event_type,
             d.attempts AS attempts, d.last_error AS last_error, d.last_attempt_at AS last_attempt_at
      FROM evt_outbox_delivery d
      JOIN evt_outbox o ON o.id = d.outbox_id
      WHERE d.consumer_id = #{consumerId} AND d.status = 'FAILED'
      ORDER BY o.catalog_revision
      LIMIT #{limit}
      """)
  List<FailedDeliveryRow> listFailedDeliveries(
      @Param("consumerId") String consumerId, @Param("limit") int limit);

  /** 死信明细行。 */
  record FailedDeliveryRow(
      String deliveryId,
      long catalogRevision,
      String eventId,
      String eventType,
      int attempts,
      String lastError,
      Instant lastAttemptAt) {}

  // 可选参数显式标注类型，理由见 AuditMapper：参数为空时 PostgreSQL 推不出 $1 的类型。
  // 这里正常路径总会传值，所以缺陷从未暴露，但不该靠「恰好没人传 null」维持正确性。
  @Select(
      """
      SELECT o.id, o.event_id, o.event_type, o.schema_version, o.aggregate_type, o.aggregate_id,
             o.aggregate_version, o.catalog_revision, o.occurred_at, o.trace_id, o.payload::text AS payload
      FROM evt_outbox o
      WHERE (CAST(#{sinceRevision} AS bigint) IS NULL OR o.catalog_revision > #{sinceRevision})
      ORDER BY o.catalog_revision
      LIMIT #{limit}
      """)
  List<OutboxRow> listSince(@Param("sinceRevision") Long sinceRevision, @Param("limit") int limit);
}
