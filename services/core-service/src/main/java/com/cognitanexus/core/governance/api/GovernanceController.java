package com.cognitanexus.core.governance.api;

import com.cognitanexus.core.governance.application.GovernanceService;
import com.cognitanexus.core.governance.domain.DedupCandidate;
import com.cognitanexus.core.governance.domain.GovernanceAnomaly;
import com.cognitanexus.core.governance.domain.MergeLedgerEntry;
import com.cognitanexus.core.platform.security.ActorContextHolder;
import com.cognitanexus.core.platform.util.PageRequest;
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
 * 治理管理接口。
 *
 * <p>依据：架构 V0.2 §4.3、AC-IMPL-FR09-02、AC-IMPL-FR15-01。
 *
 * <p>合并与拆分必须显式指定幸存实体并说明理由；相似度本身不构成合并依据。
 */
@RestController
@RequestMapping("/api/v1/admin/core/governance")
public class GovernanceController {

  private final GovernanceService governance;

  public GovernanceController(GovernanceService governance) {
    this.governance = governance;
  }

  @GetMapping("/candidates")
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> candidates(
      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
    PageRequest pageRequest = PageRequest.of(page, size);
    List<Map<String, Object>> items =
        governance.listCandidates(pageRequest).stream()
            .map(GovernanceController::candidateView)
            .toList();
    return ResponseEntity.ok(
        ApiResponse.ok(
            items,
            Map.of(
                "page", pageRequest.page(),
                "size", pageRequest.size(),
                "total", governance.countOpenCandidates()),
            TraceIdHolder.current()));
  }

  @PostMapping("/candidates/{candidateId}/resolve")
  public ResponseEntity<ApiResponse<Map<String, Object>>> resolveCandidate(
      @PathVariable String candidateId, @RequestBody ResolveCandidateRequest body) {
    governance.resolveCandidate(
        candidateId,
        body.action(),
        body.survivorId(),
        body.reason(),
        ActorContextHolder.currentOrNull());
    return ResponseEntity.ok(ApiResponse.ok(Map.of("resolved", true), TraceIdHolder.current()));
  }

  @GetMapping("/anomalies")
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> anomalies(
      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
    PageRequest pageRequest = PageRequest.of(page, size);
    List<Map<String, Object>> items =
        governance.listAnomalies(pageRequest).stream()
            .map(GovernanceController::anomalyView)
            .toList();
    return ResponseEntity.ok(
        ApiResponse.ok(
            items,
            Map.of(
                "page", pageRequest.page(),
                "size", pageRequest.size(),
                "total", governance.countOpenAnomalies()),
            TraceIdHolder.current()));
  }

  @PostMapping("/anomalies/{anomalyId}/resolve")
  public ResponseEntity<ApiResponse<Map<String, Object>>> resolveAnomaly(
      @PathVariable String anomalyId) {
    governance.resolveAnomaly(anomalyId, ActorContextHolder.currentOrNull());
    return ResponseEntity.ok(ApiResponse.ok(Map.of("resolved", true), TraceIdHolder.current()));
  }

  @PostMapping("/achievements/merge")
  public ResponseEntity<ApiResponse<Map<String, Object>>> mergeAchievements(
      @RequestBody MergeRequest body) {
    governance.mergeAchievements(
        body.survivorId(), body.mergedId(), body.reason(), ActorContextHolder.currentOrNull());
    return ResponseEntity.ok(ApiResponse.ok(Map.of("merged", true), TraceIdHolder.current()));
  }

  @PostMapping("/scholars/merge")
  public ResponseEntity<ApiResponse<Map<String, Object>>> mergeScholars(
      @RequestBody MergeRequest body) {
    governance.mergeScholars(
        body.survivorId(), body.mergedId(), body.reason(), ActorContextHolder.currentOrNull());
    return ResponseEntity.ok(ApiResponse.ok(Map.of("merged", true), TraceIdHolder.current()));
  }

  @GetMapping("/ledger")
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> ledger(
      @RequestParam String entityId, @RequestParam(defaultValue = "50") int limit) {
    List<Map<String, Object>> items =
        governance.ledgerOf(entityId, Math.min(Math.max(limit, 1), 200)).stream()
            .map(GovernanceController::ledgerView)
            .toList();
    return ResponseEntity.ok(ApiResponse.ok(items, TraceIdHolder.current()));
  }

  private static Map<String, Object> candidateView(DedupCandidate candidate) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", candidate.id());
    view.put("achievementType", candidate.achievementType());
    view.put("leftAchievementId", candidate.leftAchievementId());
    view.put("rightAchievementId", candidate.rightAchievementId());
    view.put("evidence", candidate.evidence());
    view.put("status", candidate.status().name());
    view.put("createdAt", candidate.createdAt());
    return view;
  }

  private static Map<String, Object> anomalyView(GovernanceAnomaly anomaly) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", anomaly.id());
    view.put("kind", anomaly.kind());
    view.put("sourceRecordId", anomaly.sourceRecordId());
    view.put("entityId", anomaly.entityId());
    view.put("detail", anomaly.detail());
    view.put("status", anomaly.status());
    view.put("createdAt", anomaly.createdAt());
    return view;
  }

  private static Map<String, Object> ledgerView(MergeLedgerEntry entry) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", entry.id());
    view.put("operation", entry.operation().name());
    view.put("entityType", entry.entityType());
    view.put("survivorId", entry.survivorId());
    view.put("mergedId", entry.mergedId());
    view.put("snapshot", entry.snapshot());
    view.put("reason", entry.reason());
    view.put("operatorId", entry.operatorId());
    view.put("occurredAt", entry.occurredAt());
    return view;
  }

  /** 候选裁决请求体。 */
  public record ResolveCandidateRequest(String action, String survivorId, String reason) {}

  /** 合并请求体。 */
  public record MergeRequest(String survivorId, String mergedId, String reason) {}
}
