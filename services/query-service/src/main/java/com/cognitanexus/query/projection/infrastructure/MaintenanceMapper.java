package com.cognitanexus.query.projection.infrastructure;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * generation 与重建运行记录的持久化。
 *
 * <p>依据：架构 V0.2 §7.1、§7.3。这里集中了本服务唯一允许「切换读视图」的语句：
 * {@link #switchGeneration} 是单行 UPDATE，因此切换本身是原子的——不存在「一半查询读新代、
 * 一半读旧代」的中间状态。
 */
@Mapper
public interface MaintenanceMapper {

  record GenerationRow(
      String generation, String status, Instant createdAt, Instant readyAt, String note) {}

  record RebuildRunRow(
      String id,
      String generation,
      String status,
      Instant startedAt,
      Instant finishedAt,
      Long baseRevision,
      Long replayedRevision,
      long importedRows,
      String note) {}

  @Select(
      """
      SELECT active_generation FROM qry_consumer_state WHERE consumer_id = #{consumerId}
      """)
  String activeGeneration(@Param("consumerId") String consumerId);

  @Insert(
      """
      INSERT INTO qry_generation (generation, status, note)
      VALUES (#{generation}, 'BUILDING', #{note})
      ON CONFLICT (generation) DO NOTHING
      """)
  int createGeneration(@Param("generation") String generation, @Param("note") String note);

  @Update(
      """
      UPDATE qry_generation SET status = 'READY', ready_at = now()
      WHERE generation = #{generation} AND status = 'BUILDING'
      """)
  int markGenerationReady(@Param("generation") String generation);

  @Update(
      """
      UPDATE qry_generation SET status = 'RETIRED', note = #{note}
      WHERE generation = #{generation} AND status = 'READY'
      """)
  int retireGeneration(@Param("generation") String generation, @Param("note") String note);

  @Update(
      """
      UPDATE qry_generation SET status = 'FAILED', note = #{note}
      WHERE generation = #{generation} AND status = 'BUILDING'
      """)
  int markGenerationFailed(@Param("generation") String generation, @Param("note") String note);

  @Select(
      """
      SELECT generation, status, created_at, ready_at, note
      FROM qry_generation ORDER BY created_at
      """)
  List<GenerationRow> listGenerations();

  /**
   * 原子切换活动代并同时发布水位。
   *
   * <p>两个字段必须一起写：若先切换再发布水位，中间窗口内的查询会读到新代（内容为新快照）
   * 却仍带着旧水位，而屏障判断依据的正是这个水位，于是可能出现「内容已撤销但水位说没撤销」。
   */
  @Update(
      """
      UPDATE qry_consumer_state
      SET active_generation = #{generation},
          published_revision = #{publishedRevision},
          published_at = now(),
          updated_at = now()
      WHERE consumer_id = #{consumerId}
      """)
  int switchGeneration(
      @Param("consumerId") String consumerId,
      @Param("generation") String generation,
      @Param("publishedRevision") long publishedRevision);

  @Insert(
      """
      INSERT INTO qry_rebuild_run (id, generation, status, base_revision, note)
      VALUES (#{id}, #{generation}, 'RUNNING', #{baseRevision}, #{note})
      """)
  int startRebuild(
      @Param("id") String id,
      @Param("generation") String generation,
      @Param("baseRevision") Long baseRevision,
      @Param("note") String note);

  @Update(
      """
      UPDATE qry_rebuild_run
      SET status = #{status}, finished_at = now(), replayed_revision = #{replayedRevision},
          imported_rows = #{importedRows}, note = #{note}
      WHERE id = #{id}
      """)
  int finishRebuild(
      @Param("id") String id,
      @Param("status") String status,
      @Param("replayedRevision") Long replayedRevision,
      @Param("importedRows") long importedRows,
      @Param("note") String note);

  @Select(
      """
      SELECT id, generation, status, started_at, finished_at, base_revision, replayed_revision,
             imported_rows, note
      FROM qry_rebuild_run
      ORDER BY started_at DESC
      LIMIT #{limit}
      """)
  List<RebuildRunRow> listRebuildRuns(@Param("limit") int limit);

  @Select(
      """
      SELECT count(*) FROM qry_generation WHERE status = 'RETIRED' AND generation <> #{protect}
      """)
  long countRetiredGenerations(@Param("protect") String protect);

  /**
   * 可清理的代数。
   *
   * <p>同时包含 RETIRED 与 FAILED：重建中途放弃会留下一个只剩部分行的 FAILED 代，若不清理，
   * 每次失败的重建都会永久占着一份投影空间，而它在任何列表里都不显眼。
   */
  @Select(
      """
      SELECT generation FROM qry_generation
      WHERE status IN ('RETIRED', 'FAILED') AND generation <> #{protect}
      """)
  List<String> listRetiredGenerations(@Param("protect") String protect);

  /**
   * 清理某一代的全部投影行。
   *
   * <p>写成一条带多个数据修改 CTE 的语句：PostgreSQL 保证 WITH 中的数据修改子句一定会执行到底，
   * 与主查询是否读取其输出无关，因此这里可以只用一个语句原子地清空五张表，避免分五次执行时
   * 中途失败留下半清空的代。
   */
  @Select(
      """
      WITH del_ach AS (DELETE FROM qry_achievement WHERE generation = #{generation} RETURNING 1),
           del_con AS (DELETE FROM qry_contribution WHERE generation = #{generation} RETURNING 1),
           del_org AS (DELETE FROM qry_contribution_org
                       WHERE generation = #{generation} RETURNING 1),
           del_top AS (DELETE FROM qry_achievement_topic
                       WHERE generation = #{generation} RETURNING 1),
           del_edg AS (DELETE FROM qry_coauthor_edge
                       WHERE generation = #{generation} RETURNING 1)
      SELECT ((SELECT count(*) FROM del_ach) + (SELECT count(*) FROM del_con)
            + (SELECT count(*) FROM del_org) + (SELECT count(*) FROM del_top)
            + (SELECT count(*) FROM del_edg))::bigint AS removed
      """)
  long purgeGenerationRows(@Param("generation") String generation);

  @Update(
      """
      DELETE FROM qry_generation
      WHERE generation = #{generation} AND status IN ('RETIRED', 'FAILED')
      """)
  int deleteGenerationRow(@Param("generation") String generation);
}
