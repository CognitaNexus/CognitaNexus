package com.cognitanexus.core.governance.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 治理域持久化：去重候选、合并台账、异常，以及合并所需的实体重定向写入。
 *
 * <p>依据：架构 V0.2 §5.2、§5.4。候选状态可变，台账只追加；数据库层对 gov_merge_ledger
 * 另有 REVOKE 兜底，业务代码无法改写历史。
 */
@Mapper
public interface GovernanceMapper {

  // ---------------------------------------------------------------------------
  // 去重候选
  // ---------------------------------------------------------------------------

  record CandidateRow(
      String id,
      String achievementType,
      String leftAchievementId,
      String rightAchievementId,
      String evidenceJson,
      String status,
      Instant createdAt,
      Instant resolvedAt,
      String resolvedBy,
      String resolutionReason) {}

  /**
   * 登记候选。
   *
   * <p>左右顺序在写入前已规范化为字典序，使 (left, right) 与 (right, left) 命中同一条唯一约束，
   * 不会因为比较方向不同而生成两条重复候选。
   */
  @Insert(
      """
      INSERT INTO gov_dedup_candidate
        (id, achievement_type, left_achievement_id, right_achievement_id, evidence, status)
      VALUES
        (#{id}, #{achievementType}, #{leftAchievementId}, #{rightAchievementId},
         #{evidenceJson}::jsonb, 'OPEN')
      ON CONFLICT (left_achievement_id, right_achievement_id) DO NOTHING
      """)
  int insertCandidateIfAbsent(
      @Param("id") String id,
      @Param("achievementType") String achievementType,
      @Param("leftAchievementId") String leftAchievementId,
      @Param("rightAchievementId") String rightAchievementId,
      @Param("evidenceJson") String evidenceJson);

