package com.cognitanexus.core.portal.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BibTeX / RIS 文本解析（纯本地，不联网、不引第三方库）。
 *
 * <p>依据：需求基线「学者自主提交成果」的补录场景。学者手上通常就是导出的 .bib 或 .ris，
 * 让人一条条手抄进表单是把平台的成本转嫁给用户。
 *
 * <p>三条实现边界，都是踩过的坑：
 *
 * <ol>
 *   <li><b>按字符扫描，不用正则切条目。</b>BibTeX 的 value 里可以有花括号（{Some {Nested}
 *       Title}）、逗号、甚至 @——用 {@code @\w+\{.*?\}} 这种正则切，嵌套花括号会提前截断，
 *       结果是「标题只解析到一半、后面的字段全丢」。这里按括号层级扫到匹配的闭合为止。
 *   <li><b>解析失败不抛异常、不中断整批。</b>一个 .bib 里混进一条残缺记录是常态。抛异常
 *       会让用户为了 1 条坏数据重来整批；这里记为「跳过 + 原因」，其余照常。
 *   <li><b>只做可逆的清洗。</b>去掉 LaTeX 转义（\&amp; → &amp;）与常见重音符（{\"o} → ö）
 *       是为了让标题能正常显示和检索；但不去掉「副标题」「版本号」这类内容，也不改写作者顺序
 *       ——那些是作者的事，解析器替人做决定就是伪造数据。
 * </ol>
 */
public final class BibliographyParser {

  private BibliographyParser() {}

  /** 支持的格式。 */
  public enum Format {
    BIBTEX,
    RIS
  }

  /**
   * 解析出的一条文献。
   *
   * @param sourceType 原始类型标记（BibTeX 的 entry type / RIS 的 TY 值），用于给用户解释
   * @param supported 是否为平台当前接受提交的类型；false 的条目会被跳过而不是硬塞成论文
   * @param warnings 非致命问题（摘要被截断、年份格式异常等），随结果回给用户
   */
  public record Entry(
      String sourceType,
      String citationKey,
      String title,
      String authorsRaw,
      Integer year,
      String venue,
      String doi,
      String abstractText,
      boolean supported,
      List<String> warnings) {}

  /** 一次解析的整体结果。skipped 里是「为什么没进 entries」的说明，条数与原始记录数对齐。 */
  public record ParseResult(Format format, List<Entry> entries, List<String> skipped) {}

  /** 单次导入的条目上限。超过就拒绝：一次贴 5000 条进审核队列，审核员会直接放弃这个功能。 */
  public static final int MAX_ENTRIES = 200;

  private static final int MAX_TITLE = 500;
  private static final int MAX_VENUE = 300;
  private static final int MAX_ABSTRACT = 5000;
  private static final int MAX_AUTHORS = 1000;

  /** 按内容特征判格式：BibTeX 一定有 @type{，RIS 一定有 "TY  - "。 */
  public static Format detect(String content) {
    if (content == null) {
      return Format.BIBTEX;
    }
    String head = content.length() > 4000 ? content.substring(0, 4000) : content;
    if (head.contains("@") && head.contains("{")) {
      return Format.BIBTEX;
    }
    if (Pattern.compile("(?m)^TY\\s{2}-").matcher(head).find()) {
      return Format.RIS;
    }
    // 都认不出时按 BibTeX 试一次：它的解析失败会落到「0 条有效记录」，
    // 用户看到的是「没有识别到任何条目」，比抛一个格式异常更容易自查。
    return Format.BIBTEX;
  }

  public static ParseResult parse(String content, Format hint) {
    String text = content == null ? "" : content;
    Format format = hint == null ? detect(text) : hint;
    return format == Format.RIS ? parseRis(text) : parseBibtex(text);
  }

  // ---------------------------------------------------------------------------
  // BibTeX
  // ---------------------------------------------------------------------------

  private static ParseResult parseBibtex(String text) {
    List<Entry> entries = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    int i = 0;
    int n = text.length();
    while (i < n) {
      int at = text.indexOf('@', i);
      if (at < 0) {
        break;
      }
      int cursor = at + 1;
      while (cursor < n && Character.isLetter(text.charAt(cursor))) {
        cursor++;
      }
      String type = text.substring(at + 1, cursor).trim().toLowerCase(Locale.ROOT);
      while (cursor < n && Character.isWhitespace(text.charAt(cursor))) {
        cursor++;
      }
      if (type.isEmpty()
          || cursor >= n
          || (text.charAt(cursor) != '{' && text.charAt(cursor) != '(')) {
        i = at + 1;
        continue;
      }
      char open = text.charAt(cursor);
      char close = open == '{' ? '}' : ')';
      int bodyStart = cursor + 1;
      int bodyEnd = matchClose(text, bodyStart, open, close);
      if (bodyEnd < 0) {
        skipped.add("第 " + (at + 1) + " 字符处的 @" + type + " 缺少闭合括号，已跳过");
        break;
      }
      String body = text.substring(bodyStart, bodyEnd);
      i = bodyEnd + 1;

      // @comment / @preamble / @string 不是文献条目：@string 还会定义缩写，这里不展开，
      // 展开意味着要维护一份宏表，而绝大多数导出文件里 @string 用得很少。
      if (type.equals("comment") || type.equals("preamble")) {
        continue;
      }
      if (type.equals("string")) {
        skipped.add("@string 宏定义不导入（可用本地替换后再提交）");
        continue;
      }

      int comma = firstTopLevelComma(body);
      String key = (comma < 0 ? body : body.substring(0, comma)).trim();
      String fieldsText = comma < 0 ? "" : body.substring(comma + 1);
      Map<String, String> fields = parseBibtexFields(fieldsText);
      Entry entry = toEntry(type, key, fields, skipped);
      if (entry != null) {
        if (entries.size() >= MAX_ENTRIES) {
          skipped.add("条目超过单次上限 " + MAX_ENTRIES + " 条，剩余部分未解析");
          break;
        }
        entries.add(entry);
      }
    }
    return new ParseResult(Format.BIBTEX, entries, skipped);
  }

  /** 从 start 起找到与 open 匹配的 close，跳过嵌套。返回 close 的下标；找不到返回 -1。 */
  private static int matchClose(String text, int start, char open, char close) {
    int depth = 0;
    boolean escaped = false;
    for (int i = start; i < text.length(); i++) {
      char c = text.charAt(i);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (c == '\\') {
        escaped = true;
        continue;
      }
      if (c == open) {
        depth++;
      } else if (c == close) {
        if (depth == 0) {
          return i;
        }
        depth--;
      }
    }
    return -1;
  }

  private static int firstTopLevelComma(String body) {
    int depth = 0;
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
      } else if (c == ',' && depth == 0) {
        return i;
      }
    }
    return -1;
  }

  private static Map<String, String> parseBibtexFields(String text) {
    Map<String, String> fields = new LinkedHashMap<>();
    int i = 0;
    int n = text.length();
    while (i < n) {
      while (i < n && (Character.isWhitespace(text.charAt(i)) || text.charAt(i) == ',')) {
        i++;
      }
      int nameStart = i;
      while (i < n
          && (Character.isLetterOrDigit(text.charAt(i))
              || text.charAt(i) == '_'
              || text.charAt(i) == '-')) {
        i++;
      }
      if (i == nameStart) {
        break;
      }
      String name = text.substring(nameStart, i).toLowerCase(Locale.ROOT);
      while (i < n && Character.isWhitespace(text.charAt(i))) {
        i++;
      }
      if (i >= n || text.charAt(i) != '=') {
        // 没有等号说明这一段的语法已经不成立，跳到下一个逗号继续，避免死循环。
        while (i < n && text.charAt(i) != ',') {
          i++;
        }
        continue;
      }
      i++;
      while (i < n && Character.isWhitespace(text.charAt(i))) {
        i++;
      }
      if (i >= n) {
        break;
      }
      char c = text.charAt(i);
      String value;
      if (c == '{') {
        int end = matchClose(text, i + 1, '{', '}');
        if (end < 0) {
          break;
        }
        value = text.substring(i + 1, end);
        i = end + 1;
      } else if (c == '"') {
        int end = i + 1;
        boolean escaped = false;
        while (end < n) {
          char ch = text.charAt(end);
          if (escaped) {
            escaped = false;
          } else if (ch == '\\') {
            escaped = true;
          } else if (ch == '"') {
            break;
          }
          end++;
        }
        value = text.substring(i + 1, Math.min(end, n));
        i = Math.min(end + 1, n);
      } else {
        int end = i;
        while (end < n && text.charAt(end) != ',' && text.charAt(end) != '\n') {
          end++;
        }
        value = text.substring(i, end).trim();
        i = end;
      }
      // 同名多次出现（少数导出器会重复写 keywords）保留第一次，不拼接：
      // 拼出来的值不是任何来源字段的原样内容。
      fields.putIfAbsent(name, value);
    }
    return fields;
  }

  private static Entry toEntry(
      String type, String key, Map<String, String> fields, List<String> skipped) {
    List<String> warnings = new ArrayList<>();
    String rawTitle = first(fields, "title");
    String title = cleanLatex(rawTitle);
    if (title == null || title.isBlank()) {
      skipped.add("@" + type + "{" + key + "} 没有 title，已跳过");
      return null;
    }
    if (title.length() > MAX_TITLE) {
      skipped.add("@" + type + "{" + key + "} 的标题超过 " + MAX_TITLE + " 字，已跳过");
      return null;
    }
    String authors = cleanLatex(first(fields, "author"));
    if (authors != null && authors.length() > MAX_AUTHORS) {
      authors = authors.substring(0, MAX_AUTHORS);
      warnings.add("作者署名超过 " + MAX_AUTHORS + " 字，已截断");
    }
    String venue =
        cleanLatex(first(fields, "journal", "booktitle", "journaltitle", "publisher", "school"));
    if (venue != null && venue.length() > MAX_VENUE) {
      venue = venue.substring(0, MAX_VENUE);
      warnings.add("发表载体超过 " + MAX_VENUE + " 字，已截断");
    }
    String abstractText = cleanLatex(first(fields, "abstract"));
    if (abstractText != null && abstractText.length() > MAX_ABSTRACT) {
      skipped.add("@" + type + "{" + key + "} 的摘要超过 " + MAX_ABSTRACT + " 字，已跳过");
      return null;
    }
    Integer year = parseYear(first(fields, "year", "date"));
    if (year == null && first(fields, "year", "date") != null) {
      warnings.add("年份无法识别，已留空");
    }
    String doi = cleanDoi(first(fields, "doi"));
    boolean supported = isSupportedBibtexType(type, warnings);
    return new Entry(
        type, key, title, authors, year, venue, doi, abstractText, supported, warnings);
  }

  private static boolean isSupportedBibtexType(String type, List<String> warnings) {
    switch (type) {
      case "article",
      "inproceedings",
      "conference",
      "book",
      "inbook",
      "incollection",
      "phdthesis",
      "mastersthesis",
      "techreport",
      "unpublished",
      "misc",
      "online":
        return true;
      case "patent":
        warnings.add("专利类型的条目暂不支持自助提交，已跳过（请在治理端录入）");
        return false;
      default:
        warnings.add("未识别的条目类型 @" + type + "，已按论文处理");
        return true;
    }
  }

  // ---------------------------------------------------------------------------
  // RIS
  // ---------------------------------------------------------------------------

  private static final Pattern RIS_TAG = Pattern.compile("^([A-Z][A-Z0-9])\\s{2}-\\s?(.*)$");

  private static ParseResult parseRis(String text) {
    List<Entry> entries = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    Map<String, List<String>> current = new LinkedHashMap<>();
    String lastTag = null;
    String[] lines = text.split("\\r?\\n", -1);
    for (String raw : lines) {
      String line = raw;
      Matcher m = RIS_TAG.matcher(line);
      if (m.matches()) {
        String tag = m.group(1);
        String value = m.group(2);
        if (tag.equals("TY")) {
          current.clear();
          lastTag = null;
        }
        if (tag.equals("ER")) {
          Entry entry = toRisEntry(current, skipped);
          if (entry != null) {
            entries.add(entry);
          }
          current.clear();
          lastTag = null;
          continue;
        }
        current.computeIfAbsent(tag, k -> new ArrayList<>()).add(value);
        lastTag = tag;
        continue;
      }
      // 续行：RIS 规定以空格开头；有些导出器直接换行不断缩进，这里也当作续行，
      // 否则长摘要会被切成「后半段变成一条无标签的垃圾行」而丢失。
      if (lastTag != null && !line.isBlank()) {
        List<String> values = current.get(lastTag);
        if (values != null && !values.isEmpty()) {
          values.set(values.size() - 1, values.get(values.size() - 1) + " " + line.trim());
        }
      }
    }
    // 文件末尾没有 ER 时补收最后一条：手改的 .ris 经常漏掉结尾标记。
    if (!current.isEmpty()) {
      Entry entry = toRisEntry(current, skipped);
      if (entry != null) {
        entries.add(entry);
      }
    }
    return new ParseResult(Format.RIS, entries, skipped);
  }

  private static Entry toRisEntry(Map<String, List<String>> fields, List<String> skipped) {
    List<String> warnings = new ArrayList<>();
    String type = join(fields, "TY");
    String title = cleanLatex(join(fields, "TI", "T1", "BT"));
    if (title == null || title.isBlank()) {
      if (!fields.isEmpty()) {
        skipped.add("一条 RIS 记录没有 TI，已跳过");
      }
      return null;
    }
    if (title.length() > MAX_TITLE) {
      skipped.add("标题超过 " + MAX_TITLE + " 字的 RIS 记录已跳过");
      return null;
    }
    String authors = cleanLatex(join(fields, "AU", "A1"));
    if (authors != null && authors.length() > MAX_AUTHORS) {
      authors = authors.substring(0, MAX_AUTHORS);
      warnings.add("作者署名超过 " + MAX_AUTHORS + " 字，已截断");
    }
    String venue = cleanLatex(join(fields, "JO", "JF", "T2", "JA"));
    if (venue != null && venue.length() > MAX_VENUE) {
      venue = venue.substring(0, MAX_VENUE);
      warnings.add("发表载体超过 " + MAX_VENUE + " 字，已截断");
    }
    String abstractText = cleanLatex(join(fields, "AB", "N2"));
    if (abstractText != null && abstractText.length() > MAX_ABSTRACT) {
      skipped.add("摘要超过 " + MAX_ABSTRACT + " 字的 RIS 记录已跳过");
      return null;
    }
    Integer year = parseYear(join(fields, "PY", "Y1", "DA"));
    String doi = cleanDoi(join(fields, "DO"));
    boolean supported = true;
    if (type != null && type.toUpperCase(Locale.ROOT).startsWith("PAT")) {
      warnings.add("专利类型的条目暂不支持自助提交，已跳过（请在治理端录入）");
      supported = false;
    }
    return new Entry(
        type,
        join(fields, "ID"),
        title,
        authors,
        year,
        venue,
        doi,
        abstractText,
        supported,
        warnings);
  }

  /** 多值字段（AU 可重复）用 "；" 连接。用分号而不是逗号：作者名里本身可能有逗号。 */
  private static String join(Map<String, List<String>> fields, String... tags) {
    for (String tag : tags) {
      List<String> values = fields.get(tag);
      if (values != null && !values.isEmpty()) {
        String joined = String.join("; ", values).trim();
        return joined.isEmpty() ? null : joined;
      }
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // 公共清洗
  // ---------------------------------------------------------------------------

  private static String first(Map<String, String> fields, String... names) {
    for (String name : names) {
      String value = fields.get(name);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  /**
   * LaTeX → 可读文本。只处理「一定会出现且含义确定」的转义：
   *
   * <ul>
   *   <li>转义符号 \&amp; \% \$ \# \_ \{ \}
   *   <li>常见带重音的拉丁字母（{\"o}、{\'e}、{\`a}、{\^u}、{\~n}、{\c{c}}）
   *   <li>数学模式外层 $...$ 与 \emph{} \textit{} 这类纯格式命令
   *   <li>用于保护大小写与词序的分组花括号（{Protein}、{Alpha, Beta}）
   * </ul>
   *
   * <p>不做的：不做完整 TeX 展开（\LaTeX、\cite、自定义宏），也不删除副标题。遇到不认识的
   * 命令原样保留——留着看得见，删掉就永久丢失。
   *
   * <p>处理顺序不能改：命令与转义符号都要靠花括号定位，所以必须先处理它们，最后才把剩余
   * 花括号整体剥掉。反过来做的话 {@code \emph{x}} 会先变成 {@code \emphx}，重音 {@code {\"o}}
   * 也会丢掉字母。
   */
  static String cleanLatex(String value) {
    if (value == null) {
      return null;
    }
    String s = value.replaceAll("\\s+", " ").trim();
    // 格式命令：\emph{x} / \textit{x} / \textbf{x} → x
    s =
        s.replaceAll(
            "\\\\(?:emph|textit|textbf|textsc|mathrm|mathit|text)\\s*\\{([^{}]*)\\}", "$1");
    // 数学模式里的普通内容：$x$ → x
    s = s.replaceAll("\\$([^$]*)\\$", "$1");
    // 转义符号（必须在剥花括号之前：\{ 与 \} 是字面花括号）
    s =
        s.replace("\\&", "&")
            .replace("\\%", "%")
            .replace("\\$", "$")
            .replace("\\#", "#")
            .replace("\\_", "_")
            .replace("\\{", "{")
            .replace("\\}", "}")
            .replace("~", " ")
            .replace("---", "—")
            .replace("--", "–");
    // 剩余花括号只是分组保护（{Protein}、{Alpha, Beta}），对纯文本没有意义，整体去掉。
    s = s.replace("{", "").replace("}", "");
    // 重音放在最后：此时只剩 \'e、\"i、\c{c} 这类裸命令，逐个换成真正的字符。
    s = applyAccents(s);
    s = s.replaceAll("\\s+", " ").trim();
    return s.isEmpty() ? null : s;
  }

  /** LaTeX 重音符号 → Unicode 组合附加符号。用组合再归一化的方式，不必为每种字母硬编码一张表。 */
  private static final Map<String, String> ACCENT_MARKS =
      Map.ofEntries(
          Map.entry("'", "\u0301"), // acute
          Map.entry("`", "\u0300"), // grave
          Map.entry("^", "\u0302"), // circumflex
          Map.entry("\"", "\u0308"), // diaeresis
          Map.entry("~", "\u0303"), // tilde
          Map.entry("=", "\u0304"), // macron
          Map.entry(".", "\u0307"), // dot above
          Map.entry("u", "\u0306"), // breve
          Map.entry("v", "\u030C"), // caron
          Map.entry("H", "\u030B"), // double acute
          Map.entry("c", "\u0327"), // cedilla
          Map.entry("k", "\u0328"), // ogonek
          Map.entry("r", "\u030A"), // ring above
          Map.entry("b", "\u0331"), // macron below
          Map.entry("d", "\u0323")); // dot below

  private static final Pattern ACCENT_COMMAND =
      Pattern.compile("\\\\([\"'`^~=.]|[cvuHkrdb])\\s*([A-Za-z])");

  private static String applyAccents(String s) {
    Matcher m = ACCENT_COMMAND.matcher(s);
    StringBuilder out = new StringBuilder(s.length());
    while (m.find()) {
      String mark = ACCENT_MARKS.get(m.group(1));
      String letter = m.group(2);
      String replacement =
          mark == null
              ? letter
              : java.text.Normalizer.normalize(letter + mark, java.text.Normalizer.Form.NFC);
      m.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static Integer parseYear(String raw) {
    if (raw == null) {
      return null;
    }
    Matcher m = Pattern.compile("(1[5-9]\\d{2}|20\\d{2}|21\\d{2})").matcher(raw);
    if (!m.find()) {
      return null;
    }
    return Integer.valueOf(m.group(1));
  }

  /** DOI 归一：去掉 URL 前缀与 doi: 前缀，只留裸标识。 */
  private static String cleanDoi(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String s = raw.trim();
    s = s.replaceFirst("(?i)^https?://(dx\\.)?doi\\.org/", "");
    s = s.replaceFirst("(?i)^doi:\\s*", "");
    s = s.replaceFirst("(?i)^urn:doi:", "");
    return s.isBlank() ? null : s;
  }
}
