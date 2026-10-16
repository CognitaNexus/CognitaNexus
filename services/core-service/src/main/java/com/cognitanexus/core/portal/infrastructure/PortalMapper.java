package com.cognitanexus.core.portal.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 门户流程域持久化：认领申请、认领材料元数据、门户变更申请。
 *
 * <p>依据：架构 V0.2 §5.2、AC-IMPL-FR05-02。所有裁决都是带前置状态条件的更新：
 * 受影响行数为 0 表示状态已被并发改变，调用方必须报冲突而不是覆盖。
 */
@Mapper
public interface PortalMapper {

  // ---------------------------------------------------------------------------
  // 认领申请
  // ---------------------------------------------------------------------------

  record ClaimRow(
      String id,
      String accountId,
      String scholarId,
      String status,
      String statement,
      Instant submittedAt,
      Instant decidedAt,
      String decidedBy,
      String decisionReason,
      String idempotencyKey,
      long version) {}

  @Insert(
      """
      INSERT INTO wf_claim_request
        (id, account_id, scholar_id, status, statement, submitted_at, idempotency_key, version)
      VALUES
        (#{id}, #{accountId}, #{scholarId}, 'PENDING_REVIEW', #{statement}, now(), #{idempotencyKey}, 1)
      """)
  void insert(
      @Param("id") String id,
      @Param("accountId") String accountId,
      @Param("scholarId") String scholarId,
      @Param("statement") String statement,
      @Param("idempotencyKey") String idempotencyKey);

  @Select(
      """
      SELECT id AS id, account_id AS accountId, scholar_id AS scholarId, status AS status,
             statement AS statement, submitted_at AS submittedAt, decided_at AS decidedAt,
             decided_by AS decidedBy, decision_reason AS decisionReason,
             idempotency_key AS idempotencyKey, version AS version
      FROM wf_claim_request WHERE id = #{id}
      """)
  Optional<ClaimRow> findById(@Param("id") String id);

  @Select(
      """
      SELECT id AS id, account_id AS accountId, scholar_id AS scholarId, status AS status,
             statement AS statement, submitted_at AS submittedAt, decided_at AS decidedAt,
             decided_by AS decidedBy, decision_reason AS decisionReason,
             idempotency_key AS idempotencyKey, version AS version
      FROM wf_claim_request
      WHERE account_id = #{accountId} AND idempotency_key = #{idempotencyKey}
      """)
  Optional<ClaimRow> findByIdempotencyKey(
      @Param("accountId") String accountId, @Param("idempotencyKey") String idempotencyKey);

  @Select(
      """
      SELECT id AS id, account_id AS accountId, scholar_id AS scholarId, status AS status,
             statement AS statement, submitted_at AS submittedAt, decided_at AS decidedAt,
             decided_by AS decidedBy, decision_reason AS decisionReason,
             idempotency_key AS idempotencyKey, version AS version
      FROM wf_claim_request
      WHERE account_id = #{accountId} AND scholar_id = #{scholarId}
        AND status IN ('PENDING_REVIEW', 'NEED_MORE_EVIDENCE')
      ORDER BY submitted_at DESC
      LIMIT 1
      """)
  Optional<ClaimRow> findOpen(
      @Param("accountId") String accountId, @Param("scholarId") String scholarId);

  @Select(
      """
      SELECT id AS id, account_id AS accountId, scholar_id AS scholarId, status AS status,
             statement AS statement, submitted_at AS submittedAt, decided_at AS decidedAt,
             decided_by AS decidedBy, decision_reason AS decisionReason,
             idempotency_key AS idempotencyKey, version AS version
      FROM wf_claim_request
      WHERE account_id = #{accountId}
      ORDER BY submitted_at DESC, id
      LIMIT #{limit}
      """)
  List<ClaimRow> listByAccount(@Param("accountId") String accountId, @Param("limit") int limit);

