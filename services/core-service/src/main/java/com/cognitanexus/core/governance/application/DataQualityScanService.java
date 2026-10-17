package com.cognitanexus.core.governance.application;

import com.cognitanexus.core.governance.application.GovernanceRepositories.DataQualityRepository;
import com.cognitanexus.core.governance.domain.GovernanceAnomaly;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.Capabilities;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.platform.util.Identifiers;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 数据质量巡检。
 *
 * <p>依据：AC-IMPL-FR08-01（异常必须单列披露）、BR-IMP-01～12（各类治理红线）。
 *
 * <p><b>为什么需要它。</b>此前异常只由写入路径在遇到坏数据时顺带登记——来源停用、缺必填字段、
 * 标识冲突。这三类都要求「当时正好有一条记录流进来」，因此库里已有的问题（例如公开但没有任何
 * 领域归属的成果）永远不会被登记，异常页长期为空。空页不能说明数据是干净的，只能说明没有检测。
 *
 * <p><b>巡检做什么、不做什么。</b>它只读业务表、只登记与复核异常，<b>不修改任何业务数据</b>。
 * 检测到的结论一律以「待人工处置」出现：同名学者不自动合并、缺领域归属不自动推断、年份越界不自动
 * 猜测正确年份。把这几件事做成自动修复看起来更「智能」，代价是猜出来的结论会直接进入公开目录，
 * 而目录里没有任何痕迹说明它是猜的。
 *
 * <p><b>粒度分两种。</b>逐条缺陷（年份越界、领域归属冲突）每条登记一条，因为每条都要单独判断；
 * 批量缺陷（无领域归属、无参与关系、论文无标识、同名聚集）每类登记一条并随巡检刷新计数，因为
 * 它们的处置动作是批量的，逐条登记只会把待办列表淹成同一个数字。
 *
 * <p><b>可重复运行。</b>同一问题按去重键只保留一条未处理异常；再次巡检时刷新 detail 里的计数与
 * 样例，而不是再插一条。若问题已经消失，对应的未处理异常会被自动关闭并写明原因——一个「打开着
 * 但已经不成立」的待办会让治理人员反复核查同一件不存在的事。
 */
@Service
public class DataQualityScanService {

  private static final Logger log = LoggerFactory.getLogger(DataQualityScanService.class);

  /** 巡检规则版本。口径变化时必须提升。 */
  public static final String RULE_VERSION = "DQ_SCAN_V1";

  /** 单类逐条异常的单次登记上限。超出时报告里标注「已截断」，不把截断后的数字当全量。 */
  static final int MAX_RECORD_FINDINGS = 200;

  /** 聚合异常里带的样例条数：够人工看出问题长什么样，又不至于把一条待办变成一次导出。 */
  static final int SAMPLE_LIMIT = 5;

  /** 同名分组单次列出多少组。 */
  static final int NAME_GROUP_LIMIT = 10;

  /** 同名多少人算「聚集」。低于这个数量同名是常态，登记进来只会制造噪声。 */
  static final int NAME_GROUP_MIN_COUNT = 3;

  /** 一条学者记录上多少条待核验关系算「聚集」。 */
  static final int NAME_PENDING_MIN_COUNT = 3;

  /** 复核时最多读取多少条既有未处理异常。 */
  static final int MAX_OPEN_SCAN = 500;

  /** 年份合理下界。再早的年份在本平台的数据来源里不存在，出现即为字段污染。 */
  static final int MIN_YEAR = 1800;

  /** 年份上界允许到明年：跨年预发布是真实存在的。 */
  static final int YEAR_LOOKAHEAD = 1;

  private final DataQualityRepository data;
  private final GovernanceRepositories.AnomalyRepository anomalies;
  private final AuditRecorder audit;

  public DataQualityScanService(
      DataQualityRepository data,
      GovernanceRepositories.AnomalyRepository anomalies,
      AuditRecorder audit) {
    this.data = data;
    this.anomalies = anomalies;
    this.audit = audit;
  }

