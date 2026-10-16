package com.cognitanexus.core.identity.application;

import com.cognitanexus.core.identity.application.IdentityRepositories.AccountRepository;
import com.cognitanexus.core.identity.application.IdentityRepositories.SessionRevocationRepository;
import com.cognitanexus.core.identity.domain.Account;
import com.cognitanexus.core.identity.domain.AccountCredentials;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.platform.util.Fingerprints;
import com.cognitanexus.core.platform.util.Identifiers;
import com.cognitanexus.core.platform.util.TextNormalizer;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注册、登录与退出。
 *
 * <p>依据：架构 V0.2 §10.2、AC-IMPL-FR04-02。
 *
 * <p>登录失败一律返回同一提示，不区分「登录名不存在」与「口令错误」，避免用户枚举；
 * 但内部审计可区分，且只有真实存在的账号才累加失败计数。
 */
@Service
public class AuthService {

  private static final Logger log = LoggerFactory.getLogger(AuthService.class);

  /** 连续失败达到该次数即锁定。工程默认值，须由业务与安全负责人确认后固化。 */
  private static final int LOCK_THRESHOLD = 8;

  private static final int LOCK_SECONDS = 900;

  private final AccountRepository accounts;
  private final SessionRevocationRepository revocations;
  private final PasswordEncoder passwordEncoder;
  private final PasswordPolicy passwordPolicy;
  private final LoginThrottle throttle;
  private final LoginStateUpdater loginStateUpdater;
  private final AuditRecorder audit;

  public AuthService(
      AccountRepository accounts,
      SessionRevocationRepository revocations,
      PasswordEncoder passwordEncoder,
      PasswordPolicy passwordPolicy,
      LoginThrottle throttle,
      LoginStateUpdater loginStateUpdater,
      AuditRecorder audit) {
    this.accounts = accounts;
    this.revocations = revocations;
    this.passwordEncoder = passwordEncoder;
    this.passwordPolicy = passwordPolicy;
    this.throttle = throttle;
    this.loginStateUpdater = loginStateUpdater;
    this.audit = audit;
  }

  /**
   * 自助注册。
   *
   * <p>账号与学者档案分别建模：注册只创建账号，不创建学者档案，也不代表任何成果归属。
   */
  @Transactional
  public String register(String username, String rawPassword, String displayName) {
    String trimmedUsername = username == null ? "" : username.trim();
    if (trimmedUsername.length() < 3 || trimmedUsername.length() > 64) {
      throw BusinessException.invalidArgument("登录名长度须在 3 到 64 之间");
    }
    String normalizedDisplayName = displayName == null ? "" : displayName.trim();
    if (normalizedDisplayName.isEmpty() || normalizedDisplayName.length() > 64) {
      throw BusinessException.invalidArgument("显示名长度须在 1 到 64 之间");
    }
    passwordPolicy.validate(rawPassword);

    String usernameNorm = TextNormalizer.normalize(trimmedUsername);
    if (accounts.findCredentialsByUsernameNorm(usernameNorm).isPresent()) {
      // 不暴露「该登录名已被占用」以外的信息；注册场景下占用提示是必要的可用性反馈
      throw new BusinessException(ErrorCode.VERSION_CONFLICT, "该登录名已被占用");
    }

    String accountId = Identifiers.next("acc");
    Account account =
        new Account(
            accountId,
            trimmedUsername,
            usernameNorm,
            null,
            normalizedDisplayName,
            com.cognitanexus.core.identity.domain.AccountStatus.ACTIVE,
            1L,
            Set.of(RoleNames.USER));
    accounts.insert(account, passwordEncoder.encode(rawPassword));
    audit.record(
        new AuditRecorder.AuditEntry(
            accountId,
            trimmedUsername,
            "ACCOUNT_REGISTERED",
            "ACCOUNT",
            accountId,
            null,
            Map.of("username", trimmedUsername, "roles", List.copyOf(account.roles())),
            "自助注册"));
    log.info("账号注册成功 accountId={}", accountId);
    return accountId;
  }

