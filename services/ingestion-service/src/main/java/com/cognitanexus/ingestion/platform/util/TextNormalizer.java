package com.cognitanexus.ingestion.platform.util;

import java.util.Locale;

/**
 * 文本规范化与指纹计算。
 *
 * <p>依据：架构 V0.2 §6.2、编码规范 §5.4。规范化算法带版本号：一旦规则变化，旧指纹与新指纹
 * 不能混用，必须能凭版本判断历史数据是用哪一版算出来的。
 */
public final class TextNormalizer {

  /** 规范化算法版本。修改规则时必须提升版本，否则历史指纹的语义无法区分。 */
  public static final String VERSION = "NORM_V1";

  private TextNormalizer() {}

  /** 业务文本规范化：NFKC、去除组合记号、转小写、折叠空白与不可见分隔符。 */
  public static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String text =
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT);
    StringBuilder builder = new StringBuilder(text.length());
    boolean lastWasSpace = false;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (Character.getType(ch) == Character.NON_SPACING_MARK) {
        continue;
      }
      if (Character.isWhitespace(ch) || ch == '\u200b' || ch == '\ufeff' || ch == '\u3000') {
        if (!lastWasSpace && builder.length() > 0) {
          builder.append(' ');
          lastWasSpace = true;
        }
        continue;
      }
      builder.append(ch);
      lastWasSpace = false;
    }
    return builder.toString().trim();
  }

  /**
   * 稳定标识规范化：小写、去空白、剥离常见前缀。
   *
   * <p>DOI 常见写法有 {@code 10.1000/xyz}、{@code doi:10.1000/xyz}、
   * {@code https://doi.org/10.1000/xyz} 三种，若不归一会把同一篇论文登记成两个标识。
   * 返回 null 表示输入无有效内容，调用方据此判断「没有稳定标识」而不是「标识为空串」。
   */
  public static String normalizeStableIdentifier(String value) {
    if (value == null) {
      return null;
    }
    String text = value.trim().toLowerCase(Locale.ROOT);
    if (text.startsWith("https://doi.org/")) {
      text = text.substring("https://doi.org/".length());
    } else if (text.startsWith("http://doi.org/")) {
      text = text.substring("http://doi.org/".length());
    } else if (text.startsWith("doi:")) {
      text = text.substring("doi:".length());
    }
    text = text.replaceAll("\\s+", "");
    return text.isEmpty() ? null : text;
  }
}
