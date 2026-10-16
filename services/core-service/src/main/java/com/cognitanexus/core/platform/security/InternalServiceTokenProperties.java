package com.cognitanexus.core.platform.security;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 内部服务令牌配置。
 *
 * <p>依据：架构 V0.2 §10.2。内部服务使用各自独立、可轮换的服务令牌，令牌仅授权指定内部端点，
 * 不能冒充人类管理员；凭据从外部配置注入且不入库。
 */
@ConfigurationProperties(prefix = "cn.internal")
public record InternalServiceTokenProperties(Map<String, String> serviceTokens) {

  public InternalServiceTokenProperties {
    serviceTokens = serviceTokens == null ? Map.of() : Map.copyOf(serviceTokens);
  }

  /** 校验调用方服务名与令牌是否匹配。 */
  public boolean matches(String serviceName, String token) {
    if (serviceName == null || token == null) {
      return false;
    }
    String expected = serviceTokens.get(serviceName);
    return expected != null && expected.equals(token);
  }

  public boolean configured() {
    return !serviceTokens.isEmpty();
  }
}
