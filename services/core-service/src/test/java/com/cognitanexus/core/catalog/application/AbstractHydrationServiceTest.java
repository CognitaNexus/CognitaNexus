package com.cognitanexus.core.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository.PendingAbstract;
import com.cognitanexus.core.catalog.application.CatalogRepositories.FieldProvenanceRepository;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.EffectiveStatus;
import com.cognitanexus.core.catalog.domain.PublicationYear;
import com.cognitanexus.sharedkernel.AchievementType;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.DataNature;
import com.cognitanexus.sharedkernel.Visibility;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 摘要补水回写的四条口径。
 *
 * <p>这里守的都是「不报错但会悄悄错」的情形：
 *
 * <ol>
 *   <li>来源没有摘要时只记尝试时刻、不写空文本——写了空串，详情页会出现一段空白摘要；
 *   <li>写入被闸门挡下时仍要标记「试过」，否则受人工修订保护的那批会每轮被重新取出；
 *   <li>超长摘要整条拒收，不截断——截断会把来源值改成我们编的东西，而检索仍能命中；
 *   <li>只有真正写进库才外发事件，否则公开检索永远停在旧摘要上，而它看起来仍然「正常工作」。
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AbstractHydrationServiceTest {

  private static final String ACH_ID = "ach_abstract_1";

  @Mock private AchievementRepository achievements;
  @Mock private FieldProvenanceRepository provenance;
  @Mock private CatalogChangePublisher catalogChanges;

  private AbstractHydrationService service;

  @BeforeEach
  void setUp() {
    service = new AbstractHydrationService(achievements, provenance, catalogChanges);
  }

  private static Achievement achievement() {
    return new Achievement(
        ACH_ID,
        AchievementType.PAPER,
        "Graph learning for molecules",
        "graph learning for molecules",
        DataNature.REAL,
        Visibility.PUBLIC,
        EffectiveStatus.EFFECTIVE,
        PublicationYear.of(2024),
        null,
        7L,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  @Test
  @DisplayName("来源没有摘要：只记尝试时刻，不写文本、不发事件")
  void missingAbstractOnlyMarksAttempt() {
    when(achievements.markAbstractChecked(ACH_ID)).thenReturn(1);

    AbstractHydrationService.FillReceipt receipt =
        service.recordAbstracts(
            List.of(new AbstractHydrationService.Entry(ACH_ID, "  ")), "OPENALEX");

    assertThat(receipt.notFound()).isEqualTo(1);
    assertThat(receipt.filled()).isZero();
    verify(achievements, never()).fillPaperAbstract(anyString(), anyString());
    verify(provenance, never())
        .upsertSource(anyString(), anyString(), anyString(), anyString(), any());
    verify(catalogChanges, never()).publish(anyString(), any(), anyString(), any(), anyLong());
  }

  @Test
  @DisplayName("写入成功后记溯源并外发目录变更，公开检索才会看到新摘要")
  void filledAbstractIsPublished() {
    when(achievements.fillPaperAbstract(ACH_ID, "这是摘要")).thenReturn(1);
    when(achievements.findById(ACH_ID)).thenReturn(Optional.of(achievement()));

    AbstractHydrationService.FillReceipt receipt =
        service.recordAbstracts(
            List.of(new AbstractHydrationService.Entry(ACH_ID, " 这是摘要 ")), "OPENALEX");

    assertThat(receipt.filled()).isEqualTo(1);
    // 文本必须 strip 后再写：前后空白会让「同一段摘要」在库里出现两种写法
    verify(achievements).fillPaperAbstract(ACH_ID, "这是摘要");
    verify(provenance).upsertSource("ACHIEVEMENT", ACH_ID, "abstractText", "这是摘要", null);
    verify(catalogChanges)
        .publish(eq(ACH_ID), eq(AchievementType.PAPER), anyString(), any(), eq(7L));
  }

  @Test
  @DisplayName("闸门挡下（已有摘要／人工修订／已合并）时仍标记已尝试，避免每轮重复取")
  void rejectedWriteStillMarksAttempt() {
    when(achievements.fillPaperAbstract(ACH_ID, "这是摘要")).thenReturn(0);

    AbstractHydrationService.FillReceipt receipt =
        service.recordAbstracts(
            List.of(new AbstractHydrationService.Entry(ACH_ID, "这是摘要")), "OPENALEX");

    assertThat(receipt.skipped()).isEqualTo(1);
    assertThat(receipt.filled()).isZero();
    verify(achievements).markAbstractChecked(ACH_ID);
    verify(catalogChanges, never()).publish(anyString(), any(), anyString(), any(), anyLong());
  }

  @Test
  @DisplayName("超长摘要整条拒收，不截断也不标记，下一轮仍可重试")
  void overlongAbstractIsRejectedWithoutTruncation() {
    String tooLong = "字".repeat(40_001);

    AbstractHydrationService.FillReceipt receipt =
        service.recordAbstracts(
            List.of(new AbstractHydrationService.Entry(ACH_ID, tooLong)), "OPENALEX");

    assertThat(receipt.tooLong()).isEqualTo(1);
    assertThat(receipt.filled()).isZero();
    verify(achievements, never()).fillPaperAbstract(anyString(), anyString());
    verify(achievements, never()).markAbstractChecked(anyString());
  }

  @Test
  @DisplayName("空批次与超量批次都明确拒绝，而不是静默返回 0 条")
  void rejectsEmptyAndOversizedBatches() {
    assertThatThrownBy(() -> service.recordAbstracts(List.of(), "OPENALEX"))
        .isInstanceOf(BusinessException.class);
    List<AbstractHydrationService.Entry> tooMany =
        java.util.stream.IntStream.range(0, 201)
            .mapToObj(i -> new AbstractHydrationService.Entry("ach_" + i, "摘要"))
            .toList();
    assertThatThrownBy(() -> service.recordAbstracts(tooMany, "OPENALEX"))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("待补清单回显总数，且题名关键字按投影规则归一化后再查")
  void pendingPageReportsRemainingAndNormalizesSearch() {
    when(achievements.listPendingAbstracts(2, "graph learning"))
        .thenReturn(List.of(new PendingAbstract("ach_1", "10.1/a", "A")));
    when(achievements.countPendingAbstracts("graph learning")).thenReturn(37L);

    AbstractHydrationService.PendingPage page = service.pendingAbstracts(2, "  Graph   Learning ");

    assertThat(page.remaining()).isEqualTo(37L);
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0)).containsEntry("doi", "10.1/a");
  }
}
