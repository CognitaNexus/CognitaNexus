package com.cognitanexus.core.identity.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 账号注销申请的持久化。 */
@Mapper
public interface ClosureMapper {

  record ClosureRow(
      String id,
      String accountId,
      String accountUsername,
      String reason,
      String status,
      Instant requestedAt,
      Instant effectiveAt,
      Instant decidedAt,
      String decidedBy,
      String decisionNote) {}

  @Insert(
      """
      INSERT INTO core_account_closure_request
        (id, account_id, reason, status, effective_at)
      VALUES
        (#{id}, #{accountId}, #{reason}, 'PENDING', now() + make_interval(days => #{coolingDays}))
      """)
  void insert(
      @Param("id") String id,
      @Param("accountId") String accountId,
      @Param("reason") String reason,
      @Param("coolingDays") int coolingDays);

  @Select(
      """
      SELECT c.id, c.account_id AS accountId, i.username AS accountUsername, c.reason, c.status,
             c.requested_at AS requestedAt, c.effective_at AS effectiveAt,
             c.decided_at AS decidedAt, c.decided_by AS decidedBy, c.decision_note AS decisionNote
      FROM core_account_closure_request c
      JOIN iam_account i ON i.id = c.account_id
      WHERE c.account_id = #{accountId} AND c.status = 'PENDING'
      """)
  Optional<ClosureRow> findOpenByAccount(@Param("accountId") String accountId);

  @Select(
      """
      SELECT c.id, c.account_id AS accountId, i.username AS accountUsername, c.reason, c.status,
             c.requested_at AS requestedAt, c.effective_at AS effectiveAt,
             c.decided_at AS decidedAt, c.decided_by AS decidedBy, c.decision_note AS decisionNote
      FROM core_account_closure_request c
      JOIN iam_account i ON i.id = c.account_id
      WHERE c.id = #{id}
      """)
  Optional<ClosureRow> findById(@Param("id") String id);

  @Select(
      """
      SELECT c.id, c.account_id AS accountId, i.username AS accountUsername, c.reason, c.status,
             c.requested_at AS requestedAt, c.effective_at AS effectiveAt,
             c.decided_at AS decidedAt, c.decided_by AS decidedBy, c.decision_note AS decisionNote
      FROM core_account_closure_request c
      JOIN iam_account i ON i.id = c.account_id
      WHERE c.status = 'PENDING'
      ORDER BY c.requested_at, c.id
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<ClosureRow> listPending(@Param("limit") int limit, @Param("offset") int offset);

  @Select("SELECT count(*) FROM core_account_closure_request WHERE status = 'PENDING'")
  long countPending();

  /**
   * 冷静期已届满、等待执行的申请。
   *
   * <p>时间条件同样用数据库 {@code now()}：应用层时钟与数据库时钟不一致时，用 Java 的
   * {@code Instant.now()} 判断会提前或推迟执行真正的停用动作。
   *
   * <p>不在这里加 {@code FOR UPDATE SKIP LOCKED}：执行语句本身带 {@code status = 'PENDING'
   * AND now() >= effective_at'} 条件，两个扫描者同时拿到同一行时只有一个能把状态改掉，
   * 另一个受影响 0 行、直接跳过。靠幂等而不是靠锁，避免长事务持锁堵住管理员队列查询。
   */
  @Select(
      """
      SELECT c.id, c.account_id AS accountId, i.username AS accountUsername, c.reason, c.status,
             c.requested_at AS requestedAt, c.effective_at AS effectiveAt,
             c.decided_at AS decidedAt, c.decided_by AS decidedBy, c.decision_note AS decisionNote
      FROM core_account_closure_request c
      JOIN iam_account i ON i.id = c.account_id
      WHERE c.status = 'PENDING' AND now() >= c.effective_at
      ORDER BY c.effective_at, c.id
      LIMIT #{limit}
      """)
  List<ClosureRow> listDue(@Param("limit") int limit);

  /** 本人撤销。条件带 account_id 与 PENDING：撤销不了别人的，也撤销不了已裁决的。 */
  @Update(
      """
      UPDATE core_account_closure_request
      SET status = 'CANCELLED', decided_at = now()
      WHERE id = #{id} AND account_id = #{accountId} AND status = 'PENDING'
      """)
  int cancel(@Param("id") String id, @Param("accountId") String accountId);

  /**
   * 裁决。冷静期未满时由 SQL 拒绝执行 APPROVE：
   *
   * <pre>
   *   AND (status &lt;&gt; 'PENDING' ... )
   * </pre>
   *
   * <p>把时间条件写进 WHERE 而不是只放在 Java 里：管理员与申请人可能在不同机器上，
   * 应用层用本地时钟判断会有一个时区差异带来的漏洞窗口。now() 来自数据库，唯一可信。
   */
  @Update(
      """
      UPDATE core_account_closure_request
      SET status = #{status}, decided_at = now(), decided_by = #{decidedBy,jdbcType=VARCHAR},
          decision_note = #{note}
      WHERE id = #{id}
        AND status = 'PENDING'
        AND (CAST(#{status} AS text) <> 'APPROVED' OR now() >= effective_at)
      """)
  int decide(
      @Param("id") String id,
      @Param("status") String status,
      @Param("decidedBy") String decidedBy,
      @Param("note") String note);
}
