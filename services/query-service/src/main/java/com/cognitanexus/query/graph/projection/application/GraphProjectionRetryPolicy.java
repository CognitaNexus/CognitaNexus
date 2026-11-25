package com.cognitanexus.query.graph.projection.application;

import java.time.Duration;

/**
 * 图投影写入的重试策略。
 *
 * <p>有限重试必须「有次数上限 + 有退避 + 有分类」，三者缺一：
 *
 * <ul>
 *   <li>没有上限 → 图库持续不可用时回填永不结束，表现为「一直在跑」；
 *   <li>没有退避 → 图库刚重启的几秒里把连接池打满，把瞬态故障放大成雪崩；
 *   <li>没有分类 → 数据错误被重试掩盖，日志里只剩「重试后仍然失败」，看不出上游数据有问题。
 * </ul>
 *
 * <p>{@code backoffMillis=0} 保留给测试与「已知图库健康」的一次性脚本：它是显式选择，
 * 不是默认值。
 */
public record GraphProjectionRetryPolicy(int maxAttempts, long backoffMillis) {

  public GraphProjectionRetryPolicy {
    maxAttempts = maxAttempts <= 0 ? 1 : maxAttempts;
    backoffMillis = Math.max(backoffMillis, 0L);
  }

  /** 生产默认：3 次尝试、200ms 基础退避。 */
  public static GraphProjectionRetryPolicy defaults() {
    return new GraphProjectionRetryPolicy(3, 200L);
  }

  /** 不重试（测试与显式一次性脚本用）。 */
  public static GraphProjectionRetryPolicy noRetry() {
    return new GraphProjectionRetryPolicy(1, 0L);
  }

  /** 第 {@code attempt} 次失败后的退避时长（1 起算），线性增长并封顶 5 秒。 */
  public Duration backoffFor(int attempt) {
    long millis = Math.min(backoffMillis * Math.max(attempt, 1), 5_000L);
    return Duration.ofMillis(millis);
  }

  /** 由配置构造；配置里已经做过边界收敛，这里不再解释默认值。 */
  public static GraphProjectionRetryPolicy from(GraphProjectionProperties properties) {
    return new GraphProjectionRetryPolicy(
        properties.maxAttempts(), properties.retryBackoffMillis());
  }
}
