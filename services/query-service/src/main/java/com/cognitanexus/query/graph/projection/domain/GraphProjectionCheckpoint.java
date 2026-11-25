package com.cognitanexus.query.graph.projection.domain;

import java.time.Instant;

/**
 * 图投影 Backfill 的持久化断点（PG 表 {@code qry_graph_projection_checkpoint}，每个 scope 一行）。
 *
 * <p>断点与水位（{@code GraphProjectionMeta.projected_version}）是<b>两个不同的东西</b>：
 *
 * <ul>
 *   <li>水位回答「图能不能用于公开查询」，必须只前进、语义极简；
 *   <li>断点回答「回填做到哪了」，可以停在 FAILED、可以带错误原因、可以回退到上一次成功的批次。
 * </ul>
 *
 * <p>把两者合成一个字段的后果是：一次断点写入失败会被读成水位回退，而已下线的合作边会因此重新被
 * 认为是「未追平」——看起来只是慢，实际是回到旧数据上服务。
 *
 * <p>游标用 {@code workId} 而不是数字下标：分页按 {@code work_id} 稳定升序，插入新成果不会让
 * 游标错位；重复扫描的批次因写入幂等而安全。
 */
public record GraphProjectionCheckpoint(
    String scope,
    GraphBackfillStatus status,
    String generation,
    String cursorWorkId,
    long baseRevision,
    long processedWorks,
    int processedBatches,
    long failedWorks,
    String lastError,
    Instant startedAt,
    Instant updatedAt,
    Instant finishedAt) {

  public GraphProjectionCheckpoint {
    if (scope == null || scope.isBlank()) {
      throw new IllegalArgumentException("投影作用域不能为空");
    }
    scope = scope.trim();
    if (status == null) {
      throw new IllegalArgumentException("断点状态不能为空");
    }
    if (generation == null || generation.isBlank()) {
      throw new IllegalArgumentException("断点必须记录投影对应的 PG generation");
    }
    generation = generation.trim();
    cursorWorkId = cursorWorkId == null || cursorWorkId.isBlank() ? null : cursorWorkId;
    baseRevision = Math.max(baseRevision, 0L);
    processedWorks = Math.max(processedWorks, 0L);
    processedBatches = Math.max(processedBatches, 0);
    failedWorks = Math.max(failedWorks, 0L);
    startedAt = startedAt == null ? Instant.EPOCH : startedAt;
    updatedAt = updatedAt == null ? startedAt : updatedAt;
  }

  /** 新建一次回填（从第一页开始）。 */
  public static GraphProjectionCheckpoint started(
      String scope, String generation, long baseRevision, Instant now) {
    return new GraphProjectionCheckpoint(
        scope,
        GraphBackfillStatus.RUNNING,
        generation,
        null,
        baseRevision,
        0L,
        0,
        0L,
        null,
        now,
        now,
        null);
  }

  /**
   * 记录一批进度。
   *
   * @param cursor 本批最后一条成果标识；为空表示本批没有成果（不该在成功路径出现，保留是为了
   *     不把「空批」误记成游标前进）
   */
  public GraphProjectionCheckpoint advanced(String cursor, int works, long failed, Instant now) {
    return advanced(cursor, works, failed, lastError, now);
  }

  /**
   * 记录一批进度，并记下本批失败的摘要。
   *
   * <p>失败摘要写进 {@code last_error} 而不是直接抛异常：单条成果永久失败不应该让整次回填停在
   * 同一条脏数据上（那会让它后面的成果永远轮不到修复），但失败必须可定位——{@code failed_works}
   * 只给数量，{@code last_error} 给出「哪条、什么原因」。错过的点在游标继续前进后由
   * {@code GraphProjectionReconciler.repair()} 单点补齐。
   *
   * @param error 本批失败摘要；null 表示保留上一次的错误摘要（成功批次不应抹掉已知问题）
   */
  public GraphProjectionCheckpoint advanced(
      String cursor, int works, long failed, String error, Instant now) {
    if (cursor == null || cursor.isBlank()) {
      throw new IllegalArgumentException("进度推进必须带游标；空游标会让下一轮从第一页重扫");
    }
    return new GraphProjectionCheckpoint(
        scope,
        GraphBackfillStatus.RUNNING,
        generation,
        cursor,
        baseRevision,
        processedWorks + Math.max(works, 0),
        processedBatches + 1,
        failedWorks + Math.max(failed, 0L),
        error == null ? lastError : error,
        startedAt,
        now,
        null);
  }

  /** 回填完成：保留游标（供对账定位末条）与统计，状态转 COMPLETED。 */
  public GraphProjectionCheckpoint completed(Instant now) {
    return new GraphProjectionCheckpoint(
        scope,
        GraphBackfillStatus.COMPLETED,
        generation,
        cursorWorkId,
        baseRevision,
        processedWorks,
        processedBatches,
        failedWorks,
        lastError,
        startedAt,
        now,
        now);
  }

  /** 中止：保留游标与统计，修复后可以从同一位置 resume。 */
  public GraphProjectionCheckpoint failed(String error, Instant now) {
    return new GraphProjectionCheckpoint(
        scope,
        GraphBackfillStatus.FAILED,
        generation,
        cursorWorkId,
        baseRevision,
        processedWorks,
        processedBatches,
        failedWorks,
        error == null || error.isBlank() ? "未记录原因" : error,
        startedAt,
        now,
        now);
  }

  /**
   * 修复收尾：把「因部分成果失败而停在 FAILED」的断点恢复为 COMPLETED。
   *
   * <p><b>只能在有对账证据时调用</b>（见 {@code GraphBackfillService.markRepairedAndCompleted}）：
   * 一次人工的「我修好了」不能替代「图上确实没有缺口了」，否则水位就成了口头声明。
   *
   * <p>{@code failedWorks} 归零、{@code lastError} 清空（原因已进日志与对账报告），
   * {@code processedWorks} 补上本次单点修复真正写进图的成果数，使累计计数与真实图内容一致；
   * 游标与 {@code baseRevision} 保持不变（它们记录的是快照锚点，不是修复动作）。
   *
   * @param processedDelta 本次修复实际写入图的成果数（{@code STALE_IGNORED} 不计入）
   */
  public GraphProjectionCheckpoint repaired(long processedDelta, Instant now) {
    return new GraphProjectionCheckpoint(
        scope,
        GraphBackfillStatus.COMPLETED,
        generation,
        cursorWorkId,
        baseRevision,
        processedWorks + Math.max(processedDelta, 0L),
        processedBatches,
        0L,
        null,
        startedAt,
        now,
        now);
  }

  /** 是否可以从当前游标继续。 */
  public boolean resumable() {
    return status.resumable();
  }

  /**
   * 断点对应的 generation 与 PG 当前代不同时必须重建，而不是在错误的代上续跑。
   *
   * <p>两侧都 {@code trim()} 后比较：{@code generation} 可能来自配置/运维脚本，尾随空格不该被读成
   * 「换代了，必须整库重建」——那会让一次无害的输入差异触发一次全量重建。
   */
  public boolean matchesGeneration(String activeGeneration) {
    return activeGeneration != null && generation.equals(activeGeneration.trim());
  }
}
