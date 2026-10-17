package com.cognitanexus.query.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cognitanexus.query.analytics.infrastructure.AnalyticsMapper;
import com.cognitanexus.query.analytics.infrastructure.AnalyticsMapper.CitationRow;
import com.cognitanexus.query.analytics.infrastructure.AnalyticsMapper.CountRow;
import com.cognitanexus.query.analytics.infrastructure.AnalyticsMapper.YearRow;
import com.cognitanexus.query.consistency.PublicBarrierGuard;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 统计用例的行为约束。
 *
 * <p>依据：SRS FR12（公开统计）、架构 V0.2 §9.2「统计口径必须随数据一起返回」「未知单列不进入
 * 已知排名」「引用按来源分别展示，不跨来源相加」。
 *
 * <p>这一组用例针对的是统计最容易出错、且出错后<b>看不出来</b>的三种情形：
 *
 * <ol>
 *   <li>缺省口径被实现成「不限制」——库里只有真实数据时表现与正确实现一致，一旦模拟数据入库就
 *       会污染真实口径，而响应仍然声称自己是 REAL；
 *   <li>未知年份被补成 1 月 1 日或并入相邻年份——凭空造出一条不存在的统计事实；
 *   <li>不同来源的引用量被相加成一个「总引用」——口径不同却给出一个像模像样的数字。
 * </ol>
 */
class AnalyticsServiceTest {

  private static final String GENERATION = "gen-7";
  private static final long PUBLISHED_REVISION = 12695L;

  private AnalyticsMapper mapper;
  private PublicBarrierGuard guard;
  private AnalyticsService service;

  @BeforeEach
  void setUp() {
    mapper = mock(AnalyticsMapper.class);
    guard = mock(PublicBarrierGuard.class);
    service = new AnalyticsService(mapper, guard);
  }

