package com.cognitanexus.query.analytics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.query.analytics.application.AnalyticsService;
import com.cognitanexus.query.platform.web.ApiResponse;
import com.cognitanexus.query.platform.web.TraceIdHolder;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * 公开统计端点的契约。
 *
 * <p>依据：架构 V0.2 §10.1（{@code /api/v1/public/analytics/*} 归 query 且无需登录）、§10.3
 * （统一响应包与链路标识）。用例只校验端点这一层的职责：把默认口径恰好传下去一次、把服务结果原样
 * 放进统一响应包、把链路标识带出来。口径规则本身由 {@code AnalyticsServiceTest} 负责。
 */
class PublicAnalyticsControllerTest {

  private final AnalyticsService service = mock(AnalyticsService.class);
  private final PublicAnalyticsController controller = new PublicAnalyticsController(service);

  @AfterEach
  void clearTraceId() {
    TraceIdHolder.clear();
  }

  @Test
  @DisplayName("默认口径为 REAL，调用方不传参数时服务也必须收到 REAL 而不是 null")
  void defaultDataNatureIsReal() {
    when(service.overview("REAL")).thenReturn(Map.of("ruleVersion", AnalyticsService.RULE_VERSION));

    ResponseEntity<ApiResponse<Map<String, Object>>> response = controller.overview("REAL");

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    ApiResponse<Map<String, Object>> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.code()).isEqualTo("OK");
    assertThat(body.data()).containsEntry("ruleVersion", AnalyticsService.RULE_VERSION);
    assertThat(body.meta()).isNotNull();
    verify(service).overview("REAL");
  }

  @Test
  @DisplayName("链路标识沿用当前请求，缺失时为空串而不是 null")
  void traceIdEchoed() {
    when(service.overview("ALL")).thenReturn(Map.of());

    assertThat(controller.overview("ALL").getBody().traceId()).isEmpty();

    TraceIdHolder.set("trace-abc123");
    assertThat(controller.overview("ALL").getBody().traceId()).isEqualTo("trace-abc123");
    verify(service, times(2)).overview("ALL");
  }

  @Test
  @DisplayName("领域洞察：路径里的领域标识原样下传，结果放进统一响应包")
  void topicInsightDelegatesWithPathId() {
    when(service.topicInsight("top_ai", "REAL"))
        .thenReturn(Map.of("ruleVersion", AnalyticsService.TOPIC_RULE_VERSION));

    ResponseEntity<ApiResponse<Map<String, Object>>> response = controller.topic("top_ai", "REAL");

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("OK");
    assertThat(response.getBody().data())
        .containsEntry("ruleVersion", AnalyticsService.TOPIC_RULE_VERSION);
    verify(service).topicInsight("top_ai", "REAL");
  }

  @Test
  @DisplayName("领域洞察：默认口径同样回传 REAL 而不是 null")
  void topicInsightDefaultsToReal() {
    when(service.topicInsight("top_ai", "REAL")).thenReturn(Map.of());

    controller.topic("top_ai", "REAL");

    verify(service).topicInsight("top_ai", "REAL");
  }

  @Test
  @DisplayName("关键词热点：年份窗与条数原样下传，结果放进统一响应包")
  void keywordHotspotsDelegatesWindow() {
    when(service.keywordHotspots("REAL", 2020, 2024, 20))
        .thenReturn(Map.of("ruleVersion", AnalyticsService.HOTSPOT_RULE_VERSION));

    ResponseEntity<ApiResponse<Map<String, Object>>> response =
        controller.keywords("REAL", 2020, 2024, 20);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("OK");
    assertThat(response.getBody().data())
        .containsEntry("ruleVersion", AnalyticsService.HOTSPOT_RULE_VERSION);
    verify(service).keywordHotspots("REAL", 2020, 2024, 20);
  }

  @Test
  @DisplayName("关键词热点：年份窗用 null 表达「不限」，不发明 all 之类的字符串协议")
  void keywordHotspotsAcceptsOpenWindow() {
    when(service.keywordHotspots("REAL", null, null, 20)).thenReturn(Map.of());

    controller.keywords("REAL", null, null, 20);

    // 传 null 就下传 null：接口层不得把「不限」翻译成某个魔法字符串。
    verify(service).keywordHotspots("REAL", null, null, 20);
  }
}
