package com.cognitanexus.core.identity.api;

import com.cognitanexus.core.identity.application.AuthService;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.ActorContextHolder;
import com.cognitanexus.core.platform.security.ActorPrincipal;
import com.cognitanexus.core.platform.security.SessionActor;
import com.cognitanexus.core.platform.util.Fingerprints;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 注册、登录、退出与当前身份。
 *
 * <p>依据：架构 V0.2 §10.1、§10.2。登录成功旋转会话标识（防会话固定）；退出先持久登记撤销、
 * 再销毁会话，避免 Redis 恢复旧数据后会话复活。
 *
 * <p>本控制器只做参数与协议的转换，认证判定与审计在应用层完成。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  /**
   * 初始化 CSRF 令牌。
   *
   * <p>前端在首个状态变更请求前调用；令牌同时写入 XSRF-TOKEN Cookie 供同源 SPA 读取。
   */
  @GetMapping("/csrf")
  public ResponseEntity<ApiResponse<Map<String, Object>>> csrf(HttpServletRequest request) {
    CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    Map<String, Object> data = new LinkedHashMap<>();
    if (token != null) {
      data.put("headerName", token.getHeaderName());
      data.put("parameterName", token.getParameterName());
      data.put("token", token.getToken());
    }
    return ResponseEntity.ok(ApiResponse.ok(data, TraceIdHolder.current()));
  }

  @PostMapping("/register")
  public ResponseEntity<ApiResponse<Map<String, Object>>> register(
      @Valid @RequestBody IdentityRequests.RegisterRequest body) {
    String accountId = authService.register(body.username(), body.password(), body.displayName());
    return ResponseEntity.ok(
        ApiResponse.ok(Map.of("accountId", accountId), TraceIdHolder.current()));
  }

  @PostMapping("/login")
  public ResponseEntity<ApiResponse<Map<String, Object>>> login(
      @Valid @RequestBody IdentityRequests.LoginRequest body, HttpServletRequest request) {
    AuthService.AuthenticatedAccount authenticated =
        authService.authenticate(body.username(), body.password());

    // 防会话固定：登录成功后更换会话标识，旧标识不再能提升为已登录状态
    HttpSession existing = request.getSession(false);
    if (existing != null) {
      existing.invalidate();
    }
    HttpSession session = request.getSession(true);
    request.changeSessionId();
    session.setAttribute(
        ActorPrincipal.SESSION_ATTRIBUTE,
        new SessionActor(
            authenticated.actor().accountIdValue(), authenticated.actor().authVersion()));
    session.setMaxInactiveInterval((int) Duration.ofHours(2).toSeconds());

    return ResponseEntity.ok(ApiResponse.ok(authenticated.toView(), TraceIdHolder.current()));
  }

  @PostMapping("/logout")
  public ResponseEntity<ApiResponse<Map<String, Object>>> logout(HttpServletRequest request) {
    ActorContext actor = ActorContextHolder.currentOrNull();
    if (actor == null) {
      throw new BusinessException(ErrorCode.AUTH_REQUIRED, "请先登录");
    }
    HttpSession session = request.getSession(false);
    if (session != null) {
      // 先持久登记撤销，再销毁会话：顺序反了会在 Redis 恢复后让会话复活
      authService.registerLogout(
          Fingerprints.sha256Hex(session.getId()), actor.accountIdValue(), actor.username());
      session.invalidate();
    }
    return ResponseEntity.ok(ApiResponse.ok(Map.of("loggedOut", true), TraceIdHolder.current()));
  }

  @GetMapping("/me")
  public ResponseEntity<ApiResponse<Map<String, Object>>> me() {
    ActorContext actor = ActorContextHolder.currentOrNull();
    if (actor == null) {
      throw new BusinessException(ErrorCode.AUTH_REQUIRED, "请先登录");
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("accountId", actor.accountIdValue());
    view.put("username", actor.username());
    view.put("displayName", actor.displayName());
    view.put("roles", actor.roles().stream().sorted().toList());
    view.put("authVersion", actor.authVersion());
    return ResponseEntity.ok(ApiResponse.ok(view, TraceIdHolder.current()));
  }
}
