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
import java.util.List;

/**
 * 按 DOI 单篇取参考文献列表（Crossref）。
 *
 * <p>依据：SRS FR06、架构 V0.2 §6.2。与 {@link OpenAlexCitationFetcher} 是同一件事的第二个来源，
 * 存在的理由是两条真实约束：
 *
 * <ol>
 *   <li>OpenAlex 按请求计费且有全站共享的每日额度。额度用尽时返回 429（并要求等十几小时），
 *       这时补水就完全停下——除非有另一条不花钱的路；
 *   <li>Crossref 的 {@code reference} 数组直接给 DOI，正对本平台的标识口径（成果按 DOI 注册），
 *       不需要先把作品 ID 翻译成 DOI。
 * </ol>
 *
 * <p>代价是只能一篇一请求（Crossref 的 filter 不支持 OR 批量），因此按篇限速、串行取；
 * 单篇响应可以很大（300 条参考文献），所以单独设了响应上限。
 */
public class CrossrefCitationFetcher {

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String userAgent;
  private final int maxResponseBytes;

  public CrossrefCitationFetcher(
      ObjectMapper objectMapper, HttpClient httpClient, String userAgent, int maxResponseBytes) {
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
    this.userAgent = userAgent;
    this.maxResponseBytes = maxResponseBytes;
  }

  /**
   * 取一篇的参考文献。
   *
   * @return 取到返回观测；来源没有这一篇（404）返回 {@link Result#notFound}; 其余错误抛异常
   */
  public Result fetch(String doi) {
    String url = buildUrl(doi);
    HttpResponse<byte[]> response = send(url);
    int status = response.statusCode();
    if (status == 404) {
      return Result.notFound(doi);
    }
    if (status == 429) {
      throw new BusinessException(ErrorCode.RATE_LIMITED, "Crossref 限流，请稍后再试");
    }
    if (status != 200) {
      throw new BusinessException(
          ErrorCode.DEPENDENCY_UNAVAILABLE, "Crossref 取参考文献失败 status=" + status);
    }
    JsonNode root;
    try {
      root = objectMapper.readTree(response.body());
    } catch (Exception ex) {
      throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Crossref 响应不是合法 JSON");
    }
    JsonNode message = root.path("message");
    String returnedDoi = OpenAlexCitationFetcher.normalizeDoi(message.path("DOI").asText(null));
    Integer total =
        message.path("reference-count").isNumber() ? message.path("reference-count").asInt() : null;
    List<String> references = new ArrayList<>();
    JsonNode refs = message.path("reference");
    if (refs.isArray()) {
      for (JsonNode ref : refs) {
        String refDoi = OpenAlexCitationFetcher.normalizeDoi(ref.path("DOI").asText(null));
        if (refDoi != null) {
          references.add(refDoi);
        }
      }
    }
    // 列表本身有没有给，与「给了几条」是两件事：没有 reference 字段说明来源没提供列表
    boolean listProvided = refs.isArray();
    return new Result(
        returnedDoi == null ? doi : returnedDoi,
        total,
        listProvided ? references : null,
        listProvided ? refs.size() : 0,
        Instant.now(),
        false);
  }

  String buildUrl(String doi) {
    StringBuilder url = new StringBuilder("https://api.crossref.org/works/");
    url.append(URLEncoder.encode(doi.trim(), StandardCharsets.UTF_8));
    if (userAgent != null && userAgent.contains("mailto:")) {
      String mail = userAgent.substring(userAgent.indexOf("mailto:") + 7);
      int end = mail.indexOf(')');
      if (end > 0) {
        mail = mail.substring(0, end);
      }
      // polite pool：带联系邮箱的请求走更快的一队列，这是 Crossref 官方建议
      url.append("?mailto=").append(URLEncoder.encode(mail, StandardCharsets.UTF_8));
    }
    return url.toString();
  }

  private HttpResponse<byte[]> send(String url) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .GET()
            .build();
    try {
      HttpResponse<byte[]> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.body() != null && response.body().length > maxResponseBytes) {
        throw new BusinessException(
            ErrorCode.PAYLOAD_TOO_LARGE, "Crossref 响应超过上限 " + maxResponseBytes + " 字节");
      }
      return response;
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException(
          ErrorCode.DEPENDENCY_UNAVAILABLE, "Crossref 请求失败：" + ex.getClass().getSimpleName());
    }
  }

  /**
   * 一篇的参考文献观测。
   *
   * @param references 带 DOI 的参考文献；null 表示来源未给列表（与空列表含义不同）
   * @param referencesTotal 来源声明的参考文献总数（Crossref 的 reference-count）
   * @param entriesReceived 来源实际给出的条目数（含没有 DOI 的），用于如实披露缺口
   */
  public record Result(
      String doi,
      Integer referencesTotal,
      List<String> references,
      int entriesReceived,
      Instant fetchedAt,
      boolean notFound) {

    static Result notFound(String doi) {
      return new Result(doi, null, List.of(), 0, Instant.now(), true);
    }
  }
}
