package com.cognitanexus.core.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 每请求写入追踪标识，贯穿日志与跨服务调用。
 *
 * <p>依据：编码规范 §4.6、架构 V0.2 §12.4。
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
    MDC.put(TraceIdHolder.MDC_KEY, traceId);
    response.setHeader(TraceIdHolder.HEADER, traceId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(TraceIdHolder.MDC_KEY);
    }
  }
}
