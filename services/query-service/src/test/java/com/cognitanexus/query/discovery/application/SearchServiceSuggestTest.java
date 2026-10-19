package com.cognitanexus.query.discovery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.query.consistency.PublicBarrierGuard;
import com.cognitanexus.query.discovery.infrastructure.SearchMapper;
import com.cognitanexus.query.projection.application.ProjectionProperties;
import com.cognitanexus.sharedkernel.BusinessException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 检索框建议的服务层断言。
 *
 * <p>建议看起来只是「打字提示」，但它是检索入口的一部分，因此三条判据与正式检索完全一致：
 *
 * <ol>
 *   <li><b>共用公开资格屏障</b>：建议若能绕过屏障，未公开或已撤下成果的标题会从输入框下方漏出来；
 *   <li><b>口径回显生效值</b>：缺省是 REAL，只有显式 ALL 才放宽，且响应里必须写出实际口径；
 *   <li><b>参数越界要拒绝</b>：建议条数 0 或超过上限时直接拒绝，不静默截断——静默截断会让调用方
 *       以为「就这么多」。
 * </ol>
 *
 * <p>这里不验证 SQL 里的资格条件与关系状态：那两条写在注解 SQL 里，只有真实库能证明，
 * 由 {@code tests/api/smoke-query.sh} 覆盖。
 */
class SearchServiceSuggestTest {

  private static final long REVISION = 100L;
  private static final String GENERATION = "gen0008";

  private SearchMapper mapper;
  private SearchService service;

  @BeforeEach
  void setUp() {
    mapper = mock(SearchMapper.class);
    PublicBarrierGuard guard = mock(PublicBarrierGuard.class);
    when(guard.assertPubliclyServed())
        .thenReturn(
            new PublicBarrierGuard.BarrierSnapshot(
                REVISION, REVISION, REVISION, REVISION, GENERATION));
    ProjectionProperties properties =
        new ProjectionProperties(
            "query", 500, 5, 200, true, 2000L, 20000, 60000, 800L, 200, 20, 100);
    service = new SearchService(mapper, guard, properties);
  }

  @Test
  @DisplayName("空关键词返回两组空结果，且不查询映射器")
  void blankKeywordDoesNotQuery() {
    Map<String, Object> data = service.suggest("   ", null, 6);

    assertThat((List<?>) data.get("achievements")).isEmpty();
    assertThat((List<?>) data.get("scholars")).isEmpty();
    assertThat(data.get("keyword")).isNull();
    assertThat(data.get("note")).asString().contains("关键词为空");
    verify(mapper, never()).suggestTitles(anyString(), anyString(), anyString(), any(), anyInt());
    verify(mapper, never()).suggestScholars(anyString(), anyString(), any(), anyInt());
  }

  @Test
  @DisplayName("题名与学者建议分成两组返回，字段与图谱一一对应")
  void mapsBothGroups() {
    when(mapper.suggestTitles(eq(GENERATION), eq("graph"), anyString(), eq("REAL"), eq(6)))
        .thenReturn(
            List.of(
                new SearchMapper.TitleSuggestion("ach_1", "图神经网络的鲁棒性", "PAPER", 2021),
                new SearchMapper.TitleSuggestion("ach_2", "图数据管理", "PROJECT", null)));
    when(mapper.suggestScholars(eq(GENERATION), anyString(), eq("REAL"), eq(6)))
        .thenReturn(List.of(new SearchMapper.ScholarSuggestion("sch_1", "张三", 17)));

    Map<String, Object> data = service.suggest("Graph", null, 6);

    assertThat(data.get("keyword")).isEqualTo("graph");
    List<Map<String, Object>> achievements = listOf(data, "achievements");
    assertThat(achievements).hasSize(2);
    assertThat(achievements.get(0))
        .containsEntry("id", "ach_1")
        .containsEntry("title", "图神经网络的鲁棒性")
        .containsEntry("type", "PAPER")
        .containsEntry("publicationYear", 2021);
    // 年份缺失要显式为 null，不能省掉这个键：前端按「年份未知」渲染，靠的是键存在而值为空。
    assertThat(achievements.get(1)).containsKey("publicationYear");
    assertThat(achievements.get(1).get("publicationYear")).isNull();

    List<Map<String, Object>> scholars = listOf(data, "scholars");
    assertThat(scholars).hasSize(1);
    assertThat(scholars.get(0))
        .containsEntry("scholarId", "sch_1")
        .containsEntry("scholarName", "张三")
        .containsEntry("qualifiedAchievementCount", 17L);
  }

  @Test
  @DisplayName("缺省口径为 REAL，显式 ALL 才放宽为不限")
  void scopeDefaultsToReal() {
    when(mapper.suggestTitles(anyString(), anyString(), anyString(), any(), anyInt()))
        .thenReturn(List.of());
    when(mapper.suggestScholars(anyString(), anyString(), any(), anyInt())).thenReturn(List.of());

    Map<String, Object> strict = service.suggest("graph", null, 6);
    assertThat(strict.get("dataNatureScope")).isEqualTo("REAL");
    verify(mapper).suggestTitles(eq(GENERATION), eq("graph"), anyString(), eq("REAL"), eq(6));

    Map<String, Object> demo = service.suggest("graph", "ALL", 6);
    assertThat(demo.get("dataNatureScope")).isEqualTo("ALL");
    verify(mapper).suggestTitles(eq(GENERATION), eq("graph"), anyString(), isNull(), eq(6));
  }

  @Test
  @DisplayName("建议条数为 0 或超过上限时拒绝，不静默截断")
  void rejectsOutOfRangeLimit() {
    assertThatThrownBy(() -> service.suggest("graph", null, 0))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("1 到 10");
    assertThatThrownBy(() -> service.suggest("graph", null, 11))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("1 到 10");
    verify(mapper, never()).suggestTitles(anyString(), anyString(), anyString(), any(), anyInt());
  }

  @Test
  @DisplayName("上限 10 本身是允许的")
  void allowsLimitTen() {
    when(mapper.suggestTitles(anyString(), anyString(), anyString(), any(), anyInt()))
        .thenReturn(List.of());
    when(mapper.suggestScholars(anyString(), anyString(), any(), anyInt())).thenReturn(List.of());

    service.suggest("graph", null, 10);

    verify(mapper).suggestTitles(eq(GENERATION), eq("graph"), anyString(), eq("REAL"), eq(10));
  }

  @Test
  @DisplayName("通配符按字面转义：搜 % 不会变成匹配全部")
  void escapesLikeWildcards() {
    when(mapper.suggestTitles(anyString(), anyString(), anyString(), any(), anyInt()))
        .thenReturn(List.of());
    when(mapper.suggestScholars(anyString(), anyString(), any(), anyInt())).thenReturn(List.of());

    service.suggest("100%", null, 6);

    verify(mapper).suggestTitles(eq(GENERATION), eq("100%"), eq("100\\%"), eq("REAL"), eq(6));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> listOf(Map<String, Object> data, String key) {
    return (List<Map<String, Object>>) data.get(key);
  }
}
