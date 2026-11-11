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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
  private final CatalogChangePublisher catalogChanges;

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
      PublicProjectionAssembler projections,
      CatalogChangePublisher catalogChanges) {
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
    this.catalogChanges = catalogChanges;
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

    // 白名单必须在解析之前生效：过滤后的 payload 才是本服务允许读取的事实。
    //
    // 改动前的写法是先 NormalizedRecord.of(input.payload()) 再用 filtered 存原始 JSON，
    // 于是规范化成果、类型扩展、贡献、领域读的都是过滤前的 record——原始 JSON 干净了，
    // 规范表里仍然留着被禁字段（例如题名之外还写进了来源不该提供的摘要）。
    // 白名单字段名与规范化字段名一致（见各来源的 allowed_fields 配置），因此这里无需再做映射。
    Map<String, Object> filtered = applyFieldWhitelist(input.payload(), config.allowedFields());
    NormalizedRecord record = NormalizedRecord.of(filtered);
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
      // 同一标识的「先查后建」必须互斥：并发首采时两个事务都查不到标识，各自新建成果，
      // 后提交的一方会在扩展表唯一索引上失败并整批回滚——调用方只看到一次 500。
      // 锁住标识键后，后到的一方在这里等前一方提交，醒来就能看见已注册的标识并改走追加路径。
      achievements.lockStableIdentifier(stable.get().kind().name(), stable.get().normalizedValue());
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
        // 标识一致不等于证据相容（SPEC DD-03：一致类型且无矛盾证据）。
        // 先取证再决定要不要采用本条记录的其它事实。
        List<String> conflicts =
            reportEvidenceConflicts(achievementId, kind, title, record, sourceRecord);
        if (conflicts.isEmpty()) {
          refreshSourceMetrics(achievementId, sourceRecord, record);
          return RecordReceipt.attached(
              input.sourceRecordId(), achievementId, "稳定标识已存在，追加来源归属而未新建实体");
        }
        return RecordReceipt.attached(
            input.sourceRecordId(),
            achievementId,
            "稳定标识已存在，已追加来源归属；但本条记录与既有成果在"
                + String.join("、", conflicts)
                + "上明显矛盾，未采用这些字段与指标，已登记治理冲突待裁决");
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
                input.dataNature().name(),
                config.displayLicenseGranted()));

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

  /**
   * 检查同一稳定标识下的证据是否相容；不相容则登记治理冲突，并返回矛盾的字段名。
   *
   * <p>为什么需要这一步：稳定标识只回答「这一条讲的是哪一篇」，不回答「这一条讲得对不对」。上游把 DOI 填错、
   * 复用旧 DOI、或被同名的另一篇文献冒用，都会让两条内容完全不同的记录落到同一个标识上。命中标识就无条件
   * 追加来源、刷新指标，等于把后一条的题名、年份与引用量当成前一条的事实——数据上看不出任何异常，
   * 但公开详情里会出现一篇题名与引用量对不上的成果。
   *
   * <p>判据只取"明显矛盾"，不设新的相似度阈值：
   *
   * <ul>
   *   <li>类型不同——同一标识不可能同时是论文与专利；
   *   <li>年份不同——两篇皆非空且不相等即可，不需要容忍区间；
   *   <li>作者完全不相交——两边都至少两人且没有一个共同姓名；一方只给一位作者是常见形态，
   *       那种情况下不相交不足以说明问题，故不判；
   *   <li>题名相似度低于既有的候选阈值 {@link #TITLE_SIMILARITY_THRESHOLD}——复用它而不是新设一个，
   *       否则「够得上候选」与「算不算矛盾」会用两把尺子，同一条记录在两处得到相反结论。
   *       该阈值是否适合表达"明显矛盾"仍待评审，这里只登记冲突、不做任何处置。
   * </ul>
   *
   * <p>登记冲突不等于处置：既有成果的题名、年份、类型一律不改，本条记录的指标也不刷新，由人工裁决
   * 到底哪一条才属于这个标识。人工已审核过的字段（MANUAL／REVIEWED）同样不会被改写——溯源表在写入时
   * 已自带这层保护，这里只是把"受保护"这件事写进证据，让处置人知道为什么来源新值没有生效。
   */
  private List<String> reportEvidenceConflicts(
      String achievementId,
      AchievementType kind,
      String title,
      NormalizedRecord record,
      SourceRecord sourceRecord) {

    Achievement canonical = achievements.findById(achievementId).orElse(null);
    if (canonical == null) {
      return List.of();
    }
    List<String> conflictFields = new ArrayList<>();
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("reason", "STABLE_IDENTIFIER_EVIDENCE_CONFLICT");

    if (canonical.type() != kind) {
      conflictFields.add("type");
      detail.put("canonicalType", canonical.type().name());
      detail.put("incomingType", kind.name());
    }

    Integer canonicalYear =
        canonical.publicationYear() == null ? null : canonical.publicationYear().year();
    Integer incomingYear = record.publicationYear();
    // 年份用 Objects.equals：装箱后的 Integer 用 == 比较会把两个 2023 判成不同年。
    if (canonicalYear != null
        && incomingYear != null
        && !Objects.equals(canonicalYear, incomingYear)) {
      conflictFields.add("publicationYear");
      detail.put("canonicalYear", canonicalYear);
      detail.put("incomingYear", incomingYear);
    }

    String incomingTitleNorm = TextNormalizer.normalize(title);
    if (incomingTitleNorm != null && !incomingTitleNorm.equals(canonical.titleNorm())) {
      Double similarity = achievements.titleSimilarity(achievementId, incomingTitleNorm);
      if (similarity != null && similarity < TITLE_SIMILARITY_THRESHOLD) {
        conflictFields.add("title");
        detail.put("canonicalTitle", canonical.title());
        detail.put("incomingTitle", title);
        detail.put("titleSimilarity", Math.round(similarity * 1000d) / 1000d);
      }
    }

    List<String> canonicalAuthors = canonicalAuthorNames(achievementId);
    List<String> incomingAuthors = new ArrayList<>();
    for (NormalizedRecord.Author author : record.authors()) {
      String nameNorm = TextNormalizer.normalize(author.name());
      if (nameNorm != null && !nameNorm.isBlank()) {
        incomingAuthors.add(nameNorm);
      }
    }
    if (canonicalAuthors.size() >= 2
        && incomingAuthors.size() >= 2
        && Collections.disjoint(canonicalAuthors, incomingAuthors)) {
      conflictFields.add("authors");
      detail.put("canonicalAuthors", canonicalAuthors);
      detail.put("incomingAuthors", incomingAuthors);
    }

    if (conflictFields.isEmpty()) {
      return List.of();
    }

    String titleLevel =
        provenance
            .find("ACHIEVEMENT", achievementId, "title")
            .map(row -> row.valueLevel().name())
            .orElse("SOURCE");
    detail.put("conflictingFields", conflictFields);
    detail.put("titleValueLevel", titleLevel);
    detail.put("manualProtected", "MANUAL".equals(titleLevel) || "REVIEWED".equals(titleLevel));
    detail.put("sourceRecordRef", sourceRecord.sourceRecordId());
    detail.put("rawSourceRecordId", sourceRecord.id());
    detail.put("note", "标识一致不构成证据相容；矛盾字段未采用、指标未刷新。请核实来源原始报文，" + "确认该标识归属后再决定保留哪一边。");

    governance.recordAnomaly(
        GovernanceAnomaly.KIND_METADATA_CONFLICT, sourceRecord.id(), achievementId, detail);
    log.warn(
        "稳定标识命中既有成果但证据矛盾 achievementId={} 矛盾字段={} 来源记录={}",
        achievementId,
        conflictFields,
        sourceRecord.sourceRecordId());
    return conflictFields;
  }

  /** 既有成果的参与者姓名（归一后），用于比对作者是否完全不相交。 */
  private List<String> canonicalAuthorNames(String achievementId) {
    List<String> names = new ArrayList<>();
    for (Contribution contribution : contributions.listByAchievement(achievementId)) {
      scholars
          .findById(contribution.scholarId())
          .map(Scholar::nameNorm)
          .filter(name -> name != null && !name.isBlank())
          .ifPresent(names::add);
    }
    return names;
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
    List<String> names = new ArrayList<>();
    for (String topicName : record.topics()) {
      if (topicName != null && !topicName.isBlank()) {
        names.add(topicName.trim());
      }
    }
    if (names.isEmpty()) {
      return;
    }
    // 来源带来分类时，先清掉此前由规则推断的归属，再落来源分类。
    //
    // 顺序不能反：归属表主键是 (成果, 领域)，若先落来源分类而该领域恰好已被推断占用，
    // ON CONFLICT DO NOTHING 会让来源分类写不进去，那一格继续挂着一张 INFERRED 行——
    // 来源在场却标着「系统推断」，恰好是最不该出现的一种错。
    //
    // 清除的对象只是推断产物，不改动任何来源原值：来源是权威，推断是它缺席时的替代品，
    // 权威到场后替代品应当退场，而不是与权威并列显示成两个独立来源。
    topics.clearInferred(achievementId);
    for (String name : names) {
      String topicId = topics.findOrCreate(name);
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
      String orcid = normalizeIdentifier(IdentifierKind.ORCID, author.orcid());
      String openAlexId = normalizeIdentifier(IdentifierKind.OPENALEX_ID, author.openAlexId());
      String scholarId;
      RelationState state;
      if (viaIdentifier.isPresent()) {
        scholarId = viaIdentifier.get();
        // 由稳定标识建立的关系才可标为自动关联
        state = RelationState.AUTO;
        // 两种强标识指向不同实体是明确矛盾：不能凭「先查到哪个就用哪个」继续。
        if (openAlexId != null) {
          Optional<String> byOpenAlex = scholars.findScholarIdByOpenAlexId(openAlexId);
          if (byOpenAlex.isPresent() && !byOpenAlex.get().equals(scholarId)) {
            governance.reportIdentifierConflict(
                IdentifierKind.OPENALEX_ID.name(), openAlexId, scholarId, achievementId);
            notes.add("作者「" + author.name() + "」的 ORCID 与 OpenAlex 标识指向不同学者，已登记冲突");
          }
        }
      } else {
        List<Scholar> sameName = scholars.findByExactNameNorm(nameNorm, MAX_NAME_CANDIDATES);
        if (sameName.isEmpty()) {
          scholarId = createScholar(author, nameNorm, dataNature);
          state = RelationState.AUTO;
        } else if (sameName.size() == 1) {
          Scholar existing = sameName.get(0);
          if (orcid == null && openAlexId == null) {
            // 无标识的同名：不足以确认为同一人，建待核验关系，不参与确定合作边
            scholarId = existing.id();
            state = RelationState.PENDING_VERIFY;
            notes.add("作者「" + author.name() + "」按同名匹配到既有学者，关系标为待核验");
          } else if (holdsAnotherOrcid(existing, orcid)) {
            // 同名且各持一枚不同的 ORCID。ORCID 全局唯一，这是两个不同的人：
            // 不合并、不覆盖既有身份，建新身份并把同名事实登记为冲突留待人工确认。
            scholarId = createScholar(author, nameNorm, dataNature);
            state = RelationState.PENDING_VERIFY;
            governance.reportIdentifierConflict(
                IdentifierKind.ORCID.name(), orcid, existing.id(), achievementId);
            notes.add("作者「" + author.name() + "」与既有同名学者持不同 ORCID，已建立独立身份并登记冲突待人工确认");
          } else {
            // 既有同名学者还没有同类标识，可能是同一个人这次才带上标识：
            // 补登标识而不是新建身份，避免把人拆成两个；补登仍不等于确认关系。
            scholarId = existing.id();
            state = RelationState.PENDING_VERIFY;
            boolean registered = registerAuthorIdentifiers(existing.id(), orcid, openAlexId);
            notes.add(
                registered
                    ? "作者「" + author.name() + "」按同名匹配到既有学者，已补登其稳定标识，关系仍待核验"
                    : "作者「" + author.name() + "」的标识已被其他学者占用，已登记冲突，关系待核验");
          }
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
          scholars.findScholarIdByOrcid(normalizeIdentifier(IdentifierKind.ORCID, author.orcid()));
      if (byOrcid.isPresent()) {
        return byOrcid;
      }
    }
    if (author.openAlexId() != null) {
      return scholars.findScholarIdByOpenAlexId(
          normalizeIdentifier(IdentifierKind.OPENALEX_ID, author.openAlexId()));
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
          normalizeIdentifier(IdentifierKind.ORCID, author.orcid()));
    }
    if (author.openAlexId() != null) {
      identifiers.register(
          Identifiers.next("idr"),
          "SCHOLAR",
          scholarId,
          IdentifierKind.OPENALEX_ID,
          "",
          normalizeIdentifier(IdentifierKind.OPENALEX_ID, author.openAlexId()));
    }
    return scholarId;
  }

  /**
   * 该学者是否已持有一枚不同的 ORCID。
   *
   * <p>ORCID 全局唯一，两个不同的值只能对应两个不同的人；仅凭同名就复用会把两人并成一个。
   */
  private boolean holdsAnotherOrcid(Scholar scholar, String orcid) {
    if (orcid == null) {
      return false;
    }
    List<String> registered =
        identifiers.registeredValues("SCHOLAR", scholar.id(), IdentifierKind.ORCID);
    return !registered.isEmpty() && !registered.contains(orcid);
  }

  /**
   * 把来源带来的强标识补登到既有学者。
   *
   * @return false 表示至少一个标识已被其他实体占用（此时须登记冲突，不能当作登记成功）
   */
  private boolean registerAuthorIdentifiers(String scholarId, String orcid, String openAlexId) {
    boolean allRegistered = true;
    if (orcid != null) {
      // 这里的 &= 故意不短路：两个标识都要尝试登记，不能因为第一个失败就跳过第二个。
      allRegistered &=
          identifiers.register(
              Identifiers.next("idr"), "SCHOLAR", scholarId, IdentifierKind.ORCID, "", orcid);
    }
    if (openAlexId != null) {
      allRegistered &=
          identifiers.register(
              Identifiers.next("idr"),
              "SCHOLAR",
              scholarId,
              IdentifierKind.OPENALEX_ID,
              "",
              openAlexId);
    }
    return allRegistered;
  }

  private void registerSimilarityCandidates(Achievement achievement) {
    for (Achievement exact : achievements.findByExactTitle(achievement.titleNorm(), 5)) {
      if (!exact.id().equals(achievement.id()) && exact.type() == achievement.type()) {
        // 精确同标题：相似度为 1，这是候选里最需要优先复核的一类
        governance.reportTitleSimilarity(achievement, exact, 1.0d);
      }
    }
    for (AchievementRepository.SimilarTitle similar :
        achievements.findSimilarByTitle(
            achievement.titleNorm(), TITLE_SIMILARITY_THRESHOLD, SIMILAR_CANDIDATE_LIMIT)) {
      Achievement candidate = similar.achievement();
      if (!candidate.id().equals(achievement.id()) && candidate.type() == achievement.type()) {
        // 写真实相似度而不是阈值：阈值只说明「过线了」，相似度才说明「有多像」
        governance.reportTitleSimilarity(achievement, candidate, similar.similarity());
      }
    }
  }

  private boolean hasOpenCandidateFor(String achievementId) {
    return governance.countOpenCandidatesFor(achievementId) > 0;
  }

  /**
   * 外发一次目录变更。
   *
   * <p>装配口径已收敛到 {@link CatalogChangePublisher}：此处只保留这一层薄转发，避免出现第二个
   * 字段集定义（人工确认发布与自动发布曾经各拼一份，字段集不同，回放时会丢论文指标）。
   */
  private void publishEvent(
      String achievementId,
      AchievementType type,
      String title,
      DataNature dataNature,
      long aggregateVersion) {
    catalogChanges.publish(achievementId, type, title, dataNature, aggregateVersion);
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

  /**
   * 按标识种类归一化。
   *
   * <p>{@link TextNormalizer#normalizeStableIdentifier} 只处理 DOI 前缀：ORCID 与 OpenAlex 的 URL
   * 写法会被原样保留，于是同一人的 {@code https://orcid.org/0000-...} 与裸值变成两个不同标识，
   * 之后按标识查找必然漏掉其中一个（表现为「同一人第二次出现时只能靠同名匹配」）。
   * 归一规则必须按种类区分，不能假设「去掉 DOI 前缀就算归一完成」。
   */
  private static String normalizeIdentifier(IdentifierKind kind, String raw) {
    String value = TextNormalizer.normalizeStableIdentifier(raw);
    if (value == null) {
      return null;
    }
    List<String> prefixes =
        switch (kind) {
          case ORCID ->
              List.of(
                  "https://orcid.org/",
                  "http://orcid.org/",
                  "https://www.orcid.org/",
                  "http://www.orcid.org/",
                  "orcid.org/",
                  "orcid:");
          case OPENALEX_ID ->
              List.of(
                  "https://openalex.org/",
                  "http://openalex.org/",
                  "https://api.openalex.org/authors/",
                  "https://api.openalex.org/works/",
                  "openalex.org/");
          default -> List.of();
        };
    for (String prefix : prefixes) {
      if (value.startsWith(prefix)) {
        value = value.substring(prefix.length());
        break;
      }
    }
    return value.isEmpty() ? null : value;
  }

  private Optional<StableIdentifier> resolveStableIdentifier(
      AchievementType kind, NormalizedRecord record) {
    return switch (kind) {
      case PAPER -> {
        String doiNorm = normalizeIdentifier(IdentifierKind.DOI, record.doi());
        yield doiNorm == null
            ? Optional.empty()
            : Optional.of(new StableIdentifier(IdentifierKind.DOI, doiNorm));
      }
      case PATENT -> {
        String norm =
            normalizeIdentifier(IdentifierKind.PATENT_APPLICATION_NO, record.applicationNo());
        yield norm == null
            ? Optional.empty()
            : Optional.of(new StableIdentifier(IdentifierKind.PATENT_APPLICATION_NO, norm));
      }
      case PROJECT -> {
        String norm = normalizeIdentifier(IdentifierKind.PROJECT_NO, record.projectNo());
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
