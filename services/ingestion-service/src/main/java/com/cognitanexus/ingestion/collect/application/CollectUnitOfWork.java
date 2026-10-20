package com.cognitanexus.ingestion.collect.application;

import com.cognitanexus.ingestion.collect.domain.CollectModels.CollectJob;
import com.cognitanexus.ingestion.collect.domain.CollectModels.CollectedRecord;
import com.cognitanexus.ingestion.collect.domain.CollectModels.FetchPage;
import com.cognitanexus.ingestion.collect.domain.CollectModels.SourceRunConfig;
import com.cognitanexus.ingestion.collect.domain.CollectModels.StagedRecord;
import com.cognitanexus.ingestion.platform.client.CoreInternalClient;
import com.cognitanexus.ingestion.platform.persistence.JsonCodec;
import com.cognitanexus.ingestion.platform.util.Fingerprints;
import com.cognitanexus.ingestion.platform.util.Identifiers;
import com.cognitanexus.ingestion.platform.util.TextNormalizer;
import com.cognitanexus.ingestion.platform.web.TraceIdHolder;
import com.cognitanexus.sharedkernel.DataNature;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 采集流程中需要原子性的步骤。
 *
 * <p>依据：架构 V0.2 §6.1（事务边界）。刻意独立成 Bean，而不是写成
 * {@code CollectExecutionService} 的私有方法：{@code @Transactional} 依赖 Spring 代理，
 * 同一个 Bean 内部的方法调用不经过代理，注解会被静默忽略，注解存在而事务不存在。
 * 这类失效不会报错，只会让「暂存与游标同事务」这样的设计悄悄退化为两次独立提交——
 * 表现为游标前移了但记录没落库，那段区间再也抓不到。
 *
 * <p>同类问题在本工程已出现两次（{@code LoginStateUpdater}、{@code PlatformAdminBootstrapper}），
 * 处理方式一致：把事务方法搬到另一个 Bean。
 */
@Service
public class CollectUnitOfWork {

  private final CollectRepositories.JobRepository jobs;
  private final CollectRepositories.StagedRepository staged;
  private final CollectRepositories.HandoffBatchRepository batches;
  private final CollectRepositories.FetchLogRepository logs;
  private final JsonCodec json;

  public CollectUnitOfWork(
      CollectRepositories.JobRepository jobs,
      CollectRepositories.StagedRepository staged,
      CollectRepositories.HandoffBatchRepository batches,
      CollectRepositories.FetchLogRepository logs,
      JsonCodec json) {
    this.jobs = jobs;
    this.staged = staged;
    this.batches = batches;
    this.logs = logs;
    this.json = json;
  }

  /** 暂存结果。 */
  public record StageResult(int stored, int duplicated) {}

  /**
   * 创建批次并把记录挂到批次上，两件事同一事务。
   *
   * <p>只建批次不挂记录会留下一个「查不到任何记录的空批次」，交接流程随后会把它判成数据不一致；
   * 反过来先挂记录再建批次，若建批次失败就留下一批被标记为属于不存在的批次的记录。
   *
   * @return 批次是否由本次创建；已存在同键批次时返回 false，调用方应交给重试分支而不是重挂记录
   */
  @Transactional
  public boolean openBatch(String batchId, String jobId, String batchKey, List<String> recordIds) {
    if (!batches.insert(batchId, jobId, batchKey, recordIds.size())) {
      return false;
    }
    staged.assignToBatch(recordIds, batchId);
    return true;
  }

  /**
   * 把一个撞到唯一约束的 FAILED 批次重新打开，并把记录挂回去。
   *
   * <p>两步必须同一事务：只把批次改回 PENDING 而没挂记录，下一次交接会按批次查记录得到空集合，
   * 于是把一个本该正常的批次判成「数据不一致」——这正是这个坑最初的表现形式。
   *
   * @return 是否重新打开；批次已不是 FAILED（被别处推进或已 ACK）时返回 false
   */
  @Transactional
  public boolean reopenFailedBatch(String batchId, List<String> recordIds) {
    if (batches.reopenFailed(batchId) == 0) {
      return false;
    }
    staged.assignToBatch(recordIds, batchId);
    return true;
  }

