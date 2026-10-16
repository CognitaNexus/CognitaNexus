package com.cognitanexus.query.analytics.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 统计口径的只读查询。
 *
 * <p>依据：架构 V0.2 §9.2。三条计数规则在这里落实：
 *
 * <ol>
 *   <li>平台总量按<b>去重实体</b>计数，不是按记录条数；
 *   <li>机构按成果发生时机构计数，<b>同成果同机构只计一次</b>、跨机构各计一次，因此用
 *       {@code count(DISTINCT achievement_id)} 而不是 {@code count(*)}；
 *   <li>未知单列，不进入已知排名：年份为 null 的成果单独成组返回，由调用方放在「未知」桶里，
 *       而不是被塞进某一年或从总数里消失。
 * </ol>
 *
 * <p>引用量只按来源分组返回，不提供「总引用」聚合：不同来源的引用量口径不同，相加得到的数字
 * 没有含义，却会被当成一个可比较的指标使用。
 */
@Mapper
public interface AnalyticsMapper {

  record CountRow(String bucket, long amount) {}

  record YearRow(Integer year, long amount) {}

  record CitationRow(String citationSource, long achievementCount, long citationSum) {}

  @Select(
      """
      <script>
      SELECT count(*) FROM qry_achievement a
      WHERE a.generation = #{generation}
        AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
      </script>
      """)
  long countAchievements(
      @Param("generation") String generation, @Param("dataNature") String dataNature);

  @Select(
      """
      <script>
      SELECT count(DISTINCT c.scholar_id)
      FROM qry_contribution c
      JOIN qry_achievement a
        ON a.generation = c.generation AND a.achievement_id = c.achievement_id
      WHERE c.generation = #{generation}
        AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
        AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
      </script>
      """)
  long countScholars(
      @Param("generation") String generation, @Param("dataNature") String dataNature);

  @Select(
      """
      <script>
      SELECT count(*)
      FROM qry_contribution c
      JOIN qry_achievement a
        ON a.generation = c.generation AND a.achievement_id = c.achievement_id
      WHERE c.generation = #{generation}
        AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
      </script>
      """)
  long countContributions(
      @Param("generation") String generation, @Param("dataNature") String dataNature);

  @Select(
      """
      <script>
      SELECT count(DISTINCT (e.scholar_a, e.scholar_b))
      FROM qry_coauthor_edge e
      JOIN qry_achievement a
        ON a.generation = e.generation AND a.achievement_id = e.achievement_id
      WHERE e.generation = #{generation}
        AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
      </script>
      """)
  long countCoauthorPairs(
      @Param("generation") String generation, @Param("dataNature") String dataNature);

  @Select(
      """
      <script>
      SELECT a.publication_year AS year, count(*) AS amount
      FROM qry_achievement a
      WHERE a.generation = #{generation}
        AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
      GROUP BY a.publication_year
      ORDER BY a.publication_year NULLS FIRST
      </script>
      """)
  List<YearRow> byYear(
      @Param("generation") String generation, @Param("dataNature") String dataNature);

  @Select(
      """
      <script>
      SELECT a.type AS bucket, count(*) AS amount
      FROM qry_achievement a
      WHERE a.generation = #{generation}
        AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
      GROUP BY a.type
      ORDER BY amount DESC, a.type
      </script>
      """)
  List<CountRow> byType(
      @Param("generation") String generation, @Param("dataNature") String dataNature);

  @Select(
      """
      SELECT a.data_nature AS bucket, count(*) AS amount
      FROM qry_achievement a
      WHERE a.generation = #{generation}
        AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
      GROUP BY a.data_nature
      ORDER BY a.data_nature
      """)
  List<CountRow> byNature(@Param("generation") String generation);

  /**
   * 机构计数。
   *
   * <p>{@code count(DISTINCT o.achievement_id)}：同成果同机构只计一次，跨机构各计一次
   * （架构 §9.2）。用 count(*) 会在同一成果出现同一机构多行时重复计数。
   */
  @Select(
      """
      <script>
      SELECT o.organization AS bucket, count(DISTINCT o.achievement_id) AS amount
      FROM qry_contribution_org o
      JOIN qry_achievement a
        ON a.generation = o.generation AND a.achievement_id = o.achievement_id
      WHERE o.generation = #{generation}
        AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
      GROUP BY o.organization
      ORDER BY amount DESC, o.organization
      LIMIT #{limit}
      </script>
      """)
  List<CountRow> topOrganizations(
      @Param("generation") String generation,
      @Param("dataNature") String dataNature,
      @Param("limit") int limit);

  @Select(
      """
      <script>
      SELECT t.topic_id AS bucket, count(DISTINCT t.achievement_id) AS amount
      FROM qry_achievement_topic t
      JOIN qry_achievement a
        ON a.generation = t.generation AND a.achievement_id = t.achievement_id
      WHERE t.generation = #{generation}
        AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
      GROUP BY t.topic_id
      ORDER BY amount DESC, t.topic_id
      LIMIT #{limit}
      </script>
      """)
  List<CountRow> topTopics(
      @Param("generation") String generation,
      @Param("dataNature") String dataNature,
      @Param("limit") int limit);

  @Select(
      """
      <script>
      SELECT a.venue AS bucket, count(*) AS amount
      FROM qry_achievement a
      WHERE a.generation = #{generation}
        AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
        AND a.venue IS NOT NULL AND a.venue &lt;&gt; ''
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
      GROUP BY a.venue
      ORDER BY amount DESC, a.venue
      LIMIT #{limit}
      </script>
      """)
  List<CountRow> topVenues(
      @Param("generation") String generation,
      @Param("dataNature") String dataNature,
      @Param("limit") int limit);

  /**
   * 机构归属未知的成果数。
   *
   * <p>单列返回而不是并入某个机构：未知就是未知，把它归到任意机构名下都会凭空创造一条统计事实
   * （架构 §9.2「未知单列不进入已知排名」）。
   */
  @Select(
      """
      <script>
      SELECT count(*) FROM qry_achievement a
      WHERE a.generation = #{generation}
        AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
        AND NOT EXISTS (
          SELECT 1 FROM qry_contribution_org o
          WHERE o.generation = a.generation AND o.achievement_id = a.achievement_id)
      </script>
      """)
  long countUnknownOrganization(
      @Param("generation") String generation, @Param("dataNature") String dataNature);

  @Select(
      """
      <script>
      SELECT coalesce(a.citation_source, 'UNKNOWN') AS citation_source,
             count(*) AS achievement_count,
             coalesce(sum(a.citation_count), 0) AS citation_sum
      FROM qry_achievement a
      WHERE a.generation = #{generation}
        AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
        AND a.citation_count IS NOT NULL
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
      GROUP BY coalesce(a.citation_source, 'UNKNOWN')
      ORDER BY citation_sum DESC
      </script>
      """)
  List<CitationRow> citationsBySource(
      @Param("generation") String generation, @Param("dataNature") String dataNature);
}
