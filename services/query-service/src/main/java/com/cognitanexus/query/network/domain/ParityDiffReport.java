package com.cognitanexus.query.network.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 新旧查询对照的逐字段差异明细。
 *
 * <p>为什么需要「明细」而不是一个布尔：图查询与 PG 查询的差异往往只出现在某几个字段上，
 * 例如节点集合相同但某条边的强度差 1、路径跳数相同但节点序列不同、或一侧多了 {@code pathNodes}。
 * 只记录 {@code mismatch=true} 无法定位；把字段路径（{@code nodes[2].scholarName}、
 * {@code edges[1].strength}）连同比对值组装进来，日志本身就能说明是哪条规则出现了分叉。
 *
 * <p>{@code comparedNodes}/{@code comparedEdges} 记录本次比较覆盖的结果规模：一个字段都没比到
 * （例如两侧都是空图）也会「一致」，若不记录规模，就无法区分「真的对上」与「根本没比较」。
 *
 * @param operation 查询类型：{@code neighborhood} 或 {@code shortestPath}
 * @param requestKey 本次对照请求的稳定描述（学者/深度/筛选/版本），不含凭据与个人信息
 * @param differences 字段级差异；空表示完全一致
 * @param truncatedDifferences 因报告上限而未列出的差异条数（0 表示全部列出）
 * @param comparedNodes 参与比较的节点条数
 * @param comparedEdges 参与比较的边条数
 */
public record ParityDiffReport(
    String operation,
    String requestKey,
    List<FieldDifference> differences,
    int truncatedDifferences,
    int comparedNodes,
    int comparedEdges) {

  public ParityDiffReport {
    operation = operation == null ? "" : operation;
    requestKey = requestKey == null ? "" : requestKey;
    differences = differences == null ? List.of() : List.copyOf(differences);
    truncatedDifferences = Math.max(truncatedDifferences, 0);
  }

  public boolean matched() {
    return differences.isEmpty() && truncatedDifferences == 0;
  }

  /** 供日志与运维指标使用的一行摘要。 */
  public String summary() {
    if (matched()) {
      return "对照一致："
          + operation
          + " ["
          + requestKey
          + "]，节点 "
          + comparedNodes
          + "，边 "
          + comparedEdges;
    }
    List<String> parts = new ArrayList<>();
    for (FieldDifference difference : differences) {
      parts.add(difference.describe());
    }
    if (truncatedDifferences > 0) {
      parts.add("另有 " + truncatedDifferences + " 处差异未列出");
    }
    return "对照不一致："
        + operation
        + " ["
        + requestKey
        + "]，节点 "
        + comparedNodes
        + "，边 "
        + comparedEdges
        + "，差异 "
        + (differences.size() + truncatedDifferences)
        + " 处："
        + String.join("；", parts);
  }

  /**
   * 单个字段的差异。
   *
   * <p>值统一用字符串记录：日志只关心「哪一侧是什么」，不希望因为集合/数值类型不同而被日志框架
   * 截断或序列化成不可读形态。
   */
  public record FieldDifference(String field, String postgresValue, String graphValue) {

    public FieldDifference {
      field = field == null ? "" : field;
      postgresValue = postgresValue == null ? "null" : postgresValue;
      graphValue = graphValue == null ? "null" : graphValue;
    }

    public String describe() {
      return field + " [postgres=" + postgresValue + ", graph=" + graphValue + "]";
    }
  }
}
