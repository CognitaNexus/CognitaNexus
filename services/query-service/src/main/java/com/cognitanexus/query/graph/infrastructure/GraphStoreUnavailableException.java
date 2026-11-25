package com.cognitanexus.query.graph.infrastructure;

/**
 * 图库访问失败。
 *
 * <p>上层据此决定「降级回 PostgreSQL」而不是把空结果当成真实答案。继承 {@link RuntimeException}
 * 是有意的：图投影是可选读优化，不应把 checked 异常扩散到每个调用点；但捕获必须显式，
 * 不允许被宽泛的 {@code catch (Exception)} 吞掉后返回空图。
 */
public class GraphStoreUnavailableException extends RuntimeException {

  public GraphStoreUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }

  public GraphStoreUnavailableException(String message) {
    super(message);
  }
}
