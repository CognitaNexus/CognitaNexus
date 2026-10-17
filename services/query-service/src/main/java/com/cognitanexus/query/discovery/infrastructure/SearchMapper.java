package com.cognitanexus.query.discovery.infrastructure;

import com.cognitanexus.query.discovery.application.SearchCriteria;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 检索投影的只读查询。
 *
 * <p>依据：架构 V0.2 §9.1。「排序字段走白名单，末位唯一稳定ID」在这里体现为 ORDER BY 的列名由
 * {@code <choose>} 在 SQL 内固定写出，而不是把外部传入的列名拼接进语句。参数化只能绑定值、不能
 * 绑定标识符，任何「用参数做 ORDER BY」的做法最终都会退化成字符串拼接，因此白名单必须落在 SQL 里。
 *
 * <p>所有查询都带 generation 条件与公开资格条件（生效 + 公开）。资格条件写在 SQL 而非调用方：
 * 一旦某个调用点漏了它，就是把未生效或已撤下的内容暴露出去，而这类漏洞不会自己报错。
 */
@Mapper
public interface SearchMapper {

  record AchievementHit(
      String achievementId,
      String type,
      String title,
      String titleNorm,
      String dataNature,
      String visibility,
      String effectiveStatus,
      Integer publicationYear,
      String doi,
      String venue,
      Long citationCount,
      String citationSource,
      Instant updatedAt,
      Double relevance) {}

  record NameRow(String achievementId, String name) {}

  record TopicRow(
      String achievementId,
      String topicId,
      String canonicalName,
      String assignmentKind,
      Double confidence) {}

  record ScholarHit(
      String scholarId, String scholarName, long achievementCount, Integer firstYear) {}

  /** 领域分面。sourceCount / inferredCount 把来源分类与系统推断分开计数，两者之和即总数。 */
  record TopicFacet(
      String topicId,
      String canonicalName,
      long achievementCount,
      long sourceCount,
      long inferredCount) {}

  /**
   * 计数。
   *
   * <p>数据性质用「等于」而不是 IN：默认只查 REAL，演示模式显式传 SIMULATED/MIXED/ALL。
   * 用 IN 会把「默认现实统计」与「演示数据」的边界变得含糊。
   */
  @Select(
      """
      <script>
      SELECT count(*)
      FROM qry_achievement a
      <if test="criteria.topicId != null">
        JOIN qry_achievement_topic t
          ON t.generation = a.generation AND t.achievement_id = a.achievement_id
         AND t.topic_id = #{criteria.topicId}
      </if>
      WHERE a.generation = #{generation}
        AND a.effective_status = 'EFFECTIVE'
        AND a.visibility = 'PUBLIC'
        <if test="criteria.dataNature != null">
          AND a.data_nature = #{criteria.dataNature}
        </if>
        <if test="criteria.type != null">AND a.type = #{criteria.type}</if>
        <if test="criteria.yearFrom != null">AND a.publication_year &gt;= #{criteria.yearFrom}</if>
        <if test="criteria.yearTo != null">AND a.publication_year &lt;= #{criteria.yearTo}</if>
        <if test="criteria.keywordNorm != null">
          AND (a.title_norm LIKE '%' || #{criteria.keywordNorm} || '%'
               OR lower(coalesce(a.venue, '')) LIKE '%' || #{criteria.keywordNorm} || '%'
               OR lower(coalesce(a.doi, '')) = #{criteria.keywordNorm})
        </if>
        <if test="criteria.organization != null">
          AND EXISTS (
            SELECT 1 FROM qry_contribution_org o
            WHERE o.generation = a.generation AND o.achievement_id = a.achievement_id
              AND o.organization = #{criteria.organization})
        </if>
      </script>
      """)
  long countHits(
      @Param("generation") String generation, @Param("criteria") SearchCriteria criteria);

