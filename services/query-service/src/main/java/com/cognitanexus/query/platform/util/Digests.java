package com.cognitanexus.query.platform.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 摘要计算。
 *
 * <p>依据：架构 V0.2 §9.1。检索的 queryVersion 由「规范化条件摘要 + 发布版本」构成：只有条件摘要
 * 与发布版本都相同，才允许继续翻页。若只带页码而不带条件摘要，前端改了过滤条件却继续用旧
 * queryVersion 翻页时，服务端无法察觉，就会把两套条件的结果混在同一个列表里。
 */
public final class Digests {

  private static final String SEPARATOR = "\u0001";

  private Digests() {}

  /** 以不可歧义的方式连接各段后取摘要：用分隔符拼接，避免 ["ab","c"] 与 ["a","bc"] 撞到一起。 */
  public static String of(String... parts) {
    StringBuilder builder = new StringBuilder();
    if (parts != null) {
      for (String part : parts) {
        if (builder.length() > 0) {
          builder.append(SEPARATOR);
        }
        builder.append(part == null ? "" : part);
      }
    }
    return sha256Hex(builder.toString());
  }

  public static String sha256Hex(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 不可用", ex);
    }
  }

  /** 取短摘要，用于缓存键与 queryVersion 这类需要可读性的场合。 */
  public static String shortOf(String... parts) {
    return of(parts).substring(0, 16);
  }
}
