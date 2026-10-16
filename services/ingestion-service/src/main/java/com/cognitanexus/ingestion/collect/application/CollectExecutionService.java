package com.cognitanexus.ingestion.collect.application;

import com.cognitanexus.ingestion.collect.adapter.SourceAdapter;
import com.cognitanexus.ingestion.collect.adapter.SourceAdapterRegistry;
import com.cognitanexus.ingestion.collect.domain.CollectModels.CollectJob;
import com.cognitanexus.ingestion.collect.domain.CollectModels.FetchPage;
import com.cognitanexus.ingestion.collect.domain.CollectModels.SourceRunConfig;
import com.cognitanexus.ingestion.collect.domain.CollectModels.StagedRecord;
import com.cognitanexus.ingestion.collect.domain.HandoffState;
import com.cognitanexus.ingestion.collect.domain.JobStatus;
import com.cognitanexus.ingestion.platform.client.CoreInternalClient;
import com.cognitanexus.ingestion.platform.persistence.JsonCodec;
import com.cognitanexus.ingestion.platform.util.Fingerprints;
import com.cognitanexus.ingestion.platform.util.Identifiers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 采集执行编排：抓取、暂存、交接、前移检查点。
 *
 * <p>依据：架构 V0.2 §6.1、§6.2。事务边界是这里的核心约束：
 *
 * <ul>
 *   <li><b>事务外</b>做外部 HTTP：抓取来源与交接给 core 都不能放在数据库事务里，否则一次来源
 *       超时就会长时间占用连接与行锁；
 *   <li><b>事务内</b>只做本地多语句原子操作，且都委托给 {@link CollectUnitOfWork}——本类自身的
 *       方法带有 {@code @Transactional} 无效（无代理），这一点已在 {@code CollectUnitOfWork}
 *       的类注释中说明。
 * </ul>
 *
 * <p>任务每批开始前重查 core 的来源启用状态（架构 §6.1）：跨服务撤销没有分布式事务原子性，
 * 不能在任务创建时校验一次就一路执行到底。
 *
 * <p>失败语义：抓取失败只记录错误，<b>不动检查点</b>；把失败表现为「抓到 0 条并推进检查点」
 * 会让这段区间被永久跳过。
 */
@Service
public class CollectExecutionService {

  private static final Logger log = LoggerFactory.getLogger(CollectExecutionService.class);

  /** 单轮最多处理的批次轮次，防止单轮长时间占用。超出的留待下一轮。 */
  private static final int MAX_ROUNDS_PER_RUN = 50;

  private final CollectRepositories.JobRepository jobs;
  private final CollectRepositories.StagedRepository staged;
  private final CollectRepositories.HandoffBatchRepository batches;
  private final CollectRepositories.SourceConfigRepository configs;
  private final SourceAdapterRegistry adapters;
  private final CoreInternalClient coreClient;
  private final CollectProperties properties;
  private final CollectUnitOfWork unitOfWork;
  private final JsonCodec json;

  public CollectExecutionService(
      CollectRepositories.JobRepository jobs,
      CollectRepositories.StagedRepository staged,
      CollectRepositories.HandoffBatchRepository batches,
      CollectRepositories.SourceConfigRepository configs,
      SourceAdapterRegistry adapters,
      CoreInternalClient coreClient,
      CollectProperties properties,
      CollectUnitOfWork unitOfWork,
      JsonCodec json) {
    this.jobs = jobs;
    this.staged = staged;
    this.batches = batches;
    this.configs = configs;
    this.adapters = adapters;
    this.coreClient = coreClient;
    this.properties = properties;
    this.unitOfWork = unitOfWork;
    this.json = json;
  }

