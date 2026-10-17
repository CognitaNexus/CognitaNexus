package com.cognitanexus.ingestion.collect.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cognitanexus.ingestion.collect.domain.AdapterKind;
import com.cognitanexus.ingestion.collect.domain.CollectModels.CollectJob;
import com.cognitanexus.ingestion.collect.domain.CollectModels.FetchPage;
import com.cognitanexus.ingestion.collect.domain.CollectModels.SourceRunConfig;
import com.cognitanexus.ingestion.collect.domain.JobStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 来源失败形态与游标的可控替身验证（T30／WB-13）。
 *
 * <p>为什么要起一个真实的 HTTP 服务而不是 mock 掉 HttpClient：被测的东西恰恰是「收到 429 且带
 * Retry-After 时，适配器有没有把它读出来」——mock 掉响应对象等于把要验证的事实自己写一遍。
 * 替身只监听 127.0.0.1 的随机端口，不访问任何公网来源；这也是 WB-13 明确要求的做法
 * （「用可控服务替身测试，不故意对公开网站制造压力」）。
 *
 * <p>覆盖的失败形态：429 + Retry-After（秒数）／503 无头／响应超限／非法 JSON／连接不可用。
 * 另外验证游标语义：按 offset 翻页不跳页、重复抓同一游标得到同一批记录（幂等的前提），
 * 以及末页之后不再发空请求。
 */
class CrossrefAdapterStubTest {

  private HttpServer server;
  private String baseEndpoint;
  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpClient http = HttpClient.newHttpClient();

  /** 当前替身的行为：由每个用例设定。 */
  private final AtomicReference<String> mode = new AtomicReference<>("ok");

  /** 收到的请求，按顺序记录 offset 参数，用于断言「没有重复翻页」。 */
  private final List<String> requestedOffsets = new ArrayList<>();

  private final AtomicInteger hits = new AtomicInteger();

