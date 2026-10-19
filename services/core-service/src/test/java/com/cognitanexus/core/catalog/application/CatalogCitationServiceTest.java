package com.cognitanexus.core.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ContributionRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.ScholarRepository;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.AchievementDetails;
import com.cognitanexus.core.catalog.domain.CitationWarning;
import com.cognitanexus.core.catalog.domain.Contribution;
import com.cognitanexus.core.catalog.domain.EffectiveStatus;
import com.cognitanexus.core.catalog.domain.PublicationYear;
import com.cognitanexus.core.catalog.domain.Scholar;
import com.cognitanexus.core.integrity.application.IntegrityNoticeRepository;
import com.cognitanexus.core.integrity.domain.IntegrityNotice;
import com.cognitanexus.core.integrity.domain.IntegrityNoticeType;
import com.cognitanexus.sharedkernel.AchievementType;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.DataNature;
import com.cognitanexus.sharedkernel.RelationState;
import com.cognitanexus.sharedkernel.Visibility;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 引用导出的装配与告警。
 *
 * <p>这里守的不是文本格式（那是 {@code CitationFormatterTest} 的事），而是<b>哪些事实进入了文本、
 * 哪些事实必须被报告</b>：已排除的参与关系不能当作者、署名顺序未知不能装作有顺序、撤稿必须出现在
 * 文本与告警两处、模拟数据不能给出可引用的样子。
 *
 * <p>这些判断没有一个会让文本看起来坏掉——它们只会让一段「看起来很完整」的引用悄悄引用错人或
 * 引用到一条已撤稿的成果。所以必须逐条断言。
 */
class CatalogCitationServiceTest {

  private static final String ID = "ach_0a1b2c3d4e5f";

  private PublicCatalogQueryService catalog;
  private AchievementRepository achievements;
  private ScholarRepository scholars;
  private ContributionRepository contributions;
  private IntegrityNoticeRepository integrityNotices;
  private CatalogCitationService service;

  @BeforeEach
  void setUp() {
    catalog = mock(PublicCatalogQueryService.class);
    achievements = mock(AchievementRepository.class);
    scholars = mock(ScholarRepository.class);
    contributions = mock(ContributionRepository.class);
    integrityNotices = mock(IntegrityNoticeRepository.class);
    service =
        new CatalogCitationService(
            catalog, achievements, scholars, contributions, integrityNotices);

    lenient().when(achievements.findPaper(anyString())).thenReturn(Optional.empty());
    lenient().when(achievements.findPatent(anyString())).thenReturn(Optional.empty());
    lenient().when(achievements.findProject(anyString())).thenReturn(Optional.empty());
    lenient().when(achievements.findAward(anyString())).thenReturn(Optional.empty());
    lenient().when(contributions.listByAchievement(anyString())).thenReturn(List.of());
    lenient().when(integrityNotices.findByAchievement(anyString())).thenReturn(List.of());
  }

  @Test
  @DisplayName("一次给全四种格式，默认格式由服务端指定")
  void returnsAllFormats() {
    givenPublic(achievement(AchievementType.PAPER, 2023, DataNature.REAL));
    when(achievements.findPaper(ID)).thenReturn(Optional.of(paper("中国科学: 信息科学", "10.1/x")));
    givenAuthors(
        contribution("s1", 1, RelationState.AUTO), contribution("s2", 2, RelationState.AUTO));

    CitationBundle bundle = service.citations(ID);

    assertThat(bundle.formats())
        .extracting(entry -> entry.format().name())
        .containsExactly("BIBTEX", "RIS", "GB_T_7714", "APA");
    assertThat(bundle.defaultFormat().name()).isEqualTo("GB_T_7714");
    assertThat(bundle.citeKey()).isEqualTo("anon2023item_3d4e5f");
    assertThat(bundle.formats().get(0).fileName()).isEqualTo("anon2023item_3d4e5f.bib");
    assertThat(bundle.formats().get(0).mediaType()).isEqualTo("application/x-bibtex");
    assertThat(bundle.warnings()).isEmpty();
  }

