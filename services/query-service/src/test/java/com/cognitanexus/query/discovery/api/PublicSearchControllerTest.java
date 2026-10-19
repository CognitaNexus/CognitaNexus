package com.cognitanexus.query.discovery.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.query.discovery.application.SearchQuery;
import com.cognitanexus.query.discovery.application.SearchService;
import com.cognitanexus.query.platform.web.ApiResponse;
import com.cognitanexus.query.projection.application.ProjectionProperties;
import com.cognitanexus.sharedkernel.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

/**
 * 公开检索端点的参数归一与响应封装（2026-10-18 为 discovery 纳入覆盖率门禁而补）。
 *
 * <p>这一层真正承担的职责只有三件，但三件都容易写错且都不会报错：
 *
 * <ol>
 *   <li><b>空白参数必须变成 null</b>。把空串原样传给下层，会让 SQL 多一个
 *       {@code organization = ''} 的条件，结果静默变成 0 条；
 *   <li><b>类型取值要归一后校验</b>。{@code paper} 与 {@code PAPER} 是同一类成果，前者不该被拒；
 *       非法取值必须显式拒绝——返回空结果会被读成「确实没有这类成果」；
 *   <li><b>默认值来自配置而不是写死在注解里</b>。前端从 /options 读同一份配置，两边才不会漂移。
 * </ol>
 */
class PublicSearchControllerTest {

  private SearchService service;
  private PublicSearchController controller;

  @BeforeEach
  void setUp() {
    service = mock(SearchService.class);
    when(service.search(any())).thenReturn(new LinkedHashMap<>(Map.of("total", 0L)));
    when(service.searchScholars(any())).thenReturn(new LinkedHashMap<>(Map.of("total", 0L)));
    when(service.facets(any(), anyInt())).thenReturn(new LinkedHashMap<>());
    when(service.exportCsv(any())).thenReturn("id,title\r\n".getBytes(StandardCharsets.UTF_8));
    ProjectionProperties properties =
        new ProjectionProperties(
            "query", 500, 5, 200, true, 2000L, 20000, 60000, 800L, 200, 20, 100);
    controller = new PublicSearchController(service, properties);
  }

  private SearchQuery capturedQuery(Runnable call) {
    ArgumentCaptor<SearchQuery> captor = ArgumentCaptor.forClass(SearchQuery.class);
    call.run();
    verify(service).search(captor.capture());
    return captor.getValue();
  }

  // ---------------------------------------------------------------------------
  // /achievements
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("缺省每页条数取配置值，不是写死 20")
  void defaultPageSizeComesFromConfiguration() {
    SearchQuery query =
        capturedQuery(
            () ->
                controller.achievements(
                    null, null, null, null, null, null, null, null, 1, null, null));
    assertThat(query.size()).isEqualTo(20);
    assertThat(query.page()).isEqualTo(1);
  }

  @Test
  @DisplayName("空白筛选条件归一为 null，避免 SQL 多出 organization = '' 这类条件")
  void blankFiltersBecomeNull() {
    SearchQuery query =
        capturedQuery(
            () ->
                controller.achievements(
                    " graph ", "paper", null, null, "  ", "   ", " ", "", 1, 5, "  "));

    assertThat(query.keyword()).isEqualTo(" graph ");
    assertThat(query.keywordNorm()).isEqualTo("graph");
    assertThat(query.type()).isEqualTo("PAPER");
    assertThat(query.topicId()).isNull();
    assertThat(query.organization()).isNull();
    // dataNature 空白 → null 传下去，服务层再归一成 REAL（口径判断只在服务层一处）
    assertThat(query.dataNature()).isNull();
    assertThat(query.sort()).isNull();
    assertThat(query.queryVersion()).isNull();
  }

