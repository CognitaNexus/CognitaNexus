package com.cognitanexus.query.projection.infrastructure;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 读模型写入。
 *
 * <p>依据：架构 V0.2 §7.1、§9.3、§5.4。所有语句都显式带 generation 条件：漏掉一次，重建期间的
 * 写入就会落到正在构建的新代上，最终表现是「新代里有几万条来路不明的记录」，且无法从数据本身
 * 分辨哪条是回放得到的、哪条是增量误写进去的。
 *
 * <p>派生集合（参与关系、发生时机构、领域、合作边）一律「先清后写」而不是逐条比对差异：
 * 事件载荷里带的是该成果的完整集合，先清后写既与架构 §9.3「先移除旧贡献再应用新贡献」一致，
 * 也避免「某个机构被移除后仍留在统计里」这类只在差集场景下出现的残留。清空发生在同一事务内，
 * 查询看不到中间状态。
 *
 * <p>注意：数据修改型 CTE（{@code WITH ... DELETE ... SELECT}）用 {@code @Select} 而非
 * {@code @Update} 标注。它们的主查询是 SELECT，用 executeUpdate 执行会因为在有结果集时调用
 * executeUpdate 而报错。
 */
@Mapper
public interface ReadModelMapper {

  record AchievementRow(
      String achievementId,
      String type,
      String title,
      String titleNorm,
      String dataNature,
      String visibility,
      String effectiveStatus,
      String integrityStatus,
      Integer publicationYear,
      String doi,
      String venue,
      Long citationCount,
      String citationSource,
      LocalDate publishedDate,
      String abstractText,
      long aggregateVersion,
      long revision) {}

  record ContributionRow(
      String achievementId,
      String scholarId,
      String scholarName,
      String role,
      Integer authorOrder,
      String relationState) {}

  record ContributionOrgRow(String achievementId, String scholarId, String organization) {}

  record TopicRow(
      String achievementId,
      String topicId,
      String canonicalName,
      String assignmentKind,
      java.math.BigDecimal confidence) {}

  /**
   * 一条无向合作边。
   *
   * <p>用记录而不是 {@code String[2]}：数组下标取值依赖持久层对数组属性的解析行为，一处不支持就
   * 退化成运行期报错，而字段取值是普通属性访问。边只有两个字段，没有为此牺牲可读性的道理。
   */
  record EdgeRow(String scholarA, String scholarB) {}

  // ---------------------------------------------------------------------------
  // 成果
  // ---------------------------------------------------------------------------

  @Insert(
      """
      INSERT INTO qry_achievement
        (generation, achievement_id, type, title, title_norm, data_nature, visibility,
         effective_status, integrity_status, publication_year, doi, venue, citation_count,
         citation_source, published_date, abstract_text, aggregate_version, revision, updated_at)
      VALUES
        (#{generation}, #{row.achievementId}, #{row.type}, #{row.title}, #{row.titleNorm},
         #{row.dataNature}, #{row.visibility}, #{row.effectiveStatus}, #{row.integrityStatus},
         #{row.publicationYear},
         #{row.doi}, #{row.venue}, #{row.citationCount}, #{row.citationSource},
         #{row.publishedDate}, #{row.abstractText}, #{row.aggregateVersion}, #{row.revision}, now())
      ON CONFLICT (generation, achievement_id) DO UPDATE SET
        type = EXCLUDED.type,
        title = EXCLUDED.title,
        title_norm = EXCLUDED.title_norm,
        data_nature = EXCLUDED.data_nature,
        visibility = EXCLUDED.visibility,
        effective_status = EXCLUDED.effective_status,
        integrity_status = EXCLUDED.integrity_status,
        publication_year = EXCLUDED.publication_year,
        doi = EXCLUDED.doi,
        venue = EXCLUDED.venue,
        citation_count = EXCLUDED.citation_count,
        citation_source = EXCLUDED.citation_source,
        published_date = EXCLUDED.published_date,
        abstract_text = EXCLUDED.abstract_text,
        aggregate_version = EXCLUDED.aggregate_version,
        revision = EXCLUDED.revision,
        updated_at = now()
      """)
  int upsertAchievement(@Param("generation") String generation, @Param("row") AchievementRow row);

