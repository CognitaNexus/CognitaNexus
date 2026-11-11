package com.cognitanexus.core.platform.web;

import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
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
 *
 * <p><b>错误响应不走内容协商，直接写 JSON。</b>这不是风格选择，而是一个实测出来的故障：
 * 下载类接口（`Accept: text/csv`）在业务拒绝时，若让 Spring 按 `Accept` 选转换器，就没有任何
 * 转换器能产出 `text/csv` 的 {@code ApiResponse}，于是抛 {@code HttpMediaTypeNotAcceptableException}，
 * 容器随即做一次 ERROR 派发；而认证过滤器只处理普通派发，ERROR 派发上取不到登录态，最终返回的
 * 是 <b>401「请先登录」</b>——真实原因（403「你不是该门户的管理者」）被一个更早、更无理的状态码
 * 顶掉了。调用方据此去重新登录，问题却永远不会因此解决。
 *
 * <p>直接写也顺带保证了一点：无论客户端声明接受什么媒体类型，失败时总能读到同一种结构的报文。
 * 错误报文是给人和程序两边看的，它不该取决于请求方的 Accept。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private final ObjectMapper objectMapper;

  public GlobalExceptionHandler(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @ExceptionHandler(BusinessException.class)
  public void handleBusiness(
      BusinessException ex, HttpServletRequest request, HttpServletResponse response) {
    log.warn(
        "业务拒绝 operation={} path={} code={} reason={}",
        request.getMethod(),
        request.getRequestURI(),
        ex.errorCode(),
        ex.getMessage());
    write(response, ex.errorCode(), ex.getMessage(), Map.of());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public void handleValidation(
      MethodArgumentNotValidException ex,
      HttpServletRequest request,
      HttpServletResponse response) {
    String detail =
        ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
            .orElse("请求参数不合法");
    log.warn("参数校验失败 path={} detail={}", request.getRequestURI(), detail);
    write(response, ErrorCode.INVALID_ARGUMENT, detail, Map.of());
  }

  @ExceptionHandler({
    HttpMessageNotReadableException.class,
    MissingServletRequestParameterException.class,
    MethodArgumentTypeMismatchException.class,
    IllegalArgumentException.class
  })
  public void handleBadRequest(
      Exception ex, HttpServletRequest request, HttpServletResponse response) {
    log.warn("请求格式错误 path={} type={}", request.getRequestURI(), ex.getClass().getSimpleName());
    write(response, ErrorCode.INVALID_ARGUMENT, "请求格式或参数不合法", Map.of());
  }

  @ExceptionHandler(AccessDeniedException.class)
  public void handleAccessDenied(
      AccessDeniedException ex, HttpServletRequest request, HttpServletResponse response) {
    log.warn("访问被拒绝 path={}", request.getRequestURI());
    write(response, ErrorCode.ACCESS_DENIED, "无权执行该操作", Map.of());
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
    write(response, ErrorCode.NOT_FOUND, "请求的路径不存在，请检查接口地址与版本前缀", Map.of());
  }

  /** 唯一约束冲突转为可解释的 409，而不是把数据库错误直接暴露给调用方。 */
  @ExceptionHandler(DuplicateKeyException.class)
  public void handleDuplicate(
      DuplicateKeyException ex, HttpServletRequest request, HttpServletResponse response) {
    log.warn(
        "唯一约束冲突 path={} message={}",
        request.getRequestURI(),
        ex.getMostSpecificCause().getMessage());
    write(response, ErrorCode.VERSION_CONFLICT, "该对象已存在或与现有状态冲突，请刷新后重试", Map.of());
  }

  @ExceptionHandler(Exception.class)
  public void handleUnexpected(
      Exception ex, HttpServletRequest request, HttpServletResponse response) {
    String traceId = TraceIdHolder.current();
    // 内部受控日志保留堆栈，便于排障；对外只返回追踪标识
    log.error("未预期错误 path={} traceId={}", request.getRequestURI(), traceId, ex);
    write(
        response,
        ErrorCode.INTERNAL_ERROR,
        "服务内部错误，请凭追踪标识联系管理员",
        Map.of("supportReference", traceId));
  }

  /** 直接写出错误报文，绕过内容协商（原因见类注释）。 */
  private void write(
      HttpServletResponse response, ErrorCode code, String message, Map<String, Object> meta) {
    if (response.isCommitted()) {
      // 已经写出去一半的响应无法改造：与其再抛一次异常把真实原因埋掉，不如留下可检索的日志。
      log.warn("响应已提交，错误报文未能写出 code={} message={}", code, message);
      return;
    }
    response.setStatus(code.httpStatus());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    try {
      objectMapper.writeValue(
          response.getOutputStream(),
          ApiResponse.failure(code, message, meta, TraceIdHolder.current()));
      response.flushBuffer();
    } catch (IOException e) {
      log.warn("错误报文写出失败 code={} message={}", code, message, e);
    }
  }
}
