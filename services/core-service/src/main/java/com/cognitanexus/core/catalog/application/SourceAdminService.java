package com.cognitanexus.core.catalog.application;

import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.SourceConfigRepository;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.event.EventTypes;
import com.cognitanexus.core.platform.event.OutboxEvent;
import com.cognitanexus.core.platform.event.OutboxStore;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.Capabilities;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.platform.util.Identifiers;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.Visibility;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 来源运行配置管理。
 *
 * <p>依据：架构 V0.2 §4.2、§7.2、AC-IMPL-FR08-02。
 *
 * <p>停用一个来源不只是改一个开关：如果该来源是某些公开成果的唯一证据，撤销其展示许可就必须
 * <b>同事务收紧这些成果的可见性并推进权威公开水位</b>，让查询侧立即返回 503 VIEW_UPDATING 而不是
 * 继续返回旧缓存、旧计数或旧路径证据。把这件事做成「等 TTL 过期」是本设计明确拒绝的做法。
 */
@Service
public class SourceAdminService {

  private static final Logger log = LoggerFactory.getLogger(SourceAdminService.class);

  /** 单次收紧的成果上限，避免一次管理操作产生无界的大事务。 */
  private static final int TIGHTEN_BATCH_LIMIT = 5_000;

  private final SourceConfigRepository sourceConfigs;
  private final AchievementRepository achievements;
  private final OutboxStore outbox;
  private final AuditRecorder audit;

