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
    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "ACCOUNT_ROLE_GRANTED",
            "ACCOUNT",
            accountId,
            Map.of("roles", List.copyOf(account.roles())),
            Map.of("role", role),
            "授予角色"));
    return accounts.findById(accountId).map(this::toView).orElseThrow();
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
