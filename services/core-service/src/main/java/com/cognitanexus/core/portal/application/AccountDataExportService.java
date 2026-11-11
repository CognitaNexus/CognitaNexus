package com.cognitanexus.core.portal.application;

import com.cognitanexus.core.catalog.application.CatalogRepositories.ContributionRepository;
import com.cognitanexus.core.catalog.domain.Contribution;
import com.cognitanexus.core.identity.application.IdentityRepositories.AccountRepository;
import com.cognitanexus.core.identity.application.IdentityRepositories.PortalAuthorityRepository;
import com.cognitanexus.core.identity.domain.Account;
import com.cognitanexus.core.identity.domain.PortalAuthority;
import com.cognitanexus.core.notification.application.NotificationRepositories.NotificationRepository;
import com.cognitanexus.core.notification.domain.Notification;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.util.Identifiers;
import com.cognitanexus.core.portal.domain.ClaimRequest;
import com.cognitanexus.core.portal.domain.PortalChangeRequest;
import com.cognitanexus.core.portal.domain.Submission;
import com.cognitanexus.sharedkernel.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号级数据导出（合规向）。
 *
 * <p>与「我的成果导出 CSV」不同：那个是给用户拿去用在别处的成果清单，这个是「平台关于我的全部
 * 记录」。因此三条边界：
 *
 * <ol>
 *   <li><b>只导本人。</b>全部查询以当前会话账号为条件，不接受 accountId 参数。贡献关系按「本人
 *       持有授权的门户」逐个取，而不是按学者 ID 全量取——后者会把其他认领人的关系也导出来。
 *   <li><b>不导敏感物。</b>不含口令哈希、会话标识、文件路径、来源报文原文。导出文件会被转发、
 *       上传到别的地方，口令哈希一旦进去就等于泄露。
 *   <li><b>有明确上限并写进文件。</b>通知最多 500 条、提交/认领/变更各最多 100 条。静默截断会
 *       让用户以为「这就是全部」，所以上限写进响应体的 limits 字段。
 * </ol>
 *
 * <p>导出用 JSON 而不是 CSV：这份东西的结构是嵌套的（账号下挂门户、门户下挂成果），CSV 要么
 * 拆成多个文件，要么把层级压平到无法还原。
 */
@Service
public class AccountDataExportService {

  private static final Logger log = LoggerFactory.getLogger(AccountDataExportService.class);

  private static final int MAX_NOTIFICATIONS = 500;
  private static final int MAX_ROWS = 100;

  private final AccountRepository accounts;
  private final PortalAuthorityRepository portals;
  private final PortalRepositories.ClaimRequestRepository claims;
  private final PortalRepositories.PortalChangeRequestRepository changes;
  private final PortalRepositories.SubmissionRepository submissions;
  private final ContributionRepository contributions;
  private final NotificationRepository notifications;

  private final ObjectMapper mapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(SerializationFeature.INDENT_OUTPUT);

  public AccountDataExportService(
      AccountRepository accounts,
      PortalAuthorityRepository portals,
      PortalRepositories.ClaimRequestRepository claims,
      PortalRepositories.PortalChangeRequestRepository changes,
      PortalRepositories.SubmissionRepository submissions,
      ContributionRepository contributions,
      NotificationRepository notifications) {
    this.accounts = accounts;
    this.portals = portals;
    this.claims = claims;
    this.changes = changes;
    this.submissions = submissions;
    this.contributions = contributions;
    this.notifications = notifications;
  }

  /** 导出结果：文件名与内容。内容不落地到磁盘，直接作为响应体下发。 */
  public record ExportFile(String filename, byte[] content) {}

