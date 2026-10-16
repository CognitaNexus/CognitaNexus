package com.cognitanexus.core.platform.audit;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 审计事件持久化。
 *
 * <p>依据：架构 §5.2（aud 只追加）。本 Mapper 不提供任何 UPDATE/DELETE 语句；数据库层另有
 * REVOKE 兜底，业务代码无法凭 Mapper 改写历史。
 */
@Mapper
public interface AuditMapper {

  record AuditRow(
      String id,
      String actorAccountId,
      String actorUsername,
      String action,
      String targetType,
      String targetId,
      String beforeValue,
      String afterValue,
      String reason,
      java.time.Instant occurredAt,
      String traceId) {}

  @Insert(
      """
      INSERT INTO aud_audit_event
        (id, actor_account_id, actor_username, action, target_type, target_id,
         before_value, after_value, reason, occurred_at, trace_id)
      VALUES
        (#{id}, #{actorAccountId}, #{actorUsername}, #{action}, #{targetType}, #{targetId},
         #{beforeValue}::jsonb, #{afterValue}::jsonb, #{reason}, now(), #{traceId})
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

  // 「#{p} IS NULL OR col = #{p}」这种可选条件必须给参数显式标注类型（CAST ... AS text）。
  // 参数为空串/空值时，PostgreSQL 从上下文推不出 $1 的类型，直接报
  // "could not determine data type of parameter $1"——而且只在「不传筛选条件」这个最常用的
  // 场景出现，带上条件反而正常，所以这类问题在按条件查询的验证里测不出来。
  @Select(
      """
      SELECT id, actor_account_id, actor_username, action, target_type, target_id,
             before_value::text AS before_value, after_value::text AS after_value,
             reason, occurred_at, trace_id
      FROM aud_audit_event
      WHERE (CAST(#{targetType} AS text) IS NULL OR target_type = #{targetType})
        AND (CAST(#{targetId} AS text) IS NULL OR target_id = #{targetId})
        AND (CAST(#{actorAccountId} AS text) IS NULL OR actor_account_id = #{actorAccountId})
      ORDER BY occurred_at DESC, id DESC
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<AuditRow> search(
      @Param("targetType") String targetType,
      @Param("targetId") String targetId,
      @Param("actorAccountId") String actorAccountId,
      @Param("limit") int limit,
      @Param("offset") int offset);

  @Select(
      """
      SELECT count(*) FROM aud_audit_event
      WHERE (CAST(#{targetType} AS text) IS NULL OR target_type = #{targetType})
        AND (CAST(#{targetId} AS text) IS NULL OR target_id = #{targetId})
        AND (CAST(#{actorAccountId} AS text) IS NULL OR actor_account_id = #{actorAccountId})
      """)
  long count(
      @Param("targetType") String targetType,
      @Param("targetId") String targetId,
      @Param("actorAccountId") String actorAccountId);
}
