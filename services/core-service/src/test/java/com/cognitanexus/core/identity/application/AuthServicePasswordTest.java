package com.cognitanexus.core.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.identity.domain.AccountCredentials;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 口令自助变更与管理员重置。
 *
 * <p>这一组用例守的是「没有邮件通道时，账号还能不能自救」：
 *
 * <ul>
 *   <li>改密后必须提升 authVersion，否则旧会话在改密之后仍可用——改密就成了纯装饰；
 *   <li>但提升后当前会话必须被续期，否则用户被自己的操作登出（这条在控制器，不在本类）；
 *   <li>管理员重置必须留下「需修改」标记与审计，且审计里绝不能出现口令；
 *   <li>重置自己必须被拒：那是把自己唯一的入口换成一个只显示过一次的串。
 * </ul>
 *
 * <p>口令哈希用真实的 BCrypt（而非 mock）：本类要验证「新旧口令相同」这条判断，
 * mock 的 matches 只能验证调用次数，验证不了这条业务规则是否真的成立。
 */
@ExtendWith(MockitoExtension.class)
class AuthServicePasswordTest {

  private static final String ACCOUNT_ID = "acc-1";
  private static final String USERNAME = "someone";
  private static final String CURRENT = "OldPassw0rd!";
  private static final String NEXT = "NewPassw0rd!";

  @Mock IdentityRepositories.AccountRepository accounts;
  @Mock IdentityRepositories.SessionRevocationRepository revocations;
  @Mock LoginThrottle throttle;
  @Mock LoginStateUpdater loginStateUpdater;
  @Mock AuditRecorder audit;

  private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
  private final PasswordPolicy policy = new PasswordPolicy();
  private AuthService service;

  @BeforeEach
  void setUp() {
    service =
        new AuthService(accounts, revocations, encoder, policy, throttle, loginStateUpdater, audit);
  }

  private AccountCredentials credentials(String passwordHash, AccountStatus status) {
    return new AccountCredentials(
        ACCOUNT_ID,
        USERNAME,
        USERNAME,
        passwordHash,
        "某人",
        status,
        3L,
        Set.of(RoleNames.USER),
        0,
        null,
        false);
  }

  private ActorContext user() {
    return new ActorContext(new AccountId(ACCOUNT_ID), USERNAME, "某人", Set.of(RoleNames.USER), 3L);
  }

  private ActorContext admin() {
    return new ActorContext(
        new AccountId("acc-admin"),
        "admin",
        "平台管理员",
        Set.of(RoleNames.PLATFORM_ADMIN, RoleNames.USER),
        1L);
  }

  @Test
  @DisplayName("本人改密：写入新哈希、清除需改标记、authVersion 加一")
  void changesPasswordAndBumpsVersion() {
    when(accounts.findCredentialsById(ACCOUNT_ID))
        .thenReturn(Optional.of(credentials(encoder.encode(CURRENT), AccountStatus.ACTIVE)));
    when(accounts.updatePassword(eq(ACCOUNT_ID), anyString(), eq(false))).thenReturn(1);

    long newVersion = service.changePassword(user(), CURRENT, NEXT);

    assertThat(newVersion).isEqualTo(4L);
    ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
    verify(accounts).updatePassword(eq(ACCOUNT_ID), hash.capture(), eq(false));
    assertThat(encoder.matches(NEXT, hash.getValue())).isTrue();
    assertThat(encoder.matches(CURRENT, hash.getValue())).isFalse();
  }

  @Test
  @DisplayName("当前口令不正确：不写库，且如实说明是哪一项错")
  void rejectsWrongCurrentPassword() {
    when(accounts.findCredentialsById(ACCOUNT_ID))
        .thenReturn(Optional.of(credentials(encoder.encode(CURRENT), AccountStatus.ACTIVE)));

    assertThatThrownBy(() -> service.changePassword(user(), "WrongPassw0rd!", NEXT))
        .isInstanceOfSatisfying(
            BusinessException.class, ex -> assertThat(ex.getMessage()).contains("当前口令不正确"));
    verify(accounts, never()).updatePassword(anyString(), anyString(), anyBoolean());
  }

  @Test
  @DisplayName("新口令强度不足：在写完当前口令之后才报，错误指向新口令")
  void rejectsWeakNewPassword() {
    when(accounts.findCredentialsById(ACCOUNT_ID))
        .thenReturn(Optional.of(credentials(encoder.encode(CURRENT), AccountStatus.ACTIVE)));

    assertThatThrownBy(() -> service.changePassword(user(), CURRENT, "short"))
        .isInstanceOfSatisfying(
            BusinessException.class, ex -> assertThat(ex.getMessage()).contains("至少 10 位"));
    verify(accounts, never()).updatePassword(anyString(), anyString(), anyBoolean());
  }

