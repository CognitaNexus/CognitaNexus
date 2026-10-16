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
 * @param maxBackoffSeconds 重试退避上限
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
    int maxBackoffSeconds) {

  public CollectProperties {
    batchSize = batchSize <= 0 ? 100 : batchSize;
    maxBatchBytes = maxBatchBytes <= 0 ? 2 * 1024 * 1024 : maxBatchBytes;
    maxPagesPerRun = maxPagesPerRun <= 0 ? 40 : maxPagesPerRun;
    leaseSeconds = leaseSeconds <= 0 ? 120 : leaseSeconds;
    workerIntervalMs = workerIntervalMs <= 0 ? 2000 : workerIntervalMs;
    maxHandoffAttempts = maxHandoffAttempts <= 0 ? 8 : maxHandoffAttempts;
    maxBackoffSeconds = maxBackoffSeconds <= 0 ? 300 : maxBackoffSeconds;
  }
}
