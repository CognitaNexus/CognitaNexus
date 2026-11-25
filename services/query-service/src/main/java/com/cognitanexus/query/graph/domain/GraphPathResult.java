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
 *
 * <p><b>{@link #loadedNodes()} / {@link #loadedEdges()}</b>：本次查询为了得出结论而<b>实际载入</b>的
 * 搜索子图规模（去重后的学者数与作者对数），不是路径本身的大小。它必须与 PG 侧
 * {@code withinLimits.nodes/edges} 同口径，否则响应里的诊断字段会告诉运维与对照器一件假事：
 * 「找 1 跳路径只加载了 2 个节点」会让真正接近上限的查询看起来轻得可疑。双侧搜索时按 PG 的
 * 合并口径给出（节点取两侧较大值，边取两侧之和）。
 *
 * @param reachable 是否可达
 * @param hops 跳数；不可达为 {@code null}
 * @param path 路径节点序列（含起终点）；不可达为空
 * @param hopEdges 逐跳证据，长度等于跳数
 * @param loadedNodes 搜索期间实际载入的学者数（含起点/终点的种子）
 * @param loadedEdges 搜索期间实际载入的去重合作边数
 * @param note 可公开的说明
 */
public record GraphPathResult(
    boolean reachable,
    Integer hops,
    List<String> path,
    List<GraphCollaborationEdge> hopEdges,
    int loadedNodes,
    int loadedEdges,
    String note) {

  /** 路径选择规则说明，随响应返回，避免调用方按「BFS 首次触达」自行猜测。 */
  public static final String PATH_RULE = "跳数最少；等长时取规范 ID 序列字典序最小的一条；只返回一条，不枚举全部等长路径";

  public GraphPathResult {
    path = path == null ? List.of() : List.copyOf(path);
    hopEdges = hopEdges == null ? List.of() : List.copyOf(hopEdges);
    note = note == null ? "" : note;
    if (loadedNodes < 0 || loadedEdges < 0) {
      throw new IllegalArgumentException(
          "载入规模不能为负：loadedNodes=" + loadedNodes + ", loadedEdges=" + loadedEdges);
    }
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

  /** 不可达结果。仍然要带载入规模：不可达也是一次真实搜索，规模诊断不能因此丢失。 */
  public static GraphPathResult unreachable(String note, int loadedNodes, int loadedEdges) {
    return new GraphPathResult(false, null, List.of(), List.of(), loadedNodes, loadedEdges, note);
  }
}
