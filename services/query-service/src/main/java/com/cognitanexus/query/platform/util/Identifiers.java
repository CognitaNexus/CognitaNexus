package com.cognitanexus.query.platform.util;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 业务标识生成。
 *
 * <p>依据：编码规范 §5.3。投影侧需要生成的标识只有 Inbox 事件行与重建运行记录，两者都不与 core
 * 的标识空间相撞，因此沿用与本服务之外的同一前缀风格，便于排查时区分来源。
 */
public final class Identifiers {

  private static final SecureRandom RANDOM = new SecureRandom();

  private Identifiers() {}

  public static String next(String prefix) {
    byte[] bytes = new byte[13];
    RANDOM.nextBytes(bytes);
    return prefix + "_" + HexFormat.of().formatHex(bytes);
  }
}
