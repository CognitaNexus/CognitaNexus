package com.cognitanexus.query.graph.projection.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 图投影的 PG 读取（{@code query_db} 读投影，不含 core 权威库）。
 *
 * <p>语句约定：
 *
 * <ul>
 *   <li>每条都带 {@code generation}：跨代读数会得到「新代成果 + 旧代参与关系」的拼接，
 *       而计数与边都会因此自相矛盾；
 *   <li>分页按 {@code achievement_id} 稳定升序 + {@code LIMIT}，游标比较用 {@code >}。
 *       不用 {@code OFFSET}：回填途中插入新成果会让 offset 错位，表现为「有些成果永远轮不到」；
 *   <li>批量取四张表时用 {@code IN} 一次取回，而不是逐条查 N+1 次——回填 500 条一批，
 *       N+1 会把一批变成 2000 次往返。
 * </ul>
 *
 * <p>只读：本 Mapper 没有任何写语句。图投影不得反向修改 PG 读投影，否则「PG 是唯一事实权威」
 * 这条不变量就只剩文档里的一句话。
 */
@Mapper
public interface GraphProjectionSourceMapper {

  record StageRow(String generation, long publishedRevision) {}

  record WorkRow(
      String workId,
      String title,
      String dataNature,
      String visibility,
      String effectiveStatus,
      long aggregateVersion,
      long revision) {}

  record ContributionRow(
      String achievementId,
      String scholarId,
      String scholarName,
      String relationState,
      Integer authorOrder) {}

  record TopicRow(String achievementId, String topicId) {}

  record OrgRow(String achievementId, String organization) {}

  record IdRow(String workId) {}

  record EventRow(String id, String eventType, long catalogRevision, String payloadJson) {}

  /** 参与关系最小视图（对账重算作者对时只需要两端标识）。 */
  record QualifiedRow(String achievementId, String scholarId) {}

  @Select(
      """
      SELECT active_generation AS generation, published_revision AS published_revision
      FROM qry_consumer_state
      WHERE consumer_id = #{consumerId}
      """)
  StageRow readStage(@Param("consumerId") String consumerId);

  @Select(
      """
      <script>
      SELECT achievement_id AS workId, title, data_nature AS dataNature, visibility,
             effective_status AS effectiveStatus, aggregate_version AS aggregateVersion,
             revision
      FROM qry_achievement
      WHERE generation = #{generation}
      <if test="afterWorkId != null and afterWorkId != ''">
        AND achievement_id &gt; #{afterWorkId}
      </if>
      ORDER BY achievement_id
      LIMIT #{limit}
      </script>
      """)
  List<WorkRow> pageWorks(
      @Param("generation") String generation,
      @Param("afterWorkId") String afterWorkId,
      @Param("limit") int limit);

  @Select(
      """
      <script>
      SELECT achievement_id AS workId, title, data_nature AS dataNature, visibility,
             effective_status AS effectiveStatus, aggregate_version AS aggregateVersion,
             revision
      FROM qry_achievement
      WHERE generation = #{generation}
        AND achievement_id IN
        <foreach collection="workIds" item="id" open="(" separator="," close=")">#{id}</foreach>
      </script>
      """)
  List<WorkRow> loadWorks(
      @Param("generation") String generation, @Param("workIds") List<String> workIds);

  @Select(
      """
      <script>
      SELECT achievement_id, scholar_id, scholar_name, relation_state, author_order
      FROM qry_contribution
      WHERE generation = #{generation}
        AND achievement_id IN
        <foreach collection="workIds" item="id" open="(" separator="," close=")">#{id}</foreach>
      ORDER BY achievement_id, author_order NULLS LAST, scholar_id
      </script>
      """)
  List<ContributionRow> loadContributions(
      @Param("generation") String generation, @Param("workIds") List<String> workIds);

  @Select(
      """
      <script>
      SELECT achievement_id, topic_id
      FROM qry_achievement_topic
      WHERE generation = #{generation}
        AND achievement_id IN
        <foreach collection="workIds" item="id" open="(" separator="," close=")">#{id}</foreach>
      ORDER BY achievement_id, topic_id
      </script>
      """)
  List<TopicRow> loadTopics(
      @Param("generation") String generation, @Param("workIds") List<String> workIds);

  @Select(
      """
      <script>
      SELECT achievement_id, organization
      FROM qry_contribution_org
      WHERE generation = #{generation}
        AND achievement_id IN
        <foreach collection="workIds" item="id" open="(" separator="," close=")">#{id}</foreach>
      ORDER BY achievement_id, organization
      </script>
      """)
  List<OrgRow> loadOrganizations(
      @Param("generation") String generation, @Param("workIds") List<String> workIds);

  @Select(
      """
      <script>
      SELECT count(*)
      FROM qry_achievement a
      WHERE a.generation = #{generation}
        AND a.visibility = 'PUBLIC'
        AND a.effective_status = 'EFFECTIVE'
        <if test="dataOrigin != null">AND a.data_nature = #{dataOrigin}</if>
      </script>
      """)
  long countEligibleWorks(
      @Param("generation") String generation, @Param("dataOrigin") String dataOrigin);