  /**
   * 收紧可见性并移除合作边。
   *
   * <p>只降不升：撤销与来源停用都是单向收紧，若允许同一语句把可见性改回 PUBLIC，一次伪造事件
   * 就能让已撤销的成果重新可检索。边的删除必须一起做——「仅公开、生效的成果建边」（§9.3）
   * 意味着可见性收紧后旧边立即失去依据，留着它会让合作图继续显示已被撤下的合作关系。
   */
  @Select(
      """
      WITH tightened AS (
        UPDATE qry_achievement
        SET visibility = 'RESTRICTED', revision = GREATEST(revision, #{revision}), updated_at = now()
        WHERE generation = #{generation} AND achievement_id = #{achievementId}
          AND visibility <> 'RESTRICTED'
        RETURNING 1
      ), edges_removed AS (
        DELETE FROM qry_coauthor_edge
        WHERE generation = #{generation} AND achievement_id = #{achievementId}
        RETURNING 1
      )
      SELECT (SELECT count(*) FROM tightened)::int AS tightened
      """)
  int restrictAchievement(
      @Param("generation") String generation,
      @Param("achievementId") String achievementId,
      @Param("revision") long revision);

  /**
   * 恢复可见性：只翻回「收紧时落下的」RESTRICTED（ADR-001 恢复语义：恢复只放开因撤权被收紧的）。
   * 参与关系在收紧时保留，恢复后由应用层据此重建合作边。
   */
  @Update(
      """
      UPDATE qry_achievement
      SET visibility = 'PUBLIC', revision = GREATEST(revision, #{revision}), updated_at = now()
      WHERE generation = #{generation} AND achievement_id = #{achievementId}
        AND visibility = 'RESTRICTED'
      """)
  int restoreAchievement(
      @Param("generation") String generation,
      @Param("achievementId") String achievementId,
      @Param("revision") long revision);

  @Select(
      """
      WITH del_ach AS (DELETE FROM qry_achievement
                       WHERE generation = #{generation} AND achievement_id = #{achievementId}
                       RETURNING 1),
           del_con AS (DELETE FROM qry_contribution
                       WHERE generation = #{generation} AND achievement_id = #{achievementId}
                       RETURNING 1),
           del_org AS (DELETE FROM qry_contribution_org
                       WHERE generation = #{generation} AND achievement_id = #{achievementId}
                       RETURNING 1),
           del_top AS (DELETE FROM qry_achievement_topic
                       WHERE generation = #{generation} AND achievement_id = #{achievementId}
                       RETURNING 1),
           del_edg AS (DELETE FROM qry_coauthor_edge
                       WHERE generation = #{generation} AND achievement_id = #{achievementId}
                       RETURNING 1)
      SELECT ((SELECT count(*) FROM del_ach) + (SELECT count(*) FROM del_con)
            + (SELECT count(*) FROM del_org) + (SELECT count(*) FROM del_top)
            + (SELECT count(*) FROM del_edg))::int AS removed
      """)
  int deleteAchievement(
      @Param("generation") String generation, @Param("achievementId") String achievementId);

  @Select(
      """
      SELECT achievement_id, type, title, title_norm, data_nature, visibility, effective_status,
             integrity_status, publication_year, doi, venue, citation_count, citation_source,
             published_date, abstract_text, aggregate_version, revision
      FROM qry_achievement
      WHERE generation = #{generation} AND achievement_id = #{achievementId}
      """)
  AchievementRow findAchievement(
      @Param("generation") String generation, @Param("achievementId") String achievementId);

  // ---------------------------------------------------------------------------
  // 参与关系与发生时机构
  // ---------------------------------------------------------------------------

