package com.cognitanexus.core.portal.application;

import com.cognitanexus.core.portal.domain.ClaimEvidence;
import com.cognitanexus.core.portal.domain.ClaimRequest;
import com.cognitanexus.core.portal.domain.PortalChangeRequest;
import com.cognitanexus.core.portal.domain.Submission;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 门户流程域的持久化出站端口。 */
public interface PortalRepositories {

  /** 认领申请仓储。 */
  interface ClaimRequestRepository {

    void insert(ClaimRequest request);

    Optional<ClaimRequest> findClaimById(String id);

    Optional<ClaimRequest> findClaimByIdempotencyKey(String accountId, String idempotencyKey);

    /** 同一账号对同一门户是否已有未裁决申请，避免重复排队。 */
    Optional<ClaimRequest> findOpenClaim(String accountId, String scholarId);

    List<ClaimRequest> listClaimsByAccount(String accountId, int limit);

    List<ClaimRequest> listOpenClaims(int limit, int offset);

    long countOpenClaims();

    /** 条件裁决：只允许从 open 状态迁移，受影响行数须为 1。 */
    int decideClaim(
        String id,
        String status,
        String decidedBy,
        String reason,
        Instant decidedAt,
        long expectedVersion);

    /** 申请人在补证后重新提交，回到待审状态。 */
    int resubmitClaim(String id, String statement, long expectedVersion);

    void insertEvidence(ClaimEvidence evidence);

    List<ClaimEvidence> listEvidence(String claimRequestId);

    Optional<ClaimEvidence> findEvidence(String evidenceId);

    long countByStatus(String status);
  }

  /** 门户变更申请仓储。 */
  interface PortalChangeRequestRepository {

    void insert(PortalChangeRequest request);

    Optional<PortalChangeRequest> findChangeById(String id);

    Optional<PortalChangeRequest> findOpenChange(String scholarId, String fieldName);

    List<PortalChangeRequest> listChangesByAccount(String accountId, int limit);

    List<PortalChangeRequest> listPendingChanges(int limit, int offset);

    long countPendingChanges();

    int decideChange(
        String id,
        String status,
        String decidedBy,
        String reason,
        Instant decidedAt,
        long expectedVersion);
  }

  /** 成果自助提交仓储。 */
  interface SubmissionRepository {

    void insert(Submission submission);

    Optional<Submission> findById(String id);

    /** 幂等重放：同一账号用同一个键重复提交时，返回首次那条而不是再建一条。 */
    Optional<Submission> findByIdempotencyKey(String accountId, String idempotencyKey);

    List<Submission> listByAccount(String accountId, int limit);

    /** 未裁决队列，先提交的先处理。 */
    List<Submission> listOpen(int limit, int offset);

    long countOpen();

    /**
     * 条件裁决：只允许从 SUBMITTED / NEED_MORE_INFO 迁移。
     *
     * <p>用前置状态而不是无条件赋值：两个审核员同时点「通过」时，后到的那次必须表现成 0 行，
     * 由服务层报冲突。否则同一条提交会被建成两条成果，而两条都自称「审核通过」。
     *
     * @param achievementId 批准时新建的成果标识；驳回时为 null
     */
    int decide(
        String id,
        String status,
        String decidedBy,
        String reason,
        Instant decidedAt,
        String achievementId,
        long expectedVersion);

    /** 本人撤回；账号与版本都作为前置条件，撤回不了别人的提交。 */
    int withdraw(String id, String accountId, long expectedVersion);

    /** DOI 精确命中的既有成果。 */
    Optional<String> findAchievementIdByDoi(String doiNorm);

    /** 题名相似度最高的既有成果，附相似度；阈值由应用层判断。 */
    Optional<SimilarAchievement> findMostSimilarTitle(String titleNorm);

    /** 相似候选。 */
    record SimilarAchievement(String achievementId, double score) {}
  }
}
