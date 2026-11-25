package com.cognitanexus.query.graph.projection.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognitanexus.query.graph.domain.GraphProjectionMeta;
import com.cognitanexus.query.graph.projection.FakeGraphProjectionEnv;
import com.cognitanexus.query.graph.projection.FakeGraphProjectionEnv.FakeInspector;
import com.cognitanexus.query.graph.projection.FakeGraphProjectionEnv.FakeSource;
import com.cognitanexus.query.graph.projection.FakeGraphProjectionEnv.FakeStore;
import com.cognitanexus.query.graph.projection.application.GraphProjectionReconciler.ReconcileOptions;
import com.cognitanexus.query.graph.projection.domain.GraphReconciliationFinding;
import com.cognitanexus.query.graph.projection.domain.GraphReconciliationReport;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 对账与定向修复。
 *
 * <p>验证重点：**每一种漂移都能被单独检出并指向正确的对象**。只报「不一致」的对账工具在真实故障里
 * 没有用：运维需要知道是成果少了、边多了、标题过期，还是水位落后，因为这四类对应完全不同的处理。
 *
 * <p>同时锁住「范围说明」：扫描被截断时必须显式写出，否则「前 5000 条一致」会被读成「全库一致」。
 */
class GraphProjectionReconcilerTest {

  private static final String GEN = "gen0001";
  private static final String SCOPE = "query-public";
  private static final int THRESHOLD = 20;

  private FakeStore store;
  private FakeSource source;
  private FakeInspector inspector;
  private GraphProjectionSynchronizer synchronizer;
  private GraphProjectionReconciler reconciler;

  @BeforeEach
  void setUp() {
    store = new FakeStore(SCOPE, 10L, GEN);
    source = new FakeSource(GEN, 10L);
    inspector = new FakeInspector(store, THRESHOLD);
    synchronizer =
        new GraphProjectionSynchronizer(
            source, store, GraphProjectionRetryPolicy.noRetry(), millis -> {});
    reconciler =
        new GraphProjectionReconciler(source, inspector, synchronizer, store, THRESHOLD, "query");
  }

  /** 建一份「PG 与图完全一致」的基线：三篇成果，两两作者不同。 */
  private void seedConsistentProjection() {
    var w1 = FakeGraphProjectionEnv.work("w-1", 1L, "s-a", "s-b");
    var w2 = FakeGraphProjectionEnv.work("w-2", 2L, "s-b", "s-c");
    var w3 = FakeGraphProjectionEnv.work("w-3", 3L, "s-c", "s-d", "s-e");
    for (var work : List.of(w1, w2, w3)) {
      source.add(work);
      store.works.put(
          work.workId(),
          new FakeGraphProjectionEnv.StoredWork(work.toWorkNode(), work.authorships()));
    }
  }

  @Test
  @DisplayName("完全一致：没有差异、水位不落后、报告自洽")
  void consistentProjectionReportsNoDrift() {
    seedConsistentProjection();

    GraphReconciliationReport report = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());