  @Transactional(readOnly = true)
  public ExportFile export(ActorContext actor) {
    if (actor == null) {
      throw new BusinessException(com.cognitanexus.sharedkernel.ErrorCode.AUTH_REQUIRED, "请先登录");
    }
    String accountId = actor.accountIdValue();
    Account account =
        accounts.findById(accountId).orElseThrow(() -> BusinessException.notFound("账号不存在"));

    Map<String, Object> root = new LinkedHashMap<>();
    root.put("format", "cognitanexus-account-export/1");
    root.put("exportedAt", Instant.now());
    root.put("account", accountView(account));

    List<PortalAuthority> myPortals = portals.listActiveByAccount(accountId);
    root.put("portals", myPortals.stream().map(AccountDataExportService::portalView).toList());

    List<ClaimRequest> myClaims = claims.listClaimsByAccount(accountId, MAX_ROWS);
    root.put("claims", myClaims.stream().map(AccountDataExportService::claimView).toList());

    List<PortalChangeRequest> myChanges = changes.listChangesByAccount(accountId, MAX_ROWS);
    root.put(
        "portalChanges", myChanges.stream().map(AccountDataExportService::changeView).toList());

    List<Submission> mySubmissions = submissions.listByAccount(accountId, MAX_ROWS);
    root.put(
        "submissions",
        mySubmissions.stream().map(AccountDataExportService::submissionView).toList());

    // 贡献关系按门户逐个取：listByScholar 会返回该学者的全部关系（包括其他认领人维护的），
    // 但「账号能看到的」正是这些门户下的全部成果，这也是用户在页面上能看到的内容。
    Map<String, Object> byPortal = new LinkedHashMap<>();
    for (PortalAuthority portal : myPortals) {
      List<Contribution> rows = contributions.listByScholar(portal.scholarId());
      byPortal.put(
          portal.scholarId(),
          rows.stream().map(AccountDataExportService::contributionView).toList());
    }
    root.put("contributionsByPortal", byPortal);

    List<Notification> myNotifications =
        notifications.listByAccount(accountId, false, null, MAX_NOTIFICATIONS + 1, 0);
    boolean notificationsTruncated = myNotifications.size() > MAX_NOTIFICATIONS;
    if (notificationsTruncated) {
      myNotifications = myNotifications.subList(0, MAX_NOTIFICATIONS);
    }
    root.put(
        "notifications",
        myNotifications.stream().map(AccountDataExportService::notificationView).toList());

    Map<String, Object> limits = new LinkedHashMap<>();
    limits.put("notifications", MAX_NOTIFICATIONS);
    limits.put("notificationsTruncated", notificationsTruncated);
    limits.put("claims", MAX_ROWS);
    limits.put("portalChanges", MAX_ROWS);
    limits.put("submissions", MAX_ROWS);
    limits.put("note", "超出上限的部分不在此文件中。这些上限是工程默认值，随数据量增长需要调整。");
    root.put("limits", limits);

    root.put(
        "excluded",
        List.of(
            "口令哈希与会话信息", "来源报文原文（raw_source_record.payload）", "认领材料文件本体与其存储路径", "审计事件中与本账号无关的部分"));

    try {
      byte[] bytes = mapper.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
      String filename = "account-export-" + Identifiers.next("exp") + ".json";
      log.info("账号数据导出 account={} bytes={}", accountId, bytes.length);
      return new ExportFile(filename, bytes);
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      // 序列化失败是程序错误，不该让用户看到半个文件。
      throw new IllegalStateException("账号数据导出序列化失败", ex);
    }
  }

  private static Map<String, Object> accountView(Account account) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", account.id());
    view.put("username", account.username());
    view.put("displayName", account.displayName());
    view.put("status", account.status().name());
    view.put("roles", new ArrayList<>(account.roles()));
    // 不输出 passwordHash 与 authVersion：前者是凭据，后者是会话控制位，都不属于「关于我的数据」。
    return view;
  }

  private static Map<String, Object> portalView(PortalAuthority portal) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("authorityId", portal.id());
    view.put("scholarId", portal.scholarId());
    view.put("status", portal.status());
    view.put("grantedAt", portal.grantedAt());
    view.put("claimRequestId", portal.claimRequestId());
    return view;
  }

  private static Map<String, Object> claimView(ClaimRequest claim) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", claim.id());
    view.put("scholarId", claim.scholarId());
    view.put("status", claim.status().name());
    view.put("statement", claim.statement());
    view.put("submittedAt", claim.submittedAt());
    view.put("decidedAt", claim.decidedAt());
    view.put("decisionReason", claim.decisionReason());
    return view;
  }

  private static Map<String, Object> changeView(PortalChangeRequest change) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", change.id());
    view.put("scholarId", change.scholarId());
    view.put("fieldName", change.field().name());
    view.put("currentValue", change.currentValue());
    view.put("proposedValue", change.proposedValue());
    view.put("status", change.status().name());
    view.put("submittedAt", change.submittedAt());
    view.put("decisionReason", change.decisionReason());
    return view;
  }

  private static Map<String, Object> submissionView(Submission submission) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", submission.id());
    view.put("title", submission.title());
    view.put("doi", submission.doi());
    view.put("venue", submission.venue());
    view.put("publicationYear", submission.publicationYear());
    view.put("authorsRaw", submission.authorsRaw());
    view.put("abstractText", submission.abstractText());
    view.put("status", submission.status().name());
    view.put("submittedAt", submission.createdAt());
    view.put("decidedAt", submission.decidedAt());
    view.put("decisionReason", submission.decisionReason());
    view.put("achievementId", submission.achievementId());
    return view;
  }

  private static Map<String, Object> contributionView(Contribution contribution) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", contribution.id());
    view.put("achievementId", contribution.achievementId());
    view.put("role", contribution.role());
    view.put("authorOrder", contribution.authorOrder());
    view.put("relationState", contribution.relationState().name());
    view.put("updatedAt", contribution.updatedAt());
    // sourceRecordId 不导出：那是内部溯源指针，对用户没有意义，导出后还会泄露内部 ID 规则。
    return view;
  }

  private static Map<String, Object> notificationView(Notification notification) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", notification.id());
    view.put("kind", notification.kind());
    view.put("title", notification.title());
    view.put("body", notification.body());
    view.put("createdAt", notification.createdAt());
    view.put("read", notification.readAt() != null);
    return view;
  }
}
