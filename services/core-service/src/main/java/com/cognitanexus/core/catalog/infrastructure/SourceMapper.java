package com.cognitanexus.core.catalog.infrastructure;

import com.cognitanexus.core.catalog.domain.SourceRecord;
import com.cognitanexus.sharedkernel.DataNature;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 来源域持久化（来源配置与原始来源版本）。
 *
 * <p>依据：架构 V0.2 §5.2、§6.2。所有列显式列出并显式别名：
 * 既满足「禁止 SELECT *」，也让行对象的构造器装配不依赖下划线到驼峰的隐式转换。
 */
@Mapper
public interface SourceMapper {

  record SourceConfigRow(
      String id,
      String sourceCode,
      String displayName,
      boolean enabled,
      String licenseScope,
      String baseUrl,
      int configVersion,
      String allowedFieldsJson,
      String displayLicenseStatus,
      Instant displayLicenseChangedAt,
      String displayLicenseNote) {}

  @Select(
      """
      SELECT id            AS id,
             source_code   AS sourceCode,
             display_name  AS displayName,
             enabled       AS enabled,
             license_scope AS licenseScope,
             base_url      AS baseUrl,
             config_version AS configVersion,
             allowed_fields::text AS allowedFieldsJson,
             display_license_status   AS displayLicenseStatus,
             display_license_changed_at AS displayLicenseChangedAt,
             display_license_note     AS displayLicenseNote
      FROM raw_source_config
      WHERE source_code = #{sourceCode}
      """)
  Optional<SourceConfigRow> findConfigByCode(@Param("sourceCode") String sourceCode);

  @Select(
      """
      SELECT id            AS id,
             source_code   AS sourceCode,
             display_name  AS displayName,
             enabled       AS enabled,
             license_scope AS licenseScope,
             base_url      AS baseUrl,
             config_version AS configVersion,
             allowed_fields::text AS allowedFieldsJson,
             display_license_status   AS displayLicenseStatus,
             display_license_changed_at AS displayLicenseChangedAt,
             display_license_note     AS displayLicenseNote
      FROM raw_source_config
      WHERE id = #{id}
      """)
  Optional<SourceConfigRow> findConfigById(@Param("id") String id);

  @Select(
      """
      SELECT id            AS id,
             source_code   AS sourceCode,
             display_name  AS displayName,
             enabled       AS enabled,
             license_scope AS licenseScope,
             base_url      AS baseUrl,
             config_version AS configVersion,
             allowed_fields::text AS allowedFieldsJson,
             display_license_status   AS displayLicenseStatus,
             display_license_changed_at AS displayLicenseChangedAt,
             display_license_note     AS displayLicenseNote
      FROM raw_source_config
      ORDER BY source_code
      """)
  List<SourceConfigRow> listConfigs();

  @Insert(
      """
      INSERT INTO raw_source_config
        (id, source_code, display_name, enabled, license_scope, base_url, config_version,
         allowed_fields)
      VALUES
        (#{id}, #{sourceCode}, #{displayName}, #{enabled}, #{licenseScope}, #{baseUrl}, 1,
         #{allowedFields}::jsonb)
      ON CONFLICT (source_code) DO NOTHING
      """)
  int insertConfig(
      @Param("id") String id,
      @Param("sourceCode") String sourceCode,
      @Param("displayName") String displayName,
      @Param("enabled") boolean enabled,
      @Param("licenseScope") String licenseScope,
      @Param("baseUrl") String baseUrl,
      @Param("allowedFields") String allowedFields);

  /**
   * 采集开关。
   *
   * <p>V11 起它<b>只</b>影响任务调度：不再有任何可见性收紧。收紧改由 {@link #setDisplayLicense}
   * 与撤权作业承担。ADR-001 §3 方案 A。
   */
  @Update(
      """
      UPDATE raw_source_config
      SET enabled = #{enabled}, updated_at = now()
      WHERE id = #{sourceId}
      """)
  int setEnabled(@Param("sourceId") String sourceId, @Param("enabled") boolean enabled);

  /**
   * 展示许可状态。
   *
   * <p>撤销时必须留时间点与依据：只改状态位的话，事后无法回答「这条成果是撤权之后才下线的，
   * 还是之前就已经被人工限制」。恢复许可时同样写入时间点，但不保留旧的撤销依据（它描述的是
   * 上一次撤权，留着会让「为什么现在又允许了」变得无法解释）。
   *
   * <p>{@code WHERE display_license_status <> #{status}} 让重复撤权表现为 0 行影响，
   * 而不是把 changed_at 悄悄刷新成「刚刚」。
   */
  @Update(
      """
      UPDATE raw_source_config
      SET display_license_status = #{status},
          display_license_changed_at = now(),
          display_license_note = #{note},
          updated_at = now()
      WHERE id = #{sourceId}
        AND display_license_status <> #{status}
      """)
  int setDisplayLicense(
      @Param("sourceId") String sourceId,
      @Param("status") String status,
      @Param("note") String note);

