package com.cognitanexus.ingestion.collect.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 采集运行参数。
 *
 * <p>依据：架构 V0.2 §6.2、§5.3。此处全部是<b>可调工程默认值</b>，不是需求已冻结的指标：
 * 批次大小与请求体上限影响单批事务时长与重试成本，页数与租约时长影响任务可恢复性。取值需在
 * 容量实测后调整，文档中不宣称这些数值已经过验证。
 *
 * @param batchSize 交接批次的记录条数
 * @param maxBatchBytes 交接请求体上限；超限的批次会被拆小再发
 * @param maxPagesPerRun 单次任务最多抓取的页数，防止误配查询导致无界抓取
 * @param leaseSeconds 任务租约时长；超过该时长未续租即允许其他实例接管
 * @param workerEnabled 是否启用后台执行器
 * @param workerIntervalMs 执行器轮询间隔
 * @param maxHandoffAttempts 单批次交接重试上限；超过后转 FAILED 等待人工处置
 * @param maxBackoffSeconds 自身指数退避的上限
 * @param maxRetryAfterSeconds 来源在 {@code Retry-After} 里明示的等待时间的上限
 * @param maxConsecutiveFailures 连续失败熔断阈值；达到后任务转 FAILED 不再自动重试
 */
@ConfigurationProperties(prefix = "cn.collect")
public record CollectProperties(
    int batchSize,
    long maxBatchBytes,
    int maxPagesPerRun,
    int leaseSeconds,
    boolean workerEnabled,
    long workerIntervalMs,
    int maxHandoffAttempts,
    int maxBackoffSeconds,
    int maxRetryAfterSeconds,
    int maxConsecutiveFailures) {

  public CollectProperties {
    batchSize = batchSize <= 0 ? 100 : batchSize;
    maxBatchBytes = maxBatchBytes <= 0 ? 2 * 1024 * 1024 : maxBatchBytes;
    maxPagesPerRun = maxPagesPerRun <= 0 ? 40 : maxPagesPerRun;
    leaseSeconds = leaseSeconds <= 0 ? 120 : leaseSeconds;
    workerIntervalMs = workerIntervalMs <= 0 ? 2000 : workerIntervalMs;
    maxHandoffAttempts = maxHandoffAttempts <= 0 ? 8 : maxHandoffAttempts;
    maxBackoffSeconds = maxBackoffSeconds <= 0 ? 300 : maxBackoffSeconds;
    // 与 maxBackoffSeconds 分开，因为两者的「合理上限」差两个数量级：自己的推算没必要超过几分钟，
    // 而来源明示的等待可能是按日配额算出来的十几小时。共用一个上限就只有两种坏结果——
    // 要么把自己的退避放得太长，要么把来源的要求截得太短（线上实际就是这个：OpenAlex 要求
    // 44533 秒，被 300 秒压回来，于是每 5 分钟照打一轮，只在刷新对方的限流窗口）。
    // 上限取 12 小时而不是无限：响应头解析异常（例如把日期差值算错）不该把任务冻结几十天。
    maxRetryAfterSeconds = maxRetryAfterSeconds <= 0 ? 43_200 : maxRetryAfterSeconds;
    // 20 次：按自身指数退避累计约 1 小时。对上游短暂的网络抖动足够宽容，而持续故障
    // （线上实测有任务在来源稳定返回 400 后打到第 421 次）会在约一小时后停手等人工处置。
    maxConsecutiveFailures = maxConsecutiveFailures <= 0 ? 20 : maxConsecutiveFailures;
  }
}
