package com.cognitanexus.core.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.catalog.application.CatalogRepositories.CitationRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.CitationRepository.CitedWork;
import com.cognitanexus.core.catalog.application.CatalogRepositories.CitationRepository.Observation;
import com.cognitanexus.core.catalog.application.CatalogRepositories.CitationRepository.ResolutionCounts;
import com.cognitanexus.core.catalog.application.CatalogRepositories.IdentifierRegistryRepository;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.EffectiveStatus;
import com.cognitanexus.core.catalog.domain.IdentifierKind;
import com.cognitanexus.core.catalog.domain.PublicationYear;
import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.sharedkernel.AccountId;
import com.cognitanexus.sharedkernel.AchievementType;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.DataNature;
import com.cognitanexus.sharedkernel.ErrorCode;
import com.cognitanexus.sharedkernel.Visibility;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 引用网络的业务规则。
 *
 * <p>这里守四件最容易做错的事：
 *
 * <ol>
 *   <li><b>缺失不是 0</b>：没采过、来源查不到、采到了但零条引用，三种情况必须给三种说法；
 *   <li><b>站外引用不建边</b>：解析不到就只计数，绝不猜一条成果出来；
 *   <li><b>重采整体替换</b>：先删本篇旧条目再插新的，避免旧引用永远留在库里；
 *   <li><b>公开读复用同一套资格判定</b>：不能因为「引用网络是另一条路径」就绕过合并重定向与可见性。
 * </ol>
 */
class CitationNetworkServiceTest {

  private CitationRepository citations;
  private IdentifierRegistryRepository identifiers;
  private PublicCatalogQueryService publicCatalog;
  private AuditRecorder audit;
  private CitationNetworkService service;

  private final ActorContext admin =
      new ActorContext(
          new AccountId("acc_admin"), "cnadmin", "管理员", Set.of(RoleNames.PLATFORM_ADMIN), 1L);

  @BeforeEach
  void setUp() {
    citations = mock(CitationRepository.class);
    identifiers = mock(IdentifierRegistryRepository.class);
    publicCatalog = mock(PublicCatalogQueryService.class);
    audit = mock(AuditRecorder.class);
    service = new CitationNetworkService(citations, identifiers, publicCatalog, audit);
    lenient().when(citations.countPublicPapers()).thenReturn(100);
    lenient().when(citations.countObservedWorks()).thenReturn(10);
    lenient().when(citations.countNotFoundWorks()).thenReturn(2);
    // 默认：任何标识都查不到（各用例再按需覆盖）。必须放在 setUp 里：Mockito 的「后设先赢」意味着
    // 在用例里再写一个 any() 兜底桩会盖掉前面的精确桩，表现为「明明设了却查不到」。
    lenient()
        .when(identifiers.findEntityId(anyString(), any(), anyString(), anyString()))
        .thenReturn(Optional.empty());
    lenient()
        .when(
            identifiers.register(
                anyString(), anyString(), anyString(), any(), anyString(), anyString()))
        .thenReturn(true);
  }

  private Achievement paper(String id, String title) {
    return new Achievement(
        id,
        AchievementType.PAPER,
        title,
        title,
        DataNature.REAL,
        Visibility.PUBLIC,
        EffectiveStatus.EFFECTIVE,
        PublicationYear.of(2020),
        null,
        1L,
        Instant.now(),
        Instant.now());
  }

  private CitationNetworkService.CitationObservationInput observation(
      String doi, String workId, Integer total, List<String> references) {
    return new CitationNetworkService.CitationObservationInput(
        doi, "OPENALEX", workId, total, references, null, "2026-10-19T00:00:00Z");
  }

  // ---------------------------------------------------------------------------
  // 归一
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("标识归一：DOI 去前缀、作品 ID 去 URL 且小写，与导入侧同一规则")
  void normalizesIdentifiers() {
    assertThat(CitationNetworkService.normalizeDoi("https://doi.org/10.1038/S41591-018-0136-1"))
        .isEqualTo("10.1038/s41591-018-0136-1");
    assertThat(CitationNetworkService.normalizeOpenAlexId("https://openalex.org/W2741809807"))
        .isEqualTo("w2741809807");
    assertThat(CitationNetworkService.classifyReference("https://openalex.org/W123"))
        .isEqualTo("OPENALEX_ID");
    assertThat(CitationNetworkService.classifyReference("10.1000/xyz")).isEqualTo("DOI");
    // 形状不清的按 DOI 处理：宁可少一条边，也不把 DOI 当作品 ID 去查一个不存在的键
    assertThat(CitationNetworkService.classifyReference("Wabc")).isEqualTo("DOI");
  }

