package com.cognitanexus.sharedkernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 目录修订号的行为约束。
 *
 * <p>依据：FR01（核心公开目录单调提交次序）、架构 V0.2 §6.3。
 *
 * <p>修订号是本系统判断「这个读模型是不是已经追上了写入」的唯一依据，因此它的边界行为必须是
 * 确定的：负数无意义、初始值为 0、连续性判断不能把跳跃当成连续。
 */
class CatalogRevisionTest {

  @Test
  @DisplayName("初始修订号为 0，且不大于任何合法后续修订号")
  void initialIsZero() {
    CatalogRevision initial = CatalogRevision.initial();
    assertThat(initial.value()).isZero();
    assertThat(initial.isAfter(CatalogRevision.initial())).isFalse();
    assertThat(new CatalogRevision(1L).isAfter(initial)).isTrue();
  }

  @Test
  @DisplayName("负修订号被拒绝：负值在单调序列里没有含义，静默接受会让比较结果反直觉")
  void negativeRejected() {
    assertThatThrownBy(() -> new CatalogRevision(-1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能为负");
  }

  @Test
  @DisplayName("isContiguousAfter 只认 +1：跳跃必须被判为不连续，否则会掩盖丢事件")
  void contiguityRequiresExactStep() {
    CatalogRevision base = new CatalogRevision(7L);
    assertThat(new CatalogRevision(8L).isContiguousAfter(base)).isTrue();
    assertThat(new CatalogRevision(9L).isContiguousAfter(base)).isFalse();
    assertThat(new CatalogRevision(7L).isContiguousAfter(base)).isFalse();
    assertThat(new CatalogRevision(6L).isContiguousAfter(base)).isFalse();
  }

  @Test
  @DisplayName("相等修订号之间不存在先后关系")
  void equalIsNotAfter() {
    CatalogRevision a = new CatalogRevision(42L);
    CatalogRevision b = new CatalogRevision(42L);
    assertThat(a.isAfter(b)).isFalse();
    assertThat(b.isAfter(a)).isFalse();
  }

  @Test
  @DisplayName("toString 输出裸数字：修订号会进入事件载荷与日志，带类名会让比对困难")
  void toStringIsPlainNumber() {
    assertThat(new CatalogRevision(123L).toString()).isEqualTo("123");
  }
}
