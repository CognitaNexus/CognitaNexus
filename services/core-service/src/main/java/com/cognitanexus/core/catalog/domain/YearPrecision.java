package com.cognitanexus.core.catalog.domain;

/**
 * 年份精度。
 *
 * <p>依据：架构 V0.2 §5.4。数据库约束要求「年份与精度同时有值或同时为空」，
 * 因此不得出现「有年份但精度未知」这种自相矛盾的组合。
 */
public enum YearPrecision {
  /** 年份可信且直接来源于来源记录或人工确认。 */
  YEAR,
  /** 来源未给出可用年份。 */
  UNKNOWN;

  public static YearPrecision of(String raw) {
    if (raw == null) {
      return null;
    }
    for (YearPrecision value : values()) {
      if (value.name().equals(raw)) {
        return value;
      }
    }
    throw new IllegalArgumentException("未知年份精度: " + raw);
  }
}
