package com.cognitanexus.query.graph.domain;

/**
 * 图模型中的标识校验。
 *
 * <p>图数据库里「空字符串 ID」不会报错，只会生成一个永远匹配不上任何人的节点，或者更糟：
 * 让 {@code MATCH (s:Scholar {scholar_id: ""})} 变成一条静默成立的边。这类错误在真实数据上
 * 表现为「某些学者凭空多出合作者」，排查成本极高，因此在写入图模型前就显式拒绝。
 *
 * <p>ID 在图中统一用字符串保存（架构 §9.3：对外接口的内部 ID 一律字符串），排序比较只用
 * Unicode 码点的固定字典序，不使用区域相关的 {@code Collator}。
 */
final class GraphIdentifiers {

  private GraphIdentifiers() {}

  static String require(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("图模型的 " + field + " 不能为空");
    }
    // 前导/尾随空白会让「同一个人」在图中变成两个节点：一个带空格，一个不带。
    if (!value.equals(value.trim())) {
      throw new IllegalArgumentException("图模型的 " + field + " 不能带前后空白：" + field);
    }
    return value;
  }

  static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
