package com.cognitanexus.ingestion.platform.security;

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
 * <p>依据：架构 V0.2 §10.1、§10.2。「/internal/v1 前缀本身不构成授权依据」——仅凭路径前缀
 * 就信任调用方，等于把内部接口暴露给任何能访问该端口的进程。
 *
 * <p>校验方式：请求头 X-Service-Name 与 X-Service-Token 必须同时给出且与配置一致。<b>配置为空
 * 时一律拒绝</b>（fail-closed）：未配置令牌的环境不应该把内部端点变成公开端点。
 *
 * <p>令牌比较使用恒定时间比较，避免通过响应时间差逐字节试探令牌。
 *
 * <p><b>当前状态说明</b>：本服务目前没有对外提供 {@code /internal/v1/*} 端点——来源配置与交接
 * 都是本服务<b>主动</b>调用 core，不存在被 core 反向调用的场景（架构 §6.1 采用的是「每批重查
 * 来源启用状态」而非推送通知）。这个过滤器是先于端点存在的：路由表已为该前缀预留位置，先把
 * 守卫装上，之后新增端点时不会因为忘记加鉴权而把内部接口暴露出去。不能因为它暂时拦不到东西
 * 就删除——那会让「这个前缀默认可信」成为默认行为。
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
    String serviceName = request.getHeader(IngestionSecurity.HEADER_SERVICE_NAME);
    String token = request.getHeader(IngestionSecurity.HEADER_SERVICE_TOKEN);

    boolean accepted = properties.accepts(serviceName, token);
    if (!accepted) {
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
      return expected != null && IngestionSecurity.constantTimeEquals(expected, token);
    }
  }
}
