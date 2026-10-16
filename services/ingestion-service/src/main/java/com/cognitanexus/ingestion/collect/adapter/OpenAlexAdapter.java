package com.cognitanexus.ingestion.collect.adapter;

import com.cognitanexus.ingestion.collect.domain.AdapterKind;
import com.cognitanexus.ingestion.collect.domain.CollectModels.CollectJob;
import com.cognitanexus.ingestion.collect.domain.CollectModels.CollectedRecord;
import com.cognitanexus.ingestion.collect.domain.CollectModels.FetchPage;
import com.cognitanexus.ingestion.collect.domain.CollectModels.SourceRunConfig;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.DataNature;
import com.cognitanexus.sharedkernel.ErrorCode;
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
import java.util.Map;
import java.util.Set;

/**
 * OpenAlex 适配器。
 *
 * <p>依据：架构 V0.2 §6.2。数据来自 OpenAlex 公开 API（api.openalex.org），包含作品的作者、
 * 作者 ORCID 与机构归属，因此是本系统「学者—机构—成果」关系的主要真实来源之一。产出记录
 * 数据性质为 REAL。
 *
 * <p>与 Crossref 适配器的分工：两者都覆盖期刊论文，因此同一篇论文（同一 DOI）会经两条来源到达
 * core。这正是检验「跨源重复不增加成果实体数」的场景——core 按稳定标识 DOI 识别为既有实体并只
 * 追加来源归属。
 *
 * <p>字段映射（OpenAlex → 规范化记录）：
 *
 * <ul>
 *   <li>doi（去掉 https://doi.org/ 前缀）→ doi；缺失时退化为作品 ID 作为 sourceRecordId
 *   <li>title → title
 *   <li>primary_location.source.display_name → venue
 *   <li>publication_year → publicationYear，publication_date → publishedDate
 *   <li>cited_by_count → citationCount；citationSource 固定记为 OpenAlex
 *   <li>authorships[] → authors[]（author.display_name、author.orcid、institutions[].display_name）
 *   <li>concepts[] → topics
 *   <li>abstract_inverted_index → abstractText（需重建词序）
 * </ul>
 */
public class OpenAlexAdapter implements SourceAdapter {

  private static final int MAX_ABSTRACT_CHARS = 4000;

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String userAgent;
  private final int maxResponseBytes;

  public OpenAlexAdapter(
      ObjectMapper objectMapper, HttpClient httpClient, String userAgent, int maxResponseBytes) {
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
    this.userAgent = userAgent;
    this.maxResponseBytes = maxResponseBytes;
  }

  @Override
  public AdapterKind kind() {
    return AdapterKind.OPENALEX;
  }

  @Override
  public FetchPage fetch(SourceRunConfig config, CollectJob job, String cursor) {
    String url = buildUrl(config, cursor);
    JsonNode root = get(url);
    JsonNode results = root.path("results");

    Instant fetchedAt = Instant.now();
    Set<String> allowed = new LinkedHashSet<>(config.allowedFields());
    List<CollectedRecord> records = new ArrayList<>();
    for (JsonNode item : results) {
      CollectedRecord record = toRecord(item, fetchedAt, allowed);
      if (record != null) {
        records.add(record);
      }
    }

    JsonNode meta = root.path("meta");
    Integer total = meta.path("count").isInt() ? meta.path("count").asInt() : null;
    String nextCursor = text(meta, "next_cursor");
    return new FetchPage(records, nextCursor, total);
  }

