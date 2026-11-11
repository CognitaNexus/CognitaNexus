package com.cognitanexus.query.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.query.analytics.infrastructure.AnalyticsMapper;
import com.cognitanexus.query.analytics.infrastructure.AnalyticsMapper.HotspotRow;
import com.cognitanexus.query.consistency.PublicBarrierGuard;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 关键词热点（FR13）。
 *
 * <p>这一页最容易出的事不是「算错」，而是「算对了但被读成别的意思」，所以断言重点在口径而不是
 * 排序本身：
 *
 * <ul>
 *   <li>分母是什么。领域是多对多，占比之和超过 100% 是正常的；若不写明，100% 会被当成上限，
 *       读者会以为数据有问题，或者更糟——把它当成互斥分布去做决策。
 *   <li>样本多少。三篇论文也能排出「第一热点」，这个第一没有任何意义，必须在响应里说清楚
 *       「不构成结论」，而不是让界面自己判断。
 *   <li>哪些成果参与。专利、项目、获奖没有「领域热度」这回事，混进来会让排名变成四类成果的
 *       混合榜，且没有任何标注能让人看出来。
 * </ul>
 */
class KeywordHotspotTest {

  private static final String GENERATION = "gen-11";
  private static final long PUBLISHED_REVISION = 20480L;

  private AnalyticsMapper mapper;
  private PublicBarrierGuard guard;
  private AnalyticsService service;

  @BeforeEach
  void setUp() {
    mapper = mock(AnalyticsMapper.class);
    guard = mock(PublicBarrierGuard.class);
    service = new AnalyticsService(mapper, guard);
  }

  private void givenServing() {
    when(guard.assertPubliclyServed())
        .thenReturn(
            new PublicBarrierGuard.BarrierSnapshot(
                PUBLISHED_REVISION, 20480L, 20480L, PUBLISHED_REVISION, GENERATION));
  }

