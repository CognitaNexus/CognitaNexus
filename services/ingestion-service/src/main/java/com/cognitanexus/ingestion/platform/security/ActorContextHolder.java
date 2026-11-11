package com.cognitanexus.ingestion.platform.security;

import java.util.Optional;

/**
 * 操作者上下文的请求级持有者。
 *
 * <p>依据：编码规范 §4.4。使用 ThreadLocal 且必须在请求结束时清理，否则线程复用会把一个请求的
 * 身份泄漏给下一个请求。清理在过滤器的 finally 中完成。
 */
public final class ActorContextHolder {

  private static final ThreadLocal<ActorContext> CURRENT = new ThreadLocal<>();

  private ActorContextHolder() {}

  public static void set(ActorContext actor) {
    CURRENT.set(actor);
  }

  public static ActorContext currentOrNull() {
    return CURRENT.get();
  }

  public static Optional<ActorContext> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  public static void clear() {
    CURRENT.remove();
  }
}
