package com.cognitanexus.query.projection.infrastructure;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Inbox 与发布水位的持久化。
 *
 * <p>依据：架构 V0.2 §6.3、§7.1。
 *
 * <p>本 Mapper 不提供删除事件正文的语句：事件保留是缺口补齐与重建回放的前提，删除后一旦出现
 * 缺口就无法自我修复，只能人工重放。保留策略由运维按部署清单执行，不放在应用代码里。
 */
@Mapper
public interface InboxMapper {

  /** Inbox 行。同时用作批量插入的参数类型：插入只多一个 payloadJson 字段，取值方式相同。 */
  record InboxRow(
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
      String payloadJson,
      int attempts) {}

  record ProgressRow(
      String consumerId,
      String activeGeneration,
      long receivedRevision,
      long highestSeenRevision,
      long publishedRevision,
      Instant receivedAt,
      Instant publishedAt,
      Instant updatedAt) {}

  record StateCountRow(String state, long amount) {}

  /**
   * 幂等批量插入。
   *
   * <p>冲突时 DO NOTHING 而不更新任何字段：同一 eventId 的重复投递必须不改变已发生过的处理
   * 状态，否则一次姗姗来迟的重投会把已 APPLIED 的事件退回 RECEIVED，投影被重复应用。
   */
  @Insert(
      """
      <script>
      INSERT INTO qry_inbox_event
        (id, consumer_id, event_id, event_type, schema_version, aggregate_type, aggregate_id,
         aggregate_version, catalog_revision, occurred_at, trace_id, payload)
      VALUES
      <foreach collection="events" item="e" separator=",">
        (#{e.id}, #{e.consumerId}, #{e.eventId}, #{e.eventType}, #{e.schemaVersion},
         #{e.aggregateType}, #{e.aggregateId}, #{e.aggregateVersion}, #{e.catalogRevision},
         #{e.occurredAt}, #{e.traceId}, #{e.payloadJson}::jsonb)
      </foreach>
      ON CONFLICT (consumer_id, event_id) DO NOTHING
      </script>
      """)
  int insertIfAbsent(@Param("events") List<InboxRow> events);

  @Update(
      """
      UPDATE qry_consumer_state
      SET highest_seen_revision = GREATEST(highest_seen_revision, #{revision}), updated_at = now()
      WHERE consumer_id = #{consumerId}
      """)
  int observeHighestSeen(@Param("consumerId") String consumerId, @Param("revision") long revision);

  /**
   * 按「连续」规则推进 receivedRevision。
   *
   * <p>用 row_number 与修订号的关系判断连续：若第 k 个已见修订恰好等于起始值 + k，说明从起始值
   * 到该修订之间一个都不缺，最大满足该条件的修订即新水位。缺口之后的修订即使已落库也不推进水位，
   * 这正是「不跳过毒事件伪造新水位」的实现方式。
   *
   * <p>放在一条 SQL 里而不是在 Java 里循环，是为了避免「每个事件一次往返」在高吞吐下的放大效应。
   */
  @Update(
      """
      WITH cur AS (
        SELECT consumer_id, received_revision
        FROM qry_consumer_state
        WHERE consumer_id = #{consumerId}
      ), contiguous AS (
        SELECT COALESCE(max(t.catalog_revision), cur.received_revision) AS advanced
        FROM cur
        LEFT JOIN (
          SELECT e.catalog_revision,
                 row_number() OVER (ORDER BY e.catalog_revision) AS seq
          FROM qry_inbox_event e
          JOIN cur c ON e.consumer_id = c.consumer_id
          WHERE e.catalog_revision > c.received_revision
        ) t ON t.catalog_revision = cur.received_revision + t.seq
        GROUP BY cur.received_revision
      )
      UPDATE qry_consumer_state s
      SET received_revision = c.advanced,
          received_at = CASE WHEN c.advanced > s.received_revision THEN now() ELSE s.received_at END,
          updated_at = now()
      FROM contiguous c
      WHERE s.consumer_id = #{consumerId}
      RETURNING s.received_revision
      """)
  Long advanceReceivedRevision(@Param("consumerId") String consumerId);

  /** 只前进不后退：乱序到达的旧事件不得把已发布水位拉回去。 */
  @Update(
      """
      UPDATE qry_consumer_state
      SET published_revision = GREATEST(published_revision, #{revision}),
          published_at = now(),
          updated_at = now()
      WHERE consumer_id = #{consumerId}
      """)
  int advancePublishedRevision(
      @Param("consumerId") String consumerId, @Param("revision") long revision);

  @Update(
      """
      UPDATE qry_inbox_event
      SET processing_state = 'APPLIED', applied_at = now(), last_error = NULL
      WHERE id = #{id} AND processing_state <> 'APPLIED'
      """)
  int markApplied(@Param("id") String id);

  @Update(
      """
      UPDATE qry_inbox_event
      SET processing_state = CASE WHEN attempts >= #{maxAttempts} THEN 'FAILED' ELSE 'RECEIVED' END,
          attempts = attempts + 1,
          next_attempt_at = now() + (interval '1 second' * #{backoffSeconds}),
          last_error = #{error}
      WHERE id = #{id}
      """)
  int markRetryableFailure(
      @Param("id") String id,
      @Param("error") String error,
      @Param("backoffSeconds") int backoffSeconds,
      @Param("maxAttempts") int maxAttempts);

