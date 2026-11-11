package com.cognitanexus.core.notification.application;

import com.cognitanexus.core.identity.application.IdentityRepositories.AccountRepository;
import com.cognitanexus.core.notification.domain.Notification;
import com.cognitanexus.core.notification.infrastructure.AnnouncementMapper;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.Capabilities;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.platform.util.Identifiers;
import com.cognitanexus.sharedkernel.BusinessException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 系统公告：管理员发一条，全体启用账号各收到一条站内通知。
 *
 * <p>依据：需求基线「站内通知」扩展项。三个设计决定与它们的代价：
 *
 * <ol>
 *   <li><b>广播的实现是扇形写（每个账号一条通知），不是「一张公告表 + 读取时合并」。</b>
 *       代价是新注册账号看不到历史公告、公告发错无法撤回；收益是未读数、已读、移除、按类型
 *       筛选四套逻辑完全复用既有通知通道，不需要为公告单独维护一套已读状态表。当前账号规模
 *       （百级）下，扇形写是一个事务里几百条 INSERT，远比多一套并行状态模型便宜。
 *   <li><b>公告正文在发布时被复制进每条通知</b>，之后不再引用公告表。否则改一次正文就会
 *       改写所有人已经收到的内容，而通知是不可变的历史记录。
 *   <li><b>发布是一次性事务</b>：公告行与 N 条通知同生共死。中途失败不会留下「有公告记录
 *       但没人收到」的状态——那比完全没发更让人困惑。
 * </ol>
 *
 * <p>为什么要有「同标题 60 秒内拒绝」：管理员双击发布是最常见的误操作，而后台会在几百毫秒内
 * 收到两个请求。没有这道拦截，全体账号会各收到两条一模一样的通知，且无法撤回。
 */
@Service
public class AnnouncementService {

  private static final Logger log = LoggerFactory.getLogger(AnnouncementService.class);

  /** 广播上限。超过就拒绝而不是截断：截断意味着「一部分人收到了公告」，这是最难排查的故障。 */
  private static final int MAX_RECIPIENTS = 20000;

  private static final int MAX_TITLE = 200;
  private static final int MAX_BODY = 2000;
  private static final int DUPLICATE_WINDOW_SECONDS = 60;
  private static final int MAX_LIST = 100;

  private static final String LEVEL_INFO = "INFO";
  private static final String LEVEL_WARNING = "WARNING";

  private final AnnouncementMapper announcements;
  private final NotificationService notifications;
  private final AccountRepository accounts;
  private final AuditRecorder audit;

  public AnnouncementService(
      AnnouncementMapper announcements,
      NotificationService notifications,
      AccountRepository accounts,
      AuditRecorder audit) {
    this.announcements = announcements;
    this.notifications = notifications;
    this.accounts = accounts;
    this.audit = audit;
  }

  /** 发布结果。recipients 是实际写入的通知条数，不是「已读人数」。 */
  public record PublishResult(String announcementId, int recipients, String note) {}

  @Transactional
  public PublishResult publish(ActorContext actor, String title, String body, String level) {
    Capabilities.require(actor, RoleNames.CAP_GOVERNANCE_MANAGE);
    String safeTitle = requireText(title, MAX_TITLE, "公告标题");
    String safeBody = requireText(body, MAX_BODY, "公告正文");
    String safeLevel = normalizeLevel(level);

    if (announcements.countRecentByTitle(safeTitle, DUPLICATE_WINDOW_SECONDS) > 0) {
      throw BusinessException.businessRule("60 秒内已发布过同标题公告；如确需再发一次，请修改标题以区分（公告无法撤回）");
    }

    // 多取一条用于判断是否越界。截断会让「一部分账号收到公告」变成事实，且事后无法补齐
    // （公告没有已读/未读之外的状态可用于补发）。
    List<String> accountIds = accounts.listActiveAccountIds(MAX_RECIPIENTS + 1);
    if (accountIds.size() > MAX_RECIPIENTS) {
      throw BusinessException.businessRule("启用账号数超过公告广播上限 " + MAX_RECIPIENTS + "，请改用分类推送或分批发布");
    }
    if (accountIds.isEmpty()) {
      throw BusinessException.businessRule("当前没有启用账号，公告无人接收；请先确认账号状态");
    }

    String announcementId = Identifiers.next("anc");
    for (String accountId : accountIds) {
      notifications.send(
          accountId,
          Notification.KIND_SYSTEM_ANNOUNCEMENT,
          safeTitle,
          safeBody,
          "ANNOUNCEMENT",
          announcementId);
    }
    announcements.insert(
        announcementId, safeTitle, safeBody, safeLevel, actor.accountIdValue(), accountIds.size());

    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "ANNOUNCEMENT_PUBLISHED",
            "ANNOUNCEMENT",
            announcementId,
            null,
            Map.of("level", safeLevel, "recipients", String.valueOf(accountIds.size())),
            "发布系统公告：「" + safeTitle + "」"));

    log.info("系统公告已发布 id={} 收件账号={}", announcementId, accountIds.size());
    return new PublishResult(
        announcementId,
        accountIds.size(),
        "已向 " + accountIds.size() + " 个启用账号各写一条通知。公告发出后无法撤回，" + "如需更正请再发一条；发布当时尚未注册的账号不会收到这条公告。");
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> list(ActorContext actor, int limit) {
    Capabilities.require(actor, RoleNames.CAP_GOVERNANCE_MANAGE);
    int capped = Math.min(Math.max(limit, 1), MAX_LIST);
    return announcements.list(capped).stream()
        .map(
            row -> {
              Map<String, Object> view = new LinkedHashMap<>();
              view.put("id", row.id());
              view.put("title", row.title());
              view.put("body", row.body());
              view.put("level", row.level());
              view.put("publishedBy", row.publishedBy());
              view.put("publishedByName", row.publishedByName());
              view.put("publishedAt", row.publishedAt());
              view.put("recipients", row.recipients());
              return view;
            })
        .toList();
  }

  private static String requireText(String value, int max, String what) {
    if (value == null || value.isBlank()) {
      throw BusinessException.invalidArgument(what + "不能为空");
    }
    String trimmed = value.trim();
    if (trimmed.length() > max) {
      throw BusinessException.invalidArgument(what + "超过 " + max + " 字");
    }
    return trimmed;
  }

  private static String normalizeLevel(String level) {
    if (level == null || level.isBlank()) {
      return LEVEL_INFO;
    }
    String upper = level.trim().toUpperCase(java.util.Locale.ROOT);
    if (!upper.equals(LEVEL_INFO) && !upper.equals(LEVEL_WARNING)) {
      throw BusinessException.invalidArgument("公告级别只支持 INFO 或 WARNING");
    }
    return upper;
  }
}
