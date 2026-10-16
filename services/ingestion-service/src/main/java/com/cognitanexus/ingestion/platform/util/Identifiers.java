package com.cognitanexus.ingestion.platform.util;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 业务标识生成。
 *
 * <p>依据：编码规范 §5.3。主键使用带前缀的随机串而不是自增序列：跨服务交接时需要能就地生成、
 * 不依赖数据库往返，且前缀让日志与人工排查一眼看出实体种类。碰撞概率按 26 个十六进制字符
 * （104 位随机）计，远低于本系统的实体规模。
 */
public final class Identifiers {

  private static final SecureRandom RANDOM = new SecureRandom();

  private Identifiers() {}

  public static String next(String prefix) {
    byte[] bytes = new byte[13];
    RANDOM.nextBytes(bytes);
    return prefix + "_" + HexFormat.of().formatHex(bytes);
  }

  public static String batch() {
    return next("hbt");
  }
}
