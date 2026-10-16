package com.cognitanexus.core.portal.api;

import com.cognitanexus.core.identity.domain.PortalAuthority;
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

  public AccountController(PortalService portal) {
    this.portal = portal;
  }

  @PostMapping("/claims")
  public ResponseEntity<ApiResponse<Map<String, Object>>> submitClaim(
      @RequestBody SubmitClaimRequest body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    ClaimRequest claim =
        portal.submitClaim(
            ActorContextHolder.currentOrNull(), body.scholarId(), body.statement(), idempotencyKey);
    return ResponseEntity.ok(ApiResponse.ok(claimView(claim), TraceIdHolder.current()));
  }

  @GetMapping("/claims")
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> myClaims(
      @RequestParam(defaultValue = "50") int limit) {
    List<Map<String, Object>> items =
        portal.listMyClaims(ActorContextHolder.currentOrNull(), limit).stream()
            .map(AccountController::claimView)
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
    return ResponseEntity.ok(
        ApiResponse.ok(
            claimView(
                portal.resubmit(ActorContextHolder.currentOrNull(), claimId, body.statement())),
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
}
