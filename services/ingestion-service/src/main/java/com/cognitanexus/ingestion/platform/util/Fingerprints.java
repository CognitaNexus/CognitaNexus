package com.cognitanexus.ingestion.platform.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 内容指纹。
 *
 * <p>依据：架构 V0.2 §6.2。指纹由业务字段构成，<b>不得</b>包含抓取时刻、请求标识、主机名等
 * 每次抓取都会变化的值，否则同一份内容每次抓取都会得到一个新指纹，稳定交接键退化为
 * 「每次抓取都算新记录」，跨源重复检测与幂等重试同时失效。
 */
public final class Fingerprints {

  /** 空值占位：与空串区分，避免「字段缺失」和「字段为空」算出同一个指纹。 */
  private static final String NULL_MARKER = "\u0000";

  private static final String SEPARATOR = "\u0001";

  private Fingerprints() {}

  public static String of(String... parts) {
    StringBuilder builder = new StringBuilder();
    if (parts != null) {
      for (String part : parts) {
        if (builder.length() > 0) {
          builder.append(SEPARATOR);
        }
        builder.append(part == null ? NULL_MARKER : part);
      }
    }
    return sha256Hex(builder.toString());
  }

  public static String sha256Hex(byte[] input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(input));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 不可用", ex);
    }
  }

  public static String sha256Hex(String input) {
    return sha256Hex(input.getBytes(StandardCharsets.UTF_8));
  }
}
