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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 美国国家科学基金会（NSF）资助项目适配器。
 *
 * <p>依据：AC-IMPL-FR16-01（四类成果各须有可公开样例）、AC-IMPL-FR16-02（规模按去重实体计）。
 * 任务书要求「科研项目可只考虑国家级项目」，NSF Award Search 是公开、免密钥、字段完整的国家级
 * 项目数据源，因此这里产出的是<b>真实项目记录</b>，不是构造样例。
 *
 * <p>字段映射（NSF → 规范化记录）：
 *
 * <ul>
 *   <li>id → projectNo，同时作为 sourceRecordId（NSF 的 award number 在 NSF 内稳定唯一）
 *   <li>title → title
 *   <li>pdPIName（缺失时用 piFirstName + piLastName）→ leaderName，并作为第一位作者
 *   <li>awardeeName → 机构（awardee 是承担单位，即「成果发生时机构」）
 *   <li>agency → fundingAgency
 *   <li>program[0] → projectCategory（NSF 的项目计划名，不是平台学术领域）
 *   <li>startDate / expDate（MM/DD/YYYY）→ projectStartDate / projectEndDate
 *   <li>abstractText → abstractText
 * </ul>
 *
 * <p>刻意不采集：PI 邮箱、电话、项目官员邮箱、承担单位地址与邮编。这些字段对「项目是什么、
 * 谁在做、哪个机构承担」没有贡献，采进来只会扩大个人信息面。
 *
 * <p>分页用 offset（NSF 不提供游标）。offset 存进任务游标；末页判据是「本页不满 rpp」，不用
 * 总数：NSF 的命中总数包含跨库记录，与可翻页范围并不一致，按总数判断会让最后一页永远差几条。
 */
public class NsfAdapter implements SourceAdapter {

  /** NSF 单页上限就是 25，请求更大值会被静默截断成 25，导致「本页不满即末页」的判据失真。 */
  private static final int MAX_PAGE_SIZE = 25;

  private static final String DEFAULT_BASE = "https://api.nsf.gov/services/v1/awards.json";

  private static final String PRINT_FIELDS =
      "id,title,startDate,expDate,awardeeName,pdPIName,piFirstName,piLastName,"
          + "fundsObligatedAmt,program,abstractText,agency";

  private static final DateTimeFormatter US_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy");

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String userAgent;
  private final int maxResponseBytes;

  public NsfAdapter(
      ObjectMapper objectMapper, HttpClient httpClient, String userAgent, int maxResponseBytes) {
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
    this.userAgent = userAgent;
    this.maxResponseBytes = maxResponseBytes;
  }

  @Override
  public AdapterKind kind() {
    return AdapterKind.NSF;
  }

