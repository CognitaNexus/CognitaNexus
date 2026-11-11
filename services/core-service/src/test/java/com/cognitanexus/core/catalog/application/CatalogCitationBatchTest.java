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
import com.cognitanexus.core.catalog.domain.CitationWarning;
import com.cognitanexus.core.catalog.domain.Contribution;
import com.cognitanexus.core.catalog.domain.EffectiveStatus;
import com.cognitanexus.core.catalog.domain.PublicationYear;
import com.cognitanexus.core.catalog.domain.Scholar;
import com.cognitanexus.core.integrity.application.IntegrityNoticeRepository;
import com.cognitanexus.sharedkernel.AchievementType;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.DataNature;
import com.cognitanexus.sharedkernel.RelationState;
import com.cognitanexus.sharedkernel.Visibility;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 批量导出的集合级判断：谁进文件、谁被跳过、文件里少说明了什么。
 *
 * <p>单项渲染的正确性由 {@code CitationCollectionFormatterTest} 与 {@code CitationFormatterTest}
 * 负责。这里守的是批量入口独有的四件事：单条不可见不得拖垮整批、跳过原因不得泄露存在性、
 * 上限必须显式拒绝而不是截断、全部不可导出时必须失败而不是交一份空文件。
 */
class CatalogCitationBatchTest {

