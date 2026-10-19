package com.cognitanexus.query.discovery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.query.consistency.PublicBarrierGuard;
import com.cognitanexus.query.discovery.infrastructure.SearchMapper;
import com.cognitanexus.query.projection.application.ProjectionProperties;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 检索用例的服务层断言（2026-10-18 为 discovery 纳入覆盖率门禁而补）。
 *
 * <p>为什么这些用例不能只靠 HTTP 自检：`tests/api/smoke-query.sh` 在真实库上跑，能证明链路通，
 * 但库里没有的数据形状它造不出来——例如一条成果同时带三种诚信声明、或者分面 limit 传入越界值。
 * 而这些分支恰恰是「错了也不报错」的那一类。
 *
 * <p>覆盖的四组判据：
 *
 * <ol>
 *   <li><b>参数拒绝要可解释</b>：页码、每页条数、深翻页、排序白名单、查询版本，拒绝时给出的是
 *       「为什么」与「怎么办」，不是一句「参数错误」；
 *   <li><b>口径必须回显生效值</b>：缺省的范围是 REAL 而不是「不限制」，演示口径必须由调用方显式要求；
 *   <li><b>缺失字段要显式填充</b>：三个服务都按 non_null 序列化，诚信标记若不补齐三字段，
 *       前端拿到 undefined 会渲染成空白（历史上静默卡死过）；
 *   <li><b>空结果不要多发一次查询</b>：没有命中时不该再去取作者与领域。
 * </ol>
 */
class SearchServiceSearchTest {

  private static final long REVISION = 100L;
  private static final String GENERATION = "gen0008";

  private SearchMapper mapper;
  private SearchService service;
  private ProjectionProperties properties;

  @BeforeEach
  void setUp() {
    mapper = mock(SearchMapper.class);
    PublicBarrierGuard guard = mock(PublicBarrierGuard.class);
    when(guard.assertPubliclyServed())
        .thenReturn(
            new PublicBarrierGuard.BarrierSnapshot(
                REVISION, REVISION, REVISION, REVISION, GENERATION));
    // searchMaxOffset=200、defaultPageSize=20、maxPageSize=100：三条边界都用得到，取值写在这里便于对账。
    properties =
        new ProjectionProperties(
            "query", 500, 5, 200, true, 2000L, 20000, 60000, 800L, 200, 20, 100);
    service = new SearchService(mapper, guard, properties);
  }

  private static SearchQuery query(
      String dataNature, String sort, int page, int size, String version) {
    return new SearchQuery(
        "graph", "graph", "PAPER", 2020, 2024, null, null, dataNature, sort, page, size, version);
  }

  private static SearchQuery plainQuery() {
    return query("REAL", null, 1, 20, null);
  }

  private static SearchMapper.AchievementHit hit(String id, String integrity) {
    return new SearchMapper.AchievementHit(
        id,
        "PAPER",
        "图学习综述",
        "图学习综述",
        "REAL",
        "PUBLIC",
        "EFFECTIVE",
        integrity,
        2023,
        "10.1000/x",
        "软件学报",
        12L,
        "CROSSREF",
        null,
        0.87);
  }

  private void stubEmpty() {
    when(mapper.countHits(anyString(), any())).thenReturn(0L);
    when(mapper.listHits(anyString(), any(), anyInt(), anyInt())).thenReturn(List.of());
  }

