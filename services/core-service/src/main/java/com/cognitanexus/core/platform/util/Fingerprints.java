package com.cognitanexus.core.platform.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 内容指纹。
 *
 * <p>依据：架构 V0.2 §5.3。指纹只由业务字段按固定顺序与固定编码计算，绝不把采集时间、
 * 请求 ID、机器名等易变值算进去；否则每次重试都会产生新指纹，重复导入变成新事实。
 */
public final class Fingerprints {

  private Fingerprints() {}

  /**
   * 按固定字段顺序拼接后取 SHA-256。
   *
   * @param parts 归一化后的字段值；null 统一表示为固定占位符而不是空串，避免「字段缺失」与
   *     「字段为空字符串」得到同一指纹
   */
  public static String of(String... parts) {
    StringBuilder builder = new StringBuilder();
    for (String part : parts) {
      builder.append(part == null ? "\u0000" : part);
      builder.append('\u0001');
    }
    return sha256Hex(builder.toString());
  }

  /** 直接对字节内容取摘要，用于材料等二进制内容，避免先转字符串再编码的额外转换。 */
  public static String sha256Hex(byte[] content) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(content));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 不可用", ex);
    }
  }

  public static String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 不可用", ex);
    }
  }
}
