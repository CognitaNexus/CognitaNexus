package com.cognitanexus.core.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.identity.domain.Account;
import com.cognitanexus.core.identity.domain.AccountStatus;
import com.cognitanexus.core.identity.infrastructure.ClosureMapper;
import com.cognitanexus.core.identity.infrastructure.ClosureMapper.ClosureRow;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.sharedkernel.AccountId;
import com.cognitanexus.sharedkernel.BusinessException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 账号注销申请的三条不变量。
 *
 * <p>1. <b>注销是停用而非删除</b>：执行时只调 disable，绝不存在删除账号的调用路径。
 * <p>2. <b>冷静期以服务端时钟为准</b>：SQL 返回 0 行时必须报出「冷静期未结束」，而不是静默成功。
 * <p>3. <b>不能审批自己</b>：管理员把自己注销掉，等于让平台少一个可追溯的裁决者。
 */
@ExtendWith(MockitoExtension.class)
class AccountClosureServiceTest {

  private static final String ADMIN_ID = "acc_admin";
  private static final Instant NOW = Instant.parse("2026-10-19T02:00:00Z");

  @Mock private ClosureMapper closures;
  @Mock private IdentityRepositories.AccountRepository accounts;
  @Mock private AuditRecorder audit;
  @Mock private PlatformTransactionManager txManager;

  private AccountClosureService service;
  private ActorContext admin;

  @BeforeEach
  void setUp() {
    service = new AccountClosureService(closures, accounts, audit, txManager);
    admin =
        new ActorContext(
            new AccountId(ADMIN_ID),
            "admin",
            "平台管理员",
            Set.of(RoleNames.PLATFORM_ADMIN, RoleNames.USER),
            1L);
  }

  private static ClosureRow row(String id, String accountId, String status, Instant effectiveAt) {
    return new ClosureRow(
        id,
        accountId,
        "someone",
        "个人原因",
        status,
        NOW.minus(1, ChronoUnit.DAYS),
        effectiveAt,
        null,
        null,
        null);
  }

  private static Account account(String id, AccountStatus status) {
    return new Account(
        id, "someone", "someone", "$2a$10$hash", "某人", status, 1L, Set.of(RoleNames.USER));
  }

  // ------------------------------------------------------------------ 申请