  /**
   * 跑一次巡检。
   *
   * <p>整个过程在一个事务内：登记、刷新、关闭要么一起成立，要么一起回滚。中途失败留下「登记了
   * 一半」的异常会让下一次巡检读到不一致的基线。
   */
  @Transactional
  public Report scan(ActorContext actor) {
    Capabilities.require(actor, RoleNames.CAP_GOVERNANCE_MANAGE);
    int maxYear = ZonedDateTime.now(ZoneId.systemDefault()).getYear() + YEAR_LOOKAHEAD;
    List<Finding> findings = new ArrayList<>();
    int created = 0;
    int refreshed = 0;
    int autoClosed = 0;

    // ---- 逐条缺陷：年份越界 ----
    List<String> badYearIds = data.yearOutOfRangeIds(MAX_RECORD_FINDINGS + 1);
    boolean yearTruncated = badYearIds.size() > MAX_RECORD_FINDINGS;
    List<String> yearIds = yearTruncated ? badYearIds.subList(0, MAX_RECORD_FINDINGS) : badYearIds;
    Map<String, DataQualityRepository.Sample> yearSamples =
        byId(data.yearOutOfRangeSamples(MAX_RECORD_FINDINGS));
    Outcome yearOutcome =
        upsertRecords(
            GovernanceAnomaly.KIND_YEAR_OUT_OF_RANGE,
            yearIds,
            id ->
                recordDetail(
                    GovernanceAnomaly.KIND_YEAR_OUT_OF_RANGE,
                    id,
                    "年份 "
                        + yearOf(yearSamples.get(id))
                        + " 超出合理区间 ["
                        + MIN_YEAR
                        + ", "
                        + maxYear
                        + "]",
                    "publication_year 落在 ["
                        + MIN_YEAR
                        + ", "
                        + maxYear
                        + "] 之外；"
                        + "来源把非年份内容写进了年份字段，或解析时发生了截断/位移",
                    "核对来源原始记录中的年份字段；确认正确年份后在成果上人工补齐，不要按相邻数字猜测",
                    yearSamples.containsKey(id) ? sampleView(yearSamples.get(id)) : Map.of(),
                    yearTruncated),
            actor);
    created += yearOutcome.created();
    refreshed += yearOutcome.refreshed();
    // 截断时不做「问题已消失」的复核：没被扫到的条目会被误判成已解决。
    int yearClosed =
        yearTruncated
            ? 0
            : reconcileRecords(GovernanceAnomaly.KIND_YEAR_OUT_OF_RANGE, yearIds, actor);
    autoClosed += yearClosed;
    findings.add(
        new Finding(
            GovernanceAnomaly.KIND_YEAR_OUT_OF_RANGE,
            GovernanceAnomaly.label(GovernanceAnomaly.KIND_YEAR_OUT_OF_RANGE),
            Granularity.RECORD,
            yearIds.size(),
            yearOutcome.created(),
            yearOutcome.refreshed(),
            yearClosed,
            yearTruncated));

    // ---- 逐条缺陷：领域归属冲突 ----
    List<String> conflictIds = data.topicKindConflictIds(MAX_RECORD_FINDINGS + 1);
    boolean conflictTruncated = conflictIds.size() > MAX_RECORD_FINDINGS;
    List<String> conflictPage =
        conflictTruncated ? conflictIds.subList(0, MAX_RECORD_FINDINGS) : conflictIds;
    Map<String, DataQualityRepository.Sample> conflictSamples =
        byId(data.topicKindConflictSamples(MAX_RECORD_FINDINGS));
    Outcome conflictOutcome =
        upsertRecords(
            GovernanceAnomaly.KIND_TOPIC_KIND_CONFLICT,
            conflictPage,
            id ->
                recordDetail(
                    GovernanceAnomaly.KIND_TOPIC_KIND_CONFLICT,
                    id,
                    "同一成果同一领域同时存在来源给定与推断归属",
                    "core_achievement_topic 中同一 (成果, 领域) 同时存在 assignment_kind=SOURCE 与 "
                        + "assignment_kind=INFERRED；来源分类在场时推断归属应已被撤销",
                    "核查该领域的归属来源；撤销多余的推断归属行，保留来源给定的那条（来源原值不得被推断覆盖）",
                    conflictSamples.containsKey(id)
                        ? sampleView(conflictSamples.get(id))
                        : Map.of(),
                    conflictTruncated),
            actor);
    created += conflictOutcome.created();
    refreshed += conflictOutcome.refreshed();
    int conflictClosed =
        conflictTruncated
            ? 0
            : reconcileRecords(GovernanceAnomaly.KIND_TOPIC_KIND_CONFLICT, conflictPage, actor);
    autoClosed += conflictClosed;
    findings.add(
        new Finding(
            GovernanceAnomaly.KIND_TOPIC_KIND_CONFLICT,
            GovernanceAnomaly.label(GovernanceAnomaly.KIND_TOPIC_KIND_CONFLICT),
            Granularity.RECORD,
            conflictPage.size(),
            conflictOutcome.created(),
            conflictOutcome.refreshed(),
            conflictClosed,
            conflictTruncated));

    // ---- 批量缺陷：已生效成果没有领域归属 ----
    AggregateOutcome noTopic =
        upsertAggregate(
            GovernanceAnomaly.KIND_MISSING_TOPIC,
            data.effectiveWithoutTopicByType(),
            data.effectiveWithoutTopicSamples(SAMPLE_LIMIT),
            "已生效成果没有任何领域归属",
            "effective_status=EFFECTIVE 且 merged_into_id 为空，且 core_achievement_topic 无任何行；"
                + "这类成果不会出现在领域分面与领域统计里",
            "补齐领域归属。推断归属必须标 assignment_kind=INFERRED 并写明规则版本，" + "不得写成来源给定",
            actor);
    created += noTopic.created();
    refreshed += noTopic.refreshed();
    autoClosed += noTopic.autoClosed();
    findings.add(noTopic.toFinding());

    // ---- 批量缺陷：已生效成果没有参与关系 ----
    AggregateOutcome noContributor =
        upsertAggregate(
            GovernanceAnomaly.KIND_NO_CONTRIBUTOR,
            data.effectiveWithoutContributorByType(),
            data.effectiveWithoutContributorSamples(SAMPLE_LIMIT),
            "已生效成果没有任何参与关系",
            "effective_status=EFFECTIVE 且 merged_into_id 为空，且 core_contribution 无任何行；"
                + "公开详情会显示空的作者列表，共著网络也取不到节点",
            "回到来源记录补齐参与者；无法补齐时不应保持生效状态——无参与者的成果无法被认领与核验",
            actor);
    created += noContributor.created();
    refreshed += noContributor.refreshed();
    autoClosed += noContributor.autoClosed();
    findings.add(noContributor.toFinding());

    // ---- 批量缺陷：已生效论文没有稳定标识 ----
    AggregateOutcome noIdentifier =
        upsertAggregate(
            GovernanceAnomaly.KIND_PAPER_WITHOUT_IDENTIFIER,
            data.papersWithoutIdentifierByType(),
            data.papersWithoutIdentifierSamples(SAMPLE_LIMIT),
            "已生效论文没有登记任何稳定标识",
            "type=PAPER、effective_status=EFFECTIVE、merged_into_id 为空，且 "
                + "core_identifier_registry 无 ACHIEVEMENT 标识；这类成果只能靠人工确认发布，"
                + "再次入库时也无法按标识去重",
            "从来源记录补齐 DOI/来源标识；补齐后重新评估发布状态",
            actor);
    created += noIdentifier.created();
    refreshed += noIdentifier.refreshed();
    autoClosed += noIdentifier.autoClosed();
    findings.add(noIdentifier.toFinding());

    // ---- 批量缺陷：同名待核验聚集（本平台同名风险的真实落点） ----
    AggregateOutcome ambiguousName = upsertAmbiguousNames(actor);
    created += ambiguousName.created();
    refreshed += ambiguousName.refreshed();
    autoClosed += ambiguousName.autoClosed();
    findings.add(ambiguousName.toFinding());

    // ---- 批量缺陷：同名学者聚集（不是同人证据） ----
    AggregateOutcome sameName = upsertSameName(actor);
    created += sameName.created();
    refreshed += sameName.refreshed();
    autoClosed += sameName.autoClosed();
    findings.add(sameName.toFinding());

    long openTotal = anomalies.countOpenAnomalies();
    audit.record(
        AuditRecorder.AuditEntry.of(
            actor,
            "GOVERNANCE_QUALITY_SCAN",
            "GOVERNANCE_SCAN",
            RULE_VERSION,
            null,
            Map.of("openAnomalies", openTotal),
            "巡检新建 " + created + " 条、复核 " + refreshed + " 条、自动关闭 " + autoClosed + " 条"));
    log.info("数据质量巡检完成：新建 {} 复核 {} 自动关闭 {} 当前未处理 {}", created, refreshed, autoClosed, openTotal);

    return new Report(
        RULE_VERSION,
        Instant.now(),
        ZoneId.systemDefault().getId(),
        Map.of("minYear", MIN_YEAR, "maxYear", maxYear),
        openTotal,
        created,
        refreshed,
        autoClosed,
        List.copyOf(findings));
  }

