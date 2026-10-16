package com.cognitanexus.ingestion.platform.web;

import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 业务异常到 HTTP 的映射。
 *
 * <p>依据：架构 V0.2 §10.3、编码规范 §4.6。只向调用方暴露业务异常携带的、已明确的公开参数；
 * 未预期异常一律折叠为 500 并只给出 traceId，不把异常类名、SQL 或堆栈放回响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
    ErrorCode code = ex.errorCode();
    if (code == ErrorCode.INTERNAL_ERROR) {
      log.error("业务异常被标记为内部错误 code={}", code, ex);
    } else {
      log.info("请求被业务规则拒绝 code={} message={}", code, ex.getMessage());
    }
    return ResponseEntity.status(code.httpStatus())
        .body(ApiResponse.failure(code, ex.getMessage(), TraceIdHolder.current()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
    List<String> fields =
        ex.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .toList();
    String message = fields.isEmpty() ? "请求参数不合法" : String.join("；", fields);
    return ResponseEntity.status(ErrorCode.INVALID_ARGUMENT.httpStatus())
        .body(ApiResponse.failure(ErrorCode.INVALID_ARGUMENT, message, TraceIdHolder.current()));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MissingServletRequestParameterException.class,
    IllegalArgumentException.class
  })
  public ResponseEntity<ApiResponse<Void>> handleMalformed(Exception ex) {
    log.info("请求格式不合法 message={}", ex.getMessage());
    return ResponseEntity.status(ErrorCode.INVALID_ARGUMENT.httpStatus())
        .body(
            ApiResponse.failure(
                ErrorCode.INVALID_ARGUMENT, "请求格式不合法，请检查字段与取值", TraceIdHolder.current()));
  }

  @ExceptionHandler(DuplicateKeyException.class)
  public ResponseEntity<ApiResponse<Void>> handleDuplicate(DuplicateKeyException ex) {
    log.warn("唯一约束冲突 message={}", ex.getMostSpecificCause().getMessage());
    return ResponseEntity.status(ErrorCode.VERSION_CONFLICT.httpStatus())
        .body(
            ApiResponse.failure(
                ErrorCode.VERSION_CONFLICT, "该对象已被并发修改或已存在，请刷新后重试", TraceIdHolder.current()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
    String traceId = TraceIdHolder.current();
    log.error("未预期异常 traceId={}", traceId, ex);
    return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
        .body(
            ApiResponse.failure(
                ErrorCode.INTERNAL_ERROR, "服务内部错误，请稍后重试或联系管理员（traceId=" + traceId + "）", traceId));
  }
}
