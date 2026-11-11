package com.cognitanexus.query.analytics.api;

import com.cognitanexus.query.analytics.application.AnalyticsService;
import com.cognitanexus.query.platform.web.ApiResponse;
import com.cognitanexus.query.platform.web.TraceIdHolder;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开统计端点。
 *
 * <p>依据：架构 V0.2 §10.1，{@code /api/v1/public/analytics/*} 归 query 所有，无需登录。
 * 服务端强制公开资格与版本屏障，并且默认口径只含真实数据——不让「不带参数」变成「什么数据都算」。
 */
@RestController
@RequestMapping("/api/v1/public/analytics")
public class PublicAnalyticsController {

  private final AnalyticsService analyticsService;

  public PublicAnalyticsController(AnalyticsService analyticsService) {
    this.analyticsService = analyticsService;
  }

  @GetMapping("/overview")
  public ResponseEntity<ApiResponse<Map<String, Object>>> overview(
      @RequestParam(required = false, defaultValue = "REAL") String dataNature) {
    return ResponseEntity.ok(
        ApiResponse.ok(analyticsService.overview(dataNature), TraceIdHolder.current()));
  }

  /**
   * 机构清单：可搜索、可翻页。
   *
   * <p>机构名放查询参数（{@code /organizations/detail?organization=...}）而不是路径段：机构名里有
   * 斜杠、逗号与撇号，进路径会被网关与框架各解码一次，同一个名字可能匹配不到自己的成果。
   */
  @GetMapping("/organizations")
  public ResponseEntity<ApiResponse<Map<String, Object>>> organizations(
      @RequestParam(required = false, defaultValue = "REAL") String dataNature,
      @RequestParam(required = false) String q,
      @RequestParam(required = false, defaultValue = "1") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            analyticsService.organizations(dataNature, q, page, size), TraceIdHolder.current()));
  }

  /** 单个机构的成果清单；机构名必须与机构清单返回值逐字一致。 */
  @GetMapping("/organizations/detail")
  public ResponseEntity<ApiResponse<Map<String, Object>>> organization(
      @RequestParam String organization,
      @RequestParam(required = false, defaultValue = "REAL") String dataNature,
      @RequestParam(required = false, defaultValue = "1") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            analyticsService.organization(organization, dataNature, page, size),
            TraceIdHolder.current()));
  }

  /**
   * 关键词热点（FR13）。
   *
   * <p>年份窗用两个可空参数表达「不限」，而不是用一个字符串解析：空值就是空值，
   * 前端不需要约定「"all" 或空串表示不限」这类隐形协议。
   *
   * <p>limit 有上限（服务层再夹到 50）：热点榜的意义是「看一眼最活跃的几个」，
   * 允许拉全量会让这个端点变成另一种导出接口。
   */
  @GetMapping("/keywords")
  public ResponseEntity<ApiResponse<Map<String, Object>>> keywords(
      @RequestParam(required = false, defaultValue = "REAL") String dataNature,
      @RequestParam(required = false) Integer yearFrom,
      @RequestParam(required = false) Integer yearTo,
      @RequestParam(required = false, defaultValue = "20") int limit) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            analyticsService.keywordHotspots(dataNature, yearFrom, yearTo, limit),
            TraceIdHolder.current()));
  }

  /**
   * 领域洞察。
   *
   * <p>领域标识放在路径里而不是查询参数：它标识的是「这一页讲的是哪个领域」，属于资源身份；
   * 数据性质是口径，属于内容筛选。混在查询参数里会让缓存与分享链接难以表达「同一领域的不同口径」。
   */
  @GetMapping("/topics/{topicId}")
  public ResponseEntity<ApiResponse<Map<String, Object>>> topic(
      @PathVariable String topicId,
      @RequestParam(required = false, defaultValue = "REAL") String dataNature) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            analyticsService.topicInsight(topicId, dataNature), TraceIdHolder.current()));
  }
}
