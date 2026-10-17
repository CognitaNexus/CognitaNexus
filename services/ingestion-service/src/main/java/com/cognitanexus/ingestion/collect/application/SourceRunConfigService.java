package com.cognitanexus.ingestion.collect.application;

import com.cognitanexus.ingestion.collect.domain.AdapterKind;
import com.cognitanexus.ingestion.collect.domain.CollectModels.SourceRunConfig;
import com.cognitanexus.ingestion.platform.audit.IngestionAuditRecorder;
import com.cognitanexus.ingestion.platform.client.CoreInternalClient;
import com.cognitanexus.ingestion.platform.security.ActorContext;
import com.cognitanexus.ingestion.platform.util.Identifiers;
import com.cognitanexus.sharedkernel.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 来源运行配置管理。
 *
 * <p>依据：架构 V0.2 §4.2、§6.2。本服务只持有执行副本，core 才是许可与字段白名单的权威。
 * 因此有两类操作：
 *
 * <ul>
 *   <li>本地操作：登记执行参数（查询表达式、分页、限速）、启用/停用本地开关；
 *   <li>同步操作：从 core 拉取权威配置版本与白名单，写入 {@code syncedConfigVersion}。
 * </ul>
 *
 * <p>两者的分离是关键：本地开关只控制「本服务是否去抓」，而能否抓由 core 的许可与版本一致性
 * 共同决定（见 {@code SourceRunConfig.runnable}）。
 */
@Service
public class SourceRunConfigService {

  private static final Logger log = LoggerFactory.getLogger(SourceRunConfigService.class);

  /** 许可评审状态取值。与库级 CHECK 约束保持一致。 */
  private static final List<String> LICENSE_STATES =
      List.of("PENDING_REVIEW", "APPROVED", "REVOKED");

  private final CollectRepositories.SourceConfigRepository configs;
  private final CoreInternalClient coreClient;
  private final IngestionAuditRecorder audit;

