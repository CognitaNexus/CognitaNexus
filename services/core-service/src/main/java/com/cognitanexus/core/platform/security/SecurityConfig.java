package com.cognitanexus.core.platform.security;

import com.cognitanexus.core.platform.web.TraceIdHolder;
import com.cognitanexus.sharedkernel.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * 认证与传输层安全配置。
 *
 * <p>依据：架构 V0.2 §10.1（外部路由）、§10.2（认证与会话）。
 *
 * <p>要点：
 *
 * <ul>
 *   <li>登录态由自有会话属性承载，权限每次请求按数据库当前状态重新判定，因此安全上下文不做
 *       跨请求持久化（NullSecurityContextRepository）。
 *   <li>公开路由匿名可访问，服务端强制公开资格；其余路由要求已认证。
 *   <li>状态变更请求启用 CSRF，令牌经 Cookie 与请求头传递。
 *   <li>拒绝时不返回业务副作用，401 与 403 语义分开，网络超时不得都跳登录页。
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(InternalServiceTokenProperties.class)
public class SecurityConfig {

  private final ActorAuthenticationFilter actorAuthenticationFilter;
  private final ServiceTokenFilter serviceTokenFilter;
  private final CsrfCookieFilter csrfCookieFilter;

  public SecurityConfig(
      ActorAuthenticationFilter actorAuthenticationFilter,
      ServiceTokenFilter serviceTokenFilter,
      CsrfCookieFilter csrfCookieFilter) {
    this.actorAuthenticationFilter = actorAuthenticationFilter;
    this.serviceTokenFilter = serviceTokenFilter;
    this.csrfCookieFilter = csrfCookieFilter;
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    // 自适应密码哈希；强度参数由耗时测试确定（架构 §10.2），当前开发默认 10
    return new BCryptPasswordEncoder(10);
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        // 安全上下文不跨请求持久化：权限事实源是数据库当前状态，不是会话快照
        .securityContext(
            context -> context.securityContextRepository(new NullSecurityContextRepository()))
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                    // 内部服务调用不带浏览器 Cookie，由服务令牌把关
                    .ignoringRequestMatchers("/internal/v1/**"))
        .sessionManagement(
            session ->
                session
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    // 显式关闭框架的会话固定防护。原因不是「不需要防护」，而是防护已经由
                    // AuthController.login 在登录成功时用 changeSessionId() 完成；若在此处
                    // 再交给框架处理，会与配合 NullSecurityContextRepository 产生一个真实故障：
                    // SessionManagementFilter 每请求都判定「本次请求刚完成认证」（因为空仓储
                    // 永远报「上下文中没有认证」），于是对每个请求都执行一次会话标识轮换。
                    // 后果是每次响应都换一次 SESSION，而调用方若没有回写 Set-Cookie（例如本服务
                    // 把浏览器 Cookie 转给 core 做鉴权的 ingestion，它不消费 core 的响应 Cookie），
                    // 下一次请求就带着已失效的旧标识，表现为「登录成功后随机 401」。
                    // 这里明确取「无会话固定防护策略」，把轮换点收敛到登录这一处。
                    .sessionFixation(fixation -> fixation.none()))
        // 关闭请求缓存：该机制用于「登录后跳回原地址」，面向浏览器表单登录。
        // 本服务是 JSON 接口，未认证请求只应得到 401，不应为了保存原始请求而给匿名访问
        // 创建会话，也不应把框架的 DefaultSavedRequest 写进会话语料。
        .requestCache(cache -> cache.requestCache(new NullRequestCache()))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/auth/csrf",
                        "/api/v1/auth/captcha")
                    .permitAll()
                    .requestMatchers("/api/v1/public/**")
                    .permitAll()
                    .requestMatchers("/internal/v1/**")
                    .permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                    .permitAll()
                    // OpenAPI 契约匿名开放：评审与外部开发者不必登录就能读接口清单。
                    // 边界只有文档本身——/internal/v1/** 不进文档（见 springdoc.paths-to-match），
                    // 也仍然需要服务令牌，不因为「文档里没写」而放开鉴权。
                    // 访问频率由 nginx 的 cn_docs 限流区兜（见 ops/deploy/nginx-cn.conf），
                    // 应用侧不再重复做限流：这里的请求是只读的文档生成，成本在一次序列化。
                    .requestMatchers(
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/v3/api-docs.yaml",
                        "/swagger-ui.html",
                        "/swagger-ui/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            handling ->
                handling
                    .authenticationEntryPoint(
                        (request, response, ex) ->
                            writeError(response, ErrorCode.AUTH_REQUIRED, "请先登录"))
                    .accessDeniedHandler(
                        (request, response, ex) ->
                            writeError(response, ErrorCode.ACCESS_DENIED, "无权执行该操作")))
        .addFilterBefore(serviceTokenFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(actorAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(csrfCookieFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  /**
   * 写出拒绝响应。
   *
   * <p>这里手写 JSON 而非走 {@code @RestControllerAdvice}：拒绝发生在过滤器链中，早于派发到
   * 控制器，异常处理通知不会被触发。响应字段刻意与 {@code ApiResponse} 的错误形态对齐
   * （code / message / traceId / timestamp），使调用方对所有失败看到同一种结构。
   */
  private static void writeError(HttpServletResponse response, ErrorCode code, String message)
      throws java.io.IOException {
    response.setStatus(code.httpStatus());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write(
            "{\"code\":\""
                + code.name()
                + "\",\"message\":\""
                + message
                + "\",\"traceId\":\""
                + TraceIdHolder.current()
                + "\",\"timestamp\":\""
                + java.time.Instant.now()
                + "\"}");
  }
}
