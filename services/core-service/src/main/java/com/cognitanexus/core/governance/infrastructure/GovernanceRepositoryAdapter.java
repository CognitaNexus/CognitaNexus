package com.cognitanexus.core.governance.infrastructure;

import com.cognitanexus.core.governance.application.GovernanceRepositories;
import com.cognitanexus.core.governance.domain.DedupCandidate;
import com.cognitanexus.core.governance.domain.GovernanceAnomaly;
import com.cognitanexus.core.governance.domain.MergeLedgerEntry;
import com.cognitanexus.core.platform.persistence.JsonCodec;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 治理域仓储实现。
 *
 * <p>四个端口共用同一批表与外键，汇集在一个实现类中；应用层仍只依赖各自的窄接口。
 */
@Repository
public class GovernanceRepositoryAdapter
    implements GovernanceRepositories.DedupCandidateRepository,
        GovernanceRepositories.MergeLedgerRepository,
        GovernanceRepositories.AnomalyRepository,
        GovernanceRepositories.MergeTargetRepository {

  private final GovernanceMapper mapper;
  private final JsonCodec json;

  public GovernanceRepositoryAdapter(GovernanceMapper mapper, JsonCodec json) {
    this.mapper = mapper;
    this.json = json;
  }

  // ---------------------------------------------------------------------------
  // 候选
  // ---------------------------------------------------------------------------

  @Override
  public boolean insertIfAbsent(DedupCandidate candidate) {
    return mapper.insertCandidateIfAbsent(
            candidate.id(),
            candidate.achievementType(),
            candidate.leftAchievementId(),
            candidate.rightAchievementId(),
            json.write(candidate.evidence()))
        == 1;
  }

  @Override
  public List<DedupCandidate> listOpenCandidates(int limit, int offset) {
    return mapper.listCandidates("OPEN", limit, offset).stream().map(this::toCandidate).toList();
  }

  @Override
  public Optional<DedupCandidate> findById(String id) {
    return mapper.findCandidate(id).map(this::toCandidate);
  }

  @Override
  public long countOpenCandidates() {
    return mapper.countOpenCandidates();
  }

  @Override
  public long countOpenFor(String achievementId) {
    return mapper.countOpenForAchievement(achievementId);
  }

  @Override
  public boolean resolve(String id, String status, String resolvedBy, String reason) {
    return mapper.resolveCandidate(id, status, resolvedBy, reason) == 1;
  }

  // ---------------------------------------------------------------------------
  // 台账
  // ---------------------------------------------------------------------------

  @Override
  public void append(MergeLedgerEntry entry) {
    mapper.insertLedger(
        entry.id(),
        entry.operation().name(),
        entry.survivorId(),
        entry.mergedId(),
        String.valueOf(entry.entityType()),
        json.write(entry.snapshot()),
        entry.reason(),
        entry.operatorId());
  }

  @Override
  public List<MergeLedgerEntry> listByEntity(String entityId, int limit) {
    return mapper.listLedgerByEntity(entityId, limit).stream()
        .map(
            row ->
                new MergeLedgerEntry(
                    row.id(),
                    MergeLedgerEntry.Operation.of(row.operation()),
                    row.survivorId(),
                    row.mergedId(),
                    row.entityType(),
                    json.readMap(row.snapshotJson()),
                    row.reason(),
                    row.operatorId(),
                    row.occurredAt()))
        .toList();
  }

  // ---------------------------------------------------------------------------
  // 异常
  // ---------------------------------------------------------------------------

  @Override
  public void record(GovernanceAnomaly anomaly) {
    mapper.insertAnomaly(
        anomaly.id(),
        anomaly.kind(),
        anomaly.sourceRecordId(),
        anomaly.entityId(),
        json.write(anomaly.detail()));
  }

  @Override
  public List<GovernanceAnomaly> listOpenAnomalies(int limit, int offset) {
    return mapper.listAnomalies("OPEN", limit, offset).stream()
        .map(
            row ->
                new GovernanceAnomaly(
                    row.id(),
                    row.kind(),
                    row.sourceRecordId(),
                    row.entityId(),
                    json.readMap(row.detailJson()),
                    row.status(),
                    row.createdAt(),
                    row.resolvedAt()))
        .toList();
  }

  @Override
  public long countOpenAnomalies() {
    return mapper.countOpenAnomalies();
  }

  @Override
  public boolean resolve(String id) {
    return mapper.resolveAnomaly(id) == 1;
  }

  // ---------------------------------------------------------------------------
  // 合并机制
  // ---------------------------------------------------------------------------

  @Override
  public boolean markAchievementMerged(String mergedId, String survivorId) {
    return mapper.markAchievementMerged(mergedId, survivorId) == 1;
  }

  @Override
  public boolean markScholarMerged(String mergedId, String survivorId) {
    return mapper.markScholarMerged(mergedId, survivorId) == 1;
  }

  @Override
  public int moveSourceLinks(String mergedId, String survivorId) {
    return mapper.moveSourceLinks(mergedId, survivorId);
  }

  @Override
  public int moveTopicLinks(String mergedId, String survivorId) {
    return mapper.moveTopicLinks(mergedId, survivorId);
  }

  @Override
  public ContributionMove moveContributions(String mergedId, String survivorId) {
    int inserted = mapper.insertMovedContributions(mergedId, survivorId);
    int removed = mapper.deleteContributionsOf(mergedId);
    return new ContributionMove(inserted, removed);
  }

  @Override
  public ContributionMove moveScholarContributions(
      String mergedScholarId, String survivorScholarId) {
    int inserted = mapper.insertMovedScholarContributions(mergedScholarId, survivorScholarId);
    int removed = mapper.deleteContributionsOfScholar(mergedScholarId);
    return new ContributionMove(inserted, removed);
  }

  @Override
  public int moveNameVariants(String mergedScholarId, String survivorScholarId) {
    int moved = mapper.moveNameVariants(mergedScholarId, survivorScholarId);
    mapper.deleteNameVariantsOf(mergedScholarId);
    return moved;
  }

  @Override
  public int deleteNameVariantsOf(String mergedScholarId) {
    return mapper.deleteNameVariantsOf(mergedScholarId);
  }

  @Override
  public int moveActiveAuthority(String mergedScholarId, String survivorScholarId) {
    return mapper.moveActiveAuthority(mergedScholarId, survivorScholarId);
  }

  @Override
  public int revokeAuthorityOfScholar(String mergedScholarId, String reason) {
    return mapper.revokeAuthorityOfScholar(mergedScholarId, reason);
  }

  @Override
  public Optional<String> findActiveAuthorityId(String scholarId) {
    return mapper.findActiveAuthorityId(scholarId);
  }

  @Override
  public List<String> snapshotContributions(String achievementId) {
    return mapper.snapshotContributions(achievementId);
  }

  @Override
  public List<String> snapshotScholarContributions(String scholarId) {
    return mapper.snapshotScholarContributions(scholarId);
  }

  @Override
  public List<String> snapshotSources(String achievementId) {
    return mapper.snapshotSources(achievementId);
  }

  private DedupCandidate toCandidate(GovernanceMapper.CandidateRow row) {
    return new DedupCandidate(
        row.id(),
        row.achievementType(),
        row.leftAchievementId(),
        row.rightAchievementId(),
        json.readMap(row.evidenceJson()),
        DedupCandidate.Status.of(row.status()),
        row.createdAt(),
        row.resolvedAt(),
        row.resolvedBy(),
        row.resolutionReason());
  }
}
