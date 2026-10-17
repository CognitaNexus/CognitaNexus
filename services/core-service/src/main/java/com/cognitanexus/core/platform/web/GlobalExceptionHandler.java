package com.cognitanexus.core.platform.web;

import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常到 HTTP 状态的转换。
 *
 * <p>依据：架构 V0.2 §10.3、编码规范 §4.6。不把所有结果都返回 200；错误响应不包含 SQL、
 * 堆栈、文件路径、凭据或材料内容；内部受控日志允许保存脱敏堆栈。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiResponse<Void>> handleBusiness(
      BusinessException ex, HttpServletRequest request) {
    String traceId = TraceIdHolder.current();
    log.warn(
        "业务拒绝 operation={} path={} code={} reason={}",
        request.getMethod(),
        request.getRequestURI(),
        ex.errorCode(),
        ex.getMessage());
    return ResponseEntity.status(ex.errorCode().httpStatus())
        .body(ApiResponse.failure(ex.errorCode(), ex.getMessage(), traceId));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiResponse<Void>> handleValidation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    String detail =
        ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
            .orElse("请求参数不合法");
    log.warn("参数校验失败 path={} detail={}", request.getRequestURI(), detail);
    return ResponseEntity.status(ErrorCode.INVALID_ARGUMENT.httpStatus())
        .body(ApiResponse.failure(ErrorCode.INVALID_ARGUMENT, detail, TraceIdHolder.current()));
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class
  })
  public ResponseEntity<ApiResponse<Void>> handleBadRequest(
      Exception ex, HttpServletRequest request) {
    log.warn("请求格式错误 path={} type={}", request.getRequestURI(), ex.getClass().getSimpleName());
    return ResponseEntity.status(ErrorCode.INVALID_ARGUMENT.httpStatus())
        .body(
            ApiResponse.failure(ErrorCode.INVALID_ARGUMENT, "请求格式或参数不合法", TraceIdHolder.current()));
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiResponse<Void>> handleAccessDenied(
      AccessDeniedException ex, HttpServletRequest request) {
    log.warn("访问被拒绝 path={}", request.getRequestURI());
    return ResponseEntity.status(ErrorCode.ACCESS_DENIED.httpStatus())
        .body(ApiResponse.failure(ErrorCode.ACCESS_DENIED, "无权执行该操作", TraceIdHolder.current()));
  }

  /** 唯一约束冲突转为可解释的 409，而不是把数据库错误直接暴露给调用方。 */
  /**
   * 路径不存在。
   *
   * <p>Spring 6.1 起，未匹配任何处理器的请求抛出 {@link NoResourceFoundException}，而不是 404
   * 直接返回。若不单独处理，它会被下面的兜底分支折叠成 500：调用方把「路径写错了」读成「服务器
   * 坏了」，排查方向从一开始就是错的；每次探测还会在日志里留下一条 ERROR 堆栈，把真正的故障淹没。
   */
  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception ex) {
    log.debug("路径不存在 message={}", ex.getMessage());
    return ResponseEntity.status(ErrorCode.NOT_FOUND.httpStatus())
        .body(
            ApiResponse.failure(
                ErrorCode.NOT_FOUND, "请求的路径不存在，请检查接口地址与版本前缀", TraceIdHolder.current()));
  }

  @ExceptionHandler(DuplicateKeyException.class)
  public ResponseEntity<ApiResponse<Void>> handleDuplicate(
      DuplicateKeyException ex, HttpServletRequest request) {
    log.warn(
        "唯一约束冲突 path={} message={}",
        request.getRequestURI(),
        ex.getMostSpecificCause().getMessage());
    return ResponseEntity.status(ErrorCode.VERSION_CONFLICT.httpStatus())
        .body(
            ApiResponse.failure(
                ErrorCode.VERSION_CONFLICT, "该对象已存在或与现有状态冲突，请刷新后重试", TraceIdHolder.current()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleUnexpected(
      Exception ex, HttpServletRequest request) {
    String traceId = TraceIdHolder.current();
    // 内部受控日志保留堆栈，便于排障；对外只返回追踪标识
    log.error("未预期错误 path={} traceId={}", request.getRequestURI(), traceId, ex);
    return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
        .body(
            ApiResponse.failure(
                ErrorCode.INTERNAL_ERROR,
                "服务内部错误，请凭追踪标识联系管理员",
                Map.of("supportReference", traceId),
                traceId));
  }
}
