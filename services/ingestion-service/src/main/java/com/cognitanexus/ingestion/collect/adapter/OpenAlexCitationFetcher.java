package com.cognitanexus.ingestion.collect.adapter;

import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 按 DOI 批量取参考文献列表。
 *
 * <p>依据：SRS FR06、架构 V0.2 §6.2。与 {@link OpenAlexAdapter} 的区别是用途而不是来源：适配器
 * 负责「按查询条件翻页发现新成果」，本类负责「已知 DOI，回头去取这一篇的参考文献」。后者的触发
 * 方是 core 给出的待补清单，不是采集任务，因此不经过任务与游标机制。
 *
 * <p>三个必须写在代码里的约束：
 *
 * <ol>
 *   <li><b>一次请求取多篇</b>：OpenAlex 按请求计费（每次请求 1 个配额点，与返回条目数无关），
 *       逐篇取会让可采量降到 1/25。用 {@code filter=doi:a|b|c} 一次取一批，批次大小由调用方控制
 *       以限制单次响应体积。
 *   <li><b>配额要读回来</b>：响应头的 {@code X-RateLimit-Remaining} 是来源自己报的剩余额度。
 *       额度耗尽时会返回 403，若继续跑会把「没额度」记成一串抓取失败，运维看到的是「来源挂了」。
 *       因此这里把剩余额度带回给调用方，由它决定要不要停。
 *   <li><b>缺失不是 0</b>：{@code referenced_works_count} 缺失时给 null，不写成 0；
 *       {@code referenced_works} 缺失（来源没给列表）时整条结果仍返回，但 {@code references}
 *       为 null，与「给了空列表」区分开。
 * </ol>
 */
public class OpenAlexCitationFetcher {

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String userAgent;
  private final int maxResponseBytes;

  /** 免费 API key。缺失时按匿名调用，每日预算 0.1 美元且与同出口 IP 共享。 */
  private final String apiKey;

  public OpenAlexCitationFetcher(
      ObjectMapper objectMapper, HttpClient httpClient, String userAgent, int maxResponseBytes) {
    this(objectMapper, httpClient, userAgent, maxResponseBytes, null);
  }

  public OpenAlexCitationFetcher(
      ObjectMapper objectMapper,
      HttpClient httpClient,
      String userAgent,
      int maxResponseBytes,
      String apiKey) {
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
    this.userAgent = userAgent;
    this.maxResponseBytes = maxResponseBytes;
    this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey.trim();
  }

