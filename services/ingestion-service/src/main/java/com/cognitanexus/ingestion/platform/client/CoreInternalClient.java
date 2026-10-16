package com.cognitanexus.ingestion.platform.client;

import com.cognitanexus.ingestion.platform.security.IngestionSecurity;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * core 内部接口客户端。
 *
 * <p>依据：架构 V0.2 §10.2、§10.3。本服务不自行解析 core 的会话，也不缓存 core 的鉴权结论
 * （架构明确禁止跨请求正向缓存鉴权结果），因此这里每次调用都真实发起请求。
 *
 * <p>失败语义：
 *
 * <ul>
 *   <li>core 明确判定未认证（401）→ 返回空，由调用方回绝请求；
 *   <li>core 不可达或返回 5xx → 抛 {@code DEPENDENCY_UNAVAILABLE}，<b>不回退为放行</b>。
 *       把依赖故障解释成「权限不足」会让运维误判，解释成「允许」则直接构成越权。
 * </ul>
 */
@Component
public class CoreInternalClient {

  private static final Logger log = LoggerFactory.getLogger(CoreInternalClient.class);

  private final CoreClientProperties properties;
  private final ObjectMapper objectMapper;
  private final RestClient restClient;

  public CoreInternalClient(CoreClientProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(properties.readTimeout());
    this.restClient = RestClient.builder().requestFactory(factory).build();
  }

  /**
   * 校验用户会话并取得操作者上下文。
   *
   * <p>把浏览器原始 Cookie 与 CSRF 请求头原样转给 core，由 core 完成两件事：解析会话并核对
   * 账号状态与撤销记录、执行双提交 CSRF 校验。本服务不解读 Cookie 内容。
   *
   * @return 认证通过时返回上下文；core 判定未认证时返回空
   */
  public Optional<VerifiedActor> verifySession(String cookieHeader, String csrfHeader) {
    JsonNode body =
        exchange(HttpMethod.POST, "/internal/v1/session-context", cookieHeader, csrfHeader, null);
    if (body == null) {
      return Optional.empty();
    }
    JsonNode data = body.path("data");
    Set<String> roles = toSet(data.path("roles"));
    Set<String> capabilities = toSet(data.path("capabilities"));
    return Optional.of(
        new VerifiedActor(
            data.path("accountId").asText(null),
            data.path("username").asText(null),
            data.path("displayName").asText(null),
            roles,
            capabilities,
            data.path("csrfAccepted").asBoolean(false)));
  }

  /** 读取来源运行配置；core 是来源许可与白名单的权威。 */
  public Optional<SourceRunConfigView> sourceConfig(String sourceCode) {
    JsonNode body =
        exchange(
            HttpMethod.GET,
            "/internal/v1/source-configs/" + urlEncodePath(sourceCode),
            null,
            null,
            null);
    if (body == null) {
      return Optional.empty();
    }
    JsonNode data = body.path("data");
    if (data.isMissingNode() || data.isNull()) {
      return Optional.empty();
    }
    Set<String> allowedFields = new LinkedHashSet<>();
    for (JsonNode item : data.path("allowedFields")) {
      allowedFields.add(item.asText());
    }
    return Optional.of(
        new SourceRunConfigView(
            data.path("sourceId").asText(null),
            data.path("sourceCode").asText(null),
            data.path("displayName").asText(null),
            data.path("enabled").asBoolean(false),
            data.path("licenseScope").asText(null),
            data.path("baseUrl").asText(null),
            data.path("configVersion").asInt(0),
            List.copyOf(allowedFields)));
  }

  /**
   * 交接一个来源批次。
   *
   * <p>202 表示 core 已持久接收，<b>不</b>表示治理通过；待核验与拒绝的数量在返回的计数里单列。
   */
  public HandoffResult handoff(Map<String, Object> batchBody) {
    JsonNode body = exchange(HttpMethod.POST, "/internal/v1/source-records", null, null, batchBody);
    if (body == null) {
      throw new BusinessException(
          ErrorCode.DEPENDENCY_UNAVAILABLE, "core 未返回接收结果，交接结论未知，将按同一幂等键重试");
    }
    JsonNode data = body.path("data");
    Map<String, String> receiptStatuses = new LinkedHashMap<>();
    for (JsonNode receipt : data.path("receipts")) {
      String recordId = receipt.path("sourceRecordId").asText(null);
      if (recordId != null) {
        receiptStatuses.put(
            recordId,
            receipt.path("status").asText("REJECTED")
                + (receipt.path("message").isNull()
                    ? ""
                    : " | " + receipt.path("message").asText("")));
      }
    }
    return new HandoffResult(
        data.path("batchId").asText(null),
        data.path("received").asInt(0),
        data.path("accepted").asInt(0),
        data.path("attached").asInt(0),
        data.path("duplicate").asInt(0),
        data.path("rejected").asInt(0),
        data.path("catalogRevision").asLong(0),
        receiptStatuses,
        body.toString());
  }

