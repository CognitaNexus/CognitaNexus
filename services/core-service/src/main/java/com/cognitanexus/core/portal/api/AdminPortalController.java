package com.cognitanexus.core.portal.api;

import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.ActorContextHolder;
import com.cognitanexus.core.platform.util.PageRequest;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import com.cognitanexus.core.portal.application.PortalService;
import com.cognitanexus.core.portal.application.SubmissionService;
import com.cognitanexus.core.portal.domain.ClaimEvidence;
import com.cognitanexus.core.portal.domain.ClaimRequest;
import com.cognitanexus.core.portal.domain.PortalChangeRequest;
import com.cognitanexus.core.portal.domain.Submission;
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
 * 材料<b>本体</b>不在列表响应中出现，列表只给类型、大小、上传时刻等元信息，内容必须单独经鉴权代理下载。
 */
@RestController
@RequestMapping("/api/v1/admin/core")
public class AdminPortalController {

  private final PortalService portal;
  private final SubmissionService submissionService;

  public AdminPortalController(PortalService portal, SubmissionService submissionService) {
    this.portal = portal;
    this.submissionService = submissionService;
  }

  @GetMapping("/claims")
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> claims(
      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
    PageRequest pageRequest = PageRequest.of(page, size);
    ActorContext actor = ActorContextHolder.currentOrNull();
    List<ClaimRequest> open = portal.listClaimsForReview(actor, pageRequest);
    // 审核者要能看出「这份申请有没有材料、有几份」，否则只能在页面点开下载链接去猜。
    // 材料内容仍不进入列表，只给元信息与受保护下载路径。
    Map<String, List<ClaimEvidence>> evidenceByClaim =
        portal.evidenceOfClaimsForReview(actor, open.stream().map(ClaimRequest::id).toList());
    List<Map<String, Object>> items =
        open.stream()
            .map(
                claim ->
                    AccountController.claimView(
                        claim, evidenceByClaim.getOrDefault(claim.id(), List.of())))
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

  // ---------------------------------------------------------------------------
  // 成果自助提交审核
  // ---------------------------------------------------------------------------

  /**
   * 待审队列。
   *
   * <p>列表里必须给出「疑似重复的是哪一条、题名是什么」：审核员判断一条提交能不能建档，
   * 九成靠这一点。只给一个 ID，他就得自己开新标签页搜一遍，而在队列里逐条这样做的人，
   * 最后都会跳过这一步。
   */
  @GetMapping("/submissions")
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> submissions(
      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
    PageRequest pageRequest = PageRequest.of(page, size);
    List<Submission> open =
        submissionService.queue(
            ActorContextHolder.currentOrNull(), pageRequest.size(), pageRequest.offset());
    return ResponseEntity.ok(
        ApiResponse.ok(
            submissionService.views(open),
            Map.of(
                "page", pageRequest.page(),
                "size", pageRequest.size(),
                "total", submissionService.countOpen()),
            TraceIdHolder.current()));
  }

  /** 裁决一条提交：{@code APPROVE} 建档、{@code REJECT} 驳回、{@code NEED_MORE_INFO} 要求补充。 */
  @PostMapping("/submissions/{submissionId}/decision")
  public ResponseEntity<ApiResponse<Map<String, Object>>> decideSubmission(
      @PathVariable String submissionId, @RequestBody DecisionRequest body) {
    SubmissionService.DecisionResult result =
        submissionService.decide(
            ActorContextHolder.currentOrNull(), submissionId, body.decision(), body.reason());
    return ResponseEntity.ok(
        ApiResponse.ok(
            Map.of(
                "status", result.status(),
                "achievementId", result.achievementId() == null ? "" : result.achievementId(),
                "message", result.message()),
            TraceIdHolder.current()));
  }
}