  @Select(
      """
      <script>
      SELECT a.achievement_id, a.type, a.title, a.title_norm, a.data_nature, a.visibility,
             a.effective_status, a.publication_year, a.doi, a.venue, a.citation_count,
             a.citation_source, a.updated_at,
             <choose>
               <when test="criteria.keywordNorm != null">
                 similarity(a.title_norm, #{criteria.keywordNorm})
               </when>
               <otherwise>0.0</otherwise>
             </choose>
             AS relevance
      FROM qry_achievement a
      <if test="criteria.topicId != null">
        JOIN qry_achievement_topic t
          ON t.generation = a.generation AND t.achievement_id = a.achievement_id
         AND t.topic_id = #{criteria.topicId}
      </if>
      WHERE a.generation = #{generation}
        AND a.effective_status = 'EFFECTIVE'
        AND a.visibility = 'PUBLIC'
        <if test="criteria.dataNature != null">
          AND a.data_nature = #{criteria.dataNature}
        </if>
        <if test="criteria.type != null">AND a.type = #{criteria.type}</if>
        <if test="criteria.yearFrom != null">AND a.publication_year &gt;= #{criteria.yearFrom}</if>
        <if test="criteria.yearTo != null">AND a.publication_year &lt;= #{criteria.yearTo}</if>
        <if test="criteria.keywordNorm != null">
          AND (a.title_norm LIKE '%' || #{criteria.keywordNorm} || '%'
               OR lower(coalesce(a.venue, '')) LIKE '%' || #{criteria.keywordNorm} || '%'
               OR lower(coalesce(a.doi, '')) = #{criteria.keywordNorm})
        </if>
        <if test="criteria.organization != null">
          AND EXISTS (
            SELECT 1 FROM qry_contribution_org o
            WHERE o.generation = a.generation AND o.achievement_id = a.achievement_id
              AND o.organization = #{criteria.organization})
        </if>
      ORDER BY
        <choose>
          <when test="criteria.sort == 'year_desc'">
            a.publication_year DESC NULLS LAST, a.achievement_id
          </when>
          <when test="criteria.sort == 'year_asc'">
            a.publication_year ASC NULLS LAST, a.achievement_id
          </when>
          <when test="criteria.sort == 'title_asc'">
            a.title_norm ASC, a.achievement_id
          </when>
          <when test="criteria.sort == 'citations_desc'">
            a.citation_count DESC NULLS LAST, a.achievement_id
          </when>
          <otherwise>
            relevance DESC, a.publication_year DESC NULLS LAST, a.achievement_id
          </otherwise>
        </choose>
      LIMIT #{limit} OFFSET #{offset}
      </script>
      """)
  List<AchievementHit> listHits(
      @Param("generation") String generation,
      @Param("criteria") SearchCriteria criteria,
      @Param("limit") int limit,
      @Param("offset") int offset);

  /** 一页结果的作者名。只在返回页内查询，避免把参与关系整表拉出来。 */
  @Select(
      """
      <script>
      SELECT c.achievement_id, c.scholar_name AS name
      FROM qry_contribution c
      WHERE c.generation = #{generation}
        AND c.scholar_name IS NOT NULL
        AND c.achievement_id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
      ORDER BY c.achievement_id, c.author_order NULLS LAST, c.scholar_id
      </script>
      """)
  List<NameRow> listAuthorNames(
      @Param("generation") String generation, @Param("ids") List<String> ids);

  @Select(
      """
      <script>
      SELECT t.achievement_id, t.topic_id, t.canonical_name,
             t.assignment_kind, t.confidence
      FROM qry_achievement_topic t
      WHERE t.generation = #{generation}
        AND t.achievement_id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
      ORDER BY t.achievement_id, (t.assignment_kind = 'SOURCE') DESC, t.topic_id
      </script>
      """)
  List<TopicRow> listTopics(@Param("generation") String generation, @Param("ids") List<String> ids);

