package com.cognitanexus.core.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 强制解析 CSRF 令牌，使 XSRF-TOKEN Cookie 对同源 SPA 可用。
 *
 * <p>依据：架构 V0.2 §10.2。状态变更请求启用 CSRF 防护；前端从 Cookie 读取令牌并通过
 * X-XSRF-TOKEN 头回传。禁止为方便联调全局关闭防护。
 */
@Component
public class CsrfCookieFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    if (token != null) {
      // 触发令牌解析，从而写入 Cookie
      token.getToken();
    }
    filterChain.doFilter(request, response);
  }
}