  @Select(
      """
      <script>
      SELECT count(*)
      FROM qry_contribution c
      JOIN qry_achievement a
        ON a.generation = c.generation AND a.achievement_id = c.achievement_id
      WHERE c.generation = #{generation}
        AND a.visibility = 'PUBLIC'
        AND a.effective_status = 'EFFECTIVE'
        AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')
        <if test="dataOrigin != null">AND a.data_nature = #{dataOrigin}</if>
      </script>
      """)
  long countQualifiedContributions(
      @Param("generation") String generation, @Param("dataOrigin") String dataOrigin);

  @Select(
      """
      <script>
      SELECT count(DISTINCT c.scholar_id)
      FROM qry_contribution c
      JOIN qry_achievement a
        ON a.generation = c.generation AND a.achievement_id = c.achievement_id
      WHERE c.generation = #{generation}
        AND a.visibility = 'PUBLIC'
        AND a.effective_status = 'EFFECTIVE'
        AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')
        <if test="dataOrigin != null">AND a.data_nature = #{dataOrigin}</if>
      </script>
      """)
  long countContributors(
      @Param("generation") String generation, @Param("dataOrigin") String dataOrigin);

  /**
   * 按物化阈值计算应存在的作者对总数：{@code sum(n*(n-1)/2)}，只统计作者数在 {@code [2, threshold]}。
   *
   * <p>这里算的是「作者对」而不是 {@code count(*) FROM qry_coauthor_edge}：PG 侧边表本身也可能
   * 因故缺行，用它做基准会把「两边都少」当成一致。作者数由参与关系直接聚合，是独立口径。
   */
  @Select(
      """
      <script>
      SELECT coalesce(sum(cnt.author_count * (cnt.author_count - 1) / 2), 0)
      FROM (
        SELECT c.achievement_id, count(*) AS author_count
        FROM qry_contribution c
        JOIN qry_achievement a
          ON a.generation = c.generation AND a.achievement_id = c.achievement_id
        WHERE c.generation = #{generation}
          AND a.visibility = 'PUBLIC'
          AND a.effective_status = 'EFFECTIVE'
          AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')
          <if test="dataOrigin != null">AND a.data_nature = #{dataOrigin}</if>
        GROUP BY c.achievement_id
      ) cnt
      WHERE cnt.author_count BETWEEN 2 AND #{threshold}
      </script>
      """)
  long countMaterializedPairs(
      @Param("generation") String generation,
      @Param("threshold") int threshold,
      @Param("dataOrigin") String dataOrigin);

  @Select(
      """
      <script>
      SELECT a.achievement_id AS workId
      FROM qry_achievement a
      WHERE a.generation = #{generation}
        AND a.visibility = 'PUBLIC'
        AND a.effective_status = 'EFFECTIVE'
        <if test="dataOrigin != null">AND a.data_nature = #{dataOrigin}</if>
        <if test="afterWorkId != null and afterWorkId != ''">
          AND a.achievement_id &gt; #{afterWorkId}
        </if>
      ORDER BY a.achievement_id
      LIMIT #{limit}
      </script>
      """)
  List<IdRow> pageEligibleWorkIds(
      @Param("generation") String generation,
      @Param("afterWorkId") String afterWorkId,
      @Param("limit") int limit,
      @Param("dataOrigin") String dataOrigin);

  @Select(
      """
      <script>
      SELECT c.achievement_id, c.scholar_id
      FROM qry_contribution c
      WHERE c.generation = #{generation}
        AND c.relation_state IN ('AUTO', 'SELF_CONFIRMED')
        AND c.achievement_id IN
        <foreach collection="workIds" item="id" open="(" separator="," close=")">#{id}</foreach>
      ORDER BY c.achievement_id, c.scholar_id
      </script>
      """)
  List<QualifiedRow> loadQualifiedContributions(
      @Param("generation") String generation, @Param("workIds") List<String> workIds);

  /**
   * 已由 PG 投影应用的事件，按修订升序。
   *
   * <p>只取 {@code APPLIED}：被隔离的事件在 PG 侧尚未成为事实，图若抢先投影就等于绕过了
   * 公开资格与 schemaVersion 的判断。
   */
  @Select(
      """
      SELECT id, event_type, catalog_revision, payload::text AS payloadJson
      FROM qry_inbox_event
      WHERE consumer_id = #{consumerId}
        AND processing_state = 'APPLIED'
        AND catalog_revision > #{revision}
      ORDER BY catalog_revision, id
      LIMIT #{limit}
      """)
  List<EventRow> listAppliedEventsAfter(
      @Param("consumerId") String consumerId,
      @Param("revision") long revision,
      @Param("limit") int limit);

  @Select(
      """
      SELECT DISTINCT achievement_id AS workId
      FROM qry_contribution
      WHERE generation = #{generation} AND scholar_id = #{scholarId}
      ORDER BY achievement_id
      """)
  List<IdRow> achievementIdsOfScholar(
      @Param("generation") String generation, @Param("scholarId") String scholarId);
}
