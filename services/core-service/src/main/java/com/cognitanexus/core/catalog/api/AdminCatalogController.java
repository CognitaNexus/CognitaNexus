package com.cognitanexus.core.catalog.api;

import com.cognitanexus.core.catalog.application.AdminCatalogService;
import com.cognitanexus.core.catalog.application.ContributionRelationService;
import com.cognitanexus.core.catalog.application.SourceAdminService;
import com.cognitanexus.core.platform.audit.AuditQueryService;
import com.cognitanexus.core.platform.security.ActorContextHolder;
import com.cognitanexus.core.platform.security.Capabilities;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.platform.util.PageRequest;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import com.cognitanexus.core.portal.application.PortalService;
import com.cognitanexus.sharedkernel.BusinessException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
  private final PortalService portal;

  public AdminCatalogController(
      AdminCatalogService adminCatalog,
      SourceAdminService sourceAdmin,
      AuditQueryService auditQuery,
      ContributionRelationService relations,
      PortalService portal) {
    this.adminCatalog = adminCatalog;
    this.sourceAdmin = sourceAdmin;
    this.auditQuery = auditQuery;
    this.relations = relations;
    this.portal = portal;
  }

  @GetMapping("/overview")
  public ResponseEntity<ApiResponse<Map<String, Object>>> overview() {
    return ResponseEntity.ok(
        ApiResponse.ok(
            adminCatalog.overview(ActorContextHolder.currentOrNull()), TraceIdHolder.current()));
  }

  /**
   * 统一待办：管理员每天要看的那一屏。
   *
   * <p>依据 FR14「提供采集、异常治理、学者核验、认领审核、成果变更和账号维护统一入口」。
   * 在此之前，待办散在四处：待治理成果与去重候选在治理台、治理异常在同一页的下半截、
   * 认领与门户修订在审核台、采集失败在采集台、投递积压在一致性页。没有一处能回答
   * 「今天该处理什么」，漏审认领与变更因此是真实风险，而不是理论风险。
   *
   * <p>为什么在控制器里拼而不是塞进 {@code AdminCatalogService.overview}：那会让目录服务反向依赖
   * 门户服务，把两个上下文的边界糊在一起；而这里本来就只是「把几个已存在的计数放到同一张表里」，
   * 没有业务判断。
   *
   * <p>权限：每一类待办只在该账号确实具备对应能力时才给出计数，否则为 {@code null}。
   * 给 0 是错的——「没有待办」与「你看不到」在运维决策上是两件事。
   */
  @GetMapping("/todos")
  public ResponseEntity<ApiResponse<Map<String, Object>>> todos() {
    var actor = ActorContextHolder.currentOrNull();
    Map<String, Object> view = new LinkedHashMap<>(adminCatalog.overview(actor));

    boolean canReviewClaims = Capabilities.has(actor, RoleNames.CAP_CLAIM_REVIEW);
    boolean canReviewChanges = Capabilities.has(actor, RoleNames.CAP_CHANGE_REVIEW);
    Long claims = canReviewClaims ? portal.countOpenClaims() : null;
    Long changes = canReviewChanges ? portal.countPendingChanges() : null;
    view.put("claimsPending", claims);
    view.put("portalChangesPending", changes);

    long total =
        ((Number) view.getOrDefault("achievementsPending", 0)).longValue()
            + ((Number) view.getOrDefault("dedupCandidatesOpen", 0)).longValue()
            + ((Number) view.getOrDefault("anomaliesOpen", 0)).longValue()
            + (claims == null ? 0 : claims)
            + (changes == null ? 0 : changes);
    view.put("todoTotal", total);
    view.put(
        "todoNote",
        "待办只统计「需要人做决定」的事项：待治理成果、未裁决重复候选、未处理异常、待审核认领、待审门户变更。"
            + "correctionsOpen（公众纠错）是 anomaliesOpen 的子集，已计入总数，不重复相加；"
            + "采集失败与投递积压属于运行状态，在各自的页面按状态观察，不计入本数；"
            + "为 null 的项表示当前账号没有该类审核能力，不是「没有待办」。");
    return ResponseEntity.ok(ApiResponse.ok(view, TraceIdHolder.current()));
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
            .map(SourceAdminService::toView)
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
    return ResponseEntity.ok(
        ApiResponse.ok(SourceAdminService.toView(registered), TraceIdHolder.current()));
  }

  /**
   * 采集开关（旧端点，语义已收敛）。
   *
   * <p>V11 起 {@code enabled=false} <b>不再收紧任何成果的可见性</b>，只停止调度新任务。
   * 这样改是刻意的：SRS 要求来源停用后历史合法成果仍可检索，而这个端点的本意就是「停用来源」。
   * 需要下线依赖它的成果时，请调用 {@code PUT /sources/{id}/display-license}——那是另一件事，
   * 必须由调用方明确说出来。
   *
   * <p>保留端点是为了不让现有调用方升级时直接 404；响应里的 {@code displayLicenseTouched=false}
   * 与 {@code tightenedCount} 恒为 0，是为了让「没有收紧」这件事有明确解释。
   */
  @Deprecated
  @PostMapping("/sources/{sourceId}/enabled")
  public ResponseEntity<ApiResponse<Map<String, Object>>> setSourceEnabled(
      @PathVariable String sourceId, @RequestBody SourceEnabledRequest body) {
    var outcome =
        sourceAdmin.setEnabled(
            ActorContextHolder.currentOrNull(), sourceId, body.enabled(), body.reason());
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("sourceCode", outcome.sourceCode());
    view.put("enabled", outcome.enabled());
    view.put("tightenedCount", 0);
    view.put("displayLicenseTouched", outcome.displayLicenseTouched());
    view.put("requiredPublicRevision", outcome.requiredPublicRevision());
    view.put("note", "该端点自 V11 起只切换采集开关，不再收紧可见性；撤销展示许可请用 PUT /sources/{id}/display-license");
    return ResponseEntity.ok(ApiResponse.ok(view, TraceIdHolder.current()));
  }

  /** 暂停或恢复采集：只影响调度，不触碰任何成果的可见性。 */
  @PostMapping("/sources/{sourceId}/collection")
  public ResponseEntity<ApiResponse<Map<String, Object>>> setSourceCollection(
      @PathVariable String sourceId, @RequestBody SourceCollectionRequest body) {
    var outcome =
        sourceAdmin.setCollectionPaused(
            ActorContextHolder.currentOrNull(), sourceId, body.paused(), body.reason());
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("sourceCode", outcome.sourceCode());
    view.put("enabled", outcome.enabled());
    view.put("displayLicenseTouched", outcome.displayLicenseTouched());
    view.put("requiredPublicRevision", outcome.requiredPublicRevision());
    return ResponseEntity.ok(ApiResponse.ok(view, TraceIdHolder.current()));
  }

  /**
   * 设定展示许可。
   *
   * <p>用 {@code status} 而不是两个布尔：许可只有「授予／撤销」两种，写成布尔会让
   * 「我没有要改许可，只是传了个默认值」变成一次静默撤权。
   */
  @PutMapping("/sources/{sourceId}/display-license")
  public ResponseEntity<ApiResponse<Map<String, Object>>> setDisplayLicense(
      @PathVariable String sourceId, @RequestBody SourceDisplayLicenseRequest body) {
    var actor = ActorContextHolder.currentOrNull();
    if (body == null || body.status() == null || body.status().isBlank()) {
      throw BusinessException.invalidArgument("必须给出 status：GRANTED 或 REVOKED");
    }
    var outcome =
        switch (body.status().trim().toUpperCase()) {
          case "REVOKED" -> sourceAdmin.revokeDisplayLicense(actor, sourceId, body.reason());
          case "GRANTED" -> sourceAdmin.restoreDisplayLicense(actor, sourceId, body.reason());
          default -> throw BusinessException.invalidArgument("status 只能是 GRANTED 或 REVOKED");
        };
    return ResponseEntity.ok(ApiResponse.ok(toOutcomeView(outcome), TraceIdHolder.current()));
  }

  /** 某来源的许可变更作业历史（最近的在前）。 */
  @GetMapping("/sources/{sourceId}/display-license/revocations")
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listRevocations(
      @PathVariable String sourceId, @RequestParam(required = false) Integer limit) {
    List<Map<String, Object>> items =
        sourceAdmin
            .listRevocations(
                ActorContextHolder.currentOrNull(), sourceId, limit == null ? 20 : limit)
            .stream()
            .map(
                job -> {
                  Map<String, Object> view = new LinkedHashMap<>();
                  view.put("id", job.id());
                  view.put("sourceId", job.sourceId());
                  view.put("requestedBy", job.requestedBy());
                  view.put("reason", job.reason());
                  view.put("targetCount", job.targetCount());
                  view.put("processedCount", job.processedCount());
                  view.put("status", job.status());
                  view.put("lastError", job.lastError());
                  view.put("createdAt", job.createdAt());
                  view.put("finishedAt", job.finishedAt());
                  return view;
                })
            .toList();
    return ResponseEntity.ok(ApiResponse.ok(items, TraceIdHolder.current()));
  }

  /** 续跑未完成的许可变更作业（单次调用有批数上限，超出后由此继续）。 */
  @PostMapping("/sources/{sourceId}/display-license/revocations/{jobId}/resume")
  public ResponseEntity<ApiResponse<Map<String, Object>>> resumeRevocation(
      @PathVariable String sourceId, @PathVariable String jobId) {
    var outcome = sourceAdmin.resumeJob(ActorContextHolder.currentOrNull(), jobId);
    return ResponseEntity.ok(ApiResponse.ok(toOutcomeView(outcome), TraceIdHolder.current()));
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

  /** 来源启用/停用请求体（旧端点）。 */
  public record SourceEnabledRequest(boolean enabled, String reason) {}

  /** 采集开关请求体。{@code paused} 为 true 表示暂停采集。 */
  public record SourceCollectionRequest(boolean paused, String reason) {}

  /** 展示许可请求体。{@code status} 只能是 GRANTED 或 REVOKED。 */
  public record SourceDisplayLicenseRequest(String status, String reason) {}

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

  /**
   * 许可变更结果的对外视图。
   *
   * <p>{@code residualCount} 与 {@code complete} 必须都给出：只给「处理了 N 条」时，调用方无法
   * 判断还有没有剩下（ADR-001 §2.2 记录的正是这个缺陷——旧响应只有 {@code tightenedCount}，
   * 5001 条时返回 5000，看起来像「做完了」）。
   */
  private static Map<String, Object> toOutcomeView(
      SourceAdminService.DisplayLicenseOutcome outcome) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("sourceCode", outcome.sourceCode());
    view.put("displayLicenseStatus", outcome.displayLicenseStatus());
    view.put("jobId", outcome.jobId());
    view.put("targetCount", outcome.targetCount());
    view.put("processedCount", outcome.processedCount());
    view.put("residualCount", outcome.residualCount());
    view.put("complete", outcome.complete());
    view.put("note", outcome.note());
    view.put("requiredPublicRevision", outcome.requiredPublicRevision());
    return view;
  }
}
