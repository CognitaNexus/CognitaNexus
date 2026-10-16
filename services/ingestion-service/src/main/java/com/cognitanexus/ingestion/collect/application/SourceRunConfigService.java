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
    if (command.queryExpression() == null || command.queryExpression().isBlank()) {
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
            command.queryExpression().trim(),
            command.pageSize() <= 0 ? 100 : command.pageSize(),
            List.of(),
            "0",
            licenseState,
            command.licenseNote(),
            command.rateLimitPerMinute() <= 0 ? 30 : command.rateLimitPerMinute(),
            command.enabled(),
            0);
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
    audit.record(
        actor,
        "INGESTION_SOURCE_PARAMETERS_UPDATED",
        "SOURCE",
        sourceId,
        before,
        Map.of(
            "queryExpression", command.queryExpression(),
            "pageSize", command.pageSize(),
            "licenseReviewState", command.licenseReviewState()),
        command.reason() == null ? "更新采集执行参数" : command.reason());
    return configs.findById(sourceId).orElseThrow();
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
