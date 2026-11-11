package com.cognitanexus.ingestion.platform.security;

import java.util.Set;

/**
 * 可信操作者上下文。
 *
 * <p>依据：架构 V0.2 §10.2。本服务不自行判定权限：上下文由 core 的会话鉴权结果构造，
 * capabilities 也是 core 给出的结论。保留能力集合是为了在本地做一次快速的「是否具备采集管理
 * 能力」检查，而不是把它当成第二套授权规则——真正的判定仍在 core。
 *
 * @param accountId 账号标识
 * @param username 登录名
 * @param displayName 显示名
 * @param roles 角色集合
 * @param capabilities 由 core 计算的能力集合
 */
public record ActorContext(
    String accountId,
    String username,
    String displayName,
    Set<String> roles,
    Set<String> capabilities) {

  public ActorContext {
    roles = roles == null ? Set.of() : Set.copyOf(roles);
    capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
  }

  public boolean hasCapability(String capability) {
    return capabilities.contains(capability);
  }
}
