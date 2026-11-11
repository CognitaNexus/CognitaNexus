package com.cognitanexus.core.identity.api;

import com.cognitanexus.core.identity.application.AccountClosureService;
import com.cognitanexus.core.platform.security.ActorContextHolder;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import java.util.LinkedHashMap;
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

/**
 * 注销申请的管理端。
 *
 * <p>路径独立于 /accounts：申请是「流程」不是账号属性，混在账号资源下会让「对某个账号做了什么」
 * 与「有个待处理申请」两件事挤在同一层路径里。
 */
@RestController
@RequestMapping("/api/v1/admin/core/closure-requests")
public class AdminClosureController {

  private final AccountClosureService closures;

  public AdminClosureController(AccountClosureService closures) {
    this.closures = closures;
  }

  @GetMapping
  public ResponseEntity<ApiResponse<Map<String, Object>>> pending(
      @RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset) {
    List<AccountClosureService.ClosureView> rows =
        closures.pending(ActorContextHolder.currentOrNull(), limit, offset);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put(
        "items",
        rows.stream()
            .map(
                row -> {
                  Map<String, Object> view = new LinkedHashMap<>();
                  view.put("id", row.id());
                  view.put("accountId", row.accountId());
                  view.put("accountUsername", row.accountUsername());
                  view.put("reason", row.reason());
                  view.put("status", row.status());
                  view.put("requestedAt", row.requestedAt());
                  view.put("effectiveAt", row.effectiveAt());
                  return view;
                })
            .toList());
    payload.put("total", closures.countPending());
    payload.put("note", "effectiveAt 之前不能执行注销（冷静期）。执行只停用账号，不删除任何业务数据。");
    return ResponseEntity.ok(ApiResponse.ok(payload, TraceIdHolder.current()));
  }

  @PostMapping("/{requestId}/decide")
  public ResponseEntity<ApiResponse<Map<String, Object>>> decide(
      @PathVariable String requestId, @RequestBody DecideBody body) {
    Map<String, Object> result =
        closures.decide(
            ActorContextHolder.currentOrNull(), requestId, body.decision(), body.note());
    return ResponseEntity.ok(ApiResponse.ok(result, TraceIdHolder.current()));
  }

  /** 裁决请求体。decision 取 APPROVE / REJECT。 */
  public record DecideBody(String decision, String note) {}
}
