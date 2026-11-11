package com.cognitanexus.ingestion.collect.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 引用抓取的请求 URL 组装。
 *
 * <p>守两件曾真实发生的事：一是抓取器只发 {@code mailto}、从不发 {@code api_key}，导致注册了免费
 * 密钥也用不上，一直在共享的匿名预算里挨饿；二是 OpenAlex 2026 起按美元计费，剩余额度头从
 * {@code X-RateLimit-Remaining} 变成 {@code X-RateLimit-Remaining-Usd}，旧代码读不到值，
 * 「额度不足就改走 Crossref」的保护形同虚设。
 */
class OpenAlexCitationFetcherUrlTest {

  private static final String UA = "CognitaNexus/0.2 (mailto:cn-admin@example.edu.cn)";

  private static OpenAlexCitationFetcher fetcher(String apiKey) {
    return new OpenAlexCitationFetcher(
        new ObjectMapper(), HttpClient.newHttpClient(), UA, 4 * 1024 * 1024, apiKey);
  }

  @Test
  @DisplayName("配了 API key 就带上 api_key 参数")
  void apiKeyIsAppended() {
    assertThat(fetcher("k-abc").buildUrl(List.of("10.1038/nature12373"))).contains("api_key=k-abc");
  }

  @Test
  @DisplayName("未配 key 时不发空的 api_key，避免被上游判成无效密钥")
  void apiKeyIsOmittedWhenAbsent() {
    assertThat(fetcher(null).buildUrl(List.of("10.1038/nature12373"))).doesNotContain("api_key");
    assertThat(fetcher("   ").buildUrl(List.of("10.1038/nature12373"))).doesNotContain("api_key");
  }

  @Test
  @DisplayName("一批 DOI 用 %7C 批量取，摊薄每次请求的花费")
  void batchFilterUsesEncodedPipe() {
    String url = fetcher(null).buildUrl(List.of("10.1/a", "10.1/b"));

    assertThat(url).contains("filter=doi:10.1%2Fa%7C10.1%2Fb").contains("per-page=2");
  }

  @Test
  @DisplayName("联系邮箱仍作为 mailto 传出，与 key 并存")
  void mailtoAccompaniesApiKey() {
    String url = fetcher("k-abc").buildUrl(List.of("10.1/a"));

    assertThat(url).contains("mailto=cn-admin%40example.edu.cn").contains("api_key=k-abc");
  }
}
