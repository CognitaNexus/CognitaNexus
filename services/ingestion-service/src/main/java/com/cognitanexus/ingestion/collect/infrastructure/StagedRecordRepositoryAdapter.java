package com.cognitanexus.ingestion.collect.infrastructure;

import com.cognitanexus.ingestion.collect.application.CollectRepositories;
import com.cognitanexus.ingestion.collect.domain.CollectModels.StagedRecord;
import com.cognitanexus.ingestion.collect.domain.HandoffState;
import com.cognitanexus.sharedkernel.DataNature;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * 交接暂存仓储。
 *
 * <p>依据：编码规范 §2.2。写入一律走「稳定交接键」上的唯一约束（见 {@link CollectMapper}），
 * 因此本类不做「先查再插」的补偿逻辑：那会在并发下产生检查与写入之间的竞态。
 */
@Repository
public class StagedRecordRepositoryAdapter implements CollectRepositories.StagedRepository {

  private final CollectMapper mapper;

  public StagedRecordRepositoryAdapter(CollectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public boolean insertIfAbsent(
      String id,
      String jobId,
      String sourceConfigId,
      String sourceRecordId,
      String contentFingerprint,
      String payloadJson,
      DataNature dataNature,
      Instant sourceUpdatedAt,
      LocalDate sourcePublishedAt,
      Instant fetchedAt) {
    int affected =
        mapper.insertStagedIfAbsent(
            id,
            jobId,
            sourceConfigId,
            sourceRecordId,
            contentFingerprint,
            payloadJson,
            dataNature.name(),
            sourceUpdatedAt,
            sourcePublishedAt,
            fetchedAt);
    return affected == 1;
  }

  @Override
  public List<StagedRecord> listPendingForHandoff(String jobId, int limit) {
    return mapper.listPendingForHandoff(jobId, limit).stream()
        .map(CollectRowMapper::toStaged)
        .toList();
  }

  @Override
  public List<StagedRecord> listByBatch(String batchId) {
    return mapper.listByBatch(batchId).stream().map(CollectRowMapper::toStaged).toList();
  }

  @Override
  public int assignToBatch(List<String> recordIds, String batchId) {
    if (recordIds == null || recordIds.isEmpty()) {
      return 0;
    }
    return mapper.assignBatch(batchId, recordIds.toArray(new String[0]));
  }

  @Override
  public int markHandedOff(String id, String batchId, String receiptStatus, String achievementId) {
    return mapper.markHandedOff(id, batchId, receiptStatus, achievementId);
  }

  @Override
  public int markFailed(String id, String error) {
    return mapper.markStagedFailed(id, error);
  }

  @Override
  public int incrementAttempt(String id) {
    return mapper.incrementStagedAttempt(id);
  }

  @Override
  public Map<HandoffState, Long> countByState(String sourceConfigId) {
    Map<HandoffState, Long> result = new EnumMap<>(HandoffState.class);
    for (CollectMapper.StateCountRow row : mapper.countStagedByState(sourceConfigId)) {
      // 状态列在库中受 CHECK 约束，出现未登记取值说明约束与枚举已经不同步，宁可抛错也不要静默丢计数
      result.put(HandoffState.valueOf(row.state()), row.amount());
    }
    return result;
  }

  @Override
  public long countAll() {
    return mapper.countStagedAll();
  }
}
