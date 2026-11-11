package com.cognitanexus.core.identity.api;

import com.cognitanexus.core.identity.application.AccountAdminService;
import com.cognitanexus.core.platform.security.ActorContextHolder;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 账号管理接口。停用只影响登录与受保护请求，不删除学者公开档案。 */
@RestController
@RequestMapping("/api/v1/admin/core/accounts")
public class AdminAccountController {

  private final AccountAdminService accountAdmin;

  public AdminAccountController(AccountAdminService accountAdmin) {
    this.accountAdmin = accountAdmin;
  }

  @GetMapping
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(
      @RequestParam(defaultValue = "50") int limit) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            accountAdmin.list(ActorContextHolder.currentOrNull(), limit), TraceIdHolder.current()));
  }

  @PostMapping("/{accountId}/disable")
  public ResponseEntity<ApiResponse<Map<String, Object>>> disable(
      @PathVariable String accountId, @RequestBody DisableRequest body) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            accountAdmin.disable(ActorContextHolder.currentOrNull(), accountId, body.reason()),
            TraceIdHolder.current()));
  }

  /**
   * 重置指定账号的口令。
   *
   * <p>不传 newPassword 时由服务端生成一次性口令。无论哪种方式，重置后都会置「需修改」标记，
   * 本人登录时页面会提示尽快改密。
   */
  @PostMapping("/{accountId}/password-reset")
  public ResponseEntity<ApiResponse<Map<String, Object>>> resetPassword(
      @PathVariable String accountId,
      @Valid @RequestBody IdentityRequests.ResetPasswordRequest body) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            accountAdmin.resetPassword(
                ActorContextHolder.currentOrNull(), accountId, body.newPassword(), body.reason()),
            TraceIdHolder.current()));
  }

  @PostMapping("/{accountId}/roles")
  public ResponseEntity<ApiResponse<Map<String, Object>>> grantRole(
      @PathVariable String accountId, @RequestBody RoleRequest body) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            accountAdmin.grantRole(ActorContextHolder.currentOrNull(), accountId, body.role()),
            TraceIdHolder.current()));
  }

  /**
   * 撤销角色。
   *
   * <p>用 DELETE 而不是 POST /roles/revoke：角色是一种成员关系，撤销就是删除这条关系；
   * 动词化的 URL 会让「授予」和「撤销」看起来像两件事，而它们其实是同一个集合的两个方向。
   *
   * <p>理由走 query 参数而不是请求体：DELETE 带正文在部分代理与客户端上会被丢弃，
   * 而审计要求「必须说明理由」，丢掉正文就等于丢掉审计依据。
   */
  @DeleteMapping("/{accountId}/roles/{role}")
  public ResponseEntity<ApiResponse<Map<String, Object>>> revokeRole(
      @PathVariable String accountId, @PathVariable String role, @RequestParam String reason) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            accountAdmin.revokeRole(ActorContextHolder.currentOrNull(), accountId, role, reason),
            TraceIdHolder.current()));
  }

  /** 停用请求体。 */
  public record DisableRequest(String reason) {}

  /** 角色授予请求体。 */
  public record RoleRequest(String role) {}
}
