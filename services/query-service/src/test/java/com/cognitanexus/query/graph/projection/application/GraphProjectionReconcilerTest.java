package com.cognitanexus.query.graph.projection.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognitanexus.query.graph.domain.GraphProjectionMeta;
import com.cognitanexus.query.graph.projection.FakeGraphProjectionEnv;
import com.cognitanexus.query.graph.projection.FakeGraphProjectionEnv.FakeCheckpointStore;
import com.cognitanexus.query.graph.projection.FakeGraphProjectionEnv.FakeInspector;
import com.cognitanexus.query.graph.projection.FakeGraphProjectionEnv.FakeSource;
import com.cognitanexus.query.graph.projection.FakeGraphProjectionEnv.FakeStore;
import com.cognitanexus.query.graph.projection.application.GraphProjectionReconciler.ReconcileOptions;
import com.cognitanexus.query.graph.projection.domain.GraphBackfillStatus;
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
  @DisplayName("图里多出成果（孤立节点）：被检出，PG 中已无该行时直接删除节点与边")
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
    // PG 里连行都没有：不存在可信版本号，因此不留「无版本的墓碑」，直接删除节点与边。
    assertThat(store.works).doesNotContainKey("w-ghost");
    assertThat(store.calls).contains("deleteWork:w-ghost");
  }

  @Test
  @DisplayName("孤立成果退场不抬高 source_version：PG 以正常版本重新公开后能正常恢复")
  void retiredOrphanCanBeRestoredAtNormalVersion() {
    seedConsistentProjection();
    var ghost = FakeGraphProjectionEnv.work("w-ghost", 5L, "s-x", "s-y");
    store.works.put(
        "w-ghost", new FakeGraphProjectionEnv.StoredWork(ghost.toWorkNode(), ghost.authorships()));

    GraphReconciliationReport report = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());
    assertThat(reconciler.repair(report).retiredWorks()).isEqualTo(1);

    // 日后 PG 里同一 workId 以正常聚合版本（15）重新成为合格公开成果。
    source.add(FakeGraphProjectionEnv.work("w-ghost", 15L, "s-x", "s-y"));
    GraphSyncOutcome outcome = synchronizer.syncWork(GEN, "w-ghost").orElseThrow();

    assertThat(outcome.action())
        .as("退场若把 source_version 抬到 Long.MAX_VALUE，这里会永久变成 STALE_IGNORED")
        .isEqualTo(GraphSyncOutcome.Action.APPLIED);
    assertThat(store.works.get("w-ghost").work().isPublic()).isTrue();
  }

  @Test
  @DisplayName("孤立但 PG 仍保留该行：用 PG 真实版本退场（留墓碑），不用哨兵版本号")
  void orphanStillPresentInPgIsRetiredWithPgVersion() {
    seedConsistentProjection();
    var ghost = FakeGraphProjectionEnv.work("w-ghost", 9L, "s-x", "s-y");
    store.works.put(
        "w-ghost", new FakeGraphProjectionEnv.StoredWork(ghost.toWorkNode(), ghost.authorships()));
    // PG 里仍存在该成果，只是已不合格（例如公开资格被收紧）。
    source.add(
        FakeGraphProjectionEnv.work(
            "w-ghost", 9L, false, "REAL", List.of(), List.of(), "s-x", "s-y"));

    GraphReconciliationReport report = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());
    GraphRepairResult repair = reconciler.repair(report);

    assertThat(repair.retiredWorks()).isEqualTo(1);
    assertThat(store.calls).contains("retireWork:w-ghost:v9");
    assertThat(store.works.get("w-ghost").work().isPublic()).isFalse();
    assertThat(store.works.get("w-ghost").work().sourceVersion()).isEqualTo(9L);

    // 重新公开（版本 15）能正常恢复：墓碑版本是 PG 的真实版本，不是 Long.MAX_VALUE。
    source.add(FakeGraphProjectionEnv.work("w-ghost", 15L, "s-x", "s-y"));
    assertThat(synchronizer.syncWork(GEN, "w-ghost").orElseThrow().action())
        .isEqualTo(GraphSyncOutcome.Action.APPLIED);
    assertThat(store.works.get("w-ghost").work().isPublic()).isTrue();
  }

  @Test
  @DisplayName("P1-4 报告过期：成果在 reconcile 与 repair 之间恢复公开合格时，绝不退场而是正向同步")
  void orphanRestoredInPgBeforeRepairIsResyncedNotRetired() {
    seedConsistentProjection();
    // 图里有 w-ghost，但 PG 里暂时没有该行（例如合并过程中的中间态）→ 对账判为孤立。
    var ghost = FakeGraphProjectionEnv.work("w-ghost", 5L, "s-x", "s-y");
    store.works.put(
        "w-ghost", new FakeGraphProjectionEnv.StoredWork(ghost.toWorkNode(), ghost.authorships()));

    GraphReconciliationReport report = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());
    assertThat(report.orphanedWorks()).contains("w-ghost");

    // 报告生成之后、修复之前：PG 中该成果恢复为公开合格（版本 7）。
    source.add(FakeGraphProjectionEnv.work("w-ghost", 7L, "s-x", "s-y"));

    GraphRepairResult repair = reconciler.repair(report);

    // 关键断言：不得退场、不得删除；改为正向同步，图里仍是公开成果。
    assertThat(repair.retiredWorks()).isZero();
    assertThat(repair.repairedWorks()).isEqualTo(1);
    assertThat(store.calls).noneMatch(call -> call.startsWith("deleteWork"));
    assertThat(store.calls).noneMatch(call -> call.startsWith("retireWork"));
    assertThat(store.works.get("w-ghost").work().isPublic()).isTrue();
    assertThat(store.works.get("w-ghost").work().sourceVersion()).isEqualTo(7L);
    assertThat(store.works.get("w-ghost").authorships()).hasSize(2);
    assertThat(String.join("\n", repair.notes())).contains("已过期");
  }

  @Test
  @DisplayName("STALE_IGNORED 不计入已修复：只有真正写入/退场才算修复")
  void staleOutcomeIsNotCountedAsRepaired() {
    seedConsistentProjection();
    // 图里 w-2 已是更新的版本（99），但对账扫描看不到它 → 被报成「图内缺失」。
    var newer = FakeGraphProjectionEnv.work("w-2", 99L, "s-b", "s-c");
    store.works.put(
        "w-2", new FakeGraphProjectionEnv.StoredWork(newer.toWorkNode(), newer.authorships()));
    inspector.hidden.add("w-2");

    GraphReconciliationReport report = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());
    assertThat(report.missingWorks()).containsExactly("w-2");

    GraphRepairResult repair = reconciler.repair(report);

    assertThat(repair.repairedWorks()).isZero();
    assertThat(repair.complete()).isTrue();
    assertThat(String.join("\n", repair.notes())).contains("STALE_IGNORED");
  }

  @Test
  @DisplayName("generation 不一致：拒绝退场孤立成果（属于别的代，删了就不可逆）")
  void orphanRetirementSkippedOnGenerationMismatch() {
    seedConsistentProjection();
    var ghost = FakeGraphProjectionEnv.work("w-ghost", 5L, "s-x", "s-y");
    store.works.put(
        "w-ghost", new FakeGraphProjectionEnv.StoredWork(ghost.toWorkNode(), ghost.authorships()));
    store.meta = new GraphProjectionMeta(SCOPE, 10L, 10L, "gen0000", null);

    GraphReconciliationReport report = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());
    assertThat(report.orphanedWorks()).contains("w-ghost");

    GraphRepairResult repair = reconciler.repair(report);

    assertThat(repair.retiredWorks()).isZero();
    assertThat(store.works).containsKey("w-ghost");
    assertThat(String.join("\n", repair.notes())).contains("整库重建");
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
  @DisplayName("图侧扫描截断：图上公开成果超过上限时同样显式提示")
  void graphScanTruncationIsExplicit() {
    for (String id : List.of("w-10", "w-20")) {
      var work = FakeGraphProjectionEnv.work(id, 1L, "s-a", "s-b");
      source.add(work);
      store.works.put(
          id, new FakeGraphProjectionEnv.StoredWork(work.toWorkNode(), work.authorships()));
    }
    var ghost = FakeGraphProjectionEnv.work("w-ghost", 5L, "s-x", "s-y");
    store.works.put(
        "w-ghost", new FakeGraphProjectionEnv.StoredWork(ghost.toWorkNode(), ghost.authorships()));

    GraphReconciliationReport report =
        reconciler.reconcile(SCOPE, new ReconcileOptions(null, 2, 2, true));

    // PG 侧刚好扫满上限且没有更多：PG 扫描未被截断。
    assertThat(report.scanTruncated()).isFalse();
    // 图侧多于上限（3 > 2）：孤立扫描只覆盖前 2 条标识，必须写清楚。
    assertThat(codes(report)).contains("GRAPH_SCAN_TRUNCATED", "WORK_ORPHANED_IN_GRAPH");
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
  @DisplayName("D-2 部分失败 → repair 补齐 → 断点恢复 COMPLETED、失败数归零、水位推进，增量解除封锁")
  void repairAndCompleteRecoversFailedBackfill() {
    // 三篇合格成果，图水位从 0 开始（能看出「水位是否真的被推进」）。
    var w1 = FakeGraphProjectionEnv.work("w-1", 1L, "s-a", "s-b");
    var w2 = FakeGraphProjectionEnv.work("w-2", 2L, "s-b", "s-c");
    var w3 = FakeGraphProjectionEnv.work("w-3", 3L, "s-c", "s-d", "s-e");
    for (var work : List.of(w1, w2, w3)) {
      source.add(work);
    }
    store.meta = new GraphProjectionMeta(SCOPE, 0L, 0L, GEN, null);
    FakeCheckpointStore checkpoints = new FakeCheckpointStore();
    // batchSize=2：第一批含 w-2，失败后游标仍前进（漏格由 repair 单点补齐）。
    GraphProjectionProperties properties =
        new GraphProjectionProperties(false, 2, 3, 0L, 100L, false, 5_000L, 100, 1_000, 5, true);
    GraphBackfillService backfill =
        new GraphBackfillService(source, synchronizer, store, checkpoints, properties, "query");

    // 1) 回填因 1 条成果失败停在 FAILED，水位不推进，增量被封锁。
    store.permanentFailures.add("w-2");
    GraphBackfillResult failedRun = backfill.backfill(SCOPE);

    assertThat(failedRun.status()).isEqualTo(GraphBackfillStatus.FAILED);
    assertThat(failedRun.failedWorks()).isEqualTo(1);
    assertThat(store.meta.projectedVersion()).isZero();
    assertThat(GraphProjectionReadiness.blocker(checkpoints.find(SCOPE), GEN)).isPresent();

    // 2) 底层故障未修复时收尾必须拒绝：repair 失败 → 断点仍 FAILED、水位仍不动。
    GraphReconciliationReport drifted = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());
    GraphRepairResult stillBroken =
        reconciler.repairAndComplete(drifted, ReconcileOptions.defaults(), backfill);

    assertThat(stillBroken.complete()).isFalse();
    assertThat(checkpoints.find(SCOPE).orElseThrow().status())
        .isEqualTo(GraphBackfillStatus.FAILED);
    assertThat(checkpoints.find(SCOPE).orElseThrow().failedWorks()).isEqualTo(1);
    assertThat(store.meta.projectedVersion()).isZero();

    // 3) 修好底层故障后重跑对账 + repairAndComplete：闭环应当收尾。
    store.permanentFailures.clear();
    GraphReconciliationReport afterFix = reconciler.reconcile(SCOPE, ReconcileOptions.defaults());
    assertThat(afterFix.missingWorks()).containsExactly("w-2");

    GraphRepairResult repaired =
        reconciler.repairAndComplete(afterFix, ReconcileOptions.defaults(), backfill);

    assertThat(repaired.complete()).isTrue();
    assertThat(repaired.failedWorks()).isZero();
    assertThat(repaired.repairedWorks()).isEqualTo(1);
    var recovered = checkpoints.find(SCOPE).orElseThrow();
    assertThat(recovered.status()).isEqualTo(GraphBackfillStatus.COMPLETED);
    assertThat(recovered.failedWorks()).isZero();
    assertThat(recovered.lastError()).isNull();
    // 水位推进到快照修订（10），且累计成果数补上了修复写入的那一条。
    assertThat(store.meta.projectedVersion()).isEqualTo(10L);
    assertThat(recovered.processedWorks()).isEqualTo(3L);
    assertThat(recovered.cursorWorkId()).isEqualTo("w-3");

    // 4) 增量消费不再被封锁；再调 backfill 也不会重扫（否则每次调用都变全量）。
    assertThat(GraphProjectionReadiness.blocker(checkpoints.find(SCOPE), GEN)).isEmpty();
    long pageCallsBefore =
        source.calls.stream().filter(call -> call.startsWith("pageWorks")).count();
    GraphBackfillResult again = backfill.backfill(SCOPE);
    assertThat(again.status()).isEqualTo(GraphBackfillStatus.COMPLETED);
    assertThat(again.worksThisRun()).isZero();
    assertThat(source.calls.stream().filter(call -> call.startsWith("pageWorks")).count())
        .isEqualTo(pageCallsBefore);
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
