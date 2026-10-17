package com.cognitanexus.core.catalog.application;

import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.SourceConfigRepository;
import com.cognitanexus.core.catalog.infrastructure.SourceRevocationMapper;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.event.EventTypes;
import com.cognitanexus.core.platform.event.OutboxEvent;
import com.cognitanexus.core.platform.event.OutboxStore;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.Capabilities;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.platform.util.Identifiers;
import com.cognitanexus.sharedkernel.BusinessException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 来源运行配置管理。
 *
 * <p>依据：架构 V0.2 §4.2、§7.2、AC-IMPL-FR08-02、ADR-001（方案 A）。
 *
 * <p><b>本类最重要的一件事是把两种意图拆开</b>：
 *
 * <ul>
 *   <li>{@link #setCollectionPaused}（采集开关）：决定「还抓不抓新数据」。它<b>不触碰任何可见性</b>。
 *       上游限流、维护窗口这类常规暂停属于这里，历史合法成果必须继续可读（AC-FR01-S01-02、BR-606）；
 *   <li>{@link #revokeDisplayLicense}（展示许可）：决定「依赖该来源的成果还能不能公开展示」。
 *       它不停止采集——撤权之后仍可为已授权范围更新元数据，只是新成果不再自动公开。
 * </ul>
 *
 * <p>V11 之前这两件事由同一个 {@code enabled} 布尔承担，于是「先别抓了」实际执行成「这些成果下线」。
 * 冲突的实测记录见 ADR-001 §2.6。
 *
 * <p><b>批处理的事务边界</b>：受理动作（改许可位、建作业、推进水位、写审计）在一个事务里完成；
 * 逐批收紧用 {@link TransactionTemplate} 每批独立提交提交。刻意不用 {@code @Transactional} 加自调用——
 * 自调用不过代理，批次会全部并进受理事务，1 百万条时既提交不了也回滚不起。
 */
@Service
public class SourceAdminService {

  private static final Logger log = LoggerFactory.getLogger(SourceAdminService.class);

  /**
   * 单批收紧／回填条数。
   *
   * <p>取 500 而不是原先的 5000：单批越小，批与批之间被其他事务插入的机会越多，可中断性越好；
   * 而每批的固定开销（一次事务提交 + 一次作业进度更新）在 500 条的量级上仍可忽略。
   * 这个数字不再表示「一次操作的上限」——上限由 {@link #MAX_BATCHES_PER_CALL} 决定，超出后可续跑。
   */
  private static final int BATCH_SIZE = 500;

  /**
   * 单次 HTTP 调用最多跑多少批。
   *
   * <p>400 批 × 500 条 = 20 万条。设上限的理由不是性能，是「一个管理请求不应该无限期占着连接」：
   * 上百万条的撤权应当在后台推进、由人查询进度，而不是让某个人的浏览器等十几分钟。
   * 未完成的作业留在 {@code FAILED}（可续跑）状态，调用方据此决定是否继续。
   */
  private static final int MAX_BATCHES_PER_CALL = 400;

  private final SourceConfigRepository sourceConfigs;
  private final AchievementRepository achievements;
  private final SourceRevocationMapper revocations;
  private final OutboxStore outbox;
  private final AuditRecorder audit;
  private final TransactionTemplate tx;

  public SourceAdminService(
      SourceConfigRepository sourceConfigs,
      AchievementRepository achievements,
      SourceRevocationMapper revocations,
      OutboxStore outbox,
      AuditRecorder audit,
      PlatformTransactionManager transactionManager) {
    this.sourceConfigs = sourceConfigs;
    this.achievements = achievements;
    this.revocations = revocations;
    this.outbox = outbox;
    this.audit = audit;
    this.tx = new TransactionTemplate(transactionManager);
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
   *
   * <p>新登记的来源展示许可一律为 {@code GRANTED}：登记时就要求写出许可依据（{@code license_scope}
   * 非空），这与「许可已授予」是同一件事。需要注意的是这只表示「允许公开」，不代表许可已被审核——
   * 审核状态在采集侧（{@code license_review_state}）。
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

  // ---------------------------------------------------------------------------
  // 采集开关
  // ---------------------------------------------------------------------------

  /**
   * 暂停或恢复采集。
   *
   * <p>只改采集开关，<b>不触碰任何成果的可见性，也不推进公开水位</b>。不推进水位的理由是这里没有
   * 任何公开数据发生变化：既没有成果被收紧，也没有新的公开成果产生。推进它只会让 query 无谓地
   * 返回一次 503。
   *
   * <p>返回里的 {@code displayLicenseTouched} 恒为 false，是写给调用方看的：旧接口在停用时会把
   * 成果收紧并把数量放进 {@code tightenedCount}，现在这个数字永远是 0。保留字段名是为了让
   * 「0」这件事有解释，而不是让人以为收紧失败了。
   */
  public CollectionOutcome setCollectionPaused(
      ActorContext actor, String sourceId, boolean paused, String reason) {
    Capabilities.require(actor, RoleNames.CAP_SOURCE_MANAGE);
    if (reason == null || reason.isBlank()) {
      throw BusinessException.invalidArgument("必须说明暂停或恢复采集的理由");
    }
    SourceConfigRepository.SourceConfig config = requireSource(sourceId);
    boolean targetEnabled = !paused;
    if (config.enabled() == targetEnabled) {
      throw BusinessException.versionConflict("来源当前已处于该采集状态");
    }

    tx.executeWithoutResult(
        status -> {
          int affected = sourceConfigs.setEnabled(sourceId, targetEnabled);
          if (affected != 1) {
            throw BusinessException.versionConflict("来源配置已被其他操作改变，请刷新后重试");
          }
          audit.record(
              AuditRecorder.AuditEntry.of(
                  actor,
                  paused ? "SOURCE_COLLECTION_PAUSED" : "SOURCE_COLLECTION_RESUMED",
                  "SOURCE",
                  sourceId,
                  Map.of("enabled", config.enabled()),
                  Map.of(
                      "enabled",
                      targetEnabled,
                      // 显式写 0 并说明原因：读过旧版响应的人会找这个字段，
                      // 找不到就会怀疑「是不是漏了收紧步骤」
                      "tightenedVisibilities",
                      0,
                      "displayLicenseTouched",
                      false),
                  reason));
        });

    log.info(
        "来源采集开关变更 sourceId={} paused={} 展示许可保持={}",
        sourceId,
        paused,
        config.displayLicenseStatus());
    return new CollectionOutcome(
        config.sourceCode(), targetEnabled, outbox.requiredPublicRevision());
  }

  /**
   * 旧接口的兼容入口。
   *
   * <p>语义已收敛为「只改采集开关」：传 {@code enabled=false} 不再收紧可见性。这样改是刻意的——
   * 旧名字的本意就是「停用这个来源」，而 SRS 要求来源停用后历史成果仍可读。保留端点是为了不
   * 让现有调用方在升级时直接 404，同时让行为回到需求一侧。
   *
   * @deprecated 用 {@link #setCollectionPaused}（暂停采集）或 {@link #revokeDisplayLicense}（撤权）。
   *     两个意图必须由调用方明确选一个，不能再靠一个布尔去猜。
   */
  @Deprecated
  public CollectionOutcome setEnabled(
      ActorContext actor, String sourceId, boolean enabled, String reason) {
    return setCollectionPaused(actor, sourceId, !enabled, reason);
  }

  // ---------------------------------------------------------------------------
  // 展示许可
  // ---------------------------------------------------------------------------

  /**
   * 撤销来源的展示许可。
   *
   * <p>受理与落地分成两步，先后顺序不能颠倒：
   *
   * <ol>
   *   <li><b>受理事务</b>：把来源的 {@code display_license_status} 置 REVOKED、创建作业、发出来源级
   *       事件、推进权威公开水位。公开导出条件里带「至少一个来源仍有展示许可」，所以<b>这一步做完，
   *       query 就已经看不到依赖该来源的成果了</b>——阻断是即时的，不依赖后面逐批收紧的速度；
   *   <li><b>逐批收紧</b>（事务外，每批独立提交）：把 {@code visibility} 落成 RESTRICTED 并记录收紧
   *       原因。它的作用是让库里的状态与事实一致，不是阻断手段，因此可以中断、可以续跑。
   * </ol>
   *
   * <p>为什么不每批都推进水位：推开水位的意思是「query 必须重新拉一次」。逐批收紧不改变导出的
   * 结果集——从受理那一刻起这些成果就已经不在导出里了。若每批都推，一个来源的撤权会让 query 在
   * 整个撤权期间（可能几十分钟）持续返回 503，把「一个来源撤权」放大成「全站不可用」。
   *
   * <p>为什么事件里不再带 {@code tightenedAchievementIds}：撤权可能涉及上百万条，把 id 列表塞进
   * 一个事件正文，既撑不住也让消费方无法增量处理。事件只表达「这个来源的展示许可变了」这一件
   * 事实，具体收紧了哪些由作业的计数与作业明细回答。
   */
  public DisplayLicenseOutcome revokeDisplayLicense(
      ActorContext actor, String sourceId, String reason) {
    Capabilities.require(actor, RoleNames.CAP_SOURCE_MANAGE);
    requireReason(reason, "撤销展示许可必须写明依据（许可到期、条款变更、来源要求下线等）");
    SourceConfigRepository.SourceConfig config = requireSource(sourceId);
    if (!config.displayLicenseGranted()) {
      throw BusinessException.versionConflict(activeJobHint(sourceId, "该来源的展示许可已处于撤销状态"));
    }

    Accepted accepted =
        tx.execute(
            status -> {
              int affected = sourceConfigs.setDisplayLicense(sourceId, "REVOKED", reason);
              if (affected != 1) {
                throw BusinessException.versionConflict("来源展示许可已被其他操作改变，请刷新后重试");
              }
              long target = achievements.countExclusivelyFromSource(sourceId);
              String jobId = Identifiers.next("rev");
              revocations.insertJob(
                  jobId,
                  sourceId,
                  actor.accountIdValue(),
                  reason,
                  target,
                  SourceRevocationMapper.STATUS_RUNNING);
              outbox.append(
                  OutboxEvent.builder(EventTypes.PUBLIC_VISIBILITY_TIGHTENED, "Source", sourceId)
                      .put("sourceId", sourceId)
                      .put("change", "DISPLAY_LICENSE_REVOKED")
                      .put("reason", reason)
                      .put("revocationJobId", jobId)
                      .put("targetCount", target));
              long required = outbox.tightenPublicVisibility();
              audit.record(
                  AuditRecorder.AuditEntry.of(
                      actor,
                      "SOURCE_DISPLAY_LICENSE_REVOKED",
                      "SOURCE",
                      sourceId,
                      Map.of("displayLicenseStatus", config.displayLicenseStatus()),
                      Map.of(
                          "displayLicenseStatus", "REVOKED",
                          "revocationJobId", jobId,
                          "targetCount", target),
                      reason));
              return new Accepted(jobId, target, required);
            });

    log.info(
        "来源展示许可已撤销 sourceId={} 目标条数={} 作业={} 权威水位={}",
        sourceId,
        accepted.targetCount(),
        accepted.jobId(),
        accepted.requiredPublicRevision());
    return drain(sourceId, config.sourceCode(), accepted, false);
  }

  /**
   * 恢复来源的展示许可。
   *
   * <p>不是「把 RESTRICTED 改回 PUBLIC」：那样会连人工限制、治理未通过的成果一起放开。恢复按
   * {@code display_restriction_source_id} 找回「因本次撤权被收紧」的那批，再逐条重新判定
   * （当前仍是 EFFECTIVE、且至少一个来源仍具许可），两者都满足才回到 PUBLIC。
   *
   * <p>恢复同样要推进水位：这一次公开数据确实变多了，query 必须重新拉取才看得到。
   */
  public DisplayLicenseOutcome restoreDisplayLicense(
      ActorContext actor, String sourceId, String reason) {
    Capabilities.require(actor, RoleNames.CAP_SOURCE_MANAGE);
    requireReason(reason, "恢复展示许可必须写明依据（许可续签、来源撤回下线要求等）");
    SourceConfigRepository.SourceConfig config = requireSource(sourceId);
    if (config.displayLicenseGranted()) {
      throw BusinessException.versionConflict(activeJobHint(sourceId, "该来源的展示许可已处于授予状态"));
    }

    Accepted accepted =
        tx.execute(
            status -> {
              int affected = sourceConfigs.setDisplayLicense(sourceId, "GRANTED", null);
              if (affected != 1) {
                throw BusinessException.versionConflict("来源展示许可已被其他操作改变，请刷新后重试");
              }
              long target = achievements.countRestrictedBySource(sourceId);
              String jobId = Identifiers.next("rev");
              revocations.insertJob(
                  jobId,
                  sourceId,
                  actor.accountIdValue(),
                  reason,
                  target,
                  SourceRevocationMapper.STATUS_RUNNING_RESTORE);
              outbox.append(
                  OutboxEvent.builder(EventTypes.PUBLIC_VISIBILITY_RESTORED, "Source", sourceId)
                      .put("sourceId", sourceId)
                      .put("change", "DISPLAY_LICENSE_RESTORED")
                      .put("reason", reason)
                      .put("restoreJobId", jobId)
                      .put("targetCount", target));
              long required = outbox.tightenPublicVisibility();
              audit.record(
                  AuditRecorder.AuditEntry.of(
                      actor,
                      "SOURCE_DISPLAY_LICENSE_RESTORED",
                      "SOURCE",
                      sourceId,
                      Map.of("displayLicenseStatus", config.displayLicenseStatus()),
                      Map.of(
                          "displayLicenseStatus", "GRANTED",
                          "restoreJobId", jobId,
                          "targetCount", target),
                      reason));
              return new Accepted(jobId, target, required);
            });

    log.info(
        "来源展示许可已恢复 sourceId={} 待回填条数={} 作业={}", sourceId, accepted.targetCount(), accepted.jobId());
    return drain(sourceId, config.sourceCode(), accepted, true);
  }

  /**
   * 续跑未完成的许可变更作业。
   *
   * <p>为什么需要一个人工入口：单次调用有批数上限（{@link #MAX_BATCHES_PER_CALL}），超大来源一次跑不完；
   * 中途进程重启也会留下 FAILED。没有续跑入口时，作业表里的进度就只是「记录」，不是「可继续的工作」。
   *
   * <p>续跑不改许可位——许可在受理时就已改好，这里只补落地。若发现许可位与作业方向不一致
   * （例如撤权作业未完，有人又把许可恢复了），拒绝执行并说明原因，避免两边对着改。
   */
  public DisplayLicenseOutcome resumeJob(ActorContext actor, String jobId) {
    Capabilities.require(actor, RoleNames.CAP_SOURCE_MANAGE);
    SourceRevocationMapper.RevocationRow job =
        revocations.findById(jobId).orElseThrow(() -> BusinessException.notFound("撤权作业不存在"));
    if (SourceRevocationMapper.STATUS_SUCCEEDED.equals(job.status())) {
      throw BusinessException.versionConflict("该作业已完成，无需续跑");
    }
    SourceConfigRepository.SourceConfig config = requireSource(job.sourceId());
    boolean restoreDirection = SourceRevocationMapper.STATUS_RUNNING_RESTORE.equals(job.status());
    boolean licenseMatches = restoreDirection == config.displayLicenseGranted();
    if (!licenseMatches) {
      throw BusinessException.versionConflict(
          "作业方向与来源当前许可状态不一致（作业为"
              + (restoreDirection ? "恢复" : "撤权")
              + "，来源许可为"
              + config.displayLicenseStatus()
              + "）；请先确认是否有人在此期间改过许可");
    }

    tx.executeWithoutResult(
        status -> {
          int resumed =
              revocations.resume(
                  jobId,
                  restoreDirection
                      ? SourceRevocationMapper.STATUS_RUNNING_RESTORE
                      : SourceRevocationMapper.STATUS_RUNNING);
          if (resumed != 1) {
            throw BusinessException.versionConflict("作业状态已被其他操作改变，请刷新后重试");
          }
          audit.record(
              AuditRecorder.AuditEntry.of(
                  actor,
                  "SOURCE_DISPLAY_LICENSE_JOB_RESUMED",
                  "SOURCE",
                  job.sourceId(),
                  Map.of("jobStatus", job.status()),
                  Map.of("jobId", jobId, "alreadyProcessed", job.tightenedCount()),
                  "续跑展示许可变更作业"));
        });

    return drain(
        job.sourceId(),
        config.sourceCode(),
        new Accepted(jobId, job.targetCount(), outbox.requiredPublicRevision()),
        restoreDirection);
  }

  /** 某来源的许可变更作业历史；最近的在前。 */
  @Transactional(readOnly = true)
  public List<RevocationView> listRevocations(ActorContext actor, String sourceId, int limit) {
    Capabilities.require(actor, RoleNames.CAP_SOURCE_MANAGE);
    return revocations.listBySource(sourceId, Math.min(Math.max(limit, 1), 100)).stream()
        .map(RevocationView::of)
        .toList();
  }

  // ---------------------------------------------------------------------------
  // 批次执行
  // ---------------------------------------------------------------------------

  /**
   * 一批一批地落地，直到没有可处理的或到达批数上限。
   *
   * <p>批次内部失败只回滚这一批：捕获异常后把作业标成 FAILED 并把原因写进 {@code last_error}，
   * 已经提交的批次保持已提交。不用「全部重来」——那会让一个百万级的撤权永远跑不完。
   *
   * <p>完成判据用<b>残留核对</b>而不是「处理条数等于目标条数」：执行期间来源可能又有成果公开
   * （治理动作放开了旧成果），目标数在受理那一刻就固定了，用它判完成会把「还剩几条」永远算错。
   */
  private DisplayLicenseOutcome drain(
      String sourceId, String sourceCode, Accepted accepted, boolean restoreDirection) {
    long processed = 0;
    String failure = null;
    for (int batch = 0; batch < MAX_BATCHES_PER_CALL; batch++) {
      try {
        Integer changed =
            tx.execute(
                status -> {
                  List<String> ids =
                      restoreDirection
                          ? achievements.restoreBatchForSource(sourceId, BATCH_SIZE)
                          : achievements.tightenBatchForSource(sourceId, BATCH_SIZE);
                  if (!ids.isEmpty()) {
                    revocations.addProcessed(accepted.jobId(), ids.size());
                  }
                  return ids.size();
                });
        processed += changed == null ? 0 : changed;
        if (changed == null || changed < BATCH_SIZE) {
          break;
        }
      } catch (RuntimeException ex) {
        failure = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        log.error(
            "展示许可变更批次失败 sourceId={} jobId={} 已处理={}", sourceId, accepted.jobId(), processed, ex);
        break;
      }
    }

    long residual =
        restoreDirection
            ? achievements.countRestrictedBySource(sourceId)
            : achievements.countPublicWithoutValidLicense(sourceId);
    boolean complete = failure == null && residual == 0;
    String note =
        complete
            ? null
            : (failure != null
                ? "第 " + processed + " 条后中断：" + failure
                : "本次调用处理 " + processed + " 条后仍有 " + residual + " 条待处理，可调用续跑接口");

    final long finalProcessed = processed;
    tx.executeWithoutResult(
        status ->
            revocations.setStatus(
                accepted.jobId(),
                complete
                    ? SourceRevocationMapper.STATUS_SUCCEEDED
                    : SourceRevocationMapper.STATUS_FAILED,
                note));

    SourceRevocationMapper.RevocationRow job = revocations.findById(accepted.jobId()).orElseThrow();
    return new DisplayLicenseOutcome(
        sourceCode,
        restoreDirection ? "GRANTED" : "REVOKED",
        accepted.jobId(),
        accepted.targetCount(),
        finalProcessed,
        residual,
        complete,
        note,
        accepted.requiredPublicRevision());
  }

  // ---------------------------------------------------------------------------
  // 其它
  // ---------------------------------------------------------------------------

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

  private SourceConfigRepository.SourceConfig requireSource(String sourceId) {
    return sourceConfigs.findById(sourceId).orElseThrow(() -> BusinessException.notFound("来源不存在"));
  }

  private static void requireReason(String reason, String message) {
    if (reason == null || reason.isBlank()) {
      throw BusinessException.invalidArgument(message);
    }
  }

  /**
   * 状态冲突时补一句「有什么活还没干完」。
   *
   * <p>只说「已经是这个状态了」，调用方会以为无事可做；而实际上可能正有一个跑到一半的作业，
   * 需要续跑才能把剩下的成果落地。
   */
  private String activeJobHint(String sourceId, String prefix) {
    return revocations
        .findActiveBySource(sourceId)
        .map(
            job ->
                prefix
                    + "；上一次作业 "
                    + job.id()
                    + " 尚未完成（已处理 "
                    + job.tightenedCount()
                    + " / 目标 "
                    + job.targetCount()
                    + "），可调用续跑接口")
        .orElse(prefix);
  }

  /** 采集开关变更结果。 */
  public record CollectionOutcome(String sourceCode, boolean enabled, long requiredPublicRevision) {

    /** 恒为 false：暂停采集不触碰展示许可。保留字段是为了让读到它的人确认这一点。 */
    public boolean displayLicenseTouched() {
      return false;
    }
  }

  /** 展示许可变更结果。 */
  public record DisplayLicenseOutcome(
      String sourceCode,
      String displayLicenseStatus,
      String jobId,
      long targetCount,
      long processedCount,
      long residualCount,
      boolean complete,
      String note,
      long requiredPublicRevision) {}

  /** 作业视图。 */
  public record RevocationView(
      String id,
      String sourceId,
      String requestedBy,
      String reason,
      long targetCount,
      long processedCount,
      String status,
      String lastError,
      String createdAt,
      String finishedAt) {

    static RevocationView of(SourceRevocationMapper.RevocationRow row) {
      return new RevocationView(
          row.id(),
          row.sourceId(),
          row.requestedBy(),
          row.reason(),
          row.targetCount(),
          row.tightenedCount(),
          row.status(),
          row.lastError(),
          row.createdAt() == null ? null : row.createdAt().toString(),
          row.finishedAt() == null ? null : row.finishedAt().toString());
    }
  }

  /** 受理结果（事务内产生，事务外使用）。 */
  private record Accepted(String jobId, long targetCount, long requiredPublicRevision) {}

  /** 供接口层拼装视图用：把来源配置转成对外字段，避免各处重复实现同一份口径。 */
  public static Map<String, Object> toView(SourceConfigRepository.SourceConfig config) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", config.id());
    view.put("sourceCode", config.sourceCode());
    view.put("displayName", config.displayName());
    view.put("enabled", config.enabled());
    view.put("licenseScope", config.licenseScope());
    view.put("baseUrl", config.baseUrl());
    view.put("configVersion", config.configVersion());
    view.put("allowedFields", config.allowedFields());
    view.put("displayLicenseStatus", config.displayLicenseStatus());
    view.put(
        "displayLicenseChangedAt",
        config.displayLicenseChangedAt() == null
            ? null
            : config.displayLicenseChangedAt().toString());
    view.put("displayLicenseNote", config.displayLicenseNote());
    view.put("displayLicenseGranted", config.displayLicenseGranted());
    return view;
  }
}
