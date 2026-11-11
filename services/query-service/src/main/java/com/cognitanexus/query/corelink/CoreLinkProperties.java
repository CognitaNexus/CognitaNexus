package com.cognitanexus.query.corelink;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 调用 core 内部接口的配置。
 *
 * <p>依据：架构 V0.2 §10.1（内部接口需服务身份与最小权限）、§7.2（每次公开请求校验权威公开水位）。
 * 超时必须显式配置：屏障校验处于每个公开请求的必经路径上，若没有读超时，core 慢响应会把本服务的
 * 公开查询线程全部拖住，最终表现为本服务整体不可用而不是「屏障校验失败」。
 */
@ConfigurationProperties(prefix = "cn.core")
public record CoreLinkProperties(
    String baseUrl,
    String serviceName,
    String serviceToken,
    Duration connectTimeout,
    Duration requestTimeout) {

  public CoreLinkProperties {
    baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://127.0.0.1:8081" : baseUrl;
    serviceName = serviceName == null || serviceName.isBlank() ? "query" : serviceName;
    serviceToken = serviceToken == null ? "" : serviceToken;
    connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
    requestTimeout = requestTimeout == null ? Duration.ofSeconds(3) : requestTimeout;
  }

  /** 令牌未配置时全部内部调用都不可用；调用方据此直接返回 503 而不是发一个必然被拒的请求。 */
  public boolean tokenConfigured() {
    return !serviceToken.isBlank();
  }
}
