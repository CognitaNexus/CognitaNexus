package com.cognitanexus.sharedkernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 错误码的行为约束。
 *
 * <p>依据：编码规范 §4.6、架构 V0.2 §9.3。错误码是前端唯一可靠的处置依据：同一句「失败了」，
 * 401 要跳登录、403 要提示无权限、409 要刷新快照、503 要保留输入并允许重试。
 */
class ErrorCodeTest {

  @Test
  @DisplayName("HTTP 状态映射固定：前端按状态码分流，不能靠字符串匹配提示语")
  void statusMapping() {
    assertThat(ErrorCode.INVALID_ARGUMENT.httpStatus()).isEqualTo(400);
    assertThat(ErrorCode.AUTH_REQUIRED.httpStatus()).isEqualTo(401);
    assertThat(ErrorCode.ACCESS_DENIED.httpStatus()).isEqualTo(403);
    assertThat(ErrorCode.NOT_FOUND.httpStatus()).isEqualTo(404);
    assertThat(ErrorCode.VERSION_CONFLICT.httpStatus()).isEqualTo(409);
    assertThat(ErrorCode.QUERY_VERSION_CHANGED.httpStatus()).isEqualTo(409);
    assertThat(ErrorCode.IDEMPOTENCY_KEY_REUSED.httpStatus()).isEqualTo(409);
    assertThat(ErrorCode.PAYLOAD_TOO_LARGE.httpStatus()).isEqualTo(413);
    assertThat(ErrorCode.BUSINESS_RULE_VIOLATION.httpStatus()).isEqualTo(422);
    assertThat(ErrorCode.RATE_LIMITED.httpStatus()).isEqualTo(429);
    assertThat(ErrorCode.DEPENDENCY_UNAVAILABLE.httpStatus()).isEqualTo(503);
    assertThat(ErrorCode.VIEW_UPDATING.httpStatus()).isEqualTo(503);
    assertThat(ErrorCode.GRAPH_SCOPE_TOO_LARGE.httpStatus()).isEqualTo(503);
    assertThat(ErrorCode.GRAPH_TIMEOUT.httpStatus()).isEqualTo(503);
    assertThat(ErrorCode.INTERNAL_ERROR.httpStatus()).isEqualTo(500);
  }

  @Test
  @DisplayName("可重试集合恰好是四项：把不可重试的错误标成可重试会诱导用户反复无效操作")
  void retryableSet() {
    assertThat(ErrorCode.DEPENDENCY_UNAVAILABLE.retryable()).isTrue();
    assertThat(ErrorCode.VIEW_UPDATING.retryable()).isTrue();
    assertThat(ErrorCode.GRAPH_TIMEOUT.retryable()).isTrue();
    assertThat(ErrorCode.RATE_LIMITED.retryable()).isTrue();

    assertThat(ErrorCode.INVALID_ARGUMENT.retryable()).isFalse();
    assertThat(ErrorCode.AUTH_REQUIRED.retryable()).isFalse();
    assertThat(ErrorCode.ACCESS_DENIED.retryable()).isFalse();
    assertThat(ErrorCode.NOT_FOUND.retryable()).isFalse();
    assertThat(ErrorCode.QUERY_VERSION_CHANGED.retryable()).isFalse();
    assertThat(ErrorCode.INTERNAL_ERROR.retryable()).isFalse();
  }

  @Test
  @DisplayName("409 语义区分：版本冲突与幂等键复用都不属于可重试")
  void conflictCodesNotRetryable() {
    assertThat(ErrorCode.VERSION_CONFLICT.retryable()).isFalse();
    assertThat(ErrorCode.IDEMPOTENCY_KEY_REUSED.retryable()).isFalse();
  }

  @Test
  @DisplayName("requireNonBlank 拒绝 null，避免错误响应里出现空错误码")
  void requireNonBlankRejectsNull() {
    assertThat(ErrorCode.requireNonBlank(ErrorCode.NOT_FOUND)).isEqualTo(ErrorCode.NOT_FOUND);
    assertThatThrownBy(() -> ErrorCode.requireNonBlank(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("errorCode");
  }
}