  /** 屏障放行。每个用例按需调用，避免严格桩校验因未使用的桩而失败。 */
  private void givenServing() {
    when(guard.assertPubliclyServed())
        .thenReturn(
            new PublicBarrierGuard.BarrierSnapshot(
                PUBLISHED_REVISION, 12700L, 12700L, PUBLISHED_REVISION, GENERATION));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> section(Map<String, Object> data, String key) {
    Object value = data.get(key);
    assertThat(value).as("响应中必须存在 %s", key).isInstanceOf(Map.class);
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> rows(Map<String, Object> data, String key) {
    Object value = data.get(key);
    assertThat(value).as("响应中必须存在 %s", key).isInstanceOf(List.class);
    return (List<Map<String, Object>>) value;
  }

  @Test
  @DisplayName("缺省口径只含真实数据：查询带 REAL 过滤，响应回显生效范围而不是回显请求参数")
  void defaultScopeIsReal() {
    givenServing();

    Map<String, Object> data = service.overview(null);

    verify(mapper).countAchievements(GENERATION, "REAL");
    verify(mapper).countScholars(GENERATION, "REAL");
    verify(mapper).countContributions(GENERATION, "REAL");
    verify(mapper).countCoauthorPairs(GENERATION, "REAL");
    verify(mapper).countUnknownOrganization(GENERATION, "REAL");
    verify(mapper).byType(GENERATION, "REAL");
    verify(mapper).byYear(GENERATION, "REAL");
    verify(mapper).citationsBySource(GENERATION, "REAL");

    Map<String, Object> scope = section(data, "scope");
    // 回显 null 会让字段从响应里消失，读者因而无法判断拿到的是真实口径还是演示口径。
    assertThat(scope.get("dataNature")).isEqualTo("REAL");
    assertThat(scope.get("effectiveStatus")).isEqualTo("EFFECTIVE");
    assertThat(scope.get("visibility")).isEqualTo("PUBLIC");
    assertThat(scope.get("timeWindow")).asString().contains("无时间窗");
    assertThat(scope.get("note")).asString().contains("仅含真实数据");
  }

  @Test
  @DisplayName("显式 ALL 才放宽：查询不带性质过滤，但响应必须标注为演示口径")
  void explicitAllWidensScopeAndIsLabelled() {
    givenServing();

    Map<String, Object> data = service.overview(" all ");

    verify(mapper).countAchievements(GENERATION, null);
    verify(mapper).byType(GENERATION, null);

    Map<String, Object> scope = section(data, "scope");
    assertThat(scope.get("dataNature")).isEqualTo("ALL");
    assertThat(scope.get("note")).asString().contains("演示");
    assertThat(scope.get("note")).asString().contains("不代表真实收录口径");
  }

  @Test
  @DisplayName("非法性质取值按参数错误拒绝，且不产生任何统计查询")
  void invalidNatureRejected() {
    givenServing();

    assertThatExceptionOfType(BusinessException.class)
        .isThrownBy(() -> service.overview("FAKE"))
        .satisfies(ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT))
        .withMessageContaining("数据性质取值必须是");
    verifyNoInteractions(mapper);
  }

  @Test
  @DisplayName("总量五项齐备且键顺序稳定，缺一项都会让前端把 undefined 当成 0 展示")
  void totalsAreCompleteAndOrdered() {
    givenServing();
    when(mapper.countAchievements(GENERATION, "REAL")).thenReturn(12345L);
    when(mapper.countScholars(GENERATION, "REAL")).thenReturn(890L);
    when(mapper.countContributions(GENERATION, "REAL")).thenReturn(45678L);
    when(mapper.countCoauthorPairs(GENERATION, "REAL")).thenReturn(2345L);
    when(mapper.countUnknownOrganization(GENERATION, "REAL")).thenReturn(67L);

    Map<String, Object> totals = section(service.overview("REAL"), "totals");

    assertThat(totals.keySet())
        .containsExactly(
            "achievements",
            "scholarsWithQualifiedRelation",
            "contributions",
            "coauthorPairs",
            "achievementsWithUnknownOrganization");
    assertThat(totals.values()).containsExactly(12345L, 890L, 45678L, 2345L, 67L);
  }

  @Test
  @DisplayName("年份未知单列：不补默认年份、不并入已知年份，也不从总量里消失")
  void unknownYearListedSeparately() {
    givenServing();
    when(mapper.byYear(GENERATION, "REAL"))
        .thenReturn(List.of(new YearRow(null, 7), new YearRow(2023, 10), new YearRow(2024, 5)));

    Map<String, Object> data = service.overview("REAL");

    List<Object> years =
        rows(data, "byYear").stream().map(row -> row.get("year")).collect(Collectors.toList());
    assertThat(years).containsExactly(2023, 2024);
    // 未知桶是独立字段，既不是 0 年也不是 1 月 1 日。
    assertThat(data.get("achievementsWithUnknownYear")).isEqualTo(7L);
    assertThat(years).doesNotContain(0, 1);
    assertThat(rows(data, "byYear").get(0)).containsEntry("amount", 10L);
  }

  @Test
  @DisplayName("年份分组为空时两个字段仍然存在，避免前端把缺失读成 0 条")
  void yearSectionAlwaysPresent() {
    givenServing();
    when(mapper.byYear(GENERATION, "REAL")).thenReturn(List.of());

    Map<String, Object> data = service.overview("REAL");

    assertThat(rows(data, "byYear")).isEmpty();
    assertThat(data).containsKey("achievementsWithUnknownYear");
    assertThat(data.get("achievementsWithUnknownYear")).isEqualTo(0L);
  }

  @Test
  @DisplayName("引用按来源分别返回，不产生也不暗示任何跨来源总量")
  void citationsAreNotSummed() {
    givenServing();
    when(mapper.citationsBySource(GENERATION, "REAL"))
        .thenReturn(
            List.of(new CitationRow("CROSSREF", 5L, 100L), new CitationRow("UNKNOWN", 2L, 0L)));

    Map<String, Object> data = service.overview("REAL");

    List<Map<String, Object>> citations = rows(data, "citationsBySource");
    assertThat(citations).hasSize(2);
    assertThat(citations.get(0))
        .containsEntry("citationSource", "CROSSREF")
        .containsEntry("achievementsWithCitation", 5L)
        .containsEntry("citationSum", 100L);
    assertThat(citations.get(1)).containsEntry("citationSource", "UNKNOWN");
    // 不提供任何跨来源汇总键：相加得到的数没有含义，却会被当成可比较指标使用。
    assertThat(data).doesNotContainKey("citationTotal");
    assertThat(data.keySet()).noneMatch(key -> key.toLowerCase().contains("citationtotal"));
  }

  @Test
  @DisplayName("排行类统计使用固定上限 20，不由调用方指定")
  void rankingLimitedToTwenty() {
    givenServing();

    service.overview("REAL");

    verify(mapper).topOrganizations(GENERATION, "REAL", 20);
    verify(mapper).topTopics(GENERATION, "REAL", 20);
    verify(mapper).topVenues(GENERATION, "REAL", 20);
  }

  @Test
  @DisplayName("数据性质分档不跟随请求筛选，始终展示库内实际构成")
  void byNatureIgnoresRequestedFilter() {
    givenServing();
    when(mapper.byNature(GENERATION))
        .thenReturn(List.of(new CountRow("REAL", 900L), new CountRow("SIMULATED", 100L)));

    Map<String, Object> data = service.overview("REAL");

    verify(mapper).byNature(GENERATION);
    List<Map<String, Object>> byNature = rows(data, "byNature");
    assertThat(byNature).hasSize(2);
    assertThat(byNature.get(0)).containsEntry("key", "REAL").containsEntry("achievements", 900L);
    assertThat(byNature.get(1)).containsEntry("key", "SIMULATED");
  }

  @Test
  @DisplayName("分档行统一为 key/achievements 结构，机构与领域等表共用同一种读法")
  void bucketRowsShareOneShape() {
    givenServing();
    when(mapper.byType(GENERATION, "REAL")).thenReturn(List.of(new CountRow("PAPER", 42L)));

    Map<String, Object> data = service.overview("REAL");

    assertThat(rows(data, "byType"))
        .singleElement()
        .isEqualTo(Map.of("key", "PAPER", "achievements", 42L));
  }

  @Test
  @DisplayName("口径规则六条齐备，并写明引用不跨来源相加、领域合计可超总量")
  void countingRulesAreComplete() {
    givenServing();

    Map<String, Object> rules = section(service.overview("REAL"), "countingRules");

    assertThat(rules.keySet())
        .containsExactly(
            "achievements",
            "scholarsWithQualifiedRelation",
            "organizations",
            "topics",
            "citations",
            "coauthorPairs");
    assertThat(rules.get("citations")).asString().contains("不跨来源相加");
    assertThat(rules.get("topics")).asString().contains("可超过总量");
    assertThat(rules.get("organizations")).asString().contains("同成果同机构只计一次");
    assertThat(rules.get("scholarsWithQualifiedRelation")).asString().contains("待核验");
  }

  @Test
  @DisplayName("响应自带规则版本、发布修订与生成时间——没有这三项的数字无法被复核")
  void responseCarriesItsOwnProvenance() {
    givenServing();

    Map<String, Object> data = service.overview("REAL");

    assertThat(data.get("ruleVersion")).isEqualTo(AnalyticsService.RULE_VERSION);
    assertThat(AnalyticsService.RULE_VERSION).isEqualTo("ANALYTICS_V1");
    assertThat(data.get("publishedRevision")).isEqualTo(PUBLISHED_REVISION);
    assertThat(data.get("generatedAt")).isInstanceOf(Instant.class);
  }

  @Test
  @DisplayName("统计读的是屏障给出的活动代际，不是请求参数，也不是本地猜测")
  void usesGenerationFromBarrier() {
    givenServing();

    service.overview("REAL");

    verify(mapper).countAchievements(GENERATION, "REAL");
  }

  @Test
  @DisplayName("公开水位落后时整个统计失败，不返回任何旧数字")
  void revisionLagFailsWholeRequest() {
    when(guard.assertPubliclyServed())
        .thenThrow(
            new BusinessException(ErrorCode.VIEW_UPDATING, "公开视图正在更新：已发布修订 12694 落后于权威要求 12695"));

    assertThatExceptionOfType(BusinessException.class)
        .isThrownBy(() -> service.overview("REAL"))
        .satisfies(ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VIEW_UPDATING));
    // 一致性失败时返回「大部分正确」的统计比直接失败更危险：读者无从知道哪些数字是旧的。
    verifyNoInteractions(mapper);
  }

