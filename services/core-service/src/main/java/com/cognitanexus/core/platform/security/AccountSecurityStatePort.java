package com.cognitanexus.core.platform.security;

import java.util.Optional;
import java.util.Set;

/**
 * 账号安全状态的入站端口。
 *
 * <p>依据：架构 V0.2 §10.2。受保护请求必须校验账号启用状态、会话版本及撤销记录；Redis 中的
 * 会话不是权限事实源，Redis 恢复旧数据后会话也不得复活。
 *
 * <p>端口定义在平台层、实现放在身份模块的基础设施层，避免平台层反向依赖业务模块。
 */
public interface AccountSecurityStatePort {

  /**
   * 账号安全状态快照。
   *
   * @param status ACTIVE 或 DISABLED
   */
  record AccountSecurityState(
      String accountId,
      String username,
      String displayName,
      Set<String> roles,
      String status,
      long authVersion) {

    public boolean active() {
      return "ACTIVE".equals(status);
    }
  }

  Optional<AccountSecurityState> load(String accountId);

  /** 会话标识的不可逆摘要是否已被登记撤销。 */
  boolean isSessionRevoked(String sessionIdHash);
}