  /** 逐条登记：同一实体只保留一条未处理异常。 */
  private Outcome upsertRecords(
      String kind,
      List<String> ids,
      java.util.function.Function<String, Map<String, Object>> detailOf,
      ActorContext actor) {
    int created = 0;
    int refreshed = 0;
    for (String id : new LinkedHashSet<>(ids)) {
      Map<String, Object> detail = detailOf.apply(id);
      Optional<String> existing = anomalies.findOpenIdByDedupeKey(kind, id);
      if (existing.isPresent()) {
        if (anomalies.refreshDetail(existing.get(), detail)) {
          refreshed++;
        }
        continue;
      }
      anomalies.record(
          new GovernanceAnomaly(
              Identifiers.next("anm"), kind, null, id, detail, "OPEN", Instant.now(), null));
      created++;
    }
    return new Outcome(created, refreshed);
  }

  /**
   * 复核既有逐条异常：本次没有再次命中的，说明问题已经消失，自动关闭。
   *
   * <p>只关闭「扫描结果未截断」时的条数——扫描被上限截断时，没被扫到的实体本来就不会出现在本次
   * 结果里，把它们判成已解决是把截断当成了结论。
   */
  private int reconcileRecords(String kind, List<String> currentlyViolating, ActorContext actor) {
    Set<String> stillBad = new LinkedHashSet<>(currentlyViolating);
    int closed = 0;
    for (GovernanceRepositories.AnomalyRepository.OpenAnomalyKey open :
        anomalies.listOpenOfKind(kind, MAX_OPEN_SCAN)) {
      if (open.entityId() != null && stillBad.contains(open.entityId())) {
        continue;
      }
      if (anomalies.resolve(open.id())) {
        closed++;
        // 一条异常一条审计：审计要能回答「这条待办是谁关的」，而不是只知道「这一批关了几条」。
        audit.record(
            AuditRecorder.AuditEntry.of(
                actor,
                "GOVERNANCE_ANOMALY_AUTO_CLOSED",
                "GOVERNANCE_ANOMALY",
                open.id(),
                Map.of("kind", kind, "entityId", open.entityId() == null ? "" : open.entityId()),
                Map.of("status", "RESOLVED"),
                "巡检复核：该类问题在本次扫描中已不再命中"));
      }
    }
    if (closed > 0) {
      log.info("巡检自动关闭 {} 条已消失的 {} 异常", closed, kind);
    }
    return closed;
  }