  // ---------------- 领域洞察 ----------------

  private static final String TOPIC_ID = "top_ai";

  /** 领域存在的标准桩：领域名称非空，来源分类为 0、推断为全量（与当前库内真实构成一致）。 */
  private void givenTopicFound() {
    givenServing();
    when(mapper.topicHeader(GENERATION, TOPIC_ID, "REAL"))
        .thenReturn(new AnalyticsMapper.TopicHeaderRow("人工智能", 6738L, 0L, 6738L));
    when(mapper.topicScholarCount(GENERATION, TOPIC_ID, "REAL")).thenReturn(900L);
    when(mapper.topicUnknownOrganization(GENERATION, TOPIC_ID, "REAL")).thenReturn(12L);
    when(mapper.topicByYear(GENERATION, TOPIC_ID, "REAL")).thenReturn(List.of());
    when(mapper.topicByType(GENERATION, TOPIC_ID, "REAL")).thenReturn(List.of());
    when(mapper.topicOrganizations(GENERATION, TOPIC_ID, "REAL", 10)).thenReturn(List.of());
    when(mapper.topicScholars(GENERATION, TOPIC_ID, "REAL", 10)).thenReturn(List.of());
    when(mapper.topicVenues(GENERATION, TOPIC_ID, "REAL", 10)).thenReturn(List.of());
    when(mapper.topicRepresentative(GENERATION, TOPIC_ID, "REAL", 10)).thenReturn(List.of());
  }

