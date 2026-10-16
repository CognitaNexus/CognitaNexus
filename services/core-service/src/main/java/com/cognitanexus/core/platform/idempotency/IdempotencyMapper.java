package com.cognitanexus.core.platform.idempotency;

import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 管理幂等记录持久化。
 *
 * <p>依据：架构 V0.2 §10.4。唯一键为 (actor_id, operation, idempotency_key)；冲突时插入失败，
 * 由调用方读取既有记录判断「同内容返回原结果」还是「异内容 409」。
 */
@Mapper
public interface IdempotencyMapper {

  record Row(
      String id,
      String actorId,
      String operation,
      String idempotencyKey,
      String requestHash,
      String resultReference) {}

  /**
   * 预留幂等键。
   *
   * <p>刻意使用 ON CONFLICT DO NOTHING 而不是让唯一约束抛错：PostgreSQL 事务中一旦出现错误就进入
   * 中止状态，捕获异常后无法继续用同一事务读取既有记录。改用「影响行数是否为 1」判断是否为本键
   * 的首次占用，并发时后到者会阻塞到先到者提交，再返回 0 行并读到已提交记录。
   */
  @Insert(
      """
      INSERT INTO evt_idempotency_record
        (id, actor_id, operation, idempotency_key, request_hash, result_reference, created_at, expires_at)
      VALUES
        (#{id}, #{actorId}, #{operation}, #{idempotencyKey}, #{requestHash}, #{resultReference},
         now(), now() + (interval '1 hour' * #{ttlHours}))
      ON CONFLICT (actor_id, operation, idempotency_key) DO NOTHING
      """)
  int insert(
      @Param("id") String id,
      @Param("actorId") String actorId,
      @Param("operation") String operation,
      @Param("idempotencyKey") String idempotencyKey,
      @Param("requestHash") String requestHash,
      @Param("resultReference") String resultReference,
      @Param("ttlHours") int ttlHours);

  @Select(
      """
      SELECT id, actor_id, operation, idempotency_key, request_hash, result_reference
      FROM evt_idempotency_record
      WHERE actor_id = #{actorId} AND operation = #{operation} AND idempotency_key = #{idempotencyKey}
      """)
  Optional<Row> find(
      @Param("actorId") String actorId,
      @Param("operation") String operation,
      @Param("idempotencyKey") String idempotencyKey);

  /** 业务结果产生后回填引用，使重放请求能直接返回原对象。 */
  @Update(
      """
      UPDATE evt_idempotency_record
      SET result_reference = #{resultReference}
      WHERE actor_id = #{actorId} AND operation = #{operation} AND idempotency_key = #{idempotencyKey}
      """)
  int attachResult(
      @Param("actorId") String actorId,
      @Param("operation") String operation,
      @Param("idempotencyKey") String idempotencyKey,
      @Param("resultReference") String resultReference);
}