  @Test
  @DisplayName("成果类型大小写归一，取值不在白名单时拒绝而不是返回空结果")
  void normalizesTypeAndRejectsUnknownOne() {
    SearchQuery query =
        capturedQuery(
            () ->
                controller.achievements(
                    null, "Patent", null, null, null, null, null, null, 1, 20, null));
    assertThat(query.type()).isEqualTo("PATENT");

    // 上一步已经真实调用过一次，先清空调用记录再验证「非法取值不进服务层」。
    org.mockito.Mockito.clearInvocations(service);
    assertThatThrownBy(
            () ->
                controller.achievements(
                    null, "BOOK", null, null, null, null, null, null, 1, 20, null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("成果类型取值必须是 PAPER / PATENT / PROJECT / AWARD 之一");
    verify(service, never()).search(any());
  }

  @Test
  @DisplayName("年份与筛选条件原样传给服务层，不在 web 层做二次解释")
  void passesFiltersThrough() {
    SearchQuery query =
        capturedQuery(
            () ->
                controller.achievements(
                    "graph",
                    "PAPER",
                    2020,
                    2024,
                    "top_1",
                    "北京航空航天大学",
                    "ALL",
                    "year_desc",
                    2,
                    50,
                    "q1-r1"));

    assertThat(query.yearFrom()).isEqualTo(2020);
    assertThat(query.yearTo()).isEqualTo(2024);
    assertThat(query.topicId()).isEqualTo("top_1");
    assertThat(query.organization()).isEqualTo("北京航空航天大学");
    assertThat(query.dataNature()).isEqualTo("ALL");
    assertThat(query.sort()).isEqualTo("year_desc");
    assertThat(query.page()).isEqualTo(2);
    assertThat(query.size()).isEqualTo(50);
    assertThat(query.queryVersion()).isEqualTo("q1-r1");
  }

  // ---------------------------------------------------------------------------
  // /achievements/export
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("导出返回文件本体而非信封：带附件头与 UTF-8 的 text/csv")
  void exportReturnsFileBodyWithHeaders() {
    ResponseEntity<byte[]> response =
        controller.exportAchievements("graph", null, null, null, null, null, null, null);

    assertThat(response.getHeaders().getFirst("Content-Disposition"))
        .isEqualTo("attachment; filename=achievements-export.csv");
    assertThat(response.getHeaders().getFirst("Content-Type")).isEqualTo("text/csv; charset=UTF-8");
    assertThat(response.getBody()).isEqualTo("id,title\r\n".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("导出不套分页参数：page/size 固定为 1/1，避免把交互页大小带进 CSV")
  void exportIgnoresPaging() {
    ArgumentCaptor<SearchQuery> captor = ArgumentCaptor.forClass(SearchQuery.class);
    controller.exportAchievements("graph", "PAPER", null, null, null, null, null, null);
    verify(service).exportCsv(captor.capture());

    assertThat(captor.getValue().page()).isEqualTo(1);
    assertThat(captor.getValue().size()).isEqualTo(1);
    assertThat(captor.getValue().queryVersion()).isNull();
  }

  // ---------------------------------------------------------------------------
  // /scholars、/facets、/options
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("学者端点不接收类型与年份条件，空白参数同样归一为 null")
  void scholarsIgnoreAchievementOnlyFilters() {
    ArgumentCaptor<SearchQuery> captor = ArgumentCaptor.forClass(SearchQuery.class);
    controller.scholars(" wang ", " ", "", 3, null, null);
    verify(service).searchScholars(captor.capture());

    SearchQuery query = captor.getValue();
    assertThat(query.keywordNorm()).isEqualTo("wang");
    assertThat(query.type()).isNull();
    assertThat(query.yearFrom()).isNull();
    assertThat(query.dataNature()).isNull();
    assertThat(query.sort()).isNull();
    assertThat(query.page()).isEqualTo(3);
    assertThat(query.size()).isEqualTo(20);
  }

  @Test
  @DisplayName("分面端点把 limit 原样交给服务层钳制，不在 web 层各写一份边界")
  void facetsDelegateLimitClamping() {
    controller.facets(null, 100);
    verify(service).facets(null, 100);

    controller.facets("ALL", 0);
    verify(service).facets("ALL", 0);
  }

  @SuppressWarnings("unchecked")
  private static java.util.List<String> stringsOf(Map<String, Object> data, String key) {
    return (java.util.List<String>) data.get(key);
  }

  @Test
  @DisplayName("选项端点给出与配置一致的默认页大小与上限，前端不必硬编码")
  void optionsMirrorConfiguration() {
    ApiResponse<Map<String, Object>> response = controller.options().getBody();

    assertThat(response).isNotNull();
    Map<String, Object> data = response.data();
    assertThat(data).containsEntry("maxPageSize", 100).containsEntry("defaultPageSize", 20);
    assertThat(data).containsEntry("maxOffset", 200).containsEntry("ruleVersion", "SEARCH_V1");
    assertThat(stringsOf(data, "sorts")).contains("relevance", "citations_desc");
    assertThat(stringsOf(data, "dataNatures")).contains("REAL", "ALL");
    assertThat((String) data.get("note")).contains("ALL").contains("REAL");
  }

  @Test
  @DisplayName("建议端点：筛选类空白参数归一为 null，关键词原样交给服务层归一")
  void suggestNormalizesBlankArguments() {
    when(service.suggest(any(), any(), anyInt())).thenReturn(new LinkedHashMap<>());

    controller.suggest("   ", "  ", 6);

    // 关键词不走这里的 blankToNull：它与成果检索共用 SearchQuery.normalizeKeyword 那条路径，
    // 在控制器层再归一一次会出现两套规则，早晚会分叉。口径参数不同，它只在这一层出现。
    verify(service)
        .suggest(
            org.mockito.ArgumentMatchers.eq("   "),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq(6));
  }

  @Test
  @DisplayName("建议端点：口径与条数原样透传，不在控制器层截断")
  void suggestPassesLimitThrough() {
    when(service.suggest(any(), any(), anyInt())).thenReturn(new LinkedHashMap<>());

    controller.suggest("graph", "ALL", 10);

    verify(service)
        .suggest(
            org.mockito.ArgumentMatchers.eq("graph"),
            org.mockito.ArgumentMatchers.eq("ALL"),
            org.mockito.ArgumentMatchers.eq(10));
  }
}
