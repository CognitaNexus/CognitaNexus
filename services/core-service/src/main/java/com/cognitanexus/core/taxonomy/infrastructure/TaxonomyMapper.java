package com.cognitanexus.core.taxonomy.infrastructure;

import com.cognitanexus.core.taxonomy.application.TopicInferenceRepository.InferenceRecord;
import com.cognitanexus.core.taxonomy.application.TopicInferenceRepository.KindCount;
import com.cognitanexus.core.taxonomy.application.TopicInferenceRepository.OutcomeCount;
import com.cognitanexus.core.taxonomy.application.TopicInferenceRepository.PendingAchievement;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 领域推断的持久化。
 *
 * <p>依据：架构 V0.2 §5.3、SRS 5.3。所有写入都落在 core_achievement_topic（归属本体）与
 * core_topic_inference（推断尝试台账）两张表上，不触碰来源分类行。
 */
@Mapper
public interface TaxonomyMapper {

  /**
   * 待推断的成果。
   *
   * <p>两个 NOT EXISTS 分别排除「来源已给出分类」与「当前规则版本已尝试过」。后者同时覆盖了
   * 「判不出领域」的情形——那类成果在台账里是 UNRESOLVED，也一并排除，否则它们会一直排在队首，
   * 把后面的成果永远挡住。
   */
  @Select(
      """
      SELECT a.id AS achievementId, a.title AS title,
             p.venue AS venue, p.abstract_text AS abstractText
      FROM core_achievement a
      LEFT JOIN core_paper p ON p.achievement_id = a.id
      WHERE a.merged_into_id IS NULL
        AND NOT EXISTS (
          SELECT 1 FROM core_achievement_topic t
          WHERE t.achievement_id = a.id AND t.assignment_kind = 'SOURCE')
        AND NOT EXISTS (
          SELECT 1 FROM core_topic_inference i
          WHERE i.achievement_id = a.id AND i.rule_version = #{ruleVersion})
      ORDER BY a.id
      LIMIT #{limit}
      """)
  List<PendingAchievement> listPending(
      @Param("limit") int limit, @Param("ruleVersion") String ruleVersion);

  /**
   * 待推断总数。
   *
   * <p>与 {@link #listPending} 用同一组条件。分成两条语句而不是取 listPending(size) 的长度，
   * 是为了让「还剩多少」不受批大小影响——批量推进会反复调用它，而翻页式的写法在总数超过一页时
   * 会给出恒定的一页长度，看起来像是永远推不完。
   */
  @Select(
      """
      SELECT count(*)
      FROM core_achievement a
      WHERE a.merged_into_id IS NULL
        AND NOT EXISTS (
          SELECT 1 FROM core_achievement_topic t
          WHERE t.achievement_id = a.id AND t.assignment_kind = 'SOURCE')
        AND NOT EXISTS (
          SELECT 1 FROM core_topic_inference i
          WHERE i.achievement_id = a.id AND i.rule_version = #{ruleVersion})
      """)
  long countPending(@Param("ruleVersion") String ruleVersion);

  @Insert(
      """
      INSERT INTO core_achievement_topic
          (achievement_id, topic_id, source_category, assignment_kind, confidence, rule_version, evidence)
      VALUES
          (#{achievementId}, #{topicId}, NULL, 'INFERRED', #{confidence}, #{ruleVersion}, #{evidence})
      ON CONFLICT (achievement_id, topic_id) DO NOTHING
      """)
  int linkInferred(
      @Param("achievementId") String achievementId,
      @Param("topicId") String topicId,
      @Param("ruleVersion") String ruleVersion,
      @Param("confidence") BigDecimal confidence,
      @Param("evidence") String evidence);

  @Delete(
      """
      DELETE FROM core_achievement_topic
      WHERE achievement_id = #{achievementId}
        AND assignment_kind = 'INFERRED'
        AND (rule_version IS NULL OR rule_version <> #{ruleVersion})
      """)
  int deleteInferredOtherVersions(
      @Param("achievementId") String achievementId, @Param("ruleVersion") String ruleVersion);

  /** 台账按 (成果, 版本) 幂等：同一版本重算得到同一条结论，覆盖而不是追加。 */
  @Insert(
      """
      INSERT INTO core_topic_inference
          (achievement_id, rule_version, outcome, assigned_count, best_confidence, evidence, inferred_at)
      VALUES
          (#{achievementId}, #{ruleVersion}, #{outcome}, #{assignedCount}, #{bestConfidence},
           #{evidence}, now())
      ON CONFLICT (achievement_id, rule_version) DO UPDATE SET
          outcome = EXCLUDED.outcome,
          assigned_count = EXCLUDED.assigned_count,
          best_confidence = EXCLUDED.best_confidence,
          evidence = EXCLUDED.evidence,
          inferred_at = EXCLUDED.inferred_at
      """)
  int upsertInference(
      @Param("achievementId") String achievementId,
      @Param("ruleVersion") String ruleVersion,
      @Param("outcome") String outcome,
      @Param("assignedCount") int assignedCount,
      @Param("bestConfidence") BigDecimal bestConfidence,
      @Param("evidence") String evidence);

  @Select(
      """
      SELECT assignment_kind AS kind,
             count(*) AS rowCount,
             count(DISTINCT achievement_id) AS achievementCount
      FROM core_achievement_topic
      GROUP BY assignment_kind
      ORDER BY assignment_kind
      """)
  List<KindCount> countByKind();

  @Select(
      """
      SELECT outcome AS outcome, count(*) AS achievements
      FROM core_topic_inference
      WHERE rule_version = #{ruleVersion}
      GROUP BY outcome
      ORDER BY outcome
      """)
  List<OutcomeCount> countByOutcome(@Param("ruleVersion") String ruleVersion);

  @Select(
      """
      SELECT count(*) FROM core_achievement_topic
      WHERE assignment_kind = 'INFERRED' AND rule_version = #{ruleVersion}
      """)
  long countInferredRows(@Param("ruleVersion") String ruleVersion);

  @Select(
      """
      SELECT i.achievement_id AS achievementId, i.outcome AS outcome,
             i.assigned_count AS assignedCount, i.best_confidence AS bestConfidence,
             i.evidence AS evidence, i.inferred_at AS inferredAt
      FROM core_topic_inference i
      WHERE i.rule_version = #{ruleVersion} AND i.outcome = 'UNRESOLVED'
      ORDER BY i.achievement_id
      LIMIT #{limit}
      """)
  List<InferenceRecord> listUnresolved(
      @Param("ruleVersion") String ruleVersion, @Param("limit") int limit);

  @Select(
      """
      SELECT count(*)
      FROM core_achievement a
      WHERE a.merged_into_id IS NULL
        AND NOT EXISTS (
          SELECT 1 FROM core_achievement_topic t WHERE t.achievement_id = a.id)
      """)
  long countAchievementsWithoutTopic();
}
