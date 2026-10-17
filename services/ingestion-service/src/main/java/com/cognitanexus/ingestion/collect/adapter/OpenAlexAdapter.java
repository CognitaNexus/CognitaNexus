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
 *   <li>topics[].field/subfield → topics，经 {@link OpenAlexFieldMapper} 翻成平台领域；
 *       映射不上的主题不落库，避免英文主题名撑开领域分面
 *   <li>primary_topic.field → sourceCategory，表示「来源自己认定的一级学科」
 *   <li>abstract_inverted_index → abstractText（需重建词序）
 * </ul>
 */
public class OpenAlexAdapter implements SourceAdapter {

  private static final int MAX_ABSTRACT_CHARS = 4000;

  /** 单篇成果最多保留的领域数，理由见 {@link #mappedTopics}。 */
  private static final int MAX_MAPPED_TOPICS = 3;

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String userAgent;
  private final int maxResponseBytes;
  private final OpenAlexFieldMapper fieldMapper;

  public OpenAlexAdapter(
      ObjectMapper objectMapper,
      HttpClient httpClient,
      String userAgent,
      int maxResponseBytes,
      OpenAlexFieldMapper fieldMapper) {
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
    this.userAgent = userAgent;
    this.maxResponseBytes = maxResponseBytes;
    this.fieldMapper = fieldMapper;
  }

  @Override
  public AdapterKind kind() {
    return AdapterKind.OPENALEX;
  }

  @Override
  public FetchPage fetch(SourceRunConfig config, CollectJob job, String cursor) {
    int pageNo = pageNumber(cursor);
    String url = buildUrl(config, pageNo);
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
    // 末页由自己算：page 模式下 OpenAlex 不回 next_cursor。判据取「本页不满」或「已翻到总数之后」，
    // 任一成立即停；若只按「本页不满」判断，总数正好是页大小整数倍时会多抓一个空页。
    boolean exhausted =
        results.isEmpty()
            || results.size() < config.pageSize()
            || (total != null && (long) pageNo * config.pageSize() >= total);
    String nextCursor = exhausted ? null : String.valueOf(pageNo + 1);
    return new FetchPage(records, nextCursor, total);
  }

  /**
   * 把任务保存的游标解成页码。
   *
   * <p>非数字游标直接报错而不是回落到第 1 页：回落会让任务从第一页重新采一遍，表面「有进展」
   * （暂存数不为零），实际永远推进不到新数据，且日志里看不出异常。
   */
  static int pageNumber(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return 1;
    }
    try {
      int page = Integer.parseInt(cursor.trim());
      if (page < 1) {
        throw new NumberFormatException("页码必须为正");
      }
      return page;
    } catch (NumberFormatException ex) {
      throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "OpenAlex 游标不是合法页码：" + cursor);
    }
  }

  private String buildUrl(SourceRunConfig config, int pageNo) {
    String base =
        config.baseEndpoint() == null || config.baseEndpoint().isBlank()
            ? "https://api.openalex.org/works"
            : config.baseEndpoint();
    String query = config.queryExpression() == null ? "" : config.queryExpression();
    return buildUrl(base, query, config.pageSize(), pageNo, userAgent);
  }

  /**
   * 组装查询 URL。
   *
   * <p>用 {@code page} 偏移分页而不是 {@code cursor}：OpenAlex 的游标翻页已经要求 API key
   * （免费层对 {@code cursor} 的第二页起返回 401 "API key required for cursor paging"），
   * 而 {@code page} 分页在无密钥时仍可用。若沿用游标，无密钥环境下任务能抓到第一页就被判成
   * 末页、状态还是 SUCCEEDED——看起来采集成功，实际只采了搜索结果的五十分之一。</p>
   *
   * <p>拿到 API key 后可换回游标：游标在数据变动时比偏移稳定（偏移会因插入而漏行或重行），
   * 但那是精度提升，不是能否翻页的前提。</p>
   */
  static String buildUrl(String base, String query, int pageSize, int pageNo, String userAgent) {
    StringBuilder url = new StringBuilder(base);
    url.append(base.contains("?") ? "&" : "?");
    url.append("search=")
        .append(java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8));
    url.append("&per-page=").append(pageSize);
    url.append("&page=").append(pageNo);
    url.append("&select=id,doi,title,publication_year,publication_date,type,cited_by_count,")
        .append("primary_location,authorships,topics,primary_topic,abstract_inverted_index");
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
        // 带上 Retry-After：来源明确说了等多久时，用自己的指数退避覆盖它是错的
        throw SourceFetchException.httpStatus(
            "OpenAlex",
            response.statusCode(),
            response.headers().firstValue("Retry-After").orElse(null));
      }
      byte[] body = response.body();
      if (body.length > maxResponseBytes) {
        throw SourceFetchException.oversized("OpenAlex", maxResponseBytes);
      }
      return objectMapper.readTree(body);
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw SourceFetchException.transport("OpenAlex", ex);
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
    put(payload, allowed, "topics", mappedTopics(item.path("topics"), fieldMapper));
    // sourceCategory 记的是「来源声明该成果属于哪个领域」的原始主张，因此优先取 OpenAlex 的
    // 主学科（primary_topic.field），而不是 topics[0]：topics 已按 score 排序，首项可能是宽泛
    // 的跨学科主题，主学科才是来源自己认定的一级分类。
    put(
        payload,
        allowed,
        "sourceCategory",
        sourceCategory(item.path("primary_topic"), item.path("topics"), fieldMapper));
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

  /**
   * 把 OpenAlex 的 topics 分层翻译成平台领域。
   *
   * <p>只保留能映射到既有 18 个领域的分层：OpenAlex 的主题名（如 "Magnetic confinement fusion
   * research"）粒度远细于平台领域，原样入库会把领域表撑成几千个英文条目，分面与领域统计随之失真。
   * 映射不上的分层不保留，交由 core 的规则推断在标题/摘要上判定。
   *
   * <p>最多保留 3 个：领域统计按「一成果多领域」计数，条数过多会让单篇成果在领域排行榜里被重复
   * 放大；3 个足以表达跨学科成果的主要归属。
   */
  static List<String> mappedTopics(JsonNode topicArray, OpenAlexFieldMapper mapper) {
    java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
    if (topicArray != null && topicArray.isArray()) {
      for (JsonNode node : topicArray) {
        mapper
            .map(
                text(node.path("field"), "display_name"),
                text(node.path("subfield"), "display_name"))
            .ifPresent(names::add);
        if (names.size() >= MAX_MAPPED_TOPICS) {
          break;
        }
      }
    }
    return new ArrayList<>(names);
  }

  /**
   * 来源声明的领域。
   *
   * <p>主学科优先；主学科映射不上时退到 topics 里第一个能映射上的分层。两者都映射不上就返回 null，
   * 让 core 走推断路径——来源说了话但说的不是本平台领域体系里的词，等同于这一维上没有来源主张。
   */
  static String sourceCategory(
      JsonNode primaryTopic, JsonNode topicArray, OpenAlexFieldMapper mapper) {
    if (primaryTopic != null && primaryTopic.isObject()) {
      java.util.Optional<String> hit =
          mapper.map(
              text(primaryTopic.path("field"), "display_name"),
              text(primaryTopic.path("subfield"), "display_name"));
      if (hit.isPresent()) {
        return hit.get();
      }
    }
    List<String> mapped = mappedTopics(topicArray, mapper);
    return mapped.isEmpty() ? null : mapped.get(0);
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
