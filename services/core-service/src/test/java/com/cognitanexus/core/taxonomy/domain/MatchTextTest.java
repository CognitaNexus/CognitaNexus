package com.cognitanexus.core.taxonomy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 匹配文本规范化。
 *
 * <p>这一层是词表能否用的关键，也是最容易被当成"顺手写个 contains 就行"的一层。实测过的两种
 * 失败形态都写成了用例：短判别词在无关词里命中（internal 里的 rna）、以及改成整词匹配后词干
 * 判别词集体失效（catalys 匹配不到 catalysis）。两者都由"按词起始的前缀匹配"覆盖。
 */
class MatchTextTest {

  @Nested
  @DisplayName("规范化")
  class Normalize {

    @Test
    void lowercasesAndPadsWithSpaces() {
      assertThat(MatchText.normalize("Alpha")).isEqualTo(" alpha ");
    }

    @Test
    void hyphenAndPunctuationActAsWordSeparators() {
      assertThat(MatchText.normalize("Graph-Neural-Network-Based")).isEqualTo(" graph neural network based ");
      assertThat(MatchText.normalize("a, b; c")).isEqualTo(" a b c ");
    }

    @Test
    void collapsesConsecutiveSeparators() {
      assertThat(MatchText.normalize("a  --  b")).isEqualTo(" a b ");
    }

    @Test
    void keepsChineseContiguousWithoutInsertingSpaces() {
      assertThat(MatchText.normalize("基于深度学习的识别")).isEqualTo(" 基于深度学习的识别 ");
    }

    @Test
    void nullBecomesSingleSpace() {
      assertThat(MatchText.normalize(null)).isEqualTo(" ");
    }
  }

  @Nested
  @DisplayName("查找串")
  class Needle {

    @Test
    void asciiTermGetsLeadingSpaceSoItMustStartAWord() {
      String text = MatchText.normalize("Internal Revenue Service");
      assertThat(text).doesNotContain(MatchText.needle("rna"));
      assertThat(text).contains(MatchText.needle("internal"));
    }

    @Test
    void asciiTermStillMatchesWordForms() {
      String text = MatchText.normalize("Catalysis and catalytic converters");
      assertThat(text).contains(MatchText.needle("catalys"));
    }

    @Test
    void chineseTermIsMatchedAsPlainSubstring() {
      assertThat(MatchText.needle("深度")).isEqualTo("深度");
      assertThat(MatchText.normalize("深度学习")).contains(MatchText.needle("深度"));
    }

    @Test
    void multiWordAsciiTermToleratesInflectionOnLastWord() {
      String text = MatchText.normalize("Deep neural networks for imaging");
      assertThat(text).contains(MatchText.needle("neural network"));
    }
  }

  @Nested
  @DisplayName("字符判定")
  class Classify {

    @Test
    void detectsAsciiAndCjk() {
      assertThat(MatchText.isAscii("catalys")).isTrue();
      assertThat(MatchText.isAscii("催化")).isFalse();
      assertThat(MatchText.hasCjk("催化")).isTrue();
      assertThat(MatchText.hasCjk("catalys")).isFalse();
    }
  }
}
