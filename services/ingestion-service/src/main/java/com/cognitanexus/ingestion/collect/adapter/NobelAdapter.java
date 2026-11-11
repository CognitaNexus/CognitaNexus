package com.cognitanexus.ingestion.collect.adapter;

import com.cognitanexus.ingestion.collect.domain.AdapterKind;
import com.cognitanexus.ingestion.collect.domain.CollectModels.CollectJob;
import com.cognitanexus.ingestion.collect.domain.CollectModels.CollectedRecord;
import com.cognitanexus.ingestion.collect.domain.CollectModels.FetchPage;
import com.cognitanexus.ingestion.collect.domain.CollectModels.SourceRunConfig;
import com.cognitanexus.ingestion.platform.util.MarkupText;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 诺贝尔奖官方 API 适配器，产出「获奖」类真实记录。
 *
 * <p>依据：AC-IMPL-FR16-01（四类成果各须有可公开样例）。任务书要求获奖数据「单独爬取」且优先
 * 使用公开数据集；Nobel Prize API 是颁发方自己发布的开放数据，字段含获奖人、年份、奖项全称、
 * 授奖理由与所属机构，因此这里产出的是<b>真实获奖记录</b>，不是构造样例。
 *
 * <p>字段映射（Nobel → 规范化记录）：
 *
 * <ul>
 *   <li>laureate.nobelPrizes[].awardYear → awardYear，同时作为 publicationYear
 *   <li>categoryFullName.en → awardName（保留颁发方的完整奖项名，不在本地改写）
 *   <li>category.en → 标题中的奖项短名
 *   <li>motivation.en → abstractText（授奖理由，是来源原文，不是本地评价）
 *   <li>affiliations[0].name.en → 获奖时所属机构
 *   <li>knownName.en → 获奖人，作为第一位作者
 * </ul>
 *
 * <p>一条记录对应「一个人一次获奖」而不是「一个奖项」：同一年的物理学奖可能有三位获奖者，
 * 合并成一条会让每位学者的门户都显示同一份他人成果。因此 sourceRecordId 由「获奖人 + 年份 +
 * 奖项代码」组成，而不是年份 + 奖项。
 *
 * <p>刻意不采集：出生日期、性别、邮箱、家庭信息。获奖事实与学术归属不需要这些。
 */
public class NobelAdapter implements SourceAdapter {

  private static final String DEFAULT_BASE = "https://api.nobelprize.org/2.1/laureates";

  /** 单页上限按接口保守取值；过大分页在接口侧会被截断，末页判据随之失真。 */
  private static final int MAX_PAGE_SIZE = 100;

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String userAgent;
  private final int maxResponseBytes;

  public NobelAdapter(
      ObjectMapper objectMapper, HttpClient httpClient, String userAgent, int maxResponseBytes) {
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
    this.userAgent = userAgent;
    this.maxResponseBytes = maxResponseBytes;
  }

  @Override
  public AdapterKind kind() {
    return AdapterKind.NOBEL;
  }

  @Override
  public FetchPage fetch(SourceRunConfig config, CollectJob job, String cursor) {
    int pageSize = effectivePageSize(config.pageSize());
    int offset = offsetOf(cursor);
    String url = buildUrl(config, pageSize, offset);
    JsonNode root = get(url);
    JsonNode laureates = root.path("laureates");

    Instant fetchedAt = Instant.now();
    Set<String> allowed = new LinkedHashSet<>(config.allowedFields());
    List<CollectedRecord> records = new ArrayList<>();
    if (laureates.isArray()) {
      for (JsonNode laureate : laureates) {
        collectPrizes(laureate, fetchedAt, allowed, records);
      }
    }

    JsonNode meta = root.path("meta");
    Integer total = meta.path("count").isInt() ? meta.path("count").asInt() : null;
    boolean exhausted =
        laureates.isEmpty()
            || laureates.size() < pageSize
            || (total != null && offset + laureates.size() >= total);
    String nextCursor = exhausted ? null : String.valueOf(offset + pageSize);
    return new FetchPage(records, nextCursor, total);
  }

  static void collectPrizes(
      JsonNode laureate, Instant fetchedAt, Set<String> allowed, List<CollectedRecord> records) {
    String laureateId = text(laureate, "id");
    String name = localized(laureate.path("knownName"), "en");
    if (laureateId == null || name == null) {
      return;
    }
    JsonNode prizes = laureate.path("nobelPrizes");
    if (!prizes.isArray()) {
      return;
    }
    for (JsonNode prize : prizes) {
      String year = text(prize, "awardYear");
      String category = localized(prize.path("category"), "en");
      if (year == null || category == null) {
        continue;
      }
      String categoryCode = categoryCode(prize, category);
      // 同一人多奖项时用「年份 + 奖项代码」区分，避免第二座奖覆盖第一座
      String recordId = "nobel-" + laureateId + "-" + year + "-" + categoryCode;

      Map<String, Object> payload = new LinkedHashMap<>();
      put(payload, allowed, "kind", "AWARD");
      put(payload, allowed, "title", "Nobel Prize in " + category + " " + year);
      put(
          payload,
          allowed,
          "awardName",
          firstNonNull(
              localized(prize.path("categoryFullName"), "en"), "Nobel Prize in " + category));
      put(payload, allowed, "awardYear", Integer.parseInt(year));
      put(payload, allowed, "awardLevel", "INTERNATIONAL");
      put(payload, allowed, "grantingBody", "Nobel Foundation");
      put(payload, allowed, "publicationYear", Integer.parseInt(year));
      put(payload, allowed, "abstractText", localized(prize.path("motivation"), "en"));
      put(payload, allowed, "sourceUrl", sourceUrl(prize));
      put(payload, allowed, "authors", authors(name, organization(prize)));

      records.add(new CollectedRecord(recordId, payload, DataNature.REAL, null, null, fetchedAt));
    }
  }

