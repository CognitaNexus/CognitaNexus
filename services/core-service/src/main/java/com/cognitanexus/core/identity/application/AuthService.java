package com.cognitanexus.core.identity.application;

import com.cognitanexus.core.identity.application.IdentityRepositories.AccountRepository;
import com.cognitanexus.core.identity.application.IdentityRepositories.SessionRevocationRepository;
import com.cognitanexus.core.identity.domain.Account;
import com.cognitanexus.core.identity.domain.AccountCredentials;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.Capabilities;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.platform.util.Fingerprints;
import com.cognitanexus.core.platform.util.Identifiers;
import com.cognitanexus.core.platform.util.TextNormalizer;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.security.SecureRandom;
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
            credentials.authVersion()),
        credentials.mustChangePassword());
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

  /**
   * 本人修改口令。
   *
   * <p>顺序不能换：先校验当前口令，再校验新口令强度，最后判断「新口令是否与旧口令相同」。
   * 若先判强度，用户输入一个不合规的新口令时会先收到「长度不足」，而真正的问题可能是当前口令
   * 输错了——两条错误同时成立时，必须先报能让人继续操作的那一条。
   *
   * <p>返回值是提升后的认证版本。会话层必须用它重写当前会话，否则本次请求一结束，
   * 用户就被自己的改密操作登出了（authVersion 已 +1，会话里还是旧值）。
   *
   * @return 新的 authVersion
   */
  @Transactional
  public long changePassword(ActorContext actor, String currentPassword, String newPassword) {
    if (actor == null || actor.accountIdValue() == null) {
      throw new BusinessException(ErrorCode.AUTH_REQUIRED, "请先登录");
    }
    String accountId = actor.accountIdValue();
    AccountCredentials credentials =
        accounts
            .findCredentialsById(accountId)
            .orElseThrow(() -> BusinessException.notFound("账号不存在"));
    if (!credentials.status().active()) {
      throw BusinessException.businessRule("账号已停用，不能修改口令");
    }
    if (!passwordEncoder.matches(
        currentPassword == null ? "" : currentPassword, credentials.passwordHash())) {
      // 与登录失败不同，这里调用方已经是登录用户，不存在用户名枚举问题，可以如实说明是哪一项错了
      throw BusinessException.businessRule("当前口令不正确");
    }
    passwordPolicy.validate(newPassword);
    if (passwordEncoder.matches(newPassword, credentials.passwordHash())) {
      throw BusinessException.businessRule("新口令不能与当前口令相同");
    }
    int affected = accounts.updatePassword(accountId, passwordEncoder.encode(newPassword), false);
    if (affected != 1) {
      throw BusinessException.versionConflict("账号状态已变化，请刷新后重试");
    }
    audit.record(
        new AuditRecorder.AuditEntry(
            accountId,
            credentials.username(),
            "PASSWORD_CHANGED",
            "ACCOUNT",
            accountId,
            null,
            // 只记「改了」与「需改标记已清除」，绝不记口令、哈希或其长度
            Map.<String, Object>of("mustChangePassword", false),
            "本人修改口令"));
    log.info("口令已修改 accountId={}", accountId);
    return credentials.authVersion() + 1;
  }

  /**
   * 管理员重置口令（无邮件找回通道时的唯一路径）。
   *
   * <p>返回的一次性口令只在本次响应里出现一次，平台不存储明文，事后无法再查；
   * 管理员必须当面或经可信渠道转交本人。重置后 {@code mustChangePassword} 置为 true，
   * 页面会提示本人改密（不阻断登录，理由见 V18 迁移注释）。
   *
   * <p>不允许重置自己：管理员要改自己的口令应走 {@link #changePassword}，否则一次误操作
   * 就会把当前会话踢掉，并把「自己知道的口令」换成一个只显示过一次的串。
   */
  @Transactional
  public PasswordReset resetPassword(
      ActorContext actor, String accountId, String requestedPassword, String reason) {
    Capabilities.require(actor, RoleNames.CAP_ACCOUNT_MANAGE);
    if (reason == null || reason.isBlank()) {
      throw BusinessException.invalidArgument("必须说明重置理由");
    }
    if (actor != null && accountId != null && accountId.equals(actor.accountIdValue())) {
      throw BusinessException.businessRule("不能重置当前登录账号的口令，请使用「修改口令」");
    }
    AccountCredentials credentials =
        accounts
            .findCredentialsById(accountId)
            .orElseThrow(() -> BusinessException.notFound("账号不存在"));
    if (!credentials.status().active()) {
      throw BusinessException.businessRule("账号已停用，不能重置口令");
    }
    boolean generated = requestedPassword == null || requestedPassword.isBlank();
    String password = generated ? temporaryPassword() : requestedPassword.trim();
    passwordPolicy.validate(password);
    int affected = accounts.updatePassword(accountId, passwordEncoder.encode(password), true);
    if (affected != 1) {
      throw BusinessException.versionConflict("账号状态已变化，请刷新后重试");
    }
    audit.record(
        new AuditRecorder.AuditEntry(
            actor == null ? null : actor.accountIdValue(),
            actor == null ? RoleNames.SYSTEM : actor.username(),
            "PASSWORD_RESET_BY_ADMIN",
            "ACCOUNT",
            accountId,
            null,
            Map.<String, Object>of("mustChangePassword", true, "generated", generated),
            reason));
    log.info("管理员重置口令 accountId={} generated={}", accountId, generated);
    return new PasswordReset(accountId, credentials.username(), password, true);
  }

  /**
   * 生成一次性口令。
   *
   * <p>字符集去掉了 0/O/1/l/I 这类肉眼易混字符：这个口令要靠人读给另一个人，
   * 一次读错就要再重置一次，而重置会再次踢掉对方所有会话。
   *
   * <p>首尾各钉一个字母与一个数字，保证必然满足 {@link PasswordPolicy} 的「字母+数字」要求，
   * 而不是靠概率——概率写法在极少数情况下会生成不合规口令，然后被自己的策略拒绝。
   */
  static String temporaryPassword() {
    final String letters = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    final String digits = "23456789";
    final String all = letters + digits;
    StringBuilder sb = new StringBuilder(16);
    sb.append(letters.charAt(RANDOM.nextInt(letters.length())));
    for (int i = 0; i < 14; i++) {
      sb.append(all.charAt(RANDOM.nextInt(all.length())));
    }
    sb.append(digits.charAt(RANDOM.nextInt(digits.length())));
    return sb.toString();
  }

  private static final SecureRandom RANDOM = new SecureRandom();

  /** 管理员重置结果。{@code temporaryPassword} 是本次响应中唯一一次出现的明文。 */
  public record PasswordReset(
      String accountId, String username, String temporaryPassword, boolean mustChangePassword) {}

  /** 当前账号是否处于「需修改口令」状态（管理员重置后为 true）。 */
  @Transactional(readOnly = true)
  public boolean mustChangePassword(String accountId) {
    return accounts
        .findCredentialsById(accountId)
        .map(AccountCredentials::mustChangePassword)
        .orElse(false);
  }

  /** 维护清理：删除超出保留窗口的会话撤销记录。保留窗口须长于会话最大寿命。 */
  @Transactional
  public int purgeRevocations(int retentionDays) {
    return revocations.purgeExpired(retentionDays);
  }

  /** 认证结果。会话层据此写入会话属性。 */
  public record AuthenticatedAccount(ActorContext actor, boolean mustChangePassword) {

    public Map<String, Object> toView() {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("accountId", actor.accountIdValue());
      view.put("username", actor.username());
      view.put("displayName", actor.displayName());
      view.put("roles", actor.roles().stream().sorted().toList());
      // 能力是「角色 → 许可」的推导结果，由服务端算好下发：前端各页若各自维护一份映射，
      // 加一个新能力就要改一遍所有页面，漏改的那一处表现为「按钮不见了」，且没人会报错。
      view.put(
          "capabilities",
          com.cognitanexus.core.platform.security.Capabilities.of(actor).stream()
              .sorted()
              .toList());
      view.put("authVersion", actor.authVersion());
      // 「管理员刚重置过口令」必须随登录响应下发：前端据此在顶部提示本人尽快改密，
      // 而不是等用户下次点进账号页才看到。
      view.put("mustChangePassword", mustChangePassword);
      return view;
    }
  }
}
