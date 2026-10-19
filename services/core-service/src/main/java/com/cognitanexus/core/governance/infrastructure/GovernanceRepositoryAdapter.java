package com.cognitanexus.core.governance.infrastructure;

import com.cognitanexus.core.governance.application.GovernanceRepositories;
import com.cognitanexus.core.governance.application.GovernanceRepositories.AchievementStateRepository.AchievementState;
import com.cognitanexus.core.governance.application.GovernanceRepositories.CandidateBuckets;
import com.cognitanexus.core.governance.application.GovernanceRepositories.ScoredCandidate;
import com.cognitanexus.core.governance.domain.DedupCandidate;
import com.cognitanexus.core.governance.domain.GovernanceAnomaly;
import com.cognitanexus.core.governance.domain.MergeLedgerEntry;
import com.cognitanexus.core.platform.persistence.JsonCodec;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
        GovernanceRepositories.AchievementStateRepository,
        GovernanceRepositories.DataQualityRepository,
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
  public List<ScoredCandidate> listOpenCandidatesScored(
      Double minSimilarity, String sortBy, int limit, int offset) {
    return mapper.listCandidatesFiltered("OPEN", minSimilarity, sortBy, limit, offset).stream()
        .map(
            row ->
                new ScoredCandidate(
                    toCandidate(row.toRow()), row.similarity() == null ? 0d : row.similarity()))
        .toList();
  }

  @Override
  public long countOpenCandidatesByFilter(Double minSimilarity) {
    return mapper.countCandidatesFiltered("OPEN", minSimilarity);
  }

  @Override
  public CandidateBuckets countOpenCandidatesByBucket(double highLow, double midLow) {
    GovernanceMapper.CandidateBuckets buckets =
        mapper.countCandidateBuckets("OPEN", highLow, midLow);
    return new CandidateBuckets(
        buckets.highCount(), buckets.midCount(), buckets.lowCount(), buckets.total());
  }

  @Override
  public Optional<DedupCandidate> findById(String id) {
    return mapper.findCandidate(id).map(this::toCandidate);
  }

  @Override
  public Optional<DedupCandidate> findBetween(String leftId, String rightId) {
    return mapper.findCandidateBetween(leftId, rightId).map(this::toCandidate);
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
  public long countOpenBetween(String leftId, String rightId) {
    return mapper.countOpenBetween(leftId, rightId);
  }

  @Override
  public int resolveOpenBetween(
      String leftId, String rightId, String status, String resolvedBy, String reason) {
    return mapper.resolveOpenCandidatesBetween(leftId, rightId, status, resolvedBy, reason);
  }

  @Override
  public boolean resolve(String id, String status, String resolvedBy, String reason) {
    return mapper.resolveCandidate(id, status, resolvedBy, reason) == 1;
  }

  @Override
  public boolean resolveRebased(String id, String resolvedBy, String reason, String rebasedToId) {
    return mapper.resolveCandidateRebased(id, resolvedBy, reason, rebasedToId) == 1;
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
    return mapper.listAnomalies("OPEN", limit, offset).stream().map(this::toAnomaly).toList();
  }

  private GovernanceAnomaly toAnomaly(GovernanceMapper.AnomalyRow row) {
    return new GovernanceAnomaly(
        row.id(),
        row.kind(),
        row.sourceRecordId(),
        row.entityId(),
        json.readMap(row.detailJson()),
        row.status(),
        row.createdAt(),
        row.resolvedAt());
  }

  @Override
  public long countOpenAnomalies() {
    return mapper.countOpenAnomalies();
  }

  @Override
  public boolean resolve(String id) {
    return mapper.resolveAnomaly(id) == 1;
  }

  @Override
  public Optional<String> findOpenIdByDedupeKey(String kind, String dedupeKey) {
    return Optional.ofNullable(mapper.findOpenAnomalyId(kind, dedupeKey));
  }

  @Override
  public Optional<GovernanceAnomaly> findOpenByDedupeKey(String kind, String dedupeKey) {
    return Optional.ofNullable(mapper.findAnomalyByDedupeKey(kind, dedupeKey)).map(this::toAnomaly);
  }

  @Override
  public long countOpenOfKind(String kind) {
    return mapper.countOpenAnomaliesOfKind(kind);
  }

  @Override
  public boolean refreshDetail(String id, java.util.Map<String, Object> detail) {
    return mapper.refreshAnomalyDetail(id, json.write(detail)) == 1;
  }

  @Override
  public List<OpenAnomalyKey> listOpenOfKind(String kind, int limit) {
    return mapper.openAnomaliesOfKind(kind, limit).stream()
        .map(row -> new OpenAnomalyKey(row.id(), row.entityId()))
        .toList();
  }

  // ---------------------------------------------------------------------------
  // 数据质量探测（只读）
  // ---------------------------------------------------------------------------

  @Override
  public List<String> yearOutOfRangeIds(int limit) {
    return mapper.yearOutOfRangeIds(limit);
  }

  @Override
  public List<Sample> yearOutOfRangeSamples(int limit) {
    return mapper.yearOutOfRangeSamples(limit).stream()
        .map(GovernanceRepositoryAdapter::toSample)
        .toList();
  }

  @Override
  public List<String> topicKindConflictIds(int limit) {
    return mapper.topicKindConflictIds(limit);
  }

  @Override
  public List<Sample> topicKindConflictSamples(int limit) {
    return mapper.topicKindConflictSamples(limit).stream()
        .map(GovernanceRepositoryAdapter::toSample)
        .toList();
  }

  @Override
  public List<Bucket> effectiveWithoutTopicByType() {
    return mapper.effectiveWithoutTopicByType().stream()
        .map(row -> new Bucket(row.bucket(), row.total()))
        .toList();
  }

  @Override
  public List<Sample> effectiveWithoutTopicSamples(int limit) {
    return mapper.effectiveWithoutTopicSamples(limit).stream()
        .map(GovernanceRepositoryAdapter::toSample)
        .toList();
  }

  @Override
  public List<Bucket> effectiveWithoutContributorByType() {
    return mapper.effectiveWithoutContributorByType().stream()
        .map(row -> new Bucket(row.bucket(), row.total()))
        .toList();
  }

  @Override
  public List<Sample> effectiveWithoutContributorSamples(int limit) {
    return mapper.effectiveWithoutContributorSamples(limit).stream()
        .map(GovernanceRepositoryAdapter::toSample)
        .toList();
  }

  @Override
  public List<Bucket> papersWithoutIdentifierByType() {
    return mapper.papersWithoutIdentifierByType().stream()
        .map(row -> new Bucket(row.bucket(), row.total()))
        .toList();
  }

  @Override
  public List<Sample> papersWithoutIdentifierSamples(int limit) {
    return mapper.papersWithoutIdentifierSamples(limit).stream()
        .map(GovernanceRepositoryAdapter::toSample)
        .toList();
  }

  @Override
  public List<NameGroup> duplicateScholarNames(int minCount, int limit) {
    return mapper.duplicateScholarNames(minCount, limit).stream()
        .map(row -> new NameGroup(row.nameNorm(), row.total()))
        .toList();
  }

  @Override
  public List<ScholarSample> scholarsByNameNorm(String nameNorm, int limit) {
    return mapper.scholarsByNameNorm(nameNorm, limit).stream()
        .map(
            row ->
                new ScholarSample(
                    row.id(), row.displayName(), row.organizationName(), row.claimedStatus()))
        .toList();
  }

  @Override
  public List<AmbiguousScholar> ambiguousScholars(int minCount, int limit) {
    return mapper.ambiguousScholars(minCount, limit).stream()
        .map(row -> new AmbiguousScholar(row.scholarId(), row.displayName(), row.pending()))
        .toList();
  }

  @Override
  public long countAmbiguousScholars(int minCount) {
    return mapper.countAmbiguousScholars(minCount);
  }

  @Override
  public long countPendingRelationsOfAmbiguous(int minCount) {
    return mapper.countPendingRelationsOfAmbiguous(minCount);
  }

  private static Sample toSample(GovernanceMapper.AnomalySampleRow row) {
    return new Sample(row.id(), row.title(), row.type(), row.publicationYear());
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
  public List<String> listScholarIdsOfAchievement(String achievementId) {
    return mapper.listScholarIdsOfAchievement(achievementId);
  }

  @Override
  public List<String> snapshotScholarContributions(String scholarId) {
    return mapper.snapshotScholarContributions(scholarId);
  }

  @Override
  public List<String> snapshotSources(String achievementId) {
    return mapper.snapshotSources(achievementId);
  }

  @Override
  public List<PreviewContribution> previewContributions(String achievementId) {
    return mapper.previewContributions(achievementId).stream()
        .map(
            row ->
                new PreviewContribution(
                    row.scholarId(), row.scholarName(), row.relationState(), row.role()))
        .toList();
  }

  @Override
  public List<PreviewSource> previewSources(String achievementId) {
    return mapper.previewSourceLinks(achievementId).stream()
        .map(row -> new PreviewSource(row.sourceId(), row.sourceRecordId()))
        .toList();
  }

  @Override
  public List<String> previewTopics(String achievementId) {
    return mapper.previewTopicIds(achievementId);
  }

  @Override
  public List<RegisteredIdentifier> previewIdentifiers(String entityType, String entityId) {
    return mapper.previewIdentifiers(entityType, entityId).stream()
        .map(
            row ->
                new RegisteredIdentifier(row.identifierKind(), row.scope(), row.normalizedValue()))
        .toList();
  }

  @Override
  public int moveIdentifiers(String entityType, String mergedId, String survivorId) {
    return mapper.moveIdentifiers(entityType, mergedId, survivorId);
  }

  // ---------------------------------------------------------------------------
  // 候选两端的实时状态
  // ---------------------------------------------------------------------------

  @Override
  public List<AchievementState> statesOf(Collection<String> ids) {
    if (ids == null || ids.isEmpty()) {
      // 空集合不能直接下传：`= ANY (ARRAY[]::text[])` 恒为假、结果为空，但先返回也省掉一次往返，
      // 并让「没有候选」与「候选两端都查不到」在调用侧无法混淆。
      return List.of();
    }
    Set<String> distinct = new LinkedHashSet<>(ids);
    return mapper.findAchievementStates(distinct.toArray(new String[0])).stream()
        .map(
            row ->
                new AchievementState(
                    row.id(),
                    row.achievementType(),
                    row.title(),
                    row.dataNature(),
                    row.visibility(),
                    row.effectiveStatus(),
                    row.mergedIntoId(),
                    row.publicationYear(),
                    row.yearPrecision()))
        .toList();
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
        row.resolutionReason(),
        row.rebasedToId());
  }
}