  @Override
  public FetchPage fetch(SourceRunConfig config, CollectJob job, String cursor) {
    int pageSize = effectivePageSize(config.pageSize());
    int offset = offsetOf(cursor);
    String url = buildUrl(config, pageSize, offset);
    JsonNode root = get(url);
    JsonNode awards = root.path("response").path("award");

    Instant fetchedAt = Instant.now();
    Set<String> allowed = new LinkedHashSet<>(config.allowedFields());
    List<CollectedRecord> records = new ArrayList<>();
    if (awards.isArray()) {
      for (JsonNode item : awards) {
        CollectedRecord record = toRecord(item, fetchedAt, allowed);
        if (record != null) {
          records.add(record);
        }
      }
    }

    boolean exhausted = records.isEmpty() || awards.size() < pageSize;
    String nextCursor = exhausted ? null : String.valueOf(offset + pageSize);
    Integer total =
        root.path("response").path("meta").path("total-count").isInt()
            ? root.path("response").path("meta").path("total-count").asInt()
            : null;
    return new FetchPage(records, nextCursor, total);
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
   * <p>非法游标直接报错，不回落到第一页：回落会让任务「一直有进展」却永远停在头几页。
   */
  static int offsetOf(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return 1;
    }
    try {
      int offset = Integer.parseInt(cursor.trim());
      if (offset < 1) {
        throw new NumberFormatException("offset 必须为正");
      }
      return offset;
    } catch (NumberFormatException ex) {
      throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "NSF 游标不是合法 offset：" + cursor);
    }
  }

  static String buildUrl(String base, String keyword, int pageSize, int offset, String userAgent) {
    String root = base == null || base.isBlank() ? DEFAULT_BASE : base;
    StringBuilder url = new StringBuilder(root);
    url.append(root.contains("?") ? "&" : "?");
    url.append("keyword=")
        .append(
            java.net.URLEncoder.encode(
                keyword == null ? "" : keyword, java.nio.charset.StandardCharsets.UTF_8));
    url.append("&rpp=").append(pageSize);
    url.append("&offset=").append(offset);
    url.append("&printFields=").append(PRINT_FIELDS);
    // NSF 不要求联系邮箱，也不认 mailto 参数；联系信息只通过 User-Agent 传递。
    return url.toString();
  }

  private String buildUrl(SourceRunConfig config, int pageSize, int offset) {
    return buildUrl(config.baseEndpoint(), config.queryExpression(), pageSize, offset, userAgent);
  }

  static CollectedRecord toRecord(JsonNode item, Instant fetchedAt, Set<String> allowed) {
    String awardId = text(item, "id");
    String title = MarkupText.decodeEntities(text(item, "title"));
    if (awardId == null || title == null) {
      return null;
    }

    String leader = text(item, "pdPIName");
    if (leader == null) {
      String first = text(item, "piFirstName");
      String last = text(item, "piLastName");
      leader = join(first, last);
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    put(payload, allowed, "kind", "PROJECT");
    put(payload, allowed, "title", title);
    put(payload, allowed, "projectNo", awardId);
    put(payload, allowed, "leaderName", leader);
    put(payload, allowed, "fundingAgency", text(item, "agency"));
    put(payload, allowed, "projectCategory", firstArrayText(item.path("program")));
    put(payload, allowed, "projectStartDate", isoDate(text(item, "startDate")));
    put(payload, allowed, "projectEndDate", isoDate(text(item, "expDate")));
    put(payload, allowed, "abstractText", MarkupText.decodeEntities(text(item, "abstractText")));
    // 年份进 publicationYear 而不是只放 awardYear：年份筛选、年度趋势、代表成果排序都读这一个
    // 字段，只写项目专属字段会让项目在检索的「按年份」维度上整类消失。
    LocalDate start = parseDate(text(item, "startDate"));
    put(payload, allowed, "publicationYear", start == null ? null : start.getYear());
    put(
        payload,
        allowed,
        "sourceUrl",
        "https://www.nsf.gov/awardsearch/showAward?AWD_ID=" + awardId);
    put(payload, allowed, "authors", authors(leader, text(item, "awardeeName")));

    return new CollectedRecord(awardId, payload, DataNature.REAL, null, null, fetchedAt);
  }

  private static List<Map<String, Object>> authors(String leader, String organization) {
    List<Map<String, Object>> authors = new ArrayList<>();
    if (leader == null || leader.isBlank()) {
      return authors;
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("name", leader);
    view.put("order", 1);
    if (organization != null && !organization.isBlank()) {
      view.put("organizations", List.of(organization));
    }
    authors.add(view);
    return authors;
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
        throw new BusinessException(
            ErrorCode.DEPENDENCY_UNAVAILABLE, "NSF 返回 " + response.statusCode());
      }
      byte[] body = response.body();
      if (body.length > maxResponseBytes) {
        throw new BusinessException(
            ErrorCode.BUSINESS_RULE_VIOLATION, "NSF 响应超过允许上限 " + maxResponseBytes + " 字节，已拒绝解析");
      }
      return objectMapper.readTree(body);
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException(
          ErrorCode.DEPENDENCY_UNAVAILABLE, "抓取 NSF 失败：" + ex.getClass().getSimpleName());
    }
  }

  private static String firstArrayText(JsonNode node) {
    if (node != null && node.isArray() && !node.isEmpty()) {
      return MarkupText.decodeEntities(node.get(0).asText(null));
    }
    return null;
  }

  private static LocalDate parseDate(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(raw.trim(), US_DATE);
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private static String isoDate(String raw) {
    LocalDate date = parseDate(raw);
    return date == null ? null : date.toString();
  }

  private static String join(String first, String last) {
    String left = first == null ? "" : first.trim();
    String right = last == null ? "" : last.trim();
    String joined = (left + " " + right).trim();
    return joined.isEmpty() ? null : joined;
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

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.path(field);
    if (value == null || value.isMissingNode() || value.isNull()) {
      return null;
    }
    String text = value.asText();
    return text == null || text.isBlank() ? null : text.trim();
  }
}