  @Test
  @DisplayName("领域洞察：标识为空按参数错误拒绝，且不产生任何统计查询")
  void topicInsightRequiresId() {
    givenServing();

    assertThatExceptionOfType(BusinessException.class)
        .isThrownBy(() -> service.topicInsight("   ", "REAL"))
        .satisfies(ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT));
    verifyNoInteractions(mapper);
  }

  @Test
  @DisplayName("领域在当前口径下没有公开成果时按 NOT_FOUND 拒绝，不构造一份空榜单冒充数据")
  void topicWithoutPublicResultsIsNotFound() {
    givenServing();
    // 表头是聚合查询，无行时返回的是全零行而不是 null——存在性判据是成果数为 0。
    when(mapper.topicHeader(GENERATION, TOPIC_ID, "REAL"))
        .thenReturn(new AnalyticsMapper.TopicHeaderRow(null, 0L, 0L, 0L));

    assertThatExceptionOfType(BusinessException.class)
        .isThrownBy(() -> service.topicInsight(TOPIC_ID, "REAL"))
        .satisfies(ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND))
        .withMessageContaining("没有可公开的成果");
    // 空结果与「不存在」在界面上长得一样；只有 404 能让前端区分「该领域没有公开成果」与「确实没有」。
    verify(mapper, org.mockito.Mockito.never()).topicScholarCount(GENERATION, TOPIC_ID, "REAL");
    verify(mapper, org.mockito.Mockito.never()).topicByYear(GENERATION, TOPIC_ID, "REAL");
  }

  @Test
  @DisplayName("领域洞察：领域名称缺失时以布尔显式标注，而不是让字段从响应里消失")
  void topicNameAbsenceIsExplicit() {
    givenServing();
    when(mapper.topicHeader(GENERATION, TOPIC_ID, "REAL"))
        .thenReturn(new AnalyticsMapper.TopicHeaderRow(null, 5L, 5L, 0L));
    when(mapper.topicScholarCount(GENERATION, TOPIC_ID, "REAL")).thenReturn(0L);
    when(mapper.topicUnknownOrganization(GENERATION, TOPIC_ID, "REAL")).thenReturn(0L);
    when(mapper.topicByYear(GENERATION, TOPIC_ID, "REAL")).thenReturn(List.of());
    when(mapper.topicByType(GENERATION, TOPIC_ID, "REAL")).thenReturn(List.of());
    when(mapper.topicOrganizations(GENERATION, TOPIC_ID, "REAL", 10)).thenReturn(List.of());
    when(mapper.topicScholars(GENERATION, TOPIC_ID, "REAL", 10)).thenReturn(List.of());
    when(mapper.topicVenues(GENERATION, TOPIC_ID, "REAL", 10)).thenReturn(List.of());
    when(mapper.topicRepresentative(GENERATION, TOPIC_ID, "REAL", 10)).thenReturn(List.of());

    @SuppressWarnings("unchecked")
    Map<String, Object> topic = section(service.topicInsight(TOPIC_ID, "REAL"), "topic");

    assertThat(topic).containsEntry("topicId", TOPIC_ID).containsEntry("canonicalNameKnown", false);
  }

  @Test
  @DisplayName("领域洞察：来源与推断之和等于成果总数，学者与机构未知单列计数")
  void topicTotalsAreConsistent() {
    givenTopicFound();

    Map<String, Object> data = service.topicInsight(TOPIC_ID, "REAL");

    Map<String, Object> totals = section(data, "totals");
    assertThat(totals.keySet())
        .containsExactly(
            "achievements",
            "sourceProvided",
            "systemInferred",
            "scholarsWithQualifiedRelation",
            "achievementsWithUnknownOrganization");
    assertThat(totals.get("achievements")).isEqualTo(6738L);
    // 会计恒等式：两列之和必须等于总数，否则页面上的构成说明与数字自相矛盾。
    long source = (Long) totals.get("sourceProvided");
    long inferred = (Long) totals.get("systemInferred");
    assertThat(source + inferred).isEqualTo(6738L);
    assertThat(totals.get("scholarsWithQualifiedRelation")).isEqualTo(900L);
    assertThat(totals.get("achievementsWithUnknownOrganization")).isEqualTo(12L);
  }

  @Test
  @DisplayName("领域洞察：学者行名称缺失显式标注，行标识始终存在")
  void scholarNameAbsenceIsExplicit() {
    givenTopicFound();
    when(mapper.topicScholars(GENERATION, TOPIC_ID, "REAL", 10))
        .thenReturn(List.of(new AnalyticsMapper.ScholarRow("sch_1", null, 3L)));

    List<Map<String, Object>> scholars =
        rows(service.topicInsight(TOPIC_ID, "REAL"), "topScholars");

    assertThat(scholars)
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.get("scholarId")).isEqualTo("sch_1");
              assertThat(row.get("nameKnown")).isEqualTo(false);
            });
  }

  @Test
  @DisplayName("领域洞察：代表成果的年份与引用量缺失都以布尔显式标注，引用来源一并给出")
  void representativeAbsenceIsExplicit() {
    givenTopicFound();
    when(mapper.topicRepresentative(GENERATION, TOPIC_ID, "REAL", 10))
        .thenReturn(
            List.of(new AnalyticsMapper.TopicAchievementRow("ach_1", "标题", null, null, null)));

    List<Map<String, Object>> items =
        rows(service.topicInsight(TOPIC_ID, "REAL"), "representativeAchievements");

    assertThat(items)
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.get("achievementId")).isEqualTo("ach_1");
              assertThat(row.get("publicationYearKnown")).isEqualTo(false);
              assertThat(row.get("citationCountKnown")).isEqualTo(false);
              assertThat(row.get("citationSourceKnown")).isEqualTo(false);
            });
  }

  @Test
  @DisplayName("领域洞察：排名类查询使用固定上限 10，不由调用方指定")
  void topicRankingLimitedToTen() {
    givenTopicFound();

    service.topicInsight(TOPIC_ID, "REAL");

    verify(mapper).topicOrganizations(GENERATION, TOPIC_ID, "REAL", 10);
    verify(mapper).topicScholars(GENERATION, TOPIC_ID, "REAL", 10);
    verify(mapper).topicVenues(GENERATION, TOPIC_ID, "REAL", 10);
    verify(mapper).topicRepresentative(GENERATION, TOPIC_ID, "REAL", 10);
  }

  @Test
  @DisplayName("领域洞察：口径说明写明排名仅限本领域、推断与来源之别、引用量跨来源不可比")
  void topicCountingRulesAreComplete() {
    givenTopicFound();

    Map<String, Object> rules = section(service.topicInsight(TOPIC_ID, "REAL"), "countingRules");

    assertThat(rules.get("organizations")).asString().contains("只统计该领域");
    assertThat(rules.get("scholars")).asString().contains("待核验");
    assertThat(rules.get("representativeAchievements")).asString().contains("不可比较");
    assertThat(rules.get("topics")).asString().contains("systemInferred");
  }

  @Test
  @DisplayName("领域洞察：响应自带独立规则版本与发布修订，口径变化可被复核")
  void topicResponseCarriesProvenance() {
    givenTopicFound();

    Map<String, Object> data = service.topicInsight(TOPIC_ID, "REAL");

    assertThat(data.get("ruleVersion")).isEqualTo(AnalyticsService.TOPIC_RULE_VERSION);
    assertThat(AnalyticsService.TOPIC_RULE_VERSION).isEqualTo("TOPIC_INSIGHT_V1");
    // 与总览的规则版本分开：两者口径不同，共用版本号会让复核者误以为可用同一把尺子核对。
    assertThat(AnalyticsService.TOPIC_RULE_VERSION).isNotEqualTo(AnalyticsService.RULE_VERSION);
    assertThat(data.get("publishedRevision")).isEqualTo(PUBLISHED_REVISION);
    assertThat(data.get("generatedAt")).isInstanceOf(Instant.class);
  }

  @Test
  @DisplayName("领域洞察：读的是屏障给出的活动代际，一致性失败时整体拒绝")
  void topicInsightUsesGenerationFromBarrier() {
    givenTopicFound();

    service.topicInsight(TOPIC_ID, "REAL");
    verify(mapper).topicHeader(GENERATION, TOPIC_ID, "REAL");

    when(guard.assertPubliclyServed())
        .thenThrow(new BusinessException(ErrorCode.VIEW_UPDATING, "公开视图正在更新"));
    assertThatExceptionOfType(BusinessException.class)
        .isThrownBy(() -> service.topicInsight(TOPIC_ID, "REAL"))
        .satisfies(ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VIEW_UPDATING));
  }
}
