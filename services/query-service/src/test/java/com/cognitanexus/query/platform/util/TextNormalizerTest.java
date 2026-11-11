package com.cognitanexus.query.platform.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 检索侧文本规范化与 core/ingestion 的算法一致性约束。
 *
 * <p>依据：架构 V0.2 §9.1。投影里的 title_norm 由 core 用同规则算出；若本服务的规则有任何
 * 一处不同，关键词检索就会静默漏结果，且从响应上完全看不出原因。
 *
 * <p>因此本测试不只检查「函数算得对」，而是把一张<b>跨服务对照表</b>钉在这里：同样的输入必须
 * 得到同样的输出。ingestion 侧的 TextNormalizerTest 里写着同一张表的等价断言，任何一侧改了
 * 规则都会让那一侧的测试失败，从而强制两侧同步升级算法版本。
 */
class TextNormalizerTest {

  /** 跨服务对照表：与 ingestion / core 侧必须逐条一致。 */
  private static Map<String, String> referenceTable() {
    Map<String, String> table = new LinkedHashMap<>();
    table.put("  Graph Neural Networks  ", "graph neural networks");
    table.put("graph\n\tneural   networks", "graph neural networks");
    table.put("cafe\u0301", "caf\u00e9");
    table.put("ＡＢＣ１２３", "abc123");
    table.put("学术\u3000成果", "学术 成果");
    table.put("graph\u200bneural", "graph neural");
    table.put("\u200b  hello  \u200b", "hello");
    return table;
  }

  @Test
  @DisplayName("对照表逐条成立：任一条失败说明本服务与投影侧的归一规则已分叉")
  void matchesCoreProjectionRules() {
    referenceTable()
        .forEach(
            (input, expected) ->
                assertThat(TextNormalizer.normalize(input))
                    .as("归一化 %s", input)
                    .isEqualTo(expected));
  }

  @Test
  @DisplayName("null 透传为 null")
  void nullStaysNull() {
    assertThat(TextNormalizer.normalize(null)).isNull();
  }

  @Test
  @DisplayName("算法版本与 core 侧同号：版本不同意味着检索结果不可比")
  void versionMatchesCore() {
    assertThat(TextNormalizer.VERSION).isEqualTo("NORM_V1");
  }

  @Test
  @DisplayName("稳定标识归一的三种 DOI 写法落到同一个键")
  void stableIdentifierUnified() {
    String bare = TextNormalizer.normalizeStableIdentifier("10.1000/xyz");
    assertThat(TextNormalizer.normalizeStableIdentifier("DOI:10.1000/xyz")).isEqualTo(bare);
    assertThat(TextNormalizer.normalizeStableIdentifier("https://doi.org/10.1000/xyz"))
        .isEqualTo(bare);
    assertThat(TextNormalizer.normalizeStableIdentifier("10.1000/ XYZ ")).isEqualTo(bare);
    assertThat(TextNormalizer.normalizeStableIdentifier("  ")).isNull();
    assertThat(TextNormalizer.normalizeStableIdentifier(null)).isNull();
  }
}
