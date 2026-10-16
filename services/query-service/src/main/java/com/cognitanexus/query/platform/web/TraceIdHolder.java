package com.cognitanexus.query.platform.web;

import java.security.SecureRandom;
import java.util.HexFormat;
import org.slf4j.MDC;

/**
 * 链路标识持有者。
 *
 * <p>依据：架构 V0.2 §10.4。一次公开查询跨越「事件接收 → 投影发布 → 检索」三段，若没有贯穿的
 * 标识，用户报告「搜不到刚发布的成果」时无法判断是哪一段落后。MDC 键与另外两个服务保持一致，
 * 使日志可以跨服务串起来。
 */
public final class TraceIdHolder {

  public static final String MDC_KEY = "traceId";
  public static final String HEADER = "X-Trace-Id";

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int MAX_LENGTH = 64;

  private TraceIdHolder() {}

  public static String current() {
    String value = MDC.get(MDC_KEY);
    return value == null ? "" : value;
  }

  public static String generate() {
    byte[] bytes = new byte[12];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  /** 判断调用方传入的标识是否可沿用：限制长度与字符集，避免把外部内容直接写进日志。 */
  public static boolean acceptable(String candidate) {
    if (candidate == null || candidate.isBlank() || candidate.length() > MAX_LENGTH) {
      return false;
    }
    for (int i = 0; i < candidate.length(); i++) {
      char ch = candidate.charAt(i);
      boolean allowed =
          (ch >= 'a' && ch <= 'z')
              || (ch >= 'A' && ch <= 'Z')
              || (ch >= '0' && ch <= '9')
              || ch == '-'
              || ch == '_';
      if (!allowed) {
        return false;
      }
    }
    return true;
  }

  public static void set(String traceId) {
    MDC.put(MDC_KEY, traceId);
  }

  public static void clear() {
    MDC.remove(MDC_KEY);
  }
}