  @Test
  void requestRejectsWhenOpenRequestAlreadyExists() {
    when(closures.findOpenByAccount(ADMIN_ID))
        .thenReturn(Optional.of(row("clo_1", ADMIN_ID, "PENDING", NOW.plus(7, ChronoUnit.DAYS))));

    assertThatThrownBy(() -> service.request(admin, "不想用了"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("已有待处理");
    verify(closures, never()).insert(anyString(), anyString(), anyString(), anyInt());
  }

  @Test
  void requestRejectsDisabledAccount() {
    when(closures.findOpenByAccount(ADMIN_ID)).thenReturn(Optional.empty());
    when(accounts.findById(ADMIN_ID))
        .thenReturn(Optional.of(account(ADMIN_ID, AccountStatus.DISABLED)));

    // 已停用的账号再申请注销，只会生成一条永远无法执行的申请。
    assertThatThrownBy(() -> service.request(admin, "不想用了"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("已停用");
    verify(closures, never()).insert(anyString(), anyString(), anyString(), anyInt());
  }

  @Test
  void requestWritesCoolingDaysAndAudits() {
    when(closures.findOpenByAccount(ADMIN_ID)).thenReturn(Optional.empty());
    when(accounts.findById(ADMIN_ID))
        .thenReturn(Optional.of(account(ADMIN_ID, AccountStatus.ACTIVE)));
    when(closures.findById(anyString()))
        .thenReturn(Optional.of(row("clo_new", ADMIN_ID, "PENDING", NOW.plus(7, ChronoUnit.DAYS))));

    AccountClosureService.ClosureView view = service.request(admin, "  不想用了  ");

    assertThat(view.id()).isEqualTo("clo_new");
    // 冷静期天数由服务层传给 SQL（now() + interval），而不是在 Java 里算时间戳：
    // 应用层算时间会踩到 JVM 与数据库时区不一致的坑。
    verify(closures).insert(anyString(), eq(ADMIN_ID), eq("不想用了"), eq(7));
    verify(audit).record(any());
  }

  @Test
  void requestRequiresLogin() {
    assertThatThrownBy(() -> service.request(null, "理由")).isInstanceOf(BusinessException.class);
    verifyNoInteractions(closures, accounts);
  }

  // ------------------------------------------------------------------ 撤销

  @Test
  void cancelReportsAccessDeniedWhenNothingAffected() {
    when(closures.cancel("clo_1", ADMIN_ID)).thenReturn(0);

    // 不区分「不存在」与「不是你的」：ID 不可枚举，统一按无权限处理。
    assertThatThrownBy(() -> service.cancel(admin, "clo_1"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("没有可撤销");
    verify(audit, never()).record(any());
  }

  // ------------------------------------------------------------------ 裁决

  @Test
  void decideRejectsSelfApproval() {
    when(closures.findById("clo_self"))
        .thenReturn(Optional.of(row("clo_self", ADMIN_ID, "PENDING", NOW)));

    assertThatThrownBy(() -> service.decide(admin, "clo_self", "APPROVE", "自己批自己"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("自己的注销申请");
    verify(closures, never()).decide(anyString(), anyString(), anyString(), any());
  }

  @Test
  void decideRejectsNonPendingRequest() {
    when(closures.findById("clo_done"))
        .thenReturn(Optional.of(row("clo_done", "acc_other", "APPROVED", NOW)));

    // 重复裁决必须报冲突而不是「成功」：否则会重复写审计，让人以为批了两次。
    assertThatThrownBy(() -> service.decide(admin, "clo_done", "APPROVE", "重复点击"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("已被处理");
    verify(closures, never()).decide(anyString(), anyString(), anyString(), any());
  }

  @Test
  void decideRejectsUnknownDecision() {
    when(closures.findById("clo_1"))
        .thenReturn(Optional.of(row("clo_1", "acc_other", "PENDING", NOW)));

    assertThatThrownBy(() -> service.decide(admin, "clo_1", "MAYBE", "模棱两可"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("APPROVE");
    verify(closures, never()).decide(anyString(), anyString(), anyString(), any());
  }

  @Test
  void decideApproveDisablesAccountButNeverDeletes() {
    when(closures.findById("clo_1"))
        .thenReturn(
            Optional.of(row("clo_1", "acc_other", "PENDING", NOW.minus(1, ChronoUnit.DAYS))));
    when(closures.decide(eq("clo_1"), eq("APPROVED"), eq(ADMIN_ID), any())).thenReturn(1);
    when(accounts.disable("acc_other")).thenReturn(1);

    var result = service.decide(admin, "clo_1", "approve", "材料齐全");

    assertThat(result).containsEntry("status", "APPROVED").containsEntry("accountId", "acc_other");
    verify(accounts).disable("acc_other");
    // 仓储接口里根本没有「删除账号」的方法：注销不删数据不是靠调用方自觉，而是类型上就做不到。
    verifyNoMoreInteractions(accounts);
    verify(audit).record(any());
  }

  @Test
  void decideRejectKeepsAccountUsable() {
    when(closures.findById("clo_1"))
        .thenReturn(
            Optional.of(row("clo_1", "acc_other", "PENDING", NOW.minus(1, ChronoUnit.DAYS))));
    when(closures.decide(eq("clo_1"), eq("REJECTED"), eq(ADMIN_ID), any())).thenReturn(1);

    var result = service.decide(admin, "clo_1", "REJECT", "还有未结清的认领申请");

    assertThat(result).containsEntry("status", "REJECTED");
    verify(accounts, never()).disable(anyString());
  }

  @Test
  void decideExplainsCoolingPeriodWhenSqlAffectsNoRow() {
    when(closures.findById("clo_1"))
        .thenReturn(
            Optional.of(row("clo_1", "acc_other", "PENDING", NOW.plus(3, ChronoUnit.DAYS))));
    when(closures.decide(eq("clo_1"), eq("APPROVED"), eq(ADMIN_ID), any())).thenReturn(0);

    // 冷静期条件写在 SQL 里，应用层的时钟绕不过去；0 行必须翻译成具体原因。
    assertThatThrownBy(() -> service.decide(admin, "clo_1", "APPROVE", "提前执行"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("冷静期");
    verify(accounts, never()).disable(anyString());
  }

  @Test
  void pendingRequiresAccountCapability() {
    ActorContext plainUser =
        new ActorContext(new AccountId("acc_user"), "user", "普通用户", Set.of(RoleNames.USER), 1L);

    assertThatThrownBy(() -> service.pending(plainUser, 20, 0))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(closures);
  }

  @Test
  void pendingCapsLimitAndOffset() {
    when(closures.listPending(100, 0)).thenReturn(List.of());

    assertThat(service.pending(admin, 5000, -10)).isEmpty();
    verify(closures).listPending(100, 0);
  }

  // ---------------------------------------------------------- 冷静期届满自动执行

  /**
   * 自动执行走 TransactionTemplate，必须让它真的把回调跑起来。
   *
   * <p>只在用到它的用例里开，不在 {@code setUp} 里统一 stub：Mockito 严格模式会把未被使用的
   * 桩判为失败，而这 4 个之外的用例都不碰事务。
   */
  private void enableTransactions() {
    when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
  }

  @Test
  void executeDueStopsAccountAndAuditsAsSystem() {
    enableTransactions();
    when(closures.listDue(AccountClosureService.MAX_AUTO_BATCH))
        .thenReturn(List.of(row("clo_1", "acc_other", "PENDING", NOW.minus(1, ChronoUnit.HOURS))));
    when(closures.decide(eq("clo_1"), eq("APPROVED"), eq(null), any())).thenReturn(1);
    when(accounts.disable("acc_other")).thenReturn(1);

    assertThat(service.executeDue(AccountClosureService.MAX_AUTO_BATCH)).isEqualTo(1);

    verify(accounts).disable("acc_other");
    // 与人工裁决同一条执行路径：仓储接口里没有「删除账号」的方法，自动执行也删不掉数据。
    // 没有人类裁决人时 decided_by 必须为空，审计主体是 SYSTEM；伪造一个管理员账号才是问题。
    verify(closures).decide(eq("clo_1"), eq("APPROVED"), eq(null), any());
    verify(audit).record(any());
  }

  @Test
  void executeDueSkipsRowTakenByAnotherProcess() {
    enableTransactions();
    when(closures.listDue(AccountClosureService.MAX_AUTO_BATCH))
        .thenReturn(List.of(row("clo_1", "acc_other", "PENDING", NOW.minus(1, ChronoUnit.HOURS))));
    // 另一个实例（或管理员刚裁决）已经把这行改掉：受影响 0 行，不得再停用一次。
    when(closures.decide(eq("clo_1"), eq("APPROVED"), eq(null), any())).thenReturn(0);

    assertThat(service.executeDue(AccountClosureService.MAX_AUTO_BATCH)).isZero();

    verify(accounts, never()).disable(anyString());
    verifyNoInteractions(audit);
  }

  @Test
  void executeDueIsolatesFailuresWithinBatch() {
    enableTransactions();
    ClosureRow first = row("clo_1", "acc_a", "PENDING", NOW.minus(2, ChronoUnit.HOURS));
    ClosureRow second = row("clo_2", "acc_b", "PENDING", NOW.minus(1, ChronoUnit.HOURS));
    when(closures.listDue(AccountClosureService.MAX_AUTO_BATCH)).thenReturn(List.of(first, second));
    when(closures.decide(eq("clo_1"), eq("APPROVED"), eq(null), any()))
        .thenThrow(new IllegalStateException("数据库瞬断"));
    when(closures.decide(eq("clo_2"), eq("APPROVED"), eq(null), any())).thenReturn(1);
    when(accounts.disable("acc_b")).thenReturn(1);

    // 第一条失败不能连累第二条：否则一个坏账号会让整批注销永远卡住。
    assertThat(service.executeDue(AccountClosureService.MAX_AUTO_BATCH)).isEqualTo(1);

    verify(accounts, never()).disable("acc_a");
    verify(accounts).disable("acc_b");
  }

  @Test
  void executeDueCapsBatchSize() {
    when(closures.listDue(AccountClosureService.MAX_AUTO_BATCH)).thenReturn(List.of());

    service.executeDue(9999);

    verify(closures).listDue(AccountClosureService.MAX_AUTO_BATCH);
    verify(closures, times(1)).listDue(anyInt());
  }
}
