package com.cognitanexus.core.catalog.infrastructure;

import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ContributionRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.FieldProvenanceRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.IdentifierRegistryRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.OrganizationRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.TopicRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.TopicRepository.TopicAssignment;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.AchievementDetails;
import com.cognitanexus.core.catalog.domain.Contribution;
import com.cognitanexus.core.catalog.domain.FieldProvenance;
import com.cognitanexus.core.catalog.domain.IdentifierKind;
import com.cognitanexus.core.platform.util.Identifiers;
import com.cognitanexus.core.platform.util.TextNormalizer;
import com.cognitanexus.sharedkernel.AchievementType;
import com.cognitanexus.sharedkernel.RelationState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 成果、关系、领域、机构、标识注册与字段溯源的仓储实现。
 *
 * <p>这些端口共享同一批表与外键，写入语义（同事务、唯一约束失败转可解释结果）完全一致，
 * 汇集在一个实现类中；应用层仍然只依赖各自的窄接口，不会因此获得越界访问能力。
 */
@Repository
public class CatalogCoreRepositories
    implements AchievementRepository,
        ContributionRepository,
        TopicRepository,
        OrganizationRepository,
        IdentifierRegistryRepository,
        FieldProvenanceRepository {

  private static final int MAX_MERGE_CHAIN = 8;

  private final AchievementMapper achievementMapper;
  private final RelationshipMapper relationshipMapper;

  public CatalogCoreRepositories(
      AchievementMapper achievementMapper, RelationshipMapper relationshipMapper) {
    this.achievementMapper = achievementMapper;
    this.relationshipMapper = relationshipMapper;
  }

  // ---------------------------------------------------------------------------
  // 成果
  // ---------------------------------------------------------------------------

  @Override
  public void insert(Achievement achievement) {
    achievementMapper.insert(
        achievement.id(),
        achievement.type().name(),
        achievement.title(),
        achievement.titleNorm(),
        achievement.dataNature().name(),
        achievement.visibility().name(),
        achievement.effectiveStatus().name(),
        achievement.publicationYear().year(),
        achievement.publicationYear().precision() == null
            ? null
            : achievement.publicationYear().precision().name());
  }

  @Override
  public Optional<Achievement> findById(String id) {
    return achievementMapper.findById(id).map(achievementMapper::toDomain);
  }

  /** 沿着合并链解析到最终幸存实体；链长有界，避免历史数据异常造成死循环。 */
  @Override
  public Optional<String> resolveSurvivorId(String achievementId) {
    String current = achievementId;
    for (int hop = 0; hop < MAX_MERGE_CHAIN; hop++) {
      Optional<String> next = achievementMapper.findMergedIntoId(current);
      if (next.isEmpty() || next.get() == null) {
        return Optional.of(current);
      }
      current = next.get();
    }
    return Optional.empty();
  }

  @Override
  public void insertPaper(AchievementDetails.Paper paper) {
    achievementMapper.insertPaper(
        paper.achievementId(),
        paper.doi(),
        paper.doiNorm(),
        paper.venue(),
        paper.publishedDate(),
        paper.preprintStatus(),
        paper.citationCount(),
        paper.citationSource(),
        paper.abstractText());
  }

  @Override
  public int refreshPaperMetrics(
      String achievementId, Integer citationCount, String citationSource) {
    return achievementMapper.refreshPaperMetrics(achievementId, citationCount, citationSource);
  }

  @Override
  public void insertPatent(AchievementDetails.Patent patent) {
    achievementMapper.insertPatent(
        patent.achievementId(),
        patent.applicationNo(),
        patent.applicationNoNorm(),
        patent.publicationNo(),
        textArray(patent.ipcCodes()),
        textArray(patent.applicantNames()),
        patent.filingDate(),
        patent.publicationDate());
  }

  /**
   * 领域模型用 List，JDBC 侧必须是 String[]。
   *
   * <p>StringArrayTypeHandler 以 String[] 作为形参类型并用 Connection.createArrayOf 构造数组，
   * 直接把 List 交给它会在类型强转处抛 ClassCastException；此处统一转换，且区分「空数组」与
   * 「无该字段」——null 保持 null，不让未知信息被写成空数组。
   */
  private static String[] textArray(List<String> values) {
    return values == null ? null : values.toArray(String[]::new);
  }

  @Override
  public void insertProject(AchievementDetails.ResearchProject project) {
    achievementMapper.insertProject(
        project.achievementId(),
        project.projectNo(),
        project.projectNoNorm(),
        project.leaderName(),
        project.fundingAgency(),
        project.projectCategory(),
        project.startDate(),
        project.endDate());
  }

  @Override
  public void insertAward(AchievementDetails.Award award) {
    achievementMapper.insertAward(
        award.achievementId(),
        award.awardName(),
        award.awardYear(),
        award.awardLevel(),
        award.grantingBody());
  }

  @Override
  public Optional<AchievementDetails.Paper> findPaper(String achievementId) {
    return achievementMapper.findPaper(achievementId).map(achievementMapper::toPaper);
  }

  @Override
  public Optional<AchievementDetails.Patent> findPatent(String achievementId) {
    return achievementMapper.findPatent(achievementId).map(achievementMapper::toPatent);
  }

  @Override
  public Optional<AchievementDetails.ResearchProject> findProject(String achievementId) {
    return achievementMapper.findProject(achievementId).map(achievementMapper::toProject);
  }

  @Override
  public Optional<AchievementDetails.Award> findAward(String achievementId) {
    return achievementMapper.findAward(achievementId).map(achievementMapper::toAward);
  }

  @Override
  public int updatePublicationState(
      String achievementId, String effectiveStatus, String visibility, long expectedVersion) {
    return achievementMapper.updatePublicationState(
        achievementId, effectiveStatus, visibility, expectedVersion);
  }

  @Override
  public Optional<String> findAchievementIdByDoiNorm(String doiNorm) {
    return achievementMapper.findRegisteredEntity("ACHIEVEMENT", "DOI", "", doiNorm);
  }

  @Override
  public Optional<String> findAchievementIdByApplicationNoNorm(String applicationNoNorm) {
    return achievementMapper.findRegisteredEntity(
        "ACHIEVEMENT", "PATENT_APPLICATION_NO", "", applicationNoNorm);
  }

  @Override
  public Optional<String> findAchievementIdByProjectNoNorm(String projectNoNorm) {
    return achievementMapper.findRegisteredEntity("ACHIEVEMENT", "PROJECT_NO", "", projectNoNorm);
  }

  @Override
  public void linkSource(
      String id,
      String achievementId,
      String sourceRecordId,
      String sourceId,
      Instant sourceUpdatedAt,
      Instant receivedAt) {
    achievementMapper.linkSource(
        id, achievementId, sourceRecordId, sourceId, sourceUpdatedAt, receivedAt);
  }

  @Override
  public List<AchievementRepository.SimilarTitle> findSimilarByTitle(
      String titleNorm, double threshold, int limit) {
    return achievementMapper.findSimilarByTitle(titleNorm, threshold, limit).stream()
        .map(
            row ->
                new AchievementRepository.SimilarTitle(
                    achievementMapper.toDomain(row),
                    row.similarity() == null ? 0d : row.similarity()))
        .toList();
  }

  @Override
  public List<Achievement> findByExactTitle(String titleNorm, int limit) {
    return achievementMapper.findByExactTitle(titleNorm, limit).stream()
        .map(achievementMapper::toDomain)
        .toList();
  }

  @Override
  public List<Achievement> listByStatus(String effectiveStatus, int limit, int offset) {
    return achievementMapper.listByStatus(effectiveStatus, limit, offset).stream()
        .map(achievementMapper::toDomain)
        .toList();
  }

  @Override
  public long countByStatus(String effectiveStatus) {
    return achievementMapper.countByStatus(effectiveStatus);
  }

  @Override
  public List<Achievement> listPublicForExport(int limit, int offset) {
    return achievementMapper.listPublicForExport(limit, offset).stream()
        .map(achievementMapper::toDomain)
        .toList();
  }

  @Override
  public long countPublicForExport() {
    return achievementMapper.countPublicForExport();
  }

  @Override
  public List<String> findExclusivelyFromSource(String sourceId, int limit) {
    return achievementMapper.findExclusivelyFromSource(sourceId, limit);
  }

  @Override
  public int tightenVisibility(String achievementId, String visibility) {
    return achievementMapper.tightenVisibility(achievementId, visibility);
  }

  @Override
  public Double titleSimilarity(String achievementId, String titleNorm) {
    return achievementMapper.titleSimilarity(achievementId, titleNorm);
  }

  @Override
  public void lockStableIdentifier(String kind, String normalizedValue) {
    // 键包含种类：DOI「10.1234/abc」与某专利号归一后可能同形，混用同一个键只会造成无关互斥。
    achievementMapper.lockStableIdentifier(kind + ":" + normalizedValue);
  }

  @Override
  public long countByNatureAndStatus(String dataNature, String effectiveStatus) {
    return achievementMapper.countByNatureAndStatus(dataNature, effectiveStatus);
  }

  // ---------------------------------------------------------------------------
  // 参与关系
  // ---------------------------------------------------------------------------

  @Override
  public boolean insertIfAbsent(Contribution contribution) {
    int affected =
        relationshipMapper.insertContributionIfAbsent(
            contribution.id(),
            contribution.scholarId(),
            contribution.achievementId(),
            contribution.role(),
            contribution.authorOrder(),
            contribution.relationState().name(),
            contribution.sourceRecordId());
    return affected == 1;
  }

  @Override
  public Optional<String> findContributionId(String scholarId, String achievementId) {
    return relationshipMapper.findContributionId(scholarId, achievementId);
  }

  @Override
  public List<Contribution> listByAchievement(String achievementId) {
    return relationshipMapper.listByAchievement(achievementId).stream()
        .map(relationshipMapper::toDomain)
        .toList();
  }

  @Override
  public List<Contribution> listByScholar(String scholarId) {
    return relationshipMapper.listByScholar(scholarId).stream()
        .map(relationshipMapper::toDomain)
        .toList();
  }

  @Override
  public Optional<Contribution> findContributionById(String contributionId) {
    return relationshipMapper
        .findContributionById(contributionId)
        .map(relationshipMapper::toDomain);
  }

  @Override
  public int transitionRelationState(String contributionId, RelationState from, RelationState to) {
    return relationshipMapper.transitionRelationState(contributionId, from.name(), to.name());
  }

  @Override
  public List<Contribution> listByRelationState(RelationState state, int limit) {
    return relationshipMapper.listByRelationState(state.name(), limit).stream()
        .map(relationshipMapper::toDomain)
        .toList();
  }

  @Override
  public void linkOrganization(String contributionId, String organizationId) {
    relationshipMapper.linkOrganization(contributionId, organizationId);
  }

  @Override
  public List<String> listOrganizationNames(String contributionId) {
    return relationshipMapper.listOrganizationNames(contributionId);
  }

  @Override
  public long countContributions() {
    return relationshipMapper.countContributions();
  }

  // ---------------------------------------------------------------------------
  // 领域
  // ---------------------------------------------------------------------------

  @Override
  public String findOrCreate(String canonicalName) {
    String nameNorm = TextNormalizer.normalize(canonicalName);
    Optional<String> existing = relationshipMapper.findTopicIdByNameNorm(nameNorm);
    if (existing.isPresent()) {
      return existing.get();
    }
    String id = Identifiers.next("top");
    relationshipMapper.insertTopicIfAbsent(id, canonicalName, nameNorm, null);
    return relationshipMapper.findTopicIdByNameNorm(nameNorm).orElse(id);
  }

  @Override
  public Optional<Topic> findTopic(String id) {
    return relationshipMapper.findTopicById(id);
  }

  @Override
  public void link(String achievementId, String topicId, String sourceCategory) {
    relationshipMapper.linkTopic(achievementId, topicId, sourceCategory);
  }

  @Override
  public List<String> listTopicIdsOf(String achievementId) {
    return relationshipMapper.listTopicIdsOf(achievementId);
  }

  @Override
  public List<TopicAssignment> listAssignmentsOf(String achievementId) {
    return relationshipMapper.listAssignmentsOf(achievementId);
  }

  @Override
  public int clearInferred(String achievementId) {
    int removed = relationshipMapper.deleteInferredTopicsOfAchievement(achievementId);
    relationshipMapper.deleteInferenceOfAchievement(achievementId);
    return removed;
  }

  @Override
  public long countTopics() {
    return relationshipMapper.countTopics();
  }

  // ---------------------------------------------------------------------------
  // 机构
  // ---------------------------------------------------------------------------

  @Override
  public String findOrCreate(String canonicalName, String countryCode) {
    String nameNorm = TextNormalizer.normalize(canonicalName);
    Optional<String> existing = relationshipMapper.findOrganizationIdByNameNorm(nameNorm);
    if (existing.isPresent()) {
      return existing.get();
    }
    String id = Identifiers.next("org");
    relationshipMapper.insertOrganizationIfAbsent(id, canonicalName, nameNorm, countryCode);
    return relationshipMapper.findOrganizationIdByNameNorm(nameNorm).orElse(id);
  }

  @Override
  public Optional<String> findIdByNameNorm(String nameNorm) {
    return relationshipMapper.findOrganizationIdByNameNorm(nameNorm);
  }

  // ---------------------------------------------------------------------------
  // 稳定标识注册
  // ---------------------------------------------------------------------------

  @Override
  public Optional<String> findEntityId(
      String entityType, IdentifierKind kind, String scope, String normalizedValue) {
    return achievementMapper.findRegisteredEntity(
        entityType, kind.name(), scope == null ? "" : scope, normalizedValue);
  }

  @Override
  public boolean register(
      String id,
      String entityType,
      String entityId,
      IdentifierKind kind,
      String scope,
      String normalizedValue) {
    int affected =
        achievementMapper.registerIdentifier(
            id, entityType, entityId, kind.name(), scope == null ? "" : scope, normalizedValue);
    return affected == 1;
  }

  @Override
  public boolean hasRegistration(String entityType, String entityId) {
    return achievementMapper.hasRegisteredIdentifier(entityType, entityId);
  }

  @Override
  public List<String> registeredValues(String entityType, String entityId, IdentifierKind kind) {
    return achievementMapper.findRegisteredValues(entityType, entityId, kind.name());
  }

  // ---------------------------------------------------------------------------
  // 字段溯源
  // ---------------------------------------------------------------------------

  @Override
  public void upsertSource(
      String entityType,
      String entityId,
      String fieldName,
      String valueText,
      String sourceRecordId) {
    achievementMapper.upsertSourceProvenance(
        Identifiers.next("prv"), entityType, entityId, fieldName, valueText, sourceRecordId);
  }

  @Override
  public Optional<FieldProvenance> find(String entityType, String entityId, String fieldName) {
    return achievementMapper
        .findProvenance(entityType, entityId, fieldName)
        .map(
            row ->
                new FieldProvenance(
                    row.id(),
                    row.entityType(),
                    row.entityId(),
                    row.fieldName(),
                    FieldProvenance.ValueLevel.of(row.valueLevel()),
                    row.valueText(),
                    row.sourceRecordId()));
  }

  @Override
  public List<FieldProvenance> listByEntity(String entityType, String entityId) {
    return achievementMapper.listProvenance(entityType, entityId).stream()
        .map(
            row ->
                new FieldProvenance(
                    row.id(),
                    row.entityType(),
                    row.entityId(),
                    row.fieldName(),
                    FieldProvenance.ValueLevel.of(row.valueLevel()),
                    row.valueText(),
                    row.sourceRecordId()))
        .toList();
  }

  @Override
  public void upsertReviewed(
      String entityType, String entityId, String fieldName, String valueLevel, String valueText) {
    upsertReviewedRow(entityType, entityId, fieldName, valueLevel, valueText);
  }

  /** 人工或审核值写入；来源自动更新不得覆盖 MANUAL/REVIEWED。 */
  private int upsertReviewedRow(
      String entityType, String entityId, String fieldName, String valueLevel, String valueText) {
    return achievementMapper.upsertReviewedProvenance(
        Identifiers.next("prv"), entityType, entityId, fieldName, valueLevel, valueText);
  }

  int countExtensions(String achievementId, AchievementType type) {
    return achievementMapper.countExtensions(achievementId, type);
  }
}
