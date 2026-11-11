package com.cognitanexus.ingestion.collect.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.ingestion.collect.adapter.OpenAlexAdapter;
import com.cognitanexus.ingestion.collect.adapter.SourceAdapterRegistry;
import com.cognitanexus.ingestion.collect.domain.AdapterKind;
import com.cognitanexus.ingestion.collect.domain.CollectModels.CollectedRecord;
import com.cognitanexus.ingestion.collect.domain.CollectModels.SourceRunConfig;
import com.cognitanexus.ingestion.platform.client.CoreInternalClient;
import com.cognitanexus.sharedkernel.BusinessException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 引文反向回填的边界行为。
 *
 * <p>这个类在真跑里出过三个问题，都不是编译期能发现的，所以每条测试对应其中一种：
 *
 * <ul>
 *   <li>只收节点不点边——回执上是成功，引用网络一条新边都没有；
 *   <li>单批开大——core 逐条比对标题太慢，整批超时回滚，错误却报成「依赖不可用」；
 *   <li>解析失败被当成回填失败——节点其实已经进库，报失败会让人重跑，重复做一遍已生效的事。
 * </ul>
 */
class CitationBackfillServiceTest {

  private CoreInternalClient coreClient;
  private SourceRunConfigService sources;
  private SourceAdapterRegistry adapters;
  private OpenAlexAdapter openAlex;
  private CitationBackfillService service;

  @BeforeEach
  void setUp() {
    coreClient = mock(CoreInternalClient.class);
    sources = mock(SourceRunConfigService.class);
    adapters = mock(SourceAdapterRegistry.class);
    openAlex = mock(OpenAlexAdapter.class);
    when(adapters.require(AdapterKind.OPENALEX)).thenReturn(openAlex);
    when(sources.findByCode("openalex")).thenReturn(Optional.of(config("APPROVED", true)));
    service = new CitationBackfillService(coreClient, sources, adapters);
  }

  @Test
  @DisplayName("单批上限夹到 20：core 逐条比对标题太慢，开大必定超时回滚")
  void capsBatchAtTwenty() {
    when(coreClient.backfillCandidates(eq("DOI"), anyInt()))
        .thenReturn(new CoreInternalClient.BackfillCandidates(List.of(), 500, "DOI"));
    when(coreClient.resolveCitations(anyInt()))
        .thenReturn(new CoreInternalClient.ResolveReceipt(0, 0, 0, 0));

    service.run("openalex", "DOI", 200);

    // 不是 200 也不是别的数：20 是实测出来的（20 条约 7 秒，读超时约 20 秒）
    verify(coreClient).backfillCandidates("DOI", 20);
  }

  @Test
  @DisplayName("没有候选不等于没事可做：仍要触发一次重新解析，把积压的边点亮")
  void emptyCandidatesStillResolves() {
    when(coreClient.backfillCandidates(eq("DOI"), anyInt()))
        .thenReturn(new CoreInternalClient.BackfillCandidates(List.of(), 0, "DOI"));
    when(coreClient.resolveCitations(anyInt()))
        .thenReturn(new CoreInternalClient.ResolveReceipt(9, 4, 16850, 0));

    CitationBackfillService.BackfillOutcome outcome = service.run("openalex", "DOI", 10);

    verify(coreClient).resolveCitations(anyInt());
    // 这两个数字是「回填到底有没有用」的唯一证据：只报 accepted 会把「收了节点却没连边」说成成功
    assertThat(outcome.accepted()).isZero();
    assertThat(outcome.resolvedRows()).isEqualTo(16850);
    assertThat(outcome.unresolvedRemaining()).isZero();
  }

  @Test
  @DisplayName("解析失败不算回填失败：节点已入库，重跑只会重做一遍已生效的事")
  void resolveFailureIsReportedNotThrown() {
    when(coreClient.backfillCandidates(eq("DOI"), anyInt()))
        .thenReturn(
            new CoreInternalClient.BackfillCandidates(
                List.of(new CoreInternalClient.BackfillCandidate("DOI", "10.1/x", 3)), 10, "DOI"));
    when(openAlex.fetchByIdentifiers(any(), any(), any()))
        .thenReturn(List.of(record("rec-1", "10.1/x")));
    when(coreClient.handoff(any()))
        .thenReturn(
            new CoreInternalClient.HandoffResult(
                "b1", 1, 1, 0, 0, 0, 42L, Map.of("rec-1", "NEW"), null));
    when(coreClient.resolveCitations(anyInt())).thenThrow(new IllegalStateException("core 超时"));

    CitationBackfillService.BackfillOutcome outcome = service.run("openalex", "DOI", 10);

    assertThat(outcome.accepted()).isEqualTo(1);
    assertThat(outcome.resolvedRows()).isZero();
    assertThat(outcome.failures()).anyMatch(f -> f.contains("重新解析引用失败"));
  }

