package com.cognitanexus.core.catalog.application;

import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ContributionRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.FieldProvenanceRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.IdentifierRegistryRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.OrganizationRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ScholarRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.SourceConfigRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.SourceRecordRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.TopicRepository;
import com.cognitanexus.core.catalog.application.SourceIntakeCommands.BatchReceipt;
import com.cognitanexus.core.catalog.application.SourceIntakeCommands.RecordReceipt;
import com.cognitanexus.core.catalog.application.SourceIntakeCommands.SourceBatch;
import com.cognitanexus.core.catalog.application.SourceIntakeCommands.SourceRecordInput;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.AchievementDetails;
import com.cognitanexus.core.catalog.domain.Contribution;
import com.cognitanexus.core.catalog.domain.EffectiveStatus;
import com.cognitanexus.core.catalog.domain.IdentifierKind;
import com.cognitanexus.core.catalog.domain.PublicationYear;
import com.cognitanexus.core.catalog.domain.Scholar;
import com.cognitanexus.core.catalog.domain.SourceRecord;
import com.cognitanexus.core.governance.application.GovernanceService;
import com.cognitanexus.core.governance.domain.AutoPublishPolicy;
import com.cognitanexus.core.governance.domain.GovernanceAnomaly;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.event.EventTypes;
import com.cognitanexus.core.platform.event.OutboxEvent;
import com.cognitanexus.core.platform.event.OutboxStore;
import com.cognitanexus.core.platform.persistence.JsonCodec;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.util.Fingerprints;
import com.cognitanexus.core.platform.util.Identifiers;
import com.cognitanexus.core.platform.util.TextNormalizer;
import com.cognitanexus.sharedkernel.AchievementType;
import com.cognitanexus.sharedkernel.DataNature;
import com.cognitanexus.sharedkernel.RelationState;
import com.cognitanexus.sharedkernel.Visibility;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 来源接收入口：把 ingestion 交接来的规范化记录变成规范实体。
 *
 * <p>依据：架构 V0.2 §6.2、§4.3。
 *
 * <p>本服务是可运行版本的核心链路之一，遵循以下顺序与边界：
 *
 * <ol>
 *   <li>先校验来源启用状态与配置版本；来源停用后不得以旧副本继续接收；
 *   <li>按字段白名单过滤 payload，只持久化白名单内的字段；
 *   <li>以 <code>sourceId + sourceRecordId + contentFingerprint</code> 幂等落库，重复交接返回原接收结果；
 *   <li>先按稳定标识查找已有规范实体：命中则<b>只追加来源归属</b>，不新建实体（AC-IMPL-FR16-02）；
 *   <li>未命中才创建 PENDING_GOVERNANCE 实体，随后由 {@link AutoPublishPolicy} 决定能否自动发布；
 *   <li>不满足自动发布条件时留在待治理状态并登记候选或异常，绝不自作主张发布。
 * </ol>
 *
 * <p>整个批次在一个本地事务内提交（架构 §6.1「来源批次接收」）。所有插入都使用冲突容忍写法，
 * 因此单条重复不会让整个批次失败。
 */
@Service
public class SourceIntakeService {

  private static final Logger log = LoggerFactory.getLogger(SourceIntakeService.class);

  /** 标题相似候选阈值。属 SPEC 中待评审的业务参数，这里是可用初值而非已确认结论。 */
  private static final double TITLE_SIMILARITY_THRESHOLD = 0.55;

  private static final int SIMILAR_CANDIDATE_LIMIT = 5;

  /** 同名候选上限：超过该数量说明姓名过泛，不再逐个比对。 */
  private static final int MAX_NAME_CANDIDATES = 20;

  private final SourceConfigRepository sourceConfigs;
  private final SourceRecordRepository sourceRecords;
  private final AchievementRepository achievements;
  private final ScholarRepository scholars;
  private final ContributionRepository contributions;
  private final TopicRepository topics;
  private final OrganizationRepository organizations;
  private final IdentifierRegistryRepository identifiers;
  private final FieldProvenanceRepository provenance;
  private final GovernanceService governance;
  private final OutboxStore outbox;
  private final AuditRecorder audit;
  private final JsonCodec json;
  private final PublicProjectionAssembler projections;

