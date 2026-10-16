package com.cognitanexus.ingestion.collect.domain;

/**
 * 采集任务状态。
 *
 * <p>依据：架构 V0.2 §5.3。终态（SUCCEEDED / FAILED）不再被租约领取；PAUSED 表示管理员主动
 * 暂停，可恢复。刻意不设「部分成功」：判据是「输入要么被交付、要么被登记异常」，因此任务成功
 * 与失败的区别在于未交付项是否已登记，而不是简单地按成功条数比例。
 */
public enum JobStatus {
  PENDING,
  RUNNING,
  PAUSED,
  SUCCEEDED,
  FAILED;

  /** 是否可被租约领取。 */
  public boolean claimable() {
    return this == PENDING || this == RUNNING;
  }

  public boolean terminal() {
    return this == SUCCEEDED || this == FAILED;
  }

  public static JobStatus of(String raw) {
    return raw == null ? null : valueOf(raw);
  }
}
