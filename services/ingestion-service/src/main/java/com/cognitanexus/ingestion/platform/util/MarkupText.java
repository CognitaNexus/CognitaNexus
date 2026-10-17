package com.cognitanexus.ingestion.platform.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 来源文本的字符引用（实体）解码与标记剥离。
 *
 * <p>依据：架构 V0.2 §6.2（清洗规则）、BR-IMP-01。收录的是来源提供的原始文本，因此这里
 * <b>不做</b>语义改写，只把来源对特殊字符的编码还原成字符本身。
 *
 * <p>为什么必须有这一步：Crossref 的 {@code title}、{@code container-title}、{@code subject}
 * 是 XML 转义形式，「Computer Speech &amp; Language」就是它对「Computer Speech & Language」的
 * 编码。原样入库会把转义序列当成正文的一部分，产生三类表面看不出原因的错误：
 *
 * <ul>
 *   <li>页面上直接显示 {@code &amp;}，读者看到的是编码而不是名字；
 *   <li>检索「Speech & Language」搜不到，因为库里存的是 {@code &amp;}；
 *   <li>统计里同一份期刊被算成两个不同的名字（带转义的与不带的各一组）。
 * </ul>
 *
 * <p>只解一层：{@code &amp;lt;script&amp;gt;} 解成 {@code &lt;script&gt;} 后仍然是转义形式，
 * 不会因为「多解几层」把一段文本变成可执行标记。展示层另有插值转义，两层合起来才不会出现
 * 「解码把数据变成了标记」这类问题。
 */
public final class MarkupText {

  /** 常见命名实体。只覆盖来源元数据里实际出现的字符引用，不实现通用 HTML 解析。 */
  private static final Map<String, String> NAMED = namedEntities();

  private MarkupText() {}

  private static Map<String, String> namedEntities() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("amp", "&");
    map.put("lt", "<");
    map.put("gt", ">");
    map.put("quot", "\"");
    map.put("apos", "'");
    map.put("nbsp", "\u00a0");
    map.put("ndash", "\u2013");
    map.put("mdash", "\u2014");
    map.put("hellip", "\u2026");
    map.put("rsquo", "\u2019");
    map.put("lsquo", "\u2018");
    map.put("ldquo", "\u201c");
    map.put("rdquo", "\u201d");
    return Map.copyOf(map);
  }

  /**
   * 解码字符引用（一层）。
   *
   * <p>无法识别的引用原样保留：来源可能引用了本表之外的实体，把它删掉等于改写来源文本，
   * 而保留下来至少能被检索到、能被人工看出来。
   */
  public static String decodeEntities(String raw) {
    if (raw == null || raw.indexOf('&') < 0) {
      return raw;
    }
    StringBuilder builder = new StringBuilder(raw.length());
    int index = 0;
    while (index < raw.length()) {
      char ch = raw.charAt(index);
      if (ch != '&') {
        builder.append(ch);
        index++;
        continue;
      }
      int semicolon = raw.indexOf(';', index + 1);
      // 实体长度上限 12：字符引用最长形如 &#x1F600;，超过这个长度说明不是实体，只是正文里的 &。
      if (semicolon < 0 || semicolon - index > 12) {
        builder.append(ch);
        index++;
        continue;
      }
      String body = raw.substring(index + 1, semicolon);
      String decoded = decodeOne(body);
      if (decoded == null) {
        builder.append(ch);
        index++;
        continue;
      }
      builder.append(decoded);
      index = semicolon + 1;
    }
    return builder.toString();
  }

  private static String decodeOne(String body) {
    if (body.isEmpty()) {
      return null;
    }
    if (body.charAt(0) == '#') {
      return decodeNumeric(body.substring(1));
    }
    return NAMED.get(body.toLowerCase(java.util.Locale.ROOT));
  }

  private static String decodeNumeric(String value) {
    try {
      int codePoint =
          value.startsWith("x") || value.startsWith("X")
              ? Integer.parseInt(value.substring(1), 16)
              : Integer.parseInt(value, 10);
      if (codePoint < 0 || codePoint > Character.MAX_CODE_POINT) {
        return null;
      }
      // 拒绝代理区码点：单个代理字符无法独立成字符，返回 null 让原样保留。
      if (codePoint >= 0xD800 && codePoint <= 0xDFFF) {
        return null;
      }
      return new String(Character.toChars(codePoint));
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  /** 剥离标记并解码实体，用于带 JATS/HTML 标签的字段（如 Crossref 摘要）。 */
  public static String plain(String raw) {
    return decodeEntities(stripTags(raw));
  }

  /** 剥离尖括号标记：把标签替换为空格，避免相邻文本被粘成一个词。 */
  public static String stripTags(String raw) {
    if (raw == null) {
      return null;
    }
    return raw.replaceAll("<[^>]+>", " ");
  }
}
