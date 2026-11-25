package com.cognitanexus.query.graph.projection.infrastructure;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 图投影 Backfill 断点的持久化（V7 新增表 {@code qry_graph_projection_checkpoint}）。
 *
 * <p>写入用 {@code ON CONFLICT (scope) DO UPDATE} 而不是先删后插：断点是「当前进度」这一唯一
 * 事实，不需要历史版本；先删后插会在一瞬间没有断点，此时进程被杀就等于「回填从未开始」。
 */
@Mapper
public interface GraphProjectionCheckpointMapper {

  record CheckpointRow(
      String scope,
      String status,
      String generation,
      String cursorWorkId,
      long baseRevision,
      long processedWorks,
      int processedBatches,
      long failedWorks,
      String lastError,
      Instant startedAt,
      Instant updatedAt,
      Instant finishedAt) {}

  @Select(
      """
      SELECT scope, status, generation, cursor_work_id, base_revision, processed_works,
             processed_batches, failed_works, last_error, started_at, updated_at, finished_at
      FROM qry_graph_projection_checkpoint
      WHERE scope = #{scope}
      """)
  CheckpointRow find(@Param("scope") String scope);

  @Insert(
      """
      INSERT INTO qry_graph_projection_checkpoint
        (scope, status, generation, cursor_work_id, base_revision, processed_works,
         processed_batches, failed_works, last_error, started_at, updated_at, finished_at)
      VALUES
        (#{row.scope}, #{row.status}, #{row.generation}, #{row.cursorWorkId}, #{row.baseRevision},
         #{row.processedWorks}, #{row.processedBatches}, #{row.failedWorks}, #{row.lastError},
         #{row.startedAt}, #{row.updatedAt}, #{row.finishedAt})
      ON CONFLICT (scope) DO UPDATE SET
        status = EXCLUDED.status,
        generation = EXCLUDED.generation,
        cursor_work_id = EXCLUDED.cursor_work_id,
        base_revision = EXCLUDED.base_revision,
        processed_works = EXCLUDED.processed_works,
        processed_batches = EXCLUDED.processed_batches,
        failed_works = EXCLUDED.failed_works,
        last_error = EXCLUDED.last_error,
        started_at = EXCLUDED.started_at,
        updated_at = EXCLUDED.updated_at,
        finished_at = EXCLUDED.finished_at
      """)
  int upsert(@Param("row") CheckpointRow row);
}
