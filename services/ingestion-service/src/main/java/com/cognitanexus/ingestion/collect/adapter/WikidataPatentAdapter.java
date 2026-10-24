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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wikidata 专利适配器。
 *
 * <p>为什么专利要单独接一家：论文有 Crossref/OpenAlex 的通用元数据服务，专利没有等价的公共接口。
 * 试过的几条路都不通：USPTO 的 ODP 要 API key（无 key 直接 401），EPO OPS 的匿名访问 403，
 * PatentsView 的旧接口已下线。Wikidata 是唯一免密钥、可程序化查询、且条款允许再分发的来源
 * （CC0）。
 *
 * <p>为什么用 SPARQL 而不是它自己的 REST 检索：Wikidata 的 wbsearchentities 是「按名字找条目」，
 * 返回的是条目而不是专利记录，拿不到专利号、公开日、发明人这些字段；SPARQL 能一次把需要的属性
 * 都投影出来。
 *
 * <p>已知的数据形态（实测，不是推测）：
 *
 * <ul>
 *   <li>一个专利条目在结果里会出现多行——每个发明人一行（{@code P61} 多值），必须按条目聚合，
 *       否则同一件专利会被当成多件；
 *   <li>{@code P127}（申请人／权利人）大面积为空。空就标未知，不用发明人顶替：申请人缺失是来源
 *       的事实，编一个出来会让机构统计凭空多出归属；
 *   <li>{@code P1246} 是专利号（公开号）。Wikidata 没有稳定的「申请号」属性，所以
 *       {@code applicationNo} 一律留空，而不是把公开号复制两份。
 * </ul>
 *
 * <p><b>属性号必须按名字核对，不能凭印象写。</b>第一次上线时 {@code P2676} 被当成 IPC 分类号，
 * 实际它是「电影分级编号」；{@code P495} 被当成国家，实际是「原产国」（专利里几乎不用）。两处都
 * 不报错，只是永远采不到值——1122 件专利的分类号全部为空，看起来像「来源没给」。按
 * {@code ?patent ?p ?o} 做属性普查后确认：分类号只有 {@code P5778}（CPC，884 件）、国家是
 * {@code P17}（139 件），而 Wikidata 上<b>根本没有 IPC 属性</b>。
 */
public class WikidataPatentAdapter implements SourceAdapter {

  /** Wikidata 的 SPARQL 端点。写成常量便于测试替换 baseEndpoint。 */
  static final String DEFAULT_ENDPOINT = "https://query.wikidata.org/sparql";

  /**
   * Wikidata 的「专利」条目类型。
   *
   * <p>用 {@code wdt:P31 wd:Q253623}（instance of patent）而不用 {@code P31/P279*} 的传递闭包：
   * 闭包会把「专利申请」「专利家族」等子类一并算进来，同一个专利家族的不同成员会各自成为一条
   * 成果，规模虚高。
   */
  private static final String PATENT_CLASS = "Q253623";

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String userAgent;
  private final int maxResponseBytes;

  public WikidataPatentAdapter(
      ObjectMapper objectMapper, HttpClient httpClient, String userAgent, int maxResponseBytes) {
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
    this.userAgent = userAgent;
    this.maxResponseBytes = maxResponseBytes <= 0 ? 4 * 1024 * 1024 : maxResponseBytes;
  }

  @Override
  public AdapterKind kind() {
    return AdapterKind.WIKIDATA_PATENT;
  }

  @Override
  public FetchPage fetch(SourceRunConfig config, CollectJob job, String cursor) {
    int offset = offsetOf(cursor);
    int pageSize = config.pageSize() > 0 ? config.pageSize() : 100;
    String endpoint =
        config.baseEndpoint() == null || config.baseEndpoint().isBlank()
            ? DEFAULT_ENDPOINT
            : config.baseEndpoint();
    // 查询串是「附加过滤」而不是「检索词」：专利没有通用的全文检索接口，关键词匹配只能在本地按
    // 标题过滤。留空表示取全量清单（约 1100 条），非空时用 FILTER 在标题上做大小写不敏感的包含。
    String keyword = config.queryExpression() == null ? "" : config.queryExpression().trim();
    String sparql = buildQuery(keyword, pageSize, offset);
    JsonNode root = get(endpoint, sparql);

    Instant fetchedAt = Instant.now();
    Set<String> allowed = new LinkedHashSet<>(config.allowedFields());
    List<CollectedRecord> records =
        toRecords(root.path("results").path("bindings"), fetchedAt, allowed);
    // 末页由「本页不满」判定。Wikidata 的 SPARQL 不回总数（要额外跑一条 count 查询，代价与收益
    // 不成比例），所以 totalAvailable 留 null 而不是填 0。
    String nextCursor = records.size() < pageSize ? null : String.valueOf(offset + records.size());
    return new FetchPage(records, nextCursor, null);
  }

