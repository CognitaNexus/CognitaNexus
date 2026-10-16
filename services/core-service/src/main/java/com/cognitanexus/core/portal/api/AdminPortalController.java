package com.cognitanexus.core.portal.api;

import com.cognitanexus.core.platform.security.ActorContextHolder;
import com.cognitanexus.core.platform.util.PageRequest;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import com.cognitanexus.core.portal.application.PortalService;
import com.cognitanexus.core.portal.domain.PortalChangeRequest;
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
 * 认领与门户变更的审核入口。
 *
 * <p>依据：架构 V0.2 §10.1、AC-IMPL-FR05-02、AC-IMPL-FR06-02。审核人只能看到自己有权处理的队列；
 * 材料内容不在列表响应中出现，必须单独经鉴权代理下载。
 */
@RestController
@RequestMapping("/api/v1/admin/core")
public class AdminPortalController {

  private final PortalService portal;

  public AdminPortalController(PortalService portal) {
    this.portal = portal;
  }

  @GetMapping("/claims")
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> claims(
      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
    PageRequest pageRequest = PageRequest.of(page, size);
    List<Map<String, Object>> items =
        portal.listClaimsForReview(ActorContextHolder.currentOrNull(), pageRequest).stream()
            .map(AccountController::claimView)
            .toList();
    return ResponseEntity.ok(
        ApiResponse.ok(
            items,
            Map.of(
                "page", pageRequest.page(),
                "size", pageRequest.size(),
                "total", portal.countOpenClaims()),
            TraceIdHolder.current()));
  }

  /**
   * 裁决认领申请。
   *
   * <p>竞争认领的结果是 200 加 DISPUTED 状态，而不是错误：状态本身已合法落库，
   * 用异常表达会让这次裁决痕迹一起回滚。
   */
  @PostMapping("/claims/{claimId}/decision")
  public ResponseEntity<ApiResponse<Map<String, Object>>> decide(
      @PathVariable String claimId, @RequestBody DecisionRequest body) {
    PortalService.DecisionResult result =
        portal.decideClaim(
            ActorContextHolder.currentOrNull(), claimId, body.decision(), body.reason());
    return ResponseEntity.ok(ApiResponse.ok(result.toView(), TraceIdHolder.current()));
  }

  @GetMapping("/portal-changes")
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> changes(
      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
    PageRequest pageRequest = PageRequest.of(page, size);
    List<Map<String, Object>> items =
        portal.listPendingChanges(ActorContextHolder.currentOrNull(), pageRequest).stream()
            .map(AccountController::changeView)
            .toList();
    return ResponseEntity.ok(
        ApiResponse.ok(
            items,
            Map.of(
                "page", pageRequest.page(),
                "size", pageRequest.size(),
                "total", portal.countPendingChanges()),
            TraceIdHolder.current()));
  }

  @PostMapping("/portal-changes/{changeId}/decision")
  public ResponseEntity<ApiResponse<Map<String, Object>>> decideChange(
      @PathVariable String changeId, @RequestBody ChangeDecisionRequest body) {
    PortalChangeRequest change =
        portal.decideChange(
            ActorContextHolder.currentOrNull(), changeId, body.approve(), body.reason());
    return ResponseEntity.ok(
        ApiResponse.ok(AccountController.changeView(change), TraceIdHolder.current()));
  }

  /** 认领裁决请求体。 */
  public record DecisionRequest(String decision, String reason) {}

  /** 变更裁决请求体。 */
  public record ChangeDecisionRequest(boolean approve, String reason) {}
}
