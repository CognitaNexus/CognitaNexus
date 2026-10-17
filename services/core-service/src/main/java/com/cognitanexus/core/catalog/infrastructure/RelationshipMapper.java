package com.cognitanexus.core.catalog.infrastructure;

import com.cognitanexus.core.catalog.application.CatalogRepositories.TopicRepository.Topic;
import com.cognitanexus.core.catalog.application.CatalogRepositories.TopicRepository.TopicAssignment;
import com.cognitanexus.core.catalog.domain.Contribution;
import com.cognitanexus.sharedkernel.RelationState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 关系域持久化：参与关系、发生时机归属、成果分类、领域规范词、机构。
 *
 * <p>依据：架构 V0.2 §5.1、§5.3。一个学者对同一成果只有一条参与关系，唯一约束
 * <code>uk_core_contribution_scholar_achievement</code> 保证；这里用 ON CONFLICT DO NOTHING
 * 让重复关联表现为 0 行影响，避免事务中止。
 */
@Mapper
public interface RelationshipMapper {

  record ContributionRow(
      String id,
      String scholarId,
      String achievementId,
      String role,
      Integer authorOrder,
      String relationState,
      String sourceRecordId,
      long version,
      Instant createdAt,
      Instant updatedAt) {}

  @Insert(
      """
      INSERT INTO core_contribution
        (id, scholar_id, achievement_id, role, author_order, relation_state, source_record_id, version)
      VALUES
        (#{id}, #{scholarId}, #{achievementId}, #{role}, #{authorOrder}, #{relationState},
         #{sourceRecordId}, 1)
      ON CONFLICT (scholar_id, achievement_id) DO NOTHING
      """)
  int insertContributionIfAbsent(
      @Param("id") String id,
      @Param("scholarId") String scholarId,
      @Param("achievementId") String achievementId,
      @Param("role") String role,
      @Param("authorOrder") Integer authorOrder,
      @Param("relationState") String relationState,
      @Param("sourceRecordId") String sourceRecordId);

  @Select(
      """
      SELECT id AS id, scholar_id AS scholarId, achievement_id AS achievementId, role AS role,
             author_order AS authorOrder, relation_state AS relationState,
             source_record_id AS sourceRecordId, version AS version,
             created_at AS createdAt, updated_at AS updatedAt
      FROM core_contribution
      WHERE achievement_id = #{achievementId}
      ORDER BY author_order NULLS LAST, id
      """)
  List<ContributionRow> listByAchievement(@Param("achievementId") String achievementId);

  @Select(
      """
      SELECT id AS id, scholar_id AS scholarId, achievement_id AS achievementId, role AS role,
             author_order AS authorOrder, relation_state AS relationState,
             source_record_id AS sourceRecordId, version AS version,
             created_at AS createdAt, updated_at AS updatedAt
      FROM core_contribution
      WHERE scholar_id = #{scholarId}
      ORDER BY created_at DESC, id
      """)
  List<ContributionRow> listByScholar(@Param("scholarId") String scholarId);

  @Select(
      """
      SELECT id FROM core_contribution
      WHERE scholar_id = #{scholarId} AND achievement_id = #{achievementId}
      """)
  Optional<String> findContributionId(
      @Param("scholarId") String scholarId, @Param("achievementId") String achievementId);

  @Select(
      """
      SELECT id AS id, scholar_id AS scholarId, achievement_id AS achievementId, role AS role,
             author_order AS authorOrder, relation_state AS relationState,
             source_record_id AS sourceRecordId, version AS version,
             created_at AS createdAt, updated_at AS updatedAt
      FROM core_contribution WHERE id = #{contributionId}
      """)
  Optional<ContributionRow> findContributionById(@Param("contributionId") String contributionId);

  /**
   * 处于指定关系状态的参与关系，按更新时间倒序。
   *
   * <p>治理排序要用「最近变化」：争议越新越可能还热着，压在列表末尾只会让争议在公开视图里
   * 长期保持沉默。
   */
  @Select(
      """
      SELECT id AS id, scholar_id AS scholarId, achievement_id AS achievementId, role AS role,
             author_order AS authorOrder, relation_state AS relationState,
             source_record_id AS sourceRecordId, version AS version,
             created_at AS createdAt, updated_at AS updatedAt
      FROM core_contribution
      WHERE relation_state = #{relationState}
      ORDER BY updated_at DESC, id
      LIMIT #{limit}
      """)
  List<ContributionRow> listByRelationState(
      @Param("relationState") String relationState, @Param("limit") int limit);

  @Select("SELECT count(*) FROM core_contribution")
  long countContributions();

  /**
   * 状态流转：解除本人关系时改为 EXCLUDED 而不是删除行。
   *
   * <p>删除参与关系会让「曾经存在过、后被审核排除」这段历史消失；共享成果与其他参与者的
   * 关系也不得因某人解除而受影响（AC-IMPL-FR07-01）。
   */
  @Update(
      """
      UPDATE core_contribution
      SET relation_state = #{toState}, version = version + 1, updated_at = now()
      WHERE id = #{contributionId} AND relation_state = #{fromState}
      """)
  int transitionRelationState(
      @Param("contributionId") String contributionId,
      @Param("fromState") String fromState,
      @Param("toState") String toState);

