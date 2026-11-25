package com.cognitanexus.query.graph.projection.application;

import com.cognitanexus.query.graph.application.GraphProjectionStore;
import com.cognitanexus.query.graph.domain.GraphProjectionMeta;
import com.cognitanexus.query.graph.projection.application.GraphProjectionSource.ContributionView;
import com.cognitanexus.query.graph.projection.domain.GraphProjectedWork;
import com.cognitanexus.query.graph.projection.domain.GraphReconciliationFinding;
import com.cognitanexus.query.graph.projection.domain.GraphReconciliationFinding.Severity;
import com.cognitanexus.query.graph.projection.domain.GraphReconciliationReport;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PG 权威读表与 Neo4j 图投影的数据对账（ADR-005 §6.2、§7、§8.2）。
 *
 * <p>对账不是「比一个总数」，而是逐条验证把 PG 变成图的四条映射规则：
 *
 * <ol>
 *   <li><b>成果合格性</b>：{@code visibility='PUBLIC' AND effective_status='EFFECTIVE'} ↔
 *       {@code Work.is_public=true}。数字不同意味着有成果该进图没进，或该退场没退（幽灵边）；
 *   <li><b>参与资格</b>：{@code relation_state IN ('AUTO','SELF_CONFIRMED')} ↔
 *       {@code AUTHORED.relation_state}。差一条就是「把未确认作者算成合作者」；
 *   <li><b>物化阈值</b>：作者数 ≤ 阈值的成果才建作者对边。差值是「边爆炸」或「边缺失」的直接指标；
 *   <li><b>水位</b>：{@code projected_version} 与 PG {@code published_revision} 的差。
 * </ol>
 *
 * <p>范围与边界必须显式：标识扫描有上限（{@code scanLimit}），被截断时报告里写
 * {@code scanTruncated=true} 并给 INFO 差异——「只比了前 5000 条」不能被读成「全库一致」。
 * 孤立节点扫描默认开启，但共享实例上必须关闭（别的环境的成果会被误判并退场）。
 *
 * <p>{@link #repair(GraphReconciliationReport)} 只重新同步或退场具体成果，不直接改水位：
 * 水位是「每条事件都已应用」的结论，不能由一次读操作的对比结果伪造。
 */
public class GraphProjectionReconciler {

  private static final Logger log = LoggerFactory.getLogger(GraphProjectionReconciler.class);

  /** 对账范围参数。{@code dataOrigin} 为 null/ALL 表示不限数据性质。 */
  public record ReconcileOptions(
      String dataOrigin, int scanLimit, int sampleSize, boolean scanOrphans) {

    public ReconcileOptions {
      dataOrigin = normalizeOrigin(dataOrigin);
      scanLimit = scanLimit <= 0 ? 5_000 : scanLimit;
      sampleSize = sampleSize <= 0 ? 20 : Math.min(sampleSize, 1_000);
    }

    public static ReconcileOptions defaults() {
      return new ReconcileOptions(null, 5_000, 20, true);
    }

    /** 归一数据性质：只接受 REAL / SIMULATED / MIXED，ALL 或空表示不限。 */
    static String normalizeOrigin(String raw) {
      if (raw == null || raw.isBlank()) {
        return null;
      }
      String upper = raw.trim().toUpperCase(Locale.ROOT);
      if ("ALL".equals(upper)) {
        return null;
      }
      if (!Set.of("REAL", "SIMULATED", "MIXED").contains(upper)) {
        throw new IllegalArgumentException(
            "对账数据性质必须是 REAL / SIMULATED / MIXED / ALL 之一，实际为 " + raw);
      }
      return upper;
    }
  }

  private final GraphProjectionSource source;
  private final GraphProjectionInspector inspector;
  private final GraphProjectionSynchronizer synchronizer;
  private final GraphProjectionStore store;
  private final int materializedAuthorThreshold;
  private final String consumerId;
  private final Clock clock;

  public GraphProjectionReconciler(
      GraphProjectionSource source,
      GraphProjectionInspector inspector,
      GraphProjectionSynchronizer synchronizer,
      GraphProjectionStore store,
      int materializedAuthorThreshold,
      String consumerId) {
    this(
        source,
        inspector,
        synchronizer,
        store,
        materializedAuthorThreshold,
        consumerId,
        Clock.systemUTC());
  }

  public GraphProjectionReconciler(
      GraphProjectionSource source,
      GraphProjectionInspector inspector,
      GraphProjectionSynchronizer synchronizer,
      GraphProjectionStore store,
      int materializedAuthorThreshold,
      String consumerId,
      Clock clock) {
    this.source = source;
    this.inspector = inspector;
    this.synchronizer = synchronizer;
    this.store = store;
    this.materializedAuthorThreshold =
        materializedAuthorThreshold <= 0 ? 20 : materializedAuthorThreshold;
    this.consumerId = consumerId == null || consumerId.isBlank() ? "query" : consumerId;
    this.clock = clock == null ? Clock.systemUTC() : clock;
  }

  /** 对账。 */
  public GraphReconciliationReport reconcile(String scope, ReconcileOptions options) {
    String effectiveScope = scope == null || scope.isBlank() ? "query-public" : scope.trim();
    ReconcileOptions effective = options == null ? ReconcileOptions.defaults() : options;
    String origin = effective.dataOrigin();
    String originLabel = origin == null ? "ALL" : origin;
    Instant now = clock.instant();

    Optional<GraphProjectionSource.Stage> stageOpt = source.readStage(consumerId);
    if (stageOpt.isEmpty()) {
      return reportWithStageMissing(effectiveScope, originLabel, now);
    }
    GraphProjectionSource.Stage stage = stageOpt.get();
    GraphProjectionMeta meta =
        store.readMeta(effectiveScope).orElse(GraphProjectionMeta.empty(effectiveScope));
    List<GraphReconciliationFinding> findings = new ArrayList<>();

    long pgWorks = source.countEligibleWorks(stage.generation(), origin);
    long pgScholars = source.countContributors(stage.generation(), origin);
    long pgAuthored = source.countQualifiedContributions(stage.generation(), origin);
    long pgPairs =
        source.countMaterializedPairs(stage.generation(), materializedAuthorThreshold, origin);
    GraphProjectionInspector.GraphCounts graph = inspector.counts(origin);

    compare(
        findings,
        "WORK_COUNT_MISMATCH",
        "成果数",
        pgWorks,
        graph.publicWorks(),
        "PG 合格公开成果数与图内公开 Work 节点数不同：多出的是该退场没退场，缺少的是该进图没进图");
    compare(
        findings,
        "SCHOLAR_COUNT_MISMATCH",
        "学者数",
        pgScholars,
        graph.scholars(),
        "合格参与关系涉及的学者数不同：多出通常是把已下线成果的作者仍算作可见合作者");
    compare(
        findings,
        "AUTHORED_COUNT_MISMATCH",
        "参与关系数",
        pgAuthored,
        graph.authoredRelations(),
        "关系合格的参与边数量不同：差一条就意味着有未确认作者被算成确定合作者，或反之");
    compare(
        findings,
        "EDGE_COUNT_MISMATCH",
        "物化合作边数",
        pgPairs,
        graph.materializedEdges(),
        "按物化阈值应存在的作者对数与实际合作边数不同（阈值规则或退场清理不一致）");

    if (!meta.generation().equals(stage.generation())) {
      findings.add(
          new GraphReconciliationFinding(
              Severity.ERROR,
              "GENERATION_MISMATCH",
              effectiveScope,
              "图水位记录的 generation 与 PG 活动代不同；图里可能是旧代成果，即便计数偶然相同也不能用",
              null,
              null));
    }
    if (meta.projectedVersion() < stage.publishedRevision()) {
      findings.add(
          new GraphReconciliationFinding(
              Severity.WARN,
              "WATERMARK_BEHIND",
              effectiveScope,
              "图投影水位落后于 PG 已发布修订；读路径必须降级到 PG（ADR-005 §7）",
              stage.publishedRevision(),
              meta.projectedVersion()));
    }

    // ---- 标识集合扫描（有界） ----
    List<String> pgIds = new ArrayList<>();
    String cursor = null;
    boolean truncated = false;
    while (pgIds.size() < effective.scanLimit()) {
      int want = Math.min(1_000, effective.scanLimit() - pgIds.size());
      List<String> page = source.pageEligibleWorkIds(stage.generation(), cursor, want, origin);
      if (page.isEmpty()) {
        break;
      }
      pgIds.addAll(page);
      cursor = page.get(page.size() - 1);
      if (page.size() < want) {
        break;
      }
    }
    // 多探一行判断是否被截断：恰好扫满上限时无法区分「刚好这么多」与「还有更多」。
    if (pgIds.size() >= effective.scanLimit()) {
      List<String> probe = source.pageEligibleWorkIds(stage.generation(), cursor, 1, origin);
      truncated = !probe.isEmpty();
      if (truncated) {
        findings.add(
            new GraphReconciliationFinding(
                Severity.INFO,
                "SCAN_TRUNCATED",
                effectiveScope,
                "本次只扫描了前 " + pgIds.size() + " 条合格成果标识，集合级差异与孤立扫描仅在这个范围内成立",
                (long) effective.scanLimit(),
                (long) pgIds.size()));
      }
    }

    Set<String> pgIdSet = new LinkedHashSet<>(pgIds);
    Set<String> inGraph = inspector.publicWorkIdsAmong(pgIdSet);
    List<String> missing = new ArrayList<>();
    for (String id : pgIds) {
      if (!inGraph.contains(id)) {
        missing.add(id);
      }
    }
    if (!missing.isEmpty()) {
      findings.add(
          new GraphReconciliationFinding(
              Severity.ERROR,
              "WORK_MISSING_IN_GRAPH",
              missing.get(0),
              "PG 合格成果在图中缺失（首个示例见 subject，共 " + missing.size() + " 条）",
              (long) missing.size(),
              0L));
    }

    List<String> orphaned = new ArrayList<>();
    if (effective.scanOrphans() && origin != null) {
      // 数据性质口径下的孤立判断需要「全量合格标识集合」作为参照，否则另一性质的成果
      // 会被误判为孤立并在修复阶段被退场——那是删数据，不是对账。这里明确跳过并记录。
      findings.add(
          new GraphReconciliationFinding(
              Severity.INFO,
              "ORPHAN_SCAN_SKIPPED",
              effectiveScope,
              "按数据性质（"
                  + originLabel
                  + "）对账时跳过孤立扫描：需要全量标识集合才能区分"
                  + "「已下线」与「不在本次口径内」；请用 ALL 口径执行孤立扫描",
              null,
              null));
    } else if (effective.scanOrphans() && !truncated) {
      List<String> graphIds = inspector.publicWorkIds(effective.scanLimit() + 1);
      for (String id : graphIds) {
        if (!pgIdSet.contains(id)) {
          orphaned.add(id);
        }
      }
      if (!orphaned.isEmpty()) {
        findings.add(
            new GraphReconciliationFinding(
                Severity.ERROR,
                "WORK_ORPHANED_IN_GRAPH",
                orphaned.get(0),
                "图内公开成果在 PG 合格集合中已不存在（首个示例见 subject，共 " + orphaned.size() + " 条）：应退场，否则会留下幽灵合作边",
                (long) orphaned.size(),
                0L));
      }
    } else if (effective.scanOrphans() && truncated) {
      findings.add(
          new GraphReconciliationFinding(
              Severity.INFO,
              "ORPHAN_SCAN_SKIPPED",
              effectiveScope,
              "扫描被截断时无法判断哪些图内成果在 PG 中已不存在，本次跳过孤立扫描",
              null,
              null));
    }

    // ---- 抽样证据核对 ----
    List<String> sample = sampleOf(pgIds, effective.sampleSize());
    Set<String> mismatched = new HashSet<>();
    if (!sample.isEmpty()) {
      Map<String, List<String>> expectedByWork = expectedPairs(stage.generation(), sample);
      Map<String, Set<String>> graphPairs = inspector.materializedPairsOf(sample);
      for (String workId : sample) {
        Set<String> expectedPairKeys =
            new TreeSet<>(expectedByWork.getOrDefault(workId, List.of()));
        Set<String> actualPairKeys = graphPairs.getOrDefault(workId, Set.of());
        if (!expectedPairKeys.equals(actualPairKeys)) {
          mismatched.add(workId);
          findings.add(
              new GraphReconciliationFinding(
                  Severity.ERROR,
                  "EDGE_SET_MISMATCH",
                  workId,
                  "抽样成果的作者对集合与 PG 不一致（expected="
                      + expectedPairKeys.size()
                      + ", actual="
                      + actualPairKeys.size()
                      + "）",
                  (long) expectedPairKeys.size(),
                  (long) actualPairKeys.size()));
        }
      }

      Map<String, String> pgTitles = new HashMap<>();
      for (GraphProjectedWork work : source.loadWorks(stage.generation(), sample)) {
        pgTitles.put(work.workId(), work.title());
      }
      Map<String, String> graphTitles = inspector.workTitles(sample);
      Map<String, Set<String>> evidenceTitles = inspector.evidenceTitlesOf(sample);
      for (String workId : sample) {
        String expectedTitle = pgTitles.get(workId);
        if (expectedTitle == null) {
          continue;
        }
        String actualTitle = graphTitles.get(workId);
        if (!expectedTitle.equals(actualTitle)) {
          mismatched.add(workId);
          findings.add(
              new GraphReconciliationFinding(
                  Severity.ERROR,
                  "WORK_TITLE_MISMATCH",
                  workId,
                  "图内成果标题与 PG 不同（" + actualTitle + " ≠ " + expectedTitle + "）",
                  null,
                  null));
        }
        Set<String> titles = evidenceTitles.getOrDefault(workId, Set.of());
        if (!titles.isEmpty() && !titles.equals(Set.of(expectedTitle))) {
          mismatched.add(workId);
          findings.add(
              new GraphReconciliationFinding(
                  Severity.ERROR,
                  "EVIDENCE_TITLE_STALE",
                  workId,
                  "物化边上的 work_title 证据与成果当前标题不一致：" + titles,
                  null,
                  null));
        }
      }
    }

    GraphReconciliationReport report =
        new GraphReconciliationReport(
            effectiveScope,
            stage.generation(),
            originLabel,
            pgWorks,
            graph.publicWorks(),
            pgScholars,
            graph.scholars(),
            pgAuthored,
            graph.authoredRelations(),
            pgPairs,
            graph.materializedEdges(),
            stage.publishedRevision(),
            meta.projectedVersion(),
            pgIds.size(),
            sample.size(),
            truncated,
            missing,
            orphaned,
            findings,
            now);
    log.info("图投影对账完成 {}", report.summary());
    return report;
  }

  /**
   * 定向修复：缺口成果重新同步、孤立成果退场。
   *
   * <p>孤立成果用 {@link Long#MAX_VALUE} 作为退场版本：它在 PG 中已不存在，没有可信版本可用，
   * 而版本守卫不允许退场请求被旧值挡住——留着它的代价是幽灵合作边。
   */
  public GraphRepairResult repair(GraphReconciliationReport report) {
    if (report == null) {
      throw new IllegalArgumentException("修复必须基于一份对账报告");
    }
    List<String> notes = new ArrayList<>();
    int repaired = 0;
    int retired = 0;
    int failed = 0;

    Set<String> toSync = new LinkedHashSet<>(report.missingWorks());
    for (GraphReconciliationFinding finding : report.findings()) {
      if ("EDGE_SET_MISMATCH".equals(finding.code())
          || "WORK_TITLE_MISMATCH".equals(finding.code())
          || "EVIDENCE_TITLE_STALE".equals(finding.code())) {
        if (!finding.subject().isBlank()) {
          toSync.add(finding.subject());
        }
      }
    }
    for (String workId : toSync) {
      Optional<GraphSyncOutcome> outcome = synchronizer.syncWork(report.generation(), workId);
      if (outcome.isEmpty()) {
        notes.add(workId + " 已不在 PG 投影中，未按缺口修复（交由孤立扫描处理）");
        continue;
      }
      if (outcome.get().failed()) {
        failed++;
        notes.add(workId + " 重新同步失败：" + outcome.get().describe());
      } else {
        repaired++;
      }
    }
    for (String workId : report.orphanedWorks()) {
      try {
        boolean applied = store.retireWork(workId, Long.MAX_VALUE);
        if (applied) {
          retired++;
        } else {
          notes.add(workId + " 退场未生效（版本守卫拒绝）");
        }
      } catch (RuntimeException ex) {
        failed++;
        notes.add(workId + " 退场失败：" + ex.getClass().getSimpleName());
      }
    }
    if (report.watermarkBehind()) {
      notes.add(
          "水位落后 "
              + (report.pgPublishedRevision() - report.graphProjectedVersion())
              + " 个修订：需执行回填续跑或等待增量追赶，修复不会直接改水位");
    }
    GraphRepairResult result = new GraphRepairResult(repaired, retired, failed, notes);
    log.info("图投影定向修复完成 {}", result.summary());
    return result;
  }

  /**
   * 按合格参与关系在 Java 侧重算「按阈值应物化」的作者对。
   *
   * <p>阈值规则只在这里与图写适配器里各出现一次，且用的是同一个语义（作者数在 2..阈值 之间）。
   * 把它写进 SQL 会变成第三份实现。
   */
  private Map<String, List<String>> expectedPairs(String generation, List<String> workIds) {
    Map<String, List<ContributionView>> byWork = new HashMap<>();
    for (ContributionView contribution : source.loadQualifiedContributions(generation, workIds)) {
      byWork
          .computeIfAbsent(contribution.achievementId(), key -> new ArrayList<>())
          .add(contribution);
    }
    Map<String, List<String>> pairs = new HashMap<>();
    for (Map.Entry<String, List<ContributionView>> entry : byWork.entrySet()) {
      List<String> scholars = new ArrayList<>();
      for (ContributionView contribution : entry.getValue()) {
        if (!scholars.contains(contribution.scholarId())) {
          scholars.add(contribution.scholarId());
        }
      }
      if (scholars.size() < 2 || scholars.size() > materializedAuthorThreshold) {
        // 与 Neo4jGraphProjectionStore 一致：只有 2..阈值 位作者的成果物化作者对边。
        pairs.put(entry.getKey(), List.of());
        continue;
      }
      scholars.sort(String::compareTo);
      List<String> keys = new ArrayList<>();
      for (int i = 0; i < scholars.size(); i++) {
        for (int j = i + 1; j < scholars.size(); j++) {
          keys.add(scholars.get(i) + "\u0001" + scholars.get(j));
        }
      }
      pairs.put(entry.getKey(), keys);
    }
    return pairs;
  }

  /** 均匀抽取样本，避免「只检查了前 N 条」而前 N 条恰好是同一批插入的数据。 */
  private static List<String> sampleOf(List<String> ids, int sampleSize) {
    if (ids.isEmpty() || sampleSize <= 0) {
      return List.of();
    }
    if (ids.size() <= sampleSize) {
      return List.copyOf(ids);
    }
    List<String> sample = new ArrayList<>(sampleSize);
    int step = Math.max(ids.size() / sampleSize, 1);
    for (int i = 0; i < ids.size() && sample.size() < sampleSize; i += step) {
      sample.add(ids.get(i));
    }
    return sample;
  }

  private static void compare(
      List<GraphReconciliationFinding> findings,
      String code,
      String label,
      long expected,
      long actual,
      String message) {
    if (expected != actual) {
      findings.add(
          new GraphReconciliationFinding(Severity.ERROR, code, label, message, expected, actual));
    }
  }

  private GraphReconciliationReport reportWithStageMissing(
      String scope, String originLabel, Instant now) {
    List<GraphReconciliationFinding> findings =
        List.of(
            new GraphReconciliationFinding(
                Severity.ERROR,
                "PG_STAGE_MISSING",
                scope,
                "qry_consumer_state 缺少本服务接收者水位，无法确定对账基准；图不可用于任何判断",
                null,
                null));
    return new GraphReconciliationReport(
        scope,
        "-",
        originLabel,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        false,
        List.of(),
        List.of(),
        findings,
        now);
  }
}
