package com.cognitanexus.query.network.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognitanexus.query.network.domain.ParityDiffReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 新旧查询对照器的比较语义。
 *
 * <p>对照器的价值全部取决于「什么算不一致」这一条定义，因此这里逐项锁住：
 *
 * <ul>
 *   <li>业务字段（版本、节点、边、路径、跳数、可达性、截断相关结果）必须逐字段比较，
 *       列表顺序也算契约；
 *   <li>只有墙钟时间 {@code generatedAt} 被排除；{@code withinLimits}（加载规模）<b>参与比较</b>。
 *       它曾被当成「实现细节」排除，实际后果是图侧用「路径节点数/跳数」冒充加载规模却永远不会被
 *       发现——对照器替一个错口径背书；
 *   <li>{@code Long 1} 与 {@code Integer 1} 是同一个业务值，类型差异不算不一致；
 *   <li>字段缺失比「值不同」更严重，必须单独报告为 {@code <missing>}。
 * </ul>
 */
class NetworkParityComparatorTest {

  private final NetworkParityComparator comparator = new NetworkParityComparator();

  private static Map<String, Object> neighborhood(String... scholars) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("graphVersion", "g42-r120");
    response.put("ruleVersion", "NETWORK_V1");
    response.put("dataNatureScope", "REAL");
    response.put("topicId", null);
    response.put("generatedAt", java.time.Instant.parse("2026-11-11T10:00:00Z"));
    response.put("withinLimits", Map.of("nodes", 3, "edges", 2));
    List<Map<String, Object>> nodes = new ArrayList<>();
    for (String scholar : scholars) {
      Map<String, Object> node = new LinkedHashMap<>();
      node.put("scholarId", scholar);
      node.put("scholarName", "Name-" + scholar);
      node.put("qualifiedAchievementCount", 1L);
      nodes.add(node);
    }
    response.put("nodes", nodes);
    List<Map<String, Object>> edges = new ArrayList<>();
    for (int i = 0; i + 1 < scholars.length; i++) {
      Map<String, Object> edge = new LinkedHashMap<>();
      edge.put("source", scholars[i]);
      edge.put("target", scholars[i + 1]);
      edge.put("strength", 1L);
      edges.add(edge);
    }
    response.put("edges", edges);
    return response;
  }

  private static Map<String, Object> copy(Map<String, Object> source) {
    Map<String, Object> copy = new LinkedHashMap<>(source);
    // 只有墙钟时间不同：这是唯一的诊断字段。
    copy.put("generatedAt", java.time.Instant.parse("2026-11-11T11:11:11Z"));
    return copy;
  }

  @Test
  @DisplayName("只有 generatedAt 不同：判定一致（墙钟时间不参与比较）")
  void diagnosticFieldsAreIgnored() {
    Map<String, Object> postgres = neighborhood("a", "b", "c");
    Map<String, Object> graph = copy(postgres);

    ParityDiffReport report = comparator.compare("neighborhood", "seed=a", postgres, graph);

    assertThat(report.matched()).isTrue();
    assertThat(report.differences()).isEmpty();
    assertThat(report.comparedNodes()).isEqualTo(3);
    assertThat(report.comparedEdges()).isEqualTo(2);
    assertThat(report.summary()).contains("对照一致").contains("节点 3");
  }

  @Test
  @DisplayName("回归：withinLimits 不同即不一致（加载规模诊断不再被屏蔽）")
  void withinLimitsDifferenceIsReported() {
    Map<String, Object> postgres = neighborhood("a", "b", "c");
    Map<String, Object> graph = copy(postgres);
    graph.put("withinLimits", Map.of("nodes", 1, "edges", 0));

    ParityDiffReport report = comparator.compare("shortestPath", "a->c", postgres, graph);

    assertThat(report.matched()).isFalse();
    List<String> fields =
        report.differences().stream().map(ParityDiffReport.FieldDifference::field).toList();
    assertThat(fields).contains("withinLimits.nodes", "withinLimits.edges");
  }

  @Test
  @DisplayName("回归：一侧缺 withinLimits 也必须报告（少字段比值不同更严重）")
  void missingWithinLimitsIsReported() {
    Map<String, Object> postgres = neighborhood("a", "b");
    Map<String, Object> graph = copy(postgres);
    graph.remove("withinLimits");

    ParityDiffReport report = comparator.compare("neighborhood", "seed=a", postgres, graph);

    assertThat(report.matched()).isFalse();
    assertThat(report.differences())
        .singleElement()
        .satisfies(difference -> assertThat(difference.field()).isEqualTo("withinLimits"));
  }

  @Test
  @DisplayName("节点顺序不同即不一致：顺序属于契约，抖动会被读成「图在变」")
  void nodeOrderDifferenceIsReported() {
    Map<String, Object> postgres = neighborhood("a", "b");
    Map<String, Object> graph = neighborhood("b", "a");

    ParityDiffReport report = comparator.compare("neighborhood", "seed=a", postgres, graph);

    assertThat(report.matched()).isFalse();
    assertThat(report.differences())
        .isNotEmpty()
        .anySatisfy(difference -> assertThat(difference.field()).isEqualTo("nodes[0].scholarId"));
    assertThat(report.summary()).contains("对照不一致");
  }

  @Test
  @DisplayName("边强度差异定位到具体边：不只是一个布尔")
  void edgeStrengthDifferenceIsReportedWithPath() {
    Map<String, Object> postgres = neighborhood("a", "b");
    Map<String, Object> graph = neighborhood("a", "b");
    graph.put("edges", List.of(Map.of("source", "a", "target", "b", "strength", 2L)));

    ParityDiffReport report = comparator.compare("neighborhood", "seed=a", postgres, graph);

    assertThat(report.matched()).isFalse();
    assertThat(report.differences())
        .singleElement()
        .satisfies(
            difference -> {
              assertThat(difference.field()).isEqualTo("edges[0].strength");
              assertThat(difference.postgresValue()).isEqualTo("1");
              assertThat(difference.graphValue()).isEqualTo("2");
            });
  }

  @Test
  @DisplayName("数值类型不同但数值相等：不算不一致（Long 1 与 Integer 1）")
  void numericTypeDifferenceIsNotADifference() {
    Map<String, Object> postgres = neighborhood("a", "b");
    Map<String, Object> graph = copy(postgres);
    graph.put("publishedRevision", 120); // Integer，而 PG 侧是 Long 场景
    postgres.put("publishedRevision", 120L);

    assertThat(comparator.compare("neighborhood", "seed=a", postgres, graph).matched()).isTrue();
  }

  @Test
  @DisplayName("少一个字段：报告 <missing>，这是最严重的一类不一致")
  void missingFieldIsReported() {
    Map<String, Object> postgres = neighborhood("a", "b");
    Map<String, Object> graph = copy(postgres);
    graph.remove("ruleVersion");

    ParityDiffReport report = comparator.compare("neighborhood", "seed=a", postgres, graph);

    assertThat(report.matched()).isFalse();
    assertThat(report.differences())
        .singleElement()
        .satisfies(
            difference -> {
              assertThat(difference.field()).isEqualTo("ruleVersion");
              assertThat(difference.graphValue()).isEqualTo("<missing>");
            });
  }

  @Test
  @DisplayName("多一个字段同样不一致：调用方按字段取数会拿到意外内容")
  void extraFieldIsReported() {
    Map<String, Object> postgres = neighborhood("a", "b");
    Map<String, Object> graph = copy(postgres);
    graph.put("debug", "x");

    assertThat(comparator.compare("neighborhood", "seed=a", postgres, graph).differences())
        .singleElement()
        .satisfies(difference -> assertThat(difference.field()).isEqualTo("debug"));
  }

  @Test
  @DisplayName("路径响应：跳数、路径序列、逐跳节点任一不同都报告")
  void pathDifferencesAreReported() {
    Map<String, Object> postgres = new LinkedHashMap<>();
    postgres.put("from", "a");
    postgres.put("to", "z");
    postgres.put("reachable", true);
    postgres.put("hops", 2);
    postgres.put("path", List.of("a", "b", "z"));
    postgres.put("generatedAt", java.time.Instant.EPOCH);
    postgres.put("withinLimits", Map.of("nodes", 5, "edges", 4));

    Map<String, Object> graph = copy(postgres);
    graph.put("hops", 3);
    graph.put("path", List.of("a", "b", "c", "z"));

    ParityDiffReport report = comparator.compare("shortestPath", "a->z", postgres, graph);

    assertThat(report.matched()).isFalse();
    List<String> fields =
        report.differences().stream().map(ParityDiffReport.FieldDifference::field).toList();
    assertThat(fields).contains("hops", "path.size", "path[2]");
  }

  @Test
  @DisplayName("空图与空图：一致，但对照规模为 0 也要如实记录")
  void emptyResponsesMatch() {
    ParityDiffReport report =
        comparator.compare(
            "neighborhood", "seed=a", Map.of("nodes", List.of()), Map.of("nodes", List.of()));

    assertThat(report.matched()).isTrue();
    assertThat(report.comparedNodes()).isZero();
    assertThat(report.comparedEdges()).isZero();
  }

  @Test
  @DisplayName("null 与 null 一致；一侧为 null 时报告字段差异")
  void nullsAreComparedSafely() {
    Map<String, Object> postgres = new LinkedHashMap<>();
    postgres.put("topicId", null);
    Map<String, Object> graph = new LinkedHashMap<>();
    graph.put("topicId", null);
    assertThat(comparator.compare("neighborhood", "k", postgres, graph).matched()).isTrue();

    graph.put("topicId", "t1");
    assertThat(comparator.compare("neighborhood", "k", postgres, graph).differences())
        .singleElement()
        .satisfies(difference -> assertThat(difference.field()).isEqualTo("topicId"));
  }

  @Test
  @DisplayName("差异过多时报告有上限，但「未列出条数」计入不一致，不能被当成一致")
  void differenceReportIsBoundedButStillMismatched() {
    Map<String, Object> postgres = new LinkedHashMap<>();
    Map<String, Object> graph = new LinkedHashMap<>();
    for (int i = 0; i < 40; i++) {
      postgres.put("field" + i, "postgres-" + i);
      graph.put("field" + i, "graph-" + i);
    }

    ParityDiffReport report = comparator.compare("neighborhood", "seed=a", postgres, graph);

    assertThat(report.matched()).isFalse();
    assertThat(report.differences()).hasSize(NetworkParityComparator.MAX_REPORTED_DIFFERENCES);
    assertThat(report.truncatedDifferences()).isPositive();
    assertThat(report.summary()).contains("另有").contains("处差异未列出");
  }
}
