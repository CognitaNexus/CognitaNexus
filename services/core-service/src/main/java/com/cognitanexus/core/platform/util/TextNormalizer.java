package com.cognitanexus.core.platform.util;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 文本归一。
 *
 * <p>依据：架构 V0.2 §5.3、编码规范 §4.5。归一化版本必须固定，否则同一份数据在不同版本下
 * 会产生不同指纹，重复导入会被误判为新事实（AC-IMPL-FR09-03、A12）。
 *
 * <p>当前归一版本 NORM_V1：
 *
 * <ol>
 *   <li>NFKC 兼容分解，使全角与半角、带连字符的字符归为同一形态；
 *   <li>去除 Unicode 组合记号（变音符号），使「Zürich」与「Zurich」一致；
 *   <li>小写化，使用 ROOT 区域而非默认区域，避免土耳其语 I 问题；
 *   <li>把各类空白与不可见分隔符压成单个半角空格并去首尾。
 * </ol>
 */
public final class TextNormalizer {

  /** 归一化规则版本。改变本值会使既有指纹失效，必须与迁移同步。 */
  public static final String VERSION = "NORM_V1";

  private TextNormalizer() {}

  public static String normalize(String raw) {
    if (raw == null) {
      return null;
    }
    String value = Normalizer.normalize(raw, Normalizer.Form.NFKC);
    value = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    value = value.toLowerCase(Locale.ROOT);
    value = value.replaceAll("[\\s\\u00A0\\u200B-\\u200D\\uFEFF]+", " ").trim();
    return value;
  }

  /**
   * 稳定标识归一。
   *
   * <p>DOI 与专利号等标识允许出现在 URL 或带前缀形态中，这里只保留可用于匹配的核心字符；
   * 归一不改变原始值，原值仍单独保留（架构 §5.4：原值不得被覆盖）。
   */
  public static String normalizeStableIdentifier(String raw) {
    if (raw == null) {
      return null;
    }
    String value = raw.trim().toLowerCase(Locale.ROOT);
    int marker = value.indexOf("10.");
    if (marker > 0 && (value.startsWith("http") || value.contains("doi"))) {
      value = value.substring(marker);
    }
    value = value.replaceAll("^(doi:|https?://(dx\\.)?doi\\.org/|doi\\.org/)", "");
    value = value.replaceAll("[\\s\\u00A0]+", "");
    return value.isEmpty() ? null : value;
  }
}