  public SourceIntakeService(
      SourceConfigRepository sourceConfigs,
      SourceRecordRepository sourceRecords,
      AchievementRepository achievements,
      ScholarRepository scholars,
      ContributionRepository contributions,
      TopicRepository topics,
      OrganizationRepository organizations,
      IdentifierRegistryRepository identifiers,
      FieldProvenanceRepository provenance,
      GovernanceService governance,
      OutboxStore outbox,
      AuditRecorder audit,
      JsonCodec json,
      PublicProjectionAssembler projections) {
    this.sourceConfigs = sourceConfigs;
    this.sourceRecords = sourceRecords;
    this.achievements = achievements;
    this.scholars = scholars;
    this.contributions = contributions;
    this.topics = topics;
    this.organizations = organizations;
    this.identifiers = identifiers;
    this.provenance = provenance;
    this.governance = governance;
    this.outbox = outbox;
    this.audit = audit;
    this.json = json;
    this.projections = projections;
  }

  @Transactional
  public BatchReceipt accept(SourceBatch batch, ActorContext actor) {
    SourceConfigRepository.SourceConfig config =
        sourceConfigs
            .findByCode(batch.sourceCode())
            .orElseThrow(
                () ->
                    com.cognitanexus.sharedkernel.BusinessException.notFound(
                        "来源未登记：" + batch.sourceCode()));

    if (!config.enabled()) {
      // 来源停用：不做任何写入，也不返回「已接收」，否则管理端会把停用来源算成在处理
      governance.recordAnomaly(
          GovernanceAnomaly.KIND_SOURCE_DISABLED,
          null,
          null,
          Map.of("sourceCode", config.sourceCode(), "batchId", String.valueOf(batch.batchId())));
      log.warn("来源已停用，拒绝接收 sourceCode={}", config.sourceCode());
      return new BatchReceipt(
          config.id(),
          batch.batchId(),
          batch.records().size(),
          0,
          0,
          0,
          batch.records().size(),
          outbox.currentRevision(),
          batch.records().stream()
              .map(r -> RecordReceipt.rejected(r.sourceRecordId(), "来源已停用"))
              .toList());
    }

    // 原始记录的接收本身不改变公开目录，因此只标注当前修订值，不推进计数器
    long annotatedRevision = outbox.currentRevision();
    List<RecordReceipt> receipts = new ArrayList<>(batch.records().size());
    int accepted = 0;
    int attached = 0;
    int duplicate = 0;
    int rejected = 0;
    for (SourceRecordInput input : batch.records()) {
      RecordReceipt receipt = ingestOne(config, input, annotatedRevision);
      receipts.add(receipt);
      switch (receipt.status()) {
        case "ACCEPTED" -> accepted++;
        case "ATTACHED" -> attached++;
        case "DUPLICATE" -> duplicate++;
        default -> rejected++;
      }
    }

    audit.record(
        new AuditRecorder.AuditEntry(
            actor == null ? null : actor.accountIdValue(),
            actor == null
                ? com.cognitanexus.core.platform.security.RoleNames.SYSTEM
                : actor.username(),
            "SOURCE_BATCH_RECEIVED",
            "SOURCE",
            config.id(),
            null,
            Map.of(
                "batchId", String.valueOf(batch.batchId()),
                "received", batch.records().size(),
                "accepted", accepted,
                "attached", attached,
                "duplicate", duplicate,
                "rejected", rejected),
            "来源批次接收"));

    log.info(
        "来源批次接收完成 source={} batch={} 接收={} 新建={} 归属={} 重复={} 拒绝={}",
        config.sourceCode(),
        batch.batchId(),
        batch.records().size(),
        accepted,
        attached,
        duplicate,
        rejected);

    return new BatchReceipt(
        config.id(),
        batch.batchId(),
        batch.records().size(),
        accepted,
        attached,
        duplicate,
        rejected,
        outbox.currentRevision(),
        receipts);
  }

  // ---------------------------------------------------------------------------

