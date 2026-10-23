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
      String integrityStatus,
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

  /**
   * 关键词候选集：五个分支各走各的 trigram 索引，再用 UNION 去重。
   *
   * <p>为什么不能写成一个 OR 链：PostgreSQL 的 BitmapOr 要求 OR 的<strong>每一支</strong>都能转成
   * 索引扫描。作者名那一支是 EXISTS 子查询，转不了，于是整条 OR 退化成 Filter——生成计划里能
   * 看到它老老实实用了 ix_qry_achievement_eligible 取出全部候选行，再逐行取堆算三次 LIKE。
   * 实测 18,001 行候选、单次 335 ms，而且 count 与取页两条语句各跑一次，接口总耗时因此翻倍。
   *
   * <p>拆成 UNION 之后每一支都走自己的索引，候选集只含真正命中的 ID，主查询再从候选集回表。
   * 同一份数据实测：计数 438 ms → 9.0 ms，取页 417 ms → 7.8 ms。
   *
   * <p>五支各自都带 generation / EFFECTIVE / PUBLIC 条件，是为了让索引选择不受外层影响；
   * 严格说作者名那一支可以不带（外层 JOIN 回 qry_achievement 时会再判一次），但它带了也不影响
   * 结果，反而让五支的形状一致、单看一支就能判断它是否泄漏了不该出现的行。
   *
   * <p>用 UNION 而不是 UNION ALL：同一篇成果可能同时命中题名与摘要，不去重会让计数偏大。
   */
  String KEYWORD_CANDIDATE_ARMS =
      """
          SELECT a1.achievement_id FROM qry_achievement a1
           WHERE a1.generation = #{generation}
             AND a1.effective_status = 'EFFECTIVE'
             AND a1.visibility = 'PUBLIC'
             AND a1.title_norm LIKE '%' || #{criteria.keywordLike} || '%'
          UNION
          SELECT a2.achievement_id FROM qry_achievement a2
           WHERE a2.generation = #{generation}
             AND a2.effective_status = 'EFFECTIVE'
             AND a2.visibility = 'PUBLIC'
             AND lower(coalesce(a2.venue, '')) LIKE '%' || #{criteria.keywordLike} || '%'
          UNION
          SELECT a3.achievement_id FROM qry_achievement a3
           WHERE a3.generation = #{generation}
             AND a3.effective_status = 'EFFECTIVE'
             AND a3.visibility = 'PUBLIC'
             AND lower(coalesce(a3.abstract_text, '')) LIKE '%' || #{criteria.keywordLike} || '%'
          UNION
          SELECT a4.achievement_id FROM qry_achievement a4
           WHERE a4.generation = #{generation}
             AND a4.effective_status = 'EFFECTIVE'
             AND a4.visibility = 'PUBLIC'
             AND lower(coalesce(a4.doi, '')) = #{criteria.keywordNorm}
          UNION
          SELECT k.achievement_id FROM qry_contribution k
           WHERE k.generation = #{generation}
             AND k.relation_state IN ('AUTO', 'SELF_CONFIRMED')
             AND lower(coalesce(k.scholar_name, '')) LIKE '%' || #{criteria.keywordLike} || '%'
          """;

  /**
   * 关键词顺序过滤：短关键词（取不出 trigram）时唯一可行的写法。
   *
   * <p>它与 {@link #KEYWORD_CANDIDATE_ARMS} 的语义必须完全一致，改动其一时另一处必须同步改——
   * 这条重复是刻意的取舍：两种写法在短词与长词上各快一个数量级，没有任何单一写法能同时覆盖。
   *
   * <p>短词为什么不用 UNION：每一支都取不出三元组，只能整表扫描；五支各扫一遍再哈希去重，
   * 实测单字母关键词 660 ms，比这里的顺序过滤（264 ms）慢一倍多。
   */
  String KEYWORD_SEQUENTIAL_FILTER =
      """
        AND (a.title_norm LIKE '%' || #{criteria.keywordLike} || '%'
             OR lower(coalesce(a.venue, '')) LIKE '%' || #{criteria.keywordLike} || '%'
             OR lower(coalesce(a.abstract_text, '')) LIKE '%' || #{criteria.keywordLike} || '%'
             OR lower(coalesce(a.doi, '')) = #{criteria.keywordNorm}
             OR EXISTS (
               SELECT 1 FROM qry_contribution k
               WHERE k.generation = a.generation
                 AND k.achievement_id = a.achievement_id
                 AND k.relation_state IN ('AUTO', 'SELF_CONFIRMED')
                 AND lower(coalesce(k.scholar_name, ''))
                     LIKE '%' || #{criteria.keywordLike} || '%'))
        """;

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
   *
   * <p>关键词条件按能否取到 trigram 分两条路，见 {@link #KEYWORD_CANDIDATE_ARMS} 与
   * {@link #KEYWORD_SEQUENTIAL_FILTER} 的说明。两条路的谓词语义相同，只是访问路径不同。
   */
  @Select(
      """
      <script>
      SELECT count(*)
      FROM qry_achievement a
      <if test="criteria.keywordTrigramUsable">
        JOIN (
      """
          + KEYWORD_CANDIDATE_ARMS
          + """
        ) kw ON kw.achievement_id = a.achievement_id
      </if>
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
        <if test="criteria.keywordNorm != null and not criteria.keywordTrigramUsable">
      """
          + KEYWORD_SEQUENTIAL_FILTER
          + """
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
             a.effective_status, a.integrity_status, a.publication_year, a.doi, a.venue,
             a.citation_count, a.citation_source, a.updated_at,
             <choose>
               <when test="criteria.keywordNorm != null">
                 similarity(a.title_norm, #{criteria.keywordNorm})
               </when>
               <otherwise>0.0</otherwise>
             </choose>
             AS relevance
      FROM qry_achievement a
      <if test="criteria.keywordTrigramUsable">
        JOIN (
      """
          + KEYWORD_CANDIDATE_ARMS
          + """
        ) kw ON kw.achievement_id = a.achievement_id
      </if>
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
        <if test="criteria.keywordNorm != null and not criteria.keywordTrigramUsable">
      """
          + KEYWORD_SEQUENTIAL_FILTER
          + """
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
          AND lower(coalesce(c.scholar_name, '')) LIKE '%' || #{criteria.keywordLike} || '%'
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
            AND lower(coalesce(c.scholar_name, '')) LIKE '%' || #{criteria.keywordLike} || '%'
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

  record TitleSuggestion(
      String achievementId, String title, String type, Integer publicationYear) {}

  record ScholarSuggestion(String scholarId, String scholarName, long achievementCount) {}

  /**
   * 检索框的题名建议。
   *
   * <p>只取题名维度：作者建议由 {@link #suggestScholars} 单独给出，两类结果在界面上分组显示，
   * 不能混成一个列表——把「某位学者的名字」和「某篇论文的标题」排在一起，用户点错是必然的。
   *
   * <p>排序刻意分两档：前缀命中优先于中间命中，再按相似度。纯按相似度排会让「深度学习」排在
   * 「基于深度学习的……」之前，而后者显然才是用户在敲的那一条。
   */
  @Select(
      """
      <script>
      SELECT a.achievement_id, a.title, a.type, a.publication_year
      FROM qry_achievement a
      WHERE a.generation = #{generation}
        AND a.effective_status = 'EFFECTIVE'
        AND a.visibility = 'PUBLIC'
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
        AND (a.title_norm LIKE '%' || #{keywordLike} || '%'
             OR similarity(a.title_norm, #{keywordNorm}) &gt; 0.08)
      ORDER BY (a.title_norm LIKE #{keywordLike} || '%') DESC,
               similarity(a.title_norm, #{keywordNorm}) DESC,
               a.publication_year DESC NULLS LAST,
               a.achievement_id
      LIMIT #{limit}
      </script>
      """)
  List<TitleSuggestion> suggestTitles(
      @Param("generation") String generation,
      @Param("keywordNorm") String keywordNorm,
      @Param("keywordLike") String keywordLike,
      @Param("dataNature") String dataNature,
      @Param("limit") int limit);

  /**
   * 检索框的学者建议。
   *
   * <p>只统计合格关系（AUTO / SELF_CONFIRMED）：待核验与已排除的关系不能借「建议」这个入口
   * 重新出现在页面上，否则 BR-IMP-08 在检索页就形同虚设。
   */
  @Select(
      """
      <script>
      SELECT c.scholar_id,
             max(c.scholar_name) AS scholar_name,
             count(DISTINCT c.achievement_id) AS achievement_count
      FROM qry_contribution c
      JOIN qry_achievement a
        ON a.generation = c.generation AND a.achievement_id = c.achievement_id
      WHERE c.generation = #{generation}
        AND a.effective_status = 'EFFECTIVE'
        AND a.visibility = 'PUBLIC'
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
        AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')
        AND lower(coalesce(c.scholar_name, '')) LIKE '%' || #{keywordLike} || '%'
      GROUP BY c.scholar_id
      ORDER BY achievement_count DESC, c.scholar_id
      LIMIT #{limit}
      </script>
      """)
  List<ScholarSuggestion> suggestScholars(
      @Param("generation") String generation,
      @Param("keywordLike") String keywordLike,
      @Param("dataNature") String dataNature,
      @Param("limit") int limit);
}