  public SourceAdminService(
      SourceConfigRepository sourceConfigs,
      AchievementRepository achievements,
      OutboxStore outbox,
      AuditRecorder audit) {
    this.sourceConfigs = sourceConfigs;
    this.achievements = achievements;
    this.outbox = outbox;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public List<SourceConfigRepository.SourceConfig> listSources(ActorContext actor) {
    Capabilities.require(actor, RoleNames.CAP_SOURCE_MANAGE);
    return sourceConfigs.listAll();
  }

  /**
   * 登记来源配置。
   *
   * <p>新来源默认<b>不启用</b>：许可范围需要人工确认后才可开始采集，因此登记与启用是两步。
   * 允许在登记时显式要求 enabled=true，但那要求同时给出非空的许可范围说明，避免出现
   * 「没有许可依据却已启用」的来源。
   */
  @Transactional
  public SourceConfigRepository.SourceConfig registerSource(
      ActorContext actor, RegisterSourceCommand command) {
    Capabilities.require(actor, RoleNames.CAP_SOURCE_MANAGE);
    if (command.sourceCode() == null || command.sourceCode().isBlank()) {
      throw BusinessException.invalidArgument("来源代码不能为空");
    }
    if (command.displayName() == null || command.displayName().isBlank()) {
      throw BusinessException.invalidArgument("来源显示名不能为空");
    }
    if (command.licenseScope() == null || command.licenseScope().isBlank()) {
      throw BusinessException.invalidArgument("必须写出该来源的许可依据或使用范围");
    }
    if (command.enabled() && !command.licenseReviewed()) {
      throw BusinessException.businessRule("许可未经确认时不能直接启用来源；请先登记为停用状态，确认许可后再启用");
    }

    String sourceCode = command.sourceCode().trim();
    if (sourceConfigs.findByCode(sourceCode).isPresent()) {
      throw BusinessException.versionConflict("该来源代码已登记：" + sourceCode);
    }

    String id = Identifiers.next("src");
    boolean created =
        sourceConfigs.register(
            id,
            sourceCode,
            command.displayName().trim(),
            command.enabled(),
            command.licenseScope().trim(),
            command.baseUrl() == null ? "" : command.baseUrl().trim(),
            command.allowedFields());
    if (!created) {
      throw BusinessException.versionConflict("该来源代码已登记：" + sourceCode);
    }

    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "SOURCE_REGISTERED",
            "SOURCE",
            id,
            null,
            Map.of(
                "sourceCode",
                sourceCode,
                "enabled",
                command.enabled(),
                "licenseScope",
                command.licenseScope().trim(),
                "allowedFields",
                command.allowedFields() == null ? List.of() : command.allowedFields()),
            "登记来源配置"));
    log.info("来源已登记 sourceId={} sourceCode={} enabled={}", id, sourceCode, command.enabled());
    return sourceConfigs.findById(id).orElseThrow();
  }

  /** 登记来源的输入。 */
  public record RegisterSourceCommand(
      String sourceCode,
      String displayName,
      String licenseScope,
      String baseUrl,
      List<String> allowedFields,
      boolean enabled,
      boolean licenseReviewed) {}

  /**
   * 启用或停用一个来源。
   *
   * <p>停用时：先把「唯一来源是该来源」的公开成果收紧为 RESTRICTED，再推进权威公开水位并发布
   * 收紧事件。顺序很重要——先收紧数据再抬水位，否则屏障会指向一个还没准备好的状态。
   */
  @Transactional
  public TightenOutcome setEnabled(
      ActorContext actor, String sourceId, boolean enabled, String reason) {
    Capabilities.require(actor, RoleNames.CAP_SOURCE_MANAGE);
    SourceConfigRepository.SourceConfig config =
        sourceConfigs.findById(sourceId).orElseThrow(() -> BusinessException.notFound("来源不存在"));
    if (config.enabled() == enabled) {
      throw BusinessException.versionConflict("来源当前已处于该状态");
    }
    if (reason == null || reason.isBlank()) {
      throw BusinessException.invalidArgument("必须说明启用或停用理由");
    }

    int affected = sourceConfigs.setEnabled(sourceId, enabled);
    if (affected != 1) {
      throw BusinessException.versionConflict("来源配置已被其他操作改变，请刷新后重试");
    }

    List<String> tightened = new ArrayList<>();
    if (!enabled) {
      List<String> candidates =
          achievements.findExclusivelyFromSource(sourceId, TIGHTEN_BATCH_LIMIT);
      for (String achievementId : candidates) {
        if (achievements.tightenVisibility(achievementId, Visibility.RESTRICTED.name()) == 1) {
          tightened.add(achievementId);
        }
      }
      if (!tightened.isEmpty()) {
        outbox.append(
            OutboxEvent.builder(EventTypes.PUBLIC_VISIBILITY_TIGHTENED, "Source", sourceId)
                .put("sourceId", sourceId)
                .put("reason", reason)
                .put("tightenedAchievementIds", tightened));
      }
      // 推进权威公开水位：query 必须停止返回旧版本，而不是等 TTL 自然过期
      long required = outbox.tightenPublicVisibility();
      log.info("来源停用并收紧可见性 sourceId={} 收紧数量={} 权威水位={}", sourceId, tightened.size(), required);
    }

    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            enabled ? "SOURCE_ENABLED" : "SOURCE_DISABLED",
            "SOURCE",
            sourceId,
            Map.of("enabled", !enabled),
            Map.of("enabled", enabled, "tightenedVisibilities", tightened.size()),
            reason));

    Map<String, Object> before = new LinkedHashMap<>();
    before.put("enabled", !enabled);
    return new TightenOutcome(
        config.sourceCode(), enabled, tightened.size(), outbox.requiredPublicRevision());
  }

  /** 更新字段白名单并提升来源配置版本；适配器必须按新版本执行。 */
  @Transactional
  public int updateAllowedFields(
      ActorContext actor, String sourceId, List<String> fields, String reason) {
    Capabilities.require(actor, RoleNames.CAP_SOURCE_MANAGE);
    if (fields == null) {
      throw BusinessException.invalidArgument("字段白名单不能为 null，请传空数组表示不限制");
    }
    sourceConfigs.findById(sourceId).orElseThrow(() -> BusinessException.notFound("来源不存在"));
    int affected = sourceConfigs.updateAllowedFields(sourceId, fields);
    if (affected != 1) {
      throw BusinessException.versionConflict("来源配置已被其他操作改变，请刷新后重试");
    }
    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "SOURCE_ALLOWED_FIELDS_UPDATED",
            "SOURCE",
            sourceId,
            null,
            Map.of("allowedFields", fields),
            reason));
    return affected;
  }

  /** 停用来源后的处置结果。 */
  public record TightenOutcome(
      String sourceCode, boolean enabled, int tightenedCount, long requiredPublicRevision) {}
}