  @Test
  @DisplayName("单一格式：默认下载 .bib，未知格式按参数错误拒绝")
  void singleFormat() {
    givenPublic(achievement(AchievementType.PAPER, 2023, DataNature.REAL));

    CatalogCitationService.CitationText text = service.citation(ID, "BIBTEX");
    assertThat(text.format().name()).isEqualTo("BIBTEX");
    assertThat(text.text()).startsWith("@article{");

    assertThatThrownBy(() -> service.citation(ID, "bib"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不支持的引用格式");
    assertThatThrownBy(() -> service.citation(ID, null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("引用格式不能为空");
  }

  @Test
  @DisplayName("已排除的参与关系不进署名，并在告警里说明剔除了几条")
  void excludedRelationIsDropped() {
    givenPublic(achievement(AchievementType.PAPER, 2023, DataNature.REAL));
    givenAuthors(
        contribution("s1", 1, RelationState.AUTO), contribution("s2", 2, RelationState.EXCLUDED));

    CitationBundle bundle = service.citations(ID);

    assertThat(bundle.formats().get(2).text()).startsWith("张三. ");
    assertThat(codes(bundle)).contains(CitationWarning.EXCLUDED_DROPPED);
  }

  @Test
  @DisplayName("署名顺序未知的作者排在已知序之后，并明确告警「先后不代表真实顺序」")
  void unknownAuthorOrderIsAppendedAndReported() {
    givenPublic(achievement(AchievementType.PAPER, 2023, DataNature.REAL));
    givenAuthors(
        contribution("s2", null, RelationState.AUTO), contribution("s1", 1, RelationState.AUTO));

    CatalogCitationService.CitationText text = service.citation(ID, "gb_t_7714");

    // 张三（序 1）在前，李四（序未知）在后——保持仓储顺序而不是按姓名重排。
    assertThat(text.text()).startsWith("张三, 李四. ");
    assertThat(text.warnings())
        .extracting(CitationWarning::code)
        .contains(CitationWarning.AUTHOR_ORDER_UNKNOWN);
  }

  @Test
  @DisplayName("没有任何署名者时文本从题名开始，并告警缺作者项")
  void noAuthor() {
    givenPublic(achievement(AchievementType.PAPER, 2023, DataNature.REAL));

    CatalogCitationService.CitationText text = service.citation(ID, "gb_t_7714");

    assertThat(text.text()).startsWith("深度学习在医学影像中的应用[J].");
    assertThat(text.warnings())
        .extracting(CitationWarning::code)
        .contains(CitationWarning.NO_AUTHOR);
  }

  @Test
  @DisplayName("撤稿同时体现在文本与告警上，而不是只在其中一处")
  void retractedAppearsInTextAndWarning() {
    givenPublic(achievement(AchievementType.PAPER, 2023, DataNature.REAL));
    when(integrityNotices.findByAchievement(ID))
        .thenReturn(List.of(notice(IntegrityNoticeType.RETRACTION)));

    CatalogCitationService.CitationText text = service.citation(ID, "gb_t_7714");

    assertThat(text.text()).contains("撤稿");
    assertThat(text.warnings())
        .extracting(CitationWarning::code)
        .contains(CitationWarning.RETRACTED);
  }

  @Test
  @DisplayName("模拟数据不得作为正式引用依据")
  void simulatedDataIsFlagged() {
    givenPublic(achievement(AchievementType.PAPER, 2023, DataNature.SIMULATED));

    CatalogCitationService.CitationText text = service.citation(ID, "bibtex");

    assertThat(text.warnings())
        .extracting(CitationWarning::code)
        .contains(CitationWarning.DATA_NATURE_NOT_REAL);
  }

  @Test
  @DisplayName("年份未知与缺刊名各自独立告警")
  void missingYearAndVenue() {
    givenPublic(achievement(AchievementType.PAPER, null, DataNature.REAL));

    CatalogCitationService.CitationText text = service.citation(ID, "ris");

    assertThat(text.warnings())
        .extracting(CitationWarning::code)
        .contains(CitationWarning.YEAR_UNKNOWN, CitationWarning.VENUE_MISSING);
  }

  @Test
  @DisplayName("专利编号优先取公开号，缺失时退化为申请号")
  void patentNumberPrefersPublicationNo() {
    givenPublic(achievement(AchievementType.PATENT, 2022, DataNature.REAL));
    when(achievements.findPatent(ID))
        .thenReturn(
            Optional.of(
                new AchievementDetails.Patent(
                    ID,
                    "202210000000.1",
                    "2022100000001",
                    "CN114000000A",
                    List.of("G06F"),
                    null,
                    LocalDate.parse("2022-01-05"),
                    LocalDate.parse("2022-06-01"))));

    assertThat(service.citation(ID, "gb_t_7714").text()).contains("公开号: CN114000000A");

    when(achievements.findPatent(ID))
        .thenReturn(
            Optional.of(
                new AchievementDetails.Patent(
                    ID,
                    "202210000000.1",
                    "2022100000001",
                    null,
                    List.of("G06F"),
                    null,
                    LocalDate.parse("2022-01-05"),
                    null)));
    assertThat(service.citation(ID, "gb_t_7714").text()).contains("公开号: 202210000000.1");
  }

  @Test
  @DisplayName("公开资格判定沿用详情查询，不另开一条读通路")
  void reusesPublicGate() {
    when(catalog.requirePublicTarget(ID)).thenThrow(BusinessException.notFound("成果不存在或尚不可见"));

    assertThatThrownBy(() -> service.citations(ID)).isInstanceOf(BusinessException.class);
  }

  // ------------------------------------------------------------------ 夹具

  private void givenPublic(Achievement achievement) {
    when(catalog.requirePublicTarget(achievement.id()))
        .thenReturn(new PublicCatalogQueryService.PublicTarget(achievement, null));
  }

  private void givenAuthors(Contribution... relations) {
    when(contributions.listByAchievement(ID)).thenReturn(List.of(relations));
    lenient().when(scholars.findById("s1")).thenReturn(Optional.of(scholar("s1", "张三")));
    lenient().when(scholars.findById("s2")).thenReturn(Optional.of(scholar("s2", "李四")));
  }

  private static List<String> codes(CitationBundle bundle) {
    return bundle.warnings().stream().map(CitationWarning::code).toList();
  }

  private static Achievement achievement(AchievementType type, Integer year, DataNature nature) {
    return new Achievement(
        ID,
        type,
        "深度学习在医学影像中的应用",
        "深度学习在医学影像中的应用",
        nature,
        Visibility.PUBLIC,
        EffectiveStatus.EFFECTIVE,
        PublicationYear.of(year),
        null,
        1L,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static AchievementDetails.Paper paper(String venue, String doi) {
    return new AchievementDetails.Paper(
        ID, doi, doi, venue, null, "PUBLISHED", 3, "CROSSREF", "摘要");
  }

  private static Scholar scholar(String id, String name) {
    return new Scholar(
        id,
        name,
        name,
        DataNature.REAL,
        Scholar.ClaimedStatus.UNCLAIMED,
        null,
        null,
        null,
        1L,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static Contribution contribution(String scholarId, Integer order, RelationState state) {
    return new Contribution(
        "con_" + scholarId,
        scholarId,
        ID,
        "AUTHOR",
        order,
        state,
        null,
        1L,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static IntegrityNotice notice(IntegrityNoticeType type) {
    return new IntegrityNotice(
        "itg_" + type.code(),
        ID,
        type,
        IntegrityNotice.STATUS_ACTIVE,
        "编辑部",
        "外部期刊发布撤稿通知，经核对与本条记录为同一篇",
        "https://example.org/retraction/1",
        LocalDate.parse("2024-03-01"),
        "ops_1",
        Instant.parse("2026-01-01T00:00:00Z"),
        null,
        null,
        null);
  }
}
