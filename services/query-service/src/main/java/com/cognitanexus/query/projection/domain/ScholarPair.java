package com.cognitanexus.query.projection.domain;

/**
 * 无序学者对。
 *
 * <p>依据：架构 V0.2 §9.3。构造时把两端按规范 ID 字符串的固定字典序排列：使用本机区域排序或
 * 依赖写入顺序，会让同一对作者因到达顺序不同产生两条边，边强度被双计，而结果看起来完全正常。
 *
 * <p>比较使用 {@link String#compareTo}（UTF-16 码元序），不用 {@code Collator}：不同 JVM 的默认
 * 区域设置可能不同，那样同一条边在不同环境下会得到不同的规范形式。
 */
public record ScholarPair(String scholarA, String scholarB) {

  public ScholarPair {
    if (scholarA == null || scholarB == null) {
      throw new IllegalArgumentException("合作边两端都必须存在");
    }
    if (scholarA.equals(scholarB)) {
      throw new IllegalArgumentException("合作边两端必须是不同的学者");
    }
    if (scholarA.compareTo(scholarB) > 0) {
      String swap = scholarA;
      scholarA = scholarB;
      scholarB = swap;
    }
  }

  public static ScholarPair of(String left, String right) {
    return new ScholarPair(left, right);
  }
}
