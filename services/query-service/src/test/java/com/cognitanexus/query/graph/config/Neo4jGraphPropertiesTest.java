package com.cognitanexus.query.graph.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 图投影配置的默认值与自检。 */
class Neo4jGraphPropertiesTest {

  private static Neo4jGraphProperties minimal() {
    return new Neo4jGraphProperties(
        false, null, null, null, null, null, null, 0, 0, 0, 0, 0L, null);
  }

  @Test
  @DisplayName("默认关闭，且不依赖任何图库口令即可构造")
  void defaultsAreSafeAndDisabled() {
    Neo4jGraphProperties properties = minimal();

    assertThat(properties.enabled()).isFalse();
    assertThat(properties.uri()).isEqualTo("bolt://127.0.0.1:7687");
    assertThat(properties.username()).isEqualTo("neo4j");
    assertThat(properties.password()).isEmpty();
    assertThat(properties.database()).isEqualTo("neo4j");
    assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
    assertThat(properties.queryTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(properties.maxAuthorsPerWorkForMaterializedEdge()).isEqualTo(20);
    assertThat(properties.maxEvidenceWorkIdsPerEdge()).isEqualTo(50);
    assertThat(properties.maxNodes()).isEqualTo(20_000);
    assertThat(properties.maxEdges()).isEqualTo(100_000);
    assertThat(properties.budgetMillis()).isEqualTo(1_000L);
    assertThat(properties.scope()).isEqualTo("query-public");

    // 关键：默认配置下 validate 不抛异常，服务不需要图库也能启动。
    assertThatCode(properties::validate).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("显式配置被保留；负数/零值回落到安全默认值")
  void explicitValuesAndNonPositiveFallbacks() {
    Neo4jGraphProperties properties =
        new Neo4jGraphProperties(
            true,
            "neo4j+s://ges.cn-north-4.myhuaweicloud.com:7687",
            "cn_graph",
            "secret",
            "neo4j",
            Duration.ofSeconds(7),
            Duration.ofSeconds(11),
            200,
            100,
            5_000,
            20_000,
            2_500L,
            "query-public");

    assertThat(properties.uri()).startsWith("neo4j+s://");
    assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(7));
    assertThat(properties.queryTimeout()).isEqualTo(Duration.ofSeconds(11));
    assertThat(properties.maxAuthorsPerWorkForMaterializedEdge()).isEqualTo(200);
    assertThat(properties.maxEvidenceWorkIdsPerEdge()).isEqualTo(100);
    assertThat(properties.budgetMillis()).isEqualTo(2_500L);
    assertThatCode(properties::validate).doesNotThrowAnyException();

    Neo4jGraphProperties negative =
        new Neo4jGraphProperties(
            false,
            "bolt://127.0.0.1:7687",
            "neo4j",
            "",
            "neo4j",
            Duration.ofSeconds(-1),
            Duration.ZERO,
            -5,
            -5,
            -1,
            -1,
            -1L,
            " ");
    assertThat(negative.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
    assertThat(negative.queryTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(negative.maxAuthorsPerWorkForMaterializedEdge()).isEqualTo(20);
    assertThat(negative.maxEvidenceWorkIdsPerEdge()).isEqualTo(50);
    assertThat(negative.scope()).isEqualTo("query-public");
  }

  @Test
  @DisplayName("启用但没有口令时拒绝启动：空口令在开发机上能用，在服务器上就是未授权入口")
  void enabledWithoutPasswordFailsFast() {
    Neo4jGraphProperties properties =
        new Neo4jGraphProperties(
            true,
            "bolt://127.0.0.1:7687",
            "neo4j",
            "  ",
            "neo4j",
            Duration.ofSeconds(3),
            Duration.ofSeconds(10),
            20,
            50,
            20_000,
            100_000,
            1_000L,
            "query-public");

    assertThatThrownBy(properties::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CN_NEO4J_PASSWORD");
  }

  @Test
  @DisplayName("非法 URI 方案、超阈值与超大证据上限都被拒绝，而不是被静默纠正")
  void invalidValuesAreRejected() {
    Neo4jGraphProperties httpUri =
        new Neo4jGraphProperties(
            true,
            "http://127.0.0.1:7474",
            "neo4j",
            "secret",
            "neo4j",
            Duration.ofSeconds(3),
            Duration.ofSeconds(10),
            20,
            50,
            20_000,
            100_000,
            1_000L,
            "query-public");
    assertThatThrownBy(httpUri::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cn.query.graph.uri");

    Neo4jGraphProperties tooManyAuthors =
        new Neo4jGraphProperties(
            true,
            "bolt://127.0.0.1:7687",
            "neo4j",
            "secret",
            "neo4j",
            Duration.ofSeconds(3),
            Duration.ofSeconds(10),
            201,
            50,
            20_000,
            100_000,
            1_000L,
            "query-public");
    assertThatThrownBy(tooManyAuthors::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("max-authors-per-work-for-materialized-edge");

    Neo4jGraphProperties tooMuchEvidence =
        new Neo4jGraphProperties(
            true,
            "bolt://127.0.0.1:7687",
            "neo4j",
            "secret",
            "neo4j",
            Duration.ofSeconds(3),
            Duration.ofSeconds(10),
            20,
            1_001,
            20_000,
            100_000,
            1_000L,
            "query-public");
    assertThatThrownBy(tooMuchEvidence::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("max-evidence-work-ids-per-edge");
  }

  @Test
  @DisplayName("作者数阈值决定是否物化作者对边：单人成果不建边，超阈值不建边")
  void materializationThreshold() {
    Neo4jGraphProperties properties = minimal();

    assertThat(properties.shouldMaterializeEdges(0)).isFalse();
    assertThat(properties.shouldMaterializeEdges(1)).isFalse();
    assertThat(properties.shouldMaterializeEdges(2)).isTrue();
    assertThat(properties.shouldMaterializeEdges(20)).isTrue();
    assertThat(properties.shouldMaterializeEdges(21)).isFalse();
    // 20 位作者的组合数是 190，21 位是 210：阈值之外完全不建边，避免组合爆炸。
    assertThat(properties.shouldMaterializeEdges(100)).isFalse();
  }

  @Test
  @DisplayName("describe() 不泄露口令")
  void describeNeverLeaksPassword() {
    Neo4jGraphProperties properties =
        new Neo4jGraphProperties(
            true,
            "bolt://127.0.0.1:7687",
            "neo4j",
            "top-secret-password",
            "neo4j",
            Duration.ofSeconds(3),
            Duration.ofSeconds(10),
            20,
            50,
            20_000,
            100_000,
            1_000L,
            "query-public");

    assertThat(properties.describe()).doesNotContain("top-secret-password");
    assertThat(properties.describe()).contains("enabled=true", "query-public");
  }
}
