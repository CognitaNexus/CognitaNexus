package com.cognitanexus.core.governance.api;

import com.cognitanexus.core.governance.application.DataQualityScanService;
import com.cognitanexus.core.governance.application.GovernanceRepositories;
import com.cognitanexus.core.governance.application.GovernanceResolutionWorkflow;
import com.cognitanexus.core.governance.application.GovernanceService;
import com.cognitanexus.core.governance.domain.DedupCandidate;
import com.cognitanexus.core.governance.domain.GovernanceAnomaly;
import com.cognitanexus.core.governance.domain.MergeLedgerEntry;
import com.cognitanexus.core.platform.security.ActorContextHolder;
import com.cognitanexus.core.platform.util.PageRequest;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import java.util.ArrayList;
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
  private final GovernanceResolutionWorkflow workflow;
  private final DataQualityScanService scans;

  public GovernanceController(
      GovernanceService governance,
      GovernanceResolutionWorkflow workflow,
      DataQualityScanService scans) {
    this.governance = governance;
    this.workflow = workflow;
    this.scans = scans;
  }

  @GetMapping("/candidates")
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> candidates(
      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
    PageRequest pageRequest = PageRequest.of(page, size);
    List<Map<String, Object>> items =
        governance.listCandidates(pageRequest, ActorContextHolder.currentOrNull()).stream()
            .map(GovernanceController::candidateView)
            .toList();
    return ResponseEntity.ok(
        ApiResponse.ok(
            items,
            Map.of(
                "page", pageRequest.page(),
                "size", pageRequest.size(),
                "total", governance.countOpenCandidates(ActorContextHolder.currentOrNull())),
            TraceIdHolder.current()));
  }

  @PostMapping("/candidates/{candidateId}/resolve")
  public ResponseEntity<ApiResponse<Map<String, Object>>> resolveCandidate(
      @PathVariable String candidateId, @RequestBody ResolveCandidateRequest body) {
    GovernanceResolutionWorkflow.Outcome outcome =
        workflow.resolve(
            candidateId,
            body.action(),
            body.survivorId(),
            body.reason(),
            ActorContextHolder.currentOrNull());
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("resolved", true);
    view.put("affected", outcome.affectedCount());
    view.put("released", outcome.released());
    view.put("releasedCount", outcome.released().size());
    return ResponseEntity.ok(ApiResponse.ok(view, TraceIdHolder.current()));
  }

  /**
   * 候选改判：把问题转到两端重定向后的最终实体上。
   *
   * <p>补这个入口的原因写在 {@code GovernanceService.rebaseCandidate}：合并之后，端点被并走的候选
   * 既不能再合并，也不该被驳回（驳回会写一条「判定为不同实体」的不实话）。此前预览提示里说「需要
   * 人工判断是改判为与幸存成果相关，还是直接驳回」，而改判这条路当时并不存在。
   *
   * <p>响应里同时给出承接候选与原候选的最终状态：调用方需要知道「问题去哪儿了」，而不只是「这条
   * 不在了」。两组标识都会参与发布重评估。
   */
  @PostMapping("/candidates/{candidateId}/rebase")
  public ResponseEntity<ApiResponse<Map<String, Object>>> rebaseCandidate(
      @PathVariable String candidateId, @RequestBody RebaseCandidateRequest body) {
    GovernanceResolutionWorkflow.RebaseOutcome outcome =
        workflow.rebase(candidateId, body.reason(), ActorContextHolder.currentOrNull());
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("rebased", true);
    view.put("status", outcome.status());
    view.put("finalLeftId", outcome.finalLeftId());
    view.put("finalRightId", outcome.finalRightId());
    view.put("targetExisted", outcome.targetExisted());
    view.put("released", outcome.released());
    view.put("releasedCount", outcome.released().size());
    // rebasedToId 只在真的转到另一条候选时才写：原候选直接判为已合并时它不存在，
    // 而 null 字段会被全局 non_null 配置丢掉，因此「没有承接候选」由 status 判断，不靠字段有无。
    if (outcome.rebasedToId() != null) {
      view.put("rebasedToId", outcome.rebasedToId());
    }
    return ResponseEntity.ok(ApiResponse.ok(view, TraceIdHolder.current()));
  }

  /**
   * 治理工作台候选列表。
   *
   * <p>与 {@code /candidates} 的分工：那个按登记时间倒序，用来观察采集节奏；这个按可信度组织
   * （默认相似度降序）并支持按相似度下限筛选，用来处理积压。同时返回分档计数，让「先做哪一批」
   * 是一个有依据的判断。
   */
  @GetMapping("/candidates/workbench")
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> workbench(
      @RequestParam(required = false) Double minSimilarity,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    GovernanceService.CandidateFilter filter =
        GovernanceService.CandidateFilter.of(minSimilarity, sort);
    PageRequest pageRequest = PageRequest.of(page, size);
    List<com.cognitanexus.core.governance.application.GovernanceRepositories.ScoredCandidate>
        scored =
            governance.listCandidatesForWorkbench(
                filter, pageRequest, ActorContextHolder.currentOrNull());
    // 两端状态在这里实时取一次，供卡片直接显示「这一侧已经被并走了」。候选自带的 evidence 是
    // 登记那一刻的快照，用它渲染会把「当时的结论」当成「现在的事实」。
    List<String> endpointIds = new ArrayList<>();
    for (com.cognitanexus.core.governance.application.GovernanceRepositories.ScoredCandidate item :
        scored) {
      endpointIds.add(item.candidate().leftAchievementId());
      endpointIds.add(item.candidate().rightAchievementId());
    }
    Map<String, GovernanceRepositories.AchievementStateRepository.AchievementState> states =
        governance.statesOf(endpointIds, ActorContextHolder.currentOrNull());
    List<Map<String, Object>> items =
        scored.stream().map(item -> scoredCandidateView(item, states)).toList();
    GovernanceService.WorkbenchSummary summary =
        governance.workbenchSummary(ActorContextHolder.currentOrNull());
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("page", pageRequest.page());
    meta.put("size", pageRequest.size());
    meta.put(
        "total",
        governance.countCandidatesForWorkbench(filter, ActorContextHolder.currentOrNull()));
    meta.put("openTotal", summary.total());
    meta.put("high", summary.high());
    meta.put("mid", summary.mid());
    meta.put("low", summary.low());
    meta.put("highLow", summary.highLow());
    meta.put("midLow", summary.midLow());
    meta.put("allowedSorts", GovernanceService.CandidateFilter.allowedSorts());
    meta.put("bucketNote", "分档只用于排序待办，不构成自动裁决结论：任何一档都必须人工确认后才会改变候选状态");
    return ResponseEntity.ok(ApiResponse.ok(items, meta, TraceIdHolder.current()));
  }

  /**
   * 批量驳回候选（判定为不同实体）。
   *
   * <p>不提供批量合并：驳回错了可以再合并，合并错了要把关系、来源归属与门户授权拆回来，
   * 代价不对称。合并必须逐条指定幸存实体并发起独立请求。
   *
   * <p>裁决完会顺带重评估受影响成果的发布资格并回传结果——裁决解除的是「待治理」的成因之一，
   * 成果能否随之公开要由重评估按既定条件重新判定，不由这里直接决定。
   */
  @PostMapping("/candidates/batch-reject")
  public ResponseEntity<ApiResponse<Map<String, Object>>> batchReject(
      @RequestBody BatchRejectRequest body) {
    if (body == null || body.candidateIds() == null || body.candidateIds().isEmpty()) {
      throw com.cognitanexus.sharedkernel.BusinessException.invalidArgument("必须给出待裁决的候选标识");
    }
    GovernanceResolutionWorkflow.BatchOutcome outcome =
        workflow.batchReject(
            body.candidateIds(), body.reason(), ActorContextHolder.currentOrNull());
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("requested", outcome.requested());
    view.put("resolved", outcome.resolved());
    view.put("skipped", outcome.skipped());
    view.put("releasedCount", outcome.released().size());
    view.put("released", outcome.released());
    return ResponseEntity.ok(ApiResponse.ok(view, TraceIdHolder.current()));
  }

  @GetMapping("/anomalies")
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> anomalies(
      @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
    PageRequest pageRequest = PageRequest.of(page, size);
    List<Map<String, Object>> items =
        governance.listAnomalies(pageRequest, ActorContextHolder.currentOrNull()).stream()
            .map(GovernanceController::anomalyView)
            .toList();
    return ResponseEntity.ok(
        ApiResponse.ok(
            items,
            Map.of(
                "page", pageRequest.page(),
                "size", pageRequest.size(),
                "total", governance.countOpenAnomalies(ActorContextHolder.currentOrNull())),
            TraceIdHolder.current()));
  }

  /**
   * 运行一次数据质量巡检。
   *
   * <p>只读业务数据、只登记与复核异常，不修改任何成果、关系或归属。可重复运行：同一问题在未处理
   * 期间只占一条待办，重复运行刷新计数；已经消失的问题会被自动关闭并写明原因。
   *
   * <p>返回每一类检测项本次命中的条数、新建/复核/自动关闭的条数。检测项被上限截断时
   * {@code truncated=true}，此时不得把该条数当作全量结论。
   */
  @PostMapping("/anomalies/scan")
  public ResponseEntity<ApiResponse<Map<String, Object>>> scanAnomalies() {
    DataQualityScanService.Report report = scans.scan(ActorContextHolder.currentOrNull());
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("ruleVersion", report.ruleVersion());
    view.put("scannedAt", report.scannedAt());
    view.put("timeZone", report.timeZone());
    view.put("yearBounds", report.yearBounds());
    view.put("openAnomalies", report.openAnomalies());
    view.put("created", report.created());
    view.put("refreshed", report.refreshed());
    view.put("autoClosed", report.autoClosed());
    List<Map<String, Object>> findings = new ArrayList<>();
    for (DataQualityScanService.Finding finding : report.findings()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("kind", finding.kind());
      row.put("label", finding.label());
      row.put("granularity", finding.granularity().name());
      row.put("detected", finding.detected());
      row.put("created", finding.created());
      row.put("refreshed", finding.refreshed());
      row.put("autoClosed", finding.autoClosed());
      row.put("truncated", finding.truncated());
      findings.add(row);
    }
    view.put("findings", findings);
    return ResponseEntity.ok(ApiResponse.ok(view, TraceIdHolder.current()));
  }

  @PostMapping("/anomalies/{anomalyId}/resolve")
  public ResponseEntity<ApiResponse<Map<String, Object>>> resolveAnomaly(
      @PathVariable String anomalyId) {
    governance.resolveAnomaly(anomalyId, ActorContextHolder.currentOrNull());
    return ResponseEntity.ok(ApiResponse.ok(Map.of("resolved", true), TraceIdHolder.current()));
  }

  /**
   * 合并影响预览。
   *
   * <p>合并是一次不可逆的人工决定，唯一依据只能是「合并后会发生什么」。执行前先取这份只读后果，
   * 使「相似度够高就先合了再说」不再是唯一可行路径。
   */
  @GetMapping("/achievements/merge-preview")
  public ResponseEntity<ApiResponse<Map<String, Object>>> mergePreview(
      @RequestParam String survivorId, @RequestParam String mergedId) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            governance.previewMerge(survivorId, mergedId, ActorContextHolder.currentOrNull()),
            TraceIdHolder.current()));
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
        governance
            .ledgerOf(
                entityId, Math.min(Math.max(limit, 1), 200), ActorContextHolder.currentOrNull())
            .stream()
            .map(GovernanceController::ledgerView)
            .toList();
    return ResponseEntity.ok(ApiResponse.ok(items, TraceIdHolder.current()));
  }

  private static Map<String, Object> scoredCandidateView(
      com.cognitanexus.core.governance.application.GovernanceRepositories.ScoredCandidate scored,
      Map<String, GovernanceRepositories.AchievementStateRepository.AchievementState> states) {
    Map<String, Object> view = candidateView(scored.candidate());
    view.put("similarity", scored.similarity());
    DedupCandidate candidate = scored.candidate();
    view.put("left", endpointStateView(candidate.leftAchievementId(), states));
    view.put("right", endpointStateView(candidate.rightAchievementId(), states));
    // 处置可行性由服务端给出，而不是让前端自己拼：前端拼的话，「哪一侧不可作为幸存实体」这条
    // 规则会出现两份实现，后端改了口径前端不会跟着变。
    view.put("recommendedAction", recommendedAction(candidate, states));
    return view;
  }

  /**
   * 一端成果的实时状态。
   *
   * <p>查不到时 {@code known} 为 false，且不编造任何状态值：外键约束保证候选两端一定存在，
   * 真查不到说明数据被外部改动，此时如实说「查不到」比填一个看起来正常的状态安全。
   */
  private static Map<String, Object> endpointStateView(
      String achievementId,
      Map<String, GovernanceRepositories.AchievementStateRepository.AchievementState> states) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", achievementId);
    GovernanceRepositories.AchievementStateRepository.AchievementState state =
        states.get(achievementId);
    if (state == null) {
      view.put("known", false);
      return view;
    }
    view.put("known", true);
    view.put("title", state.title());
    view.put("achievementType", state.achievementType());
    view.put("dataNature", state.dataNature());
    view.put("visibility", state.visibility());
    view.put("effectiveStatus", state.effectiveStatus());
    view.put("merged", state.merged());
    view.put("publicationYear", state.publicationYear());
    view.put("yearPrecision", state.yearPrecision());
    // mergedIntoId 只在这一端真的被并走时才写：null 字段会被全局 non_null 配置丢掉，
    // 前端因此只能靠 merged 这个永不缺失的布尔判断，不能靠 mergedIntoId 是否存在。
    if (state.merged()) {
      view.put("mergedIntoId", state.mergedIntoId());
    }
    return view;
  }

  /**
   * 这一条候选能怎么处置。
   *
   * <p>规则与 {@code GovernanceService.mergeAchievements} 的阻断条件一致：任一端已被合并走时，
   * 合并会被服务端拒绝（重定向不覆盖首次结论），此时可选的处置只剩「驳回」。把这件事写在响应里
   * 而不是留给用户去撞错误码：工作台里确实存在这样一批候选——它们的另一端在别处被并走了，
   * 而候选本身没有作废，需要人判断是驳回还是先解析到最终实体。
   */
  private static String recommendedAction(
      DedupCandidate candidate,
      Map<String, GovernanceRepositories.AchievementStateRepository.AchievementState> states) {
    GovernanceRepositories.AchievementStateRepository.AchievementState left =
        states.get(candidate.leftAchievementId());
    GovernanceRepositories.AchievementStateRepository.AchievementState right =
        states.get(candidate.rightAchievementId());
    if (left == null || right == null) {
      return "UNKNOWN";
    }
    if (left.merged() || right.merged()) {
      return "REJECT_ONLY";
    }
    return "MERGE_OR_REJECT";
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
    if (candidate.rebasedToIdOrNull() != null) {
      view.put("rebasedToId", candidate.rebasedToIdOrNull());
    }
    return view;
  }

  /**
   * 异常视图。
   *
   * <p>除了原始的 {@code detail} 结构，另给一个永不为空的 {@code summary}：detail 是巡检与接入
   * 路径写入的结构化证据，字段随种类变化，界面直接渲染 Map 只会显示成 "[object Object]"。
   * 由服务端把「这一条在说什么」压成一句话，读的人不必知道每类异常的字段名。
   */
  private static Map<String, Object> anomalyView(GovernanceAnomaly anomaly) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", anomaly.id());
    view.put("kind", anomaly.kind());
    view.put("kindLabel", GovernanceAnomaly.label(anomaly.kind()));
    view.put("summary", summaryOf(anomaly));
    view.put("sourceRecordId", anomaly.sourceRecordId());
    view.put("entityId", anomaly.entityId());
    view.put("detail", anomaly.detail());
    view.put("status", anomaly.status());
    view.put("createdAt", anomaly.createdAt());
    return view;
  }

  /** 异常的一句话说明：优先用写入方给的 summary，其次拼出可读的通用描述。 */
  private static String summaryOf(GovernanceAnomaly anomaly) {
    Map<String, Object> detail = anomaly.detail();
    if (detail != null) {
      Object summary = detail.get("summary");
      if (summary instanceof String text && !text.isBlank()) {
        return text;
      }
    }
    String label = GovernanceAnomaly.label(anomaly.kind());
    if (anomaly.entityId() == null || anomaly.entityId().isBlank()) {
      return label + "（未关联具体实体，详情见 raw detail）";
    }
    return label + "：" + anomaly.entityId();
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

  /** 候选改判请求体。理由必填：改判会改变「这个问题由谁作答」，是必须可追溯的治理动作。 */
  public record RebaseCandidateRequest(String reason) {}

  /** 批量驳回请求体。 */
  public record BatchRejectRequest(List<String> candidateIds, String reason) {}

  /** 合并请求体。 */
  public record MergeRequest(String survivorId, String mergedId, String reason) {}
}
