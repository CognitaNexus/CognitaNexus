package com.cognitanexus.ingestion.collect.application;

import com.cognitanexus.ingestion.collect.domain.CollectModels.CollectJob;
import com.cognitanexus.ingestion.collect.domain.CollectModels.SourceRunConfig;
import com.cognitanexus.ingestion.collect.domain.HandoffState;
import com.cognitanexus.ingestion.collect.domain.JobStatus;
import com.cognitanexus.ingestion.platform.audit.IngestionAuditRecorder;
import com.cognitanexus.ingestion.platform.security.ActorContext;
import com.cognitanexus.ingestion.platform.util.Identifiers;
import com.cognitanexus.sharedkernel.BusinessException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 采集任务管理。
 *
 * <p>依据：架构 V0.2 §5.3、§6.2。任务由「幂等键 + 游标 + 已确认检查点」描述：
 *
 * <ul>
 *   <li>幂等键 {@code (sourceConfigId, jobKey)} 使重复提交同一任务不产生第二条任务，也不重置进度；
 *   <li>游标是抓取位置，检查点只在前移时更新，且必须等 core ACK 之后才前移——顺序反了会在交接
 *       失败时丢掉已经交接完成的记录，重跑要么重复交接、要么跳过未交接的区间。
 * </ul>
 *
 * <p>租约领取与业务状态变更分开：领不到租约只表示「这一刻有别的实例在处理」，不改变任务状态。
 */
@Service
public class CollectJobService {

  private static final Logger log = LoggerFactory.getLogger(CollectJobService.class);

  private final CollectRepositories.JobRepository jobs;
  private final CollectRepositories.StagedRepository staged;
  private final CollectRepositories.HandoffBatchRepository batches;
  private final CollectRepositories.SourceConfigRepository configs;
  private final CollectRepositories.FetchLogRepository logs;
  private final IngestionAuditRecorder audit;

  public CollectJobService(
      CollectRepositories.JobRepository jobs,
      CollectRepositories.StagedRepository staged,
      CollectRepositories.HandoffBatchRepository batches,
      CollectRepositories.SourceConfigRepository configs,
      CollectRepositories.FetchLogRepository logs,
      IngestionAuditRecorder audit) {
    this.jobs = jobs;
    this.staged = staged;
    this.batches = batches;
    this.configs = configs;
    this.logs = logs;
    this.audit = audit;
  }

  /**
   * 创建任务（幂等）。
   *
   * <p>同一来源下 jobKey 相同的任务只会存在一条；重复提交返回既有任务并明确标注「已存在」，
   * 让调用方知道没有新任务被创建，而不是误以为提交两次会产生两个任务。
   */
  @Transactional
  public CreateOutcome create(ActorContext actor, String sourceId, String jobKey, String reason) {
    SourceRunConfig config =
        configs.findById(sourceId).orElseThrow(() -> BusinessException.notFound("采集运行配置不存在"));
    if (!config.licenseApproved()) {
      throw BusinessException.businessRule(
          "该来源许可未获批准，不能创建采集任务，当前状态：" + config.licenseReviewState());
    }
    String normalizedKey = jobKey == null || jobKey.isBlank() ? defaultJobKey() : jobKey.trim();

    Optional<CollectJob> existing = jobs.findByKey(sourceId, normalizedKey);
    if (existing.isPresent()) {
      return new CreateOutcome(existing.get(), false, "该任务键已存在，返回既有任务，未创建新任务");
    }

    String id = Identifiers.next("job");
    if (!jobs.insert(id, sourceId, normalizedKey, actor == null ? "SYSTEM" : actor.username())) {
      // 并发下另一个请求先创建成功：返回既有任务即可，不视为错误
      CollectJob concurrent =
          jobs.findByKey(sourceId, normalizedKey)
              .orElseThrow(() -> BusinessException.versionConflict("任务创建冲突，请刷新后重试"));
      return new CreateOutcome(concurrent, false, "并发创建，返回既有任务");
    }
    audit.record(
        actor,
        "INGESTION_JOB_CREATED",
        "COLLECT_JOB",
        id,
        null,
        Map.of("sourceCode", config.sourceCode(), "jobKey", normalizedKey),
        reason == null ? "创建采集任务" : reason);
    log.info("采集任务已创建 id={} sourceCode={} jobKey={}", id, config.sourceCode(), normalizedKey);
    return new CreateOutcome(jobs.findById(id).orElseThrow(), true, "任务已创建");
  }

  private static String defaultJobKey() {
    return "run-" + java.time.LocalDate.now();
  }

  @Transactional(readOnly = true)
  public List<CollectJob> list(int limit) {
    return jobs.listRecent(Math.min(Math.max(limit, 1), 200));
  }

