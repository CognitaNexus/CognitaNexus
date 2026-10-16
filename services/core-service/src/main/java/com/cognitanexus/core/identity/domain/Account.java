package com.cognitanexus.core.identity.domain;

import java.util.Set;

/**
 * 账号。
 *
 * <p>账号与学者档案分别建模；账号停用不删除学者档案，认领通过只授予门户管理权，不等于身份
 * 证明或成果真实性背书（SRS 5.4、架构 V0.2 §5.4）。
 *
 * @param authVersion 认证版本；停用或撤销授权时递增，使旧会话即使仍在 Redis 也不可用
 */
public record Account(
    String id,
    String username,
    String usernameNorm,
    String passwordHash,
    String displayName,
    AccountStatus status,
    long authVersion,
    Set<String> roles) {

  public Account {
    roles = roles == null ? Set.of() : Set.copyOf(roles);
  }

  public boolean canAuthenticate() {
    return status.active();
  }

  public boolean isPlatformAdmin() {
    return roles.contains("PLATFORM_ADMIN");
  }

  public boolean isReviewer() {
    return roles.contains("REVIEWER") || isPlatformAdmin();
  }
}
