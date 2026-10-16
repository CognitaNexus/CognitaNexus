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
 * @param organization 发生时机构名，精确匹配；web 层刻意不做模糊匹配，避免把相似机构名混为一谈
 */
public record SearchCriteria(
    String keywordNorm,
    String type,
    Integer yearFrom,
    Integer yearTo,
    String topicId,
    String organization,
    String dataNature,
    String sort) {}
