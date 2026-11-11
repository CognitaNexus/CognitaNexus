package com.cognitanexus.ingestion.platform.client;

import com.cognitanexus.ingestion.platform.security.IngestionSecurity;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
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

  /**
   * 待补参考文献的成果清单。
   *
   * <p>为什么由 core 决定「补哪些」：可见性、合并重定向与「是否已采过」的权威都在 core。采集侧
   * 自己挑的话，会挑到受限成果（采集侧看不到可见性，只知道有条记录），也会重复采已采过的。
   */
  public PendingCitations pendingCitations(int limit, String search, String topicId) {
    StringBuilder path = new StringBuilder("/internal/v1/citations/pending?limit=").append(limit);
    if (search != null && !search.isBlank()) {
      path.append("&search=").append(urlEncodePath(search));
    }
    if (topicId != null && !topicId.isBlank()) {
      path.append("&topicId=").append(urlEncodePath(topicId));
    }
    JsonNode body = exchange(HttpMethod.GET, path.toString(), null, null, null);
    if (body == null) {
      return new PendingCitations(List.of(), 0);
    }
    JsonNode data = body.path("data");
    List<PendingCitation> items = new ArrayList<>();
    for (JsonNode item : data.path("items")) {
      items.add(
          new PendingCitation(
              item.path("achievementId").asText(null),
              item.path("doi").asText(null),
              item.path("citationCount").isNumber() ? item.path("citationCount").asInt() : null));
    }
    return new PendingCitations(items, data.path("remaining").asInt(0));
  }

  /**
   * 引文反向回填候选：站内被引次数最多、但本站还没有收录的外部文献标识。
   *
   * <p>与 {@link #pendingCitations} 是相反方向：那个补「站内论文的参考文献观测」，这个补「被引的
   * 那一端节点」。前者最多把边记成 EXTERNAL，后者才能让边真正连起来。
   *
   * <p>为什么清单必须由 core 出：{@code core_identifier_registry} 才记录「本站收了哪些标识」，
   * 采集侧看不到它，自己挑会重复抓已收录的作品。
   *
   * @param kind {@code DOI} 或 {@code OPENALEX_ID}；为空由 core 回落为 DOI
   */
  public BackfillCandidates backfillCandidates(String kind, int limit) {
    StringBuilder path =
        new StringBuilder("/internal/v1/citations/backfill-candidates?limit=")
            .append(Math.max(limit, 1));
    if (kind != null && !kind.isBlank()) {
      path.append("&kind=").append(urlEncodePath(kind));
    }
    JsonNode body = exchange(HttpMethod.GET, path.toString(), null, null, null);
    if (body == null) {
      return new BackfillCandidates(List.of(), 0, kind);
    }
    JsonNode data = body.path("data");
    List<BackfillCandidate> items = new ArrayList<>();
    for (JsonNode item : data.path("items")) {
      items.add(
          new BackfillCandidate(
              item.path("kind").asText(null),
              item.path("value").asText(null),
              item.path("refs").isNumber() ? item.path("refs").asInt() : 0));
    }
    return new BackfillCandidates(
        items, data.path("remaining").asInt(0), data.path("kind").asText(kind));
  }

  /**
   * 待补摘要的成果清单。
   *
   * <p>与 {@link #pendingCitations} 同理：补哪些由 core 决定。这里额外只取有 DOI 的——摘要必须
   * 挂到确定的那一篇上，凭题名猜出来的摘要挂错文章比没有更糟。
   */
  public PendingAbstracts pendingAbstracts(int limit) {
    JsonNode body =
        exchange(
            HttpMethod.GET,
            "/internal/v1/papers/pending-abstracts?limit=" + Math.max(limit, 1),
            null,
            null,
            null);
    if (body == null) {
      return new PendingAbstracts(List.of(), 0);
    }
    JsonNode data = body.path("data");
    List<PendingAbstract> items = new ArrayList<>();
    for (JsonNode item : data.path("items")) {
      items.add(
          new PendingAbstract(
              item.path("achievementId").asText(null),
              item.path("doi").asText(null),
              item.path("title").asText(null)));
    }
    return new PendingAbstracts(items, data.path("remaining").asLong(0));
  }

  /**
   * 投递取回的摘要。
   *
   * <p>{@code abstractText} 为 null 的条目表示「来源没有这一篇的摘要」，必须回报：core 据此记下
   * 尝试时刻，下一轮才不会重复取同一批。
   */
  public AbstractReceipt postAbstracts(List<Map<String, Object>> entries) {
    JsonNode body =
        exchange(
            HttpMethod.POST,
            "/internal/v1/paper-abstracts",
            null,
            null,
            Map.of("provider", "OPENALEX", "entries", entries));
    if (body == null) {
      throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "core 未返回摘要回执，本批结果未知");
    }
    JsonNode data = body.path("data");
    return new AbstractReceipt(
        data.path("received").asInt(0),
        data.path("filled").asInt(0),
        data.path("notFound").asInt(0),
        data.path("skipped").asInt(0),
        data.path("tooLong").asInt(0));
  }

  /**
   * 触发 core 重新解析外部引用，把刚回填进来的节点对应的边点亮。
   *
   * <p>回填与解析是两件事：回填只把外部文献收成站内成果，边仍停在 EXTERNAL；不解析的话
   * 「成果数涨了、引用网络没变」，从外面看就是跑成功了却什么都没发生。所以回填的最后一公里
   * 必须由这里补上。
   *
   * <p>返回值里 {@code keysRemaining} 用来判断是否还要再跑：为 0 表示可解析的键都处理完了。
   */
  public ResolveReceipt resolveCitations(int limit) {
    JsonNode body =
        exchange(
            HttpMethod.POST,
            "/internal/v1/citations/resolve?limit=" + Math.max(limit, 1),
            null,
            null,
            null);
    if (body == null) {
      throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "core 未返回解析回执，本步结果未知");
    }
    JsonNode data = body.path("data");
    return new ResolveReceipt(
        data.path("keysScanned").asInt(0),
        data.path("keysResolved").asInt(0),
        data.path("rowsResolved").asInt(0),
        data.path("keysRemaining").asInt(0));
  }

  /** 重新解析回执。 */
  public record ResolveReceipt(
      int keysScanned, int keysResolved, int rowsResolved, int keysRemaining) {}

  /**
   * 投递参考文献观测。
   *
   * <p>core 的接收是幂等的（每篇先删旧条目再插），因此超时重投是安全的；这里不做本地重试，
   * 由调用方按批重投并记录回执——重试逻辑放在一处，才不会出现「批内重试 + 批间重试」叠成两层。
   */
  public CitationReceipt postCitationObservations(List<Map<String, Object>> observations) {
    JsonNode body =
        exchange(
            HttpMethod.POST,
            "/internal/v1/citation-observations",
            null,
            null,
            Map.of("observations", observations));
    if (body == null) {
      throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "core 未返回引用观测回执，本批结果未知");
    }
    JsonNode data = body.path("data");
    return new CitationReceipt(
        data.path("received").asInt(0),
        data.path("matched").asInt(0),
        data.path("referencesWritten").asInt(0),
        data.path("resolved").asInt(0),
        data.path("external").asInt(0));
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

  /** 待补参考文献的成果。 */
  public record PendingCitation(String achievementId, String doi, Integer citationCount) {}

  /** 待补清单与剩余规模。 */
  public record PendingCitations(List<PendingCitation> items, int remaining) {}

  /**
   * 一个待反向回填的外部被引标识。
   *
   * @param refs 站内有多少篇引用了它；收进来之后能点亮的边数上限
   */
  public record BackfillCandidate(String kind, String value, int refs) {}

  /** 回填候选页。remaining 是全量还剩多少个，用来判断还要跑几轮。 */
  public record BackfillCandidates(List<BackfillCandidate> items, int remaining, String kind) {}

  /** 待补摘要的成果。 */
  public record PendingAbstract(String achievementId, String doi, String title) {}

  /** 待补摘要清单与剩余规模。remaining 用 long：全量待补可能上万，int 够用但没有理由赌。 */
  public record PendingAbstracts(List<PendingAbstract> items, long remaining) {}

  /** 摘要入库回执。 */
  public record AbstractReceipt(int received, int filled, int notFound, int skipped, int tooLong) {}

  /** 引用观测入库回执。 */
  public record CitationReceipt(
      int received, int matched, int referencesWritten, int resolved, int external) {}

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
