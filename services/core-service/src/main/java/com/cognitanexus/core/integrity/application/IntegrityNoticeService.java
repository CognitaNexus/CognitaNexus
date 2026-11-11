package com.cognitanexus.core.integrity.application;

import com.cognitanexus.core.catalog.application.CatalogChangePublisher;
import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.integrity.domain.IntegrityNotice;
import com.cognitanexus.core.integrity.domain.IntegrityNoticeType;
import com.cognitanexus.core.integrity.domain.IntegritySummary;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.Capabilities;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.platform.util.Identifiers;
import com.cognitanexus.sharedkernel.BusinessException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 学术诚信声明的登记与撤销。
 *
 * <p>依据：FR02／FR16 的推论与 COPE 的三类已发表文献处理。此前平台只有「可见 / 不可见」两态，
 * 无法表达一件对学术读者至关重要的事实：<b>这条成果仍然成立吗</b>。撤稿与更正是学术记录的一部分，
 * 直接下架反而有害——引用过它的人需要看到「它已被撤稿」，而不是一个 404。
 *
 * <p><b>声明不修改成果的任何字段。</b>它是一条挂在成果上的独立判断：题名、年份、期刊、作者仍以
 * 来源原值为准，纠正内容仍走既有的修订与裁决流程。若让「撤稿」顺手改写字段，就等于用一次治理
 * 判断覆盖来源事实，而这正是本平台反复强调的红线。
 *
 * <p><b>撤稿不等于下架。</b>公开检索与详情仍然可访问，但必须显著标记。理由是：学术记录的完整性
 * 依赖于「曾经存在过的东西仍然可查」，隐藏一条撤稿成果会让引用它的人无从追溯。若确需下架，
 * 走的是发布状态（{@code WITHDRAWN}）与可见性，那是另一个决定，两者不应混为一谈。
 *
 * <p><b>登记与撤销都写审计，并外发一次目录变更。</b>外发不是为了「让检索显示得更快」，而是因为
 * 检索侧是一份投影：不重发事件，投影里就永远没有这条声明，读者在列表里看到的仍是干净的一条。
 */
@Service
public class IntegrityNoticeService {

  private static final Logger log = LoggerFactory.getLogger(IntegrityNoticeService.class);

  /** 理由下限：短于它无法判断这次声明依据的是什么事实。 */
  public static final int REASON_MIN = 10;

  /** 理由上限：详情页要能完整显示，不做截断。 */
  public static final int REASON_MAX = 1000;

  /** 声明主体名称上限。 */
  public static final int DECLARED_BY_MAX = 120;

  /** 可核查链接上限。 */
  public static final int SOURCE_URL_MAX = 500;

  /** 治理台一次最多返回的声明条数。 */
  public static final int RECENT_LIMIT_MAX = 200;

  private final AchievementRepository achievements;
  private final IntegrityNoticeRepository notices;
  private final CatalogChangePublisher publisher;
  private final AuditRecorder audit;
  private final Clock clock;

  @Autowired
  public IntegrityNoticeService(
      AchievementRepository achievements,
      IntegrityNoticeRepository notices,
      CatalogChangePublisher publisher,
      AuditRecorder audit) {
    this(achievements, notices, publisher, audit, Clock.systemUTC());
  }

  /** 测试入口：注入可控时钟，使「声明日期不能在未来」这条断言不依赖真实今天。 */
  public IntegrityNoticeService(
      AchievementRepository achievements,
      IntegrityNoticeRepository notices,
      CatalogChangePublisher publisher,
      AuditRecorder audit,
      Clock clock) {
    this.achievements = achievements;
    this.notices = notices;
    this.publisher = publisher;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * 登记一条声明。
   *
   * @param command 已通过格式校验之外的全部业务判断都在这里
   */
  @Transactional
  public IntegrityNotice register(
      ActorContext actor, String achievementId, RegisterCommand command) {
    Capabilities.require(actor, RoleNames.CAP_GOVERNANCE_MANAGE);
    Achievement achievement = requireLiveAchievement(achievementId);
    IntegrityNoticeType type = IntegrityNoticeType.of(command.noticeType());

    String declaredBy = requireText(command.declaredBy(), "声明主体", DECLARED_BY_MAX);
    String reason = requireReason(command.reason());
    String sourceUrl = normalizeSourceUrl(command.sourceUrl());
    LocalDate announcedOn = requireNotFuture(command.announcedOn());

    // 同一成果同一类型只允许一条生效声明。这里读一次是为了给出可操作的错误信息（告诉他已有一条
    // 以及在哪个 id 上），真正的唯一性由部分唯一索引兜住——两个请求同时通过这一步时，后者会被
    // 数据库拒绝，而不是写出两条并存声明。
    notices
        .findActive(achievement.id(), type.code())
        .ifPresent(
            existing -> {
              throw BusinessException.businessRule(
                  "该成果已有生效的" + type.label() + "声明（" + existing.id() + "），如需更正请先撤销");
            });

    Instant now = clock.instant();
    IntegrityNotice notice =
        new IntegrityNotice(
            Identifiers.next("integrity"),
            achievement.id(),
            type,
            IntegrityNotice.STATUS_ACTIVE,
            declaredBy,
            reason,
            sourceUrl,
            announcedOn,
            requireActorId(actor),
            now,
            null,
            null,
            null);
    notices.insert(notice);

    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "INTEGRITY_NOTICE_RECORDED",
            "ACHIEVEMENT",
            achievement.id(),
            Map.of(),
            afterView(notice),
            reason));