  @Select("SELECT relation_state FROM core_contribution WHERE id = #{contributionId}")
  Optional<String> findRelationState(@Param("contributionId") String contributionId);

  // ---------------------------------------------------------------------------
  // 发生时机归属
  // ---------------------------------------------------------------------------

  @Insert(
      """
      INSERT INTO core_contribution_org (contribution_id, organization_id)
      VALUES (#{contributionId}, #{organizationId})
      ON CONFLICT (contribution_id, organization_id) DO NOTHING
      """)
  int linkOrganization(
      @Param("contributionId") String contributionId,
      @Param("organizationId") String organizationId);

  @Select(
      """
      SELECT count(*) FROM core_contribution_org WHERE contribution_id = #{contributionId}
      """)
  int countOrganizationsOf(@Param("contributionId") String contributionId);

  /**
   * 参与关系在成果发生时的机构规范名。
   *
   * <p>按机构 id 排序返回固定顺序：投影载荷要可比对，不能让行序随机漂移导致同一份数据每次
   * 计算出不同的摘要。缺失机构归属时返回空列表（未知即未知），不退化为当前任职机构。
   */
  @Select(
      """
      SELECT o.canonical_name
      FROM core_contribution_org co
      JOIN ref_organization o ON o.id = co.organization_id
      WHERE co.contribution_id = #{contributionId}
      ORDER BY o.id
      """)
  List<String> listOrganizationNames(@Param("contributionId") String contributionId);

  // ---------------------------------------------------------------------------
  // 领域与分类
  // ---------------------------------------------------------------------------

  @Select("SELECT id FROM ref_topic WHERE name_norm = #{nameNorm}")
  Optional<String> findTopicIdByNameNorm(@Param("nameNorm") String nameNorm);

  @Insert(
      """
      INSERT INTO ref_topic (id, canonical_name, name_norm, source_category)
      VALUES (#{id}, #{canonicalName}, #{nameNorm}, #{sourceCategory})
      ON CONFLICT (name_norm) DO NOTHING
      """)
  int insertTopicIfAbsent(
      @Param("id") String id,
      @Param("canonicalName") String canonicalName,
      @Param("nameNorm") String nameNorm,
      @Param("sourceCategory") String sourceCategory);

  @Select(
      """
      SELECT id AS id, canonical_name AS canonicalName, name_norm AS nameNorm,
             source_category AS sourceCategory
      FROM ref_topic WHERE id = #{id}
      """)
  Optional<Topic> findTopicById(@Param("id") String id);

  @Insert(
      """
      INSERT INTO core_achievement_topic (achievement_id, topic_id, source_category)
      VALUES (#{achievementId}, #{topicId}, #{sourceCategory})
      ON CONFLICT (achievement_id, topic_id) DO NOTHING
      """)
  int linkTopic(
      @Param("achievementId") String achievementId,
      @Param("topicId") String topicId,
      @Param("sourceCategory") String sourceCategory);

  @Select(
      """
      SELECT topic_id FROM core_achievement_topic
      WHERE achievement_id = #{achievementId} ORDER BY topic_id
      """)
  List<String> listTopicIdsOf(@Param("achievementId") String achievementId);

  /**
   * 带归属来源的领域列表。
   *
   * <p>来源分类排在推断之前：来源是权威，展示顺序应当反映这一点，而不是按 kind 的字典序
   * （那是 INFERRED 在前）。
   */
  @Select(
      """
      SELECT topic_id AS topicId, assignment_kind AS assignmentKind, confidence AS confidence
      FROM core_achievement_topic
      WHERE achievement_id = #{achievementId}
      ORDER BY (assignment_kind = 'SOURCE') DESC, topic_id
      """)
  List<TopicAssignment> listAssignmentsOf(@Param("achievementId") String achievementId);

  @Delete(
      """
      DELETE FROM core_achievement_topic
      WHERE achievement_id = #{achievementId} AND assignment_kind = 'INFERRED'
      """)
  int deleteInferredTopicsOfAchievement(@Param("achievementId") String achievementId);

  @Delete("DELETE FROM core_topic_inference WHERE achievement_id = #{achievementId}")
  int deleteInferenceOfAchievement(@Param("achievementId") String achievementId);

  @Select("SELECT count(*) FROM ref_topic")
  long countTopics();

  // ---------------------------------------------------------------------------
  // 机构
  // ---------------------------------------------------------------------------

  @Select("SELECT id FROM ref_organization WHERE name_norm = #{nameNorm}")
  Optional<String> findOrganizationIdByNameNorm(@Param("nameNorm") String nameNorm);

  @Insert(
      """
      INSERT INTO ref_organization (id, canonical_name, name_norm, country_code)
      VALUES (#{id}, #{canonicalName}, #{nameNorm}, #{countryCode})
      ON CONFLICT (name_norm) DO NOTHING
      """)
  int insertOrganizationIfAbsent(
      @Param("id") String id,
      @Param("canonicalName") String canonicalName,
      @Param("nameNorm") String nameNorm,
      @Param("countryCode") String countryCode);

  default Contribution toDomain(ContributionRow row) {
    return new Contribution(
        row.id(),
        row.scholarId(),
        row.achievementId(),
        row.role(),
        row.authorOrder(),
        RelationState.valueOf(row.relationState()),
        row.sourceRecordId(),
        row.version(),
        row.createdAt(),
        row.updatedAt());
  }
}
