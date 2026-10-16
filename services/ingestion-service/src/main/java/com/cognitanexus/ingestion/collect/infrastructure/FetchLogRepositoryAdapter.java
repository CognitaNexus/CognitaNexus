package com.cognitanexus.ingestion.collect.infrastructure;

import com.cognitanexus.ingestion.collect.application.CollectRepositories;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 采集运行日志仓储（只追加）。
 *
 * <p>依据：编码规范 §5.1。运行账号对 {@code ing_fetch_log} 只有 INSERT/SELECT 权限（见迁移
 * V2），所以这里自然地没有「改日志」的方法。
 */
@Repository
public class FetchLogRepositoryAdapter implements CollectRepositories.FetchLogRepository {

  private final CollectMapper mapper;

  public FetchLogRepositoryAdapter(CollectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void append(
      String id,
      String jobId,
      String sourceConfigId,
      String phase,
      String outcome,
      Integer fetchedCount,
      Integer stagedCount,
      Integer rejectedCount,
      String detailJson,
      String traceId) {
    mapper.insertLog(
        id,
        jobId,
        sourceConfigId,
        phase,
        outcome,
        fetchedCount,
        stagedCount,
        rejectedCount,
        detailJson == null ? "{}" : detailJson,
        traceId);
  }

  @Override
  public List<FetchLogEntry> listByJob(String jobId, int limit) {
    return mapper.listLogsByJob(jobId, limit).stream().map(CollectRowMapper::toLog).toList();
  }
}