  /**
   * 把游标解成偏移量。
   *
   * <p>非数字游标直接报错而不回落到 0：回落会让任务从第一条重新采，表面有进展、实际永远推不到
   * 新数据，而日志里看不出异常。
   */
  static int offsetOf(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return 0;
    }
    try {
      int offset = Integer.parseInt(cursor.trim());
      if (offset < 0) {
        throw new NumberFormatException("偏移必须非负");
      }
      return offset;
    } catch (NumberFormatException ex) {
      throw new BusinessException(
          ErrorCode.BUSINESS_RULE_VIOLATION, "Wikidata 专利游标不是合法偏移量：" + cursor);
    }
  }

  /**
   * 组装 SPARQL。
   *
   * <p>分页必须作用在「专利条目」上，不能作用在最终结果行上：一件专利有多个发明人就会有对应的
   * 多行，按行偏移会让相邻两页取到同一件专利的一部分，而中间那件被跳过。所以内层子查询先按
   * 条目取一页，外层再展开属性。
   *
   * <p>{@code ORDER BY ?patent} 不是装饰：没有确定的排序，OFFSET 分页在数据变动时会漏行或重行。
   *
   * <p><b>关键词模式实测不可用（2026-10-23）。</b>{@code keyword=laser} 的单页查询耗时 71.7 秒，
   * 已超过本适配器自己的 60 秒 HTTP 超时：带关键词的任务会表现为传输层超时，而不是「查不到」。原因是
   * 包含匹配要在 {@code rdfs:label} 上做，WDQS 据此无法裁剪。专利的正常用法是遍历全量清单（约一千
   * 条），所以带关键词的模式不要在调度里使用；保留它是因为接口契约要求来源有个 queryExpression，
   * 不是因为它在这个来源上性能可用。
   */
  static String buildQuery(String keyword, int pageSize, int offset) {
    StringBuilder query = new StringBuilder();
    query
        .append("SELECT ?patent ?patentLabel ?number ?pubdate ?filing ?applicantLabel ")
        .append("?inventorLabel ?cpc ?countryLabel WHERE { ");
    query
        .append("{ SELECT DISTINCT ?patent WHERE { ")
        .append("?patent wdt:P31 wd:")
        .append(PATENT_CLASS)
        .append(" . ?patent wdt:P1246 ?n . ");
    if (keyword != null && !keyword.isBlank()) {
      // 关键词过滤必须写在子查询的 WHERE 里，不能写在内层 WHERE 闭合之后：SPARQL 的三联组
      // 一旦被 } 关掉，后面就只能跟解算修饰符（ORDER BY/LIMIT）或新的模式，再写 FILTER 是语法错误。
      // Wikidata 的标签过滤只能作用在 rdfs:label 上；用 LCASE + CONTAINS 做大小写不敏感的包含。
      // 这是唯一的「关键词」能力，不是全文检索：只匹配标题，不匹配摘要或权利要求。
      query
          .append("?patent rdfs:label ?kwLabel . ")
          .append("FILTER(CONTAINS(LCASE(?kwLabel), \"")
          .append(sparqlString(keyword.toLowerCase(java.util.Locale.ROOT)))
          .append("\")) ");
    }
    // 这里只闭合内层 WHERE，随后写子查询自己的 ORDER BY/LIMIT/OFFSET，再单独闭合子查询。
    // 顺序写反（先闭合子查询再写 ORDER BY）会把修饰符放到外层 WHERE 组里，成为语法错误——
    // Wikidata 只会回一个 400 并把查询原样回显，看不出错在哪。
    query.append("} ORDER BY ?patent LIMIT ").append(Math.max(pageSize, 1));
    query.append(" OFFSET ").append(Math.max(offset, 0)).append(" } ");
    query.append("?patent wdt:P1246 ?number . ");
    query.append("OPTIONAL { ?patent wdt:P577 ?pubdate } ");
    query.append("OPTIONAL { ?patent wdt:P571 ?filing } ");
    query.append("OPTIONAL { ?patent wdt:P127 ?applicant } ");
    query.append("OPTIONAL { ?patent wdt:P61 ?inventor } ");
    // 分类号取 P5778（CPC）。这里不用 P2676：那是「电影分级编号」，专利条目上基本没有值，
    // 上线首轮 1122 件专利的分类号因此全空。Wikidata 没有 IPC 属性，能拿到的分类体系只有 CPC。
    query.append("OPTIONAL { ?patent wdt:P5778 ?cpc } ");
    // 国家取 P17（country）。P495 是「原产国」，专利里几乎不用，取它同样只会得到空值。
    query.append("OPTIONAL { ?patent wdt:P17 ?country } ");
    query.append("SERVICE wikibase:label { bd:serviceParam wikibase:language \"en,zh\". } ");
    query.append("}");
    return query.toString();
  }

  /** SPARQL 字符串字面量转义：只处理反斜杠与双引号，换行按空格处理。 */
  private static String sparqlString(String raw) {
    return raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
  }

  private JsonNode get(String endpoint, String sparql) {
    String url =
        endpoint
            + (endpoint.contains("?") ? "&" : "?")
            + "query="
            + URLEncoder.encode(sparql, StandardCharsets.UTF_8)
            + "&format=json";
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .header("User-Agent", userAgent)
            .header("Accept", "application/sparql-results+json")
            .GET()
            .build();
    try {
      HttpResponse<byte[]> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200) {
        // 429 是 Wikidata 的常见拒绝：SPARQL 端点对匿名查询有速率与算力预算，不区分「谁」。
        // 把 Retry-After 原样带上去，由调用方与自己的退避上限取小——用自己的推算覆盖对方的事实，
        // 会在对方限流期间持续敲门，把限流窗口不断刷新。
        //
        // 同时带上响应体片段：SPARQL 的 400 一定附带一段说明（语法错误、变量未绑定、查询被拒），
        // 只报状态码时无从判断是「我们的查询写错了」还是「对方临时拒绝」。
        String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
        throw SourceFetchException.httpStatus(
            "Wikidata", response.statusCode(), retryAfter, textOf(response.body()));
      }
      byte[] body = response.body();
      if (body != null && body.length > maxResponseBytes) {
        throw SourceFetchException.oversized("Wikidata", maxResponseBytes);
      }
      return objectMapper.readTree(body == null ? new byte[0] : body);
    } catch (SourceFetchException ex) {
      throw ex;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw SourceFetchException.transport("Wikidata", ex);
    } catch (Exception ex) {
      throw SourceFetchException.transport("Wikidata", ex);
    }
  }

  /**
   * 把 SPARQL 结果行聚合成成果记录。
   *
   * <p>聚合键用条目 URI 而不是专利号：同一件专利可能在不同语言标签下出现，但 URI 唯一。用号做键
   * 在个别来源里会把两件不同专利合掉（例如同一号在不同专利局重复登记）。
   */
  private List<CollectedRecord> toRecords(
      JsonNode bindings, Instant fetchedAt, Set<String> allowed) {
    Map<String, PatentAggregate> aggregates = new LinkedHashMap<>();
    for (JsonNode row : bindings) {
      String uri = value(row, "patent");
      if (uri == null) {
        continue;
      }
      PatentAggregate aggregate = aggregates.computeIfAbsent(uri, key -> new PatentAggregate());
      if (aggregate.number == null) {
        aggregate.number = value(row, "number");
      }
      if (aggregate.title == null) {
        aggregate.title = value(row, "patentLabel");
      }
      if (aggregate.publishedDate == null) {
        aggregate.publishedDate = datePart(value(row, "pubdate"));
      }
      if (aggregate.filingDate == null) {
        aggregate.filingDate = datePart(value(row, "filing"));
      }
      String applicant = value(row, "applicantLabel");
      if (applicant != null) {
        aggregate.applicants.add(applicant);
      }
      String inventor = value(row, "inventorLabel");
      if (inventor != null) {
        aggregate.inventors.add(inventor);
      }
      String cpc = value(row, "cpc");
      if (cpc != null) {
        aggregate.classificationCodes.add(cpc);
      }
      String country = value(row, "countryLabel");
      if (country != null) {
        aggregate.countries.add(country);
      }
    }

    List<CollectedRecord> records = new ArrayList<>(aggregates.size());
    for (PatentAggregate aggregate : aggregates.values()) {
      // 没有专利号的条目跳过：号是这件专利的唯一稳定标识，缺号既无法去重也无法对外展示。
      if (aggregate.number == null || aggregate.title == null) {
        continue;
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      put(payload, allowed, "kind", "PATENT");
      put(payload, allowed, "title", aggregate.title);
      // Wikidata 没有稳定的申请号属性；留空而不是拿公开号顶替。
      put(payload, allowed, "publicationNo", aggregate.number);
      put(payload, allowed, "patentPublicationDate", aggregate.publishedDate);
      put(payload, allowed, "filingDate", aggregate.filingDate);
      put(payload, allowed, "applicantNames", new ArrayList<>(aggregate.applicants));
      // 分类号写进 ipcCodes，但内容实际是 CPC（Wikidata 没有 IPC 属性）。这是字段名与体系不一致的
      // 一处妥协：core_patent 只有 ipc_codes 一列，而「有分类号」比「字段名严格对应」更有用。
      // 代价要写清楚——不要把这一列当成 IPC 报给外部，也不要用 IPC 的口径去校核它。
      put(payload, allowed, "ipcCodes", new ArrayList<>(aggregate.classificationCodes));
      // 发明人按来源给出的顺序编号；缺失顺序时按出现次序补齐，与论文作者的约定一致。
      List<Map<String, Object>> authors = new ArrayList<>();
      int order = 1;
      for (String inventor : aggregate.inventors) {
        Map<String, Object> author = new LinkedHashMap<>();
        author.put("name", inventor);
        author.put("order", order++);
        authors.add(author);
      }
      put(payload, allowed, "authors", authors);
      if (!aggregate.countries.isEmpty()) {
        put(payload, allowed, "sourceCategory", String.join("/", aggregate.countries));
      }
      put(payload, allowed, "publicationYear", yearOf(aggregate.publishedDate));
      records.add(
          new CollectedRecord(aggregate.number, payload, DataNature.REAL, null, null, fetchedAt));
    }
    return records;
  }

  /** 响应体按 UTF-8 解成文本；空体返回 null，交给异常侧决定不带尾巴。 */
  private static String textOf(byte[] body) {
    return body == null || body.length == 0 ? null : new String(body, StandardCharsets.UTF_8);
  }

  private static String value(JsonNode row, String field) {
    JsonNode node = row.path(field).path("value");
    if (node.isMissingNode() || node.isNull()) {
      return null;
    }
    String text = node.asText();
    return text == null || text.isBlank() ? null : text.trim();
  }

  /** 只取日期部分：Wikidata 的日期是 {@code 2018-12-04T00:00:00Z}，而 core 存的是 LocalDate。 */
  private static String datePart(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String trimmed = raw.trim();
    return trimmed.length() >= 10 ? trimmed.substring(0, 10) : trimmed;
  }

  private static Integer yearOf(String isoDate) {
    if (isoDate == null || isoDate.length() < 4) {
      return null;
    }
    try {
      return Integer.valueOf(isoDate.substring(0, 4));
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  /** 字段白名单：不在白名单里的字段不进入 payload，即使来源给了也不写。 */
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

  /** 一件专利的聚合容器。 */
  private static final class PatentAggregate {
    private String number;
    private String title;
    private String publishedDate;
    private String filingDate;
    private final Set<String> applicants = new LinkedHashSet<>();
    private final Set<String> inventors = new LinkedHashSet<>();
    private final Set<String> classificationCodes = new LinkedHashSet<>();
    private final Set<String> countries = new LinkedHashSet<>();
  }
}
