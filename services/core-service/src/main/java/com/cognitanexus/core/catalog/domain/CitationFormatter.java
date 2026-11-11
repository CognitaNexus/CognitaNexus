package com.cognitanexus.core.catalog.domain;

import com.cognitanexus.sharedkernel.AchievementType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 把 {@link CitationSubject} 渲染成四种引用格式。
 *
 * <p>依据：FR02。格式化器是<b>纯函数</b>：给定同一份事实，输出的字节必须完全一致，不读时间、不读环境、
 * 不查数据库。引用文本会被贴进论文、被脚本解析、被别的平台转载，它一旦随运行环境漂移，
 * 「同一篇文献在两台机器上引用格式不同」就会变成无法复现的问题。
 *
 * <p>三条贯穿全部格式的原则：
 *
 * <ol>
 *   <li><b>不编造缺失事实</b>。年份未知时写各规范允许的占位（APA 的 {@code n.d.}、国标的
 *       「出版年不详」、BibTeX 干脆省略 year 字段），绝不填当前年份或 1900。
 *   <li><b>不替用户猜姓名结构</b>。平台只有显示名，没有「姓／名」两栏。把「张三」切成姓张名三、
 *       把「Zhang San」认成姓 Zhang，都是猜；猜错的引用比不完整的引用更难被发现。因此作者一律按
 *       原显示名整体输出，APA 这类要求「姓, 名首字母」的格式另附
 *       {@link CitationWarning#NAME_SPLIT_NOT_APPLIED} 说明简化了什么。
 *   <li><b>撤稿必须进文本</b>。一条被撤稿的成果若给出与正常成果毫无区别的引用文本，平台就等于在
 *       帮着把不成立的结论继续传播下去。
 * </ol>
 */
public final class CitationFormatter {

  /** GB/T 7714-2015 §8.1.2：作者超过 3 位只列前 3 位，其后加「等」。 */
  private static final int GB_AUTHOR_LIMIT = 3;

  /** APA 7 §9.8：作者不超过 20 位时全部列出。 */
  private static final int APA_AUTHOR_LIMIT = 20;

  /** 引用键里作者段的最大长度，避免超长键被某些 BibTeX 工具截断。 */
  private static final int KEY_AUTHOR_MAX = 12;

  /** 引用键里题名段的最大长度。 */
  private static final int KEY_TITLE_MAX = 10;

  private CitationFormatter() {}

  public static String format(CitationSubject subject, CitationFormat format) {
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(format, "format");
    return format(subject, format, citeKey(subject));
  }

  /**
   * 用调用方指定的引用键渲染。
   *
   * <p>存在的唯一理由是批量导出：一个 .bib 文件里键必须互不相同，撞键时调用方会调整键，而调整后的
   * 键必须真的出现在条目上——若在这里重新算一次，导出的文件里就会出现两个同名条目，BibTeX 会静默
   * 让后者覆盖前者，参考文献表里少一条且不报错。
   *
   * <p>其余三种格式不使用引用键，传进来的值被忽略。
   */
  public static String format(CitationSubject subject, CitationFormat format, String citeKey) {
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(format, "format");
    String key = (citeKey == null || citeKey.isBlank()) ? citeKey(subject) : citeKey;
    return switch (format) {
      case BIBTEX -> bibtex(subject, key);
      case RIS -> ris(subject);
      case GB_T_7714 -> gbT7714(subject);
      case APA -> apa(subject);
    };
  }

  /**
   * 该格式自身的局限说明（与「元数据缺失」无关，只与格式规范有关）。
   *
   * <p>目前只有 APA：它要求把作者写成「姓, 名首字母」，而平台不拆姓名。把它做成随格式返回，
   * 是因为国标与 BibTeX/RIS 不受这个限制影响——在顶层统一提示会让人误以为所有格式都不可靠。
   */
  public static List<CitationWarning> formatWarnings(
      CitationSubject subject, CitationFormat format) {
    if (format == CitationFormat.APA && !subject.authors().isEmpty()) {
      return List.of(
          CitationWarning.of(
              CitationWarning.NAME_SPLIT_NOT_APPLIED,
              "本格式要求把作者写成「姓, 名首字母」，平台只持有显示名，未拆分姓名；" + "正式引用前请按原文补全姓名缩写"));
    }
    return List.of();
  }

  /**
   * 引用键（BibTeX 的 cite key，同时用作下载文件名）。
   *
   * <p>形如 {@code zhangsan2023deep_a1b2c3}：作者段的 ASCII 折叠 + 年份 + 题名首段 + 成果标识尾段。
   * 尾段是必需的：仅靠「作者+年份+题名」在一个文档里引用同一作者同年的两篇相近题名成果就会撞键，
   * 而 BibTeX 撞键的后果是<b>后者静默覆盖前者</b>——参考文献表里少一条，编译不报错。
   *
   * <p>作者段不提取「姓氏」：中英文姓名结构不同，从显示名里猜哪一段是姓，两种语言必有一种猜错。
   * 全部 ASCII 折叠后截断，虽然没有姓氏好看，但同一作者稳定得到同一段，且不会把两个人折叠成一段。
   */
  public static String citeKey(CitationSubject subject) {
    String author = keyToken(firstAuthorName(subject), "anon", KEY_AUTHOR_MAX);
    String year = subject.yearKnown() ? String.valueOf(subject.year()) : "nd";
    String title = keyToken(subject.title(), "item", KEY_TITLE_MAX);
    return author + year + title + "_" + idSuffix(subject.achievementId());
  }

  // ---------------------------------------------------------------- BibTeX

  private static String bibtex(CitationSubject subject, String key) {
    StringBuilder out = new StringBuilder();
    out.append('@').append(bibtexEntryType(subject.type())).append('{').append(key);
    List<String> fields = new ArrayList<>();
    if (!subject.authors().isEmpty()) {
      fields.add(bibField("author", bibtexAuthors(subject.authors())));
    }
    fields.add(bibField("title", subject.title()));
    switch (subject.type()) {
      case PAPER -> {
        addBibField(fields, "journal", subject.venue());
        addBibField(fields, "year", yearText(subject));
        addBibField(fields, "doi", subject.doi());
      }
      case PATENT -> {
        addBibField(fields, "year", yearText(subject));
        addBibField(fields, "number", subject.number());
        addBibField(fields, "assignee", subject.issuingBody());
      }
      case PROJECT, AWARD -> {
        addBibField(fields, "year", yearText(subject));
        addBibField(fields, "howpublished", subject.issuingBody());
        addBibField(fields, "number", subject.number());
      }
    }
    String notes = bibtexNote(subject);
    if (!notes.isEmpty()) {
      fields.add(bibField("note", notes));
    }
    for (String field : fields) {
      out.append(",\n").append(field);
    }
    out.append("\n}\n");
    return out.toString();
  }

  /**
   * BibTeX 条目类型。
   *
   * <p>只有论文用 {@code @article}（它有 journal/doi 这些 article 专有字段），其余一律 {@code @misc}。
   * 不用 {@code @patent}：那是 biblatex 的扩展类型，纯 BibTeX + bibtex 编译器不认，会在编译时
   * 静默丢弃该条目的类型并在参考文献里渲染成未知类型。
   */
  private static String bibtexEntryType(AchievementType type) {
    return type == AchievementType.PAPER ? "article" : "misc";
  }

  /**
   * BibTeX 的 author 字段值。
   *
   * <p>只用 {@code and} 连接，不做任何转义——转义由 {@link #bibField} 统一完成。这里再转一次会把
   * 反斜杠本身再转义一遍，作者名里一个普通的 {@code &} 最终变成 {@code \textbackslash\{\}\&}。
   */
  private static String bibtexAuthors(List<CitationAuthor> authors) {
    List<String> names = new ArrayList<>();
    for (CitationAuthor author : authors) {
      names.add(author.displayName());
    }
    return String.join(" and ", names);
  }

  private static String bibtexNote(CitationSubject subject) {
    List<String> notes = new ArrayList<>();
    if (subject.type() == AchievementType.AWARD && notBlank(subject.awardLevel())) {
      notes.add(subject.awardLevel());
    }
    switch (subject.type()) {
      case PATENT -> notes.add("专利");
      case PROJECT -> notes.add("科研项目");
      case AWARD -> notes.add("获奖");
      default -> {
        // 论文不需要在这里补类型说明：@article 已经说明了。
      }
    }
    if (subject.noticed()) {
      notes.add(integritySentence(subject));
    }
    // 不在这里转义：note 字段由 bibField 统一转义，避免反斜杠被转义两次。
    return String.join("; ", notes);
  }

  /**
   * BibTeX 特殊字符转义。
   *
   * <p>十个字符在 BibTeX 里有语法含义，不转义就会破坏花括号平衡或让编译报错：{@code & % $ # _ { } ~ ^ \}。
   * 反斜杠必须先处理，否则后面替换插入的反斜杠会被二次处理。
   */
  static String bibEscape(String raw) {
    if (raw == null) {
      return "";
    }
    StringBuilder out = new StringBuilder(raw.length() + 8);
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      switch (c) {
        case '\\' -> out.append("\\textbackslash{}");
        case '&' -> out.append("\\&");
        case '%' -> out.append("\\%");
        case '$' -> out.append("\\$");
        case '#' -> out.append("\\#");
        case '_' -> out.append("\\_");
        case '{' -> out.append("\\{");
        case '}' -> out.append("\\}");
        case '~' -> out.append("\\textasciitilde{}");
        case '^' -> out.append("\\textasciicircum{}");
        default -> out.append(c);
      }
    }
    return out.toString();
  }

  private static String bibField(String name, String rawValue) {
    return "  " + name + " = {" + bibEscape(rawValue) + "}";
  }

  private static void addBibField(List<String> fields, String name, String rawValue) {
    if (notBlank(rawValue)) {
      fields.add(bibField(name, rawValue));
    }
  }

  // ------------------------------------------------------------------- RIS

  private static String ris(CitationSubject subject) {
    StringBuilder out = new StringBuilder();
    out.append("TY  - ").append(risType(subject.type())).append('\n');
    for (CitationAuthor author : subject.authors()) {
      out.append("AU  - ").append(oneLine(author.displayName())).append('\n');
    }
    out.append("TI  - ").append(oneLine(subject.title())).append('\n');
    if (subject.yearKnown()) {
      out.append("PY  - ").append(subject.year()).append('\n');
    }
    switch (subject.type()) {
      case PAPER -> {
        appendRis(out, "JO", subject.venue());
        appendRis(out, "DO", subject.doi());
      }
      case PATENT -> {
        appendRis(out, "M1", subject.number());
        appendRis(out, "PB", subject.issuingBody());
      }
      case PROJECT, AWARD -> {
        appendRis(out, "PB", subject.issuingBody());
        appendRis(out, "M1", subject.number());
      }
    }
    appendRis(out, "N1", risNote(subject));
    out.append("ER  - \n");
    return out.toString();
  }

  private static String risType(AchievementType type) {
    return switch (type) {
      case PAPER -> "JOUR";
      case PATENT -> "PAT";
      case PROJECT, AWARD -> "GEN";
    };
  }

  private static String risNote(CitationSubject subject) {
    List<String> notes = new ArrayList<>();
    if (!subject.yearKnown()) {
      notes.add("来源未提供年份");
    }
    if (subject.type() == AchievementType.AWARD && notBlank(subject.awardLevel())) {
      notes.add(subject.awardLevel());
    }
    if (subject.noticed()) {
      notes.add(integritySentence(subject));
    }
    return String.join("; ", notes);
  }

  private static void appendRis(StringBuilder out, String tag, String value) {
    if (notBlank(value)) {
      out.append(tag).append("  - ").append(oneLine(value)).append('\n');
    }
  }

  // ------------------------------------------------------------ GB/T 7714

  private static String gbT7714(CitationSubject subject) {
    List<String> segments = new ArrayList<>();
    String authors = gbAuthors(subject.authors());
    String head = (authors.isEmpty() ? "" : authors + ". ") + subject.title() + gbTypeMark(subject);
    segments.add(head);

    List<String> imprint = new ArrayList<>();
    switch (subject.type()) {
      case PAPER -> {
        imprint.add(notBlank(subject.venue()) ? subject.venue() : "[刊名不详]");
      }
      case PATENT -> {
        if (notBlank(subject.number())) {
          imprint.add("公开号: " + subject.number());
        }
        if (notBlank(subject.issuingBody())) {
          imprint.add(subject.issuingBody());
        }
      }
      case PROJECT -> {
        if (notBlank(subject.number())) {
          imprint.add("项目编号: " + subject.number());
        }
        if (notBlank(subject.issuingBody())) {
          imprint.add(subject.issuingBody());
        }
      }
      case AWARD -> {
        if (notBlank(subject.issuingBody())) {
          imprint.add(subject.issuingBody());
        }
        // 获奖级别是这条记录的关键限定（同一个奖项名，国家级与校级不是一回事），
        // 国标没有为它留位置，放在出版项里比丢掉更接近「如实转写」。
        if (notBlank(subject.awardLevel())) {
          imprint.add(subject.awardLevel());
        }
      }
    }
    imprint.add(subject.yearKnown() ? String.valueOf(subject.year()) : "[出版年不详]");
    segments.add(String.join(", ", imprint));

    if (notBlank(subject.doi())) {
      segments.add("DOI: " + subject.doi());
    }
    if (subject.noticed()) {
      segments.add("注：" + integritySentence(subject));
    }
    return String.join(". ", segments) + ".";
  }

  /** GB/T 7714-2015 文献类型标识。获奖无标准标识，按 §4 的「其他」用 {@code [Z]}。 */
  private static String gbTypeMark(CitationSubject subject) {
    return switch (subject.type()) {
      case PAPER -> "[J]";
      case PATENT -> "[P]";
      case PROJECT -> "[R]";
      case AWARD -> "[Z]";
    };
  }

  private static String gbAuthors(List<CitationAuthor> authors) {
    if (authors.isEmpty()) {
      return "";
    }
    List<String> names = new ArrayList<>();
    for (int i = 0; i < authors.size() && i < GB_AUTHOR_LIMIT; i++) {
      names.add(authors.get(i).displayName());
    }
    String joined = String.join(", ", names);
    return authors.size() > GB_AUTHOR_LIMIT ? joined + ", 等" : joined;
  }

  // ------------------------------------------------------------------- APA

  private static String apa(CitationSubject subject) {
    List<String> segments = new ArrayList<>();
    String authors = apaAuthors(subject.authors());
    String year = subject.yearKnown() ? "(" + subject.year() + ")" : "(n.d.)";
    String head = authors.isEmpty() ? year + "." : authors + " " + year + ".";
    segments.add(head);

    String title = subject.title();
    if (subject.type() == AchievementType.PATENT && notBlank(subject.number())) {
      title = title + " (专利号 " + subject.number() + ")";
    }
    if (subject.type() == AchievementType.AWARD && notBlank(subject.awardLevel())) {
      title = title + " (" + subject.awardLevel() + ")";
    }
    segments.add(withTerminal(title));

    if (notBlank(subject.venue())) {
      segments.add(withTerminal(subject.venue()));
    }
    if (notBlank(subject.issuingBody())) {
      segments.add(withTerminal(subject.issuingBody()));
    }
    if (notBlank(subject.doi())) {
      segments.add("https://doi.org/" + stripDoiPrefix(subject.doi()));
    }
    if (subject.noticed()) {
      segments.add("[" + integritySentence(subject) + "]");
    }
    return String.join(" ", segments);
  }

  private static String apaAuthors(List<CitationAuthor> authors) {
    if (authors.isEmpty()) {
      return "";
    }
    List<String> names = new ArrayList<>();
    for (int i = 0; i < authors.size() && i < APA_AUTHOR_LIMIT; i++) {
      names.add(authors.get(i).displayName());
    }
    if (names.size() == 1) {
      return withTerminal(names.get(0));
    }
    String last = names.remove(names.size() - 1);
    return String.join(", ", names) + ", & " + withTerminal(last);
  }

  // ---------------------------------------------------------------- 公共片段

  /** 诚信声明的一句话说明，四种格式共用同一措辞，避免同一条成果在不同格式里描述不一致。 */
  private static String integritySentence(CitationSubject subject) {
    String label = notBlank(subject.integrityLabel()) ? subject.integrityLabel() : "诚信声明";
    return "平台登记有" + label + "，引用前请核对原文";
  }

  /**
   * 年份字段文本；未知时返回 null，由调用方跳过该字段。
   *
   * <p>刻意不返回 {@code n.d.}：BibTeX 的 year 字段会被排序与去重逻辑当作数字使用，塞进 {@code n.d.}
   * 会让该条目在按年排序时排到最前或最后，且部分工具会直接报错。缺就是缺。
   */
  private static String yearText(CitationSubject subject) {
    return subject.yearKnown() ? String.valueOf(subject.year()) : null;
  }

  private static String firstAuthorName(CitationSubject subject) {
    return subject.authors().isEmpty() ? null : subject.authors().get(0).displayName();
  }

  private static String keyToken(String raw, String fallback, int maxLength) {
    if (raw == null) {
      return fallback;
    }
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < raw.length() && out.length() < maxLength; i++) {
      char c = Character.toLowerCase(raw.charAt(i));
      if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
        out.append(c);
      }
    }
    return out.isEmpty() ? fallback : out.toString();
  }

  private static String idSuffix(String achievementId) {
    if (achievementId == null) {
      return "000000";
    }
    String token = keyToken(achievementId, "", 64);
    String suffix = token.length() <= 6 ? token : token.substring(token.length() - 6);
    return suffix.isEmpty() ? "000000" : suffix;
  }

  private static String stripDoiPrefix(String doi) {
    String trimmed = doi.trim();
    String lower = trimmed.toLowerCase(Locale.ROOT);
    if (lower.startsWith("https://doi.org/")) {
      return trimmed.substring("https://doi.org/".length());
    }
    if (lower.startsWith("http://doi.org/")) {
      return trimmed.substring("http://doi.org/".length());
    }
    if (lower.startsWith("doi:")) {
      return trimmed.substring("doi:".length()).trim();
    }
    return trimmed;
  }

  /** RIS 是逐行格式：值里出现换行会把一行拆成两行，后者被解析成未知标签。 */
  private static String oneLine(String raw) {
    return raw == null ? "" : raw.replace("\r", " ").replace("\n", " ").trim();
  }

  private static String withTerminal(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    char last = text.charAt(text.length() - 1);
    if (last == '.' || last == '?' || last == '!' || last == '。' || last == '？' || last == '！') {
      return text;
    }
    return text + ".";
  }

  private static boolean notBlank(String raw) {
    return raw != null && !raw.isBlank();
  }
}
