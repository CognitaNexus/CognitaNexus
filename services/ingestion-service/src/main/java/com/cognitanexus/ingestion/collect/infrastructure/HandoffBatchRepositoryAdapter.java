package com.cognitanexus.ingestion.collect.infrastructure;

import com.cognitanexus.ingestion.collect.application.CollectRepositories;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 交接批次仓储。
 *
 * <p>依据：架构 V0.2 §6.2。批次键是 core 侧识别「同一批重试」的依据，因此创建走唯一约束幂等
 * （见 {@link CollectMapper}），重试时复用同一个键而不新建批次。
 */
@Repository
public class HandoffBatchRepositoryAdapter implements CollectRepositories.HandoffBatchRepository {

  private final CollectMapper mapper;

  public HandoffBatchRepositoryAdapter(CollectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public boolean insert(String id, String jobId, String batchKey, int recordCount) {
    return mapper.insertBatch(id, jobId, batchKey, recordCount) == 1;
  }

  @Override
  public Optional<HandoffBatch> findByKey(String batchKey) {
    return mapper.findBatchByKey(batchKey).map(CollectRowMapper::toBatch);
  }

  @Override
  public Optional<HandoffBatch> findNextPending(String jobId) {
    return mapper.findNextPendingBatch(jobId).map(CollectRowMapper::toBatch);
  }

  @Override
  public List<HandoffBatch> listByJob(String jobId, int limit) {
    return mapper.listBatchesByJob(jobId, limit).stream().map(CollectRowMapper::toBatch).toList();
  }

  @Override
  public int markAcked(String id, String receiptJson) {
    return mapper.markBatchAcked(id, receiptJson);
  }

  @Override
  public int markFailed(String id, String status, String error, int backoffSeconds) {
    return mapper.markBatchFailed(id, status, error, backoffSeconds);
  }

  @Override
  public long countPending() {
    return mapper.countPendingBatches();
  }

  @Override
  public long countFailed() {
    return mapper.countFailedBatches();
  }
}
