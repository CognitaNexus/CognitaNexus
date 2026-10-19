package com.cognitanexus.core.identity.api;

import com.cognitanexus.core.identity.application.AccountClosureService;
import com.cognitanexus.core.platform.security.ActorContextHolder;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号注销申请的本人侧。
 *
 * <p>注销是自助能力，但执行权在管理员：本人只能申请与撤销，不能自己把账号停掉。
 * 这不是不信任用户，而是「注销后既有业务关系如何处理」需要一个可追责的人来判断。
 */
@RestController
@RequestMapping("/api/v1/account/closure")
public class AccountClosureController {

  private final AccountClosureService closures;

  public AccountClosureController(AccountClosureService closures) {
    this.closures = closures;
  }

  /** 当前待处理申请。没有申请时 open=false，而不是 404：前端靠一个字段判断即可。 */
  @GetMapping
  public ResponseEntity<ApiResponse<Map<String, Object>>> mine() {
    AccountClosureService.ClosureView view = closures.mine(ActorContextHolder.currentOrNull());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("open", view != null);
    if (view != null) {
      payload.put("id", view.id());
      payload.put("reason", view.reason());
      payload.put("status", view.status());
      payload.put("requestedAt", view.requestedAt());
      payload.put("effectiveAt", view.effectiveAt());
      payload.put("note", "冷静期结束前不可执行；在此之前你随时可以撤销申请。执行后账号停用、会话失效，" + "但已产生的业务数据不会删除。");
    }
    return ResponseEntity.ok(ApiResponse.ok(payload, TraceIdHolder.current()));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<Map<String, Object>>> request(
      @RequestBody ClosureRequestBody body) {
    AccountClosureService.ClosureView view =
        closures.request(ActorContextHolder.currentOrNull(), body.reason());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", view.id());
    payload.put("status", view.status());
    payload.put("effectiveAt", view.effectiveAt());
    payload.put("note", "申请已登记。冷静期结束后可由管理员执行；期间你可以随时撤销。" + "执行注销只会停用账号，不会删除你的成果、认领与审计记录。");
    return ResponseEntity.ok(ApiResponse.ok(payload, TraceIdHolder.current()));
  }

  @PostMapping("/{requestId}/cancel")
  public ResponseEntity<ApiResponse<Map<String, Object>>> cancel(@PathVariable String requestId) {
    int affected = closures.cancel(ActorContextHolder.currentOrNull(), requestId);
    return ResponseEntity.ok(
        ApiResponse.ok(Map.of("cancelled", affected), TraceIdHolder.current()));
  }

  /** 申请请求体。理由必填：注销是不可逆操作，事后追查需要知道当时的原因。 */
  public record ClosureRequestBody(String reason) {}
}
