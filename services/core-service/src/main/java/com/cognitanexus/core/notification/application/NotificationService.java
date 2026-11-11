package com.cognitanexus.core.notification.application;

import com.cognitanexus.core.notification.domain.Notification;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.util.Identifiers;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 站内通知的写入与读取。
 *
 * <p>依据：ADR-003。核心语义：
 *
 * <ul>
 *   <li><b>发送跟随业务事务</b>。send 用 {@link Propagation#MANDATORY}：必须已经处在业务事务里，
 *       由业务提交时一并落库。业务回滚通知一起消失——「合并被回滚却留下『你有一条成果被合并』」
 *       才是错的（ADR-003 §2 第 3 条）。
 *   <li><b>系统单向写入</b>：没有面向用户的发送接口，写入只能由本服务在业务代码里调用。
 *   <li><b>读取只看自己</b>：列表、未读数、标记已读全部以当前登录账号为条件，不接受任何
 *       accountId 参数。
 * </ul>
 */
@Service
public class NotificationService {

  private static final int MAX_PAGE_SIZE = 100;
  private static final int MAX_MARK_READ = 200;
  private static final int MAX_BODY_LENGTH = 2000;

  private final NotificationRepositories.NotificationRepository notifications;
  private final NotificationRepositories.ScholarClaimantResolver claimants;

  public NotificationService(
      NotificationRepositories.NotificationRepository notifications,
      NotificationRepositories.ScholarClaimantResolver claimants) {
    this.notifications = notifications;
    this.claimants = claimants;
  }

  // ---------------------------------------------------------------------------
  // 写入（仅限业务代码在事务内调用）
  // ---------------------------------------------------------------------------

  /**
   * 给单个账号发通知。必须在业务事务内调用；本方法自身不开启事务，保证与业务动作同生共死。
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void send(
      String accountId, String kind, String title, String body, String refType, String refId) {
    if (accountId == null || accountId.isBlank()) {
      throw new IllegalArgumentException("通知接收账号不能为空");
    }
    String safeTitle = requireText(title, "通知标题");
    String safeBody = requireText(body, "通知内容");
    if (safeBody.length() > MAX_BODY_LENGTH) {
      // 措辞由调用方控制，这里只做硬上限防呆：谁往通知里塞了申请陈述原文，会在测试期暴露。
      throw new IllegalArgumentException("通知内容超过上限 " + MAX_BODY_LENGTH + " 字，请只写结论与去向");
    }
    notifications.insert(
        new Notification(
            Identifiers.next("ntf"),
            accountId,
            kind,
            safeTitle,
            safeBody,
            refType,
            refId,
            Instant.now(),
            null));
  }

  /**
   * 给这批学者的活跃认领账号发同一条通知，按账号去重（一个账号管理多个档案时只收到一条，
   * ADR-003 §3）。返回实际写入条数，供调用方写进日志或台账。
   *
   * <p>同样要求在业务事务内调用。学者无人认领时静默返回 0——「没有接收者」不是错误。
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int sendToScholarClaimants(
      Collection<String> scholarIds,
      String kind,
      String title,
      String body,
      String refType,
      String refId) {
    if (scholarIds == null || scholarIds.isEmpty()) {
      return 0;
    }
    // 服务层自己再去重一次，不依赖「实现承诺已去重」：端口契约说了去重，但同一个账号通过
    // 不同学者命中时少一道防线就是两条轰炸。LinkedHashSet 同时保证每个账号只发一条。
    List<String> accountIds =
        List.copyOf(new LinkedHashSet<>(claimants.resolveActiveAccountIds(scholarIds)));
    for (String accountId : accountIds) {
      send(accountId, kind, title, body, refType, refId);
    }
    return accountIds.size();
  }

  // ---------------------------------------------------------------------------
  // 读取（登录用户本人）
  // ---------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public List<Notification> list(
      ActorContext actor, boolean unreadOnly, String kind, int limit, int offset) {
    requireActor(actor);
    return notifications.listByAccount(
        actor.accountIdValue(), unreadOnly, normalizeKind(kind), cap(limit), Math.max(offset, 0));
  }

  @Transactional(readOnly = true)
  public long unreadCount(ActorContext actor) {
    requireActor(actor);
    return notifications.countUnread(actor.accountIdValue());
  }

  /** 本人列表中实际出现过的通知类型，用于筛选下拉。只给类型不给数量：数量会被误读成「未读数」。 */
  @Transactional(readOnly = true)
  public List<String> kinds(ActorContext actor) {
    requireActor(actor);
    return notifications.kindsOf(actor.accountIdValue());
  }

  /** 标记已读。ids 为空直接返回 0；部分 id 不是本人的行不受影响，也不报错。 */
  @Transactional
  public int markRead(ActorContext actor, List<String> ids) {
    requireActor(actor);
    if (ids == null || ids.isEmpty()) {
      return 0;
    }
    return notifications.markRead(actor.accountIdValue(), new LinkedHashSet<>(ids), MAX_MARK_READ);
  }

  /** 全部已读。只动本人的未读行，返回受影响条数。 */
  @Transactional
  public int markAllRead(ActorContext actor) {
    requireActor(actor);
    return notifications.markAllRead(actor.accountIdValue());
  }

  /**
   * 从本人列表移除若干条。
   *
   * <p>是归档不是删除：行仍在库里，只是不再出现在该账号的列表与未读数里（ADR-003 §2 的
   * 「通知不可删除」与用户「列表太长」的诉求，在这一点上并不冲突）。
   */
  @Transactional
  public int archive(ActorContext actor, List<String> ids) {
    requireActor(actor);
    if (ids == null || ids.isEmpty()) {
      return 0;
    }
    return notifications.archive(actor.accountIdValue(), new LinkedHashSet<>(ids), MAX_MARK_READ);
  }

  /** 清空已读：只归档自己已读的通知，未读的一条都不动。 */
  @Transactional
  public int archiveAllRead(ActorContext actor) {
    requireActor(actor);
    return notifications.archiveAllRead(actor.accountIdValue());
  }

  private static String normalizeKind(String kind) {
    if (kind == null || kind.isBlank()) {
      return null;
    }
    return kind.trim().toUpperCase(java.util.Locale.ROOT);
  }

  private static void requireActor(ActorContext actor) {
    if (actor == null) {
      throw new BusinessException(ErrorCode.AUTH_REQUIRED, "请先登录");
    }
  }

  private static int cap(int limit) {
    return Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
  }

  private static String requireText(String value, String what) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(what + "不能为空");
    }
    return value.trim();
  }
}
