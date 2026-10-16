package com.cognitanexus.ingestion.platform.audit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 采集管理审计持久化。
 *
 * <p>依据：编码规范 §5.1。本映射器<b>不提供</b> UPDATE/DELETE：审计表是只追加的，
 * 数据库层另有 REVOKE 兜底（见 V2 迁移），两道限制同时存在。
 */
@Mapper
public interface AuditMapper {

  @Insert(
      """
      INSERT INTO ing_audit_event
        (id, actor_account_id, actor_username, action, target_type, target_id, before_value,
         after_value, reason, trace_id)
      VALUES
        (#{id}, #{actorAccountId}, #{actorUsername}, #{action}, #{targetType}, #{targetId},
         #{beforeValue}::jsonb, #{afterValue}::jsonb, #{reason}, #{traceId})
      """)
  void insert(
      @Param("id") String id,
      @Param("actorAccountId") String actorAccountId,
      @Param("actorUsername") String actorUsername,
      @Param("action") String action,
      @Param("targetType") String targetType,
      @Param("targetId") String targetId,
      @Param("beforeValue") String beforeValue,
      @Param("afterValue") String afterValue,
      @Param("reason") String reason,
      @Param("traceId") String traceId);

  record AuditRow(
      String id,
      String actorAccountId,
      String actorUsername,
      String action,
      String targetType,
      String targetId,
      String beforeValueJson,
      String afterValueJson,
      String reason,
      Instant occurredAt) {}

  @Select(
      """
      SELECT id, actor_account_id, actor_username, action, target_type, target_id,
             before_value::text AS before_value_json, after_value::text AS after_value_json,
             reason, occurred_at
      FROM ing_audit_event
      WHERE (#{targetType}::text IS NULL OR target_type = #{targetType})
        AND (#{targetId}::text IS NULL OR target_id = #{targetId})
      ORDER BY occurred_at DESC
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<AuditRow> search(
      @Param("targetType") String targetType,
      @Param("targetId") String targetId,
      @Param("limit") int limit,
      @Param("offset") int offset);

  @Select(
      """
      SELECT count(*) FROM ing_audit_event
      WHERE (#{targetType}::text IS NULL OR target_type = #{targetType})
        AND (#{targetId}::text IS NULL OR target_id = #{targetId})
      """)
  long count(@Param("targetType") String targetType, @Param("targetId") String targetId);

  @Select("SELECT now()")
  Optional<Instant> now();
}
