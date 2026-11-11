package com.cognitanexus.sharedkernel;

import java.util.Objects;

/**
 * 错误标识。由有限、稳定的错误码组成，参数只允许可公开的白名单值。
 *
 * <p>依据：编码规范 §4.6。错误响应不包含 SQL、堆栈、文件路径、凭据或材料内容。
 */
public enum ErrorCode {
  // 400
  INVALID_ARGUMENT(400),
  // 401
  AUTH_REQUIRED(401),
  // 403
  ACCESS_DENIED(403),
  // 404
  NOT_FOUND(404),
  // 409
  VERSION_CONFLICT(409),
  QUERY_VERSION_CHANGED(409),
  IDEMPOTENCY_KEY_REUSED(409),
  // 413
  PAYLOAD_TOO_LARGE(413),
  // 422
  BUSINESS_RULE_VIOLATION(422),
  // 429
  RATE_LIMITED(429),
  // 503
  DEPENDENCY_UNAVAILABLE(503),
  VIEW_UPDATING(503),
  GRAPH_SCOPE_TOO_LARGE(503),
  GRAPH_TIMEOUT(503),
  // 500
  INTERNAL_ERROR(500);

  private final int httpStatus;

  ErrorCode(int httpStatus) {
    this.httpStatus = httpStatus;
  }

  public int httpStatus() {
    return httpStatus;
  }

  /** 是否为客户端可重试的错误（前端据此决定是否保留输入并提供重试按钮）。 */
  public boolean retryable() {
    return this == DEPENDENCY_UNAVAILABLE
        || this == VIEW_UPDATING
        || this == GRAPH_TIMEOUT
        || this == RATE_LIMITED;
  }

  public static ErrorCode requireNonBlank(ErrorCode code) {
    return Objects.requireNonNull(code, "errorCode");
  }
}
