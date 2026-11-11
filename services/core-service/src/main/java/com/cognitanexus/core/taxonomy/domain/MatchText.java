package com.cognitanexus.core.taxonomy.domain;

/**
 * 匹配用的文本规范化。
 *
 * <p>为什么不能直接做子串包含：判别词里有 dna、rna、mofs、cnc、icu 这样的短词，直接包含会在无关
 * 词里命中——实测「internal」里含「rna」、「Transactional」因此被判成了生物学。反过来，如果改成
 * 严格的整词匹配，catalys、spectroscop、polym 这类故意写成词干的判别词又全都失效。
 *
 * <p>因此采用「按词起始的前缀匹配」：把待匹配文本规范化为「以空格分隔的词序列」，判别词只有在
 * 某个词的<b>起始处</b>出现才算命中。这样：
 *
 * <ul>
 *   <li>{@code rna} 只匹配 {@code rna}/{@code rnase}，不再匹配 {@code inte-rna-l}；
 *   <li>{@code catalys} 命中 {@code catalysis}/{@code catalyst}/{@code catalytic}；
 *   <li>{@code neural network} 命中 {@code neural networks}（末词允许词形变化）；
 *   <li>连字符被当作分隔符，{@code Graph-Neural-Network-Based} 与 {@code graph neural network}
 *       视为同一串词。
 * </ul>
 *
 * <p>中文没有词边界，汉字逐字保留且不加空格，因此中文判别词退化为连续子串匹配——这与中文的
 * 书写方式一致。
 */
public final class MatchText {

  private MatchText() {}

  /**
   * 规范化：转小写，把非字母数字且非汉字的字符替换为单个空格，压缩连续空格，两侧各补一个空格。
   *
   * <p>两侧补空格让「词起始」判定退化为一次 indexOf：ASCII 判别词统一在前面带一个空格去查找即可，
   * 首词位于文本开头的情况也被覆盖。
   */
  public static String normalize(String raw) {
    if (raw == null) {
      return " ";
    }
    StringBuilder out = new StringBuilder(raw.length() + 2);
    out.append(' ');
    boolean pendingSpace = false;
    for (int i = 0; i < raw.length(); i++) {
      char c = Character.toLowerCase(raw.charAt(i));
      if (isWordChar(c)) {
        if (pendingSpace) {
          out.append(' ');
          pendingSpace = false;
        }
        out.append(c);
      } else {
        pendingSpace = true;
      }
    }
    out.append(' ');
    return out.toString();
  }

  /** 判别词的查找串：ASCII 判别词要求在词起始处出现，故前置一个空格；中文判别词直接子串查找。 */
  public static String needle(String normalizedTerm) {
    return isAscii(normalizedTerm) ? " " + normalizedTerm : normalizedTerm;
  }

  public static boolean isAscii(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) > 0x7F) {
        return false;
      }
    }
    return true;
  }

  public static boolean hasCjk(String value) {
    for (int i = 0; i < value.length(); i++) {
      if (isCjk(value.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  private static boolean isWordChar(char c) {
    return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || isCjk(c);
  }

  private static boolean isCjk(char c) {
    return c >= 0x4E00 && c <= 0x9FFF;
  }
}
