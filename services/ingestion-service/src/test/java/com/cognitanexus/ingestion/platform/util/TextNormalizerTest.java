package com.cognitanexus.ingestion.platform.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 采集侧文本规范化的行为约束。
 *
 * <p>依据：FR02（多源采集）、BR-IMP-02、架构 V0.2 §6.2、编码规范 §5.4。
 *
 * <p>规范化结果会被算成指纹并用于跨源去重，所以这里的每条规则都不是「顺手清洗」：规则一旦
 * 变化，同一份内容会得到新指纹，历史指纹与新指纹混用会让去重静默失效。因此测试同时固定
 * 算法版本号。
 */
class TextNormalizerTest {

  @Nested
  @DisplayName("normalize：业务文本规范化")
  class Normalize {

    @Test
    @DisplayName("null 不抛异常，返回 null，由调用方区分「没有值」与「空串」")
    void nullStaysNull() {
      assertThat(TextNormalizer.normalize(null)).isNull();
    }

    @Test
    @DisplayName("大小写与首尾空白归一，使同一标题的不同写法得到同一指纹")
    void caseAndTrim() {
      assertThat(TextNormalizer.normalize("  Graph Neural Networks  "))
          .isEqualTo("graph neural networks");
    }

    @Test
    @DisplayName("连续空白折叠为单个空格：换行、制表符与多空格都算同一段空白")
    void collapsesWhitespace() {
      assertThat(TextNormalizer.normalize("graph\n\tneural   networks"))
          .isEqualTo("graph neural networks");
    }

    @Test
    @DisplayName("零宽字符与 BOM 按空白处理：它们肉眼不可见，留着会让「看起来一样」的两个标题算成两个指纹")
    void stripsInvisibleCharacters() {
      // \u200b 落在空白分支，因此折叠为单个空格而不是直接删除：
      // 直接删除会把 "graph zero-width neural" 里的词粘连起来，产生一个不存在的新标题。
      assertThat(TextNormalizer.normalize("graph\u200bneural\ufeff networks"))
          .isEqualTo("graph neural networks");
    }

    @Test
    @DisplayName("全角空格按空白处理：中文源里它常被当作普通空格使用")
    void ideographicSpaceIsWhitespace() {
      assertThat(TextNormalizer.normalize("学术\u3000成果")).isEqualTo("学术 成果");
    }

    @Test
    @DisplayName("开头与结尾的不可见字符被丢弃后，不残留前导或尾随空格")
    void noLeadingOrTrailingSpaceAfterStripping() {
      assertThat(TextNormalizer.normalize("\u200b  hello  \u200b")).isEqualTo("hello");
    }

    @Test
    @DisplayName("NFKC 先组合：\"café\" 的组合写法与预组合写法归一为同一结果")
    void combiningMarksRemoved() {
      String composed = "caf\u00e9";
      String decomposed = "cafe\u0301";
      assertThat(TextNormalizer.normalize(composed))
          .isEqualTo(TextNormalizer.normalize(decomposed));
      // NFKC 把分解形式重新组合为预组合字符，因此重音被保留而不是被剥掉：
      // 剥掉重音会让 "café" 与 "cafe" 撞成同一个指纹，那是另一个词或另一个人名。
      assertThat(TextNormalizer.normalize(decomposed)).isEqualTo("caf\u00e9");
      assertThat(TextNormalizer.normalize(decomposed))
          .isNotEqualTo(TextNormalizer.normalize("cafe"));
    }

    @Test
    @DisplayName("中文文本保持原样（仅折叠空白），不被错误地按西文规则处理")
    void chinesePreserved() {
      assertThat(TextNormalizer.normalize("面向数据分析的 Agent 系统")).isEqualTo("面向数据分析的 agent 系统");
    }

    @Test
    @DisplayName("全角字符经 NFKC 归一为半角，避免同一标识的两种宽度写法产生两个指纹")
    void fullWidthNormalized() {
      assertThat(TextNormalizer.normalize("ＡＢＣ１２３")).isEqualTo("abc123");
    }
  }

  @Nested
  @DisplayName("normalizeStableIdentifier：稳定标识归一")
  class StableIdentifier {

    @Test
    @DisplayName("DOI 的三种常见写法归一为同一结果——否则同一篇论文会被登记成两个标识")
    void doiPrefixesUnified() {
      String bare = TextNormalizer.normalizeStableIdentifier("10.1000/xyz");
      assertThat(TextNormalizer.normalizeStableIdentifier("DOI:10.1000/xyz")).isEqualTo(bare);
      assertThat(TextNormalizer.normalizeStableIdentifier("https://doi.org/10.1000/xyz"))
          .isEqualTo(bare);
      assertThat(TextNormalizer.normalizeStableIdentifier("http://doi.org/10.1000/xyz"))
          .isEqualTo(bare);
    }

    @Test
    @DisplayName("大小写归一：DOI 大小写敏感语义由注册机构定义，但入库比较统一转小写")
    void lowerCased() {
      assertThat(TextNormalizer.normalizeStableIdentifier("10.1000/XYZ")).isEqualTo("10.1000/xyz");
    }

    @Test
    @DisplayName("内部空白被剥离：从网页复制 DOI 常带换行")
    void internalWhitespaceRemoved() {
      assertThat(TextNormalizer.normalizeStableIdentifier("10.1000/ \n xyz"))
          .isEqualTo("10.1000/xyz");
    }

    @Test
    @DisplayName("null 返回 null：调用方据此判定「没有稳定标识」，而不是「标识为空串」")
    void nullReturnsNull() {
      assertThat(TextNormalizer.normalizeStableIdentifier(null)).isNull();
    }

    @Test
    @DisplayName("空白串与仅含前缀的串返回 null，不返回空串")
    void blankReturnsNull() {
      assertThat(TextNormalizer.normalizeStableIdentifier("   ")).isNull();
      assertThat(TextNormalizer.normalizeStableIdentifier("doi:")).isNull();
      assertThat(TextNormalizer.normalizeStableIdentifier("https://doi.org/")).isNull();
    }

    @Test
    @DisplayName("非 DOI 前缀的标识原样保留，不做无依据的前缀猜测")
    void otherIdentifiersUntouched() {
      assertThat(TextNormalizer.normalizeStableIdentifier("ARXIV:2101.00001"))
          .isEqualTo("arxiv:2101.00001");
    }
  }

  @Test
  @DisplayName("规范化算法带版本号：规则变化后旧指纹必须能被识别为旧版")
  void versionIsDeclared() {
    assertThat(TextNormalizer.VERSION).isEqualTo("NORM_V1");
  }
}
