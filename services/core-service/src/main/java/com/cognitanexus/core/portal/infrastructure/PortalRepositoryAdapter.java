package com.cognitanexus.core.portal.infrastructure;

import com.cognitanexus.core.portal.application.PortalRepositories;
import com.cognitanexus.core.portal.domain.ClaimEvidence;
import com.cognitanexus.core.portal.domain.ClaimRequest;
import com.cognitanexus.core.portal.domain.PortalChangeRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 门户流程域仓储实现。 */
@Repository
public class PortalRepositoryAdapter
    implements PortalRepositories.ClaimRequestRepository,
        PortalRepositories.PortalChangeRequestRepository {

  private final PortalMapper mapper;

  public PortalRepositoryAdapter(PortalMapper mapper) {
    this.mapper = mapper;
  }

  // ---------------------------------------------------------------------------
  // 认领申请
  // ---------------------------------------------------------------------------

  @Override
  public void insert(ClaimRequest request) {
    mapper.insert(
        request.id(),
        request.accountId(),
        request.scholarId(),
        request.statement(),
        request.idempotencyKey());
  }

  @Override
  public Optional<ClaimRequest> findClaimById(String id) {
    return mapper.findById(id).map(this::toClaim);
  }

  @Override
  public Optional<ClaimRequest> findClaimByIdempotencyKey(String accountId, String idempotencyKey) {
    return mapper.findByIdempotencyKey(accountId, idempotencyKey).map(this::toClaim);
  }

  @Override
  public Optional<ClaimRequest> findOpenClaim(String accountId, String scholarId) {
    return mapper.findOpen(accountId, scholarId).map(this::toClaim);
  }

  @Override
  public List<ClaimRequest> listClaimsByAccount(String accountId, int limit) {
    return mapper.listByAccount(accountId, limit).stream().map(this::toClaim).toList();
  }

  @Override
  public List<ClaimRequest> listOpenClaims(int limit, int offset) {
    return mapper.listOpen(limit, offset).stream().map(this::toClaim).toList();
  }

  @Override
  public long countOpenClaims() {
    return mapper.countOpen();
  }

  @Override
  public int decideClaim(
      String id,
      String status,
      String decidedBy,
      String reason,
      Instant decidedAt,
      long expectedVersion) {
    return mapper.decide(id, status, decidedBy, reason, decidedAt, expectedVersion);
  }

  @Override
  public void insertEvidence(ClaimEvidence evidence) {
    mapper.insertEvidence(
        evidence.id(),
        evidence.claimRequestId(),
        evidence.fileRef(),
        evidence.contentHash(),
        evidence.contentType(),
        evidence.sizeBytes());
  }

  @Override
  public List<ClaimEvidence> listEvidence(String claimRequestId) {
    return mapper.listEvidence(claimRequestId).stream().map(this::toEvidence).toList();
  }

  @Override
  public Optional<ClaimEvidence> findEvidence(String evidenceId) {
    return mapper.findEvidence(evidenceId).map(this::toEvidence);
  }

  @Override
  public long countByStatus(String status) {
    return mapper.countByStatus(status);
  }

  @Override
  public int resubmitClaim(String id, String statement, long expectedVersion) {
    return mapper.resubmit(id, statement, expectedVersion);
  }

  public long countEvidence() {
    return mapper.countEvidence();
  }

  // ---------------------------------------------------------------------------
  // 门户变更
  // ---------------------------------------------------------------------------

  @Override
  public void insert(PortalChangeRequest request) {
    mapper.insertChange(
        request.id(),
        request.accountId(),
        request.scholarId(),
        request.field().name(),
        request.currentValue(),
        request.proposedValue(),
        request.requiresReview(),
        request.status().name());
  }

  @Override
  public Optional<PortalChangeRequest> findChangeById(String id) {
    return mapper.findChange(id).map(this::toChange);
  }

  @Override
  public Optional<PortalChangeRequest> findOpenChange(String scholarId, String fieldName) {
    return mapper.findOpenChange(scholarId, fieldName).map(this::toChange);
  }

  @Override
  public List<PortalChangeRequest> listChangesByAccount(String accountId, int limit) {
    return mapper.listChangesByAccount(accountId, limit).stream().map(this::toChange).toList();
  }

  @Override
  public List<PortalChangeRequest> listPendingChanges(int limit, int offset) {
    return mapper.listPendingChanges(limit, offset).stream().map(this::toChange).toList();
  }

  @Override
  public long countPendingChanges() {
    return mapper.countPendingChanges();
  }

  @Override
  public int decideChange(
      String id,
      String status,
      String decidedBy,
      String reason,
      Instant decidedAt,
      long expectedVersion) {
    return mapper.decideChange(id, status, decidedBy, reason, decidedAt, expectedVersion);
  }

  private ClaimRequest toClaim(PortalMapper.ClaimRow row) {
    return new ClaimRequest(
        row.id(),
        row.accountId(),
        row.scholarId(),
        ClaimRequest.Status.of(row.status()),
        row.statement(),
        row.submittedAt(),
        row.decidedAt(),
        row.decidedBy(),
        row.decisionReason(),
        row.idempotencyKey(),
        row.version());
  }

  private ClaimEvidence toEvidence(PortalMapper.EvidenceRow row) {
    return new ClaimEvidence(
        row.id(),
        row.claimRequestId(),
        row.fileRef(),
        row.contentHash(),
        row.contentType(),
        row.sizeBytes(),
        row.createdAt());
  }

  private PortalChangeRequest toChange(PortalMapper.ChangeRow row) {
    return new PortalChangeRequest(
        row.id(),
        row.accountId(),
        row.scholarId(),
        PortalChangeRequest.Field.of(row.fieldName()),
        row.currentValue(),
        row.proposedValue(),
        row.requiresReview(),
        PortalChangeRequest.Status.of(row.status()),
        row.submittedAt(),
        row.decidedAt(),
        row.decidedBy(),
        row.decisionReason(),
        row.version());
  }
}