  @Insert(
      """
      <script>
      INSERT INTO qry_contribution
        (generation, achievement_id, scholar_id, scholar_name, role, author_order, relation_state)
      VALUES
      <foreach collection="rows" item="r" separator=",">
        (#{generation}, #{r.achievementId}, #{r.scholarId}, #{r.scholarName}, #{r.role},
         #{r.authorOrder}, #{r.relationState})
      </foreach>
      </script>
      """)
  int insertContributions(
      @Param("generation") String generation, @Param("rows") List<ContributionRow> rows);

  @Insert(
      """
      <script>
      INSERT INTO qry_contribution_org (generation, achievement_id, scholar_id, organization)
      VALUES
      <foreach collection="rows" item="r" separator=",">
        (#{generation}, #{r.achievementId}, #{r.scholarId}, #{r.organization})
      </foreach>
      </script>
      """)
  int insertContributionOrgs(
      @Param("generation") String generation, @Param("rows") List<ContributionOrgRow> rows);

  @Select(
      """
      SELECT achievement_id, scholar_id, scholar_name, role, author_order, relation_state
      FROM qry_contribution
      WHERE generation = #{generation} AND achievement_id = #{achievementId}
      ORDER BY author_order NULLS LAST, scholar_id
      """)
  List<ContributionRow> listContributions(
      @Param("generation") String generation, @Param("achievementId") String achievementId);

  @Select(
      """
      SELECT DISTINCT achievement_id FROM qry_contribution
      WHERE generation = #{generation} AND scholar_id = #{scholarId}
      """)
  List<String> listAchievementIdsOfScholar(
      @Param("generation") String generation, @Param("scholarId") String scholarId);

  @Select(
      """
      SELECT organization FROM qry_contribution_org
      WHERE generation = #{generation} AND achievement_id = #{achievementId}
        AND scholar_id = #{scholarId}
      ORDER BY organization
      """)
  List<String> listOrganizations(
      @Param("generation") String generation,
      @Param("achievementId") String achievementId,
      @Param("scholarId") String scholarId);

  /**
   * 学者合并时把参与关系改挂到幸存学者。
   *
   * <p>用 INSERT ... SELECT 而不是 UPDATE，并且用 NOT EXISTS 排除「幸存者已在该成果上有关系」
   * 的情况：直接改 scholar_id 会撞唯一键并让整个合并失败；而先查后写会引入竞态。这条语句在
   * 数据库内一次判定，与 core 侧 moveScholarContributions 的「先到者保留、不留第二条」语义一致。
   */
  @Insert(
      """
      INSERT INTO qry_contribution
        (generation, achievement_id, scholar_id, scholar_name, role, author_order, relation_state)
      SELECT c.generation, c.achievement_id, #{survivorId}, #{survivorName}, c.role,
             c.author_order, c.relation_state
      FROM qry_contribution c
      WHERE c.generation = #{generation} AND c.scholar_id = #{mergedId}
        AND NOT EXISTS (
          SELECT 1 FROM qry_contribution s
          WHERE s.generation = c.generation AND s.achievement_id = c.achievement_id
            AND s.scholar_id = #{survivorId})
      """)
  int insertMovedScholarContributions(
      @Param("generation") String generation,
      @Param("survivorId") String survivorId,
      @Param("survivorName") String survivorName,
      @Param("mergedId") String mergedId);

  /**
   * 学者合并时改挂发生时机构。
   *
   * <p>必须在{@link #insertMovedScholarContributions}之前执行：它要判断的是「合并前幸存者是否
   * 已在同一成果上有关系」。若放在之后，先前插入的行会让判断恒为真，机构归属就不会迁移，
   * 表现为合并后机构统计凭空少掉一批成果。
   */
  @Insert(
      """
      INSERT INTO qry_contribution_org (generation, achievement_id, scholar_id, organization)
      SELECT o.generation, o.achievement_id, #{survivorId}, o.organization
      FROM qry_contribution_org o
      WHERE o.generation = #{generation} AND o.scholar_id = #{mergedId}
        AND NOT EXISTS (
          SELECT 1 FROM qry_contribution s
          WHERE s.generation = o.generation AND s.achievement_id = o.achievement_id
            AND s.scholar_id = #{survivorId})
      """)
  int insertMovedScholarOrgs(
      @Param("generation") String generation,
      @Param("survivorId") String survivorId,
      @Param("mergedId") String mergedId);