  /**
   * 暂存一页记录并推进游标。
   *
   * <p>两件事必须在同一事务内：游标前移而记录未落库，会让这段区间被永久跳过；反过来记录落库而
   * 游标未前移，则重复抓取同一页，好在稳定交接键会把它识别为重复而不产生新行。
   */
  @Transactional
  public StageResult stagePage(
      SourceRunConfig config,
      CollectJob job,
      FetchPage page,
      int rejectedCount,
      String nextCursor) {
    int stored = 0;
    for (CollectedRecord record : page.records()) {
      boolean inserted =
          staged.insertIfAbsent(
              Identifiers.next("stg"),
              job.id(),
              config.id(),
              record.sourceRecordId(),
              Fingerprints.of(fingerprintParts(config, record)),
              json.write(record.payload()),
              // 数据性质不允许缺省：适配器未声明时按 SIMULATED 处理，绝不默认成 REAL
              record.dataNature() == null ? DataNature.SIMULATED : record.dataNature(),
              record.sourceUpdatedAt(),
              record.sourcePublishedAt(),
              record.fetchedAt() == null ? Instant.now() : record.fetchedAt());
      if (inserted) {
        stored++;
      }
    }
    int duplicated = page.records().size() - stored;
    jobs.recordFetchProgress(job.id(), nextCursor, page.records().size(), stored, rejectedCount);
    appendLog(
        job.id(),
        config.id(),
        "STAGE",
        "SUCCEEDED",
        page.records().size(),
        stored,
        rejectedCount,
        Map.of("duplicated", duplicated, "nextCursor", String.valueOf(nextCursor)));
    return new StageResult(stored, duplicated);
  }

  /**
   * 记录交接成功并前移检查点。
   *
   * <p>顺序是「标记已交接 → 记录批次 ACK → 前移检查点」，全部在一个事务内。检查点最后移动到
   * 已确认的位置：如果先移检查点再标记，中间失败会让记录既不在待交接集合里、也没被标记为已交接。
   */
  @Transactional
  public void recordHandoffSuccess(
      CollectRepositories.HandoffBatchRepository.HandoffBatch batch,
      List<StagedRecord> records,
      CoreInternalClient.HandoffResult result,
      Map<String, Object> receiptSummary) {
    for (StagedRecord record : records) {
      String receipt =
          result.receiptStatuses().getOrDefault(record.sourceRecordId(), "ACCEPTED（回执未单列）");
      staged.markHandedOff(record.id(), batch.id(), truncate(receipt, 200), null);
    }
    batches.markAcked(batch.id(), json.write(receiptSummary));
    jobs.advanceCheckpoint(batch.jobId(), batch.batchKey(), records.size());
    appendLog(
        batch.jobId(),
        sourceConfigIdOf(batch, records),
        "HANDOFF",
        "SUCCEEDED",
        records.size(),
        records.size(),
        result.rejected(),
        Map.of(
            "batchKey", batch.batchKey(),
            "accepted", result.accepted(),
            "attached", result.attached(),
            "duplicate", result.duplicate(),
            "rejected", result.rejected(),
            "catalogRevision", result.catalogRevision()));
  }

  /** 记录交接失败：重试排期，或达到上限后转 FAILED 并标记记录。 */
  @Transactional
  public void recordHandoffFailure(
      CollectRepositories.HandoffBatchRepository.HandoffBatch batch,
      List<StagedRecord> records,
      String error,
      boolean exhausted,
      int backoffSeconds) {
    batches.markFailed(
        batch.id(), exhausted ? "FAILED" : "PENDING", truncate(error, 400), backoffSeconds);
    if (exhausted) {
      for (StagedRecord record : records) {
        staged.markFailed(record.id(), "交接重试超过上限：" + truncate(error, 300));
      }
    } else {
      for (StagedRecord record : records) {
        staged.incrementAttempt(record.id());
      }
    }
    appendLog(
        batch.jobId(),
        sourceConfigIdOf(batch, records),
        "HANDOFF",
        exhausted ? "EXHAUSTED" : "RETRY_SCHEDULED",
        records.size(),
        0,
        exhausted ? records.size() : 0,
        Map.of(
            "batchKey", batch.batchKey(), "error", String.valueOf(error), "exhausted", exhausted));
  }

  @Transactional
  public void updateJobStatus(
      String jobId, com.cognitanexus.ingestion.collect.domain.JobStatus status, String error) {
    jobs.updateStatus(jobId, status, error);
  }

  @Transactional
  public void recordJobError(String jobId, String error) {
    jobs.recordError(jobId, truncate(error, 400));
  }

  /**
   * 记录一次抓取失败并把下一次尝试排到 {@code backoffSeconds} 秒之后。
   *
   * <p>与 {@link #recordJobError} 分开而不是加个参数：两者的语义不同。前者只写一行错误信息
   * （用于留痕），后者改变任务的<b>可执行性</b>——接下来一段时间内调度根本不会取它。把它们合成
   * 一个方法，调用方很容易在只想记错误的地方顺手引入一次退避。
   */
  @Transactional
  public void recordFetchFailure(String jobId, String error, int backoffSeconds) {
    jobs.recordFetchFailure(jobId, truncate(error, 400), backoffSeconds);
  }