  /** 推进一个任务一轮。 */
  public RunOutcome runOnce(String jobId, String leaseOwner) {
    CollectJob job = jobs.findById(jobId).orElse(null);
    if (job == null) {
      return RunOutcome.notRun("任务不存在");
    }
    if (job.status().terminal() || job.status() == JobStatus.PAUSED) {
      return RunOutcome.notRun("任务处于 " + job.status() + "，本轮跳过");
    }
    if (!jobs.claimLease(jobId, leaseOwner, properties.leaseSeconds())) {
      return new RunOutcome(true, "租约被其他实例持有", 0, 0, 0, false);
    }

    try {
      CollectJob leased = jobs.findById(jobId).orElseThrow();
      SourceRunConfig config = configs.findById(leased.sourceConfigId()).orElse(null);
      if (config == null) {
        unitOfWork.updateJobStatus(jobId, JobStatus.FAILED, "找不到来源运行配置");
        return RunOutcome.notRun("来源运行配置缺失");
      }

      // 每批重查 core：来源可能在两批之间被停用
      Optional<CoreInternalClient.SourceRunConfigView> authoritative =
          coreClient.sourceConfig(config.sourceCode());
      if (authoritative.isEmpty()) {
        unitOfWork.updateJobStatus(jobId, JobStatus.PAUSED, "core 未登记该来源，已暂停");
        log.warn("core 未登记来源，任务暂停 jobId={} sourceCode={}", jobId, config.sourceCode());
        return RunOutcome.notRun("core 未登记该来源，任务已暂停");
      }
      if (!authoritative.get().enabled()) {
        unitOfWork.updateJobStatus(jobId, JobStatus.PAUSED, "来源已在 core 停用，任务暂停");
        log.warn("来源已在 core 停用，任务暂停 jobId={} sourceCode={}", jobId, config.sourceCode());
        return RunOutcome.notRun("来源已停用，任务已暂停");
      }
      if (!config.licenseApproved()) {
        unitOfWork.updateJobStatus(jobId, JobStatus.PAUSED, "本地许可状态未批准");
        return RunOutcome.notRun("许可未批准，任务已暂停");
      }

      // 先交付上一轮遗留的暂存，再抓新页：顺序反过来会让暂存无界增长
      int handedOff = drainPendingHandoffs(leased);

      FetchOutcome fetched = fetchOnePage(config, leased);
      if (fetched.error() != null) {
        unitOfWork.recordJobError(jobId, fetched.error());
        unitOfWork.appendLog(
            jobId,
            config.id(),
            "FETCH",
            "FAILED",
            null,
            null,
            null,
            Map.of("error", fetched.error()));
        return new RunOutcome(false, "抓取失败：" + fetched.error(), 0, 0, handedOff, false);
      }

      handedOff += drainPendingHandoffs(jobs.findById(jobId).orElseThrow());

      boolean finished = fetched.lastPage();
      if (finished) {
        Map<HandoffState, Long> counts = staged.countByState(config.id());
        long remaining = counts.getOrDefault(HandoffState.STAGED, 0L);
        long failed = counts.getOrDefault(HandoffState.FAILED, 0L);
        // 判据是「输入要么被交付、要么被登记异常」：还有未处置项就不算成功
        if (remaining == 0 && failed == 0) {
          unitOfWork.updateJobStatus(jobId, JobStatus.SUCCEEDED, null);
        } else {
          unitOfWork.updateJobStatus(
              jobId,
              JobStatus.PAUSED,
              "抓取已到末页，但仍有 " + remaining + " 条待交接、" + failed + " 条交接失败，处理后恢复");
        }
      }

      return new RunOutcome(
          false,
          fetched.unitOfWorkStored() + " 条暂存，" + handedOff + " 条已交接",
          fetched.unitOfWorkStored(),
          fetched.duplicated(),
          handedOff,
          finished);
    } finally {
      // 释放租约但不改状态：把「可被其他实例接管」与「任务是否结束」分开
      jobs.releaseLease(jobId, leaseOwner);
    }
  }

  // ---------------------------------------------------------------------------

  /**
   * 有界地连续推进一个任务，最多 {@code maxRounds} 轮。
   *
   * <p>为什么需要「有界连推」而不是「每轮只抓一页」：采集吞吐的下限由外部 API 的往返时间决定，
   * 若一个调度周期内只抓一页，40 页的查询就要等 40 个周期；而完全无界又会把一个任务拖住不放，
   * 使同一批任务里后面的任务饿死。页数上限把两者折中成「一次调用最多占用多少次外部请求」。
   *
   * <p>提前结束的三种情形，都<b>不</b>改动检查点：
   *
   * <ul>
   *   <li>租约被其他实例持有——不是失败，交给下一轮；
   *   <li>抓取失败——错误已写入任务，检查点保持在上次确认交接的位置；
   *   <li>本轮没有任何进展——继续循环只会重复得到同样的结果。
   * </ul>
   *
   * @return 汇总结果，含实际完成的轮数与累计条数
   */
  public BatchOutcome runBounded(String jobId, String leaseOwner, int maxRounds) {
    int rounds = 0;
    int productive = 0;
    int stagedTotal = 0;
    int duplicatedTotal = 0;
    int handedOffTotal = 0;
    String lastMessage = "未执行任何一轮";
    for (int i = 0; i < Math.max(maxRounds, 1); i++) {
      RunOutcome outcome = runOnce(jobId, leaseOwner);
      rounds++;
      lastMessage = outcome.message();
      stagedTotal += outcome.staged();
      duplicatedTotal += outcome.duplicated();
      handedOffTotal += outcome.handedOff();
      if (outcome.leaseBusy() || outcome.sourceExhausted()) {
        break;
      }
      if (outcome.staged() == 0 && outcome.duplicated() == 0 && outcome.handedOff() == 0) {
        break;
      }
      productive++;
    }
    return new BatchOutcome(
        jobId, rounds, productive, stagedTotal, duplicatedTotal, handedOffTotal, lastMessage);
  }

