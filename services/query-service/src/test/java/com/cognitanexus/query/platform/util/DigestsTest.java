package com.cognitanexus.query.platform.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 摘要计算的行为约束。
 *
 * <p>依据：架构 V0.2 §9.1。摘要用于 queryVersion 与缓存键，因此两个性质至关重要：
 * <b>不可歧义</b>（不同字段切分不得撞键）与<b>稳定</b>（同输入恒等）。
 */
class DigestsTest {

  @Test
  @DisplayName("同输入同输出：摘要必须可复现，否则分页版本校验会误判为「已变化」")
  void deterministic() {
    assertThat(Digests.of("a", "b")).isEqualTo(Digests.of("a", "b"));
  }

  @Test
  @DisplayName("用分隔符拼接，避免 [\"ab\",\"c\"] 与 [\"a\",\"bc\"] 撞成同一个摘要")
  void separatorPreventsAmbiguity() {
    assertThat(Digests.of("ab", "c")).isNotEqualTo(Digests.of("a", "bc"));
  }

  @Test
  @DisplayName("null 与空串在摘要里等价：条件字段缺省时两者语义相同")
  void nullEqualsEmpty() {
    assertThat(Digests.of("a", null, "b")).isEqualTo(Digests.of("a", "", "b"));
  }

  @Test
  @DisplayName("顺序敏感：字段顺序变化必须改变摘要")
  void orderMatters() {
    assertThat(Digests.of("a", "b")).isNotEqualTo(Digests.of("b", "a"));
  }

  @Test
  @DisplayName("空参数与 null 参数不抛异常")
  void toleratesEmptyInput() {
    assertThat(Digests.of()).hasSize(64);
    assertThat(Digests.of((String[]) null)).hasSize(64);
  }

  @Test
  @DisplayName("短摘要取前 16 位且与全摘要一致")
  void shortDigestConsistent() {
    String full = Digests.of("a", "b");
    assertThat(Digests.shortOf("a", "b")).hasSize(16);
    assertThat(full).startsWith(Digests.shortOf("a", "b"));
  }

  @Test
  @DisplayName("SHA-256 输出为小写十六进制 64 位")
  void sha256Format() {
    String hex = Digests.sha256Hex("abc");
    assertThat(hex).matches("[0-9a-f]{64}");
    // 已知向量：SHA-256("abc")
    assertThat(hex).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  @DisplayName("中文按 UTF-8 编码：不因平台默认字符集不同而给出不同摘要")
  void utf8Stable() {
    String hex = Digests.sha256Hex("学术成果");
    assertThat(hex).matches("[0-9a-f]{64}");
    // 逐字节不同的两个中文串必须得到不同摘要（UTF-8 字节序列不同）。
    assertThat(hex).isNotEqualTo(Digests.sha256Hex("学术 成果"));
    assertThat(hex).isEqualTo(Digests.sha256Hex("学术成果"));
  }
}
