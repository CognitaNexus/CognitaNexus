package com.cognitanexus.core.identity.infrastructure;

import com.cognitanexus.core.identity.domain.PortalAuthority;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 门户对象级授权持久化。
 *
 * <p>依据：架构 V0.2 §5.3、AC-IMPL-FR05-03。生效授权唯一性由部分唯一索引
 * <code>uk_iam_portal_authority_active</code> 保证；这里额外使用
 * <code>ON CONFLICT DO NOTHING</code> 让「竞争认领」表现为可解释的 0 行影响，
 * 而不是让 PostgreSQL 事务进入中止状态。
 */
@Mapper
public interface PortalAuthorityMapper {

  record AuthorityRow(
      String id,
      String accountId,
      String scholarId,
      String status,
      String claimRequestId,
      Instant grantedAt,
      Instant revokedAt) {}

  @Select(
      """
      SELECT id, account_id, scholar_id, status, claim_request_id, granted_at, revoked_at
      FROM iam_portal_authority
      WHERE scholar_id = #{scholarId} AND status = 'ACTIVE'
      """)
  Optional<AuthorityRow> findActiveByScholar(@Param("scholarId") String scholarId);

  @Select(
      """
      SELECT id, account_id, scholar_id, status, claim_request_id, granted_at, revoked_at
      FROM iam_portal_authority
      WHERE account_id = #{accountId} AND scholar_id = #{scholarId} AND status = 'ACTIVE'
      """)
  Optional<AuthorityRow> findActiveByAccountAndScholar(
      @Param("accountId") String accountId, @Param("scholarId") String scholarId);

  @Select(
      """
      SELECT id, account_id, scholar_id, status, claim_request_id, granted_at, revoked_at
      FROM iam_portal_authority
      WHERE account_id = #{accountId} AND status = 'ACTIVE'
      ORDER BY granted_at DESC
      """)
  List<AuthorityRow> listActiveByAccount(@Param("accountId") String accountId);

  @Select(
      """
      SELECT id, account_id, scholar_id, status, claim_request_id, granted_at, revoked_at
      FROM iam_portal_authority
      WHERE id = #{id}
      """)
  Optional<AuthorityRow> findById(@Param("id") String id);

  @Select(
      """
      <script>
      SELECT DISTINCT account_id
      FROM iam_portal_authority
      WHERE status = 'ACTIVE'
        AND scholar_id IN
        <foreach collection="scholarIds" item="item" open="(" separator="," close=")">#{item}</foreach>
      </script>
      """)
  List<String> listActiveAccountIdsByScholars(
      @Param("scholarIds") java.util.Collection<String> scholarIds);

  /**
   * 插入生效授权。
   *
   * <p>若该门户已有生效授权（含并发插入），部分唯一索引会命中冲突，本语句返回 0 行影响，
   * 调用方据此返回「竞争认领不转移原授权」的明确结果（AC-IMPL-FR05-03）。
   */
  @Insert(
      """
      INSERT INTO iam_portal_authority
        (id, account_id, scholar_id, status, claim_request_id, granted_at, granted_by_account_id)
      VALUES
        (#{id}, #{accountId}, #{scholarId}, 'ACTIVE', #{claimRequestId}, #{grantedAt}, #{grantedBy})
      ON CONFLICT (scholar_id) WHERE status = 'ACTIVE' DO NOTHING
      """)
  int insertActive(
      @Param("id") String id,
      @Param("accountId") String accountId,
      @Param("scholarId") String scholarId,
      @Param("claimRequestId") String claimRequestId,
      @Param("grantedAt") Instant grantedAt,
      @Param("grantedBy") String grantedBy);

  /** 条件撤销：必须仍为 ACTIVE 才生效，受影响行数须为 1。 */
  @Update(
      """
      UPDATE iam_portal_authority
      SET status = 'REVOKED', revoked_at = #{at}, revoke_reason = #{reason}
      WHERE id = #{id} AND status = 'ACTIVE'
      """)
  int revoke(@Param("id") String id, @Param("reason") String reason, @Param("at") Instant at);

  default PortalAuthority toDomain(AuthorityRow row) {
    return new PortalAuthority(
        row.id(),
        row.accountId(),
        row.scholarId(),
        row.status(),
        row.claimRequestId(),
        row.grantedAt(),
        row.revokedAt());
  }
}