  // ---------------------------------------------------------------------------
  // 观测入库
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("空观测批次直接拒绝，不写任何东西")
  void rejectsEmptyBatch() {
    assertThatThrownBy(() -> service.recordObservations(List.of(), admin))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("观测列表为空");
    verify(citations, never()).saveObservation(any());
  }

  @Test
  @DisplayName("单批超过上限直接拒绝（避免超长事务）")
  void rejectsOversizedBatch() {
    List<CitationNetworkService.CitationObservationInput> inputs =
        java.util.stream.IntStream.range(0, 201)
            .mapToObj(i -> observation("10.1/" + i, null, 0, List.of()))
            .toList();
    assertThatThrownBy(() -> service.recordObservations(inputs, admin))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("单次观测条数上限");
  }

  @Test
  @DisplayName("DOI 在库内查不到：不写观测、不出引用，只在回执里点名")
  void unmatchedDoiIsReportedNotWritten() {
    when(identifiers.findEntityId(
            eq("ACHIEVEMENT"), eq(IdentifierKind.DOI), eq(""), eq("10.9/none")))
        .thenReturn(Optional.empty());

    var receipt =
        service.recordObservations(
            List.of(observation("10.9/none", "W1", 3, List.of("W2"))), admin);

    assertThat(receipt.matched()).isZero();
    assertThat(receipt.referencesWritten()).isZero();
    assertThat(receipt.unmatchedDois()).containsExactly("10.9/none");
    verify(citations, never()).saveObservation(any());
    verify(citations, never()).insertReference(any());
  }

  @Test
  @DisplayName("命中成果：先删旧条目再写观测与引用，站内/站外/自引分别计数")
  void recordsObservationAndClassifiesReferences() {
    when(identifiers.findEntityId(eq("ACHIEVEMENT"), eq(IdentifierKind.DOI), eq(""), eq("10.1/a")))
        .thenReturn(Optional.of("ach_a"));
    when(identifiers.findEntityId(
            eq("ACHIEVEMENT"), eq(IdentifierKind.OPENALEX_ID), eq(""), eq("w100")))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of("ach_a"));
    when(identifiers.findEntityId(
            eq("ACHIEVEMENT"), eq(IdentifierKind.OPENALEX_ID), eq(""), eq("w200")))
        .thenReturn(Optional.of("ach_b"));

    var receipt =
        service.recordObservations(
            List.of(
                observation(
                    "10.1/a", "https://openalex.org/W100", 3, List.of("W100", "W200", "W300"))),
            admin);

    assertThat(receipt.matched()).isEqualTo(1);
    assertThat(receipt.referencesWritten()).isEqualTo(3);
    assertThat(receipt.resolved()).isEqualTo(1);
    assertThat(receipt.external()).isEqualTo(1);
    assertThat(receipt.selfCitations()).isEqualTo(1);

