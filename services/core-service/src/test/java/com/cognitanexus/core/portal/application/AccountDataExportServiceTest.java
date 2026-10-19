package com.cognitanexus.core.portal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.catalog.application.CatalogRepositories.ContributionRepository;
import com.cognitanexus.core.identity.application.IdentityRepositories.AccountRepository;
import com.cognitanexus.core.identity.application.IdentityRepositories.PortalAuthorityRepository;
import com.cognitanexus.core.identity.domain.Account;
import com.cognitanexus.core.identity.domain.AccountStatus;
import com.cognitanexus.core.notification.application.NotificationRepositories.NotificationRepository;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.portal.application.PortalRepositories.ClaimRequestRepository;
import com.cognitanexus.core.portal.application.PortalRepositories.PortalChangeRequestRepository;
import com.cognitanexus.core.portal.application.PortalRepositories.SubmissionRepository;
import com.cognitanexus.sharedkernel.AccountId;
import com.cognitanexus.sharedkernel.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 账号数据导出。
 *
 * <p>这份文件会被用户下载、转存、发给第三方，所以这一组用例只关心两件事：
 * <b>必须包含什么</b>（本人的门户、认领、提交、通知）与<b>绝不能包含什么</b>
 * （口令哈希、会话版本、来源报文原文）。后者一旦漏出去，一次导出就等于一次凭据泄露。
 */
@ExtendWith(MockitoExtension.class)
class AccountDataExportServiceTest {

  private static final String ACCOUNT_ID = "acc_1";

  @Mock private AccountRepository accounts;
  @Mock private PortalAuthorityRepository portals;
  @Mock private ClaimRequestRepository claims;
  @Mock private PortalChangeRequestRepository changes;
  @Mock private SubmissionRepository submissions;
  @Mock private ContributionRepository contributions;
  @Mock private NotificationRepository notifications;

  private AccountDataExportService service;
  private ActorContext actor;

  @BeforeEach
  void setUp() {
    service =
        new AccountDataExportService(
            accounts, portals, claims, changes, submissions, contributions, notifications);
    actor = new ActorContext(new AccountId(ACCOUNT_ID), "u1", "用户一", Set.of(RoleNames.USER), 1L);
  }

  private void stubEmptyEverything() {
    when(accounts.findById(ACCOUNT_ID))
        .thenReturn(
            Optional.of(
                new Account(
                    ACCOUNT_ID,
                    "zhangwei",
                    "zhangwei",
                    "$2a$10$super-secret-hash",
                    "张伟",
                    AccountStatus.ACTIVE,
                    7L,
                    Set.of(RoleNames.USER))));
    when(portals.listActiveByAccount(ACCOUNT_ID)).thenReturn(List.of());
    when(claims.listClaimsByAccount(ACCOUNT_ID, 100)).thenReturn(List.of());
    when(changes.listChangesByAccount(ACCOUNT_ID, 100)).thenReturn(List.of());
    when(submissions.listByAccount(ACCOUNT_ID, 100)).thenReturn(List.of());
    when(notifications.listByAccount(ACCOUNT_ID, false, null, 501, 0)).thenReturn(List.of());
  }

  @Test
  void exportNeverContainsCredentialOrSessionFields() {
    stubEmptyEverything();

    AccountDataExportService.ExportFile file = service.export(actor);
    String json = new String(file.content(), StandardCharsets.UTF_8);

    assertThat(json).contains("cognitanexus-account-export/1");
    assertThat(json).contains("zhangwei");
    // 三样东西不能出现在「我的数据」里：凭据、会话控制位、来源报文原文。
    assertThat(json).doesNotContain("passwordHash");
    assertThat(json).doesNotContain("super-secret-hash");
    assertThat(json).doesNotContain("authVersion");
    assertThat(json).doesNotContain("sourceRecordId");
    assertThat(file.filename()).startsWith("account-export-").endsWith(".json");
  }

  @Test
  void exportDeclaresItsLimitsAndExclusions() {
    stubEmptyEverything();

    String json = new String(service.export(actor).content(), StandardCharsets.UTF_8);

    // 上限必须写进文件本身：拿到文件的人要知道这份不是全量，而不是自己猜。
    assertThat(json).contains("\"notificationsTruncated\"").contains("\"limits\"");
    assertThat(json).contains("不在此文件中");
    assertThat(json).contains("口令哈希与会话信息");
  }

  @Test
  void notificationsAreReadWithCapPlusOne() {
    stubEmptyEverything();

    service.export(actor);

    // 多取一条用于判断是否截断：否则「刚好 500 条」与「有 800 条但只给了 500」无法区分。
    verify(notifications).listByAccount(ACCOUNT_ID, false, null, 501, 0);
  }

  @Test
  void exportRequiresLogin() {
    assertThatThrownBy(() -> service.export(null)).isInstanceOf(BusinessException.class);
  }

  @Test
  void exportFailsWhenAccountRowIsGone() {
    when(accounts.findById(anyString())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.export(actor))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("账号不存在");
  }
}
