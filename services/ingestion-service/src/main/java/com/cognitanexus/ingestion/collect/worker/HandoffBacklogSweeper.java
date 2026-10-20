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
 * 补交接巡逻：收拾「任务已暂停，但批次还没交出去」的遗留。
 *
 * <p>为什么需要它：交接重试只发生在任务真的跑一轮的时候（{@code runOnce} 先排空遗留批次再抓新页），
 * 而 {@code runOnce} 对 PAUSED 直接跳过。于是「批次已建、core 没回执」的那一刻若正好撞上暂停，
 * 或 core 侧超时导致客户端先放弃，批次会停在 PENDING、记录停在 STAGED，两边都不会自己变化，
 * 也不会再产生任何日志。线上实测两次：一次 100 条、一次 50 条（后者卡了三天，任务状态看着只是「已暂停」）。
 *
 * <p>为什么不做成「自动恢复任务」：暂停是管理员的显式决定。这里只交付已经抓到的数据——
 * 上游配额和解析成本已经花掉，不交付等于白花；要不要再抓必须由人回答。委托给
 * {@link CollectExecutionService#drainBacklog} 做的事情也正是这一件。
 *
 * <p>为什么每轮只取 1 个任务：遗留批次是异常路径的产物，正常运行时这里应当什么都不做。
 * 一次取很多只会在出事时把 core 一起压住——而这正是最不该加剧的时刻。
 *
 * <p>异常一律就地吞掉：巡逻任务自己失败不该让调度线程停摆，也不该影响其它定时任务
 * （同池还跑着采集执行器与引用补水）。
 */
@Component
public class HandoffBacklogSweeper {

  private static final Logger log = LoggerFactory.getLogger(HandoffBacklogSweeper.class);

  /** 每轮最多看几个候选任务。取 1：正常情况这条路径应当长期静默。 */
  private static final int CANDIDATES_PER_TICK = 1;

  private final CollectRepositories.JobRepository jobs;
  private final CollectExecutionService execution;
  private final CollectProperties properties;
  private final String leaseOwner;

  public HandoffBacklogSweeper(
      CollectRepositories.JobRepository jobs,
      CollectExecutionService execution,
      CollectProperties properties) {
    this.jobs = jobs;
    this.execution = execution;
    this.properties = properties;
    // 租约属主带进程号：排查「租约被谁持有」时不必靠时间去猜
    this.leaseOwner = "sweeper-" + ProcessHandle.current().pid();
  }

  @Scheduled(
      fixedDelayString = "${cn.collect.backlog-sweep-interval-ms:900000}",
      initialDelayString = "60000")
  public void tick() {
    if (!properties.workerEnabled()) {
      return;
    }
    List<String> candidates;
    try {
      candidates = jobs.listPausedJobsWithDueBatch(CANDIDATES_PER_TICK);
    } catch (RuntimeException ex) {
      log.error("查询暂停任务的遗留交接批次失败，本轮跳过", ex);
      return;
    }
    if (candidates.isEmpty()) {
      return;
    }
    for (String jobId : candidates) {
      try {
        int handedOff = execution.drainBacklog(jobId, leaseOwner);
        if (handedOff > 0) {
          log.info("补交接巡逻完成 jobId={} 条数={}", jobId, handedOff);
        }
      } catch (RuntimeException ex) {
        // 就地吞掉：失败详情已在批次与任务上，下一轮还会再来
        log.error("补交接巡逻异常 jobId={}", jobId, ex);
      }
    }
  }
}
