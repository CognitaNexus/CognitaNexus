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
