package com.cognitanexus.core.catalog.application;

import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ContributionRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.FieldProvenanceRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.IdentifierRegistryRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ScholarRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.SourceRecordRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.TopicRepository;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.EffectiveStatus;
import com.cognitanexus.core.catalog.domain.FieldProvenance;
import com.cognitanexus.core.governance.application.GovernanceService;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.event.EventTypes;
import com.cognitanexus.core.platform.event.OutboxEvent;
import com.cognitanexus.core.platform.event.OutboxStore;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.Capabilities;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.platform.util.Identifiers;
import com.cognitanexus.core.platform.util.PageRequest;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.DataNature;
import com.cognitanexus.sharedkernel.Visibility;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理端目录操作与运行概览。
 *
 * <p>依据：架构 V0.2 §10.2（能力校验）、AC-IMPL-FR10-01、§12.4（必备指标）。
 *
 * <p>概览里的每个计数都来自权威表，不使用估算值；未精确统计的项明确说明而不填 0 冒充已知。
 */
@Service
public class AdminCatalogService {

  private static final Logger log = LoggerFactory.getLogger(AdminCatalogService.class);

  private final AchievementRepository achievements;
  private final ScholarRepository scholars;
  private final ContributionRepository contributions;
  private final SourceRecordRepository sourceRecords;
  private final CatalogRepositories.SourceConfigRepository sourceConfigs;
  private final TopicRepository topics;
  private final FieldProvenanceRepository provenance;
  private final IdentifierRegistryRepository identifiers;
  private final GovernanceService governance;
  private final PublicCatalogQueryService publicCatalog;
  private final OutboxStore outbox;
  private final AuditRecorder audit;
  private final PublicProjectionAssembler projections;
  private final CatalogChangePublisher catalogChanges;

  public AdminCatalogService(
      AchievementRepository achievements,
      ScholarRepository scholars,
      ContributionRepository contributions,
      SourceRecordRepository sourceRecords,
      CatalogRepositories.SourceConfigRepository sourceConfigs,
      TopicRepository topics,
      FieldProvenanceRepository provenance,
      IdentifierRegistryRepository identifiers,
      GovernanceService governance,
      PublicCatalogQueryService publicCatalog,
      OutboxStore outbox,
      AuditRecorder audit,
      PublicProjectionAssembler projections,
      CatalogChangePublisher catalogChanges) {
    this.achievements = achievements;
    this.scholars = scholars;
    this.contributions = contributions;
    this.sourceRecords = sourceRecords;
    this.sourceConfigs = sourceConfigs;
    this.topics = topics;
    this.provenance = provenance;
    this.identifiers = identifiers;
    this.governance = governance;
    this.publicCatalog = publicCatalog;
    this.outbox = outbox;
    this.audit = audit;
    this.projections = projections;
    this.catalogChanges = catalogChanges;
  }