  public SourceRunConfigService(
      CollectRepositories.SourceConfigRepository configs,
      CoreInternalClient coreClient,
      IngestionAuditRecorder audit) {
    this.configs = configs;
    this.coreClient = coreClient;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public List<SourceRunConfig> list() {
    return configs.listAll();
  }

  @Transactional(readOnly = true)
  public Optional<SourceRunConfig> findByCode(String sourceCode) {
    return configs.findByCode(sourceCode);
  }

  @Transactional(readOnly = true)
  public SourceRunConfig require(String sourceId) {
    return configs.findById(sourceId).orElseThrow(() -> BusinessException.notFound("采集运行配置不存在"));
  }

  /**
   * 登记来源运行配置。
   *
   * <p>新来源默认不启用。启用要求许可状态为 APPROVED，这与库级 CHECK 约束一致——把约束同时写在
   * 应用层与数据库层，是为了让绕过应用层的直接写库也无法产生「未获批却已启用」的状态。
   */
  @Transactional
  public SourceRunConfig register(ActorContext actor, RegisterCommand command) {
    if (command.sourceCode() == null || command.sourceCode().isBlank()) {
      throw BusinessException.invalidArgument("来源代码不能为空");
    }
    if (command.adapterKind() == null) {
      throw BusinessException.invalidArgument("必须指定适配器种类");
    }
    if ((command.queryExpression() == null || command.queryExpression().isBlank())
        && !command.adapterKind().supportsFullScan()) {
      throw BusinessException.invalidArgument("必须指定查询表达式；空查询会抓取整个来源");
    }
    String licenseState =
        command.licenseReviewState() == null ? "PENDING_REVIEW" : command.licenseReviewState();
    if (!LICENSE_STATES.contains(licenseState)) {
      throw BusinessException.invalidArgument("不支持的许可评审状态：" + licenseState);
    }
    if (command.enabled() && !"APPROVED".equals(licenseState)) {
      throw BusinessException.businessRule("许可未获批准时不能启用采集");
    }
    if (configs.findByCode(command.sourceCode()).isPresent()) {
      throw BusinessException.versionConflict("该来源代码已登记运行配置：" + command.sourceCode());
    }

    String id = Identifiers.next("src");
    SourceRunConfig config =
        new SourceRunConfig(
            id,
            command.sourceCode().trim(),
            command.displayName() == null
                ? command.sourceCode().trim()
                : command.displayName().trim(),
            command.adapterKind(),
            command.baseEndpoint(),
            command.queryExpression() == null ? "" : command.queryExpression().trim(),
            command.pageSize() <= 0 ? 100 : command.pageSize(),
            List.of(),
            "0",
            licenseState,
            command.licenseNote(),
            command.rateLimitPerMinute() <= 0 ? 30 : command.rateLimitPerMinute(),
            command.enabled(),
            0,
            // 新登记的来源一律「未设定更新周期」：周期必须由人给出依据后单独设定，
            // 不能因为「一般一个月更新一次」这种说法就填一个默认值（WB-13）。
            null,
            null,
            null,
            null);
    if (!configs.register(config)) {
      throw BusinessException.versionConflict("该来源代码已登记运行配置：" + command.sourceCode());
    }
    audit.record(
        actor,
        "INGESTION_SOURCE_REGISTERED",
        "SOURCE",
        id,
        null,
        Map.of(
            "sourceCode", config.sourceCode(),
            "adapterKind", config.adapterKind().name(),
            "licenseReviewState", licenseState,
            "enabled", config.enabled()),
        "登记采集运行配置");
    log.info("采集运行配置已登记 id={} sourceCode={}", id, config.sourceCode());
    return configs.findById(id).orElseThrow();
  }

  /**
   * 从 core 同步权威许可版本与字段白名单。
   *
   * <p>同步前必须能在 core 查到该来源：本地存在而 core 不存在的来源属于「无许可依据的采集」，
   * 直接拒绝而不是照旧执行。
   */
  @Transactional
  public SyncOutcome syncFromCore(ActorContext actor, String sourceId) {
    SourceRunConfig config =
        configs.findById(sourceId).orElseThrow(() -> BusinessException.notFound("采集运行配置不存在"));
    CoreInternalClient.SourceRunConfigView authoritative =
        coreClient
            .sourceConfig(config.sourceCode())
            .orElseThrow(
                () ->
                    BusinessException.businessRule("core 未登记该来源，无法确认许可依据：" + config.sourceCode()));

    boolean changed =
        configs.syncAuthoritativeConfig(
            sourceId, authoritative.allowedFields(), authoritative.configVersion());
    if (!authoritative.enabled() && config.enabled()) {
      // core 已停用而本地仍在启用：以 core 为准立即停用本地开关，避免继续按旧副本采集
      configs.setEnabled(sourceId, false);
      log.warn("core 已停用来源，本地同步停用 sourceCode={}", config.sourceCode());
    }
    if (changed) {
      audit.record(
          actor,
          "INGESTION_SOURCE_CONFIG_SYNCED",
          "SOURCE",
          sourceId,
          Map.of("syncedConfigVersion", config.syncedConfigVersion()),
          Map.of(
              "syncedConfigVersion", authoritative.configVersion(),
              "allowedFields", authoritative.allowedFields()),
          "同步 core 权威配置版本与字段白名单");
    }
    return new SyncOutcome(
        config.sourceCode(),
        changed,
        authoritative.configVersion(),
        authoritative.enabled(),
        configs.findById(sourceId).orElseThrow().syncedConfigVersion());
  }

  @Transactional
  public SourceRunConfig setEnabled(
      ActorContext actor, String sourceId, boolean enabled, String reason) {
    if (reason == null || reason.isBlank()) {
      throw BusinessException.invalidArgument("必须说明启用或停用理由");
    }
    SourceRunConfig config =
        configs.findById(sourceId).orElseThrow(() -> BusinessException.notFound("采集运行配置不存在"));
    if (enabled && !config.licenseApproved()) {
      throw BusinessException.businessRule("许可未获批准时不能启用采集，当前状态：" + config.licenseReviewState());
    }
    if (config.enabled() == enabled) {
      throw BusinessException.versionConflict("该来源当前已处于此状态");
    }
    if (configs.setEnabled(sourceId, enabled) != 1) {
      throw BusinessException.versionConflict("配置已被其他操作改变，请刷新后重试");
    }
    audit.record(
        actor,
        enabled ? "INGESTION_SOURCE_ENABLED" : "INGESTION_SOURCE_DISABLED",
        "SOURCE",
        sourceId,
        Map.of("enabled", !enabled),
        Map.of("enabled", enabled),
        reason);
    return configs.findById(sourceId).orElseThrow();
  }

  /** 更新执行参数（查询表达式、分页、许可评审状态与说明）。 */
  @Transactional
  public SourceRunConfig updateRunParameters(
      ActorContext actor, String sourceId, UpdateCommand command) {
    SourceRunConfig config =
        configs.findById(sourceId).orElseThrow(() -> BusinessException.notFound("采集运行配置不存在"));
    if (command.pageSize() != null && (command.pageSize() < 1 || command.pageSize() > 200)) {
      throw BusinessException.invalidArgument("分页大小须在 1 到 200 之间");
    }
    if (command.licenseReviewState() != null
        && !LICENSE_STATES.contains(command.licenseReviewState())) {
      throw BusinessException.invalidArgument("不支持的许可评审状态：" + command.licenseReviewState());
    }
    String nextState =
        command.licenseReviewState() == null
            ? config.licenseReviewState()
            : command.licenseReviewState();
    if (config.enabled() && !"APPROVED".equals(nextState)) {
      throw BusinessException.businessRule("来源处于启用状态时不能把许可改回未批准，请先停用采集");
    }

    Map<String, Object> before = new LinkedHashMap<>();
    before.put("queryExpression", config.queryExpression());
    before.put("pageSize", config.pageSize());
    before.put("licenseReviewState", config.licenseReviewState());

    configs.updateRunParameters(
        sourceId,
        command.queryExpression(),
        command.pageSize(),
        command.licenseReviewState(),
        command.licenseNote());
    // 审计的 after 取<b>实际生效后</b>的值，不回显请求参数：本接口是部分更新，未传的字段
    // 在请求里是 null，既会让 Map.of 直接抛空指针（表现为 500），又会在审计里留下一片
    // 「字段被清空」的假象——而它们其实原样保留。
    SourceRunConfig updated = configs.findById(sourceId).orElseThrow();
    audit.record(
        actor,
        "INGESTION_SOURCE_PARAMETERS_UPDATED",
        "SOURCE",
        sourceId,
        before,
        Map.of(
            "queryExpression", updated.queryExpression(),
            "pageSize", updated.pageSize(),
            "licenseReviewState", updated.licenseReviewState()),
        command.reason() == null ? "更新采集执行参数" : command.reason());
    return updated;
  }

  /** 供执行器判断能否运行：需要本地开关、许可批准、且本地副本版本与 core 一致。 */
  public boolean runnable(SourceRunConfig config) {
    if (!config.enabled() || !config.licenseApproved()) {
      return false;
    }
    return coreClient
        .sourceConfig(config.sourceCode())
        .map(authoritative -> config.runnable(authoritative.configVersion()))
        .orElse(false);
  }

  /**
   * 设定来源的业务更新周期。
   *
   * <p>三个周期概念在这里必须分清，否则会写出「每 2 秒抓一次学术来源」这种把执行机制当业务事实
   * 的配置：
   *
   * <ul>
   *   <li>轮询间隔（{@code cn.collect.worker-interval-ms}）——服务多久看一次队列，属实例参数；
   *   <li>失败退避（任务上的 {@code nextAttemptAt}）——失败后多久才允许再试，属执行机制；
   *   <li>业务更新周期（本方法）——这个来源的新记录与历史字段<em>应当</em>多久更新一次。
   * </ul>
   *
   * <p>本方法只处理第三个。设定时强制要求写依据：一个没有出处的数字，下一次评审时没人能判断
   * 它是否还成立。两个周期都为 null 表示撤销周期，行为退回「只由人工创建任务驱动」。
   */
  @Transactional
  public SourceRunConfig updateUpdatePlan(
      ActorContext actor, String sourceId, UpdatePlanCommand command) {
    SourceRunConfig config =
        configs.findById(sourceId).orElseThrow(() -> BusinessException.notFound("采集运行配置不存在"));
    Integer newRecord = command.newRecordIntervalMinutes();
    Integer fieldRefresh = command.fieldRefreshIntervalMinutes();
    boolean clearing = newRecord == null && fieldRefresh == null;
    if (!clearing) {
      if (command.note() == null || command.note().isBlank()) {
        throw BusinessException.invalidArgument("设定更新周期必须说明依据（来源声明频率／许可条款／人工评估），否则数字无从复核");
      }
      requireInterval(newRecord, "发现新记录的周期");
      requireInterval(fieldRefresh, "字段刷新周期");
    }
    Map<String, Object> before = new LinkedHashMap<>();
    before.put("newRecordIntervalMinutes", config.newRecordIntervalMinutes());
    before.put("fieldRefreshIntervalMinutes", config.fieldRefreshIntervalMinutes());
    before.put("updatePlanNote", config.updatePlanNote());

    configs.updateUpdatePlan(
        sourceId, newRecord, fieldRefresh, clearing ? config.updatePlanNote() : command.note());
    SourceRunConfig updated = configs.findById(sourceId).orElseThrow();
    audit.record(
        actor,
        "INGESTION_SOURCE_UPDATE_PLAN_SET",
        "SOURCE",
        sourceId,
        before,
        Map.of(
            "newRecordIntervalMinutes",
            String.valueOf(updated.newRecordIntervalMinutes()),
            "fieldRefreshIntervalMinutes",
            String.valueOf(updated.fieldRefreshIntervalMinutes()),
            "updatePlanNote",
            String.valueOf(updated.updatePlanNote()),
            "nextDueAt",
            String.valueOf(updated.nextDueAt())),
        clearing ? "撤销业务更新周期" : command.note().trim());
    log.info(
        "业务更新周期已设定 sourceCode={} newRecord={} fieldRefresh={} nextDue={}",
        config.sourceCode(),
        updated.newRecordIntervalMinutes(),
        updated.fieldRefreshIntervalMinutes(),
        updated.nextDueAt());
    return updated;
  }

  private static void requireInterval(Integer minutes, String label) {
    if (minutes == null) {
      return;
    }
    if (minutes < 60 || minutes > 525_600) {
      throw BusinessException.invalidArgument(label + "须在 60 到 525600 分钟之间（1 小时到 1 年）");
    }
  }

  /** 登记命令。 */
  public record RegisterCommand(
      String sourceCode,
      String displayName,
      AdapterKind adapterKind,
      String baseEndpoint,
      String queryExpression,
      int pageSize,
      String licenseReviewState,
      String licenseNote,
      int rateLimitPerMinute,
      boolean enabled) {}

  /** 更新命令；null 表示不修改该字段。 */
  public record UpdateCommand(
      String queryExpression,
      Integer pageSize,
      String licenseReviewState,
      String licenseNote,
      String reason) {}

  /**
   * 业务更新周期命令。
   *
   * <p>两个周期都用 Integer 而不是 int：{@code null} 是「未设定」这个有意义的取值，
   * 不能与 0 混为一谈（0 会被库级 CHECK 拒绝，而 null 是撤销周期）。
   */
  public record UpdatePlanCommand(
      Integer newRecordIntervalMinutes, Integer fieldRefreshIntervalMinutes, String note) {}

  /** 同步结果。 */
  public record SyncOutcome(
      String sourceCode,
      boolean changed,
      int authoritativeConfigVersion,
      boolean authoritativeEnabled,
      int syncedConfigVersion) {

    public List<String> notes() {
      List<String> notes = new ArrayList<>();
      notes.add(changed ? "配置版本已更新" : "本地已是最新版本，未产生写入");
      if (!authoritativeEnabled) {
        notes.add("core 侧该来源当前为停用，本地开关已同步停用");
      }
      return notes;
    }
  }
}