  private RecordReceipt ingestOne(
      SourceConfigRepository.SourceConfig config, SourceRecordInput input, long annotatedRevision) {
    if (input.sourceRecordId() == null || input.sourceRecordId().isBlank()) {
      return RecordReceipt.rejected(input.sourceRecordId(), "缺少来源记录标识");
    }
    if (input.dataNature() == null) {
      // 数据性质不允许缺省：缺省成 REAL 会让合成数据被当成真实收录
      governance.recordAnomaly(
          GovernanceAnomaly.KIND_MISSING_REQUIRED_FIELD,
          null,
          null,
          Map.of("field", "dataNature", "sourceRecordId", input.sourceRecordId()));
      return RecordReceipt.rejected(input.sourceRecordId(), "缺少数据性质声明");
    }

    NormalizedRecord record = NormalizedRecord.of(input.payload());
    AchievementType kind = record.kind();
    String title = record.title();
    if (kind == null || title == null) {
      governance.recordAnomaly(
          GovernanceAnomaly.KIND_MISSING_REQUIRED_FIELD,
          null,
          null,
          Map.of(
              "field", kind == null ? "kind" : "title", "sourceRecordId", input.sourceRecordId()));
      return RecordReceipt.rejected(input.sourceRecordId(), "缺少成果类型或标题");
    }

    Map<String, Object> filtered = applyFieldWhitelist(input.payload(), config.allowedFields());
    String fingerprint =
        input.contentFingerprint() != null && !input.contentFingerprint().isBlank()
            ? input.contentFingerprint()
            : Fingerprints.of(
                record.fingerprintParts(input.sourceRecordId()).toArray(new String[0]));

    SourceRecord sourceRecord =
        new SourceRecord(
            Identifiers.next("rsr"),
            config.id(),
            input.sourceRecordId(),
            fingerprint,
            json.write(filtered),
            input.dataNature(),
            input.sourceUpdatedAt(),
            input.sourcePublishedAt(),
            input.fetchedAt() == null ? Instant.now() : input.fetchedAt(),
            null,
            annotatedRevision);

    if (!sourceRecords.insertIfAbsent(sourceRecord)) {
      // 幂等重复：返回原接收结果，且不改动任何原值
      Optional<String> linked =
          sourceRecords.findLinkedAchievementId(config.id(), input.sourceRecordId());
      return RecordReceipt.duplicate(input.sourceRecordId(), linked.orElse(null));
    }

    Optional<StableIdentifier> stable = resolveStableIdentifier(kind, record);
    if (stable.isPresent()) {
      Optional<String> existing =
          identifiers.findEntityId(
              "ACHIEVEMENT", stable.get().kind(), "", stable.get().normalizedValue());
      if (existing.isPresent()) {
        // 已有规范实体：只追加来源归属，不新建实体，跨源重复不增加成果实体数
        String achievementId = existing.get();
        achievements.linkSource(
            Identifiers.next("acs"),
            achievementId,
            sourceRecord.id(),
            config.id(),
            input.sourceUpdatedAt(),
            Instant.now());
        refreshSourceMetrics(achievementId, sourceRecord, record);
        return RecordReceipt.attached(
            input.sourceRecordId(), achievementId, "稳定标识已存在，追加来源归属而未新建实体");
      }
    }

    return createCanonical(config, input, record, sourceRecord, kind, title, stable);
  }

