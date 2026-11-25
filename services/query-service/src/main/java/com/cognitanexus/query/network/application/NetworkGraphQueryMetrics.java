package com.cognitanexus.query.network.application;

import com.cognitanexus.query.network.domain.GraphParityComparisonResult;
import com.cognitanexus.query.network.domain.GraphProjectionStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 图查询路由与新旧对照的可观测指标。
 *
 * <p>为什么需要显式计数而不是只打日志：ADR-005 §7 的降级承诺是「图库故障/投影落后时公开查询
 * 仍可用」，但**降级本身也是一种故障**。如果只靠日志，一个长期 {@code degradedToPostgres=true}
 * 的实例看起来完全健康：所有请求都成功，只是全走 PG。把这些计数暴露出来，才能对「图模式已启用
 * 但实际一直在回退」持续告警，避免「重启好了」式的收尾（AGENTS.md §5）。
 *
 * <p>指标语义刻意分开：
 *
 * <ul>
 *   <li>{@code staleProjectionFallbackCount}：投影落后/无水位/换代不一致导致的回退。这类回退说明
 *       <b>回填没跟上</b>，需要运维介入；
 *   <li>{@code graphUnavailableFallbackCount}：水位读取失败（图库不可达/认证失败）导致的回退；
 *   <li>{@code graphErrorFallbackCount}：图查询执行期异常导致的回退（例如损坏数据）；
 *   <li>{@code parityMismatchedCount} 与 {@code parityMatchedCount}/{@code paritySkippedCount}：
 *       对照结果。**跳过不计入一致**——否则「图库一直不可用」会让对照指标永远绿。
 * </ul>
 *
 * <p>线程安全：路由会被并发请求调用，计数器一律 {@link AtomicLong}；只保存「最近一次对照结果」
 * 供运维快速查看，不保留历史（历史属于日志与指标系统）。
 */
public class NetworkGraphQueryMetrics {

  private final AtomicLong postgresOnlyQueries = new AtomicLong();
  private final AtomicLong graphServedQueries = new AtomicLong();
  private final AtomicLong staleProjectionFallbackCount = new AtomicLong();
  private final AtomicLong graphUnavailableFallbackCount = new AtomicLong();
  private final AtomicLong graphErrorFallbackCount = new AtomicLong();
  private final AtomicLong strictRejections = new AtomicLong();
  private final AtomicLong parityComparedCount = new AtomicLong();
  private final AtomicLong parityMatchedCount = new AtomicLong();
  private final AtomicLong parityMismatchedCount = new AtomicLong();
  private final AtomicLong paritySkippedCount = new AtomicLong();
  private final AtomicLong parityDroppedCount = new AtomicLong();
  private final Map<String, AtomicLong> fallbackByReason = new ConcurrentHashMap<>();
  private final AtomicReference<GraphParityComparisonResult> lastParityComparison =
      new AtomicReference<>();

  public void recordPostgresOnly() {
    postgresOnlyQueries.incrementAndGet();
  }

  public void recordGraphServed() {
    graphServedQueries.incrementAndGet();
  }

  /**
   * 记录一次因「图不可用/水位不足」发生的回退。
   *
   * <p>按原因码分别累计，同时把「投影落后」类原因汇总到 {@code staleProjectionFallbackCount}：
   * 平台需要区分「回填落后」与「图库挂了」这两种完全不同的处置方式。
   */
  public void recordFallback(GraphProjectionStatus status) {
    String reason = status == null ? "UNKNOWN" : status.reasonCode();
    switch (reason) {
      case GraphProjectionStatus.PROJECTION_STALE,
          GraphProjectionStatus.PROJECTION_META_MISSING,
          GraphProjectionStatus.GENERATION_MISMATCH ->
          staleProjectionFallbackCount.incrementAndGet();
      case GraphProjectionStatus.SCHEMA_NOT_READY,
          GraphProjectionStatus.PROJECTION_META_UNREADABLE ->
          graphUnavailableFallbackCount.incrementAndGet();
      default -> {
        // 未知原因只进 fallbackByReason，避免把「不知道」统计成已知故障类型。
      }
    }
    fallbackByReason.computeIfAbsent(reason, key -> new AtomicLong()).incrementAndGet();
  }

  public void recordGraphErrorFallback() {
    graphErrorFallbackCount.incrementAndGet();
  }

  public void recordStrictRejection(GraphProjectionStatus status) {
    strictRejections.incrementAndGet();
    String reason = status == null ? "UNKNOWN" : status.reasonCode();
    fallbackByReason.computeIfAbsent(reason, key -> new AtomicLong()).incrementAndGet();
  }

  /** 记录一次对照执行（含一致与不一致两种结果）。 */
  public void recordParityComparison(GraphParityComparisonResult result) {
    if (result == null) {
      return;
    }
    lastParityComparison.set(result);
    if (!result.compared()) {
      paritySkippedCount.incrementAndGet();
      return;
    }
    parityComparedCount.incrementAndGet();
    if (result.matched()) {
      parityMatchedCount.incrementAndGet();
    } else {
      parityMismatchedCount.incrementAndGet();
    }
  }

  /** 对照任务因执行器队列已满被丢弃（异步模式）。丢弃必须可见，否则对照覆盖率会悄悄下降。 */
  public void recordParityDropped() {
    parityDroppedCount.incrementAndGet();
  }

  public GraphParityComparisonResult lastParityComparison() {
    return lastParityComparison.get();
  }

  /** 供健康检查/运维端点展示的快照。键名稳定，便于告警配置。 */
  public Map<String, Object> snapshot() {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("postgresOnlyQueries", postgresOnlyQueries.get());
    view.put("graphServedQueries", graphServedQueries.get());
    view.put("staleProjectionFallbackCount", staleProjectionFallbackCount.get());
    view.put("graphUnavailableFallbackCount", graphUnavailableFallbackCount.get());
    view.put("graphErrorFallbackCount", graphErrorFallbackCount.get());
    view.put("strictRejections", strictRejections.get());
    view.put("parityComparedCount", parityComparedCount.get());
    view.put("parityMatchedCount", parityMatchedCount.get());
    view.put("parityMismatchedCount", parityMismatchedCount.get());
    view.put("paritySkippedCount", paritySkippedCount.get());
    view.put("parityDroppedCount", parityDroppedCount.get());
    Map<String, Long> reasons = new LinkedHashMap<>();
    fallbackByReason.forEach((reason, count) -> reasons.put(reason, count.get()));
    view.put("fallbackByReason", reasons);
    GraphParityComparisonResult last = lastParityComparison.get();
    view.put("lastParityComparison", last == null ? null : last.describe());
    return view;
  }
}
