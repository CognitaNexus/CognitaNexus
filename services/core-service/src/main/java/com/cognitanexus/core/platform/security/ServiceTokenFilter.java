package com.cognitanexus.core.platform.security;

import com.cognitanexus.sharedkernel.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 内部端点服务身份校验。
 *
 * <p>依据：架构 V0.2 §10.1、§10.2。/internal/v1/* 禁止外网入口，服务身份与最小权限校验，
 * 不能凭路径自动信任。未配置令牌时拒绝全部内部调用，而不是放行。
 */
@Component
@Order(0)
public class ServiceTokenFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(ServiceTokenFilter.class);
  private static final String SERVICE_HEADER = "X-Service-Name";
  private static final String TOKEN_HEADER = "X-Service-Token";

  private final InternalServiceTokenProperties properties;

  public ServiceTokenFilter(InternalServiceTokenProperties properties) {
    this.properties = properties;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/internal/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String serviceName = request.getHeader(SERVICE_HEADER);
    String token = request.getHeader(TOKEN_HEADER);
    if (!properties.matches(serviceName, token)) {
      log.warn(
          "内部调用鉴权失败 path={} service={} configured={}",
          request.getRequestURI(),
          serviceName,
          properties.configured());
      response.setStatus(ErrorCode.ACCESS_DENIED.httpStatus());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding("UTF-8");
      response.getWriter().write("{\"code\":\"ACCESS_DENIED\",\"message\":\"内部调用鉴权失败\"}");
      return;
    }
    filterChain.doFilter(request, response);
  }
}
