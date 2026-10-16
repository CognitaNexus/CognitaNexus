package com.cognitanexus.core.identity.application;

import com.cognitanexus.core.identity.application.IdentityRepositories.AccountRepository;
import com.cognitanexus.core.identity.domain.Account;
import com.cognitanexus.core.identity.domain.AccountCredentials;
import com.cognitanexus.core.identity.domain.AccountStatus;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.platform.util.Identifiers;
import com.cognitanexus.core.platform.util.TextNormalizer;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 首个平台管理员引导的业务实现。
 *
 * <p>依据：AC-IMPL-FR04-02、架构 V0.2 §10.1。与 {@link PlatformAdminBootstrap} 分开，
 * 是为了避免 {@code @Transactional} 自调用失效：启动事件监听方法若与本方法同处一个 Bean，
 * 事件回调经 CGLIB 代理进入后会直接调用自身方法，不再是代理调用，事务不会开启，
 * 而审计记录器要求调用方已开启事务（{@code propagation = MANDATORY}），必然失败。
 * 同类问题在 {@code LoginStateUpdater} 处已经出现过一次，此处沿用拆 Bean 的做法。
 *
 * <p>执行条件（必须同时满足）：
 *
 * <ol>
 *   <li>{@code cn.bootstrap.platform-admin-username} 与 {@code -password} 均已配置（由调用方判定）；
 *   <li>库中当前不存在状态为 ACTIVE 的平台管理员。
 * </ol>
 *
 * <p>第 2 条使引导天然幂等：一旦存在活跃管理员，重复启动不会再次执行，也不会重置任何既有凭据。
 * 引导动作写入审计表，与接口授予的角色变更落在同一张表上，不构成绕过审计的特权路径。
 */
@Service
public class PlatformAdminBootstrapper {

  private final AccountRepository accounts;
  private final PasswordPolicy passwordPolicy;
  private final PasswordEncoder passwordEncoder;
  private final AuditRecorder audit;
  private final BootstrapProperties properties;

  public PlatformAdminBootstrapper(
      AccountRepository accounts,
      PasswordPolicy passwordPolicy,
      PasswordEncoder passwordEncoder,
      AuditRecorder audit,
      BootstrapProperties properties) {
    this.accounts = accounts;
    this.passwordPolicy = passwordPolicy;
    this.passwordEncoder = passwordEncoder;
    this.audit = audit;
    this.properties = properties;
  }

  /** 执行一次引导判定；由启动流程或测试驱动。 */
  @Transactional
  public Outcome bootstrapIfAbsent() {
    String username = properties.platformAdminUsername().trim();
    String usernameNorm = TextNormalizer.normalize(username);

    long existingAdmins = accounts.countActiveByRole(RoleNames.PLATFORM_ADMIN);
    if (existingAdmins > 0) {
      return new Outcome(OutcomeKind.SKIPPED_EXISTING_ADMIN, null, existingAdmins);
    }

    Optional<AccountCredentials> existing = accounts.findCredentialsByUsernameNorm(usernameNorm);
    String accountId;
    boolean created;
    if (existing.isPresent()) {
      AccountCredentials credentials = existing.get();
      accountId = credentials.accountId();
      created = false;
      if (credentials.roles().contains(RoleNames.PLATFORM_ADMIN)) {
        // 已持该角色却未通过上面的存在性判定，只可能是账号非 ACTIVE：不能自动复活
        return new Outcome(OutcomeKind.SKIPPED_INACTIVE_HOLDER, accountId, 0);
      }
    } else {
      passwordPolicy.validate(properties.platformAdminPassword());
      accountId = Identifiers.next("acc");
      Account account =
          new Account(
              accountId,
              username,
              usernameNorm,
              null,
              properties.platformAdminDisplayName(),
              AccountStatus.ACTIVE,
              1L,
              Set.of(RoleNames.USER));
      accounts.insert(account, passwordEncoder.encode(properties.platformAdminPassword()));
      created = true;
    }

    accounts.grantRole(accountId, RoleNames.PLATFORM_ADMIN);
    audit.record(
        new AuditRecorder.AuditEntry(
            accountId,
            username,
            "PLATFORM_ADMIN_BOOTSTRAPPED",
            "ACCOUNT",
            accountId,
            null,
            Map.of("role", RoleNames.PLATFORM_ADMIN, "accountCreated", created),
            created ? "部署配置引导：创建账号并授予平台管理员" : "部署配置引导：为既有账号授予平台管理员"));

    return new Outcome(created ? OutcomeKind.CREATED : OutcomeKind.PROMOTED, accountId, 0);
  }

  /** 引导结果种类。 */
  public enum OutcomeKind {
    /** 已存在活跃平台管理员，未做任何变更。 */
    SKIPPED_EXISTING_ADMIN,
    /** 目标账号持有该角色但其账号非活跃，需人工处理。 */
    SKIPPED_INACTIVE_HOLDER,
    /** 新建账号并授予平台管理员。 */
    CREATED,
    /** 为既有账号补充平台管理员角色。 */
    PROMOTED
  }

  /**
   * 引导结果。
   *
   * @param accountId 被创建或被授予角色的账号标识；跳过时为空
   * @param existingAdminCount 跳过时库中已存在的活跃管理员数量
   */
  public record Outcome(OutcomeKind kind, String accountId, long existingAdminCount) {}
}