  private String buildUrl(SourceRunConfig config, String cursor) {
    String base =
        config.baseEndpoint() == null || config.baseEndpoint().isBlank()
            ? "https://api.openalex.org/works"
            : config.baseEndpoint();
    String query = config.queryExpression() == null ? "" : config.queryExpression();
    StringBuilder url = new StringBuilder(base);
    url.append(base.contains("?") ? '&' : '?');
    url.append("search=")
        .append(java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8));
    url.append("&per-page=").append(config.pageSize());
    url.append("&select=id,doi,title,publication_year,publication_date,type,cited_by_count,")
        .append("primary_location,authorships,concepts,abstract_inverted_index");
    if (cursor != null && !cursor.isBlank()) {
      url.append("&cursor=")
          .append(java.net.URLEncoder.encode(cursor, java.nio.charset.StandardCharsets.UTF_8));
    }
    // 带上联系邮箱进入 polite pool，获得更稳定的配额；这是 OpenAlex 官方建议的做法
    if (userAgent != null && userAgent.contains("mailto:")) {
      String mail = userAgent.substring(userAgent.indexOf("mailto:") + 7);
      int end = mail.indexOf(')');
      if (end > 0) {
        mail = mail.substring(0, end);
      }
      url.append("&mailto=")
          .append(java.net.URLEncoder.encode(mail, java.nio.charset.StandardCharsets.UTF_8));
    }
    return url.toString();
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
            ErrorCode.DEPENDENCY_UNAVAILABLE, "OpenAlex 返回 " + response.statusCode());
      }
      byte[] body = response.body();
      if (body.length > maxResponseBytes) {
        throw new BusinessException(
            ErrorCode.BUSINESS_RULE_VIOLATION,
            "OpenAlex 响应超过允许上限 " + maxResponseBytes + " 字节，已拒绝解析");
      }
      return objectMapper.readTree(body);
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException(
          ErrorCode.DEPENDENCY_UNAVAILABLE, "抓取 OpenAlex 失败：" + ex.getClass().getSimpleName());
    }
  }

  private CollectedRecord toRecord(JsonNode item, Instant fetchedAt, Set<String> allowed) {
    String workId = text(item, "id");
    String doi = normalizeDoi(text(item, "doi"));
    // sourceRecordId 优先用 DOI：跨来源比对时 DOI 是共同键，作品 ID 只在 OpenAlex 内唯一
    String sourceRecordId = doi != null ? doi : workId;
    if (sourceRecordId == null) {
      return null;
    }
    String title = text(item, "title");
    if (title == null) {
      return null;
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    put(payload, allowed, "kind", "PAPER");
    put(payload, allowed, "title", title);
    put(payload, allowed, "doi", doi);
    put(
        payload,
        allowed,
        "venue",
        text(item.path("primary_location").path("source"), "display_name"));
    put(payload, allowed, "publicationYear", intOrNull(item, "publication_year"));
    put(payload, allowed, "publishedDate", fullDate(text(item, "publication_date")));
    put(payload, allowed, "citationCount", intOrNull(item, "cited_by_count"));
    put(payload, allowed, "citationSource", "OpenAlex");
    put(
        payload,
        allowed,
        "abstractText",
        reconstructAbstract(item.path("abstract_inverted_index")));
    put(payload, allowed, "topics", concepts(item.path("concepts")));
    put(payload, allowed, "sourceCategory", firstConcept(item.path("concepts")));
    put(payload, allowed, "authors", authors(item.path("authorships")));

    return new CollectedRecord(sourceRecordId, payload, DataNature.REAL, null, null, fetchedAt);
  }

  private static List<Map<String, Object>> authors(JsonNode authorships) {
    List<Map<String, Object>> authors = new ArrayList<>();
    if (!authorships.isArray()) {
      return authors;
    }
    int order = 1;
    for (JsonNode authorship : authorships) {
      JsonNode author = authorship.path("author");
      String name = text(author, "display_name");
      if (name == null) {
        continue;
      }
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("name", name);
      view.put("order", order++);
      String orcid = text(author, "orcid");
      if (orcid != null) {
        view.put("orcid", orcid.replace("https://orcid.org/", "").replace("http://orcid.org/", ""));
      }
      String openAlexId = text(author, "id");
      if (openAlexId != null) {
        // OpenAlex 作者 ID 是稳定的跨来源学者标识，交给 core 用于建立自动关联
        view.put("openAlexId", openAlexId.replace("https://openalex.org/", ""));
      }
      List<String> organizations = new ArrayList<>();
      for (JsonNode institution : authorship.path("institutions")) {
        String orgName = text(institution, "display_name");
        if (orgName != null) {
          organizations.add(orgName);
        }
      }
      if (!organizations.isEmpty()) {
        view.put("organizations", organizations);
      }
      String country = text(authorship.path("institutions").path(0), "country_code");
      if (country != null) {
        view.put("countryCode", country);
      }
      authors.add(view);
    }
    return authors;
  }

  /**
   * 重建摘要。
   *
   * <p>OpenAlex 出于版权考虑不返回摘要原文，而是返回「词 → 出现位置数组」的倒排索引。要还原成
   * 可读文本必须按位置把词填回原位。若只把词拼起来，会得到一份语序错乱的文本，比没有摘要更糟。
   */
  static String reconstructAbstract(JsonNode invertedIndex) {
    if (invertedIndex == null || invertedIndex.isMissingNode() || !invertedIndex.isObject()) {
      return null;
    }
    int maxPosition = -1;
    var fields = invertedIndex.fields();
    while (fields.hasNext()) {
      var entry = fields.next();
      for (JsonNode position : entry.getValue()) {
        maxPosition = Math.max(maxPosition, position.asInt(-1));
      }
    }
    if (maxPosition < 0) {
      return null;
    }
    String[] words = new String[maxPosition + 1];
    fields = invertedIndex.fields();
    while (fields.hasNext()) {
      var entry = fields.next();
      for (JsonNode position : entry.getValue()) {
        int index = position.asInt(-1);
        if (index >= 0 && index < words.length) {
          words[index] = entry.getKey();
        }
      }
    }
    StringBuilder builder = new StringBuilder();
    for (String word : words) {
      if (word == null) {
        continue;
      }
      if (builder.length() > 0) {
        builder.append(' ');
      }
      builder.append(word);
    }
    if (builder.isEmpty()) {
      return null;
    }
    return builder.length() > MAX_ABSTRACT_CHARS
        ? builder.substring(0, MAX_ABSTRACT_CHARS)
        : builder.toString();
  }

  private static List<String> concepts(JsonNode conceptArray) {
    List<String> values = new ArrayList<>();
    if (conceptArray.isArray()) {
      for (JsonNode node : conceptArray) {
        String name = text(node, "display_name");
        if (name != null) {
          values.add(name);
        }
      }
    }
    return values;
  }

  private static String firstConcept(JsonNode conceptArray) {
    List<String> values = concepts(conceptArray);
    return values.isEmpty() ? null : values.get(0);
  }

  static String normalizeDoi(String raw) {
    if (raw == null) {
      return null;
    }
    String text = raw.trim();
    if (text.regionMatches(true, 0, "https://doi.org/", 0, 16)) {
      text = text.substring(16);
    } else if (text.regionMatches(true, 0, "http://doi.org/", 0, 15)) {
      text = text.substring(15);
    }
    return text.isBlank() ? null : text;
  }

  /** 只接受完整的 YYYY-MM-DD：只有年份或年月时不编造缺失的部分。 */
  private static String fullDate(String raw) {
    if (raw == null || raw.length() != 10) {
      return null;
    }
    return raw;
  }

  private static Integer intOrNull(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isNumber() ? value.asInt() : null;
  }

  private static String text(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull()) {
      return null;
    }
    String text = value.asText(null);
    return text == null || text.isBlank() ? null : text.trim();
  }

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
