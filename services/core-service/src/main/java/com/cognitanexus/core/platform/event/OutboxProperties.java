package com.cognitanexus.core.platform.event;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 投递器配置。
 *
 * <p>依据：架构 V0.2 §6.3。重试退避 1、2、4、8 秒至最高 60 秒并加抖动；超过阈值报 FAILED 并等待
 * 人工重放，事件不自动删除。这些是可调工程默认值，不是性能承诺。
 */
@ConfigurationProperties("cn.outbox")
public class OutboxProperties {

  private boolean dispatcherEnabled = true;
  private int batchSize = 100;
  private int maxAttempts = 12;

  /** 投递租约时长。领取后在该窗口内其他实例不得重复领取。 */
  private Duration lease = Duration.ofSeconds(20);

  private Duration maxBackoff = Duration.ofSeconds(60);
  private int maxJitterSeconds = 5;

  /** 接收者标识到接收端点的映射；键即为 consumerId。 */
  private Map<String, String> consumerEndpoints = new LinkedHashMap<>();

  private Duration connectTimeout = Duration.ofSeconds(2);
  private Duration requestTimeout = Duration.ofSeconds(10);

  public boolean isDispatcherEnabled() {
    return dispatcherEnabled;
  }

  public void setDispatcherEnabled(boolean dispatcherEnabled) {
    this.dispatcherEnabled = dispatcherEnabled;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  public Duration getLease() {
    return lease;
  }

  public void setLease(Duration lease) {
    this.lease = lease;
  }

  public Duration getMaxBackoff() {
    return maxBackoff;
  }

  public void setMaxBackoff(Duration maxBackoff) {
    this.maxBackoff = maxBackoff;
  }

  public int getMaxJitterSeconds() {
    return maxJitterSeconds;
  }

  public void setMaxJitterSeconds(int maxJitterSeconds) {
    this.maxJitterSeconds = maxJitterSeconds;
  }

  public Map<String, String> getConsumerEndpoints() {
    return consumerEndpoints;
  }

  public void setConsumerEndpoints(Map<String, String> consumerEndpoints) {
    this.consumerEndpoints = consumerEndpoints == null ? new LinkedHashMap<>() : consumerEndpoints;
  }

  public Duration getConnectTimeout() {
    return connectTimeout;
  }

  public void setConnectTimeout(Duration connectTimeout) {
    this.connectTimeout = connectTimeout;
  }

  public Duration getRequestTimeout() {
    return requestTimeout;
  }

  public void setRequestTimeout(Duration requestTimeout) {
    this.requestTimeout = requestTimeout;
  }
}
