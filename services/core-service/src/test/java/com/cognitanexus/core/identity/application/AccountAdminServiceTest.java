package com.cognitanexus.core.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.identity.domain.Account;
import com.cognitanexus.core.identity.domain.AccountStatus;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.sharedkernel.AccountId;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 账号角色授予与撤销。
 *
 * <p>锁住三条具体缺陷，都是「授权」这条路径上最容易只做一半的地方：
 *
 * <ul>
 *   <li>不改认证版本：会话里的角色是登录那一刻的快照，数据库改了而会话没失效，结果是「已授权」
 *       只存在于库里，对方仍在按旧角色鉴权（或反过来，撤销后仍能操作）。
 *   <li>没有撤销入口：此前只有授予，撤错一次只能直接改库，而改库不留审计。
 *   <li>撤自己的角色：把人锁在门外，且没有第二条恢复路径。
 * </ul>
 *
 * <p>本类只验证应用层的判断与副作用调用顺序，不验证 SQL；SQL 的 affected==0 语义由
 * 这里的「受影响行数不是 1 就冲突」模拟。
 */
@ExtendWith(MockitoExtension.class)
class AccountAdminServiceTest {

  private static final String ADMIN_ID = "acc-admin";
  private static final String TARGET_ID = "acc-target";

  @Mock IdentityRepositories.AccountRepository accounts;
  @Mock AuthService authService;
  @Mock AuditRecorder audit;

  private AccountAdminService service;
  private ActorContext admin;

  @BeforeEach
  void setUp() {
    service = new AccountAdminService(accounts, authService, audit);
    admin =
        new ActorContext(
            new AccountId(ADMIN_ID),
            "admin",
            "平台管理员",
            Set.of(RoleNames.PLATFORM_ADMIN, RoleNames.USER),
            1L);
  }

  private static Account account(String id, long authVersion, String... roles) {
    return new Account(
        id,
        "someone",
        "someone",
        "$2a$10$hash",
        "某人",
        AccountStatus.ACTIVE,
        authVersion,
        Set.of(roles));
  }

  // ---------- 授予 ----------

  @Test
  @DisplayName("授予角色后提升认证版本，并在审计中标注会话已失效")
  void grantRoleBumpsAuthVersion() {
    when(accounts.findById(TARGET_ID))
        .thenReturn(
            Optional.of(account(TARGET_ID, 3L, RoleNames.USER)),
            Optional.of(account(TARGET_ID, 4L, RoleNames.USER, RoleNames.REVIEWER)));

    service.grantRole(admin, TARGET_ID, RoleNames.REVIEWER);

    verify(accounts).grantRole(TARGET_ID, RoleNames.REVIEWER);
    // 不提升认证版本的授予等于「下次登录才生效」，这条断言就是防它。
    verify(accounts).bumpAuthVersion(TARGET_ID);

    ArgumentCaptor<AuditRecorder.AuditEntry> captor =
        ArgumentCaptor.forClass(AuditRecorder.AuditEntry.class);
    verify(audit).record(captor.capture());
    assertThat(captor.getValue().action()).isEqualTo("ACCOUNT_ROLE_GRANTED");
    assertThat(captor.getValue().afterValue()).containsEntry("sessionsInvalidated", true);
  }

  @Test
  @DisplayName("已有该角色时重复授予既不写库也不提升版本")
  void grantRoleIsIdempotent() {
    when(accounts.findById(TARGET_ID))
        .thenReturn(Optional.of(account(TARGET_ID, 3L, RoleNames.USER, RoleNames.REVIEWER)));

    service.grantRole(admin, TARGET_ID, RoleNames.REVIEWER);

    verify(accounts, never()).grantRole(any(), any());
    verify(accounts, never()).bumpAuthVersion(any());
    // 重复授予不该产生审计噪音，否则审计表里会出现「授予了本来就有角色」的假记录。
    verifyNoInteractions(audit);
  }

