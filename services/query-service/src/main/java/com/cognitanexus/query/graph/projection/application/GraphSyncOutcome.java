package com.cognitanexus.query.graph.projection.application;

/**
 * 单条成果的一次投影结果。
 *
 * <p>{@code STALE_IGNORED} 不是错误：版本守卫拒绝更旧/相同版本的写入正是幂等投递、重复回填
 * 与迟到事件的正常结局。把它记成失败会让人去排查一个不存在的问题；把它记成「已应用」又会
 * 掩盖「其实没写」——所以它单独一档，并且不计入应用的成果数。
 *
 * <p>失败分两档（PERMANENT / RETRYABLE，见 {@link GraphProjectionErrorClassifier}）：
 * 运维需要知道「要不要重跑」；合在一起只剩「失败」，重跑与否只能靠猜。
 */
public record GraphSyncOutcome(
    String workId, Action action, long sourceVersion, int authorships, int attempts, String note) {

  public enum Action {
    /** 成果节点与边已写入（含作者集合与物化判定）。 */
    APPLIED,
    /** 成果不再合格：留下墓碑并删除物化合作边。 */
    RETIRED,
    /** 版本守卫拒绝：已有版本不更旧，未产生任何副作用。 */
    STALE_IGNORED,
    /** 域/数据错误：重试同一输入仍会失败。 */
    FAILED_PERMANENT,
    /** 瞬态基础设施错误且重试次数耗尽。 */
    FAILED_RETRYABLE
  }

  public GraphSyncOutcome {
    if (workId == null || workId.isBlank()) {
      throw new IllegalArgumentException("同步结果必须带 workId");
    }
    if (action == null) {
      throw new IllegalArgumentException("同步结果必须带 action");
    }
    sourceVersion = Math.max(sourceVersion, 0L);
    authorships = Math.max(authorships, 0);
    attempts = Math.max(attempts, 1);
    note = note == null ? "" : note;
  }

  public boolean failed() {
    return action == Action.FAILED_PERMANENT || action == Action.FAILED_RETRYABLE;
  }

  public boolean permanentFailure() {
    return action == Action.FAILED_PERMANENT;
  }

  /** 是否真正改动了图（应用或退场）。 */
  public boolean applied() {
    return action == Action.APPLIED || action == Action.RETIRED;
  }

  public String describe() {
    return workId
        + " "
        + action
        + " version="
        + sourceVersion
        + " authors="
        + authorships
        + " attempts="
        + attempts
        + (note.isBlank() ? "" : " note=" + note);
  }
}