  @Select(
      """
      SELECT id AS id, achievement_type AS achievementType,
             left_achievement_id AS leftAchievementId,
             right_achievement_id AS rightAchievementId,
             evidence::text AS evidenceJson, status AS status, created_at AS createdAt,
             resolved_at AS resolvedAt, resolved_by AS resolvedBy,
             resolution_reason AS resolutionReason
      FROM gov_dedup_candidate
      WHERE status = #{status}
      ORDER BY created_at DESC, id
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<CandidateRow> listCandidates(
      @Param("status") String status, @Param("limit") int limit, @Param("offset") int offset);

  @Select(
      """
      SELECT count(*) FROM gov_dedup_candidate WHERE status = #{status}
      """)
  long countCandidates(@Param("status") String status);

  @Select(
      """
      SELECT id AS id, achievement_type AS achievementType,
             left_achievement_id AS leftAchievementId,
             right_achievement_id AS rightAchievementId,
             evidence::text AS evidenceJson, status AS status, created_at AS createdAt,
             resolved_at AS resolvedAt, resolved_by AS resolvedBy,
             resolution_reason AS resolutionReason
      FROM gov_dedup_candidate WHERE id = #{id}
      """)
  Optional<CandidateRow> findCandidate(@Param("id") String id);

  @Select(
      """
      SELECT count(*) FROM gov_dedup_candidate
      WHERE status = 'OPEN'
        AND (left_achievement_id = #{achievementId} OR right_achievement_id = #{achievementId})
      """)
  long countOpenForAchievement(@Param("achievementId") String achievementId);

  /** 裁决候选。条件 status='OPEN' 保证重复裁决返回 0 行影响而不是覆盖既有结论。 */
  @Update(
      """
      UPDATE gov_dedup_candidate
      SET status = #{status}, resolved_at = now(), resolved_by = #{resolvedBy},
          resolution_reason = #{reason}
      WHERE id = #{id} AND status = 'OPEN'
      """)
  int resolveCandidate(
      @Param("id") String id,
      @Param("status") String status,
      @Param("resolvedBy") String resolvedBy,
      @Param("reason") String reason);

  // ---------------------------------------------------------------------------
  // 合并台账
  // ---------------------------------------------------------------------------

  @Insert(
      """
      INSERT INTO gov_merge_ledger
        (id, operation, survivor_id, merged_id, entity_type, snapshot, reason, operator_id)
      VALUES
        (#{id}, #{operation}, #{survivorId}, #{mergedId}, #{entityType},
         #{snapshotJson}::jsonb, #{reason}, #{operatorId})
      """)
  void insertLedger(
      @Param("id") String id,
      @Param("operation") String operation,
      @Param("survivorId") String survivorId,
      @Param("mergedId") String mergedId,
      @Param("entityType") String entityType,
      @Param("snapshotJson") String snapshotJson,
      @Param("reason") String reason,
      @Param("operatorId") String operatorId);

  record LedgerRow(
      String id,
      String operation,
      String survivorId,
      String mergedId,
      String entityType,
      String snapshotJson,
      String reason,
      String operatorId,
      Instant occurredAt) {}

  @Select(
      """
      SELECT id AS id, operation AS operation, survivor_id AS survivorId,
             merged_id AS mergedId, entity_type AS entityType, snapshot::text AS snapshotJson,
             reason AS reason, operator_id AS operatorId, occurred_at AS occurredAt
      FROM gov_merge_ledger
      WHERE merged_id = #{entityId} OR survivor_id = #{entityId}
      ORDER BY occurred_at DESC, id
      LIMIT #{limit}
      """)
  List<LedgerRow> listLedgerByEntity(@Param("entityId") String entityId, @Param("limit") int limit);

  // ---------------------------------------------------------------------------
  // 异常
  // ---------------------------------------------------------------------------

  @Insert(
      """
      INSERT INTO gov_anomaly (id, kind, source_record_id, entity_id, detail, status)
      VALUES (#{id}, #{kind}, #{sourceRecordId}, #{entityId}, #{detailJson}::jsonb, 'OPEN')
      """)
  void insertAnomaly(
      @Param("id") String id,
      @Param("kind") String kind,
      @Param("sourceRecordId") String sourceRecordId,
      @Param("entityId") String entityId,
      @Param("detailJson") String detailJson);

  record AnomalyRow(
      String id,
      String kind,
      String sourceRecordId,
      String entityId,
      String detailJson,
      String status,
      Instant createdAt,
      Instant resolvedAt) {}

  @Select(
      """
      SELECT id AS id, kind AS kind, source_record_id AS sourceRecordId, entity_id AS entityId,
             detail::text AS detailJson, status AS status, created_at AS createdAt,
             resolved_at AS resolvedAt
      FROM gov_anomaly
      WHERE status = #{status}
      ORDER BY created_at DESC, id
      LIMIT #{limit} OFFSET #{offset}
      """)
  List<AnomalyRow> listAnomalies(
      @Param("status") String status, @Param("limit") int limit, @Param("offset") int offset);

  @Select("SELECT count(*) FROM gov_anomaly WHERE status = #{status}")
  long countAnomalies(@Param("status") String status);

  @Update(
      """
      UPDATE gov_anomaly SET status = 'RESOLVED', resolved_at = now()
      WHERE id = #{id} AND status = 'OPEN'
      """)
  int resolveAnomaly(@Param("id") String id);

  // ---------------------------------------------------------------------------
  // 合并所需的实体操作
  // ---------------------------------------------------------------------------

  /** 标记被合并实体并建立重定向。条件 merged_into_id IS NULL 防止二次合并覆盖首次重定向。 */
  @Update(
      """
      UPDATE core_achievement
      SET merged_into_id = #{survivorId}, effective_status = 'WITHDRAWN',
          version = version + 1, updated_at = now()
      WHERE id = #{mergedId} AND merged_into_id IS NULL AND id <> #{survivorId}
      """)
  int markAchievementMerged(
      @Param("mergedId") String mergedId, @Param("survivorId") String survivorId);

  @Update(
      """
      UPDATE core_scholar
      SET merged_into_id = #{survivorId}, version = version + 1, updated_at = now()
      WHERE id = #{mergedId} AND merged_into_id IS NULL AND id <> #{survivorId}
      """)
  int markScholarMerged(@Param("mergedId") String mergedId, @Param("survivorId") String survivorId);

  @Select(
      """
      SELECT achievement_id FROM core_achievement_source WHERE id = #{id}
      """)
  Optional<String> findSourceLinkAchievement(@Param("id") String id);

  @Update(
      """
      UPDATE core_achievement_source SET achievement_id = #{survivorId}
      WHERE achievement_id = #{mergedId}
        AND NOT EXISTS (
          SELECT 1 FROM core_achievement_source s2
          WHERE s2.achievement_id = #{survivorId} AND s2.source_record_id = core_achievement_source.source_record_id
        )
      """)
  int moveSourceLinks(@Param("mergedId") String mergedId, @Param("survivorId") String survivorId);

  @Update(
      """
      UPDATE core_achievement_topic SET achievement_id = #{survivorId}
      WHERE achievement_id = #{mergedId}
        AND NOT EXISTS (
          SELECT 1 FROM core_achievement_topic t2
          WHERE t2.achievement_id = #{survivorId} AND t2.topic_id = core_achievement_topic.topic_id
        )
      """)
  int moveTopicLinks(@Param("mergedId") String mergedId, @Param("survivorId") String survivorId);

  /**
   * 把被合并成果的参与关系迁到幸存实体。
   *
   * <p>先补插再删除：同一学者若在两个实体上都有关系，唯一约束会让补插跳过该行，
   * 随后删除旧行就完成了「保留一条、去掉重复」的效果，不会为同一份贡献重复计数。
   * 主键在 SQL 内生成（gen_random_uuid 为 PostgreSQL 13+ 内置），避免在应用侧逐行往返。
   */
  @Insert(
      """
      INSERT INTO core_contribution
        (id, scholar_id, achievement_id, role, author_order, relation_state, source_record_id, version)
      SELECT 'ctr_' || replace(gen_random_uuid()::text, '-', ''),
             scholar_id, #{survivorId}, role, author_order, relation_state, source_record_id, version
      FROM core_contribution
      WHERE achievement_id = #{mergedId}
      ON CONFLICT (scholar_id, achievement_id) DO NOTHING
      """)
  int insertMovedContributions(
      @Param("mergedId") String mergedId, @Param("survivorId") String survivorId);

  @org.apache.ibatis.annotations.Delete(
      """
      DELETE FROM core_contribution WHERE achievement_id = #{mergedId}
      """)
  int deleteContributionsOf(@Param("mergedId") String mergedId);

  /** 参与关系快照，用于台账记录合并前后的关系归属。 */
  @Select(
      """
      SELECT c.id || '|' || c.scholar_id || '|' || c.relation_state
      FROM core_contribution c
      WHERE c.achievement_id = #{achievementId}
      ORDER BY c.id
      """)
  List<String> snapshotContributions(@Param("achievementId") String achievementId);

  @Select(
      """
      SELECT cas.id || '|' || cas.source_record_id || '|' || cas.source_id
      FROM core_achievement_source cas
      WHERE cas.achievement_id = #{achievementId}
      ORDER BY cas.id
      """)
  List<String> snapshotSources(@Param("achievementId") String achievementId);

  /** 学者视角的参与关系快照：按 scholar_id 而不是 achievement_id 取值。 */
  @Select(
      """
      SELECT c.id || '|' || c.achievement_id || '|' || c.relation_state
      FROM core_contribution c
      WHERE c.scholar_id = #{scholarId}
      ORDER BY c.id
      """)
  List<String> snapshotScholarContributions(@Param("scholarId") String scholarId);

  // ---------------------------------------------------------------------------
  // 学者合并所需的实体操作
  // ---------------------------------------------------------------------------

  @Insert(
      """
      INSERT INTO core_contribution
        (id, scholar_id, achievement_id, role, author_order, relation_state, source_record_id, version)
      SELECT 'ctr_' || replace(gen_random_uuid()::text, '-', ''),
             #{survivorScholarId}, achievement_id, role, author_order, relation_state, source_record_id, version
      FROM core_contribution
      WHERE scholar_id = #{mergedScholarId}
      ON CONFLICT (scholar_id, achievement_id) DO NOTHING
      """)
  int insertMovedScholarContributions(
      @Param("mergedScholarId") String mergedScholarId,
      @Param("survivorScholarId") String survivorScholarId);

  @org.apache.ibatis.annotations.Delete(
      """
      DELETE FROM core_contribution WHERE scholar_id = #{mergedScholarId}
      """)
  int deleteContributionsOfScholar(@Param("mergedScholarId") String mergedScholarId);

  @Update(
      """
      UPDATE core_scholar_name_variant SET scholar_id = #{survivorScholarId}
      WHERE scholar_id = #{mergedScholarId}
        AND NOT EXISTS (
          SELECT 1 FROM core_scholar_name_variant v2
          WHERE v2.scholar_id = #{survivorScholarId}
            AND v2.variant_norm = core_scholar_name_variant.variant_norm
        )
      """)
  int moveNameVariants(
      @Param("mergedScholarId") String mergedScholarId,
      @Param("survivorScholarId") String survivorScholarId);

  @org.apache.ibatis.annotations.Delete(
      """
      DELETE FROM core_scholar_name_variant WHERE scholar_id = #{mergedScholarId}
      """)
  int deleteNameVariantsOf(@Param("mergedScholarId") String mergedScholarId);

  @Select(
      """
      SELECT id FROM iam_portal_authority
      WHERE scholar_id = #{scholarId} AND status = 'ACTIVE'
      """)
  Optional<String> findActiveAuthorityId(@Param("scholarId") String scholarId);

  /**
   * 把被合并学者门户上的生效授权转到幸存门户。
   *
   * <p>只在幸存门户当前没有生效授权时才转移：若两边都有授权，说明存在两个不同的管理者，
   * 自动转移会改变访问控制结论，必须留给人工裁决，因此这里返回 0 行影响。
   */
  @Update(
      """
      UPDATE iam_portal_authority SET scholar_id = #{survivorScholarId}
      WHERE scholar_id = #{mergedScholarId} AND status = 'ACTIVE'
        AND NOT EXISTS (
          SELECT 1 FROM iam_portal_authority a2
          WHERE a2.scholar_id = #{survivorScholarId} AND a2.status = 'ACTIVE'
        )
      """)
  int moveActiveAuthority(
      @Param("mergedScholarId") String mergedScholarId,
      @Param("survivorScholarId") String survivorScholarId);

  @Update(
      """
      UPDATE iam_portal_authority
      SET status = 'REVOKED', revoked_at = now(), revoke_reason = #{reason}
      WHERE scholar_id = #{mergedScholarId} AND status = 'ACTIVE'
      """)
  int revokeAuthorityOfScholar(
      @Param("mergedScholarId") String mergedScholarId, @Param("reason") String reason);

  @Select("SELECT count(*) FROM gov_dedup_candidate WHERE status = 'OPEN'")
  long countOpenCandidates();

  @Select("SELECT count(*) FROM gov_anomaly WHERE status = 'OPEN'")
  long countOpenAnomalies();
}
