package com.cognitanexus.ingestion.collect.infrastructure;

import com.cognitanexus.ingestion.collect.application.CollectRepositories.FetchLogRepository.FetchLogEntry;
import com.cognitanexus.ingestion.collect.application.CollectRepositories.HandoffBatchRepository.HandoffBatch;
import com.cognitanexus.ingestion.collect.domain.AdapterKind;
import com.cognitanexus.ingestion.collect.domain.CollectModels.CollectJob;
import com.cognitanexus.ingestion.collect.domain.CollectModels.SourceRunConfig;
import com.cognitanexus.ingestion.collect.domain.CollectModels.StagedRecord;
import com.cognitanexus.ingestion.collect.domain.HandoffState;
import com.cognitanexus.ingestion.collect.domain.JobStatus;
import com.cognitanexus.sharedkernel.DataNature;
import java.util.List;

/**
 * 行对象到领域对象的转换。
 *
 * <p>依据：编码规范 §2.2。这些转换被五个仓储实现共享，所以放在一个包内可见的类里，而不是让每个
 * 实现类各写一份——重复的映射代码在列增删时会只改一处、漏改其余，产生「同一张表两种读法」。
 *
 * <p>转换是纯函数，不含数据库访问，因此不构成「一个类承担多个职责」的问题。
 */
final class CollectRowMapper {

  private CollectRowMapper() {}

  static SourceRunConfig toConfig(CollectMapper.SourceRunConfigRow row) {
    return new SourceRunConfig(
        row.id(),
        row.sourceCode(),
        row.displayName(),
        AdapterKind.of(row.adapterKind()),
        row.baseEndpoint(),
        row.queryExpression(),
        row.pageSize(),
        // text[] 未收录时给出空列表：空白名单意味着「不允许任何字段」，比缺省放行安全
        row.allowedFields() == null ? List.of() : List.of(row.allowedFields()),
        row.configVersion(),
        row.licenseReviewState(),
        row.licenseNote(),
        row.rateLimitPerMinute(),
        row.enabled(),
        row.syncedConfigVersion());
  }

  static CollectJob toJob(CollectMapper.JobRow row) {
    return new CollectJob(
        row.id(),
        row.sourceConfigId(),
        row.jobKey(),
        JobStatus.of(row.status()),
        row.cursor(),
        row.checkpoint(),
        row.totalFetched(),
        row.totalStaged(),
        row.totalHandedOff(),
        row.totalRejected(),
        row.leaseOwner(),
        row.leaseUntil(),
        row.lastError(),
        row.createdBy(),
        row.consecutiveFailures(),
        row.nextAttemptAt());
  }

  static StagedRecord toStaged(CollectMapper.StagedRow row) {
    return new StagedRecord(
        row.id(),
        row.jobId(),
        row.sourceConfigId(),
        row.sourceRecordId(),
        row.contentFingerprint(),
        row.payloadJson(),
        DataNature.valueOf(row.dataNature()),
        row.sourceUpdatedAt(),
        row.sourcePublishedAt(),
        row.fetchedAt(),
        HandoffState.valueOf(row.handoffState()),
        row.handoffBatchId(),
        row.handoffAttempts(),
        row.lastError(),
        row.coreReceiptStatus(),
        row.coreAchievementId());
  }

  static HandoffBatch toBatch(CollectMapper.BatchRow row) {
    return new HandoffBatch(
        row.id(),
        row.jobId(),
        row.batchKey(),
        row.status(),
        row.recordCount(),
        row.attempts(),
        row.nextAttemptAt(),
        row.ackedAt(),
        row.lastError(),
        row.coreReceiptJson(),
        row.createdAt());
  }

  static FetchLogEntry toLog(CollectMapper.LogRow row) {
    return new FetchLogEntry(
        row.id(),
        row.jobId(),
        row.phase(),
        row.outcome(),
        row.fetchedCount(),
        row.stagedCount(),
        row.rejectedCount(),
        row.detailJson(),
        row.occurredAt());
  }
}