  private JsonNode exchange(
      HttpMethod method, String path, String cookieHeader, String csrfHeader, Object requestBody) {
    String url = properties.baseUrl() + path;
    try {
      RestClient.RequestBodySpec spec =
          restClient
              .method(method)
              .uri(url)
              .contentType(MediaType.APPLICATION_JSON)
              .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
              // 服务身份：core 侧据此查令牌，令牌只授权内部端点，不能冒充人类管理员
              .header(IngestionSecurity.HEADER_SERVICE_NAME, properties.serviceName())
              .header(IngestionSecurity.HEADER_SERVICE_TOKEN, properties.serviceToken());
      if (cookieHeader != null) {
        spec = spec.header(HttpHeaders.COOKIE, cookieHeader);
      }
      if (csrfHeader != null) {
        spec = spec.header(IngestionSecurity.HEADER_CSRF, csrfHeader);
      }
      String payload = requestBody == null ? null : objectMapper.writeValueAsString(requestBody);
      var response =
          payload == null
              ? spec.retrieve().toEntity(String.class)
              : spec.body(payload).retrieve().toEntity(String.class);
      String responseBody = response.getBody();
      if (responseBody == null || responseBody.isBlank()) {
        return null;
      }
      return objectMapper.readTree(responseBody);
    } catch (RestClientResponseException ex) {
      int status = ex.getStatusCode().value();
      // 401/404 是 core 给出的确定性结论（未认证 / 对象不存在），不是依赖故障，交由调用方表达
      if (status == 401 || status == 404) {
        log.info("core 给出确定性结论 path={} status={}", path, status);
        return null;
      }
      throw new BusinessException(
          ErrorCode.DEPENDENCY_UNAVAILABLE, "调用 core 失败 status=" + status + " path=" + path);
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException(
          ErrorCode.DEPENDENCY_UNAVAILABLE, "调用 core 失败：" + ex.getClass().getSimpleName());
    }
  }

  private static Set<String> toSet(JsonNode arrayNode) {
    Set<String> values = new LinkedHashSet<>();
    if (arrayNode != null && arrayNode.isArray()) {
      for (JsonNode item : arrayNode) {
        values.add(item.asText());
      }
    }
    return values;
  }

  private static String urlEncodePath(String value) {
    return java.net.URLEncoder.encode(
            value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8)
        .replace("+", "%20");
  }

  /** 会话校验结果。 */
  public record VerifiedActor(
      String accountId,
      String username,
      String displayName,
      Set<String> roles,
      Set<String> capabilities,
      boolean csrfAccepted) {}

  /** 来源运行配置视图。 */
  public record SourceRunConfigView(
      String sourceId,
      String sourceCode,
      String displayName,
      boolean enabled,
      String licenseScope,
      String baseUrl,
      int configVersion,
      List<String> allowedFields) {}

  /** 交接结果。 */
  public record HandoffResult(
      String batchId,
      int received,
      int accepted,
      int attached,
      int duplicate,
      int rejected,
      long catalogRevision,
      Map<String, String> receiptStatuses,
      String rawBody) {}

  /** core 调用配置。 */
  @ConfigurationProperties(prefix = "cn.core")
  public record CoreClientProperties(
      String baseUrl,
      String serviceName,
      String serviceToken,
      Duration connectTimeout,
      Duration readTimeout) {

    public CoreClientProperties {
      baseUrl = baseUrl == null ? "http://127.0.0.1:8081" : baseUrl;
      serviceName = serviceName == null ? IngestionSecurity.SERVICE_NAME : serviceName;
      serviceToken = serviceToken == null ? "" : serviceToken;
      connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
      readTimeout = readTimeout == null ? Duration.ofSeconds(20) : readTimeout;
    }
  }
}