  private record FetchOutcome(
      int unitOfWorkStored, int duplicated, boolean lastPage, String error) {}

  private FetchOutcome fetchOnePage(SourceRunConfig config, CollectJob job) {
    SourceAdapter adapter = adapters.require(config.adapterKind());
    FetchPage page;
    try {
      // 抓取在事务外：外部 HTTP 不应持有数据库连接与行锁
      page = adapter.fetch(config, job, job.cursor());
    } catch (RuntimeException ex) {
      return new FetchOutcome(0, 0, false, ex.getMessage());
    }
    CollectUnitOfWork.StageResult result =
        unitOfWork.stagePage(config, job, page, 0, page.nextCursor());
    return new FetchOutcome(result.stored(), result.duplicated(), page.lastPage(), null);
  }

  private int drainPendingHandoffs(CollectJob job) {
    int totalHandedOff = 0;
    for (int round = 0; round < MAX_ROUNDS_PER_RUN; round++) {
      // 先处理上一轮遗留的待重试批次：超时重试必须复用同一个幂等键
      Optional<CollectRepositories.HandoffBatchRepository.HandoffBatch> retry =
          batches.findNextPending(job.id());
      if (retry.isPresent()) {
        HandoffAttempt attempt = handoffBatch(retry.get());
        if (!attempt.acked()) {
          return totalHandedOff;
        }
        totalHandedOff += attempt.recordCount();
        continue;
      }

      List<StagedRecord> pending = staged.listPendingForHandoff(job.id(), 200);
      if (pending.isEmpty()) {
        return totalHandedOff;
      }
      List<StagedRecord> slice = trimToBatch(pending);
      if (slice.isEmpty()) {
        return totalHandedOff;
      }
      String batchKey = buildBatchKey(job, slice);
      String batchId = Identifiers.batch();
      // 建批次与把记录挂到批次上必须一起完成：只建批次会留下一个查不到记录的空批次，
      // 后续按批次取记录时得到空集合，会把正常批次误判成数据不一致
      if (!unitOfWork.openBatch(
          batchId, job.id(), batchKey, slice.stream().map(StagedRecord::id).toList())) {
        // 同键批次已存在。两种可能：已 ACK（由检查点前移体现）或在退避重试窗口内等待。
        // 两者都不该在本次调用里继续循环：同样的记录集只会算出同一个键、再撞一次唯一约束，
        // 既推不动任何状态，又会在一个调度周期内空转几十次。交给下一个调度周期。
        return totalHandedOff;
      }
      Optional<CollectRepositories.HandoffBatchRepository.HandoffBatch> created =
          batches.findByKey(batchKey);
      if (created.isEmpty()) {
        continue;
      }
      HandoffAttempt attempt = handoffBatch(created.get());
      if (!attempt.acked()) {
        return totalHandedOff;
      }
      totalHandedOff += attempt.recordCount();
    }
    log.warn("单轮交接轮次达到上限 jobId={}，剩余待交接记录留待下一轮", job.id());
    return totalHandedOff;
  }

  /**
   * 按条数与字节双上限裁剪批次。
   *
   * <p>只有条数限制时，一条超长摘要就可能让整个批次超过服务端允许的请求体，表现为整批被拒
   * 而不是单条被拒。两个上限同时生效才能把失败限制在可控范围内。
   */
  private List<StagedRecord> trimToBatch(List<StagedRecord> pending) {
    List<StagedRecord> slice = new ArrayList<>();
    long bytes = 0;
    for (StagedRecord record : pending) {
      if (slice.size() >= properties.batchSize()) {
        break;
      }
      long recordBytes = record.payloadJson().getBytes(StandardCharsets.UTF_8).length + 256L;
      if (!slice.isEmpty() && bytes + recordBytes > properties.maxBatchBytes()) {
        break;
      }
      slice.add(record);
      bytes += recordBytes;
    }
    return slice;
  }

  /**
   * 批次幂等键。
   *
   * <p>由任务、来源与首尾记录标识共同决定：同一批记录重试时得到同一个键，重试提交不会重复建实体；
   * 不同批记录不会撞键。用首尾而不是全部标识，是为了让键长度与批次规模无关。
   */
  private static String buildBatchKey(CollectJob job, List<StagedRecord> slice) {
    return Fingerprints.sha256Hex(
        job.id()
            + "|"
            + job.sourceConfigId()
            + "|"
            + slice.get(0).id()
            + "|"
            + slice.get(slice.size() - 1).id());
  }

  private record HandoffAttempt(boolean acked, int recordCount, String message) {}