  // ---------------------------------------------------------------------------
  // 结果组装
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("结果条目字段齐全，作者与领域按成果分组挂在各自条目上")
  void itemsCarryAuthorsAndTopicsBelongingToEachAchievement() {
    when(mapper.countHits(eq(GENERATION), any())).thenReturn(3L);
    when(mapper.listHits(eq(GENERATION), any(), eq(20), eq(0)))
        .thenReturn(
            List.of(hit("ach_1", "NONE"), hit("ach_2", "RETRACTION"), hit("ach_3", "NONE")));
    when(mapper.listAuthorNames(eq(GENERATION), anyList()))
        .thenReturn(
            List.of(
                new SearchMapper.NameRow("ach_1", "王世平"),
                new SearchMapper.NameRow("ach_1", "李娜"),
                new SearchMapper.NameRow("ach_2", "张伟")));
    when(mapper.listTopics(eq(GENERATION), anyList()))
        .thenReturn(List.of(new SearchMapper.TopicRow("ach_1", "top_1", "图学习", "SOURCE", 0.9)));

    Map<String, Object> data = service.search(plainQuery());

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
    assertThat(items).hasSize(3);
    Map<String, Object> first = items.get(0);
    assertThat(first)
        .containsKeys(
            "id",
            "type",
            "title",
            "dataNature",
            "visibility",
            "effectiveStatus",
            "integrity",
            "publicationYear",
            "doi",
            "venue",
            "citationCount",
            "citationSource",
            "authors",
            "topics",
            "relevance");
    assertThat(first.get("authors")).isEqualTo(List.of("王世平", "李娜"));
    // 领域必须带上「来源给的还是系统推断的」，否则列表页无从区分，只能点进去才知道。
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> topics = (List<Map<String, Object>>) first.get("topics");
    assertThat(topics).hasSize(1);
    assertThat(topics.get(0))
        .containsEntry("topicId", "top_1")
        .containsEntry("assignmentKind", "SOURCE");
    assertThat(topics.get(0)).containsEntry("confidence", 0.9);
    // 第二条既无作者也无领域：必须是空列表而不是缺字段，前端拿不到键会渲染成空白。
    Map<String, Object> second = items.get(1);
    assertThat(second.get("authors")).isEqualTo(List.of("张伟"));
    assertThat((List<?>) second.get("topics")).isEmpty();
    // 第三条既无作者也无领域：必须是空列表而不是缺字段，前端拿不到键会渲染成空白。
    Map<String, Object> third = items.get(2);
    assertThat(third).containsKey("authors").containsKey("topics");
    assertThat((List<?>) third.get("authors")).isEmpty();
    assertThat((List<?>) third.get("topics")).isEmpty();
  }

