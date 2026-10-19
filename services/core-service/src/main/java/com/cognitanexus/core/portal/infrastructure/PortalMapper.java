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

  // ---------------------------------------------------------------------------
  // 成果自助提交
  // ---------------------------------------------------------------------------

  record SubmissionRow(
      String id,
      String accountId,
      String scholarId,
      String type,
      String title,
      String titleNorm,
      String doi,
      String doiNorm,
      String venue,
      Integer publicationYear,
      String abstractText,
      String authorsRaw,
      String status,
      String duplicateOf,
      String duplicateKind,
      String duplicateNote,
      String decisionReason,
      String decidedBy,
      Instant decidedAt,
      String achievementId,
      String idempotencyKey,
      long version,
      Instant createdAt,
      Instant updatedAt) {}

  /** 查重命中的候选：成果标识与相似度。 */
  record DuplicateCandidateRow(String achievementId, double score) {}

  @Insert(
      """
      INSERT INTO core_submission
        (id, account_id, scholar_id, type, title, title_norm, doi, doi_norm, venue,
         publication_year, abstract_text, authors_raw, status, duplicate_of, duplicate_kind,
         duplicate_note, idempotency_key, version)
      VALUES
        (#{id}, #{accountId}, #{scholarId}, #{type}, #{title}, #{titleNorm}, #{doi}, #{doiNorm},
         #{venue}, #{publicationYear}, #{abstractText}, #{authorsRaw}, #{status}, #{duplicateOf},
         #{duplicateKind}, #{duplicateNote}, #{idempotencyKey}, 1)
      """)
  void insertSubmission(
      @Param("id") String id,
      @Param("accountId") String accountId,
      @Param("scholarId") String scholarId,
      @Param("type") String type,
      @Param("title") String title,
      @Param("titleNorm") String titleNorm,
      @Param("doi") String doi,
      @Param("doiNorm") String doiNorm,
      @Param("venue") String venue,
      @Param("publicationYear") Integer publicationYear,
      @Param("abstractText") String abstractText,
      @Param("authorsRaw") String authorsRaw,
      @Param("status") String status,
      @Param("duplicateOf") String duplicateOf,
      @Param("duplicateKind") String duplicateKind,
      @Param("duplicateNote") String duplicateNote,
      @Param("idempotencyKey") String idempotencyKey);

  @Select(
      """
      SELECT id AS id, account_id AS accountId, scholar_id AS scholarId, type AS type,
             title AS title, title_norm AS titleNorm, doi AS doi, doi_norm AS doiNorm,
             venue AS venue, publication_year AS publicationYear, abstract_text AS abstractText,
             authors_raw AS authorsRaw, status AS status, duplicate_of AS duplicateOf,
             duplicate_kind AS duplicateKind, duplicate_note AS duplicateNote,
             decision_reason AS decisionReason, decided_by AS decidedBy, decided_at AS decidedAt,
             achievement_id AS achievementId, idempotency_key AS idempotencyKey,
             version AS version, created_at AS createdAt, updated_at AS updatedAt
      FROM core_submission
      WHERE id = #{id}
      """)
  Optional<SubmissionRow> findSubmissionById(@Param("id") String id);

  @Select(
      """
      SELECT id AS id, account_id AS accountId, scholar_id AS scholarId, type AS type,
             title AS title, title_norm AS titleNorm, doi AS doi, doi_norm AS doiNorm,
             venue AS venue, publication_year AS publicationYear, abstract_text AS abstractText,
             authors_raw AS authorsRaw, status AS status, duplicate_of AS duplicateOf,
             duplicate_kind AS duplicateKind, duplicate_note AS duplicateNote,
             decision_reason AS decisionReason, decided_by AS decidedBy, decided_at AS decidedAt,
             achievement_id AS achievementId, idempotency_key AS idempotencyKey,
             version AS version, created_at AS createdAt, updated_at AS updatedAt
      FROM core_submission
      WHERE account_id = #{accountId} AND idempotency_key = #{idempotencyKey}
      """)
  Optional<SubmissionRow> findSubmissionByIdempotencyKey(
      @Param("accountId") String accountId, @Param("idempotencyKey") String idempotencyKey);

  @Select(
      """
      SELECT id AS id, account_id AS accountId, scholar_id AS scholarId, type AS type,
             title AS title, title_norm AS titleNorm, doi AS doi, doi_norm AS doiNorm,
             venue AS venue, publication_year AS publicationYear, abstract_text AS abstractText,
             authors_raw AS authorsRaw, status AS status, duplicate_of AS duplicateOf,
             duplicate_kind AS duplicateKind, duplicate_note AS duplicateNote,
             decision_reason AS decisionReason, decided_by AS decidedBy, decided_at AS decidedAt,
             achievement_id AS achievementId, idempotency_key AS idempotencyKey,
             version AS version, created_at AS createdAt, updated_at AS updatedAt
      FROM core_submission
      WHERE account_id = #{accountId}
      ORDER BY created_at DESC, id
      LIMIT #{limit}
      """)
  List<SubmissionRow> listSubmissionsByAccount(
      @Param("accountId") String accountId, @Param("limit") int limit);

  @Select(
      """
      SELECT id AS id, account_id AS accountId, scholar_id AS scholarId, type AS type,
             title AS title, title_norm AS titleNorm, doi AS doi, doi_norm AS doiNorm,
             venue AS venue, publication_year AS publicationYear, abstract_text AS abstractText,
             authors_raw AS authorsRaw, status AS status, duplicate_of AS duplicateOf,
             duplicate_kind AS duplicateKind, duplicate_note AS duplicateNote,
             decision_reason AS decisionReason, decided_by AS decidedBy, decided_at AS decidedAt,
             achievement_id AS achievementId, idempotency_key AS idempotencyKey,
             version AS version, created_at AS createdAt, updated_at AS updatedAt
      FROM core_submission
      WHERE status IN ('SUBMITTED', 'NEED_MORE_INFO')
      ORDER BY created_at, id
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<SubmissionRow> listOpenSubmissions(@Param("limit") int limit, @Param("offset") int offset);

  @Select("SELECT count(*) FROM core_submission WHERE status IN ('SUBMITTED', 'NEED_MORE_INFO')")
  long countOpenSubmissions();

  @Update(
      """
      UPDATE core_submission
      SET status = #{status}, decision_reason = #{reason}, decided_by = #{decidedBy},
          decided_at = #{decidedAt}, achievement_id = #{achievementId},
          updated_at = now(), version = version + 1
      WHERE id = #{id} AND version = #{expectedVersion} AND status IN ('SUBMITTED', 'NEED_MORE_INFO')
      """)
  int decideSubmission(
      @Param("id") String id,
      @Param("status") String status,
      @Param("decidedBy") String decidedBy,
      @Param("reason") String reason,
      @Param("decidedAt") Instant decidedAt,
      @Param("achievementId") String achievementId,
      @Param("expectedVersion") long expectedVersion);

  /** 本人撤回：只允许提交人撤回自己的、仍处于未裁决状态的提交件。 */
  @Update(
      """
      UPDATE core_submission
      SET status = 'WITHDRAWN', updated_at = now(), version = version + 1
      WHERE id = #{id} AND account_id = #{accountId} AND version = #{expectedVersion}
        AND status IN ('SUBMITTED', 'NEED_MORE_INFO')
      """)
  int withdrawSubmission(
      @Param("id") String id,
      @Param("accountId") String accountId,
      @Param("expectedVersion") long expectedVersion);

  /**
   * DOI 精确命中。
   *
   * <p>已被合并的成果不算命中：命中的是「当前仍然生效的那一条」，否则审核员点开链接会看到一个
   * 跳转页，还得自己找终点。
   */
  @Select(
      """
      SELECT a.id AS achievementId, 1.0 AS score
      FROM core_paper p
      JOIN core_achievement a ON a.id = p.achievement_id
      WHERE p.doi_norm = #{doiNorm} AND a.merged_into_id IS NULL
      ORDER BY a.created_at
      LIMIT 1
      """)
  Optional<DuplicateCandidateRow> findAchievementByDoi(@Param("doiNorm") String doiNorm);

  /**
   * 题名相似候选。
   *
   * <p>用 pg_trgm 的 {@code %} 运算符（走 GIN 索引），再按 similarity 排序取最高的一条。
   * 这里只返回候选，阈值判断放在应用层：阈值属于业务规则，写进 SQL 之后，单元测试就只能
   * 靠真实数据库来验证，而那正是「改一行没人发现」的地方。
   */
  @Select(
      """
      SELECT a.id AS achievementId, similarity(a.title_norm, #{titleNorm}) AS score
      FROM core_achievement a
      WHERE a.title_norm % #{titleNorm}
        AND a.merged_into_id IS NULL
      ORDER BY score DESC, a.created_at
      LIMIT 1
      """)
  Optional<DuplicateCandidateRow> findAchievementByTitle(@Param("titleNorm") String titleNorm);
}
