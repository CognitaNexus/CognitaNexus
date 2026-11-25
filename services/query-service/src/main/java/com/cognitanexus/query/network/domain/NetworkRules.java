package com.cognitanexus.query.network.domain;

/**
 * 合作网络的业务规则常量与规则版本。
 *
 * <p>为什么要有这个类：同一套规则现在有两个查询实现（PostgreSQL 与图投影）。如果「最大深度 3」
 * 「路径深度上限 6」「规则版本 NETWORK_V1」在两个实现里各写一份，任何一处调整都会让两条路径
 * 给出不同结果，而响应里的 {@code ruleVersion} 仍然相同——调用方无法察觉自己拿到的是哪套规则。
 * 因此常量只在这里定义，两处实现都引用它。
 *
 * <p>规则本身（建边口径、路径选择）不因存储变化：仅真实、公开、生效、关系合格的共同成果建边；
 * 跳数最少；等长取规范 ID 序列字典序最小的一条；只返回一条。
 *
 * <p><b>响应文案也在这里</b>：PG 实现与图实现共用同一组 {@code note}/{@code pathRule}/{@code scope}
 * 文本（{@link #PATH_RULE}、{@link #NOTE_SEED_OUT_OF_SCOPE} 等）。文案看似只是「说明」，实际是
 * 契约的一部分：同一请求在两条路径上必须给出同样的说明，否则调用方无法判断差异来自存储还是规则；
 * 而且「空图」与「不连通」的区分依赖这些句子，各写一份迟早有一处漏改。
 */
public final class NetworkRules {

  /** 图规则版本。建边口径或路径选择规则变化时提升，两个实现必须同时提升。 */
  public static final String RULE_VERSION = "NETWORK_V1";

  /** 自我网络最大深度。 */
  public static final int MAX_NEIGHBORHOOD_DEPTH = 3;

  /**
   * 最短路径搜索深度上限。
   *
   * <p>合作网络的经验直径在 6 跳以内；给上限是为了让「查不到」有可解释的边界，而不是在超大
   * 网络上无界展开。超出时如实说明「在 N 跳内不连通」，不冒充「全图不连通」。
   */
  public static final int MAX_PATH_DEPTH = 6;

  /** 边规则说明，随响应返回；两处实现共用同一句，避免口径描述漂移。 */
  public static final String EDGE_RULE =
      "仅真实、公开、生效、关系合格（AUTO / SELF_CONFIRMED）的共同论文建边；" + "待核验/争议/已排除关系不参与确定合作；边强度为不同合格论文数";

  /** 路径选择规则。 */
  public static final String PATH_RULE = "跳数最少；等长时取规范 ID 序列字典序最小的一条；只返回一条，不枚举全部等长路径";

  /** 起点与终点相同时的路径选择规则（0 跳也要先确认在范围内）。 */
  public static final String PATH_RULE_ZERO_HOP = "起点与终点相同时，只有在当前范围内存在合格合作边才返回 0 跳路径。";

  /** 种子在当前范围内没有合格合作边时的说明。 */
  public static final String NOTE_SEED_OUT_OF_SCOPE =
      "该学者在当前范围内没有合格合作边，可能原因：成果未公开或未生效、关系非 AUTO/SELF_CONFIRMED、" + "或当前筛选条件将其排除。";

  /** 起点与终点相同且确实在范围内时的说明。 */
  public static final String NOTE_ZERO_HOP_REACHABLE = "起点与终点相同，按 0 跳返回。";

  /** 起点与终点相同但不在范围内时的说明。 */
  public static final String NOTE_ZERO_HOP_OUT_OF_SCOPE =
      "起点在当前范围内没有合格公开合作边（可能成果未公开或未生效、关系非 AUTO/SELF_CONFIRMED、" + "或当前筛选条件将其排除），不按 0 跳可达处理。";

  private NetworkRules() {}

  /**
   * 超过搜索深度时的说明。
   *
   * <p>必须点明「N 跳内未找到」与「全图不连通」的差别：把后者当前者会给出错误的拓扑结论。
   */
  public static String noteUnreachable(int maxPathDepth) {
    return "在 "
        + maxPathDepth
        + " 跳范围内未找到合作路径；这不等于全图不连通，只说明最短距离超过 "
        + maxPathDepth
        + " 跳。起点或终点若在范围内没有合格合作边，也会得到这个结果。";
  }

  /** 自我网络的 {@code scope} 说明：强调是种子邻域，不是筛选范围的全图。 */
  public static String neighborhoodScope(int depth) {
    return "按种子 " + depth + " 跳内实际载入的子图，不是筛选范围的全图";
  }

  /** 最短路径的 {@code scope} 说明。 */
  public static String pathScope(int distance) {
    return "双侧按需扩边至第 " + distance + " 跳，不是筛选范围的全图";
  }
}
