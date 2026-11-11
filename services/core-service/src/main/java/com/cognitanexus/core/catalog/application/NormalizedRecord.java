package com.cognitanexus.core.catalog.application;

import com.cognitanexus.sharedkernel.AchievementType;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 来源适配器交给核心服务的规范化记录。
 *
 * <p>依据：架构 V0.2 §6.2。这是 ingestion 与 core 之间的规范化交换格式：适配器负责把各家来源的
 * 字段差异抹平，核心服务只处理这一种结构，因此新增来源不需要改动核心服务的规范化逻辑。
 *
 * <p>字段白名单在核心服务侧执行：解析时只读取本类声明的字段，来源响应中的其他内容即使被适配器
 * 放进了 payload 也不会进入规范化流程，更不会被持久化。
 */
public final class NormalizedRecord {

  private final Map<String, Object> payload;

  private NormalizedRecord(Map<String, Object> payload) {
    this.payload = payload;
  }

  public static NormalizedRecord of(Map<String, Object> payload) {
    return new NormalizedRecord(payload == null ? Map.of() : payload);
  }

  /** 数据性质的默认缺省是 SIMULATED 而非 REAL：来源未声明性质时不得被当成真实收录。 */
  public boolean declaredReal() {
    return "REAL".equalsIgnoreCase(string("dataNature"));
  }

  public AchievementType kind() {
    String raw = string("kind");
    if (raw == null) {
      return null;
    }
    try {
      return AchievementType.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  public String title() {
    return string("title");
  }

  public String doi() {
    return string("doi");
  }

  public String venue() {
    return string("venue");
  }

  public Integer publicationYear() {
    return integer("publicationYear");
  }

  public LocalDate publishedDate() {
    return date("publishedDate");
  }

  public String preprintStatus() {
    return string("preprintStatus");
  }

  public Integer citationCount() {
    return integer("citationCount");
  }

  public String citationSource() {
    return string("citationSource");
  }

  public String abstractText() {
    return string("abstractText");
  }

  public String applicationNo() {
    return string("applicationNo");
  }

  public String publicationNo() {
    return string("publicationNo");
  }

  public List<String> ipcCodes() {
    return strings("ipcCodes");
  }

  public List<String> applicantNames() {
    return strings("applicantNames");
  }

  public LocalDate filingDate() {
    return date("filingDate");
  }

  public LocalDate patentPublicationDate() {
    return date("patentPublicationDate");
  }

  public String projectNo() {
    return string("projectNo");
  }

  public String leaderName() {
    return string("leaderName");
  }

  public String fundingAgency() {
    return string("fundingAgency");
  }

  public String projectCategory() {
    return string("projectCategory");
  }

  public LocalDate projectStartDate() {
    return date("projectStartDate");
  }

  public LocalDate projectEndDate() {
    return date("projectEndDate");
  }

  public String awardName() {
    return string("awardName");
  }

  public Integer awardYear() {
    return integer("awardYear");
  }

  public String awardLevel() {
    return string("awardLevel");
  }

  public String grantingBody() {
    return string("grantingBody");
  }

  public List<String> topics() {
    return strings("topics");
  }

  /** 来源给出的原始分类名，原样保留不覆盖（FR13：同义词合并必须有凭证）。 */
  public String sourceCategory() {
    return string("sourceCategory");
  }

  public List<Author> authors() {
    List<Author> authors = new ArrayList<>();
    Object raw = payload.get("authors");
    if (!(raw instanceof List<?> list)) {
      return authors;
    }
    int fallbackOrder = 1;
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map)) {
        continue;
      }
      String name = text(map.get("name"));
      if (name == null) {
        continue;
      }
      Integer order = toInteger(map.get("order"));
      authors.add(
          new Author(
              name,
              order == null ? fallbackOrder : order,
              text(map.get("orcid")),
              text(map.get("openAlexId")),
              stringList(map.get("organizations")),
              text(map.get("countryCode"))));
      fallbackOrder++;
    }
    return authors;
  }

  /**
   * 作者。
   *
   * @param order 来源给出的作者序；缺失时按出现次序补齐，并且只在来源确实给了顺序时才可信
   */
  public record Author(
      String name,
      int order,
      String orcid,
      String openAlexId,
      List<String> organizations,
      String countryCode) {}

  /** 指纹计算的输入：只用业务字段，绝不含抓取时刻等易变值。 */
  public List<String> fingerprintParts(String sourceRecordId) {
    List<String> parts = new ArrayList<>();
    parts.add(sourceRecordId);
    parts.add(string("kind"));
    parts.add(title());
    parts.add(doi());
    parts.add(applicationNo());
    parts.add(projectNo());
    parts.add(awardName());
    parts.add(String.valueOf(integer("publicationYear")));
    parts.add(venue());
    return parts;
  }

  // -------------------------------------------------------------------------
  // 取值辅助：只读取白名单内的字段，并对类型做显式判断而不是强制转换
  // -------------------------------------------------------------------------

  private String string(String key) {
    return text(payload.get(key));
  }

  private static String text(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : text;
  }

  private Integer integer(String key) {
    return toInteger(payload.get(key));
  }

  private static Integer toInteger(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return Integer.valueOf(String.valueOf(value).trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private LocalDate date(String key) {
    String raw = string(key);
    if (raw == null) {
      return null;
    }
    try {
      return LocalDate.parse(raw);
    } catch (java.time.format.DateTimeParseException ex) {
      return null;
    }
  }

  private List<String> strings(String key) {
    return stringList(payload.get(key));
  }

  private static List<String> stringList(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    for (Object item : list) {
      String text = text(item);
      if (text != null) {
        values.add(text);
      }
    }
    return values;
  }
}
