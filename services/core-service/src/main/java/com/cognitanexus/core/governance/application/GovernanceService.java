package com.cognitanexus.core.governance.application;

import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ScholarRepository;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.Scholar;
import com.cognitanexus.core.governance.domain.DedupCandidate;
import com.cognitanexus.core.governance.domain.GovernanceAnomaly;
import com.cognitanexus.core.governance.domain.MergeLedgerEntry;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.event.EventTypes;
import com.cognitanexus.core.platform.event.OutboxEvent;
import com.cognitanexus.core.platform.event.OutboxStore;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.platform.util.Identifiers;
import com.cognitanexus.core.platform.util.PageRequest;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 治理业务：候选登记、裁决与合并。
 *
 * <p>依据：架构 V0.2 §4.3、§5.4、AC-IMPL-FR09-02/03、AC-IMPL-FR15-01/02。
 *
 * <p>三条不可越过的边界：
 *
 * <ol>
 *   <li>相似度只用于<b>产生候选</b>，从不用于自动合并；
 *   <li>合并必须留下台账（旧 ID 重定向、来源归属与关系前后快照），使旧链接跳到正确对象；
 *   <li>合并后必须发布变更事件，否则查询视图会长期保留被合并实体的旧投影。
 * </ol>
 */
@Service
public class GovernanceService {

  private static final Logger log = LoggerFactory.getLogger(GovernanceService.class);

  /** 单次合并允许迁移的关系上限，防止误操作把超大实体一次吞掉。 */
  private static final int MAX_MOVE_ROWS = 20_000;

  private final GovernanceRepositories.DedupCandidateRepository candidates;
  private final GovernanceRepositories.MergeLedgerRepository ledger;
  private final GovernanceRepositories.AnomalyRepository anomalies;
  private final GovernanceRepositories.MergeTargetRepository mergeTargets;
  private final AchievementRepository achievements;
  private final ScholarRepository scholars;
  private final OutboxStore outbox;
  private final AuditRecorder audit;

  public GovernanceService(
      GovernanceRepositories.DedupCandidateRepository candidates,
      GovernanceRepositories.MergeLedgerRepository ledger,
      GovernanceRepositories.AnomalyRepository anomalies,
      GovernanceRepositories.MergeTargetRepository mergeTargets,
      AchievementRepository achievements,
      ScholarRepository scholars,
      OutboxStore outbox,
      AuditRecorder audit) {
    this.candidates = candidates;
    this.ledger = ledger;
    this.anomalies = anomalies;
    this.mergeTargets = mergeTargets;
    this.achievements = achievements;
    this.scholars = scholars;
    this.outbox = outbox;
    this.audit = audit;
  }

  // ---------------------------------------------------------------------------
  // 候选与异常登记
  // ---------------------------------------------------------------------------

  /**
   * 登记标题相似候选。
   *
   * <p>左右实体按规范 ID 的字典序排列后再写入，使 (A,B) 与 (B,A) 命中同一条唯一约束，
   * 不因比较方向不同而产生两条重复候选。
   */
  @Transactional
  public void reportTitleSimilarity(Achievement left, Achievement right, double similarity) {
    if (left.id().equals(right.id())) {
      return;
    }
    String first = left.id().compareTo(right.id()) <= 0 ? left.id() : right.id();
    String second = left.id().equals(first) ? right.id() : left.id();
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("reason", "TITLE_SIMILARITY");
    evidence.put("similarity", round(similarity));
    evidence.put("leftTitle", left.title());
    evidence.put("rightTitle", right.title());
    evidence.put("leftYear", left.publicationYear().year());
    evidence.put("rightYear", right.publicationYear().year());
    evidence.put("note", "仅标题相似不构成合并依据，须比对年份、参与者与类型");

    boolean inserted =
        candidates.insertIfAbsent(
            new DedupCandidate(
                Identifiers.next("ddc"),
                left.type().name(),
                first,
                second,
                evidence,
                DedupCandidate.Status.OPEN,
                Instant.now(),
                null,
                null,
                null));
    if (inserted) {
      log.info("登记去重候选 left={} right={} similarity={}", first, second, round(similarity));
    }
  }

