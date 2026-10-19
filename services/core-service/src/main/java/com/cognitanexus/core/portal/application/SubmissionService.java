package com.cognitanexus.core.portal.application;

import com.cognitanexus.core.catalog.application.CatalogChangePublisher;
import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ContributionRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.FieldProvenanceRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.IdentifierRegistryRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ScholarRepository;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.AchievementDetails;
import com.cognitanexus.core.catalog.domain.Contribution;
import com.cognitanexus.core.catalog.domain.EffectiveStatus;
import com.cognitanexus.core.catalog.domain.FieldProvenance;
import com.cognitanexus.core.catalog.domain.IdentifierKind;
import com.cognitanexus.core.catalog.domain.PublicationYear;
import com.cognitanexus.core.catalog.domain.Scholar;
import com.cognitanexus.core.notification.application.NotificationService;
import com.cognitanexus.core.notification.domain.Notification;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.idempotency.IdempotencyGuard;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.Capabilities;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.platform.util.Identifiers;
import com.cognitanexus.core.platform.util.TextNormalizer;
import com.cognitanexus.core.portal.domain.Submission;
import com.cognitanexus.sharedkernel.AchievementType;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.DataNature;
import com.cognitanexus.sharedkernel.RelationState;
import com.cognitanexus.sharedkernel.Visibility;
import java.time.Instant;
import java.time.Year;
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
 * 成果自助提交：提交 → 查重 → 人工审核 → 入库 → 通知。
 *
 * <p>依据：需求基线「学者自主提交成果」；SRS 5.2、BR-IMP-08。核心边界有三条，全部体现在代码里：
 *
 * <ol>
 *   <li><b>提交件不是成果。</b>审核通过前，它只存在于 {@code core_submission}，不进入公开目录、
 *       不产生参与关系、也不出现在任何检索里。先建成成果再挂一个「待审」标记，会让每个读路径
 *       都多出一个必须记得加的条件，漏一处就泄露未审内容。
 *   <li><b>查重只提示，不合并。</b>DOI 精确命中或题名高度相似，都只写进提交件的提示字段，
 *       由人裁决。「相似标题不直接合并」是业务红线——自动合并两个不同作者的同一题名，
 *       后果是把一个人的成果记到另一个人名下，且事后无从分辨。
 *   <li><b>批准后的数据性质是 REAL，值级别是 MANUAL。</b>内容由本人提供，属于人工录入而非来源
 *       抓取，因此写 MANUAL：此后来源自动更新不得覆盖它（BR-IMP-04）。同时它不是「平台给的
 *       认证」——页面上仍标注来源，读者能看出这条记录是本人提交、经人工审核后公开的。
 * </ol>
 */
@Service
public class SubmissionService {

