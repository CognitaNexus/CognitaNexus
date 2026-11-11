package com.cognitanexus.query.platform.util;

import java.util.Locale;

/**
 * 文本规范化。
 *
 * <p>依据：架构 V0.2 §9.1。检索要对用户输入做归一化后再与投影中的 title_norm 比较。
 *
 * <p><b>与 core 的算法必须逐字节一致</b>：投影里的 title_norm 是 core 用同一套规则算出来的，
 * 如果本服务的归一化规则不同（哪怕只差一个大小写折叠或空白处理），关键词检索就会静默漏结果——
 * 表现为「明明有这条成果，搜标题却搜不到」，且从响应上完全看不出原因。因此这里不重写规则，
 * 而是逐条照搬并保留版本号：任何一侧修改都必须提升 {@link #VERSION} 并触发全量重建。
 */
public final class TextNormalizer {

  /** 归一化算法版本。与 core 的 NORM_V1 必须同步变更，否则检索结果不可比。 */
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

  /** 稳定标识规范化：小写、去空白、剥离常见前缀，使 DOI 的多种写法落到同一个键。 */
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