  @Update(
      """
      UPDATE raw_source_config
      SET allowed_fields = #{allowedFields}::jsonb,
          config_version = config_version + 1,
          updated_at = now()
      WHERE id = #{sourceId}
      """)
  int updateAllowedFields(
      @Param("sourceId") String sourceId, @Param("allowedFields") String allowedFields);

  record SourceRecordRow(
      String id,
      String sourceId,
      String sourceRecordId,
      String contentFingerprint,
      String payloadJson,
      String dataNature,
      Instant sourceUpdatedAt,
      LocalDate sourcePublishedAt,
      Instant fetchedAt,
      Instant receivedAt,
      long catalogRevision) {}

  /**
   * 首次接收原始来源版本。
   *
   * <p>ON CONFLICT DO NOTHING 使幂等重复表现为 0 行影响而不是让事务中止；
   * 重复时调用方返回原接收结果，且不改动任何原值。
   */
  @Insert(
      """
      INSERT INTO raw_source_record
        (id, source_id, source_record_id, content_fingerprint, payload, data_nature,
         source_updated_at, source_published_at, fetched_at, catalog_revision)
      VALUES
        (#{id}, #{sourceId}, #{sourceRecordId}, #{contentFingerprint}, #{payloadJson}::jsonb,
         #{dataNature}, #{sourceUpdatedAt}, #{sourcePublishedAt}, #{fetchedAt}, #{catalogRevision})
      ON CONFLICT (source_id, source_record_id, content_fingerprint) DO NOTHING
      """)
  int insertIfAbsent(
      @Param("id") String id,
      @Param("sourceId") String sourceId,
      @Param("sourceRecordId") String sourceRecordId,
      @Param("contentFingerprint") String contentFingerprint,
      @Param("payloadJson") String payloadJson,
      @Param("dataNature") String dataNature,
      @Param("sourceUpdatedAt") Instant sourceUpdatedAt,
      @Param("sourcePublishedAt") LocalDate sourcePublishedAt,
      @Param("fetchedAt") Instant fetchedAt,
      @Param("catalogRevision") long catalogRevision);

  @Select(
      """
      SELECT id                 AS id,
             source_id          AS sourceId,
             source_record_id   AS sourceRecordId,
             content_fingerprint AS contentFingerprint,
             payload::text      AS payloadJson,
             data_nature        AS dataNature,
             source_updated_at  AS sourceUpdatedAt,
             source_published_at AS sourcePublishedAt,
             fetched_at         AS fetchedAt,
             received_at        AS receivedAt,
             catalog_revision   AS catalogRevision
      FROM raw_source_record
      WHERE source_id = #{sourceId}
        AND source_record_id = #{sourceRecordId}
        AND content_fingerprint = #{contentFingerprint}
      """)
  Optional<SourceRecordRow> findByHandoffKey(
      @Param("sourceId") String sourceId,
      @Param("sourceRecordId") String sourceRecordId,
      @Param("contentFingerprint") String contentFingerprint);

  /**
   * 用交接键反查当时落到的成果。
   *
   * <p>同一来源记录可能演化出多个内容版本，反查取最早关联的那个，使重复交接能返回一致的接收结果。
   */
  @Select(
      """
      SELECT cas.achievement_id
      FROM raw_source_record rsr
      JOIN core_achievement_source cas ON cas.source_record_id = rsr.id
      WHERE rsr.source_id = #{sourceId} AND rsr.source_record_id = #{sourceRecordId}
      ORDER BY rsr.first_received_at, rsr.id
      LIMIT 1
      """)
  Optional<String> findLinkedAchievementId(
      @Param("sourceId") String sourceId, @Param("sourceRecordId") String sourceRecordId);

  @Select("SELECT count(*) FROM raw_source_record")
  long countAll();

  default SourceRecord toDomain(SourceRecordRow row) {
    return new SourceRecord(
        row.id(),
        row.sourceId(),
        row.sourceRecordId(),
        row.contentFingerprint(),
        row.payloadJson(),
        DataNature.valueOf(row.dataNature()),
        row.sourceUpdatedAt(),
        row.sourcePublishedAt(),
        row.fetchedAt(),
        row.receivedAt(),
        row.catalogRevision());
  }
}
