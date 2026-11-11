package com.cognitanexus.core.platform.util;

import java.util.UUID;

/**
 * 标识生成。
 *
 * <p>依据：编码规范 §4.5、架构 §9.3。所有对外标识使用字符串；数据库主键由应用生成，
 * 便于在同一事务内先写关联行再提交，不依赖数据库序列的可见性。
 *
 * <p>前缀用于人工排查时区分实体种类，不承载业务含义，也不用作排序依据。
 */
public final class Identifiers {

  private Identifiers() {}

  public static String next(String prefix) {
    return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 26);
  }

  public static String event() {
    return next("evt");
  }
}
