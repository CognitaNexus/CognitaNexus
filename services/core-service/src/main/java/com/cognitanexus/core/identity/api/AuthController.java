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

  /**
   * 本人修改口令。
   *
   * <p>改密会使 auth_version +1，因此必须用返回的新版本重写当前会话：不重写的话，
   * 本次响应之后的下一个请求就会因为「会话版本落后」被判为未登录，用户会以为改密把自己踢了。
   * 其他设备上的会话仍保持旧版本，下一次请求即失效——这是改密应有的代价。
   */
  @PostMapping("/password")
  public ResponseEntity<ApiResponse<Map<String, Object>>> changePassword(
      @Valid @RequestBody IdentityRequests.ChangePasswordRequest body, HttpServletRequest request) {
    ActorContext actor = ActorContextHolder.currentOrNull();
    long newVersion = authService.changePassword(actor, body.currentPassword(), body.newPassword());
    HttpSession session = request.getSession(false);
    if (session != null) {
      // 轮换会话标识：改密前后沿用同一个会话 ID，等于给会话固定攻击留了一个已认证的入口
      request.changeSessionId();
      session.setAttribute(
          ActorPrincipal.SESSION_ATTRIBUTE, new SessionActor(actor.accountIdValue(), newVersion));
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("changed", true);
    data.put("mustChangePassword", false);
    data.put("authVersion", newVersion);
    data.put("note", "其他设备上的会话已失效；当前会话已续期");
    return ResponseEntity.ok(ApiResponse.ok(data, TraceIdHolder.current()));
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
    // 与登录响应用同一份推导（Capabilities.of），不在这里另列一遍能力名。
    view.put(
        "capabilities",
        com.cognitanexus.core.platform.security.Capabilities.of(actor).stream().sorted().toList());
    view.put("authVersion", actor.authVersion());
    // 与登录响应同源：刷新页面后仍要知道「这是管理员重置的一次性口令，尚未修改」
    view.put("mustChangePassword", authService.mustChangePassword(actor.accountIdValue()));
    return ResponseEntity.ok(ApiResponse.ok(view, TraceIdHolder.current()));
  }
}
