package com.cognitanexus.ingestion.collect.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cognitanexus.ingestion.collect.adapter.SourceAdapter;
import com.cognitanexus.ingestion.collect.adapter.SourceAdapterRegistry;
import com.cognitanexus.ingestion.collect.domain.AdapterKind;
import com.cognitanexus.ingestion.collect.domain.CollectModels.CollectJob;
import com.cognitanexus.ingestion.collect.domain.CollectModels.FetchPage;
import com.cognitanexus.ingestion.collect.domain.CollectModels.SourceRunConfig;
import com.cognitanexus.ingestion.collect.domain.JobStatus;
import com.cognitanexus.ingestion.platform.client.CoreInternalClient;
import com.cognitanexus.ingestion.platform.persistence.JsonCodec;
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
 * 采集执行的失败语义。
 *
 * <p>依据：架构 V0.2 §6.1、§6.2、AC-IMPL-FR14-*。
 *
 * <p>这一层真正会出错的地方不是抛异常，而是<b>失败的形状</b>：抓取失败不能被表示成「抓到 0 条并
 * 推进游标」（那段区间会被永久跳过），也不能被表示成「每 2 秒重试一次直到有人发现」（持续故障被
 * 放大成持续负载）。因此本测试盯住三件事：
 *
 * <ol>
 *   <li>抓取失败不动检查点，且写入退避时刻；
 *   <li>退避时长随连续失败次数增长，并由运行参数封顶；
 *   <li>抓到一页即复位失败计数——退避是「上游一直不好」的度量，不是累计惩罚。
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class CollectExecutionServiceTest {

  @Mock CollectRepositories.JobRepository jobs;
  @Mock CollectRepositories.StagedRepository staged;
  @Mock CollectRepositories.HandoffBatchRepository batches;
  @Mock CollectRepositories.SourceConfigRepository configs;
  @Mock SourceAdapterRegistry adapters;
  @Mock CoreInternalClient coreClient;
  @Mock CollectUnitOfWork unitOfWork;
  @Mock JsonCodec json;
  @Mock SourceAdapter adapter;

  private CollectExecutionService service;

  private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

  @BeforeEach
  void setUp() {
    service =
        new CollectExecutionService(
            jobs,
            staged,
            batches,
            configs,
            adapters,
            coreClient,
            new CollectProperties(100, 2_097_152, 40, 120, false, 2000, 8, 300),
            unitOfWork,
            json);
  }

  private static SourceRunConfig config() {
    return new SourceRunConfig(
        "src_1",
        "crossref",
        "Crossref",
        AdapterKind.CROSSREF,
        "https://api.crossref.org",
        "graph",
        20,
        List.of("title", "doi"),
        "v1",
        "APPROVED",
        null,
        60,
        true,
        1);
  }

  private static CollectJob job(int consecutiveFailures, Instant nextAttemptAt) {
    return new CollectJob(
        "job_1",
        "src_1",
        "graph",
        JobStatus.RUNNING,
        "0",
        null,
        0,
        0,
        0,
        0,
        null,
        null,
        null,
        "acc_admin",
        consecutiveFailures,
        nextAttemptAt);
  }

  private void givenRunnable(CollectJob job) {
    givenClaimedJob(job);
    givenNoPendingHandoff();
    // 只有在真的会走到抓取那一步的用例里才打这个桩：被停用来源的用例不会抓取，
    // 在那里打桩只会得到一个「多余打桩」而不是一条有用的失败信息。
    when(adapters.require(any())).thenReturn(adapter);
  }

  /** 任务存在、租约可领、来源在 core 侧启用：跑到「抓取」之前的全部前置条件。 */
  private void givenClaimedJob(CollectJob job) {
    when(jobs.findById("job_1")).thenReturn(Optional.of(job));
    when(jobs.claimLease(eq("job_1"), anyString(), anyInt())).thenReturn(true);
    when(configs.findById("src_1")).thenReturn(Optional.of(config()));
    givenSourceEnabled(true);
  }

  private void givenSourceEnabled(boolean enabled) {
    when(coreClient.sourceConfig("crossref"))
        .thenReturn(
            Optional.of(
                new CoreInternalClient.SourceRunConfigView(
                    "src_1",
                    "crossref",
                    "Crossref",
                    enabled,
                    "OPEN",
                    null,
                    1,
                    List.of("title", "doi"))));
  }

  /** 没有遗留待交接批次与记录：把注意力留给抓取分支。 */
  private void givenNoPendingHandoff() {
    when(batches.findNextPending("job_1")).thenReturn(Optional.empty());
    when(staged.listPendingForHandoff(eq("job_1"), anyInt())).thenReturn(List.of());
  }

  @Test
  @DisplayName("抓取失败：写错误、排退避、不动检查点，也不表现为「抓到 0 条」")
  void fetchFailureSchedulesBackoffAndKeepsCheckpoint() {
    givenRunnable(job(0, null));
    when(adapter.fetch(any(), any(), any())).thenThrow(new IllegalStateException("上游返回 400"));

    CollectExecutionService.RunOutcome outcome = service.runOnce("job_1", "worker-1");

    assertThat(outcome.fetchFailed()).isTrue();
    assertThat(outcome.staged()).isZero();
    // 第一次失败退避 1 秒：时长按「连续第几次」算，不从最大值开始。
    verify(unitOfWork).recordFetchFailure("job_1", "上游返回 400", 1);
    // 失败绝不能前移检查点：把失败表达成「抓到 0 条并推进游标」会让这段区间被永久跳过。
    verify(jobs, never()).advanceCheckpoint(anyString(), anyString(), anyInt());
    verify(unitOfWork, never()).stagePage(any(), any(), any(), anyInt(), any());
  }

  @Test
  @DisplayName("退避随连续失败次数增长：1、2、4、8 秒，而不是一直 1 秒")
  void backoffGrowsWithConsecutiveFailures() {
    when(adapter.fetch(any(), any(), any())).thenThrow(new IllegalStateException("上游返回 400"));

    for (int failures = 0; failures < 4; failures++) {
      givenRunnable(job(failures, null));
      service.runOnce("job_1", "worker-1");
    }

    verify(unitOfWork).recordFetchFailure("job_1", "上游返回 400", 1);
    verify(unitOfWork).recordFetchFailure("job_1", "上游返回 400", 2);
    verify(unitOfWork).recordFetchFailure("job_1", "上游返回 400", 4);
    verify(unitOfWork).recordFetchFailure("job_1", "上游返回 400", 8);
  }

  @Test
  @DisplayName("退避上限由运行参数决定，不被位移上限悄悄压成固定值")
  void backoffCappedByConfiguration() {
    when(adapter.fetch(any(), any(), any())).thenThrow(new IllegalStateException("上游返回 400"));

    // 第 40 次连续失败：1 << 39 远超任何配置，必须回落到 maxBackoffSeconds。
    givenRunnable(job(39, null));
    service.runOnce("job_1", "worker-1");

    verify(unitOfWork).recordFetchFailure("job_1", "上游返回 400", 300);
  }

  @Test
  @DisplayName("一次有界连推在抓取失败后立刻停下：不在同一个调用里把刚排好的退避作废")
  void boundedRunStopsRightAfterFetchFailure() {
    givenRunnable(job(0, null));
    when(adapter.fetch(any(), any(), any())).thenThrow(new IllegalStateException("上游超时"));

    CollectExecutionService.BatchOutcome outcome = service.runBounded("job_1", "worker-1", 40);

    assertThat(outcome.rounds()).isEqualTo(1);
    assertThat(outcome.fetchFailed()).isTrue();
    assertThat(outcome.staged()).isZero();
    assertThat(outcome.handedOff()).isZero();
    // 只尝试了一次抓取。此前 handedOff 不为 0 时还会在同一个调用里再抓一次。
    verify(adapter).fetch(any(), any(), any());
    verify(unitOfWork).recordFetchFailure("job_1", "上游超时", 1);
  }

  @Test
  @DisplayName("抓到一页即复位失败计数与退避窗口：退避衡量的是「上游一直不好」，不是累计惩罚")
  void successfulFetchClearsFailures() {
    givenRunnable(job(5, NOW.plusSeconds(300)));
    when(adapter.fetch(any(), any(), any())).thenReturn(new FetchPage(List.of(), null, null));
    when(unitOfWork.stagePage(any(), any(), any(), anyInt(), any()))
        .thenReturn(new CollectUnitOfWork.StageResult(0, 0));

    CollectExecutionService.RunOutcome outcome = service.runOnce("job_1", "worker-1");

    assertThat(outcome.fetchFailed()).isFalse();
    verify(unitOfWork).clearFetchFailures("job_1");
    verify(unitOfWork, never()).recordFetchFailure(anyString(), anyString(), anyInt());
  }

  @Test
  @DisplayName("本来就健康的任务抓取成功后不写一次无意义的复位：每次抓取都 UPDATE 一行是白开销")
  void healthyFetchDoesNotTouchFailureState() {
    givenRunnable(job(0, null));
    when(adapter.fetch(any(), any(), any())).thenReturn(new FetchPage(List.of(), null, null));
    when(unitOfWork.stagePage(any(), any(), any(), anyInt(), any()))
        .thenReturn(new CollectUnitOfWork.StageResult(0, 0));

    service.runOnce("job_1", "worker-1");

    verify(unitOfWork, never()).clearFetchFailures(anyString());
  }

  @Test
  @DisplayName("暂停与已结束的任务直接跳过：不领租约、不碰来源")
  void pausedJobSkipped() {
    CollectJob paused =
        new CollectJob(
            "job_1",
            "src_1",
            "graph",
            JobStatus.PAUSED,
            "0",
            null,
            0,
            0,
            0,
            0,
            null,
            null,
            null,
            "acc_admin",
            3,
            NOW.plusSeconds(60));
    when(jobs.findById("job_1")).thenReturn(Optional.of(paused));

    CollectExecutionService.RunOutcome outcome = service.runOnce("job_1", "worker-1");

    assertThat(outcome.fetchFailed()).isFalse();
    assertThat(outcome.message()).contains("本轮跳过");
    verify(jobs, never()).claimLease(anyString(), anyString(), anyInt());
    verifyNoInteractions(unitOfWork);
  }

  @Test
  @DisplayName("来源在 core 侧被停用时任务转暂停，不再抓取")
  void disabledSourcePausesJob() {
    givenClaimedJob(job(0, null));
    givenSourceEnabled(false);

    CollectExecutionService.RunOutcome outcome = service.runOnce("job_1", "worker-1");

    assertThat(outcome.message()).contains("已暂停");
    verify(unitOfWork).updateJobStatus(eq("job_1"), eq(JobStatus.PAUSED), anyString());
    verify(adapter, never()).fetch(any(), any(), any());
  }

  @Test
  @DisplayName("执行器自己不判断退避：窗口由查询侧过滤，手动执行因此仍能立刻探测上游是否恢复")
  void executorDoesNotSkipBackoffWindowItself() {
    // 任务仍处在 300 秒的退避窗口内，但执行器只认「拿到就做」——窗口是查询侧的事。
    // 若执行器自己再判一次退避，管理端「立即执行」会在最需要探测的时候失效。
    givenRunnable(job(3, NOW.plusSeconds(300)));
    when(adapter.fetch(any(), any(), any())).thenThrow(new IllegalStateException("上游仍不可达"));

    CollectExecutionService.RunOutcome outcome = service.runOnce("job_1", "worker-1");

    assertThat(outcome.fetchFailed()).isTrue();
    verify(adapter).fetch(any(), any(), any());
    verify(unitOfWork).recordFetchFailure("job_1", "上游仍不可达", 8);
  }
}
