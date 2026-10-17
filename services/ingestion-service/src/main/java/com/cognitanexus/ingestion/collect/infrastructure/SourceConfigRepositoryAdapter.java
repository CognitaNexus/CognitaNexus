package com.cognitanexus.ingestion.collect.infrastructure;

import com.cognitanexus.ingestion.collect.application.CollectRepositories;
import com.cognitanexus.ingestion.collect.domain.CollectModels.SourceRunConfig;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 来源运行配置仓储。依据：编码规范 §2.2。 */
@Repository
public class SourceConfigRepositoryAdapter implements CollectRepositories.SourceConfigRepository {

  private final CollectMapper mapper;

  public SourceConfigRepositoryAdapter(CollectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<SourceRunConfig> findByCode(String sourceCode) {
    return mapper.findConfigByCode(sourceCode).map(CollectRowMapper::toConfig);
  }

  @Override
  public Optional<SourceRunConfig> findById(String id) {
    return mapper.findConfigById(id).map(CollectRowMapper::toConfig);
  }

  @Override
  public List<SourceRunConfig> listAll() {
    return mapper.listConfigs().stream().map(CollectRowMapper::toConfig).toList();
  }

  @Override
  public boolean register(SourceRunConfig config) {
    int affected =
        mapper.insertConfig(
            config.id(),
            config.sourceCode(),
            config.displayName(),
            config.adapterKind().name(),
            config.baseEndpoint(),
            config.queryExpression(),
            config.pageSize(),
            config.allowedFields().toArray(new String[0]),
            config.configVersion(),
            config.licenseReviewState(),
            config.licenseNote(),
            config.rateLimitPerMinute(),
            config.enabled(),
            config.syncedConfigVersion());
    return affected == 1;
  }

  @Override
  public int setEnabled(String id, boolean enabled) {
    return mapper.setConfigEnabled(id, enabled);
  }

  @Override
  public boolean syncAuthoritativeConfig(String id, List<String> allowedFields, int configVersion) {
    int affected =
        mapper.syncAuthoritativeConfig(id, allowedFields.toArray(new String[0]), configVersion);
    return affected == 1;
  }

  @Override
  public int updateRunParameters(
      String id,
      String queryExpression,
      Integer pageSize,
      String licenseReviewState,
      String licenseNote) {
    return mapper.updateRunParameters(
        id, queryExpression, pageSize, licenseReviewState, licenseNote);
  }

  @Override
  public int updateUpdatePlan(
      String id,
      Integer newRecordIntervalMinutes,
      Integer fieldRefreshIntervalMinutes,
      String note) {
    return mapper.updateUpdatePlan(id, newRecordIntervalMinutes, fieldRefreshIntervalMinutes, note);
  }

  @Override
  public int advanceNextDueAt(String id, java.time.Instant nextDueAt) {
    return mapper.advanceNextDueAt(id, nextDueAt);
  }
}
