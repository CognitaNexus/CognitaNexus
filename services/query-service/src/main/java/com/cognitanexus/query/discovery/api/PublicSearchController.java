package com.cognitanexus.query.discovery.api;

import com.cognitanexus.query.discovery.application.SearchQuery;
import com.cognitanexus.query.discovery.application.SearchService;
import com.cognitanexus.query.platform.web.ApiResponse;
import com.cognitanexus.query.platform.web.TraceIdHolder;
import com.cognitanexus.query.projection.application.ProjectionProperties;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开检索端点。
 *
 * <p>依据：架构 V0.2 §10.1：{@code /api/v1/public/search/*} 归 query 所有，无需登录，由服务端强制
 * 公开资格与版本屏障。匿名可读不等于无需保护：屏障校验、分页上限与排序白名单都是服务端强制，
 * 不依赖前端传参正确。
 */
@RestController
@RequestMapping("/api/v1/public/search")
public class PublicSearchController {

  private static final Logger log = LoggerFactory.getLogger(PublicSearchController.class);

  private final SearchService searchService;
  private final ProjectionProperties properties;

  public PublicSearchController(SearchService searchService, ProjectionProperties properties) {
    this.searchService = searchService;
    this.properties = properties;
  }

  @GetMapping("/achievements")
  public ResponseEntity<ApiResponse<Map<String, Object>>> achievements(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String type,
      @RequestParam(required = false) Integer yearFrom,
      @RequestParam(required = false) Integer yearTo,
      @RequestParam(required = false) String topic,
      @RequestParam(required = false) String organization,
      @RequestParam(required = false) String dataNature,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String queryVersion) {
    SearchQuery query =
        new SearchQuery(
            q,
            SearchQuery.normalizeKeyword(q),
            normalizeType(type),
            yearFrom,
            yearTo,
            blankToNull(topic),
            blankToNull(organization),
            blankToNull(dataNature),
            blankToNull(sort),
            page,
            size == null ? properties.searchDefaultPageSize() : size,
            blankToNull(queryVersion));
    Map<String, Object> data = searchService.search(query);
    log.debug("公开检索 page={} size={} total={}", page, query.size(), data.get("total"));
    return ResponseEntity.ok(ApiResponse.ok(data, TraceIdHolder.current()));
  }

  @GetMapping("/scholars")
  public ResponseEntity<ApiResponse<Map<String, Object>>> scholars(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String dataNature,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String queryVersion) {
    SearchQuery query =
        new SearchQuery(
            q,
            SearchQuery.normalizeKeyword(q),
            null,
            null,
            null,
            null,
            null,
            blankToNull(dataNature),
            blankToNull(sort),
            page,
            size == null ? properties.searchDefaultPageSize() : size,
            blankToNull(queryVersion));
    return ResponseEntity.ok(
        ApiResponse.ok(searchService.searchScholars(query), TraceIdHolder.current()));
  }

  /** 筛选候选。前端用它渲染领域与机构的可选值，避免前端硬编码取值。 */
  @GetMapping("/facets")
  public ResponseEntity<ApiResponse<Map<String, Object>>> facets(
      @RequestParam(required = false) String dataNature,
      @RequestParam(defaultValue = "100") int limit) {
    return ResponseEntity.ok(
        ApiResponse.ok(searchService.facets(dataNature, limit), TraceIdHolder.current()));
  }

  /** 排序与数据性质的可选值，由服务端给出，避免前端与后端各自维护一份白名单。 */
  @GetMapping("/options")
  public ResponseEntity<ApiResponse<Map<String, Object>>> options() {
    Map<String, Object> data = new java.util.LinkedHashMap<>();
    data.put("sorts", SearchQuery.allowedSorts());
    data.put("dataNatures", SearchQuery.allowedNatures());
    data.put("maxPageSize", properties.searchMaxPageSize());
    data.put("defaultPageSize", properties.searchDefaultPageSize());
    data.put("maxOffset", properties.searchMaxOffset());
    data.put("ruleVersion", SearchService.RULE_VERSION);
    data.put("note", "ALL 表示不限数据性质（演示口径）；默认 REAL 只含真实数据。交互式深翻页上限为 maxOffset 条。");
    return ResponseEntity.ok(ApiResponse.ok(data, TraceIdHolder.current()));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /**
   * 成果类型归一。
   *
   * <p>取值不在白名单内时直接拒绝而不是返回空结果：返回空结果会让用户以为「确实没有这类成果」，
   * 而真实原因是参数写错了。
   */
  private static String normalizeType(String type) {
    String value = blankToNull(type);
    if (value == null) {
      return null;
    }
    String upper = value.toUpperCase(java.util.Locale.ROOT);
    if (!java.util.List.of("PAPER", "PATENT", "PROJECT", "AWARD").contains(upper)) {
      throw com.cognitanexus.sharedkernel.BusinessException.invalidArgument(
          "成果类型取值必须是 PAPER / PATENT / PROJECT / AWARD 之一，实际为 " + type);
    }
    return upper;
  }
}