  private static List<Map<String, Object>> authors(String name, String organization) {
    List<Map<String, Object>> authors = new ArrayList<>();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("name", name);
    view.put("order", 1);
    if (organization != null) {
      view.put("organizations", List.of(organization));
    }
    authors.add(view);
    return authors;
  }

  private static String organization(JsonNode prize) {
    JsonNode affiliations = prize.path("affiliations");
    if (!affiliations.isArray()) {
      return null;
    }
    for (JsonNode affiliation : affiliations) {
      String name = localized(affiliation.path("name"), "en");
      if (name != null) {
        return name;
      }
    }
    return null;
  }

  /** 奖项代码取颁发方自己的短码（如 eco / phy / che），缺失时用英文类别名归一化。 */
  static String categoryCode(JsonNode prize, String category) {
    JsonNode links = prize.path("links");
    if (links.isArray()) {
      for (JsonNode link : links) {
        String href = text(link, "href");
        if (href != null && href.contains("/nobelPrize/")) {
          String tail = href.substring(href.indexOf("/nobelPrize/") + "/nobelPrize/".length());
          int slash = tail.indexOf('/');
          if (slash > 0) {
            return tail.substring(0, slash);
          }
        }
      }
    }
    return category.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }

  private static String sourceUrl(JsonNode prize) {
    JsonNode links = prize.path("links");
    if (links.isArray()) {
      for (JsonNode link : links) {
        String href = text(link, "href");
        if (href != null && href.contains("/nobelPrize/")) {
          return href;
        }
      }
    }
    return null;
  }

  static String localized(JsonNode node, String language) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    JsonNode value = node.path(language);
    if (value.isMissingNode() || value.isNull()) {
      return null;
    }
    return MarkupText.decodeEntities(value.asText(null));
  }

  private int effectivePageSize(int configured) {
    if (configured <= 0) {
      return MAX_PAGE_SIZE;
    }
    return Math.min(configured, MAX_PAGE_SIZE);
  }

  /**
   * 游标即 offset。
   *
   * <p>非法游标直接报错而不是回落到第一页：回落会让任务每次从头采，看起来一直在工作，
   * 实际永远推进不到新记录。
   */
  static int offsetOf(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return 0;
    }
    try {
      int offset = Integer.parseInt(cursor.trim());
      if (offset < 0) {
        throw new NumberFormatException("offset 不能为负");
      }
      return offset;
    } catch (NumberFormatException ex) {
      throw new BusinessException(
          ErrorCode.BUSINESS_RULE_VIOLATION, "Nobel 游标不是合法 offset：" + cursor);
    }
  }

  static String buildUrl(String base, int pageSize, int offset) {
    String root = base == null || base.isBlank() ? DEFAULT_BASE : base;
    StringBuilder url = new StringBuilder(root);
    url.append(root.contains("?") ? "&" : "?");
    url.append("limit=").append(pageSize);
    url.append("&offset=").append(offset);
    return url.toString();
  }

  private String buildUrl(SourceRunConfig config, int pageSize, int offset) {
    return buildUrl(config.baseEndpoint(), pageSize, offset);
  }

  private JsonNode get(String url) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", userAgent == null ? "CognitaNexus/0.2" : userAgent)
            .header("Accept", "application/json")
            .GET()
            .build();
    try {
      HttpResponse<byte[]> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200) {
        // 带上 Retry-After：来源明确说了等多久时，用自己的指数退避覆盖它是错的
        throw SourceFetchException.httpStatus(
            "Nobel",
            response.statusCode(),
            response.headers().firstValue("Retry-After").orElse(null));
      }
      byte[] body = response.body();
      if (body.length > maxResponseBytes) {
        throw SourceFetchException.oversized("Nobel", maxResponseBytes);
      }
      return objectMapper.readTree(body);
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw SourceFetchException.transport("Nobel", ex);
    }
  }

  private static void put(
      Map<String, Object> payload, Set<String> allowed, String key, Object value) {
    if (value == null) {
      return;
    }
    if (allowed != null && !allowed.isEmpty() && !allowed.contains(key)) {
      return;
    }
    payload.put(key, value);
  }

  private static String firstNonNull(String first, String second) {
    return first == null ? second : first;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.path(field);
    if (value == null || value.isMissingNode() || value.isNull()) {
      return null;
    }
    String text = value.asText();
    return text == null || text.isBlank() ? null : text.trim();
  }
}
