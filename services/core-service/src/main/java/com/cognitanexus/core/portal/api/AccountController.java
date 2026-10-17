package com.cognitanexus.core.portal.api;

import com.cognitanexus.core.catalog.application.ContributionRelationService;
import com.cognitanexus.core.identity.domain.PortalAuthority;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.ActorContextHolder;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import com.cognitanexus.core.portal.application.PortalService;
import com.cognitanexus.core.portal.domain.ClaimEvidence;
import com.cognitanexus.core.portal.domain.ClaimRequest;
import com.cognitanexus.core.portal.domain.PortalChangeRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 本人申请与私有状态。
 *
 * <p>依据：架构 V0.2 §10.1、§10.4。敏感写请求支持 Idempotency-Key：相同键同内容返回原结果，
 * 相同键不同内容返回 409。
 */
@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

  private static final long MAX_EVIDENCE_BYTES = 10L * 1024 * 1024;

  private final PortalService portal;
  private final ContributionRelationService relations;

  public AccountController(PortalService portal, ContributionRelationService relations) {
    this.portal = portal;
    this.relations = relations;
  }

  @PostMapping("/claims")
  public ResponseEntity<ApiResponse<Map<String, Object>>> submitClaim(
      @RequestBody SubmitClaimRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    ClaimRequest claim =
        portal.submitClaim(
            ActorContextHolder.currentOrNull(), body.scholarId(), body.statement(), idempotencyKey);
    // 新建的申请一定没有材料，但仍显式给空数组：同一个 DTO 在创建、列表、重提三处形状一致，
    // 前端不需要为「这个接口没有 evidence 字段」单独分支。
    return ResponseEntity.ok(ApiResponse.ok(claimView(claim, List.of()), TraceIdHolder.current()));
  }

  @GetMapping("/claims")
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> myClaims(
      @RequestParam(defaultValue = "50") int limit) {
    ActorContext actor = ActorContextHolder.currentOrNull();
    List<ClaimRequest> mine = portal.listMyClaims(actor, limit);
    // 材料元信息随申请一起给：申请人要能看到「我这份申请传了什么」，才谈得上补证与重提。
    // 材料本体仍走受保护下载，这里不含文件路径与文件名。
    Map<String, List<ClaimEvidence>> evidenceByClaim =
        portal.evidenceOfClaimsForApplicant(actor, mine.stream().map(ClaimRequest::id).toList());
    List<Map<String, Object>> items =
        mine.stream()
            .map(claim -> claimView(claim, evidenceByClaim.getOrDefault(claim.id(), List.of())))
            .toList();
    return ResponseEntity.ok(ApiResponse.ok(items, TraceIdHolder.current()));
  }

  /** 上传认领材料。只接受 PDF/PNG/JPEG，单文件不超过 10 MiB。 */
  @PostMapping("/claims/{claimId}/evidence")
  public ResponseEntity<ApiResponse<Map<String, Object>>> uploadEvidence(
      @PathVariable String claimId, @RequestPart("file") MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw com.cognitanexus.sharedkernel.BusinessException.invalidArgument("请选择要上传的材料文件");
    }
    if (file.getSize() > MAX_EVIDENCE_BYTES) {
      throw new com.cognitanexus.sharedkernel.BusinessException(
          com.cognitanexus.sharedkernel.ErrorCode.PAYLOAD_TOO_LARGE, "单个材料不得超过 10 MiB");
    }
    byte[] content;
    try {
      content = file.getBytes();
    } catch (java.io.IOException ex) {
      throw new com.cognitanexus.sharedkernel.BusinessException(
          com.cognitanexus.sharedkernel.ErrorCode.INVALID_ARGUMENT, "材料读取失败");
    }
    ClaimEvidence evidence =
        portal.attachEvidence(
            ActorContextHolder.currentOrNull(),
            claimId,
            file.getOriginalFilename(),
            file.getContentType(),
            content);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", evidence.id());
    view.put("claimRequestId", evidence.claimRequestId());
    view.put("contentType", evidence.contentType());
    view.put("sizeBytes", evidence.sizeBytes());
    view.put("contentHash", evidence.contentHash());
    view.put("createdAt", evidence.createdAt());
    view.put("note", "响应不含文件路径；材料经核心服务鉴权后代理下载");
    return ResponseEntity.ok(ApiResponse.ok(view, TraceIdHolder.current()));
  }

  /** 材料下载：仅申请人与审核者可读，文件不经静态目录暴露（A08）。 */
  @GetMapping("/claims/evidence/{evidenceId}")
  public ResponseEntity<byte[]> downloadEvidence(@PathVariable String evidenceId) {
    PortalService.EvidenceDownload download =
        portal.downloadEvidence(ActorContextHolder.currentOrNull(), evidenceId);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.parseMediaType(download.evidence().contentType()));
    headers.setContentDispositionFormData("attachment", download.evidence().fileRef());
    headers.setContentLength(download.content().length);
    return ResponseEntity.ok().headers(headers).body(download.content());
  }

  @PostMapping("/claims/{claimId}/resubmit")
  public ResponseEntity<ApiResponse<Map<String, Object>>> resubmit(
      @PathVariable String claimId, @RequestBody ResubmitRequest body) {
    ActorContext actor = ActorContextHolder.currentOrNull();
    ClaimRequest claim = portal.resubmit(actor, claimId, body.statement());
    // 重提后的材料清单要一起回给调用方：申请人刚补过材料，响应里没有清单会让人以为材料没保存。
    return ResponseEntity.ok(
        ApiResponse.ok(
            claimView(claim, portal.listEvidenceForClaim(actor, claim.id())),
            TraceIdHolder.current()));
  }

  @PostMapping("/portal-changes")
  public ResponseEntity<ApiResponse<Map<String, Object>>> submitChange(
      @RequestBody SubmitChangeRequest body) {
    PortalChangeRequest change =
        portal.submitChange(
            ActorContextHolder.currentOrNull(),
            body.scholarId(),
            body.fieldName(),
            body.proposedValue());
    return ResponseEntity.ok(ApiResponse.ok(changeView(change), TraceIdHolder.current()));
  }

  @GetMapping("/portal-changes")
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> myChanges(
      @RequestParam(defaultValue = "50") int limit) {
    List<Map<String, Object>> items =
        portal.listMyChanges(ActorContextHolder.currentOrNull(), limit).stream()
            .map(AccountController::changeView)
            .toList();
    return ResponseEntity.ok(ApiResponse.ok(items, TraceIdHolder.current()));
  }

  /** 我的门户：当前账号持有的生效授权。 */
  @GetMapping("/portals")
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> myPortals() {
    List<PortalAuthority> authorities = portal.myPortals(ActorContextHolder.currentOrNull());
    List<Map<String, Object>> items = new ArrayList<>();
    for (PortalAuthority authority : authorities) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("authorityId", authority.id());
      view.put("scholarId", authority.scholarId());
      view.put("grantedAt", authority.grantedAt());
      view.put("claimRequestId", authority.claimRequestId());
      items.add(view);
    }
    return ResponseEntity.ok(ApiResponse.ok(items, TraceIdHolder.current()));
  }

  /** 我的成果：当前账号所管理门户上的参与关系，含当前状态与可执行动作。 */
  @GetMapping("/contributions")
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> myContributions(
      @RequestParam String scholarId, @RequestParam(defaultValue = "50") int limit) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            relations.listOwnedContributions(ActorContextHolder.currentOrNull(), scholarId, limit),
            TraceIdHolder.current()));
  }

  /** 本人确认参与关系。确认只改状态，不删除关系、不覆盖来源事实。 */
  @PostMapping("/contributions/{contributionId}/confirm")
  public ResponseEntity<ApiResponse<Map<String, Object>>> confirmContribution(
      @PathVariable String contributionId) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            relations.confirmByOwner(ActorContextHolder.currentOrNull(), contributionId),
            TraceIdHolder.current()));
  }

  /** 本人对参与关系提出异议：须写理由，裁决前不计入确定合作。 */
  @PostMapping("/contributions/{contributionId}/dispute")
  public ResponseEntity<ApiResponse<Map<String, Object>>> disputeContribution(
      @PathVariable String contributionId, @RequestBody DisputeRequest body) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            relations.disputeByOwner(
                ActorContextHolder.currentOrNull(), contributionId, body.reason()),
            TraceIdHolder.current()));
  }

  /** 本人撤回异议：关系回到自动关联，而不是直接变成已确认。 */
  @PostMapping("/contributions/{contributionId}/withdraw-dispute")
  public ResponseEntity<ApiResponse<Map<String, Object>>> withdrawDispute(
      @PathVariable String contributionId) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            relations.withdrawDisputeByOwner(ActorContextHolder.currentOrNull(), contributionId),
            TraceIdHolder.current()));
  }

  static Map<String, Object> claimView(ClaimRequest claim) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", claim.id());
    view.put("scholarId", claim.scholarId());
    view.put("status", claim.status().name());
    view.put("statement", claim.statement());
    view.put("submittedAt", claim.submittedAt());
    view.put("decidedAt", claim.decidedAt());
    view.put("decisionReason", claim.decisionReason());
    view.put("version", claim.version());
    view.put("canResubmit", claim.status() == ClaimRequest.Status.NEED_MORE_EVIDENCE);
    view.put("canAttachEvidence", claim.status().open());
    return view;
  }

  /**
   * 带材料元信息的认领视图。
   *
   * <p>只给元信息，不含 fileRef／磁盘路径／上传时的原始文件名：材料本体必须走
   * {@code GET /api/v1/account/claims/evidence/{id}} 的鉴权代理下载，页面上的直链会把
   * 「谁可以读」交给浏览器决定。downloadUrl 是受保护端点的相对路径，不是文件地址。
   */
  static Map<String, Object> claimView(ClaimRequest claim, List<ClaimEvidence> evidences) {
    Map<String, Object> view = claimView(claim);
    List<Map<String, Object>> items = new ArrayList<>();
    for (ClaimEvidence evidence : evidences) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", evidence.id());
      item.put("contentType", evidence.contentType());
      item.put("sizeBytes", evidence.sizeBytes());
      item.put("contentHash", evidence.contentHash());
      item.put("createdAt", evidence.createdAt());
      item.put("downloadUrl", "/api/v1/account/claims/evidence/" + evidence.id());
      items.add(item);
    }
    view.put("evidence", items);
    view.put("evidenceNote", "材料本体不在列表响应里，只能经鉴权代理下载；申请人本人与授权审核者可读");
    return view;
  }

  static Map<String, Object> changeView(PortalChangeRequest change) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", change.id());
    view.put("scholarId", change.scholarId());
    view.put("fieldName", change.field().name());
    view.put("currentValue", change.currentValue());
    view.put("proposedValue", change.proposedValue());
    view.put("requiresReview", change.requiresReview());
    view.put("status", change.status().name());
    view.put("submittedAt", change.submittedAt());
    view.put("decisionReason", change.decisionReason());
    view.put("version", change.version());
    return view;
  }

  /** 提交认领请求体。 */
  public record SubmitClaimRequest(String scholarId, String statement) {}

  /** 补证请求体。 */
  public record ResubmitRequest(String statement) {}

  /** 提交变更请求体。 */
  public record SubmitChangeRequest(String scholarId, String fieldName, String proposedValue) {}

  /** 关系异议请求体。 */
  public record DisputeRequest(String reason) {}
}
