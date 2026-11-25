package com.cognitanexus.query.network.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognitanexus.query.graph.domain.GraphProjectionMeta;
import com.cognitanexus.query.network.domain.GraphParityComparisonResult;
import com.cognitanexus.query.network.domain.GraphProjectionStatus;
import com.cognitanexus.query.network.domain.GraphQueryMode;
import com.cognitanexus.query.network.domain.ParityDiffReport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 路由指标的语义。
 *
 * <p>重点是「跳过 ≠ 一致」与「按原因分列」：把跳过计入一致，会让「图库一直不可用」的实例也显示
 * 对照健康；把「回填落后」与「图库挂了」混成一个计数，则会让运维按错误的处置方式去修。
 */
class NetworkGraphQueryMetricsTest {

  private final NetworkGraphQueryMetrics metrics = new NetworkGraphQueryMetrics();

  private static GraphProjectionStatus stale() {
    return GraphProjectionStatus.stale(
        new GraphProjectionMeta("query-public", 1L, 1L, "42", Instant.now()), 10L);
  }

  @Test
  @DisplayName("回退原因分列：落后/无水位/换代计为 stale，schema/不可读计为图库不可用")
  void fallbackReasonsAreGrouped() {
    metrics.recordFallback(stale());
    metrics.recordFallback(GraphProjectionStatus.metaMissing(10L, "42"));
    metrics.recordFallback(
        GraphProjectionStatus.generationMismatch(
            new GraphProjectionMeta("query-public", 10L, 10L, "41", Instant.now()), 10L, "42"));
    metrics.recordFallback(GraphProjectionStatus.schemaNotReady(10L, "42"));
    metrics.recordFallback(GraphProjectionStatus.metaUnreadable(10L, "42", "Timed Out"));

    Map<String, Object> snapshot = metrics.snapshot();
    assertThat(snapshot).containsEntry("staleProjectionFallbackCount", 3L);
    assertThat(snapshot).containsEntry("graphUnavailableFallbackCount", 2L);
    @SuppressWarnings("unchecked")
    Map<String, Long> byReason = (Map<String, Long>) snapshot.get("fallbackByReason");
    assertThat(byReason)
        .containsEntry(GraphProjectionStatus.PROJECTION_STALE, 1L)
        .containsEntry(GraphProjectionStatus.PROJECTION_META_MISSING, 1L)
        .containsEntry(GraphProjectionStatus.GENERATION_MISMATCH, 1L)
        .containsEntry(GraphProjectionStatus.SCHEMA_NOT_READY, 1L)
        .containsEntry(GraphProjectionStatus.PROJECTION_META_UNREADABLE, 1L);
  }

  @Test
  @DisplayName("未知原因只进分列计数，不冒充已知故障类型")
  void unknownReasonIsNotCountedAsKnownFailure() {
    metrics.recordFallback(
        new GraphProjectionStatus(true, true, false, 0L, 10L, null, "42", "SOMETHING_NEW", "x"));

    assertThat(metrics.snapshot())
        .containsEntry("staleProjectionFallbackCount", 0L)
        .containsEntry("graphUnavailableFallbackCount", 0L);
    @SuppressWarnings("unchecked")
    Map<String, Long> byReason = (Map<String, Long>) metrics.snapshot().get("fallbackByReason");
    assertThat(byReason).containsEntry("SOMETHING_NEW", 1L);
  }

  @Test
  @DisplayName("对照：执行且一致 / 执行且不一致 / 跳过 三类分别计数，跳过不算一致")
  void parityCountersSeparateSkipFromMatch() {
    ParityDiffReport matched = new ParityDiffReport("neighborhood", "k", List.of(), 0, 2, 1);
    ParityDiffReport mismatched =
        new ParityDiffReport(
            "neighborhood",
            "k",
            List.of(new ParityDiffReport.FieldDifference("ruleVersion", "V1", "V2")),
            0,
            2,
            1);

    metrics.recordParityComparison(
        GraphParityComparisonResult.compared(
            "neighborhood", "k", GraphQueryMode.SHADOW_COMPARE, matched, 1L, 2L));
    metrics.recordParityComparison(
        GraphParityComparisonResult.compared(
            "neighborhood", "k", GraphQueryMode.SHADOW_COMPARE, mismatched, 1L, 2L));
    metrics.recordParityComparison(
        GraphParityComparisonResult.skipped(
            "neighborhood", "k", GraphQueryMode.SHADOW_COMPARE, "GRAPH_ERROR", 1L));

    assertThat(metrics.snapshot())
        .containsEntry("parityComparedCount", 2L)
        .containsEntry("parityMatchedCount", 1L)
        .containsEntry("parityMismatchedCount", 1L)
        .containsEntry("paritySkippedCount", 1L);
    assertThat(metrics.lastParityComparison().skipReason()).isEqualTo("GRAPH_ERROR");
  }

  @Test
  @DisplayName("降级/严格拒绝/丢弃/图服务计数彼此独立，快照键名稳定")
  void snapshotHasStableKeys() {
    metrics.recordPostgresOnly();
    metrics.recordGraphServed();
    metrics.recordGraphErrorFallback();
    metrics.recordStrictRejection(stale());
    metrics.recordParityDropped();

    Map<String, Object> snapshot = metrics.snapshot();
    assertThat(snapshot)
        .containsEntry("postgresOnlyQueries", 1L)
        .containsEntry("graphServedQueries", 1L)
        .containsEntry("graphErrorFallbackCount", 1L)
        .containsEntry("strictRejections", 1L)
        .containsEntry("parityDroppedCount", 1L)
        .containsEntry("lastParityComparison", null)
        .containsKeys(
            "staleProjectionFallbackCount",
            "graphUnavailableFallbackCount",
            "parityComparedCount",
            "parityMatchedCount",
            "parityMismatchedCount",
            "paritySkippedCount",
            "fallbackByReason");
  }
}
