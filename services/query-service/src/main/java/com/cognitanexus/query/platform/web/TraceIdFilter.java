package com.cognitanexus.query.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 链路标识过滤器。
 *
 * <p>依据：架构 V0.2 §10.4。最高优先级执行，保证后续所有过滤器和业务日志都带 traceId，
 * 包括服务令牌拒绝这类不进入业务代码的路径。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String incoming = request.getHeader(TraceIdHolder.HEADER);
    String traceId = TraceIdHolder.acceptable(incoming) ? incoming : TraceIdHolder.generate();
    TraceIdHolder.set(traceId);
    response.setHeader(TraceIdHolder.HEADER, traceId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      TraceIdHolder.clear();
    }
  }
}
