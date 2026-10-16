package com.cognitanexus.core.catalog.infrastructure;

import com.cognitanexus.core.catalog.application.CatalogRepositories.SourceConfigRepository;
import com.cognitanexus.core.catalog.application.CatalogRepositories.SourceRecordRepository;
import com.cognitanexus.core.catalog.domain.SourceRecord;
import com.cognitanexus.core.platform.persistence.JsonCodec;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 来源域仓储实现。
 *
 * <p>一个实现类承担来源配置与来源记录两个端口：两者共享同一张配置表的外键关系与同一份事务语义，
 * 拆成两个 bean 只会增加装配噪音而不产生边界收益。
 */
@Repository
public class CatalogSourceRepositories implements SourceConfigRepository, SourceRecordRepository {

  private final SourceMapper mapper;
  private final JsonCodec json;

  public CatalogSourceRepositories(SourceMapper mapper, JsonCodec json) {
    this.mapper = mapper;
    this.json = json;
  }

  @Override
  public Optional<SourceConfig> findByCode(String sourceCode) {
    return mapper.findConfigByCode(sourceCode).map(this::toConfig);
  }

  @Override
  public Optional<SourceConfig> findById(String id) {
    return mapper.findConfigById(id).map(this::toConfig);
  }

  @Override
  public List<SourceConfig> listAll() {
    return mapper.listConfigs().stream().map(this::toConfig).toList();
  }

  @Override
  public boolean register(
      String id,
      String sourceCode,
      String displayName,
      boolean enabled,
      String licenseScope,
      String baseUrl,
      List<String> allowedFields) {
    int affected =
        mapper.insertConfig(
            id,
            sourceCode,
            displayName,
            enabled,
            licenseScope,
            baseUrl,
            json.write(allowedFields == null ? List.of() : allowedFields));
    return affected == 1;
  }

  @Override
  public int setEnabled(String sourceId, boolean enabled) {
    return mapper.setEnabled(sourceId, enabled);
  }

  @Override
  public int updateAllowedFields(String sourceId, List<String> allowedFields) {
    return mapper.updateAllowedFields(sourceId, json.write(allowedFields));
  }

  @Override
  public boolean insertIfAbsent(SourceRecord record) {
    int affected =
        mapper.insertIfAbsent(
            record.id(),
            record.sourceId(),
            record.sourceRecordId(),
            record.contentFingerprint(),
            record.payloadJson(),
            record.dataNature().name(),
            record.sourceUpdatedAt(),
            record.sourcePublishedAt(),
            record.fetchedAt(),
            record.catalogRevision());
    return affected == 1;
  }

  @Override
  public Optional<SourceRecord> findByHandoffKey(
      String sourceId, String sourceRecordId, String contentFingerprint) {
    return mapper
        .findByHandoffKey(sourceId, sourceRecordId, contentFingerprint)
        .map(mapper::toDomain);
  }

  @Override
  public Optional<String> findLinkedAchievementId(String sourceId, String sourceRecordId) {
    return mapper.findLinkedAchievementId(sourceId, sourceRecordId);
  }

  @Override
  public long countAll() {
    return mapper.countAll();
  }

  private SourceConfig toConfig(SourceMapper.SourceConfigRow row) {
    List<String> allowed = json.readStringList(row.allowedFieldsJson());
    return new SourceConfig(
        row.id(),
        row.sourceCode(),
        row.displayName(),
        row.enabled(),
        row.licenseScope(),
        row.baseUrl(),
        row.configVersion(),
        allowed);
  }
}
