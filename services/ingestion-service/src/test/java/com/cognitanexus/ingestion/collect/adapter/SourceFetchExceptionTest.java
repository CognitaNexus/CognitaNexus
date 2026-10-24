package com.cognitanexus.ingestion.collect.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognitanexus.sharedkernel.ErrorCode;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 抓取失败与 {@code Retry-After} 的解析（WB-13）。
 *
 * <p>为什么这些断言值得单独写：退避时长直接决定服务在来源限流时会不会继续敲门。解析错一个分支
 * 不会报错，只会静默退化成本地指数退避——对外表现是「来源好像一直不可用」，而不是「我们没读懂响应头」。
 */
class SourceFetchExceptionTest {

  @Test
  @DisplayName("秒数形式：原样解析")
  void parsesSecondsForm() {
    assertThat(SourceFetchException.parseRetryAfter("120")).isEqualTo(120);
    assertThat(SourceFetchException.parseRetryAfter("  5  ")).isEqualTo(5);
  }

  @Test
  @DisplayName("HTTP 日期形式：按当前时间换算成秒，过期归零")
  void parsesHttpDateForm() {
    ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(90);
    String header = future.format(DateTimeFormatter.RFC_1123_DATE_TIME);
    Integer parsed = SourceFetchException.parseRetryAfter(header);
    // RFC 1123 只到秒，且构造与解析之间有耗时，允许 2 秒误差
    assertThat(parsed).isNotNull().isBetween(85, 90);

    String past =
        ZonedDateTime.now(ZoneOffset.UTC)
            .minusHours(1)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME);
    assertThat(SourceFetchException.parseRetryAfter(past))
        .as("已过期的日期表示「现在就能重试」，不是「再等一小时」")
        .isEqualTo(0);
  }

  @Test
  @DisplayName("无法识别的取值与缺失头：返回 null，退化成自己的退避")
  void unparsableBecomesNull() {
    assertThat(SourceFetchException.parseRetryAfter(null)).isNull();
    assertThat(SourceFetchException.parseRetryAfter("")).isNull();
    assertThat(SourceFetchException.parseRetryAfter("later")).isNull();
    assertThat(SourceFetchException.parseRetryAfter("-30")).as("负数归零").isEqualTo(0);
  }

  @Test
  @DisplayName("非 200 响应：状态码与等待时间都随异常往上带")
  void httpStatusCarriesBothFacts() {
    SourceFetchException ex = SourceFetchException.httpStatus("Crossref", 429, "60");
    assertThat(ex.statusCode()).isEqualTo(429);
    assertThat(ex.retryAfterSeconds()).isEqualTo(60);
    assertThat(ex.errorCode()).isEqualTo(ErrorCode.DEPENDENCY_UNAVAILABLE);
    assertThat(ex.getMessage()).contains("429").contains("60");
  }

  @Test
  @DisplayName("响应体片段随消息带上：只报状态码等于把唯一能解释原因的信息丢掉")
  void httpStatusCarriesBodySnippet() {
    SourceFetchException ex =
        SourceFetchException.httpStatus("Wikidata", 400, null, "SPARQL-QUERY: queryStr=SELECT ...");
    assertThat(ex.getMessage()).contains("400").contains("SPARQL-QUERY");
    assertThat(ex.statusCode()).isEqualTo(400);
  }

  @Test
  @DisplayName("响应体压成单行并截断：整页 HTML 不该把日志淹掉")
  void bodySnippetIsFlattenedAndTruncated() {
    String html = "<html>\n  <body>\r\n    error\n  </body>\n</html>";
    assertThat(SourceFetchException.snippetOf(html)).doesNotContain("\n").doesNotContain("\r");

    String huge = "x".repeat(SourceFetchException.BODY_SNIPPET_LIMIT + 50);
    String snippet = SourceFetchException.snippetOf(huge);
    // "：" + 上限个字符 + "…"
    assertThat(snippet).hasSize(SourceFetchException.BODY_SNIPPET_LIMIT + 2).endsWith("…");
  }

  @Test
  @DisplayName("空响应体不产生只有冒号的尾巴")
  void emptyBodyLeavesNoTrailingColon() {
    assertThat(SourceFetchException.snippetOf(null)).isEmpty();
    assertThat(SourceFetchException.snippetOf("   ")).isEmpty();
    assertThat(SourceFetchException.httpStatus("Wikidata", 400, null, "  ").getMessage())
        .doesNotContain("：");
  }

  @Test
  @DisplayName("没有 Retry-After 的 5xx：状态码保留，等待时间留空")
  void serverErrorWithoutHeaderHasNoRetryAfter() {
    SourceFetchException ex = SourceFetchException.httpStatus("OpenAlex", 503, null);
    assertThat(ex.statusCode()).isEqualTo(503);
    assertThat(ex.retryAfterSeconds()).isNull();
    assertThat(ex.getMessage()).doesNotContain("要求等待");
  }

  @Test
  @DisplayName("响应超限与传输失败：不是 HTTP 状态，等待时间留空")
  void oversizedAndTransportHaveNoStatus() {
    SourceFetchException oversized = SourceFetchException.oversized("NSF", 1024);
    assertThat(oversized.statusCode()).isEqualTo(0);
    assertThat(oversized.retryAfterSeconds()).isNull();
    assertThat(oversized.errorCode()).isEqualTo(ErrorCode.BUSINESS_RULE_VIOLATION);

    SourceFetchException transport =
        SourceFetchException.transport("Nobel", new java.net.http.HttpTimeoutException("timeout"));
    assertThat(transport.statusCode()).isEqualTo(0);
    assertThat(transport.retryAfterSeconds()).isNull();
    assertThat(transport.getMessage()).contains("HttpTimeoutException");
  }
}
