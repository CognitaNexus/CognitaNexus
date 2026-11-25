package com.cognitanexus.query.network.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 路由模式的解析与语义。
 *
 * <p>锁住两件事：
 *
 * <ul>
 *   <li>合法写法必须宽容（大小写、空白、连字符来自 YAML 与环境变量两种来源）；
 *   <li>非法写法必须失败。静默回落默认值会让「配成图模式但一直走 PG」看起来一切正常——
 *       而这正是灰度切换中最危险的误会。
 * </ul>
 */
class GraphQueryModeTest {

  @Test
  @DisplayName("缺省为空一律是 POSTGRES_ONLY：不配置就等于今天的行为")
  void blankDefaultsToPostgresOnly() {
    assertThat(GraphQueryMode.parse(null)).isEqualTo(GraphQueryMode.POSTGRES_ONLY);
    assertThat(GraphQueryMode.parse("")).isEqualTo(GraphQueryMode.POSTGRES_ONLY);
    assertThat(GraphQueryMode.parse("   ")).isEqualTo(GraphQueryMode.POSTGRES_ONLY);
  }

  @Test
  @DisplayName("大小写、前后空白与连字符写法都能解析")
  void parseIsLenientAboutFormatting() {
    assertThat(GraphQueryMode.parse("postgres_only")).isEqualTo(GraphQueryMode.POSTGRES_ONLY);
    assertThat(GraphQueryMode.parse(" shadow-compare ")).isEqualTo(GraphQueryMode.SHADOW_COMPARE);
    assertThat(GraphQueryMode.parse("Neo4j-With-Pg-Fallback"))
        .isEqualTo(GraphQueryMode.NEO4J_WITH_PG_FALLBACK);
    assertThat(GraphQueryMode.parse("neo4j strict")).isEqualTo(GraphQueryMode.NEO4J_STRICT);
  }

  @Test
  @DisplayName("未知取值直接失败并列出全部合法值，不回落默认值")
  void unknownValueFails() {
    assertThatThrownBy(() -> GraphQueryMode.parse("neo4j"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cn.query.graph.query.mode")
        .hasMessageContaining("NEO4J_STRICT");
  }

  @Test
  @DisplayName("模式语义：只有两种 NEO4J 模式由图库返回，只有 STRICT 强制要求图库")
  void modeSemantics() {
    assertThat(GraphQueryMode.POSTGRES_ONLY.servesFromGraph()).isFalse();
    assertThat(GraphQueryMode.POSTGRES_ONLY.shadows()).isFalse();
    assertThat(GraphQueryMode.POSTGRES_ONLY.requiresGraph()).isFalse();

    assertThat(GraphQueryMode.SHADOW_COMPARE.shadows()).isTrue();
    // 对照模式以 PG 为权威返回，因此不算「由图库返回」。
    assertThat(GraphQueryMode.SHADOW_COMPARE.servesFromGraph()).isFalse();
    assertThat(GraphQueryMode.SHADOW_COMPARE.requiresGraph()).isFalse();

    assertThat(GraphQueryMode.NEO4J_WITH_PG_FALLBACK.servesFromGraph()).isTrue();
    assertThat(GraphQueryMode.NEO4J_WITH_PG_FALLBACK.requiresGraph()).isFalse();

    assertThat(GraphQueryMode.NEO4J_STRICT.servesFromGraph()).isTrue();
    assertThat(GraphQueryMode.NEO4J_STRICT.requiresGraph()).isTrue();
  }

  @Test
  @DisplayName("枚举名是外部契约：配置值与环境变量按名字书写，改名等于破坏配置")
  void enumNamesAreStable() {
    assertThat(Arrays.stream(GraphQueryMode.values()).map(Enum::name))
        .containsExactly(
            "POSTGRES_ONLY", "SHADOW_COMPARE", "NEO4J_WITH_PG_FALLBACK", "NEO4J_STRICT");
  }
}
