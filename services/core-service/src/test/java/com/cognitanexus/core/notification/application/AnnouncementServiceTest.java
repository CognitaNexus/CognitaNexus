package com.cognitanexus.core.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.identity.application.IdentityRepositories;
import com.cognitanexus.core.notification.domain.Notification;
import com.cognitanexus.core.notification.infrastructure.AnnouncementMapper;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.sharedkernel.BusinessException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 系统公告的发布规则。
 *
 * <p>这一组用例守的不是「接口返回 200」，而是三条一旦破坏就无法补救的规则：<b>公告发出无法撤回</b>、
 * <b>不能只让一部分人收到</b>、<b>双击不能变成两条</b>。因此断言大多落在「有没有发生写入」上，
 * 而不是返回体的字段。
 */
@ExtendWith(MockitoExtension.class)
class AnnouncementServiceTest {

  private static final String ADMIN_ID = "acc_admin";

  @Mock private AnnouncementMapper announcements;
  @Mock private NotificationService notifications;
  @Mock private IdentityRepositories.AccountRepository accounts;
  @Mock private AuditRecorder audit;

  private AnnouncementService service;
  private ActorContext admin;

  @BeforeEach
  void setUp() {
    service = new AnnouncementService(announcements, notifications, accounts, audit);
    admin =
        new ActorContext(
            new com.cognitanexus.sharedkernel.AccountId(ADMIN_ID),
            "admin",
            "平台管理员",
            Set.of(RoleNames.PLATFORM_ADMIN, RoleNames.USER),
            1L);
  }

  @Test
  void publishFansOutOneNotificationPerActiveAccount() {
    when(announcements.countRecentByTitle("系统维护", 60)).thenReturn(0L);
    when(accounts.listActiveAccountIds(20001)).thenReturn(List.of("acc_1", "acc_2"));

    AnnouncementService.PublishResult result =
        service.publish(admin, "系统维护", "今晚 22:00 起停机一小时", "info");

    assertThat(result.recipients()).isEqualTo(2);
    // 正文被复制进每条通知：之后改公告表不会改写已经收到的内容。
    verify(notifications, times(2))
        .send(
            anyString(),
            eq(Notification.KIND_SYSTEM_ANNOUNCEMENT),
            eq("系统维护"),
            eq("今晚 22:00 起停机一小时"),
            eq("ANNOUNCEMENT"),
            anyString());
    verify(announcements)
        .insert(anyString(), eq("系统维护"), eq("今晚 22:00 起停机一小时"), eq("INFO"), eq(ADMIN_ID), eq(2));
    verify(audit).record(any());
  }

  @Test
  void publishRejectsDuplicateTitleWithinWindow() {
    when(announcements.countRecentByTitle("系统维护", 60)).thenReturn(1L);

    assertThatThrownBy(() -> service.publish(admin, "系统维护", "正文", null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("60 秒");

    // 拦在写入之前才算拦截：晚一步就是全体账号多收一条无法撤回的通知。
    verify(notifications, never())
        .send(anyString(), anyString(), anyString(), anyString(), any(), any());
    verify(announcements, never())
        .insert(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void publishRejectsBlankTitleWithoutTouchingStorage() {
    assertThatThrownBy(() -> service.publish(admin, "   ", "正文", null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("标题");
    verifyNoInteractions(announcements, notifications);
  }

  @Test
  void publishRejectsUnknownLevel() {
    assertThatThrownBy(() -> service.publish(admin, "标题", "正文", "URGENT"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("INFO");
    verifyNoInteractions(announcements, notifications);
  }

  @Test
  void publishRejectsWhenRecipientsExceedCap() {
    when(announcements.countRecentByTitle("标题", 60)).thenReturn(0L);
    // 多取一条就是为了发现越界：截断会让「一部分账号收到公告」变成既成事实。
    String[] oversized = new String[20001];
    for (int i = 0; i < oversized.length; i++) {
      oversized[i] = "acc_" + i;
    }
    when(accounts.listActiveAccountIds(20001)).thenReturn(List.of(oversized));

    assertThatThrownBy(() -> service.publish(admin, "标题", "正文", null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("上限");
    verify(notifications, never())
        .send(anyString(), anyString(), anyString(), anyString(), any(), any());
  }

  @Test
  void publishRejectsWhenNobodyWouldReceive() {
    when(announcements.countRecentByTitle("标题", 60)).thenReturn(0L);
    when(accounts.listActiveAccountIds(20001)).thenReturn(List.of());

    // 没有收件人的公告不是成功：写进台账会变成一条「发过但无人收到」的记录。
    assertThatThrownBy(() -> service.publish(admin, "标题", "正文", null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("没有启用账号");
    verify(announcements, never())
        .insert(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void publishRequiresGovernanceCapability() {
    ActorContext plainUser =
        new ActorContext(
            new com.cognitanexus.sharedkernel.AccountId("acc_user"),
            "user",
            "普通用户",
            Set.of(RoleNames.USER),
            1L);

    assertThatThrownBy(() -> service.publish(plainUser, "标题", "正文", null))
        .isInstanceOf(BusinessException.class);
    // 普通用户连重复检查都不该触发。
    verifyNoInteractions(announcements, notifications);
  }

  @Test
  void listCapsLimitAtHundred() {
    when(announcements.list(100)).thenReturn(List.of());

    assertThat(service.list(admin, 500)).isEmpty();
    verify(announcements).list(100);
  }
}
