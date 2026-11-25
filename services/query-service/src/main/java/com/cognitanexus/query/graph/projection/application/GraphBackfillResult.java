package com.cognitanexus.query.graph.projection.application;

import com.cognitanexus.query.graph.projection.domain.GraphBackfillStatus;

/**
 * 一次回填运行的结果（同时可作为「当前断点」的只读视图）。
 *
 * <p>{@code worksThisRun} 与 {@code processedWorks} 分开：前者是本次进程真正处理了多少（用于
 * 观察吞吐与「续跑是否真的在前进」），后者是该代累计值（用于与 {@code qry_achievement} 行数对账）。
 * 只给累计值时，一次什么都不做的续跑看起来也像「处理了 30 万条」。
 *
 * @param projectedVersion 结束后图的实际水位（可能因「只前进」而高于 baseRevision）
 */
public record GraphBackfillResult(
    String scope,
    GraphBackfillStatus status,
    String generation,
    long processedWorks,
    int processedBatches,
    long failedWorks,
    String cursorWorkId,
    long baseRevision,
    long projectedVersion,
    long worksThisRun,
    int batchesThisRun,
    String note) {

  public boolean completed() {
    return status == GraphBackfillStatus.COMPLETED;
  }

  /** 是否还能从这里继续（FAILED 与 RUNNING 都可以；COMPLETED 不需要）。 */
  public boolean resumable() {
    return status.resumable();
  }

  public String summary() {
    return String.format(
        "graph-backfill[scope=%s, status=%s, generation=%s, processed=%d, batches=%d, "
            + "failed=%d, cursor=%s, revision %d→%d, thisRun works=%d batches=%d]",
        scope,
        status,
        generation,
        processedWorks,
        processedBatches,
        failedWorks,
        cursorWorkId == null ? "-" : cursorWorkId,
        baseRevision,
        projectedVersion,
        worksThisRun,
        batchesThisRun);
  }
}
