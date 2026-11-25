package com.cognitanexus.query.graph.projection.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 图投影同步（Backfill / 事件增量 / 对账）的工程参数。
 *
 * <p>依据：ADR-005 §5.2、§6.3、§8.2；AGENTS.md §3.1（规模与批次口径必须可配置并如实报告）。
 *
 * <p>默认全部<b>关闭</b>（{@code cn.query.graph.projection.enabled=false}）：
 *
 * <ul>
 *   <li>图投影是派生能力，默认关闭时不存在任何后台任务、不会因为误接生产库就开始往图里灌数据；
 *   <li>开启必须显式，且仍受 {@code cn.query.graph.enabled} 约束——没有图库就不可能有图投影。
 * </ul>
 *
 * @param enabled 是否启用图投影同步（回填/增量/对账），默认 false
 * @param batchSize 单批回填的成果数，默认 500。2C8G 实例上每批要装「成果 + 作者 + 领域 + 机构 +
 *     作者对」的内存数据，批次过大是重建期 OOM 的首要原因；500 是在既有 PG 侧批大小与内存之间
 *     取的保守值，必须按实测调整而不是反向用来证明容量
 * @param maxAttempts 单条成果写入的最大尝试次数（含首次），默认 3
 * @param retryBackoffMillis 重试基础退避毫秒，默认 200；按 {@code backoff * attempt} 线性增长
 * @param maxFailedWorks 单次运行允许的失败成果数上限，默认 100。超过即整次运行转 FAILED：
 *     「跑完了但有几万条没进图」不能被当作成功
 * @param workerEnabled 是否启用后台增量追赶调度，默认 false（需要显式开启并确认已接线）
 * @param workerIntervalMs 调度间隔，默认 5000
 * @param eventBatchSize 单次增量消费的事件数，默认 200
 * @param reconcileScanLimit 对账扫描成果标识的上限，默认 5000。扫描有界并显式报告是否被截断，
 *     不允许把「只比了前 5000 条」读成「全库一致」
 * @param reconcileSampleSize 对账抽样核对证据的成果数，默认 20
 * @param reconcileOrphanScan 是否扫描「图里有、PG 已无」的孤立成果，默认 true。共享实例上应关闭：
 *     别的环境的成果会被误判为孤立并被退场
 */
@ConfigurationProperties(prefix = "cn.query.graph.projection")
public record GraphProjectionProperties(
    boolean enabled,
    int batchSize,
    int maxAttempts,
    long retryBackoffMillis,
    long maxFailedWorks,
    boolean workerEnabled,
    long workerIntervalMs,
    int eventBatchSize,
    int reconcileScanLimit,
    int reconcileSampleSize,
    boolean reconcileOrphanScan) {

  /** 单批上限：即使配置写错也不允许一次把整代成果读进内存。 */
  public static final int MAX_BATCH_SIZE = 5_000;

  public GraphProjectionProperties {
    batchSize = clamped(batchSize, 500, MAX_BATCH_SIZE);
    maxAttempts = maxAttempts <= 0 ? 3 : Math.min(maxAttempts, 10);
    retryBackoffMillis = retryBackoffMillis < 0 ? 200L : Math.min(retryBackoffMillis, 60_000L);
    maxFailedWorks = maxFailedWorks < 0 ? 100L : maxFailedWorks;
    workerIntervalMs = workerIntervalMs <= 0 ? 5_000L : workerIntervalMs;
    eventBatchSize = clamped(eventBatchSize, 200, MAX_BATCH_SIZE);
    reconcileScanLimit = clamped(reconcileScanLimit, 5_000, 200_000);
    reconcileSampleSize = clamped(reconcileSampleSize, 20, 1_000);
  }

  public Duration retryBackoff() {
    return Duration.ofMillis(retryBackoffMillis);
  }

  private static int clamped(int value, int fallback, int max) {
    int effective = value <= 0 ? fallback : value;
    return Math.min(effective, max);
  }

  /** 供日志展示的脱敏描述。 */
  public String describe() {
    return String.format(
        java.util.Locale.ROOT,
        "graph-projection[enabled=%s, batchSize=%d, maxAttempts=%d, backoffMs=%d, "
            + "maxFailedWorks=%d, workerEnabled=%s, eventBatchSize=%d]",
        enabled,
        batchSize,
        maxAttempts,
        retryBackoffMillis,
        maxFailedWorks,
        workerEnabled,
        eventBatchSize);
  }
}