  private HandoffAttempt handoffBatch(
      CollectRepositories.HandoffBatchRepository.HandoffBatch batch) {
    List<StagedRecord> records = staged.listByBatch(batch.id());
    if (records.isEmpty()) {
      // 批次存在但记录不在：批次与暂存不一致，判失败等待人工处理，不自动重建
      unitOfWork.recordHandoffFailure(batch, records, "批次内找不到暂存记录，数据不一致", true, 0);
      return new HandoffAttempt(false, 0, "批次与暂存记录不一致");
    }
    SourceRunConfig config = configs.findById(records.get(0).sourceConfigId()).orElse(null);
    if (config == null) {
      unitOfWork.recordHandoffFailure(batch, records, "来源运行配置缺失", true, 0);
      return new HandoffAttempt(false, 0, "来源运行配置缺失");
    }

    Map<String, Object> body = buildHandoffBody(config, batch, records);
    try {
      // 交接请求在事务外发起
      CoreInternalClient.HandoffResult result = coreClient.handoff(body);
      unitOfWork.recordHandoffSuccess(batch, records, result, summarize(result));
      log.info(
          "批次交接完成 batchKey={} 条数={} 新建={} 归属={} 重复={} 拒绝={}",
          batch.batchKey(),
          records.size(),
          result.accepted(),
          result.attached(),
          result.duplicate(),
          result.rejected());
      return new HandoffAttempt(true, records.size(), "已接收");
    } catch (RuntimeException ex) {
      int attempts = batch.attempts() + 1;
      boolean exhausted = attempts >= properties.maxHandoffAttempts();
      int backoff = backoffSeconds(attempts);
      String error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
      unitOfWork.recordHandoffFailure(batch, records, error, exhausted, backoff);
      if (exhausted) {
        log.error("批次交接超过重试上限 batchKey={} error={}", batch.batchKey(), error);
      }
      return new HandoffAttempt(false, 0, error);
    }
  }

  private Map<String, Object> buildHandoffBody(
      SourceRunConfig config,
      CollectRepositories.HandoffBatchRepository.HandoffBatch batch,
      List<StagedRecord> records) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sourceCode", config.sourceCode());
    body.put("configVersion", config.configVersion());
    // 批次键作为交接批次号：超时重试复用同一键，core 侧据此识别为同一批
    body.put("batchId", batch.batchKey());
    List<Map<String, Object>> payloads = new ArrayList<>(records.size());
    for (StagedRecord record : records) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("sourceRecordId", record.sourceRecordId());
      item.put("contentFingerprint", record.contentFingerprint());
      item.put("payload", json.readMap(record.payloadJson()));
      // 数据性质逐条显式传递：缺该字段 core 会直接拒绝，不会缺省成 REAL
      item.put("dataNature", record.dataNature().name());
      item.put(
          "sourceUpdatedAt",
          record.sourceUpdatedAt() == null ? null : record.sourceUpdatedAt().toString());
      item.put(
          "sourcePublishedAt",
          record.sourcePublishedAt() == null ? null : record.sourcePublishedAt().toString());
      item.put("fetchedAt", record.fetchedAt() == null ? null : record.fetchedAt().toString());
      payloads.add(item);
    }
    body.put("records", payloads);
    return body;
  }

  private static Map<String, Object> summarize(CoreInternalClient.HandoffResult result) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("coreBatchId", result.batchId());
    summary.put("received", result.received());
    summary.put("accepted", result.accepted());
    summary.put("attached", result.attached());
    summary.put("duplicate", result.duplicate());
    summary.put("rejected", result.rejected());
    summary.put("catalogRevision", result.catalogRevision());
    return summary;
  }

  /**
   * 指数退避：1、2、4、8…秒，上限取运行参数。
   *
   * <p>不使用随机抖动：单实例部署下抖动没有收益，而可预测的退避更容易在验收时复现。
   * 多实例部署后若出现重试同步，再按实例加入抖动。
   */
  private int backoffSeconds(int attempt) {
    long base = 1L << Math.min(Math.max(attempt - 1, 0), 6);
    return (int) Math.min(base, properties.maxBackoffSeconds());
  }

  /** 一轮执行的结果。 */
  public record RunOutcome(
      boolean leaseBusy,
      String message,
      int staged,
      int duplicated,
      int handedOff,
      boolean sourceExhausted) {

    static RunOutcome notRun(String message) {
      return new RunOutcome(false, message, 0, 0, 0, false);
    }
  }

  /**
   * 一次有界连推的汇总。
   *
   * @param rounds 实际尝试的轮数（含因租约占用而立即返回的轮次）
   * @param productive 产生了实际抓取或交接的轮数
   */
  public record BatchOutcome(
      String jobId,
      int rounds,
      int productive,
      int staged,
      int duplicated,
      int handedOff,
      String lastMessage) {}
}
