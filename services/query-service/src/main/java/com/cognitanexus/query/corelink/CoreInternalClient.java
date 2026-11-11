package com.cognitanexus.query.corelink;

import com.cognitanexus.query.platform.web.TraceIdHolder;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * core 内部接口客户端。
 *
 * <p>依据：架构 V0.2 §7.2、§7.3。
 *
 * <p>本客户端刻意只做三件事：读权威公开水位、分页导出公开投影、取单个成果投影。它是查询侧唯一
 * 允许触碰 core 的地方——若检索、统计、图各自直接拼 URL 调 core，第一处未加超时的调用就会成为
 * 全站故障点，而且「哪些公开查询依赖 core」这个问题将无法回答。
 *
 * <p>失败语义分三类，混在一起就会出错：
 *
 * <ul>
 *   <li>core 明确回答 404（单个成果已不具公开资格）→ 返回 empty，调用方应从投影中移除该记录；
 *   <li>core 不可达、超时或返回 5xx → 抛 {@code DEPENDENCY_UNAVAILABLE}，调用方必须放弃本次请求；
 *   <li>core 返回结构不符合预期 → 同样按依赖故障处理，不猜字段默认值继续算。
 * </ul>
 *
 * <p><b>不降级为「假定已核实」</b>：架构 §7.2 明确要求 core 不可用时校验失败，查询不得假装已经
 * 核实公开资格，否则一次 core 抖动就会让已撤销的成果重新出现在检索结果里。
 */
@Component
public class CoreInternalClient {

  private static final Logger log = LoggerFactory.getLogger(CoreInternalClient.class);

  private final CoreLinkProperties properties;
  private final RestClient restClient;

  public CoreInternalClient(CoreLinkProperties properties) {
    this.properties = properties;
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout())
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(properties.requestTimeout());
    this.restClient = RestClient.builder().requestFactory(factory).build();
  }

  /** 权威公开水位。 */
  public record PublicBarrier(long catalogRevision, long requiredPublicRevision) {}

  /** 公开投影导出的一个分页。 */
  public record ExportPage(
      long catalogRevision,
      long requiredPublicRevision,
      long total,
      int limit,
      int offset,
      List<Map<String, Object>> items) {}

  /** 读取权威公开水位。 */
  public PublicBarrier readBarrier() {
    Map<String, Object> data = requireData("/internal/v1/public-barrier", Map.of());
    return new PublicBarrier(
        asLong(data.get("catalogRevision")), asLong(data.get("requiredPublicRevision")));
  }

  /** 分页读取公开投影导出。 */
  public ExportPage exportProjection(int limit, int offset) {
    Map<String, Object> data =
        requireData(
            "/internal/v1/exports/public-projection",
            Map.of("limit", String.valueOf(limit), "offset", String.valueOf(offset)));
    return new ExportPage(
        asLong(data.get("catalogRevision")),
        asLong(data.get("requiredPublicRevision")),
        asLong(data.get("total")),
        (int) asLong(data.get("limit")),
        (int) asLong(data.get("offset")),
        itemsOf(data.get("items")));
  }

  /**
   * 单个成果的公开投影。
   *
   * <p>返回 empty 表示 core 明确回答「该成果当前不具备公开资格」（404）。这与「core 不可用」必须
   * 区分：前者要求把该记录从投影中移除，后者要求放弃本次处理并稍后重试，混在一起会导致一次网络
   * 抖动把整批成果从公开检索里删掉。
   */
  public Optional<Map<String, Object>> exportAchievement(String achievementId) {
    ResponseEntity<Map> response =
        exchange("/internal/v1/exports/public-achievement/" + achievementId, Map.of());
    if (response.getStatusCode().value() == 404) {
      return Optional.empty();
    }
    requireSuccess(response, "/internal/v1/exports/public-achievement/{id}");
    Object data = dataOf(response.getBody());
    if (data instanceof Map<?, ?> map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> typed = (Map<String, Object>) map;
      return Optional.of(typed);
    }
    throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "core 返回的成果投影结构不符合预期");
  }

  private Map<String, Object> requireData(String path, Map<String, String> query) {
    ResponseEntity<Map> response = exchange(path, query);
    requireSuccess(response, path);
    Object data = dataOf(response.getBody());
    if (data instanceof Map<?, ?> map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> typed = (Map<String, Object>) map;
      return typed;
    }
    throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "core 返回的数据结构不符合预期：" + path);
  }

  private void requireSuccess(ResponseEntity<Map> response, String path) {
    if (response.getStatusCode().is2xxSuccessful()) {
      return;
    }
    log.warn("调用 core 未成功 path={} status={}", path, response.getStatusCode().value());
    throw new BusinessException(
        ErrorCode.DEPENDENCY_UNAVAILABLE,
        "权威服务 core 返回 " + response.getStatusCode().value() + "，本次请求无法核实公开资格");
  }

  @SuppressWarnings("rawtypes")
  private ResponseEntity<Map> exchange(String path, Map<String, String> query) {
    if (!properties.tokenConfigured()) {
      throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "本服务未配置调用 core 的服务令牌，无法核实公开资格");
    }
    UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(properties.baseUrl() + path);
    query.forEach(builder::queryParam);
    URI uri = builder.build().toUri();
    try {
      return restClient
          .get()
          .uri(uri)
          .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
          .header("X-Service-Name", properties.serviceName())
          .header("X-Service-Token", properties.serviceToken())
          .header(TraceIdHolder.HEADER, TraceIdHolder.current())
          .retrieve()
          // 注册空处理器以取消防务端 4xx/5xx 的默认抛异常行为：调用方需要区分
          // 「404 明确不存在」与「5xx 依赖故障」，而默认行为会把两者都变成同一个异常。
          .onStatus(
              status -> status.is4xxClientError() || status.is5xxServerError(), (req, res) -> {})
          .toEntity(Map.class);
    } catch (Exception ex) {
      // 只记摘要，不把 URL 查询串整体写进日志（检索条件可能含用户输入）
      log.warn("调用 core 失败 path={} cause={}", path, describe(ex));
      throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "无法连接权威服务 core，本次请求无法核实公开资格");
    }
  }

  private static Object dataOf(Map<?, ?> body) {
    return body == null ? null : body.get("data");
  }

  private static List<Map<String, Object>> itemsOf(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    return list.stream()
        .filter(item -> item instanceof Map<?, ?>)
        .map(
            item -> {
              @SuppressWarnings("unchecked")
              Map<String, Object> map = (Map<String, Object>) item;
              return map;
            })
        .toList();
  }

  private static long asLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text && !text.isBlank()) {
      try {
        return Long.parseLong(text.trim());
      } catch (NumberFormatException ex) {
        return 0L;
      }
    }
    return 0L;
  }

  private static String describe(Throwable ex) {
    Throwable root = ex;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String message = root.getMessage();
    String text = root.getClass().getSimpleName() + ": " + (message == null ? "" : message);
    return text.length() > 300 ? text.substring(0, 300) : text;
  }

  /** 供运维观察当前配置目标。不含令牌。 */
  public Map<String, Object> describeTarget() {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("baseUrl", properties.baseUrl());
    view.put("serviceName", properties.serviceName());
    view.put("tokenConfigured", properties.tokenConfigured());
    view.put("connectTimeoutMs", properties.connectTimeout().toMillis());
    view.put("requestTimeoutMs", properties.requestTimeout().toMillis());
    return view;
  }
}
