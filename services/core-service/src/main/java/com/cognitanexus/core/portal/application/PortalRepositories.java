package com.cognitanexus.core.portal.application;

import com.cognitanexus.core.portal.domain.ClaimEvidence;
import com.cognitanexus.core.portal.domain.ClaimRequest;
import com.cognitanexus.core.portal.domain.PortalChangeRequest;
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
}