  @Update(
      """
      UPDATE qry_inbox_event
      SET processing_state = 'ISOLATED', last_error = #{reason}, applied_at = NULL
      WHERE id = #{id}
      """)
  int markIsolated(@Param("id") String id, @Param("reason") String reason);

  /** 把已超退避窗口的 FAILED 行重新置为 RECEIVED，供调度周期重试。 */
  @Update(
      """
      UPDATE qry_inbox_event
      SET processing_state = 'RECEIVED', next_attempt_at = now()
      WHERE consumer_id = #{consumerId} AND processing_state = 'FAILED'
        AND next_attempt_at <= now()
        AND attempts < #{maxAttempts}
      """)
  int rearmFailed(@Param("consumerId") String consumerId, @Param("maxAttempts") int maxAttempts);

  @Select(
      """
      SELECT id, consumer_id, event_id, event_type, schema_version, aggregate_type, aggregate_id,
             aggregate_version, catalog_revision, occurred_at, trace_id, payload::text AS payload_json,
             attempts
      FROM qry_inbox_event
      WHERE consumer_id = #{consumerId}
        AND processing_state = 'RECEIVED'
        AND next_attempt_at <= now()
      ORDER BY catalog_revision
      LIMIT #{limit}
      """)
  List<InboxRow> listPending(@Param("consumerId") String consumerId, @Param("limit") int limit);

  @Select(
      """
      SELECT id, consumer_id, event_id, event_type, schema_version, aggregate_type, aggregate_id,
             aggregate_version, catalog_revision, occurred_at, trace_id, payload::text AS payload_json,
             attempts
      FROM qry_inbox_event
      WHERE consumer_id = #{consumerId} AND catalog_revision > #{revision}
      ORDER BY catalog_revision
      LIMIT #{limit}
      """)
  List<InboxRow> listAfterRevision(
      @Param("consumerId") String consumerId,
      @Param("revision") long revision,
      @Param("limit") int limit);

  @Select(
      """
      SELECT id, consumer_id, event_id, event_type, schema_version, aggregate_type, aggregate_id,
             aggregate_version, catalog_revision, occurred_at, trace_id, payload::text AS payload_json,
             attempts
      FROM qry_inbox_event
      WHERE id = #{id}
      """)
  InboxRow findById(@Param("id") String id);

  @Select(
      """
      SELECT * FROM qry_consumer_state WHERE consumer_id = #{consumerId} FOR UPDATE
      """)
  ProgressRow lockProgress(@Param("consumerId") String consumerId);

  @Select(
      """
      SELECT * FROM qry_consumer_state WHERE consumer_id = #{consumerId}
      """)
  ProgressRow readProgress(@Param("consumerId") String consumerId);

  @Select(
      """
      SELECT processing_state AS state, count(*) AS amount
      FROM qry_inbox_event
      WHERE consumer_id = #{consumerId}
      GROUP BY processing_state
      """)
  List<StateCountRow> countByState(@Param("consumerId") String consumerId);

  @Select(
      """
      SELECT COALESCE(min(catalog_revision), 0)
      FROM qry_inbox_event
      WHERE consumer_id = #{consumerId} AND processing_state = 'ISOLATED'
      """)
  long oldestIsolatedRevision(@Param("consumerId") String consumerId);

  /**
   * 该修订之后最早的「不能自动处理」事件修订号。
   *
   * <p>返回 0 表示没有阻塞。重建回放必须据此中止：带着一条隔离事件换上新的 generation，
   * 等于用一个看起来完整的新视图替换掉旧视图，而那条事件所代表的变更被永久丢失。
   */
  @Select(
      """
      SELECT COALESCE(min(catalog_revision), 0)
      FROM qry_inbox_event
      WHERE consumer_id = #{consumerId} AND catalog_revision > #{revision}
        AND processing_state IN ('ISOLATED', 'FAILED')
      """)
  long firstBlockedRevisionAfter(
      @Param("consumerId") String consumerId, @Param("revision") long revision);

  /**
   * 该修订之后最早一条已持久接收事件所占的修订值；无事件时返回 null。
   *
   * <p>与 {@link #firstBlockedRevisionAfter} 的区别是这里看的是「收到了什么」，而不是「哪条卡住了」。
   * 连续门控下，若权威侧某条事件被永久判死，本地就会先收到 N+k 而永远等不到 N+1：水位停在 N，
   * 所有新事件都被判为「还没轮到」。这个查询让那段空洞可以被读出来。
   *
   * <p>返回类型用 Long 而不是 0 兜底：0 是合法修订号之外的值，可以当哨兵，但「没有事件」与
   * 「最早事件在修订 0」在这里必须区分开才不至于误报空洞。
   */
  @Select(
      """
      SELECT min(catalog_revision)
      FROM qry_inbox_event
      WHERE consumer_id = #{consumerId} AND catalog_revision > #{revision}
      """)
  Long firstReceivedRevisionAfter(
      @Param("consumerId") String consumerId, @Param("revision") long revision);
}
