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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 按 DOI 批量取摘要。
 *
 * <p>依据：FR02／FR16。与 {@link OpenAlexCitationFetcher} 同源同形态，区别只在取什么：那个取
 * 「这一篇引用了谁」，这个取「这一篇自己讲了什么」。分成两个类而不是加一个方法，是因为两者的
 * 返回体大小差一个数量级（参考文献可达数百条，摘要通常两百词），混用同一个批次上限会让其中一方
 * 总是过大或过小。
 *
 * <p><b>倒排索引必须还原成文本</b>：OpenAlex 不直接给摘字符串，给的是
 * {@code abstract_inverted_index}——每个词对应它在原文里出现的位置列表。直接把它存进摘要字段，
 * 页面上会出现一段乱序的 JSON。还原时按位置排序而不是按词字典序，这是唯一正确的做法：按词序拼
 * 出来的句子读不通，而且看不出是程序错的，只会被当成「来源的摘要质量差」。
 *
 * <p><b>一次请求取多篇</b>：OpenAlex 按请求计费，逐篇取会把可采量降到 1/25。与引用补水一致。
 *
 * <p><b>没有摘要也是信息</b>：{@code abstract_inverted_index} 缺失表示来源没有这一篇的摘要
 * （Elsevier 系普遍如此），与「请求失败」是两件事。前者要回报给 core 记下尝试时刻，后者要重试。
 */
public class OpenAlexAbstractFetcher {

  /** OpenAlex 单次 filter 查询允许的最大条目数。超过会被来源截断，看起来像「一半的 DOI 不存在」。 */
  static final int MAX_DOIS_PER_REQUEST = 50;

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String userAgent;
  private final int maxResponseBytes;
  private final String apiKey;

  public OpenAlexAbstractFetcher(
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
   * 取一批 DOI 的摘要。
   *
   * @param dois 一批 DOI（调用方保证不超过 {@link #MAX_DOIS_PER_REQUEST}）
   */
  public FetchResult fetch(List<String> dois) {
    if (dois == null || dois.isEmpty()) {
      return new FetchResult(List.of(), null, 200);
    }
    if (dois.size() > MAX_DOIS_PER_REQUEST) {
      throw new BusinessException(
          ErrorCode.INVALID_ARGUMENT,
          "单次摘要请求最多 " + MAX_DOIS_PER_REQUEST + " 个 DOI，收到 " + dois.size());
    }
    HttpResponse<byte[]> response = send(buildUrl(dois));
    int status = response.statusCode();
    Integer remaining = remainingCredits(response);
    if (status != 200) {
      ErrorCode code =
          status == 429 || status == 403
              ? ErrorCode.RATE_LIMITED
              : ErrorCode.DEPENDENCY_UNAVAILABLE;
      throw new BusinessException(code, "OpenAlex 取摘要失败 status=" + status);
    }
    JsonNode root;
    try {
      root = objectMapper.readTree(response.body());
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "OpenAlex 响应不是合法 JSON");
    }
    Set<String> requested = new LinkedHashSet<>();
    for (String doi : dois) {
      String normalized = OpenAlexCitationFetcher.normalizeDoi(doi);
      if (normalized != null) {
        requested.add(normalized);
      }
    }
    List<WorkAbstract> works = new ArrayList<>();
    for (JsonNode item : root.path("results")) {
      String doi = OpenAlexCitationFetcher.normalizeDoi(item.path("doi").asText(null));
      if (doi == null || !requested.contains(doi)) {
        continue;
      }
      works.add(
          new WorkAbstract(
              doi,
              item.path("id").asText(null),
              reconstruct(item.path("abstract_inverted_index"))));
    }
    return new FetchResult(works, remaining, status);
  }

  /**
   * 把倒排索引还原成原文。
   *
   * <p>返回 null 表示来源没给摘要，返回空串表示给了但还原后没有内容（理论上不会发生，但真发生了
   * 要和「没给」区分开，否则调用方会把一个空串当成有效摘要写进库里，页面上出现一段空白）。
   *
   * <p>位置可能不连续（来源只给了部分词），此时按位置从小到大拼接，不补占位符：补了会造出
   * 原文里没有的文字。
   */
  static String reconstruct(JsonNode invertedIndex) {
    if (invertedIndex == null || invertedIndex.isMissingNode() || !invertedIndex.isObject()) {
      return null;
    }
    Map<Integer, String> byPosition = new TreeMap<>();
    var fields = invertedIndex.fields();
    while (fields.hasNext()) {
      var entry = fields.next();
      String word = entry.getKey();
      JsonNode positions = entry.getValue();
      if (word == null || word.isEmpty() || !positions.isArray()) {
        continue;
      }
      for (JsonNode position : positions) {
        if (position.isInt() || position.isLong()) {
          // 同一位置被两个词占用时保留先到的那个，并且不覆盖：覆盖会静默改写原文
          byPosition.putIfAbsent(position.asInt(), word);
        }
      }
    }
    if (byPosition.isEmpty()) {
      return "";
    }
    StringBuilder text = new StringBuilder(byPosition.size() * 8);
    for (String word : byPosition.values()) {
      if (text.length() > 0) {
        text.append(' ');
      }
      text.append(word);
    }
    return text.toString();
  }

  String buildUrl(List<String> dois) {
    StringBuilder url = new StringBuilder("https://api.openalex.org/works?filter=doi:");
    boolean first = true;
    for (String doi : dois) {
      if (!first) {
        // 分隔符必须写成 %7C：裸竖线会让 URI.create 抛 Illegal character in query
        url.append("%7C");
      }
      url.append(URLEncoder.encode(doi.trim(), StandardCharsets.UTF_8));
      first = false;
    }
    url.append("&per-page=").append(Math.max(dois.size(), 1));
    url.append("&select=id,doi,abstract_inverted_index");
    if (userAgent != null && userAgent.contains("mailto:")) {
      String mail = userAgent.substring(userAgent.indexOf("mailto:") + 7);
      int end = mail.indexOf(')');
      if (end > 0) {
        mail = mail.substring(0, end);
      }
      url.append("&mailto=").append(URLEncoder.encode(mail, StandardCharsets.UTF_8));
    }
    if (apiKey != null) {
      url.append("&api_key=").append(URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
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
            ErrorCode.PAYLOAD_TOO_LARGE, "OpenAlex 摘要响应超过上限 " + maxResponseBytes + " 字节，请缩小批次");
      }
      return response;
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException(
          ErrorCode.DEPENDENCY_UNAVAILABLE, "OpenAlex 请求失败：" + ex.getClass().getSimpleName());
    }
  }

  /** 与引用取数共用同一套额度读取规则（新头折算成美分，老头回退）。 */
  private static Integer remainingCredits(HttpResponse<?> response) {
    Optional<String> usd = response.headers().firstValue("X-RateLimit-Remaining-Usd");
    if (usd.isPresent()) {
      try {
        return (int) Math.round(Double.parseDouble(usd.get().trim()) * 100);
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    return response
        .headers()
        .firstValue("X-RateLimit-Remaining")
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

  /**
   * 一篇作品的摘要。
   *
   * @param abstractText null 表示来源没给；空串表示给了但还原不出内容
   */
  public record WorkAbstract(String doi, String workId, String abstractText) {}

  /** 一批请求的结果与来源侧配额状态。 */
  public record FetchResult(List<WorkAbstract> works, Integer remainingCredits, int status) {}
}