  private static final String ID_A = "ach_aaaaaa111111";
  private static final String ID_B = "ach_bbbbbb222222";

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
    // 每条成果都有一位署名者：没有作者时 APA 不会产生「姓名未拆分」告警，格式告警的断言就失去意义。
    lenient()
        .when(contributions.listByAchievement(anyString()))
        .thenReturn(List.of(contribution("s1", 1)));
    lenient().when(integrityNotices.findByAchievement(anyString())).thenReturn(List.of());
    lenient().when(scholars.findById(anyString())).thenReturn(Optional.of(scholar("张三")));
  }

  @Test
  @DisplayName("两条可公开成果：一条文件、两条条目、文件名带格式与日期")
  void exportsBoth() {
    givenPublic(ID_A);
    givenPublic(ID_B);

    CitationBatch batch = service.exportBatch(List.of(ID_A, ID_B), "bibtex");

    assertThat(batch.requested()).isEqualTo(2);
    assertThat(batch.entries()).hasSize(2);
    assertThat(batch.skipped()).isEmpty();
    assertThat(batch.mediaType()).isEqualTo("application/x-bibtex");
    assertThat(batch.machineReadable()).isTrue();
    assertThat(batch.entryFileName()).isEqualTo("citations-bibtex-" + LocalDate.now() + ".bib");
    assertThat(batch.text()).contains("@article{", "}\n\n@").doesNotContain("[1]");
    assertThat(batch.exportedOn()).isEqualTo(LocalDate.now().toString());
    assertThat(batch.provenance()).isNotBlank();
  }

  @Test
  @DisplayName("单条不可公开不拖垮整批：跳过并报数，原因只给一个码")
  void skipsUnavailable() {
    givenPublic(ID_A);
    when(catalog.requirePublicTarget("ach_gone"))
        .thenThrow(BusinessException.notFound("成果不存在或尚不可见"));

    CitationBatch batch = service.exportBatch(List.of(ID_A, "ach_gone"), "ris");

    assertThat(batch.requested()).isEqualTo(2);
    assertThat(batch.entries()).hasSize(1);
    assertThat(batch.skipped()).hasSize(1);
    assertThat(batch.skipped().get(0).achievementId()).isEqualTo("ach_gone");
    // 存在性与公开资格必须是同一个码：区分它们等于给受限成果留一条探测通道。
    assertThat(batch.skipped().get(0).reason()).isEqualTo(CitationBatch.REASON_NOT_AVAILABLE);
    assertThat(batch.text()).contains("TY  - JOUR");
  }

  @Test
  @DisplayName("全部不可导出时明确失败，不给出空文件")
  void allUnavailableFails() {
    when(catalog.requirePublicTarget(anyString()))
        .thenThrow(BusinessException.notFound("成果不存在或尚不可见"));

    assertThatThrownBy(() -> service.exportBatch(List.of(ID_A, ID_B), "bibtex"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("都不可公开导出")
        .extracting(error -> ((BusinessException) error).errorCode().name())
        .isEqualTo("NOT_FOUND");
  }

  @Test
  @DisplayName("未选任何成果、格式非法、超过上限分别按 400 / 400 / 422 拒绝")
  void inputValidation() {
    givenPublic(ID_A);

    assertThat(errorCodeOf(() -> service.exportBatch(List.of(), "bibtex")))
        .isEqualTo("INVALID_ARGUMENT");
    assertThat(errorCodeOf(() -> service.exportBatch(List.of("  ", ""), "bibtex")))
        .isEqualTo("INVALID_ARGUMENT");
    assertThat(errorCodeOf(() -> service.exportBatch(List.of(ID_A), "bib")))
        .isEqualTo("INVALID_ARGUMENT");

    List<String> tooMany = new ArrayList<>();
    for (int i = 0; i < CatalogCitationService.MAX_BATCH_ITEMS + 1; i++) {
      tooMany.add("ach_" + i);
    }
    assertThat(errorCodeOf(() -> service.exportBatch(tooMany, "bibtex")))
        .isEqualTo("BUSINESS_RULE_VIOLATION");
  }

  @Test
  @DisplayName("重复选中同一成果只写一条，顺序保持读者选中顺序")
  void deduplicatesAndKeepsOrder() {
    givenPublic(ID_A);
    givenPublic(ID_B);

    CitationBatch batch = service.exportBatch(List.of(ID_B, ID_A, ID_B), "gb_t_7714");

    assertThat(batch.requested()).isEqualTo(2);
    assertThat(batch.entries())
        .extracting(CitationBatch.Entry::achievementId)
        .containsExactly(ID_B, ID_A);
    // 国标成篇逐条编号，编号由装配时统一加，条目本身不带编号。
    assertThat(batch.text()).startsWith("[1] ").contains("\n[2] ");
    assertThat(batch.entries().get(0).text()).doesNotStartWith("[1]");
  }

  @Test
  @DisplayName("两条成果的键相同则改键并回告警：BibTeX 撞键是静默覆盖")
  void disambiguatesCiteKeys() {
    // 两个 id 的 ASCII 折叠结果末 6 位相同，因此引用键相同——这正是需要处理的情形。
    givenPublic("ach_aaaaaa111111");
    givenPublic("ach_cccccc111111");

    CitationBatch batch =
        service.exportBatch(List.of("ach_aaaaaa111111", "ach_cccccc111111"), "bibtex");

    assertThat(batch.entries()).extracting(CitationBatch.Entry::citeKey).doesNotHaveDuplicates();
    assertThat(batch.warnings())
        .extracting(CitationWarning::code)
        .contains(CitationWarning.CITE_KEY_DISAMBIGUATED);
    // 改过的键必须真的写进条目，否则文件里仍是两个同名条目。
    assertThat(batch.text()).contains("{" + batch.entries().get(1).citeKey() + ",");
  }

  @Test
  @DisplayName("键不冲突时不产生改键告警，避免无差别提示")
  void noWarningWhenKeysDistinct() {
    givenPublic(ID_A);
    givenPublic(ID_B);

    assertThat(service.exportBatch(List.of(ID_A, ID_B), "bibtex").warnings()).isEmpty();
  }

  @Test
  @DisplayName("格式自身的局限随条目给出：APA 提示姓名未拆分，BibTeX 不提示")
  void formatWarningIsPerEntry() {
    givenPublic(ID_A);

    CitationBatch apa = service.exportBatch(List.of(ID_A), "apa");
    assertThat(apa.entries().get(0).warnings())
        .extracting(CitationWarning::code)
        .contains(CitationWarning.NAME_SPLIT_NOT_APPLIED);

    CitationBatch bibtex = service.exportBatch(List.of(ID_A), "bibtex");
    assertThat(bibtex.entries().get(0).warnings())
        .extracting(CitationWarning::code)
        .doesNotContain(CitationWarning.NAME_SPLIT_NOT_APPLIED);
  }

  @Test
  @DisplayName("元数据告警逐条给出：缺年份的那条带 YEAR_UNKNOWN，另一条不带")
  void metadataWarningsArePerEntry() {
    givenPublic(ID_A, 2023);
    givenPublic(ID_B, null);

    CitationBatch batch = service.exportBatch(List.of(ID_A, ID_B), "bibtex");

    assertThat(batch.entries().get(0).warnings())
        .extracting(CitationWarning::code)
        .doesNotContain(CitationWarning.YEAR_UNKNOWN);
    assertThat(batch.entries().get(1).warnings())
        .extracting(CitationWarning::code)
        .contains(CitationWarning.YEAR_UNKNOWN);
  }

  @Test
  @DisplayName("合并重定向后的重复目标只写一条：否则文件里会出现两份指向同一篇文献的引用")
  void deduplicatesResolvedTarget() {
    // 「原记录」重定向到幸存实体，两条 id 解析成同一条成果。
    when(catalog.requirePublicTarget("ach_merged"))
        .thenReturn(
            new PublicCatalogQueryService.PublicTarget(achievement(ID_A, 2023), "ach_merged"));
    givenPublic(ID_A);

    CitationBatch batch = service.exportBatch(List.of("ach_merged", ID_A), "bibtex");

    assertThat(batch.requested()).isEqualTo(2);
    assertThat(batch.entries()).hasSize(1);
    // 条目里记的是解析后的实体（原记录引用了也是引用那一篇），不是读者勾的那个 id。
    assertThat(batch.entries().get(0).achievementId()).isEqualTo(ID_A);
    assertThat(batch.skipped()).hasSize(1);
    // 记成重复项的必须是被合并掉的旧记录，而不是幸存记录——「那条活着的被跳过了」读起来是错的。
    assertThat(batch.skipped().get(0).achievementId()).isEqualTo("ach_merged");
    assertThat(batch.skipped().get(0).reason()).isEqualTo(CitationBatch.REASON_DUPLICATE_TARGET);
    // 这条与「不可公开」不是同一回事，说明必须能分别读出来。
    assertThat(batch.skipped().get(0).message()).contains("合并重定向");
  }

  // ------------------------------------------------------------------ 夹具

  private void givenPublic(String id) {
    givenPublic(id, 2023);
  }

  private void givenPublic(String id, Integer year) {
    when(catalog.requirePublicTarget(id))
        .thenReturn(new PublicCatalogQueryService.PublicTarget(achievement(id, year), null));
  }

  private static String errorCodeOf(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    try {
      call.call();
    } catch (BusinessException ex) {
      return ex.errorCode().name();
    } catch (Throwable other) {
      throw new AssertionError("期望抛出 BusinessException，实际：" + other, other);
    }
    throw new AssertionError("期望抛出 BusinessException，实际正常返回");
  }

  private static Achievement achievement(String id, Integer year) {
    return new Achievement(
        id,
        AchievementType.PAPER,
        "深度学习在医学影像中的应用",
        "深度学习在医学影像中的应用",
        DataNature.REAL,
        Visibility.PUBLIC,
        EffectiveStatus.EFFECTIVE,
        PublicationYear.of(year),
        null,
        1L,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static Scholar scholar(String name) {
    return new Scholar(
        "s1",
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

  private static Contribution contribution(String scholarId, Integer order) {
    return new Contribution(
        "con_" + scholarId,
        scholarId,
        ID_A,
        "AUTHOR",
        order,
        RelationState.AUTO,
        null,
        1L,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }
}