  private static HotspotRow row(String topicId, String name, long count) {
    return new HotspotRow(topicId, name, count, count - 1, 1L, 2019, 2024);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> items(Map<String, Object> data) {
    return (List<Map<String, Object>>) data.get("items");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> section(Map<String, Object> data, String key) {
    Object value = data.get(key);
    assertThat(value).as("响应中必须存在 %s", key).isInstanceOf(Map.class);
    return (Map<String, Object>) value;
  }

  @Test
  @DisplayName("缺省口径为真实数据、全时段，查询不再按年份过滤")
  void defaultScopeIsRealAndUnbounded() {
    givenServing();
    when(mapper.countHotspotSample(GENERATION, "REAL", null, null)).thenReturn(0L);
    when(mapper.topicHotspots(GENERATION, "REAL", null, null, 20)).thenReturn(List.of());

    Map<String, Object> data = service.keywordHotspots(null, null, null, 20);

    verify(mapper).topicHotspots(eq(GENERATION), eq("REAL"), isNull(), isNull(), eq(20));
    assertThat(section(data, "scope")).containsEntry("dataNature", "REAL");
    assertThat(section(data, "window")).containsEntry("label", "全时段（未限定年份窗）");
    // 回显生效范围而不是回显请求参数：请求传 null，响应不能也是 null。
    assertThat(data.get("publishedRevision")).isEqualTo(PUBLISHED_REVISION);
  }

  @Test
  @DisplayName("占比以窗口内全部公开论文为分母，多对多时可以超过 100%")
  void shareUsesSampleAsDenominatorAndMayExceedHundred() {
    givenServing();
    when(mapper.countHotspotSample(GENERATION, "REAL", 2020, 2024)).thenReturn(5L);
    // 5 篇论文里，A 领域出现 6 次：这在「一篇论文可属两个领域」的口径下完全正常。
    when(mapper.topicHotspots(GENERATION, "REAL", 2020, 2024, 20))
        .thenReturn(List.of(row("tpc_a", "人工智能", 6)));

    Map<String, Object> data = service.keywordHotspots(null, 2020, 2024, 20);

    assertThat(data.get("sampleSize")).isEqualTo(5L);
    assertThat(items(data).get(0).get("shareOfSample")).isEqualTo(120.0d);
    // 这一句是防误读的唯一手段，必须随数据返回。
    assertThat(String.valueOf(data.get("note"))).contains("超过 100%");
  }

  @Test
  @DisplayName("样本不足 5 篇时明确标注不构成结论，即使已经排出了名次")
  void sparseSampleIsDeclaredNotConcluded() {
    givenServing();
    when(mapper.countHotspotSample(GENERATION, "REAL", null, null)).thenReturn(2L);
    when(mapper.topicHotspots(GENERATION, "REAL", null, null, 20))
        .thenReturn(List.of(row("tpc_a", "人工智能", 2)));

    Map<String, Object> data = service.keywordHotspots(null, null, null, 20);

    assertThat(data.get("sparse")).isEqualTo(true);
    assertThat(String.valueOf(data.get("note"))).contains("不足 5 篇").contains("不构成热点结论");
    // 「有排名」与「结论成立」是两件事，界面靠 sparse 决定是否打警示条。
    assertThat(items(data)).hasSize(1);
  }

  @Test
  @DisplayName("样本足够但没有领域标注时，说明「不按标题猜主题」而不是显示空榜")
  void noKeywordCoverageIsExplained() {
    givenServing();
    when(mapper.countHotspotSample(GENERATION, "REAL", null, null)).thenReturn(120L);
    when(mapper.topicHotspots(GENERATION, "REAL", null, null, 20)).thenReturn(List.of());

    Map<String, Object> data = service.keywordHotspots(null, null, null, 20);

    assertThat(data.get("sparse")).isEqualTo(true);
    assertThat(String.valueOf(data.get("note"))).contains("没有可用的领域标注").contains("不按标题猜");
  }

  @Test
  @DisplayName("样本充足且有名次时不打稀疏警示")
  void denseSampleIsNotSparse() {
    givenServing();
    when(mapper.countHotspotSample(GENERATION, "REAL", null, null)).thenReturn(400L);
    when(mapper.countHotspotTopics(GENERATION, "REAL", null, null)).thenReturn(18L);
    when(mapper.topicHotspots(GENERATION, "REAL", null, null, 20))
        .thenReturn(List.of(row("tpc_a", "人工智能", 60), row("tpc_b", "数据库", 30)));

    Map<String, Object> data = service.keywordHotspots(null, null, null, 20);

    assertThat(data.get("sparse")).isEqualTo(false);
    assertThat(data.get("topicsWithKeywords")).isEqualTo(18L);
    assertThat(data.get("returnedTopics")).isEqualTo(2);
    assertThat(items(data).get(0).get("shareOfSample")).isEqualTo(15.0d);
  }

  @Test
  @DisplayName("limit 小于领域数时，「有标注的领域数」不能被「本页行数」顶替")
  void topicCountIsNotTruncatedByLimit() {
    givenServing();
    when(mapper.countHotspotSample(GENERATION, "REAL", null, null)).thenReturn(500L);
    when(mapper.countHotspotTopics(GENERATION, "REAL", null, null)).thenReturn(18L);
    when(mapper.topicHotspots(GENERATION, "REAL", null, null, 2))
        .thenReturn(List.of(row("tpc_a", "人工智能", 100), row("tpc_b", "数据库", 60)));

    Map<String, Object> data = service.keywordHotspots(null, null, null, 2);

    // 把行数填进 topicsWithKeywords 时这里会是 2，页面就会说「只有 2 个领域有标注」。
    assertThat(data.get("topicsWithKeywords")).isEqualTo(18L);
    assertThat(data.get("returnedTopics")).isEqualTo(2);
    assertThat(items(data)).hasSize(2);
  }

  @Test
  @DisplayName("领域名称缺失时另给布尔，前端不靠空串猜")
  void missingCanonicalNameIsFlagged() {
    givenServing();
    when(mapper.countHotspotSample(GENERATION, "REAL", null, null)).thenReturn(50L);
    when(mapper.topicHotspots(GENERATION, "REAL", null, null, 20))
        .thenReturn(List.of(row("tpc_x", null, 9)));

    Map<String, Object> data = service.keywordHotspots(null, null, null, 20);

    assertThat(items(data).get(0)).containsEntry("canonicalNameKnown", false);
    assertThat(items(data).get(0)).containsEntry("topicId", "tpc_x");
  }

  @Test
  @DisplayName("起始年份晚于结束年份直接拒绝，不查库")
  void rejectsInvertedYearWindow() {
    assertThatExceptionOfType(BusinessException.class)
        .isThrownBy(() -> service.keywordHotspots(null, 2024, 2020, 20))
        .extracting(e -> e.errorCode())
        .isEqualTo(ErrorCode.INVALID_ARGUMENT);
    verify(mapper, never()).topicHotspots(any(), anyString(), any(), any(), anyInt());
  }

  @Test
  @DisplayName("年份越界拒绝：1800–2200 之外的值说明调用方抄错了字段")
  void rejectsOutOfRangeYear() {
    assertThatExceptionOfType(BusinessException.class)
        .isThrownBy(() -> service.keywordHotspots(null, 1200, null, 20))
        .extracting(e -> e.errorCode())
        .isEqualTo(ErrorCode.INVALID_ARGUMENT);
    assertThatExceptionOfType(BusinessException.class)
        .isThrownBy(() -> service.keywordHotspots(null, null, 3024, 20))
        .extracting(e -> e.errorCode())
        .isEqualTo(ErrorCode.INVALID_ARGUMENT);
    verify(mapper, never()).countHotspotSample(any(), anyString(), any(), any());
  }

  @Test
  @DisplayName("条数上限被裁剪到 1–50，避免一次拉全表")
  void limitIsClamped() {
    givenServing();
    when(mapper.countHotspotSample(GENERATION, "REAL", null, null)).thenReturn(0L);
    when(mapper.topicHotspots(GENERATION, "REAL", null, null, 50)).thenReturn(List.of());

    service.keywordHotspots(null, null, null, 5000);

    verify(mapper).topicHotspots(GENERATION, "REAL", null, null, 50);
  }

  @Test
  @DisplayName("规则版本与总览分开，且逐条给出计数口径")
  void ruleVersionAndCountingRulesAreReturned() {
    givenServing();
    when(mapper.countHotspotSample(GENERATION, "REAL", null, null)).thenReturn(500L);
    when(mapper.topicHotspots(GENERATION, "REAL", null, null, 20))
        .thenReturn(List.of(row("tpc_a", "人工智能", 100)));

    Map<String, Object> data = service.keywordHotspots(null, null, null, 20);

    assertThat(data.get("ruleVersion")).isEqualTo(AnalyticsService.HOTSPOT_RULE_VERSION);
    // 分母不同就不能共用版本号，否则复核者会拿总览的尺子核对这里的数字。
    assertThat(AnalyticsService.HOTSPOT_RULE_VERSION).isNotEqualTo(AnalyticsService.RULE_VERSION);

    Map<String, Object> rules = section(data, "countingRules");
    assertThat(rules).containsKeys("unit", "types", "years", "order", "synonyms", "sample");
    assertThat(String.valueOf(rules.get("types"))).contains("只统计论文");
    assertThat(String.valueOf(rules.get("years"))).contains("年份未知");
    assertThat(String.valueOf(rules.get("order"))).contains("升序");
  }
}