  @Test
  @DisplayName("解析回执原样透传：三个数字各自独立，不能合并成一个「处理了 N 条」")
  void passesResolveReceiptThrough() {
    when(coreClient.backfillCandidates(eq("DOI"), anyInt()))
        .thenReturn(
            new CoreInternalClient.BackfillCandidates(
                List.of(new CoreInternalClient.BackfillCandidate("DOI", "10.1/x", 3)), 10, "DOI"));
    when(openAlex.fetchByIdentifiers(any(), any(), any()))
        .thenReturn(List.of(record("rec-1", "10.1/x")));
    when(coreClient.handoff(any()))
        .thenReturn(
            new CoreInternalClient.HandoffResult(
                "b1", 1, 1, 0, 0, 0, 42L, Map.of("rec-1", "NEW"), null));
    when(coreClient.resolveCitations(anyInt()))
        .thenReturn(new CoreInternalClient.ResolveReceipt(2000, 37, 621, 0));

    CitationBackfillService.BackfillOutcome outcome = service.run("openalex", "DOI", 10);

    assertThat(outcome.resolvedRows()).isEqualTo(621);
    assertThat(outcome.unresolvedRemaining()).isZero();
    assertThat(outcome.remaining()).isEqualTo(10);
  }

  @Test
  @DisplayName("来源查不到的作品记进 missing，不能算作已补上")
  void missingIdentifiersAreNotCountedAsFetched() {
    when(coreClient.backfillCandidates(eq("DOI"), anyInt()))
        .thenReturn(
            new CoreInternalClient.BackfillCandidates(
                List.of(
                    new CoreInternalClient.BackfillCandidate("DOI", "10.1/x", 3),
                    new CoreInternalClient.BackfillCandidate("DOI", "10.1/gone", 2)),
                10,
                "DOI"));
    when(openAlex.fetchByIdentifiers(any(), any(), any()))
        .thenReturn(List.of(record("rec-1", "10.1/x")));
    when(coreClient.handoff(any()))
        .thenReturn(
            new CoreInternalClient.HandoffResult(
                "b1", 1, 1, 0, 0, 0, 42L, Map.of("rec-1", "NEW"), null));
    when(coreClient.resolveCitations(anyInt()))
        .thenReturn(new CoreInternalClient.ResolveReceipt(0, 0, 0, 0));

    CitationBackfillService.BackfillOutcome outcome = service.run("openalex", "DOI", 10);

    assertThat(outcome.requested()).isEqualTo(2);
    assertThat(outcome.fetched()).isEqualTo(1);
    assertThat(outcome.missing()).isEqualTo(1);
  }

  @Test
  @DisplayName("许可未批准：拒绝执行，且不向 core 取候选")
  void rejectsUnapprovedLicense() {
    when(sources.findByCode("openalex")).thenReturn(Optional.of(config("PENDING_REVIEW", true)));

    assertThatThrownBy(() -> service.run("openalex", "DOI", 10))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("许可");
    verify(coreClient, never()).backfillCandidates(any(), anyInt());
  }

  @Test
  @DisplayName("来源停用：拒绝执行，且不向 core 取候选")
  void rejectsDisabledSource() {
    when(sources.findByCode("openalex")).thenReturn(Optional.of(config("APPROVED", false)));

    assertThatThrownBy(() -> service.run("openalex", "DOI", 10))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("停用");
    verify(coreClient, never()).backfillCandidates(any(), anyInt());
  }

  @Test
  @DisplayName("来源不是 OpenAlex：按标识精确取元数据只有它支持，不硬跑")
  void rejectsNonOpenAlexAdapter() {
    SourceRunConfig crossref = config("APPROVED", true, AdapterKind.CROSSREF);
    when(sources.findByCode("openalex")).thenReturn(Optional.of(crossref));

    assertThatThrownBy(() -> service.run("openalex", "DOI", 10))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("OpenAlex");
    verify(coreClient, never()).backfillCandidates(any(), anyInt());
  }

  private static SourceRunConfig config(String licenseState, boolean enabled) {
    return config(licenseState, enabled, AdapterKind.OPENALEX);
  }

  private static SourceRunConfig config(String licenseState, boolean enabled, AdapterKind kind) {
    return new SourceRunConfig(
        "src_1",
        "openalex",
        "OpenAlex",
        kind,
        "https://api.openalex.org/works",
        null,
        100,
        List.of("title", "doi", "authors", "publicationYear"),
        "1",
        licenseState,
        "CC0",
        60,
        enabled,
        1,
        null,
        null,
        "不覆盖人工审核值",
        null);
  }

  private static CollectedRecord record(String sourceRecordId, String doi) {
    return new CollectedRecord(
        sourceRecordId,
        Map.of("kind", "PAPER", "title", "某篇论文", "doi", doi),
        com.cognitanexus.sharedkernel.DataNature.REAL,
        null,
        null,
        java.time.Instant.parse("2026-10-24T00:00:00Z"));
  }
}
