package com.cognitanexus.query.graph.projection.domain;

/**
 * 图投影回填的持久化状态。
 *
 * <p>只有三个状态，没有 INTERRUPTED：进程被杀时数据库里留下的是 {@code RUNNING} 加一个游标，
 * 这本身就是「可继续」的证据。再造一个「运行中但已死」的状态只会让判定依赖心跳时间，
 * 而心跳在长事务里本来就不可信。
 */
public enum GraphBackfillStatus {
  /** 正在回填；cursor 之前的成果已写入图，可以从这里继续。 */
  RUNNING,
  /** 本代已全量写入图，水位已推进到 base_revision；之后由事件增量追平。 */
  COMPLETED,
  /** 因数据/图库错误中止；保留游标与错误原因，修复后可以 resume。 */
  FAILED;

  /** 是否可以从当前游标继续（RUNNING/FAILED 都可以，COMPLETED 不需要继续）。 */
  public boolean resumable() {
    return this != COMPLETED;
  }
}
