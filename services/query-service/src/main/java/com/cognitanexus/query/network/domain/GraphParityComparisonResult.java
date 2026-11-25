package com.cognitanexus.query.network.domain;

import java.time.Instant;
import java.util.Locale;

/**
 * 一次新旧查询对照的结构化结果。
 *
 * <p>用途是把「灰度前到底对没对上」变成可计数、可告警的事实，而不是一次性的手工核对：
 *
 * <ul>
 *   <li>{@code compared=false} 且带 {@code skipReason} 表示对照未执行（图库不可用或投影落后）。
 *       这必须与「执行了且一致」分开计数——把跳过记成一致，会得到一个永远健康的对照指标；
 *   <li>{@code report} 是逐字段差异明细；{@code matched} 只看明细是否为空；
 *   <li>两侧耗时分开记录：对照模式本身会多一次图查询，用它衡量的是「图查询比 PG 慢多少」，
 *       而不是用户实际感知的延迟（用户拿到的是 PG 结果）。
 * </ul>
 *
 * @param operation 查询类型（{@code neighborhood}/{@code shortestPath}）
 * @param requestKey 请求的稳定描述（含版本、深度、筛选），用于定位差异
 * @param mode 触发本次对照的路由模式
 * @param report 差异明细；未执行对照时为 null
 * @param skipReason 未执行对照的原因码（{@link GraphProjectionStatus} 的 reasonCode 或 {@code GRAPH_ERROR}）；
 *     已执行时为 null
 * @param postgresNanos PG 查询耗时（纳秒）
 * @param graphNanos 图查询耗时（纳秒）；未执行时为 0
 * @param comparedAt 对照发生时间
 */
public record GraphParityComparisonResult(
    String operation,
    String requestKey,
    GraphQueryMode mode,
    ParityDiffReport report,
    String skipReason,
    long postgresNanos,
    long graphNanos,
    Instant comparedAt) {

  public GraphParityComparisonResult {
    operation = operation == null ? "" : operation;
    requestKey = requestKey == null ? "" : requestKey;
    mode = mode == null ? GraphQueryMode.SHADOW_COMPARE : mode;
    postgresNanos = Math.max(postgresNanos, 0L);
    graphNanos = Math.max(graphNanos, 0L);
    comparedAt = comparedAt == null ? Instant.now() : comparedAt;
  }

  /** 是否真的执行了图查询对照（而不是因降级条件被跳过）。 */
  public boolean compared() {
    return report != null;
  }

  /** 是否执行且逐字段一致。未执行也返回 false，避免「跳过」冒充「一致」。 */
  public boolean matched() {
    return report != null && report.matched();
  }

  public static GraphParityComparisonResult skipped(
      String operation,
      String requestKey,
      GraphQueryMode mode,
      String skipReason,
      long postgresNanos) {
    return new GraphParityComparisonResult(
        operation, requestKey, mode, null, skipReason, postgresNanos, 0L, Instant.now());
  }

  public static GraphParityComparisonResult compared(
      String operation,
      String requestKey,
      GraphQueryMode mode,
      ParityDiffReport report,
      long postgresNanos,
      long graphNanos) {
    return new GraphParityComparisonResult(
        operation, requestKey, mode, report, null, postgresNanos, graphNanos, Instant.now());
  }

  /** 供日志一行输出；不包含地址与凭据。 */
  public String describe() {
    if (!compared()) {
      return String.format(
          Locale.ROOT,
          "对照跳过：%s [%s]，原因=%s，pgNanos=%d",
          operation,
          requestKey,
          skipReason,
          postgresNanos);
    }
    return String.format(
        Locale.ROOT, "%s，pgNanos=%d，graphNanos=%d", report.summary(), postgresNanos, graphNanos);
  }
}
