package com.cognitanexus.core.governance.application;

import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ScholarRepository;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.Scholar;
import com.cognitanexus.core.governance.application.GovernanceRepositories.ScoredCandidate;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
  private final GovernanceRepositories.AchievementStateRepository achievementStates;
  private final AchievementRepository achievements;
  private final ScholarRepository scholars;
  private final OutboxStore outbox;
  private final AuditRecorder audit;

  public GovernanceService(
      GovernanceRepositories.DedupCandidateRepository candidates,
      GovernanceRepositories.MergeLedgerRepository ledger,
      GovernanceRepositories.AnomalyRepository anomalies,
      GovernanceRepositories.MergeTargetRepository mergeTargets,
      GovernanceRepositories.AchievementStateRepository achievementStates,
      AchievementRepository achievements,
      ScholarRepository scholars,
      OutboxStore outbox,
      AuditRecorder audit) {
    this.candidates = candidates;
    this.ledger = ledger;
    this.anomalies = anomalies;
    this.mergeTargets = mergeTargets;
    this.achievementStates = achievementStates;
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
    // 左右实体按 ID 字典序落库，证据里的标题与年份必须跟着同一次排序走。若按调用方传入的方向
    // 写标题、却按字典序写 ID，候选 A 的标题会挂在候选 B 的标识上，界面读起来是错位的对照。
    Achievement firstEntity = left.id().compareTo(right.id()) <= 0 ? left : right;
    Achievement secondEntity = firstEntity == left ? right : left;
    String first = firstEntity.id();
    String second = secondEntity.id();
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("reason", "TITLE_SIMILARITY");
    evidence.put("similarity", round(similarity));
    evidence.put("leftTitle", firstEntity.title());
    evidence.put("rightTitle", secondEntity.title());
    evidence.put("leftYear", firstEntity.publicationYear().year());
    evidence.put("rightYear", secondEntity.publicationYear().year());
    // 可见性要进证据：合并一条已公开与一条受限实体时，处置人必须先看清哪一边对外可见，
    // 否则「保留哪一边」等于在不知道后果的情况下做选择。
    evidence.put("leftVisibility", firstEntity.visibility().name());
    evidence.put("rightVisibility", secondEntity.visibility().name());
    evidence.put("leftEffectiveStatus", firstEntity.effectiveStatus().name());
    evidence.put("rightEffectiveStatus", secondEntity.effectiveStatus().name());
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

  // ---------------------------------------------------------------------------
  // 治理工作台：让「先处理哪一批」成为可判断的问题
  // ---------------------------------------------------------------------------

  /**
   * 候选相似度分档阈值。
   *
   * <p>属 SPEC 中待评审的业务参数，这里是可用初值而非已确认结论。分档的用途是排序待办，
   * 不是自动裁决——任何一档都必须人工确认后才能改变候选状态。
   */
  private static final double SIMILARITY_HIGH = 0.90d;

  private static final double SIMILARITY_MID = 0.70d;

  /** 单次批量裁决的条数上限。上限的作用是让「一次点击」的责任范围可见、可复核。 */
  private static final int MAX_BATCH_RESOLVE = 200;

  /**
   * 工作台候选列表。
   *
   * <p>与 {@link #listCandidates} 的区别：那个给出「最新登记」（看采集节奏），这个按可信度
   * 组织（处理积压）。两者服务的问题不同，因此保留两个入口而不是合并成一个带很多开关的方法。
   */
  @Transactional(readOnly = true)
  public List<ScoredCandidate> listCandidatesForWorkbench(
      CandidateFilter filter, PageRequest page) {
    return candidates.listOpenCandidatesScored(
        filter.minSimilarity(), filter.sortBy(), page.size(), page.offset());
  }

  @Transactional(readOnly = true)
  public long countCandidatesForWorkbench(CandidateFilter filter) {
    return candidates.countOpenCandidatesByFilter(filter.minSimilarity());
  }

  /**
   * 候选两端成果的实时状态，按标识取值。
   *
   * <p>工作台在渲染一页候选时调用它。为什么不让列表 SQL 直接 join 出这些列：候选列表的排序与
   * 筛选只依赖候选表与 evidence，把它变成三表 join 会让分档统计与列表查询走上不同的执行计划，
   * 而这两处的「待办数」必须一致。分成一次批量取状态，读路径各自简单，也只在渲染时需要。
   */
  @Transactional(readOnly = true)
  public Map<String, GovernanceRepositories.AchievementStateRepository.AchievementState> statesOf(
      java.util.Collection<String> ids) {
    Map<String, GovernanceRepositories.AchievementStateRepository.AchievementState> byId =
        new LinkedHashMap<>();
    for (GovernanceRepositories.AchievementStateRepository.AchievementState state :
        achievementStates.statesOf(ids)) {
      byId.put(state.id(), state);
    }
    return byId;
  }

  /** 待办概览：分档计数让治理人员知道积压由哪一类构成。 */
  @Transactional(readOnly = true)
  public WorkbenchSummary workbenchSummary() {
    GovernanceRepositories.CandidateBuckets buckets =
        candidates.countOpenCandidatesByBucket(SIMILARITY_HIGH, SIMILARITY_MID);
    return new WorkbenchSummary(
        buckets.high(),
        buckets.mid(),
        buckets.low(),
        buckets.total(),
        SIMILARITY_HIGH,
        SIMILARITY_MID);
  }

  /**
   * 批量裁决候选为「不同实体」（驳回）。
   *
   * <p><b>只提供批量驳回，不提供批量合并。</b>两个方向的代价不对称：驳回错了只是留下两个各自
   * 独立的实体，之后可以再合并；合并错了要把参与关系、来源归属与门户授权从幸存实体上拆回来，
   * 而其中门户授权一旦被撤销就无法自动恢复。批量合并会把 N 个独立判定压缩成一次点击，而每次
   * 合并都要求显式指定幸存实体——那正是「相似标题不得直接合并」这条红线的落点。
   *
   * <p>逐条独立裁决：不存在或已被他人裁决的候选计入 skipped 并逐条给出原因，不影响同一批里
   * 其它候选。数据库异常照常冒泡并回滚整批——那不是「某一条不合法」，而是环境故障，
   * 此时部分成功比整体失败更难收拾。
   */
  @Transactional
  public BatchResolveOutcome batchReject(
      List<String> candidateIds, String reason, ActorContext actor) {
    if (candidateIds == null || candidateIds.isEmpty()) {
      throw BusinessException.invalidArgument("必须给出待裁决的候选标识");
    }
    if (candidateIds.size() > MAX_BATCH_RESOLVE) {
      throw BusinessException.invalidArgument("单次批量裁决上限为 " + MAX_BATCH_RESOLVE + " 条，请分批处理");
    }
    String resolved = requireReason(reason);
    String operator = actor == null ? RoleNames.SYSTEM : actor.accountIdValue();

    List<String> skipped = new ArrayList<>();
    List<String> affected = new ArrayList<>();
    int resolvedCount = 0;
    for (String candidateId : new LinkedHashSet<>(candidateIds)) {
      Optional<DedupCandidate> found = candidates.findById(candidateId);
      if (found.isEmpty()) {
        skipped.add(candidateId + "：候选不存在");
        continue;
      }
      if (found.get().status() != DedupCandidate.Status.OPEN) {
        skipped.add(candidateId + "：已裁决为 " + found.get().status());
        continue;
      }
      if (!candidates.resolve(candidateId, "REJECTED", operator, resolved)) {
        skipped.add(candidateId + "：状态已被其他操作改变");
        continue;
      }
      affected.add(found.get().leftAchievementId());
      affected.add(found.get().rightAchievementId());
      audit.record(
          AuditRecorder.AuditEntry.of(
              actor,
              "DEDUP_CANDIDATE_REJECTED",
              "DEDUP_CANDIDATE",
              candidateId,
              Map.of(
                  "leftAchievementId", found.get().leftAchievementId(),
                  "rightAchievementId", found.get().rightAchievementId()),
              Map.of("status", "REJECTED"),
              resolved));
      resolvedCount++;
    }
    return new BatchResolveOutcome(candidateIds.size(), resolvedCount, skipped, affected);
  }

  /** 候选筛选条件。排序键走白名单：未知取值明确报错，不静默回落到默认排序。 */
  public record CandidateFilter(Double minSimilarity, String sortBy) {

    public static final String SORT_SIMILARITY_DESC = "similarity_desc";

    private static final List<String> ALLOWED_SORTS =
        List.of(SORT_SIMILARITY_DESC, "created_asc", "created_desc", "type_asc");

    public static List<String> allowedSorts() {
      return ALLOWED_SORTS;
    }

    public static CandidateFilter of(Double minSimilarity, String sortBy) {
      if (minSimilarity != null && (minSimilarity < 0d || minSimilarity > 1d)) {
        throw BusinessException.invalidArgument("相似度下限须在 0 到 1 之间");
      }
      String normalized = sortBy == null || sortBy.isBlank() ? SORT_SIMILARITY_DESC : sortBy;
      if (!ALLOWED_SORTS.contains(normalized)) {
        throw BusinessException.invalidArgument("排序键必须是以下之一：" + String.join("、", ALLOWED_SORTS));
      }
      return new CandidateFilter(minSimilarity, normalized);
    }
  }

  /** 待办概览。 */
  public record WorkbenchSummary(
      long high, long mid, long low, long total, double highLow, double midLow) {}

  /**
   * 批量裁决结果。
   *
   * @param affectedAchievementIds 本次裁决牵涉到的成果标识（去重），供调用方做发布重评估。
   *     候选被裁决只解除「待治理」的一部分成因，成果能否发布要由重评估重新判定。
   */
  public record BatchResolveOutcome(
      int requested, int resolved, List<String> skipped, List<String> affectedAchievementIds) {

    public BatchResolveOutcome {
      skipped = List.copyOf(skipped);
      affectedAchievementIds = List.copyOf(affectedAchievementIds);
    }
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
  public List<String> resolveCandidate(
      String candidateId, String action, String survivorId, String reason, ActorContext actor) {
    DedupCandidate candidate =
        candidates.findById(candidateId).orElseThrow(() -> BusinessException.notFound("候选不存在"));
    if (candidate.status() != DedupCandidate.Status.OPEN) {
      throw BusinessException.versionConflict("该候选已裁决，请刷新后重试");
    }
    // 两端都要交回给调用方做发布重评估：驳回时双方仍在原状态，合并时被合并方会因 merged 而
    // 被重评估跳过，由跳过条件兜住，不在这里分支判断——那种判断会让「谁该重评估」多出一份口径。
    List<String> affected = List.of(candidate.leftAchievementId(), candidate.rightAchievementId());
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
      return affected;
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
    return affected;
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

  /**
   * 候选改判：把问题转到两端重定向后的最终实体上。
   *
   * <p><b>为什么必须有这一步。</b>成果合并之后，那两个标识各自还牵涉一批「对手方是第三方」的候选。
   * 这些候选问的是「这两条是不是同一条」，而其中一端已经被并走，于是这个问题既不能再合并（服务端
   * 会以「已被合并过」拒绝，重定向不覆盖首次结论），也不该被驳回——驳回写进库里的结论是「判定为
   * 不同实体」，可实际上这两端从来没有被比较过。原先后台只提供这两条路，治理人员于是只能二选一：
   * 要么留下一条永远做不掉的死条目，要么写一条不实的结论；预览提示里那句「需要人工判断是改判为与
   * 幸存成果相关，还是直接驳回」，指的正是这件当时做不了的事。
   *
   * <p><b>怎么改判。</b>沿合并链把两端各自解析到最终实体，然后分三种情况：
   *
   * <ol>
   *   <li>两端解析到<b>同一个</b>实体：候选提出的问题已经被合并回答了（它们确实是同一条），
   *       直接裁决为 {@code MERGED}，不新建候选；
   *   <li>两端解析到<b>不同</b>实体，且这一对<b>已有</b>候选：把问题指过去，复用既有候选，
   *       不重复登记同一条问题；
   *   <li>两端解析到<b>不同</b>实体，且这一对<b>没有</b>候选：登记一条新候选承接，证据沿用原候选
   *       并追加改判溯源，使「这条候选是怎么来的」可以从证据里读回来。
   * </ol>
   *
   * <p>无论哪种情况，原候选都置为 {@code REBASED} 并写明结论；<b>不删除</b>——候选是治理过程的
   * 记录，「这条问题后来去哪儿了」必须查得到。
   *
   * <p>合并链解析失败（链长超过上限，说明历史数据异常）时直接报错，不落任何一行：那种情况下算出来
   * 的「最终实体」是不可信的，据此登记候选等于把一条错误的问题放进待办。
   */
  @Transactional
  public RebaseOutcome rebaseCandidate(String candidateId, String reason, ActorContext actor) {
    DedupCandidate candidate =
        candidates.findById(candidateId).orElseThrow(() -> BusinessException.notFound("候选不存在"));
    if (candidate.status() != DedupCandidate.Status.OPEN) {
      throw BusinessException.versionConflict("该候选已裁决，请刷新后重试");
    }
    String resolvedReason = requireReason(reason);
    String operator = actor == null ? RoleNames.SYSTEM : actor.accountIdValue();

    String finalLeft = finalEntityOf(candidate.leftAchievementId());
    String finalRight = finalEntityOf(candidate.rightAchievementId());

    // 两端指向同一条实体：这条候选问的「是不是同一条」已经被合并回答了。
    if (finalLeft.equals(finalRight)) {
      if (!candidates.resolve(candidateId, "MERGED", operator, "改判后两端指向同一实体：" + resolvedReason)) {
        throw BusinessException.versionConflict("候选状态已被其他操作改变");
      }
      audit.record(
          AuditRecorder.AuditEntry.of(
              actor,
              "DEDUP_CANDIDATE_REBASED",
              "DEDUP_CANDIDATE",
              candidateId,
              Map.of(
                  "left", candidate.leftAchievementId(),
                  "right", candidate.rightAchievementId(),
                  "status", DedupCandidate.Status.OPEN.name()),
              Map.of(
                  "status",
                  DedupCandidate.Status.MERGED.name(),
                  "finalEntity",
                  finalLeft,
                  "decision",
                  "两端归并到同一实体，这条候选的问题已由合并回答"),
              resolvedReason));
      return new RebaseOutcome(
          candidateId,
          DedupCandidate.Status.MERGED.name(),
          null,
          false,
          finalLeft,
          finalRight,
          affectedOf(candidate, finalLeft, finalRight));
    }

    // 两端不同：问题转移到 (最终左, 最终右)。这一对问过就不再问第二遍。
    Optional<DedupCandidate> existing = candidates.findBetween(finalLeft, finalRight);
    RebaseTarget target =
        existing
            .map(row -> new RebaseTarget(row.id(), true))
            .orElseGet(
                () -> createRebasedCandidate(candidate, finalLeft, finalRight, resolvedReason));

    if (!candidates.resolveRebased(candidateId, operator, resolvedReason, target.id())) {
      throw BusinessException.versionConflict("候选状态已被其他操作改变");
    }
    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "DEDUP_CANDIDATE_REBASED",
            "DEDUP_CANDIDATE",
            candidateId,
            Map.of(
                "left", candidate.leftAchievementId(),
                "right", candidate.rightAchievementId(),
                "status", DedupCandidate.Status.OPEN.name()),
            Map.of(
                "status",
                DedupCandidate.Status.REBASED.name(),
                "rebasedToId",
                target.id(),
                "finalLeft",
                finalLeft,
                "finalRight",
                finalRight),
            resolvedReason));
    log.info(
        "候选改判 candidate={} -> {}（最终实体 {} / {}）", candidateId, target.id(), finalLeft, finalRight);
    return new RebaseOutcome(
        candidateId,
        DedupCandidate.Status.REBASED.name(),
        target.id(),
        target.existed(),
        finalLeft,
        finalRight,
        affectedOf(candidate, finalLeft, finalRight));
  }

  /**
   * 登记承接改判的新候选。
   *
   * <p>左右按标识字典序落库，与 {@link #reportTitleSimilarity} 同一口径：候选在 (左,右) 上有唯一
   * 约束，只有左右顺序规范化之后，(A,B) 与 (B,A) 才会命中同一条约束。若并发下已被别人抢先登记，
   * {@code insertIfAbsent} 返回 false，此时必须把既有那条读回来——直接返回刚生成的 id 会让原候选
   * 指向一个并不存在的候选。
   */
  private RebaseTarget createRebasedCandidate(
      DedupCandidate origin, String finalLeft, String finalRight, String reason) {
    String first = finalLeft.compareTo(finalRight) <= 0 ? finalLeft : finalRight;
    String second = first.equals(finalLeft) ? finalRight : finalLeft;
    Map<String, Object> evidence = new LinkedHashMap<>(origin.evidence());
    Map<String, Object> rebase = new LinkedHashMap<>();
    rebase.put("fromCandidateId", origin.id());
    rebase.put("fromLeft", origin.leftAchievementId());
    rebase.put("fromRight", origin.rightAchievementId());
    rebase.put("reason", reason);
    rebase.put("at", Instant.now().toString());
    rebase.put("note", "本条候选由改判产生：原候选的端点已被合并，问题转由最终实体作答");
    evidence.put("rebase", rebase);
    String id = Identifiers.next("ddc");
    boolean inserted =
        candidates.insertIfAbsent(
            new DedupCandidate(
                id,
                origin.achievementType(),
                first,
                second,
                evidence,
                DedupCandidate.Status.OPEN,
                Instant.now(),
                null,
                null,
                null,
                null));
    if (inserted) {
      return new RebaseTarget(id, false);
    }
    return candidates
        .findBetween(finalLeft, finalRight)
        .map(row -> new RebaseTarget(row.id(), true))
        .orElseThrow(() -> BusinessException.versionConflict("承接候选登记冲突，请刷新后重试"));
  }

  /** 承接候选：标识 + 它是不是本来就存在（决定结果里要不要说「已复用既有候选」）。 */
  private record RebaseTarget(String id, boolean existed) {}

  /** 沿合并链解析到最终实体；链长超限时明确报错，不回落成「就用原来那个」。 */
  private String finalEntityOf(String achievementId) {
    return achievements
        .resolveSurvivorId(achievementId)
        .orElseThrow(() -> BusinessException.businessRule("合并链超过解析上限，疑似历史数据异常，请先核查台账"));
  }

  /**
   * 改判影响到的成果：原两端 + 最终两端。
   *
   * <p>两组都要交回给调用方重评估。原两端里没被并走的那端会因候选关闭而少一个「待治理」成因；
   * 最终两端则可能因为新候选的出现而多一个成因（重评估的跳过条件会挡住后者，方向是 fail-closed）。
   */
  private static List<String> affectedOf(
      DedupCandidate candidate, String finalLeft, String finalRight) {
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    ids.add(candidate.leftAchievementId());
    ids.add(candidate.rightAchievementId());
    ids.add(finalLeft);
    ids.add(finalRight);
    return List.copyOf(ids);
  }

  /**
   * 改判结果。
   *
   * @param status 原候选的最终状态：{@code MERGED}（两端归并到同一实体）或 {@code REBASED}
   * @param rebasedToId 承接候选；{@code status = MERGED} 时为 null
   * @param targetExisted 承接候选是既有候选而不是本次新建的
   */
  public record RebaseOutcome(
      String candidateId,
      String status,
      String rebasedToId,
      boolean targetExisted,
      String finalLeftId,
      String finalRightId,
      List<String> affectedAchievementIds) {

    public RebaseOutcome {
      affectedAchievementIds = List.copyOf(affectedAchievementIds);
    }
  }

  // ---------------------------------------------------------------------------
  // 合并
  // ---------------------------------------------------------------------------

  /**
   * 合并影响预览：只读，不写入任何数据。
   *
   * <p>为什么需要它：合并的后果大部分是<b>看不见</b>的——被合并实体的元数据被丢弃、同一学者的两条
   * 参与关系被并成一条、来源归属因重复而不再挂接。这些只在台账快照里留痕，也就是只有合并<i>之后</i>
   * 才读得到。而「相似标题不得直接合并」意味着合并永远是一次人工决定，人工决定的依据只能是
   * 「合并后会发生什么」。没有预览时，治理人员只能凭标题相似度下手，风险与收益都不透明。
   *
   * <p>预览与执行共用同一批读取语义（本方法里的判断就是执行路径的判断），所以预览说会迁 3 条，
   * 执行时就迁 3 条；不一致的预览比没有预览更糟。
   */
  @Transactional(readOnly = true)
  public Map<String, Object> previewMerge(String survivorId, String mergedId) {
    if (survivorId == null || survivorId.isBlank() || mergedId == null || mergedId.isBlank()) {
      throw BusinessException.invalidArgument("必须同时指定幸存成果与被合并成果");
    }
    if (survivorId.equals(mergedId)) {
      throw BusinessException.invalidArgument("幸存成果与被合并成果不能是同一条");
    }
    Achievement survivor =
        achievements.findById(survivorId).orElseThrow(() -> BusinessException.notFound("幸存成果不存在"));
    Achievement merged =
        achievements.findById(mergedId).orElseThrow(() -> BusinessException.notFound("被合并成果不存在"));

    List<String> blockers = new ArrayList<>();
    if (survivor.merged()) {
      blockers.add("幸存成果本身已被合并，需先解析到最终实体再操作");
    }
    if (merged.merged()) {
      blockers.add("被合并成果已被合并过：重定向不覆盖首次结论，执行会被拒绝");
    }

    // 参与关系：同一学者在两侧都有关系时，执行路径是「补插去重 → 删除旧行」，即保留幸存侧既有
    // 那条。因此被合并侧的状态不会写过去——它可能更强（例如 SELF_CONFIRMED 对 AUTO）。
    List<GovernanceRepositories.MergeTargetRepository.PreviewContribution> survivorContributions =
        mergeTargets.previewContributions(survivorId);
    List<GovernanceRepositories.MergeTargetRepository.PreviewContribution> mergedContributions =
        mergeTargets.previewContributions(mergedId);
    Map<String, GovernanceRepositories.MergeTargetRepository.PreviewContribution>
        survivorByScholar = new LinkedHashMap<>();
    for (GovernanceRepositories.MergeTargetRepository.PreviewContribution row :
        survivorContributions) {
      survivorByScholar.putIfAbsent(row.scholarId(), row);
    }
    List<Map<String, Object>> transferContributions = new ArrayList<>();
    List<Map<String, Object>> duplicateContributions = new ArrayList<>();
    for (GovernanceRepositories.MergeTargetRepository.PreviewContribution row :
        mergedContributions) {
      GovernanceRepositories.MergeTargetRepository.PreviewContribution existing =
          survivorByScholar.get(row.scholarId());
      if (existing == null) {
        transferContributions.add(contributionView(row));
        continue;
      }
      boolean stateDiffers = !existing.relationState().equals(row.relationState());
      Map<String, Object> duplicate = contributionView(row);
      duplicate.put("survivorRelationState", existing.relationState());
      duplicate.put("stateDiffers", stateDiffers);
      duplicate.put(
          "effect",
          stateDiffers
              ? "合并后保留幸存侧状态 " + existing.relationState() + "，被合并侧状态不会写入（关系状态独立存储，不得互相推导）"
              : "两侧状态一致，合并为一条，无需额外处理");
      duplicateContributions.add(duplicate);
    }

    // 来源归属与领域标签：执行路径在幸存侧已有同一条记录时跳过，避免重复挂接。
    List<GovernanceRepositories.MergeTargetRepository.PreviewSource> survivorSources =
        mergeTargets.previewSources(survivorId);
    List<GovernanceRepositories.MergeTargetRepository.PreviewSource> mergedSources =
        mergeTargets.previewSources(mergedId);
    LinkedHashSet<String> survivorSourceRecords = new LinkedHashSet<>();
    for (GovernanceRepositories.MergeTargetRepository.PreviewSource row : survivorSources) {
      survivorSourceRecords.add(row.sourceRecordId());
    }
    List<String> transferSourceRecords = new ArrayList<>();
    List<String> duplicateSourceRecords = new ArrayList<>();
    for (GovernanceRepositories.MergeTargetRepository.PreviewSource row : mergedSources) {
      if (survivorSourceRecords.contains(row.sourceRecordId())) {
        duplicateSourceRecords.add(row.sourceRecordId());
      } else {
        transferSourceRecords.add(row.sourceRecordId());
      }
    }

    List<String> survivorTopics = mergeTargets.previewTopics(survivorId);
    List<String> mergedTopics = mergeTargets.previewTopics(mergedId);
    LinkedHashSet<String> topicSet = new LinkedHashSet<>(survivorTopics);
    List<String> transferTopics = new ArrayList<>();
    List<String> duplicateTopics = new ArrayList<>();
    for (String topic : mergedTopics) {
      if (!topicSet.add(topic)) {
        duplicateTopics.add(topic);
      } else {
        transferTopics.add(topic);
      }
    }

    List<GovernanceRepositories.MergeTargetRepository.RegisteredIdentifier> survivorIdentifiers =
        mergeTargets.previewIdentifiers("ACHIEVEMENT", survivorId);
    List<GovernanceRepositories.MergeTargetRepository.RegisteredIdentifier> mergedIdentifiers =
        mergeTargets.previewIdentifiers("ACHIEVEMENT", mergedId);

    long openBetween = candidates.countOpenBetween(survivorId, mergedId);
    long openOnMergedSide = Math.max(0, candidates.countOpenFor(mergedId) - openBetween);
    long openOnSurvivorSide = Math.max(0, candidates.countOpenFor(survivorId) - openBetween);

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("dryRun", true);
    data.put("note", "本响应只读取、不写入：预览不会改变任何实体、候选或台账。执行合并需另行提交理由，" + "理由会按原样写入合并台账与审计记录。");
    data.put("canMerge", blockers.isEmpty());
    data.put("blockers", blockers);
    data.put("survivor", achievementView(survivor));
    data.put("merged", achievementView(merged));

    Map<String, Object> contributions = new LinkedHashMap<>();
    contributions.put("survivorRows", survivorContributions.size());
    contributions.put("mergedRows", mergedContributions.size());
    contributions.put("willTransfer", transferContributions.size());
    contributions.put("willMergeIntoExisting", duplicateContributions.size());
    contributions.put("resultingRows", survivorContributions.size() + transferContributions.size());
    contributions.put("transferList", transferContributions);
    contributions.put("duplicateList", duplicateContributions);
    data.put("contributions", contributions);

    Map<String, Object> sources = new LinkedHashMap<>();
    sources.put("survivorRows", survivorSources.size());
    sources.put("mergedRows", mergedSources.size());
    sources.put("willTransfer", transferSourceRecords.size());
    sources.put("willSkipDuplicate", duplicateSourceRecords.size());
    sources.put("skippedSourceRecordIds", duplicateSourceRecords);
    sources.put("resultingRows", survivorSources.size() + transferSourceRecords.size());
    data.put("sources", sources);

    Map<String, Object> topics = new LinkedHashMap<>();
    topics.put("survivorRows", survivorTopics.size());
    topics.put("mergedRows", mergedTopics.size());
    topics.put("willTransfer", transferTopics.size());
    topics.put("willSkipDuplicate", duplicateTopics.size());
    topics.put("resultingRows", survivorTopics.size() + transferTopics.size());
    data.put("topics", topics);

    Map<String, Object> identifiers = new LinkedHashMap<>();
    identifiers.put("survivorCount", survivorIdentifiers.size());
    identifiers.put("mergedCount", mergedIdentifiers.size());
    identifiers.put("willTransfer", mergedIdentifiers.size());
    identifiers.put("resultingCount", survivorIdentifiers.size() + mergedIdentifiers.size());
    identifiers.put("survivorList", identifierViews(survivorIdentifiers));
    identifiers.put("mergedList", identifierViews(mergedIdentifiers));
    identifiers.put(
        "effect",
        "被合并实体的稳定标识改指幸存实体：唯一约束在「标识种类+作用域+规范值」上，不按实体划分，" + "因此改指不会冲突。不转移会让相同标识在再次入库时命中的是已撤回实体。");
    data.put("identifiers", identifiers);

    List<String> warnings = new ArrayList<>();
    if (!blockers.isEmpty()) {
      // 被阻断时不能把后果写成「将来会发生的事」。执行路径会拒绝这次合并，因此下面那些
      // 「会迁移 N 条」「会一并裁决 N 条」一条都不会发生——把它们原样留在响应里，读者会以为
      // 这次操作已经成立、只是暂时没做，而实际状态是「现在做不成」。先把这句话放在最前面。
      warnings.add(
          "当前状态不可执行（原因见阻断项）：下面列出的「执行后」后果、以及「会一并裁决的候选」，"
              + "都只是假定阻断原因全部解决之后才会发生的结果；本次合并不成立，也不会改动任何数据。");
    }
    if (survivorIdentifiers.isEmpty() && !mergedIdentifiers.isEmpty()) {
      warnings.add(
          "幸存成果当前没有稳定标识，合并后将从被合并成果转入 "
              + mergedIdentifiers.size()
              + " 个标识；也就是说「有稳定标识」这一自动发布条件在合并之后才成立。");
    }
    if (!duplicateContributions.isEmpty()) {
      warnings.add(
          duplicateContributions.size()
              + " 位学者在两侧都有参与关系，合并后保留幸存侧状态；被合并侧的状态不会写入。"
              + "若判断应以更强状态为准，需在合并后单独裁决该学者的关系状态。");
    }
    if (mergedContributions.size() > MAX_MOVE_ROWS) {
      warnings.add(
          "被合并成果的参与关系 "
              + mergedContributions.size()
              + " 行超过单次迁移上限 "
              + MAX_MOVE_ROWS
              + "，执行时会被拒绝；请先拆分处理。");
    }
    if (!achievementTitlesEqual(survivor, merged) || !yearsEqual(survivor, merged)) {
      warnings.add(
          "被合并成果的标题与年份不会写入幸存成果，仅在合并台账快照中留档："
              + "「"
              + merged.title()
              + "」（"
              + yearValueLabel(merged)
              + "） → 保留「"
              + survivor.title()
              + "」（"
              + yearValueLabel(survivor)
              + "）。若被合并记录的元数据更完整，请先在幸存记录上人工补齐再合并。");
    }
    if (openBetween > 0) {
      warnings.add(
          "这两条成果之间仍有 "
              + openBetween
              + " 条待治理候选：候选问的就是「这两条是不是同一条」，而本次合并已经回答了这个问题，"
              + "因此它们会随合并一并被标记为已合并，不需要再单独处理。");
    }
    if (openOnMergedSide > 0) {
      warnings.add(
          "被合并成果还牵涉其它 "
              + openOnMergedSide
              + " 条候选（对手方不是幸存成果）：这些候选本次不会被处置。被合并成果此后处于已撤回 + 重定向状态，"
              + "需要人工判断是改判为与幸存成果相关，还是直接驳回。");
    }
    data.put("warnings", warnings);

    Map<String, Object> effects = new LinkedHashMap<>();
    effects.put("mergedEffectiveStatus", "WITHDRAWN");
    effects.put("redirectTo", survivorId);
    effects.put("survivorVersion", survivor.version() + 1);
    effects.put("maxMoveRows", MAX_MOVE_ROWS);
    effects.put("exceedsMoveLimit", mergedContributions.size() > MAX_MOVE_ROWS);
    effects.put("candidatesClosedWithMerge", openBetween);
    effects.put("otherCandidatesOnMergedSide", openOnMergedSide);
    effects.put("otherCandidatesOnSurvivorSide", openOnSurvivorSide);
    effects.put("ledgerAndAudit", "写入合并台账（含合并前快照）与审计记录，动作 ACHIEVEMENT_MERGED");
    effects.put("event", "发布 ACHIEVEMENT_MERGED 事件，供查询服务重投影被合并实体与幸存实体");
    data.put("effects", effects);
    return data;
  }

  private static Map<String, Object> contributionView(
      GovernanceRepositories.MergeTargetRepository.PreviewContribution row) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("scholarId", row.scholarId());
    view.put("scholarName", row.scholarName());
    view.put("relationState", row.relationState());
    view.put("role", row.role());
    return view;
  }

  private static List<Map<String, Object>> identifierViews(
      List<GovernanceRepositories.MergeTargetRepository.RegisteredIdentifier> rows) {
    List<Map<String, Object>> views = new ArrayList<>(rows.size());
    for (GovernanceRepositories.MergeTargetRepository.RegisteredIdentifier row : rows) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("identifierKind", row.identifierKind());
      view.put("scope", row.scope());
      view.put("normalizedValue", row.normalizedValue());
      views.add(view);
    }
    return views;
  }

  private static boolean achievementTitlesEqual(Achievement left, Achievement right) {
    return left.titleNorm().equals(right.titleNorm());
  }

  /**
   * 年份是否相同。
   *
   * <p>必须用 {@code Objects.equals}：{@code year()} 是 {@code Integer}，两个 2023 装箱后是不同对象，
   * 用 {@code ==} 比较会把「同年」判成「不同年」，于是预览对每条同年的重复记录都报一次
   * 「年份会丢失」的假警告；年份未知（null）时也不会被漏判。
   */
  private static boolean yearsEqual(Achievement left, Achievement right) {
    return Objects.equals(left.publicationYear().year(), right.publicationYear().year());
  }

  /** 年份展示：未知年份如实写「未知」，不写 null，也不补一个看起来正常的年份。 */
  private static String yearValueLabel(Achievement achievement) {
    Integer year = achievement.publicationYear().year();
    return year == null ? "未知" : String.valueOf(year);
  }

  /**
   * 侧别概要。
   *
   * <p>年份必须<b>同时</b>给出布尔与标签，不能只给一个可空的 {@code publicationYear}：三个服务全局
   * 配置了 {@code default-property-inclusion: non_null}，null 字段会直接从响应里消失。也就是说
   * 「年份未知」与「服务端忘了写这个字段」在报文上长得一模一样，前端拿到 undefined 会把未知渲染成
   * 空白，读者只会看到少了一行。未知是一个必须被读到的结论，因此另给 {@code yearKnown} 与
   * {@code publicationYearLabel} 两个永不缺失的字段；{@code merged} 与 {@code mergedIntoId} 同理。
   */
  private static Map<String, Object> achievementView(Achievement achievement) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", achievement.id());
    view.put("title", achievement.title());
    view.put("type", achievement.type().name());
    view.put("publicationYear", achievement.publicationYear().year());
    view.put("yearKnown", achievement.publicationYear().known());
    view.put("publicationYearLabel", yearValueLabel(achievement));
    view.put("dataNature", achievement.dataNature().name());
    view.put("visibility", achievement.visibility().name());
    view.put("effectiveStatus", achievement.effectiveStatus().name());
    view.put("version", achievement.version());
    view.put("merged", achievement.merged());
    view.put("mergedIntoId", achievement.mergedIntoId());
    return view;
  }

  /**
   * 合并成果。
   *
   * <p>整个过程在一个本地事务内完成：快照 → 迁移关系与归属 → 建立重定向 → 裁决这一对之间的候选
   * → 台账 → 审计 → 事件。事务外只做跨服务投递。
   */
  @Transactional
  public void mergeAchievements(
      String survivorId, String mergedId, String reason, ActorContext actor) {
    String resolvedReason = requireReason(reason);
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
    snapshot.put("mergedPublicationYear", merged.publicationYear().year());
    snapshot.put("contributionsBefore", mergeTargets.snapshotContributions(mergedId));
    snapshot.put("sourcesBefore", mergeTargets.snapshotSources(mergedId));
    snapshot.put(
        "identifiersBefore",
        identifierViews(mergeTargets.previewIdentifiers("ACHIEVEMENT", mergedId)));

    GovernanceRepositories.MergeTargetRepository.ContributionMove move =
        mergeTargets.moveContributions(mergedId, survivorId);
    if (move.removed() > MAX_MOVE_ROWS) {
      throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "参与关系迁移数量超过单次上限，请拆分处理");
    }
    int movedSources = mergeTargets.moveSourceLinks(mergedId, survivorId);
    int movedTopics = mergeTargets.moveTopicLinks(mergedId, survivorId);
    int movedIdentifiers = mergeTargets.moveIdentifiers("ACHIEVEMENT", mergedId, survivorId);

    if (!mergeTargets.markAchievementMerged(mergedId, survivorId)) {
      throw BusinessException.versionConflict("被合并成果状态已改变，请刷新后重试");
    }

    // 同步裁决这两条之间的候选：候选问的是「这两条是不是同一条」，合并就是回答。
    // 留着 OPEN 会让它变成一条处理不掉的死条目（再合并被拒、驳回又是在回答已回答的问题），
    // 而工作台按相似度降序，这类候选相似度最高，会一直压在最前面挡住真正待判断的。
    int candidatesClosed =
        candidates.resolveOpenBetween(
            survivorId,
            mergedId,
            DedupCandidate.Status.MERGED.name(),
            actor == null ? RoleNames.SYSTEM : actor.accountIdValue(),
            "随成果合并一并裁决：" + resolvedReason);

    snapshot.put("contributionsInserted", move.inserted());
    snapshot.put("contributionsRemoved", move.removed());
    snapshot.put("sourcesMoved", movedSources);
    snapshot.put("topicsMoved", movedTopics);
    snapshot.put("identifiersMoved", movedIdentifiers);
    snapshot.put("candidatesClosedWithMerge", candidatesClosed);

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
    snapshot.put(
        "identifiersBefore", identifierViews(mergeTargets.previewIdentifiers("SCHOLAR", mergedId)));
    snapshot.put("survivorHasAuthority", survivorAuthority.isPresent());
    snapshot.put("mergedHasAuthority", mergedAuthority.isPresent());

    GovernanceRepositories.MergeTargetRepository.ContributionMove move =
        mergeTargets.moveScholarContributions(mergedId, survivorId);
    int movedVariants = mergeTargets.moveNameVariants(mergedId, survivorId);
    // 学者的 ORCID / OpenAlex 标识同样改指幸存学者：不转移时同一 ORCID 再次入库会命中已合并实体。
    int movedIdentifiers = mergeTargets.moveIdentifiers("SCHOLAR", mergedId, survivorId);

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
    snapshot.put("identifiersMoved", movedIdentifiers);

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
