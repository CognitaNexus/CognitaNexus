package com.cognitanexus.core.platform.web;

import com.cognitanexus.sharedkernel.ErrorCode;
import java.time.Instant;
import java.util.Map;

/**
 * 统一响应包。
 *
 * <p>依据：架构 V0.2 §10.3。成功包含 code/data/traceId 及适用版本字段；列表包含
 * items/page/size/total 或 totalRelation/queryVersion；空结果为 HTTP 200 而非错误。
 * 未知总数不得填 0。
 */
public record ApiResponse<T>(
    String code,
    String message,
    T data,
    Map<String, Object> meta,
    String traceId,
    Instant timestamp) {

  public static <T> ApiResponse<T> ok(T data, String traceId) {
    return new ApiResponse<>("OK", "成功", data, Map.of(), traceId, Instant.now());
  }

  public static <T> ApiResponse<T> ok(T data, Map<String, Object> meta, String traceId) {
    return new ApiResponse<>(
        "OK", "成功", data, meta == null ? Map.of() : meta, traceId, Instant.now());
  }

  public static <T> ApiResponse<T> failure(ErrorCode code, String message, String traceId) {
    return new ApiResponse<>(code.name(), message, null, Map.of(), traceId, Instant.now());
  }

  public static <T> ApiResponse<T> failure(
      ErrorCode code, String message, Map<String, Object> meta, String traceId) {
    return new ApiResponse<>(
        code.name(), message, null, meta == null ? Map.of() : meta, traceId, Instant.now());
  }
}
