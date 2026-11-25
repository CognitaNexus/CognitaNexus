package com.cognitanexus.query.graph.projection.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图侧只读检视端口（对账用）。
 *
 * <p>与 {@code GraphProjectionStore} 分开的理由：写端口属于投影链路，读端口属于运维检视，
 * 调用方、权限与失败语义都不同。混在一起会出现「对账工具顺手拿到了写方法」，而对账的动作是
 * <b>先读后判</b>，它不应有能力直接改图——修复必须走投影链路（再同步/退场），才能保持
 * 「图只能由 PG 派生」这条不变量。
 *
 * <p>计数口径与 PG 侧必须一一对应：
 *
 * <ul>
 *   <li>{@code publicWorks} = {@code Work.is_public = true}（可选按 {@code data_origin}）；
 *   <li>{@code scholars} = 在<b>公开</b>成果上有合格 {@code AUTHORED} 的 distinct 学者。墓碑成果上
 *       保留的参与边不计入——它们只用于追溯与二部图，不构成「当前可见的合作者」；
 *   <li>{@code authoredRelations} = 上述参与边条数；
 *   <li>{@code materializedEdges} = 全部 {@code COOPERATED_WITH} 条数（物化边只存在于公开成果上，
 *       退场时已按 {@code work_id} 删净）。
 * </ul>
 */
public interface GraphProjectionInspector {

  /** 图侧四个核心计数。 */
  record GraphCounts(
      long publicWorks, long scholars, long authoredRelations, long materializedEdges) {}

  /**
   * 图侧计数。
   *
   * @param dataOrigin 数据性质（REAL/SIMULATED/MIXED）或 null 表示不限制
   */
  GraphCounts counts(String dataOrigin);

  /** 图内公开成果标识（升序，最多 {@code limit} 条；对账用它判断孤立节点）。 */
  List<String> publicWorkIds(int limit);

  /** 给定标识集合中，图里处于公开状态的子集。 */
  Set<String> publicWorkIdsAmong(Collection<String> workIds);

  /** 指定成果的物化合作边（{@code pairKey → {aUSb}}，仅 pairs 集合的承载结构）。 */
  Map<String, Set<String>> materializedPairsOf(Collection<String> workIds);

  /** 指定成果在图里的标题（证据核对：边上的 work_title 与工作节点标题都要与 PG 一致）。 */
  Map<String, String> workTitles(Collection<String> workIds);

  /**
   * 图上以 {@code work_id} 记录的证据标题（{@code COOPERATED_WITH.work_title}），
   * 用于发现「节点标题已更新、边证据还停在旧标题」这类不一致。
   */
  Map<String, Set<String>> evidenceTitlesOf(Collection<String> workIds);
}