  @Delete(
      """
      DELETE FROM qry_contribution
      WHERE generation = #{generation} AND scholar_id = #{scholarId}
      """)
  int deleteContributionsOfScholar(
      @Param("generation") String generation, @Param("scholarId") String scholarId);

  @Delete(
      """
      DELETE FROM qry_contribution_org
      WHERE generation = #{generation} AND scholar_id = #{scholarId}
      """)
  int deleteContributionOrgsOfScholar(
      @Param("generation") String generation, @Param("scholarId") String scholarId);

  @Delete(
      """
      DELETE FROM qry_contribution
      WHERE generation = #{generation} AND achievement_id = #{achievementId}
      """)
  int deleteContributionsOfAchievement(
      @Param("generation") String generation, @Param("achievementId") String achievementId);

  @Delete(
      """
      DELETE FROM qry_contribution_org
      WHERE generation = #{generation} AND achievement_id = #{achievementId}
      """)
  int deleteContributionOrgsOfAchievement(
      @Param("generation") String generation, @Param("achievementId") String achievementId);

  @Update(
      """
      UPDATE qry_contribution
      SET scholar_name = #{scholarName}
      WHERE generation = #{generation} AND scholar_id = #{scholarId}
        AND #{scholarName} IS NOT NULL
      """)
  int updateScholarName(
      @Param("generation") String generation,
      @Param("scholarId") String scholarId,
      @Param("scholarName") String scholarName);

  // ---------------------------------------------------------------------------
  // 领域
  // ---------------------------------------------------------------------------

  @Insert(
      """
      <script>
      INSERT INTO qry_achievement_topic
          (generation, achievement_id, topic_id, canonical_name, assignment_kind, confidence)
      VALUES
      <foreach collection="rows" item="r" separator=",">
        (#{generation}, #{r.achievementId}, #{r.topicId}, #{r.canonicalName},
         #{r.assignmentKind}, #{r.confidence})
      </foreach>
      </script>
      """)
  int insertTopics(@Param("generation") String generation, @Param("rows") List<TopicRow> rows);

  @Delete(
      """
      DELETE FROM qry_achievement_topic
      WHERE generation = #{generation} AND achievement_id = #{achievementId}
      """)
  int deleteTopics(
      @Param("generation") String generation, @Param("achievementId") String achievementId);

  // ---------------------------------------------------------------------------
  // 合作边
  // ---------------------------------------------------------------------------

  @Insert(
      """
      <script>
      INSERT INTO qry_coauthor_edge (generation, achievement_id, scholar_a, scholar_b)
      VALUES
      <foreach collection="pairs" item="p" separator=",">
        (#{generation}, #{achievementId}, #{p.scholarA}, #{p.scholarB})
      </foreach>
      </script>
      """)
  int insertEdges(
      @Param("generation") String generation,
      @Param("achievementId") String achievementId,
      @Param("pairs") List<EdgeRow> pairs);

  @Delete(
      """
      DELETE FROM qry_coauthor_edge
      WHERE generation = #{generation} AND achievement_id = #{achievementId}
      """)
  int deleteEdges(
      @Param("generation") String generation, @Param("achievementId") String achievementId);

  // ---------------------------------------------------------------------------
  // 合并重定向
  // ---------------------------------------------------------------------------

  @Insert(
      """
      INSERT INTO qry_id_redirect (entity_type, old_id, new_id, revision)
      VALUES (#{entityType}, #{oldId}, #{newId}, #{revision})
      ON CONFLICT (entity_type, old_id) DO UPDATE SET
        new_id = EXCLUDED.new_id,
        revision = EXCLUDED.revision
      """)
  int upsertRedirect(
      @Param("entityType") String entityType,
      @Param("oldId") String oldId,
      @Param("newId") String newId,
      @Param("revision") long revision);
}
