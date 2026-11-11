package com.cognitanexus.ingestion.collect.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.ingestion.collect.application.CitationHydrationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CitationHydrationWorkerTest {

  private final CitationHydrationService hydration = Mockito.mock(CitationHydrationService.class);

  private static CitationHydrationService.HydrationReport report(int fetched) {
    return new CitationHydrationService.HydrationReport(
        300, fetched, fetched, 1200, 30, 1170, 0, 3, "OPENALEX", 97, "已补到本次目标篇数 300", null);
  }

  @Test
  @DisplayName("关闭时一次都不调上游")
  void disabledDoesNothing() {
    CitationHydrationWorker worker =
        new CitationHydrationWorker(
            hydration, new CitationHydrationWorker.WorkerProperties(false, 300));

    worker.tick();

    verify(hydration, never()).hydrate(anyInt(), any(), any(), any());
  }

  @Test
  @DisplayName("按配置的篇数、固定走 OPENALEX 调用")
  void callsOpenAlexWithConfiguredBatch() {
    when(hydration.hydrate(anyInt(), any(), any(), any())).thenReturn(report(120));
    CitationHydrationWorker worker =
        new CitationHydrationWorker(
            hydration, new CitationHydrationWorker.WorkerProperties(true, 120));

    worker.tick();

    verify(hydration)
        .hydrate(eq(120), isNull(), isNull(), eq(CitationHydrationService.Provider.OPENALEX));
  }

  @Test
  @DisplayName("上游异常不能抛出，否则调度线程会停掉")
  void swallowsRuntimeException() {
    when(hydration.hydrate(anyInt(), any(), any(), any()))
        .thenThrow(new IllegalStateException("core 不可达"));
    CitationHydrationWorker worker =
        new CitationHydrationWorker(
            hydration, new CitationHydrationWorker.WorkerProperties(null, null));

    assertThatCode(worker::tick).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("配置默认值：默认开启、每轮 300 篇")
  void defaults() {
    CitationHydrationWorker.WorkerProperties defaults =
        new CitationHydrationWorker.WorkerProperties(null, null);

    assertThat(defaults.enabled()).isTrue();
    assertThat(defaults.worksPerTick()).isEqualTo(300);
  }

  @Test
  @DisplayName("非正数篇数退回默认值，避免配成 0 后永远空转")
  void nonPositiveBatchFallsBackToDefault() {
    assertThat(new CitationHydrationWorker.WorkerProperties(null, 0).worksPerTick()).isEqualTo(300);
    assertThat(new CitationHydrationWorker.WorkerProperties(null, -5).worksPerTick())
        .isEqualTo(300);
  }
}