  /** 批量登记：每类一条，计数随巡检刷新；计数归零则关闭。 */
  private AggregateOutcome upsertAggregate(
      String kind,
      List<DataQualityRepository.Bucket> buckets,
      List<DataQualityRepository.Sample> samples,
      String headline,
      String criteria,
      String suggestedAction,
      ActorContext actor) {
    long total = 0;
    Map<String, Object> byType = new LinkedHashMap<>();
    for (DataQualityRepository.Bucket bucket : buckets) {
      byType.put(bucket.bucket(), bucket.total());
      total += bucket.total();
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("dedupeKey", "ALL");
    detail.put("summary", headline + "：" + total + " 条" + typeSuffix(byType));
    detail.put("criteria", criteria);
    detail.put("count", total);
    detail.put("byType", byType);
    detail.put("samples", sampleViews(samples));
    detail.put("suggestedAction", suggestedAction);
    detail.put("ruleVersion", RULE_VERSION);
    detail.put("scannedAt", Instant.now().toString());

    return writeAggregate(kind, total, detail, actor);
  }

  /**
   * 同名待核验聚集。
   *
   * <p>这一项回答的是「同名到底压到了什么程度」：接入路径遇到同名但无法确认同一人时，会复用既有
   * 学者记录并把关系标为待核验。于是某个人名记录下可能同时挂着好几位不同学者的成果，而这些关系
   * 既不进共著网络、也不构成任何可公开的合作结论。计数是「待核验关系条数」，学者数另给。
   */
  private AggregateOutcome upsertAmbiguousNames(ActorContext actor) {
    long scholars = data.countAmbiguousScholars(NAME_PENDING_MIN_COUNT);
    long pending = data.countPendingRelationsOfAmbiguous(NAME_PENDING_MIN_COUNT);
    List<Map<String, Object>> samples = new ArrayList<>();
    for (DataQualityRepository.AmbiguousScholar scholar :
        data.ambiguousScholars(NAME_PENDING_MIN_COUNT, NAME_GROUP_LIMIT)) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("scholarId", scholar.scholarId());
      row.put("displayName", scholar.displayName());
      row.put("pendingRelations", scholar.pending());
      samples.add(row);
    }

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("dedupeKey", "ALL");
    detail.put(
        "summary",
        "待核验关系聚集：学者 "
            + scholars
            + " 人、关系 "
            + pending
            + " 条（单条学者记录上待核验关系 ≥ "
            + NAME_PENDING_MIN_COUNT
            + " 条）");
    detail.put(
        "criteria",
        "core_contribution.relation_state = PENDING_VERIFY 按 scholar_id 分组，组内条数 ≥ "
            + NAME_PENDING_MIN_COUNT
            + "。待核验关系的成因是「姓名相同但无法确认为同一人」：接入时复用既有学者、不新建、"
            + "也不直接确认为同一人；这些关系不参与共著边与确定合作");
    detail.put("count", pending);
    detail.put("scholarCount", scholars);
    detail.put("threshold", NAME_PENDING_MIN_COUNT);
    detail.put("samples", samples);
    detail.put(
        "suggestedAction", "逐人核验：结合机构、研究方向与代表作判断是否同一人。确认后由本人在门户确认关系，" + "或由治理端对关系作出裁决；不得凭姓名相同批量确认");
    detail.put("ruleVersion", RULE_VERSION);
    detail.put("scannedAt", Instant.now().toString());

    return writeAggregate(GovernanceAnomaly.KIND_AMBIGUOUS_SCHOLAR_NAME, pending, detail, actor);
  }