  private RecordReceipt createCanonical(
      SourceConfigRepository.SourceConfig config,
      SourceRecordInput input,
      NormalizedRecord record,
      SourceRecord sourceRecord,
      AchievementType kind,
      String title,
      Optional<StableIdentifier> stable) {

    String achievementId = Identifiers.next("ach");
    Instant now = Instant.now();
    Achievement achievement =
        new Achievement(
            achievementId,
            kind,
            title,
            TextNormalizer.normalize(title),
            input.dataNature(),
            // 新建实体一律先按受限可见性登记；只有通过自动发布判定才改为公开
            Visibility.RESTRICTED,
            EffectiveStatus.PENDING_GOVERNANCE,
            PublicationYear.of(record.publicationYear()),
            null,
            1L,
            now,
            now);
    achievements.insert(achievement);
    insertTypedExtension(achievementId, kind, record);

    if (stable.isPresent()) {
      boolean registered =
          identifiers.register(
              Identifiers.next("idr"),
              "ACHIEVEMENT",
              achievementId,
              stable.get().kind(),
              "",
              stable.get().normalizedValue());
      if (!registered) {
        // 并发下另一个事务先注册了同一标识：登记冲突异常，由人工决定归并方向
        governance.reportIdentifierConflict(
            stable.get().kind().name(), stable.get().normalizedValue(), "UNKNOWN", achievementId);
      }
    }

    achievements.linkSource(
        Identifiers.next("acs"),
        achievementId,
        sourceRecord.id(),
        config.id(),
        input.sourceUpdatedAt(),
        Instant.now());

    recordProvenance(achievementId, sourceRecord.id(), title, record);
    attachTopics(achievementId, record);
    AttachmentOutcome attachment =
        attachContributions(achievementId, sourceRecord.id(), input.dataNature(), record);

    registerSimilarityCandidates(achievement);

    AutoPublishPolicy.Decision decision =
        AutoPublishPolicy.evaluate(
            new AutoPublishPolicy.Inputs(
                stable.isPresent(),
                false,
                hasOpenCandidateFor(achievementId),
                input.dataNature().name()));

    List<String> notes = new ArrayList<>(attachment.notes());
    if (decision.publishable()) {
      long newVersion = achievement.version();
      int updated =
          achievements.updatePublicationState(
              achievementId,
              EffectiveStatus.EFFECTIVE.name(),
              Visibility.PUBLIC.name(),
              newVersion);
      if (updated == 1) {
        publishEvent(achievementId, achievement.type(), title, input.dataNature(), newVersion + 1);
        notes.add("已通过自动发布判定并进入公开目录");
      } else {
        notes.add("发布状态更新未命中预期版本，实体保留在待治理状态");
      }
    } else {
      notes.add("保留待治理：" + String.join("；", decision.reasons()));
    }
    return RecordReceipt.accepted(input.sourceRecordId(), achievementId)
        .withMessage(String.join("；", notes));
  }

  private void insertTypedExtension(
      String achievementId, AchievementType kind, NormalizedRecord record) {
    switch (kind) {
      case PAPER ->
          achievements.insertPaper(
              new AchievementDetails.Paper(
                  achievementId,
                  record.doi(),
                  TextNormalizer.normalizeStableIdentifier(record.doi()),
                  record.venue(),
                  record.publishedDate(),
                  record.preprintStatus(),
                  record.citationCount(),
                  record.citationSource(),
                  record.abstractText()));
      case PATENT ->
          achievements.insertPatent(
              new AchievementDetails.Patent(
                  achievementId,
                  record.applicationNo(),
                  TextNormalizer.normalizeStableIdentifier(record.applicationNo()),
                  record.publicationNo(),
                  record.ipcCodes(),
                  record.applicantNames(),
                  record.filingDate(),
                  record.patentPublicationDate()));
      case PROJECT ->
          achievements.insertProject(
              new AchievementDetails.ResearchProject(
                  achievementId,
                  record.projectNo(),
                  TextNormalizer.normalizeStableIdentifier(record.projectNo()),
                  record.leaderName(),
                  record.fundingAgency(),
                  record.projectCategory(),
                  record.projectStartDate(),
                  record.projectEndDate()));
      case AWARD ->
          achievements.insertAward(
              new AchievementDetails.Award(
                  achievementId,
                  record.awardName() == null ? record.title() : record.awardName(),
                  record.awardYear(),
                  record.awardLevel(),
                  record.grantingBody()));
    }
  }