  /**
   * 学者检索。
   *
   * <p>只统计「合格关系（AUTO / SELF_CONFIRMED）」在公开生效成果上的出现次数：待核验与已排除
   * 关系不能计入确定合作或确定产出，否则同名同姓的自动匹配结果会被当作可信数据展示（BR-IMP-08）。
   */
  @Select(
      """
      <script>
      SELECT c.scholar_id,
             max(c.scholar_name) AS scholar_name,
             count(DISTINCT c.achievement_id) AS achievement_count,
             min(a.publication_year)::int AS first_year
      FROM qry_contribution c
      JOIN qry_achievement a
        ON a.generation = c.generation AND a.achievement_id = c.achievement_id
      WHERE c.generation = #{generation}
        AND a.effective_status = 'EFFECTIVE'
        AND a.visibility = 'PUBLIC'
        AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')
        <if test="criteria.dataNature != null">
          AND a.data_nature = #{criteria.dataNature}
        </if>
        <if test="criteria.keywordNorm != null">
          AND lower(coalesce(c.scholar_name, '')) LIKE '%' || #{criteria.keywordNorm} || '%'
        </if>
      GROUP BY c.scholar_id
      ORDER BY achievement_count DESC, c.scholar_id
      LIMIT #{limit} OFFSET #{offset}
      </script>
      """)
  List<ScholarHit> searchScholars(
      @Param("generation") String generation,
      @Param("criteria") SearchCriteria criteria,
      @Param("limit") int limit,
      @Param("offset") int offset);

  @Select(
      """
      <script>
      SELECT count(*) FROM (
        SELECT c.scholar_id
        FROM qry_contribution c
        JOIN qry_achievement a
          ON a.generation = c.generation AND a.achievement_id = c.achievement_id
        WHERE c.generation = #{generation}
          AND a.effective_status = 'EFFECTIVE'
          AND a.visibility = 'PUBLIC'
          AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')
          <if test="criteria.dataNature != null">
            AND a.data_nature = #{criteria.dataNature}
          </if>
          <if test="criteria.keywordNorm != null">
            AND lower(coalesce(c.scholar_name, '')) LIKE '%' || #{criteria.keywordNorm} || '%'
          </if>
        GROUP BY c.scholar_id
      ) s
      </script>
      """)
  long countScholars(
      @Param("generation") String generation, @Param("criteria") SearchCriteria criteria);

  /** 领域候选清单，供筛选下拉使用；只统计当前可见的公开生效成果。 */
  @Select(
      """
      <script>
      SELECT t.topic_id,
             max(t.canonical_name) AS canonical_name,
             count(DISTINCT t.achievement_id) AS achievement_count,
             count(DISTINCT t.achievement_id) FILTER (WHERE t.assignment_kind = 'SOURCE')
               AS source_count,
             count(DISTINCT t.achievement_id) FILTER (WHERE t.assignment_kind = 'INFERRED')
               AS inferred_count
      FROM qry_achievement_topic t
      JOIN qry_achievement a
        ON a.generation = t.generation AND a.achievement_id = t.achievement_id
      WHERE t.generation = #{generation}
        AND a.effective_status = 'EFFECTIVE'
        AND a.visibility = 'PUBLIC'
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
      GROUP BY t.topic_id
      ORDER BY achievement_count DESC, t.topic_id
      LIMIT #{limit}
      </script>
      """)
  List<TopicFacet> listTopicFacets(
      @Param("generation") String generation,
      @Param("dataNature") String dataNature,
      @Param("limit") int limit);

  /** 机构候选清单，机构名来自成果发生时的机构归属，不是学者当前任职机构。 */
  @Select(
      """
      <script>
      SELECT o.organization AS name, count(DISTINCT o.achievement_id) AS achievement_count
      FROM qry_contribution_org o
      JOIN qry_achievement a
        ON a.generation = o.generation AND a.achievement_id = o.achievement_id
      WHERE o.generation = #{generation}
        AND a.effective_status = 'EFFECTIVE'
        AND a.visibility = 'PUBLIC'
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
      GROUP BY o.organization
      ORDER BY achievement_count DESC, o.organization
      LIMIT #{limit}
      </script>
      """)
  List<Map<String, Object>> listOrganizationFacets(
      @Param("generation") String generation,
      @Param("dataNature") String dataNature,
      @Param("limit") int limit);
}