    // 投影侧必须知道这条声明，否则检索列表会把一条已撤稿的成果显示成干净的一条。
    publisher.publish(achievement, achievement.version());
    log.info(
        "登记诚信声明 achievementId={} type={} noticeId={}", achievement.id(), type.code(), notice.id());
    return notice;
  }

  /**
   * 撤销一条声明。
   *
   * <p>撤销不是删除：行保留，状态改为 {@code REVOKED}，并强制记录撤销人、时间与理由。撤销后
   * 汇总状态按剩余生效声明重算——若这是唯一一条撤稿声明，成果立刻恢复为「无诚信声明」。
   */
  @Transactional
  public IntegrityNotice revoke(ActorContext actor, String noticeId, String reason) {
    Capabilities.require(actor, RoleNames.CAP_GOVERNANCE_MANAGE);
    String trimmed = requireReason(reason);
    IntegrityNotice notice =
        notices.findById(noticeId).orElseThrow(() -> BusinessException.notFound("声明不存在"));
    if (!notice.active()) {
      throw BusinessException.versionConflict("该声明已被撤销（" + notice.revokedAt() + "），无需重复操作");
    }
    Achievement achievement = requireLiveAchievement(notice.achievementId());

    int changed = notices.revoke(noticeId, requireActorId(actor), clock.instant(), trimmed);
    if (changed != 1) {
      // 并发撤销：另一个请求已经把状态改掉。按版本冲突返回，不覆盖对方写下的撤销理由。
      throw BusinessException.versionConflict("该声明状态已被其他操作改变，请刷新后重试");
    }
    IntegrityNotice revoked =
        notices.findById(noticeId).orElseThrow(() -> BusinessException.notFound("声明不存在"));

    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "INTEGRITY_NOTICE_REVOKED",
            "ACHIEVEMENT",
            achievement.id(),
            afterView(notice),
            afterView(revoked),
            trimmed));

    publisher.publish(achievement, achievement.version());
    log.info("撤销诚信声明 achievementId={} noticeId={}", achievement.id(), noticeId);
    return revoked;
  }

  /** 某条成果的全部声明（含已撤销），供公开详情与治理台使用。 */
  /**
   * 某条成果的全部声明。
   *
   * <p>要求治理能力：返回的条目含「登记账号」等管理端字段，公开页面用的是投影里已经裁过一次的
   * 视图，不从这里取。把校验放在应用层而不是控制器，是为了让将来新增的调用方也自动受约束——
   * 控制器上的注解只保护那一个 URL，保护不了下一个调用点。
   */
  public List<IntegrityNotice> noticesOf(ActorContext actor, String achievementId) {
    Capabilities.require(actor, RoleNames.CAP_GOVERNANCE_MANAGE);
    return notices.findByAchievement(achievementId);
  }

  /** 汇总状态。管理端视图，要求治理能力；没有声明时返回「无诚信声明」而不是空对象。 */
  public IntegritySummary summaryOf(ActorContext actor, String achievementId) {
    Capabilities.require(actor, RoleNames.CAP_GOVERNANCE_MANAGE);
    return IntegritySummary.of(notices.findByAchievement(achievementId));
  }

  /** 治理台：最近的声明。跨成果列出，因此必须要求治理能力。 */
  public List<IntegrityNotice> recent(ActorContext actor, int limit, boolean activeOnly) {
    Capabilities.require(actor, RoleNames.CAP_GOVERNANCE_MANAGE);
    int safeLimit = Math.min(Math.max(limit, 1), RECENT_LIMIT_MAX);
    return notices.listRecent(safeLimit, activeOnly);
  }

  /** 管理端登记命令。{@code noticeType} 用字符串接收，由领域枚举严格解析。 */
  public record RegisterCommand(
      String noticeType,
      String declaredBy,
      String reason,
      String sourceUrl,
      LocalDate announcedOn) {}

  // ---------------------------------------------------------------------------
  // 内部
  // ---------------------------------------------------------------------------

  /**
   * 取一条「仍然独立存在」的成果。
   *
   * <p>被合并的成果是墓碑：它已经不代表任何实体，在它上面登记声明会写进一条永远不会被展示的行。
   * 因此直接拒绝，并把幸存标识告诉调用方——让人去改链接，而不是让调用方自己猜。
   */
  private Achievement requireLiveAchievement(String achievementId) {
    if (achievementId == null || achievementId.isBlank()) {
      throw BusinessException.invalidArgument("成果标识不能为空");
    }
    Achievement achievement =
        achievements.findById(achievementId).orElseThrow(() -> BusinessException.notFound("成果不存在"));
    if (achievement.mergedIntoId() != null) {
      throw BusinessException.businessRule(
          "该成果已被合并到 " + achievement.mergedIntoId() + "，请在幸存成果上登记声明");
    }
    return achievement;
  }

  private static String requireActorId(ActorContext actor) {
    String accountId = actor == null ? null : actor.accountIdValue();
    if (accountId == null || accountId.isBlank()) {
      // 声明必须能追到人。能力校验已经要求登录，这里兜住「角色来自系统主体」的异常情形。
      throw BusinessException.accessDenied("登记诚信声明需要已认证的治理账号");
    }
    return accountId;
  }

  private static String requireText(String raw, String field, int max) {
    if (raw == null || raw.isBlank()) {
      throw BusinessException.invalidArgument(field + "不能为空");
    }
    String value = raw.trim();
    if (value.length() > max) {
      throw BusinessException.invalidArgument(field + "不能超过 " + max + " 字");
    }
    return value;
  }

  private static String requireReason(String raw) {
    String value = requireText(raw, "理由", REASON_MAX);
    if (value.length() < REASON_MIN) {
      throw BusinessException.invalidArgument("理由至少 " + REASON_MIN + " 字：读者要能据此判断这次声明的依据");
    }
    return value;
  }

  /**
   * 规范化可核查链接。
   *
   * <p>只接受 http/https 且必须有主机名：这个字段会渲染成链接，允许 {@code javascript:} 之类
   * 的伪协议等于把详情页交给声明登记人控制。这里不做「自动补 https://」之类的宽容处理——猜错的
   * 链接比空链接更难发现。
   */
  private static String normalizeSourceUrl(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String value = raw.trim();
    if (value.length() > SOURCE_URL_MAX) {
      throw BusinessException.invalidArgument("链接不能超过 " + SOURCE_URL_MAX + " 字符");
    }
    URI uri;
    try {
      uri = new URI(value);
    } catch (URISyntaxException e) {
      throw BusinessException.invalidArgument("链接格式不合法：" + value);
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
      throw BusinessException.invalidArgument("链接只支持 http 或 https");
    }
    if (uri.getHost() == null || uri.getHost().isBlank()) {
      throw BusinessException.invalidArgument("链接缺少主机名：" + value);
    }
    return value;
  }

  /** 声明日期不能在未来：未来的「撤稿日期」是录入错误，不是事实。 */
  private LocalDate requireNotFuture(LocalDate announcedOn) {
    if (announcedOn == null) {
      return null;
    }
    LocalDate today = LocalDate.ofInstant(clock.instant(), clock.getZone());
    if (announcedOn.isAfter(today)) {
      throw BusinessException.invalidArgument("声明日期不能晚于今天（" + today + "）");
    }
    return announcedOn;
  }

  private static Map<String, Object> afterView(IntegrityNotice notice) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", notice.id());
    view.put("achievementId", notice.achievementId());
    view.put("noticeType", notice.noticeType().code());
    view.put("status", notice.status());
    view.put("declaredBy", notice.declaredBy());
    view.put("sourceUrl", notice.sourceUrl());
    view.put("announcedOn", notice.announcedOn() == null ? null : notice.announcedOn().toString());
    return view;
  }
}
