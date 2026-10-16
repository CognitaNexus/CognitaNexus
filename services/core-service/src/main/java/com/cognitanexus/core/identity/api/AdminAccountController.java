package com.cognitanexus.core.identity.api;

import com.cognitanexus.core.identity.application.AccountAdminService;
import com.cognitanexus.core.platform.security.ActorContextHolder;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
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

  @PostMapping("/{accountId}/roles")
  public ResponseEntity<ApiResponse<Map<String, Object>>> grantRole(
      @PathVariable String accountId, @RequestBody RoleRequest body) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            accountAdmin.grantRole(ActorContextHolder.currentOrNull(), accountId, body.role()),
            TraceIdHolder.current()));
  }

  /** 停用请求体。 */
  public record DisableRequest(String reason) {}

  /** 角色授予请求体。 */
  public record RoleRequest(String role) {}
}