  /** 同名学者：按姓名分组登记，detail 里带「同名不等于同人」的明确声明。 */
  private AggregateOutcome upsertSameName(ActorContext actor) {
    List<DataQualityRepository.NameGroup> groups =
        data.duplicateScholarNames(NAME_GROUP_MIN_COUNT, NAME_GROUP_LIMIT);
    long total = 0;
    List<Map<String, Object>> groupViews = new ArrayList<>();
    for (DataQualityRepository.NameGroup group : groups) {
      total += group.total();
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("nameNorm", group.nameNorm());
      view.put("scholarCount", group.total());
      List<Map<String, Object>> scholars = new ArrayList<>();
      for (DataQualityRepository.ScholarSample scholar :
          data.scholarsByNameNorm(group.nameNorm(), SAMPLE_LIMIT)) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", scholar.id());
        row.put("displayName", scholar.displayName());
        row.put("organizationName", scholar.organizationName());
        row.put("claimedStatus", scholar.claimedStatus());
        scholars.add(row);
      }
      view.put("sampleScholars", scholars);
      groupViews.add(view);
    }

    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("dedupeKey", "ALL");
    detail.put(
        "summary",
        "同名但未合并的学者姓名组 "
            + groups.size()
            + " 组、涉及学者 "
            + total
            + " 人（每组至少 "
            + NAME_GROUP_MIN_COUNT
            + " 人）");
    detail.put(
        "criteria",
        "core_scholar 按 name_norm 分组，组内 merged_into_id 为空的人数 ≥ "
            + NAME_GROUP_MIN_COUNT
            + "；同名是常见现象，本项只提示「值得核对的姓名」，不作为同人证据");
    detail.put("groupCount", groups.size());
    detail.put("scholarCount", total);
    detail.put("groups", groupViews);
    detail.put("suggestedAction", "按机构、研究方向、代表作逐一比对后，对确认同一人的学者执行合并；" + "不得仅凭姓名相同合并（BR-IMP-03）");
    detail.put("ruleVersion", RULE_VERSION);
    detail.put("scannedAt", Instant.now().toString());

