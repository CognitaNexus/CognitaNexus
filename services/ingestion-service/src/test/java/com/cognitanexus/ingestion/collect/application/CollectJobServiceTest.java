package com.cognitanexus.ingestion.collect.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.ingestion.collect.domain.CollectModels.CollectJob;
import com.cognitanexus.ingestion.collect.domain.JobStatus;
import com.cognitanexus.ingestion.platform.audit.IngestionAuditRecorder;
import com.cognitanexus.ingestion.platform.security.ActorContext;
import com.cognitanexus.sharedkernel.BusinessException;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 采集任务的停止与失败重试。
 *
 * <p>依据：SRS V1.1 §6.2（六状态与守卫）、AC-IMPL-FR08-02、NF04。
 *
 * <p>这两件事最容易做错的地方不是抛异常，而是<b>语义被抹平</b>：
 * <ul>
 *   <li>把「停止」做成第二个「暂停」——都能恢复，于是没人能真正结束一个任务；
 *   <li>把「重试」做成「新建一条任务」——检查点被丢掉，已经交接过的一整段会被重新抓一遍，
 *       上游配额白烧，且「失败点在哪」这个问题再次失去意义。
 * </ul>
 * 所以下面的断言盯的是状态迁移的守卫与检查点的保留，而不是方法返回值好不好看。
 */
@ExtendWith(MockitoExtension.class)
class CollectJobServiceTest {

  @Mock CollectRepositories.JobRepository jobs;
  @Mock CollectRepositories.StagedRepository staged;
  @Mock CollectRepositories.HandoffBatchRepository batches;
  @Mock CollectRepositories.SourceConfigRepository configs;
  @Mock CollectRepositories.FetchLogRepository logs;
  @Mock IngestionAuditRecorder audit;

  private CollectJobService service;

  private static final ActorContext ADMIN =
      new ActorContext("acc_admin", "admin", "管理员", Set.of("PLATFORM_ADMIN"), Set.of());

  @BeforeEach
  void setUp() {
    service = new CollectJobService(jobs, staged, batches, configs, logs, audit);
  }

  @Test
  @DisplayName("停止是终态：检查点与已交接计数原样保留，且之后没有恢复入口")
  void stopKeepsCheckpointAndCannotBeResumed() {
    CollectJob running = job(JobStatus.RUNNING, "cp-42", 120, 0);
    CollectJob stopped = job(JobStatus.STOPPED, "cp-42", 120, 0);
    // 第一次 findById 是服务内部的 require（看到 RUNNING），第二次是返回前重读（看到 STOPPED）。
    // 用顺序返回值而不是两次 when：后者会覆盖前者，让 require 直接看到终态。
    when(jobs.findById("job_1")).thenReturn(Optional.of(running), Optional.of(stopped));
    when(jobs.updateStatus("job_1", JobStatus.STOPPED, null)).thenReturn(1);

    CollectJob result = service.stop(ADMIN, "job_1", "来源条款变更，停止本轮抓取");

    assertThat(result.status()).isEqualTo(JobStatus.STOPPED);
    // 停止不动检查点：它记录的是「已确认交接的位置」，不是「本任务还能不能跑」。
    assertThat(result.checkpoint()).isEqualTo("cp-42");
    assertThat(result.totalHandedOff()).isEqualTo(120);
    verify(audit)
        .record(
            eq(ADMIN),
            eq("INGESTION_JOB_STOPPED"),
            eq("COLLECT_JOB"),
            eq("job_1"),
            any(),
            any(),
            anyString());

    // 停止后恢复必须被拒绝——否则「停止」只是第二个「暂停」。
    assertThatThrownBy(() -> service.resume(ADMIN, "job_1", "想接着跑"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("只有暂停中的任务可以恢复");
  }

  @Test
  @DisplayName("已成功的任务不能停止：停止一个结束了的任务只会让人误以为它被打断过")
  void succeededJobCannotBeStopped() {
    when(jobs.findById("job_1")).thenReturn(Optional.of(job(JobStatus.SUCCEEDED, "cp-1", 10, 0)));

    assertThatThrownBy(() -> service.stop(ADMIN, "job_1", "停止"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("任务已结束");
    verify(jobs, never()).updateStatus(anyString(), any(), any());
  }

  @Test
  @DisplayName("失败重试只把状态放回队列，不动检查点；重试次数加一")
  void retryRequeuesWithoutTouchingCheckpoint() {
    CollectJob failed = job(JobStatus.FAILED, "cp-9000", 9000, 2);
    CollectJob requeued = job(JobStatus.PENDING, "cp-9000", 9000, 3);
    when(jobs.findById("job_1")).thenReturn(Optional.of(failed), Optional.of(requeued));
    when(jobs.requeueFailed("job_1")).thenReturn(1);

    CollectJob result = service.retry(ADMIN, "job_1", "上游已恢复");

    assertThat(result.status()).isEqualTo(JobStatus.PENDING);
    // 这两条是「重试不等于重抓」的全部证据：检查点不变、历史已交接计数不变。
    assertThat(result.checkpoint()).isEqualTo("cp-9000");
    assertThat(result.totalHandedOff()).isEqualTo(9000);
    assertThat(result.retryCount()).isEqualTo(3);
    verify(jobs).requeueFailed("job_1");
  }

  @Test
  @DisplayName("只有 FAILED 能重试：运行中、暂停、已停止、已成功各自被拒绝且不写库")
  void retryOnlyAllowedFromFailed() {
    for (JobStatus status :
        new JobStatus[] {
          JobStatus.RUNNING, JobStatus.PAUSED, JobStatus.STOPPED, JobStatus.SUCCEEDED
        }) {
      when(jobs.findById("job_1")).thenReturn(Optional.of(job(status, "cp", 1, 0)));
      assertThatThrownBy(() -> service.retry(ADMIN, "job_1", "重试"))
          .as("状态 %s 不应允许重试", status)
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("只有失败的任务可以重试");
    }
    verify(jobs, never()).requeueFailed(anyString());
  }

  private static CollectJob job(
      JobStatus status, String checkpoint, int handedOff, int retryCount) {
    return new CollectJob(
        "job_1",
        "src_1",
        "graph",
        status,
        "cursor-1",
        checkpoint,
        12_000,
        9_000,
        handedOff,
        30,
        null,
        null,
        status == JobStatus.FAILED ? "HTTP 503 from upstream" : null,
        "acc_admin",
        1,
        null,
        retryCount);
  }
}
