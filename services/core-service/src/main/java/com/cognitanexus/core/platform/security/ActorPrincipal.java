package com.cognitanexus.core.platform.security;

import com.cognitanexus.sharedkernel.AccountId;
import java.util.stream.Collectors;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** 会话主体与 Spring Security 认证对象之间的转换。 */
public final class ActorPrincipal {

  /** 会话属性名。会话存于 redis-session，不承载权限事实，权限由数据库状态校验。 */
  public static final String SESSION_ATTRIBUTE = "cn.actor.session";

  private ActorPrincipal() {}

  public static UsernamePasswordAuthenticationToken toAuthentication(ActorContext actor) {
    var authorities =
        actor.roles().stream()
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
            .collect(Collectors.toUnmodifiableSet());
    var token = new UsernamePasswordAuthenticationToken(actor.username(), null, authorities);
    token.setDetails(actor);
    return token;
  }

  public static ActorContext fromAuthentication(
      UsernamePasswordAuthenticationToken authentication) {
    Object details = authentication.getDetails();
    if (details instanceof ActorContext actor) {
      return actor;
    }
    return null;
  }

  public static ActorContext of(
      String accountId,
      String username,
      String displayName,
      java.util.Set<String> roles,
      long authVersion) {
    return new ActorContext(new AccountId(accountId), username, displayName, roles, authVersion);
  }
}
