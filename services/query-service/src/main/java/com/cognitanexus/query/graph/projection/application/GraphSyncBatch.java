package com.cognitanexus.query.graph.projection.application;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 一批成果的投影结果汇总。
 *
 * <p>计数按动作分档而不是只给「成功/失败」：一次回填的正常结果里既有 APPLIED，也有大量
 * STALE_IGNORED（续跑时重扫的批次），把后者算进失败会让任何一次断点续传都「有失败记录」，
 * 于是真正的失败不再显眼。
 */
public record GraphSyncBatch(List<GraphSyncOutcome> outcomes) {

  public GraphSyncBatch {
    outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
  }

  public static GraphSyncBatch empty() {
    return new GraphSyncBatch(List.of());
  }

  public GraphSyncBatch plus(GraphSyncBatch other) {
    if (other == null || other.outcomes().isEmpty()) {
      return this;
    }
    List<GraphSyncOutcome> merged = new java.util.ArrayList<>(outcomes);
    merged.addAll(other.outcomes());
    return new GraphSyncBatch(merged);
  }

  public int processed() {
    return outcomes.size();
  }

  public long count(GraphSyncOutcome.Action action) {
    return outcomes.stream().filter(outcome -> outcome.action() == action).count();
  }

  public long applied() {
    return count(GraphSyncOutcome.Action.APPLIED);
  }

  public long retired() {
    return count(GraphSyncOutcome.Action.RETIRED);
  }

  public long stale() {
    return count(GraphSyncOutcome.Action.STALE_IGNORED);
  }

  public long failed() {
    return outcomes.stream().filter(GraphSyncOutcome::failed).count();
  }

  public long permanentFailures() {
    return count(GraphSyncOutcome.Action.FAILED_PERMANENT);
  }

  /** 失败成果的标识与原因，用于断点里的错误摘要（截断到前若干条，避免无界文本）。 */
  public String failureSummary(int limit) {
    return outcomes.stream()
        .filter(GraphSyncOutcome::failed)
        .limit(Math.max(limit, 1))
        .map(outcome -> outcome.workId() + ":" + outcome.action() + ":" + outcome.note())
        .collect(Collectors.joining("; "));
  }
}
