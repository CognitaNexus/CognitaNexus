package com.cognitanexus.core.catalog.api;

import com.cognitanexus.core.catalog.application.AdminCatalogService;
import com.cognitanexus.core.catalog.application.ContributionRelationService;
import com.cognitanexus.core.catalog.application.SourceAdminService;
import com.cognitanexus.core.platform.audit.AuditQueryService;
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
 * 核心管理端接口。
 *
 * <p>依据：架构 V0.2 §10.1。路由前缀 /api/v1/admin/core/* 由核心服务拥有；能力校验在应用层完成，
 * URL 前缀不构成授权依据。
 */
@RestController
@RequestMapping("/api/v1/admin/core")
public class AdminCatalogController {

  private final AdminCatalogService adminCatalog;
  private final SourceAdminService sourceAdmin;
  private final AuditQueryService auditQuery;
  private final ContributionRelationService relations;

  public AdminCatalogController(
      AdminCatalogService adminCatalog,
      SourceAdminService sourceAdmin,
      AuditQueryService auditQuery,
      ContributionRelationService relations) {
    this.adminCatalog = adminCatalog;
    this.sourceAdmin = sourceAdmin;
    this.auditQuery = auditQuery;
    this.relations = relations;
  }

  @GetMapping("/overview")
  public ResponseEntity<ApiResponse<Map<String, Object>>> overview() {
    return ResponseEntity.ok(
        ApiResponse.ok(
            adminCatalog.overview(ActorContextHolder.currentOrNull()), TraceIdHolder.current()));
  }

  @GetMapping("/achievements/pending")
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> pendingAchievements(
      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
    PageRequest pageRequest = PageRequest.of(page, size);
    List<Map<String, Object>> items =
        adminCatalog.listPending(ActorContextHolder.currentOrNull(), pageRequest);
    return ResponseEntity.ok(
        ApiResponse.ok(
            items,
            Map.of(
                "page", pageRequest.page(),
                "size", pageRequest.size(),
                "total", adminCatalog.countPending()),
            TraceIdHolder.current()));
  }