  /**
   * 取一批 DOI 的参考文献列表。
   *
   * @param dois 一批 DOI（调用方保证批次大小）
   * @return 结果与配额状态
   */
  public FetchResult fetch(List<String> dois) {
    if (dois == null || dois.isEmpty()) {
      return new FetchResult(List.of(), null, null, 200);
    }
    String url = buildUrl(dois);
    HttpResponse<byte[]> response = send(url);
    int status = response.statusCode();
    Integer remaining = remainingCredits(response);
    Long resetSeconds = longHeader(response, "X-RateLimit-Reset");
    if (status != 200) {
      // 403/429 是配额或限流，属于「暂时做不了」；其余按来源故障处理
      ErrorCode code =
          status == 429 || status == 403
              ? ErrorCode.RATE_LIMITED
              : ErrorCode.DEPENDENCY_UNAVAILABLE;
      throw new BusinessException(code, "OpenAlex 取参考文献失败 status=" + status);
    }
    JsonNode root;
    try {
      root = objectMapper.readTree(response.body());
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "OpenAlex 响应不是合法 JSON");
    }
    Instant fetchedAt = Instant.now();
    Set<String> requested = new LinkedHashSet<>();
    for (String doi : dois) {
      requested.add(normalizeDoi(doi));
    }
    List<WorkReferences> works = new ArrayList<>();
    for (JsonNode item : root.path("results")) {
      String doi = normalizeDoi(item.path("doi").asText(null));
      if (doi == null || !requested.contains(doi)) {
        continue;
      }
      String workId = item.path("id").asText(null);
      Integer total =
          item.path("referenced_works_count").isNumber()
              ? item.path("referenced_works_count").asInt()
              : null;
      List<String> references = null;
      JsonNode refNode = item.path("referenced_works");
      if (refNode.isArray()) {
        references = new ArrayList<>(refNode.size());
        for (JsonNode ref : refNode) {
          String value = ref.asText(null);
          if (value != null && !value.isBlank()) {
            references.add(value);
          }
        }
      }
      works.add(new WorkReferences(doi, workId, total, references, fetchedAt));
    }
    return new FetchResult(works, remaining, resetSeconds, status);
  }

  String buildUrl(List<String> dois) {
    StringBuilder url = new StringBuilder("https://api.openalex.org/works?filter=doi:");
    boolean first = true;
    for (String doi : dois) {
      if (!first) {
        // 分隔符也必须写成 %7C：URI.create 只接受合法转义，裸竖线会抛
        // "Illegal character in query"，响应还没发出就变成了 500/400
        url.append("%7C");
      }
      url.append(URLEncoder.encode(doi.trim(), StandardCharsets.UTF_8));
      first = false;
    }
    url.append("&per-page=").append(Math.max(dois.size(), 1));
    url.append("&select=id,doi,referenced_works,referenced_works_count");
    if (userAgent != null && userAgent.contains("mailto:")) {
      String mail = userAgent.substring(userAgent.indexOf("mailto:") + 7);
      int end = mail.indexOf(')');
      if (end > 0) {
        mail = mail.substring(0, end);
      }
      url.append("&mailto=").append(URLEncoder.encode(mail, StandardCharsets.UTF_8));
    }
    if (apiKey != null && !apiKey.isBlank()) {
      url.append("&api_key=").append(URLEncoder.encode(apiKey.trim(), StandardCharsets.UTF_8));
    }
    return url.toString();
  }

  private HttpResponse<byte[]> send(String url) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(45))
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .GET()
            .build();
    try {
      HttpResponse<byte[]> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.body() != null && response.body().length > maxResponseBytes) {
        throw new BusinessException(
            ErrorCode.PAYLOAD_TOO_LARGE, "OpenAlex 响应超过上限 " + maxResponseBytes + " 字节，请缩小批次");
      }
      return response;
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException(
          ErrorCode.DEPENDENCY_UNAVAILABLE, "OpenAlex 请求失败：" + ex.getClass().getSimpleName());
    }
  }

  /** DOI 归一：与 core 侧同一规则（小写、去 URL 与 doi: 前缀），否则两边的键对不上。 */
  public static String normalizeDoi(String raw) {
    if (raw == null) {
      return null;
    }
    String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
    value = value.replaceAll("^(doi:|https?://(dx\\.)?doi\\.org/|doi\\.org/)", "");
    value = value.replaceAll("[\\s\\u00A0]+", "");
    return value.isEmpty() ? null : value;
  }

  /**
   * 剩余额度，统一折算成「美分」整数返回。
   *
   * <p>OpenAlex 2026 年起改为按美元计费，响应头变成 {@code X-RateLimit-Remaining-Usd}，值是小数
   * （实测形如 {@code 0.0871}）。旧代码读的是 {@code X-RateLimit-Remaining}，在那个头消失之后
   * 一直取到 null，于是「剩余额度低于阈值就整轮改用 Crossref」这条保护从未真正生效——额度耗尽时
   * 不会切换来源，只会把一串 403 记成抓取失败。这里优先读新头并折算成美分，老头保留作回退。
   */
  private static Integer remainingCredits(HttpResponse<?> response) {
    Optional<String> usd = response.headers().firstValue("X-RateLimit-Remaining-Usd");
    if (usd.isPresent()) {
      try {
        return (int) Math.round(Double.parseDouble(usd.get().trim()) * 100);
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    return intHeader(response, "X-RateLimit-Remaining");
  }

  private static Integer intHeader(HttpResponse<?> response, String name) {
    return response
        .headers()
        .firstValue(name)
        .map(
            raw -> {
              try {
                return Integer.valueOf(raw.trim());
              } catch (NumberFormatException ex) {
                return null;
              }
            })
        .orElse(null);
  }

  private static Long longHeader(HttpResponse<?> response, String name) {
    return response
        .headers()
        .firstValue(name)
        .map(
            raw -> {
              try {
                return Long.valueOf(raw.trim());
              } catch (NumberFormatException ex) {
                return null;
              }
            })
        .orElse(null);
  }

  /**
   * 一篇作品的参考文献观测。
   *
   * @param references 参考文献标识列表；null 表示来源未给列表（与空列表含义不同）
   */
  public record WorkReferences(
      String doi,
      String workId,
      Integer referencesTotal,
      List<String> references,
      Instant fetchedAt) {}

  /** 一批请求的结果与来源侧配额状态。 */
  public record FetchResult(
      List<WorkReferences> works, Integer remainingCredits, Long resetSeconds, int status) {}
}
