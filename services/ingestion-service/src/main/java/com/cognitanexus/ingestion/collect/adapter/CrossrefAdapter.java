package com.cognitanexus.ingestion.collect.adapter;

import com.cognitanexus.ingestion.collect.domain.AdapterKind;
import com.cognitanexus.ingestion.collect.domain.CollectModels.CollectJob;
import com.cognitanexus.ingestion.collect.domain.CollectModels.CollectedRecord;
import com.cognitanexus.ingestion.collect.domain.CollectModels.FetchPage;
import com.cognitanexus.ingestion.collect.domain.CollectModels.SourceRunConfig;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.DataNature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Crossref 适配器。
 *
 * <p>依据：架构 V0.2 §6.2。数据来自 Crossref 公开 REST API（api.crossref.org），是 DOI 注册机构
 * 的权威元数据，因此本适配器产出的记录数据性质为 REAL——这是真实抓取，不是构造的演示数据。
 *
 * <p>字段映射（Crossref → 规范化记录）：
 *
 * <ul>
 *   <li>DOI → doi，同时作为 sourceRecordId（Crossref 中 DOI 稳定唯一）
 *   <li>title[0] → title
 *   <li>container-title[0] → venue
 *   <li>issued.date-parts[0][0] → publicationYear
 *   <li>author[] → authors[]（family+given 拼接、affiliation[].name → organizations、ORCID 去掉
 *       前缀）
 *   <li>subject[] → topics
 *   <li>abstract → abstractText（Crossref 返回带 JATS 标签，此处剥离标签）
 *   <li>is-referenced-by-count → citationCount，并固定标注 citationSource = Crossref
 * </ul>
 *
 * <p>关于引用量的取向：本适配器只报「Crossref 自己统计的引用数」，不试图跨来源合并出总引用。
 * 不同来源统计口径与覆盖范围不同，相加得到的数字没有可解释的含义；缺引用数据时留空，
 * 也不拿别处的数字补位——公开页面上「没有引用数据」与「引用数为 0」是两件不同的事。
 *
 * <p>刻意不采集的字段：许可与费用、作者邮箱、审稿信息。它们与「学术成果分享」这一用途无关，
 * 采集它们只会扩大个人信息面。
 */
public class CrossrefAdapter implements SourceAdapter {

  private static final int MAX_ABSTRACT_CHARS = 4000;

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String userAgent;
  private final int maxResponseBytes;

  public CrossrefAdapter(
      ObjectMapper objectMapper, HttpClient httpClient, String userAgent, int maxResponseBytes) {
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
    this.userAgent = userAgent;
    this.maxResponseBytes = maxResponseBytes;
  }

  @Override
  public AdapterKind kind() {
    return AdapterKind.CROSSREF;
  }

  @Override
  public FetchPage fetch(SourceRunConfig config, CollectJob job, String cursor) {
    int offset = parseOffset(cursor);
    String url = buildUrl(config, offset);
    JsonNode root = get(url);
    JsonNode message = root.path("message");
    JsonNode items = message.path("items");

    Instant fetchedAt = Instant.now();
    Set<String> allowed = new LinkedHashSet<>(config.allowedFields());
    List<CollectedRecord> records = new ArrayList<>();
    for (JsonNode item : items) {
      CollectedRecord record = toRecord(item, config, fetchedAt, allowed);
      if (record != null) {
        records.add(record);
      }
    }

    int total = message.path("total-results").asInt(-1);
    int nextOffset = offset + records.size();
    // 只在确实还有下一页时给出游标，避免最后一页之后再多跑一次空请求
    String nextCursor =
        records.isEmpty() || nextOffset >= total ? null : String.valueOf(nextOffset);
    return new FetchPage(records, nextCursor, total < 0 ? null : total);
  }

