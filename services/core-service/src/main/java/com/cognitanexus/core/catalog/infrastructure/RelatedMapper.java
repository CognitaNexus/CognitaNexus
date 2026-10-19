package com.cognitanexus.core.catalog.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 相关成果推荐的单条 SQL。
 *
 * <p>依据：需求基线「相关成果」扩展项。四条信号全部来自平台已有数据，不引入任何外部服务：
 *
 * <ul>
 *   <li><b>同作者</b>：参与关系（AUTO / SELF_CONFIRMED）。PENDING_VERIFY 的关系不算——待核验的
 *       署名还没有被确认，拿它推「同一作者的其他成果」等于把未核实的事实当依据。
 *   <li><b>共被引</b>：两篇成果被同一篇论文引用。citation_reference 里 citing_achievement_id
 *       是引用方，cited_achievement_id 是被引方；两边都在平台内才成立。
 *   <li><b>共享参考文献</b>：两篇成果的参考文献列表有交集（bibliographic coupling）。
 *       用 cited_value_norm 比而不是 cited_achievement_id 比：大量参考文献指向平台外，
 *       只比库内 ID 会把「都引了同一篇 Nature」这种最强的信号漏掉。
 *   <li><b>同主题</b>：主题归属交集。这条最泛，权重最低，只在前三类不够时用来兜底。
 * </ul>
 *
 * <p>为什么写成一条 SQL 而不是四次查询再在 Java 里合并：
 * 四种子查询都要用到候选集，分开查会各自扫一遍 core_achievement_topic 与引用表；合成一条，
 * PostgreSQL 只解析一次 CTE。更重要的是「分数怎么算」这件事写在 SQL 里只有一份，Java 侧
 * 不再参与打分，避免出现「排序看起来对但权重散落在两个地方」。
 *
 * <p>权重是有意倾斜的：同作者 1 个就满分 40，共被引每条 8 分（上限 32），共享参考文献每条
 * 3 分（上限 27），同主题每个 3 分（上限 18）。同作者最可信（同一个人写的），共被引次之
 * （学术共同体认为相关），同主题最泛（一个大类下可能有上万篇）。
 */
@Mapper
public interface RelatedMapper {

  /**
   * 一条候选。
   *
   * <p>字段顺序必须与 SELECT 的列顺序一致：MyBatis 用构造器映射 record，按位置绑定。
   */
  record CandidateRow(
      String achievementId,
      String title,
      Integer publicationYear,
      String venue,
      String doi,
      Integer citationCount,
      long sharedTopics,
      long sharedAuthors,
      long sharedReferences,
      long coCitedBy,
      long score) {}

  @Select(
      """
      WITH base AS (SELECT #{achievementId}::text AS aid),
      theme AS (
        SELECT t2.achievement_id AS cand, count(*) AS n
          FROM core_achievement_topic t1
          JOIN core_achievement_topic t2
            ON t2.topic_id = t1.topic_id
           AND t2.achievement_id <> t1.achievement_id
         WHERE t1.achievement_id = (SELECT aid FROM base)
         GROUP BY t2.achievement_id
      ),
      author AS (
        SELECT c2.achievement_id AS cand, count(DISTINCT c2.scholar_id) AS n
          FROM core_contribution c1
          JOIN core_contribution c2
            ON c2.scholar_id = c1.scholar_id
           AND c2.achievement_id <> c1.achievement_id
         WHERE c1.achievement_id = (SELECT aid FROM base)
           AND c1.relation_state IN ('AUTO', 'SELF_CONFIRMED')
           AND c2.relation_state IN ('AUTO', 'SELF_CONFIRMED')
         GROUP BY c2.achievement_id
      ),
      coupling AS (
        SELECT r2.citing_achievement_id AS cand, count(DISTINCT r1.cited_value_norm) AS n
          FROM core_citation_reference r1
          JOIN core_citation_reference r2
            ON r2.cited_value_norm = r1.cited_value_norm
           AND r2.citing_achievement_id <> r1.citing_achievement_id
         WHERE r1.citing_achievement_id = (SELECT aid FROM base)
         GROUP BY r2.citing_achievement_id
      ),
      cocite AS (
        SELECT r2.cited_achievement_id AS cand, count(DISTINCT r1.citing_achievement_id) AS n
          FROM core_citation_reference r1
          JOIN core_citation_reference r2
            ON r2.citing_achievement_id = r1.citing_achievement_id
         WHERE r1.cited_achievement_id = (SELECT aid FROM base)
           AND r2.cited_achievement_id IS NOT NULL
           AND r2.cited_achievement_id <> (SELECT aid FROM base)
         GROUP BY r2.cited_achievement_id
      ),
      cand AS (
        SELECT cand FROM theme
        UNION SELECT cand FROM author
        UNION SELECT cand FROM coupling
        UNION SELECT cand FROM cocite
      )
      SELECT a.id                                  AS achievement_id,
             a.title                               AS title,
             a.publication_year                    AS publication_year,
             p.venue                               AS venue,
             p.doi                                 AS doi,
             p.citation_count                      AS citation_count,
             COALESCE(th.n, 0)                     AS shared_topics,
             COALESCE(au.n, 0)                     AS shared_authors,
             COALESCE(cp.n, 0)                     AS shared_references,
             COALESCE(co.n, 0)                     AS co_cited_by,
             (LEAST(COALESCE(th.n, 0) * 3, 18)
              + LEAST(COALESCE(au.n, 0) * 40, 40)
              + LEAST(COALESCE(cp.n, 0) * 3, 27)
              + LEAST(COALESCE(co.n, 0) * 8, 32))  AS score
        FROM cand c
        JOIN core_achievement a ON a.id = c.cand
        LEFT JOIN core_paper p ON p.achievement_id = a.id
        LEFT JOIN theme th ON th.cand = c.cand
        LEFT JOIN author au ON au.cand = c.cand
        LEFT JOIN coupling cp ON cp.cand = c.cand
        LEFT JOIN cocite co ON co.cand = c.cand
       WHERE a.visibility = 'PUBLIC'
         AND a.effective_status = 'EFFECTIVE'
         AND a.merged_into_id IS NULL
         AND a.data_nature = 'REAL'
       ORDER BY score DESC, p.citation_count DESC NULLS LAST, a.id
       LIMIT #{limit}
      """)
  List<CandidateRow> findRelated(
      @Param("achievementId") String achievementId, @Param("limit") int limit);
}