  /**
   * 刷新既有论文的单一来源指标（引用量与来源）。
   *
   * <p>为什么放在「追加来源归属」分支而不是重建扩展行：实体已经存在，重建会连带重置 DOI、摘要、
   * 发表年份这些已经进入治理与人工核对视野的字段。指标只是随来源时间推移的观测值，与身份和治理
   * 状态是两件不同生命周期的事，只刷新它自己。
   *
   * <p>三道闸门，缺一不可：
   *
   * <ol>
   *   <li>本次来源记录没带指标就直接返回：不去用旧值「回填」，也不把缺失当成 0；
   *   <li>人工修订保护：字段已被 MANUAL/REVIEWED 标记时跳过。这一判断在 SQL 内与写入同语句完成，
   *       调用方这边的预检只是省一次无意义更新，不作为保护依据；
   *   <li>确实写进去了才发事件。指标变了而不外发，公开统计会永远停在旧值——而它看起来仍然
   *       「正常工作」，只是数字是过去的。
   * </ol>
   *
   * <p>顺带把溯源标成 SOURCE 并指向本次来源版本：这样「这个引用数是谁在哪一版记录里给的」
   * 始终可查，而不是一个来路不明的数字。
   */
  private void refreshSourceMetrics(
      String achievementId, SourceRecord sourceRecord, NormalizedRecord record) {
    Integer citationCount = record.citationCount();
    String citationSource = record.citationSource();
    if (citationCount == null && citationSource == null) {
      return;
    }
    int updated = achievements.refreshPaperMetrics(achievementId, citationCount, citationSource);
    if (updated != 1) {
      // 0 有三种可能：非论文类型没有 core_paper 行、成果已被合并、或指标受人工修订保护。
      // 都不属于错误，但必须可追溯，否则「统计里没有引用量」会变成查不出原因的现象。
      log.debug(
          "既有成果指标未刷新 achievementId={} citationCount={} 原因=非论文/已合并/受人工修订保护",
          achievementId,
          citationCount);
      return;
    }
    if (citationCount != null) {
      provenance.upsertSource(
          "ACHIEVEMENT",
          achievementId,
          "citationCount",
          String.valueOf(citationCount),
          sourceRecord.id());
    }
    if (citationSource != null) {
      provenance.upsertSource(
          "ACHIEVEMENT", achievementId, "citationSource", citationSource, sourceRecord.id());
    }
    Achievement current = achievements.findById(achievementId).orElse(null);
    if (current != null) {
      publishEvent(
          achievementId, current.type(), current.title(), current.dataNature(), current.version());
      log.info(
          "已按来源新版本刷新指标 achievementId={} citationCount={} source={}",
          achievementId,
          citationCount,
          citationSource);
    }
  }

  private void recordProvenance(
      String achievementId, String sourceRecordId, String title, NormalizedRecord record) {
    provenance.upsertSource("ACHIEVEMENT", achievementId, "title", title, sourceRecordId);
    if (record.doi() != null) {
      provenance.upsertSource("ACHIEVEMENT", achievementId, "doi", record.doi(), sourceRecordId);
    }
    if (record.venue() != null) {
      provenance.upsertSource(
          "ACHIEVEMENT", achievementId, "venue", record.venue(), sourceRecordId);
    }
    if (record.publicationYear() != null) {
      provenance.upsertSource(
          "ACHIEVEMENT",
          achievementId,
          "publicationYear",
          String.valueOf(record.publicationYear()),
          sourceRecordId);
    }
  }

  private void attachTopics(String achievementId, NormalizedRecord record) {
    String sourceCategory = record.sourceCategory();
    for (String topicName : record.topics()) {
      if (topicName == null || topicName.isBlank()) {
        continue;
      }
      String topicId = topics.findOrCreate(topicName.trim());
      topics.link(achievementId, topicId, sourceCategory);
    }
  }

  private AttachmentOutcome attachContributions(
      String achievementId, String sourceRecordId, DataNature dataNature, NormalizedRecord record) {
    List<String> notes = new ArrayList<>();
    for (NormalizedRecord.Author author : record.authors()) {
      String nameNorm = TextNormalizer.normalize(author.name());
      if (nameNorm == null || nameNorm.isBlank()) {
        continue;
      }
      Optional<String> viaIdentifier = resolveAuthorIdentifier(author);
      String scholarId;
      RelationState state;
      if (viaIdentifier.isPresent()) {
        scholarId = viaIdentifier.get();
        // 由稳定标识建立的关系才可标为自动关联
        state = RelationState.AUTO;
      } else {
        List<Scholar> sameName = scholars.findByExactNameNorm(nameNorm, MAX_NAME_CANDIDATES);
        if (sameName.isEmpty()) {
          scholarId = createScholar(author, nameNorm, dataNature);
          state = RelationState.AUTO;
        } else if (sameName.size() == 1) {
          scholarId = sameName.get(0).id();
          // 同名不足以确认为同一人：建立待核验关系，它不参与确定合作边
          state = RelationState.PENDING_VERIFY;
          notes.add("作者「" + author.name() + "」按同名匹配到既有学者，关系标为待核验");
        } else {
          governance.recordAnomaly(
              GovernanceAnomaly.KIND_IDENTIFIER_CONFLICT,
              null,
              achievementId,
              Map.of(
                  "reason", "AMBIGUOUS_SCHOLAR_NAME",
                  "name", author.name(),
                  "candidateCount", sameName.size(),
                  "candidates", sameName.stream().map(Scholar::id).toList()));
          notes.add("作者「" + author.name() + "」存在多个同名学者，未自动归属，已登记异常");
          continue;
        }
      }

      boolean created =
          contributions.insertIfAbsent(
              new Contribution(
                  Identifiers.next("ctr"),
                  scholarId,
                  achievementId,
                  Contribution.Role.AUTHOR.name(),
                  author.order(),
                  state,
                  sourceRecordId,
                  1L,
                  Instant.now(),
                  Instant.now()));
      if (!created) {
        continue;
      }
      String contributionId =
          contributions.findContributionId(scholarId, achievementId).orElse(null);
      if (contributionId == null) {
        continue;
      }
      for (String organization : author.organizations()) {
        if (organization == null || organization.isBlank()) {
          continue;
        }
        String organizationId =
            organizations.findOrCreate(organization.trim(), author.countryCode());
        contributions.linkOrganization(contributionId, organizationId);
      }
    }
    return new AttachmentOutcome(notes);
  }

