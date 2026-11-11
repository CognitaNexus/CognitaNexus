package com.cognitanexus.ingestion.platform.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 内容指纹的行为约束。
 *
 * <p>依据：FR02、AC-IMPL-FR02-03、架构 V0.2 §6.2。
 *
 * <p>指纹是跨源去重与幂等重试的共同基础。它只有在一份内容重复抓取时保持稳定才有意义，
 * 因此下面同时固定两件事：成分取值不变，以及「缺失」与「空串」不能撞成同一个指纹。
 */
class FingerprintsTest {

  @Test
  @DisplayName("同一组成分重复计算结果一致：这是幂等重试成立的前提")
  void deterministic() {
    String first = Fingerprints.of("10.1000/xyz", "graph neural networks", "2024");
    String second = Fingerprints.of("10.1000/xyz", "graph neural networks", "2024");
    assertThat(first).isEqualTo(second);
    assertThat(first).hasSize(64);
  }

  @Test
  @DisplayName("成分顺序变化会得到不同指纹：不接受把不同字段顺序当成同一份内容")
  void orderMatters() {
    assertThat(Fingerprints.of("a", "b")).isNotEqualTo(Fingerprints.of("b", "a"));
  }

  @Test
  @DisplayName("null 与空串不撞同一个指纹：字段缺失与字段为空是两件事")
  void nullDiffersFromEmpty() {
    assertThat(Fingerprints.of("a", null, "b")).isNotEqualTo(Fingerprints.of("a", "", "b"));
  }

  @Test
  @DisplayName("分段拼接使用分隔符：[\"ab\",\"c\"] 与 [\"a\",\"bc\"] 不得撞键")
  void separatorPreventsCollision() {
    assertThat(Fingerprints.of("ab", "c")).isNotEqualTo(Fingerprints.of("a", "bc"));
  }

  @Test
  @DisplayName("空成分列表也能算出指纹，不抛异常")
  void emptyPartsAllowed() {
    assertThat(Fingerprints.of()).hasSize(64);
    assertThat(Fingerprints.of((String[]) null)).hasSize(64);
  }

  @Test
  @DisplayName("同内容不同大小写视为不同指纹：规范化是上游职责，指纹层不做隐式归一")
  void noImplicitNormalization() {
    assertThat(Fingerprints.of("Graph")).isNotEqualTo(Fingerprints.of("graph"));
  }

  @Test
  @DisplayName("字节序列与字符串两条入口对同一内容给出同一结果")
  void byteAndStringEntryAgree() {
    assertThat(Fingerprints.sha256Hex("abc"))
        .isEqualTo(Fingerprints.sha256Hex("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  @Test
  @DisplayName("指纹为小写十六进制：大小写混用会让同一指纹在文本比较下不等")
  void lowercaseHex() {
    String value = Fingerprints.of("x");
    assertThat(value).isEqualTo(value.toLowerCase(java.util.Locale.ROOT));
    assertThat(value).matches("[0-9a-f]{64}");
  }
}
