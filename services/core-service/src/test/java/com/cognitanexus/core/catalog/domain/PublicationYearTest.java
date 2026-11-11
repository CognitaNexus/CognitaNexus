package com.cognitanexus.core.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 发表年份的行为约束。
 *
 * <p>依据：FR03（成果元数据）、BR-IMP-06（年份未知保持未知）、架构 V0.2 §5.4、编码规范 §4.5。
 *
 * <p>这里守的是一条很容易被「顺手补全」破坏的规则：年份缺失时不得用 1 月 1 日或当年填充，
 * 因为补出来的年份会进入统计图表，看上去和真实年份没有区别。
 */
class PublicationYearTest {

  @Test
  @DisplayName("正常年份记为 YEAR 精度")
  void knownYear() {
    PublicationYear year = PublicationYear.of(2024);
    assertThat(year.year()).isEqualTo(2024);
    assertThat(year.precision()).isEqualTo(YearPrecision.YEAR);
    assertThat(year.known()).isTrue();
  }

  @Test
  @DisplayName("null 年份归一为 UNKNOWN 精度，不补默认值")
  void nullYearBecomesUnknown() {
    PublicationYear year = PublicationYear.of(null);
    assertThat(year.year()).isNull();
    assertThat(year.precision()).isEqualTo(YearPrecision.UNKNOWN);
    assertThat(year.known()).isFalse();
  }

  @Test
  @DisplayName("year 为 null 却声明 YEAR 精度会被拒绝：不一致的状态必须当场失败")
  void nullYearWithYearPrecisionRejected() {
    assertThatThrownBy(() -> new PublicationYear(null, YearPrecision.YEAR))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("精度不得为 YEAR");
  }

  @Test
  @DisplayName("小于 1000 的年份被拒绝：三位数年份几乎一定来自解析错误而非真实数据")
  void implausibleYearRejected() {
    assertThatThrownBy(() -> new PublicationYear(999, YearPrecision.YEAR))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("年份值不合理");
  }

  @Test
  @DisplayName("unknown(reason) 与 of(null) 等价，缺失原因不影响数据本身")
  void unknownFactoryIgnoresReason() {
    PublicationYear fromReason = PublicationYear.unknown("来源未提供年份");
    assertThat(fromReason.year()).isNull();
    assertThat(fromReason.precision()).isEqualTo(YearPrecision.UNKNOWN);
    assertThat(fromReason).isEqualTo(PublicationYear.of(null));
  }

  @Test
  @DisplayName("边界年份 1000 是合法的下界")
  void lowerBoundaryAccepted() {
    assertThat(new PublicationYear(1000, YearPrecision.YEAR).known()).isTrue();
  }
}
