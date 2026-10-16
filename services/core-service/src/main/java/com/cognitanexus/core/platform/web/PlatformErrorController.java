package com.cognitanexus.core.platform.web;

import com.cognitanexus.sharedkernel.ErrorCode;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 容器级错误兜底。
 *
 * <p>依据：架构 V0.2 §10.3（统一响应契约）、编码规范 §4.6。
 *
 * <p>为什么需要它：{@code @RestControllerAdvice} 只能拦截派发到控制器之后抛出的异常。发生在
 * 过滤器链中（认证、CSRF、服务令牌）或派发之前的错误由 Servlet 容器接管，默认返回 Tomcat
 * 自带的 HTML 错误页。结果是同一套接口在失败时出现两种截然不同的响应形态：前端无法按 code
 * 分支处理，500 的正文里也没有任何可定位的线索。
 *
 * <p>本控制器接管容器的 /error 端点，把这类错误重新表达为统一外壳，并且：
 *
 * <ul>
 *   <li>对外只给出稳定的 code 与一句概述，不泄漏异常类名、SQL、堆栈或内部路径；
 *   <li>把 traceId 一并返回，值班人员可用它在服务日志里定位完整堆栈；
 *   <li>日志中记录原始异常与请求信息，保证排障不受「对外含糊」影响。
 * </ul>
 */
@RestController
public class PlatformErrorController implements ErrorController {

  private static final Logger log = LoggerFactory.getLogger(PlatformErrorController.class);

  @RequestMapping("${server.error.path:${error.path:/error}}")
  public ResponseEntity<ApiResponse<Void>> handleError(HttpServletRequest request) {
    Object statusAttribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    int status = statusAttribute instanceof Integer code ? code : 500;

    Throwable cause = (Throwable) request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
    String path = (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
    String traceId = TraceIdHolder.current();

    ErrorCode errorCode = mapStatus(status);
    if (status >= 500) {
      log.error(
          "容器级错误 traceId={} status={} path={} cause={}",
          traceId,
          status,
          path,
          cause == null ? "(无异常对象)" : cause.toString(),
          cause);
    } else {
      log.warn("请求未能完成 traceId={} status={} path={}", traceId, status, path);
    }

    return ResponseEntity.status(status)
        .body(ApiResponse.failure(errorCode, publicMessage(errorCode), traceId));
  }

  private static ErrorCode mapStatus(int status) {
    return switch (status) {
      case 400, 405 -> ErrorCode.INVALID_ARGUMENT;
      case 401 -> ErrorCode.AUTH_REQUIRED;
      case 403 -> ErrorCode.ACCESS_DENIED;
      case 404 -> ErrorCode.NOT_FOUND;
      case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
      case 429 -> ErrorCode.RATE_LIMITED;
      case 503 -> ErrorCode.DEPENDENCY_UNAVAILABLE;
      default -> status >= 500 ? ErrorCode.INTERNAL_ERROR : ErrorCode.INVALID_ARGUMENT;
    };
  }

  private static String publicMessage(ErrorCode code) {
    return switch (code) {
      case AUTH_REQUIRED -> "请先登录";
      case ACCESS_DENIED -> "无权执行该操作";
      case NOT_FOUND -> "请求的资源不存在";
      case RATE_LIMITED -> "请求过于频繁，请稍后再试";
      case PAYLOAD_TOO_LARGE -> "请求内容过大";
      case DEPENDENCY_UNAVAILABLE -> "依赖服务暂不可用，请稍后重试";
      case INTERNAL_ERROR -> "服务内部错误，请稍后重试或联系管理员";
      default -> "请求无法处理";
    };
  }
}
