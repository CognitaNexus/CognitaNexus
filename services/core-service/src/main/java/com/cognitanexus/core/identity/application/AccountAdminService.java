package com.cognitanexus.core.identity.application;

import com.cognitanexus.core.identity.application.IdentityRepositories.AccountRepository;
import com.cognitanexus.core.identity.domain.Account;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.Capabilities;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.sharedkernel.BusinessException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号管理。
 *
 * <p>依据：AC-IMPL-FR04-02、架构 §5.4。停用账号不删除学者公开档案：账号与学者分别建模，
 * 停用只影响登录与受保护请求，已公开的成果与门户仍然可读。
 */
@Service
public class AccountAdminService {

  private static final Set<String> ASSIGNABLE_ROLES =
      Set.of(RoleNames.USER, RoleNames.REVIEWER, RoleNames.PLATFORM_ADMIN);

  private final AccountRepository accounts;
  private final AuthService authService;
  private final AuditRecorder audit;

  public AccountAdminService(
      AccountRepository accounts, AuthService authService, AuditRecorder audit) {
    this.accounts = accounts;
    this.authService = authService;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public List<Map<String, Object>> list(ActorContext actor, int limit) {
    Capabilities.require(actor, RoleNames.CAP_ACCOUNT_MANAGE);
    return accounts.listAll(Math.min(Math.max(limit, 1), 200)).stream().map(this::toView).toList();
  }

  /** 停用账号：提升 authVersion，使既有会话立即失效（含仍存在于 Redis 的会话）。 */
  @Transactional
  public Map<String, Object> disable(ActorContext actor, String accountId, String reason) {
    Capabilities.require(actor, RoleNames.CAP_ACCOUNT_MANAGE);
    if (reason == null || reason.isBlank()) {
      throw BusinessException.invalidArgument("必须说明停用理由");
    }
    if (actor.accountIdValue() != null && actor.accountIdValue().equals(accountId)) {
      throw BusinessException.businessRule("不能停用当前登录账号");
    }
    authService.disableAccount(accountId, actor, reason);
    return accounts.findById(accountId).map(this::toView).orElseThrow();
  }

  @Transactional
  public Map<String, Object> grantRole(ActorContext actor, String accountId, String role) {
    Capabilities.require(actor, RoleNames.CAP_ACCOUNT_MANAGE);
    if (role == null || !ASSIGNABLE_ROLES.contains(role)) {
      throw BusinessException.invalidArgument("不支持的角色：" + role);
    }
    Account account =
        accounts.findById(accountId).orElseThrow(() -> BusinessException.notFound("账号不存在"));
    if (account.roles().contains(role)) {
      return toView(account);
    }
    accounts.grantRole(accountId, role);
    // 提升认证版本：会话里的角色是登录那一刻的快照，不提升的话新角色要等对方重新登录才生效，
    // 期间「已授权」只存在于数据库里。代价是对方当前会话失效，这是一次权限变更应有的代价。
    accounts.bumpAuthVersion(accountId);
    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "ACCOUNT_ROLE_GRANTED",
            "ACCOUNT",
            accountId,
            Map.of("roles", List.copyOf(account.roles())),
            Map.of("role", role, "sessionsInvalidated", true),
            "授予角色"));
    return accounts.findById(accountId).map(this::toView).orElseThrow();
  }

  /**
   * 撤销角色。
   *
   * <p>三条守卫，都是「撤销」比「授予」更容易出事的点：
   *
   * <ul>
   *   <li>不能撤销自己的角色：误操作会把自己锁在门外，而平台管理员一旦把自己降权，
   *       再想恢复就得直接改库——这不是「严格」，是把自己做成了单点故障；
   *   <li>USER 不可撤销：它是账号的基础身份（能登录、能提交认领），不是特权。要禁止某人使用，
   *       正确动作是停用账号，而停用有独立入口、独立审计；
   *   <li>撤销后必须提升认证版本：否则对方的当前会话仍按旧角色鉴权，撤销只是「下次登录才生效」。
   * </ul>
   *
   * <p>为什么不做「至少保留一个平台管理员」的全局校验：那需要跨账号统计并在并发下加锁，
   * 而本平台的管理员账号由引导流程创建、变更极少；真正会出事的是「撤掉自己」，已被第一条挡住。
   * 这个取舍写在 ADR-002 的后续项里，不装作已经解决。
   */
  @Transactional
  public Map<String, Object> revokeRole(
      ActorContext actor, String accountId, String role, String reason) {
    Capabilities.require(actor, RoleNames.CAP_ACCOUNT_MANAGE);
    if (reason == null || reason.isBlank()) {
      throw BusinessException.invalidArgument("必须说明撤销角色的理由");
    }
    if (role == null || !ASSIGNABLE_ROLES.contains(role)) {
      throw BusinessException.invalidArgument("不支持的角色：" + role);
    }
    if (RoleNames.USER.equals(role)) {
      throw BusinessException.businessRule("USER 是账号的基础身份，不可撤销；如需禁止其登录请停用账号");
    }
    if (actor.accountIdValue() != null && actor.accountIdValue().equals(accountId)) {
      throw BusinessException.businessRule("不能撤销当前登录账号的角色，请由其他管理员操作");
    }
    Account account =
        accounts.findById(accountId).orElseThrow(() -> BusinessException.notFound("账号不存在"));
    if (!account.roles().contains(role)) {
      throw BusinessException.versionConflict("该账号当前没有这个角色，请刷新后重试");
    }
    int affected = accounts.revokeRole(accountId, role);
    if (affected != 1) {
      throw BusinessException.versionConflict("角色已被其他操作改变，请刷新后重试");
    }
    accounts.bumpAuthVersion(accountId);
    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "ACCOUNT_ROLE_REVOKED",
            "ACCOUNT",
            accountId,
            Map.of("roles", List.copyOf(account.roles())),
            Map.of("role", role, "sessionsInvalidated", true),
            reason));
    return accounts.findById(accountId).map(this::toView).orElseThrow();
  }

  /**
   * 管理员重置口令。
   *
   * <p>平台没有邮件通道，「忘记口令」只有这一条找回路径，因此这个动作必须可用、可审计、
   * 且把一次性口令如实交给操作者（只交一次）。返回视图里含明文口令，是本接口与其他管理接口
   * 最大的不同——它不能写进审计、不能写进日志、也不应被网关缓存。
   *
   * <p>重置后该账号 auth_version +1：对方所有既有会话立即失效，必须用新口令重新登录。
   */
  @Transactional
  public Map<String, Object> resetPassword(
      ActorContext actor, String accountId, String newPassword, String reason) {
    AuthService.PasswordReset reset =
        authService.resetPassword(actor, accountId, newPassword, reason);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("accountId", reset.accountId());
    view.put("username", reset.username());
    view.put("temporaryPassword", reset.temporaryPassword());
    view.put("mustChangePassword", reset.mustChangePassword());
    view.put("note", "一次性口令只在本响应中出现一次，平台不保存明文；请当面或经可信渠道转交本人。" + "该账号在其他设备上的会话已全部失效，本人登录后应尽快修改口令。");
    return view;
  }

  private Map<String, Object> toView(Account account) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", account.id());
    view.put("username", account.username());
    view.put("displayName", account.displayName());
    view.put("status", account.status().name());
    view.put("roles", account.roles().stream().sorted().toList());
    view.put("authVersion", account.authVersion());
    return view;
  }
}
