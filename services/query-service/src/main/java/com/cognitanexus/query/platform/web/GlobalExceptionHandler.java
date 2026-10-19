package com.cognitanexus.query.platform.web;

import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
 * <p>依据：架构 V0.2 §10.3、编码规范 §4.6。错误响应不含 SQL、堆栈、文件路径或凭据。
 *
 * <p><b>错误报文直接写出，不走内容协商。</b>本服务有下载类接口（检索结果 CSV，客户端会带
 * {@code Accept: text/csv}）。若让 Spring 按 Accept 选转换器，拒绝时没有任何转换器能产出
 * {@code text/csv} 的 {@code ApiResponse}，于是抛 406 并触发一次 ERROR 派发——最终调用方看到的是
 * 一个与真实原因无关的状态码，而真实原因（例如「命中超过导出上限，请缩小条件」）恰恰是它最需要
 * 读到的那句话。直接写也保证：无论 Accept 是什么，失败时总能拿到同一种结构的报文。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private final ObjectMapper objectMapper;

  public GlobalExceptionHandler(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @ExceptionHandler(BusinessException.class)
  public void handleBusiness(BusinessException ex, HttpServletResponse response) {
    ErrorCode code = ex.errorCode();
    if (code == ErrorCode.INTERNAL_ERROR) {
      log.error("业务异常被标记为内部错误 code={}", code, ex);
    } else if (code.retryable()) {
      log.warn("请求暂时无法完成 code={} message={}", code, ex.getMessage());
    } else {
      log.info("请求被业务规则拒绝 code={} message={}", code, ex.getMessage());
    }
    write(response, code, ex.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public void handleValidation(MethodArgumentNotValidException ex, HttpServletResponse response) {
    List<String> fields =
        ex.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .toList();
    String message = fields.isEmpty() ? "请求参数不合法" : String.join("；", fields);
    write(response, ErrorCode.INVALID_ARGUMENT, message);
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class,
    IllegalArgumentException.class
  })
  public void handleMalformed(Exception ex, HttpServletResponse response) {
    log.info("请求格式不合法 message={}", ex.getMessage());
    write(response, ErrorCode.INVALID_ARGUMENT, "请求格式不合法，请检查字段与取值");
  }

  /**
   * 路径不存在。
   *
   * <p>Spring 6.1 起，未匹配任何处理器的请求抛出 {@link NoResourceFoundException}，而不是 404
   * 直接返回。若不单独处理，它会被下面的兜底分支折叠成 500：调用方把「路径写错了」读成「服务器
   * 坏了」，排查方向从一开始就是错的；每次探测还会在日志里留下一条 ERROR 堆栈，把真正的故障淹没。
   */
  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  public void handleNotFound(Exception ex, HttpServletResponse response) {
    log.debug("路径不存在 message={}", ex.getMessage());
    write(response, ErrorCode.NOT_FOUND, "请求的路径不存在，请检查接口地址与版本前缀");
  }

  @ExceptionHandler(DuplicateKeyException.class)
  public void handleDuplicate(DuplicateKeyException ex, HttpServletResponse response) {
    log.warn("唯一约束冲突 message={}", ex.getMostSpecificCause().getMessage());
    write(response, ErrorCode.VERSION_CONFLICT, "该对象已被并发修改或已存在，请刷新后重试");
  }

  @ExceptionHandler(Exception.class)
  public void handleUnexpected(Exception ex, HttpServletResponse response) {
    String traceId = TraceIdHolder.current();
    log.error("未预期异常 traceId={}", traceId, ex);
    write(response, ErrorCode.INTERNAL_ERROR, "服务内部错误，请稍后重试或联系管理员（traceId=" + traceId + "）");
  }

  /** 直接写出错误报文，绕过内容协商（原因见类注释）。 */
  private void write(HttpServletResponse response, ErrorCode code, String message) {
    if (response.isCommitted()) {
      log.warn("响应已提交，错误报文未能写出 code={} message={}", code, message);
      return;
    }
    response.setStatus(code.httpStatus());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    try {
      objectMapper.writeValue(
          response.getOutputStream(), ApiResponse.failure(code, message, TraceIdHolder.current()));
      response.flushBuffer();
    } catch (IOException e) {
      log.warn("错误报文写出失败 code={} message={}", code, message, e);
    }
  }
}
