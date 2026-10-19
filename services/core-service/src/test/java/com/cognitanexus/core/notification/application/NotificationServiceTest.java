package com.cognitanexus.core.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.notification.domain.Notification;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.sharedkernel.AccountId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 站内通知写入与读取的边界（ADR-003 §6 验收判据的服务层部分）。
 *
 * <p>真实库行为（事务回滚通知一起消失、部分索引、越权 0 行）由接口级自检覆盖；
 * 这里钉的是不依赖数据库的判断逻辑。
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

  @Mock NotificationRepositories.NotificationRepository repository;
  @Mock NotificationRepositories.ScholarClaimantResolver claimants;

  private NotificationService service;

  @BeforeEach
  void setUp() {
    service = new NotificationService(repository, claimants);
  }

  @Test
  void sendRequiresTitleAndBody() {
    assertThatThrownBy(
            () ->
                service.send("acc_1", Notification.KIND_ACHIEVEMENT_MERGED, "  ", "内容", null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                service.send(
                    "acc_1", Notification.KIND_ACHIEVEMENT_MERGED, "标题", "   ", null, null))
        .isInstanceOf(IllegalArgumentException.class);
    // MANDATORY 传播（发送必须跟随业务事务）由 Spring 代理在装配层强制；直调实例不经过代理，
    // 该约束由接口自检覆盖，本测试不重复验证代理行为。
  }

  @Test
  void sendRejectsOversizedBody() {
    // 2000 字上限是防呆：谁往通知里塞申请陈述原文，会在测试期暴露而不是上线后。
    String body = "x".repeat(2001);
    assertThatThrownBy(
            () ->
                service.send("acc_1", Notification.KIND_ACHIEVEMENT_MERGED, "标题", body, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("2000");
  }

  @Test
  void sendToScholarClaimantsDeduplicatesAccounts() {
    // 同一账号管理两个学者档案，一次合并两端都涉及：只发一条，不是两条轰炸（ADR-003 §3）。
    when(claimants.resolveActiveAccountIds(anyCollection()))
        .thenReturn(List.of("acc_a", "acc_a", "acc_b"));

    int sent =
        service.sendToScholarClaimants(
            List.of("sch_1", "sch_2"),
            Notification.KIND_ACHIEVEMENT_MERGED,
            "有成果被合并",
            "详情",
            "ACHIEVEMENT",
            "ach_1");

    assertThat(sent).isEqualTo(2);
    ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
    verify(repository, org.mockito.Mockito.times(2)).insert(captor.capture());
    assertThat(captor.getAllValues())
        .allSatisfy(n -> assertThat(n.readAt()).isNull())
        .extracting(Notification::accountId)
        .containsExactlyInAnyOrder("acc_a", "acc_b");
  }

  @Test
  void sendToUnclaimedScholarsIsSilentlyZero() {
    // 学者无人认领时「没有接收者」不是错误（ADR-003 §2），一条都不写。
    when(claimants.resolveActiveAccountIds(anyCollection())).thenReturn(List.of());

    int sent =
        service.sendToScholarClaimants(
            List.of("sch_nobody"), Notification.KIND_RELATION_DECIDED, "标题", "内容", null, null);

    assertThat(sent).isZero();
    verify(repository, never()).insert(any());
  }

  @Test
  void readOperationsRequireLogin() {
    assertThatThrownBy(() -> service.list(null, false, null, 20, 0))
        .isInstanceOf(com.cognitanexus.sharedkernel.BusinessException.class);
    assertThatThrownBy(() -> service.unreadCount(null))
        .isInstanceOf(com.cognitanexus.sharedkernel.BusinessException.class);
    // 未登录时连「标记已读」都不该触碰仓储。
    verify(repository, never())
        .listByAccount(
            any(),
            anyBoolean(),
            any(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt());
    verify(repository, never()).countUnread(any());
  }

  @Test
  void markReadPassesOwnerScopeAndCap() {
    ActorContext actor = new ActorContext(new AccountId("acc_1"), "u1", "用户一", Set.of(), 1L);
    service.markRead(actor, List.of("ntf_1", "ntf_1", "ntf_2"));
    // 重复 id 去重后再交给仓储；账号与上限由服务层保证。
    verify(repository)
        .markRead("acc_1", new java.util.LinkedHashSet<>(List.of("ntf_1", "ntf_2")), 200);
  }

  @Test
  void markReadWithEmptyIdsSkipsRepository() {
    ActorContext actor = new ActorContext(new AccountId("acc_1"), "u1", "用户一", Set.of(), 1L);
    assertThat(service.markRead(actor, List.of())).isZero();
    assertThat(service.markRead(actor, null)).isZero();
    verify(repository, never())
        .markRead(any(), anyCollection(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void listDelegatesWithCappedPaging() {
    ActorContext actor = new ActorContext(new AccountId("acc_1"), "u1", "用户一", Set.of(), 1L);
    lenient().when(repository.listByAccount("acc_1", true, null, 100, 0)).thenReturn(List.of());
    service.list(actor, true, null, 500, -5);
    // limit 收敛到上限 100、offset 不接受负数。
    verify(repository).listByAccount("acc_1", true, null, 100, 0);
  }

  @Test
  void listNormalizesKindFilter() {
    ActorContext actor = new ActorContext(new AccountId("acc_1"), "u1", "用户一", Set.of(), 1L);
    lenient()
        .when(repository.listByAccount("acc_1", false, "SUBMISSION_DECIDED", 20, 0))
        .thenReturn(List.of());
    service.list(actor, false, "  submission_decided  ", 20, 0);
    // 类型过滤器大小写不敏感：前端传小写也要命中，否则筛选会静默失效。
    verify(repository).listByAccount("acc_1", false, "SUBMISSION_DECIDED", 20, 0);
  }

  @Test
  void markAllReadDelegatesForCurrentAccountOnly() {
    ActorContext actor = new ActorContext(new AccountId("acc_1"), "u1", "用户一", Set.of(), 1L);
    when(repository.markAllRead("acc_1")).thenReturn(7);
    assertThat(service.markAllRead(actor)).isEqualTo(7);
    verify(repository).markAllRead("acc_1");
  }

  @Test
  void archiveWithEmptyIdsSkipsRepository() {
    ActorContext actor = new ActorContext(new AccountId("acc_1"), "u1", "用户一", Set.of(), 1L);
    assertThat(service.archive(actor, List.of())).isZero();
    assertThat(service.archive(actor, null)).isZero();
    verify(repository, never())
        .archive(any(), anyCollection(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void archiveDelegatesWithAccountScopeAndCap() {
    ActorContext actor = new ActorContext(new AccountId("acc_1"), "u1", "用户一", Set.of(), 1L);
    when(repository.archive(eq("acc_1"), anyCollection(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(2);
    assertThat(service.archive(actor, List.of("n1", "n2"))).isEqualTo(2);
    verify(repository).archive(eq("acc_1"), anyCollection(), org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void kindsRequireActor() {
    assertThatThrownBy(() -> service.kinds(null))
        .isInstanceOf(com.cognitanexus.sharedkernel.BusinessException.class)
        .extracting(ex -> ((com.cognitanexus.sharedkernel.BusinessException) ex).errorCode())
        .isEqualTo(com.cognitanexus.sharedkernel.ErrorCode.AUTH_REQUIRED);
  }
}
