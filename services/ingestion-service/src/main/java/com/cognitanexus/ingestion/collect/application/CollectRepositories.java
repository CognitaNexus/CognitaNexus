package com.cognitanexus.ingestion.collect.application;

import com.cognitanexus.ingestion.collect.domain.CollectModels.CollectJob;
import com.cognitanexus.ingestion.collect.domain.CollectModels.SourceRunConfig;
import com.cognitanexus.ingestion.collect.domain.CollectModels.StagedRecord;
import com.cognitanexus.ingestion.collect.domain.HandoffState;
import com.cognitanexus.ingestion.collect.domain.JobStatus;
import com.cognitanexus.sharedkernel.DataNature;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 采集模块的持久化出站端口。
 *
 * <p>依据：编码规范 §2.2。端口由应用层定义、基础设施实现，装配层通过构造器注入；禁止把端口
 * 放在 infrastructure 后要求领域反向依赖。
 *
 * <p>本接口自身只作命名空间，不声明方法：五个仓储是彼此独立的端口，应用层按需注入所需的
 * 那一个。之所以不做成「一个容器接口 + 一个 now()」的形态，是因为先前把五个仓储实现在同一个
 * 类里会导致同名方法（如 {@code findById}）的返回类型互相冲突而无法编译；拆成五个实现类后，
 * 容器上再挂一个无人调用的 {@code now()} 就只是死端口。
 */
public interface CollectRepositories {

  /** 来源运行配置仓储。 */
  interface SourceConfigRepository {

    Optional<SourceRunConfig> findByCode(String sourceCode);

    Optional<SourceRunConfig> findById(String id);

    List<SourceRunConfig> listAll();

    /** 已存在同 source_code 时返回 false，不覆盖既有配置。 */
    boolean register(SourceRunConfig config);

    int setEnabled(String id, boolean enabled);

    /**
     * 同步来自 core 的权威配置版本与字段白名单。
     *
     * @return 本次是否确实发生了同步（版本不同才更新）
     */
    boolean syncAuthoritativeConfig(String id, List<String> allowedFields, int configVersion);

    int updateRunParameters(
        String id,
        String queryExpression,
        Integer pageSize,
        String licenseReviewState,
        String licenseNote);

    /**
     * 设定业务更新周期（发现新记录／刷新历史字段各自独立，均可为 null 表示未设定）。
     *
     * @return 受影响行数；0 表示配置不存在
     */
    int updateUpdatePlan(
        String id,
        Integer newRecordIntervalMinutes,
        Integer fieldRefreshIntervalMinutes,
        String note);

    /** 一次按周期的执行收尾后前移下次到期时间；传 null 表示撤销周期。 */
    int advanceNextDueAt(String id, java.time.Instant nextDueAt);
  }

  /** 采集任务仓储。 */
  interface JobRepository {

    Optional<CollectJob> findById(String id);

    Optional<CollectJob> findByKey(String sourceConfigId, String jobKey);

    List<CollectJob> listRecent(int limit);

    List<CollectJob> listBySource(String sourceConfigId, int limit);

    boolean insert(String id, String sourceConfigId, String jobKey, String createdBy);

    /** 领取租约；返回是否成功。未领到不代表失败，可能只是被其他实例持有。 */
    boolean claimLease(String jobId, String owner, int leaseSeconds);

    boolean renewLease(String jobId, String owner, int leaseSeconds);

    boolean releaseLease(String jobId, String owner);

    int recordFetchProgress(
        String jobId, String cursor, int fetchedDelta, int stagedDelta, int rejectedDelta);

    /** 前移检查点；返回是否实际前移。 */
    boolean advanceCheckpoint(String jobId, String checkpoint, int handedOffDelta);

    int updateStatus(String jobId, JobStatus status, String lastError);

    int recordError(String jobId, String lastError);

    /**
     * 记录一次抓取失败并把下一次尝试推到 {@code backoffSeconds} 秒之后。
     *
     * <p>抓取失败的退避此前是缺失的：任务由固定间隔的调度驱动，上游持续失败时每 2 秒重试一次、
     * 每次都从同一游标重新翻页。失败计数与退避时刻必须一次写成，否则中间状态会被取任务的查询
     * 当成「可以立刻重试」。
     */
    int recordFetchFailure(String jobId, String lastError, int backoffSeconds);

    /** 抓取成功：连续失败计数归零、退避窗口取消。已经归零时不产生写入。 */
    int clearFetchFailures(String jobId);

    long countOpenJobs();

    /**
     * 可执行的任务标识，供后台执行器轮询；不含已暂停、已结束以及仍在退避窗口内的任务。
     *
     * <p>退避在取任务时过滤，而不是让执行器自己去判断该不该跳过：把「哪些任务现在能做」收在一处，
     * 后台执行器就只是「拿到就做」，不会出现两处各有一套判据。
     */
    List<String> listRunnableJobIds(int limit);
  }

  /** 交接暂存仓储。 */
  interface StagedRepository {

    /** 按稳定交接键幂等写入；已存在时返回 false 且不产生新行。 */
    boolean insertIfAbsent(
        String id,
        String jobId,
        String sourceConfigId,
        String sourceRecordId,
        String contentFingerprint,
        String payloadJson,
        DataNature dataNature,
        Instant sourceUpdatedAt,
        LocalDate sourcePublishedAt,
        Instant fetchedAt);

    /** 取本任务自己暂存的待交接记录；按任务而非按来源，理由见 CollectMapper 中的说明。 */
    List<StagedRecord> listPendingForHandoff(String jobId, int limit);

    List<StagedRecord> listByBatch(String batchId);

    /** 建立批次时把记录挂到批次上，使「按批次取记录」在交接前就可用。 */
    int assignToBatch(List<String> recordIds, String batchId);

    int markHandedOff(String id, String batchId, String receiptStatus, String achievementId);

    int markFailed(String id, String error);

    int incrementAttempt(String id);

    /** 各交接状态的数量。 */
    java.util.Map<HandoffState, Long> countByState(String sourceConfigId);

    long countAll();
  }

  /** 交接批次仓储。 */
  interface HandoffBatchRepository {

    record HandoffBatch(
        String id,
        String jobId,
        String batchKey,
        String status,
        int recordCount,
        int attempts,
        Instant nextAttemptAt,
        Instant ackedAt,
        String lastError,
        String coreReceiptJson,
        Instant createdAt) {}

    /** 按幂等键创建；已存在时返回 false。 */
    boolean insert(String id, String jobId, String batchKey, int recordCount);

    Optional<HandoffBatch> findByKey(String batchKey);

    Optional<HandoffBatch> findNextPending(String jobId);

    List<HandoffBatch> listByJob(String jobId, int limit);

    int markAcked(String id, String receiptJson);

    int markFailed(String id, String status, String error, int backoffSeconds);

    long countPending();

    long countFailed();
  }

  /** 运行日志仓储（只追加）。 */
  interface FetchLogRepository {

    record FetchLogEntry(
        String id,
        String jobId,
        String phase,
        String outcome,
        Integer fetchedCount,
        Integer stagedCount,
        Integer rejectedCount,
        String detailJson,
        Instant occurredAt) {}

    void append(
        String id,
        String jobId,
        String sourceConfigId,
        String phase,
        String outcome,
        Integer fetchedCount,
        Integer stagedCount,
        Integer rejectedCount,
        String detailJson,
        String traceId);

    List<FetchLogEntry> listByJob(String jobId, int limit);
  }
}
