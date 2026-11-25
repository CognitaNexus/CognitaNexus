package com.cognitanexus.query.graph.domain;

import java.util.List;

/**
 * 最短合作路径的结果。
 *
 * <p>规则与 {@code NetworkService} 的 PG 实现逐字一致：跳数最少；等长时取规范 ID 序列字典序
 * 最小的一条；只返回一条，不枚举全部等长路径；深度上限取
 * {@link com.cognitanexus.query.network.domain.NetworkRules#MAX_PATH_DEPTH}（常量单一来源，
 * 本类不复制数值），超出时如实说明「N 跳内不连通」而不是「全图不连通」。
 *
 * <p>{@link #hopEdges()} 给出每一跳的边与共同成果证据，满足 IN01「逐跳有证据」。它按路径顺序
 * 排列，长度等于 {@code path.size()-1}；零跳路径没有边。
 */
public record GraphPathResult(
    boolean reachable,
    Integer hops,
    List<String> path,
    List<GraphCollaborationEdge> hopEdges,
    String note) {

  /** 路径选择规则说明，随响应返回，避免调用方按「BFS 首次触达」自行猜测。 */
  public static final String PATH_RULE = "跳数最少；等长时取规范 ID 序列字典序最小的一条；只返回一条，不枚举全部等长路径";

  public GraphPathResult {
    path = path == null ? List.of() : List.copyOf(path);
    hopEdges = hopEdges == null ? List.of() : List.copyOf(hopEdges);
    note = note == null ? "" : note;
    if (reachable) {
      if (hops == null || hops < 0) {
        throw new IllegalArgumentException("可达路径必须给出非负跳数");
      }
      if (path.size() != hops + 1) {
        throw new IllegalArgumentException(
            "路径节点数必须等于跳数 + 1：path=" + path.size() + ", hops=" + hops);
      }
      if (hopEdges.size() != hops) {
        throw new IllegalArgumentException(
            "逐跳证据数必须等于跳数：edges=" + hopEdges.size() + ", hops=" + hops);
      }
    } else {
      if (hops != null) {
        throw new IllegalArgumentException("不可达结果不应带跳数");
      }
      if (!path.isEmpty() || !hopEdges.isEmpty()) {
        throw new IllegalArgumentException("不可达结果不应带路径或证据");
      }
    }
  }

  public static GraphPathResult unreachable(String note) {
    return new GraphPathResult(false, null, List.of(), List.of(), note);
  }
}