  @Transactional(readOnly = true)
  public List<CollectJob> listBySource(String sourceId, int limit) {
    return jobs.listBySource(sourceId, Math.min(Math.max(limit, 1), 200));
  }

  @Transactional(readOnly = true)
  public CollectJob require(String jobId) {
    return jobs.findById(jobId).orElseThrow(() -> BusinessException.notFound("采集任务不存在"));
  }

  /**
   * 暂停任务。
   *
   * <p>暂停只改状态不释放未完成的工作：已暂存但未交接的记录会在恢复后继续交接，不会因为暂停
   * 而丢失。
   */
  @Transactional
  public CollectJob pause(ActorContext actor, String jobId, String reason) {
    CollectJob job = require(jobId);
    if (job.status().terminal()) {
      throw BusinessException.businessRule("任务已结束，无法暂停");
    }
    if (job.status() == JobStatus.PAUSED) {
      throw BusinessException.versionConflict("任务当前已处于暂停状态");
    }
    jobs.updateStatus(jobId, JobStatus.PAUSED, job.lastError());
    audit.record(
        actor,
        "INGESTION_JOB_PAUSED",
        "COLLECT_JOB",
        jobId,
        Map.of("status", job.status().name()),
        Map.of("status", JobStatus.PAUSED.name()),
        reason == null ? "暂停采集任务" : reason);
    return jobs.findById(jobId).orElseThrow();
  }

  /** 恢复暂停的任务；检查点不动，恢复后从已确认交接的位置继续。 */
  @Transactional
  public CollectJob resume(ActorContext actor, String jobId, String reason) {
    CollectJob job = require(jobId);
    if (job.status() != JobStatus.PAUSED) {
      throw BusinessException.businessRule("只有暂停中的任务可以恢复，当前状态：" + job.status());
    }
    jobs.updateStatus(jobId, JobStatus.RUNNING, null);
    audit.record(
        actor,
        "INGESTION_JOB_RESUMED",
        "COLLECT_JOB",
        jobId,
        Map.of("status", JobStatus.PAUSED.name()),
        Map.of("status", JobStatus.RUNNING.name(), "checkpoint", String.valueOf(job.checkpoint())),
        reason == null ? "恢复采集任务" : reason);
    return jobs.findById(jobId).orElseThrow();
  }

  /**
   * 任务运行统计。
   *
   * <p>依据架构 §6.2 第 6 条，管理后台必须<b>分别</b>展示已抓取、已持久交接、已发布、待核验与失败数。
   * 这里给出前四类的本地口径：已发布与待核验由 core 治理得出，本服务只能报「已交接」以及 core
   * 回执中体现的接收结论，不能把「已交接」等同于「已发布」。
   */
  @Transactional(readOnly = true)
  public Map<String, Object> statistics(String jobId) {
    CollectJob job = require(jobId);
    Map<HandoffState, Long> stagedCounts = staged.countByState(job.sourceConfigId());
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("jobId", job.id());
    view.put("sourceConfigId", job.sourceConfigId());
    view.put("jobKey", job.jobKey());
    view.put("status", job.status().name());
    view.put("cursor", job.cursor());
    view.put("checkpoint", job.checkpoint());
    view.put("totalFetched", job.totalFetched());
    view.put("totalStaged", job.totalStaged());
    view.put("totalHandedOff", job.totalHandedOff());
    view.put("totalRejected", job.totalRejected());
    view.put("stagedNotYetHandedOff", stagedCounts.getOrDefault(HandoffState.STAGED, 0L));
    view.put("handoffFailed", stagedCounts.getOrDefault(HandoffState.FAILED, 0L));
    view.put("pendingBatches", batches.countPending());
    view.put("failedBatches", batches.countFailed());
    view.put("leaseOwner", job.leaseOwner());
    view.put("leaseUntil", job.leaseUntil() == null ? null : job.leaseUntil().toString());
    view.put("lastError", job.lastError());
    view.put("note", "已交接不等于已发布：是否进入公开目录由 core 的治理判定，本服务只报交接结论");
    return view;
  }

  /** 交接批次明细，用于排查「批次已建但记录未交付」这类不一致。 */
  @Transactional(readOnly = true)
  public List<CollectRepositories.HandoffBatchRepository.HandoffBatch> listBatches(
      String jobId, int limit) {
    require(jobId);
    return batches.listByJob(jobId, Math.min(Math.max(limit, 1), 200));
  }

  /** 运行日志（只读观测数据）。 */
  @Transactional(readOnly = true)
  public List<CollectRepositories.FetchLogRepository.FetchLogEntry> listLogs(
      String jobId, int limit) {
    require(jobId);
    return logs.listByJob(jobId, Math.min(Math.max(limit, 1), 500));
  }

  /** 创建结果。 */
  public record CreateOutcome(CollectJob job, boolean created, String message) {}
}
