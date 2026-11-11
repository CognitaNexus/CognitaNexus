package com.cognitanexus.core.catalog.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 来源展示许可变更作业（ADR-001 §4）。
 *
 * <p>存在这张表的原因是「一次撤权可能涉及上百万条成果」这个事实本身：一次大事务既改不完、
 * 也回滚不起；而调用方必须能区分「请求已受理但一条都没落地」与「已全部落地」。
 * 作业把这两件事分开表达——状态与两个计数回答「落地了多少」，来源的
 * {@code display_license_status} 回答「公开读路径是否已经被阻断」。
 *
 * <p>阻断不依赖本表的进度：撤权受理的同事务就把来源标成 REVOKED，公开导出条件立刻排除了
 * 依赖它的成果。这使「逐批收紧」可以中断、可以续跑，而不必担心中断期间数据仍对外可见。
 */
@Mapper
public interface SourceRevocationMapper {

  String STATUS_RUNNING = "RUNNING";
  String STATUS_RUNNING_RESTORE = "RUNNING_RESTORE";
  String STATUS_SUCCEEDED = "SUCCEEDED";
  String STATUS_FAILED = "FAILED";

  /** 作业行。 */
  record RevocationRow(
      String id,
      String sourceId,
      String requestedBy,
      String reason,
      long targetCount,
      long tightenedCount,
      String status,
      String lastError,
      Instant createdAt,
      Instant updatedAt,
      Instant finishedAt) {}

  @Insert(
      """
      INSERT INTO core_source_display_revocation
        (id, source_id, requested_by, reason, target_count, tightened_count, status)
      VALUES
        (#{id}, #{sourceId}, #{requestedBy}, #{reason}, #{targetCount}, 0, #{status})
      """)
  int insertJob(
      @Param("id") String id,
      @Param("sourceId") String sourceId,
      @Param("requestedBy") String requestedBy,
      @Param("reason") String reason,
      @Param("targetCount") long targetCount,
      @Param("status") String status);

  @Select(
      """
      SELECT id              AS id,
             source_id       AS sourceId,
             requested_by    AS requestedBy,
             reason          AS reason,
             target_count    AS targetCount,
             tightened_count AS tightenedCount,
             status          AS status,
             last_error      AS lastError,
             created_at      AS createdAt,
             updated_at      AS updatedAt,
             finished_at     AS finishedAt
      FROM core_source_display_revocation
      WHERE id = #{id}
      """)
  Optional<RevocationRow> findById(@Param("id") String id);

  /**
   * 取该来源当前未完成的作业。
   *
   * <p>用 {@code status IN (...)} 与部分唯一索引同形：索引保证最多一行，这里不必再 ORDER BY。
   */
  @Select(
      """
      SELECT id              AS id,
             source_id       AS sourceId,
             requested_by    AS requestedBy,
             reason          AS reason,
             target_count    AS targetCount,
             tightened_count AS tightenedCount,
             status          AS status,
             last_error      AS lastError,
             created_at      AS createdAt,
             updated_at      AS updatedAt,
             finished_at     AS finishedAt
      FROM core_source_display_revocation
      WHERE source_id = #{sourceId}
        AND status IN ('RUNNING', 'FAILED', 'RUNNING_RESTORE')
      """)
  Optional<RevocationRow> findActiveBySource(@Param("sourceId") String sourceId);

  @Select(
      """
      SELECT id              AS id,
             source_id       AS sourceId,
             requested_by    AS requestedBy,
             reason          AS reason,
             target_count    AS targetCount,
             tightened_count AS tightenedCount,
             status          AS status,
             last_error      AS lastError,
             created_at      AS createdAt,
             updated_at      AS updatedAt,
             finished_at     AS finishedAt
      FROM core_source_display_revocation
      WHERE source_id = #{sourceId}
      ORDER BY created_at DESC
      LIMIT #{limit}
      """)
  List<RevocationRow> listBySource(@Param("sourceId") String sourceId, @Param("limit") int limit);

  /**
   * 累加已处理条数。
   *
   * <p>用 {@code tightened_count + #{delta}} 而不是由调用方回写绝对值：续跑时若某批被重试，
   * 绝对值写法会把中间失败的部分算漏或算重，累计写法则只在真正提交了成果之后加。
   */
  @Update(
      """
      UPDATE core_source_display_revocation
      SET tightened_count = tightened_count + #{delta}, updated_at = now()
      WHERE id = #{id}
      """)
  int addProcessed(@Param("id") String id, @Param("delta") int delta);

  @Update(
      """
      UPDATE core_source_display_revocation
      SET status = #{status},
          last_error = #{lastError},
          updated_at = now(),
          finished_at = CASE WHEN #{status} IN ('SUCCEEDED') THEN now() ELSE finished_at END
      WHERE id = #{id}
      """)
  int setStatus(
      @Param("id") String id, @Param("status") String status, @Param("lastError") String lastError);

  /**
   * 中断后重新置为执行中。
   *
   * <p>必须同时清掉 last_error：留着上一次的失败原因，接口会把「上一轮为什么失败」显示成
   * 「这一轮正在失败」，运维会去查一个已经不存在的问题。
   */
  @Update(
      """
      UPDATE core_source_display_revocation
      SET status = #{status}, last_error = NULL, updated_at = now(), finished_at = NULL
      WHERE id = #{id}
      """)
  int resume(@Param("id") String id, @Param("status") String status);
}