  /** 登记稳定标识冲突：同一标识已被其他规范实体占用。 */
  @Transactional
  public void reportIdentifierConflict(
      String kind, String normalizedValue, String occupiedByEntityId, String attemptedEntityId) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("identifierKind", kind);
    detail.put("normalizedValue", normalizedValue);
    detail.put("occupiedBy", occupiedByEntityId);
    detail.put("attempted", attemptedEntityId);
    recordAnomaly(GovernanceAnomaly.KIND_IDENTIFIER_CONFLICT, null, attemptedEntityId, detail);
  }

  @Transactional
  public void recordAnomaly(
      String kind, String sourceRecordId, String entityId, Map<String, Object> detail) {
    anomalies.record(
        new GovernanceAnomaly(
            Identifiers.next("anm"),
            kind,
            sourceRecordId,
            entityId,
            detail,
            "OPEN",
            Instant.now(),
            null));
  }

  @Transactional(readOnly = true)
  public List<DedupCandidate> listCandidates(PageRequest page) {
    return candidates.listOpenCandidates(page.size(), page.offset());
  }

  @Transactional(readOnly = true)
  public long countOpenCandidates() {
    return candidates.countOpenCandidates();
  }

  @Transactional(readOnly = true)
  public long countOpenCandidatesFor(String achievementId) {
    return candidates.countOpenFor(achievementId);
  }

  @Transactional(readOnly = true)
  public long countOpenAnomalies() {
    return anomalies.countOpenAnomalies();
  }

  @Transactional(readOnly = true)
  public List<GovernanceAnomaly> listAnomalies(PageRequest page) {
    return anomalies.listOpenAnomalies(page.size(), page.offset());
  }

  @Transactional
  public void resolveAnomaly(String anomalyId, ActorContext actor) {
    if (!anomalies.resolve(anomalyId)) {
      throw BusinessException.versionConflict("异常不存在或已处理，请刷新后重试");
    }
    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "GOVERNANCE_ANOMALY_RESOLVED",
            "GOVERNANCE_ANOMALY",
            anomalyId,
            null,
            null,
            "标记已处理"));
  }

  // ---------------------------------------------------------------------------
  // 裁决
  // ---------------------------------------------------------------------------

  /**
   * 裁决一个去重候选。
   *
   * @param action MERGE 表示合并；REJECT 表示判定为不同实体
   * @param survivorId MERGE 时指定的幸存实体；必须在候选两端之内
   */
  @Transactional
  public void resolveCandidate(
      String candidateId, String action, String survivorId, String reason, ActorContext actor) {
    DedupCandidate candidate =
        candidates.findById(candidateId).orElseThrow(() -> BusinessException.notFound("候选不存在"));
    if (candidate.status() != DedupCandidate.Status.OPEN) {
      throw BusinessException.versionConflict("该候选已裁决，请刷新后重试");
    }
    if ("REJECT".equals(action)) {
      if (!candidates.resolve(
          candidateId, "REJECTED", actor.accountIdValue(), requireReason(reason))) {
        throw BusinessException.versionConflict("候选状态已被其他操作改变");
      }
      audit.record(
          AuditRecorder.AuditEntry.of(
              actor,
              "DEDUP_CANDIDATE_REJECTED",
              "DEDUP_CANDIDATE",
              candidateId,
              null,
              null,
              reason));
      return;
    }
    if (!"MERGE".equals(action)) {
      throw BusinessException.invalidArgument("action 只支持 MERGE 或 REJECT");
    }
    String survivor = resolveSurvivor(candidate, survivorId);
    String merged =
        survivor.equals(candidate.leftAchievementId())
            ? candidate.rightAchievementId()
            : candidate.leftAchievementId();
    String resolved = requireReason(reason);

    mergeAchievements(survivor, merged, resolved, actor);

    if (!candidates.resolve(candidateId, "MERGED", actor.accountIdValue(), resolved)) {
      throw BusinessException.versionConflict("候选状态已被其他操作改变");
    }
  }

  private String resolveSurvivor(DedupCandidate candidate, String requested) {
    if (requested == null || requested.isBlank()) {
      throw BusinessException.invalidArgument("合并必须显式指定幸存实体");
    }
    if (requested.equals(candidate.leftAchievementId())
        || requested.equals(candidate.rightAchievementId())) {
      return requested;
    }
    throw BusinessException.invalidArgument("幸存实体必须是候选两端之一");
  }

  // ---------------------------------------------------------------------------
  // 合并
  // ---------------------------------------------------------------------------

  /**
   * 合并成果。
   *
   * <p>整个过程在一个本地事务内完成：快照 → 迁移关系与归属 → 建立重定向 → 台账 → 审计 → 事件。
   * 事务外只做跨服务投递。
   */
  @Transactional
  public void mergeAchievements(
      String survivorId, String mergedId, String reason, ActorContext actor) {
    Achievement survivor =
        achievements.findById(survivorId).orElseThrow(() -> BusinessException.notFound("幸存成果不存在"));
    Achievement merged =
        achievements.findById(mergedId).orElseThrow(() -> BusinessException.notFound("被合并成果不存在"));
    if (survivor.merged()) {
      throw BusinessException.businessRule("幸存成果本身已被合并，请先解析到最终实体");
    }
    if (merged.merged()) {
      throw BusinessException.businessRule("该成果已被合并过，不能重复合并");
    }

    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("mergedTitle", merged.title());
    snapshot.put("mergedType", merged.type().name());
    snapshot.put("mergedDataNature", merged.dataNature().name());
    snapshot.put("mergedVersion", merged.version());
    snapshot.put("contributionsBefore", mergeTargets.snapshotContributions(mergedId));
    snapshot.put("sourcesBefore", mergeTargets.snapshotSources(mergedId));

    GovernanceRepositories.MergeTargetRepository.ContributionMove move =
        mergeTargets.moveContributions(mergedId, survivorId);
    if (move.removed() > MAX_MOVE_ROWS) {
      throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "参与关系迁移数量超过单次上限，请拆分处理");
    }
    int movedSources = mergeTargets.moveSourceLinks(mergedId, survivorId);
    int movedTopics = mergeTargets.moveTopicLinks(mergedId, survivorId);

    if (!mergeTargets.markAchievementMerged(mergedId, survivorId)) {
      throw BusinessException.versionConflict("被合并成果状态已改变，请刷新后重试");
    }

    snapshot.put("contributionsInserted", move.inserted());
    snapshot.put("contributionsRemoved", move.removed());
    snapshot.put("sourcesMoved", movedSources);
    snapshot.put("topicsMoved", movedTopics);

    ledger.append(
        new MergeLedgerEntry(
            Identifiers.next("mgl"),
            MergeLedgerEntry.Operation.MERGE,
            survivorId,
            mergedId,
            MergeLedgerEntry.EntityType.ACHIEVEMENT.name(),
            snapshot,
            reason,
            actor == null ? RoleNames.SYSTEM : actor.accountIdValue(),
            Instant.now()));

    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "ACHIEVEMENT_MERGED",
            "ACHIEVEMENT",
            mergedId,
            Map.of("id", mergedId, "title", merged.title()),
            Map.of("mergedInto", survivorId, "title", survivor.title()),
            reason));

    outbox.append(
        OutboxEvent.builder(EventTypes.ACHIEVEMENT_MERGED, EventTypes.AGG_ACHIEVEMENT, survivorId)
            .aggregateVersion(survivor.version() + 1)
            .put("survivorId", survivorId)
            .put("survivorTitle", survivor.title())
            .put("mergedId", mergedId)
            .put("changeKind", "MERGE"));
    log.info("成果合并完成 survivor={} merged={}", survivorId, mergedId);
  }

  /**
   * 合并学者。
   *
   * <p>同名不是同人证据（BR-IMP-03）：本方法只执行已被人工裁决为同一人的合并，
   * 不提供任何基于姓名相似度的自动调用入口。
   */
  @Transactional
  public void mergeScholars(String survivorId, String mergedId, String reason, ActorContext actor) {
    Scholar survivor =
        scholars.findById(survivorId).orElseThrow(() -> BusinessException.notFound("幸存学者不存在"));
    Scholar merged =
        scholars.findById(mergedId).orElseThrow(() -> BusinessException.notFound("被合并学者不存在"));
    if (survivor.merged() || merged.merged()) {
      throw BusinessException.businessRule("学者已被合并，不能重复合并");
    }
    String resolved = requireReason(reason);

    Optional<String> survivorAuthority = mergeTargets.findActiveAuthorityId(survivorId);
    Optional<String> mergedAuthority = mergeTargets.findActiveAuthorityId(mergedId);

    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("mergedName", merged.displayName());
    snapshot.put("mergedClaimedStatus", merged.claimedStatus().name());
    snapshot.put("mergedVersion", merged.version());
    snapshot.put("contributionsBefore", mergeTargets.snapshotScholarContributions(mergedId));
    snapshot.put("survivorHasAuthority", survivorAuthority.isPresent());
    snapshot.put("mergedHasAuthority", mergedAuthority.isPresent());

    GovernanceRepositories.MergeTargetRepository.ContributionMove move =
        mergeTargets.moveScholarContributions(mergedId, survivorId);
    int movedVariants = mergeTargets.moveNameVariants(mergedId, survivorId);

    int authorityOutcome;
    if (mergedAuthority.isEmpty()) {
      authorityOutcome = 0;
    } else if (survivorAuthority.isEmpty()) {
      authorityOutcome = mergeTargets.moveActiveAuthority(mergedId, survivorId);
      snapshot.put("authorityTransferred", authorityOutcome == 1);
    } else {
      // 两个门户各有管理者：自动转移会改变访问控制结论，撤销被合并门户的授权并留待人工重新授予
      authorityOutcome = mergeTargets.revokeAuthorityOfScholar(mergedId, "学者合并：双方均有管理者，需人工重新授予");
      snapshot.put("authorityRevokedOnMerge", authorityOutcome == 1);
    }

    if (!mergeTargets.markScholarMerged(mergedId, survivorId)) {
      throw BusinessException.versionConflict("被合并学者状态已改变，请刷新后重试");
    }
    snapshot.put("contributionsInserted", move.inserted());
    snapshot.put("contributionsRemoved", move.removed());
    snapshot.put("nameVariantsMoved", movedVariants);

    ledger.append(
        new MergeLedgerEntry(
            Identifiers.next("mgl"),
            MergeLedgerEntry.Operation.MERGE,
            survivorId,
            mergedId,
            MergeLedgerEntry.EntityType.SCHOLAR.name(),
            snapshot,
            resolved,
            actor == null ? RoleNames.SYSTEM : actor.accountIdValue(),
            Instant.now()));

    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "SCHOLAR_MERGED",
            "SCHOLAR",
            mergedId,
            Map.of("id", mergedId, "displayName", merged.displayName()),
            Map.of("mergedInto", survivorId, "displayName", survivor.displayName()),
            resolved));

    outbox.append(
        OutboxEvent.builder(EventTypes.SCHOLAR_MERGED, EventTypes.AGG_SCHOLAR, survivorId)
            .aggregateVersion(survivor.version() + 1)
            .put("survivorId", survivorId)
            .put("survivorName", survivor.displayName())
            .put("mergedId", mergedId)
            .put("changeKind", "MERGE"));
    log.info("学者合并完成 survivor={} merged={} 授权处置={}", survivorId, mergedId, authorityOutcome);
  }

  @Transactional(readOnly = true)
  public List<MergeLedgerEntry> ledgerOf(String entityId, int limit) {
    return ledger.listByEntity(entityId, limit);
  }

  private String requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw BusinessException.invalidArgument("必须说明裁决理由");
    }
    return reason.length() > 500 ? reason.substring(0, 500) : reason;
  }

  private double round(double value) {
    return Math.round(value * 1000d) / 1000d;
  }
}