  /**
   * 抓取恢复正常：连续失败计数归零、退避窗口取消。
   *
   * <p>不写运行日志：这是一次状态复位，不是一次事件；把它也记一条日志会让「失败—成功」交替时
   * 的日志量翻倍，而这两条的信息（失败次数归零）在执行日志的成功行里已经能读出来。
   */
  @Transactional
  public void clearFetchFailures(String jobId) {
    jobs.clearFetchFailures(jobId);
  }

  /** 运行日志单独提交：它是观测数据，不应把业务事务拖长，也不应因日志写失败而回滚业务。 */
  @Transactional
  public void appendLog(
      String jobId,
      String sourceConfigId,
      String phase,
      String outcome,
      Integer fetched,
      Integer stagedCount,
      Integer rejected,
      Map<String, Object> detail) {
    try {
      logs.append(
          Identifiers.next("log"),
          jobId,
          sourceConfigId,
          phase,
          outcome,
          fetched,
          stagedCount,
          rejected,
          json.write(detail),
          TraceIdHolder.current());
    } catch (RuntimeException ex) {
      // 日志用于观测，不影响业务结论
      org.slf4j.LoggerFactory.getLogger(CollectUnitOfWork.class)
          .warn("采集运行日志写入失败 jobId={} phase={} message={}", jobId, phase, ex.toString());
    }
  }

  /**
   * 日志里的来源配置标识。
   *
   * <p>刻意不从 `records.get(0)` 取：批次与记录不一致时记录集可能是空的，而对「空批次」这种异常
   * 恰恰最需要留下日志；在那里抛下标越界会让异常被另一个异常掩盖，只剩一条看不懂的错误。
   * 记录集为空时回落到任务上查来源配置，查不到才留空。
   */
  private String sourceConfigIdOf(
      CollectRepositories.HandoffBatchRepository.HandoffBatch batch, List<StagedRecord> records) {
    if (!records.isEmpty()) {
      return records.get(0).sourceConfigId();
    }
    return jobs.findById(batch.jobId())
        .map(com.cognitanexus.ingestion.collect.domain.CollectModels.CollectJob::sourceConfigId)
        .orElse(null);
  }

  /**
   * 指纹输入。
   *
   * <p>只包含业务字段，绝不含抓取时刻或请求标识：否则同一份内容每次抓取都会得到新指纹，
   * 稳定交接键退化为「每次抓取都算新记录」，幂等重试与跨源去重同时失效。
   *
   * <p>但必须包含来源配置版本，理由与「不含抓取时刻」并不矛盾。指纹要回答的问题是「这份记录的
   * 内容是否变了」，而一份记录的内容是「来源给出的原始字段」加上「本期映射决定读取哪些字段」。
   * 映射版本变了，同一个 DOI 交出去的字段集合就真的变了：加了引用量字段之后，下游拿到的是
   * 一份更完整的记录，而不是同一份记录的重复投递。
   *
   * <p>漏掉这一项的后果不是报错而是静默失效：映射扩了字段、白名单也同步了，指纹却与旧版一致，
   * 于是所有既有记录都被判为重复、永不再交接，新字段永远进不了 core——表面上采集在正常跑、
   * 统计却是空的。反过来，把每次抓取时刻放进指纹会产生相反的错误：每轮都造新版本。两者的区别
   * 在于配置版本只在映射真正变化时前进，而抓取时刻每次都变。
   *
   * <p>刻意<b>不</b>把引用量之类的指标值本身放进指纹：那会让每个引用数的自然增长都生成一条新的
   * 来源版本，把「来源记录的版本」和「指标观测值」两种不同生命周期的东西混成一种。
   */
  private static String[] fingerprintParts(SourceRunConfig config, CollectedRecord record) {
    Map<String, Object> payload = record.payload();
    return new String[] {
      record.sourceRecordId(),
      asText(payload.get("kind")),
      TextNormalizer.normalize(asText(payload.get("title"))),
      TextNormalizer.normalizeStableIdentifier(asText(payload.get("doi"))),
      asText(payload.get("applicationNo")),
      asText(payload.get("projectNo")),
      asText(payload.get("awardName")),
      asText(payload.get("publicationYear")),
      TextNormalizer.normalize(asText(payload.get("venue"))),
      config.configVersion()
    };
  }

  private static String asText(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
