package com.cognitanexus.sharedkernel;

import java.util.List;
import java.util.Objects;

/**
 * 业务异常。承载稳定的错误标识与可公开的参数。
 *
 * <p>依据：编码规范 §4.6。参数只允许白名单内的可公开值，禁止把 SQL、路径、凭据或材料内容
 * 作为参数传入。
 */
public class BusinessException extends RuntimeException {

  private final ErrorCode errorCode;
  private final List<String> publicArguments;

  public BusinessException(ErrorCode errorCode, String publicMessage, String... publicArguments) {
    super(Objects.requireNonNull(publicMessage, "publicMessage"));
    this.errorCode = ErrorCode.requireNonBlank(errorCode);
    this.publicArguments = List.of(publicArguments);
  }

  public BusinessException(ErrorCode errorCode, String publicMessage) {
    this(errorCode, publicMessage, new String[0]);
  }

  public ErrorCode errorCode() {
    return errorCode;
  }

  public List<String> publicArguments() {
    return publicArguments;
  }

  public static BusinessException invalidArgument(String message) {
    return new BusinessException(ErrorCode.INVALID_ARGUMENT, message);
  }

  public static BusinessException notFound(String message) {
    return new BusinessException(ErrorCode.NOT_FOUND, message);
  }

  public static BusinessException accessDenied(String message) {
    return new BusinessException(ErrorCode.ACCESS_DENIED, message);
  }

  public static BusinessException versionConflict(String message) {
    return new BusinessException(ErrorCode.VERSION_CONFLICT, message);
  }

  public static BusinessException businessRule(String message) {
    return new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, message);
  }
}