  @Test
  @DisplayName("诚信标记四种取值都给齐 status/label/noticed，不随有无声明改变字段集")
  void integrityViewAlwaysCarriesThreeFields() {
    when(mapper.countHits(anyString(), any())).thenReturn(4L);
    when(mapper.listHits(anyString(), any(), anyInt(), anyInt()))
        .thenReturn(
            List.of(
                hit("ach_1", null),
                hit("ach_2", "RETRACTION"),
                hit("ach_3", "CORRECTION"),
                hit("ach_4", "CONCERN")));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items =
        (List<Map<String, Object>>) service.search(plainQuery()).get("items");

    assertThat(integrityOf(items.get(0)))
        .containsEntry("status", "NONE")
        .containsEntry("label", "无诚信声明")
        .containsEntry("noticed", false);
    assertThat(integrityOf(items.get(1)))
        .containsEntry("status", "RETRACTION")
        .containsEntry("label", "已撤稿")
        .containsEntry("noticed", true);
    assertThat(integrityOf(items.get(2))).containsEntry("label", "已更正");
    assertThat(integrityOf(items.get(3))).containsEntry("label", "有关注声明");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> integrityOf(Map<String, Object> item) {
    return (Map<String, Object>) item.get("integrity");
  }

  @Test
  @DisplayName("空结果不再去取作者与领域，且总数与条目数一致")
  void emptyResultSkipsAuthorAndTopicQueries() {
    stubEmpty();

    Map<String, Object> data = service.search(plainQuery());

    assertThat(data).containsEntry("total", 0L).containsEntry("totalRelation", "exact");
    assertThat((List<?>) data.get("items")).isEmpty();
    verify(mapper, never()).listAuthorNames(anyString(), anyList());
    verify(mapper, never()).listTopics(anyString(), anyList());
  }

  // ---------------------------------------------------------------------------
  // 参数拒绝
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("页码小于 1 时拒绝并说明从 1 开始")
  void rejectsPageBelowOne() {
    assertThatThrownBy(() -> service.search(query("REAL", null, 0, 20, null)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("页码必须从 1 开始");
    verify(mapper, never()).countHits(anyString(), any());
  }

  @Test
  @DisplayName("每页条数越界时拒绝，消息给出上限值")
  void rejectsSizeOutOfRange() {
    assertThatThrownBy(() -> service.search(query("REAL", null, 1, 0, null)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("每页条数必须在 1 到 100 之间");
    assertThatThrownBy(() -> service.search(query("REAL", null, 1, 101, null)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("每页条数必须在 1 到 100 之间");
  }

  @Test
  @DisplayName("深翻页超过上限时拒绝，并给出缩小范围的具体做法")
  void rejectsDeepOffsetWithActionableHint() {
    // offset = (page-1)*size；page=3、size=100 → 200，等于上限可通过；page=4 → 300，超过。
    service.search(query("REAL", null, 3, 100, null));
    assertThatThrownBy(() -> service.search(query("REAL", null, 4, 100, null)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("偏移上限为 200 条")
        .hasMessageContaining("缩小检索范围");
  }

  @Test
  @DisplayName("排序不在白名单内时拒绝，并列出允许取值而不是静默回落")
  void rejectsUnknownSort() {
    assertThatThrownBy(() -> service.search(query("REAL", "citation_desc", 1, 20, null)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("排序字段必须是以下之一")
        .hasMessageContaining("citations_desc");
  }

  @Test
  @DisplayName("查询版本不一致时以 QUERY_VERSION_CHANGED 拒绝，不做任何列表查询")
  void rejectsStaleQueryVersion() {
    SearchQuery stale = query("REAL", null, 2, 20, "qdeadbeef-r99");

    assertThatThrownBy(() -> service.search(stale))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("查询版本已变化")
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.QUERY_VERSION_CHANGED);
    verify(mapper, never()).listHits(anyString(), any(), anyInt(), anyInt());
  }

  @Test
  @DisplayName("版本一致或未携带（首请求）都放行，回显的版本与校验用的是同一个值")
  void acceptsMatchingOrAbsentVersion() {
    stubEmpty();
    String expected = plainQuery().versionFor(REVISION);

    assertThat(service.search(query("REAL", null, 1, 20, expected)))
        .containsEntry("queryVersion", expected);
    assertThat(service.search(query("REAL", null, 1, 20, "   ")))
        .containsEntry("queryVersion", expected);
  }

  @Test
  @DisplayName("非法数据性质取值被拒绝，不会当成「不限」放过去")
  void rejectsUnknownDataNature() {
    assertThatThrownBy(() -> service.search(query("REEL", null, 1, 20, null)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("数据性质取值必须是");
  }

  // ---------------------------------------------------------------------------
  // 口径回显
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("缺省口径回显 REAL，显式 ALL 才放宽为演示口径")
  void echoesEffectiveNatureScope() {
    stubEmpty();

    Map<String, Object> defaulted = service.search(plainQuery());
    assertThat(defaulted).containsEntry("dataNatureScope", "REAL");
    assertThat((String) defaulted.get("scopeNote")).contains("仅含真实数据");

    Map<String, Object> demo = service.search(query("ALL", null, 1, 20, null));
    assertThat(demo).containsEntry("dataNatureScope", "ALL");
    assertThat((String) demo.get("scopeNote")).contains("包含全部数据性质");
  }

  @Test
  @DisplayName("未指定排序时回显 relevance，且与 criteria 里传给 SQL 的值一致")
  void echoesAppliedSortAndPassesSameCriteriaDown() {
    stubEmpty();
    ArgumentCaptor<SearchCriteria> captor = ArgumentCaptor.forClass(SearchCriteria.class);

    Map<String, Object> data = service.search(plainQuery());
    verify(mapper).countHits(eq(GENERATION), captor.capture());

    assertThat(data).containsEntry("appliedSort", "relevance");
    SearchCriteria criteria = captor.getValue();
    // 相似度用归一化关键词、LIKE 用转义后的模式：两者不能混用，混用会让 % 变成通配。
    assertThat(criteria.keywordNorm()).isEqualTo("graph");
    assertThat(criteria.keywordLike()).isEqualTo("graph");
    assertThat(criteria.type()).isEqualTo("PAPER");
    assertThat(criteria.dataNature()).isEqualTo("REAL");
  }

  // ---------------------------------------------------------------------------
  // 学者检索
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("学者检索给出计入口径说明与查询版本")
  void scholarSearchExplainsCountingRule() {
    when(mapper.countScholars(anyString(), any())).thenReturn(1L);
    when(mapper.searchScholars(eq(GENERATION), any(), eq(20), eq(0)))
        .thenReturn(List.of(new SearchMapper.ScholarHit("sch_1", "王世平", 7L, 2015)));

    Map<String, Object> data = service.searchScholars(plainQuery());

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
    assertThat(items).hasSize(1);
    assertThat(items.get(0))
        .containsEntry("scholarId", "sch_1")
        .containsEntry("qualifiedAchievementCount", 7L)
        .containsEntry("firstYear", 2015);
    assertThat((String) data.get("countingNote")).contains("AUTO").contains("SELF_CONFIRMED");
    assertThat(data).containsEntry("queryVersion", plainQuery().versionFor(REVISION));
  }

  @Test
  @DisplayName("学者检索收到不生效的排序键时拒绝，而不是当没传")
  void scholarSearchRejectsUnsupportedSort() {
    assertThatThrownBy(() -> service.searchScholars(query("REAL", "year_desc", 1, 20, null)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("achievements_desc");
  }

  @Test
  @DisplayName("学者检索对过期版本与成果检索采取同一策略")
  void scholarSearchRejectsStaleVersionToo() {
    assertThatThrownBy(() -> service.searchScholars(query("REAL", null, 2, 20, "qdeadbeef-r99")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("查询版本已变化");
    verify(mapper, never()).searchScholars(anyString(), any(), anyInt(), anyInt());
  }

  // ---------------------------------------------------------------------------
  // 分面
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("分面把来源与推断分开计数，limit 越界被钳到 1..500")
  void facetsSplitCountsAndClampLimit() {
    when(mapper.listTopicFacets(eq(GENERATION), eq("REAL"), eq(1)))
        .thenReturn(List.of(new SearchMapper.TopicFacet("top_1", "图学习", 9L, 6L, 3L)));
    Map<String, Object> orgRow = new LinkedHashMap<>();
    orgRow.put("name", "北京航空航天大学");
    orgRow.put("achievementCount", 42);
    when(mapper.listOrganizationFacets(eq(GENERATION), eq("REAL"), eq(1)))
        .thenReturn(List.of(orgRow));

    Map<String, Object> data = service.facets(null, 0);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> topics = (List<Map<String, Object>>) data.get("topics");
    assertThat(topics.get(0))
        .containsEntry("achievementCount", 9L)
        .containsEntry("sourceProvided", 6L)
        .containsEntry("systemInferred", 3L);
    assertThat((List<?>) data.get("organizations")).hasSize(1);
    assertThat(data).containsEntry("dataNatureScope", "REAL");

    // 上限 500：传入 9999 时按 500 查，避免把全部分面拉进响应。
    when(mapper.listTopicFacets(eq(GENERATION), eq("REAL"), eq(500))).thenReturn(List.of());
    when(mapper.listOrganizationFacets(eq(GENERATION), eq("REAL"), eq(500))).thenReturn(List.of());
    service.facets("REAL", 9999);
    verify(mapper).listTopicFacets(GENERATION, "REAL", 500);
  }

  @Test
  @DisplayName("分面也遵守同一条口径规则：显式 ALL 才不限性质")
  void facetsRespectDataNatureScope() {
    when(mapper.listTopicFacets(eq(GENERATION), eq((String) null), anyInt())).thenReturn(List.of());
    when(mapper.listOrganizationFacets(eq(GENERATION), eq((String) null), anyInt()))
        .thenReturn(List.of());

    Map<String, Object> data = service.facets("ALL", 100);

    assertThat(data).containsEntry("dataNatureScope", "ALL");
    assertThat((String) data.get("scopeNote")).contains("仅适用于演示");
  }
}
