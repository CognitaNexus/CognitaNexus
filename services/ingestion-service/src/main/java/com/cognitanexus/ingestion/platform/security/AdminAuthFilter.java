package com.cognitanexus.ingestion.platform.security;

import com.cognitanexus.ingestion.platform.client.CoreInternalClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 采集管理端点鉴权：委托给 core。
 *
 * <p>依据：架构 V0.2 §10.2「ingestion 的管理请求携带用户 Cookie 由 core 鉴权接口验证并校验 CSRF
 * 和目标能力；结果不作跨请求正向缓存」。
 *
 * <p>本过滤器做三件事，顺序固定：
 *
 * <ol>
 *   <li>把浏览器的 Cookie 与 CSRF 请求头原样转给 core，由 core 解析会话、核对账号状态与撤销
 *       记录、执行双提交 CSRF 校验——本服务不解读会话内容，也不自己实现一套判权；
 *   <li>core 判定通过后，检查其返回的能力集合是否包含采集管理能力；
 *   <li>失败即拒绝，不进入控制器。
 * </ol>
 *
 * <p>状态变更方法（非 GET/HEAD）额外要求 CSRF 校验通过：缺少这一步，任何第三方页面都能让已
 * 登录管理员的浏览器去创建采集任务。
 *
 * <p>不缓存的代价是每次管理请求多一次内部调用；换来的是「撤销立即生效」——若缓存判定结果，
 * 被撤销权限的管理员在缓存过期前仍可操作采集任务（架构明确禁止这种正向缓存）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AdminAuthFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(AdminAuthFilter.class);

  private static final String ADMIN_PREFIX = "/api/v1/admin/ingestion/";

  private final CoreInternalClient coreClient;

  public AdminAuthFilter(CoreInternalClient coreClient) {
    this.coreClient = coreClient;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(ADMIN_PREFIX);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String cookieHeader = request.getHeader(HttpHeaders.COOKIE);
    String csrfHeader = request.getHeader(IngestionSecurity.HEADER_CSRF);

    Optional<CoreInternalClient.VerifiedActor> verified;
    try {
      verified = coreClient.verifySession(cookieHeader, csrfHeader);
    } catch (RuntimeException ex) {
      // core 不可达时不回退为放行：把依赖故障当成授权通过，等于在 core 宕机期间开放采集管理
      log.error("core 鉴权不可用，拒绝本次管理请求 path={}", request.getRequestURI(), ex);
      writeError(response, 503, "DEPENDENCY_UNAVAILABLE", "鉴权服务暂不可用，请稍后重试");
      return;
    }
    if (verified.isEmpty()) {
      writeError(response, 401, "AUTH_REQUIRED", "请先登录");
      return;
    }

    CoreInternalClient.VerifiedActor actor = verified.get();
    // 状态变更请求必须带有效 CSRF：缺这一步，第三方页面就能借管理员的浏览器创建采集任务
    if (isStateChanging(request) && !actor.csrfAccepted()) {
      log.info("拒绝缺少 CSRF 的管理请求 accountId={} path={}", actor.accountId(), request.getRequestURI());
      writeError(response, 403, "ACCESS_DENIED", "缺少或无效的 CSRF 令牌");
      return;
    }
    if (!actor.capabilities().contains(IngestionSecurity.CAP_SOURCE_MANAGE)) {
      log.info(
          "拒绝采集管理操作 accountId={} path={} 缺少能力 {}",
          actor.accountId(),
          request.getRequestURI(),
          IngestionSecurity.CAP_SOURCE_MANAGE);
      writeError(response, 403, "ACCESS_DENIED", "无权管理采集");
      return;
    }

    try {
      ActorContextHolder.set(
          new ActorContext(
              actor.accountId(),
              actor.username(),
              actor.displayName(),
              actor.roles(),
              actor.capabilities()));
      filterChain.doFilter(request, response);
    } finally {
      ActorContextHolder.clear();
    }
  }

  private static boolean isStateChanging(HttpServletRequest request) {
    String method = request.getMethod();
    return !("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method));
  }

  private static void writeError(
      HttpServletResponse response, int status, String code, String message) throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"traceId\":\"\"}");
  }
}
