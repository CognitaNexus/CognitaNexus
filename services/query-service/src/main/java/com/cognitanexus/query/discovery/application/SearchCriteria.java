package com.cognitanexus.query.discovery.application;

/**
 * 检索的 SQL 侧条件。
 *
 * <p>依据：架构 V0.2 §9.1、§9.2。
 *
 * <p>{@code dataNature} 为 null 表示不限制数据性质（演示模式）；默认情形由服务层填入 REAL。
 * 用「null＝不限」而不是"ALL"字符串，是为了让持久层的条件判断只需要判空——字符串常量比较在
 * 模板表达式里容易被写成引用比较，而那种错误不会报错，只会让过滤条件静默失效。
 *
 * @param keywordNorm 已按与投影相同的规则归一化的关键词；null 表示不含关键词条件
 * @param keywordLike 关键词转成 LIKE 模式后的值：{@code %} 与 {@code _} 已按字面转义。
 *     直接拿 keywordNorm 拼 LIKE 模式会让用户输入的一个 {@code %} 变成「匹配全部」，
 *     而检索结果看起来仍然正常，属于不会报错的错误。相似度计算仍用 keywordNorm。
 * @param keywordTrigramUsable 关键词是否长到能取出 trigram。为 false 时检索 SQL 走顺序过滤，
 *     为 true 时走候选集 UNION。这个判断必须显式传下来而不是在 SQL 里算：两种写法的执行计划
 *     差一个数量级，而 SQL 模板无法表达「模式里有没有三元组」这种事。
 * @param organization 发生时机构名，精确匹配；web 层刻意不做模糊匹配，避免把相似机构名混为一谈
 */
public record SearchCriteria(
    String keywordNorm,
    String keywordLike,
    boolean keywordTrigramUsable,
    String type,
    Integer yearFrom,
    Integer yearTo,
    String topicId,
    String organization,
    String dataNature,
    String sort) {

  /**
   * 能取出一个完整 trigram 的最短关键词长度。
   *
   * <p>pg_trgm 把字符串切成连续三元组，两个字符凑不出一个三元组，索引对这类模式没有任何
   * 过滤能力——PostgreSQL 会照常「用」索引，但实际退化成整表扫描还多付了索引页的开销。
   * 中文尤其容易踩到：两个汉字是最自然的检索词，而它恰好落在阈值的错误一侧。
   */
  public static final int MIN_TRIGRAM_KEYWORD_LENGTH = 3;

  /** 该关键词能否借 trigram 索引过滤。null（不含关键词条件）时返回 false，调用方另有分支处理。 */
  public static boolean trigramUsable(String keywordNorm) {
    return keywordNorm != null && keywordNorm.length() >= MIN_TRIGRAM_KEYWORD_LENGTH;
  }
}
