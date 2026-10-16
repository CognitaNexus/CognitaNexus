package com.cognitanexus.ingestion.collect.worker;

import com.cognitanexus.ingestion.collect.application.CollectExecutionService;
import com.cognitanexus.ingestion.collect.application.CollectProperties;
import com.cognitanexus.ingestion.collect.application.CollectRepositories;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 采集后台执行器。
 *
 * <p>依据：架构 V0.2 §6.1、§6.2。每个周期做两件事：取出可执行任务、对其中一个做有界连推。
 *
 * <p>为什么一个周期只推一个任务：任务之间共享外部来源的速率限制，并发推进多个任务会让每个任务
 * 都变慢并更容易触发来源侧的限流。取的是 {@code updated_at} 最早的那个，刚被处理过的任务自动
 * 排到队尾，因此不会长期独占。
 *
 * <p>并发安全不依赖本类的任何状态：多实例场景下由数据库租约（{@code FOR UPDATE SKIP LOCKED}）
 * 决定谁执行，本类不做「我是主节点」之类的选举——那类判断在实例重启与时钟漂移下都不可靠。
 *
 * <p>异常一律就地吞掉并记录：单次执行失败不应让调度线程停止，否则一次来源超时就会让采集永久
 * 停摆；真正的失败信息记在任务与运行日志里，可查可重试。
 */
@Component
public class CollectWorker {

  private static final Logger log = LoggerFactory.getLogger(CollectWorker.class);

  /** 每个周期最多检查多少个候选任务。取 1 时总是队列里最落后的那个。 */
  private static final int CANDIDATES_PER_TICK = 1;

  private final CollectRepositories.JobRepository jobs;
  private final CollectExecutionService execution;
  private final CollectProperties properties;
  private final String leaseOwner;

  public CollectWorker(
      CollectRepositories.JobRepository jobs,
      CollectExecutionService execution,
      CollectProperties properties) {
    this.jobs = jobs;
    this.execution = execution;
    this.properties = properties;
    // 租约属主带上进程号：多实例部署时可以从任务行看出最后一次执行发生在哪个进程，
    // 排查「租约被谁持有」时不必再靠时间猜测
    this.leaseOwner = "worker-" + ProcessHandle.current().pid();
  }

  @Scheduled(
      fixedDelayString = "${cn.collect.worker-interval-ms:2000}",
      initialDelayString = "3000")
  public void tick() {
    if (!properties.workerEnabled()) {
      return;
    }
    List<String> runnable;
    try {
      runnable = jobs.listRunnableJobIds(CANDIDATES_PER_TICK);
    } catch (RuntimeException ex) {
      log.error("查询可执行采集任务失败，本轮跳过", ex);
      return;
    }
    if (runnable.isEmpty()) {
      return;
    }
    for (String jobId : runnable) {
      try {
        CollectExecutionService.BatchOutcome outcome =
            execution.runBounded(jobId, leaseOwner, properties.maxPagesPerRun());
        if (outcome.productive() > 0) {
          log.info(
              "采集推进 jobId={} 轮数={} 暂存={} 重复={} 已交接={} 说明={}",
              jobId,
              outcome.rounds(),
              outcome.staged(),
              outcome.duplicated(),
              outcome.handedOff(),
              outcome.lastMessage());
        } else {
          log.debug("采集未产生进展 jobId={} 说明={}", jobId, outcome.lastMessage());
        }
      } catch (RuntimeException ex) {
        // 就地吞掉：一次失败不能停掉调度线程。失败详情已写入任务与运行日志
        log.error("采集执行异常 jobId={}", jobId, ex);
      }
    }
  }
}
