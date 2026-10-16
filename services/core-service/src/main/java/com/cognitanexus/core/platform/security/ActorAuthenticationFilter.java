package com.cognitanexus.core.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 认证过滤器：把会话中的登录态还原为可信操作者上下文，并在每次请求校验账号当前状态。
 *
 * <p>依据：架构 V0.2 §10.2、AC-IMPL-FR04-02。账号被停用或会话已被撤销时立即失效，
 * 不依赖令牌过期；Redis 会话丢失也不能被解释为用户主动退出。
 *
 * <p>本过滤器只做认证（你是谁），不做授权（你能做什么）；授权在应用层三层校验中完成。
 */
@Component
public class ActorAuthenticationFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(ActorAuthenticationFilter.class);

  private final AccountSecurityStatePort accountSecurityStatePort;

  public ActorAuthenticationFilter(AccountSecurityStatePort accountSecurityStatePort) {
    this.accountSecurityStatePort = accountSecurityStatePort;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    try {
      Optional<ActorContext> actor;
      try {
        actor = restoreActor(request);
      } catch (RuntimeException ex) {
        // 会话存储（Redis）不可用或会话内容无法反序列化时，不把异常抛给容器。
        // 抛出去的结果是整个请求变成 500，连公开读接口也一起挂掉；而此处按「无法确认身份」
        // 处理，受保护接口会正常返回 401（fail-closed），公开接口仍可服务。
        log.error("会话恢复失败，本次请求按未认证处理 path={}", request.getRequestURI(), ex);
        actor = Optional.empty();
      }
      actor.ifPresent(
          found -> {
            SecurityContextHolder.getContext()
                .setAuthentication(ActorPrincipal.toAuthentication(found));
            ActorContextHolder.set(found);
          });
      filterChain.doFilter(request, response);
    } finally {
      ActorContextHolder.clear();
      SecurityContextHolder.clearContext();
    }
  }

  private Optional<ActorContext> restoreActor(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session == null) {
      return Optional.empty();
    }
    Object attribute = session.getAttribute(ActorPrincipal.SESSION_ATTRIBUTE);
    if (!(attribute instanceof SessionActor sessionActor)) {
      return Optional.empty();
    }

    String sessionHash = sha256Hex(session.getId());
    if (accountSecurityStatePort.isSessionRevoked(sessionHash)) {
      log.debug("会话已被登记撤销，拒绝恢复登录态");
      session.invalidate();
      return Optional.empty();
    }

    Optional<AccountSecurityStatePort.AccountSecurityState> state =
        accountSecurityStatePort.load(sessionActor.accountId());
    if (state.isEmpty()) {
      session.invalidate();
      return Optional.empty();
    }
    AccountSecurityStatePort.AccountSecurityState current = state.get();
    if (!current.active() || current.authVersion() != sessionActor.authVersion()) {
      // 账号被停用或认证版本已提升：旧会话即使仍在 Redis 也不可用
      log.info(
          "会话失效 accountId={} reason={}",
          sessionActor.accountId(),
          current.active() ? "AUTH_VERSION_CHANGED" : "ACCOUNT_DISABLED");
      session.invalidate();
      return Optional.empty();
    }

    return Optional.of(
        new ActorContext(
            new com.cognitanexus.sharedkernel.AccountId(current.accountId()),
            current.username(),
            current.displayName(),
            current.roles(),
            current.authVersion()));
  }

  private String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 不可用", ex);
    }
  }
}
