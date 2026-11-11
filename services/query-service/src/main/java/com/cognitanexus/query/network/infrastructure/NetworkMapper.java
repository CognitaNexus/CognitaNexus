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

  /**
   * 「以这些学者为 a 端」的合作边（扩边查询的一半，与 {@link #listEdgesWhereScholarB} 成对使用）。
   *
   * <p>为什么不是一条 {@code scholar_a IN (...) OR scholar_b IN (...)}：实测（2026-10-18，真实库）这条
   * OR 查询的执行计划对 ID 集合敏感——同一形状的语句，有的 ID 集合走 54ms 的双侧位图索引计划，
   * 有的集合被规划器判成「按主键扫整代再过滤」的 2.2 秒计划，字面量与绑定参数都会踩中，且
   * 服务端无法干预计划选择。拆成两条单列 IN 之后，列与索引一一对应（ix_qry_coauthor_edge_a/b），
   * 三种执行方式（字面量、自定义计划、泛型计划）实测全部稳定在 1～19ms。两端各自查全该对的
   * 强度（count(DISTINCT achievement_id) 不受端点筛选影响），调用方按对去重合并即可，不会重复计数。
   *
   * <p>过滤条件与 {@link #listEdges} 完全一致，保证「同一个边在两个出口的口径相同」。
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
        AND e.scholar_a IN
             <foreach collection="scholars" item="s" open="(" separator="," close=")">#{s}</foreach>
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
  List<EdgeRow> listEdgesWhereScholarA(
      @Param("generation") String generation,
      @Param("dataNature") String dataNature,
      @Param("topicId") String topicId,
      @Param("organization") String organization,
      @Param("scholars") List<String> scholars,
      @Param("limit") int limit);

  /** {@link #listEdgesWhereScholarA} 的 b 端版本，除列名外逐字相同，保证两支口径一致。 */
  @Select(
      """
      <script>
      SELECT e.scholar_a, e.scholar_b, count(DISTINCT e.achievement_id) AS strength
      FROM qry_coauthor_edge e
      JOIN qry_achievement a
        ON a.generation = e.generation AND a.achievement_id = e.achievement_id
      WHERE e.generation = #{generation}
        AND a.effective_status = 'EFFECTIVE' AND a.visibility = 'PUBLIC'
        AND e.scholar_b IN
             <foreach collection="scholars" item="s" open="(" separator="," close=")">#{s}</foreach>
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
  List<EdgeRow> listEdgesWhereScholarB(
      @Param("generation") String generation,
      @Param("dataNature") String dataNature,
      @Param("topicId") String topicId,
      @Param("organization") String organization,
      @Param("scholars") List<String> scholars,
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
