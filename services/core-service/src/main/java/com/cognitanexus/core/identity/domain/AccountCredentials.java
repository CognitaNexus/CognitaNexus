package com.cognitanexus.core.identity.domain;

import java.time.Instant;
import java.util.Set;

/**
 * 认证所需的账号凭据视图。
 *
 * <p>依据：架构 V0.2 §8.1、§10.2。与 {@link Account} 分开的原因：口令哈希、失败次数与锁定时刻
 * 属于认证内部状态，不应出现在门户、治理等业务用途的账号视图里，避免被无意序列化进响应。
 *
 * @param failedLoginAttempts 连续失败次数，登录成功时清零
 * @param lockedUntil 锁定截止时刻，使用数据库时钟判断；null 表示未锁定
 */
public record AccountCredentials(
    String accountId,
    String username,
    String usernameNorm,
    String passwordHash,
    String displayName,
    AccountStatus status,
    long authVersion,
    Set<String> roles,
    int failedLoginAttempts,
    Instant lockedUntil,
    boolean mustChangePassword) {

  public AccountCredentials {
    roles = roles == null ? Set.of() : Set.copyOf(roles);
  }

  /** 是否处于锁定期。比较使用传入的时刻，调用方应传入数据库当前时刻。 */
  public boolean lockedAt(Instant now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }

  public boolean canAuthenticate(Instant now) {
    return status.active() && !lockedAt(now);
  }
}
