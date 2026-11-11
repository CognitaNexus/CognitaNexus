package com.cognitanexus.query.projection.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 投影与检索的工程参数。
 *
 * <p>依据：架构 V0.2 §8.2、§9.1、§9.3、§13.2。这些是<b>可调起始值，不是性能承诺</b>：
 * 子图节点上限、路径时间预算、批次大小都必须经压测后按测量结果调整，而不是反过来用它证明
 * 系统已达到目标。
 *
 * @param consumerId 本服务在 core Outbox 中的接收者标识，必须与 core 的 consumer-endpoints 键一致
 * @param publishBatchPerBatch 每次投递回调最多应用的投影事件数，防止一次回调长时间占用连接
 * @param failureBackoffSeconds 投影应用失败的基础退避秒数
 * @param maxAuthorsPerAchievementForEdges 单篇成果参与建边的作者上限。
 *     <p>超过上限时<b>隔离该事件并记录原因</b>，绝不只取前 N 位作者——静默截断会改变科研语义，
 *     而结果看起来完全正常（架构 §9.3 明确要求「超预算明确失败并记录」）
 * @param graphMaxNodes 单次子图节点上限
 * @param graphMaxEdges 单次子图边上限
 * @param graphBudgetMillis 单次路径计算时间预算
 */
@ConfigurationProperties(prefix = "cn.projection")
public record ProjectionProperties(
    String consumerId,
    int publishBatchPerBatch,
    int failureBackoffSeconds,
    int maxAuthorsPerAchievementForEdges,
    boolean workerEnabled,
    long workerIntervalMs,
    int graphMaxNodes,
    int graphMaxEdges,
    long graphBudgetMillis,
    int searchMaxOffset,
    int searchDefaultPageSize,
    int searchMaxPageSize) {

  public ProjectionProperties {
    consumerId = consumerId == null || consumerId.isBlank() ? "query" : consumerId;
    publishBatchPerBatch = publishBatchPerBatch <= 0 ? 500 : publishBatchPerBatch;
    failureBackoffSeconds = failureBackoffSeconds <= 0 ? 5 : failureBackoffSeconds;
    maxAuthorsPerAchievementForEdges =
        maxAuthorsPerAchievementForEdges <= 0 ? 200 : maxAuthorsPerAchievementForEdges;
    workerIntervalMs = workerIntervalMs <= 0 ? 2000L : workerIntervalMs;
    graphMaxNodes = graphMaxNodes <= 0 ? 20_000 : graphMaxNodes;
    graphMaxEdges = graphMaxEdges <= 0 ? 100_000 : graphMaxEdges;
    graphBudgetMillis = graphBudgetMillis <= 0 ? 1_000L : graphBudgetMillis;
    searchMaxOffset = searchMaxOffset <= 0 ? 10_000 : searchMaxOffset;
    searchDefaultPageSize = searchDefaultPageSize <= 0 ? 20 : searchDefaultPageSize;
    searchMaxPageSize = searchMaxPageSize <= 0 ? 100 : searchMaxPageSize;
  }

  public Duration failureBackoff() {
    return Duration.ofSeconds(failureBackoffSeconds);
  }
}
