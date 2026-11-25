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

  private NetworkRules() {}
}