  private String buildUrl(SourceRunConfig config, int offset) {
    String base =
        config.baseEndpoint() == null || config.baseEndpoint().isBlank()
            ? "https://api.crossref.org/works"
            : config.baseEndpoint();
    String query = config.queryExpression() == null ? "" : config.queryExpression();
    String separator = base.contains("?") ? "&" : "?";
    return base
        + separator
        + "query.bibliographic="
        + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)
        + "&rows="
        + config.pageSize()
        + "&offset="
        + offset
        + "&select=DOI,title,author,container-title,issued,type,subject,abstract,URL,is-referenced-by-count";
  }

  private JsonNode get(String url) {
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
      if (response.statusCode() != 200) {
        throw new BusinessException(
            com.cognitanexus.sharedkernel.ErrorCode.DEPENDENCY_UNAVAILABLE,
            "Crossref 返回 " + response.statusCode());
      }
      byte[] body = response.body();
      if (body.length > maxResponseBytes) {
        throw new BusinessException(
            com.cognitanexus.sharedkernel.ErrorCode.BUSINESS_RULE_VIOLATION,
            "Crossref 响应超过允许上限 " + maxResponseBytes + " 字节，已拒绝解析");
      }
      return objectMapper.readTree(body);
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException(
          com.cognitanexus.sharedkernel.ErrorCode.DEPENDENCY_UNAVAILABLE,
          "抓取 Crossref 失败：" + ex.getClass().getSimpleName());
    }
  }

  private CollectedRecord toRecord(
      JsonNode item, SourceRunConfig config, Instant fetchedAt, Set<String> allowed) {
    String doi = text(item, "DOI");
    if (doi == null) {
      // 没有 DOI 的条目缺少稳定标识，无法参与幂等交接，跳过而不是编造一个标识
      return null;
    }
    String title = firstText(item.path("title"));
    if (title == null) {
      return null;
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    put(payload, allowed, "kind", "PAPER");
    put(payload, allowed, "title", title);
    put(payload, allowed, "doi", doi);
    put(payload, allowed, "venue", firstText(item.path("container-title")));
    put(payload, allowed, "publicationYear", year(item.path("issued")));
    put(payload, allowed, "publishedDate", publishedDate(item.path("issued")));
    put(payload, allowed, "abstractText", stripMarkup(text(item, "abstract")));
    put(payload, allowed, "sourceCategory", firstText(item.path("subject")));
    put(payload, allowed, "topics", textList(item.path("subject")));
    put(payload, allowed, "authors", authors(item.path("author")));
    // 引用量与来源必须成对出现：只有数字而没有来源，前端无法说明「这是谁统计的」，
    // 而来源缺失时展示引用数会让人误以为是全口径总数。core 侧同样成对校验。
    Integer citationCount = intOrNull(item.path("is-referenced-by-count"));
    if (citationCount != null) {
      put(payload, allowed, "citationCount", citationCount);
      put(payload, allowed, "citationSource", "Crossref");
    }
    // 明确标注：本字段为适配器声明的来源标识，供排查而非业务使用
    put(payload, allowed, "sourceUrl", text(item, "URL"));

    return new CollectedRecord(doi, payload, DataNature.REAL, null, null, fetchedAt);
  }

  private static List<Map<String, Object>> authors(JsonNode authorArray) {
    List<Map<String, Object>> authors = new ArrayList<>();
    if (!authorArray.isArray()) {
      return authors;
    }
    int order = 1;
    for (JsonNode node : authorArray) {
      String family = text(node, "family");
      String given = text(node, "given");
      String name = join(family, given);
      if (name == null) {
        String literal = text(node, "name");
        name = literal;
      }
      if (name == null) {
        continue;
      }
      Map<String, Object> author = new LinkedHashMap<>();
      author.put("name", name);
      author.put("order", order++);
      String orcid = text(node, "ORCID");
      if (orcid != null) {
        // Crossref 给出完整 URL 形态，core 侧会再规范化一次；这里先剥掉以减少歧义
        author.put(
            "orcid", orcid.replace("http://orcid.org/", "").replace("https://orcid.org/", ""));
      }
      List<String> organizations = new ArrayList<>();
      for (JsonNode affiliation : node.path("affiliation")) {
        String orgName = text(affiliation, "name");
        if (orgName != null) {
          organizations.add(orgName);
        }
      }
      if (!organizations.isEmpty()) {
        author.put("organizations", organizations);
      }
      authors.add(author);
    }
    return authors;
  }

  private static String join(String family, String given) {
    if (family == null && given == null) {
      return null;
    }
    if (family == null) {
      return given;
    }
    if (given == null) {
      return family;
    }
    return family + " " + given;
  }

  private static Integer year(JsonNode dateParts) {
    JsonNode first = dateParts.path("date-parts").path(0);
    if (!first.isArray() || first.isEmpty()) {
      return null;
    }
    JsonNode value = first.get(0);
    return value.isInt() ? value.asInt() : null;
  }

  private static String publishedDate(JsonNode dateParts) {
    JsonNode first = dateParts.path("date-parts").path(0);
    if (!first.isArray() || first.size() < 3) {
      // 只有年份或年份+月份时不编造日期：core 侧 PublicationYear 明确区分「年份精度」与
      // 「具体日期」，凑一个 01-01 会把估计值伪装成事实
      return null;
    }
    try {
      return String.format(
          Locale.ROOT,
          "%04d-%02d-%02d",
          first.get(0).asInt(),
          first.get(1).asInt(),
          first.get(2).asInt());
    } catch (RuntimeException ex) {
      return null;
    }
  }

  /** 剥离 JATS/HTML 标签：Crossref 摘要带标签，原样入库会让摘要字段混入标记语言。 */
  private static String stripMarkup(String raw) {
    if (raw == null) {
      return null;
    }
    String text = raw.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    if (text.isEmpty()) {
      return null;
    }
    return text.length() > MAX_ABSTRACT_CHARS ? text.substring(0, MAX_ABSTRACT_CHARS) : text;
  }

  private static String firstText(JsonNode arrayNode) {
    if (arrayNode == null || !arrayNode.isArray() || arrayNode.isEmpty()) {
      return null;
    }
    String value = arrayNode.get(0).asText(null);
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static List<String> textList(JsonNode arrayNode) {
    List<String> values = new ArrayList<>();
    if (arrayNode != null && arrayNode.isArray()) {
      for (JsonNode node : arrayNode) {
        String value = node.asText(null);
        if (value != null && !value.isBlank()) {
          values.add(value.trim());
        }
      }
    }
    return values;
  }

  /**
   * 读一个非负整数字段。
   *
   * <p>只是「读不到」就返回 null，不返回 0：引用数为 0 是一个明确的观测结论，而字段缺失是
   * 「来源没说」。把后者当 0 会让所有新论文在统计图上表现为「零引用」，看起来像真的。
   */
  private static Integer intOrNull(JsonNode node) {
    if (node == null || node.isMissingNode() || node.isNull() || !node.canConvertToInt()) {
      return null;
    }
    int value = node.asInt();
    return value < 0 ? null : value;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull()) {
      return null;
    }
    String text = value.asText(null);
    return text == null || text.isBlank() ? null : text.trim();
  }

  private static int parseOffset(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return 0;
    }
    try {
      return Math.max(0, Integer.parseInt(cursor.trim()));
    } catch (NumberFormatException ex) {
      throw new BusinessException(
          com.cognitanexus.sharedkernel.ErrorCode.INVALID_ARGUMENT,
          "Crossref 游标必须是整数偏移，实际为：" + cursor);
    }
  }

  /**
   * 按字段白名单写入。
   *
   * <p>白名单为空表示不额外限制。注意：白名单只控制「是否写入 payload」，不改变适配器读取了
   * 哪些来源字段——真正拦住敏感字段的是适配器本身没有映射它们。
   */
  private static void put(
      Map<String, Object> payload, Set<String> allowed, String key, Object value) {
    if (value == null) {
      return;
    }
    if (!allowed.isEmpty() && !allowed.contains(key)) {
      return;
    }
    payload.put(key, value);
  }
}
