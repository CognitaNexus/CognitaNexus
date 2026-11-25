package com.cognitanexus.query.graph.projection.application;

import java.util.List;

/**
 * 一次增量消费的结果。
 *
 * <p>为什么要分别给出 {@code fromRevision} / {@code projectedRevision} / {@code stoppedEventId}：
 * 「事件读到几条」与「水位推进到哪里」是两件事。只有事件全部成功应用之后水位才前进；一旦某条
 * 成果写入失败就停在它之前，缺口因此可见（下一次仍会从同一条事件重来），而不是「读了一堆、
 * 水位却跳到后面」——后者会让被跳过的事件永远不再被处理。
 *
 * @param eventsRead 本次读到的事件数
 * @param eventsIgnored 不影响图投影的事件数（门户授权、认领结论、未知但已由 PG 应用的类型）
 * @param worksDeleted 因 PG 当前代已无该成果行（合并删除、旧代残留）而删除的图内残留成果数
 * @param failedWorks 写入或清理失败的成果数
 * @param fromRevision 消费前的水位
 * @param projectedRevision 消费后的水位（只有事件全部成功应用才前进；存在任何失败时与
 *     {@code fromRevision} 相同）
 * @param stoppedEventId 因失败而停止时的事件标识；未停止为 null
 */
public record GraphEventProjectionResult(
    int eventsRead,
    int eventsIgnored,
    long worksApplied,
    long worksRetired,
    long worksDeleted,
    long worksStale,
    long failedWorks,
    long fromRevision,
    long projectedRevision,
    String stoppedEventId,
    List<String> notes) {

  public GraphEventProjectionResult {
    notes = notes == null ? List.of() : List.copyOf(notes);
  }

  public boolean watermarkAdvanced() {
    return projectedRevision > fromRevision;
  }

  public boolean stopped() {
    return stoppedEventId != null;
  }

  public String summary() {
    return String.format(
        "graph-events[read=%d, ignored=%d, applied=%d, retired=%d, deleted=%d, stale=%d, "
            + "failed=%d, revision %d→%d%s]",
        eventsRead,
        eventsIgnored,
        worksApplied,
        worksRetired,
        worksDeleted,
        worksStale,
        failedWorks,
        fromRevision,
        projectedRevision,
        stopped() ? ", stoppedAt=" + stoppedEventId : "");
  }
}