  private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);

  private static final int MAX_TITLE_LENGTH = 500;
  private static final int MAX_VENUE_LENGTH = 300;
  private static final int MAX_ABSTRACT_LENGTH = 5000;
  private static final int MAX_AUTHORS_LENGTH = 1000;
  private static final int MAX_LIMIT = 100;
  private static final int MAX_REASON_LENGTH = 1000;
  private static final int MIN_YEAR = 1500;

  /**
   * 题名相似度阈值。
   *
   * <p>定在 0.75 而不是更低：这个值只用来「提醒审核员看一眼」，但提醒太频繁等于没有提醒——
   * 每条提交都标红，人就学会无视它了。0.75 大致对应「几乎逐字相同、只差标点与副标题」。
   */
  static final double TITLE_DUPLICATE_THRESHOLD = 0.75;

  private final PortalRepositories.SubmissionRepository submissions;
  private final ScholarRepository scholars;
  private final AchievementRepository achievements;
  private final ContributionRepository contributions;
  private final FieldProvenanceRepository provenance;
  private final IdentifierRegistryRepository identifiers;
  private final CatalogChangePublisher catalogChanges;
  private final IdempotencyGuard idempotency;
  private final AuditRecorder audit;
  private final NotificationService notifications;

  public SubmissionService(
      PortalRepositories.SubmissionRepository submissions,
      ScholarRepository scholars,
      AchievementRepository achievements,
      ContributionRepository contributions,
      FieldProvenanceRepository provenance,
      IdentifierRegistryRepository identifiers,
      CatalogChangePublisher catalogChanges,
      IdempotencyGuard idempotency,
      AuditRecorder audit,
      NotificationService notifications) {
    this.submissions = submissions;
    this.scholars = scholars;
    this.achievements = achievements;
    this.contributions = contributions;
    this.provenance = provenance;
    this.identifiers = identifiers;
    this.catalogChanges = catalogChanges;
    this.idempotency = idempotency;
    this.audit = audit;
    this.notifications = notifications;
  }

  /** 提交表单。字段全部可选除了题名；缺什么就在审核时缺什么，不在提交时替作者补。 */
  public record NewSubmission(
      String type,
      String title,
      String doi,
      String venue,
      Integer publicationYear,
      String abstractText,
      String authorsRaw,
      String scholarId) {}

  /** 裁决结果。 */
  public record DecisionResult(String status, String achievementId, String message) {}

  // ---------------------------------------------------------------------------
  // 提交与本人操作
  // ---------------------------------------------------------------------------

  /**
   * 提交一条成果。
   *
   * <p>幂等键的处理与认领一致：同一个键重复提交返回首次那条，而不是建第二条。没有幂等键时
   * 不做去重——作者在两分钟内改了题名再交一次，确实可能是两条不同的记录，平台不该替人决定。
   */
  @Transactional
  public Submission submit(ActorContext actor, NewSubmission request, String idempotencyKey) {
    Capabilities.require(actor, RoleNames.CAP_SUBMISSION_SUBMIT);
    if (request == null) {
      throw BusinessException.invalidArgument("提交内容不能为空");
    }
    String title = requireText(request.title(), MAX_TITLE_LENGTH, "题名");
    AchievementType type = parseType(request.type());
    if (type != AchievementType.PAPER) {
      // 专利/项目/获奖的扩展各自要求申请号、项目编号等必填事实，缺了它们建出来的记录在数据库
      // 层面会被类型一致性约束拒绝。与其在这里编一个空申请号，不如明确说目前只收论文。
      throw BusinessException.invalidArgument("目前只接受论文类型的自助提交；专利/项目/获奖请在治理端录入");
    }
    String doi = trimToNull(request.doi(), 200, "DOI");
    String doiNorm = doi == null ? null : TextNormalizer.normalizeStableIdentifier(doi);
    String venue = trimToNull(request.venue(), MAX_VENUE_LENGTH, "发表载体");
    String abstractText = trimToNull(request.abstractText(), MAX_ABSTRACT_LENGTH, "摘要");
    String authors = trimToNull(request.authorsRaw(), MAX_AUTHORS_LENGTH, "作者署名");
    Integer year = requireYear(request.publicationYear());
    String scholarId = trimToNull(request.scholarId(), 64, "学者标识");
    if (scholarId != null) {
      Scholar scholar =
          scholars.findById(scholarId).orElseThrow(() -> BusinessException.notFound("学者不存在"));
      if (scholar.merged()) {
        throw BusinessException.businessRule("该学者档案已被合并，请对最终实体提交");
      }
    }

    IdempotencyGuard.Reservation reservation =
        idempotency.reserve(
            actor.accountIdValue(),
            "SUBMISSION_SUBMIT",
            idempotencyKey,
            title + "|" + (doiNorm == null ? "" : doiNorm));
    if (reservation.isReplay() && reservation.resultReference() != null) {
      Optional<Submission> existing =
          submissions.findById(String.valueOf(reservation.resultReference()));
      if (existing.isPresent()) {
        return existing.get();
      }
    }

    String titleNorm = TextNormalizer.normalize(title);
    // 查重顺序必须是「先 DOI 后题名」：DOI 是唯一标识，题名相似只是线索。反过来会让一条
    // DOI 完全一致、题名被作者改过的提交被标成「题名相似」，而真正的重复事实被冲淡。
    String duplicateOf = null;
    Submission.DuplicateKind duplicateKind = null;
    String duplicateNote = null;
    if (doiNorm != null) {
      Optional<String> byDoi = submissions.findAchievementIdByDoi(doiNorm);
      if (byDoi.isPresent()) {
        duplicateOf = byDoi.get();
        duplicateKind = Submission.DuplicateKind.DOI;
        duplicateNote = "平台已有相同 DOI 的成果；提交仍可继续，由审核确认是否重复";
      }
    }
    if (duplicateOf == null) {
      Optional<PortalRepositories.SubmissionRepository.SimilarAchievement> similar =
          submissions.findMostSimilarTitle(titleNorm);
      if (similar.isPresent() && similar.get().score() >= TITLE_DUPLICATE_THRESHOLD) {
        duplicateOf = similar.get().achievementId();
        duplicateKind = Submission.DuplicateKind.TITLE;
        duplicateNote =
            "题名与已有成果高度相似（相似度 " + Math.round(similar.get().score() * 100) + "%）；相似不等于重复，由审核判断";
      }
    }

    String submissionId = Identifiers.next("sub");
    Submission submission =
        new Submission(
            submissionId,
            actor.accountIdValue(),
            scholarId,
            type,
            title,
            titleNorm,
            doi,
            doiNorm,
            venue,
            year,
            abstractText,
            authors,
            Submission.Status.SUBMITTED,
            duplicateOf,
            duplicateKind,
            duplicateNote,
            null,
            null,
            null,
            null,
            idempotencyKey,
            1L,
            Instant.now(),
            Instant.now());
    submissions.insert(submission);

    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "SUBMISSION_CREATED",
            "SUBMISSION",
            submissionId,
            null,
            Map.of(
                "type",
                type.name(),
                "status",
                Submission.Status.SUBMITTED.name(),
                "duplicateKind",
                duplicateKind == null ? "" : duplicateKind.name(),
                "duplicateOf",
                duplicateOf == null ? "" : duplicateOf),
            "提交成果待审"));
    idempotency.attachResult(
        actor.accountIdValue(), "SUBMISSION_SUBMIT", idempotencyKey, submissionId);
    log.info("成果提交已登记 submissionId={} account={}", submissionId, actor.accountIdValue());
    return submissions.findById(submissionId).orElseThrow();
  }

  @Transactional(readOnly = true)
  public List<Submission> listMine(ActorContext actor, int limit) {
    Capabilities.require(actor, RoleNames.CAP_SUBMISSION_SUBMIT);
    return submissions.listByAccount(actor.accountIdValue(), clampLimit(limit));
  }

  /** 本人撤回。只允许撤回自己名下、尚未裁决的提交。 */
  @Transactional
  public Submission withdraw(ActorContext actor, String submissionId) {
    Capabilities.require(actor, RoleNames.CAP_SUBMISSION_SUBMIT);
    Submission submission = load(submissionId);
    if (!submission.accountId().equals(actor.accountIdValue())) {
      // 不区分「不存在」与「不是你的」会让接口变成一个探测他人提交 ID 的入口；
      // 这里统一报 403，因为 ID 不可枚举（sub_ + 随机串），泄露风险的代价高于排查便利。
      throw BusinessException.accessDenied("只能撤回自己提交的记录");
    }
    if (!submission.open()) {
      throw BusinessException.businessRule("该提交已被裁决，不能再撤回");
    }
    int affected = submissions.withdraw(submissionId, actor.accountIdValue(), submission.version());
    if (affected != 1) {
      throw BusinessException.versionConflict("提交状态已被其他操作改变，请刷新后重试");
    }
    audit.record(
        AuditRecorder.AuditEntry.of(
            actor, "SUBMISSION_WITHDRAWN", "SUBMISSION", submissionId, null, null, "本人撤回提交"));
    return submissions.findById(submissionId).orElseThrow();
  }

  // ---------------------------------------------------------------------------
  // 审核
  // ---------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public List<Submission> queue(ActorContext actor, int limit, int offset) {
    Capabilities.require(actor, RoleNames.CAP_SUBMISSION_REVIEW);
    return submissions.listOpen(clampLimit(limit), Math.max(0, offset));
  }

  @Transactional(readOnly = true)
  public long countOpen() {
    return submissions.countOpen();
  }

  /**
   * 裁决一条提交。
   *
   * <p>批准时在一个事务里完成四件事：建成果、建论文扩展、按需建参与关系、写字段溯源。任何一步
   * 失败都整体回滚——留下「成果已建但扩展没建」的半条记录，会让公开列表出现一条点开就报错的
   * 条目，而触发器只在提交时校验，事后没人会再检查。
   *
   * <p>参与关系只在作者<b>明确指定了</b>学者档案时才建立，且状态是 SELF_CONFIRMED：
   * 本人提交即本人确认参与。没指定就只建成果——替作者认下一条他没说过的署名，是这类系统里
   * 最常见的越界。
   */
  @Transactional
  public DecisionResult decide(
      ActorContext actor, String submissionId, String decision, String reason) {
    Capabilities.require(actor, RoleNames.CAP_SUBMISSION_REVIEW);
    Submission submission = load(submissionId);
    if (!submission.open()) {
      throw BusinessException.versionConflict("该提交已被裁决，请刷新队列");
    }
    String normalized = decision == null ? "" : decision.trim().toUpperCase(java.util.Locale.ROOT);
    String note = normalizeReason(reason);
    Instant now = Instant.now();

    switch (normalized) {
      case "APPROVE" -> {
        String achievementId = approve(actor, submission, note, now);
        audit.record(
            AuditRecorder.AuditEntry.of(
                actor,
                "SUBMISSION_APPROVED",
                "SUBMISSION",
                submissionId,
                Map.of("status", submission.status().name()),
                Map.of("status", "APPROVED", "achievementId", achievementId),
                note));
        notifySubmitter(
            submission,
            "你提交的成果已通过审核",
            "「" + submission.title() + "」已进入公开目录。平台按本人提交录入，字段值级别为人工值，" + "后续来源自动更新不会覆盖它。");
        return new DecisionResult("APPROVED", achievementId, "已通过并建档");
      }
      case "REJECT" -> {
        if (note.equals("未说明原因")) {
          throw BusinessException.invalidArgument("驳回必须说明原因");
        }
        transition(submission, Submission.Status.REJECTED, actor, note, now, null);
        audit.record(
            AuditRecorder.AuditEntry.of(
                actor, "SUBMISSION_REJECTED", "SUBMISSION", submissionId, null, null, note));
        notifySubmitter(
            submission,
            "你提交的成果未通过审核",
            "「" + submission.title() + "」未通过审核：" + note + "。如认为有误，可在补充材料后重新提交。");
        return new DecisionResult("REJECTED", null, "已驳回");
      }
      case "NEED_MORE_INFO" -> {
        if (note.equals("未说明原因")) {
          throw BusinessException.invalidArgument("要求补充材料时必须说明缺什么");
        }
        transition(submission, Submission.Status.NEED_MORE_INFO, actor, note, now, null);
        audit.record(
            AuditRecorder.AuditEntry.of(
                actor, "SUBMISSION_NEED_MORE_INFO", "SUBMISSION", submissionId, null, null, note));
        notifySubmitter(
            submission, "你提交的成果需要补充材料", "「" + submission.title() + "」暂未通过：" + note + "。");
        return new DecisionResult("NEED_MORE_INFO", null, "已标记待补充");
      }
      default -> throw BusinessException.invalidArgument("裁决只能是 APPROVE / REJECT / NEED_MORE_INFO");
    }
  }

  /**
   * 建成果。返回新成果标识。
   *
   * <p>可见性与生效状态直接写成 PUBLIC/EFFECTIVE：走到这里说明人工已经审核通过，而「待治理」
   * 是给自动采集用的过渡态——人工审过的记录再进待治理队列，等于同一条记录被要求审两次。
   */
  private String approve(ActorContext actor, Submission submission, String note, Instant now) {
    String achievementId = Identifiers.next("ach");
    Achievement achievement =
        new Achievement(
            achievementId,
            submission.type(),
            submission.title(),
            submission.titleNorm(),
            // 本人提供 + 人工审核：数据性质是真实数据，不是模拟，也不是混合。
            DataNature.REAL,
            Visibility.PUBLIC,
            EffectiveStatus.EFFECTIVE,
            PublicationYear.of(submission.publicationYear()),
            null,
            1L,
            now,
            now);
    achievements.insert(achievement);
    achievements.insertPaper(
        new AchievementDetails.Paper(
            achievementId,
            submission.doi(),
            submission.doiNorm(),
            submission.venue(),
            // 不把年份补成 1 月 1 日：那会制造一个来源从未给出的日期。
            null,
            null,
            // 引用量留空：提交内容里没有这一项，凭 0 填进去会被当成「确认无人引用」。
            null,
            null,
            submission.abstractText()));

    // 字段溯源写成 MANUAL：此后来源自动更新不得覆盖（BR-IMP-04）。
    provenance.upsertReviewed(
        "ACHIEVEMENT",
        achievementId,
        "title",
        FieldProvenance.ValueLevel.MANUAL.name(),
        submission.title());
    if (submission.doi() != null) {
      provenance.upsertReviewed(
          "ACHIEVEMENT",
          achievementId,
          "doi",
          FieldProvenance.ValueLevel.MANUAL.name(),
          submission.doi());
    }

    if (submission.scholarId() != null) {
      contributions.insertIfAbsent(
          new Contribution(
              Identifiers.next("ctb"),
              submission.scholarId(),
              achievementId,
              Contribution.Role.AUTHOR.name(),
              null,
              RelationState.SELF_CONFIRMED,
              null,
              1L,
              now,
              now));
    }

    // 有 DOI 的提交件要登记稳定标识：来源采集的论文在 SourceIntakeService 里走的是同一张注册表，
    // 提交来的不走，会让「明明有 DOI」的成果在红线核对（无稳定标识必须有人工确认）里被算成无标识，
    // 也让引用网络找不到这条成果——引用列表只给作品 ID / DOI，没有注册表就只能算站外。
    // 登记冲突不阻断批准：查重结论在提交时已经给过审核员，重复记录该走合并治理，不该在这一步炸掉。
    if (submission.doiNorm() != null) {
      boolean registered =
          identifiers.register(
              Identifiers.next("idr"),
              "ACHIEVEMENT",
              achievementId,
              IdentifierKind.DOI,
              "",
              submission.doiNorm());
      if (!registered) {
        log.warn("提交成果的 DOI 已被其他实体登记，本条未登记标识：achievement={}", achievementId);
      }
    }

    catalogChanges.publish(achievement, 1L);
    // 人工确认公开要留审计，即使它没有稳定标识。红线要求的是「无标识不得在未经人工确认的情况下公开」，
    // 审核通过本身就是那次确认；不落这条事件，就等于「确认过但无法证明」——治理端核对不变量时
    // 会把这条成果判成违规公开，而它其实合规。
    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "ACHIEVEMENT_PUBLISH_CONFIRMED",
            "ACHIEVEMENT",
            achievementId,
            null,
            Map.of("effectiveStatus", EffectiveStatus.EFFECTIVE.name(), "visibility", "PUBLIC"),
            note == null || note.isBlank() ? "成果自助提交审核通过，人工确认公开" : note));
    transition(submission, Submission.Status.APPROVED, actor, note, now, achievementId);
    return achievementId;
  }

  private void transition(
      Submission submission,
      Submission.Status status,
      ActorContext actor,
      String reason,
      Instant at,
      String achievementId) {
    int affected =
        submissions.decide(
            submission.id(),
            status.name(),
            actor.accountIdValue(),
            reason,
            at,
            achievementId,
            submission.version());
    if (affected != 1) {
      throw BusinessException.versionConflict("提交状态已被其他操作改变，请刷新后重试");
    }
  }

  private void notifySubmitter(Submission submission, String title, String body) {
    notifications.send(
        submission.accountId(),
        Notification.KIND_SUBMISSION_DECIDED,
        title,
        body,
        "SUBMISSION",
        submission.id());
  }

  private Submission load(String submissionId) {
    return submissions
        .findById(submissionId)
        .orElseThrow(() -> BusinessException.notFound("提交记录不存在"));
  }

  // ---------------------------------------------------------------------------
  // 校验
  // ---------------------------------------------------------------------------

  private static AchievementType parseType(String raw) {
    String value =
        raw == null || raw.isBlank() ? "PAPER" : raw.trim().toUpperCase(java.util.Locale.ROOT);
    try {
      return AchievementType.valueOf(value);
    } catch (IllegalArgumentException cause) {
      throw BusinessException.invalidArgument("未知的成果类型: " + raw);
    }
  }

  private static String requireText(String raw, int max, String label) {
    if (raw == null || raw.isBlank()) {
      throw BusinessException.invalidArgument(label + "不能为空");
    }
    String value = raw.trim();
    if (value.length() > max) {
      throw BusinessException.invalidArgument(label + "超过 " + max + " 字");
    }
    return value;
  }

  /** 可选文本：空白归一成 null，而不是空串——库里存空串会让「没填」与「填了空」变成两种状态。 */
  private static String trimToNull(String raw, int max, String label) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String value = raw.trim();
    if (value.length() > max) {
      throw BusinessException.invalidArgument(label + "超过 " + max + " 字");
    }
    return value;
  }

  /**
   * 年份校验：只挡明显不可能的年份。
   *
   * <p>上限取「明年」而不是「今年」：在线优先（online first）的论文常带有下一年的卷期年。
   * 下限取 1500：比这更早的「成果」几乎必然是录入错误，而放过它会让年度分布图多出一根
   * 孤零零的柱子，看起来像数据有问题。
   */
  private static Integer requireYear(Integer year) {
    if (year == null) {
      return null;
    }
    int current = Year.now().getValue();
    if (year < MIN_YEAR || year > current + 1) {
      throw BusinessException.invalidArgument("发表年份应在 " + MIN_YEAR + " 与 " + (current + 1) + " 之间");
    }
    return year;
  }

  private static String normalizeReason(String reason) {
    if (reason == null || reason.isBlank()) {
      return "未说明原因";
    }
    String value = reason.trim();
    if (value.length() > MAX_REASON_LENGTH) {
      throw BusinessException.invalidArgument("说明超过 " + MAX_REASON_LENGTH + " 字");
    }
    return value;
  }

  private static int clampLimit(int limit) {
    if (limit <= 0) {
      return 20;
    }
    return Math.min(limit, MAX_LIMIT);
  }

  // ---------------------------------------------------------------------------
  // 视图
  // ---------------------------------------------------------------------------

  /** 提交件视图。疑似重复时带上对方标题，否则审核员只看到一个 ID，还得自己去搜。 */
  @Transactional(readOnly = true)
  public Map<String, Object> view(Submission submission) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", submission.id());
    view.put("type", submission.type().name());
    view.put("typeLabel", submission.type().label());
    view.put("title", submission.title());
    view.put("doi", submission.doi());
    view.put("venue", submission.venue());
    view.put("publicationYear", submission.publicationYear());
    view.put("abstractText", submission.abstractText());
    view.put("authorsRaw", submission.authorsRaw());
    view.put("scholarId", submission.scholarId());
    view.put("status", submission.status().name());
    view.put("statusLabel", statusLabel(submission.status()));
    view.put("decisionReason", submission.decisionReason());
    view.put(
        "decidedAt", submission.decidedAt() == null ? null : submission.decidedAt().toString());
    view.put("achievementId", submission.achievementId());
    view.put("duplicateOf", submission.duplicateOf());
    view.put(
        "duplicateKind",
        submission.duplicateKind() == null ? null : submission.duplicateKind().name());
    view.put("duplicateNote", submission.duplicateNote());
    view.put("duplicateTitle", duplicateTitle(submission.duplicateOf()));
    view.put(
        "submittedAt", submission.createdAt() == null ? null : submission.createdAt().toString());
    view.put("open", submission.open());
    return view;
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> views(List<Submission> rows) {
    List<Map<String, Object>> views = new ArrayList<>();
    for (Submission row : rows) {
      views.add(view(row));
    }
    return views;
  }

  private String duplicateTitle(String achievementId) {
    if (achievementId == null) {
      return null;
    }
    return achievements.findById(achievementId).map(Achievement::title).orElse(null);
  }

  private static String statusLabel(Submission.Status status) {
    return switch (status) {
      case SUBMITTED -> "待审核";
      case NEED_MORE_INFO -> "待补充材料";
      case APPROVED -> "已通过并建档";
      case REJECTED -> "未通过";
      case WITHDRAWN -> "已撤回";
    };
  }
}
