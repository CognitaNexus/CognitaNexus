package com.cognitanexus.core.platform.security;

/**
 * 当前请求的操作者上下文持有者。
 *
 * <p>由认证过滤器写入、请求结束后清除。应用层通过本类取得可信操作者，不从 DTO 读取身份。
 */
public final class ActorContextHolder {

  private static final ThreadLocal<ActorContext> HOLDER = new ThreadLocal<>();

  private ActorContextHolder() {}

  public static void set(ActorContext actor) {
    HOLDER.set(actor);
  }

  public static ActorContext get() {
    return HOLDER.get();
  }

  /** 取当前操作者，未认证返回 null。 */
  public static ActorContext currentOrNull() {
    return HOLDER.get();
  }

  public static void clear() {
    HOLDER.remove();
  }
}
