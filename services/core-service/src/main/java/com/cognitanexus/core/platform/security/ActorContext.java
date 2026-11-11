package com.cognitanexus.core.platform.security;

import com.cognitanexus.sharedkernel.AccountId;
import java.util.Set;

/**
 * 可信操作者上下文。
 *
 * <p>依据：编码规范 §4.2。真实操作者一律来自认证上下文，禁止把客户端传来的 actorId/role
 * 当作身份；系统任务使用独立服务主体，不创建假管理员账号绕过审计。
 *
 * @param accountId 账号标识
 * @param username 登录名
 * @param displayName 展示名
 * @param roles 角色集合
 * @param authVersion 认证版本，用于检测会话是否已被撤销
 */
public record ActorContext(
    AccountId accountId, String username, String displayName, Set<String> roles, long authVersion) {

  public ActorContext {
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("username 不能为空");
    }
    roles = roles == null ? Set.of() : Set.copyOf(roles);
  }

  /** 是否具备某项能力。能力与角色分离：角色是身份的集合，能力是操作的许可。 */
  public boolean hasRole(String role) {
    return roles.contains(role);
  }

  public boolean isPlatformAdmin() {
    return roles.contains(RoleNames.PLATFORM_ADMIN);
  }

  public String accountIdValue() {
    return accountId == null ? null : accountId.value();
  }
}
