package com.cognitanexus.ingestion.collect.api;

import com.cognitanexus.ingestion.collect.adapter.SourceAdapterRegistry;
import com.cognitanexus.ingestion.collect.application.CollectExecutionService;
import com.cognitanexus.ingestion.collect.application.CollectJobService;
import com.cognitanexus.ingestion.collect.application.CollectProperties;
import com.cognitanexus.ingestion.collect.application.CollectRepositories;
import com.cognitanexus.ingestion.collect.application.ImportService;
import com.cognitanexus.ingestion.collect.application.SourceRunConfigService;
import com.cognitanexus.ingestion.collect.domain.AdapterKind;
import com.cognitanexus.ingestion.collect.domain.CollectModels.CollectJob;
import com.cognitanexus.ingestion.collect.domain.CollectModels.SourceRunConfig;
import com.cognitanexus.ingestion.platform.security.ActorContextHolder;
import com.cognitanexus.ingestion.platform.web.ApiResponse;
import com.cognitanexus.ingestion.platform.web.TraceIdHolder;
import com.cognitanexus.sharedkernel.BusinessException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 采集管理端点。
 *
 * <p>依据：架构 V0.2 §10.1（路由表 {@code /api/v1/admin/ingestion/*}）。鉴权与 CSRF 已由
 * {@code AdminAuthFilter} 在进入本控制器之前完成——控制器里再写一遍「检查权限」只会造成两处
 * 判据各自演化。因此本类的方法体只做参数整形与调用应用服务。
 *
 * <p>所有返回都用统一响应包，列表用 items/page/size/total 结构，空结果返回 200 而不是 404。
 */
@RestController
@RequestMapping("/api/v1/admin/ingestion")
public class AdminIngestionController {

  private final SourceRunConfigService sources;
  private final CollectJobService jobs;
  private final CollectExecutionService execution;
  private final SourceAdapterRegistry adapters;
  private final CollectProperties properties;
  private final CollectRepositories.JobRepository jobRepository;
  private final ImportService imports;

  public AdminIngestionController(
      SourceRunConfigService sources,
      CollectJobService jobs,
      CollectExecutionService execution,
      SourceAdapterRegistry adapters,
      CollectProperties properties,
      CollectRepositories.JobRepository jobRepository,
      ImportService imports) {
    this.sources = sources;
    this.jobs = jobs;
    this.execution = execution;
    this.adapters = adapters;
    this.properties = properties;
    this.jobRepository = jobRepository;
    this.imports = imports;
  }

  // ---------------------------------------------------------------------------
  // 能力边界
  // ---------------------------------------------------------------------------

  /**
   * 服务能力描述。
   *
   * <p>把「已实现哪些来源」与「运行参数当前取值」显式暴露出来，是为了让管理界面能如实显示能力
   * 边界：未实现的来源种类在界面上不应表现为「可选」。
   */
  @GetMapping("/capabilities")
  public ApiResponse<Map<String, Object>> capabilities() {
    Map<String, Object> view = new LinkedHashMap<>();
    List<Map<String, Object>> kinds = new ArrayList<>();
    for (AdapterKind kind : AdapterKind.values()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("kind", kind.name());
      item.put("label", kind.label());
      item.put("implemented", adapters.implementedKinds().contains(kind.name()));
      kinds.add(item);
    }
    view.put("adapterKinds", kinds);
    view.put("batchSize", properties.batchSize());
    view.put("maxBatchBytes", properties.maxBatchBytes());
    view.put("maxPagesPerRun", properties.maxPagesPerRun());
    view.put("leaseSeconds", properties.leaseSeconds());
    view.put("workerEnabled", properties.workerEnabled());
    view.put("workerIntervalMs", properties.workerIntervalMs());
    view.put("maxHandoffAttempts", properties.maxHandoffAttempts());
    return ApiResponse.ok(view, TraceIdHolder.current());
  }

  // ---------------------------------------------------------------------------
  // 来源运行配置
  // ---------------------------------------------------------------------------

  @GetMapping("/sources")
  public ApiResponse<Map<String, Object>> listSources() {
    List<SourceRunConfig> configs = sources.list();
    List<Map<String, Object>> items =
        configs.stream().map(AdminIngestionController::toSourceView).toList();
    Map<String, Object> page = new LinkedHashMap<>();
    page.put("items", items);
    page.put("page", 1);
    page.put("size", items.size());
    page.put("total", items.size());
    return ApiResponse.ok(page, TraceIdHolder.current());
  }

  @GetMapping("/sources/{sourceId}")
  public ApiResponse<Map<String, Object>> sourceDetail(@PathVariable String sourceId) {
    return ApiResponse.ok(toSourceView(sources.require(sourceId)), TraceIdHolder.current());
  }

  @PostMapping("/sources")
  public ApiResponse<Map<String, Object>> registerSource(
      @RequestBody SourceRegisterRequest request) {
    if (request == null) {
      throw BusinessException.invalidArgument("请求体不能为空");
    }
    SourceRunConfig config =
        sources.register(
            ActorContextHolder.currentOrNull(),
            new SourceRunConfigService.RegisterCommand(
                request.sourceCode(),
                request.displayName(),
                AdapterKind.of(request.adapterKind()),
                request.baseEndpoint(),
                request.queryExpression(),
                request.pageSize() == null ? 0 : request.pageSize(),
                request.licenseReviewState(),
                request.licenseNote(),
                request.rateLimitPerMinute() == null ? 0 : request.rateLimitPerMinute(),
                Boolean.TRUE.equals(request.enabled())));
    return ApiResponse.ok(toSourceView(config), TraceIdHolder.current());
  }

  @PostMapping("/sources/{sourceId}/sync")
  public ApiResponse<Map<String, Object>> syncSource(@PathVariable String sourceId) {
    SourceRunConfigService.SyncOutcome outcome =
        sources.syncFromCore(ActorContextHolder.currentOrNull(), sourceId);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("sourceCode", outcome.sourceCode());
    view.put("changed", outcome.changed());
    view.put("authoritativeConfigVersion", outcome.authoritativeConfigVersion());
    view.put("authoritativeEnabled", outcome.authoritativeEnabled());
    view.put("syncedConfigVersion", outcome.syncedConfigVersion());
    view.put("notes", outcome.notes());
    return ApiResponse.ok(view, TraceIdHolder.current());
  }

  @PostMapping("/sources/{sourceId}/enabled")
  public ApiResponse<Map<String, Object>> setSourceEnabled(
      @PathVariable String sourceId, @RequestBody SourceEnabledRequest request) {
    if (request == null || request.enabled() == null) {
      throw BusinessException.invalidArgument("必须给出 enabled 取值");
    }
    SourceRunConfig config =
        sources.setEnabled(
            ActorContextHolder.currentOrNull(), sourceId, request.enabled(), request.reason());
    return ApiResponse.ok(toSourceView(config), TraceIdHolder.current());
  }

  @PatchMapping("/sources/{sourceId}")
  public ApiResponse<Map<String, Object>> updateSource(
      @PathVariable String sourceId, @RequestBody SourceUpdateRequest request) {
    if (request == null) {
      throw BusinessException.invalidArgument("请求体不能为空");
    }
    SourceRunConfig config =
        sources.updateRunParameters(
            ActorContextHolder.currentOrNull(),
            sourceId,
            new SourceRunConfigService.UpdateCommand(
                request.queryExpression(),
                request.pageSize(),
                request.licenseReviewState(),
                request.licenseNote(),
                request.reason()));
    return ApiResponse.ok(toSourceView(config), TraceIdHolder.current());
  }

  /**
   * 设定或撤销来源的业务更新周期。
   *
   * <p>单独一个端点而不是塞进 {@code PATCH /sources/{id}}：改周期必须同时给依据，而那份依据是
   * 审计要留的东西；把它混进「顺手改分页大小」的接口里，最后一定会有一次改动没有理由。
   * 两个周期都传 null 表示撤销周期（行为退回到只由人工创建任务驱动）。
   */
  @PutMapping("/sources/{sourceId}/update-plan")
  public ApiResponse<Map<String, Object>> setUpdatePlan(
      @PathVariable String sourceId, @RequestBody SourceUpdatePlanRequest request) {
    if (request == null) {
      throw BusinessException.invalidArgument("请求体不能为空；撤销周期请把两个周期字段都传 null");
    }
    SourceRunConfig config =
        sources.updateUpdatePlan(
            ActorContextHolder.currentOrNull(),
            sourceId,
            new SourceRunConfigService.UpdatePlanCommand(
                request.newRecordIntervalMinutes(),
                request.fieldRefreshIntervalMinutes(),
                request.note()));
    return ApiResponse.ok(toSourceView(config), TraceIdHolder.current());
  }

  // ---------------------------------------------------------------------------
  // 采集任务
  // ---------------------------------------------------------------------------

  @GetMapping("/jobs")
  public ApiResponse<Map<String, Object>> listJobs(
      @RequestParam(required = false, defaultValue = "50") int limit,
      @RequestParam(required = false) String sourceId) {
    List<CollectJob> found =
        sourceId == null || sourceId.isBlank()
            ? jobs.list(limit)
            : jobs.listBySource(sourceId, limit);
    List<Map<String, Object>> items =
        found.stream().map(AdminIngestionController::toJobView).toList();
    Map<String, Object> page = new LinkedHashMap<>();
    page.put("items", items);
    page.put("page", 1);
    page.put("size", items.size());
    page.put("total", items.size());
    page.put("openJobs", jobRepository.countOpenJobs());
    return ApiResponse.ok(page, TraceIdHolder.current());
  }

  @PostMapping("/jobs")
  public ApiResponse<Map<String, Object>> createJob(@RequestBody JobCreateRequest request) {
    if (request == null || request.sourceId() == null || request.sourceId().isBlank()) {
      throw BusinessException.invalidArgument("必须给出 sourceId");
    }
    CollectJobService.CreateOutcome outcome =
        jobs.create(
            ActorContextHolder.currentOrNull(),
            request.sourceId(),
            request.jobKey(),
            request.reason());
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("job", toJobView(outcome.job()));
    view.put("created", outcome.created());
    view.put("message", outcome.message());
    return ApiResponse.ok(view, TraceIdHolder.current());
  }

  @GetMapping("/jobs/{jobId}")
  public ApiResponse<Map<String, Object>> jobDetail(@PathVariable String jobId) {
    return ApiResponse.ok(toJobView(jobs.require(jobId)), TraceIdHolder.current());
  }

  @GetMapping("/jobs/{jobId}/statistics")
  public ApiResponse<Map<String, Object>> jobStatistics(@PathVariable String jobId) {
    return ApiResponse.ok(jobs.statistics(jobId), TraceIdHolder.current());
  }

  @GetMapping("/jobs/{jobId}/batches")
  public ApiResponse<Map<String, Object>> jobBatches(
      @PathVariable String jobId, @RequestParam(required = false, defaultValue = "50") int limit) {
    List<Map<String, Object>> items =
        jobs.listBatches(jobId, limit).stream().map(AdminIngestionController::toBatchView).toList();
    Map<String, Object> page = new LinkedHashMap<>();
    page.put("items", items);
    page.put("page", 1);
    page.put("size", items.size());
    page.put("total", items.size());
    return ApiResponse.ok(page, TraceIdHolder.current());
  }

  @GetMapping("/jobs/{jobId}/logs")
  public ApiResponse<Map<String, Object>> jobLogs(
      @PathVariable String jobId, @RequestParam(required = false, defaultValue = "100") int limit) {
    List<Map<String, Object>> items =
        jobs.listLogs(jobId, limit).stream().map(AdminIngestionController::toLogView).toList();
    Map<String, Object> page = new LinkedHashMap<>();
    page.put("items", items);
    page.put("page", 1);
    page.put("size", items.size());
    page.put("total", items.size());
    return ApiResponse.ok(page, TraceIdHolder.current());
  }

  @PostMapping("/jobs/{jobId}/pause")
  public ApiResponse<Map<String, Object>> pauseJob(
      @PathVariable String jobId, @RequestBody(required = false) ReasonRequest request) {
    CollectJob job =
        jobs.pause(
            ActorContextHolder.currentOrNull(), jobId, request == null ? null : request.reason());
    return ApiResponse.ok(toJobView(job), TraceIdHolder.current());
  }

  @PostMapping("/jobs/{jobId}/resume")
  public ApiResponse<Map<String, Object>> resumeJob(
      @PathVariable String jobId, @RequestBody(required = false) ReasonRequest request) {
    CollectJob job =
        jobs.resume(
            ActorContextHolder.currentOrNull(), jobId, request == null ? null : request.reason());
    return ApiResponse.ok(toJobView(job), TraceIdHolder.current());
  }

  /**
   * 立即执行一轮（有界）。
   *
   * <p>这是后台执行器的同步版本，存在的理由是运维与验收需要可重复的驱动方式：等待定时调度
   * 无法控制何时开始，也无法把「本次抓了多少」与某次请求对应起来。
   *
   * <p>它与后台执行器走同一把租约：假如后台执行器正在处理该任务，本次会返回租约占用而不会
   * 并发抓取同一批记录。
   *
   * <p>手动执行<b>不</b>受抓取失败退避窗口限制：后台执行器取任务时会跳过还在退避窗口内的任务，
   * 而人点「立即执行」的意图通常正是「上游可能已经恢复，试一次」。本次若仍失败，退避会照常累加，
   * 结果在 {@code fetchFailed} 与 {@code lastMessage} 里如实回报。
   */
  @PostMapping("/jobs/{jobId}/run")
  public ApiResponse<Map<String, Object>> runJobOnce(
      @PathVariable String jobId, @RequestParam(required = false) Integer maxRounds) {
    jobs.require(jobId);
    int budget =
        maxRounds == null || maxRounds <= 0
            ? properties.maxPagesPerRun()
            : Math.min(maxRounds, 200);
    CollectExecutionService.BatchOutcome outcome =
        execution.runBounded(jobId, manualLeaseOwner(), budget);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("jobId", outcome.jobId());
    view.put("rounds", outcome.rounds());
    view.put("productiveRounds", outcome.productive());
    view.put("staged", outcome.staged());
    view.put("duplicated", outcome.duplicated());
    view.put("handedOff", outcome.handedOff());
    view.put("fetchFailed", outcome.fetchFailed());
    view.put("lastMessage", outcome.lastMessage());
    view.put("note", "手动执行与后台执行器共用同一租约；此处立刻返回本轮结果，不等待后台周期");
    return ApiResponse.ok(view, TraceIdHolder.current());
  }

  /** 手动执行的租约属主标识；与后台执行器区分开，便于在任务上看出是谁在跑。 */
  private static String manualLeaseOwner() {
    return "manual:" + ProcessHandle.current().pid();
  }

  // ---------------------------------------------------------------------------
  // 视图转换
  // ---------------------------------------------------------------------------

  private static Map<String, Object> toSourceView(SourceRunConfig config) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", config.id());
    view.put("sourceCode", config.sourceCode());
    view.put("displayName", config.displayName());
    view.put("adapterKind", config.adapterKind() == null ? null : config.adapterKind().name());
    view.put("baseEndpoint", config.baseEndpoint());
    view.put("queryExpression", config.queryExpression());
    view.put("pageSize", config.pageSize());
    view.put("allowedFields", config.allowedFields());
    view.put("configVersion", config.configVersion());
    view.put("syncedConfigVersion", config.syncedConfigVersion());
    view.put("licenseReviewState", config.licenseReviewState());
    view.put("licenseNote", config.licenseNote());
    view.put("rateLimitPerMinute", config.rateLimitPerMinute());
    view.put("enabled", config.enabled());
    view.put("licenseApproved", config.licenseApproved());
    // 业务更新计划：三个字段必须一起给。只给周期不给 next_due_at，运维无法回答
    // 「它为什么还没跑」；只给 next_due_at 不给理由，则无法判断这个数字是否还成立。
    view.put("newRecordIntervalMinutes", config.newRecordIntervalMinutes());
    view.put("fieldRefreshIntervalMinutes", config.fieldRefreshIntervalMinutes());
    view.put("updatePlanNote", config.updatePlanNote());
    view.put("nextDueAt", config.nextDueAt() == null ? null : config.nextDueAt().toString());
    view.put("hasUpdatePlan", config.hasUpdatePlan());
    return view;
  }

  private static Map<String, Object> toJobView(CollectJob job) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", job.id());
    view.put("sourceConfigId", job.sourceConfigId());
    view.put("jobKey", job.jobKey());
    view.put("status", job.status() == null ? null : job.status().name());
    view.put("cursor", job.cursor());
    view.put("checkpoint", job.checkpoint());
    view.put("totalFetched", job.totalFetched());
    view.put("totalStaged", job.totalStaged());
    view.put("totalHandedOff", job.totalHandedOff());
    view.put("totalRejected", job.totalRejected());
    view.put("leaseOwner", job.leaseOwner());
    view.put("leaseUntil", job.leaseUntil() == null ? null : job.leaseUntil().toString());
    view.put("lastError", job.lastError());
    view.put("createdBy", job.createdBy());
    // 连续失败次数与下一次尝试时刻：任务列表要能回答「这个任务为什么没在跑」。只给 lastError
    // 时看不出「它正在退避」，运维会以为调度坏了。
    view.put("consecutiveFailures", job.consecutiveFailures());
    view.put("nextAttemptAt", job.nextAttemptAt() == null ? null : job.nextAttemptAt().toString());
    return view;
  }

  private static Map<String, Object> toBatchView(
      CollectRepositories.HandoffBatchRepository.HandoffBatch batch) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", batch.id());
    view.put("jobId", batch.jobId());
    view.put("batchKey", batch.batchKey());
    view.put("status", batch.status());
    view.put("recordCount", batch.recordCount());
    view.put("attempts", batch.attempts());
    view.put(
        "nextAttemptAt", batch.nextAttemptAt() == null ? null : batch.nextAttemptAt().toString());
    view.put("ackedAt", batch.ackedAt() == null ? null : batch.ackedAt().toString());
    view.put("lastError", batch.lastError());
    view.put("coreReceiptJson", batch.coreReceiptJson());
    view.put("createdAt", batch.createdAt() == null ? null : batch.createdAt().toString());
    return view;
  }

  private static Map<String, Object> toLogView(
      CollectRepositories.FetchLogRepository.FetchLogEntry entry) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", entry.id());
    view.put("jobId", entry.jobId());
    view.put("phase", entry.phase());
    view.put("outcome", entry.outcome());
    view.put("fetchedCount", entry.fetchedCount());
    view.put("stagedCount", entry.stagedCount());
    view.put("rejectedCount", entry.rejectedCount());
    view.put("detailJson", entry.detailJson());
    view.put("occurredAt", entry.occurredAt() == null ? null : entry.occurredAt().toString());
    return view;
  }

  // ---------------------------------------------------------------------------
  // 请求体
  // ---------------------------------------------------------------------------

  /** 登记来源运行配置。 */
  public record SourceRegisterRequest(
      String sourceCode,
      String displayName,
      String adapterKind,
      String baseEndpoint,
      String queryExpression,
      Integer pageSize,
      String licenseReviewState,
      String licenseNote,
      Integer rateLimitPerMinute,
      Boolean enabled) {}

  /** 启用或停用采集。 */
  public record SourceEnabledRequest(Boolean enabled, String reason) {}

  /**
   * 业务更新周期请求。
   *
   * <p>两个周期都可为 null（表示未设定／撤销）。刻意不设默认值：默认值会变成「没人决定过的周期」，
   * 而这正是 WB-13 指出的问题。
   */
  public record SourceUpdatePlanRequest(
      Integer newRecordIntervalMinutes, Integer fieldRefreshIntervalMinutes, String note) {}

  /** 更新执行参数；null 表示不改该字段。 */
  public record SourceUpdateRequest(
      String queryExpression,
      Integer pageSize,
      String licenseReviewState,
      String licenseNote,
      String reason) {}

  /** 创建采集任务。 */
  public record JobCreateRequest(String sourceId, String jobKey, String reason) {}

  /** 需要理由的操作。 */
  public record ReasonRequest(String reason) {}

  // ---------------------------------------------------------------------------
  // 文件导入
  // ---------------------------------------------------------------------------

  /**
   * 以文件形态导入来源记录（CSV 或 JSON）。
   *
   * <p>依据：AC-IMPL-FR08（API/文件任选）、AC-IMPL-FR16-01。用于专利这类没有免密钥公开 API 的
   * 类别：由使用者从合法渠道导出后导入，系统只负责校验、去重与治理，不替使用者取得授权。
   *
   * <p>{@code dataNature} 必须显式给出；缺省按 SIMULATED 处理，绝不默认成 REAL。导入结果区分
   * 「接收 / 新建 / 追加到既有成果 / 重复 / 拒绝 / 本地校验不通过」，并给出逐行错误。
   */
  @PostMapping(path = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<Map<String, Object>> importFile(
      @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
      @RequestParam("sourceCode") String sourceCode,
      @RequestParam(value = "dataNature", required = false) String dataNature) {
    if (file == null || file.isEmpty()) {
      throw BusinessException.invalidArgument("导入文件不能为空");
    }
    byte[] content;
    try {
      content = file.getBytes();
    } catch (IOException ex) {
      throw BusinessException.invalidArgument("读取上传文件失败：" + ex.getMessage());
    }
    ImportService.ImportOutcome outcome =
        imports.importFile(sourceCode, dataNature, file.getOriginalFilename(), content);

    Map<String, Object> view = new LinkedHashMap<>();
    view.put("filename", file.getOriginalFilename());
    view.put("sourceCode", sourceCode);
    view.put("dataNature", dataNature == null ? "SIMULATED" : dataNature.trim().toUpperCase());
    view.put("received", outcome.received());
    view.put("accepted", outcome.accepted());
    view.put("attached", outcome.attached());
    view.put("duplicate", outcome.duplicate());
    view.put("rejected", outcome.rejected());
    view.put("invalid", outcome.invalid());
    view.put("errors", outcome.errors());
    view.put("note", "接收不等于已发布：成果是否进入公开目录由治理判定；本次导入只保证记录已进入 core 的清洗与去重流程");
    return ApiResponse.ok(view, TraceIdHolder.current());
  }
}
