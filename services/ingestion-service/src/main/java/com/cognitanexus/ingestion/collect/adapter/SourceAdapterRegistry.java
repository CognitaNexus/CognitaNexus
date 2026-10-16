package com.cognitanexus.ingestion.collect.adapter;

import com.cognitanexus.ingestion.collect.domain.AdapterKind;
import com.cognitanexus.sharedkernel.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 适配器登记表。
 *
 * <p>依据：架构 V0.2 §6.2。按来源运行配置声明的种类取出适配器；未实现的种类直接报错，不做
 * 「找不到适配器就跳过」的静默降级——静默跳过会让任务显示成功而实际什么都没采。
 */
@Component
public class SourceAdapterRegistry {

  private final Map<AdapterKind, SourceAdapter> adapters = new EnumMap<>(AdapterKind.class);

  public SourceAdapterRegistry(ObjectMapper objectMapper, AdapterProperties properties) {
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    register(
        new CrossrefAdapter(
            objectMapper, httpClient, properties.userAgent(), properties.maxResponseBytes()));
    register(
        new OpenAlexAdapter(
            objectMapper, httpClient, properties.userAgent(), properties.maxResponseBytes()));
  }

  private void register(SourceAdapter adapter) {
    adapters.put(adapter.kind(), adapter);
  }

  /** 取适配器；未实现的种类抛业务异常。 */
  public SourceAdapter require(AdapterKind kind) {
    SourceAdapter adapter = adapters.get(kind);
    if (adapter == null) {
      throw new BusinessException(
          com.cognitanexus.sharedkernel.ErrorCode.BUSINESS_RULE_VIOLATION, "尚未实现该来源种类的适配器：" + kind);
    }
    return adapter;
  }

  /** 已实现适配器的种类列表，供管理端展示能力边界。 */
  public List<String> implementedKinds() {
    return adapters.keySet().stream().map(Enum::name).sorted().toList();
  }

  /** 适配器运行参数。 */
  @ConfigurationProperties(prefix = "cn.collect.adapter")
  public record AdapterProperties(String userAgent, Duration requestTimeout, int maxResponseBytes) {

    public AdapterProperties {
      userAgent =
          userAgent == null || userAgent.isBlank()
              ? "CognitaNexus/0.2 (mailto:cn-admin@example.edu.cn)"
              : userAgent;
      requestTimeout = requestTimeout == null ? Duration.ofSeconds(20) : requestTimeout;
      maxResponseBytes = maxResponseBytes <= 0 ? 4 * 1024 * 1024 : maxResponseBytes;
    }

    Duration connectTimeout() {
      return requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout;
    }
  }
}