  @Select(
      """
      SELECT id AS id, account_id AS accountId, scholar_id AS scholarId, status AS status,
             statement AS statement, submitted_at AS submittedAt, decided_at AS decidedAt,
             decided_by AS decidedBy, decision_reason AS decisionReason,
             idempotency_key AS idempotencyKey, version AS version
      FROM wf_claim_request
      WHERE status IN ('PENDING_REVIEW', 'NEED_MORE_EVIDENCE')
      ORDER BY submitted_at, id
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<ClaimRow> listOpen(@Param("limit") int limit, @Param("offset") int offset);

  @Select(
      """
      SELECT count(*) FROM wf_claim_request
      WHERE status IN ('PENDING_REVIEW', 'NEED_MORE_EVIDENCE')
      """)
  long countOpen();

  @Select("SELECT count(*) FROM wf_claim_request WHERE status = #{status}")
  long countByStatus(@Param("status") String status);

  @Update(
      """
      UPDATE wf_claim_request
      SET status = #{status}, decided_at = #{decidedAt}, decided_by = #{decidedBy},
          decision_reason = #{reason}, version = version + 1
      WHERE id = #{id} AND version = #{expectedVersion}
        AND status IN ('PENDING_REVIEW', 'NEED_MORE_EVIDENCE')
      """)
  int decide(
      @Param("id") String id,
      @Param("status") String status,
      @Param("decidedBy") String decidedBy,
      @Param("reason") String reason,
      @Param("decidedAt") Instant decidedAt,
      @Param("expectedVersion") long expectedVersion);

  /** 申请人补证后重新进入待审：由 open 状态回到 PENDING_REVIEW。 */
  @Update(
      """
      UPDATE wf_claim_request
      SET status = 'PENDING_REVIEW', statement = #{statement}, version = version + 1
      WHERE id = #{id} AND version = #{expectedVersion} AND status = 'NEED_MORE_EVIDENCE'
      """)
  int resubmit(
      @Param("id") String id,
      @Param("statement") String statement,
      @Param("expectedVersion") long expectedVersion);

  // ---------------------------------------------------------------------------
  // 认领材料
  // ---------------------------------------------------------------------------

  record EvidenceRow(
      String id,
      String claimRequestId,
      String fileRef,
      String contentHash,
      String contentType,
      long sizeBytes,
      Instant createdAt) {}

  @Insert(
      """
      INSERT INTO wf_claim_evidence
        (id, claim_request_id, file_ref, content_hash, content_type, size_bytes)
      VALUES
        (#{id}, #{claimRequestId}, #{fileRef}, #{contentHash}, #{contentType}, #{sizeBytes})
      """)
  void insertEvidence(
      @Param("id") String id,
      @Param("claimRequestId") String claimRequestId,
      @Param("fileRef") String fileRef,
      @Param("contentHash") String contentHash,
      @Param("contentType") String contentType,
      @Param("sizeBytes") long sizeBytes);

  @Select(
      """
      SELECT id AS id, claim_request_id AS claimRequestId, file_ref AS fileRef,
             content_hash AS contentHash, content_type AS contentType,
             size_bytes AS sizeBytes, created_at AS createdAt
      FROM wf_claim_evidence WHERE claim_request_id = #{claimRequestId}
      ORDER BY created_at, id
      """)
  List<EvidenceRow> listEvidence(@Param("claimRequestId") String claimRequestId);

  @Select(
      """
      SELECT id AS id, claim_request_id AS claimRequestId, file_ref AS fileRef,
             content_hash AS contentHash, content_type AS contentType,
             size_bytes AS sizeBytes, created_at AS createdAt
      FROM wf_claim_evidence WHERE id = #{id}
      """)
  Optional<EvidenceRow> findEvidence(@Param("id") String id);

  @Select("SELECT count(*) FROM wf_claim_evidence")
  long countEvidence();

  // ---------------------------------------------------------------------------
  // 门户变更申请
  // ---------------------------------------------------------------------------

  record ChangeRow(
      String id,
      String accountId,
      String scholarId,
      String fieldName,
      String currentValue,
      String proposedValue,
      boolean requiresReview,
      String status,
      Instant submittedAt,
      Instant decidedAt,
      String decidedBy,
      String decisionReason,
      long version) {}

  @Insert(
      """
      INSERT INTO wf_portal_change_request
        (id, account_id, scholar_id, field_name, current_value, proposed_value,
         requires_review, status, submitted_at, version)
      VALUES
        (#{id}, #{accountId}, #{scholarId}, #{fieldName}, #{currentValue}, #{proposedValue},
         #{requiresReview}, #{status}, now(), 1)
      """)
  void insertChange(
      @Param("id") String id,
      @Param("accountId") String accountId,
      @Param("scholarId") String scholarId,
      @Param("fieldName") String fieldName,
      @Param("currentValue") String currentValue,
      @Param("proposedValue") String proposedValue,
      @Param("requiresReview") boolean requiresReview,
      @Param("status") String status);

  @Select(
      """
      SELECT id AS id, account_id AS accountId, scholar_id AS scholarId, field_name AS fieldName,
             current_value AS currentValue, proposed_value AS proposedValue,
             requires_review AS requiresReview, status AS status, submitted_at AS submittedAt,
             decided_at AS decidedAt, decided_by AS decidedBy,
             decision_reason AS decisionReason, version AS version
      FROM wf_portal_change_request WHERE id = #{id}
      """)
  Optional<ChangeRow> findChange(@Param("id") String id);

  @Select(
      """
      SELECT id AS id, account_id AS accountId, scholar_id AS scholarId, field_name AS fieldName,
             current_value AS currentValue, proposed_value AS proposedValue,
             requires_review AS requiresReview, status AS status, submitted_at AS submittedAt,
             decided_at AS decidedAt, decided_by AS decidedBy,
             decision_reason AS decisionReason, version AS version
      FROM wf_portal_change_request
      WHERE scholar_id = #{scholarId} AND field_name = #{fieldName} AND status = 'PENDING_REVIEW'
      ORDER BY submitted_at DESC LIMIT 1
      """)
  Optional<ChangeRow> findOpenChange(
      @Param("scholarId") String scholarId, @Param("fieldName") String fieldName);

  @Select(
      """
      SELECT id AS id, account_id AS accountId, scholar_id AS scholarId, field_name AS fieldName,
             current_value AS currentValue, proposed_value AS proposedValue,
             requires_review AS requiresReview, status AS status, submitted_at AS submittedAt,
             decided_at AS decidedAt, decided_by AS decidedBy,
             decision_reason AS decisionReason, version AS version
      FROM wf_portal_change_request
      WHERE account_id = #{accountId}
      ORDER BY submitted_at DESC, id
      LIMIT #{limit}
      """)
  List<ChangeRow> listChangesByAccount(
      @Param("accountId") String accountId, @Param("limit") int limit);

  @Select(
      """
      SELECT id AS id, account_id AS accountId, scholar_id AS scholarId, field_name AS fieldName,
             current_value AS currentValue, proposed_value AS proposedValue,
             requires_review AS requiresReview, status AS status, submitted_at AS submittedAt,
             decided_at AS decidedAt, decided_by AS decidedBy,
             decision_reason AS decisionReason, version AS version
      FROM wf_portal_change_request
      WHERE status = 'PENDING_REVIEW'
      ORDER BY submitted_at, id
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<ChangeRow> listPendingChanges(@Param("limit") int limit, @Param("offset") int offset);

  @Select("SELECT count(*) FROM wf_portal_change_request WHERE status = 'PENDING_REVIEW'")
  long countPendingChanges();

  @Update(
      """
      UPDATE wf_portal_change_request
      SET status = #{status}, decided_at = #{decidedAt}, decided_by = #{decidedBy},
          decision_reason = #{reason}, version = version + 1
      WHERE id = #{id} AND version = #{expectedVersion} AND status = 'PENDING_REVIEW'
      """)
  int decideChange(
      @Param("id") String id,
      @Param("status") String status,
      @Param("decidedBy") String decidedBy,
      @Param("reason") String reason,
      @Param("decidedAt") Instant decidedAt,
      @Param("expectedVersion") long expectedVersion);
}
