package com.cognitanexus.ingestion.collect.infrastructure;

import com.cognitanexus.ingestion.collect.application.CollectRepositories;
import com.cognitanexus.ingestion.collect.domain.CollectModels.CollectJob;
import com.cognitanexus.ingestion.collect.domain.JobStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 采集任务仓储。
 *
 * <p>依据：编码规范 §2.2、§5.1。租约相关语句的时钟取自数据库（见 {@link CollectMapper}），
 * 因此本类不做任何本地时间比较。
 */
@Repository
public class CollectJobRepositoryAdapter implements CollectRepositories.JobRepository {

  private final CollectMapper mapper;

  public CollectJobRepositoryAdapter(CollectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<CollectJob> findById(String id) {
    return mapper.findJobById(id).map(CollectRowMapper::toJob);
  }

  @Override
  public Optional<CollectJob> findByKey(String sourceConfigId, String jobKey) {
    return mapper.findJobByKey(sourceConfigId, jobKey).map(CollectRowMapper::toJob);
  }

  @Override
  public List<CollectJob> listRecent(int limit) {
    return mapper.listJobs(limit).stream().map(CollectRowMapper::toJob).toList();
  }

  @Override
  public List<CollectJob> listBySource(String sourceConfigId, int limit) {
    return mapper.listJobsBySource(sourceConfigId, limit).stream()
        .map(CollectRowMapper::toJob)
        .toList();
  }

  @Override
  public boolean insert(String id, String sourceConfigId, String jobKey, String createdBy) {
    return mapper.insertJob(id, sourceConfigId, jobKey, createdBy) == 1;
  }

  @Override
  public boolean claimLease(String jobId, String owner, int leaseSeconds) {
    return mapper.claimLease(jobId, owner, leaseSeconds) == 1;
  }

  @Override
  public boolean renewLease(String jobId, String owner, int leaseSeconds) {
    return mapper.renewLease(jobId, owner, leaseSeconds) == 1;
  }

  @Override
  public boolean releaseLease(String jobId, String owner) {
    return mapper.releaseLease(jobId, owner) == 1;
  }

  @Override
  public int recordFetchProgress(
      String jobId, String cursor, int fetchedDelta, int stagedDelta, int rejectedDelta) {
    return mapper.recordFetchProgress(jobId, cursor, fetchedDelta, stagedDelta, rejectedDelta);
  }

  @Override
  public boolean advanceCheckpoint(String jobId, String checkpoint, int handedOffDelta) {
    return mapper.advanceCheckpoint(jobId, checkpoint, handedOffDelta) == 1;
  }

  @Override
  public int updateStatus(String jobId, JobStatus status, String lastError) {
    return mapper.updateJobStatus(jobId, status.name(), lastError);
  }

  @Override
  public int recordError(String jobId, String lastError) {
    return mapper.recordJobError(jobId, lastError);
  }

  @Override
  public long countOpenJobs() {
    return mapper.countOpenJobs();
  }

  @Override
  public List<String> listRunnableJobIds(int limit) {
    return mapper.listRunnableJobIds(limit);
  }
}
