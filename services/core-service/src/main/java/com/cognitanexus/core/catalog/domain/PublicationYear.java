package com.cognitanexus.core.catalog.domain;

/**
 * 发表年份。
 *
 * <p>依据：架构 V0.2 §5.4、编码规范 §4.5。年份使用年份值，<b>不得</b>补 1 月 1 日伪装成完整日期；
 * 年份未知时保持未知并说明原因，而不是用当年或某个占位值填充。
 *
 * @param year 四位年份；未知时为 null
 * @param precision YEAR 表示年份可信；UNKNOWN 表示来源未给出可用年份
 */
public record PublicationYear(Integer year, YearPrecision precision) {

  public PublicationYear {
    if (year == null && precision == YearPrecision.YEAR) {
      throw new IllegalArgumentException("年份未知时精度不得为 YEAR");
    }
    if (year != null && year < 1000) {
      throw new IllegalArgumentException("年份值不合理: " + year);
    }
    if (year == null) {
      precision = YearPrecision.UNKNOWN;
    }
  }

  /** 年份缺失，且缺失原因已知：来源未提供。 */
  public static PublicationYear unknown(String ignoredReason) {
    return new PublicationYear(null, YearPrecision.UNKNOWN);
  }

  public static PublicationYear of(Integer year) {
    return year == null ? unknown("来源未提供年份") : new PublicationYear(year, YearPrecision.YEAR);
  }

  public boolean known() {
    return year != null;
  }
}
