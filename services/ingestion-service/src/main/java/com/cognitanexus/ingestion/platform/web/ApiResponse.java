package com.cognitanexus.ingestion.platform.web;

import com.cognitanexus.sharedkernel.ErrorCode;
import java.time.Instant;
import java.util.Map;

/**
 * 统一响应包。
 *
 * <p>依据：架构 V0.2 §10.3。字段与 core 保持一致，使前端对三个服务的失败可以按同一个 code
 * 分支处理。列表结果使用 items/page/size/total 或 totalRelation，空结果返回 200 而非错误。
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
