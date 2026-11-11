package com.cognitanexus.query.discovery.application;

import com.cognitanexus.query.platform.util.Digests;
import com.cognitanexus.query.platform.util.TextNormalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * 规范化后的检索条件。
 *
 * <p>依据：架构 V0.2 §9.1「排序字段走白名单，末位唯一稳定ID」「首请求返回 queryVersion、
 * 规范化条件摘要和规则版本，后续分页必须携带」。
 *
 * <p>queryVersion ＝ 条件摘要 + 发布版本。两者任一变化都必须让旧分页被拒绝：只带条件摘要时，
 * 数据更新后翻页会跨过两个不同的快照；只带发布版本时，前端改了过滤条件却沿用旧版本翻页，
 * 服务端无从察觉，两套条件的结果会混进同一个列表。
 */
public record SearchQuery(
    String keyword,
    String keywordNorm,
    String type,
    Integer yearFrom,
    Integer yearTo,
    String topicId,
    String organization,
    String dataNature,
    String sort,
    int page,
    int size,
    String queryVersion) {

  /** 允许的排序键。任何未列出的取值都被拒绝，而不是回落到默认值——静默回落会让用户以为排序生效。 */
  public static final String SORT_RELEVANCE = "relevance";

  public static final String SORT_YEAR_DESC = "year_desc";
  public static final String SORT_YEAR_ASC = "year_asc";
  public static final String SORT_TITLE_ASC = "title_asc";
  public static final String SORT_CITATIONS_DESC = "citations_desc";

  /**
   * 学者检索唯一受支持的排序：按公开合格成果数降序、同数按学者标识稳定排序。
   *
   * <p>单独命名而不是复用成果的排序键：两者的可比字段不同，混用会让「排序生效了」变成一句
   * 无法验证的话。
   */
  public static final String SORT_SCHOLAR_ACHIEVEMENTS_DESC = "achievements_desc";

  private static final List<String> ALLOWED_SORTS =
      List.of(SORT_RELEVANCE, SORT_YEAR_DESC, SORT_YEAR_ASC, SORT_TITLE_ASC, SORT_CITATIONS_DESC);

  /** 数据性质范围：默认只查真实数据，其余取值必须显式给出（架构 §9.2）。 */
  public static final String NATURE_REAL = "REAL";

  /** 演示口径：不限数据性质。必须由调用方显式要求，缺省不落在这一支上。 */
  public static final String NATURE_ALL = "ALL";

  private static final List<String> ALLOWED_NATURES =
      List.of(NATURE_REAL, "SIMULATED", "MIXED", NATURE_ALL);

  public static List<String> allowedSorts() {
    return ALLOWED_SORTS;
  }

  public static List<String> allowedNatures() {
    return ALLOWED_NATURES;
  }

  /** 条件摘要。字段顺序固定，避免同一组条件因拼装顺序不同得到不同版本号。 */
  public String digest() {
    List<String> parts = new ArrayList<>();
    parts.add(keywordNorm == null ? "" : keywordNorm);
    parts.add(type == null ? "" : type);
    parts.add(yearFrom == null ? "" : yearFrom.toString());
    parts.add(yearTo == null ? "" : yearTo.toString());
    parts.add(topicId == null ? "" : topicId);
    parts.add(organization == null ? "" : organization);
    parts.add(dataNature);
    parts.add(sort);
    parts.add(Integer.toString(size));
    return Digests.shortOf(parts.toArray(String[]::new));
  }

  /** queryVersion = 条件摘要 + 发布版本。 */
  public String versionFor(long publishedRevision) {
    return "q" + digest() + "-r" + publishedRevision;
  }

  /** 归一化关键词：与投影中的 title_norm 使用同一规则，否则会静默漏结果。 */
  public static String normalizeKeyword(String raw) {
    String normalized = TextNormalizer.normalize(raw);
    return normalized == null || normalized.isBlank() ? null : normalized;
  }

  /**
   * 把归一化关键词转成 LIKE 模式里可安全拼接的片段。
   *
   * <p>用户输入的 {@code %} 或 {@code _} 必须当成普通字符：前者会让 {@code LIKE '%x%'} 变成
   * 「任意内容」，搜一个 % 得到全库结果；后者会把单字符位置变成通配。这类错误不会报错，
   * 也不影响页面渲染，只是悄悄给出了错的答案，所以要在进入 SQL 之前就转义。
   *
   * <p>PostgreSQL 的 LIKE 默认转义字符是反斜杠，因此只转义参数本身，不需要额外 ESCAPE 子句。
   */
  public static String likePatternOf(String keywordNorm) {
    if (keywordNorm == null) {
      return null;
    }
    StringBuilder escaped = new StringBuilder(keywordNorm.length() + 8);
    for (int i = 0; i < keywordNorm.length(); i++) {
      char ch = keywordNorm.charAt(i);
      if (ch == '\\' || ch == '%' || ch == '_') {
        escaped.append('\\');
      }
      escaped.append(ch);
    }
    return escaped.toString();
  }

  /** 页码偏移；超出交互上限时由调用方返回可解释的拒绝，不伪报无结果。 */
  public int offset() {
    return (page - 1) * size;
  }
}