    assertThat(report.findings()).isEmpty();
    assertThat(report.hasDrift()).isFalse();
    assertThat(report.watermarkBehind()).isFalse();
    assertThat(report.consistent()).isTrue();
    assertThat(report.pgEligibleWorks()).isEqualTo(3);
    assertThat(report.graphPublicWorks()).isEqualTo(3);
    assertThat(report.pgMaterializedPairs()).isEqualTo(1 + 1 + 3);
    assertThat(report.graphMaterializedPairs()).isEqualTo(1 + 1 + 3);
    assertThat(report.scannedWorks()).isEqualTo(3);
  }

  @Test
  @DisplayName("图里缺成果：计数与集合级差异都被检出，修复后对账回到一致")
  void missingWorkIsDetectedAndRepaired() {
    seedConsistentProjection();
    inspector.hidden.add("w-2");

    GraphReconciliationReport report = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());

    assertThat(report.hasDrift()).isTrue();
    assertThat(report.consistent()).isFalse();
    assertThat(codes(report)).contains("WORK_COUNT_MISMATCH", "WORK_MISSING_IN_GRAPH");
    assertThat(report.missingWorks()).containsExactly("w-2");

    GraphRepairResult repair = reconciler.repair(report);
    assertThat(repair.complete()).isTrue();
    assertThat(repair.repairedWorks()).isEqualTo(1);

    inspector.hidden.clear();
    GraphReconciliationReport after = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());
    assertThat(after.consistent()).isTrue();
  }

  @Test
  @DisplayName("图里多出成果（孤立节点）：被检出，修复时退场而不是重新写入")
  void orphanedWorkIsDetectedAndRetired() {
    seedConsistentProjection();
    var ghost = FakeGraphProjectionEnv.work("w-ghost", 5L, "s-x", "s-y");
    // 孤立节点必须真的「在图里」：只放进图状态，不能同时用 extra 再叠加一次。
    store.works.put(
        "w-ghost", new FakeGraphProjectionEnv.StoredWork(ghost.toWorkNode(), ghost.authorships()));

    GraphReconciliationReport report = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());

    assertThat(report.orphanedWorks()).contains("w-ghost");
    assertThat(codes(report)).contains("WORK_ORPHANED_IN_GRAPH");

    GraphRepairResult repair = reconciler.repair(report);

    assertThat(repair.retiredWorks()).isEqualTo(1);
    assertThat(store.works.get("w-ghost").work().isPublic()).isFalse();
    assertThat(store.works.get("w-ghost").authorships()).isEmpty();
  }

  @Test
  @DisplayName("边集合漂移：抽样核对指出具体成果，修复后重新同步该成果")
  void edgeDriftIsDetectedAndRepaired() {
    seedConsistentProjection();
    // 图里 w-1 的边被说成 s-a/s-b 之外还多了一条（模拟历史残留）
    inspector.pairOverrides.put("w-1", Set.of("s-a\u0001s-b", "s-a\u0001s-z"));

    GraphReconciliationReport report = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());

    assertThat(codes(report)).contains("EDGE_SET_MISMATCH");
    GraphReconciliationFinding finding =
        report.findings().stream()
            .filter(item -> "EDGE_SET_MISMATCH".equals(item.code()))
            .findFirst()
            .orElseThrow();
    assertThat(finding.subject()).isEqualTo("w-1");
    assertThat(finding.expected()).isEqualTo(1L);
    assertThat(finding.actual()).isEqualTo(2L);

    GraphRepairResult repair = reconciler.repair(report);
    assertThat(repair.repairedWorks()).isEqualTo(1);
    inspector.pairOverrides.clear();
    assertThat(reconciler.reconcile(SCOPE, ReconcileOptions.defaults()).consistent()).isTrue();
  }

  @Test
  @DisplayName("标题与边证据过期：两类差异分别报出，不混成一条")
  void staleTitleAndEvidenceAreReportedSeparately() {
    seedConsistentProjection();
    inspector.titleOverrides.put("w-1", "旧标题");

    GraphReconciliationReport report = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());

    assertThat(codes(report)).contains("WORK_TITLE_MISMATCH");
  }

  @Test
  @DisplayName("水位落后：WARN 而不是 ERROR（数据可能自洽，但图不能用于公开查询）")
  void watermarkBehindIsWarning() {
    seedConsistentProjection();
    store.meta = new GraphProjectionMeta(SCOPE, 8L, 8L, GEN, null);
    source.stage = new GraphProjectionSource.Stage(GEN, 10L);

    GraphReconciliationReport report = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());

    assertThat(report.watermarkBehind()).isTrue();
    assertThat(report.consistent()).isFalse();
    assertThat(report.hasDrift()).isFalse();
    assertThat(report.countBySeverity(GraphReconciliationFinding.Severity.WARN)).isEqualTo(1);
    assertThat(codes(report)).containsExactly("WATERMARK_BEHIND");
  }

  @Test
  @DisplayName("generation 不一致：ERROR，即使计数碰巧相同也不能用")
  void generationMismatchIsError() {
    seedConsistentProjection();
    store.meta = new GraphProjectionMeta(SCOPE, 10L, 10L, "gen0000", null);

    GraphReconciliationReport report = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());

    assertThat(codes(report)).contains("GENERATION_MISMATCH");
    assertThat(report.hasDrift()).isTrue();
  }

  @Test
  @DisplayName("扫描截断：显式标记 truncated 并给出 INFO，不能读成「全库一致」")
  void truncatedScanIsExplicit() {
    for (int i = 1; i <= 5; i++) {
      var work = FakeGraphProjectionEnv.work(String.format("w-%02d", i), i, "s-a", "s-b");
      source.add(work);
      store.works.put(
          work.workId(),
          new FakeGraphProjectionEnv.StoredWork(work.toWorkNode(), work.authorships()));
    }

    GraphReconciliationReport report =
        reconciler.reconcile(SCOPE, new ReconcileOptions(null, 2, 2, true));

    assertThat(report.scanTruncated()).isTrue();
    assertThat(report.scannedWorks()).isEqualTo(2);
    assertThat(codes(report)).contains("SCAN_TRUNCATED", "ORPHAN_SCAN_SKIPPED");
    // 截断时跳过孤立扫描，避免把「没扫到的成果」误判成孤立并退场。
    assertThat(report.orphanedWorks()).isEmpty();
  }

  @Test
  @DisplayName("数据性质口径：REAL 对账只看真实成果，模拟成果的差异不影响真实口径")
  void dataOriginScopeIsRespected() {
    var real = FakeGraphProjectionEnv.work("w-r", 1L, "s-a", "s-b");
    var simulated =
        FakeGraphProjectionEnv.work(
            "w-s", 2L, true, "SIMULATED", List.of("t-1"), List.of("org-1"), "s-a", "s-c");
    for (var work : List.of(real, simulated)) {
      source.add(work);
      store.works.put(
          work.workId(),
          new FakeGraphProjectionEnv.StoredWork(work.toWorkNode(), work.authorships()));
    }
    // 模拟成果在图里缺一条边：REAL 口径不应因此报告差异
    inspector.pairOverrides.put("w-s", Set.of());

    GraphReconciliationReport realReport =
        reconciler.reconcile(SCOPE, new ReconcileOptions("REAL", 100, 10, true));
    GraphReconciliationReport allReport =
        reconciler.reconcile(SCOPE, new ReconcileOptions(null, 100, 10, true));

    assertThat(realReport.pgEligibleWorks()).isEqualTo(1);
    assertThat(realReport.consistent()).isTrue();
    assertThat(allReport.pgEligibleWorks()).isEqualTo(2);
    assertThat(codes(allReport)).contains("EDGE_SET_MISMATCH");
  }

  @Test
  @DisplayName("PG 水位缺失：报告直接给出 ERROR，而不是「零差异」")
  void missingPgStageIsError() {
    source.stage = null;

    GraphReconciliationReport report = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());

    assertThat(codes(report)).containsExactly("PG_STAGE_MISSING");
    assertThat(report.hasDrift()).isTrue();
    assertThat(report.consistent()).isFalse();
  }

  private static List<String> codes(GraphReconciliationReport report) {
    return report.findings().stream().map(GraphReconciliationFinding::code).toList();
  }
}