  /** 运行概览：权威计数 + 事件积压 + 治理待办。 */
  @Transactional(readOnly = true)
  public Map<String, Object> overview(ActorContext actor) {
    Capabilities.require(actor, RoleNames.CAP_GOVERNANCE_MANAGE);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put(
        "achievementsTotal",
        achievements.countByNatureAndStatus(
                DataNature.REAL.name(), EffectiveStatus.EFFECTIVE.name())
            + achievements.countByStatus(EffectiveStatus.PENDING_GOVERNANCE.name())
            + achievements.countByStatus(EffectiveStatus.WITHDRAWN.name()));
    view.put(
        "achievementsPending",
        achievements.countByStatus(EffectiveStatus.PENDING_GOVERNANCE.name()));
    view.put("achievementsEffective", achievements.countByStatus(EffectiveStatus.EFFECTIVE.name()));
    view.put("achievementsPublic", achievements.countPublicForExport());
    view.put(
        "achievementsReal",
        achievements.countByNatureAndStatus(
            DataNature.REAL.name(), EffectiveStatus.EFFECTIVE.name()));
    view.put(
        "achievementsSimulated",
        achievements.countByNatureAndStatus(
            DataNature.SIMULATED.name(), EffectiveStatus.EFFECTIVE.name()));
    view.put(
        "achievementsMixed",
        achievements.countByNatureAndStatus(
            DataNature.MIXED.name(), EffectiveStatus.EFFECTIVE.name()));
    view.put("scholars", scholars.countAll());
    view.put("contributions", contributions.countContributions());
    view.put("topics", topics.countTopics());
    view.put("sourceRecords", sourceRecords.countAll());
    view.put("dedupCandidatesOpen", governance.countOpenCandidates(actor));
    view.put("anomaliesOpen", governance.countOpenAnomalies(actor));
    view.put("catalogRevision", outbox.currentRevision());
    view.put("requiredPublicRevision", outbox.requiredPublicRevision());

    Map<String, Object> delivery = new LinkedHashMap<>();
    for (String consumer : outbox.consumers()) {
      Map<String, Object> status = new LinkedHashMap<>();
      status.put("pending", outbox.pendingCount(consumer));
      status.put("oldestPendingRevision", outbox.oldestPendingRevision(consumer));
      status.put("failed", outbox.failedCount(consumer));
      delivery.put(consumer, status);
    }
    view.put("outboxDelivery", delivery);
    view.put("note", "计数口径：公开数为「公开且生效且未被合并」的成果；不同数据性质分别列出，不合并成一个总数");
    return view;
  }

  /** 待治理成果列表。 */
  @Transactional(readOnly = true)
  public List<Map<String, Object>> listPending(ActorContext actor, PageRequest page) {
    Capabilities.require(actor, RoleNames.CAP_GOVERNANCE_MANAGE);
    return achievements
        .listByStatus(EffectiveStatus.PENDING_GOVERNANCE.name(), page.size(), page.offset())
        .stream()
        .map(this::briefView)
        .toList();
  }

  @Transactional(readOnly = true)
  public long countPending() {
    return achievements.countByStatus(EffectiveStatus.PENDING_GOVERNANCE.name());
  }

