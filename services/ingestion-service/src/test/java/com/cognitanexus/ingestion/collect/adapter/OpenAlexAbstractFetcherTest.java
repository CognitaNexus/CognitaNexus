package com.cognitanexus.ingestion.collect.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 摘要倒排索引还原与请求组装。
 *
 * <p>守的是这类错误：倒排索引按「词 → 位置列表」存，直接当字符串用会在页面上显示一段乱序 JSON；
 * 而按词字典序拼接会得到读不通的句子，并且看起来像「来源的摘要质量差」而不是程序错。两种错都
 * 不会报错，只能靠断言位置顺序来发现。
 */
class OpenAlexAbstractFetcherTest {

  private static final String UA = "CognitaNexus/0.2 (mailto:cn-admin@example.edu.cn)";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static OpenAlexAbstractFetcher fetcher(String apiKey) {
    return new OpenAlexAbstractFetcher(
        MAPPER, HttpClient.newHttpClient(), UA, 4 * 1024 * 1024, apiKey);
  }

  private static JsonNode json(String raw) {
    try {
      return MAPPER.readTree(raw);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  @Test
  @DisplayName("按位置还原成原文，而不是按词序拼接")
  void rebuildsByPosition() {
    // 词按字典序恰好与原文相反：按字段顺序拼会得到 "world hello"，测试就是为守住这一点
    JsonNode index =
        json(
            """
            {"world": [1], "hello": [0]}
            """);
    assertThat(OpenAlexAbstractFetcher.reconstruct(index)).isEqualTo("hello world");
  }

  @Test
  @DisplayName("同一个词出现多次按各自位置落位")
  void handlesRepeatedWords() {
    JsonNode index =
        json(
            """
            {"the": [0, 3], "cat": [1], "sat": [2], "mat": [4]}
            """);
    assertThat(OpenAlexAbstractFetcher.reconstruct(index)).isEqualTo("the cat sat the mat");
  }

  @Test
  @DisplayName("缺失与空对象要分开：缺失是「来源没给」，空对象是「给了但没内容」")
  void distinguishesMissingFromEmpty() {
    assertThat(OpenAlexAbstractFetcher.reconstruct(null)).isNull();
    assertThat(OpenAlexAbstractFetcher.reconstruct(json("{}"))).isEmpty();
    assertThat(OpenAlexAbstractFetcher.reconstruct(json("null"))).isNull();
  }

  @Test
  @DisplayName("位置有空洞时按已给的位置拼接，不补占位符")
  void handlesGaps() {
    JsonNode index =
        json(
            """
            {"alpha": [0], "omega": [7]}
            """);
    assertThat(OpenAlexAbstractFetcher.reconstruct(index)).isEqualTo("alpha omega");
  }

  @Test
  @DisplayName("同一位置被两个词占用时保留先到的，不静默改写原文")
  void keepsFirstWordOnPositionCollision() {
    JsonNode index =
        json(
            """
            {"first": [0], "second": [0]}
            """);
    assertThat(OpenAlexAbstractFetcher.reconstruct(index)).isEqualTo("first");
  }

  @Test
  @DisplayName("配了 API key 就带上 api_key，未配则不带空参数")
  void apiKeyHandling() {
    assertThat(fetcher("k-abc").buildUrl(List.of("10.1/a"))).contains("api_key=k-abc");
    assertThat(fetcher(null).buildUrl(List.of("10.1/a"))).doesNotContain("api_key");
    assertThat(fetcher("   ").buildUrl(List.of("10.1/a"))).doesNotContain("api_key");
  }

  @Test
  @DisplayName("一批 DOI 用 %7C 批量取，只选摘要需要的三个字段")
  void batchFilterUsesEncodedPipeAndNarrowSelect() {
    String url = fetcher(null).buildUrl(List.of("10.1/a", "10.1/b"));
    assertThat(url).contains("filter=doi:10.1%2Fa%7C10.1%2Fb");
    assertThat(url).contains("select=id,doi,abstract_inverted_index");
    assertThat(url).doesNotContain("referenced_works");
  }
}
