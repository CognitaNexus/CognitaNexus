package com.cognitanexus.query.graph.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 无向合作边及其共同成果证据。
 *
 * <p>口径与 {@code qry_coauthor_edge} 完全一致：仅「真实、公开、生效、关系合格（AUTO /
 * SELF_CONFIRMED）」的共同成果建边；{@code strength} 是<b>不同合格成果数</b>，不是行数，
 * 也不随查询次数累加。
 *
 * <p>两端按规范 ID 字符串的固定字典序保存（{@code scholarA < scholarB}），所以同一个作者对
 * 无论从哪一端被发现都只有一种表示。证据按 {@code workId} 去重并排序，使相同输入产生相同输出：
 * 路径结果要可复核，证据列表的随机顺序会让「同一条路径两次响应不同」看起来像图在变。
 *
 * <p>{@code strength} 允许大于 {@link #evidence()} 的元素个数：持久化时每条边只带一段有上限的
 * 证据（避免单条边属性无界增长），而强度始终是真实计数。截断与否由 {@link #evidenceTruncated()}
 * 明确表达，不靠调用方从长度猜。
 */
public record GraphCollaborationEdge(
    String scholarA, String scholarB, long strength, List<GraphWorkEvidence> evidence) {

  public GraphCollaborationEdge {
    String left = GraphIdentifiers.require(scholarA, "scholarA");
    String right = GraphIdentifiers.require(scholarB, "scholarB");
    // 交换而不是报错：调用方从 b 端发现同一条边是合法场景（原来 PG 实现分两支查询），
    // 让边自己归一化比要求每个调用点都记得排序更不容易出错。
    if (left.compareTo(right) > 0) {
      String swap = left;
      left = right;
      right = swap;
    }
    scholarA = left;
    scholarB = right;

    Map<String, GraphWorkEvidence> deduped = new LinkedHashMap<>();
    if (evidence != null) {
      for (GraphWorkEvidence item : evidence) {
        deduped.putIfAbsent(item.workId(), item);
      }
    }
    List<GraphWorkEvidence> sorted = new ArrayList<>(deduped.values());
    sorted.sort(Comparator.comparing(GraphWorkEvidence::workId));
    evidence = List.copyOf(sorted);

    if (strength <= 0) {
      throw new IllegalArgumentException("合作边强度必须为正：" + strength);
    }
    if (evidence.isEmpty()) {
      // 「没有证据的合作边」是本设计明确禁止的形态：它无法被核对，也无法在撤销成果时下线。
      throw new IllegalArgumentException("合作边必须带至少一条共同成果证据：" + scholarA + " / " + scholarB);
    }
    if (strength < evidence.size()) {
      throw new IllegalArgumentException("合作边强度不能小于已列出的证据条数：" + strength + " < " + evidence.size());
    }
  }

  /** 边的规范键：两端 ID 的固定字典序拼接，用于去重与对账。 */
  public String pairKey() {
    return scholarA + "\u0001" + scholarB;
  }

  /** 已列出的证据是否少于真实强度（表示持久化侧对证据做了上限截断）。 */
  public boolean evidenceTruncated() {
    return strength > evidence.size();
  }

  /**
   * 合并同一条边的两种来源（物化边与二部图推导）。
   *
   * <p>两路结果覆盖的成果集合互不重叠（物化边只覆盖低作者数成果，二部图补高作者数成果），
   * 但同一作者对的两端方向在 Cypher 里会各出现一次，因此按 {@code workId} 去重后再取强度。
   * 强度取「两侧声明的强度」与「去重后证据数」的最大值：持久化侧可能截断证据，声明值更高时
   * 不得因为截断而把强度改小。
   */
  public static GraphCollaborationEdge merge(
      GraphCollaborationEdge left, GraphCollaborationEdge right) {
    if (!left.pairKey().equals(right.pairKey())) {
      throw new IllegalArgumentException("只能合并同一作者对的边：" + left.pairKey() + " / " + right.pairKey());
    }
    List<GraphWorkEvidence> merged = new ArrayList<>(left.evidence());
    merged.addAll(right.evidence());
    long distinct = merged.stream().map(GraphWorkEvidence::workId).distinct().count();
    long strength = Math.max(Math.max(left.strength(), right.strength()), distinct);
    return new GraphCollaborationEdge(left.scholarA(), left.scholarB(), strength, merged);
  }
}