  @Test
  @DisplayName("新口令与当前口令相同：拒绝，不产生一次无意义的版本提升")
  void rejectsSamePassword() {
    when(accounts.findCredentialsById(ACCOUNT_ID))
        .thenReturn(Optional.of(credentials(encoder.encode(CURRENT), AccountStatus.ACTIVE)));

    assertThatThrownBy(() -> service.changePassword(user(), CURRENT, CURRENT))
        .isInstanceOfSatisfying(
            BusinessException.class, ex -> assertThat(ex.getMessage()).contains("不能与当前口令相同"));
    verify(accounts, never()).updatePassword(anyString(), anyString(), anyBoolean());
  }

  @Test
  @DisplayName("停用账号不能自助改密")
  void inactiveAccountCannotChange() {
    when(accounts.findCredentialsById(ACCOUNT_ID))
        .thenReturn(Optional.of(credentials(encoder.encode(CURRENT), AccountStatus.DISABLED)));

    assertThatThrownBy(() -> service.changePassword(user(), CURRENT, NEXT))
        .isInstanceOfSatisfying(
            BusinessException.class, ex -> assertThat(ex.getMessage()).contains("已停用"));
  }

  @Test
  @DisplayName("管理员重置：默认生成一次性口令、置需改标记、提升版本")
  void adminResetGeneratesTemporaryPassword() {
    when(accounts.findCredentialsById(ACCOUNT_ID))
        .thenReturn(Optional.of(credentials(encoder.encode(CURRENT), AccountStatus.ACTIVE)));
    when(accounts.updatePassword(eq(ACCOUNT_ID), anyString(), eq(true))).thenReturn(1);

    AuthService.PasswordReset reset = service.resetPassword(admin(), ACCOUNT_ID, null, "本人遗忘口令");

    assertThat(reset.temporaryPassword()).hasSize(16);
    assertThat(reset.mustChangePassword()).isTrue();
    assertThat(reset.username()).isEqualTo(USERNAME);
    // 生成的口令必须能通过平台自己的强度策略，否则用户拿着它登录后再改密会先被拒一次
    assertThatCode(() -> policy.validate(reset.temporaryPassword())).doesNotThrowAnyException();
    ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
    verify(accounts).updatePassword(eq(ACCOUNT_ID), hash.capture(), eq(true));
    assertThat(encoder.matches(reset.temporaryPassword(), hash.getValue())).isTrue();
  }

  @Test
  @DisplayName("一次性口令的字符集不含易混字符，且永远满足强度策略")
  void temporaryPasswordIsReadableAndAlwaysValid() {
    for (int i = 0; i < 500; i++) {
      String pwd = AuthService.temporaryPassword();
      assertThat(pwd).hasSize(16);
      assertThat(pwd).doesNotContain("0", "O", "1", "l", "I");
      assertThatCode(() -> policy.validate(pwd)).doesNotThrowAnyException();
    }
  }

  @Test
  @DisplayName("重置他人口令需要账号管理能力：普通用户被拒且不写库")
  void adminResetRequiresManageCapability() {
    assertThatThrownBy(() -> service.resetPassword(user(), ACCOUNT_ID, null, "理由"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
    verify(accounts, never()).updatePassword(anyString(), anyString(), anyBoolean());
  }

  @Test
  @DisplayName("不能重置自己的口令：应走本人改密")
  void adminResetRejectsSelf() {
    assertThatThrownBy(() -> service.resetPassword(admin(), "acc-admin", null, "理由"))
        .isInstanceOfSatisfying(
            BusinessException.class, ex -> assertThat(ex.getMessage()).contains("当前登录账号"));
    verify(accounts, never()).updatePassword(anyString(), anyString(), anyBoolean());
  }

  @Test
  @DisplayName("重置必须说明理由")
  void adminResetRequiresReason() {
    assertThatThrownBy(() -> service.resetPassword(admin(), ACCOUNT_ID, null, " "))
        .isInstanceOfSatisfying(
            BusinessException.class, ex -> assertThat(ex.getMessage()).contains("理由"));
  }

  @Test
  @DisplayName("审计里只记「改了什么」，不记口令、哈希与长度")
  void auditNeverContainsPasswordMaterial() {
    String hashOfCurrent = encoder.encode(CURRENT);
    when(accounts.findCredentialsById(ACCOUNT_ID))
        .thenReturn(Optional.of(credentials(hashOfCurrent, AccountStatus.ACTIVE)));
    when(accounts.updatePassword(eq(ACCOUNT_ID), anyString(), eq(false))).thenReturn(1);

    service.changePassword(user(), CURRENT, NEXT);

    ArgumentCaptor<AuditRecorder.AuditEntry> entry =
        ArgumentCaptor.forClass(AuditRecorder.AuditEntry.class);
    verify(audit).record(entry.capture());
    assertThat(entry.getValue().action()).isEqualTo("PASSWORD_CHANGED");
    assertThat(String.valueOf(entry.getValue().afterValue()))
        .doesNotContain(NEXT, CURRENT, hashOfCurrent);
    assertThat(entry.getValue().beforeValue()).isNull();
  }
}