  /**
   * 校验凭据。
   *
   * <p>刻意不使用 {@code @Transactional}：失败路径必须先把失败计数提交再抛出业务异常，
   * 否则计数会随异常回滚（见 {@link LoginStateUpdater}）。
   */
  public AuthenticatedAccount authenticate(String username, String rawPassword) {
    String usernameNorm = TextNormalizer.normalize(username == null ? "" : username.trim());
    String subjectHash = Fingerprints.sha256Hex(usernameNorm);
    if (throttle.exceeded(subjectHash)) {
      throw new BusinessException(ErrorCode.RATE_LIMITED, "登录尝试过于频繁，请稍后再试");
    }
    throttle.recordAttempt(subjectHash);

    Optional<AccountCredentials> found = accounts.findCredentialsByUsernameNorm(usernameNorm);
    if (found.isEmpty()) {
      loginStateUpdater.onRejected("登录名不存在");
      throw new BusinessException(ErrorCode.AUTH_REQUIRED, "登录名或口令不正确");
    }
    AccountCredentials credentials = found.get();

    if (!credentials.status().active()) {
      loginStateUpdater.onRejected("账号已停用");
      throw new BusinessException(ErrorCode.AUTH_REQUIRED, "登录名或口令不正确");
    }
    if (credentials.lockedAt(accounts.now())) {
      loginStateUpdater.onRejected("账号处于锁定期");
      throw new BusinessException(ErrorCode.AUTH_REQUIRED, "登录名或口令不正确");
    }

    if (!passwordEncoder.matches(
        rawPassword == null ? "" : rawPassword, credentials.passwordHash())) {
      loginStateUpdater.onFailure(
          credentials.accountId(), credentials.username(), LOCK_THRESHOLD, LOCK_SECONDS, "口令校验失败");
      throw new BusinessException(ErrorCode.AUTH_REQUIRED, "登录名或口令不正确");
    }

    loginStateUpdater.onSuccess(credentials.accountId(), credentials.username());
    throttle.clear(subjectHash);
    return new AuthenticatedAccount(
        new ActorContext(
            new com.cognitanexus.sharedkernel.AccountId(credentials.accountId()),
            credentials.username(),
            credentials.displayName(),
            credentials.roles(),
            credentials.authVersion()));
  }

  /**
   * 退出：先持久登记撤销，再清 Redis 会话。
   *
   * <p>使用 REQUIRES_NEW，保证撤销登记立即提交；若与调用方事务绑定，一旦外层回滚，
   * 已从 Redis 删除的会话就失去了撤销凭证，形成「已退出但仍可用」的窗口。
   */
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
  public void registerLogout(String sessionIdHash, String accountId, String username) {
    revocations.register(sessionIdHash, accountId, "LOGOUT");
    audit.record(
        new AuditRecorder.AuditEntry(
            accountId, username, "LOGOUT", "ACCOUNT", accountId, null, null, "用户主动退出"));
  }

  /** 供管理端停用账号使用：提升 authVersion 使既有会话立即失效。 */
  @Transactional
  public void disableAccount(String accountId, ActorContext actor, String reason) {
    int affected = accounts.disable(accountId);
    if (affected != 1) {
      throw BusinessException.versionConflict("账号不存在或已停用，请刷新后重试");
    }
    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "ACCOUNT_DISABLED",
            "ACCOUNT",
            accountId,
            Map.of("status", "ACTIVE"),
            Map.of("status", "DISABLED"),
            reason));
  }

  /** 维护清理：删除超出保留窗口的会话撤销记录。保留窗口须长于会话最大寿命。 */
  @Transactional
  public int purgeRevocations(int retentionDays) {
    return revocations.purgeExpired(retentionDays);
  }

  /** 认证结果。会话层据此写入会话属性。 */
  public record AuthenticatedAccount(ActorContext actor) {

    public Map<String, Object> toView() {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("accountId", actor.accountIdValue());
      view.put("username", actor.username());
      view.put("displayName", actor.displayName());
      view.put("roles", actor.roles().stream().sorted().toList());
      view.put("authVersion", actor.authVersion());
      return view;
    }
  }
}
