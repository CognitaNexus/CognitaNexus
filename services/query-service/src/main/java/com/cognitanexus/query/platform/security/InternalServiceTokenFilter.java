package com.cognitanexus.query.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 内部端点服务身份校验。
 *
 * <p>依据：架构 V0.2 §10.1、§10.2。「/internal/v1 前缀本身不构成授权依据」。本服务的
 * {@code /internal/v1/inbox/batches} 会写入投影与发布水位：任何能访问该端口并成功投递伪造事件的
 * 进程，就能凭空往公开检索里塞入成果，或声明「已发布到修订 N」而实际没有投影。这是本服务
 * 权限最高的写入口，因此必须 fail-closed。
 *
 * <p><b>配置为空时一律拒绝</b>：未配置令牌的环境不应该把内部端点变成公开端点。
 *
 * <p>令牌比较使用恒定时间比较，避免通过响应时间差逐字节试探令牌。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class InternalServiceTokenFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(InternalServiceTokenFilter.class);

  private final InternalServiceTokenProperties properties;

  public InternalServiceTokenFilter(InternalServiceTokenProperties properties) {
    this.properties = properties;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/internal/v1/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String serviceName = request.getHeader(QuerySecurity.HEADER_SERVICE_NAME);
    String token = request.getHeader(QuerySecurity.HEADER_SERVICE_TOKEN);

    if (!properties.accepts(serviceName, token)) {
      log.warn(
          "内部端点被拒绝 path={} serviceName={} configured={}",
          request.getRequestURI(),
          serviceName,
          properties.configured());
      writeDenied(response);
      return;
    }
    filterChain.doFilter(request, response);
  }

  private static void writeDenied(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write("{\"code\":\"ACCESS_DENIED\",\"message\":\"内部端点需要有效的服务身份\",\"traceId\":\"\"}");
  }

  /** 内部服务令牌配置。 */
  @ConfigurationProperties(prefix = "cn.internal")
  public record InternalServiceTokenProperties(Map<String, String> serviceTokens) {

    public InternalServiceTokenProperties {
      serviceTokens = serviceTokens == null ? Map.of() : Map.copyOf(serviceTokens);
    }

    public boolean configured() {
      return !serviceTokens.isEmpty();
    }

    /** 服务名与令牌是否匹配。任一为空、服务名未登记、令牌不符，都返回 false。 */
    public boolean accepts(String serviceName, String token) {
      if (serviceName == null || token == null) {
        return false;
      }
      String expected = serviceTokens.get(serviceName);
      return expected != null && QuerySecurity.constantTimeEquals(expected, token);
    }
  }
}
