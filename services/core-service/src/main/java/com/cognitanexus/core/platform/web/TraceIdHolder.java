package com.cognitanexus.core.platform.web;

import java.util.UUID;
import org.slf4j.MDC;

/**
 * 追踪标识：每请求生成或沿用合法的外部 traceId，并写入日志上下文。
 *
 * <p>依据：编码规范 §4.6。外部传入的 traceId 只在长度与字符合法时采用，否则重新生成；
 * 服务间传播同一 traceId，便于定位跨服务失败（NF07、A07）。
 */
public final class TraceIdHolder {

  public static final String MDC_KEY = "traceId";
  public static final String HEADER = "X-Trace-Id";
  private static final int MAX_LENGTH = 64;

  private TraceIdHolder() {}

  public static String current() {
    String existing = MDC.get(MDC_KEY);
    return existing == null ? generate() : existing;
  }

  public static String generate() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 24);
  }

  /** 判定外部 traceId 是否可用。 */
  public static boolean acceptable(String candidate) {
    if (candidate == null || candidate.isBlank() || candidate.length() > MAX_LENGTH) {
      return false;
    }
    return candidate.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_');
  }
}