  private Optional<String> resolveAuthorIdentifier(NormalizedRecord.Author author) {
    if (author.orcid() != null) {
      Optional<String> byOrcid =
          scholars.findScholarIdByOrcid(TextNormalizer.normalizeStableIdentifier(author.orcid()));
      if (byOrcid.isPresent()) {
        return byOrcid;
      }
    }
    if (author.openAlexId() != null) {
      return scholars.findScholarIdByOpenAlexId(
          TextNormalizer.normalizeStableIdentifier(author.openAlexId()));
    }
    return Optional.empty();
  }

  private String createScholar(
      NormalizedRecord.Author author, String nameNorm, DataNature dataNature) {
    String scholarId = Identifiers.next("sch");
    Instant now = Instant.now();
    String primaryOrganization =
        author.organizations().isEmpty() ? null : author.organizations().get(0);
    scholars.insert(
        new Scholar(
            scholarId,
            author.name(),
            nameNorm,
            dataNature,
            Scholar.ClaimedStatus.UNCLAIMED,
            primaryOrganization,
            null,
            null,
            1L,
            now,
            now));
    if (author.orcid() != null) {
      identifiers.register(
          Identifiers.next("idr"),
          "SCHOLAR",
          scholarId,
          IdentifierKind.ORCID,
          "",
          TextNormalizer.normalizeStableIdentifier(author.orcid()));
    }
    if (author.openAlexId() != null) {
      identifiers.register(
          Identifiers.next("idr"),
          "SCHOLAR",
          scholarId,
          IdentifierKind.OPENALEX_ID,
          "",
          TextNormalizer.normalizeStableIdentifier(author.openAlexId()));
    }
    return scholarId;
  }

  private void registerSimilarityCandidates(Achievement achievement) {
    for (Achievement exact : achievements.findByExactTitle(achievement.titleNorm(), 5)) {
      if (!exact.id().equals(achievement.id()) && exact.type() == achievement.type()) {
        governance.reportTitleSimilarity(achievement, exact, 1.0d);
      }
    }
    for (Achievement similar :
        achievements.findSimilarByTitle(
            achievement.titleNorm(), TITLE_SIMILARITY_THRESHOLD, SIMILAR_CANDIDATE_LIMIT)) {
      if (!similar.id().equals(achievement.id()) && similar.type() == achievement.type()) {
        governance.reportTitleSimilarity(achievement, similar, TITLE_SIMILARITY_THRESHOLD);
      }
    }
  }

  private boolean hasOpenCandidateFor(String achievementId) {
    return governance.countOpenCandidatesFor(achievementId) > 0;
  }