  @PostMapping("/achievements/{achievementId}/confirm")
  public ResponseEntity<ApiResponse<Map<String, Object>>> confirm(
      @PathVariable String achievementId, @RequestBody DecisionRequest body) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            adminCatalog.confirmPublication(
                ActorContextHolder.currentOrNull(), achievementId, body.reason()),
            TraceIdHolder.current()));
  }

  @PostMapping("/achievements/{achievementId}/withdraw")
  public ResponseEntity<ApiResponse<Map<String, Object>>> withdraw(
      @PathVariable String achievementId, @RequestBody DecisionRequest body) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            adminCatalog.withdraw(ActorContextHolder.currentOrNull(), achievementId, body.reason()),
            TraceIdHolder.current()));
  }

  /** 处于争议状态的参与关系：治理端的关系裁决队列。 */
  @GetMapping("/contributions/disputed")
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> disputedContributions(
      @RequestParam(defaultValue = "50") int limit) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            relations.listDisputed(ActorContextHolder.currentOrNull(), limit),
            TraceIdHolder.current()));
  }

  /**
   * 裁决一条参与关系。
   *
   * <p>结论只有三种：EXCLUDE（判定不成立）、CONFIRM（核验后确认）、RESTORE（撤销此前结论回到
   * 自动关联）。裁决只改关系状态，不删除关系行，来源记录与发生时机机构原样保留。
   */
  @PostMapping("/contributions/{contributionId}/resolution")
  public ResponseEntity<ApiResponse<Map<String, Object>>> resolveContribution(
      @PathVariable String contributionId, @RequestBody RelationResolutionRequest body) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            relations.resolveByAdmin(
                ActorContextHolder.currentOrNull(), contributionId, body.decision(), body.reason()),
            TraceIdHolder.current()));
  }

  /** 关系裁决请求体。 */
  public record RelationResolutionRequest(String decision, String reason) {}

  @GetMapping("/achievements/{achievementId}/provenance")
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> provenance(
      @PathVariable String achievementId) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            adminCatalog.provenanceOf(ActorContextHolder.currentOrNull(), achievementId),
            TraceIdHolder.current()));
  }

  @GetMapping("/sources")
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> sources() {
    List<Map<String, Object>> items =
        sourceAdmin.listSources(ActorContextHolder.currentOrNull()).stream()
            .map(
                config -> {
                  Map<String, Object> view = new LinkedHashMap<>();
                  view.put("id", config.id());
                  view.put("sourceCode", config.sourceCode());
                  view.put("displayName", config.displayName());
                  view.put("enabled", config.enabled());
                  view.put("licenseScope", config.licenseScope());
                  view.put("baseUrl", config.baseUrl());
                  view.put("configVersion", config.configVersion());
                  view.put("allowedFields", config.allowedFields());
                  return view;
                })
            .toList();
    return ResponseEntity.ok(ApiResponse.ok(items, TraceIdHolder.current()));
  }

  @PostMapping("/sources")
  public ResponseEntity<ApiResponse<Map<String, Object>>> registerSource(
      @RequestBody SourceRegisterRequest body) {
    SourceAdminService.RegisterSourceCommand command =
        new SourceAdminService.RegisterSourceCommand(
            body.sourceCode(),
            body.displayName(),
            body.licenseScope(),
            body.baseUrl(),
            body.allowedFields(),
            body.enabled(),
            body.licenseReviewed());
    var registered = sourceAdmin.registerSource(ActorContextHolder.currentOrNull(), command);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", registered.id());
    view.put("sourceCode", registered.sourceCode());
    view.put("displayName", registered.displayName());
    view.put("enabled", registered.enabled());
    view.put("licenseScope", registered.licenseScope());
    view.put("baseUrl", registered.baseUrl());
    view.put("configVersion", registered.configVersion());
    view.put("allowedFields", registered.allowedFields());
    return ResponseEntity.ok(ApiResponse.ok(view, TraceIdHolder.current()));
  }

  @PostMapping("/sources/{sourceId}/enabled")
  public ResponseEntity<ApiResponse<SourceAdminService.TightenOutcome>> setSourceEnabled(
      @PathVariable String sourceId, @RequestBody SourceEnabledRequest body) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            sourceAdmin.setEnabled(
                ActorContextHolder.currentOrNull(), sourceId, body.enabled(), body.reason()),
            TraceIdHolder.current()));
  }

  @PostMapping("/sources/{sourceId}/allowed-fields")
  public ResponseEntity<ApiResponse<Map<String, Object>>> setAllowedFields(
      @PathVariable String sourceId, @RequestBody AllowedFieldsRequest body) {
    int affected =
        sourceAdmin.updateAllowedFields(
            ActorContextHolder.currentOrNull(), sourceId, body.allowedFields(), body.reason());
    return ResponseEntity.ok(ApiResponse.ok(Map.of("updated", affected), TraceIdHolder.current()));
  }

  @GetMapping("/audit")
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> audit(
      @RequestParam(required = false) String targetType,
      @RequestParam(required = false) String targetId,
      @RequestParam(required = false) String actorAccountId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    PageRequest pageRequest = PageRequest.of(page, size);
    var actor = ActorContextHolder.currentOrNull();
    List<Map<String, Object>> items =
        auditQuery.search(actor, targetType, targetId, actorAccountId, pageRequest);
    long total = auditQuery.count(actor, targetType, targetId, actorAccountId);
    return ResponseEntity.ok(
        ApiResponse.ok(
            items,
            Map.of("page", pageRequest.page(), "size", pageRequest.size(), "total", total),
            TraceIdHolder.current()));
  }

  /** 管理决策请求体。 */
  public record DecisionRequest(String reason) {}

  /** 来源启用/停用请求体。 */
  public record SourceEnabledRequest(boolean enabled, String reason) {}

  /** 登记来源请求体。 */
  public record SourceRegisterRequest(
      String sourceCode,
      String displayName,
      String licenseScope,
      String baseUrl,
      List<String> allowedFields,
      boolean enabled,
      boolean licenseReviewed) {}

  /** 字段白名单请求体。 */
  public record AllowedFieldsRequest(List<String> allowedFields, String reason) {}
}