    ArgumentCaptor<Observation> saved = ArgumentCaptor.forClass(Observation.class);
    verify(citations).saveObservation(saved.capture());
    assertThat(saved.getValue().providerWorkId()).isEqualTo("w100");
    assertThat(saved.getValue().referencesListed()).isEqualTo(3);
    assertThat(saved.getValue().outcome()).isEqualTo("OBSERVED");
    // 重采必须整体替换：先删后插，否则上一次采到的引用会永久残留
    verify(citations).deleteReferencesOf("ach_a");
    verify(citations, times(3)).insertReference(any());
  }

  @Test
  @DisplayName("同一篇文献在一条观测里重复出现只写一条边")
  void dedupesReferencesWithinOneWork() {
    when(identifiers.findEntityId(eq("ACHIEVEMENT"), eq(IdentifierKind.DOI), eq(""), eq("10.1/a")))
        .thenReturn(Optional.of("ach_a"));

    var receipt =
        service.recordObservations(
            List.of(observation("10.1/a", "W1", 2, List.of("W9", "https://openalex.org/W9"))),
            admin);

    assertThat(receipt.referencesWritten()).isEqualTo(1);
    verify(citations, times(1)).insertReference(any());
  }

  @Test
  @DisplayName("来源查不到这一篇：记 NOT_FOUND 且不写任何引用条目")
  void notFoundObservationIsDistinctFromEmptyList() {
    when(identifiers.findEntityId(eq("ACHIEVEMENT"), eq(IdentifierKind.DOI), eq(""), eq("10.1/a")))
        .thenReturn(Optional.of("ach_a"));

    var receipt =
        service.recordObservations(List.of(observation("10.1/a", null, null, List.of())), admin);

    assertThat(receipt.notFoundWorks()).isEqualTo(1);
    ArgumentCaptor<Observation> saved = ArgumentCaptor.forClass(Observation.class);
    verify(citations).saveObservation(saved.capture());
    assertThat(saved.getValue().outcome()).isEqualTo("NOT_FOUND");
    verify(citations, never()).insertReference(any());
  }

  @Test
  @DisplayName("采到了但列表为空：是 OBSERVED 而不是 NOT_FOUND（这是事实，不是缺口）")
  void emptyListIsObservedNotMissing() {
    when(identifiers.findEntityId(eq("ACHIEVEMENT"), eq(IdentifierKind.DOI), eq(""), eq("10.1/a")))
        .thenReturn(Optional.of("ach_a"));

    service.recordObservations(List.of(observation("10.1/a", "W1", 0, List.of())), admin);

    ArgumentCaptor<Observation> saved = ArgumentCaptor.forClass(Observation.class);
    verify(citations).saveObservation(saved.capture());
    assertThat(saved.getValue().outcome()).isEqualTo("OBSERVED");
    assertThat(saved.getValue().referencesListed()).isZero();
  }

  @Test
  @DisplayName("作品 ID 已被别的成果占用：不抛异常，进冲突清单")
  void identifierConflictIsReportedNotThrown() {
    when(identifiers.findEntityId(eq("ACHIEVEMENT"), eq(IdentifierKind.DOI), eq(""), eq("10.1/a")))
        .thenReturn(Optional.of("ach_a"));
    when(identifiers.findEntityId(
            eq("ACHIEVEMENT"), eq(IdentifierKind.OPENALEX_ID), eq(""), eq("w1")))
        .thenReturn(Optional.of("ach_other"));

    var receipt =
        service.recordObservations(List.of(observation("10.1/a", "W1", 1, List.of("W2"))), admin);

    assertThat(receipt.identifierConflicts()).hasSize(1);
    assertThat(receipt.identifierConflicts().get(0)).contains("w1");
    verify(identifiers, never())
        .register(anyString(), anyString(), anyString(), any(), anyString(), anyString());
  }

  // ---------------------------------------------------------------------------
  // 重新解析
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("重新解析把库内统计原样透传，并带上剩余可解析键数")
  void resolvePendingPassesThroughBatchCounts() {
    when(citations.resolveBatch(anyInt(), any()))
        .thenReturn(new CitationRepository.ResolveBatch(2, 1, 2));
    when(citations.countUnresolvedKeys()).thenReturn(17);

    var result = service.resolvePending(100, admin);

    // 三个数字各自独立：扫了多少键、实际升级了多少键、连带点亮了多少行边。
    // 合并成一个「处理了 N 条」会把「一个键带 200 行边」和「200 个键各 1 行」说成同一件事。
    assertThat(result.keysScanned()).isEqualTo(2);
    assertThat(result.keysResolved()).isEqualTo(1);
    assertThat(result.rowsResolved()).isEqualTo(2);
    assertThat(result.keysRemaining()).isEqualTo(17);
  }

  @Test
  @DisplayName("重新解析的扫描上限被夹逼，不能一次扫全表")
  void resolvePendingClampsLimit() {
    when(citations.resolveBatch(anyInt(), any()))
        .thenReturn(new CitationRepository.ResolveBatch(0, 0, 0));
    service.resolvePending(999_999, admin);
    verify(citations).resolveBatch(eq(5000), any());
  }

  // ---------------------------------------------------------------------------
  // 公开读取
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("没采过：不显示任何引用条目，并明说「未采集不等于没有引用」")
  void unobservedWorkSaysUnknownNotZero() {
    when(publicCatalog.requirePublicTarget("ach_a"))
        .thenReturn(new PublicCatalogQueryService.PublicTarget(paper("ach_a", "题名"), null));
    when(citations.findObservation("ach_a")).thenReturn(Optional.empty());
    when(citations.listCitedBy("ach_a")).thenReturn(List.of());

    Map<String, Object> view = service.publicNetwork("ach_a");

    @SuppressWarnings("unchecked")
    Map<String, Object> coverage = (Map<String, Object>) view.get("coverage");
    assertThat(coverage.get("observed")).isEqualTo(false);
    assertThat(coverage.get("outcome")).isEqualTo("NONE");
    @SuppressWarnings("unchecked")
    Map<String, Object> references = (Map<String, Object>) view.get("references");
    assertThat(references.get("count")).isEqualTo(0);
    assertThat((String) references.get("note")).contains("不表示本篇没有引用任何文献");
  }

  @Test
  @DisplayName("采过：明细条数与计数来自同一口径，且披露列表差额")
  void observedWorkReportsCountsAndGap() {
    when(publicCatalog.requirePublicTarget("ach_a"))
        .thenReturn(new PublicCatalogQueryService.PublicTarget(paper("ach_a", "题名"), null));
    when(citations.findObservation("ach_a"))
        .thenReturn(
            Optional.of(
                new Observation("ach_a", "OPENALEX", "w1", 5, 3, "OBSERVED", Instant.now())));
    when(citations.countReferencesOf("ach_a")).thenReturn(new ResolutionCounts(1, 2, 0));
    when(citations.listResolvedReferences("ach_a"))
        .thenReturn(
            List.of(
                new CitedWork("ach_b", "被引论文", AchievementType.PAPER, 2019, true, "某刊", "10.1/b")));
    when(citations.listCitedBy("ach_a")).thenReturn(List.of());

    Map<String, Object> view = service.publicNetwork("ach_a");

    @SuppressWarnings("unchecked")
    Map<String, Object> coverage = (Map<String, Object>) view.get("coverage");
    assertThat(coverage.get("referencesTotal")).isEqualTo(5);
    assertThat(coverage.get("referencesListed")).isEqualTo(3);
    assertThat(coverage.get("listingGap")).isEqualTo(2);
    @SuppressWarnings("unchecked")
    Map<String, Object> references = (Map<String, Object>) view.get("references");
    assertThat(references.get("count")).isEqualTo(1);
    assertThat((String) references.get("note")).contains("1 篇收录于本平台").contains("2 篇为平台外文献");
  }

  @Test
  @DisplayName("来源查不到该成果：页面上说「取不到」，不说「没有引用」")
  void notFoundObservationIsSurfacedAsGap() {
    when(publicCatalog.requirePublicTarget("ach_a"))
        .thenReturn(new PublicCatalogQueryService.PublicTarget(paper("ach_a", "题名"), null));
    when(citations.findObservation("ach_a"))
        .thenReturn(
            Optional.of(
                new Observation("ach_a", "OPENALEX", null, null, 0, "NOT_FOUND", Instant.now())));
    when(citations.listCitedBy("ach_a")).thenReturn(List.of());

    Map<String, Object> view = service.publicNetwork("ach_a");

    @SuppressWarnings("unchecked")
    Map<String, Object> coverage = (Map<String, Object>) view.get("coverage");
    assertThat(coverage.get("outcome")).isEqualTo("NOT_FOUND");
    @SuppressWarnings("unchecked")
    Map<String, Object> references = (Map<String, Object>) view.get("references");
    assertThat((String) references.get("note")).contains("取不到");
  }

  @Test
  @DisplayName("公开资格判定被复用：不可见成果把 404 原样抛出去，不返回任何关系")
  void reusesPublicEligibilityCheck() {
    when(publicCatalog.requirePublicTarget("ach_hidden"))
        .thenThrow(BusinessException.notFound("成果不存在或尚不可见"));

    assertThatThrownBy(() -> service.publicNetwork("ach_hidden"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    verify(citations, never()).listCitedBy(anyString());
  }

  @Test
  @DisplayName("覆盖率：分母为 0 时给 null，不给 0.00（否则读成「覆盖率为零」）")
  void coveragePercentIsNullWhenNoPapers() {
    when(citations.countPublicPapers()).thenReturn(0);
    Map<String, Object> stats = service.stats(null, null);
    assertThat(stats.get("coveragePercent")).isNull();
    assertThat(stats.get("observedWorks")).isEqualTo(10);
    assertThat(stats.get("notFoundWorks")).isEqualTo(2);
  }
}
