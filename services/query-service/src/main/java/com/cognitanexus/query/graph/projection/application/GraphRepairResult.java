package com.cognitanexus.query.graph.projection.application;

import java.util.List;

/**
 * 定向修复结果。
 *
 * <p>修复只做两件事：把 PG 里合格但图里缺失/失真的成果<b>重新同步</b>，以及把图里已不被 PG 认可的
 * 成果<b>退场</b>。它不做第三件事——直接改水位。水位只由回填与事件增量按「每条事件都已应用」的
 * 规则推进；对账顺手把水位调到 PG 值，等于用一次读操作伪造了「已追平」的证据。
 */
public record GraphRepairResult(
    int repairedWorks, int retiredWorks, int failedWorks, List<String> notes) {

  public GraphRepairResult {
    notes = notes == null ? List.of() : List.copyOf(notes);
  }

  public boolean complete() {
    return failedWorks == 0;
  }

  public String summary() {
    return String.format(
        "graph-repair[repaired=%d, retired=%d, failed=%d, notes=%s]",
        repairedWorks, retiredWorks, failedWorks, notes.size());
  }
}
