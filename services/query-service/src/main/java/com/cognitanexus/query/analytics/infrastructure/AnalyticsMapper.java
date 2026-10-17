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

  /**
   * 领域计数，按归属来源拆开。
   *
   * <p>两者之和恒等于 amount：归属表主键是 (成果, 领域)，同一成果在同一领域上只可能有一条归属，
   * 因此不存在被同时计入两侧的行。
   *
   * <p>名称与标识一起返回：榜单只给 id 时读者看到的是一串无意义的标识符，而领域名称就在
   * 归属表上，取 max 即可得到（同一领域在同一代内的名称一致）。
   */
  record TopicCountRow(
      String bucket,
      String canonicalName,
      long amount,
      long sourceAmount,
      long inferredAmount) {}

  record YearRow(Integer year, long amount) {}

  record CitationRow(String citationSource, long achievementCount, long citationSum) {}

  /** 领域概览表头。canonicalName 可能为空（投影时来源未给名称），由调用方决定如何展示。 */
  record TopicHeaderRow(
      String canonicalName, long achievements, long sourceAmount, long inferredAmount) {}

  /** 领域内的学者排名行。name 允许为空，缺失时由调用方退回显示标识。 */
  record ScholarRow(String scholarId, String name, long achievements) {}

  /**
   * 领域代表成果行。
   *
   * <p>引用量与来源一起返回，因为不同来源的引用量口径不同、不可相互比较；调用方排序时只能把
   * 引用量当作同来源内的次序，不能把它当成跨来源可比的绝对指标。
   */
  record TopicAchievementRow(
      String achievementId,
      String title,
      Integer publicationYear,
      Long citationCount,
      String citationSource) {}

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
      SELECT t.topic_id AS bucket,
             max(t.canonical_name) AS canonicalName,
             count(DISTINCT t.achievement_id) AS amount,
             count(DISTINCT t.achievement_id) FILTER (WHERE t.assignment_kind = 'SOURCE')
               AS sourceAmount,
             count(DISTINCT t.achievement_id) FILTER (WHERE t.assignment_kind = 'INFERRED')
               AS inferredAmount
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
  List<TopicCountRow> topTopics(
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

  // ---------------------------------------------------------------------------------------
  // 领域洞察：单个领域在当前公开口径下的构成。所有查询共用同一组过滤条件，避免各类计数
  // 因为各自漏掉一条过滤而在同一个页面上互相矛盾。
  // ---------------------------------------------------------------------------------------

  @Select(
      """
      <script>
      SELECT max(t.canonical_name) AS canonicalName,
             count(DISTINCT t.achievement_id) AS achievements,
             count(DISTINCT t.achievement_id) FILTER (WHERE t.assignment_kind = 'SOURCE')
               AS sourceAmount,
             count(DISTINCT t.achievement_id) FILTER (WHERE t.assignment_kind = 'INFERRED')
               AS inferredAmount
      FROM qry_achievement_topic t
      JOIN qry_achievement a
        ON a.generation = t.generation AND a.achievement_id = t.achievement_id
      WHERE t.generation = #{generation} AND t.topic_id = #{topicId}
        AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
      </script>
      """)
  TopicHeaderRow topicHeader(
      @Param("generation") String generation,
      @Param("topicId") String topicId,
      @Param("dataNature") String dataNature);

  @Select(
      """
      <script>
      SELECT a.publication_year AS year, count(DISTINCT t.achievement_id) AS amount
      FROM qry_achievement_topic t
      JOIN qry_achievement a
        ON a.generation = t.generation AND a.achievement_id = t.achievement_id
      WHERE t.generation = #{generation} AND t.topic_id = #{topicId}
        AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
      GROUP BY a.publication_year
      ORDER BY a.publication_year NULLS FIRST
      </script>
      """)
  List<YearRow> topicByYear(
      @Param("generation") String generation,
      @Param("topicId") String topicId,
      @Param("dataNature") String dataNature);

  @Select(
      """
      <script>
      SELECT a.type AS bucket, count(DISTINCT t.achievement_id) AS amount
      FROM qry_achievement_topic t
      JOIN qry_achievement a
        ON a.generation = t.generation AND a.achievement_id = t.achievement_id
      WHERE t.generation = #{generation} AND t.topic_id = #{topicId}
        AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
      GROUP BY a.type
      ORDER BY amount DESC, a.type
      </script>
      """)
  List<CountRow> topicByType(
      @Param("generation") String generation,
      @Param("topicId") String topicId,
      @Param("dataNature") String dataNature);

  @Select(
      """
      <script>
      SELECT o.organization AS bucket, count(DISTINCT o.achievement_id) AS amount
      FROM qry_achievement_topic t
      JOIN qry_contribution_org o
        ON o.generation = t.generation AND o.achievement_id = t.achievement_id
      JOIN qry_achievement a
        ON a.generation = t.generation AND a.achievement_id = t.achievement_id
      WHERE t.generation = #{generation} AND t.topic_id = #{topicId}
        AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
        AND o.organization IS NOT NULL AND btrim(o.organization) &lt;&gt; ''
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
      GROUP BY o.organization
      ORDER BY amount DESC, o.organization
      LIMIT #{limit}
      </script>
      """)
  List<CountRow> topicOrganizations(
      @Param("generation") String generation,
      @Param("topicId") String topicId,
      @Param("dataNature") String dataNature,
      @Param("limit") int limit);

  /** 领域内机构未知的成果数。与总量单列口径一致：未知不并入任何机构。 */
  @Select(
      """
      <script>
      SELECT count(DISTINCT t.achievement_id)
      FROM qry_achievement_topic t
      JOIN qry_achievement a
        ON a.generation = t.generation AND a.achievement_id = t.achievement_id
      WHERE t.generation = #{generation} AND t.topic_id = #{topicId}
        AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
        AND NOT EXISTS (
          SELECT 1 FROM qry_contribution_org o
          WHERE o.generation = t.generation AND o.achievement_id = t.achievement_id
            AND o.organization IS NOT NULL AND btrim(o.organization) &lt;&gt; '')
      </script>
      """)
  long topicUnknownOrganization(
      @Param("generation") String generation,
      @Param("topicId") String topicId,
      @Param("dataNature") String dataNature);

  /**
   * 领域内学者排名。
   *
   * <p>只计 AUTO 与 SELF_CONFIRMED：待核验/争议/已排除的关系不能因为「出现过名字」就进入排名，
   * 那等于把一条未确认的归属当成既成事实展示给读者。
   */
  @Select(
      """
      <script>
      SELECT c.scholar_id AS scholarId,
             max(c.scholar_name) AS name,
             count(DISTINCT c.achievement_id) AS achievements
      FROM qry_achievement_topic t
      JOIN qry_contribution c
        ON c.generation = t.generation AND c.achievement_id = t.achievement_id
      JOIN qry_achievement a
        ON a.generation = t.generation AND a.achievement_id = t.achievement_id
      WHERE t.generation = #{generation} AND t.topic_id = #{topicId}
        AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
        AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
      GROUP BY c.scholar_id
      ORDER BY achievements DESC, c.scholar_id
      LIMIT #{limit}
      </script>
      """)
  List<ScholarRow> topicScholars(
      @Param("generation") String generation,
      @Param("topicId") String topicId,
      @Param("dataNature") String dataNature,
      @Param("limit") int limit);

  /**
   * 领域内学者总数。
   *
   * <p>榜单是截断的，总量必须单独数：用榜单行数当总量，会让「共 12 位学者」随着上限从 10 调到
   * 20 而变成 20，读者看不出这个数字其实是页面上限而不是事实。
   */
  @Select(
      """
      <script>
      SELECT count(DISTINCT c.scholar_id)
      FROM qry_achievement_topic t
      JOIN qry_contribution c
        ON c.generation = t.generation AND c.achievement_id = t.achievement_id
      JOIN qry_achievement a
        ON a.generation = t.generation AND a.achievement_id = t.achievement_id
      WHERE t.generation = #{generation} AND t.topic_id = #{topicId}
        AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
        AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
      </script>
      """)
  long topicScholarCount(
      @Param("generation") String generation,
      @Param("topicId") String topicId,
      @Param("dataNature") String dataNature);

  @Select(
      """
      <script>
      SELECT a.venue AS bucket, count(DISTINCT t.achievement_id) AS amount
      FROM qry_achievement_topic t
      JOIN qry_achievement a
        ON a.generation = t.generation AND a.achievement_id = t.achievement_id
      WHERE t.generation = #{generation} AND t.topic_id = #{topicId}
        AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
        AND a.venue IS NOT NULL AND btrim(a.venue) &lt;&gt; ''
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
      GROUP BY a.venue
      ORDER BY amount DESC, a.venue
      LIMIT #{limit}
      </script>
      """)
  List<CountRow> topicVenues(
      @Param("generation") String generation,
      @Param("topicId") String topicId,
      @Param("dataNature") String dataNature,
      @Param("limit") int limit);

  /**
   * 领域代表成果。
   *
   * <p>排序键是「发表年份降序，同年再按引用量降序」而不是「按引用量降序」：引用量来自不同来源
   * 时口径不同，跨来源比较没有意义；当年份相同时读者比较的通常是同一来源的同一批记录，这时
   * 引用量才有次序含义。这也是本查询把 citation_source 一并返回的原因。
   */
  @Select(
      """
      <script>
      SELECT a.achievement_id AS achievementId,
             a.title AS title,
             a.publication_year AS publicationYear,
             a.citation_count AS citationCount,
             a.citation_source AS citationSource
      FROM qry_achievement_topic t
      JOIN qry_achievement a
        ON a.generation = t.generation AND a.achievement_id = t.achievement_id
      WHERE t.generation = #{generation} AND t.topic_id = #{topicId}
        AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
      ORDER BY a.publication_year DESC NULLS LAST,
               a.citation_count DESC NULLS LAST,
               a.achievement_id
      LIMIT #{limit}
      </script>
      """)
  List<TopicAchievementRow> topicRepresentative(
      @Param("generation") String generation,
      @Param("topicId") String topicId,
      @Param("dataNature") String dataNature,
      @Param("limit") int limit);
}
