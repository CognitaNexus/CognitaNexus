package com.cognitanexus.core.catalog.infrastructure;

import com.cognitanexus.core.catalog.domain.Scholar;
import com.cognitanexus.sharedkernel.DataNature;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 学者域持久化：学者档案与姓名别名。
 *
 * <p>依据：架构 V0.2 §5.4、BR-IMP-03。同名不是同人证据：本层只提供精确归一匹配，
 * 「是否同一人」的判断在治理层并须有凭证，别名归一只产候选。
 */
@Mapper
public interface ScholarMapper {

  record ScholarRow(
      String id,
      String displayName,
      String nameNorm,
      String dataNature,
      String claimedStatus,
      String organizationName,
      String researchSummary,
      String mergedIntoId,
      long version,
      Instant createdAt,
      Instant updatedAt) {}

  @Insert(
      """
      INSERT INTO core_scholar
        (id, display_name, name_norm, data_nature, claimed_status, organization_name,
         research_summary, version)
      VALUES
        (#{id}, #{displayName}, #{nameNorm}, #{dataNature}, #{claimedStatus}, #{organizationName},
         #{researchSummary}, 1)
      """)
  void insert(
      @Param("id") String id,
      @Param("displayName") String displayName,
      @Param("nameNorm") String nameNorm,
      @Param("dataNature") String dataNature,
      @Param("claimedStatus") String claimedStatus,
      @Param("organizationName") String organizationName,
      @Param("researchSummary") String researchSummary);

  @Select(
      """
      SELECT id AS id, display_name AS displayName, name_norm AS nameNorm,
             data_nature AS dataNature, claimed_status AS claimedStatus,
             organization_name AS organizationName, research_summary AS researchSummary,
             merged_into_id AS mergedIntoId, version AS version,
             created_at AS createdAt, updated_at AS updatedAt
      FROM core_scholar WHERE id = #{id}
      """)
  Optional<ScholarRow> findById(@Param("id") String id);

  @Select(
      """
      SELECT id AS id, display_name AS displayName, name_norm AS nameNorm,
             data_nature AS dataNature, claimed_status AS claimedStatus,
             organization_name AS organizationName, research_summary AS researchSummary,
             merged_into_id AS mergedIntoId, version AS version,
             created_at AS createdAt, updated_at AS updatedAt
      FROM core_scholar
      WHERE name_norm = #{nameNorm} AND merged_into_id IS NULL
      ORDER BY id
      LIMIT #{limit}
      """)
  List<ScholarRow> findByExactNameNorm(
      @Param("nameNorm") String nameNorm, @Param("limit") int limit);

  @Insert(
      """
      INSERT INTO core_scholar_name_variant (id, scholar_id, variant, variant_norm, evidence)
      VALUES (#{id}, #{scholarId}, #{variant}, #{variantNorm}, #{evidence})
      ON CONFLICT (scholar_id, variant_norm) DO NOTHING
      """)
  int insertNameVariant(
      @Param("id") String id,
      @Param("scholarId") String scholarId,
      @Param("variant") String variant,
      @Param("variantNorm") String variantNorm,
      @Param("evidence") String evidence);

  /**
   * 记录来源给出的机构线索。
   *
   * <p>只在该字段为空时写入，且不覆盖已有值：当前任职机构与「成果发生时的机构归属」是两件事，
   * 后者存在参与关系上（BR-IMP-06）。
   */
  @Update(
      """
      UPDATE core_scholar
      SET organization_name = #{organizationName}, updated_at = now()
      WHERE id = #{scholarId}
        AND (organization_name IS NULL OR organization_name = '')
      """)
  int upsertOrganizationHint(
      @Param("scholarId") String scholarId, @Param("organizationName") String organizationName);

  @Update(
      """
      UPDATE core_scholar
      SET claimed_status = #{claimedStatus}, version = version + 1, updated_at = now()
      WHERE id = #{scholarId} AND version = #{expectedVersion}
      """)
  int updateClaimedStatus(
      @Param("scholarId") String scholarId,
      @Param("claimedStatus") String claimedStatus,
      @Param("expectedVersion") long expectedVersion);

  /** 认领状态流转移：只有指定前置状态才可迁移，受影响行数须为 1。 */
  @Update(
      """
      UPDATE core_scholar
      SET claimed_status = #{toStatus}, version = version + 1, updated_at = now()
      WHERE id = #{scholarId} AND claimed_status = #{fromStatus}
      """)
  int transitionClaimedStatus(
      @Param("scholarId") String scholarId,
      @Param("fromStatus") String fromStatus,
      @Param("toStatus") String toStatus);

  @Update(
      """
      UPDATE core_scholar
      SET research_summary = #{researchSummary}, version = version + 1, updated_at = now()
      WHERE id = #{scholarId}
      """)
  int updateResearchSummary(
      @Param("scholarId") String scholarId, @Param("researchSummary") String researchSummary);

  @Update(
      """
      UPDATE core_scholar
      SET organization_name = #{organizationName}, version = version + 1, updated_at = now()
      WHERE id = #{scholarId}
      """)
  int updateOrganizationName(
      @Param("scholarId") String scholarId, @Param("organizationName") String organizationName);

  @Select("SELECT merged_into_id FROM core_scholar WHERE id = #{id}")
  Optional<String> findMergedIntoId(@Param("id") String id);

  @Select("SELECT count(*) FROM core_scholar WHERE merged_into_id IS NULL")
  long countAll();

  @Select(
      """
      SELECT entity_id FROM core_identifier_registry
      WHERE identifier_kind = #{kind} AND normalized_value = #{normalizedValue}
      """)
  Optional<String> findRegisteredScholar(
      @Param("kind") String kind, @Param("normalizedValue") String normalizedValue);

  default Scholar toDomain(ScholarRow row) {
    return new Scholar(
        row.id(),
        row.displayName(),
        row.nameNorm(),
        DataNature.valueOf(row.dataNature()),
        Scholar.ClaimedStatus.of(row.claimedStatus()),
        row.organizationName(),
        row.researchSummary(),
        row.mergedIntoId(),
        row.version(),
        row.createdAt(),
        row.updatedAt());
  }
}