    return writeAggregate(
        GovernanceAnomaly.KIND_DUPLICATE_SCHOLAR_NAME, groups.size(), detail, actor);
  }

  private AggregateOutcome writeAggregate(
      String kind, long measured, Map<String, Object> detail, ActorContext actor) {
    Optional<String> existing = anomalies.findOpenIdByDedupeKey(kind, "ALL");
    if (measured == 0) {
      if (existing.isEmpty()) {
        return new AggregateOutcome(kind, 0, 0, 0, 0);
      }
      boolean closed = anomalies.resolve(existing.get());
      if (closed) {
        audit.record(
            AuditRecorder.AuditEntry.of(
                actor,
                "GOVERNANCE_ANOMALY_AUTO_CLOSED",
                "GOVERNANCE_ANOMALY",
                existing.get(),
                null,
                Map.of("kind", kind),
                "巡检复核：该类问题在本次扫描中计数为 0"));
      }
      return new AggregateOutcome(kind, 0, 0, closed ? 1 : 0, 0);
    }
    if (existing.isPresent()) {
      boolean ok = anomalies.refreshDetail(existing.get(), detail);
      return new AggregateOutcome(kind, measured, 0, 0, ok ? 1 : 0);
    }
    anomalies.record(
        new GovernanceAnomaly(
            Identifiers.next("anm"), kind, null, null, detail, "OPEN", Instant.now(), null));
    return new AggregateOutcome(kind, measured, 1, 0, 0);
  }

  private Map<String, Object> recordDetail(
      String kind,
      String entityId,
      String summary,
      String criteria,
      String suggestedAction,
      Map<String, Object> sample,
      boolean truncated) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("dedupeKey", entityId);
    detail.put("summary", summary);
    detail.put("criteria", criteria);
    detail.put("suggestedAction", suggestedAction);
    if (!sample.isEmpty()) {
      detail.put("achievement", sample);
    }
    detail.put("ruleVersion", RULE_VERSION);
    detail.put("scannedAt", Instant.now().toString());
    if (truncated) {
      detail.put("truncated", true);
    }
    return detail;
  }

  private static Map<String, DataQualityRepository.Sample> byId(
      List<DataQualityRepository.Sample> samples) {
    Map<String, DataQualityRepository.Sample> map = new LinkedHashMap<>();
    for (DataQualityRepository.Sample sample : samples) {
      map.put(sample.id(), sample);
    }
    return map;
  }

  private static List<Map<String, Object>> sampleViews(List<DataQualityRepository.Sample> samples) {
    List<Map<String, Object>> views = new ArrayList<>(samples.size());
    for (DataQualityRepository.Sample sample : samples) {
      views.add(sampleView(sample));
    }
    return views;
  }

  private static Map<String, Object> sampleView(DataQualityRepository.Sample sample) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", sample.id());
    view.put("title", sample.title());
    view.put("type", sample.type());
    view.put("publicationYear", sample.publicationYear());
    view.put("yearKnown", sample.publicationYear() != null);
    view.put(
        "publicationYearLabel",
        sample.publicationYear() == null ? "未知" : String.valueOf(sample.publicationYear()));
    return view;
  }

  private static String yearOf(DataQualityRepository.Sample sample) {
    return sample == null || sample.publicationYear() == null
        ? "未知"
        : String.valueOf(sample.publicationYear());
  }

  private static String typeSuffix(Map<String, Object> byType) {
    if (byType.isEmpty()) {
      return "";
    }
    List<String> parts = new ArrayList<>();
    byType.forEach((type, count) -> parts.add(type + " " + count));
    return "（" + String.join("、", parts) + "）";
  }

  /** 异常粒度。影响的是「一条待办代表一个实体还是代表一类问题」。 */
  public enum Granularity {
    RECORD,
    AGGREGATE
  }

  /** 单类检测结果。 */
  public record Finding(
      String kind,
      String label,
      Granularity granularity,
      long detected,
      int created,
      int refreshed,
      int autoClosed,
      boolean truncated) {}

  /** 巡检报告。 */
  public record Report(
      String ruleVersion,
      Instant scannedAt,
      String timeZone,
      Map<String, Object> yearBounds,
      long openAnomalies,
      int created,
      int refreshed,
      int autoClosed,
      List<Finding> findings) {}

  private record Outcome(int created, int refreshed) {}

  private record AggregateOutcome(
      String kind, long detected, int created, int autoClosed, int refreshed) {

    Finding toFinding() {
      return new Finding(
          kind,
          GovernanceAnomaly.label(kind),
          Granularity.AGGREGATE,
          detected,
          created,
          refreshed,
          autoClosed,
          false);
    }
  }
}