  @Test
  @DisplayName("授予不支持的角色名直接拒绝，不写库")
  void grantRoleRejectsUnknownRole() {
    assertThatThrownBy(() -> service.grantRole(admin, TARGET_ID, RoleNames.SYSTEM))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.INVALID_ARGUMENT);
    verifyNoInteractions(accounts, audit);
  }

  // ---------- 撤销 ----------

  @Test
  @DisplayName("撤销角色成功：删行、提升认证版本、审计记录原因")
  void revokeRoleBumpsAuthVersionAndAudits() {
    when(accounts.findById(TARGET_ID))
        .thenReturn(
            Optional.of(account(TARGET_ID, 5L, RoleNames.USER, RoleNames.REVIEWER)),
            Optional.of(account(TARGET_ID, 6L, RoleNames.USER)));
    when(accounts.revokeRole(TARGET_ID, RoleNames.REVIEWER)).thenReturn(1);

    service.revokeRole(admin, TARGET_ID, RoleNames.REVIEWER, "误授，撤销");

    verify(accounts).revokeRole(TARGET_ID, RoleNames.REVIEWER);
    // 撤销必须让既有会话失效，否则对方当前会话仍带着 REVIEWER 继续操作。
    verify(accounts).bumpAuthVersion(TARGET_ID);

    ArgumentCaptor<AuditRecorder.AuditEntry> captor =
        ArgumentCaptor.forClass(AuditRecorder.AuditEntry.class);
    verify(audit).record(captor.capture());
    AuditRecorder.AuditEntry entry = captor.getValue();
    assertThat(entry.action()).isEqualTo("ACCOUNT_ROLE_REVOKED");
    assertThat(entry.targetId()).isEqualTo(TARGET_ID);
    assertThat(entry.reason()).isEqualTo("误授，撤销");
    assertThat(entry.beforeValue().get("roles"))
        .asList()
        .containsExactlyInAnyOrder(RoleNames.USER, RoleNames.REVIEWER);
    assertThat(entry.afterValue()).containsEntry("role", RoleNames.REVIEWER);
  }

  @Test
  @DisplayName("不能撤销自己的角色，避免把自己锁在门外")
  void revokeRoleRejectsSelf() {
    assertThatThrownBy(() -> service.revokeRole(admin, ADMIN_ID, RoleNames.PLATFORM_ADMIN, "手滑"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("当前登录账号");
    verifyNoInteractions(audit);
  }

  @Test
  @DisplayName("USER 是基础身份，不可撤销；禁止登录应走停用")
  void revokeRoleRejectsUserRole() {
    assertThatThrownBy(() -> service.revokeRole(admin, TARGET_ID, RoleNames.USER, "清退"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION);
    verifyNoInteractions(accounts, audit);
  }

  @Test
  @DisplayName("撤销理由必填，否则无法在审计里解释这次权限收紧")
  void revokeRoleRequiresReason() {
    assertThatThrownBy(() -> service.revokeRole(admin, TARGET_ID, RoleNames.REVIEWER, "  "))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.INVALID_ARGUMENT);
    verifyNoInteractions(accounts, audit);
  }

  @Test
  @DisplayName("账号本来没有该角色时按版本冲突拒绝，且不提升认证版本")
  void revokeRoleRejectsMissingRole() {
    when(accounts.findById(TARGET_ID))
        .thenReturn(Optional.of(account(TARGET_ID, 7L, RoleNames.USER)));

    assertThatThrownBy(() -> service.revokeRole(admin, TARGET_ID, RoleNames.REVIEWER, "撤销"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.VERSION_CONFLICT);

    // 关键：什么都没改就不该提升认证版本，否则等于无故把对方踢下线。
    verify(accounts, never()).bumpAuthVersion(any());
    verify(accounts, never()).revokeRole(any(), any());
    verifyNoInteractions(audit);
  }

  @Test
  @DisplayName("删行未命中（并发已被他人撤销）按版本冲突处理，不写审计")
  void revokeRoleRejectsConcurrentRemoval() {
    when(accounts.findById(TARGET_ID))
        .thenReturn(Optional.of(account(TARGET_ID, 7L, RoleNames.USER, RoleNames.REVIEWER)));
    when(accounts.revokeRole(TARGET_ID, RoleNames.REVIEWER)).thenReturn(0);

    assertThatThrownBy(() -> service.revokeRole(admin, TARGET_ID, RoleNames.REVIEWER, "撤销"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.VERSION_CONFLICT);
    verify(accounts, never()).bumpAuthVersion(any());
    verifyNoInteractions(audit);
  }

  // ---------- 权限 ----------

  @Test
  @DisplayName("没有账号管理能力的人不能授予也不能撤销角色")
  void roleChangesRequireAccountManageCapability() {
    ActorContext plainUser =
        new ActorContext(new AccountId("acc-other"), "user", "普通用户", Set.of(RoleNames.USER), 1L);

    assertThatThrownBy(() -> service.grantRole(plainUser, TARGET_ID, RoleNames.REVIEWER))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.ACCESS_DENIED);
    assertThatThrownBy(() -> service.revokeRole(plainUser, TARGET_ID, RoleNames.REVIEWER, "撤销"))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.ACCESS_DENIED);

    verifyNoInteractions(accounts, audit);
  }

  @Test
  @DisplayName("审计人身份来自认证上下文，不能由请求参数指定")
  void auditActorComesFromContext() {
    when(accounts.findById(TARGET_ID))
        .thenReturn(
            Optional.of(account(TARGET_ID, 5L, RoleNames.USER, RoleNames.REVIEWER)),
            Optional.of(account(TARGET_ID, 6L, RoleNames.USER)));
    when(accounts.revokeRole(TARGET_ID, RoleNames.REVIEWER)).thenReturn(1);

    service.revokeRole(admin, TARGET_ID, RoleNames.REVIEWER, "撤销");

    ArgumentCaptor<AuditRecorder.AuditEntry> captor =
        ArgumentCaptor.forClass(AuditRecorder.AuditEntry.class);
    verify(audit).record(captor.capture());
    assertThat(captor.getValue().actorAccountId()).isEqualTo(ADMIN_ID);
    assertThat(captor.getValue().actorUsername()).isEqualTo("admin");
  }

  // ---------- 停用 ----------

  @Test
  @DisplayName("不能停用当前登录账号")
  void disableRejectsSelf() {
    assertThatThrownBy(() -> service.disable(admin, ADMIN_ID, "测试"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("当前登录账号");
    verifyNoInteractions(authService);
  }

  @Test
  @DisplayName("停用理由必填")
  void disableRequiresReason() {
    assertThatThrownBy(() -> service.disable(admin, TARGET_ID, null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.INVALID_ARGUMENT);
    verifyNoInteractions(authService, audit);
  }

  @Test
  @DisplayName("停用通过认证服务完成，停用与版本提升是同一件事")
  void disableDelegatesToAuthService() {
    when(accounts.findById(TARGET_ID))
        .thenReturn(Optional.of(account(TARGET_ID, 9L, RoleNames.USER)));

    service.disable(admin, TARGET_ID, "违规");

    // 停用不删学者档案，只让登录与受保护请求失效（AC-IMPL-FR04-02），因此这里只应调用认证服务。
    verify(authService).disableAccount(TARGET_ID, admin, "违规");
    verify(accounts, never()).revokeRole(any(), any());
    verifyNoInteractions(audit);
  }
}