  @BeforeEach
  void startStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/works", this::handle);
    server.start();
    baseEndpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/works";
  }

  @AfterEach
  void stopStub() {
    server.stop(0);
  }

  // ---------------------------------------------------------------------------
  // 失败形态
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("429 带 Retry-After：状态码与等待秒数都要读出来，不能退化成自己的退避")
  void rateLimitedCarriesRetryAfter() {
    mode.set("429");
    CrossrefAdapter adapter = new CrossrefAdapter(mapper, http, "CognitaNexus-test", 1 << 20);

    assertThatThrownBy(() -> adapter.fetch(config(), job(), null))
        .isInstanceOf(SourceFetchException.class)
        .satisfies(
            thrown -> {
              SourceFetchException ex = (SourceFetchException) thrown;
              assertThat(ex.statusCode()).isEqualTo(429);
              assertThat(ex.retryAfterSeconds()).as("读不出 Retry-After 就只会在限流窗口里继续敲门").isEqualTo(45);
            });
  }

  @Test
  @DisplayName("503 无 Retry-After：状态码保留，等待时间留空（交给本地退避决定）")
  void serverErrorWithoutHeaderLeavesRetryAfterEmpty() {
    mode.set("503");
    CrossrefAdapter adapter = new CrossrefAdapter(mapper, http, "CognitaNexus-test", 1 << 20);

    assertThatThrownBy(() -> adapter.fetch(config(), job(), null))
        .isInstanceOf(SourceFetchException.class)
        .satisfies(
            thrown -> {
              SourceFetchException ex = (SourceFetchException) thrown;
              assertThat(ex.statusCode()).isEqualTo(503);
              assertThat(ex.retryAfterSeconds()).isNull();
            });
  }

  @Test
  @DisplayName("响应超过上限：拒绝解析，且不把半个响应当数据")
  void oversizedResponseIsRejected() {
    mode.set("ok");
    // 上限压到 100 字节：替身返回的两条记录一定超过它，用例不依赖具体报文长度
    CrossrefAdapter adapter = new CrossrefAdapter(mapper, http, "CognitaNexus-test", 100);

    assertThatThrownBy(() -> adapter.fetch(config(), job(), null))
        .isInstanceOf(SourceFetchException.class)
        .satisfies(
            thrown ->
                assertThat(((SourceFetchException) thrown).statusCode())
                    .as("超限不是 HTTP 失败，状态码用 0 表示")
                    .isEqualTo(0))
        .hasMessageContaining("超过允许上限");
  }

  @Test
  @DisplayName("非法 JSON：按传输失败处理，不抛裸的 Jackson 异常")
  void invalidJsonBecomesTransportFailure() {
    mode.set("badjson");
    CrossrefAdapter adapter = new CrossrefAdapter(mapper, http, "CognitaNexus-test", 1 << 20);

    assertThatThrownBy(() -> adapter.fetch(config(), job(), null))
        .isInstanceOf(SourceFetchException.class)
        .hasMessageContaining("抓取 Crossref 失败");
  }

  @Test
  @DisplayName("连接不可用：同样按传输失败处理")
  void connectionRefusedBecomesTransportFailure() {
    int port = server.getAddress().getPort();
    server.stop(0);
    CrossrefAdapter adapter = new CrossrefAdapter(mapper, http, "CognitaNexus-test", 1 << 20);
    SourceRunConfig cfg =
        new SourceRunConfig(
            "src_1",
            "crossref",
            "Crossref",
            AdapterKind.CROSSREF,
            "http://127.0.0.1:" + port + "/works",
            "graph",
            2,
            List.of(),
            "v1",
            "APPROVED",
            null,
            60,
            true,
            1,
            null,
            null,
            null,
            null);

    assertThatThrownBy(() -> adapter.fetch(cfg, job(), null))
        .isInstanceOf(SourceFetchException.class)
        .hasMessageContaining("抓取 Crossref 失败");
  }

  // ---------------------------------------------------------------------------
  // 游标与翻页
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("按 offset 翻页：不跳页；同一游标重复抓得到同一批（幂等的前提）")
  void pagingByOffsetDoesNotSkipAndIsRepeatable() {
    mode.set("ok");
    CrossrefAdapter adapter = new CrossrefAdapter(mapper, http, "CognitaNexus-test", 1 << 20);

    FetchPage first = adapter.fetch(config(), job(), null);
    assertThat(first.records())
        .extracting(r -> r.sourceRecordId())
        .containsExactly("10.1/a", "10.1/b");
    assertThat(first.totalAvailable()).isEqualTo(4);
    assertThat(first.nextCursor()).isEqualTo("2");
    assertThat(first.lastPage()).isFalse();

    // 同一游标再抓一次：结果必须完全一致。采集的幂等依赖这一点——重放同一次抓取不能换一批数据。
    FetchPage again = adapter.fetch(config(), job(), null);
    assertThat(again.records())
        .extracting(r -> r.sourceRecordId())
        .containsExactly("10.1/a", "10.1/b");

    FetchPage second = adapter.fetch(config(), job(), first.nextCursor());
    assertThat(second.records())
        .extracting(r -> r.sourceRecordId())
        .as("第二页从 offset=2 开始，不能把第一页的两条再抓一遍")
        .containsExactly("10.1/c", "10.1/d");
    assertThat(second.lastPage()).as("已到末页时不再给游标，避免多跑一次空请求").isTrue();
    assertThat(requestedOffsets).containsExactly("0", "0", "2");
  }

  @Test
  @DisplayName("来源声明总数变化时不报错，只按本页实际记录推进游标")
  void cursorAdvancesByActualRecordCount() {
    mode.set("ok");
    CrossrefAdapter adapter = new CrossrefAdapter(mapper, http, "CognitaNexus-test", 1 << 20);

    FetchPage page = adapter.fetch(config(), job(), "2");
    // offset=2 只剩 2 条，total-results 仍报 4（替身固定），游标按实际条数 2 推进到 4，
    // 不按 total 推算——否则总数一变就会跳过或重复一段区间
    assertThat(page.records()).hasSize(2);
    assertThat(page.nextCursor()).as("offset 2 + 2 条 = 4，已到底").isNull();
    assertThat(page.lastPage()).isTrue();
  }

  // ---------------------------------------------------------------------------
  // 替身
  // ---------------------------------------------------------------------------

  private void handle(HttpExchange exchange) throws IOException {
    hits.incrementAndGet();
    String query =
        exchange.getRequestURI().getQuery() == null ? "" : exchange.getRequestURI().getQuery();
    String offset = "0";
    for (String part : query.split("&")) {
      if (part.startsWith("offset=")) {
        offset = part.substring("offset=".length());
      }
    }
    synchronized (requestedOffsets) {
      requestedOffsets.add(offset);
    }
    switch (mode.get()) {
      case "429" -> respond(exchange, 429, "{\"status\":\"rate limited\"}", "45");
      case "503" -> respond(exchange, 503, "{\"status\":\"unavailable\"}", null);
      case "badjson" -> respond(exchange, 200, "{not json", null);
      default -> respond(exchange, 200, body(offset), null);
    }
  }

  private void respond(HttpExchange exchange, int status, String body, String retryAfter)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    if (retryAfter != null) {
      exchange.getResponseHeaders().add("Retry-After", retryAfter);
    }
    exchange.sendResponseHeaders(status, bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /** 四篇文章的替身响应，按 offset 切片；rows 固定 2。 */
  private String body(String offset) {
    int start = Integer.parseInt(offset);
    StringBuilder items = new StringBuilder();
    for (int i = start; i < Math.min(start + 2, 4); i++) {
      if (items.length() > 0) {
        items.append(',');
      }
      char letter = (char) ('a' + i);
      items.append(
          """
          {"DOI":"10.1/%c","title":["Stub paper %c"],"container-title":["Stub Venue"],
           "issued":{"date-parts":[[2024]]},"author":[{"family":"Doe","given":"Jane"}],
           "is-referenced-by-count":%d}
          """
              .formatted(letter, letter, i));
    }
    return "{\"status\":\"ok\",\"message\":{\"total-results\":4,\"items\":[" + items + "]}}";
  }

  private SourceRunConfig config() {
    return new SourceRunConfig(
        "src_1",
        "crossref",
        "Crossref",
        AdapterKind.CROSSREF,
        baseEndpoint,
        "graph",
        2,
        List.of(),
        "v1",
        "APPROVED",
        null,
        60,
        true,
        1,
        null,
        null,
        null,
        null);
  }

  private static CollectJob job() {
    return new CollectJob(
        "job_1",
        "src_1",
        "run-test",
        JobStatus.RUNNING,
        null,
        null,
        0,
        0,
        0,
        0,
        null,
        null,
        null,
        "tester",
        0,
        null);
  }
}
