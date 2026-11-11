package com.cognitanexus.ingestion.platform.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 采集管理能力与内部服务身份的常量。
 *
 * <p>依据：架构 V0.2 §10.1、§10.2。能力字符串与 core 的 {@code RoleNames} 保持一致：两侧若不一致，
 * core 返回的能力名在本服务会被判成「不具备」，表现为管理接口永远 403，且错误信息毫无提示。
 */
public final class IngestionSecurity {

  /** 采集管理能力名；必须与 core 的 source:manage 逐字一致。 */
  public static final String CAP_SOURCE_MANAGE = "source:manage";

  /** 调用 core 内部接口时使用的服务名。 */
  public static final String SERVICE_NAME = "ingestion";

  public static final String HEADER_SERVICE_NAME = "X-Service-Name";
  public static final String HEADER_SERVICE_TOKEN = "X-Service-Token";
  public static final String HEADER_CSRF = "X-XSRF-TOKEN";
  public static final String COOKIE_CSRF = "XSRF-TOKEN";

  private IngestionSecurity() {}

  /** 恒定时间比较，避免用令牌前缀匹配速度差反推令牌内容。 */
  public static boolean constantTimeEquals(String expected, String actual) {
    if (expected == null || actual == null) {
      return false;
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }
}