  /**
   * 外发一次目录变更。
   *
   * <p><b>可见性与生效状态必须取自持久化实体，不得在此推断。</b>这里是曾经出过一个真实缺陷的地方：
   * 早先本方法把 {@code visibility} 与 {@code effectiveStatus} 写死成 PUBLIC / EFFECTIVE，理由是
   * 「本方法只在通过自动发布判定之后被调用」。但本方法还有第二个调用点——既有身份收到来源新版本时
   * 刷新引用量。于是一条仍然处于 RESTRICTED / PENDING_GOVERNANCE 的成果，只要来源刷新了引用量，
   * 就会外发一条自称 PUBLIC 的事件，查询侧据此把它放进了公开检索。结果是：<b>核心服务的公开详情
   * 接口对它返回 404，而公开检索却能搜到它</b>——权威侧说不可见，投影侧说可见。
   *
   * <p>这个缺陷的教训不是「多加一个判断」，而是：<b>写死一个断言，等于把断言成立的隐含前提
   * 变成了全局约定</b>；后来任何新增的调用点都会静默地继承这个错误前提。因此这里一律以数据库为
   * 唯一事实来源。
   *
   * <p>取不到实体时**失败关闭**：按受限可见性与待治理状态外发。查不到实体时宣称「已公开」，
   * 是这类错误里后果最重的一种——公开视图会先于权威表把内容放出去，而事后很难判断哪些是误放的。
   */
  private void publishEvent(
      String achievementId,
      AchievementType type,
      String title,
      DataNature dataNature,
      long aggregateVersion) {
    Achievement current = achievements.findById(achievementId).orElse(null);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("changeKind", EventTypes.CHANGE_UPSERT);
    payload.put("achievementId", achievementId);
    payload.put("type", type.name());
    payload.put("title", title);
    payload.put("dataNature", dataNature.name());
    if (current == null) {
      // 失败关闭：宁可让投影把这条按受限保存（读路径会把它过滤掉），也不外发一个无法证实的「已公开」。
      log.warn(
          "外发目录变更时未能读到实体，按受限可见性外发 achievementId={} aggregateVersion={}",
          achievementId,
          aggregateVersion);
      payload.put("visibility", Visibility.RESTRICTED.name());
      payload.put("effectiveStatus", EffectiveStatus.PENDING_GOVERNANCE.name());
    } else {
      payload.put("visibility", current.visibility().name());
      payload.put("effectiveStatus", current.effectiveStatus().name());
      payload.put("publicationYear", current.publicationYear().year());
      payload.put("titleNorm", current.titleNorm());
    }
    achievements
        .findPaper(achievementId)
        .ifPresent(
            p -> {
              payload.put("doi", p.doi());
              payload.put("venue", p.venue());
              payload.put("citationCount", p.citationCount());
              payload.put("citationSource", p.citationSource());
              payload.put(
                  "publishedDate", p.publishedDate() == null ? null : p.publishedDate().toString());
              payload.put("abstractText", p.abstractText());
            });

    payload.put("contributions", projections.contributionsOf(achievementId));
    payload.put("topics", projections.topicIdsOf(achievementId));
    payload.put("topicViews", projections.topicsOf(achievementId));

    outbox.append(
        OutboxEvent.builder(
                EventTypes.CATALOG_CHANGE_COMMITTED, EventTypes.AGG_ACHIEVEMENT, achievementId)
            .aggregateVersion(aggregateVersion)
            .payload(payload));
  }

  /** 按来源声明的字段白名单过滤 payload；白名单为空表示不限制字段集合。 */
  private Map<String, Object> applyFieldWhitelist(
      Map<String, Object> payload, List<String> allowedFields) {
    if (payload == null) {
      return Map.of();
    }
    if (allowedFields == null || allowedFields.isEmpty()) {
      return payload;
    }
    Map<String, Object> filtered = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : payload.entrySet()) {
      if (allowedFields.contains(entry.getKey())) {
        filtered.put(entry.getKey(), entry.getValue());
      }
    }
    return filtered;
  }

  private Optional<StableIdentifier> resolveStableIdentifier(
      AchievementType kind, NormalizedRecord record) {
    return switch (kind) {
      case PAPER -> {
        String doiNorm = TextNormalizer.normalizeStableIdentifier(record.doi());
        yield doiNorm == null
            ? Optional.empty()
            : Optional.of(new StableIdentifier(IdentifierKind.DOI, doiNorm));
      }
      case PATENT -> {
        String norm = TextNormalizer.normalizeStableIdentifier(record.applicationNo());
        yield norm == null
            ? Optional.empty()
            : Optional.of(new StableIdentifier(IdentifierKind.PATENT_APPLICATION_NO, norm));
      }
      case PROJECT -> {
        String norm = TextNormalizer.normalizeStableIdentifier(record.projectNo());
        yield norm == null
            ? Optional.empty()
            : Optional.of(new StableIdentifier(IdentifierKind.PROJECT_NO, norm));
      }
      case AWARD -> Optional.empty();
    };
  }

  private record StableIdentifier(IdentifierKind kind, String normalizedValue) {}

  private record AttachmentOutcome(List<String> notes) {}
}
