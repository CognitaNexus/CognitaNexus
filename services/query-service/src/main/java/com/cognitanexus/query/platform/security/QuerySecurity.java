package com.cognitanexus.query.platform.security;

/** 服务身份与内部端点的公共常量。 */
public final class QuerySecurity {

  public static final String SERVICE_NAME = "query";
  public static final String HEADER_SERVICE_NAME = "X-Service-Name";
  public static final String HEADER_SERVICE_TOKEN = "X-Service-Token";

  /** 公开查询的服务端并发上限，用于缓存不可用时的回源保护。 */
  public static final int PUBLIC_FALLBACK_CONCURRENCY = 16;

  private QuerySecurity() {}

  /**
   * 恒定时间比较。
   *
   * <p>依据：架构 V0.2 §10.2。普通字符串比较在首个不同字节处返回，攻击者可以用大量请求测量
   * 响应时间，逐字节试探服务令牌。长度不同时也遍历一遍再返回，避免长度本身成为旁路。
   */
  public static boolean constantTimeEquals(String expected, String actual) {
    if (expected == null || actual == null) {
      return false;
    }
    byte[] left = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] right = actual.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    int diff = left.length ^ right.length;
    for (int i = 0; i < left.length; i++) {
      diff |= left[i] ^ right[i % Math.max(right.length, 1)];
    }
    return diff == 0;
  }
}