  /**
   * 人工确认发布。
   *
   * <p>发布前再次核对类型扩展：数据库的延迟约束触发器也会拒绝「公开但缺少类型扩展」的成果，
   * 这里提前给出可解释的错误，避免把约束违例暴露成 500。
   */
  @Transactional
  public Map<String, Object> confirmPublication(
      ActorContext actor, String achievementId, String reason) {
    Capabilities.require(actor, RoleNames.CAP_ACHIEVEMENT_CONFIRM);
    if (reason == null || reason.isBlank()) {
      throw BusinessException.invalidArgument("必须说明确认发布的依据");
    }
    Achievement achievement =
        achievements.findById(achievementId).orElseThrow(() -> BusinessException.notFound("成果不存在"));
    if (achievement.merged()) {
      throw BusinessException.businessRule("该成果已被合并，请对幸存实体操作");
    }
    // 这里要判的是「当前是否处于待治理状态」，不是 publiclyEligible()。后者表达的是「已经是
    // EFFECTIVE」，用它取反会把待治理成果全部挡在门外——人工确认发布对唯一该走这条路的实体
    // 永远返回 409，而错误文案还写着「只有待治理状态的成果可以确认发布」，正好相反。
    //
    // 已下架（WITHDRAWN）也允许重新确认：撤销是治理端的下架动作，如果它不可逆，那么一次误撤
    // 或一次「先下架、核实后再上架」的处置就把成果永久钉死在不可见状态——既没有入口恢复，
    // 也没有任何提示说明只能等来源重新采集。下架与重新上架是一对对称的人工动作，都要留审计。
    if (achievement.effectiveStatus() != EffectiveStatus.PENDING_GOVERNANCE
        && achievement.effectiveStatus() != EffectiveStatus.WITHDRAWN) {
      throw BusinessException.versionConflict("只有待治理或已下架的成果可以确认发布");
    }
    if (!achievement.dataNature().eligibleForRealityView()) {
      throw BusinessException.businessRule("数据性质为 " + achievement.dataNature() + " 的成果不得进入默认现实视图");
    }
    int extensionCount = countExtensions(achievementId, achievement);
    if (extensionCount != 1) {
      throw new BusinessException(
          com.cognitanexus.sharedkernel.ErrorCode.BUSINESS_RULE_VIOLATION,
          "类型扩展数量为 " + extensionCount + "，公开成果必须恰好有一个与类型一致的扩展");
    }
    int affected =
        achievements.updatePublicationState(
            achievementId,
            EffectiveStatus.EFFECTIVE.name(),
            Visibility.PUBLIC.name(),
            achievement.version());
    if (affected != 1) {
      throw BusinessException.versionConflict("成果版本已被其他操作改变，请刷新后重试");
    }
    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "ACHIEVEMENT_PUBLISH_CONFIRMED",
            "ACHIEVEMENT",
            achievementId,
            Map.of(
                "effectiveStatus",
                achievement.effectiveStatus().name(),
                "version",
                achievement.version()),
            Map.of(
                "effectiveStatus",
                EffectiveStatus.EFFECTIVE.name(),
                "visibility",
                Visibility.PUBLIC.name()),
            reason));

    // 事件载荷的可见性取自更新后的实体，而不是重复一次「我知道刚才是设成 PUBLIC」的断言。
    // 同一个断言写在两处，早晚会有一处被新调用点继承而另一处被改掉（SourceIntakeService
    // 就出过这个问题：事件自称 PUBLIC、实体仍是 RESTRICTED，于是检索能搜到、权威侧却是 404）。
    Achievement refreshed = achievements.findById(achievementId).orElseThrow();
    publishCatalogChange(refreshed, achievement.version() + 1);

    return briefView(refreshed);
  }

  /**
   * 外发一次目录成果变更事件。
   *
   * <p>装配口径收敛在 {@link CatalogChangePublisher}：人工确认发布与来源自动发布必须发出字段集
   * 完全相同的事件，否则查询侧重建出来的投影会取决于「这条成果当年是怎么进公开目录的」。
   */
  private void publishCatalogChange(Achievement achievement, long aggregateVersion) {
    catalogChanges.publish(achievement, aggregateVersion);
  }

  /**
   * 裁决后重评估发布。
   *
   * <p>为什么需要它：成果停在待治理状态的原因之一是「存在未裁决的相似候选」。人把候选裁决完，
   * 这个原因就消失了，但成果不会自己回到公开目录——除非有人再逐条点一遍「确认发布」。
   * 于是积压的成因与解除条件对不上：工作台把候选清空，成果仍原地不动。
   *
   * <p>重评估只重跑既定的自动发布条件，一条都不放宽：仍有未裁决候选、没有稳定标识、数据性质
   * 不是真实、类型扩展不唯一，任一不满足就继续留在待治理。它也不冒充人工审核——审计事件的动作
   * 是 {@code ACHIEVEMENT_RELEASE_RECHECKED}，与人工「确认发布」分得开，事后能看出是谁放行的。
   *
   * @return 本次实际进入公开目录的成果标识
   */
  @Transactional
  public List<String> recheckReleaseAfterResolution(
      Collection<String> achievementIds, ActorContext actor, String reason) {
    if (achievementIds == null || achievementIds.isEmpty()) {
      return List.of();
    }
    String note = reason == null || reason.isBlank() ? "候选裁决后重评估发布" : reason;
    List<String> released = new ArrayList<>();
    for (String achievementId : new LinkedHashSet<>(achievementIds)) {
      if (achievementId == null || achievementId.isBlank()) {
        continue;
      }
      Optional<Achievement> found = achievements.findById(achievementId);
      if (found.isEmpty()) {
        continue;
      }
      Achievement achievement = found.get();
      // 只对「待治理」的成果重评估：已生效的不必再评，已撤回与已合并的都不该由一次裁决重新公开。
      if (achievement.merged()
          || achievement.effectiveStatus() != EffectiveStatus.PENDING_GOVERNANCE) {
        continue;
      }
      if (!achievement.dataNature().eligibleForRealityView()) {
        continue;
      }
      if (governance.countOpenCandidatesFor(achievementId) > 0) {
        continue;
      }
      if (!identifiers.hasRegistration("ACHIEVEMENT", achievementId)) {
        continue;
      }
      if (countExtensions(achievementId, achievement) != 1) {
        continue;
      }
      int affected =
          achievements.updatePublicationState(
              achievementId,
              EffectiveStatus.EFFECTIVE.name(),
              Visibility.PUBLIC.name(),
              achievement.version());
      if (affected != 1) {
        continue;
      }
      audit.record(
          AuditRecorder.AuditEntry.of(
              actor,
              "ACHIEVEMENT_RELEASE_RECHECKED",
              "ACHIEVEMENT",
              achievementId,
              Map.of(
                  "effectiveStatus", achievement.effectiveStatus().name(),
                  "version", achievement.version()),
              Map.of(
                  "effectiveStatus", EffectiveStatus.EFFECTIVE.name(),
                  "visibility", Visibility.PUBLIC.name()),
              note));
      Achievement refreshed = achievements.findById(achievementId).orElseThrow();
      publishCatalogChange(refreshed, achievement.version() + 1);
      released.add(achievementId);
    }
    if (!released.isEmpty()) {
      log.info("裁决后重评估发布 released={} 条", released.size());
    }
    return released;
  }

  /**
   * 撤回公开成果。
   *
   * <p>撤回属于「收紧」：同事务推进权威公开水位，使查询侧立即停止返回该成果，而不是等缓存过期。
   */
  @Transactional
  public Map<String, Object> withdraw(ActorContext actor, String achievementId, String reason) {
    Capabilities.require(actor, RoleNames.CAP_ACHIEVEMENT_CONFIRM);
    if (reason == null || reason.isBlank()) {
      throw BusinessException.invalidArgument("必须说明撤回理由");
    }
    Achievement achievement =
        achievements.findById(achievementId).orElseThrow(() -> BusinessException.notFound("成果不存在"));
    if (!achievement.effectiveStatus().publiclyEligible()) {
      throw BusinessException.versionConflict("该成果当前不是生效状态");
    }
    int affected =
        achievements.updatePublicationState(
            achievementId,
            EffectiveStatus.WITHDRAWN.name(),
            Visibility.RESTRICTED.name(),
            achievement.version());
    if (affected != 1) {
      throw BusinessException.versionConflict("成果版本已被其他操作改变，请刷新后重试");
    }
    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "ACHIEVEMENT_WITHDRAWN",
            "ACHIEVEMENT",
            achievementId,
            Map.of("effectiveStatus", achievement.effectiveStatus().name()),
            Map.of(
                "effectiveStatus",
                EffectiveStatus.WITHDRAWN.name(),
                "visibility",
                Visibility.RESTRICTED.name()),
            reason));
    outbox.append(
        OutboxEvent.builder(
                EventTypes.PUBLIC_VISIBILITY_TIGHTENED, EventTypes.AGG_ACHIEVEMENT, achievementId)
            .aggregateVersion(achievement.version() + 1)
            .put("achievementId", achievementId)
            .put("reason", reason)
            .put("tightened", List.of(achievementId)));
    outbox.tightenPublicVisibility();
    return briefView(achievements.findById(achievementId).orElseThrow());
  }

  /** 字段溯源明细：展示原值 / 人工值 / 已审核值的来源，便于核对「来源不覆盖人工值」。 */
  @Transactional(readOnly = true)
  public List<Map<String, Object>> provenanceOf(ActorContext actor, String achievementId) {
    Capabilities.require(actor, RoleNames.CAP_GOVERNANCE_MANAGE);
    List<Map<String, Object>> views = new ArrayList<>();
    for (FieldProvenance entry : provenance.listByEntity("ACHIEVEMENT", achievementId)) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("fieldName", entry.fieldName());
      view.put("valueLevel", entry.valueLevel().name());
      view.put("valueText", entry.valueText());
      view.put("sourceRecordId", entry.sourceRecordId());
      views.add(view);
    }
    return views;
  }

  /**
   * 导出单个来源的运行配置，供 ingestion 同步执行副本。
   *
   * <p>依据：架构 V0.2 §4.2。不做人类能力校验是有意的：本方法只被内部端点调用，而内部端点由
   * 服务令牌把关；在此再要求一个人类能力会把「服务身份」与「人类权限」两件事混在一起。
   * 导出内容只含许可范围与字段白名单等执行所需字段，不含账号或审计信息。
   *
   * @throws com.cognitanexus.sharedkernel.BusinessException 来源未登记时返回 404
   */
  @Transactional(readOnly = true)
  public Map<String, Object> exportSourceConfig(String sourceCode) {
    var config =
        sourceConfigs
            .findByCode(sourceCode)
            .orElseThrow(
                () ->
                    com.cognitanexus.sharedkernel.BusinessException.notFound(
                        "来源未登记：" + sourceCode));
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("sourceId", config.id());
    view.put("sourceCode", config.sourceCode());
    view.put("displayName", config.displayName());
    view.put("enabled", config.enabled());
    view.put("licenseScope", config.licenseScope());
    view.put("baseUrl", config.baseUrl());
    view.put("configVersion", config.configVersion());
    view.put("allowedFields", config.allowedFields());
    view.put("catalogRevision", outbox.currentRevision());
    view.put("requiredPublicRevision", outbox.requiredPublicRevision());
    return view;
  }

  /**
   * 公开投影导出：query 全量重建时的数据来源（架构 §7.3）。
   *
   * <p><b>导出项必须与事件载荷同字段集。</b>这里是第二个出过真实缺陷的地方：本方法早先只导出标题、
   * 年份、参与关系等字段，唯独漏了 {@code visibility} 与 {@code effectiveStatus}。查询侧导入时对
   * 缺失的可见性字段**失败关闭**（按 RESTRICTED / PENDING_GOVERNANCE 落库），于是<b>一次重建把
   * 导出回来的每一行都变成了非公开行</b>——权威侧有 6515 条公开成果，重建后的公开检索却返回
   * total=0。故障现象是「重建报告成功、平台却空了」，从重建日志里完全看不出异常。
   *
   * <p>根因不是漏了两个字段，而是同一份视图有<b>三个出口</b>（事件载荷、分页导出、单条导出）各自
   * 手写字段列表：只要有一处落后于其他两处，两条路径产出的投影就会出现字段级差异，而这类差异只在
   * 切换 generation 时才暴露。因此三处统一走 {@link #publicProjectionView(Achievement)}，字段集
   * 只有一处定义。
   */
  @Transactional(readOnly = true)
  public Map<String, Object> exportPublic(int limit, int offset) {
    List<Map<String, Object>> items = new ArrayList<>();
    for (Achievement achievement : achievements.listPublicForExport(limit, offset)) {
      items.add(publicProjectionView(achievement));
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("catalogRevision", outbox.currentRevision());
    result.put("requiredPublicRevision", outbox.requiredPublicRevision());
    result.put("total", achievements.countPublicForExport());
    result.put("limit", limit);
    result.put("offset", offset);
    result.put("items", items);
    result.put("note", "导出在同一只读快照内产生；分页读取时每页都带 catalogRevision，重建方必须在一致快照或回放事件后使用");
    return result;
  }

  /**
   * 单个成果的公开投影。
   *
   * <p>用于查询侧在收到合并事件后重新取回幸存实体的当前投影：合并事件只带标识，若查询侧
   * 靠自己拼接「幸存者现在的参与关系」，就等于在第二个地方重新实现了一遍核心合并语义，
   * 两边迟早不一致。让权威方直接给出投影，是避免这种重复实现的办法。
   *
   * <p>返回 empty 表示该成果当前不满足公开资格（非生效、非公开或已合并），此时查询侧应当
   * 把它从投影中移除，而不是保留一份过期的旧投影。
   */
  @Transactional(readOnly = true)
  public Optional<Map<String, Object>> exportPublicOne(String achievementId) {
    Optional<String> survivor = achievements.resolveSurvivorId(achievementId);
    String targetId = survivor.orElse(achievementId);
    Achievement achievement = achievements.findById(targetId).orElse(null);
    if (achievement == null
        || achievement.merged()
        || !achievement.visibility().publiclyVisible()
        || !achievement.effectiveStatus().publiclyEligible()) {
      return Optional.empty();
    }
    return Optional.of(publicProjectionView(achievement));
  }

  /**
   * 公开投影的规范字段集。
   *
   * <p><b>与事件载荷的关系是「投影字段集一致」，不是「两份报文逐字相等」。</b>事件是「发生了什么」，
   * 快照是「现在是什么」，必然各有独占字段。经确认允许不同的只有三处，且必须只有这三处：
   *
   * <ul>
   *   <li>{@code changeKind}——事件独占，它是控制字段（UPSERT/MERGE），投影侧不读；
   *   <li>{@code id} 在事件里叫 {@code achievementId}——同一字段的两个名字，查询侧
   *       {@code EventPayloadReader} 两条路径互为兜底；
   *   <li>{@code version}——快照放在载荷上，事件里由信封列 {@code aggregate_version} 承载，
   *       两者取值必须相等（增量路径读信封、重建路径读载荷，不等就会在换代时才显形）。
   * </ul>
   *
   * <p>除此之外，任一侧新增字段都必须同步到这里与 {@link CatalogChangePublisher}，否则两条路径
   * 产出的投影出现字段级差异。{@code tests/api/smoke-contract.py} 会按上述映射做集合比对。
   *
   * <p>可见性与生效状态在这里是**必填**：它们不是展示字段，而是查询侧读路径的过滤依据。导出时省略
   * 它们，等于把「这条成果是否公开」的判断权从权威侧交给了投影侧——而投影侧的兜底是失败关闭，
   * 于是整个公开视图会安静地清空。
   */
  private Map<String, Object> publicProjectionView(Achievement achievement) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", achievement.id());
    view.put("type", achievement.type().name());
    view.put("title", achievement.title());
    view.put("titleNorm", achievement.titleNorm());
    view.put("dataNature", achievement.dataNature().name());
    view.put("publicationYear", achievement.publicationYear().year());
    view.put("version", achievement.version());
    view.put("visibility", achievement.visibility().name());
    view.put("effectiveStatus", achievement.effectiveStatus().name());
    // 论文扩展字段：事件路径会带，导出路径早先漏了。漏掉的后果不是「少显示一个 DOI」，而是重建
    // 之后引用量、期刊、摘要集体归零，且与增量期写下的旧值混在同一张表里，无法分辨哪些是缺数据。
    achievements
        .findPaper(achievement.id())
        .ifPresent(
            p -> {
              view.put("doi", p.doi());
              view.put("venue", p.venue());
              view.put("citationCount", p.citationCount());
              view.put("citationSource", p.citationSource());
              view.put(
                  "publishedDate", p.publishedDate() == null ? null : p.publishedDate().toString());
              view.put("abstractText", p.abstractText());
            });
    view.put("contributions", projections.contributionsOf(achievement.id()));
    view.put("topics", projections.topicIdsOf(achievement.id()));
    view.put("topicViews", projections.topicsOf(achievement.id()));
    return view;
  }

  private int countExtensions(String achievementId, Achievement achievement) {
    return switch (achievement.type()) {
      case PAPER -> achievements.findPaper(achievementId).isPresent() ? 1 : 0;
      case PATENT -> achievements.findPatent(achievementId).isPresent() ? 1 : 0;
      case PROJECT -> achievements.findProject(achievementId).isPresent() ? 1 : 0;
      case AWARD -> achievements.findAward(achievementId).isPresent() ? 1 : 0;
    };
  }

  private Map<String, Object> briefView(Achievement achievement) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", achievement.id());
    view.put("type", achievement.type().name());
    view.put("typeLabel", achievement.type().label());
    view.put("title", achievement.title());
    view.put("dataNature", achievement.dataNature().name());
    view.put("visibility", achievement.visibility().name());
    view.put("effectiveStatus", achievement.effectiveStatus().name());
    view.put("publicationYear", achievement.publicationYear().year());
    view.put("version", achievement.version());
    view.put("publiclyVisible", publicCatalog.publiclyVisible(achievement.id()));
    return view;
  }

  /** 预留标识生成入口，供后续管理功能扩展时复用。 */
  static String nextId(String prefix) {
    return Identifiers.next(prefix);
  }
}
