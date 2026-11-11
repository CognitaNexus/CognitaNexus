package com.cognitanexus.sharedkernel;

/**
 * CSV 行拼装（RFC 4180）。
 *
 * <p>为什么放进共享内核：本平台有两个 CSV 出口（公开检索结果导出、门户「我的成果」导出），
 * 转义规则却只有一份正确答案。各自手写一遍的结果不是「两次都写错」，而是「一处改了另一处没改」——
 * 某天有人在标题里加了逗号，一个出口正常、另一个出口整表错列，而这种错误在下载下来的文件里
 * 看不出来，只有拿 Excel 打开才会发现列错位。
 *
 * <p>规则：
 *
 * <ul>
 *   <li>字段含逗号、双引号、CR 或 LF 时用双引号包裹，字段内的双引号翻倍；
 *   <li>行尾一律 {@code \r\n}（RFC 4180 要求，Excel 也认）；
 *   <li>{@code null} 输出为空字段而不是字面量「null」——把 null 打成字符串会让下游误以为
 *       存在一个名叫 null 的值；
 *   <li>输出带 UTF-8 BOM（{@link #BOM}）：不带 BOM 的 UTF-8 CSV 在中文 Windows 的 Excel 里
 *       会按 GBK 解读，整表中文变乱码，而文件本身没有损坏，很难归因。
 * </ul>
 */
public final class Csv {

  /** UTF-8 BOM。写文件时置于最前，供 Excel 识别编码。 */
  public static final char BOM = '\uFEFF';

  private static final String CRLF = "\r\n";

  private Csv() {}

  /** 新建一个带 BOM 的缓冲区。 */
  public static StringBuilder begin() {
    StringBuilder csv = new StringBuilder();
    csv.append(BOM);
    return csv;
  }

  /** 追加一行。 */
  public static StringBuilder appendRow(StringBuilder csv, String... values) {
    if (values == null) {
      return csv.append(CRLF);
    }
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        csv.append(',');
      }
      appendField(csv, values[i]);
    }
    return csv.append(CRLF);
  }

  /** 追加一个字段（已按需加引号）。 */
  public static StringBuilder appendField(StringBuilder csv, String value) {
    if (value == null || value.isEmpty()) {
      return csv;
    }
    if (value.indexOf(',') >= 0
        || value.indexOf('"') >= 0
        || value.indexOf('\n') >= 0
        || value.indexOf('\r') >= 0) {
      return csv.append('"').append(value.replace("\"", "\"\"")).append('"');
    }
    return csv.append(value);
  }

  /** 转成可下载的字节（UTF-8，含 BOM）。 */
  public static byte[] toBytes(CharSequence csv) {
    return csv == null
        ? new byte[0]
        : csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }
}
