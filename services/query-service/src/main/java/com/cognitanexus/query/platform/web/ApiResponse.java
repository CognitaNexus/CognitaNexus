package com.cognitanexus.query.platform.web;

import com.cognitanexus.sharedkernel.ErrorCode;
import java.time.Instant;
import java.util.Map;

/**
 * 统一响应包。
 *
 * <p>依据：架构 V0.2 §10.3。字段与 core、ingestion 保持一致，使前端对三个服务的失败可以按同一个
 * code 分支处理。公开检索与统计额外在 meta 中给出发布版本与规则版本，这是「同一响应内的版本声明」，
 * 前端据此判断结果是否已被撤销。
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
}
