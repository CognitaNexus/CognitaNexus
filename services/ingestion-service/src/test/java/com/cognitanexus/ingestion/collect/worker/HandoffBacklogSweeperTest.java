package com.cognitanexus.ingestion.collect.worker;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.ingestion.collect.application.CollectExecutionService;
import com.cognitanexus.ingestion.collect.application.CollectProperties;
import com.cognitanexus.ingestion.collect.application.CollectRepositories;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class HandoffBacklogSweeperTest {

  private final CollectRepositories.JobRepository jobs =
      Mockito.mock(CollectRepositories.JobRepository.class);
  private final CollectExecutionService execution = Mockito.mock(CollectExecutionService.class);

  private static CollectProperties props(boolean workerEnabled) {
    return new CollectProperties(100, 2_097_152, 40, 120, workerEnabled, 2000, 8, 300, 43_200, 20);
  }

  @Test
  @DisplayName("关闭执行器时连查询都不做")
  void disabledDoesNotEvenQuery() {
    HandoffBacklogSweeper sweeper = new HandoffBacklogSweeper(jobs, execution, props(false));

    sweeper.tick();

    verify(jobs, never()).listPausedJobsWithDueBatch(anyInt());
  }

  @Test
  @DisplayName("没有遗留批次时不做任何交付")
  void emptyBacklogDoesNothing() {
    when(jobs.listPausedJobsWithDueBatch(anyInt())).thenReturn(List.of());
    HandoffBacklogSweeper sweeper = new HandoffBacklogSweeper(jobs, execution, props(true));

    sweeper.tick();

    verify(execution, never()).drainBacklog(anyString(), anyString());
  }

  @Test
  @DisplayName("有遗留批次时按任务补交接，且租约属主带 sweeper 前缀")
  void drainsEachCandidate() {
    when(jobs.listPausedJobsWithDueBatch(1)).thenReturn(List.of("job_a"));
    when(execution.drainBacklog(eq("job_a"), anyString())).thenReturn(50);
    HandoffBacklogSweeper sweeper = new HandoffBacklogSweeper(jobs, execution, props(true));

    sweeper.tick();

    verify(execution).drainBacklog(eq("job_a"), Mockito.startsWith("sweeper-"));
  }

  @Test
  @DisplayName("查询失败不能抛出，否则调度线程会停掉")
  void queryFailureIsSwallowed() {
    when(jobs.listPausedJobsWithDueBatch(anyInt())).thenThrow(new IllegalStateException("库不可达"));
    HandoffBacklogSweeper sweeper = new HandoffBacklogSweeper(jobs, execution, props(true));

    assertThatCode(sweeper::tick).doesNotThrowAnyException();
    verify(execution, never()).drainBacklog(anyString(), anyString());
  }

  @Test
  @DisplayName("单个任务交付失败不能影响其它任务，也不能抛出")
  void drainFailureIsSwallowed() {
    when(jobs.listPausedJobsWithDueBatch(anyInt())).thenReturn(List.of("job_a"));
    when(execution.drainBacklog(eq("job_a"), any()))
        .thenThrow(new IllegalStateException("core 500"));
    HandoffBacklogSweeper sweeper = new HandoffBacklogSweeper(jobs, execution, props(true));

    assertThatCode(sweeper::tick).doesNotThrowAnyException();
  }
}
