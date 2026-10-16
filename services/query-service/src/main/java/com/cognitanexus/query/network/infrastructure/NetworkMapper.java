package com.cognitanexus.query.network.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 合作图查询。
 *
 * <p>依据：架构 V0.2 §9.3。
 *
 * <p>边的口径：仅真实、公开、生效、关系合格的共同论文建边。这些条件在查询里与建边时的条件完全
 * 一致——若查询侧再做一遍过滤，两处迟早分叉，出现「图里有一条边但按查询条件不该有」的情况。
 *
 * <p>范围限制：先数节点、再取边，都带硬上限。文档要求超预算时明确失败
 * （{@code GRAPH_SCOPE_TOO_LARGE}），不能截断后冒充「无路径」，因此取边时多取一行以判断是否越界。
 */
@Mapper
public interface NetworkMapper {

  record EdgeRow(String scholarA, String scholarB, long strength) {}

  record ScholarNameRow(String scholarId, String scholarName, long achievementCount) {}

  @Select(
      """
      <script>
      SELECT count(*) FROM (
        SELECT e.scholar_a AS s
        FROM qry_coauthor_edge e
        JOIN qry_achievement a
          ON a.generation = e.generation AND a.achievement_id = e.achievement_id
        WHERE e.generation = #{generation}
          AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
          <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
          <if test="topicId != null">
            AND EXISTS (SELECT 1 FROM qry_achievement_topic t
                        WHERE t.generation = a.generation AND t.achievement_id = a.achievement_id
                          AND t.topic_id = #{topicId})
          </if>
          <if test="organization != null">
            AND EXISTS (SELECT 1 FROM qry_contribution_org o
                        WHERE o.generation = a.generation AND o.achievement_id = a.achievement_id
                          AND o.organization = #{organization})
          </if>
        UNION
        SELECT e.scholar_b AS s
        FROM qry_coauthor_edge e
        JOIN qry_achievement a
          ON a.generation = e.generation AND a.achievement_id = e.achievement_id
        WHERE e.generation = #{generation}
          AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
          <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
          <if test="topicId != null">
            AND EXISTS (SELECT 1 FROM qry_achievement_topic t
                        WHERE t.generation = a.generation AND t.achievement_id = a.achievement_id
                          AND t.topic_id = #{topicId})
          </if>
          <if test="organization != null">
            AND EXISTS (SELECT 1 FROM qry_contribution_org o
                        WHERE o.generation = a.generation AND o.achievement_id = a.achievement_id
                          AND o.organization = #{organization})
          </if>
      ) nodes
      </script>
      """)
  long countNodes(
      @Param("generation") String generation,
      @Param("dataNature") String dataNature,
      @Param("topicId") String topicId,
      @Param("organization") String organization);

  /**
   * 子图边与边强度。
   *
   * <p>强度是「不同合格论文数」，由 {@code count(DISTINCT achievement_id)} 得出，而不是行数：
   * 同一对作者可能合作多篇，行数等于论文数，但若同一论文产生多行（理论上不该发生）行数就会虚高。
   * 用去重计数使强度定义只依赖语义，不依赖存储细节。
   */
  @Select(
      """
      <script>
      SELECT e.scholar_a, e.scholar_b, count(DISTINCT e.achievement_id) AS strength
      FROM qry_coauthor_edge e
      JOIN qry_achievement a
        ON a.generation = e.generation AND a.achievement_id = e.achievement_id
      WHERE e.generation = #{generation}
        AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
        <if test="topicId != null">
          AND EXISTS (SELECT 1 FROM qry_achievement_topic t
                      WHERE t.generation = a.generation AND t.achievement_id = a.achievement_id
                        AND t.topic_id = #{topicId})
        </if>
        <if test="organization != null">
          AND EXISTS (SELECT 1 FROM qry_contribution_org o
                      WHERE o.generation = a.generation AND o.achievement_id = a.achievement_id
                        AND o.organization = #{organization})
        </if>
      GROUP BY e.scholar_a, e.scholar_b
      ORDER BY e.scholar_a, e.scholar_b
      LIMIT #{limit}
      </script>
      """)
  List<EdgeRow> listEdges(
      @Param("generation") String generation,
      @Param("dataNature") String dataNature,
      @Param("topicId") String topicId,
      @Param("organization") String organization,
      @Param("limit") int limit);

  @Select(
      """
      <script>
      SELECT c.scholar_id, max(c.scholar_name) AS scholar_name,
             count(DISTINCT c.achievement_id) AS achievement_count
      FROM qry_contribution c
      JOIN qry_achievement a
        ON a.generation = c.generation AND a.achievement_id = c.achievement_id
      WHERE c.generation = #{generation}
        AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
        AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')
        AND c.scholar_id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
      GROUP BY c.scholar_id
      </script>
      """)
  List<ScholarNameRow> listScholarNames(
      @Param("generation") String generation, @Param("ids") List<String> ids);

  @Select(
      """
      <script>
      SELECT count(DISTINCT c.achievement_id)
      FROM qry_contribution c
      JOIN qry_achievement a
        ON a.generation = c.generation AND a.achievement_id = c.achievement_id
      WHERE c.generation = #{generation} AND c.scholar_id = #{scholarId}
        AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
        AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')
        <if test="dataNature != null">AND a.data_nature = #{dataNature}</if>
        <if test="topicId != null">
          AND EXISTS (SELECT 1 FROM qry_achievement_topic t
                      WHERE t.generation = a.generation AND t.achievement_id = a.achievement_id
                        AND t.topic_id = #{topicId})
        </if>
      </script>
      """)
  long countScholarAchievements(
      @Param("generation") String generation,
      @Param("scholarId") String scholarId,
      @Param("dataNature") String dataNature,
      @Param("topicId") String topicId);
}
